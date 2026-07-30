package com.styly.mdmclient

/**
 * Keeps the one-shot manual URL preference separate from the connection-window
 * state machine. A new window arms one manual attempt; only a previously
 * established connection dropping re-arms it inside the current process.
 */
internal class ManualServerUrlAttempt {
    private var pendingUrl: String? = null

    fun onWindowOpened(manualUrl: String?) {
        pendingUrl = manualUrl
    }

    fun onSocketGone(wasConnected: Boolean, manualUrl: String?) {
        if (wasConnected) {
            pendingUrl = manualUrl
        }
    }

    fun take(): String? {
        val url = pendingUrl
        pendingUrl = null
        return url
    }
}
