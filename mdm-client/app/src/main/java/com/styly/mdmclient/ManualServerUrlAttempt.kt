package com.styly.mdmclient

/**
 * Keeps the one-shot manual URL preference separate from the connection-window
 * state machine. A new connection window arms one manual attempt.
 */
internal class ManualServerUrlAttempt {
    private var pendingUrl: String? = null

    fun onWindowOpened(manualUrl: String?) {
        pendingUrl = manualUrl
    }

    fun take(): String? {
        val url = pendingUrl
        pendingUrl = null
        return url
    }
}
