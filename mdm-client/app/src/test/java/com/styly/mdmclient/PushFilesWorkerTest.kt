package com.styly.mdmclient

import org.json.JSONObject
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
    fun `matching artifact SHA is verified before callbacks and apply`() {
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
                ),
            )

            assertEquals("success", execution.result.status)
        }
        assertEquals(listOf("transfer", "validated", "applying"), callbacks)
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
                retryDelay = { index -> delays += PushFilesWorker.retryDelayMillis(index) },
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
                ),
            )

            assertEquals("fail", execution.result.status)
            assertEquals("validation_failed", execution.result.failureCode)
        }
        assertEquals(listOf("transfer"), callbacks)
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
            "${root.canonicalPath}/safe/content",
            root,
        )
        assertEquals(File(root, "safe/content").canonicalFile, target)
    }
}
