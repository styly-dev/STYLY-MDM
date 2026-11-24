package com.styly.mdmclient

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PicoPackageAppliedNotice(private val context: Context) {
    companion object {
        private const val TAG = "PackageAppliedNotice"
        private const val NOTICE_DURATION_MS = 12_000L
        private const val NOTICE_DOWNWARD_OFFSET_PX = 480
        private const val XR_SHELL_READY_PROPERTY = "picovr.systemext.prop.ready"
        private const val XR_SHELL_POLL_INTERVAL_MS = 1_000L
        private const val XR_SHELL_TIMEOUT_MS = 30_000L
        private const val GETPROP_TIMEOUT_MS = 500L
    }

    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "package-applied-notice").apply { isDaemon = true }
    }

    fun show(bootReapply: Boolean, isCurrent: () -> Boolean) {
        if (!supportsPico4UltraEnterpriseSettings()) return
        val deadline = if (bootReapply) SystemClock.elapsedRealtime() + XR_SHELL_TIMEOUT_MS else null
        worker.execute { requestNotice(deadline, isCurrent) }
    }

    private fun requestNotice(deadline: Long?, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        runCatching {
            if (deadline != null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    Log.w(TAG, "Timed out waiting for PICO XR Shell before the package applied notice")
                    return
                }
                if (!isXrShellReady(minOf(remaining, GETPROP_TIMEOUT_MS))) {
                    val delay = minOf(XR_SHELL_POLL_INTERVAL_MS, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    worker.schedule({ requestNotice(deadline, isCurrent) }, delay, TimeUnit.MILLISECONDS)
                    return
                }
            }

            val service = ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy ?: return
            val icon = context.getDrawable(R.mipmap.ic_launcher)?.toBitmap(width = 64, height = 64) ?: return
            if (!isCurrent()) return
            val result = service.showGlobalTipsDialog(
                icon, context.getString(R.string.configuration_applied_notice),
                NOTICE_DURATION_MS, NOTICE_DOWNWARD_OFFSET_PX,
                Color.rgb(30, 35, 46),
            )
            if (result == 0) Log.i(TAG, "PICO accepted the package applied notice (bootReapply=${deadline != null})")
            else Log.w(TAG, "PICO rejected the package applied notice: $result")
        }.onFailure { Log.w(TAG, "Could not show the package applied notice", it) }
    }

    private fun isXrShellReady(timeoutMillis: Long): Boolean {
        val process = ProcessBuilder("/system/bin/getprop", XR_SHELL_READY_PROPERTY).redirectErrorStream(true).start()
        try {
            return process.inputStream.bufferedReader().use { output ->
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) && process.exitValue() == 0 &&
                    output.readLine()?.trim() == "1"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
