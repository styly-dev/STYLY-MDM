package com.styly.mdmclient

import android.content.Context
import com.styly.deviceid.DeviceIdProvider
import com.styly.deviceid.DeviceIdStatus
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

sealed interface DeviceIdentityState {
    data object Resolving : DeviceIdentityState

    data class Ready(
        val deviceId: String,
        val candidateCount: Int,
        val wasMinted: Boolean,
    ) : DeviceIdentityState

    data class Unavailable(
        val status: DeviceIdentityStatus,
        val diagnostic: String,
        val mintAttempted: Boolean,
    ) : DeviceIdentityState
}

enum class DeviceIdentityStatus(val protocolValue: String) {
    ACCESS_DENIED("access_denied"),
    IO_ERROR("io_error"),
    UNSUPPORTED_API("unsupported_api"),
}

internal data class DeviceIdentityLookupResult(
    val status: DeviceIdStatus,
    val deviceId: String?,
    val candidateCount: Int,
    val mintAttempted: Boolean,
    val diagnostic: String,
)

/** Process-wide, single-flight owner of the canonical MediaStore identity lookup. */
class DeviceIdentityResolver internal constructor(
    private val executor: Executor,
    private val lookup: () -> DeviceIdentityLookupResult,
) {
    companion object {
        private const val MAX_DIAGNOSTIC_LENGTH = 256
        private val CANONICAL_GUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )

        fun create(context: Context): DeviceIdentityResolver {
            val appContext = context.applicationContext
            return DeviceIdentityResolver(
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "device-identity-resolver")
                }
            ) {
                val result = DeviceIdProvider.getOrCreate(appContext)
                DeviceIdentityLookupResult(
                    status = result.status,
                    deviceId = result.deviceId,
                    candidateCount = result.candidateCount,
                    mintAttempted = result.wasMintAttempted(),
                    diagnostic = result.diagnosticMessage,
                )
            }
        }

        internal fun sanitizeDiagnostic(value: String): String = value
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(MAX_DIAGNOSTIC_LENGTH)
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<(DeviceIdentityState) -> Unit>()
    @Volatile
    private var visibleState: DeviceIdentityState = DeviceIdentityState.Resolving
    private var lookupStarted = false
    private var lookupInFlight = false

    fun snapshot(): DeviceIdentityState = visibleState

    fun addListener(listener: (DeviceIdentityState) -> Unit) {
        listeners.add(listener)
        listener(visibleState)
    }

    fun removeListener(listener: (DeviceIdentityState) -> Unit) {
        listeners.remove(listener)
    }

    fun startInitialLookup(): Boolean = startLookup(isRetry = false)

    fun retry(): Boolean = startLookup(isRetry = true)

    private fun startLookup(isRetry: Boolean): Boolean {
        synchronized(lock) {
            if (visibleState is DeviceIdentityState.Ready || lookupInFlight) return false
            if (!isRetry && lookupStarted) return false
            lookupStarted = true
            lookupInFlight = true
        }
        publish(DeviceIdentityState.Resolving)
        executor.execute {
            val next = try {
                mapResult(lookup())
            } catch (error: Throwable) {
                DeviceIdentityState.Unavailable(
                    DeviceIdentityStatus.IO_ERROR,
                    sanitizeDiagnostic(error.message ?: error.javaClass.simpleName),
                    false,
                )
            }
            val shouldPublish = synchronized(lock) {
                if (visibleState is DeviceIdentityState.Ready) {
                    lookupInFlight = false
                    false
                } else {
                    visibleState = next
                    true
                }
            }
            if (!shouldPublish) return@execute
            try {
                listeners.forEach { it(next) }
            } finally {
                synchronized(lock) {
                    lookupInFlight = false
                }
            }
        }
        return true
    }

    private fun mapResult(result: DeviceIdentityLookupResult): DeviceIdentityState {
        if (result.status == DeviceIdStatus.SUCCESS) {
            val canonical = result.deviceId?.lowercase(Locale.ROOT)
            if (canonical != null && CANONICAL_GUID.matches(canonical)) {
                return DeviceIdentityState.Ready(
                    canonical,
                    result.candidateCount.coerceAtLeast(1),
                    result.mintAttempted,
                )
            }
            return DeviceIdentityState.Unavailable(
                DeviceIdentityStatus.IO_ERROR,
                "Device ID provider returned an invalid canonical GUID",
                result.mintAttempted,
            )
        }
        val status = when (result.status) {
            DeviceIdStatus.ACCESS_DENIED -> DeviceIdentityStatus.ACCESS_DENIED
            DeviceIdStatus.UNSUPPORTED_API -> DeviceIdentityStatus.UNSUPPORTED_API
            DeviceIdStatus.IO_ERROR, DeviceIdStatus.NOT_FOUND, DeviceIdStatus.SUCCESS ->
                DeviceIdentityStatus.IO_ERROR
        }
        return DeviceIdentityState.Unavailable(
            status,
            sanitizeDiagnostic(result.diagnostic),
            result.mintAttempted,
        )
    }

    private fun publish(next: DeviceIdentityState) {
        visibleState = next
        listeners.forEach { it(next) }
    }
}
