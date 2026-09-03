package com.styly.mdmclient

import java.io.File
import java.nio.file.Files

data class SyncResult(val added: Int, val updated: Int, val deleted: Int)

/**
 * Applies an extracted bundle without traversing destination or staging symlinks.
 *
 * The caller validates that [destDir] belongs to shared storage. This class owns the
 * remaining filesystem invariant: every copied or removed entry stays below the exact
 * canonical destination root even if a hostile tree contains symbolic links.
 */
object BundleSync {

    fun apply(staging: File, destDir: File, deleteExtras: Boolean): SyncResult {
        requireOrdinaryDirectory(staging, "staging")
        if (Files.isSymbolicLink(destDir.toPath())) {
            throw IllegalStateException("invalid_destination: destination must not be a symbolic link")
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IllegalStateException("apply_failed: failed to create destination directory")
        }
        requireOrdinaryDirectory(destDir, "destination")
        val destinationRoot = destDir.canonicalFile

        var added = 0
        var updated = 0
        staging.walkTopDown().forEach { source ->
            if (source == staging) return@forEach
            if (Files.isSymbolicLink(source.toPath())) {
                throw IllegalStateException("validation_failed: staging contains a symbolic link")
            }
            if (source.isDirectory) return@forEach
            val relative = source.relativeTo(staging).path
            val target = safeTarget(destinationRoot, relative)
            val existed = target.isFile
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("apply_failed: failed to create destination directory")
            }
            // Recheck after mkdirs: another process must not replace an ancestor with a link.
            requireSafeAncestors(destinationRoot, target)
            if (Files.isSymbolicLink(target.toPath())) {
                throw IllegalStateException("invalid_destination: destination entry is a symbolic link")
            }
            source.copyTo(target, overwrite = true)
            if (existed) updated++ else added++
        }

        if (!deleteExtras) return SyncResult(added, updated, 0)

        val stagedFiles = HashSet<String>()
        val stagedDirectories = HashSet<String>()
        staging.walkTopDown().forEach { entry ->
            if (entry == staging) return@forEach
            if (Files.isSymbolicLink(entry.toPath())) {
                throw IllegalStateException("validation_failed: staging contains a symbolic link")
            }
            val relative = entry.relativeTo(staging).path.replace(File.separatorChar, '/')
            if (entry.isDirectory) {
                stagedDirectories.add(relative)
            } else {
                stagedFiles.add(relative)
                var parent = File(relative).parent
                while (parent != null) {
                    stagedDirectories.add(parent.replace(File.separatorChar, '/'))
                    parent = File(parent).parent
                }
            }
        }

        var deleted = 0
        for (entry in destinationEntriesBottomUp(destinationRoot)) {
            val relative = entry.relativeTo(destinationRoot).path.replace(File.separatorChar, '/')
            if (Files.isSymbolicLink(entry.toPath())) {
                if (relative in stagedFiles || relative in stagedDirectories) {
                    throw IllegalStateException(
                        "invalid_destination: bundle path is occupied by a symbolic link",
                    )
                }
                if (entry.delete()) deleted++
            } else if (entry.isFile) {
                if (relative !in stagedFiles && entry.delete()) deleted++
            } else if (entry.isDirectory && relative !in stagedDirectories) {
                entry.delete()
            }
        }
        return SyncResult(added, updated, deleted)
    }

    private fun requireOrdinaryDirectory(directory: File, label: String) {
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) {
            throw IllegalStateException("validation_failed: $label is not an ordinary directory")
        }
    }

    private fun safeTarget(root: File, relativePath: String): File {
        val target = File(root, relativePath)
        requireSafeAncestors(root, target)
        return target
    }

    private fun requireSafeAncestors(root: File, target: File) {
        val rootPath = root.canonicalPath
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget.path == rootPath ||
            !canonicalTarget.path.startsWith(rootPath + File.separator)
        ) {
            throw IllegalStateException("invalid_destination: destination entry escapes its root")
        }
        var current: File? = target.absoluteFile
        while (current != null && current.absolutePath != root.absolutePath) {
            if (current.exists() && Files.isSymbolicLink(current.toPath())) {
                throw IllegalStateException("invalid_destination: destination path contains a symbolic link")
            }
            current = current.parentFile
        }
    }

    /** Collect bottom-up without following directory symbolic links. */
    private fun destinationEntriesBottomUp(root: File): List<File> {
        val entries = ArrayList<File>()
        fun visit(directory: File) {
            val children = directory.listFiles()
                ?: throw IllegalStateException("apply_failed: could not list destination directory")
            for (child in children) {
                if (!Files.isSymbolicLink(child.toPath()) && child.isDirectory) visit(child)
                entries.add(child)
            }
        }
        visit(root)
        return entries
    }
}
