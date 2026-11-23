package com.styly.mdmclient

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBService
import com.pvr.tobservice.interfaces.IToBServiceProxy

internal data class WifiConfiguredEntry(
    val networkId: Int,
    val ssid: String,
    val security: String?,
    val hidden: Boolean,
    val autoJoinEnabled: Boolean,
)

internal data class WifiConfiguredView(
    val entry: WifiConfiguredEntry,
    val original: WifiConfiguration,
)

internal data class WifiProfileApplication(
    val usableIndexes: List<Int>,
    val failure: WifiReason? = null,
    /** Profiles whose saved SSID/security/hidden configuration was added or rewritten. */
    val rewrittenIndexes: Set<Int> = emptySet(),
    val networkIds: Map<Int, Int> = emptyMap(),
)

private val WIFI_SECURITY_VIEW_PREFERENCE = listOf("OPEN", "WPA_PSK", "SAE")

/** Whether a saved or observed security type satisfies a validated [profile]. */
internal fun wifiSecurityViewMatchesProfile(profile: WifiNetworkProfile, security: String?): Boolean = when {
    profile.security == "OPEN" -> security == "OPEN"
    profile.security != "WPA2_WPA3" -> false
    profile.requiresSaeOnly -> security == "SAE"
    else -> security == "WPA_PSK" || security == "SAE"
}

internal fun desiredWifiSecurityView(profile: WifiNetworkProfile, savedSecurity: String? = null): String? =
    savedSecurity?.takeIf { wifiSecurityViewMatchesProfile(profile, it) } ?: preferredWifiSecurityView(profile)

private fun preferredWifiSecurityView(
    profile: WifiNetworkProfile,
    securities: Collection<String?> = WIFI_SECURITY_VIEW_PREFERENCE,
): String? = WIFI_SECURITY_VIEW_PREFERENCE.firstOrNull {
    it in securities && wifiSecurityViewMatchesProfile(profile, it)
}

/** Return null for an absent candidate; reject an identity shared by distinct saved networks. */
internal fun resolveWifiProfileViewIndex(
    profile: WifiNetworkProfile,
    entries: List<WifiConfiguredEntry>,
): Int? {
    val matches = entries.indices.filter { entries[it].matches(profile) }
    if (matches.isEmpty()) return null
    if (matches.map { entries[it].networkId }.distinct().size != 1) {
        throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
    }
    val preferred = preferredWifiSecurityView(profile, matches.map { entries[it].security })
    return matches.firstOrNull { entries[it].security == preferred }
        ?: throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
}

private fun WifiConfiguredEntry.matches(profile: WifiNetworkProfile): Boolean =
    ssid == profile.ssid && wifiSecurityViewMatchesProfile(profile, security)

/** Secured profiles need evidence for the current Android ID because their credentials are masked. */
internal fun canReuseConfiguredWifi(
    entry: WifiConfiguredEntry,
    profile: WifiNetworkProfile,
    trustedNetworkIds: Map<WifiNetworkProfile, Int>?,
): Boolean = trustedNetworkIds != null &&
    (profile.security == "OPEN" || trustedNetworkIds[profile] == entry.networkId) &&
    entry.matches(profile) && entry.hidden == profile.hidden

/** Restrict auto-join to the requested network IDs after at least one confirmed save. */
internal fun wifiAutoJoinChanges(
    entries: List<WifiConfiguredEntry>,
    target: List<WifiNetworkProfile>,
    prioritizeNetworks: Boolean,
    hasVerifiedCandidate: Boolean,
): Map<Int, Boolean> {
    if (!hasVerifiedCandidate) return emptyMap()
    // PUI may expose multiple security views of a single saved network ID.
    val enabledIds = if (prioritizeNetworks) {
        val primary = target.first()
        val primaryId = entries.filter { it.matches(primary) }.map { it.networkId }.distinct().singleOrNull()
        setOfNotNull(primaryId)
    } else {
        entries.filter { entry -> target.any(entry::matches) }.mapTo(mutableSetOf()) { it.networkId }
    }
    val changes = linkedMapOf<Int, Boolean>()
    for (entry in entries) {
        if (entry.networkId in enabledIds && !entry.autoJoinEnabled) changes[entry.networkId] = true
    }
    for (entry in entries) {
        if (entry.networkId !in enabledIds && entry.autoJoinEnabled) changes[entry.networkId] = false
    }
    return changes
}

