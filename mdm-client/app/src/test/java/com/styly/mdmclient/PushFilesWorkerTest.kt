package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
