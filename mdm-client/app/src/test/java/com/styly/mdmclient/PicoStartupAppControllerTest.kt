package com.styly.mdmclient

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PicoStartupAppControllerTest {
    private class FakeSocket : WebSocket {
        val messages = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }

    private fun await(queue: ArrayBlockingQueue<PicoStartupAppController.Result>): PicoStartupAppController.Result =
        queue.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for PICO API result")

    @Test
    fun explicitNullRestoresNativeHomeWithoutCheckingPackageLaunchability() {
        val results = ArrayBlockingQueue<PicoStartupAppController.Result>(2)
        val launcherCalls = AtomicInteger()
        val launcherArgument = AtomicReference<String?>()
        val controller = PicoStartupAppController(
            setLauncher = { launcherCalls.incrementAndGet(); launcherArgument.set(it); 0 },
            isLaunchable = { throw AssertionError("Restoring native Home must not check an app package") },
        )

        try {
            controller.submit(null) { results.add(it) }

            assertEquals(PicoStartupAppController.Result("accepted", resultCode = 0), await(results))
            assertEquals(1, launcherCalls.get())
            assertNull(launcherArgument.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun invalidPackagesAreRejectedBeforeAnyDeviceOrSdkCheck() {
        val results = ArrayBlockingQueue<PicoStartupAppController.Result>(8)
        val sdkCalls = AtomicInteger()
        val launchabilityChecks = AtomicInteger()
        val controller = PicoStartupAppController(
            setLauncher = { sdkCalls.incrementAndGet(); 0 },
            isLaunchable = { launchabilityChecks.incrementAndGet(); true },
        )
        val invalidPackages = listOf(
            "",
            "example",
            "com.example.",
            "com.9example.app",
            "com.example.bad-name",
            " com.example.app",
            "x".repeat(256),
        )

        try {
            invalidPackages.forEach { packageName ->
                controller.submit(packageName) { results.add(it) }
                assertEquals(
                    "Invalid PICO startup command",
                    await(results).error,
                )
            }
            assertEquals(0, sdkCalls.get())
            assertEquals(0, launchabilityChecks.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun uninstalledOrDisabledPackageIsRejectedWithoutCallingSetLauncher() {
        val results = ArrayBlockingQueue<PicoStartupAppController.Result>(1)
        val sdkCalls = AtomicInteger()
        val controller = PicoStartupAppController(
            setLauncher = { sdkCalls.incrementAndGet(); 0 },
            isLaunchable = { it == "com.example.installed" },
        )

        try {
            controller.submit("com.example.notInstalled") { results.add(it) }

            val result = await(results)
            assertEquals("fail", result.status)
            assertEquals("Package is not an installed, enabled launchable app", result.error)
            assertEquals(0, sdkCalls.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun sdkReturnCodeDeterminesAcceptedOrFailResult() {
        listOf(0 to "accepted", -2 to "fail").forEach { (code, status) ->
            val results = ArrayBlockingQueue<PicoStartupAppController.Result>(1)
            val controller = PicoStartupAppController(
                setLauncher = { code },
                isLaunchable = { true },
            )

            try {
                controller.submit("com.example.app") { results.add(it) }
                val result = await(results)
                assertEquals(status, result.status)
                assertEquals(code, result.resultCode)
                if (code != 0) {
                    assertEquals(
                        "PICO API returned a nonzero result; verify on the device before retrying",
                        result.error,
                    )
                }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun sdkExceptionAndMissingSdkClassBecomeFailureResults() {
        val failures: List<() -> Int> = listOf(
            { throw IllegalStateException("binder failure") },
            { throw NoClassDefFoundError("missing TobService class") },
        )

        failures.forEachIndexed { index, setLauncher ->
            val results = ArrayBlockingQueue<PicoStartupAppController.Result>(1)
            val controller = PicoStartupAppController({ _ -> setLauncher() }, isLaunchable = { true })
            try {
                controller.submit("com.example.app") { results.add(it) }
                val result = await(results)
                assertEquals("fail", result.status)
                if (index == 0) {
                    assertEquals("PICO API failed: IllegalStateException", result.error)
                } else {
                    assertEquals("PICO startup API is unavailable", result.error)
                }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun timedOutBinderStaysBusyAndItsLateReturnDoesNotSendAnotherResult() {
        val entered = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstResults = ArrayBlockingQueue<PicoStartupAppController.Result>(3)
        val secondResults = ArrayBlockingQueue<PicoStartupAppController.Result>(1)
        val sdkCalls = AtomicInteger()
        val controller = PicoStartupAppController(
            setLauncher = {
                val call = sdkCalls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    try {
                        releaseFirst.await(2, TimeUnit.SECONDS)
                    } finally {
                        firstReturned.countDown()
                    }
                }
                0
            },
            isLaunchable = { true },
            timeoutMs = 150,
        )

        try {
            controller.submit("com.example.first") { firstResults.add(it) }
            assertTrue("SDK call did not start", entered.await(1, TimeUnit.SECONDS))
            assertEquals("unknown", await(firstResults).status)

            controller.submit("com.example.second") { firstResults.add(it) }
            val busy = await(firstResults)
            assertEquals("fail", busy.status)
            assertEquals("A PICO startup command is still running", busy.error)
            assertEquals(1, sdkCalls.get())

            releaseFirst.countDown()
            assertTrue("Timed out waiting for the first SDK call to return", firstReturned.await(1, TimeUnit.SECONDS))
            // The worker releases its slot just after the uncancellable first call ends.
            // Retry only while that final cleanup is still holding the busy slot.
            var second: PicoStartupAppController.Result? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (second == null && System.nanoTime() < deadline) {
                controller.submit("com.example.second") { secondResults.add(it) }
                val result = await(secondResults)
                if (result.error != "A PICO startup command is still running") {
                    second = result
                } else {
                    Thread.yield()
                }
            }
            assertTrue("Second SDK call did not complete", second != null)
            assertEquals("accepted", second!!.status)
            assertEquals(0, second!!.resultCode)

            assertNull("Late binder completion must not emit a second result", firstResults.poll(150, TimeUnit.MILLISECONDS))
            assertEquals(2, sdkCalls.get())
        } finally {
            releaseFirst.countDown()
            controller.close()
        }
    }

    @Test
    fun closeSuppressesResultsAndRejectsLaterSubmissions() {
        val entered = CountDownLatch(1)
        val callUnwound = CountDownLatch(1)
        val resultCount = AtomicInteger()
        val controller = PicoStartupAppController(
            setLauncher = {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                    0
                } finally {
                    callUnwound.countDown()
                }
            },
            isLaunchable = { true },
            timeoutMs = 5_000,
        )

        controller.submit("com.example.app", { resultCount.incrementAndGet() })
        assertTrue("SDK call did not start", entered.await(1, TimeUnit.SECONDS))
        controller.close()
        controller.submit("com.example.afterClose", { resultCount.incrementAndGet() })

        // shutdownNow interrupts the blocked wrapper; after it unwinds, the worker
        // must pass through the closed-result guard without invoking the callback.
        assertTrue("Worker did not unwind after close", callUnwound.await(1, TimeUnit.SECONDS))
        assertEquals(0, resultCount.get())
    }

    @Test
    fun lateSdkResultAfterSocketReplacementIsSentToNeitherConnection() {
        val sdkEntered = CountDownLatch(1)
        val releaseSdk = CountDownLatch(1)
        val resultAttempted = CountDownLatch(1)
        val original = FakeSocket()
        val replacement = FakeSocket()
        var currentSocket: WebSocket? = original
        val registration = ConnectionRegistration<WebSocket>().apply {
            open(original)
            markSent(original)
            acknowledge(original)
        }
        val commandContext = WebSocketManager.CommandContext(original, null, registration) { currentSocket }
        val sentOnOriginalContext = AtomicReference<Boolean>()
        val controller = PicoStartupAppController(
            setLauncher = {
                sdkEntered.countDown()
                releaseSdk.await(2, TimeUnit.SECONDS)
                0
            },
            isLaunchable = { true },
            timeoutMs = 5_000,
        )

        try {
            controller.submit("com.example.app") {
                sentOnOriginalContext.set(
                    commandContext.sendMessage("{\"type\":\"PICO_API_RESULT\",\"status\":\"accepted\"}")
                )
                resultAttempted.countDown()
            }
            assertTrue("SDK call did not start", sdkEntered.await(1, TimeUnit.SECONDS))

            // A replacement socket registers and acknowledges while the old binder
            // call is still blocked. Its eventual callback remains bound to the old
            // CommandContext and must not be redirected to the new session.
            registration.open(replacement)
            registration.markSent(replacement)
            assertTrue(registration.acknowledge(replacement))
            currentSocket = replacement

            releaseSdk.countDown()
            assertTrue("SDK result callback did not run", resultAttempted.await(1, TimeUnit.SECONDS))

            assertFalse(sentOnOriginalContext.get())
            assertTrue(original.messages.isEmpty())
            assertTrue(replacement.messages.isEmpty())
        } finally {
            releaseSdk.countDown()
            controller.close()
        }
    }
}
