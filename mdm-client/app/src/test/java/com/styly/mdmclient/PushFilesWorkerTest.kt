package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PushFilesWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `partial cleanup metadata defaults to twenty four hours`() {
        assertEquals(24L * 60 * 60 * 1000, PushFilesWorker.PARTIAL_RETENTION_MS)
    }

    @Test
    fun `legacy connection errors remain download failures`() {
        val unavailablePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use {
            it.localPort
        }
        val legacy = command(
            artifactUrl = "http://127.0.0.1:$unavailablePort/artifact.zip",
        ).copy(
            jobId = null,
            artifactId = null,
            revision = 0L,
            artifactEtag = null,
        )

        val execution = PushFilesWorker(
            hasExternalStorageAccess = { true },
            attemptDirectoryProvider = { File(tmp.root, "legacy-io") },
        ).execute(legacy, PushFilesWorker.Callbacks({}, {}, {}))

        assertEquals("fail", execution.result.status)
        assertEquals("download_failed", execution.result.failureCode)
    }

    @Test
    fun `deadline refreshes only for bytes and cannot be revived at expiry`() {
        var now = 0L
        PushDownloadDeadline({ now }, 60_000L).use { deadline ->
            now = 59_999L
            assertEquals(1L, deadline.remaining())
            deadline.receivedBytes()
            now += 59_999L
            assertEquals(1L, deadline.remaining())
            now++
            assertThrows(PushWorkerException::class.java) { deadline.receivedBytes() }
            now = 0L
            assertThrows(PushWorkerException::class.java) { deadline.remaining() }
        }
    }

    @Test
    fun `more than six transient failures can recover inside the idle window`() {
        val archive = zip("content.txt" to "after-seven-retries").readBytes()
        var now = 0L
        RetryServer(archive, transientFailures = 7).use { server ->
            val result = PushFilesWorker(
                { true }, { File(tmp.root, "seven-work") }, { File(tmp.root, "seven-dest") },
                retryDelay = { now += it }, monotonicMillis = { now },
            ).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                PushFilesWorker.Callbacks({}, {}, {}),
            )
            assertEquals("success", result.result.status)
            assertEquals(8, server.requestCount)
            assertTrue(now < 60_000L)
        }
    }

    @Test
    fun `progress keeps a transfer alive beyond sixty seconds and validation is untimed`() {
        val content = java.util.Random(4).let { random ->
            CharArray(100_000) { (' '.code + random.nextInt(90)).toChar() }.concatToString()
        }
        val archive = zip("content.txt" to content).readBytes()
        var now = 0L
        ArtifactServer(archive).use { server ->
            val result = PushFilesWorker(
                { true }, { File(tmp.root, "long-work") }, { File(tmp.root, "long-dest") },
                monotonicMillis = { now },
            ).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { assertTrue(now > 60_000L); now += 600_000L },
                    onValidated = {}, onApplying = {}, onTransferProgress = { now += 10_000L },
                ),
            )
            assertEquals("success", result.result.status)
            assertEquals(content, File(tmp.root, "long-dest/content.txt").readText())
        }
    }

    @Test
    fun `late bytes after idle expiry are not appended to the retained partial`() {
        val content = java.util.Random(5).let { random ->
            CharArray(100_000) { (' '.code + random.nextInt(90)).toChar() }.concatToString()
        }
        val archive = zip("content.txt" to content).readBytes()
        var now = 0L
        var acceptedBytes = 0L
        val work = File(tmp.root, "late-work")
        ArtifactServer(archive).use { server ->
            val result = PushFilesWorker({ true }, { work }, monotonicMillis = { now }).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)).copy(revision = 1L),
                PushFilesWorker.Callbacks({}, {}, {}, onTransferProgress = { acceptedBytes = it; now = 60_000L }),
            )
            assertTrue(result.interrupted)
            assertEquals("download_retry_exhausted", result.interruptionReason)
            assertTrue(acceptedBytes in 1 until archive.size.toLong())
            assertEquals(acceptedBytes, File(work, "artifact.part").length())
        }
    }

    @Test
    fun `revoked transfer lease interrupts and preserves the exact partial without retry`() {
        val archive = zip("content.txt" to "resume-after-lease").readBytes()
        val split = archive.size / 2
        val work = File(tmp.root, "revoked-lease-work")
        val command = command(
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        ).copy(revision = 7L)
        val partial = archive.copyOfRange(0, split)
        seedResume(work, command, partial)
        var now = 0L
        var retryCount = 0

        OneShotServer(
            status = 409,
            headers = "X-Push-Lease-Status: revoked\r\nContent-Length: 0",
        ).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                retryDelay = { delay -> retryCount++; now += delay },
                monotonicMillis = { now },
                noProgressTimeoutMs = 60_000L,
            ).execute(
                command.copy(artifactUrl = server.url),
                PushFilesWorker.Callbacks({}, {}, {}),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("push_lease_revoked", execution.result.failureCode)
            assertTrue(execution.interrupted)
            assertEquals("server_lease_revoked", execution.interruptionReason)
            assertEquals(0, retryCount)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertArrayEquals(partial, File(work, "artifact.part").readBytes())
        }
    }

    @Test
    fun `partial storage open failure is nonretryable`() {
        val archive = zip("content.txt" to "storage-error").readBytes()
        val work = File(tmp.root, "storage-error-work")
        val command = command(
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        ).copy(revision = 7L)
        seedResume(work, command, byteArrayOf())
        val partial = File(work, "artifact.part")
        assertTrue(partial.delete())
        assertTrue(partial.mkdir())
        var now = 0L
        var retryCount = 0

        OneShotServer(
            status = 200,
            headers = "ETag: \"v1\"\r\nContent-Length: ${archive.size}",
            body = archive,
        ).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                retryDelay = { delay -> retryCount++; now += delay },
                monotonicMillis = { now },
            ).execute(
                command.copy(artifactUrl = server.url),
                PushFilesWorker.Callbacks({}, {}, {}),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("storage_write_failed", execution.result.failureCode)
            assertFalse(execution.interrupted)
            assertEquals(0, retryCount)
            assertFalse(partial.exists())
        }
    }

    @Test
    fun `storage failure after resumed bytes preserves partial for manual resume`() {
        val archive = zip("content.txt" to "retain-nearly-complete").readBytes()
        val split = archive.size * 95 / 100
        val work = File(tmp.root, "storage-resume-work")
        val remaining = archive.copyOfRange(split, archive.size)
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            assertTrue(File(work, "metadata.json.tmp").mkdir())
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))
            assertEquals("storage_write_failed", execution.result.failureCode)
            assertTrue(execution.interrupted)
            assertEquals("storage_write_failed", execution.interruptionReason)
            assertArrayEquals(archive, File(work, "artifact.part").readBytes())
        }
    }

    @Test
    fun `real progress extends watchdog and complete body does not wait for socket close`() {
        val archive = zip("content.txt" to "slow but progressing").readBytes()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val release = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${archive.size}\r\nETag: \"v1\"\r\n\r\n").toByteArray())
                output.flush()
                var offset = 0
                while (offset < archive.size) {
                    Thread.sleep(100L)
                    val count = minOf((archive.size + 7) / 8, archive.size - offset)
                    output.write(archive, offset, count)
                    output.flush()
                    offset += count
                }
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        val started = System.nanoTime()
        try {
            val result = PushFilesWorker(
                { true }, { File(tmp.root, "slow-work") }, { File(tmp.root, "slow-dest") },
                noProgressTimeoutMs = 500L,
            ).execute(command(artifactUrl = "http://127.0.0.1:${server.localPort}/artifact.zip",
                artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)).copy(revision = 1L),
                PushFilesWorker.Callbacks({}, {}, {}))
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            assertEquals("success", result.result.status)
            assertTrue("Progressing download took ${elapsed}ms", elapsed in 700L..3_000L)
        } finally {
            release.countDown()
            server.close()
            thread.join(5_000)
        }
    }

    private fun stalledHttpResult(sendHeaders: Boolean): Pair<PushFilesWorker.Execution, Long> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val release = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                if (sendHeaders) {
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 42\r\nETag: \"v1\"\r\n\r\n").toByteArray())
                    socket.getOutputStream().flush()
                }
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { start() }
        val started = System.nanoTime()
        try {
            val result = PushFilesWorker(
                { true }, { File(tmp.root, "stalled-$sendHeaders") }, noProgressTimeoutMs = 250L,
            ).execute(command(artifactUrl = "http://127.0.0.1:${server.localPort}/artifact.zip").copy(revision = 1L),
                PushFilesWorker.Callbacks({}, {}, {}))
            return result to (System.nanoTime() - started) / 1_000_000L
        } finally {
            release.countDown()
            server.close()
            thread.join(5_000)
        }
    }

    @Test
    fun `real stalled headers and stalled body are cancelled at the same deadline`() {
        for (sendHeaders in listOf(false, true)) {
            val (result, elapsed) = stalledHttpResult(sendHeaders)
            assertTrue(result.interrupted)
            assertEquals("download_retry_exhausted", result.interruptionReason)
            assertTrue("Stalled HTTP took ${elapsed}ms", elapsed in 200L..2_000L)
        }
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val archive = File(tmp.root, "${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(archive)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return archive
    }

    private fun command(
        jobId: String? = UUID.randomUUID().toString(),
        artifactId: String? = UUID.randomUUID().toString(),
        artifactUrl: String = "http://server/artifacts/value",
        artifactSize: Long? = 42,
        artifactSha256: String? = "a".repeat(64),
    ) = PushProtocol.Command(
        jobId = jobId,
        attempt = PushProtocol.ATTEMPT_V1,
        artifactId = artifactId,
        artifactUrl = artifactUrl,
        artifactSize = artifactSize,
        artifactSha256 = artifactSha256,
        bundleFilename = "bundle.zip",
        destPath = "/sdcard/STYLY/content",
        deleteExtras = false,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun seedResume(
        work: File,
        command: PushProtocol.Command,
        bytes: ByteArray,
        etag: String = "\"v1\"",
    ) {
        work.mkdirs()
        File(work, "artifact.part").writeBytes(bytes)
        File(work, "metadata.json").writeText(
            JSONObject().apply {
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("revision", command.revision)
                put("artifact_id", command.artifactId)
                put("artifact_url", command.artifactUrl)
                put("artifact_size", command.artifactSize)
                put("artifact_sha256", command.artifactSha256)
                put("artifact_etag", etag)
                put("created_at", 1)
                put("updated_at", 1)
                put("retention_deadline", System.currentTimeMillis() + 60_000)
            }.toString(),
        )
    }

    @Test
    fun `validated resume offset accepts an exact partial and a refreshed locator`() {
        val work = File(tmp.root, "validated-offset-work")
        val command = command(artifactSize = 100).copy(artifactEtag = "\"v1\"")
        seedResume(work, command, ByteArray(40))
        val worker = PushFilesWorker(
            hasExternalStorageAccess = { true },
            attemptDirectoryProvider = { work },
        )

        assertEquals(40L, worker.validatedResumeOffset(command))
        assertEquals(
            40L,
            worker.validatedResumeOffset(command.copy(artifactUrl = "http://new-server/artifact.zip")),
        )
        assertTrue(File(work, "artifact.part").renameTo(File(work, "artifact.zip")))
        assertEquals(0L, worker.validatedResumeOffset(command))
        File(work, "artifact.zip").writeBytes(ByteArray(100))
        assertEquals(100L, worker.validatedResumeOffset(command))
    }

    @Test
    fun `validated resume offset rejects untrusted partial metadata and lengths`() {
        val work = File(tmp.root, "invalid-offset-work")
        val command = command(artifactSize = 100).copy(revision = 7L, artifactEtag = "\"v1\"")
        val worker = PushFilesWorker(
            hasExternalStorageAccess = { true },
            attemptDirectoryProvider = { work },
        )

        fun offsetAfter(change: (JSONObject) -> Unit): Long {
            seedResume(work, command, ByteArray(40))
            val metadataFile = File(work, "metadata.json")
            val metadata = JSONObject(metadataFile.readText())
            change(metadata)
            metadataFile.writeText(metadata.toString())
            return worker.validatedResumeOffset(command)
        }

        assertEquals(0L, offsetAfter { it.put("job_id", UUID.randomUUID().toString()) })
        assertEquals(0L, offsetAfter { it.put("attempt", 2) })
        assertEquals(0L, offsetAfter { it.put("revision", 8L) })
        assertEquals(0L, offsetAfter { it.put("artifact_id", UUID.randomUUID().toString()) })
        assertEquals(0L, offsetAfter { it.put("artifact_size", 101L) })
        assertEquals(0L, offsetAfter { it.put("artifact_sha256", "b".repeat(64)) })
        assertEquals(0L, offsetAfter { it.put("artifact_etag", "\"v2\"") })
        assertEquals(0L, offsetAfter { it.remove("artifact_etag") })
        assertEquals(0L, offsetAfter { it.put("artifact_etag", "W/\"v1\"") })
        assertEquals(0L, offsetAfter { it.put("job_id", JSONObject.NULL) })

        seedResume(work, command.copy(artifactEtag = null), ByteArray(40), etag = "")
        assertEquals(0L, worker.validatedResumeOffset(command.copy(artifactEtag = null)))
        File(work, "metadata.json").writeText("not-json")
        assertEquals(0L, worker.validatedResumeOffset(command))
        File(work, "metadata.json").delete()
        assertEquals(0L, worker.validatedResumeOffset(command))

        seedResume(work, command, ByteArray(101))
        assertEquals(0L, worker.validatedResumeOffset(command))
    }

    private class ArtifactServer(private val content: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/artifact.zip"
        private val thread = Thread({
            server.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                while (reader.readLine()?.isNotEmpty() == true) Unit
                client.getOutputStream().use { output ->
                    output.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Length: ${content.size}\r\n" +
                                "ETag: \"test-artifact\"\r\n" +
                                "Connection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII),
                    )
                    output.write(content)
                    output.flush()
                }
            }
        }, "push-worker-test-http").apply { start() }

        override fun close() {
            server.close()
            thread.join(5_000)
        }
    }

    private class OneShotServer(
        private val status: Int,
        private val headers: String,
        private val body: ByteArray = byteArrayOf(),
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/artifact.zip"
        @Volatile var request = ""
        @Volatile var responseBytesWritten = 0
        private val thread = Thread({
            server.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val lines = buildList {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        add(line)
                    }
                }
                request = lines.joinToString("\n")
                client.getOutputStream().use { output ->
                    output.write(("HTTP/1.1 $status Test\r\n$headers\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                    responseBytesWritten = body.size
                }
            }
        }, "push-worker-resume-test-http").apply { start() }

        override fun close() {
            server.close()
            thread.join(5_000)
        }
    }

    private class RetryServer(
        private val content: ByteArray,
        private val transientFailures: Int,
    ) : AutoCloseable {
        private val server = ServerSocket(0, transientFailures + 1, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/artifact.zip"
        @Volatile var requestCount = 0
        private val thread = Thread({
            repeat(transientFailures + 1) { index ->
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    requestCount++
                    client.getOutputStream().use { output ->
                        if (index < transientFailures) {
                            output.write(
                                "HTTP/1.1 503 Retry\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                        } else {
                            output.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: ${content.size}\r\n" +
                                    "ETag: \"retry-artifact\"\r\n" +
                                    "Connection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.write(content)
                        }
                        output.flush()
                    }
                }
            }
        }, "push-worker-retry-test-http").apply { start() }

        override fun close() {
            server.close()
            thread.join(5_000)
        }
    }

    @Test
    fun `missing all-files access returns a stable permission failure before file work`() {
        val work = File(tmp.root, "permission-denied")
        var callbackInvoked = false
        val execution = PushFilesWorker(
            hasExternalStorageAccess = { false },
            attemptDirectoryProvider = { work },
        ).execute(
            command(),
            PushFilesWorker.Callbacks(
                onTransferComplete = { callbackInvoked = true },
                onValidated = { callbackInvoked = true },
                onApplying = { callbackInvoked = true },
            ),
        )

        assertEquals("fail", execution.result.status)
        assertEquals(
            PushFilesWorker.EXTERNAL_STORAGE_PERMISSION_FAILURE,
            execution.result.failureCode,
        )
        assertEquals(
            PushFilesWorker.EXTERNAL_STORAGE_PERMISSION_DETAIL,
            execution.result.detail,
        )
        assertFalse(callbackInvoked)
        assertFalse(work.exists())
    }

    @Test
    fun `validation starts before verified completion and apply`() {
        val archive = zip("content.txt" to "verified").readBytes()
        val destination = tmp.newFolder("sha-match-destination")
        val work = File(tmp.root, "sha-match-work")
        val callbacks = mutableListOf<String>()

        ArtifactServer(archive).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive).uppercase(),
                ),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { callbacks += "transfer" },
                    onValidated = { callbacks += "validated" },
                    onApplying = { callbacks += "applying" },
                    onValidationStart = { callbacks += "validation_start" },
                ),
            )

            assertEquals("success", execution.result.status)
        }
        assertEquals(listOf("validation_start", "transfer", "validated", "applying"), callbacks)
        assertEquals("verified", File(destination, "content.txt").readText())
    }

    @Test
    fun `validated partial resumes with a strict range and if-match request`() {
        val archive = zip("content.txt" to "resume-proof").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("resume-destination")
        val work = File(tmp.root, "resume-work")
        val remaining = archive.copyOfRange(split, archive.size)
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))
            assertEquals("success", execution.result.status)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertTrue(server.request.contains("If-Match: \"v1\""))
            assertEquals(remaining.size, server.responseBytesWritten)
            assertEquals("resume-proof", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `fresh locator resumes an exact partial after server authority changes`() {
        val archive = zip("content.txt" to "new-authority").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("new-authority-destination")
        val work = File(tmp.root, "new-authority-work")
        val oldCommand = command(
            artifactUrl = "http://old-server/artifact.zip",
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        ).copy(revision = 7, artifactEtag = "\"v1\"")
        seedResume(work, oldCommand, archive.copyOfRange(0, split))
        val remaining = archive.copyOfRange(split, archive.size)

        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = oldCommand.copy(artifactUrl = server.url)
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("success", execution.result.status)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertEquals("new-authority", File(destination, "content.txt").readText())
            assertEquals(
                server.url,
                JSONObject(File(work, "metadata.json").readText()).getString("artifact_url"),
            )
        }
    }

    @Test
    fun `transient server failures use bounded exponential retries`() {
        val archive = zip("content.txt" to "retried").readBytes()
        val destination = tmp.newFolder("retry-destination")
        val work = File(tmp.root, "retry-work")
        val delays = mutableListOf<Long>()

        RetryServer(archive, transientFailures = 3).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
                retryDelay = { delay -> delays += delay },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                PushFilesWorker.Callbacks({}, {}, {}),
            )

            assertEquals("success", execution.result.status)
            assertEquals(4, server.requestCount)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
            assertEquals("retried", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `expired metadata deadline does not discard an exactly authorized partial`() {
        val archive = zip("content.txt" to "coordinator-authorized").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("expired-metadata-destination")
        val work = File(tmp.root, "expired-metadata-work")
        val remaining = archive.copyOfRange(split, archive.size)
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            val metadata = JSONObject(File(work, "metadata.json").readText())
            metadata.put("retention_deadline", System.currentTimeMillis() - 1)
            File(work, "metadata.json").writeText(metadata.toString())

            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("success", execution.result.status)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertEquals("coordinator-authorized", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `retry budget exhaustion retains a partial for later exact range authorization`() {
        val archive = zip("content.txt" to "resumed-after-outage").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("retry-exhausted-destination")
        val work = File(tmp.root, "retry-exhausted-work")
        val delays = mutableListOf<Long>()
        var now = 0L

        RetryServer(archive, transientFailures = 10).use { unavailable ->
            val command = command(
                artifactUrl = unavailable.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
                retryDelay = { delay -> delays += delay; now += delay },
                monotonicMillis = { now },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("download_failed", execution.result.failureCode)
            assertTrue(execution.interrupted)
            assertEquals("download_retry_exhausted", execution.interruptionReason)
            assertEquals(split.toLong(), File(work, "artifact.part").length())
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L, 8_000L, 8_000L, 8_000L, 5_000L), delays)
        }

        val remaining = archive.copyOfRange(split, archive.size)
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { resumed ->
            val resumedCommand = command(
                artifactUrl = resumed.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L)
            // Keep the exact durable identity while refreshing only its locator.
            val original = JSONObject(File(work, "metadata.json").readText())
            resumedCommand.copy(
                jobId = original.getString("job_id"),
                artifactId = original.getString("artifact_id"),
            ).also { exact ->
                val execution = PushFilesWorker(
                    hasExternalStorageAccess = { true },
                    attemptDirectoryProvider = { work },
                    destinationProvider = { destination },
                ).execute(exact, PushFilesWorker.Callbacks({}, {}, {}))
                assertEquals("success", execution.result.status)
            }
            assertTrue(resumed.request.contains("Range: bytes=$split-"))
            assertEquals("resumed-after-outage", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `complete 416 response finalizes an exact local artifact`() {
        val archive = zip("content.txt" to "complete-416").readBytes()
        val destination = tmp.newFolder("complete-416-destination")
        val work = File(tmp.root, "complete-416-work")
        OneShotServer(
            status = 416,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes */${archive.size}\r\nContent-Length: 0",
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive)
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("success", execution.result.status)
            assertTrue(server.request.contains("Range: bytes=${archive.size}-"))
            assertEquals("complete-416", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `inconsistent 416 response rejects a non-complete local artifact`() {
        val archive = zip("content.txt" to "incomplete-416").readBytes()
        val split = archive.size / 2
        val work = File(tmp.root, "incomplete-416-work")
        OneShotServer(
            status = 416,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes */${archive.size}\r\nContent-Length: 0",
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `ETag mismatch rejects a resumed response and discards the partial`() {
        val archive = zip("content.txt" to "etag-mismatch").readBytes()
        val split = archive.size / 2
        val remaining = archive.copyOfRange(split, archive.size)
        val work = File(tmp.root, "etag-mismatch-work")
        OneShotServer(
            status = 206,
            headers = "ETag: \"v2\"\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `verified completed artifact resumes apply without another download`() {
        val archive = zip("content.txt" to "already-complete").readBytes()
        val destination = tmp.newFolder("completed-artifact-destination")
        val work = File(tmp.root, "completed-artifact-work")
        val command = command(
            artifactUrl = "http://127.0.0.1:1/must-not-connect",
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        )
        seedResume(work, command, byteArrayOf())
        File(work, "artifact.part").delete()
        File(work, "artifact.zip").writeBytes(archive)

        val execution = PushFilesWorker(
            hasExternalStorageAccess = { true },
            attemptDirectoryProvider = { work },
            destinationProvider = { destination },
        ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

        assertEquals("success", execution.result.status)
        assertEquals("already-complete", File(destination, "content.txt").readText())
    }

    @Test
    fun `ignored range response replaces rather than appends to the partial`() {
        val archive = zip("content.txt" to "range-ignored").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("ignored-range-destination")
        val work = File(tmp.root, "ignored-range-work")
        OneShotServer(
            status = 200,
            headers = "ETag: \"v1\"\r\nContent-Length: ${archive.size}",
            body = archive,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("success", execution.result.status)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertEquals("range-ignored", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `precondition failure rejects and discards the partial`() {
        val archive = zip("content.txt" to "old").readBytes()
        val split = archive.size / 2
        val work = File(tmp.root, "precondition-work")
        OneShotServer(status = 412, headers = "ETag: \"v2\"\r\nContent-Length: 0").use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `malformed content range rejects and discards the partial`() {
        val archive = zip("content.txt" to "range-invalid").readBytes()
        val split = archive.size / 2
        val remaining = archive.copyOfRange(split, archive.size)
        val work = File(tmp.root, "malformed-range-work")
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes invalid\r\nContent-Length: ${remaining.size}",
            body = remaining,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `content range ending at or beyond total is rejected before append`() {
        val archive = zip("content.txt" to "range-end-overflow").readBytes()
        val split = archive.size / 2
        val remaining = archive.copyOfRange(split, archive.size)
        val malformedBody = remaining + byteArrayOf(0)
        val work = File(tmp.root, "range-end-overflow-work")
        val progress = mutableListOf<Long>()
        val retryDelays = mutableListOf<Long>()
        OneShotServer(
            status = 206,
            headers = "ETag: \"v1\"\r\nContent-Range: bytes $split-${archive.size}/${archive.size}\r\nContent-Length: ${malformedBody.size}",
            body = malformedBody,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                retryDelay = { index -> retryDelays += index },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}, { received -> progress += received }))

            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
            assertTrue(progress.isEmpty())
            assertTrue(retryDelays.isEmpty())
        }
    }

    @Test
    fun `non identity content encoding is rejected before downloading`() {
        val archive = zip("content.txt" to "encoded").readBytes()
        val work = File(tmp.root, "encoded-response-work")
        OneShotServer(
            status = 200,
            headers = "ETag: \"v1\"\r\nContent-Encoding: gzip\r\nContent-Length: ${archive.size}",
            body = archive,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `initial job response without a strong etag is rejected`() {
        val archive = zip("content.txt" to "missing-etag").readBytes()
        val work = File(tmp.root, "missing-etag-work")
        OneShotServer(
            status = 200,
            headers = "Content-Length: ${archive.size}",
            body = archive,
        ).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, {}))

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `artifact download is not limited by the extracted byte ceiling`() {
        val archive = zip("content.txt" to "larger-than-extracted-limit").readBytes()
        val callbacks = mutableListOf<String>()

        ArtifactServer(archive).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { File(tmp.root, "artifact-over-extracted-limit") },
                destinationProvider = { throw AssertionError("validation must fail before apply") },
                maxExtractedBytes = 1,
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { callbacks += "transfer" },
                    onValidated = { callbacks += "validated" },
                    onApplying = { callbacks += "applying" },
                    onValidationStart = { callbacks += "validation_start" },
                ),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("validation_failed", execution.result.failureCode)
        }
        assertEquals(listOf("validation_start", "transfer"), callbacks)
    }

    @Test
    fun `legacy artifact without SHA remains compatible`() {
        val archive = zip("content.txt" to "legacy").readBytes()
        val destination = tmp.newFolder("legacy-no-sha-destination")

        ArtifactServer(archive).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { File(tmp.root, "legacy-no-sha-work") },
                destinationProvider = { destination },
            ).execute(
                command(
                    jobId = null,
                    artifactId = null,
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = null,
                ),
                PushFilesWorker.Callbacks({}, {}, {}),
            )

            assertEquals("success", execution.result.status)
        }
        assertEquals("legacy", File(destination, "content.txt").readText())
    }

    @Test
    fun `mismatched artifact SHA stops before callbacks and destination changes`() {
        val archive = zip("content.txt" to "untrusted").readBytes()
        val destination = tmp.newFolder("sha-mismatch-destination")
        val sentinel = File(destination, "existing.txt").apply { writeText("unchanged") }
        val work = File(tmp.root, "sha-mismatch-work")
        var callbackInvoked = false

        ArtifactServer(archive).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { work },
                destinationProvider = { destination },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = "0".repeat(64),
                ),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { callbackInvoked = true },
                    onValidated = { callbackInvoked = true },
                    onApplying = { callbackInvoked = true },
                ),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("artifact_identity_mismatch", execution.result.failureCode)
        }
        assertFalse(callbackInvoked)
        assertEquals("unchanged", sentinel.readText())
        assertFalse(File(work, "artifact.part").exists())
        assertFalse(File(work, "artifact.zip").exists())
    }

    @Test
    fun `invalid destination is rejected after validation without applying callback`() {
        val archive = zip("content.txt" to "verified").readBytes()
        val destination = tmp.newFolder("invalid-destination")
        val sentinel = File(destination, "existing.txt").apply { writeText("unchanged") }
        val callbacks = mutableListOf<String>()

        ArtifactServer(archive).use { server ->
            val execution = PushFilesWorker(
                hasExternalStorageAccess = { true },
                attemptDirectoryProvider = { File(tmp.root, "invalid-destination-work") },
                destinationProvider = {
                    throw PushWorkerException("invalid_destination", "destination rejected")
                },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { callbacks += "transfer" },
                    onValidated = { callbacks += "validated" },
                    onApplying = { callbacks += "applying" },
                ),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("invalid_destination", execution.result.failureCode)
        }
        assertEquals(listOf("transfer", "validated"), callbacks)
        assertEquals("unchanged", sentinel.readText())
    }

    @Test
    fun `basic validation extracts ordinary files`() {
        val staging = File(tmp.root, "staging")
        PushFilesWorker().validateAndExtract(
            zip("a/b.txt" to "payload"),
            staging,
        )
        assertEquals("payload", File(staging, "a/b.txt").readText())
    }

    @Test
    fun `basic validation rejects zip slip`() {
        val staging = File(tmp.root, "slip")
        assertThrows(PushWorkerException::class.java) {
            PushFilesWorker().validateAndExtract(
                zip("../outside.txt" to "payload"),
                staging,
            )
        }
        assertEquals(false, File(tmp.root, "outside.txt").exists())
    }

    @Test
    fun `basic validation rejects file directory conflicts`() {
        val staging = File(tmp.root, "conflict")
        assertThrows(PushWorkerException::class.java) {
            PushFilesWorker().validateAndExtract(
                zip("a" to "file", "a/b.txt" to "payload"),
                staging,
            )
        }
    }

    @Test
    fun `destination validation rejects a symbolic link component`() {
        val root = tmp.newFolder("shared")
        val real = File(root, "real").apply { mkdirs() }
        val link = File(root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())

        assertThrows(PushWorkerException::class.java) {
            PushFilesWorker().validateDestinationAgainstRoot(
                "${root.absolutePath}/link/content",
                root,
            )
        }
    }

    @Test
    fun `destination validation accepts an ordinary shared storage child`() {
        val root = tmp.newFolder("ordinary-shared")
        val target = PushFilesWorker().validateDestinationAgainstRoot(
            "/sdcard/safe/content",
            root,
        )
        assertEquals(File(root, "safe/content").canonicalFile, target)
    }
}
