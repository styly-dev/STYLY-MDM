package com.styly.mdmclient

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.pvr.tobservice.interfaces.IToBServiceProxy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The client's side of the mutual watch with the guard app (com.styly.mdmguard).
 *
 * The guard revives this client after a self-update replaces its package: the
 * installer kills the process and nothing else on PICO restarts it (measured for
 * #39), and the scheduled power-cycle fallback is dead on firmware whose SELinux
 * policy denies the poweroffalarm app the RTC alarm HAL. In return the client
 * provisions the guard: its APK ships embedded in the client build
 * (assets/guard.apk, packed by :app's copyGuardApk tasks), and the mutual-watch
 * tick silent-installs it whenever it is missing or older than the embedded copy —
 * so deploying the client deploys the guard, a deleted guard comes back, and guard
 * upgrades ride client updates. The tick also starts a guard that is installed but
 * not running (a fresh install is in the stopped state and receives no boot
 * broadcast until a reboot; the same applies after the guard's own update).
 *
 * All starts go through TobService's privileged startForegroundService, the same
 * primitive the guard uses for revival: it is exempt from background-start
 * restrictions and punches through the stopped state (verified on device).
 */
object GuardLink {

    private const val TAG = "GuardLink"

    const val GUARD_PACKAGE = "com.styly.mdmguard"
    private const val GUARD_SERVICE = "com.styly.mdmguard.GuardService"

    private const val EMBEDDED_ASSET = "guard.apk"
    private const val STAGED_PREFIX = "guard-embedded-"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GUARD_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The installed guard's versionCode, or null when it is not installed. */
    fun installedVersionCode(context: Context): Long? = try {
        context.packageManager.getPackageInfo(GUARD_PACKAGE, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The guard APK bundled into this client build, staged on disk ready to install.
     * [sha256] is the digest of the asset bytes as they were written; the caller must
     * confirm the staged file still matches (see [verifyStaged]) immediately before
     * handing the path to the installer.
     */
    data class EmbeddedGuard(val file: File, val versionCode: Long, val sha256: String)

    /**
     * Copies the bundled guard APK (assets/guard.apk, packed at build time) into the
     * shared download directory — the PICO installer runs as a separate system-user
     * process and cannot read app-private storage (fails with 102 "APK does not
     * exist") — and reads its identity. Shared storage means other apps granted
     * all-files access could touch the file, so the staged name is randomized (a
     * fixed name could be pre-created or watched) and the asset's SHA-256 is captured
     * during the copy for a last-moment re-check before install. Returns null when
     * the asset is absent, unreadable, or not the guard package; the caller deletes
     * the staged file once it is done with it. Stale staged copies from a process
     * that died mid-provisioning are swept here.
     */
    fun extractEmbedded(context: Context): EmbeddedGuard? = try {
        val downloadsRoot =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkDir = File(downloadsRoot, "styly-mdm")
        if (!apkDir.exists() && !apkDir.mkdirs()) {
            throw IllegalStateException("Failed to create staging directory")
        }
        apkDir.listFiles { f -> f.name.startsWith(STAGED_PREFIX) }?.forEach { it.delete() }
        val staged = File(apkDir, "$STAGED_PREFIX${UUID.randomUUID()}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(EMBEDDED_ASSET).use { input ->
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val info = context.packageManager.getPackageArchiveInfo(staged.absolutePath, 0)
        val versionCode = info?.longVersionCode
        if (info?.packageName != GUARD_PACKAGE || versionCode == null) {
            Log.e(TAG, "Embedded guard APK has unexpected identity: ${info?.packageName}")
            staged.delete()
            null
        } else {
            EmbeddedGuard(staged, versionCode, sha256)
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Could not stage the embedded guard APK", e)
        null
    }

    /**
     * True when the staged file still hashes to what was written from the asset.
     * Called immediately before the installer is invoked: it shrinks the tamper
     * window on the shared-storage staging from "since extraction" to the moment
     * between this check and the installer's own read — the narrowest a path-based
     * install API allows.
     */
    fun verifyStaged(embedded: EmbeddedGuard): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        embedded.file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == embedded.sha256
    } catch (e: Throwable) {
        Log.e(TAG, "Could not re-hash the staged guard APK", e)
        false
    }

    /** true/false when TobService answered, null when liveness is unknowable right now. */
    fun isRunning(proxy: IToBServiceProxy): Boolean? = try {
        proxy.runningAppProcesses?.any { it.processName == GUARD_PACKAGE }
    } catch (e: Throwable) {
        Log.e(TAG, "getRunningAppProcesses threw", e)
        null
    }

    /**
     * Makes sure the guard is up, starting it when it is not, and returns true only
     * once its process is confirmed running. With [waitMs] > 0 the confirmation is
     * polled until the deadline; the self-update gate uses this because handing
     * recovery to a guard that never came up would strand the device. With the
     * default fire-and-forget the caller's next periodic check is the confirmation.
     */
    fun ensureRunning(context: Context, proxy: IToBServiceProxy, waitMs: Long = 0): Boolean {
        if (!isInstalled(context)) return false
        if (isRunning(proxy) == true) return true
        try {
            val started = proxy.startForegroundService(
                Intent().setClassName(GUARD_PACKAGE, GUARD_SERVICE)
            )
            Log.i(TAG, "Requested guard start: $started")
        } catch (e: Throwable) {
            Log.e(TAG, "Starting the guard threw", e)
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRunning(proxy) == true) return true
            SystemClock.sleep(500)
        }
        return isRunning(proxy) == true
    }
}
