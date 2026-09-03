package com.styly.mdmclient

import com.styly.deviceid.DeviceIdStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class DeviceIdentityResolverTest {
    private val directExecutor = Executor { it.run() }

    @Test
    fun successFreezesCanonicalIdentity() {
        var calls = 0
        val resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(
                DeviceIdStatus.SUCCESS,
                "64B19041-0B8C-4EF4-82FD-000000000000",
                2,
                true,
                "",
            )
        }

        assertTrue(resolver.startInitialLookup())
        assertEquals(
            DeviceIdentityState.Ready(
                "64b19041-0b8c-4ef4-82fd-000000000000",
                2,
                true,
            ),
            resolver.snapshot(),
        )
        assertFalse(resolver.retry())
        assertEquals(1, calls)
    }

    @Test
    fun unavailableResultRetriesOnlyWhenExplicitlyRequested() {
        var calls = 0
        val resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(
                DeviceIdStatus.ACCESS_DENIED,
                null,
                0,
                false,
                "permission\nrequired",
            )
        }

        assertTrue(resolver.startInitialLookup())
        assertFalse(resolver.startInitialLookup())
        assertEquals(
            DeviceIdentityState.Unavailable(
                DeviceIdentityStatus.ACCESS_DENIED,
                "permission required",
                false,
            ),
            resolver.snapshot(),
        )
        assertTrue(resolver.retry())
        assertEquals(2, calls)
    }

    @Test
    fun overlappingRetryIsSingleFlight() {
        val queued = mutableListOf<Runnable>()
        val resolver = DeviceIdentityResolver(Executor { queued += it }) {
            DeviceIdentityLookupResult(DeviceIdStatus.IO_ERROR, null, 0, false, "failed")
        }

        assertTrue(resolver.startInitialLookup())
        assertFalse(resolver.retry())
        assertEquals(1, queued.size)
        queued.single().run()
        assertTrue(resolver.retry())
        assertEquals(2, queued.size)
    }

    @Test
    fun completionListenerCannotStartRetryBeforeCompletionFinishesPublishing() {
        var calls = 0
        val retryResults = mutableListOf<Boolean>()
        lateinit var resolver: DeviceIdentityResolver
        resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(DeviceIdStatus.IO_ERROR, null, 0, false, "failed")
        }
        resolver.addListener { state ->
            if (state is DeviceIdentityState.Unavailable && retryResults.isEmpty()) {
                retryResults += resolver.retry()
            }
        }

        assertTrue(resolver.startInitialLookup())

        assertEquals(listOf(false), retryResults)
        assertEquals(1, calls)
        assertTrue(resolver.retry())
        assertEquals(2, calls)
    }
}
