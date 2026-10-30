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
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PushFilesWorkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `connection errors interrupt resumable download at its idle deadline`() {
        val unavailablePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use {
            it.localPort
        }
        val job = command(
            artifactUrl = "http://127.0.0.1:$unavailablePort/artifact.zip",
        )

        val execution = PushFilesWorker(
            hasExternalStorageAccess = { true },
            attemptDirectoryProvider = { File(tmp.root, "job-io") },
            noProgressTimeoutMs = 100L,
        ).execute(job, PushFilesWorker.Callbacks({}, {}, beforeApply = { true }))

        assertEquals("download_interrupted", execution.interruptedOutcome().reason)
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
        retryServer(archive, failures = 7).use { server ->
            val result = PushFilesWorker(
                { true }, { File(tmp.root, "seven-work") }, { File(tmp.root, "seven-dest") },
                retryDelay = { now += it }, monotonicMillis = { now },
            ).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                PushFilesWorker.Callbacks({}, {}, beforeApply = { true }),
            )
            assertEquals("success", result.completedResult().status)
            assertEquals(8, server.requests.size)
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
        var observedLength = 0L
        val work = File(tmp.root, "long-work")
        artifactServer(archive).use { server ->
            val result = PushFilesWorker(
                { true }, { work }, { File(tmp.root, "long-dest") },
                monotonicMillis = {
                    val length = File(work, "artifact.part").length()
                    if (length != observedLength) {
                        observedLength = length
                        now += 10_000L
                    }
                    now
                },
            ).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { assertTrue(now > 60_000L); now += 600_000L },
                    onValidated = {}, beforeApply = { true },
                ),
            )
            assertEquals("success", result.completedResult().status)
            assertEquals(content, File(tmp.root, "long-dest/content.txt").readText())
        }
    }

    @Test
    fun `late bytes after idle expiry are not appended to the retained partial`() {
        val content = java.util.Random(5).let { random ->
            CharArray(100_000) { (' '.code + random.nextInt(90)).toChar() }.concatToString()
        }
        val archive = zip("content.txt" to content).readBytes()
        var acceptedBytes = 0L
        var observedWrite = false
        val work = File(tmp.root, "late-work")
        artifactServer(archive).use { server ->
            val result = PushFilesWorker({ true }, { work }, monotonicMillis = {
                if (observedWrite) 60_000L else {
                    acceptedBytes = File(work, "artifact.part").length()
                    observedWrite = acceptedBytes > 0L
                    0L
                }
            }).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)).copy(revision = 1L),
                PushFilesWorker.Callbacks({}, {}, beforeApply = { true }),
            )
            assertEquals("download_interrupted", result.interruptedOutcome().reason)
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
            val execution = worker(
                work,
                retryDelay = { delay -> retryCount++; now += delay },
                monotonicMillis = { now },
            ).execute(
                command.copy(artifactUrl = server.url),
                workerCallbacks(),
            )

            val outcome = execution.interruptedOutcome()
            assertEquals("server_lease_revoked", outcome.reason)
            assertEquals(0, retryCount)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertArrayEquals(partial, File(work, "artifact.part").readBytes())
        }
    }

    @Test
    fun `unexpected status on a resumed request interrupts and preserves the partial without retry`() {
        val archive = zip("content.txt" to "unexpected-resume-status").readBytes()
        val split = archive.size / 2
        val work = File(tmp.root, "unexpected-resume-status-work")
        val command = command(
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        ).copy(revision = 7L)
        val partial = archive.copyOfRange(0, split)
        seedResume(work, command, partial)
        var retryCount = 0

        // No ETag: the status must be classified before any validator check.
        OneShotServer(status = 403, headers = "Content-Length: 0").use { server ->
            val execution = worker(
                work,
                retryDelay = { retryCount++ },
            ).execute(
                command.copy(artifactUrl = server.url),
                workerCallbacks(),
            )

            val outcome = execution.interruptedOutcome()
            assertEquals("download_interrupted", outcome.reason)
            assertEquals("artifact download returned unexpected HTTP 403", outcome.detail)
            assertEquals(0, retryCount)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertArrayEquals(partial, File(work, "artifact.part").readBytes())
        }
    }

    @Test
    fun `unexpected initial status is a download failure, not an identity mismatch`() {
        val archive = zip("content.txt" to "unexpected-initial-status").readBytes()
        val work = File(tmp.root, "unexpected-initial-status-work")
        var retryCount = 0

        OneShotServer(status = 401, headers = "Content-Length: 0").use { server ->
            val execution = worker(
                work,
                retryDelay = { retryCount++ },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ).copy(revision = 7L),
                workerCallbacks(),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("download_failed", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
            assertEquals(0, retryCount)
        }
    }

    @Test
    fun `retry backoff starts over after a retry received artifact bytes`() {
        val archive = zip("content.txt" to "backoff-reset").readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("backoff-reset-destination")
        val work = File(tmp.root, "backoff-reset-work")
        val delays = mutableListOf<Long>()
        val unavailable = response("HTTP/1.1 503 Retry\r\nContent-Length: 0\r\n")
        val remaining = archive.copyOfRange(split, archive.size)

        TestHttpServer(
            listOf(
                unavailable,
                unavailable,
                unavailable,
                unavailable,
                // Delivers half of the body, then the connection drops.
                response(
                    "HTTP/1.1 200 OK\r\nETag: \"v1\"\r\nContent-Length: ${archive.size}\r\n",
                    archive.copyOfRange(0, split),
                ),
                unavailable,
                response(
                    "HTTP/1.1 206 Partial Content\r\nETag: \"v1\"\r\n" +
                        "Content-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\n" +
                        "Content-Length: ${remaining.size}\r\n",
                    remaining,
                ),
            ),
        ).use { server ->
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
                PushFilesWorker.Callbacks({}, {}, beforeApply = { true }),
            )

            assertEquals("success", execution.completedResult().status)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 1_000L, 2_000L), delays)
            assertEquals("backoff-reset", File(destination, "content.txt").readText())
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
                PushFilesWorker.Callbacks({}, {}, beforeApply = { true }),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("storage_write_failed", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
            assertEquals(0, retryCount)
            assertFalse(partial.exists())
        }
    }

    @Test
    fun `resumed bytes do not rewrite unchanged metadata`() {
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
                destinationProvider = { tmp.newFolder("storage-resume-destination") },
            ).execute(command, PushFilesWorker.Callbacks({}, {}, beforeApply = { true }))
            assertEquals("success", execution.completedResult().status)
            assertArrayEquals(archive, File(work, "artifact.zip").readBytes())
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
            val result = worker(
                File(tmp.root, "slow-work"), { File(tmp.root, "slow-dest") },
                noProgressTimeoutMs = 500L,
            ).execute(command(artifactUrl = "http://127.0.0.1:${server.localPort}/artifact.zip",
                artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)).copy(revision = 1L),
                workerCallbacks())
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            assertEquals("success", result.completedResult().status)
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
            val result = worker(
                File(tmp.root, "stalled-$sendHeaders"), noProgressTimeoutMs = 250L,
            ).execute(command(artifactUrl = "http://127.0.0.1:${server.localPort}/artifact.zip").copy(revision = 1L),
                workerCallbacks())
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
            assertEquals("download_interrupted", result.interruptedOutcome().reason)
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

    private fun PushFilesWorker.Execution.completedResult(): PushProtocol.Result =
        (outcome as? PushFilesWorker.Outcome.Completed)?.result
            ?: error("Expected a completed worker outcome, got $outcome")

    private fun PushFilesWorker.Execution.interruptedOutcome(): PushFilesWorker.Outcome.Interrupted =
        outcome as? PushFilesWorker.Outcome.Interrupted
            ?: error("Expected an interrupted worker outcome, got $outcome")

    private fun command(
        artifactUrl: String = "http://server/artifacts/value",
        artifactSize: Long = 42,
        artifactSha256: String = "a".repeat(64),
    ) = pushTestCommand(
        artifactUrl = artifactUrl,
        artifactSize = artifactSize,
        artifactSha256 = artifactSha256,
        revision = 7L,
        artifactEtag = "\"v1\"",
    )

    private fun worker(
        work: File,
        destinationProvider: ((String) -> File)? = null,
        retryDelay: (Long) -> Unit = { Thread.sleep(it) },
        monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        noProgressTimeoutMs: Long = PushFilesWorker.DOWNLOAD_NO_PROGRESS_TIMEOUT_MS,
        digestFactory: (File) -> PartialFileDigest = ::PartialFileDigest,
    ) = PushFilesWorker(
        hasExternalStorageAccess = { true },
        attemptDirectoryProvider = { work },
        destinationProvider = destinationProvider,
        retryDelay = retryDelay,
        monotonicMillis = monotonicMillis,
        noProgressTimeoutMs = noProgressTimeoutMs,
        digestFactory = digestFactory,
    )

    private fun workerCallbacks(
        events: MutableList<String>? = null,
        onTransferComplete: (Long) -> Unit = { events?.add("transfer") },
        onValidated: () -> Unit = { events?.add("validated") },
        onValidationStart: () -> Unit = { events?.add("validation_start") },
        beforeApply: () -> Boolean = { events?.add("before_apply"); true },
    ) = PushFilesWorker.Callbacks(
        onTransferComplete = onTransferComplete,
        onValidated = onValidated,
        onValidationStart = onValidationStart,
        beforeApply = beforeApply,
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
                put("artifact_size", command.artifactSize)
                put("artifact_sha256", command.artifactSha256)
                put("artifact_etag", etag)
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

    private fun artifactServer(content: ByteArray) = OneShotServer(
        status = 200,
        headers = "Content-Length: ${content.size}\r\nETag: \"v1\"",
        body = content,
    )

    private fun OneShotServer(
        status: Int,
        headers: String,
        body: ByteArray = byteArrayOf(),
    ) = TestHttpServer(
        listOf(response("HTTP/1.1 $status Test\r\n$headers", body)),
    )

    private fun retryServer(content: ByteArray, failures: Int) = TestHttpServer(
        buildList {
            repeat(failures) {
                add(response("HTTP/1.1 503 Retry\r\nContent-Length: 0"))
            }
            add(
                response(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nETag: \"v1\"",
                    content,
                ),
            )
        },
    )

    private fun response(head: String, body: ByteArray = byteArrayOf()) =
        head.trimEnd('\r', '\n').toByteArray(Charsets.US_ASCII) to body

    private class TestHttpServer(
        private val responses: List<Pair<ByteArray, ByteArray>>,
    ) : AutoCloseable {
        private val server = ServerSocket(0, responses.size, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/artifact.zip"
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val request: String get() = requests.firstOrNull().orEmpty()
        @Volatile var responseBytesWritten = 0
            private set

        private val thread = Thread({
            try {
                responses.forEach { (head, body) ->
                    server.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                        requests += buildList {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                add(line)
                            }
                        }.joinToString("\n")
                        client.getOutputStream().use { output ->
                            output.write(head)
                            output.write("\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            output.write(body)
                            output.flush()
                            responseBytesWritten += body.size
                        }
                    }
                }
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }, "push-worker-test-http").apply { start() }

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
                beforeApply = { callbackInvoked = true; true },
            ),
        )

        assertEquals("fail", execution.completedResult().status)
        assertEquals(
            PushFilesWorker.EXTERNAL_STORAGE_PERMISSION_FAILURE,
            execution.completedResult().failureCode,
        )
        assertEquals(
            PushFilesWorker.EXTERNAL_STORAGE_PERMISSION_DETAIL,
            execution.completedResult().detail,
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

        artifactServer(archive).use { server ->
            val execution = worker(work, { destination }).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive).uppercase(),
                ),
                workerCallbacks(callbacks),
            )

            assertEquals("success", execution.completedResult().status)
        }
        assertEquals(listOf("validation_start", "transfer", "validated", "before_apply"), callbacks)
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
            val execution = worker(work, { destination }).execute(command, workerCallbacks())
            assertEquals("success", execution.completedResult().status)
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
            val execution = worker(work, { destination }).execute(command, workerCallbacks())

            assertEquals("success", execution.completedResult().status)
            assertTrue(server.request.contains("Range: bytes=$split-"))
            assertEquals("new-authority", File(destination, "content.txt").readText())
            // The locator always comes from the command, so metadata never stores it.
            assertFalse(JSONObject(File(work, "metadata.json").readText()).has("artifact_url"))
        }
    }

    @Test
    fun `transient server failures use bounded exponential retries`() {
        val archive = zip("content.txt" to "retried").readBytes()
        val destination = tmp.newFolder("retry-destination")
        val work = File(tmp.root, "retry-work")
        val delays = mutableListOf<Long>()

        retryServer(archive, failures = 3).use { server ->
            val execution = worker(
                work,
                { destination },
                retryDelay = { delay -> delays += delay },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                workerCallbacks(),
            )

            assertEquals("success", execution.completedResult().status)
            assertEquals(4, server.requests.size)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
            assertEquals("retried", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `in-execution range retry keeps the incremental digest without rereading`() {
        val content = java.util.Random(6).let { random ->
            CharArray(100_000) { (' '.code + random.nextInt(90)).toChar() }.concatToString()
        }
        val archive = zip("content.txt" to content).readBytes()
        val split = archive.size / 2
        val destination = tmp.newFolder("digest-retry-destination")
        val digests = mutableListOf<PartialFileDigest>()

        TestHttpServer(
            listOf(
                response("HTTP/1.1 200 OK\r\nETag: \"v1\"\r\nContent-Length: ${archive.size}", archive.copyOfRange(0, split)),
                response(
                    "HTTP/1.1 206 Partial Content\r\nETag: \"v1\"\r\n" +
                        "Content-Range: bytes $split-${archive.lastIndex}/${archive.size}\r\n" +
                        "Content-Length: ${archive.size - split}",
                    archive.copyOfRange(split, archive.size),
                ),
            ),
        ).use { server ->
            val execution = worker(
                File(tmp.root, "digest-retry-work"),
                { destination },
                retryDelay = {},
                digestFactory = { file -> PartialFileDigest(file).also { digests += it } },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                workerCallbacks(),
            )

            assertEquals("success", execution.completedResult().status)
            assertTrue(server.requests[1].contains("Range: bytes=$split-"))
            assertEquals(archive.size, server.responseBytesWritten)
            assertEquals(1, digests.size)
            assertEquals(0L, digests.single().rehashedBytes)
            assertEquals(content, File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `metadata written with earlier locator and timestamp fields still resumes`() {
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
            metadata.put("artifact_url", "http://old-server/artifact.zip")
            metadata.put("created_at", 1L)
            metadata.put("updated_at", 1L)
            metadata.put("retention_deadline", System.currentTimeMillis() - 1)
            File(work, "metadata.json").writeText(metadata.toString())

            val execution = worker(work, { destination }).execute(command, workerCallbacks())

            assertEquals("success", execution.completedResult().status)
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

        retryServer(archive, failures = 10).use { unavailable ->
            val command = command(
                artifactUrl = unavailable.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = worker(
                work,
                { destination },
                retryDelay = { delay -> delays += delay; now += delay },
                monotonicMillis = { now },
            ).execute(command, workerCallbacks())

            val interrupted = execution.interruptedOutcome()
            assertEquals("download_interrupted", interrupted.reason)
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
                val execution = worker(work, { destination }).execute(exact, workerCallbacks())
                assertEquals("success", execution.completedResult().status)
            }
            assertTrue(resumed.request.contains("Range: bytes=$split-"))
            assertEquals("resumed-after-outage", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `complete metadata bound partial validates and applies without HTTP`() {
        val archive = zip("content.txt" to "complete-local").readBytes()
        val destination = tmp.newFolder("complete-local-destination")
        val work = File(tmp.root, "complete-local-work")
        val callbacks = mutableListOf<String>()
        val digests = mutableListOf<PartialFileDigest>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val command = command(
                artifactUrl = "http://127.0.0.1:${server.localPort}/artifact.zip",
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command, archive)
            val execution = worker(
                work,
                { destination },
                noProgressTimeoutMs = 250L,
                digestFactory = { file -> PartialFileDigest(file).also { digests += it } },
            ).execute(command, workerCallbacks(callbacks))

            assertEquals("success", execution.completedResult().status)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
            server.soTimeout = 100
            assertThrows(SocketTimeoutException::class.java) { server.accept().use {} }
            assertEquals(listOf("validation_start", "transfer", "validated", "before_apply"), callbacks)
            assertEquals(archive.size.toLong(), digests.single().rehashedBytes)
            assertFalse(File(work, "artifact.part").exists())
            assertArrayEquals(archive, File(work, "artifact.zip").readBytes())
            assertEquals("complete-local", File(destination, "content.txt").readText())
        }
    }

    @Test
    fun `same size corrupt complete partial fails SHA before extraction without HTTP`() {
        val archive = zip("content.txt" to "complete-corrupt").readBytes()
        val work = File(tmp.root, "complete-corrupt-work")
        val callbacks = mutableListOf<String>()
        val command = command(
            artifactSize = archive.size.toLong(),
            artifactSha256 = sha256(archive),
        )
        seedResume(work, command, archive.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        val execution = worker(
            work,
            monotonicMillis = { error("A complete partial must not start an HTTP deadline") },
        ).execute(command, workerCallbacks(callbacks))

        assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
        assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
        assertEquals(listOf("validation_start"), callbacks)
        assertFalse(File(work, "artifact.part").exists())
        assertFalse(File(work, "metadata.json").exists())
        assertFalse(File(work, "artifact.zip").exists())
        assertFalse(File(work, "staging").exists())
    }

    @Test
    fun `complete saved bytes at idle expiry validate without retry or Resume`() {
        val archive = zip("content.txt" to "complete-at-expiry").readBytes()
        for (corrupt in listOf(false, true)) {
            val work = File(tmp.root, "complete-at-expiry-work-$corrupt")
            val destination = tmp.newFolder("complete-at-expiry-destination-$corrupt")
            val callbacks = mutableListOf<String>()
            val delays = mutableListOf<Long>()
            val body = archive.clone().apply {
                if (corrupt) this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            OneShotServer(200, "ETag: \"v1\"\r\nContent-Length: ${body.size}", body).use { server ->
                val command = command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                )
                val execution = worker(
                    work, { destination },
                    monotonicMillis = {
                        if (File(work, "artifact.part").length() == archive.size.toLong()) 60_000L else 0L
                    },
                    retryDelay = { delays += it },
                ).execute(command, workerCallbacks(callbacks))

                assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
                assertTrue(delays.isEmpty())
                assertFalse(File(work, "artifact.part").exists())
                if (corrupt) {
                    assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
                    assertEquals(listOf("validation_start"), callbacks)
                    assertFalse(File(work, "metadata.json").exists())
                    assertFalse(File(work, "artifact.zip").exists())
                    assertFalse(File(destination, "content.txt").exists())
                } else {
                    assertEquals("success", execution.completedResult().status)
                    assertEquals(listOf("validation_start", "transfer", "validated", "before_apply"), callbacks)
                    assertEquals("complete-at-expiry", File(destination, "content.txt").readText())
                }
            }
        }
    }

    @Test
    fun `incomplete saved bytes at idle expiry remain interrupted`() {
        val archive = zip("content.txt" to "incomplete-at-expiry").readBytes()
        val prefix = archive.copyOfRange(0, archive.size / 2)
        val work = File(tmp.root, "incomplete-at-expiry-work")
        val callbacks = mutableListOf<String>()
        val delays = mutableListOf<Long>()
        OneShotServer(200, "ETag: \"v1\"\r\nContent-Length: ${archive.size}", prefix).use { server ->
            val execution = worker(
                work, { tmp.newFolder("incomplete-at-expiry-destination") },
                monotonicMillis = { if (File(work, "artifact.part").length() > 0L) 60_000L else 0L },
                retryDelay = { delays += it },
            ).execute(
                command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                workerCallbacks(callbacks),
            )
            assertEquals("download_interrupted", execution.interruptedOutcome().reason)
            assertTrue(callbacks.isEmpty())
            assertTrue(delays.isEmpty())
            assertArrayEquals(prefix, File(work, "artifact.part").readBytes())
            assertFalse(File(work, "artifact.zip").exists())
        }
    }

    @Test
    fun `complete bytes after framing IO failure validate without retry or backoff`() {
        val archive = zip("content.txt" to "complete-before-framing-error").readBytes()
        val work = File(tmp.root, "complete-framing-work")
        val destination = tmp.newFolder("complete-framing-destination")
        val delays = mutableListOf<Long>()
        val callbacks = mutableListOf<String>()
        for (corrupt in listOf(false, true)) {
            val body = archive.clone().apply {
                if (corrupt) this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            val framed = (body.size.toString(16) + "\r\n").toByteArray() + body + "\r\ninvalid-chunk\r\n".toByteArray()
            OneShotServer(200, "ETag: \"v1\"\r\nTransfer-Encoding: chunked", framed).use { server ->
                val execution = worker(work, { destination }, retryDelay = { delays += it }).execute(
                    command(artifactUrl = server.url, artifactSize = archive.size.toLong(), artifactSha256 = sha256(archive)),
                    workerCallbacks(callbacks),
                )

                assertTrue(delays.isEmpty())
                assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
                if (corrupt) {
                    assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
                    assertEquals(listOf("validation_start"), callbacks)
                    assertFalse(File(work, "artifact.part").exists())
                    assertFalse(File(work, "metadata.json").exists())
                    assertEquals("complete-before-framing-error", File(destination, "content.txt").readText())
                } else {
                    assertEquals("success", execution.completedResult().status)
                    assertEquals(listOf("validation_start", "transfer", "validated", "before_apply"), callbacks)
                }
                callbacks.clear()
            }
        }
    }

    @Test
    fun `complete partial with stale metadata is redownloaded rather than reused`() {
        val archive = zip("content.txt" to "fresh-assignment").readBytes()
        val work = File(tmp.root, "complete-stale-work")
        val destination = tmp.newFolder("complete-stale-destination")
        artifactServer(archive).use { server ->
            val command = command(
                artifactUrl = server.url,
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            )
            seedResume(work, command.copy(revision = command.revision + 1), archive)
            val execution = worker(work, { destination }).execute(command, workerCallbacks())

            assertEquals("success", execution.completedResult().status)
            assertTrue(server.request.contains("GET /artifact.zip"))
            assertFalse(server.request.contains("Range:"))
            assertEquals("fresh-assignment", File(destination, "content.txt").readText())
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
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = worker(work).execute(command, workerCallbacks())

            assertEquals("fail", execution.completedResult().status)
            assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
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

        val execution = worker(work, { destination }).execute(command, workerCallbacks())

        assertEquals("success", execution.completedResult().status)
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
            val execution = worker(work, { destination }).execute(command, workerCallbacks())

            assertEquals("success", execution.completedResult().status)
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
            ).copy(revision = 7L)
            seedResume(work, command, archive.copyOfRange(0, split))
            val execution = worker(work).execute(command, workerCallbacks())

            assertEquals("fail", execution.completedResult().status)
            assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
            assertFalse(File(work, "artifact.part").exists())
            // A new worker after an unsaved terminal result must not report these bytes.
            assertEquals(0L, PushFilesWorker({ true }, { work }).validatedResumeOffset(command))
        }
    }

    @Test
    fun `unavailable bytes wait for terminal cleanup while revoked lease remains interrupted`() {
        val archive = zip("content.txt" to "retained-prefix").readBytes()
        val prefix = archive.copyOfRange(0, archive.size / 2)
        for (status in listOf(404, 410, 409)) {
            val work = File(tmp.root, "retained-$status")
            val headers = "Content-Length: 0" +
                if (status == 409) "\r\nX-Push-Lease-Status: revoked" else ""
            OneShotServer(status, headers).use { server ->
                val command = command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                )
                seedResume(work, command, prefix)
                val worker = PushFilesWorker({ true }, { work })
                val execution = worker.execute(command, PushFilesWorker.Callbacks({}, {}, beforeApply = { true }))

                assertArrayEquals(prefix, File(work, "artifact.part").readBytes())
                assertEquals(prefix.size.toLong(), worker.validatedResumeOffset(command))
                if (status == 409) {
                    val interrupted = execution.interruptedOutcome()
                    assertEquals("server_lease_revoked", interrupted.reason)
                } else {
                    assertEquals("fail", execution.completedResult().status)
                    assertEquals("artifact_unavailable", execution.completedResult().failureCode)
                    assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
                    worker.cleanup(execution.workDirectory)
                    assertFalse(work.exists())
                    assertEquals(0L, worker.validatedResumeOffset(command))
                }
            }
        }
    }

    @Test
    fun `invalid resume responses retain the exact prefix and require manual resume`() {
        val archive = zip("content.txt" to "invalid-response-headers").readBytes()
        val split = archive.size / 2
        val prefix = archive.copyOfRange(0, split)
        val remaining = archive.copyOfRange(split, archive.size)
        val etag = "ETag: \"v1\""
        val range = "Content-Range: bytes $split-${archive.lastIndex}/${archive.size}"
        val length = "Content-Length: ${remaining.size}"
        data class Case(val name: String, val status: Int, val headers: String, val body: ByteArray)
        fun invalid(name: String, status: Int, headers: String, body: ByteArray = remaining) =
            Case(name, status, headers, body)
        val cases = listOf(
            invalid("missing ETag", 206, "$range\r\n$length"),
            invalid("weak ETag", 206, "ETag: W/\"v1\"\r\n$range\r\n$length"),
            invalid("encoded body", 206, "$etag\r\n$range\r\n$length\r\nContent-Encoding: gzip"),
            invalid("missing length", 206, "$etag\r\n$range"),
            invalid("wrong length", 206, "$etag\r\n$range\r\nContent-Length: ${remaining.size + 1}"),
            invalid("wrong range start", 206, "$etag\r\nContent-Range: bytes 0-${remaining.lastIndex}/${archive.size}\r\n$length"),
            invalid("wrong range total", 206, "$etag\r\nContent-Range: bytes $split-${archive.lastIndex}/${archive.size + 1}\r\n$length"),
            invalid("range ignored", 200, "$etag\r\n$length"),
            invalid("malformed range", 206, "$etag\r\nContent-Range: bytes invalid\r\n$length"),
            invalid(
                "range ends at total",
                206,
                "$etag\r\nContent-Range: bytes $split-${archive.size}/${archive.size}\r\nContent-Length: ${remaining.size + 1}",
                remaining + byteArrayOf(0),
            ),
            invalid(
                "416 for incomplete prefix",
                416,
                "$etag\r\nContent-Range: bytes */${archive.size}\r\nContent-Length: 0",
                byteArrayOf(),
            ),
            invalid("malformed 416 range", 416, "$etag\r\nContent-Range: bytes invalid\r\nContent-Length: 0", byteArrayOf()),
        )
        cases.forEach { case ->
            val work = File(tmp.root, "invalid-resume-${case.name.replace(' ', '-')}")
            val command = command(
                artifactSize = archive.size.toLong(),
                artifactSha256 = sha256(archive),
            ).copy(revision = 7L, artifactEtag = "\"v1\"")
            seedResume(work, command, prefix)
            val retryDelays = mutableListOf<Long>()
            OneShotServer(status = case.status, headers = case.headers, body = case.body).use { server ->
                val execution = PushFilesWorker(
                    hasExternalStorageAccess = { true },
                    attemptDirectoryProvider = { work },
                    retryDelay = { retryDelays += it },
                ).execute(
                    command.copy(artifactUrl = server.url),
                    PushFilesWorker.Callbacks({}, {}, beforeApply = { true }),
                )

                val interrupted = execution.interruptedOutcome()
                assertEquals(case.name, "download_interrupted", interrupted.reason)
                assertArrayEquals(case.name, prefix, File(work, "artifact.part").readBytes())
                assertTrue(case.name, File(work, "metadata.json").isFile)
                assertTrue(case.name, retryDelays.isEmpty())
                assertTrue(case.name, server.request.contains("Range: bytes=$split-"))
            }
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
            val execution = worker(work).execute(command, workerCallbacks())

            assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
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
            val execution = worker(work).execute(command, workerCallbacks())

            assertEquals("fail", execution.completedResult().status)
            assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
            assertFalse(File(work, "artifact.part").exists())
        }
    }

    @Test
    fun `artifact download is not limited by the extracted byte ceiling`() {
        val archive = zip("content.txt" to "larger-than-extracted-limit").readBytes()
        val callbacks = mutableListOf<String>()

        artifactServer(archive).use { server ->
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
                    beforeApply = { callbacks += "apply_checkpoint"; true },
                    onValidationStart = { callbacks += "validation_start" },
                ),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("validation_failed", execution.completedResult().failureCode)
        }
        assertEquals(listOf("validation_start", "transfer"), callbacks)
    }

    @Test
    fun `failed applying checkpoint prevents destination mutation`() {
        val archive = zip("content.txt" to "verified-but-not-applied").readBytes()
        val destination = tmp.newFolder("apply-checkpoint-destination")
        val sentinel = File(destination, "existing.txt").apply { writeText("unchanged") }
        val checkpointCalls = mutableListOf<String>()

        artifactServer(archive).use { server ->
            val execution = worker(
                File(tmp.root, "apply-checkpoint-work"),
                { destination },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                PushFilesWorker.Callbacks(
                    onTransferComplete = {},
                    onValidated = {},
                    beforeApply = { checkpointCalls += "checkpoint"; false },
                ),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("client_persistence_unavailable", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
        }
        assertEquals(listOf("checkpoint"), checkpointCalls)
        assertEquals("unchanged", sentinel.readText())
        assertFalse(File(destination, "content.txt").exists())
    }

    @Test
    fun `mismatched artifact SHA stops before callbacks and destination changes`() {
        val archive = zip("content.txt" to "untrusted").readBytes()
        val destination = tmp.newFolder("sha-mismatch-destination")
        val sentinel = File(destination, "existing.txt").apply { writeText("unchanged") }
        val work = File(tmp.root, "sha-mismatch-work")
        var callbackInvoked = false

        artifactServer(archive).use { server ->
            val execution = worker(work, { destination }).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = "0".repeat(64),
                ).copy(revision = 7L),
                PushFilesWorker.Callbacks(
                    onTransferComplete = { callbackInvoked = true },
                    onValidated = { callbackInvoked = true },
                    beforeApply = { callbackInvoked = true; true },
                ),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("artifact_identity_mismatch", execution.completedResult().failureCode)
            assertTrue(execution.outcome is PushFilesWorker.Outcome.Completed)
        }
        assertFalse(callbackInvoked)
        assertEquals("unchanged", sentinel.readText())
        assertFalse(File(work, "artifact.part").exists())
        assertFalse(File(work, "artifact.zip").exists())
        assertFalse(File(work, "metadata.json").exists())
    }

    @Test
    fun `invalid destination is rejected after validation without applying callback`() {
        val archive = zip("content.txt" to "verified").readBytes()
        val destination = tmp.newFolder("invalid-destination")
        val sentinel = File(destination, "existing.txt").apply { writeText("unchanged") }
        val callbacks = mutableListOf<String>()

        artifactServer(archive).use { server ->
            val execution = worker(
                File(tmp.root, "invalid-destination-work"),
                destinationProvider = {
                    throw PushWorkerException("invalid_destination", "destination rejected")
                },
            ).execute(
                command(
                    artifactUrl = server.url,
                    artifactSize = archive.size.toLong(),
                    artifactSha256 = sha256(archive),
                ),
                workerCallbacks(callbacks),
            )

            assertEquals("fail", execution.completedResult().status)
            assertEquals("invalid_destination", execution.completedResult().failureCode)
        }
        assertEquals(listOf("validation_start", "transfer", "validated"), callbacks)
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

        // Use the protocol alias so the symlink check runs on any host JVM, not an
        // earlier "not absolute" rejection of a Windows drive path.
        val error = assertThrows(PushWorkerException::class.java) {
            PushFilesWorker().validateDestinationAgainstRoot("/sdcard/link/content", root)
        }
        assertEquals("destination path contains a symbolic link", error.message)
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
