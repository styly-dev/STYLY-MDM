package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicoDeviceSettingsTest {
    @Test
    fun `Bluetooth transition near the deadline does not start another wait`() {
        var now = 0L
        var lastRead = 0L
        val actual = pollBluetoothState(target = true, nowElapsed = { now }, sleep = { now += it }) {
            lastRead = now
            if (now < 1_900L) false else null
        }

        assertNull(actual)
        assertEquals(1_900L, lastRead)
        assertEquals(2_000L, now)
    }

    @Test
    fun `Bluetooth polling stops when the requested state is available`() {
        var now = 0L
        val actual = pollBluetoothState(target = false, nowElapsed = { now }, sleep = { now += it }) {
            when {
                now < 100L -> true
                now < 300L -> null
                else -> false
            }
        }

        assertEquals(false, actual)
        assertEquals(300L, now)
    }

    @Test
    fun `Bluetooth polling retains a stable mismatch and caps the final sleep`() {
        var now = 0L
        val actual = pollBluetoothState(target = true, nowElapsed = { now }, sleep = { now += it }) {
            now += 37L
            false
        }

        assertEquals(false, actual)
        assertEquals(2_000L, now)
    }
}
