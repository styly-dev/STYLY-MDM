package com.styly.mdmclient

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

class PushFilesWorker {
    data class Callbacks(
        val onTransferComplete: (Long) -> Unit,
        val onValidated: () -> Unit,
        val onApplying: () -> Unit,
    )

    fun execute(command: PushProtocol.Command, callbacks: Callbacks): PushProtocol.Result {
        var work: File? = null
        var bundle: File? = null
        var staging: File? = null
        return try {
            work = workDirectory(command)
            bundle = File(work, "artifact.zip")
            val received = download(command.artifactUrl, bundle)
            if (command.artifactSize != null && received != command.artifactSize) {
                throw IllegalStateException("artifact_identity_mismatch: expected ${command.artifactSize} bytes, received $received")
            }
            callbacks.onTransferComplete(received)
            staging = File(work, "staging")
            validateAndExtract(bundle, staging)
            callbacks.onValidated()
            callbacks.onApplying()
            rejectDestinationSymlinks(File(command.destPath))
            val result = BundleSync.apply(staging, File(command.destPath), command.deleteExtras)
            PushProtocol.Result(
                command.jobId, command.attempt, "success", command.destPath,
                result.added, result.updated, result.deleted, ""
            )
        } catch (error: Throwable) {
            PushProtocol.Result(
                command.jobId, command.attempt, "fail", command.destPath,
                0, 0, 0, error.message ?: error.javaClass.simpleName
            )
        } finally {
            try { staging?.deleteRecursively() } catch (_: Throwable) {}
            try { bundle?.delete() } catch (_: Throwable) {}
            try { work?.deleteRecursively() } catch (_: Throwable) {}
        }
    }

    private fun workDirectory(command: PushProtocol.Command): File {
        val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(root, "styly-mdm/.push-tmp/jobs/$key/${command.attempt}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Failed to create push work directory")
        }
        return directory
    }

    private fun download(url: String, outputFile: File): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("download_failed: HTTP $code")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) throw IllegalStateException("download_failed: artifact is empty")
            return total
        } finally {
            connection.disconnect()
        }
    }

    private fun validateAndExtract(bundle: File, staging: File) {
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IllegalStateException("validation_failed: cannot create staging directory")
        val root = staging.canonicalFile
        val seen = HashSet<String>()
        val kinds = HashMap<String, Boolean>() // true=directory
        ZipFile(bundle).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty() || parts.any { it == ".." }) {
                    throw IllegalStateException("validation_failed: invalid ZIP entry ${entry.name}")
                }
                val relative = parts.joinToString("/")
                if (!seen.add(relative)) {
                    throw IllegalStateException("validation_failed: duplicate ZIP path $relative")
                }
                for (index in 1 until parts.size) {
                    val ancestor = parts.take(index).joinToString("/")
                    if (kinds[ancestor] == false) {
                        throw IllegalStateException("validation_failed: file/directory conflict at $ancestor")
                    }
                    kinds[ancestor] = true
                }
                val previous = kinds[relative]
                if (previous != null && previous != entry.isDirectory) {
                    throw IllegalStateException("validation_failed: file/directory conflict at $relative")
                }
                kinds[relative] = entry.isDirectory
                val target = File(staging, relative).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw IllegalStateException("validation_failed: ZIP entry escaped staging")
                }
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IllegalStateException("validation_failed: cannot create $relative")
                    }
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun rejectDestinationSymlinks(destination: File) {
        var current: File? = destination.absoluteFile
        while (current != null) {
            if (current.exists() && Files.isSymbolicLink(current.toPath())) {
                throw IllegalStateException("invalid_destination: symbolic links are not allowed in the destination path")
            }
            current = current.parentFile
        }
        if (destination.exists()) {
            destination.walkTopDown().forEach { entry ->
                if (Files.isSymbolicLink(entry.toPath())) {
                    throw IllegalStateException("invalid_destination: destination tree contains a symbolic link")
                }
            }
        }
    }
}
