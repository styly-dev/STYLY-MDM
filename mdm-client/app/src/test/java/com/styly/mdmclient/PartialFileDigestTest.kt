package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PartialFileDigestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun append(file: File, bytes: ByteArray, digest: PartialFileDigest) {
        FileOutputStream(file, true).use { it.write(bytes) }
        digest.update(bytes, 0, bytes.size)
    }

    @Test
    fun `uninterrupted writes are hashed without rereading the file`() {
        val file = tmp.newFile("artifact.part")
        val digest = PartialFileDigest(file)
        val first = ByteArray(10_000) { it.toByte() }
        val second = ByteArray(5_000) { (it * 7).toByte() }
        digest.beginWrite(append = false)
        append(file, first, digest)
        // A retry in the same execution appends to exactly the tracked bytes.
        digest.beginWrite(append = true)
        append(file, second, digest)

        assertEquals(sha256(first + second), digest.finishHex())
        assertEquals(0L, digest.rehashedBytes)
    }

    @Test
    fun `resume in a new execution defers the whole-file hash to validation`() {
        val file = tmp.newFile("artifact.part")
        val prefix = ByteArray(4_096) { 3 }
        file.writeBytes(prefix)
        val digest = PartialFileDigest(file)
        digest.beginWrite(append = true)
        val rest = ByteArray(2_048) { 9 }
        append(file, rest, digest)
        // Nothing is reread while the transfer is in progress.
        assertEquals(0L, digest.rehashedBytes)

        assertEquals(sha256(prefix + rest), digest.finishHex())
        assertEquals((prefix.size + rest.size).toLong(), digest.rehashedBytes)
    }

    @Test
    fun `truncation for a full restart resets the digest`() {
        val file = tmp.newFile("artifact.part")
        file.writeBytes(ByteArray(1_000) { 1 })
        val digest = PartialFileDigest(file)
        digest.beginWrite(append = true)
        // An ignored Range response replaces the partial from byte zero.
        FileOutputStream(file, false).close()
        digest.beginWrite(append = false)
        val replacement = ByteArray(3_000) { 2 }
        append(file, replacement, digest)

        assertEquals(sha256(replacement), digest.finishHex())
        assertEquals(0L, digest.rehashedBytes)
    }

    @Test
    fun `bytes written without a digest update are recovered at validation`() {
        val file = tmp.newFile("artifact.part")
        val digest = PartialFileDigest(file)
        val hashed = ByteArray(1_000) { 4 }
        digest.beginWrite(append = false)
        append(file, hashed, digest)
        // Simulates a write that reached disk before its caller failed.
        val unhashed = ByteArray(500) { 5 }
        FileOutputStream(file, true).use { it.write(unhashed) }
        digest.beginWrite(append = true)
        val rest = ByteArray(250) { 6 }
        append(file, rest, digest)
        assertEquals(0L, digest.rehashedBytes)

        assertEquals(sha256(hashed + unhashed + rest), digest.finishHex())
        assertEquals(1_750L, digest.rehashedBytes)
    }
}
