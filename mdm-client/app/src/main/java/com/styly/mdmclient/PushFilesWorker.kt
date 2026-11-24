package com.styly.mdmclient

import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Blocking Push/Sync download, validation, extraction, and apply worker. */
class PushFilesWorker internal constructor(
    private val hasExternalStorageAccess: () -> Boolean,
    private val attemptDirectoryProvider: (PushProtocol.Command) -> File,
    private val destinationProvider: ((String) -> File)? = null,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val noProgressTimeoutMs: Long = DOWNLOAD_NO_PROGRESS_TIMEOUT_MS,
    private val digestFactory: (File) -> PartialFileDigest = ::PartialFileDigest,
    private val storageOutputFactory: (File, Boolean) -> FileOutputStream = ::FileOutputStream,
) {
    constructor() : this(
        hasExternalStorageAccess = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        },
        attemptDirectoryProvider = ::defaultAttemptDirectory,
        monotonicMillis = SystemClock::elapsedRealtime,
    )

    companion object {
        internal const val EXTERNAL_STORAGE_PERMISSION_FAILURE =
            "external_storage_permission_denied"
        internal const val EXTERNAL_STORAGE_PERMISSION_DETAIL =
            "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
        private const val PUSH_LEASE_REVOKED = "push_lease_revoked"
        private const val CONNECT_TIMEOUT_MS = 15_000
        internal const val DOWNLOAD_NO_PROGRESS_TIMEOUT_MS = 60_000L
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
        private val PROTECTED_TOPLEVEL_DIRS = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones",
        )

        internal fun defaultAttemptDirectory(command: PushProtocol.Command): File {
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return attemptDirectoryUnder(downloads, command)
        }

        internal fun attemptDirectoryUnder(downloads: File, command: PushProtocol.Command): File {
            // Historical identity-less active state still owns this directory for cleanup.
            val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
            return File(downloads, "styly-mdm/.push-tmp/jobs/$key/${command.attempt}")
        }

    }

    data class Callbacks(
        val onTransferComplete: (Long) -> Unit,
        val onValidated: () -> Unit,
        /** A false result prevents destination mutation and returns a terminal failure. */
        val beforeApply: () -> Boolean,
        val onValidationStart: () -> Unit = {},
    )

    sealed class Outcome {
        /** The worker reached a terminal success or failure result. */
        data class Completed(val result: PushProtocol.Result) : Outcome()

        /** The exact job remains active and its validated partial data is retained. */
        data class Interrupted(
            val reason: String,
            val failureCode: String?,
            val detail: String?,
        ) : Outcome()
    }

    data class Execution(
        val outcome: Outcome,
        val workDirectory: File,
    )

    internal data class FailurePolicy(
        val interruptionReason: String? = null,
        val retainPartial: Boolean = false,
        val recovery: PushFailureRecovery = PushFailureRecovery.TerminalFailure,
    )

    fun execute(command: PushProtocol.Command, callbacks: Callbacks): Execution {
        if (!hasExternalStorageAccess()) {
            val work = attemptDirectoryProvider(command)
            return Execution(
                Outcome.Completed(PushProtocol.Result(
                    jobId = command.jobId,
                    attempt = command.attempt,
                    status = "fail",
                    destPath = command.destPath,
                    failureCode = EXTERNAL_STORAGE_PERMISSION_FAILURE,
                    detail = EXTERNAL_STORAGE_PERMISSION_DETAIL,
                )),
                work,
            )
        }
        val work = attemptDirectoryProvider(command)
        var interruptionReason: String? = null
        val result = try {
            prepareResumableDirectory(command, work)
            val bundle = download(command, work, callbacks.onValidationStart)
            callbacks.onTransferComplete(bundle.length())
            val staging = File(work, "staging")
            validateAndExtract(bundle, staging)
            callbacks.onValidated()
            val destination = destinationProvider?.invoke(command.destPath)
                ?: validateDestination(command.destPath)
            if (!callbacks.beforeApply()) {
                throw PushWorkerException(
                    "client_persistence_unavailable",
                    "Could not persist the applying phase before changing the destination",
                )
            }
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
            val policy = failurePolicy(command, error)
            interruptionReason = policy.interruptionReason
            PushProtocol.Result(
                jobId = command.jobId,
                attempt = command.attempt,
                status = "fail",
                destPath = command.destPath,
                added = 0,
                updated = 0,
                deleted = 0,
                failureCode = failure?.code ?: classifyFailure(error),
                detail = downloadFailureDetail(error),
            )
        }
        // The coordinator persists this execution outcome before terminal cleanup;
        // interrupted downloads instead retain their durable ownership and bytes.
        val outcome = interruptionReason?.let {
            Outcome.Interrupted(it, result.failureCode, result.detail)
        } ?: Outcome.Completed(result)
        return Execution(outcome, work)
    }

    fun cleanup(workDirectory: File) {
        workDirectory.deleteRecursively()
        val jobDirectory = workDirectory.parentFile
        if (jobDirectory?.listFiles()?.isEmpty() == true) jobDirectory.delete()
    }

    /**
     * Returns a resumable byte offset only when the on-disk artifact is still bound
     * to this exact authorization. This is intentionally metadata-only: the final
     * SHA-256 remains a worker-thread validation before extraction or apply.
     */
    internal fun validatedResumeOffset(command: PushProtocol.Command): Long {
        val expected = resumeMetadataOf(command) ?: return 0L
        val work = attemptDirectoryProvider(command)
        if (readMetadata(File(work, "metadata.json")) != expected) return 0L
        val completed = File(work, "artifact.zip")
        if (completed.isFile) {
            return if (completed.length() == expected.artifactSize) expected.artifactSize else 0L
        }
        val partial = File(work, "artifact.part")
        if (!partial.isFile) return 0L
        val offset = partial.length()
        return if (offset > expected.artifactSize) 0L else offset
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

    /**
     * The exact artifact assignment that owns the bytes in a resumable work directory.
     * The artifact URL is a replaceable locator and is always taken from the command.
     */
    private data class ResumeMetadata(
        val jobId: String,
        val attempt: Int,
        val revision: Long,
        val artifactId: String,
        val artifactSize: Long,
        val artifactSha256: String,
        val artifactEtag: String,
    ) {
        fun toJson() = org.json.JSONObject().apply {
            put("job_id", jobId)
            put("attempt", attempt)
            put("revision", revision)
            put("artifact_id", artifactId)
            put("artifact_size", artifactSize)
            put("artifact_sha256", artifactSha256)
            put("artifact_etag", artifactEtag)
        }
    }

    /** Returns null for a command that cannot own resumable work. */
    private fun resumeMetadataOf(command: PushProtocol.Command): ResumeMetadata? {
        if (command.revision <= 0L) return null
        return ResumeMetadata(
            command.jobId ?: return null,
            command.attempt,
            command.revision,
            command.artifactId ?: return null,
            command.artifactSize ?: return null,
            command.artifactSha256?.lowercase() ?: return null,
            command.artifactEtag ?: return null,
        )
    }

    private fun prepareResumableDirectory(command: PushProtocol.Command, work: File) {
        val expected = resumeMetadataOf(command)
            ?: throw PushWorkerException("artifact_identity_mismatch", "job-v1 identity is incomplete")
        if (!work.exists() && !work.mkdirs()) {
            throw PushWorkerException("download_failed", "could not create resumable work directory")
        }
        if (readMetadata(File(work, "metadata.json")) != expected) {
            work.listFiles()?.forEach { if (!it.deleteRecursively()) throw PushWorkerException("download_failed", "could not reset stale resumable work") }
            writeMetadata(expected, work)
        }
    }

    private fun readMetadata(file: File): ResumeMetadata? {
        if (!file.isFile) return null
        return try {
            val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
            val etag = json.getString("artifact_etag")
            if (!PushProtocol.isStrongEtag(etag)) return null
            ResumeMetadata(
                json.getString("job_id"), json.getInt("attempt"), json.getLong("revision"),
                json.getString("artifact_id"), json.getLong("artifact_size"),
                json.getString("artifact_sha256").lowercase(), etag,
            )
        } catch (_: Exception) { null }
    }

    private fun writeMetadata(metadata: ResumeMetadata, work: File) {
        val target = File(work, "metadata.json")
        val temporary = File(work, "metadata.json.tmp")
        val output = openStorageOutput(temporary, append = false)
        try {
            storageWrite { output.write(metadata.toJson().toString().toByteArray(Charsets.UTF_8)) }
            storageWrite { output.flush() }
            storageWrite { output.fd.sync() }
        } finally {
            storageWrite { output.close() }
        }
        storageWrite { atomicMoveArtifact(temporary, target) }
    }

    private fun openStorageOutput(file: File, append: Boolean): FileOutputStream =
        try {
            storageOutputFactory(file, append)
        } catch (error: IOException) {
            throw storageWriteFailure(error)
        }

    private inline fun <T> storageWrite(operation: () -> T): T =
        try {
            operation()
        } catch (error: IOException) {
            throw storageWriteFailure(error)
        }

    private fun storageWriteFailure(error: IOException) = PushWorkerException(
        "storage_write_failed",
        error.message ?: "Could not write Push/Sync download data",
        error,
    )

    private fun download(
        command: PushProtocol.Command,
        work: File,
        onValidationStart: () -> Unit,
    ): File {
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        return downloadArtifact(ArtifactDownloadRequest(
            url = command.artifactUrl,
            partialFile = partial,
            completedFile = completed,
            expectedSize = requireNotNull(command.artifactSize),
            expectedSha256 = requireNotNull(command.artifactSha256),
            expectedEtag = requireNotNull(command.artifactEtag),
            client = resumableHttpClient,
            onValidationStart = onValidationStart,
            noProgressTimeoutMs = noProgressTimeoutMs,
            monotonicMillis = monotonicMillis,
            retryDelay = retryDelay,
            digestFactory = digestFactory,
            storageOutputFactory = ::openStorageOutput,
            failurePolicy = { error ->
                val policy = failurePolicy(command, error)
                ArtifactDownloadFailurePolicy(
                    retryWithinDeadline = policy.recovery == PushFailureRecovery.RetryWithinDeadline,
                    retainPartial = policy.retainPartial,
                )
            },
            onIntegrityFailure = { File(work, "metadata.json").delete() },
        ))
    }

    internal fun failurePolicy(
        command: PushProtocol.Command,
        error: Throwable?,
    ): FailurePolicy {
        if (error == null) return FailurePolicy()
        val failure = error as? PushWorkerException
        if (failure == null) return FailurePolicy(
            retainPartial = error is IOException,
            recovery = if (error is IOException) PushFailureRecovery.RetryWithinDeadline
                else PushFailureRecovery.TerminalFailure,
        )
        return when (failure.code) {
            "download_failed" -> when (failure.recovery) {
                PushFailureRecovery.RetryWithinDeadline,
                PushFailureRecovery.AwaitOperatorResume -> FailurePolicy(
                    interruptionReason = "download_interrupted",
                    retainPartial = true,
                    recovery = failure.recovery,
                )
                PushFailureRecovery.TerminalFailure -> FailurePolicy()
            }
            PUSH_LEASE_REVOKED -> FailurePolicy(
                interruptionReason = "server_lease_revoked",
                retainPartial = true,
                recovery = PushFailureRecovery.AwaitOperatorResume,
            )
            "storage_write_failed" -> if (validatedResumeOffset(command) > 0L) {
                FailurePolicy(
                    interruptionReason = "storage_write_failed",
                    retainPartial = true,
                    recovery = PushFailureRecovery.AwaitOperatorResume,
                )
            } else {
                FailurePolicy()
            }
            else -> FailurePolicy(
                retainPartial = failure.code == "artifact_unavailable",
                recovery = failure.recovery,
            )
        }
    }

    private val resumableHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_NO_PROGRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

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
                                    if (extractedBytes > maxExtractedBytes) {
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
            "artifact_unavailable", "storage_write_failed", "validation_failed", "apply_failed",
            "client_persistence_unavailable" -> prefix
            else -> "apply_failed"
        }
    }
}
