package com.styly.mdmclient

import android.os.Environment
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
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

internal data class LbeMapTransfer(
    val path: String,
    val token: String?,
    val serverOrigin: String?,
    val expectedDeviceId: String?,
)

internal class StaleConfigurationException : IOException()

/** The next live policy delivery can provide a usable network route or a fresh token. */
internal class RetryableMapDownloadException(message: String, cause: IOException? = null) : IOException(message, cause)

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
    fun acquire(
        descriptor: LbeMapDescriptor,
        transfer: LbeMapTransfer?,
        revision: ConfigurationVersion,
        isCurrent: () -> Boolean,
    ): File {
        if (!isCurrent()) throw StaleConfigurationException()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Map cache directory is unavailable")
        val target = File(directory, "${descriptor.sha256}.zip")
        val partial = File(directory, "${descriptor.sha256}.part")
        pruneForAcquisition(target, partial)
        if (isVerified(target, descriptor, isCurrent)) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        java.nio.file.Files.deleteIfExists(target.toPath())
        if (partial.length() > descriptor.size) java.nio.file.Files.delete(partial.toPath())
        if (partial.length() < descriptor.size) {
            val details = transfer ?: throw RetryableMapDownloadException("Map transfer awaits a live policy")
            val origin = details.serverOrigin ?: throw IOException("Map server origin is unavailable")
            val token = details.token?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: throw IOException("Map download token is invalid")
            if (token.any { it !in '!'..'~' }) throw IOException("Map download token is invalid")
            val url = validatedLbeMapUrl(origin, details.path, descriptor, revision, details.expectedDeviceId)
                ?: throw IOException("Map download route is invalid")
            download(url, token, descriptor, partial, isCurrent)
        }

        if (!isVerified(partial, descriptor, isCurrent)) {
            java.nio.file.Files.deleteIfExists(partial.toPath())
            throw IOException("Downloaded map failed ZIP, size or SHA-256 verification")
        }
        if (!isCurrent()) throw StaleConfigurationException()
        try {
            java.nio.file.Files.move(
                partial.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun download(
        url: HttpUrl,
        token: String,
        descriptor: LbeMapDescriptor,
        destination: File,
        isCurrent: () -> Boolean,
    ) {
        val offset = destination.length()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/zip")
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        readNetwork { client.newCall(request).execute() }.use { response ->
            val append = when (response.code) {
                200 -> false // A server may ignore Range and send the entire archive.
                206 -> {
                    if (offset == 0L || response.header("Content-Range") != "bytes $offset-${descriptor.size - 1}/${descriptor.size}") {
                        throw IOException("Map download returned an unexpected Content-Range")
                    }
                    true
                }
                401, 403, 408, 429, in 500..599 ->
                    throw RetryableMapDownloadException("Map download returned HTTP ${response.code}")
                else -> throw IOException("Map download returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Map download body is empty")
            var received = if (append) offset else 0L
            if (body.contentLength() >= 0 && body.contentLength() != descriptor.size - received) {
                throw IOException("Map download size did not match its descriptor")
            }
            body.byteStream().use { input ->
                FileOutputStream(destination, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (!isCurrent()) throw StaleConfigurationException()
                        val count = readNetwork { input.read(buffer) }
                        if (count < 0) break
                        received += count
                        if (received > descriptor.size) throw IOException("Map download exceeded its declared size")
                        output.write(buffer, 0, count)
                    }
                    if (received < descriptor.size) throw RetryableMapDownloadException("Map download ended early")
                    output.fd.sync()
                }
            }
        }
    }

    private fun isVerified(file: File, descriptor: LbeMapDescriptor, isCurrent: () -> Boolean): Boolean {
        if (!file.isFile || file.length() != descriptor.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (!isCurrent()) throw StaleConfigurationException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (hash != descriptor.sha256) return false
        return try {
            ZipFile(file).use { it.entries().hasMoreElements() }
        } catch (_: java.util.zip.ZipException) {
            false
        }
    }

    private inline fun <T> readNetwork(action: () -> T): T = try {
        action()
    } catch (failure: IOException) {
        throw RetryableMapDownloadException("Map transfer interrupted", failure)
    }

    companion object {
        private val ZIP_NAME = Regex("[a-f0-9]{64}\\.zip")
        private val PART_NAME = Regex("[a-f0-9]{64}\\.part")
    }
}

private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
