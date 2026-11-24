package com.styly.mdmclient

import android.content.ContextWrapper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoDeviceSettingsTest {
    @Test
    fun `apply owns the LBE switch and reports the IPD callback without reading a lens position`() {
        var ipdCode = 0
        val lensRequests = mutableListOf<Float>()
        val sdk = IToBServiceProxy::class.java
        val service = Proxy.newProxyInstance(sdk.classLoader, arrayOf(sdk)) { _, method, args ->
            check(method.name == "pbsSetIPD") { method.name }
            lensRequests += args[0] as Float
            @Suppress("UNCHECKED_CAST")
            (args[1] as Consumer<Int>).accept(ipdCode)
            null
        } as IToBServiceProxy
        var mode = false
        val switchCalls = mutableListOf<Boolean>()
        val lbe = PicoLbeMode(
            readStatus = { if (mode) "1" else "0" },
            setEnabled = { target -> switchCalls += target; mode = target; true },
        )
        var elapsed = 0L
        val settings = PicoDeviceSettings(ContextWrapper(null), service, lbe) { elapsed += 200L; elapsed }
        val request = JSONObject().put("lbeMode", true).put("ipd", 62.5)
        val applied = settings.apply(request, overwrite = false)
        assertEquals("applied", applied.getJSONObject("lbeMode").getString("status"))
        assertEquals(true, applied.getJSONObject("lbeMode").getBoolean("value"))
        assertEquals("ACCEPTED_UNVERIFIED", applied.getJSONObject("ipd").getString("reason"))
        assertEquals(62.5, applied.getJSONObject("ipd").getDouble("value"), 0.0)

        ipdCode = -2
        val overwrite = settings.apply(request, overwrite = true)
        assertEquals("applied", overwrite.getJSONObject("lbeMode").getString("status"))
        assertEquals("unsupported", overwrite.getJSONObject("ipd").getString("status"))
        assertEquals(listOf(true), switchCalls)
        assertEquals(listOf(62.5f, 62.5f), lensRequests)

        val disabled = settings.apply(JSONObject().put("lbeMode", false), overwrite = false)
        assertEquals(false, disabled.getJSONObject("lbeMode").getBoolean("value"))
        assertEquals(listOf(true, false), switchCalls)
    }

    @Test
    fun `only the SDK match code while LBE is On verifies a venue ZIP`() {
        assertEquals(ActiveLbeMap.MODE_OFF, inspectActiveLbeMap({ false }) { error("unexpected comparison") })
        assertEquals(ActiveLbeMap.MATCH, inspectActiveLbeMap({ true }) { 0 })
        assertEquals(ActiveLbeMap.DIFFERENT, inspectActiveLbeMap({ true }) { 1 })
        assertEquals(ActiveLbeMap.UNKNOWN, inspectActiveLbeMap({ true }) { -2 })
        assertEquals(ActiveLbeMap.UNKNOWN, inspectActiveLbeMap({ true }) { throw NoSuchMethodError() })
        assertEquals(ActiveLbeMap.UNKNOWN, inspectActiveLbeMap({ throw java.util.concurrent.TimeoutException() }) { 0 })
    }

    @Test
    fun `IPD accepts only tenths inside the SDK range`() {
        assertEquals(58.0, normalizeIpd(58), 0.0)
        assertEquals(63.1, normalizeIpd(63.1), 0.0)
        assertEquals(72.0, normalizeIpd(72L), 0.0)
    }

    @Test
    fun `IPD rejects off-grid and out-of-range values`() {
        for (value in listOf(57.9, 72.1, 58.00001, Double.NaN, Double.POSITIVE_INFINITY, "63.1")) {
            try {
                normalizeIpd(value)
                throw AssertionError("Expected IPD $value to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `LBE map availability must stay active for the full observation window`() {
        var now = 0L
        val stable = awaitStableAvailability(
            isAvailable = { now >= 600L },
            nowElapsed = { now },
            sleep = { now += it },
            stableMillis = 800L,
            timeoutMillis = 2_000L,
            pollMillis = 100L,
        )

        assertTrue(stable)
        assertEquals(1_400L, now)
    }

    @Test
    fun `flapping LBE map availability times out`() {
        var now = 0L
        val stable = awaitStableAvailability(
            isAvailable = { (now / 200L) % 2L == 0L },
            nowElapsed = { now },
            sleep = { now += it },
            stableMillis = 800L,
            timeoutMillis = 1_500L,
            pollMillis = 100L,
        )

        assertFalse(stable)
        assertEquals(1_500L, now)
    }

    @Test
    fun `a transient LBE read timeout resets stability and keeps polling`() {
        var now = 0L
        var reads = 0
        val stable = awaitStableAvailability(
            isAvailable = {
                reads++
                if (reads == 1) throw java.util.concurrent.TimeoutException()
                true
            },
            nowElapsed = { now },
            sleep = { now += it },
            stableMillis = 200L,
            timeoutMillis = 1_000L,
            pollMillis = 100L,
        )

        assertTrue(stable)
        assertEquals(300L, now)
        assertEquals(4, reads)
    }

    @Test
    fun `a permanent security failure stops LBE observation immediately`() {
        var reads = 0
        var now = 0L
        try {
            awaitStableAvailability(
                isAvailable = {
                    reads++
                    throw SecurityException("permission denied")
                },
                nowElapsed = { now },
                sleep = { now += it },
            )
            throw AssertionError("Expected the permanent read failure to escape")
        } catch (_: SecurityException) {
            assertEquals(1, reads)
        }
    }

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
    fun `missing startup app restores the default before waiting and invalid values do not write`() {
        val launcherCalls = mutableListOf<String?>()

        val missing = applyPicoLauncher("com.example.missing", { null }) { target ->
            launcherCalls += target
            0
        }
        val disabled = applyPicoLauncher("com.example.disabled", { false }) { target ->
            launcherCalls += target
            0
        }
        // The installed Android framework package is outside the home app package-name format.
        val malformed = applyPicoLauncher("android", { true }) { target ->
            launcherCalls += target
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
        assertEquals(listOf<String?>(null), launcherCalls)
    }

    @Test
    fun `a failed default home reset is retryable instead of reporting waiting`() {
        val rejected = applyPicoLauncher("com.example.missing", { null }) { 1 }
        assertEquals("failed", rejected.getString("status"))
        assertEquals("WRITE_FAILED", rejected.getString("reason"))
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
