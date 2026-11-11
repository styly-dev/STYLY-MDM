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

internal data class WifiConfiguredView(
    val entry: WifiConfiguredEntry,
    val original: WifiConfiguration,
)

internal fun wifiSecurityViewMatchesProfile(profile: WifiNetworkProfile, security: String?): Boolean = when {
    profile.security == "OPEN" -> security == "OPEN"
    profile.security != "WPA2_WPA3" -> false
    profile.requiresSaeOnly -> security == "SAE"
    else -> security == "WPA_PSK" || security == "SAE"
}

internal fun desiredWifiSecurityView(profile: WifiNetworkProfile): String? = when {
    profile.security == "OPEN" -> "OPEN"
    profile.security == "WPA2_WPA3" && profile.requiresSaeOnly -> "SAE"
    profile.security == "WPA2_WPA3" -> "WPA_PSK"
    else -> null
}

internal fun preferredWifiSecurityView(
    profile: WifiNetworkProfile,
    securities: Collection<String?>,
): String? {
    val available = securities.filterNotNull().toSet()
    return when {
        profile.security == "OPEN" -> "OPEN".takeIf { it in available }
        profile.requiresSaeOnly -> "SAE".takeIf { it in available }
        profile.security == "WPA2_WPA3" -> listOf("WPA_PSK", "SAE").firstOrNull { it in available }
        else -> null
    }
}

/** Resolve a candidate's exact security view while treating same-ID views as one saved network. */
internal fun resolveWifiProfileViewIndex(
    profile: WifiNetworkProfile,
    entries: List<WifiConfiguredEntry>,
): Int? {
    val matches = entries.indices.filter {
        entries[it].ssid == profile.ssid && wifiSecurityViewMatchesProfile(profile, entries[it].security)
    }
    if (matches.map { entries[it].networkId }.distinct().size != 1) return null
    val preferred = preferredWifiSecurityView(profile, matches.map { entries[it].security })
    return matches.firstOrNull { entries[it].security == preferred }
}

/** Resolve the original security view even if Android reassigned the network ID after reboot. */
internal fun resolveWifiRecoveryViewIndex(
    old: WifiAutoJoinState,
    security: String,
    entries: List<WifiConfiguredEntry>,
): Int? {
    val oldEntry = resolveWifiRecoveryEntry(old, entries) ?: return null
    return entries.indexOfFirst {
        it.networkId == oldEntry.networkId && it.ssid == old.ssid && it.security == security
    }.takeIf { it >= 0 }
}

internal fun resolveWifiRollbackNetworkId(
    target: WifiRollbackTarget,
    entries: List<WifiConfiguredEntry>,
): Int? = entries.asSequence()
    .filter { it.ssid == target.ssid && it.security == target.security }
    .map { it.networkId }
    .distinct()
    .singleOrNull()

internal data class WifiRecoveryAutoJoinPlan(
    val targets: Map<Int, Boolean>,
    val fullyResolved: Boolean,
)

/** Resolve only current network IDs whose SSID/security identity is unambiguous. */
internal fun wifiRecoveryAutoJoinPlan(
    snapshot: WifiRecoverySnapshot,
    entries: List<WifiConfiguredEntry>,
    candidate: List<WifiNetworkProfile>,
): WifiRecoveryAutoJoinPlan {
    val targets = linkedMapOf<Int, Boolean>()
    val conflicts = linkedSetOf<Int>()
    var fullyResolved = true
    for (old in snapshot.networks) {
        val current = resolveWifiRecoveryEntry(old, entries)
        if (current == null) {
            fullyResolved = false
            continue
        }
        if (current.networkId in targets || current.networkId in conflicts) {
            targets.remove(current.networkId)
            conflicts.add(current.networkId)
            fullyResolved = false
        } else {
            targets[current.networkId] = old.allowed
        }
    }
    for (profile in candidate) {
        val ids = entries.filter {
            it.ssid == profile.ssid && wifiSecurityViewMatchesProfile(profile, it.security)
        }.map { it.networkId }.distinct()
        when (ids.size) {
            0 -> Unit
            1 -> if (ids.single() !in conflicts) targets.putIfAbsent(ids.single(), false)
            else -> fullyResolved = false
        }
    }
    return WifiRecoveryAutoJoinPlan(targets, fullyResolved)
}

