package com.styly.mdmclient

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Parcel
import android.util.Base64
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
    val priority: Int,
    val configurationParcel: String,
)

internal class WifiAdapterFailure(val reason: WifiReason) : Exception()

internal interface WifiSettingsAdapter {
    fun readCurrent(): List<WifiConfiguredEntry>
    fun restorableSnapshot(): List<WifiNetworkSnapshot>
    fun applyProfile(
        target: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean = { true },
        restoreSnapshot: List<WifiNetworkSnapshot>? = null,
        beforeFirstMutation: () -> WifiReason? = { null },
        expectedSnapshot: List<WifiNetworkSnapshot>? = null,
        onMutationStarted: () -> WifiReason? = { null },
        onMutationFinished: () -> Unit = {},
    ): WifiReason
    fun compareTo(profile: List<WifiNetworkProfile>): String
    fun validateFinalState(profile: List<WifiNetworkProfile>, priorApiWriteValidated: Boolean): String
}

/** Uses only public Android configured-network APIs and fails closed without full-list authority. */
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

    override fun restorableSnapshot(): List<WifiNetworkSnapshot> {
        val current = readCurrent()
        if (current.map { it.ssid + "\u0000" + it.security }.toSet().size != current.size) {
            throw WifiAdapterFailure(WifiReason.RESTORE_INFO_MISSING)
        }
        val snapshot = ArrayList<WifiNetworkSnapshot>(current.size)
        for (entry in current) {
            val security = entry.security ?: throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
            if (security == "WPA_PSK" && (!entry.passwordReadable || entry.password == null)) {
                throw WifiAdapterFailure(WifiReason.RESTORE_INFO_MISSING)
            }
            try {
                WifiSettingsProtocol.validateNetwork(entry.ssid, security, entry.password.orEmpty())
            } catch (_: IllegalArgumentException) {
                throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
            }
            snapshot.add(
                WifiNetworkSnapshot(
                    WifiNetworkProfile(entry.ssid, security, entry.password.orEmpty(), entry.hidden),
                    entry.enabled,
                    entry.priority,
                    entry.configurationParcel,
                ),
            )
        }
        return snapshot
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>,
        shouldContinue: () -> Boolean,
        restoreSnapshot: List<WifiNetworkSnapshot>?,
        beforeFirstMutation: () -> WifiReason?,
        expectedSnapshot: List<WifiNetworkSnapshot>?,
        onMutationStarted: () -> WifiReason?,
        onMutationFinished: () -> Unit,
    ): WifiReason {
        try {
            requireManagementAuthority()
            val manager = wifiManager ?: return WifiReason.UNSUPPORTED
            val initial = readCurrent()
            if (expectedSnapshot != null && !sameSnapshot(initial, expectedSnapshot)) {
                return WifiReason.RESTORE_INFO_MISSING
            }
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
                    if (restoreSnapshot == null) {
                        // Managed networks must be enabled so Android can reconnect automatically.
                        if (!exact.enabled) {
                            val (reason, enabled) = mutateApi { enableNetwork(manager, exact.networkId) }
                            if (reason != null) return reason
                            if (enabled != true) return WifiReason.UPDATE_FAILED
                        }
                    }
                    continue
                }

                val existing = matching.firstOrNull()
                val restoreConfig = restoreSnapshot
                    ?.firstOrNull { it.profile.identity == network.identity }
                    ?.configurationParcel
                if (restoreSnapshot != null && restoreConfig == null) return WifiReason.RESTORE_INFO_MISSING
                val baseConfig = when {
                    restoreConfig != null -> unmarshalConfiguration(restoreConfig)
                    existing != null -> unmarshalConfiguration(existing.configurationParcel)
                    else -> null
                }
                val configured = toWifiConfiguration(network, baseConfig).apply {
                    networkId = existing?.networkId ?: -1
                    if (existing != null) {
                        priority = if (restoreSnapshot == null) 0 else {
                            restoreSnapshot.firstOrNull { it.profile.identity == network.identity }?.priority ?: 0
                        }
                    }
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
                    if (restoreSnapshot == null) {
                        val (enableReason, enabled) = mutateApi { enableNetwork(manager, networkId) }
                        if (enableReason != null) return enableReason
                        if (enabled != true) {
                            return WifiReason.UPDATE_FAILED
                        }
                    }
                    retainedIds.add(networkId)
                }
                changedIdentity.add(network.identity)
            }

            // Removal happens after every desired network has a successful API result.
            for (entry in initial) {
                if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
                if (entry.networkId in retainedIds) continue
                val (removeReason, removed) = mutateApi { removeNetwork(manager, entry.networkId) }
                if (removeReason != null) return removeReason
                if (removed != true) {
                    return WifiReason.DELETE_FAILED
                }
            }

            if (restoreSnapshot != null) {
                val afterRestore = readCurrent()
                for (saved in restoreSnapshot) {
                    if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
                    val current = afterRestore.firstOrNull {
                        it.ssid == saved.profile.ssid && it.security == saved.profile.security
                    } ?: return WifiReason.VERIFY_FAILED
                    if (current.priority != saved.priority) {
                        val parcel = saved.configurationParcel ?: return WifiReason.RESTORE_INFO_MISSING
                        val configured = unmarshalConfiguration(parcel).apply {
                            networkId = current.networkId
                            priority = saved.priority
                        }
                        val (updateReason, updated) = mutateApi { updateNetwork(manager, configured) }
                        if (updateReason != null) return updateReason
                        if (updated == null || updated < 0) {
                            return WifiReason.RESTORE_FAILED
                        }
                    }
                    val enabledResult = if (saved.enabled) {
                        mutateApi { enableNetwork(manager, current.networkId) }
                    } else {
                        mutateApi { disableNetwork(manager, current.networkId) }
                    }
                    if (enabledResult.first != null) return enabledResult.first!!
                    if (enabledResult.second != true) return WifiReason.RESTORE_FAILED
                }
            }

            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            val finalEntries = readCurrent()
            val expectedCount = target.size
            if (finalEntries.size != expectedCount) return WifiReason.VERIFY_FAILED
            for (network in target) {
                val current = finalEntries.singleOrNull {
                    it.ssid == network.ssid && it.security == network.security
                } ?: return WifiReason.VERIFY_FAILED
                if (current.hidden != network.hidden) return WifiReason.VERIFY_FAILED
                if (current.passwordReadable && current.password != network.password) {
                    return WifiReason.VERIFY_FAILED
                }
                // Android may mask a PSK. The write API result plus readable fields are retained,
                // but a masked value is never reported as a password match by observations.
                if (!current.passwordReadable && network.identity !in changedIdentity) {
                    return WifiReason.VERIFY_FAILED
                }
            }
            return WifiReason.NONE
        } catch (failure: WifiAdapterFailure) {
            return failure.reason
        } catch (_: SecurityException) {
            return WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            return WifiReason.CONFIG_READ_FAILED
        }
    }

    override fun compareTo(profile: List<WifiNetworkProfile>): String {
        val entries = try {
            readCurrent()
        } catch (_: WifiAdapterFailure) {
            return "unknown"
        }
        if (entries.any { it.security == null }) return "unknown"
        if (entries.any { it.security == "WPA_PSK" && !it.passwordReadable }) return "unknown"
        if (entries.size != profile.size) return "mismatch"
        for (expected in profile) {
            val actual = entries.singleOrNull {
                it.ssid == expected.ssid && it.security == expected.security
            } ?: return "mismatch"
            if (actual.hidden != expected.hidden || actual.password != expected.password) return "mismatch"
        }
        return "match"
    }

    /** Validate visible fields after a successful API write without claiming masked PSKs match. */
    override fun validateFinalState(profile: List<WifiNetworkProfile>, priorApiWriteValidated: Boolean): String {
        val entries = try {
            readCurrent()
        } catch (_: WifiAdapterFailure) {
            return "unknown"
        }
        if (entries.any { it.security == null }) return "unknown"
        if (entries.size != profile.size) return "mismatch"
        for (expected in profile) {
            val actual = entries.singleOrNull {
                it.ssid == expected.ssid && it.security == expected.security
            } ?: return "mismatch"
            if (actual.hidden != expected.hidden) return "mismatch"
            if (actual.security == "WPA_PSK") {
                if (actual.passwordReadable && actual.password != expected.password) return "mismatch"
                if (!actual.passwordReadable && !priorApiWriteValidated) return "unknown"
            }
        }
        return "validated"
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
            priority = config.priority,
            configurationParcel = marshalConfiguration(config),
        )
    }

    private fun currentMatches(current: WifiConfiguredEntry, requested: WifiNetworkProfile): Boolean =
        current.ssid == requested.ssid &&
            current.security == requested.security &&
            current.hidden == requested.hidden &&
            current.passwordReadable &&
            current.password == requested.password

    private fun sameSnapshot(current: List<WifiConfiguredEntry>, expected: List<WifiNetworkSnapshot>): Boolean {
        if (current.size != expected.size) return false
        return expected.all { saved ->
            val matches = current.filter {
                it.ssid == saved.profile.ssid && it.security == saved.profile.security
            }
            matches.size == 1 && currentMatches(matches.single(), saved.profile) &&
                matches.single().enabled == saved.enabled && matches.single().priority == saved.priority
        }
    }

    private fun toWifiConfiguration(
        profile: WifiNetworkProfile,
        base: WifiConfiguration? = null,
    ) = (base ?: WifiConfiguration()).apply {
        SSID = "\"${profile.ssid}\""
        hiddenSSID = profile.hidden
        priority = 0
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

    @Suppress("DEPRECATION")
    private fun disableNetwork(manager: WifiManager, networkId: Int): Boolean = manager.disableNetwork(networkId)

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

    private fun marshalConfiguration(config: WifiConfiguration): String {
        val parcel = Parcel.obtain()
        return try {
            config.writeToParcel(parcel, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun unmarshalConfiguration(encoded: String): WifiConfiguration {
        val parcel = Parcel.obtain()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            parcel.readParcelable<WifiConfiguration>(WifiConfiguration::class.java.classLoader)
                ?: throw WifiAdapterFailure(WifiReason.RESTORE_INFO_MISSING)
        } catch (_: Exception) {
            throw WifiAdapterFailure(WifiReason.RESTORE_INFO_MISSING)
        } finally {
            parcel.recycle()
        }
    }
}
