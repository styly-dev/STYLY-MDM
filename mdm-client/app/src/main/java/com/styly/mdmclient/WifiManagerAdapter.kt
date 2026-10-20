package com.styly.mdmclient

import android.app.admin.DevicePolicyManager
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class WifiConfiguredEntry(
    val networkId: Int,
    val ssid: String,
    val security: String?,
    val password: String?,
    val passwordReadable: Boolean,
    val hidden: Boolean,
    val enabled: Boolean,
)

/** PICO can expose several security views for one saved network ID. */
internal fun wifiProfileReadback(
    entries: List<WifiConfiguredEntry>,
    profile: List<WifiNetworkProfile>,
    knownCredentialWrites: Set<String>,
): String {
    if (entries.map { it.networkId }.toSet().size != profile.size) return "mismatch"
    val matchedIds = HashSet<Int>()
    for (expected in profile) {
        val actual = entries.filter {
            it.ssid == expected.ssid && it.security == expected.security
        }.distinctBy { it.networkId }.singleOrNull() ?: return "mismatch"
        if (!matchedIds.add(actual.networkId) || actual.hidden != expected.hidden) return "mismatch"
        if (actual.passwordReadable && actual.password != expected.password) return "mismatch"
        if (!actual.passwordReadable && expected.identity !in knownCredentialWrites) return "unknown"
    }
    return "validated"
}

internal class WifiAdapterFailure(val reason: WifiReason) : Exception()

