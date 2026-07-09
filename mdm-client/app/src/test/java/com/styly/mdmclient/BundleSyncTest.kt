package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The destructive branch of the push-files feature lives here, so it is tested on the host JVM.
 * The central guarantee: pushing a single file into a directory must never remove what is
 * already there. Only an explicit sync (deleteExtras = true) prunes.
 */
class BundleSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, content: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `push does not delete existing files at the destination`() {
        val staging = tmp.newFolder("staging")
        val dest = tmp.newFolder("dest")
        write(staging, "new.txt", "new")
        val untouched = write(dest, "keep.txt", "keep")
        val nested = write(dest, "sub/deep.txt", "deep")

        val result = BundleSync.apply(staging, dest, deleteExtras = false)

        assertTrue("bundle file must be created", File(dest, "new.txt").isFile)
        assertTrue("unrelated file must survive a push", untouched.isFile)
        assertTrue("unrelated nested file must survive a push", nested.isFile)
        assertEquals("keep", untouched.readText())
        assertEquals(SyncResult(added = 1, updated = 0, deleted = 0), result)
    }

    @Test
    fun `push overwrites a file of the same name and counts it as updated`() {
        val staging = tmp.newFolder("staging")
        val dest = tmp.newFolder("dest")
        write(staging, "same.txt", "fresh")
        write(dest, "same.txt", "stale")

        val result = BundleSync.apply(staging, dest, deleteExtras = false)

        assertEquals("fresh", File(dest, "same.txt").readText())
        assertEquals(SyncResult(added = 0, updated = 1, deleted = 0), result)
    }

    @Test
    fun `sync deletes extras and prunes emptied directories`() {
        val staging = tmp.newFolder("staging")
        val dest = tmp.newFolder("dest")
        write(staging, "keep.txt", "keep")
        write(dest, "keep.txt", "old")
        write(dest, "extra.txt", "extra")
        write(dest, "stale/nested.txt", "nested")

        val result = BundleSync.apply(staging, dest, deleteExtras = true)

        assertEquals("keep", File(dest, "keep.txt").readText())
        assertFalse("extra file must be pruned by a sync", File(dest, "extra.txt").exists())
        assertFalse("emptied directory must be pruned by a sync", File(dest, "stale").exists())
        assertEquals(SyncResult(added = 0, updated = 1, deleted = 2), result)
    }

    @Test
    fun `sync keeps directories that a bundle file lives in`() {
        val staging = tmp.newFolder("staging")
        val dest = tmp.newFolder("dest")
        write(staging, "a/b/leaf.txt", "leaf")

        val result = BundleSync.apply(staging, dest, deleteExtras = true)

        assertTrue(File(dest, "a/b/leaf.txt").isFile)
        assertEquals(SyncResult(added = 1, updated = 0, deleted = 0), result)
    }

    @Test
    fun `push creates a missing destination directory`() {
        val staging = tmp.newFolder("staging")
        val dest = File(tmp.root, "absent")
        write(staging, "only.txt", "x")

        BundleSync.apply(staging, dest, deleteExtras = false)

        assertTrue(File(dest, "only.txt").isFile)
    }
}
