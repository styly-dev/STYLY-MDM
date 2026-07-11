package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM coverage for the partial-arming decision (issue #39, PR #48 review):
 * when scheduling a self-update's power cycle fails part-way, arm() must never
 * report a safe refusal while a shutdown timer may still be armed without a paired
 * startup timer — and must not cry wolf when no shutdown timer was ever armed. The
 * live TobService close calls stay device-only; this exercises the retry/verdict
 * control flow with scripted close results.
 */
class PowerCycleResolveTest {

    private fun script(vararg passes: PowerCycleTimers.CloseResult): () -> PowerCycleTimers.CloseResult {
        val it = passes.iterator()
        // Repeat the final pass if the resolver asks for more than we scripted, so a
        // test only has to script the passes that differ.
        var last = PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = false)
        return {
            if (it.hasNext()) last = it.next()
            last
        }
    }

    @Test
    fun bothClosedOnFirstPassIsSafeAndStopsEarly() {
        var calls = 0
        val outcome = PowerCycleTimers.resolvePartialArm(4, shutdownWasArmed = true) {
            calls++
            PowerCycleTimers.CloseResult(shutdownClosed = true, startupClosed = true)
        }
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_SAFE, outcome)
        assertEquals("stops as soon as both confirm closed", 1, calls)
    }

    @Test
    fun armedShutdownThatNeverClosesIsUnsafeAfterTheFullWindow() {
        var calls = 0
        val outcome = PowerCycleTimers.resolvePartialArm(4, shutdownWasArmed = true) {
            calls++
            PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = true)
        }
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_UNSAFE, outcome)
        assertEquals("retries the full bounded window before giving up", 4, calls)
    }

    @Test
    fun transientCloseFailureThatRecoversIsSafe() {
        val outcome = PowerCycleTimers.resolvePartialArm(
            4,
            shutdownWasArmed = true,
            closeAttempt = script(
                PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = false),
                PowerCycleTimers.CloseResult(shutdownClosed = true, startupClosed = true),
            ),
        )
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_SAFE, outcome)
    }

    @Test
    fun aLoneLingeringStartupIsBenignAndStillSafe() {
        // The shutdown timer — the only one that can strand the device — is confirmed
        // closed; a startup timer that will not close only powers an already-on
        // device on, so the refusal is safe.
        val outcome = PowerCycleTimers.resolvePartialArm(
            3,
            shutdownWasArmed = true,
            closeAttempt = script(PowerCycleTimers.CloseResult(shutdownClosed = true, startupClosed = false)),
        )
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_SAFE, outcome)
    }

    @Test
    fun shutdownConfirmedClosedStaysSafeEvenIfALaterRecloseReportsFailure() {
        // pass 1 closes the shutdown timer; pass 2's re-close of the already-closed
        // one-shot reports non-zero. The verdict must not flip back to unsafe.
        val outcome = PowerCycleTimers.resolvePartialArm(
            4,
            shutdownWasArmed = true,
            closeAttempt = script(
                PowerCycleTimers.CloseResult(shutdownClosed = true, startupClosed = false),
                PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = false),
            ),
        )
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_SAFE, outcome)
    }

    @Test
    fun anArmedShutdownWindowThatNeverConfirmsIsUnsafe() {
        val outcome = PowerCycleTimers.resolvePartialArm(
            2,
            shutdownWasArmed = true,
            closeAttempt = script(PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = false)),
        )
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_UNSAFE, outcome)
    }

    @Test
    fun neverArmedShutdownIsSafeEvenWhenItsCloseNeverConfirms() {
        // Reverse-partial / total failure: shutdown never opened, so nothing can
        // strand the device. A close of the never-armed timer may report non-zero;
        // that must not be mistaken for a live shutdown timer.
        val outcome = PowerCycleTimers.resolvePartialArm(
            3,
            shutdownWasArmed = false,
            closeAttempt = script(PowerCycleTimers.CloseResult(shutdownClosed = false, startupClosed = false)),
        )
        assertEquals(PowerCycleTimers.ArmOutcome.REFUSED_SAFE, outcome)
    }
}
