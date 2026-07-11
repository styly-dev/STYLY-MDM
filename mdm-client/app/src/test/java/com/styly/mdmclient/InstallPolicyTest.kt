package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPolicyTest {

    private val sha = "a".repeat(64)

    @Test
    fun normalInstallWithoutExpectedHashProceeds() {
        assertNull(InstallPolicy.hashGateError(null, null, isSelfUpdate = false))
        assertNull(InstallPolicy.hashGateError("", null, isSelfUpdate = false))
        assertNull(InstallPolicy.hashGateError("   ", null, isSelfUpdate = false))
    }

    @Test
    fun selfUpdateWithoutExpectedHashIsRefused() {
        val error = InstallPolicy.hashGateError(null, null, isSelfUpdate = true)
        assertNotNull(error)
        assertTrue(error!!.contains("Self-update requires"))
    }

    @Test
    fun blankExpectedHashCountsAsAbsentForSelfUpdate() {
        assertNotNull(InstallPolicy.hashGateError("  ", null, isSelfUpdate = true))
    }

    @Test
    fun matchingHashProceeds() {
        assertNull(InstallPolicy.hashGateError(sha, sha, isSelfUpdate = false))
        assertNull(InstallPolicy.hashGateError(sha, sha, isSelfUpdate = true))
    }

    @Test
    fun hexComparisonIsCaseInsensitiveAndTrimmed() {
        assertNull(InstallPolicy.hashGateError("ABCDEF", " abcdef ", isSelfUpdate = true))
        assertNull(InstallPolicy.hashGateError(" abcDEF", "ABCdef", isSelfUpdate = false))
    }

    @Test
    fun mismatchedHashIsRefusedForAnyInstall() {
        val expected = sha
        val actual = "b".repeat(64)
        val normal = InstallPolicy.hashGateError(expected, actual, isSelfUpdate = false)
        val self = InstallPolicy.hashGateError(expected, actual, isSelfUpdate = true)
        assertNotNull(normal)
        assertNotNull(self)
        assertTrue(normal!!.contains("mismatch"))
        assertEquals(normal, self)
    }

    @Test
    fun expectedHashWithoutComputedActualIsRefused() {
        val error = InstallPolicy.hashGateError(sha, null, isSelfUpdate = false)
        assertNotNull(error)
        assertTrue(error!!.contains("Could not compute"))
    }
}
