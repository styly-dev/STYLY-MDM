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
                true,
                "",
            )
        }

        assertTrue(resolver.startInitialLookup())
        assertEquals(
            DeviceIdentityState.Ready(
                "64b19041-0b8c-4ef4-82fd-000000000000",
            ),
            resolver.snapshot(),
        )
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }

    @Test
    fun unavailableResultDoesNotRetryInTheSameProcess() {
        var calls = 0
        val resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(
                DeviceIdStatus.ACCESS_DENIED,
                null,
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
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }

    @Test
    fun overlappingLookupIsSingleFlight() {
        val queued = mutableListOf<Runnable>()
        val resolver = DeviceIdentityResolver(Executor { queued += it }) {
            DeviceIdentityLookupResult(DeviceIdStatus.IO_ERROR, null, false, "failed")
        }

        assertTrue(resolver.startInitialLookup())
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, queued.size)
        queued.single().run()
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, queued.size)
    }

    @Test
    fun completionListenerCannotRestartLookup() {
        var calls = 0
        val retryResults = mutableListOf<Boolean>()
        lateinit var resolver: DeviceIdentityResolver
        resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(DeviceIdStatus.IO_ERROR, null, false, "failed")
        }
        resolver.addListener { state ->
            if (state is DeviceIdentityState.Unavailable && retryResults.isEmpty()) {
                retryResults += resolver.startInitialLookup()
            }
        }

        assertTrue(resolver.startInitialLookup())

        assertEquals(listOf(false), retryResults)
        assertEquals(1, calls)
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }

    @Test
    fun invalidGuidFromSuccessfulProviderIsReportedWithoutRetry() {
        var calls = 0
        val resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            DeviceIdentityLookupResult(
                DeviceIdStatus.SUCCESS,
                "not-a-canonical-guid",
                true,
                "provider returned success",
            )
        }

        assertTrue(resolver.startInitialLookup())
        assertEquals(
            DeviceIdentityState.Unavailable(
                DeviceIdentityStatus.IO_ERROR,
                "Device ID provider returned an invalid canonical GUID",
                true,
            ),
            resolver.snapshot(),
        )
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }

    @Test
    fun providerExceptionIsReportedWithoutRetry() {
        var calls = 0
        val resolver = DeviceIdentityResolver(directExecutor) {
            calls += 1
            error("provider\nexception")
        }

        assertTrue(resolver.startInitialLookup())
        assertEquals(
            DeviceIdentityState.Unavailable(
                DeviceIdentityStatus.IO_ERROR,
                "provider exception",
                false,
            ),
            resolver.snapshot(),
        )
        assertFalse(resolver.startInitialLookup())
        assertEquals(1, calls)
    }
}
