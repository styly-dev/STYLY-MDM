package com.styly.mdmclient

import android.os.Environment
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class LbeMapDescriptor(
    val sha256: String,
    val name: String,
    val size: Long,
) {
    fun toJson() = org.json.JSONObject()
        .put("sha256", sha256)
        .put("name", name)
        .put("size", size)

    companion object {
        const val MAX_BYTES = 512L * 1024L * 1024L

        fun parse(value: Any?): LbeMapDescriptor? {
            val json = value as? org.json.JSONObject ?: return null
            val sha256 = json.opt("sha256") as? String ?: return null
            val name = json.opt("name") as? String ?: return null
            val sizeNumber = json.opt("size") as? Number ?: return null
            val sizeValue = sizeNumber.toDouble()
            if (!sizeValue.isFinite() || sizeValue % 1.0 != 0.0 || sizeValue !in 1.0..MAX_BYTES.toDouble()) return null
            val size = sizeValue.toLong()
            if (!sha256.matches(Regex("[a-f0-9]{64}")) ||
                name.isBlank() || name.length > 255 || name == "." || name == ".." ||
                name.contains('/') || name.contains('\\') || name.contains('\u0000') ||
                !name.endsWith(".zip", ignoreCase = true)
            ) return null
            return LbeMapDescriptor(sha256, name, size)
        }
    }
}

internal class LbeMapTransfer(
    val path: String,
    val token: String?,
    val serverOrigin: String?,
    val expectedDeviceId: String?,
)

internal data class LbeMapAcquisition(
    val file: File,
    val verifiedCacheHit: Boolean,
)

/** The next live policy delivery can provide a usable network route or a fresh token. */
internal class RetryableMapDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun validatedLbeMapUrl(
    serverOrigin: String,
    path: String,
    descriptor: LbeMapDescriptor,
    revision: ConfigurationVersion,
    expectedDeviceId: String?,
): HttpUrl? {
    val origin = serverOrigin.toHttpUrlOrNull() ?: return null
    if (origin.encodedPath != "/" || origin.encodedQuery != null || origin.encodedFragment != null ||
        origin.username.isNotEmpty() || origin.password.isNotEmpty()
    ) return null
    if (!path.startsWith('/') || path.startsWith("//")) return null
    val url = origin.resolve(path) ?: return null
    if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
        url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.encodedPath != "/api/lbe-maps/${descriptor.sha256}/download" ||
        url.encodedFragment != null || url.queryParameterNames != setOf("device_id", "revision") ||
        url.queryParameterValues("device_id").size != 1 || url.queryParameterValues("revision").size != 1 ||
        url.queryParameter("revision") != revision.revision
    ) return null
    val deviceId = url.queryParameter("device_id") ?: return null
    if (!WifiSettingsProtocol.isValidUuid(deviceId)) return null
    if (expectedDeviceId == null || deviceId != expectedDeviceId) return null
    return url
}

