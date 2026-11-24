package com.styly.mdmclient

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okhttp3.ResponseBody.Companion.asResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LbeMapArtifactTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `descriptor validates archive name hash and integral bounded size`() {
        val valid = descriptorJson()
        assertEquals(DESCRIPTOR, LbeMapDescriptor.parse(valid))
        for (invalid in listOf(
            JSONObject(valid.toString()).put("name", "../map.zip"),
            JSONObject(valid.toString()).put("name", "map.txt"),
            JSONObject(valid.toString()).put("sha256", "A".repeat(64)),
            JSONObject(valid.toString()).put("size", 1.5),
            JSONObject(valid.toString()).put("size", LbeMapDescriptor.MAX_BYTES + 1),
        )) assertNull(LbeMapDescriptor.parse(invalid))
    }

    @Test
    fun `download route requires exact origin hash revision and device`() {
        assertNotNull(validatedLbeMapUrl(ORIGIN, validPath(), DESCRIPTOR, VERSION, DEVICE_ID))
        val rejected = listOf(
            "https://attacker.example/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION",
            "//attacker.example/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION",
            "/api/lbe-maps/${"0".repeat(64)}/download?device_id=$DEVICE_ID&revision=$REVISION",
            "/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION&extra=1",
            "/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION&revision=$REVISION",
            "/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION_2",
            "/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION",
        )
        rejected.forEach { path ->
            val expectedDevice = if (path == rejected.last()) "cccccccc-cccc-4ccc-8ccc-cccccccccccc" else DEVICE_ID
            assertNull(validatedLbeMapUrl(ORIGIN, path, DESCRIPTOR, VERSION, expectedDevice))
        }
        assertNull(validatedLbeMapUrl("$ORIGIN/other", validPath(), DESCRIPTOR, VERSION, DEVICE_ID))
        assertNull(validatedLbeMapUrl("$ORIGIN?x=1", validPath(), DESCRIPTOR, VERSION, DEVICE_ID))
    }

    @Test
    fun `interrupted download resumes verified bytes with a new token and survives offline restart`() {
        val directory = temporary.newFolder("maps")
        val source = temporary.newFile("source.zip").also(::writeZip)
        val bytes = source.readBytes()
        val descriptor = descriptorFor(source)
        val split = bytes.size / 2
        val requests = mutableListOf<Request>()
        val store = storeWithServer(directory) { request ->
            requests += request
            if (requests.size == 1) {
                response(request, 200, bytes).newBuilder()
                    .body(Buffer().write(bytes, 0, split).asResponseBody(null, descriptor.size))
                    .build()
            } else {
                response(request, 403, byteArrayOf())
            }
        }

        assertThrows(RetryableMapDownloadException::class.java) {
            store.acquire(descriptor, transfer(descriptor), VERSION) { true }
        }
        assertEquals(split.toLong(), File(directory, "${descriptor.sha256}.part").length())
        val resumedStore = storeWithServer(directory) { request ->
            requests += request
            response(request, 206, bytes.copyOfRange(split, bytes.size)).newBuilder()
                .header("Content-Range", "bytes $split-${bytes.lastIndex}/${bytes.size}")
                .build()
        }
        val file = resumedStore.acquire(descriptor, transfer(descriptor, "fresh-token"), VERSION) { true }.file
        assertNull(requests[0].header("Range"))
        assertEquals("bytes=$split-", requests[1].header("Range"))
        assertEquals("Bearer token", requests[1].header("Authorization"))
        assertEquals("bytes=$split-", requests[2].header("Range"))
        assertEquals("Bearer fresh-token", requests[2].header("Authorization"))
        assertEquals("\"${descriptor.sha256}\"", requests[2].header("If-Match"))
        assertEquals(file, LbeMapArtifactStore(directory).acquire(descriptor, null, VERSION) { true }.file)
        assertEquals(descriptor.sha256, descriptorFor(file).sha256)
    }

    @Test
    fun `queued responses do not replenish the retry budget for server errors`() {
        val work = temporary.newFolder("alternating-queue")
        var now = 0L
        val delays = mutableListOf<Long>()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor {
            check(++calls <= 4) { "The no-progress budget was reset" }
            val queued = calls % 2 == 0
            response(it.request(), 503, byteArrayOf()).newBuilder().apply {
                if (queued) header("X-MDM-Transfer-Status", "queued")
            }.build()
        }.build()
        val request = ArtifactDownloadRequest(
            url = ORIGIN, partialFile = File(work, "map.part"), completedFile = File(work, "map.zip"),
            expectedSize = DESCRIPTOR.size, expectedSha256 = HASH, expectedEtag = "\"$HASH\"",
            client = client, authorization = "Bearer secret-credential", supportsQueuedResponse = true,
            noProgressTimeoutMs = 3_000L, monotonicMillis = { now },
            retryDelay = { delay -> delays += delay; now += delay },
            failurePolicy = { ArtifactDownloadFailurePolicy(true, true) },
        )
        val failure = assertThrows(PushWorkerException::class.java) { downloadArtifact(request) }
        assertEquals(PushFailureRecovery.RetryWithinDeadline, failure.recovery)
        assertEquals(listOf(1_000L, 1_000L, 2_000L), delays)
        assertFalse(request.toString().contains("secret-credential"))
    }

    @Test
    fun `queued transfer retries and resumes the same prefix`() {
        val directory = temporary.newFolder("queued-map")
        val source = temporary.newFile("queued-source.zip").also(::writeZip)
        val bytes = source.readBytes()
        val descriptor = descriptorFor(source)
        val split = bytes.size / 2
        val partial = File(directory, "${descriptor.sha256}.part").apply {
            writeBytes(bytes.copyOfRange(0, split))
        }
        val requests = mutableListOf<Request>()
        var queued = 0
        val store = storeWithServer(directory) { request ->
            requests += request
            if (queued++ < 3) {
                response(request, 503, byteArrayOf()).newBuilder()
                    .header("X-MDM-Transfer-Status", "queued")
                    .header("Retry-After", "1")
                    .build()
            } else {
                response(request, 206, bytes.copyOfRange(split, bytes.size)).newBuilder()
                    .header("Content-Range", "bytes $split-${bytes.lastIndex}/${bytes.size}")
                    .build()
            }
        }

        val file = store.acquire(descriptor, transfer(descriptor), VERSION) { true }.file

        assertTrue(file.isFile)
        assertFalse(partial.exists())
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.header("Range") == "bytes=$split-" })
        assertTrue(requests.all { it.header("Authorization") == "Bearer token" })
        assertTrue(requests.all { it.header("If-Match") == "\"${descriptor.sha256}\"" })
    }

    @Test
    fun `acquisition reports whether it reused a verified cache`() {
        val directory = temporary.newFolder("cache-hit")
        val source = temporary.newFile("cache-hit-source.zip").also(::writeZip)
        val bytes = source.readBytes()
        val descriptor = descriptorFor(source)
        val cached = File(directory, descriptor.sha256 + ".zip")
        cached.writeBytes(bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
        var downloadCount = 0
        val store = storeWithServer(directory) { request ->
            downloadCount++
            response(request, 200, bytes)
        }

        val downloaded = store.acquire(descriptor, transfer(descriptor), VERSION) { true }
        val cachedAgain = store.acquire(descriptor, null, VERSION) { true }

        assertFalse(downloaded.verifiedCacheHit)
        assertTrue(downloaded.file.readBytes().contentEquals(bytes))
        assertTrue(cachedAgain.verifiedCacheHit)
        assertEquals(downloaded.file, cachedAgain.file)
        assertEquals(1, downloadCount)
    }

    @Test
    fun `newer assignment cancels only superseded map HTTP calls`() {
        val directory = temporary.newFolder("cancelled-map")
        val source = temporary.newFile("cancelled-source.zip").also(::writeZip)
        val descriptor = descriptorFor(source)
        val requestStarted = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestStarted.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(10)
            throw IOException("cancelled by a newer assignment")
        }.build()
        val store = LbeMapArtifactStore(directory, client)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val acquisition = worker.submit<LbeMapAcquisition> {
                store.acquire(descriptor, transfer(descriptor), VERSION) { true }
            }
            assertTrue(requestStarted.await(3, TimeUnit.SECONDS))

            store.cancelLbeMapTransfer(VERSION)
            assertFalse(acquisition.isDone)

            store.cancelLbeMapTransfer(VERSION_2)
            val failure = assertThrows(ExecutionException::class.java) {
                acquisition.get(3, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is StaleConfigurationException)
            assertFalse(File(directory, descriptor.sha256 + ".zip").exists())
            assertTrue(File(directory, descriptor.sha256 + ".part").length() < descriptor.size)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `network error with storage cleanup failure is terminal and clears partial once`() {
        val directory = temporary.newFolder("storage-cleanup-map")
        val source = temporary.newFile("storage-cleanup-source.zip").also(::writeZip)
        val descriptor = descriptorFor(source)
        val partial = File(directory, "${descriptor.sha256}.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var requestCount = 0
        val store = storeWithServer(directory) { request ->
            requestCount++
            throw IOException("network read failed").apply {
                addSuppressed(PushWorkerException("storage_write_failed", "output close failed"))
            }
        }

        val failure = assertThrows(IOException::class.java) {
            store.acquire(descriptor, transfer(descriptor), VERSION) { true }
        }

        assertFalse(failure is RetryableMapDownloadException)
        assertEquals(1, requestCount)
        assertFalse(partial.exists())
    }

    @Test
    fun `range ignored restarts safely and invalid ranges never append bytes`() {
        val directory = temporary.newFolder("maps")
        val source = temporary.newFile("source.zip").also(::writeZip)
        val bytes = source.readBytes()
        val descriptor = descriptorFor(source)
        val partial = File(directory, "${descriptor.sha256}.part").apply { writeBytes(byteArrayOf(0, 0)) }
        val badRange = storeWithServer(directory) { request ->
            response(request, 206, bytes.copyOfRange(2, bytes.size)).newBuilder()
                .header("Content-Range", "bytes 1-${bytes.lastIndex}/${bytes.size}")
                .build()
        }
        assertFalse(assertThrows(java.io.IOException::class.java) {
            badRange.acquire(descriptor, transfer(descriptor), VERSION) { true }
        } is RetryableMapDownloadException)
        assertEquals(2L, partial.length())
        val full = storeWithServer(directory) { request -> response(request, 200, bytes) }
        assertEquals(
            descriptor.sha256,
            descriptorFor(full.acquire(descriptor, transfer(descriptor), VERSION) { true }.file).sha256,
        )
    }

    @Test
    fun `expired token may retry while missing and corrupt maps require explicit retry`() {
        val directory = temporary.newFolder("maps")
        for (code in listOf(403, 404, 200)) {
            val store = storeWithServer(directory) { request -> response(request, code, ByteArray(DESCRIPTOR.size.toInt())) }
            val failure = assertThrows(java.io.IOException::class.java) {
                store.acquire(DESCRIPTOR, transfer(DESCRIPTOR), VERSION) { true }
            }
            assertEquals(code == 403, failure is RetryableMapDownloadException)
            assertFalse(File(directory, "$HASH.zip").exists())
            assertFalse(File(directory, "$HASH.part").exists())
        }
    }

    private fun storeWithServer(
        directory: File,
        respond: (Request) -> Response,
    ): LbeMapArtifactStore {
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        return LbeMapArtifactStore(directory, client)
    }

    private fun response(request: Request, code: Int, bytes: ByteArray): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("ETag", request.header("If-Match") ?: "\"${HASH}\"")
            .body(bytes.toResponseBody()).build()

    private fun transfer(descriptor: LbeMapDescriptor, token: String = "token") =
        LbeMapTransfer(validPath().replace(HASH, descriptor.sha256), token, ORIGIN, DEVICE_ID)

    @Test
    fun `acquisition bounds the cache before import and preserves operator files`() {
        val directory = temporary.newFolder("maps")
        val source = temporary.newFile("source.zip").also(::writeZip)
        val descriptor = descriptorFor(source)
        val current = source.copyTo(File(directory, "${descriptor.sha256}.zip"))
        val predecessorHash = "b".repeat(64)
        val predecessor = File(directory, "$predecessorHash.zip").apply {
            writeText("previous")
            setLastModified(System.currentTimeMillis() - 10_000L)
        }
        val stale = File(directory, "c".repeat(64) + ".zip").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - 20_000L)
        }
        val operatorFile = File(directory, "README.txt").apply { writeText("operator data") }
        val store = LbeMapArtifactStore(directory)

        val abandoned = File(directory, "${"d".repeat(64)}.part").apply { writeText("partial") }
        store.acquire(descriptor, null, VERSION) { true }
        assertFalse(abandoned.exists())

        assertTrue(current.exists())
        assertTrue(predecessor.exists())
        assertFalse(stale.exists())
        assertTrue(operatorFile.exists())
    }

    private fun descriptorFor(file: File): LbeMapDescriptor {
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return LbeMapDescriptor(hash, "map.zip", file.length())
    }

    private fun writeZip(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("0.map"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }
    }

    private fun descriptorJson() = JSONObject()
        .put("sha256", HASH)
        .put("name", "room-map.zip")
        .put("size", 123L)

    private fun validPath() =
        "/api/lbe-maps/$HASH/download?device_id=$DEVICE_ID&revision=$REVISION"

    companion object {
        private val HASH = "a".repeat(64)
        private const val ORIGIN = "https://mdm.example:8443"
        private const val DEVICE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val REVISION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val REVISION_2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private val VERSION = ConfigurationVersion("cccccccc-cccc-4ccc-8ccc-cccccccccccc", REVISION)
        private val VERSION_2 = ConfigurationVersion("cccccccc-cccc-4ccc-8ccc-cccccccccccc", REVISION_2)
        private val DESCRIPTOR = LbeMapDescriptor(HASH, "room-map.zip", 123)
    }
}