/**
 * Return only candidates that were written and whose saved settings can be read back.
 * Each candidate is checked independently so one failed write does not hide a later usable one.
 */
internal fun wifiProfileReadbackIndexes(
    entries: List<WifiConfiguredEntry>,
    profiles: List<WifiNetworkProfile>,
    writtenNetworks: Map<Int, Pair<Int, String>>,
): List<Int> = profiles.indices.filter { index ->
    val (networkId, expectedSecurity) = writtenNetworks[index] ?: return@filter false
    val saved = entries.filter { it.networkId == networkId }
    saved.any {
        it.ssid == profiles[index].ssid && it.security == expectedSecurity
    } && saved.all {
        it.ssid == profiles[index].ssid && it.hidden == profiles[index].hidden
    }
}

internal fun wifiSavedUpdateReadback(
    entries: List<WifiConfiguredEntry>,
    original: WifiConfiguredEntry,
    security: String,
    hidden: Boolean,
): Boolean {
    val saved = entries.filter { it.networkId == original.networkId }
    return saved.any { it.security == security } && saved.all {
        it.ssid == original.ssid && it.hidden == hidden && it.autoJoinEnabled == original.autoJoinEnabled
    }
}

private const val SAVED_WIFI_DUMP_BEGIN = "WifiConfigManager - Configured networks Begin ----"
private const val SAVED_WIFI_DUMP_END = "WifiConfigManager - Configured networks End ----"
private const val SAVED_WIFI_MAC_PREFIX = " macRandomizationSetting: "
private val WIFI_MAC_RANDOMIZATION_SETTINGS = setOf(
    WifiConfiguration.RANDOMIZATION_AUTO,
    WifiConfiguration.RANDOMIZATION_NONE,
    WifiConfiguration.RANDOMIZATION_PERSISTENT,
    WifiConfiguration.RANDOMIZATION_NON_PERSISTENT,
)
private val savedWifiHeader = Regex("(?:\\* |- DSBLE )?ID: (\\d+) SSID: (.*)")

/** Read one existing network's MAC policy, rejecting ambiguous or incomplete PUI dumps. */
internal fun wifiStoredMacSetting(lines: Sequence<String>, networkId: Int, ssid: String): Int? {
    var inSavedNetworks = false
    var seenId = false
    var readingTarget = false
    var result: Int? = null
    for (line in lines) {
        if (!inSavedNetworks) {
            inSavedNetworks = line == SAVED_WIFI_DUMP_BEGIN
            continue
        }
        if (line == SAVED_WIFI_DUMP_END) return result
        if (line == SAVED_WIFI_DUMP_BEGIN) return null
        val header = savedWifiHeader.matchEntire(line)
        if (header != null) {
            readingTarget = false
            if (header.groupValues[1].toIntOrNull() != networkId) continue
            if (seenId) return null
            seenId = true
            val displayed = header.groupValues[2]
            // PUI 5.15.7 masks indices zero through length / 4 in WifiConfiguration.toString().
            val maskedLength = ssid.length / 4 + 1
            val maskedSsid = "*".repeat(maskedLength) + ssid.drop(maskedLength)
            readingTarget = displayed.startsWith("$ssid PROVIDER-NAME: ") ||
                displayed.startsWith("$maskedSsid PROVIDER-NAME: ")
        } else if (readingTarget && line.startsWith(SAVED_WIFI_MAC_PREFIX)) {
            if (result != null) return null
            result = line.removePrefix(SAVED_WIFI_MAC_PREFIX).toIntOrNull()
                ?.takeIf { it in WIFI_MAC_RANDOMIZATION_SETTINGS } ?: return null
        }
    }
    return null
}

