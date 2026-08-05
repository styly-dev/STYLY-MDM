package com.styly.mdmclient

import com.styly.mdmclient.ConnectionScheduler.Action
import com.styly.mdmclient.ConnectionScheduler.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSchedulerTest {

    private fun scheduler(
        windowMs: Long = ClientConfig.DEFAULT_CONNECT_WINDOW_MS,
        retryMs: Long = ClientConfig.DEFAULT_RETRY_INTERVAL_MS,
    ) = ConnectionScheduler { ClientConfig.Values(windowMs, retryMs) }

    @Test
    fun networkAvailableOpensWindowAndStartsAttempt() {
        val s = scheduler()
        val actions = s.onNetworkAvailable(now = 1_000L)
        assertEquals(State.WINDOW_OPEN, s.state)
        assertEquals(
            listOf(
                Action.CancelTimers,
                Action.ScheduleWindowExpiry(ClientConfig.DEFAULT_CONNECT_WINDOW_MS),
                Action.WindowOpened,
                Action.StartAttempt,
            ),
            actions,
        )
    }

    @Test
    fun failureInsideWindowSchedulesRetryAtConfiguredInterval() {
        val s = scheduler(windowMs = 10_000L, retryMs = 7_000L)
        s.onNetworkAvailable(now = 0L)
        val actions = s.onSocketDisconnected(now = 3_000L)
        assertEquals(listOf<Action>(Action.ScheduleRetry(7_000L)), actions)
        assertFalse(actions.contains(Action.WindowOpened))
        assertEquals(State.WINDOW_OPEN, s.state)
    }

    @Test
    fun retryElapsedStartsNextAttemptWhileWindowOpen() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        assertEquals(listOf<Action>(Action.StartAttempt), s.onRetryElapsed())
    }

    @Test
    fun windowExpiryCancelsAttemptAndEntersSilence() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        val actions = s.onWindowExpired()
        assertEquals(State.SILENT, s.state)
        assertTrue(actions.contains(Action.CancelAttempt))
        assertTrue(actions.contains(Action.EnterSilence))
        // Once silent, stale socket callbacks and timers must produce no traffic.
        assertEquals(emptyList<Action>(), s.onSocketDisconnected(now = 99_000L))
        assertEquals(emptyList<Action>(), s.onRetryElapsed())
        assertEquals(emptyList<Action>(), s.onWindowExpired())
    }

    @Test
    fun failureAtOrPastDeadlineEntersSilenceWithoutRetry() {
        val s = scheduler(windowMs = 10_000L)
        s.onNetworkAvailable(now = 0L)
        val actions = s.onSocketDisconnected(now = 10_000L)
        assertEquals(State.SILENT, s.state)
        assertTrue(actions.contains(Action.EnterSilence))
    }

    @Test
    fun networkRegainedAfterSilenceOpensFreshWindow() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        s.onWindowExpired()
        assertEquals(State.SILENT, s.state)
        // Wi-Fi off does not reopen anything...
        s.onNetworkLost()
        assertEquals(State.IDLE, s.state)
        // ...Wi-Fi back on does — the non-reboot recovery path.
        val actions = s.onNetworkAvailable(now = 60_000L)
        assertEquals(State.WINDOW_OPEN, s.state)
        assertTrue(actions.contains(Action.StartAttempt))
    }

    @Test
    fun networkLostCancelsEverythingAndBlocksRetries() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        val actions = s.onNetworkLost()
        assertEquals(State.IDLE, s.state)
        assertTrue(actions.contains(Action.CancelTimers))
        assertTrue(actions.contains(Action.CancelAttempt))
        // A cancelled socket reporting failure while idle must not reopen a window.
        assertEquals(emptyList<Action>(), s.onSocketDisconnected(now = 1_000L))
        assertEquals(emptyList<Action>(), s.onRetryElapsed())
    }

    @Test
    fun establishedConnectionDropOpensFreshWindow() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        assertEquals(listOf<Action>(Action.CancelTimers), s.onConnected())
        assertEquals(State.CONNECTED, s.state)
        val actions = s.onSocketDisconnected(now = 500_000L)
        assertEquals(State.WINDOW_OPEN, s.state)
        assertTrue(actions.contains(Action.WindowOpened))
        assertTrue(actions.contains(Action.StartAttempt))
        assertTrue(actions.contains(Action.ScheduleWindowExpiry(ClientConfig.DEFAULT_CONNECT_WINDOW_MS)))
        // The fresh window is anchored on the drop time, not the original window.
        assertEquals(
            listOf<Action>(Action.ScheduleRetry(ClientConfig.DEFAULT_RETRY_INTERVAL_MS)),
            s.onSocketDisconnected(now = 500_001L)
        )
    }

    @Test
    fun networkAvailableWhileConnectedIsIgnored() {
        val s = scheduler()
        s.onNetworkAvailable(now = 0L)
        s.onConnected()
        assertEquals(emptyList<Action>(), s.onNetworkAvailable(now = 5_000L))
        assertEquals(State.CONNECTED, s.state)
    }

    @Test
    fun networkChangeMidWindowRestartsTheWindow() {
        val s = scheduler(windowMs = 10_000L)
        s.onNetworkAvailable(now = 0L)
        val actions = s.onNetworkAvailable(now = 8_000L)
        assertEquals(State.WINDOW_OPEN, s.state)
        assertTrue(actions.contains(Action.CancelAttempt))
        assertTrue(actions.contains(Action.StartAttempt))
        assertTrue(actions.contains(Action.ScheduleWindowExpiry(10_000L)))
        // Deadline moved to 8s + 10s: a failure at 12s is still inside the window.
        assertEquals(
            listOf<Action>(Action.ScheduleRetry(ClientConfig.DEFAULT_RETRY_INTERVAL_MS)),
            s.onSocketDisconnected(now = 12_000L)
        )
    }

    @Test
    fun customConfigValuesAreHonored() {
        val s = scheduler(windowMs = 60_000L, retryMs = 5_000L)
        val open = s.onNetworkAvailable(now = 0L)
        assertTrue(open.contains(Action.ScheduleWindowExpiry(60_000L)))
        assertEquals(listOf<Action>(Action.ScheduleRetry(5_000L)), s.onSocketDisconnected(now = 59_999L))
        s.onSocketDisconnected(now = 60_000L)
        assertEquals(State.SILENT, s.state)
    }

    @Test
    fun configIsReReadAtEachWindowOpen() {
        var values = ClientConfig.Values(connectWindowMs = 10_000L, retryIntervalMs = 2_000L)
        val s = ConnectionScheduler { values }
        s.onNetworkAvailable(now = 0L)
        s.onWindowExpired()
        // Operator pushes a new config while the device sits silent.
        values = ClientConfig.Values(connectWindowMs = 30_000L, retryIntervalMs = 9_000L)
        val actions = s.onNetworkAvailable(now = 100_000L)
        assertTrue(actions.contains(Action.ScheduleWindowExpiry(30_000L)))
        assertEquals(listOf<Action>(Action.ScheduleRetry(9_000L)), s.onSocketDisconnected(now = 100_001L))
    }
}
