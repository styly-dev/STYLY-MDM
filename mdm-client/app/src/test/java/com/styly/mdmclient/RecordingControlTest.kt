package com.styly.mdmclient

import org.junit.Assert.*
import org.junit.Test

class RecordingControlTest {
    private class Receipts : RecordingReceipts {
        val results = mutableMapOf<String, Pair<String, RecordingResult>>()
        var writable = true
        var toggleAllowed = true
        override fun find(requestId: String) = results[requestId]
        override fun save(requestId: String, action: String, result: RecordingResult): Boolean {
            if (writable) results[requestId] = action to result
            return writable
        }
        override fun reserveToggle(atMillis: Long) = toggleAllowed
    }
    private class Fixture {
        val receipts = Receipts()
        var state = RecordingState.STOPPED
        var active = true
        var time = 1_000L
        var calls = 0
        var settles = true
        var onRead: () -> Unit = {}
        val controller = RecordingControl(receipts, { onRead(); state }, {
            assertNotNull(receipts.results["request"])
            calls++
            if (settles) state = if (state == RecordingState.STOPPED) RecordingState.RECORDING else RecordingState.STOPPED
        }, { active }, { time }, { time += it })
    }

    @Test fun cooldownUsesMonotonicTimeAndAllowsRebootReset() {
        assertTrue(recordingCooldownActive(10_000, 10_500))
        assertFalse(recordingCooldownActive(10_000, 18_000))
        assertFalse(recordingCooldownActive(10_000, 1_000))
        assertFalse(recordingCooldownActive(0, 1_000))
    }

    @Test fun nativeFlagAndNarrowInitialIdle() {
        assertEquals(RecordingState.RECORDING, RecordingState.fromProperties("[pvr.screen.action]: [record]", -1))
        assertEquals(RecordingState.STOPPED, RecordingState.fromProperties("[pvr.screen.action]: []", -1))
        assertEquals(RecordingState.UNKNOWN, RecordingState.fromProperties("", 100004072))
        assertEquals(RecordingState.UNKNOWN, RecordingState.fromProperties("[ro.pui.build.version]: [5.15.8]", 100004072))
        assertEquals(RecordingState.UNKNOWN, RecordingState.fromProperties("[ro.pui.build.version]: [5.15.7]", 1))
        assertEquals(RecordingState.STOPPED, RecordingState.fromProperties("[ro.pui.build.version]: [5.15.7]", 100004072))
        assertEquals(RecordingState.UNKNOWN, RecordingState.fromProperties("[ro.pui.build.version]: [5.15.7]\n[pvr.screen.action]: [other]", 100004072))
        assertEquals(RecordingState.UNKNOWN, RecordingState.fromProperties("[pvr.screen.action]: []\n[pvr.screen.action]: [record]", 100004072))
    }
    @Test fun startAndStopConfirmDesiredStateAfterCountdown() {
        val f = Fixture()
        val started = f.controller.execute("request", "start")
        assertEquals(RecordingResult("success", RecordingState.RECORDING, true), started)
        assertEquals(4_000L, f.time)
        val stopped = f.controller.execute("second", "stop")
        assertEquals(RecordingResult("success", RecordingState.STOPPED, true), stopped)
        assertEquals(2, f.calls)
    }
    @Test fun repeatedStartAndStopAreNoOps() {
        val f = Fixture()
        assertEquals(RecordingResult("success", RecordingState.STOPPED), f.controller.execute("request", "stop"))
        f.state = RecordingState.RECORDING
        assertEquals(RecordingResult("success", RecordingState.RECORDING), f.controller.execute("other", "start"))
        assertEquals(0, f.calls)
    }
    @Test fun unknownStateAndFailedPersistenceNeverToggle() {
        val f = Fixture()
        f.state = RecordingState.UNKNOWN
        assertEquals("fail", f.controller.execute("request", "start").status)
        f.state = RecordingState.STOPPED
        f.receipts.writable = false
        assertEquals("fail", f.controller.execute("other", "start").status)
        assertEquals(0, f.calls)
    }
    @Test fun repeatedAndInterruptedLatestReceiptNeverToggle() {
        val f = Fixture()
        val result = f.controller.execute("request", "start")
        f.state = RecordingState.STOPPED
        assertEquals(result, f.controller.execute("request", "start"))
        assertEquals("fail", f.controller.execute("request", "stop").status)
        f.receipts.results["interrupted"] = "start" to RecordingResult("fail", detail = "Interrupted")
        assertEquals("fail", f.controller.execute("interrupted", "start").status)
        assertEquals(1, f.calls)
    }
    @Test fun lostConnectionDuringStateReadPreventsToggle() {
        val f = Fixture()
        f.onRead = { f.active = false }
        assertEquals("fail", f.controller.execute("request", "start").status)
        assertEquals(0, f.calls)
    }
    @Test fun confirmationTimeoutNeverRetries() {
        val f = Fixture()
        f.settles = false
        assertEquals("fail", f.controller.execute("request", "start").status)
        assertEquals(1, f.calls)
        assertEquals(9_000L, f.time)
        assertEquals("fail", f.controller.execute("request", "start").status)
        assertEquals(1, f.calls)
    }
    @Test fun cooldownBlocksChangeButAllowsAlreadyDesiredState() {
        val f = Fixture()
        f.receipts.toggleAllowed = false
        assertEquals("fail", f.controller.execute("request", "start").status)
        assertEquals("success", f.controller.execute("other", "stop").status)
        assertEquals(0, f.calls)
    }
    @Test fun statusAndInvalidActionsNeverToggleOrStoreReceipts() {
        val f = Fixture()
        assertEquals("success", f.controller.execute("request", "status").status)
        assertEquals("fail", f.controller.execute("request", "toggle").status)
        assertTrue(f.receipts.results.isEmpty())
        assertEquals(0, f.calls)
    }
}
