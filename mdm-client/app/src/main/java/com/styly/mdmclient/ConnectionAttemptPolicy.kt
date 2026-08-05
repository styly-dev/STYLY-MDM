package com.styly.mdmclient

/** Keeps the connection target stable for the lifetime of a connection window. */
internal class ConnectionAttemptPolicy {

    sealed class Target {
        data class Manual(val url: String) : Target()
        object AutoDiscovery : Target()
    }

    private var target: Target = Target.AutoDiscovery

    fun onWindowOpened(manualUrl: String?) {
        target = if (manualUrl != null) {
            Target.Manual(manualUrl)
        } else {
            Target.AutoDiscovery
        }
    }

    fun targetForAttempt(): Target = target
}
