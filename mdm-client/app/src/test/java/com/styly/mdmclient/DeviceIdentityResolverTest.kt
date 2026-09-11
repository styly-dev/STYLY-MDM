package com.styly.mdmclient

import com.styly.deviceid.DeviceIdStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class DeviceIdentityResolverTest {
    private val canonical = "64b19041-0b8c-4ef4-82fd-000000000000"

    @Test
    fun pendingProviderStaysResolvingThenFreezesCanonicalIdentity() {
        val request = CompletableFuture<DeviceIdentityLookupResult>()
        val states = mutableListOf<DeviceIdentityState>()
        var calls = 0
        val resolver = DeviceIdentityResolver {
            calls++
            request
        }
        resolver.addListener { states += it }

        assertTrue(resolver.startInitialLookup())
        repeat(3) { assertFalse(resolver.startInitialLookup()) }
        assertEquals(DeviceIdentityState.Resolving, resolver.snapshot())
        assertEquals(listOf(DeviceIdentityState.Resolving), states)
        assertEquals(1, calls)

        request.complete(DeviceIdentityLookupResult(
            DeviceIdStatus.SUCCESS, canonical.uppercase(), false, "",
        ))

        assertEquals(DeviceIdentityState.Ready(canonical), resolver.snapshot())
        assertEquals(listOf(DeviceIdentityState.Resolving, DeviceIdentityState.Ready(canonical)), states)
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }

    @Test
    fun permissionAndIoFailuresAreTerminalWithoutMdmRetries() {
        for ((providerStatus, status) in listOf(
            DeviceIdStatus.ACCESS_DENIED to DeviceIdentityStatus.ACCESS_DENIED,
            DeviceIdStatus.IO_ERROR to DeviceIdentityStatus.IO_ERROR,
            DeviceIdStatus.UNSUPPORTED_API to DeviceIdentityStatus.UNSUPPORTED_API,
        )) {
            var calls = 0
            val resolver = DeviceIdentityResolver {
                calls++
                CompletableFuture.completedFuture(DeviceIdentityLookupResult(
                    providerStatus, null, false, "provider\nfailed",
                ))
            }

            assertTrue(resolver.startInitialLookup())
            assertEquals(DeviceIdentityState.Unavailable(status, "provider failed", false), resolver.snapshot())
            assertFalse(resolver.startInitialLookup())
            assertEquals(1, calls)
        }
    }

    @Test
    fun completionListenerCannotRestartLookup() {
        val request = CompletableFuture<DeviceIdentityLookupResult>()
        var calls = 0
        val resolver = DeviceIdentityResolver { calls++; request }
        val restartResults = mutableListOf<Boolean>()
        resolver.addListener { state ->
            if (state is DeviceIdentityState.Unavailable) {
                restartResults += resolver.startInitialLookup()
            }
        }
        resolver.startInitialLookup()

        request.complete(DeviceIdentityLookupResult(DeviceIdStatus.IO_ERROR, null, true, "failed"))

        assertEquals(listOf(false), restartResults)
        assertEquals(1, calls)
        assertTrue((resolver.snapshot() as DeviceIdentityState.Unavailable).mintAttempted)
    }

    @Test
    fun invalidCanonicalGuidIsRejected() {
        val resolver = DeviceIdentityResolver {
            CompletableFuture.completedFuture(DeviceIdentityLookupResult(
                DeviceIdStatus.SUCCESS, "not-a-canonical-guid", true, "provider returned success",
            ))
        }
        resolver.startInitialLookup()

        assertEquals(DeviceIdentityState.Unavailable(
            DeviceIdentityStatus.IO_ERROR,
            "Device ID provider returned an invalid canonical GUID", true,
        ), resolver.snapshot())
        assertFalse(resolver.startInitialLookup())
    }

    @Test
    fun providerStartExceptionIsTerminal() {
        val resolver = DeviceIdentityResolver { error("provider\nexception") }

        assertTrue(resolver.startInitialLookup())

        assertEquals(DeviceIdentityState.Unavailable(
            DeviceIdentityStatus.IO_ERROR, "provider exception", false,
        ), resolver.snapshot())
        assertFalse(resolver.startInitialLookup())
    }

    @Test
    fun asyncTimeoutKeepsReadinessCauseAndPublishesFailureOnce() {
        val providerRequest = CompletableFuture<DeviceIdentityLookupResult>()
        val request = providerRequest.thenApply { it }
        val resolver = DeviceIdentityResolver { request }
        val states = mutableListOf<DeviceIdentityState>()
        resolver.addListener { states += it }
        resolver.startInitialLookup()
        val timeout = TimeoutException("Lookup timed out").apply {
            initCause(IllegalStateException("Primary storage is\nunmounted"))
        }

        providerRequest.completeExceptionally(timeout)

        val failure = resolver.snapshot() as DeviceIdentityState.Unavailable
        assertEquals(DeviceIdentityStatus.IO_ERROR, failure.status)
        assertTrue(failure.diagnostic.contains("TimeoutException: Lookup timed out"))
        assertTrue(failure.diagnostic.contains("Primary storage is unmounted"))
        assertTrue(failure.diagnostic.contains("in-flight lookup may still create an ID"))
        assertEquals(2, states.size)
        assertFalse(resolver.startInitialLookup())
    }

    @Test
    fun asyncExceptionDiagnosticIsBoundedAndSanitized() {
        val request = CompletableFuture<DeviceIdentityLookupResult>()
        val resolver = DeviceIdentityResolver { request }
        resolver.startInitialLookup()

        request.completeExceptionally(CompletionException(IllegalStateException("bad\r\n" + "x".repeat(400))))

        val failure = resolver.snapshot() as DeviceIdentityState.Unavailable
        assertEquals(256, failure.diagnostic.length)
        assertTrue(failure.diagnostic.startsWith("bad "))
        assertFalse(failure.diagnostic.contains('\n'))
    }

    @Test
    fun lateListenerReceivesResolvedIdentityAndRemovedListenerIsNotNotified() {
        val request = CompletableFuture<DeviceIdentityLookupResult>()
        val resolver = DeviceIdentityResolver { request }
        val removedStates = mutableListOf<DeviceIdentityState>()
        val removed: (DeviceIdentityState) -> Unit = { removedStates += it }
        resolver.addListener(removed)
        resolver.removeListener(removed)
        resolver.startInitialLookup()
        request.complete(DeviceIdentityLookupResult(DeviceIdStatus.SUCCESS, canonical, false, ""))
        val lateStates = mutableListOf<DeviceIdentityState>()
        resolver.addListener { lateStates += it }

        assertEquals(listOf(DeviceIdentityState.Resolving), removedStates)
        assertEquals(listOf(DeviceIdentityState.Ready(canonical)), lateStates)
    }

    @Test
    fun permissionRetryResolvesWithoutRestartAndRemainsSingleFlight() {
        val initial = CompletableFuture<DeviceIdentityLookupResult>()
        val recovery = CompletableFuture<DeviceIdentityLookupResult>()
        var calls = 0
        val resolver = DeviceIdentityResolver { if (++calls == 1) initial else recovery }
        val states = mutableListOf<DeviceIdentityState>()
        resolver.addListener { states += it }
        resolver.startInitialLookup()
        assertFalse(resolver.retryAfterPermissionGranted())
        initial.complete(DeviceIdentityLookupResult(DeviceIdStatus.ACCESS_DENIED, null, false, "Denied"))

        assertTrue(resolver.retryAfterPermissionGranted())
        repeat(3) { assertFalse(resolver.retryAfterPermissionGranted()) }
        assertEquals(DeviceIdentityState.Resolving, resolver.snapshot())
        recovery.complete(DeviceIdentityLookupResult(DeviceIdStatus.SUCCESS, canonical, false, ""))

        assertEquals(DeviceIdentityState.Ready(canonical), resolver.snapshot())
        assertFalse(resolver.retryAfterPermissionGranted())
        assertFalse(resolver.startInitialLookup())
        assertEquals(2, calls)
        assertEquals(4, states.size)
        assertEquals(DeviceIdentityState.Resolving, states[2])
    }

    @Test
    fun permissionRetryDoesNotRetryIoErrorsOrMintAttempts() {
        for ((status, minted) in listOf(
            DeviceIdStatus.IO_ERROR to false,
            DeviceIdStatus.UNSUPPORTED_API to false,
            DeviceIdStatus.ACCESS_DENIED to true,
        )) {
            var calls = 0
            val resolver = DeviceIdentityResolver {
                calls++
                CompletableFuture.completedFuture(DeviceIdentityLookupResult(status, null, minted, "Failed"))
            }
            resolver.startInitialLookup()
            assertFalse(resolver.retryAfterPermissionGranted())
            assertEquals(1, calls)
        }
    }

    @Test
    fun permissionRetryFailureDoesNotStartAnAutomaticLoop() {
        var calls = 0
        val resolver = DeviceIdentityResolver {
            calls++
            CompletableFuture.completedFuture(DeviceIdentityLookupResult(
                DeviceIdStatus.ACCESS_DENIED, null, false, "Still denied",
            ))
        }
        resolver.startInitialLookup()
        assertTrue(resolver.retryAfterPermissionGranted())
        assertEquals(2, calls)
        assertTrue(resolver.snapshot() is DeviceIdentityState.Unavailable)
    }


    @Test
    fun dependencyLinkageErrorsBecomeUnavailable() {
        for (failure in listOf(
            NoClassDefFoundError("Missing dependency"),
            ExceptionInInitializerError("Initialization failed"),
        )) {
            val resolver = DeviceIdentityResolver { throw failure }
            assertTrue(resolver.startInitialLookup())
            val state = resolver.snapshot() as DeviceIdentityState.Unavailable
            assertEquals(DeviceIdentityStatus.IO_ERROR, state.status)
            assertTrue(state.diagnostic.isNotBlank())
            assertFalse(resolver.retryAfterPermissionGranted())
        }
    }

    @Test
    fun permissionRecoveryCannotOvertakePendingFailureNotifications() {
        val mainQueue = ArrayDeque<Runnable>()
        val initial = CompletableFuture<DeviceIdentityLookupResult>()
        var calls = 0
        val resolver = DeviceIdentityResolver(dispatchCompletion = { mainQueue.add(it); Unit }) {
            calls++
            if (calls == 1) initial else CompletableFuture.completedFuture(
                DeviceIdentityLookupResult(DeviceIdStatus.SUCCESS, canonical, false, ""),
            )
        }
        val socketStates = mutableListOf<DeviceIdentityState>()
        // Settings registers first and schedules recovery, just as the real UI does.
        resolver.addListener { state ->
            if (state is DeviceIdentityState.Unavailable) {
                mainQueue.add(Runnable { resolver.retryAfterPermissionGranted() })
            }
        }
        resolver.addListener { socketStates += it }
        resolver.startInitialLookup()
        val provider = Thread {
            initial.complete(DeviceIdentityLookupResult(DeviceIdStatus.ACCESS_DENIED, null, false, "Denied"))
        }
        provider.start()
        provider.join(2_000)
        assertFalse(provider.isAlive)

        // A worker completion must not publish inline or run Settings callbacks.
        assertEquals(listOf(DeviceIdentityState.Resolving), socketStates)
        assertEquals(DeviceIdentityState.Resolving, resolver.snapshot())
        mainQueue.removeFirst().run()
        assertTrue(socketStates.last() is DeviceIdentityState.Unavailable)
        assertEquals(1, calls)
        mainQueue.removeFirst().run()
        assertEquals(DeviceIdentityState.Resolving, socketStates.last())
        mainQueue.removeFirst().run()

        assertEquals(listOf(
            DeviceIdentityState.Resolving,
            DeviceIdentityState.Unavailable(DeviceIdentityStatus.ACCESS_DENIED, "Denied", false),
            DeviceIdentityState.Resolving,
            DeviceIdentityState.Ready(canonical),
        ), socketStates)
        assertTrue(mainQueue.isEmpty())
        assertEquals(2, calls)
    }

}
