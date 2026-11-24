package com.styly.mdmclient

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A byte-progress deadline shared by Push/Sync and LBE artifact transfers. */
internal class ArtifactDownloadDeadline(
    private val clock: () -> Long,
    private val timeoutMs: Long,
) : AutoCloseable {
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "artifact-download-deadline").apply { isDaemon = true }
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
            throw PushWorkerException(
                "download_failed", "No download data received for ${timeoutMs}ms",
                recovery = PushFailureRecovery.RetryWithinDeadline,
            )
        }
        return timeoutMs - elapsed
    }

    @Synchronized fun receivedBytes() {
        val now = clock()
        remainingAt(now)
        lastProgress = now
    }

    // Waiting for an explicit slot offer consumes no transfer time. Preserve the
    // budget already spent on failed HTTP requests and network backoff.
    @Synchronized fun excludeQueueTime(requestStarted: Long, waitMs: Long) {
        val now = clock()
        remainingAt(now)
        lastProgress += now - requestStarted + waitMs
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
 * Incremental SHA-256 for bytes written during a transfer. An existing prefix is
 * rehashed only after the response ends, outside the network progress deadline.
 */
internal class PartialFileDigest(private val file: File) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var length = 0L
    private var tracking = true
    var rehashedBytes = 0L
        private set

    fun beginWrite(append: Boolean) {
        if (!append) restart()
        else if (!tracking || file.length() != length) tracking = false
    }

    fun update(buffer: ByteArray, offset: Int, count: Int) {
        if (!tracking) return
        digest.update(buffer, offset, count)
        length += count
    }

    fun finishHex(isCurrent: () -> Boolean = { true }): String {
        val actual = if (file.isFile) file.length() else 0L
        if (!tracking || actual != length) {
            digest.reset()
            if (actual > 0L) digest.updateWithFile(file, isCurrent)
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

private fun MessageDigest.updateWithFile(file: File, isCurrent: () -> Boolean) {
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (!isCurrent()) throw StaleConfigurationException()
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }
}

private fun MessageDigest.hexDigest(): String =
    digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

enum class PushFailureRecovery {
    RetryWithinDeadline,
    AwaitOperatorResume,
    TerminalFailure,
}

class PushWorkerException(
    val code: String,
    message: String,
    cause: Throwable? = null,
    val recovery: PushFailureRecovery = PushFailureRecovery.TerminalFailure,
) : Exception(message, cause) {
    internal var httpStatus: Int? = null
}

internal class StaleConfigurationException : IOException()

internal class ArtifactDownloadCancellation {
    private val cancelledSignal = CountDownLatch(1)
    private val callLock = Any()
    private var call: Call? = null

    fun attach(value: Call?) {
        synchronized(callLock) {
            call = value
            if (isCancelled()) value?.cancel()
        }
    }

    fun cancel() {
        synchronized(callLock) {
            call?.cancel()
            call = null
            cancelledSignal.countDown()
        }
    }

    fun isCancelled(): Boolean = cancelledSignal.count == 0L

    fun await(delayMs: Long) {
        if (cancelledSignal.await(delayMs, TimeUnit.MILLISECONDS)) throw StaleConfigurationException()
        ensureActive()
    }

    fun ensureActive() {
        if (isCancelled()) throw StaleConfigurationException()
    }
}

internal class ArtifactDownloadFailurePolicy(
    val retryWithinDeadline: Boolean,
    val retainPartial: Boolean,
)

/** All byte transfer, HTTP range handling, retries, size checks and SHA checks live here. */
internal class ArtifactDownloadRequest(
    val url: String?,
    val partialFile: File,
    val completedFile: File,
    val expectedSize: Long,
    val expectedSha256: String,
    val expectedEtag: String,
    val client: OkHttpClient,
    val authorization: String? = null,
    val accept: String? = null,
    val isCurrent: () -> Boolean = { true },
    val onValidationStart: () -> Unit = {},
    val supportsQueuedResponse: Boolean = false,
    val noProgressTimeoutMs: Long = 60_000L,
    val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
    val digestFactory: (File) -> PartialFileDigest = ::PartialFileDigest,
    val storageOutputFactory: (File, Boolean) -> FileOutputStream = ::FileOutputStream,
    val failurePolicy: (Throwable) -> ArtifactDownloadFailurePolicy,
    val cancellation: ArtifactDownloadCancellation? = null,
    val onIntegrityFailure: () -> Unit = {},
)

internal fun artifactRetryDelayMillis(retryIndex: Int): Long {
    require(retryIndex >= 0)
    var delay = 1_000L
    repeat(retryIndex.coerceAtMost(3)) { delay = (delay * 2).coerceAtMost(8_000L) }
    return delay
}

internal fun downloadArtifact(request: ArtifactDownloadRequest): File {
    require(request.expectedSize > 0L)
    require(PushProtocol.isStrongEtag(request.expectedEtag))
    require(request.expectedSha256.matches(Regex("[a-fA-F0-9]{64}")))
    ensureCurrent(request)

    if (request.completedFile.isFile) {
        request.onValidationStart()
        if (matchesArtifactIdentity(request.completedFile, request.expectedSize, request.expectedSha256, request.isCurrent)) {
            return request.completedFile
        }
        request.completedFile.delete()
        request.onIntegrityFailure()
        throw PushWorkerException("artifact_identity_mismatch", "completed artifact SHA-256 did not match")
    }

    if (request.partialFile.isFile && request.partialFile.length() > request.expectedSize) {
        request.partialFile.delete()
        throw PushWorkerException("artifact_identity_mismatch", "partial exceeds expected size")
    }

    val digest = request.digestFactory(request.partialFile)
    if (request.partialFile.isFile && request.partialFile.length() == request.expectedSize) {
        request.onValidationStart()
        return verifyAndFinalize(request, digest)
    }

    ArtifactDownloadDeadline(request.monotonicMillis, request.noProgressTimeoutMs).use { deadline ->
        var retryIndex = 0
        var receivedSinceRetry = false
        while (true) {
            deadline.remaining()
            try {
                val offset = request.partialFile.takeIf { it.isFile }?.length() ?: 0L
                val requestStarted = request.monotonicMillis()
                val queuedDelay = downloadArtifactOnce(request, offset, deadline, digest) {
                    receivedSinceRetry = true
                }
                if (queuedDelay != null) {
                    deadline.excludeQueueTime(requestStarted, queuedDelay)
                    request.retryDelay(queuedDelay)
                    continue
                }
                if (request.partialFile.length() == request.expectedSize) break
                throw PushWorkerException(
                    "download_failed", "artifact response ended before declared size",
                    recovery = PushFailureRecovery.RetryWithinDeadline,
                )
            } catch (error: StaleConfigurationException) {
                throw error
            } catch (error: Exception) {
                ensureCurrent(request)
                val classifiedError = if (error.isRecoverableDownloadFailure() && error.hasStorageCleanupFailure()) {
                    PushWorkerException("storage_write_failed", downloadFailureDetail(error), error)
                } else error
                val policy = request.failurePolicy(classifiedError)
                if (!policy.retainPartial) request.partialFile.delete()
                if (!policy.retryWithinDeadline) throw classifiedError
            }
            if (request.partialFile.isFile && request.partialFile.length() == request.expectedSize) break
            if (receivedSinceRetry) retryIndex = 0
            receivedSinceRetry = false
            val delay = artifactRetryDelayMillis(retryIndex).coerceAtMost(deadline.remaining())
            retryIndex = (retryIndex + 1).coerceAtMost(3)
            request.retryDelay(delay)
        }
    }

    request.onValidationStart()
    return verifyAndFinalize(request, digest)
}

private fun downloadArtifactOnce(
    request: ArtifactDownloadRequest,
    offset: Long,
    deadline: ArtifactDownloadDeadline,
    digest: PartialFileDigest,
    onBytesWritten: () -> Unit,
): Long? {
    ensureCurrent(request)
    val builder = Request.Builder()
        .url(requireNotNull(request.url) { "artifact transfer URL is unavailable" })
        .header("Accept-Encoding", "identity")
        .header("If-Match", request.expectedEtag)
    request.authorization?.let { builder.header("Authorization", it) }
    request.accept?.let { builder.header("Accept", it) }
    if (offset > 0L) builder.header("Range", "bytes=$offset-")
    val call = request.client.newCall(builder.build())
    request.cancellation?.attach(call)
    deadline.attach(call)
    val response = try {
        call.execute()
    } catch (error: IOException) {
        deadline.attach(null)
        request.cancellation?.attach(null)
        ensureCurrent(request)
        throw error
    }

    try {
        ensureCurrent(request)
        deadline.remaining()
        val status = response.code
        if (status == 503 && request.supportsQueuedResponse &&
            response.header("X-MDM-Transfer-Status")?.equals("queued", ignoreCase = true) == true
        ) {
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 10L) ?: 1L
            return retryAfterSeconds * 1_000L
        }
        if (status == 409 && response.header("X-Push-Lease-Status")
                ?.equals("revoked", ignoreCase = true) == true
        ) {
            throw PushWorkerException("push_lease_revoked", "server revoked the Push/Sync transfer lease")
        }
        if (status == HttpURLConnection.HTTP_PRECON_FAILED) {
            throw httpFailure(status, "artifact_identity_mismatch", "artifact precondition failed (HTTP 412)")
        }
        if (status == HttpURLConnection.HTTP_NOT_FOUND || status == HttpURLConnection.HTTP_GONE) {
            throw httpFailure(status, "artifact_unavailable", "artifact is unavailable (HTTP $status)")
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw httpFailure(
                status,
                "download_failed", "artifact download returned HTTP $status",
                PushFailureRecovery.RetryWithinDeadline,
            )
        }

        val handledStatuses = if (offset == 0L) setOf(HttpURLConnection.HTTP_OK)
            else setOf(HttpURLConnection.HTTP_PARTIAL, HttpURLConnection.HTTP_OK, 416)
        if (status !in handledStatuses) {
            val recovery = when {
                offset > 0L -> PushFailureRecovery.AwaitOperatorResume
                else -> PushFailureRecovery.TerminalFailure
            }
            throw httpFailure(status, "download_failed", "artifact download returned unexpected HTTP $status", recovery)
        }

        fun rejectInvalidResponse(detail: String): Nothing {
            if (offset > 0L) {
                throw PushWorkerException("download_failed", detail, recovery = PushFailureRecovery.AwaitOperatorResume)
            }
            throw PushWorkerException("artifact_identity_mismatch", detail)
        }

        val responseEtag = response.header("ETag")
        if (responseEtag == null || !PushProtocol.isStrongEtag(responseEtag)) {
            rejectInvalidResponse("response did not provide a strong ETag")
        }
        if (responseEtag != request.expectedEtag) {
            throw PushWorkerException("artifact_identity_mismatch", "artifact ETag changed while resuming")
        }
        val contentEncoding = response.header("Content-Encoding")?.trim()?.takeIf(String::isNotEmpty)
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            rejectInvalidResponse("artifact response used non-identity Content-Encoding")
        }

        fun writeBody(append: Boolean, expectedLength: Long) {
            val body = response.body ?: throw IOException("artifact response body is empty")
            val declared = body.contentLength()
            if (declared >= 0L && declared != expectedLength) {
                rejectInvalidResponse("HTTP body length does not match the expected range")
            }
            writeArtifactBody(
                body.byteStream(), request, append, expectedLength, digest, deadline,
                onBytesWritten,
            )
        }

        if (offset == 0L) {
            writeBody(append = false, expectedLength = request.expectedSize)
            return null
        }

        when (status) {
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = parseArtifactContentRange(response.header("Content-Range"))
                    ?: rejectInvalidResponse("missing or malformed Content-Range")
                if (range.start != offset || range.totalLength != request.expectedSize ||
                    range.endInclusive < range.start || range.endInclusive >= range.totalLength
                ) rejectInvalidResponse("Content-Range does not match the requested offset or expected size")
                val rangeLength = range.endInclusive - range.start + 1L
                if ((response.body?.contentLength() ?: -1L) != rangeLength) {
                    rejectInvalidResponse("Content-Length does not match Content-Range")
                }
                writeBody(append = true, expectedLength = rangeLength)
            }
            HttpURLConnection.HTTP_OK -> writeBody(append = false, expectedLength = request.expectedSize)
            416 -> rejectInvalidResponse("416 range cannot describe an incomplete local artifact")
        }
    } finally {
        deadline.attach(null)
        response.close()
        request.cancellation?.attach(null)
    }
    return null
}