internal interface WifiSettingsAdapter {
    fun readCurrent(): List<WifiConfiguredEntry>
    fun applyProfile(
        target: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean = { true },
        beforeFirstMutation: () -> WifiReason? = { null },
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun disconnectCurrent(
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason
    fun reconnect(
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason
}

/** Writes known MDM credentials using public Android configured-network APIs. */
internal class WifiManagerAdapter(context: Context) : WifiSettingsAdapter {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val devicePolicyManager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val mutationLock = ReentrantLock()

    override fun readCurrent(): List<WifiConfiguredEntry> {
        requireManagementAuthority()
        val manager = wifiManager ?: throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        try {
            @Suppress("DEPRECATION")
            val configured = manager.configuredNetworks
                ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            return configured.map(::readEntry)
        } catch (failure: WifiAdapterFailure) {
            throw failure
        } catch (_: SecurityException) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        } catch (_: Exception) {
            throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
        }
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean,
        beforeFirstMutation: () -> WifiReason?,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason {
        try {
            requireManagementAuthority()
            val manager = wifiManager ?: return WifiReason.UNSUPPORTED
            val initial = readCurrent()
            val retainedIds = HashSet<Int>()
            val changedIdentity = HashSet<String>()
            var mutationPrepared = false
            fun <T> mutateApi(block: () -> T): Pair<WifiReason?, T?> = mutationLock.withLock {
                if (!shouldContinue()) return@withLock WifiReason.TIMEOUT_ROLLED_BACK to null
                if (!mutationPrepared) {
                    val failure = beforeFirstMutation()
                    if (failure != null) return@withLock failure to null
                    mutationPrepared = true
                }
                if (!shouldContinue()) return@withLock WifiReason.TIMEOUT_ROLLED_BACK to null
                val refusal = onMutationStarted()
                if (refusal != null) return@withLock refusal to null
                try {
                    null to block()
                } finally {
                    onMutationFinished()
                }
            }

            for (network in target) {
                if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
                val matching = initial.filter { it.ssid == network.ssid && it.security == network.security }
                val exact = matching.firstOrNull { currentMatches(it, network) }
                if (exact != null) {
                    retainedIds.add(exact.networkId)
                    // Managed networks must be enabled so Android can reconnect automatically.
                    if (!exact.enabled) {
                        val (reason, enabled) = mutateApi { enableNetwork(manager, exact.networkId) }
                        if (reason != null) return reason
                        if (enabled != true) return WifiReason.UPDATE_FAILED
                    }
                    continue
                }

                val existing = matching.firstOrNull()
                val configured = toWifiConfiguration(network).apply {
                    networkId = existing?.networkId ?: -1
                }

                if (existing == null) {
                    val (addReason, id) = mutateApi { addNetwork(manager, configured) }
                    if (addReason != null) return addReason
                    val networkId = id ?: return WifiReason.ADD_FAILED
                    if (networkId < 0) return WifiReason.ADD_FAILED
                    val (enableReason, enabled) = mutateApi { enableNetwork(manager, networkId) }
                    if (enableReason != null) return enableReason
                    if (enabled != true) {
                        return WifiReason.UPDATE_FAILED
                    }
                    retainedIds.add(networkId)
                } else {
                    val (updateReason, id) = mutateApi { updateNetwork(manager, configured) }
                    if (updateReason != null) return updateReason
                    val networkId = id ?: return WifiReason.UPDATE_FAILED
                    if (networkId < 0) return WifiReason.UPDATE_FAILED
                    val (enableReason, enabled) = mutateApi { enableNetwork(manager, networkId) }
                    if (enableReason != null) return enableReason
                    if (enabled != true) {
                        return WifiReason.UPDATE_FAILED
                    }
                    retainedIds.add(networkId)
                }
                changedIdentity.add(network.identity)
            }

            // Removal happens after every desired network has a successful API result.
            for (entry in initial.distinctBy { it.networkId }) {
                if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
                if (entry.networkId in retainedIds) continue
                val (removeReason, removed) = mutateApi { removeNetwork(manager, entry.networkId) }
                if (removeReason != null) return removeReason
                if (removed != true) {
                    return WifiReason.DELETE_FAILED
                }
            }

            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            val finalEntries = readCurrent()
            val readback = wifiProfileReadback(finalEntries, target, changedIdentity)
            return if (readback == "validated") {
                WifiReason.NONE
            } else {
                val process = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(process)
                val matches = target.mapIndexed { index, network ->
                    val views = finalEntries.filter { it.ssid == network.ssid && it.security == network.security }
                    "$index:views=${views.size},hidden=${views.any { it.hidden == network.hidden }}," +
                        "readable=${views.any { it.passwordReadable }},written=${network.identity in changedIdentity}"
                }
                Log.w("WifiSettings", "Configuration readback=$readback expected=${target.size} " +
                    "ids=${finalEntries.map { it.networkId }.toSet().size} importance=${process.importance} matches=$matches")
                WifiReason.VERIFY_FAILED
            }
        } catch (failure: WifiAdapterFailure) {
            return failure.reason
        } catch (_: SecurityException) {
            return WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            return WifiReason.CONFIG_READ_FAILED
        }
    }

    override fun disconnectCurrent(
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason = runConnectionMutation(shouldContinue, onMutationStarted, onMutationFinished) { manager ->
        @Suppress("DEPRECATION")
        if (manager.disconnect()) WifiReason.NONE else WifiReason.UNREACHABLE
    }

    override fun reconnect(
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason = runConnectionMutation(shouldContinue, onMutationStarted, onMutationFinished) { manager ->
        @Suppress("DEPRECATION")
        if (manager.reconnect()) WifiReason.NONE else WifiReason.UNREACHABLE
    }

    private inline fun runConnectionMutation(
        shouldContinue: () -> Boolean,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
        action: (WifiManager) -> WifiReason,
    ): WifiReason = mutationLock.withLock {
        var started = false
        try {
            requireManagementAuthority()
            val manager = wifiManager ?: return WifiReason.UNSUPPORTED
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            val refusal = onMutationStarted()
            if (refusal != null) return refusal
            started = true
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            action(manager)
        } catch (failure: WifiAdapterFailure) {
            failure.reason
        } catch (_: SecurityException) {
            WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            WifiReason.UNREACHABLE
        } finally {
            if (started) onMutationFinished()
        }
    }

    private fun requireManagementAuthority() {
        if (appContext.checkSelfPermission("android.permission.ACCESS_WIFI_STATE") != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission("android.permission.CHANGE_WIFI_STATE") != PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED
        ) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        }
        val deviceOwner = try {
            devicePolicyManager?.isDeviceOwnerApp(appContext.packageName) == true
        } catch (_: SecurityException) {
            false
        }
        val systemWifiAuthority = appContext.packageManager.checkPermission(
            "android.permission.NETWORK_SETTINGS",
            appContext.packageName,
        ) == PackageManager.PERMISSION_GRANTED
        if (!deviceOwner && !systemWifiAuthority) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        }
    }

    private fun readEntry(config: WifiConfiguration): WifiConfiguredEntry {
        val ssid = unquote(config.SSID) ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
        val keyManagement = config.allowedKeyManagement
        val security = when {
            keyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) && keyManagement.cardinality() == 1 -> "WPA_PSK"
            keyManagement.get(WifiConfiguration.KeyMgmt.NONE) && keyManagement.cardinality() == 1 &&
                config.wepKeys.all { it == null } -> "OPEN"
            else -> null
        }
        val rawPassword = config.preSharedKey
        val passwordReadable = security != "WPA_PSK" ||
            (!rawPassword.isNullOrEmpty() && rawPassword != "*" && rawPassword != "\"*\"")
        val password = if (security == "WPA_PSK" && passwordReadable) unquote(rawPassword) else ""
        if (passwordReadable && security == "WPA_PSK" && password == null) {
            throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
        }
        return WifiConfiguredEntry(
            networkId = config.networkId,
            ssid = ssid,
            security = security,
            password = password,
            passwordReadable = passwordReadable,
            hidden = config.hiddenSSID,
            enabled = config.status != WifiConfiguration.Status.DISABLED,
        )
    }

    private fun currentMatches(current: WifiConfiguredEntry, requested: WifiNetworkProfile): Boolean =
        current.ssid == requested.ssid &&
            current.security == requested.security &&
            current.hidden == requested.hidden &&
            current.passwordReadable &&
            current.password == requested.password

    private fun toWifiConfiguration(profile: WifiNetworkProfile) = WifiConfiguration().apply {
        SSID = "\"${profile.ssid}\""
        hiddenSSID = profile.hidden
        allowedKeyManagement.clear()
        when (profile.security) {
            "OPEN" -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            "WPA_PSK" -> {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                preSharedKey = if (profile.password.length == 64 && profile.password.all { it.digitToIntOrNull(16) != null }) {
                    profile.password
                } else {
                    quote(profile.password)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addNetwork(manager: WifiManager, config: WifiConfiguration): Int = manager.addNetwork(config)

    @Suppress("DEPRECATION")
    private fun updateNetwork(manager: WifiManager, config: WifiConfiguration): Int = manager.updateNetwork(config)

    @Suppress("DEPRECATION")
    private fun removeNetwork(manager: WifiManager, networkId: Int): Boolean = manager.removeNetwork(networkId)

    @Suppress("DEPRECATION")
    private fun enableNetwork(manager: WifiManager, networkId: Int): Boolean = manager.enableNetwork(networkId, false)

    private fun unquote(value: String?): String? {
        if (value == null) return null
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            val result = StringBuilder(value.length - 2)
            var escaped = false
            for (character in value.substring(1, value.length - 1)) {
                if (escaped) {
                    result.append(character)
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else {
                    result.append(character)
                }
            }
            if (escaped) return null
            return result.toString()
        }
        return value.takeIf { it.isNotEmpty() }
    }

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
