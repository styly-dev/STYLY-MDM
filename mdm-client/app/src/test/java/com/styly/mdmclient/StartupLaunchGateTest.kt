package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CompletableFuture

class StartupLaunchGateTest {
    @Test fun `successful failed and exceptional recovery launch immediately only once`() {
        for (outcome in listOf("success", "failed", "exception")) {
            val harness = Harness()
            when (outcome) {
                "success" -> harness.recovery.complete(true)
                "failed" -> harness.recovery.complete(false)
                else -> harness.recovery.completeExceptionally(IllegalStateException())
            }
            harness.advanceTo(0L)
            assertEquals(outcome, 1, harness.launches)
            harness.advanceTo(60_000L)
            assertEquals(outcome, 1, harness.launches)
        }
    }

    @Test fun `hung recovery launches at sixty seconds and late completion cannot launch again`() {
        val harness = Harness()
        harness.advanceTo(59_999L)
        assertEquals(0, harness.launches)
        harness.advanceTo(60_000L)
        assertEquals(1, harness.launches)
        harness.recovery.complete(true)
        harness.advanceTo(60_001L)
        assertEquals(1, harness.launches)
    }

    @Test fun `destroyed service cannot launch from deadline or late completion`() {
        val harness = Harness()
        harness.gate.close()
        harness.advanceTo(60_000L)
        harness.recovery.complete(false)
        harness.advanceTo(60_001L)
        assertEquals(0, harness.launches)
    }

    private class Harness {
        val gate = StartupLaunchGate()
        val recovery = CompletableFuture<Boolean>()
        var launches = 0
        private var now = 0L
        private val scheduled = mutableListOf<Pair<Long, Runnable>>()
        init {
            gate.start(recovery, { task, delay -> scheduled += (now + delay) to task }, { launches++ })
        }
        fun advanceTo(target: Long) {
            while (true) {
                val next = scheduled.minByOrNull { it.first }?.takeIf { it.first <= target } ?: break
                scheduled.remove(next)
                now = next.first
                next.second.run()
            }
            now = target
        }
    }
}