private fun writeArtifactBody(
    input: InputStream,
    request: ArtifactDownloadRequest,
    append: Boolean,
    expectedLength: Long,
    digest: PartialFileDigest,
    deadline: ArtifactDownloadDeadline,
    onBytesWritten: () -> Unit,
) {
    var received = 0L
    input.use {
        val output = try {
            request.storageOutputFactory(request.partialFile, append)
        } catch (error: IOException) {
            throw PushWorkerException("storage_write_failed", error.message ?: "Could not write artifact data", error)
        }
        var primaryFailure: Throwable? = null
        try {
            digest.beginWrite(append)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                ensureCurrent(request)
                deadline.remaining()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                ensureCurrent(request)
                if (read.toLong() > Long.MAX_VALUE - received) {
                    throw PushWorkerException("artifact_identity_mismatch", "artifact body length overflow")
                }
                received += read
                if (received > expectedLength) {
                    throw PushWorkerException("artifact_identity_mismatch", "HTTP body exceeded its declared range")
                }
                try {
                    output.write(buffer, 0, read)
                } catch (error: IOException) {
                    throw PushWorkerException("storage_write_failed", error.message ?: "Could not write artifact data", error)
                }
                digest.update(buffer, 0, read)
                deadline.receivedBytes()
                onBytesWritten()
            }
            if (received != expectedLength) {
                throw PushWorkerException(
                    "download_failed", "HTTP body ended before its declared range",
                    recovery = PushFailureRecovery.RetryWithinDeadline,
                )
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            finishDownloadOutput(primaryFailure, { output.flush() }, { output.fd.sync() }, { output.close() })
        }
    }
}

