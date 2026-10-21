package com.styly.mdmclient

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes native Home changes; a timed-out binder call may still take effect. */
internal class PicoStartupAppController(
    private val setLauncher: (String?) -> Int,
    private val isLaunchable: (String) -> Boolean,
    private val timeoutMs: Long = 15_000L
) : AutoCloseable {
    data class Result(
        val status: String,
        val resultCode: Int? = null,
        val error: String? = null
    )

    companion object {
        const val CAPABILITY = "pico_startup_app_v1"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }

    private val lock = Any()
    private var closed = false
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pico-startup-app").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pico-startup-timeout").apply { isDaemon = true }
    }

    fun submit(packageName: String?, report: (Result) -> Unit) {
        synchronized(lock) {
            if (closed) return
            if (packageName != null && (packageName.length > 255 || !PACKAGE.matches(packageName))) {
                report(Result("fail", error = "Invalid PICO startup command"))
                return
            }
            if (busy) {
                report(Result("fail", error = "A PICO startup command is still running"))
                return
            }
            busy = true
            val replied = AtomicBoolean(false)
            fun reply(result: Result) = synchronized(lock) {
                if (!closed && replied.compareAndSet(false, true)) report(result)
            }
            val timeout = timer.schedule({
                reply(Result("unknown", error =
                    "PICO API timed out; the setting may still change. Verify on the device before retrying."))
            }, timeoutMs, TimeUnit.MILLISECONDS)
            worker.execute {
                try {
                    if (synchronized(lock) { closed }) return@execute
                    val result = when {
                        packageName != null && !isLaunchable(packageName) ->
                            Result("fail", error = "Package is not an installed, enabled launchable app")
                        else -> {
                            // Restore deliberately passes a Java null, never an empty string.
                            val code = setLauncher(packageName)
                            if (code == 0) Result("accepted", resultCode = code)
                            else Result("fail", resultCode = code, error =
                                "PICO API returned a nonzero result; verify on the device before retrying")
                        }
                    }
                    reply(result)
                } catch (e: Exception) {
                    reply(Result("fail", error = "PICO API failed: ${e.javaClass.simpleName}"))
                } catch (e: LinkageError) {
                    reply(Result("fail", error = "PICO startup API is unavailable"))
                } finally {
                    timeout.cancel(false)
                    // Do not release this slot at timeout: an uncancellable binder may still run.
                    synchronized(lock) { busy = false }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            timer.shutdownNow()
            worker.shutdownNow()
        }
    }
}
