package com.styly.mdmclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class BundleSyncSecurityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copy never follows a destination symlink outside the root`() {
        val staging = tmp.newFolder("staging")
        val destination = tmp.newFolder("destination")
        val outside = tmp.newFolder("outside")
        val link = File(destination, "linked")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue("symbolic links are unsupported", false)
        } catch (_: SecurityException) {
            assumeTrue("symbolic links are unavailable", false)
        }
        File(staging, "linked/payload.txt").apply {
            parentFile.mkdirs()
            writeText("payload")
        }

        assertThrows(IllegalStateException::class.java) {
            BundleSync.apply(staging, destination, deleteExtras = false)
        }
        assertFalse(File(outside, "payload.txt").exists())
    }
}
