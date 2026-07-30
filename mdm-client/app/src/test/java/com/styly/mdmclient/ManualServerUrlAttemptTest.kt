package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualServerUrlAttemptTest {

    private val manualUrl = "ws://10.0.0.5:8080/ws/device"

    @Test
    fun newWindowAttemptsManualUrlOnlyOnce() {
        val attempt = ManualServerUrlAttempt()

        attempt.onWindowOpened(manualUrl)

        assertEquals(manualUrl, attempt.take())
        assertNull(attempt.take())
    }

    @Test
    fun failedAttemptDoesNotRearmManualUrl() {
        val attempt = ManualServerUrlAttempt()
        attempt.onWindowOpened(manualUrl)
        attempt.take()

        attempt.onSocketGone(wasConnected = false, manualUrl = manualUrl)

        assertNull(attempt.take())
    }

    @Test
    fun laterWindowRearmsPersistedManualUrl() {
        val attempt = ManualServerUrlAttempt()
        attempt.onWindowOpened(manualUrl)
        attempt.take()

        attempt.onWindowOpened(manualUrl)

        assertEquals(manualUrl, attempt.take())
    }

    @Test
    fun establishedConnectionDropRearmsManualUrl() {
        val attempt = ManualServerUrlAttempt()
        attempt.onWindowOpened(manualUrl)
        attempt.take()

        attempt.onSocketGone(wasConnected = true, manualUrl = manualUrl)

        assertEquals(manualUrl, attempt.take())
    }

    @Test
    fun windowWithoutManualUrlStartsWithDiscovery() {
        val attempt = ManualServerUrlAttempt()

        attempt.onWindowOpened(null)

        assertNull(attempt.take())
    }
}