// PUI can expose different product names to apps and to the ADB shell.
internal fun supportsPico4UltraEnterpriseSettings(
    product: String = Build.PRODUCT,
    manufacturer: String = Build.MANUFACTURER,
): Boolean = manufacturer.equals("Pico", ignoreCase = true) &&
    product == "PICO 4 Ultra Enterprise"

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
internal fun supportsManagedWifi(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && supportsPico4UltraEnterpriseSettings()

internal class WifiAdapterFailure(val reason: WifiReason) : Exception()

internal fun hasManagedWifiLocationPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun hasManagedWifiReadAccess(context: Context): Boolean = Settings.canDrawOverlays(context)

internal fun hasManagedWifiDiagnosticsAccess(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

internal interface WifiSettingsAdapter {
    fun isWifiEnabled(): Boolean
    fun applyProfile(
        target: List<WifiNetworkProfile>,
        prioritizeNetworks: Boolean,
        trustedNetworkIds: Map<WifiNetworkProfile, Int>?,
        beforeMutation: () -> WifiReason?,
    ): WifiProfileApplication
    fun selectSavedCandidate(candidate: WifiNetworkProfile, beforeMutation: () -> WifiReason?): WifiReason
    fun disconnectCurrent(beforeMutation: () -> WifiReason?): WifiReason
}

/** PICO 4 Ultra Enterprise: retain saved credentials and change per-network auto-join. */
internal class WifiManagerAdapter(context: Context) : WifiSettingsAdapter {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val autoJoinField by lazy { WifiConfiguration::class.java.getField("allowAutojoin") }
    private val ownedMacSettings by lazy { appContext.getSharedPreferences("wifi-owned-mac-settings", Context.MODE_PRIVATE) }

    override fun isWifiEnabled(): Boolean = runCatching { manager?.isWifiEnabled == true }.getOrDefault(false)

    private fun readCurrent(): List<WifiConfiguredEntry> = configuredNetworkViews().map { it.entry }

    private fun resolveView(profile: WifiNetworkProfile, views: List<WifiConfiguredView> = configuredNetworkViews()): WifiConfiguredView? =
        resolveWifiProfileViewIndex(profile, views.map { it.entry })?.let(views::get)

    private fun configuredNetworkViews(): List<WifiConfiguredView> {
        requireAuthority()
        return try {
            @Suppress("DEPRECATION")
            val configs = manager?.configuredNetworks ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            configs.mapNotNull { config ->
                val ssid = normalizeConfiguredWifiSsid(config.SSID) ?: return@mapNotNull null
                val entry = WifiConfiguredEntry(config.networkId, ssid, config.securityView(),
                    config.hiddenSSID, autoJoinField.getBoolean(config))
                WifiConfiguredView(entry, config)
            }
        } catch (failure: WifiAdapterFailure) { throw failure
        } catch (_: SecurityException) { throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        } catch (_: ReflectiveOperationException) { throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        } catch (_: Exception) { throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED) }
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>,
        prioritizeNetworks: Boolean,
        trustedNetworkIds: Map<WifiNetworkProfile, Int>?,
        beforeMutation: () -> WifiReason?,
    ): WifiProfileApplication = guardedApplication {
        val wifi = manager ?: return@guardedApplication WifiProfileApplication(emptyList(), WifiReason.UNSUPPORTED)
        val service = try {
            autoJoinService()
        } catch (failure: WifiAdapterFailure) {
            logOperationFailure("AUTO_JOIN", failure.reason)
            return@guardedApplication WifiProfileApplication(emptyList(), failure.reason)
        } catch (_: Exception) {
            logOperationFailure("AUTO_JOIN", WifiReason.UNSUPPORTED)
            return@guardedApplication WifiProfileApplication(emptyList(), WifiReason.UNSUPPORTED)
        }
        if (target.any { it.requiresSaeOnly } && !wifi.isWpa3SaeSupported) {
            return@guardedApplication WifiProfileApplication(emptyList(), WifiReason.UNSUPPORTED)
        }

        var firstFailure: WifiReason? = null
        var commonFailure: WifiReason? = null
        val writtenNetworks = linkedMapOf<Int, Pair<Int, String>>()
        val rewritten = mutableSetOf<Int>()
        fun checkMutationAllowed(): Boolean {
            val reason = try {
                beforeMutation()
            } catch (_: Exception) {
                WifiReason.UNKNOWN
            }
            if (reason == null) return true
            commonFailure = reason
            firstFailure = reason
            return false
        }
        val beforeWrite = { if (checkMutationAllowed()) null else commonFailure }

        for ((index, network) in target.withIndex()) {
            if (commonFailure != null) break
            var operation = "UPDATE"
            val result = try {
                val views = configuredNetworkViews()
                val existing = resolveView(network, views)
                val reusable = existing?.takeIf { view ->
                    views.filter { it.entry.networkId == view.entry.networkId }
                        .all { canReuseConfiguredWifi(it.entry, network, trustedNetworkIds) }
                }
                val written = if (reusable != null) {
                    reusable.entry.networkId to checkNotNull(reusable.entry.security)
                } else if (existing == null) {
                    operation = "ADD"
                    addSavedNetwork(wifi, network, views, beforeWrite)
                } else {
                    updateSavedNetwork(wifi, service, network, existing, beforeWrite)
                }
                writtenNetworks[index] = written
                if (reusable == null) rewritten += index
                null
            } catch (_: SecurityException) {
                commonFailure = WifiReason.PERMISSION_DENIED
                WifiReason.PERMISSION_DENIED
            } catch (failure: WifiAdapterFailure) {
                failure.reason
            } catch (_: Exception) {
                WifiReason.UPDATE_FAILED
            }
            if (result != null) {
                firstFailure = commonFailure ?: firstFailure ?: result
                logOperationFailure(operation, result)
            }
        }

        if (commonFailure != null || writtenNetworks.isEmpty()) {
            return@guardedApplication WifiProfileApplication(emptyList(), firstFailure ?: WifiReason.UPDATE_FAILED)
        }

        val beforeAutoJoin = readCurrent()
        val savedIndexes = wifiProfileReadbackIndexes(beforeAutoJoin, target, writtenNetworks)
        val autoJoinChanges = wifiAutoJoinChanges(beforeAutoJoin, target, prioritizeNetworks, savedIndexes.isNotEmpty())
        var autoJoinFailure: WifiReason? = null
        for ((id, allowed) in autoJoinChanges) {
            if (!checkMutationAllowed()) break
            val result = try {
                if (service.allowWifiAutoJoin(null, id, allowed) == 0) WifiReason.NONE else WifiReason.UPDATE_FAILED
            } catch (_: SecurityException) {
                WifiReason.PERMISSION_DENIED
            } catch (_: LinkageError) {
                WifiReason.UNSUPPORTED
            } catch (_: Exception) {
                WifiReason.UPDATE_FAILED
            }
            if (result != WifiReason.NONE) {
                autoJoinFailure = autoJoinFailure ?: result
                logOperationFailure("AUTO_JOIN", result)
                if (result == WifiReason.PERMISSION_DENIED) {
                    commonFailure = result
                    firstFailure = result
                    break
                }
            }
        }

        if (commonFailure != null) {
            return@guardedApplication WifiProfileApplication(emptyList(), firstFailure)
        }
        val readback = if (autoJoinChanges.isEmpty()) beforeAutoJoin else readCurrent()
        val usableIndexes = if (autoJoinChanges.isEmpty()) savedIndexes
            else wifiProfileReadbackIndexes(readback, target, writtenNetworks)
        if (usableIndexes.size != writtenNetworks.size) {
            firstFailure = firstFailure ?: WifiReason.VERIFY_FAILED
        }
        if (autoJoinFailure == null && autoJoinChanges.isNotEmpty() &&
            wifiAutoJoinChanges(readback, target, prioritizeNetworks, hasVerifiedCandidate = true).isNotEmpty()
        ) {
            autoJoinFailure = WifiReason.VERIFY_FAILED
            logOperationFailure("AUTO_JOIN", WifiReason.VERIFY_FAILED)
        }
        WifiProfileApplication(
            usableIndexes,
            autoJoinFailure ?: firstFailure,
            rewritten.intersect(usableIndexes.toSet()),
            networkIds = usableIndexes.associateWith { writtenNetworks.getValue(it).first },
        )
    }

    override fun selectSavedCandidate(
        candidate: WifiNetworkProfile,
        beforeMutation: () -> WifiReason?,
    ): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        if (!isWifiEnabled()) return@guarded WifiReason.UNREACHABLE
        val current = readCurrent()
        val entry = resolveWifiProfileViewIndex(candidate, current)?.let(current::get)
            ?: return@guarded WifiReason.VERIFY_FAILED
        beforeMutation()?.let { return@guarded it }
        if (!isWifiEnabled()) return@guarded WifiReason.UNREACHABLE
        val reason = try {
            @Suppress("DEPRECATION")
            if (wifi.enableNetwork(entry.networkId, true)) WifiReason.NONE else WifiReason.UPDATE_FAILED
        } catch (_: SecurityException) {
            WifiReason.PERMISSION_DENIED
        } catch (_: Exception) {
            WifiReason.UPDATE_FAILED
        }
        if (reason != WifiReason.NONE) logOperationFailure("AUTO_JOIN", reason)
        reason
    }

    override fun disconnectCurrent(beforeMutation: () -> WifiReason?): WifiReason = guarded {
        val wifi = manager ?: return@guarded WifiReason.UNSUPPORTED
        if (!isWifiEnabled()) return@guarded WifiReason.UNREACHABLE
        beforeMutation()?.let { return@guarded it }
        if (!isWifiEnabled()) return@guarded WifiReason.UNREACHABLE
        @Suppress("DEPRECATION")
        if (wifi.disconnect()) WifiReason.NONE else WifiReason.UNREACHABLE
    }

    private fun autoJoinService(): IToBServiceProxy {
        return ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy
            ?: throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
    }

    fun readinessFailure(): WifiReason? = try {
        requireAuthority()
        if (manager == null || ToBServiceHelper.getInstance().serviceBinder !is IToBServiceProxy) {
            WifiReason.UNSUPPORTED
        } else null
    } catch (failure: WifiAdapterFailure) {
        failure.reason
    }

    private fun requireAuthority() {
        if (!supportsManagedWifi()) throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        if (!hasManagedWifiLocationPermissions(appContext)) throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        if (!hasManagedWifiReadAccess(appContext)) throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
    }

    private inline fun guarded(action: () -> WifiReason): WifiReason {
        val reason = try {
            requireAuthority()
            action()
        } catch (failure: WifiAdapterFailure) { failure.reason
        } catch (_: SecurityException) { WifiReason.PERMISSION_DENIED
        } catch (_: LinkageError) { WifiReason.UNSUPPORTED
        } catch (_: Exception) { WifiReason.CONFIG_READ_FAILED }
        if (reason != WifiReason.NONE) android.util.Log.w("WifiSettings", "Wi-Fi adapter failed: ${reason.wireValue}")
        return reason
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU, lambda = 0)
    private inline fun guardedApplication(action: () -> WifiProfileApplication): WifiProfileApplication {
        val result = try {
            requireAuthority()
            action()
        } catch (failure: WifiAdapterFailure) {
            WifiProfileApplication(emptyList(), failure.reason)
        } catch (_: SecurityException) {
            WifiProfileApplication(emptyList(), WifiReason.PERMISSION_DENIED)
        } catch (_: LinkageError) {
            WifiProfileApplication(emptyList(), WifiReason.UNSUPPORTED)
        } catch (_: Exception) {
            WifiProfileApplication(emptyList(), WifiReason.CONFIG_READ_FAILED)
        }
        result.failure?.let { android.util.Log.w("WifiSettings", "Wi-Fi adapter failed: ${it.wireValue}") }
        return result
    }

    // The configuration and update helpers run only inside guarded, which requires API 33.
    @TargetApi(Build.VERSION_CODES.R)
    private fun configuration(profile: WifiNetworkProfile, original: WifiConfiguration? = null): WifiConfiguration {
        // Do not turn an existing SAE configuration into a PSK/SAE transition profile.
        val desiredSecurity = desiredWifiSecurityView(profile, original?.securityView())
            ?: throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        if (desiredSecurity == "SAE" && manager?.isWpa3SaeSupported != true) {
            throw WifiAdapterFailure(WifiReason.UNSUPPORTED)
        }
        val config = if (original == null) {
            WifiConfiguration()
        } else {
            WifiConfiguration(original)
        }
        return config.apply {
            networkId = original?.networkId ?: -1
            SSID = "\"${profile.ssid}\""
            hiddenSSID = profile.hidden
            if (original?.securityView() != desiredSecurity) {
                when (desiredSecurity) {
                    "OPEN" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN)
                    "SAE" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE)
                    "WPA_PSK" -> setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK)
                }
            }
            preSharedKey = if (desiredSecurity == "OPEN") null else "\"${profile.password}\""
        }
    }

    private fun ownedMacSettingKey(entry: WifiConfiguredEntry) = "${Process.myUid()}:${entry.security}:${entry.ssid}"

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("DEPRECATION")
    private fun addSavedNetwork(
        wifi: WifiManager,
        profile: WifiNetworkProfile,
        before: List<WifiConfiguredView>,
        beforeMutation: () -> WifiReason?,
    ): Pair<Int, String> {
        val config = configuration(profile)
        beforeMutation()?.let { throw WifiAdapterFailure(it) }
        val id = wifi.addNetwork(config)
        if (id < 0) throw WifiAdapterFailure(WifiReason.UPDATE_FAILED)
        val saved = resolveView(profile)?.entry
        val security = config.securityView()
        if (saved == null || saved.networkId != id || saved.security != security || saved.hidden != profile.hidden) {
            throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
        }
        // addNetwork can also return an existing ID. Only a verified creation supplies provenance.
        val mac = config.macRandomizationSetting
        if (before.none { it.entry.networkId == id } && mac in WIFI_MAC_RANDOMIZATION_SETTINGS) {
            val recorded = runCatching { ownedMacSettings.edit().putInt(ownedMacSettingKey(saved), mac).commit() }
                .getOrDefault(false)
            if (!recorded) android.util.Log.w("WifiSettings", "Could not record the MAC policy for a new Wi-Fi profile")
        }
        return id to security
    }

    private fun readStoredMacSetting(view: WifiConfiguredView): Int {
        if (!hasManagedWifiDiagnosticsAccess(appContext)) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        }
        // PUI can mask the MAC policy in getConfiguredNetworks(). Read the stored value
        // before a privileged write, which can otherwise change that policy silently.
        try {
            val dump = ProcessBuilder("/system/bin/dumpsys", "-t", "2", "wifi").redirectErrorStream(true).start()
            try {
                return dump.inputStream.bufferedReader().use {
                    wifiStoredMacSetting(it.lineSequence(), view.entry.networkId, view.original.SSID)
                } ?: throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
            } finally {
                dump.destroy()
            }
        } catch (_: SecurityException) {
            throw WifiAdapterFailure(WifiReason.PERMISSION_DENIED)
        } catch (_: Exception) {
            throw WifiAdapterFailure(WifiReason.CONFIG_READ_FAILED)
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("DEPRECATION")
    private fun updateSavedNetwork(
        wifi: WifiManager,
        service: IToBService,
        profile: WifiNetworkProfile,
        existing: WifiConfiguredView,
        beforeMutation: () -> WifiReason?,
    ): Pair<Int, String> {
        val knownMac = runCatching { ownedMacSettings.getInt(ownedMacSettingKey(existing.entry), -1) }
            .getOrNull()?.takeIf { it in WIFI_MAC_RANDOMIZATION_SETTINGS }
        if (knownMac != null) {
            val update = configuration(profile, existing.original).apply { macRandomizationSetting = knownMac }
            beforeMutation()?.let { throw WifiAdapterFailure(it) }
            // Android checks whether this app can update the current saved ID and MAC policy.
            val updatedId = try { wifi.updateNetwork(update) } catch (_: SecurityException) { -1 }
            if (updatedId >= 0) {
                val security = verifySavedUpdate(profile, existing.entry, update, updatedId)
                android.util.Log.d("WifiSettings", "Updated a saved Wi-Fi profile through WifiManager")
                return updatedId to security
            }
        }

        val external = if (knownMac == null) existing else resolveView(profile)
            ?: throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
        val storedMacSetting = readStoredMacSetting(external)
        val current = resolveView(profile)
        if (current == null || current.entry != external.entry) throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
        val original = current.entry
        // A named parcel for an existing SSID only toggles auto-join. Omitting the SSID
        // sends the saved ID through TobService's add-or-update path without selecting it.
        val update = configuration(profile, current.original).apply {
            SSID = null
            macRandomizationSetting = storedMacSetting
        }
        beforeMutation()?.let { throw WifiAdapterFailure(it) }
        val arguments = Bundle().apply {
            putInt("network_id", original.networkId)
            putBoolean("allow_auto_join", original.autoJoinEnabled)
            putParcelable("wifi_configuration", update)
        }
        // SDK 4.3.24 allowWifiAutoJoin defaults a missing result code to zero (success).
        val result = service.pbsCommonMessageLocked("allow_wifi_auto_join", arguments)
        if (result?.getInt("key_result_code", -1) != 0) {
            throw WifiAdapterFailure(WifiReason.UPDATE_FAILED)
        }
        val security = verifySavedUpdate(profile, original, update, original.networkId)
        android.util.Log.d("WifiSettings", "Updated a saved Wi-Fi profile through TobService")
        return original.networkId to security
    }

    private fun verifySavedUpdate(
        profile: WifiNetworkProfile,
        original: WifiConfiguredEntry,
        update: WifiConfiguration,
        updatedId: Int,
    ): String {
        val security = update.securityView()
        val saved = readCurrent()
        val observed = resolveWifiProfileViewIndex(profile, saved)?.let(saved::get)
        if (updatedId != original.networkId || observed?.networkId != updatedId ||
            !wifiSavedUpdateReadback(saved, original, security, profile.hidden)
        ) {
            throw WifiAdapterFailure(WifiReason.VERIFY_FAILED)
        }
        return security
    }

    private fun logOperationFailure(operation: String, reason: WifiReason) {
        android.util.Log.w("WifiSettings", "Wi-Fi operation $operation failed: ${reason.wireValue}")
    }
}

internal fun normalizeConfiguredWifiSsid(ssid: String?): String? =
    ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }

private fun WifiConfiguration.securityView(): String = when {
    allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> "SAE"
    allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> "WPA_PSK"
    allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) && wepKeys.all { it == null } -> "OPEN"
    else -> "OTHER:$allowedKeyManagement"
}
