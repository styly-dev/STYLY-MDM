package com.styly.mdmclient

import android.os.Build
import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
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

/**
 * SHA-256 of received buffers after successful writes to [file]. This does not
 * reread stored bytes to detect same-size changes after writing.
 *
 * The file is never reread while a transfer is in progress, because the server's
 * HTTP lease idle timer keeps running during that time. When the tracked length stops
 * matching the file length (a prefix kept from an earlier execution, or a write that
 * failed after reaching disk), incremental hashing stops and [finishHex] hashes the
 * whole file once during validation instead.
 */
internal class PartialFileDigest(private val file: File) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var length = 0L
    private var tracking = true
    /** Bytes reread from disk at validation; exposed for tests. */
    var rehashedBytes = 0L
        private set

    /** Call right after opening [file] for writing, before the first [update]. */
    fun beginWrite(append: Boolean) {
        if (!append) {
            // Opening without append truncated the file; restart from byte zero.
            restart()
        } else if (!tracking || file.length() != length) {
            tracking = false
        }
    }

    fun update(buffer: ByteArray, offset: Int, count: Int) {
        if (!tracking) return
        digest.update(buffer, offset, count)
        length += count
    }

    /** Finalizes the tracked hash, rereading the file if tracking was lost, and resets. */
    fun finishHex(): String {
        val actual = if (file.isFile) file.length() else 0L
        if (!tracking || actual != length) {
            digest.reset()
            if (actual > 0L) digest.updateWithFile(file)
            rehashedBytes += actual
        }
        return digest.hexDigest().also { restart() }
    }

    private fun restart() {
        digest.reset()
        length = 0L
        tracking = true
    }
}

/** Streams the whole of [file] into this digest. */
private fun MessageDigest.updateWithFile(file: File) {
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }
}