internal class LbeMapArtifactStore(
    private val directory: File = File(Environment.getExternalStorageDirectory(), "maps/styly-mdm"),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    private data class TransferSession(
        val revision: ConfigurationVersion,
        val cancellation: ArtifactDownloadCancellation,
    )

    private val transferLock = Any()
    private val activeTransfers = linkedSetOf<TransferSession>()

    fun cancelLbeMapTransfer(keepVersion: ConfigurationVersion?) {
        val cancelled = synchronized(transferLock) {
            activeTransfers.filter { keepVersion == null || it.revision != keepVersion }
                .map(TransferSession::cancellation)
        }
        cancelled.forEach(ArtifactDownloadCancellation::cancel)
    }

    fun acquire(
        descriptor: LbeMapDescriptor,
        transfer: LbeMapTransfer?,
        revision: ConfigurationVersion,
        isCurrent: () -> Boolean,
    ): LbeMapAcquisition {
        val session = TransferSession(revision, ArtifactDownloadCancellation())
        synchronized(transferLock) { activeTransfers += session }
        try {
            return acquire(descriptor, transfer, revision, isCurrent, session.cancellation)
        } finally {
            synchronized(transferLock) { activeTransfers -= session }
        }
    }

    private fun acquire(
        descriptor: LbeMapDescriptor,
        transfer: LbeMapTransfer?,
        revision: ConfigurationVersion,
        isCurrent: () -> Boolean,
        cancellation: ArtifactDownloadCancellation,
    ): LbeMapAcquisition {
        fun checkCurrent() {
            if (!isCurrent() || cancellation.isCancelled()) throw StaleConfigurationException()
        }
        val transferIsCurrent = { isCurrent() && !cancellation.isCancelled() }
        checkCurrent()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Map cache directory is unavailable")
        val target = File(directory, descriptor.sha256 + ".zip")
        val partial = File(directory, descriptor.sha256 + ".part")
        pruneForAcquisition(target, partial)
        verifiedCachedFile(descriptor, transferIsCurrent)?.let { return LbeMapAcquisition(it, verifiedCacheHit = true) }
        java.nio.file.Files.deleteIfExists(target.toPath())
        if (partial.length() > descriptor.size) java.nio.file.Files.delete(partial.toPath())
        var downloadUrl: String? = null
        var authorization: String? = null
        if (partial.length() < descriptor.size) {
            val details = transfer ?: throw RetryableMapDownloadException("Map transfer awaits a live policy")
            val origin = details.serverOrigin ?: throw IOException("Map server origin is unavailable")
            val token = details.token?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: throw IOException("Map download token is invalid")
            if (token.any { it !in '!'..'~' }) throw IOException("Map download token is invalid")
            downloadUrl = validatedLbeMapUrl(origin, details.path, descriptor, revision, details.expectedDeviceId)
                ?.toString() ?: throw IOException("Map download route is invalid")
            authorization = "Bearer $token"
        }

        try {
            downloadArtifact(ArtifactDownloadRequest(
                url = downloadUrl,
                partialFile = partial,
                completedFile = target,
                expectedSize = descriptor.size,
                expectedSha256 = descriptor.sha256,
                expectedEtag = "\"${descriptor.sha256}\"",
                client = client,
                authorization = authorization,
                accept = "application/zip",
                isCurrent = transferIsCurrent,
                supportsQueuedResponse = true,
                retryDelay = cancellation::await,
                cancellation = cancellation,
                failurePolicy = ::mapDownloadFailurePolicy,
            ))
        } catch (failure: PushWorkerException) {
            if (failure.recovery == PushFailureRecovery.RetryWithinDeadline) {
                throw RetryableMapDownloadException("Map transfer interrupted", failure)
            }
            throw IOException(failure.message ?: "Map transfer failed", failure)
        }

        checkCurrent()
        if (!hasZipEntries(target)) {
            java.nio.file.Files.deleteIfExists(target.toPath())
            throw IOException("Downloaded map is not a valid ZIP archive")
        }
        checkCurrent()
        return LbeMapAcquisition(target, verifiedCacheHit = false)
    }

    private fun verifiedCachedFile(descriptor: LbeMapDescriptor, isCurrent: () -> Boolean): File? {
        if (!isCurrent()) throw StaleConfigurationException()
        val target = File(directory, "${descriptor.sha256}.zip")
        val verified = try {
            matchesArtifactIdentity(target, descriptor.size, descriptor.sha256, isCurrent) && hasZipEntries(target)
        } catch (stale: StaleConfigurationException) {
            throw stale
        } catch (error: IOException) {
            if (!isCurrent()) throw StaleConfigurationException()
            if (!target.isFile) return null
            throw error
        }
        if (!verified || !target.isFile) return null
        if (!isCurrent()) throw StaleConfigurationException()
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    // The SDK owns the imported map. Bound staging even when an import fails or times out.
    private fun pruneForAcquisition(target: File, partial: File) {
        val files = directory.listFiles() ?: throw IOException("Map cache directory could not be listed")
        val predecessor = files.asSequence()
            .filter { it.isFile && it != target && ZIP_NAME.matches(it.name) }
            .maxByOrNull(File::lastModified)
        for (file in files) {
            if (!file.isFile || file == target || file == predecessor || file == partial) continue
            if (PART_NAME.matches(file.name) || ZIP_NAME.matches(file.name)) java.nio.file.Files.delete(file.toPath())
        }
    }

    private fun mapDownloadFailurePolicy(error: Throwable): ArtifactDownloadFailurePolicy {
        return when (error) {
            is PushWorkerException -> when {
                error.httpStatus == 401 || error.httpStatus == 403 ->
                    throw RetryableMapDownloadException("Map transfer needs a fresh credential", error)
                error.code == "artifact_unavailable" -> ArtifactDownloadFailurePolicy(false, true)
                error.code == "download_failed" && error.recovery == PushFailureRecovery.RetryWithinDeadline ->
                    ArtifactDownloadFailurePolicy(true, true)
                error.recovery == PushFailureRecovery.AwaitOperatorResume -> ArtifactDownloadFailurePolicy(false, true)
                else -> ArtifactDownloadFailurePolicy(false, false)
            }
            is IOException -> ArtifactDownloadFailurePolicy(true, true)
            else -> ArtifactDownloadFailurePolicy(false, false)
        }
    }

    private fun hasZipEntries(file: File): Boolean = try {
        ZipFile(file).use { it.entries().hasMoreElements() }
    } catch (_: java.util.zip.ZipException) {
        false
    }

    companion object {
        private val ZIP_NAME = Regex("[a-f0-9]{64}\\.zip")
        private val PART_NAME = Regex("[a-f0-9]{64}\\.part")
    }
}

private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
