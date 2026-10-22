package com.styly.mdmclient

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.pvr.tobservice.ToBServiceHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class WifiConfiguredEntry(
    val networkId: Int,
    val ssid: String,
    val security: String?,
    val hidden: Boolean,
    val autoJoinEnabled: Boolean,
) {
    val identity: String get() = "$ssid\u0000$security"
}

/** PICO returns multiple security views for some saved network IDs. */
internal fun wifiProfileReadback(entries: List<WifiConfiguredEntry>, profile: List<WifiNetworkProfile>): Boolean {
    val selected = linkedSetOf<Int>()
    for (network in profile) {
        val matches = entries.filter { it.identity == network.identity }.distinctBy { it.networkId }
        val entry = matches.singleOrNull() ?: return false
        if (!selected.add(entry.networkId) || entry.hidden != network.hidden) return false
    }
    return entries.all { it.autoJoinEnabled == (it.networkId in selected) }
}

internal fun resolveWifiRecoveryEntry(old: WifiAutoJoinState, entries: List<WifiConfiguredEntry>): WifiConfiguredEntry? {
    val matches = entries.filter { it.ssid == old.ssid && it.security in old.securities }.distinctBy { it.networkId }
    return matches.firstOrNull { it.networkId == old.networkId } ?: matches.singleOrNull()
}

internal fun wifiRecoveryAutoJoinTargets(
    snapshot: WifiRecoverySnapshot,
    entries: List<WifiConfiguredEntry>,
    candidate: List<WifiNetworkProfile>,
): Map<Int, Boolean>? {
    val expected = linkedMapOf<Int, Boolean>()
    for (old in snapshot.networks) {
        val current = resolveWifiRecoveryEntry(old, entries) ?: return null
        if (expected.put(current.networkId, old.allowed) != null) return null
    }
    for (entry in entries.filter { current -> candidate.any { it.identity == current.identity } }) {
        expected.putIfAbsent(entry.networkId, false)
    }
    return expected
}

internal fun supportsWifiSettingsPlatform(): Boolean =
    Build.MODEL in setOf("A9210", "A94U0") &&
        Build.MANUFACTURER.equals("Pico", ignoreCase = true) && Build.VERSION.SDK_INT >= 34

internal class WifiAdapterFailure(val reason: WifiReason) : Exception()

