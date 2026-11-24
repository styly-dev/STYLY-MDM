package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject

internal const val WIFI_MANUAL_TIMEOUT_MS = 60_000L
internal const val WIFI_MAX_NETWORKS = 4

internal enum class WifiReason(val wireValue: String) {
    NONE("NONE"),
    SAVED("SAVED"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    DUMP_PERMISSION_MISSING("DUMP_PERMISSION_MISSING"),
    UNSUPPORTED("UNSUPPORTED"),
    UNSUPPORTED_HIDDEN_NETWORK("UNSUPPORTED_HIDDEN_NETWORK"),
    UNSUPPORTED_NETWORK_LIMIT("UNSUPPORTED_PRIORITY_NETWORK_LIMIT"),
    CONFIG_READ_FAILED("CONFIG_READ_FAILED"),
    UPDATE_FAILED("UPDATE_FAILED"),
    VERIFY_FAILED("VERIFY_FAILED"),
    STORAGE_ERROR("STORAGE_ERROR"),
    UNREACHABLE("UNREACHABLE"),
    UNKNOWN("UNKNOWN"),
}

internal val WifiReason.isUnsupported: Boolean
    get() = this == WifiReason.UNSUPPORTED ||
        this == WifiReason.UNSUPPORTED_HIDDEN_NETWORK ||
        this == WifiReason.UNSUPPORTED_NETWORK_LIMIT

internal data class WifiNetworkProfile(
    val ssid: String,
    val security: String,
    val password: String,
    val hidden: Boolean,
) {
    val identity: String get() = "$ssid\u0000$security"
    val requiresSaeOnly: Boolean get() = security == "WPA2_WPA3" && password.length in 1..7
}

internal data class WifiSavedProfile(
    val revision: String,
    val networks: List<WifiNetworkProfile>,
    val autoReturn: Boolean = false,
    /** Original first candidate, even if its settings could not be saved. */
    val primaryIdentity: String? = networks.firstOrNull()?.identity,
    val prioritizeNetworks: Boolean = true,
    /** Android network IDs read back for the credentials in this profile. */
    val networkIds: Map<String, Int> = emptyMap(),
) {
    fun hasSameSettings(other: WifiSavedProfile): Boolean =
        networks == other.networks && autoReturn == other.autoReturn &&
            primaryIdentity == other.primaryIdentity && prioritizeNetworks == other.prioritizeNetworks

    fun networkIdsByProfile(): Map<WifiNetworkProfile, Int> = networks.mapNotNull { network ->
        networkIds[network.identity]?.let { network to it }
    }.toMap()

}

internal data class WifiManualResult(
    val revision: String,
    val status: String,
    val reason: WifiReason,
)

internal data class WifiLocalState(
    val lastApplied: WifiSavedProfile? = null,
    val managedWifi: Boolean = true,
)

internal class WifiPolicyFailure(val reason: WifiReason) : Exception()

internal object WifiSettingsProtocol {
    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun parseManagedPolicy(revision: String, wifi: JSONObject): WifiSavedProfile {
        requireUuid(revision, "revision")
        val networkArray = wifi.optJSONArray("networks")
            ?: throw IllegalArgumentException("wifi.networks must be an array")
        val autoReturn = requiredBoolean(wifi, "auto_return")
        val prioritizeNetworks = !wifi.has("prioritize_networks") || requiredBoolean(wifi, "prioritize_networks")
        require(prioritizeNetworks || !autoReturn) { "auto_return requires prioritize_networks" }
        if (networkArray.length() > WIFI_MAX_NETWORKS) throw WifiPolicyFailure(WifiReason.UNSUPPORTED_NETWORK_LIMIT)
        if (autoReturn && networkArrayHasHidden(networkArray)) {
            throw WifiPolicyFailure(WifiReason.UNSUPPORTED_HIDDEN_NETWORK)
        }
        val networks = parseNetworks(networkArray)
        return WifiSavedProfile(revision, networks, autoReturn, prioritizeNetworks = prioritizeNetworks)
    }

    private fun networkArrayHasHidden(array: JSONArray): Boolean = (0 until array.length()).any { index ->
        array.optJSONObject(index)?.optBoolean("hidden", false) == true
    }

    private fun parseNetworks(networkArray: JSONArray): List<WifiNetworkProfile> {
        if (networkArray.length() !in 1..WIFI_MAX_NETWORKS) {
            throw IllegalArgumentException("managed networks must contain 1..$WIFI_MAX_NETWORKS items")
        }
        val networks = ArrayList<WifiNetworkProfile>(networkArray.length())
        val identities = HashSet<String>()
        for (index in 0 until networkArray.length()) {
            val item = networkArray.optJSONObject(index)
                ?: throw IllegalArgumentException("network entry must be an object")
            val ssid = requiredString(item, "ssid")
            val security = requiredString(item, "security")
            val password = requiredString(item, "password")
            val hidden = requiredBoolean(item, "hidden")
            validateNetwork(ssid, security, password)
            val network = WifiNetworkProfile(ssid, security, password, hidden)
            if (!identities.add(network.identity)) {
                throw IllegalArgumentException("duplicate SSID and security pair")
            }
            networks.add(network)
        }
        return networks
    }