/** Attempts every recovery phase and keeps any failure sticky. */
internal fun restoreWifiRecoveryBestEffort(
    restoreCredentials: () -> WifiReason,
    restoreAutoJoin: () -> WifiReason,
    reconnect: () -> WifiReason,
    verify: () -> WifiReason,
    onFailure: (String, WifiReason) -> Unit = { _, _ -> },
): WifiReason {
    var failed = false
    fun attempt(operation: String, action: () -> WifiReason) {
        val reason = try { action() } catch (_: Exception) { WifiReason.RESTORE_FAILED }
        if (reason != WifiReason.NONE) {
            failed = true
            runCatching { onFailure(operation, reason) }
        }
    }
    attempt("RESTORE", restoreCredentials)
    attempt("AUTO_JOIN", restoreAutoJoin)
    attempt("RESTORE", reconnect)
    attempt("RESTORE", verify)
    return if (failed) WifiReason.RESTORE_FAILED else WifiReason.NONE
}

/** PICO returns multiple security views for some saved network IDs. */
internal fun wifiProfileReadback(entries: List<WifiConfiguredEntry>, profile: List<WifiNetworkProfile>): Boolean {
    val selected = linkedSetOf<Int>()
    for (network in profile) {
        val matches = entries.filter {
            it.ssid == network.ssid && wifiSecurityViewMatchesProfile(network, it.security)
        }.distinctBy { it.networkId }
        val entry = matches.singleOrNull() ?: return false
        if (!selected.add(entry.networkId) || entry.hidden != network.hidden) return false
    }
    return entries.all { it.autoJoinEnabled == (it.networkId in selected) }
}

internal fun resolveWifiRecoveryEntry(old: WifiAutoJoinState, entries: List<WifiConfiguredEntry>): WifiConfiguredEntry? {
    val matches = entries.filter { it.ssid == old.ssid && it.security in old.securities }.distinctBy { it.networkId }
    return matches.singleOrNull()
}

