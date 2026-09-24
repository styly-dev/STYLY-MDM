package com.styly.mdmclient

import android.os.Build
import android.os.Environment
import android.os.SystemClock
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One monotonic deadline across connections and retries, refreshed only by received bytes. */
internal class PushDownloadDeadline(
    private val clock: () -> Long,
    private val timeoutMs: Long,
) : AutoCloseable {
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "push-download-deadline").apply { isDaemon = true }
        }
    }
    private var lastProgress = clock()
    private var expired = false
    private var closed = false
    private var call: Call? = null
    private var wakeup: ScheduledFuture<*>? = null

    init {
        require(timeoutMs > 0L)
        schedule(timeoutMs)
    }

    @Synchronized fun remaining(): Long = remainingAt(clock())

    private fun remainingAt(now: Long): Long {
        val elapsed = now - lastProgress
        if (expired || elapsed >= timeoutMs) {
            expired = true
            call?.cancel()
            throw PushWorkerException("download_failed", "No download data received for ${timeoutMs}ms", retryable = true)
        }
        return timeoutMs - elapsed
    }

    @Synchronized fun receivedBytes() {
        val now = clock()
        remainingAt(now) // Bytes arriving at or after expiry cannot revive this execution.
        lastProgress = now
    }

    @Synchronized fun attach(value: Call?) {
        if (value != null) remaining()
        call = value
    }

    private fun schedule(delay: Long) {
        wakeup = timer.schedule({
            synchronized(this) {
                if (!closed) {
                    try { schedule(remaining()) }
                    catch (_: PushWorkerException) { /* remaining cancels the blocked call. */ }
                }
            }
        }, delay.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    @Synchronized override fun close() {
        closed = true
        wakeup?.cancel(false)
        call = null
    }
}

/** Blocking Push/Sync download, validation, extraction, and apply worker. */
class PushFilesWorker internal constructor(
    private val hasExternalStorageAccess: () -> Boolean,
    private val attemptDirectoryProvider: (PushProtocol.Command) -> File,
    private val destinationProvider: ((String) -> File)? = null,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val noProgressTimeoutMs: Long = DOWNLOAD_NO_PROGRESS_TIMEOUT_MS,
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
        private const val READ_TIMEOUT_MS = 120_000
        internal const val DOWNLOAD_NO_PROGRESS_TIMEOUT_MS = 60_000L
        private const val INITIAL_RETRY_BACKOFF_MS = 1_000L
        private const val MAX_RETRY_BACKOFF_MS = 8_000L
        internal const val PARTIAL_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024
        private val PROTECTED_TOPLEVEL_DIRS = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones",
        )

        internal fun defaultAttemptDirectory(command: PushProtocol.Command): File {
            val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return File(downloads, "styly-mdm/.push-tmp/jobs/$key/${command.attempt}")
        }

        internal fun retryDelayMillis(retryIndex: Int): Long {
            require(retryIndex >= 0)
            var delay = INITIAL_RETRY_BACKOFF_MS
            repeat(retryIndex.coerceAtMost(3)) {
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
        val onValidationStart: () -> Unit = {},
    )

    data class Execution(
        val result: PushProtocol.Result,
        val workDirectory: File,
        /** A retryable job-v1 transfer exhausted its short in-process retry budget. */
        val interrupted: Boolean = false,
        val interruptionReason: String? = null,
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
        var interrupted = false
        var interruptionReason: String? = null
        val result = try {
            if (command.isJobV1) prepareResumableDirectory(command, work) else recreateDirectory(work)
            val bundle = download(command, work, callbacks.onTransferProgress, callbacks.onValidationStart)
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
            interrupted = command.isJobV1 && command.revision > 0L && (
                (failure?.code == "download_failed" && failure.retryable) ||
                    failure?.code == PUSH_LEASE_REVOKED ||
                    (failure?.code == "storage_write_failed" && validatedResumeOffset(command) > 0L)
                )
            if (interrupted) {
                interruptionReason = when (failure?.code) {
                    PUSH_LEASE_REVOKED -> "server_lease_revoked"
                    "storage_write_failed" -> "storage_write_failed"
                    else -> "download_retry_exhausted"
                }
            }
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
        // The coordinator persists this execution outcome before terminal cleanup;
        // retry-budget exhaustion instead becomes an interrupted durable state.
        return Execution(result, work, interrupted, interruptionReason)
    }

    fun cleanup(execution: Execution) {
        execution.workDirectory.deleteRecursively()
        val jobDirectory = execution.workDirectory.parentFile
        if (jobDirectory?.listFiles()?.isEmpty() == true) jobDirectory.delete()
    }

    /**
     * Returns a resumable byte offset only when the on-disk artifact is still bound
     * to this exact authorization. This is intentionally metadata-only: the final
     * SHA-256 remains a worker-thread validation before extraction or apply.
     */
    internal fun validatedResumeOffset(command: PushProtocol.Command): Long {
        if (!command.isJobV1) return 0L
        val work = attemptDirectoryProvider(command)
        val metadata = readMetadata(File(work, "metadata.json")) ?: return 0L
        if (!metadata.matches(command)) return 0L
        val completed = File(work, "artifact.zip")
        if (completed.isFile) {
            return if (completed.length() == metadata.artifactSize) metadata.artifactSize else 0L
        }
        val partial = File(work, "artifact.part")
        if (!partial.isFile) return 0L
        val offset = partial.length()
        if (offset > metadata.artifactSize) return 0L
        if (offset > 0L && metadata.artifactEtag == null) return 0L
        return offset
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
        // PushJobCoordinator owns interruption expiry. This retained field is advisory
        // metadata compatibility only; it must not shorten a coordinator-authorized resume.
        if (metadata == null || !metadata.matches(command)) {
            work.listFiles()?.forEach { if (!it.deleteRecursively()) throw PushWorkerException("download_failed", "could not reset stale resumable work") }
            writeMetadata(newMetadata(command, command.artifactEtag), work)
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
        val output = openStorageOutput(temporary, append = false)
        try {
            storageWrite { output.write(metadata.toJson().toString().toByteArray(Charsets.UTF_8)) }
            storageWrite { output.flush() }
            storageWrite { output.fd.sync() }
        } finally {
            storageWrite { output.close() }
        }
        storageWrite { atomicMove(temporary, target) }
    }

    private fun openStorageOutput(file: File, append: Boolean): FileOutputStream =
        try {
            FileOutputStream(file, append)
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
        onProgress: (Long) -> Unit,
        onValidationStart: () -> Unit,
    ): File {
        if (!command.isJobV1) return downloadLegacy(command, work, onProgress)
        val expectedSize = requireNotNull(command.artifactSize)
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        if (completed.isFile) {
            onValidationStart()
            if (matchesArtifactIdentity(command, completed)) return completed
            completed.delete()
            throw PushWorkerException("artifact_identity_mismatch", "completed artifact SHA-256 did not match")
        }
        PushDownloadDeadline(monotonicMillis, noProgressTimeoutMs).use { deadline ->
            var retryIndex = 0
            while (true) {
                deadline.remaining()
                try {
                    val metadata = readMetadata(File(work, "metadata.json"))
                        ?: throw PushWorkerException("artifact_identity_mismatch", "resumable metadata is missing")
                    val offset = partial.takeIf { it.isFile }?.length() ?: 0L
                    if (offset > expectedSize) throw PushWorkerException("artifact_identity_mismatch", "partial exceeds expected size")
                    downloadOnce(command, work, metadata, offset, onProgress, deadline)
                    if (partial.length() == expectedSize) break
                    throw PushWorkerException("download_failed", "artifact response ended before declared size", retryable = true)
                } catch (error: PushWorkerException) {
                    if (!error.retryable) {
                        if (error.code != "artifact_unavailable" && error.code != PUSH_LEASE_REVOKED &&
                            (error.code != "storage_write_failed" || validatedResumeOffset(command) == 0L)
                        ) {
                            partial.deleteRecursively()
                        }
                        throw error
                    }
                } catch (_: IOException) {
                    // Keep the exact partial and retry only within the remaining idle window.
                }
                val delay = retryDelayMillis(retryIndex).coerceAtMost(deadline.remaining())
                retryIndex = (retryIndex + 1).coerceAtMost(3)
                retryDelay(delay)
            }
        }
        // Validation is outside both the download deadline and the network retry loop.
        onValidationStart()
        verifyAndFinalize(command, partial, completed)
        return completed
    }

    private fun downloadLegacy(command: PushProtocol.Command, work: File, onProgress: (Long) -> Unit): File {
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        val connection = openConnection(command)
        try {
            if (connection.responseCode !in 200..299) throw PushWorkerException("download_failed", "artifact download returned HTTP ${connection.responseCode}")
            writeResponse(connection, partial, command.artifactSize, onProgress)
            verifyLegacyAndFinalize(command, partial, completed)
            return completed
        } catch (error: IOException) {
            throw PushWorkerException("download_failed", error.message ?: "I/O error", error, true)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(command: PushProtocol.Command): HttpURLConnection =
        (URI(command.artifactUrl).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }

    private val resumableHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_NO_PROGRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun openResumableResponse(
        command: PushProtocol.Command,
        offset: Long,
        etag: String?,
        deadline: PushDownloadDeadline,
    ): Response {
        val request = Request.Builder().url(command.artifactUrl).header("Accept-Encoding", "identity")
        if (offset > 0L) {
            request.header("Range", "bytes=$offset-")
            request.header("If-Match", requireNotNull(etag))
        } else {
            (etag ?: command.artifactEtag)?.let { request.header("If-Match", it) }
        }
        val call = resumableHttpClient.newCall(request.build())
        deadline.attach(call)
        return try { call.execute() }
        catch (error: IOException) {
            deadline.attach(null)
            throw error
        }
    }

    private fun writeResumableResponse(
        response: Response,
        deadline: PushDownloadDeadline,
        partial: File,
        append: Boolean,
        expectedLength: Long,
        onProgress: (Long) -> Unit,
    ) {
        val body = requireNotNull(response.body)
        val declared = body.contentLength()
        if (declared >= 0L && declared != expectedLength) {
            throw PushWorkerException("artifact_identity_mismatch", "HTTP body length does not match the expected range")
        }
        var received = 0L
        val initialLength = if (append) partial.length() else 0L
        body.byteStream().use { input ->
            val output = openStorageOutput(partial, append)
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    deadline.remaining()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    received = safeAdd(received, read.toLong())
                    if (received > expectedLength) throw PushWorkerException("artifact_identity_mismatch", "HTTP body exceeded its declared range")
                    storageWrite { output.write(buffer, 0, read) }
                    deadline.receivedBytes()
                    onProgress(safeAdd(initialLength, received))
                }
            } finally {
                try {
                    // Persist the exact bytes already written even when the network
                    // read or idle deadline failed, before a retry can observe EOF/416.
                    storageWrite {
                        output.flush()
                        output.fd.sync()
                    }
                } finally {
                    storageWrite { output.close() }
                }
            }
        }
        if (received != expectedLength) {
            throw PushWorkerException("download_failed", "HTTP body ended before its declared range", retryable = true)
        }
    }

    private fun downloadOnce(
        command: PushProtocol.Command,
        work: File,
        metadata: ResumeMetadata,
        offset: Long,
        onProgress: (Long) -> Unit,
        deadline: PushDownloadDeadline,
    ) {
        val expectedSize = requireNotNull(command.artifactSize)
        val partial = File(work, "artifact.part")
        val connection = openResumableResponse(command, offset, metadata.artifactEtag, deadline)
        try {
            deadline.remaining()
            val status = connection.code
            val responseEtag = connection.header("ETag")
            if (status == 409 && connection.header("X-Push-Lease-Status")
                    ?.equals("revoked", ignoreCase = true) == true
            ) {
                throw PushWorkerException(
                    PUSH_LEASE_REVOKED,
                    "server revoked the Push/Sync transfer lease",
                )
            }
            if (status == HttpURLConnection.HTTP_PRECON_FAILED) {
                throw PushWorkerException("artifact_identity_mismatch", "artifact precondition failed (HTTP 412)")
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
                throw PushWorkerException("artifact_unavailable", "artifact is unavailable (HTTP $status)")
            }
            if (status == 408 || status == 429 || status >= 500) {
                throw PushWorkerException("download_failed", "artifact download returned HTTP $status", retryable = true)
            }
            val contentEncoding = connection.header("Content-Encoding")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
                throw PushWorkerException(
                    "artifact_identity_mismatch",
                    "artifact response used non-identity Content-Encoding",
                )
            }

            if (offset == 0L) {
                if (status != HttpURLConnection.HTTP_OK) {
                    throw PushWorkerException("artifact_identity_mismatch", "initial artifact response must be HTTP 200")
                }
                validateResponseEtag(responseEtag, metadata.artifactEtag ?: command.artifactEtag)
                val etag = requireNotNull(responseEtag)
                writeMetadata(metadata.copy(artifactEtag = etag, updatedAt = System.currentTimeMillis()), work)
                writeResumableResponse(connection, deadline, partial, append = false, expectedLength = expectedSize, onProgress = onProgress)
                return
            }

            val storedEtag = metadata.artifactEtag
                ?: throw PushWorkerException("artifact_identity_mismatch", "cannot resume without a stored strong ETag")
            validateResponseEtag(responseEtag, storedEtag)
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = parseContentRange(connection.header("Content-Range"))
                        ?: throw PushWorkerException("artifact_identity_mismatch", "missing or malformed Content-Range")
                    if (range.first != offset || range.third != expectedSize ||
                        range.second < range.first || range.second >= range.third
                    ) {
                        throw PushWorkerException("artifact_identity_mismatch", "Content-Range does not match the requested offset or expected size")
                    }
                    val rangeLength = range.second - range.first + 1L
                    if ((connection.body?.contentLength() ?: -1L) != rangeLength) {
                        throw PushWorkerException("artifact_identity_mismatch", "Content-Length does not match Content-Range")
                    }
                    writeResumableResponse(connection, deadline, partial, append = true, expectedLength = rangeLength, onProgress = onProgress)
                }
                HttpURLConnection.HTTP_OK -> {
                    // A server that ignored Range must never be appended to a partial file.
                    writeResumableResponse(connection, deadline, partial, append = false, expectedLength = expectedSize, onProgress = onProgress)
                }
                416 -> {
                    val total = parseUnsatisfiedContentRange(connection.header("Content-Range"))
                        ?: throw PushWorkerException("artifact_identity_mismatch", "missing or malformed 416 Content-Range")
                    if (total != expectedSize || offset != total) {
                        throw PushWorkerException("artifact_identity_mismatch", "416 range does not describe the complete expected artifact")
                    }
                    return
                }
                else -> throw PushWorkerException("download_failed", "artifact download returned HTTP $status")
            }
        } finally {
            deadline.attach(null)
            connection.close()
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
        expectedLength: Long?,
        onProgress: (Long) -> Unit,
    ) {
        val declared = connection.contentLengthLong
        if (expectedLength != null && declared >= 0L && declared != expectedLength) {
            throw PushWorkerException("artifact_identity_mismatch", "HTTP body length does not match the expected range")
        }
        var received = 0L
        connection.inputStream.use { input ->
            val output = openStorageOutput(partial, append = false)
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received = safeAdd(received, read.toLong())
                    if (expectedLength != null && received > expectedLength) {
                        throw PushWorkerException("artifact_identity_mismatch", "HTTP body exceeded its declared range")
                    }
                    storageWrite { output.write(buffer, 0, read) }
                    onProgress(received)
                }
            } finally {
                try {
                    storageWrite {
                        output.flush()
                        output.fd.sync()
                    }
                } finally {
                    storageWrite { output.close() }
                }
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
            "artifact_unavailable", "storage_write_failed", "validation_failed", "apply_failed" -> prefix
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
