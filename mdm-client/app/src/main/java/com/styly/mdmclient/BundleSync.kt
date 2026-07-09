package com.styly.mdmclient

import java.io.File

data class SyncResult(val added: Int, val updated: Int, val deleted: Int)

/**
 * Applies a downloaded, unzipped bundle tree onto a destination directory.
 *
 * Deliberately free of Android dependencies (plain `java.io.File` I/O) so the destructive
 * branch can be exercised by host JVM unit tests rather than only on a real device.
 */
object BundleSync {

    /**
     * Copy every staged file into [destDir], overwriting existing files.
     *
     * When [deleteExtras] is false (push): nothing at the destination is removed, so files
     * already there that are absent from the bundle survive untouched. `deleted` is 0.
     *
     * When [deleteExtras] is true (sync): the destination is additionally pruned of anything
     * not present in the bundle — including now-empty directories — so it ends up identical
     * to the bundle (`rsync --delete` semantics).
     *
     * [deleteExtras] has no default on purpose: deleting is destructive enough that every
     * call site must name its intent. The safe default lives at the protocol boundary, where
     * a missing `delete_extras` field decodes to false.
     */
    fun apply(staging: File, destDir: File, deleteExtras: Boolean): SyncResult {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("Failed to create destination directory: ${destDir.absolutePath}")
        }

        var added = 0
        var updated = 0
        staging.walkTopDown().forEach { src ->
            if (src == staging || src.isDirectory) return@forEach
            val rel = src.relativeTo(staging).path
            val target = File(destDir, rel)
            val existed = target.isFile
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
            if (existed) updated++ else added++
        }

        if (!deleteExtras) {
            return SyncResult(added, updated, 0)
        }

        val stagedFiles = HashSet<String>()
        val stagedDirs = HashSet<String>()
        staging.walkTopDown().forEach { f ->
            if (f == staging) return@forEach
            val rel = f.relativeTo(staging).path.replace(File.separatorChar, '/')
            if (f.isDirectory) {
                stagedDirs.add(rel)
            } else {
                stagedFiles.add(rel)
                // Keep every ancestor directory of a staged file.
                var parent = File(rel).parent
                while (parent != null) {
                    stagedDirs.add(parent.replace(File.separatorChar, '/'))
                    parent = File(parent).parent
                }
            }
        }

        // Prune extras bottom-up: files first, then dirs (empty by the time we reach them).
        var deleted = 0
        destDir.walkBottomUp().forEach { entry ->
            if (entry == destDir) return@forEach
            val rel = entry.relativeTo(destDir).path.replace(File.separatorChar, '/')
            if (entry.isFile) {
                if (rel !in stagedFiles && entry.delete()) deleted++
            } else if (rel !in stagedDirs) {
                entry.delete()
            }
        }
        return SyncResult(added, updated, deleted)
    }
}
