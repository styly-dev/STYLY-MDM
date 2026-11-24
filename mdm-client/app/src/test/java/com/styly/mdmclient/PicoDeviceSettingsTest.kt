package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PicoDeviceSettingsTest {
    @Test
    fun `SDK linkage errors use the existing failed setting reason`() {
        val outcome = settingsFailureOutcome(NoSuchMethodError("missing SDK method"), "WRITE_FAILED")

        assertEquals("failed", outcome.getString("status"))
        assertEquals("WRITE_FAILED", outcome.getString("reason"))
    }

    @Test
    fun `startup app applies a package through the PICO launcher API`() {
        var requested: String? = null

        val result = applyPicoLauncher("com.example.app", { true }, { packageName ->
            requested = packageName
            0
        })

        assertEquals("com.example.app", requested)
        assertEquals("applied", result.getString("status"))
        assertEquals("com.example.app", result.getString("value"))
    }

    @Test
    fun `empty startup app restores the default PICO launcher`() {
        var requested: String? = "unexpected"

        val result = applyPicoLauncher(
            "",
            { error("The default launcher does not require an installed package") },
        ) { packageName ->
            requested = packageName
            0
        }

        assertNull(requested)
        assertEquals("applied", result.getString("status"))
        assertEquals("", result.getString("value"))
    }

    @Test
    fun `startup app reports launcher API failure`() {
        val result = applyPicoLauncher("com.example.app", { true }) { 1 }

        assertEquals("failed", result.getString("status"))
        assertEquals("WRITE_FAILED", result.getString("reason"))
    }

    @Test
    fun `missing startup app waits but disabled or malformed apps fail without changing the launcher`() {
        var launcherCalls = 0

        val missing = applyPicoLauncher("com.example.missing", { null }) {
            launcherCalls += 1
            0
        }
        val disabled = applyPicoLauncher("com.example.disabled", { false }) {
            launcherCalls += 1
            0
        }
        // The installed Android framework package is outside the home app package-name format.
        val malformed = applyPicoLauncher("android", { true }) {
            launcherCalls += 1
            0
        }

        assertEquals("waiting", missing.getString("status"))
        assertEquals("APP_NOT_INSTALLED", missing.getString("reason"))
        assertEquals("com.example.missing", missing.getString("value"))
        assertEquals("failed", disabled.getString("status"))
        assertEquals("APP_DISABLED", disabled.getString("reason"))
        assertEquals("com.example.disabled", disabled.getString("value"))
        assertEquals("failed", malformed.getString("status"))
        assertEquals("INVALID_VALUE", malformed.getString("reason"))
        assertEquals(0, launcherCalls)
    }

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
