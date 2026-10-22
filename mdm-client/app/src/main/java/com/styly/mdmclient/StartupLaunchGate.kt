package com.styly.mdmclient

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Recovery completion and its independent deadline compete for one service launch. */
internal class StartupLaunchGate {
    private val claimedOrClosed = AtomicBoolean(false)

    fun start(recovery: CompletableFuture<Boolean>, post: (Runnable, Long) -> Unit, launch: () -> Unit) {
        val attempt = Runnable { if (claimedOrClosed.compareAndSet(false, true)) launch() }
        post(attempt, WIFI_MANUAL_TIMEOUT_MS)
        recovery.whenComplete { _, _ -> post(attempt, 0L) }
    }

    fun close() { claimedOrClosed.set(true) }
}