    fun validateNetwork(ssid: String, security: String, password: String) {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        require(ssid.isNotEmpty() && ssidBytes.size <= 32) { "invalid SSID" }
        require(ssid.none { it.isISOControl() || it == '"' || it == '\\' }) { "unsupported SSID characters" }
        when (security) {
            "OPEN" -> require(password.isEmpty()) { "OPEN networks must not have a password" }
            "WPA2_WPA3" -> {
                val printableAscii = password.all { it.code in 0x20..0x7e }
                require(printableAscii && password.length in 1..63) {
                    "WPA2_WPA3 password must contain 1..63 printable ASCII characters"
                }
            }
            else -> throw IllegalArgumentException("unsupported Wi-Fi security mode")
        }
    }

    fun isValidUuid(value: String): Boolean = UUID_V4.matches(value)

    private fun requireUuid(value: String, field: String) {
        if (!UUID_V4.matches(value)) throw IllegalArgumentException("$field must be a lowercase UUIDv4")
    }

    private fun requiredString(json: JSONObject, key: String): String {
        if (!json.has(key) || json.isNull(key) || json.get(key) !is String) {
            throw IllegalArgumentException("$key must be a string")
        }
        return json.getString(key)
    }

}

private fun requiredBoolean(json: JSONObject, key: String): Boolean =
    json.opt(key) as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")

internal object WifiSettingsCodec {
    private const val VERSION = 5

    fun encode(state: WifiLocalState): String = JSONObject().apply {
        put("version", VERSION)
        put("last_applied", state.lastApplied?.let(::encodeSavedProfile) ?: JSONObject.NULL)
        put("managed_wifi", state.managedWifi)
    }.toString()

    fun decode(text: String): WifiLocalState {
        val root = JSONObject(text)
        val version = root.optInt("version", -1)
        require(version == VERSION) { "unsupported Wi-Fi state version" }
        return WifiLocalState(
            lastApplied = root.optJSONObject("last_applied")?.let(::decodeSavedProfile),
            managedWifi = requiredBoolean(root, "managed_wifi"),
        )
    }

    private fun encodeSavedProfile(profile: WifiSavedProfile) = JSONObject().apply {
        put("revision", profile.revision)
        put("networks", encodeNetworks(profile.networks))
        put("auto_return", profile.autoReturn)
        if (!profile.prioritizeNetworks) put("prioritize_networks", false)
        if (profile.networkIds.isNotEmpty()) put("network_ids", JSONObject(profile.networkIds))
        if (profile.primaryIdentity != profile.networks.firstOrNull()?.identity) {
            put("primary_identity", profile.primaryIdentity ?: JSONObject.NULL)
        }
    }

    private fun decodeSavedProfile(json: JSONObject): WifiSavedProfile {
        val networks = decodeNetworks(json.getJSONArray("networks"))
        val ids = json.optJSONObject("network_ids")
        return WifiSavedProfile(
            revision = json.getString("revision"),
            networks = networks,
            autoReturn = requiredBoolean(json, "auto_return"),
            primaryIdentity = if (json.has("primary_identity")) optionalString(json, "primary_identity")
                else networks.firstOrNull()?.identity,
            prioritizeNetworks = !json.has("prioritize_networks") || requiredBoolean(json, "prioritize_networks"),
            networkIds = networks.mapNotNull { network ->
                val id = ids?.opt(network.identity) as? Int
                id?.takeIf { it >= 0 }?.let { network.identity to it }
            }.toMap(),
        )
    }

    private fun encodeNetworks(networks: List<WifiNetworkProfile>) = JSONArray().apply {
        networks.forEach { network ->
            put(JSONObject().apply {
                put("ssid", network.ssid)
                put("security", network.security)
                put("password", network.password)
                put("hidden", network.hidden)
            })
        }
    }

    private fun decodeNetworks(array: JSONArray): List<WifiNetworkProfile> = buildList {
        if (array.length() > WIFI_MAX_NETWORKS) throw IllegalArgumentException("too many Wi-Fi networks")
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val ssid = item.getString("ssid")
            val security = item.getString("security")
            val password = item.getString("password")
            WifiSettingsProtocol.validateNetwork(ssid, security, password)
            add(WifiNetworkProfile(ssid, security, password, item.getBoolean("hidden")))
        }
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.get(key)
        if (value !is String) throw IllegalArgumentException("$key must be a string")
        return value
    }

}
