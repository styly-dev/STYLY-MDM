package com.styly.mdmclient

import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

/** Blocking Push/Sync download, validation, extraction, and apply worker. */
class PushFilesWorker internal constructor(
    private val hasExternalStorageAccess: () -> Boolean,
    private val attemptDirectoryProvider: (PushProtocol.Command) -> File,
    private val destinationProvider: ((String) -> File)? = null,
) {
    constructor() : this(
        hasExternalStorageAccess = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        },
        attemptDirectoryProvider = ::defaultAttemptDirectory,
    )

    companion object {
        internal const val EXTERNAL_STORAGE_PERMISSION_FAILURE =
            "external_storage_permission_denied"
        internal const val EXTERNAL_STORAGE_PERMISSION_DETAIL =
            "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private const val MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
        private val PROTECTED_TOPLEVEL_DIRS = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones",
        )

        private fun defaultAttemptDirectory(command: PushProtocol.Command): File {
            val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return File(downloads, "styly-mdm/.push-tmp/jobs/$key/${command.attempt}")
        }
    }

    data class Callbacks(
        val onTransferComplete: (Long) -> Unit,
        val onValidated: () -> Unit,
        val onApplying: () -> Unit,
    )

    data class Execution(
        val result: PushProtocol.Result,
        val workDirectory: File,
    )

    fun execute(command: PushProtocol.Command, callbacks: Callbacks): Execution {
        if (!hasExternalStorageAccess()) {
            val work = attemptDirectoryProvider(command)
            return Execution(
                PushProtocol.Result(
                    jobId = command.jobId,
                    attempt = command.attempt,
                    status = "fail",
                    destPath = command.destPath,
                    failureCode = EXTERNAL_STORAGE_PERMISSION_FAILURE,
                    detail = EXTERNAL_STORAGE_PERMISSION_DETAIL,
                ),
                work,
            )
        }
        val work = attemptDirectoryProvider(command)
        val result = try {
            recreateDirectory(work)
            val bundle = download(command, work)
            callbacks.onTransferComplete(bundle.length())
            val staging = File(work, "staging")
            validateAndExtract(bundle, staging)
            callbacks.onValidated()
            val destination = destinationProvider?.invoke(command.destPath)
                ?: validateDestination(command.destPath)
            callbacks.onApplying()
            val applied = BundleSync.apply(staging, destination, command.deleteExtras)
            PushProtocol.Result(
                jobId = command.jobId,
                attempt = command.attempt,
                status = "success",
                destPath = command.destPath,
                added = applied.added,
                updated = applied.updated,
                deleted = applied.deleted,
                failureCode = null,
                detail = null,
            )
        } catch (error: Throwable) {
            val failure = error as? PushWorkerException
            PushProtocol.Result(
                jobId = command.jobId,
                attempt = command.attempt,
                status = "fail",
                destPath = command.destPath,
                added = 0,
                updated = 0,
                deleted = 0,
                failureCode = failure?.code ?: classifyFailure(error),
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
        // The coordinator persists this terminal result before invoking cleanup().
        return Execution(result, work)
    }

    fun cleanup(execution: Execution) {
        execution.workDirectory.deleteRecursively()
        val jobDirectory = execution.workDirectory.parentFile
        if (jobDirectory?.listFiles()?.isEmpty() == true) jobDirectory.delete()
    }

    internal fun validateDestination(destPath: String): File =
        validateDestinationAgainstRoot(
            destPath,
            Environment.getExternalStorageDirectory(),
        )

    internal fun validateDestinationAgainstRoot(
        destPath: String,
        rootDirectory: File,
    ): File {
        val normalized = destPath.trim().replace('\\', '/')
        if (normalized.isBlank() || !normalized.startsWith('/')) {
            throw PushWorkerException(
                "invalid_destination",
                "destination must be an absolute path",
            )
        }
        val root = try {
            rootDirectory.canonicalFile
        } catch (error: Exception) {
            throw PushWorkerException(
                "invalid_destination",
                "shared storage root is invalid",
                error,
            )
        }
        val rootProtocolPath = root.absolutePath.replace(File.separatorChar, '/')
        val aliases = listOf(
            "/sdcard",
            "/storage/emulated/0",
            rootProtocolPath,
        ).distinct().sortedByDescending { it.length }
        val alias = aliases.firstOrNull {
            normalized == it || normalized.startsWith("$it/")
        } ?: throw PushWorkerException(
            "invalid_destination",
            "destination must be under shared storage",
        )
        val relative = normalized.removePrefix(alias).trim('/')
        if (relative.isBlank()) {
            throw PushWorkerException(
                "invalid_destination",
                "destination must be a subdirectory of shared storage",
            )
        }
        val parts = relative.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw PushWorkerException(
                "invalid_destination",
                "destination contains an unsafe path component",
            )
        }
        val first = parts.first().lowercase()
        if (first in PROTECTED_TOPLEVEL_DIRS) {
            throw PushWorkerException(
                "invalid_destination",
                "destination must not be inside the protected '$first' directory",
            )
        }

        var lexicalTarget = root
        for (part in parts) {
            lexicalTarget = File(lexicalTarget, part)
            if (Files.isSymbolicLink(lexicalTarget.toPath())) {
                throw PushWorkerException(
                    "invalid_destination",
                    "destination path contains a symbolic link",
                )
            }
        }
        val target = try {
            lexicalTarget.canonicalFile
        } catch (error: Exception) {
            throw PushWorkerException(
                "invalid_destination",
                "destination path is invalid",
                error,
            )
        }
        if (target.path == root.path || !target.path.startsWith(root.path + File.separator)) {
            throw PushWorkerException(
                "invalid_destination",
                "destination must be a subdirectory of shared storage",
            )
        }
        return target
    }

    private fun download(command: PushProtocol.Command, work: File): File {
        val expectedSize = command.artifactSize
        if (expectedSize != null && expectedSize > MAX_UNCOMPRESSED_BYTES) {
            throw PushWorkerException("artifact_identity_mismatch", "artifact exceeds the size limit")
        }
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        val connection = (URI(command.artifactUrl).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw PushWorkerException("download_failed", "artifact download returned HTTP $status")
            }
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received = safeAdd(received, read.toLong())
                        if (received > MAX_UNCOMPRESSED_BYTES) {
                            throw PushWorkerException(
                                "artifact_identity_mismatch",
                                "artifact exceeded the configured size limit",
                            )
                        }
                        if (expectedSize != null && received > expectedSize) {
                            throw PushWorkerException(
                                "artifact_identity_mismatch",
                                "artifact exceeded its declared size",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (expectedSize != null && received != expectedSize) {
                throw PushWorkerException(
                    "artifact_identity_mismatch",
                    "received $received bytes but expected $expectedSize",
                )
            }
            atomicMove(partial, completed)
            return completed
        } catch (error: PushWorkerException) {
            partial.delete()
            throw error
        } catch (error: Exception) {
            partial.delete()
            throw PushWorkerException(
                "download_failed",
                error.message ?: "artifact download failed",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun validateAndExtract(bundle: File, staging: File) {
        recreateDirectory(staging)
        val root = staging.canonicalFile
        val rootPrefix = root.path + File.separator
        val seen = HashSet<String>()
        val kinds = HashMap<String, Boolean>() // true = directory
        var entryCount = 0
        var extractedBytes = 0L
        try {
            ZipFile(bundle).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    if (entryCount > MAX_ARCHIVE_ENTRIES) {
                        throw PushWorkerException("validation_failed", "ZIP contains too many entries")
                    }
                    val relative = validateEntryName(entry.name)
                    if (!seen.add(relative)) {
                        throw PushWorkerException(
                            "validation_failed",
                            "ZIP contains duplicate path $relative",
                        )
                    }
                    val parts = relative.split('/')
                    for (index in 1 until parts.size) {
                        val ancestor = parts.take(index).joinToString("/")
                        if (kinds[ancestor] == false) {
                            throw PushWorkerException(
                                "validation_failed",
                                "ZIP has a file/directory conflict at $ancestor",
                            )
                        }
                        kinds[ancestor] = true
                    }
                    val previous = kinds[relative]
                    if (previous != null && previous != entry.isDirectory) {
                        throw PushWorkerException(
                            "validation_failed",
                            "ZIP has a file/directory conflict at $relative",
                        )
                    }
                    kinds[relative] = entry.isDirectory
                    val target = File(staging, relative).canonicalFile
                    if (target.path != root.path && !target.path.startsWith(rootPrefix)) {
                        throw PushWorkerException("validation_failed", "ZIP entry escaped staging")
                    }
                    if (entry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) {
                            throw PushWorkerException(
                                "validation_failed",
                                "could not create staging directory",
                            )
                        }
                    } else {
                        val parent = target.parentFile
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw PushWorkerException(
                                "validation_failed",
                                "could not create staging directory",
                            )
                        }
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(target, false).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    extractedBytes = safeAdd(extractedBytes, read.toLong())
                                    if (extractedBytes > MAX_UNCOMPRESSED_BYTES) {
                                        throw PushWorkerException(
                                            "validation_failed",
                                            "ZIP expands beyond the allowed size",
                                        )
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: PushWorkerException) {
            throw error
        } catch (error: Exception) {
            throw PushWorkerException("validation_failed", "artifact is not a valid ZIP", error)
        }
        if (entryCount == 0) {
            throw PushWorkerException("validation_failed", "ZIP contains no entries")
        }
    }

    private fun validateEntryName(raw: String): String {
        if (raw.isBlank() || '\u0000' in raw || raw.startsWith('/') || raw.startsWith('\\')) {
            throw PushWorkerException("validation_failed", "ZIP contains an invalid entry path")
        }
        val normalized = raw.replace('\\', '/').trimEnd('/')
        val parts = normalized.split('/')
        if (normalized.isBlank() || parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw PushWorkerException("validation_failed", "ZIP contains an unsafe entry path")
        }
        return normalized
    }

    private fun recreateDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw PushWorkerException("validation_failed", "could not reset work directory")
        }
        if (!directory.mkdirs()) {
            throw PushWorkerException("validation_failed", "could not create work directory")
        }
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            throw PushWorkerException("validation_failed", "artifact size overflow")
        }
        return left + right
    }

    private fun classifyFailure(error: Throwable): String {
        val prefix = error.message?.substringBefore(':')
        return when (prefix) {
            "invalid_destination", "artifact_identity_mismatch", "download_failed",
            "validation_failed", "apply_failed" -> prefix
            else -> "apply_failed"
        }
    }
}

class PushWorkerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
