package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAttemptPolicyTest {

    private val manualUrl = "ws://10.0.0.5:8080/ws/device"

    @Test
    fun manualUrlIsUsedForEveryAttemptInWindow() {
        val policy = ConnectionAttemptPolicy()
        val expected = ConnectionAttemptPolicy.Target.Manual(manualUrl)

        policy.onWindowOpened(manualUrl)

        assertEquals(expected, policy.targetForAttempt())
        assertEquals(expected, policy.targetForAttempt())
    }

    @Test
    fun laterWindowReevaluatesConnectionMode() {
        val policy = ConnectionAttemptPolicy()
        policy.onWindowOpened(manualUrl)
        assertEquals(
            ConnectionAttemptPolicy.Target.Manual(manualUrl),
            policy.targetForAttempt(),
        )

        policy.onWindowOpened(null)

        assertEquals(
            ConnectionAttemptPolicy.Target.AutoDiscovery,
            policy.targetForAttempt(),
        )
    }

    @Test
    fun windowWithoutManualUrlUsesAutoDiscovery() {
        val policy = ConnectionAttemptPolicy()

        policy.onWindowOpened(null)

        assertEquals(
            ConnectionAttemptPolicy.Target.AutoDiscovery,
            policy.targetForAttempt(),
        )
    }
}