private data class ArtifactContentRange(val start: Long, val endInclusive: Long, val totalLength: Long)

private fun parseArtifactContentRange(value: String?): ArtifactContentRange? {
    val match = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").matchEntire(value ?: "") ?: return null
    return try {
        ArtifactContentRange(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
    } catch (_: NumberFormatException) {
        null
    }
}

private fun verifyAndFinalize(
    request: ArtifactDownloadRequest,
    digest: PartialFileDigest,
): File {
    ensureCurrent(request)
    if (!request.partialFile.isFile || request.partialFile.length() != request.expectedSize) {
        discardIntegrityFailure(request)
        throw PushWorkerException("artifact_identity_mismatch", "received size did not match the declared size")
    }
    if (!digest.finishHex(request.isCurrent).equals(request.expectedSha256, ignoreCase = true)) {
        discardIntegrityFailure(request)
        throw PushWorkerException("artifact_identity_mismatch", "artifact SHA-256 did not match its declared identity")
    }
    ensureCurrent(request)
    atomicMoveArtifact(request.partialFile, request.completedFile)
    return request.completedFile
}

internal fun matchesArtifactIdentity(file: File, expectedSize: Long, sha256: String, isCurrent: () -> Boolean): Boolean {
    if (!file.isFile || file.length() != expectedSize) return false
    val digest = MessageDigest.getInstance("SHA-256").apply { updateWithFile(file, isCurrent) }
    return digest.hexDigest().equals(sha256, ignoreCase = true)
}

private fun discardIntegrityFailure(request: ArtifactDownloadRequest) {
    request.partialFile.delete()
    request.completedFile.delete()
    request.onIntegrityFailure()
}

private fun ensureCurrent(request: ArtifactDownloadRequest) {
    request.cancellation?.ensureActive()
    if (!request.isCurrent()) throw StaleConfigurationException()
}

private fun httpFailure(
    status: Int,
    code: String,
    detail: String,
    recovery: PushFailureRecovery = PushFailureRecovery.TerminalFailure,
): PushWorkerException = PushWorkerException(code, detail, recovery = recovery).also {
    it.httpStatus = status
}

internal fun atomicMoveArtifact(source: File, destination: File) {
    val options = arrayOf(java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    try {
        java.nio.file.Files.move(source.toPath(), destination.toPath(), *options)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Preserves the body failure while attempting every output persistence/close step. */
internal fun finishDownloadOutput(primaryFailure: Throwable?, vararg operations: () -> Unit) {
    var failure = primaryFailure
    for (operation in operations) {
        try {
            operation()
        } catch (error: IOException) {
            val storageFailure = PushWorkerException(
                "storage_write_failed", error.message ?: "Could not persist download data", error,
            )
            if (failure == null) failure = storageFailure else failure.addSuppressed(storageFailure)
        }
    }
    if (primaryFailure == null && failure != null) throw failure
}

internal fun downloadFailureDetail(error: Throwable): String = buildString {
    append(error.message ?: error.javaClass.simpleName)
    error.suppressed.forEach {
        append("; cleanup: ")
        append(it.message ?: it.javaClass.simpleName)
    }
}

internal fun Throwable.hasStorageCleanupFailure(): Boolean = suppressed.any {
    it is PushWorkerException && it.code == "storage_write_failed"
}

internal fun Throwable.isRecoverableDownloadFailure(): Boolean = this is IOException ||
    this is PushWorkerException && recovery != PushFailureRecovery.TerminalFailure