internal interface WifiSettingsAdapter {
    fun readCurrent(): List<WifiConfiguredEntry>
    fun captureRecovery(knownProfiles: List<WifiNetworkProfile>): WifiRecoverySnapshot
    fun applyProfile(
        target: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean = { true },
        beforeFirstMutation: () -> WifiReason? = { null },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun restoreRecovery(
        snapshot: WifiRecoverySnapshot,
        candidate: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean = { true },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun disconnectCurrent(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason
    fun reconnect(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason
}

/** PICO 4 Ultra Enterprise: retain saved credentials and change per-network auto-join. */
internal class WifiManagerAdapter(context: Context) : WifiSettingsAdapter {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val owner = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val mutationLock = ReentrantLock()
    private val autoJoinField by lazy { WifiConfiguration::class.java.getField("allowAutojoin") }

    override fun readCurrent(): List<WifiConfiguredEntry> {
        requireAuthority()
        return try {
            @Suppress("DEPRECATION")
            val configs = manager?.configuredNetworks ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            configs.map { config ->
                val security = when {
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA_PSK"
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> "SAE"
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                        config.wepKeys.all { it == null } -> "OPEN"
                    else -> "OTHER:${config.allowedKeyManagement}"
                }
                WifiConfiguredEntry(config.networkId, config.SSID.removeSurrounding("\""), security,
                    config.hiddenSSID, autoJoinField.getBoolean(config))
            }
        } catch (failure: WifiAdapterFailure) { throw failure
        } catch (_: SecurityException) { throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        } catch (_: ReflectiveOperationException) { throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        } catch (_: Exception) { throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED) }
    }

    override fun captureRecovery(knownProfiles: List<WifiNetworkProfile>): WifiRecoverySnapshot {
        val entries = readCurrent()
        val states = entries.groupBy { it.networkId }.map { (id, views) ->
            if (views.map { it.autoJoinEnabled }.distinct().size != 1 || views.map { it.ssid }.distinct().size != 1) {
                throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            }
            WifiAutoJoinState(id, views.first().ssid, views.mapNotNull { it.security }.distinct(), views.first().autoJoinEnabled)
        }
        return WifiRecoverySnapshot(states, knownProfiles.filter { old -> entries.any { it.identity == old.identity } })
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>, shouldContinue: () -> Boolean,
        beforeFirstMutation: () -> WifiReason?, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val service = autoJoinService()
        val initial = readCurrent()
        val selected = linkedSetOf<Int>()
        val mutate = Mutations(shouldContinue, beforeFirstMutation, onMutationStarted, onMutationFinished)
        for (network in target) {
            val existing = initial.filter { it.identity == network.identity }.distinctBy { it.networkId }
            if (existing.size > 1) return@guarded WifiReason.VERIFY_FAILED
            var id = -1
            val failure = mutate.run {
                @Suppress("DEPRECATION")
                val config = configuration(network).apply { networkId = existing.singleOrNull()?.networkId ?: -1 }
                @Suppress("DEPRECATION")
                id = if (config.networkId < 0) wifi.addNetwork(config) else wifi.updateNetwork(config)
                if (id < 0) WifiReason.UPDATE_FAILED else WifiReason.NONE
            }
            if (failure != WifiReason.NONE) return@guarded failure
            selected.add(id)
            val enabled = mutate.run {
                @Suppress("DEPRECATION")
                if (wifi.enableNetwork(id, false)) WifiReason.NONE else WifiReason.UPDATE_FAILED
            }
            if (enabled != WifiReason.NONE) return@guarded enabled
        }
        for (entry in readCurrent().distinctBy { it.networkId }) {
            val allowed = entry.networkId in selected
            if (entry.autoJoinEnabled == allowed) continue
            val failure = mutate.run { setAutoJoin(service, entry.networkId, allowed) }
            if (failure != WifiReason.NONE) return@guarded failure
        }
        if (!shouldContinue()) WifiReason.TIMEOUT_ROLLED_BACK
        else if (wifiProfileReadback(readCurrent(), target)) WifiReason.NONE else WifiReason.VERIFY_FAILED
    }

    override fun restoreRecovery(
        snapshot: WifiRecoverySnapshot, candidate: List<WifiNetworkProfile>, shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val service = autoJoinService()
        val mutate = Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished)
        var entries = readCurrent()
        // Only credentials that MDM already knew and this attempt could overwrite are restored.
        for (known in snapshot.knownCredentials.filter { old -> candidate.any { it.identity == old.identity } }) {
            val original = snapshot.networks.singleOrNull { it.ssid == known.ssid && known.security in it.securities }
                ?: return@guarded WifiReason.RESTORE_FAILED
            val entry = resolveWifiRecoveryEntry(original, entries) ?: return@guarded WifiReason.RESTORE_FAILED
            val failure = mutate.run {
                @Suppress("DEPRECATION")
                if (wifi.updateNetwork(configuration(known).apply { networkId = entry.networkId }) >= 0) WifiReason.NONE
                else WifiReason.RESTORE_FAILED
            }
            if (failure != WifiReason.NONE) return@guarded failure
        }
        entries = readCurrent()
        // A saved pre-change ID alone is insufficient after an OS restart.
        val expected = wifiRecoveryAutoJoinTargets(snapshot, entries, candidate)
            ?: return@guarded WifiReason.RESTORE_FAILED
        for ((id, allowed) in expected) {
            if (entries.filter { it.networkId == id }.all { it.autoJoinEnabled == allowed }) continue
            val failure = mutate.run { setAutoJoin(service, id, allowed) }
            if (failure != WifiReason.NONE) return@guarded failure
        }
        @Suppress("DEPRECATION")
        val enabled = mutate.run {
            for ((id, allowed) in expected) if (allowed && !wifi.enableNetwork(id, false)) return@run WifiReason.RESTORE_FAILED
            if (wifi.reconnect()) WifiReason.NONE else WifiReason.RESTORE_FAILED
        }
        if (enabled != WifiReason.NONE) return@guarded enabled
        val restored = readCurrent()
        if (expected.all { (id, allowed) -> restored.any { it.networkId == id } && restored.filter { it.networkId == id }.all { it.autoJoinEnabled == allowed } }) WifiReason.NONE
        else WifiReason.RESTORE_FAILED
    }

    override fun disconnectCurrent(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason = guarded {
        Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
            @Suppress("DEPRECATION")
            if (manager?.disconnect() == true) WifiReason.NONE else WifiReason.UNREACHABLE
        }
    }

    override fun reconnect(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason = guarded {
        Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
            @Suppress("DEPRECATION")
            if (manager?.reconnect() == true) WifiReason.NONE else WifiReason.UNREACHABLE
        }
    }

    private inner class Mutations(
        val canContinue: () -> Boolean, val prepare: () -> WifiReason?,
        val begin: () -> WifiReason?, val end: () -> Unit,
    ) {
        private var prepared = false
        fun run(action: () -> WifiReason): WifiReason = mutationLock.withLock {
            if (!canContinue()) return@withLock WifiReason.TIMEOUT_ROLLED_BACK
            if (!prepared) {
                prepare()?.let { return@withLock it }
                prepared = true
            }
            begin()?.let { return@withLock it }
            try { if (canContinue()) action() else WifiReason.TIMEOUT_ROLLED_BACK } finally { end() }
        }
    }

    private fun autoJoinService(): com.pvr.tobservice.interfaces.IToBService {
        // Application startup binds asynchronously; wait before the first mutation, not on the main thread.
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            ToBServiceHelper.getInstance().serviceBinder?.let { return it }
            Thread.sleep(50)
        }
        throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
    }

    private fun setAutoJoin(service: com.pvr.tobservice.interfaces.IToBService, id: Int, allowed: Boolean): WifiReason {
        val arguments = Bundle().apply { putInt("network_id", id); putBoolean("allow_auto_join", allowed) }
        val result = service.pbsCommonMessageLocked("allow_wifi_auto_join", arguments)
        return if (result?.getInt("key_result_code", -1) == 0) WifiReason.NONE else WifiReason.UPDATE_FAILED
    }

    fun readinessFailure(): WifiReason? = try {
        requireAuthority()
        if (manager == null || ToBServiceHelper.getInstance().serviceBinder == null) WifiReason.UNSUPPORTED else null
    } catch (failure: WifiAdapterFailure) {
        failure.reason
    }

    private fun requireAuthority() {
        // PUI exposes A94U0 to application processes and A9210 through ADB on this device.
        if (!supportsWifiSettingsPlatform()) throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        if (appContext.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") != PackageManager.PERMISSION_GRANTED
        ) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        }
        if (owner?.isDeviceOwnerApp(appContext.packageName) != true) throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
    }

    private inline fun guarded(action: () -> WifiReason): WifiReason {
        val reason = try {
            requireAuthority()
            action()
        } catch (failure: WifiAdapterFailure) { failure.reason
        } catch (_: SecurityException) { WifiReason.PERMISSION_DENIED
        } catch (_: Exception) { WifiReason.CONFIG_READ_FAILED }
        if (reason != WifiReason.NONE) android.util.Log.w("WifiSettings", "Wi-Fi adapter failed: ${reason.wireValue}")
        return reason
    }

    private fun configuration(profile: WifiNetworkProfile) = WifiConfiguration().apply {
        SSID = "\"${profile.ssid}\""
        hiddenSSID = profile.hidden
        if (profile.security == "OPEN") allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        else {
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            preSharedKey = if (profile.password.length == 64) profile.password else "\"${profile.password}\""
        }
    }
}