internal fun supportsPico4UltraEnterpriseSettings(): Boolean =
    Build.MODEL == "A94U0" && Build.MANUFACTURER.equals("Pico", ignoreCase = true)

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
    fun selectNetwork(
        profile: WifiNetworkProfile,
        shouldContinue: () -> Boolean = { true },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun restoreRecovery(
        snapshot: WifiRecoverySnapshot,
        candidate: List<WifiNetworkProfile>,
        restoreTarget: WifiRollbackTarget? = null,
        shouldContinue: () -> Boolean = { true },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun connectToSavedNetwork(
        target: WifiRollbackTarget,
        shouldContinue: () -> Boolean = { true },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun disconnectCurrent(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason
}

/** PICO 4 Ultra Enterprise: retain saved credentials and change per-network auto-join. */
internal class WifiManagerAdapter(context: Context) : WifiSettingsAdapter {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val owner = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val mutationLock = ReentrantLock()
    private val autoJoinField by lazy { WifiConfiguration::class.java.getField("allowAutojoin") }

    override fun readCurrent(): List<WifiConfiguredEntry> = configuredNetworkViews().map { it.entry }

    private fun configuredNetworkViews(): List<WifiConfiguredView> {
        requireAuthority()
        return try {
            @Suppress("DEPRECATION")
            val configs = manager?.configuredNetworks ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            configs.map { config ->
                val security = when {
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> "SAE"
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA_PSK"
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                        config.wepKeys.all { it == null } -> "OPEN"
                    else -> "OTHER:${config.allowedKeyManagement}"
                }
                val entry = WifiConfiguredEntry(config.networkId, config.SSID.removeSurrounding("\""), security,
                    config.hiddenSSID, autoJoinField.getBoolean(config))
                WifiConfiguredView(entry, config)
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
        return WifiRecoverySnapshot(states, knownProfiles.filter { old -> entries.any {
            it.ssid == old.ssid && wifiSecurityViewMatchesProfile(old, it.security)
        } })
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>, shouldContinue: () -> Boolean,
        beforeFirstMutation: () -> WifiReason?, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val service = try {
            autoJoinService()
        } catch (failure: WifiAdapterFailure) {
            logOperationFailure("AUTO_JOIN", failure.reason)
            return@guarded failure.reason
        } catch (_: Exception) {
            logOperationFailure("AUTO_JOIN", WifiReason.UNSUPPORTED)
            return@guarded WifiReason.UNSUPPORTED
        }
        val initialViews = configuredNetworkViews()
        val initial = initialViews.map { it.entry }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded WifiReason.UNSUPPORTED
        if (target.any { it.requiresSaeOnly } &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || wifi.isWpa3SaeSupported != true)
        ) return@guarded WifiReason.UNSUPPORTED
        val selected = linkedSetOf<Int>()
        val mutate = Mutations(shouldContinue, beforeFirstMutation, onMutationStarted, onMutationFinished)
        for (network in target) {
            val existing = initial.filter {
                it.ssid == network.ssid && wifiSecurityViewMatchesProfile(network, it.security)
            }.distinctBy { it.networkId }
            if (existing.size > 1) return@guarded WifiReason.VERIFY_FAILED
            val existingIndex = resolveWifiProfileViewIndex(network, initial)
            val existingView = existingIndex?.let(initialViews::get)
            if (existing.isNotEmpty() && existingView == null) return@guarded WifiReason.VERIFY_FAILED
            var id = -1
            val failure = mutate.run {
                val operation = if (existingView == null) "ADD" else "UPDATE"
                try {
                    val config = configuration(network, existingView?.original)
                    @Suppress("DEPRECATION")
                    id = if (existingView == null) wifi.addNetwork(config) else wifi.updateNetwork(config)
                    if (id < 0) {
                        logOperationFailure(operation, WifiReason.UPDATE_FAILED)
                        WifiReason.UPDATE_FAILED
                    } else {
                        WifiReason.NONE
                    }
                } catch (_: SecurityException) {
                    logOperationFailure(operation, WifiReason.PERMISSION_DENIED)
                    WifiReason.PERMISSION_DENIED
                } catch (failure: WifiAdapterFailure) {
                    logOperationFailure(operation, failure.reason)
                    failure.reason
                } catch (_: Exception) {
                    logOperationFailure(operation, WifiReason.UPDATE_FAILED)
                    WifiReason.UPDATE_FAILED
                }
            }
            if (failure != WifiReason.NONE) return@guarded failure
            // The controller selects candidates in order afterwards; do not enable here.
            selected.add(id)
        }
        for (entry in readCurrent().distinctBy { it.networkId }) {
            val allowed = entry.networkId in selected
            if (entry.autoJoinEnabled == allowed) continue
            val failure = try {
                mutate.run { setAutoJoin(service, entry.networkId, allowed) }
            } catch (_: SecurityException) {
                WifiReason.PERMISSION_DENIED
            } catch (_: Exception) {
                WifiReason.UPDATE_FAILED
            }
            if (failure != WifiReason.NONE) {
                logOperationFailure("AUTO_JOIN", failure)
                return@guarded failure
            }
        }
        if (!shouldContinue()) WifiReason.TIMEOUT_ROLLED_BACK
        else if (wifiProfileReadback(readCurrent(), target)) WifiReason.NONE else WifiReason.VERIFY_FAILED
    }

    override fun selectNetwork(
        profile: WifiNetworkProfile,
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val current = readCurrent()
        val entry = resolveWifiProfileViewIndex(profile, current)?.let(current::get)
            ?: return@guarded WifiReason.VERIFY_FAILED
        val reason = try {
            Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
                @Suppress("DEPRECATION")
                if (wifi.enableNetwork(entry.networkId, true)) WifiReason.NONE else WifiReason.UPDATE_FAILED
            }
        } catch (_: SecurityException) {
            WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            WifiReason.UPDATE_FAILED
        }
        if (reason != WifiReason.NONE) logOperationFailure("AUTO_JOIN", reason)
        reason
    }

    override fun connectToSavedNetwork(
        target: WifiRollbackTarget,
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val id = resolveWifiRollbackNetworkId(target, readCurrent())
            ?: return@guarded WifiReason.VERIFY_FAILED
        try {
            val selected = Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
                @Suppress("DEPRECATION")
                if (wifi.enableNetwork(id, true)) WifiReason.NONE else WifiReason.UNREACHABLE
            }
            if (selected != WifiReason.NONE) return@guarded selected
            Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
                @Suppress("DEPRECATION")
                if (wifi.reconnect()) WifiReason.NONE else WifiReason.UNREACHABLE
            }
        } catch (_: SecurityException) {
            WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            WifiReason.UNREACHABLE
        }.also { if (it != WifiReason.NONE) logOperationFailure("RESTORE", it) }
    }

    override fun restoreRecovery(
        snapshot: WifiRecoverySnapshot, candidate: List<WifiNetworkProfile>, restoreTarget: WifiRollbackTarget?,
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        val mutate = Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished)
        var expectedAutoJoin = emptyMap<Int, Boolean>()
        restoreWifiRecoveryBestEffort(
            restoreCredentials = {
                var failed = false
                val views = try {
                    configuredNetworkViews()
                } catch (_: Exception) {
                    return@restoreWifiRecoveryBestEffort WifiReason.RESTORE_FAILED
                }
                val entries = views.map { it.entry }
                // Only credentials that MDM already knew and this attempt could overwrite are restored.
                for (known in snapshot.knownCredentials.filter { old -> candidate.any { it.identity == old.identity } }) {
                    val original = snapshot.networks.singleOrNull {
                        it.ssid == known.ssid && it.securities.any { security ->
                            wifiSecurityViewMatchesProfile(known, security)
                        }
                    }
                    val originalSecurity = original?.let { preferredWifiSecurityView(known, it.securities) }
                    val viewIndex = original?.let { old ->
                        originalSecurity?.let { security -> resolveWifiRecoveryViewIndex(old, security, entries) }
                    }
                    val view = viewIndex?.let(views::get)
                    if (view == null) {
                        failed = true
                        continue
                    }
                    val failure = try {
                        mutate.run {
                            @Suppress("DEPRECATION")
                            if (wifi.updateNetwork(configuration(known, view.original)) >= 0) WifiReason.NONE
                            else WifiReason.RESTORE_FAILED
                        }
                    } catch (_: SecurityException) {
                        WifiReason.PERMISSION_DENIED
                    } catch (_: Exception) {
                        WifiReason.RESTORE_FAILED
                    }
                    if (failure != WifiReason.NONE) {
                        failed = true
                        logOperationFailure("UPDATE", failure)
                    }
                }
                if (failed) WifiReason.RESTORE_FAILED else WifiReason.NONE
            },
            restoreAutoJoin = {
                val serviceForRestore = try {
                    autoJoinService()
                } catch (_: Exception) {
                    null
                }
                val views = try {
                    configuredNetworkViews()
                } catch (_: Exception) {
                    null
                }
                val entries = views?.map { it.entry }
                val plan = entries?.let { wifiRecoveryAutoJoinPlan(snapshot, it, candidate) }
                    ?: WifiRecoveryAutoJoinPlan(emptyMap(), fullyResolved = false)
                expectedAutoJoin = plan.targets
                var failed = !plan.fullyResolved
                for ((id, allowed) in expectedAutoJoin) {
                    if (entries?.filter { it.networkId == id }?.all { it.autoJoinEnabled == allowed } == true) continue
                    val service = serviceForRestore
                    if (service == null) {
                        failed = true
                        continue
                    }
                    val failure = try {
                        mutate.run { setAutoJoin(service, id, allowed) }
                    } catch (_: Exception) {
                        WifiReason.RESTORE_FAILED
                    }
                    if (failure != WifiReason.NONE) {
                        failed = true
                    }
                }
                if (failed) WifiReason.RESTORE_FAILED else WifiReason.NONE
            },
            reconnect = {
                var selectedTargetId: Int? = null
                val entries = runCatching { readCurrent() }.getOrNull().orEmpty()
                if (restoreTarget != null) {
                    selectedTargetId = resolveWifiRollbackNetworkId(restoreTarget, entries)
                    val selection = selectedTargetId?.let { id ->
                        runCatching {
                            mutate.run {
                                @Suppress("DEPRECATION")
                                if (wifi.enableNetwork(id, true)) WifiReason.NONE else WifiReason.UNREACHABLE
                            }
                        }.getOrDefault(WifiReason.UNREACHABLE)
                    } ?: WifiReason.VERIFY_FAILED
                    if (selection != WifiReason.NONE) logOperationFailure("RESTORE", selection)
                }
                for ((id, allowed) in expectedAutoJoin) {
                    if (!allowed || id == selectedTargetId) continue
                    val failure = try {
                        mutate.run {
                            @Suppress("DEPRECATION")
                            if (wifi.enableNetwork(id, false)) WifiReason.NONE else WifiReason.RESTORE_FAILED
                        }
                    } catch (_: Exception) {
                        WifiReason.RESTORE_FAILED
                    }
                    if (failure != WifiReason.NONE) logOperationFailure("RESTORE", failure)
                }
                val reconnectFailure = try {
                    mutate.run {
                        @Suppress("DEPRECATION")
                        if (wifi.reconnect()) WifiReason.NONE else WifiReason.RESTORE_FAILED
                    }
                } catch (_: Exception) {
                    WifiReason.RESTORE_FAILED
                }
                if (reconnectFailure != WifiReason.NONE) logOperationFailure("RESTORE", reconnectFailure)
                WifiReason.NONE
            },
            verify = {
                val restored = try {
                    readCurrent()
                } catch (_: Exception) {
                    return@restoreWifiRecoveryBestEffort WifiReason.RESTORE_FAILED
                }
                if (expectedAutoJoin.all { (id, allowed) ->
                        restored.any { it.networkId == id } && restored.filter { it.networkId == id }.all { it.autoJoinEnabled == allowed }
                    }
                ) WifiReason.NONE else WifiReason.RESTORE_FAILED
            },
            onFailure = { operation, reason -> logOperationFailure(operation, reason) },
        )
    }

    override fun disconnectCurrent(shouldContinue: () -> Boolean, onMutationStarted: () -> WifiReason?, onMutationFinished: () -> Unit): WifiReason = guarded {
        Mutations(shouldContinue, { null }, onMutationStarted, onMutationFinished).run {
            @Suppress("DEPRECATION")
            if (manager?.disconnect() == true) WifiReason.NONE else WifiReason.UNREACHABLE
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
        if (!supportsPico4UltraEnterpriseSettings()) throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
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

    private fun configuration(profile: WifiNetworkProfile, original: WifiConfiguration? = null): WifiConfiguration {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        val config = if (original == null) {
            WifiConfiguration()
        } else {
            WifiConfiguration(original)
        }
        return config.apply {
            networkId = original?.networkId ?: -1
            SSID = "\"${profile.ssid}\""
            hiddenSSID = profile.hidden
            val desiredSecurity = desiredWifiSecurityView(profile)
                ?: throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
            val originalSecurity = when {
                original?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.SAE) == true -> "SAE"
                original?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.WPA_PSK) == true -> "WPA_PSK"
                original?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE) == true -> "OPEN"
                else -> null
            }
            if (originalSecurity != desiredSecurity) {
                when (desiredSecurity) {
                    "OPEN" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
                    "SAE" -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || manager?.isWpa3SaeSupported != true) {
                            throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
                        }
                        setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                    }
                    "WPA_PSK" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK)
                }
            }
            when {
                profile.security == "OPEN" -> {
                    preSharedKey = null
                }
                profile.security == "WPA2_WPA3" && profile.requiresSaeOnly -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || manager?.isWpa3SaeSupported != true) {
                        throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
                    }
                    preSharedKey = "\"${profile.password}\""
                }
                profile.security == "WPA2_WPA3" -> {
                    preSharedKey = "\"${profile.password}\""
                }
                else -> throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
            }
        }
    }

    private fun logOperationFailure(operation: String, reason: WifiReason) {
        android.util.Log.w("WifiSettings", "Wi-Fi operation $operation failed: ${reason.wireValue}")
    }
}
