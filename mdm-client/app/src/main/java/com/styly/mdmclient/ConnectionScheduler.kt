package com.styly.mdmclient

/**
 * Decides when the client may generate network traffic (#67). In serverless
 * deployments there is usually no mdm-server to find, so instead of retrying
 * forever the client gets a bounded connection window anchored on network
 * availability: attempts run inside the window on a fixed retry interval, and
 * when the window expires without a connection the client goes fully silent —
 * no WebSocket attempts, no UDP discovery — until the network transitions
 * again (Wi-Fi off/on, reboot, process restart).
 *
 * Pure Kotlin: the current time and the config values are injected and every
 * side effect is returned as an [Action] for the caller to execute, so the
 * window logic runs under plain JUnit like [PowerCycleSchedule].
 */
class ConnectionScheduler(
    private val config: () -> ClientConfig.Values,
) {

    enum class State { IDLE, WINDOW_OPEN, CONNECTED, SILENT }

    sealed class Action {
        /** A fresh connection window opened; arm policies that run once per window. */
        object WindowOpened : Action()

        /** Begin the next connection attempt now. */
        object StartAttempt : Action()

        /** Re-enter [StartAttempt] after [delayMs] (report via onRetryElapsed). */
        data class ScheduleRetry(val delayMs: Long) : Action()

        /** Arm the window deadline timer (report via onWindowExpired). */
        data class ScheduleWindowExpiry(val delayMs: Long) : Action()

        /** Remove any pending retry and window-expiry timers. */
        object CancelTimers : Action()

        /** Abort the in-flight attempt: cancel the socket without a close handshake. */
        object CancelAttempt : Action()

        /** No server was found within the window — surface standby status. */
        object EnterSilence : Action()
    }

    var state: State = State.IDLE
        private set

    private var windowDeadline = 0L
    private var retryIntervalMs = ClientConfig.DEFAULT_RETRY_INTERVAL_MS

    /** A (new) default network became available. */
    fun onNetworkAvailable(now: Long): List<Action> = when (state) {
        State.IDLE, State.SILENT -> openWindow(now, cancelAttempt = false)
        // The default network changed mid-window: restart the cycle on the new one.
        State.WINDOW_OPEN -> openWindow(now, cancelAttempt = true)
        // A healthy connection stays as-is; if the network really changed under
        // it the socket dies and onSocketDisconnected reopens a window.
        State.CONNECTED -> emptyList()
    }

    /** The default network was lost: stop all traffic until one returns. */
    fun onNetworkLost(): List<Action> = when (state) {
        State.IDLE -> emptyList()
        else -> {
            state = State.IDLE
            listOf(Action.CancelTimers, Action.CancelAttempt)
        }
    }

    /** The WebSocket reached onOpen. */
    fun onConnected(): List<Action> {
        state = State.CONNECTED
        return listOf(Action.CancelTimers)
    }

    /** The socket died — a failed attempt, or an established connection dropping. */
    fun onSocketDisconnected(now: Long): List<Action> = when (state) {
        // The network is still up (otherwise onNetworkLost already ran), so an
        // established connection dropping opens a fresh window.
        State.CONNECTED -> openWindow(now, cancelAttempt = false)
        State.WINDOW_OPEN ->
            if (now >= windowDeadline) goSilent()
            else listOf(Action.ScheduleRetry(retryIntervalMs))
        // Stale callback from a cancelled socket.
        State.IDLE, State.SILENT -> emptyList()
    }

    /** A [Action.ScheduleRetry] timer fired. */
    fun onRetryElapsed(): List<Action> = when (state) {
        State.WINDOW_OPEN -> listOf(Action.StartAttempt)
        else -> emptyList()
    }

    /** A [Action.ScheduleWindowExpiry] timer fired. */
    fun onWindowExpired(): List<Action> = when (state) {
        State.WINDOW_OPEN -> goSilent()
        else -> emptyList()
    }

    private fun openWindow(now: Long, cancelAttempt: Boolean): List<Action> {
        // Config is re-read on every window open so an updated config file
        // takes effect on the next network transition or disconnect.
        val values = config()
        state = State.WINDOW_OPEN
        windowDeadline = now + values.connectWindowMs
        retryIntervalMs = values.retryIntervalMs
        val actions = mutableListOf<Action>(Action.CancelTimers)
        if (cancelAttempt) actions.add(Action.CancelAttempt)
        actions.add(Action.ScheduleWindowExpiry(values.connectWindowMs))
        actions.add(Action.WindowOpened)
        actions.add(Action.StartAttempt)
        return actions
    }

    private fun goSilent(): List<Action> {
        state = State.SILENT
        return listOf(Action.CancelTimers, Action.CancelAttempt, Action.EnterSilence)
    }
}
