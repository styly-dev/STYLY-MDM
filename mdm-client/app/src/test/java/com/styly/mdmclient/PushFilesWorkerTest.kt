package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
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

    private fun command() = PushProtocol.Command(
        jobId = UUID.randomUUID().toString(),
        attempt = PushProtocol.ATTEMPT_V1,
        artifactId = UUID.randomUUID().toString(),
        artifactUrl = "http://server/artifacts/value",
        artifactSize = 42,
        artifactSha256 = "a".repeat(64),
        bundleFilename = "bundle.zip",
        destPath = "/sdcard/STYLY/content",
        deleteExtras = false,
    )

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
