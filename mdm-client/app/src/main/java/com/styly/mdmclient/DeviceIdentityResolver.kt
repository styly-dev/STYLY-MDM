package com.styly.mdmclient

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.styly.deviceid.DeviceIdProvider
import com.styly.deviceid.DeviceIdStatus
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeoutException

sealed interface DeviceIdentityState {
    data object Resolving : DeviceIdentityState

    data class Ready(
        val deviceId: String,
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
    val mintAttempted: Boolean,
    val diagnostic: String,
)

/** Process-wide, single-flight owner of the canonical MediaStore identity lookup. */
class DeviceIdentityResolver internal constructor(
    private val dispatchCompletion: (Runnable) -> Unit = { it.run() },
    private val lookup: () -> CompletableFuture<DeviceIdentityLookupResult>,
) {
    companion object {
        private const val MAX_DIAGNOSTIC_LENGTH = 256
        private const val LOOKUP_TIMEOUT_MS = 30_000L
        private const val RETRY_DELAY_MS = 250L
        private val CANONICAL_GUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )

        fun create(context: Context): DeviceIdentityResolver {
            val appContext = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            return DeviceIdentityResolver(dispatchCompletion = { handler.post(it); Unit }) {
                DeviceIdProvider.getOrCreateAsync(appContext, LOOKUP_TIMEOUT_MS, RETRY_DELAY_MS)
                    .thenApply { result ->
                        DeviceIdentityLookupResult(
                            status = result.status,
                            deviceId = result.deviceId,
                            mintAttempted = result.wasMintAttempted(),
                            diagnostic = result.diagnosticMessage,
                        )
                    }
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

    fun snapshot(): DeviceIdentityState = visibleState

    @MainThread
    fun addListener(listener: (DeviceIdentityState) -> Unit) {
        listeners.add(listener)
        listener(visibleState)
    }

    @MainThread
    fun removeListener(listener: (DeviceIdentityState) -> Unit) {
        listeners.remove(listener)
    }

    @MainThread
    fun startInitialLookup(): Boolean {
        synchronized(lock) {
            if (lookupStarted) return false
            lookupStarted = true
        }
        return runLookup()
    }

    @MainThread
    fun retryAfterPermissionGranted(): Boolean {
        synchronized(lock) {
            val failure = visibleState as? DeviceIdentityState.Unavailable ?: return false
            if (failure.status != DeviceIdentityStatus.ACCESS_DENIED || failure.mintAttempted) return false
            visibleState = DeviceIdentityState.Resolving
        }
        listeners.forEach { it(DeviceIdentityState.Resolving) }
        return runLookup()
    }

    private fun runLookup(): Boolean {
        val request = try {
            lookup()
        } catch (error: Exception) {
            complete(null, error)
            return true
        } catch (error: LinkageError) {
            complete(null, error)
            return true
        }
        request.whenComplete { result, error -> complete(result, error) }
        return true
    }

    private fun complete(result: DeviceIdentityLookupResult?, error: Throwable?) {
        // Always enqueue in production, including already-completed futures. State changes
        // and listener delivery share the main queue with permission recovery.
        dispatchCompletion(Runnable {
            val next = if (error != null) {
                unavailable(error)
            } else if (result == null) {
                DeviceIdentityState.Unavailable(
                    DeviceIdentityStatus.IO_ERROR, "Device ID provider completed without a result", false,
                )
            } else {
                mapResult(result)
            }
            publish(next)
        })
    }

    private fun unavailable(error: Throwable): DeviceIdentityState.Unavailable {
        // thenApply wraps exceptional Provider completion; retain the original timeout and cause.
        val failure = if (error is CompletionException) error.cause ?: error else error
        val detail = failure.message ?: failure.javaClass.simpleName
        val cause = failure.cause?.let { ": ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val diagnostic = if (failure is TimeoutException) {
            "TimeoutException: $detail$cause; in-flight lookup may still create an ID"
        } else {
            "$detail$cause"
        }
        return DeviceIdentityState.Unavailable(
            DeviceIdentityStatus.IO_ERROR, sanitizeDiagnostic(diagnostic), false,
        )
    }

    private fun mapResult(result: DeviceIdentityLookupResult): DeviceIdentityState {
        if (result.status == DeviceIdStatus.SUCCESS) {
            val canonical = result.deviceId?.lowercase(Locale.ROOT)
            if (canonical != null && CANONICAL_GUID.matches(canonical)) {
                return DeviceIdentityState.Ready(canonical)
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
