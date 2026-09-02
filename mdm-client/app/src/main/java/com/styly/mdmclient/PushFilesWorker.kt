package com.styly.mdmclient

import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Blocking Push/Sync download, validation, extraction, and apply worker. */
class PushFilesWorker internal constructor(
    private val hasExternalStorageAccess: () -> Boolean,
    private val attemptDirectoryProvider: (PushProtocol.Command) -> File,
    private val destinationProvider: ((String) -> File)? = null,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    private val retryDelay: (Int) -> Unit = {},
) {
    constructor() : this(
        hasExternalStorageAccess = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        },
        attemptDirectoryProvider = ::defaultAttemptDirectory,
        retryDelay = { retryIndex -> Thread.sleep(retryDelayMillis(retryIndex)) },
    )

    companion object {
        internal const val EXTERNAL_STORAGE_PERMISSION_FAILURE =
            "external_storage_permission_denied"
        internal const val EXTERNAL_STORAGE_PERMISSION_DETAIL =
            "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_DOWNLOAD_ATTEMPTS = 6
        private const val INITIAL_RETRY_BACKOFF_MS = 1_000L
        private const val MAX_RETRY_BACKOFF_MS = 8_000L
        internal const val PARTIAL_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
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

        internal fun retryDelayMillis(retryIndex: Int): Long {
            require(retryIndex >= 0)
            var delay = INITIAL_RETRY_BACKOFF_MS
            repeat(retryIndex) {
                delay = (delay * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            }
            return delay
        }
    }

    data class Callbacks(
        val onTransferComplete: (Long) -> Unit,
        val onValidated: () -> Unit,
        val onApplying: () -> Unit,
        val onTransferProgress: (Long) -> Unit = {},
    )

    data class Execution(
        val result: PushProtocol.Result,
        val workDirectory: File,
    )

    fun execute(command: PushProtocol.Command, callbacks: Callbacks): Execution {
        if (!hasExternalStorageAccess()) {
            val work = attemptDirectoryProvider(command)
            val execution = Execution(
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
            return execution
        }
        val work = attemptDirectoryProvider(command)
        val result = try {
            if (command.isJobV1) prepareResumableDirectory(command, work) else recreateDirectory(work)
            val bundle = download(command, work, callbacks.onTransferProgress)
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
        val windowsAbsolute = normalized.length >= 3 && normalized[1] == ':' && normalized[2] == '/'
        if (normalized.isBlank() || (!normalized.startsWith('/') && !windowsAbsolute)) {
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

    private data class ResumeMetadata(
        val jobId: String,
        val attempt: Int,
        val revision: Long,
        val artifactId: String,
        val artifactUrl: String,
        val artifactSize: Long,
        val artifactSha256: String,
        val artifactEtag: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val retentionDeadline: Long,
    ) {
        fun toJson() = org.json.JSONObject().apply {
            put("job_id", jobId)
            put("attempt", attempt)
            put("revision", revision)
            put("artifact_id", artifactId)
            put("artifact_url", artifactUrl)
            put("artifact_size", artifactSize)
            put("artifact_sha256", artifactSha256)
            if (artifactEtag != null) put("artifact_etag", artifactEtag)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
            put("retention_deadline", retentionDeadline)
        }
    }

    private fun prepareResumableDirectory(command: PushProtocol.Command, work: File) {
        if (command.jobId == null || command.artifactId == null ||
            command.artifactSize == null || command.artifactSha256 == null
        ) throw PushWorkerException("artifact_identity_mismatch", "job-v1 identity is incomplete")
        if (!work.exists() && !work.mkdirs()) {
            throw PushWorkerException("download_failed", "could not create resumable work directory")
        }
        val metadata = readMetadata(File(work, "metadata.json"))
        if (metadata == null || !metadata.matches(command) || metadata.retentionDeadline < System.currentTimeMillis()) {
            work.listFiles()?.forEach { if (!it.deleteRecursively()) throw PushWorkerException("download_failed", "could not reset stale resumable work") }
            writeMetadata(newMetadata(command, null), work)
        } else if (metadata.artifactUrl != command.artifactUrl) {
            // The server authority may change across restart or rediscovery. The
            // exact artifact identity authorizes reuse; the fresh URL is only the
            // locator for the next HTTP request.
            writeMetadata(
                metadata.copy(
                    artifactUrl = command.artifactUrl,
                    updatedAt = System.currentTimeMillis(),
                ),
                work,
            )
        }
    }

    private fun newMetadata(command: PushProtocol.Command, etag: String?): ResumeMetadata {
        val now = System.currentTimeMillis()
        return ResumeMetadata(
            requireNotNull(command.jobId), command.attempt, command.revision,
            requireNotNull(command.artifactId), command.artifactUrl,
            requireNotNull(command.artifactSize), requireNotNull(command.artifactSha256).lowercase(),
            etag, now, now, now + PARTIAL_RETENTION_MS,
        )
    }

    private fun ResumeMetadata.matches(command: PushProtocol.Command): Boolean =
        jobId == command.jobId && attempt == command.attempt && revision == command.revision &&
            artifactId == command.artifactId &&
            artifactSize == command.artifactSize && artifactSha256.equals(command.artifactSha256, true) &&
            (command.artifactEtag == null || artifactEtag == command.artifactEtag)

    private fun readMetadata(file: File): ResumeMetadata? {
        if (!file.isFile) return null
        return try {
            val json = org.json.JSONObject(file.readText(Charsets.UTF_8))
            val etag = json.optString("artifact_etag", "").ifBlank { null }
            if (etag != null && (etag.startsWith("W/") || !etag.startsWith("\"") || !etag.endsWith("\""))) return null
            ResumeMetadata(
                json.getString("job_id"), json.getInt("attempt"), json.getLong("revision"),
                json.getString("artifact_id"), json.getString("artifact_url"), json.getLong("artifact_size"),
                json.getString("artifact_sha256"), etag, json.getLong("created_at"),
                json.getLong("updated_at"), json.getLong("retention_deadline"),
            )
        } catch (_: Exception) { null }
    }

    private fun writeMetadata(metadata: ResumeMetadata, work: File) {
        val target = File(work, "metadata.json")
        val temporary = File(work, "metadata.json.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(metadata.toJson().toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        atomicMove(temporary, target)
    }

    private fun download(command: PushProtocol.Command, work: File, onProgress: (Long) -> Unit): File {
        if (!command.isJobV1) return downloadLegacy(command, work, onProgress)
        val expectedSize = requireNotNull(command.artifactSize)
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        if (completed.isFile) {
            if (matchesArtifactIdentity(command, completed)) return completed
            completed.delete()
        }
        var lastError: PushWorkerException? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                val metadata = readMetadata(File(work, "metadata.json"))
                    ?: throw PushWorkerException("artifact_identity_mismatch", "resumable metadata is missing")
                val offset = partial.takeIf { it.isFile }?.length() ?: 0L
                if (offset > expectedSize) throw PushWorkerException("artifact_identity_mismatch", "partial exceeds expected size")
                val nextMetadata = downloadOnce(command, work, metadata, offset, onProgress)
                if (nextMetadata != null) writeMetadata(nextMetadata, work)
                if (completed.isFile) return completed
                if (partial.length() == expectedSize) {
                    verifyAndFinalize(command, partial, completed)
                    return completed
                }
                throw PushWorkerException("download_failed", "artifact response ended before declared size", retryable = true)
            } catch (error: PushWorkerException) {
                if (!error.retryable || attempt == MAX_DOWNLOAD_ATTEMPTS - 1) {
                    // An unavailable immutable artifact may come back later; keep a
                    // validated partial so the next server dispatch can resume it.
                    if (!error.retryable && error.code != "artifact_unavailable") {
                        partial.deleteRecursively()
                    }
                    throw error
                }
                lastError = error
                retryDelay(attempt)
            } catch (error: IOException) {
                lastError = PushWorkerException("download_failed", error.message ?: "I/O error", error, true)
                if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) retryDelay(attempt)
            }
        }
        throw lastError ?: PushWorkerException("download_failed", "artifact download failed")
    }

    private fun downloadLegacy(command: PushProtocol.Command, work: File, onProgress: (Long) -> Unit): File {
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        val connection = openConnection(command)
        try {
            if (connection.responseCode !in 200..299) throw PushWorkerException("download_failed", "artifact download returned HTTP ${connection.responseCode}")
            writeResponse(connection, partial, append = false, expectedLength = command.artifactSize, onProgress = onProgress)
            verifyLegacyAndFinalize(command, partial, completed)
            return completed
        } catch (error: IOException) {
            throw PushWorkerException("download_failed", error.message ?: "I/O error", error, true)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        command: PushProtocol.Command,
        offset: Long = 0L,
        etag: String? = null,
    ): HttpURLConnection =
        (URI(command.artifactUrl).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Match", requireNotNull(etag))
            } else if (etag != null || command.artifactEtag != null) {
                setRequestProperty("If-Match", etag ?: command.artifactEtag)
            }
        }

    private fun downloadOnce(
        command: PushProtocol.Command,
        work: File,
        metadata: ResumeMetadata,
        offset: Long,
        onProgress: (Long) -> Unit,
    ): ResumeMetadata? {
        val expectedSize = requireNotNull(command.artifactSize)
        val partial = File(work, "artifact.part")
        val connection = openConnection(command, offset, metadata.artifactEtag)
        try {
            val status = connection.responseCode
            val responseEtag = connection.getHeaderField("ETag")
            if (status == HttpURLConnection.HTTP_PRECON_FAILED) {
                throw PushWorkerException("artifact_identity_mismatch", "artifact precondition failed (HTTP 412)")
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
                throw PushWorkerException("artifact_unavailable", "artifact is unavailable (HTTP $status)")
            }
            if (status == 408 || status == 429 || status >= 500) {
                throw PushWorkerException("download_failed", "artifact download returned HTTP $status", retryable = true)
            }

            if (offset == 0L) {
                if (status != HttpURLConnection.HTTP_OK) {
                    throw PushWorkerException("artifact_identity_mismatch", "initial artifact response must be HTTP 200")
                }
                validateResponseEtag(responseEtag, metadata.artifactEtag ?: command.artifactEtag)
                val etag = requireNotNull(responseEtag)
                writeMetadata(metadata.copy(artifactEtag = etag, updatedAt = System.currentTimeMillis()), work)
                writeResponse(connection, partial, append = false, expectedLength = expectedSize, onProgress = onProgress)
                return metadata.copy(artifactEtag = etag, updatedAt = System.currentTimeMillis())
            }

            val storedEtag = metadata.artifactEtag
                ?: throw PushWorkerException("artifact_identity_mismatch", "cannot resume without a stored strong ETag")
            validateResponseEtag(responseEtag, storedEtag)
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = parseContentRange(connection.getHeaderField("Content-Range"))
                        ?: throw PushWorkerException("artifact_identity_mismatch", "missing or malformed Content-Range")
                    if (range.first != offset || range.third != expectedSize || range.second < range.first) {
                        throw PushWorkerException("artifact_identity_mismatch", "Content-Range does not match the requested offset or expected size")
                    }
                    val rangeLength = range.second - range.first + 1L
                    if (connection.contentLengthLong != rangeLength) {
                        throw PushWorkerException("artifact_identity_mismatch", "Content-Length does not match Content-Range")
                    }
                    writeResponse(connection, partial, append = true, expectedLength = rangeLength, onProgress = onProgress)
                }
                HttpURLConnection.HTTP_OK -> {
                    // A server that ignored Range must never be appended to a partial file.
                    partial.outputStream().use { }
                    writeResponse(connection, partial, append = false, expectedLength = expectedSize, onProgress = onProgress)
                }
                416 -> {
                    val total = parseUnsatisfiedContentRange(connection.getHeaderField("Content-Range"))
                        ?: throw PushWorkerException("artifact_identity_mismatch", "missing or malformed 416 Content-Range")
                    if (total != expectedSize || offset != total) {
                        throw PushWorkerException("artifact_identity_mismatch", "416 range does not describe the complete expected artifact")
                    }
                    verifyAndFinalize(command, partial, File(work, "artifact.zip"))
                    return metadata
                }
                else -> throw PushWorkerException("download_failed", "artifact download returned HTTP $status")
            }
            return metadata.copy(updatedAt = System.currentTimeMillis())
        } finally {
            connection.disconnect()
        }
    }

    private data class ContentRange(val first: Long, val second: Long, val third: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").matchEntire(value ?: "") ?: return null
        return try {
            ContentRange(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
        } catch (_: NumberFormatException) { null }
    }

    private fun parseUnsatisfiedContentRange(value: String?): Long? {
        val match = Regex("^bytes \\*/([0-9]+)$").matchEntire(value ?: "") ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun validateResponseEtag(response: String?, expected: String?) {
        if (response.isNullOrBlank() || response.startsWith("W/") ||
            !response.startsWith("\"") || !response.endsWith("\"")
        ) throw PushWorkerException("artifact_identity_mismatch", "response did not provide a strong ETag")
        if (expected != null && response != expected) {
            throw PushWorkerException("artifact_identity_mismatch", "artifact ETag changed while resuming")
        }
    }

    private fun writeResponse(
        connection: HttpURLConnection,
        partial: File,
        append: Boolean,
        expectedLength: Long?,
        onProgress: (Long) -> Unit = {},
    ) {
        val declared = connection.contentLengthLong
        if (expectedLength != null && declared >= 0L && declared != expectedLength) {
            throw PushWorkerException("artifact_identity_mismatch", "HTTP body length does not match the expected range")
        }
        var received = 0L
        val initialLength = if (append) partial.length() else 0L
        connection.inputStream.use { input ->
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received = safeAdd(received, read.toLong())
                    if (expectedLength != null && received > expectedLength) {
                        throw PushWorkerException("artifact_identity_mismatch", "HTTP body exceeded its declared range")
                    }
                    output.write(buffer, 0, read)
                    onProgress(safeAdd(initialLength, received))
                }
                output.flush()
                output.fd.sync()
            }
        }
        if (expectedLength != null && received != expectedLength) {
            throw PushWorkerException("download_failed", "HTTP body ended before its declared range", retryable = true)
        }
    }

    private fun verifyAndFinalize(command: PushProtocol.Command, partial: File, completed: File) {
        if (!partial.isFile || partial.length() != requireNotNull(command.artifactSize)) {
            throw PushWorkerException("artifact_identity_mismatch", "partial length does not match declared size")
        }
        if (!matchesArtifactIdentity(command, partial)) {
            partial.delete()
            File(partial.parentFile, "metadata.json").delete()
            throw PushWorkerException("artifact_identity_mismatch", "artifact SHA-256 did not match its declared identity")
        }
        atomicMove(partial, completed)
    }

    private fun matchesArtifactIdentity(command: PushProtocol.Command, file: File): Boolean {
        if (!file.isFile || file.length() != command.artifactSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual.equals(command.artifactSha256, ignoreCase = true)
    }

    private fun verifyLegacyAndFinalize(command: PushProtocol.Command, partial: File, completed: File) {
        if (command.artifactSize != null && partial.length() != command.artifactSize) {
            throw PushWorkerException("artifact_identity_mismatch", "received size did not match the declared size")
        }
        command.artifactSha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(expected, ignoreCase = true)) {
                partial.delete()
                throw PushWorkerException("artifact_identity_mismatch", "artifact SHA-256 did not match its declared identity")
            }
        }
        atomicMove(partial, completed)
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
            "artifact_unavailable", "validation_failed", "apply_failed" -> prefix
            else -> "apply_failed"
        }
    }
}

class PushWorkerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : Exception(message, cause)