/** Completes this digest (which also resets it) as lowercase hexadecimal. */
private fun MessageDigest.hexDigest(): String =
    digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

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
            val downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return attemptDirectoryUnder(downloads, command)
        }

        internal fun attemptDirectoryUnder(downloads: File, command: PushProtocol.Command): File {
            // Historical identity-less active state still owns this directory for cleanup.
            val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
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

    private data class FailurePolicy(
        val interruptionReason: String? = null,
        val retainPartial: Boolean = false,
        val retryInWorker: Boolean = false,
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
            val policy = failurePolicy(command, failure)
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
                detail = error.message ?: error.javaClass.simpleName,
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
        onValidationStart: () -> Unit,
    ): File {
        val expectedSize = requireNotNull(command.artifactSize)
        val partial = File(work, "artifact.part")
        val completed = File(work, "artifact.zip")
        if (completed.isFile) {
            onValidationStart()
            if (matchesArtifactIdentity(command, completed)) return completed
            completed.delete()
            throw PushWorkerException("artifact_identity_mismatch", "completed artifact SHA-256 did not match")
        }
        // A prefix kept from an earlier execution is hashed during validation, not
        // here: rereading it before the request would consume the HTTP lease window.
        val digest = digestFactory(partial)
        if (partial.isFile && partial.length() == expectedSize) {
            onValidationStart()
            verifyAndFinalize(command, partial, completed, digest)
            return completed
        }
        PushDownloadDeadline(monotonicMillis, noProgressTimeoutMs).use { deadline ->
            var retryIndex = 0
            var receivedSinceRetry = false
            while (true) {
                deadline.remaining()
                try {
                    val offset = partial.takeIf { it.isFile }?.length() ?: 0L
                    if (offset > expectedSize) throw PushWorkerException("artifact_identity_mismatch", "partial exceeds expected size")
                    downloadOnce(command, partial, offset, deadline, digest) {
                        receivedSinceRetry = true
                    }
                    if (partial.length() == expectedSize) break
                    throw PushWorkerException("download_failed", "artifact response ended before declared size", retryable = true)
                } catch (stop: PushTransferInterrupted) {
                    throw stop.failure
                } catch (error: PushWorkerException) {
                    val policy = failurePolicy(command, error)
                    discardPartialOnFailure(command, partial, error, policy = policy)
                    if (!policy.retryInWorker) throw error
                } catch (error: IOException) {
                    // Keep the exact partial and retry only within the remaining idle window.
                    if (!failurePolicy(command, error).retryInWorker) throw error
                }
                if (partial.isFile && partial.length() == expectedSize) {
                    break
                }
                // Back off across consecutive failures only; a retry that received
                // artifact bytes starts the backoff over.
                if (receivedSinceRetry) retryIndex = 0
                receivedSinceRetry = false
                val delay = retryDelayMillis(retryIndex).coerceAtMost(deadline.remaining())
                retryIndex = (retryIndex + 1).coerceAtMost(3)
                retryDelay(delay)
            }
        }
        // Validation is outside both the download deadline and the network retry loop.
        onValidationStart()
        verifyAndFinalize(command, partial, completed, digest)
        return completed
    }

    private fun failurePolicy(
        command: PushProtocol.Command,
        error: Throwable?,
    ): FailurePolicy {
        if (error == null) return FailurePolicy()
        val failure = error as? PushWorkerException ?: return FailurePolicy(
            retainPartial = error is IOException,
            retryInWorker = error is IOException,
        )
        val retainPartial = failure.code == "artifact_unavailable"
        return when (failure.code) {
            "download_failed" -> if (failure.retryable) {
                FailurePolicy(
                    interruptionReason = "download_interrupted",
                    retainPartial = true,
                    retryInWorker = true,
                )
            } else {
                FailurePolicy(retainPartial = retainPartial)
            }
            PUSH_LEASE_REVOKED -> FailurePolicy(
                interruptionReason = "server_lease_revoked",
                retainPartial = true,
            )
            "storage_write_failed" -> if (validatedResumeOffset(command) > 0L) {
                FailurePolicy(
                    interruptionReason = "storage_write_failed",
                    retainPartial = true,
                )
            } else {
                FailurePolicy()
            }
            else -> FailurePolicy(
                retainPartial = retainPartial,
                retryInWorker = failure.retryable,
            )
        }
    }

    /** Keep failure cleanup in the worker even if the coordinator cannot save its result. */
    private fun discardPartialOnFailure(
        command: PushProtocol.Command,
        partial: File,
        failure: PushWorkerException,
        invalidateMetadata: Boolean = false,
        policy: FailurePolicy = failurePolicy(command, failure),
    ) {
        if (policy.retainPartial) return
        partial.deleteRecursively()
        if (invalidateMetadata) File(partial.parentFile, "metadata.json").delete()
    }

    private val resumableHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_NO_PROGRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun openResumableResponse(
        command: PushProtocol.Command,
        offset: Long,
        deadline: PushDownloadDeadline,
    ): Response {
        val request = Request.Builder().url(command.artifactUrl)
            .header("Accept-Encoding", "identity")
            .header("If-Match", requireNotNull(command.artifactEtag))
        if (offset > 0L) request.header("Range", "bytes=$offset-")
        val call = resumableHttpClient.newCall(request.build())
        deadline.attach(call)
        return try { call.execute() }
        catch (error: IOException) {
            deadline.attach(null)
            throw error
        }
    }

    private fun writeDownloadBody(
        input: InputStream,
        partial: File,
        append: Boolean,
        expectedLength: Long?,
        digest: PartialFileDigest,
        beforeRead: () -> Unit = {},
        onBytesWritten: () -> Unit = {},
    ) {
        var received = 0L
        input.use {
            val output = openStorageOutput(partial, append)
            try {
                digest.beginWrite(append)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    beforeRead()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    received = safeAdd(received, read.toLong())
                    if (expectedLength != null && received > expectedLength) {
                        throw PushWorkerException("artifact_identity_mismatch", "HTTP body exceeded its declared range")
                    }
                    storageWrite { output.write(buffer, 0, read) }
                    digest.update(buffer, 0, read)
                    onBytesWritten()
                }
            } finally {
                try {
                    // Persist the exact bytes already written even when the network
                    // read or idle deadline failed, before retry or local validation.
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

    private fun downloadOnce(
        command: PushProtocol.Command,
        partial: File,
        offset: Long,
        deadline: PushDownloadDeadline,
        digest: PartialFileDigest,
        onBytesWritten: () -> Unit,
    ) {
        val expectedSize = requireNotNull(command.artifactSize)
        val connection = openResumableResponse(command, offset, deadline)
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
            val handledStatuses = if (offset == 0L) {
                setOf(HttpURLConnection.HTTP_OK)
            } else {
                setOf(HttpURLConnection.HTTP_PARTIAL, HttpURLConnection.HTTP_OK, 416)
            }
            if (status !in handledStatuses) {
                val failure = "artifact download returned unexpected HTTP $status"
                if (offset == 0L) throw PushWorkerException("download_failed", failure)
                // The status says nothing about the stored bytes: keep the validated
                // partial and end this attempt as interrupted for manual Resume.
                throw PushTransferInterrupted(PushWorkerException("download_failed", failure, retryable = true))
            }
            fun rejectInvalidResponse(detail: String): Nothing {
                // Reject this response before writing, without discarding an authorized prefix.
                if (offset > 0L) {
                    throw PushTransferInterrupted(PushWorkerException("download_failed", detail, retryable = true))
                }
                throw PushWorkerException("artifact_identity_mismatch", detail)
            }
            if (responseEtag == null || !PushProtocol.isStrongEtag(responseEtag)) {
                rejectInvalidResponse("response did not provide a strong ETag")
            }
            if (responseEtag != command.artifactEtag) {
                throw PushWorkerException("artifact_identity_mismatch", "artifact ETag changed while resuming")
            }
            val contentEncoding = connection.header("Content-Encoding")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
                rejectInvalidResponse("artifact response used non-identity Content-Encoding")
            }

            fun writeBody(append: Boolean, expectedLength: Long) {
                val body = requireNotNull(connection.body)
                val declared = body.contentLength()
                if (declared >= 0L && declared != expectedLength) {
                    rejectInvalidResponse("HTTP body length does not match the expected range")
                }
                writeDownloadBody(
                    body.byteStream(), partial, append, expectedLength, digest,
                    beforeRead = { deadline.remaining() },
                    onBytesWritten = {
                        deadline.receivedBytes()
                        onBytesWritten()
                    },
                )
            }

            if (offset == 0L) {
                writeBody(append = false, expectedLength = expectedSize)
                return
            }

            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = parseContentRange(connection.header("Content-Range"))
                        ?: rejectInvalidResponse("missing or malformed Content-Range")
                    if (range.first != offset || range.third != expectedSize ||
                        range.second < range.first || range.second >= range.third
                    ) {
                        rejectInvalidResponse("Content-Range does not match the requested offset or expected size")
                    }
                    val rangeLength = range.second - range.first + 1L
                    if ((connection.body?.contentLength() ?: -1L) != rangeLength) {
                        rejectInvalidResponse("Content-Length does not match Content-Range")
                    }
                    writeBody(append = true, expectedLength = rangeLength)
                }
                HttpURLConnection.HTTP_OK -> {
                    // A server that ignored Range must never be appended to a partial file.
                    writeBody(append = false, expectedLength = expectedSize)
                }
                416 -> rejectInvalidResponse("416 range cannot describe an incomplete local artifact")
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

    private fun verifyAndFinalize(
        command: PushProtocol.Command,
        partial: File,
        completed: File,
        digest: PartialFileDigest,
    ) {
        val expectedSize = requireNotNull(command.artifactSize)
        if (!partial.isFile || partial.length() != expectedSize) {
            throw PushWorkerException("artifact_identity_mismatch", "received size did not match the declared size")
        }
        val expectedSha = requireNotNull(command.artifactSha256)
        if (!digest.finishHex().equals(expectedSha, ignoreCase = true)) {
            val failure = PushWorkerException("artifact_identity_mismatch", "artifact SHA-256 did not match its declared identity")
            discardPartialOnFailure(command, partial, failure, invalidateMetadata = true)
            throw failure
        }
        atomicMove(partial, completed)
    }

    private fun matchesArtifactIdentity(command: PushProtocol.Command, file: File): Boolean {
        if (!file.isFile || file.length() != requireNotNull(command.artifactSize)) return false
        val digest = MessageDigest.getInstance("SHA-256").apply { updateWithFile(file) }
        return digest.hexDigest().equals(requireNotNull(command.artifactSha256), ignoreCase = true)
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
            "artifact_unavailable", "storage_write_failed", "validation_failed", "apply_failed",
            "client_persistence_unavailable" -> prefix
            else -> "apply_failed"
        }
    }
}

/**
 * Ends the job-v1 transfer loop without another retry. The wrapped retryable
 * `download_failed` [failure] becomes an interrupted attempt that keeps the partial.
 */
private class PushTransferInterrupted(val failure: PushWorkerException) : Exception(failure.message, failure)

class PushWorkerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : Exception(message, cause)
