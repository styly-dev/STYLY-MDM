package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject

internal const val WIFI_SETTINGS_CAPABILITY = "wifi_settings_v4"
internal const val WIFI_MANUAL_TIMEOUT_MS = 60_000L
internal const val WIFI_MAX_NETWORKS = 32
internal const val WIFI_MAX_OPERATION_RECEIPTS = 500

internal fun appendWifiOperationReceipt(existing: List<String>, operationId: String): List<String> =
    (existing.filterNot { it == operationId } + operationId)
        .distinct()
        .takeLast(WIFI_MAX_OPERATION_RECEIPTS)

internal enum class WifiReason(val wireValue: String) {
    NONE("NONE"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    UNSUPPORTED("UNSUPPORTED"),
    CONFIG_READ_FAILED("CONFIG_READ_FAILED"),
    UPDATE_FAILED("UPDATE_FAILED"),
    VERIFY_FAILED("VERIFY_FAILED"),
    TIMEOUT_ROLLED_BACK("TIMEOUT_ROLLED_BACK"),
    RESTORE_FAILED("RESTORE_FAILED"),
    BUSY("BUSY"),
    UNREACHABLE("UNREACHABLE"),
    UNKNOWN("UNKNOWN"),
}

internal data class WifiNetworkProfile(
    val ssid: String,
    val security: String,
    val password: String,
    val hidden: Boolean,
) {
    val identity: String get() = "$ssid\u0000$security"
}

internal data class WifiSavedProfile(
    val revision: String,
    val networks: List<WifiNetworkProfile>,
)

internal data class WifiAutoJoinState(
    val networkId: Int,
    val ssid: String,
    val securities: List<String>,
    val allowed: Boolean,
)

internal data class WifiRecoverySnapshot(
    val networks: List<WifiAutoJoinState>,
    val knownCredentials: List<WifiNetworkProfile>,
)

internal enum class WifiManualPhase(val wireValue: String) {
    PREPARED("prepared"),
    APPLYING("applying"),
    ROLLING_BACK("rolling_back"),
}

internal data class WifiManualOperation(
    val operationId: String,
    val revision: String,
    val candidate: List<WifiNetworkProfile>,
    val recoverySnapshot: WifiRecoverySnapshot,
    val phase: WifiManualPhase,
    val deadlineElapsedMillis: Long? = null,
    val failureReason: WifiReason? = null,
    val settingsChanged: Boolean = false,
    val policyEpoch: Long = 0L,
)

internal data class WifiManualResult(
    val operationId: String,
    val revision: String,
    val status: String,
    val reason: WifiReason,
    val rolledBack: Boolean,
)

internal data class WifiLocalState(
    val lastApplied: WifiSavedProfile? = null,
    val manualOperation: WifiManualOperation? = null,
    val latestResult: WifiManualResult? = null,
    val bootGeneration: String? = null,
    val processedOperationIds: List<String> = emptyList(),
    val managedWifi: Boolean = true,
    val policyEpoch: Long = 0L,
    val policyRevision: String? = null,
)

internal enum class WifiApplyMode(val wireValue: String) {
    MANAGED("managed"),
    UNMANAGED("unmanaged"),
}

internal data class WifiApplyCommand(
    val operationId: String,
    val revision: String,
    val mode: WifiApplyMode,
    val networks: List<WifiNetworkProfile>,
)

internal object WifiSettingsProtocol {
    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val RAW_PSK = Regex("^[0-9a-fA-F]{64}$")

    fun parseApplyCommand(payload: JSONObject): WifiApplyCommand {
        if (payload.has("recovery_scope")) {
            throw IllegalArgumentException("unsupported Wi-Fi apply command")
        }
        val operationId = requiredString(payload, "operation_id")
        val revision = requiredString(payload, "revision")
        requireUuid(operationId, "operation_id")
        requireUuid(revision, "revision")
        val mode = WifiApplyMode.entries.firstOrNull { it.wireValue == requiredString(payload, "mode") }
            ?: throw IllegalArgumentException("unsupported Wi-Fi apply mode")
        val networkArray = payload.optJSONArray("networks")
            ?: throw IllegalArgumentException("networks must be an array")
        if (mode == WifiApplyMode.MANAGED && networkArray.length() !in 1..WIFI_MAX_NETWORKS) {
            throw IllegalArgumentException("managed networks must contain 1..$WIFI_MAX_NETWORKS items")
        }
        if (mode == WifiApplyMode.UNMANAGED && networkArray.length() != 0) {
            throw IllegalArgumentException("unmanaged mode must not include networks")
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
        return WifiApplyCommand(operationId, revision, mode, networks)
    }

    fun validateNetwork(ssid: String, security: String, password: String) {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        require(ssid.isNotEmpty() && ssidBytes.size <= 32) { "invalid SSID" }
        require(ssid.none { it.isISOControl() || it == '"' || it == '\\' }) { "unsupported SSID characters" }
        when (security) {
            "OPEN" -> require(password.isEmpty()) { "OPEN networks must not have a password" }
            "WPA_PSK" -> {
                val printableAscii = password.all { it.code in 0x20..0x7e }
                require((printableAscii && password.length in 8..63) || RAW_PSK.matches(password)) {
                    "invalid WPA PSK password"
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

    private fun requiredBoolean(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key) || json.get(key) !is Boolean) {
            throw IllegalArgumentException("$key must be a boolean")
        }
        return json.getBoolean(key)
    }
}

internal object WifiSettingsCodec {
    private const val VERSION = 1

    fun encode(state: WifiLocalState): String = JSONObject().apply {
        put("version", VERSION)
        put("last_applied", state.lastApplied?.let(::encodeSavedProfile) ?: JSONObject.NULL)
        put("manual_operation", state.manualOperation?.let(::encodeManualOperation) ?: JSONObject.NULL)
        put("latest_result", state.latestResult?.let(::encodeManualResult) ?: JSONObject.NULL)
        put("boot_generation", state.bootGeneration ?: JSONObject.NULL)
        put("processed_operation_ids", JSONArray(state.processedOperationIds))
        put("managed_wifi", state.managedWifi)
        put("policy_epoch", state.policyEpoch)
        put("policy_revision", state.policyRevision ?: JSONObject.NULL)
    }.toString()

    fun decode(text: String): WifiLocalState {
        val root = JSONObject(text)
        if (root.optInt("version", -1) != VERSION) {
            throw IllegalArgumentException("unsupported Wi-Fi state version")
        }
        return WifiLocalState(
            lastApplied = root.optJSONObject("last_applied")?.let(::decodeSavedProfile),
            manualOperation = root.optJSONObject("manual_operation")?.let(::decodeManualOperation),
            latestResult = root.optJSONObject("latest_result")?.let(::decodeManualResult),
            bootGeneration = optionalString(root, "boot_generation"),
            processedOperationIds = decodeOperationReceipts(root.optJSONArray("processed_operation_ids")),
            managedWifi = requiredBoolean(root, "managed_wifi", default = true),
            policyEpoch = root.optLong("policy_epoch", 0L).coerceAtLeast(0L),
            policyRevision = optionalString(root, "policy_revision"),
        )
    }

    private fun encodeSavedProfile(profile: WifiSavedProfile) = JSONObject().apply {
        put("revision", profile.revision)
        put("networks", encodeNetworks(profile.networks))
    }

    private fun decodeSavedProfile(json: JSONObject) = WifiSavedProfile(
        revision = json.getString("revision"),
        networks = decodeNetworks(json.getJSONArray("networks")),
    )

    private fun encodeManualOperation(operation: WifiManualOperation) = JSONObject().apply {
        put("operation_id", operation.operationId)
        put("revision", operation.revision)
        put("candidate", encodeNetworks(operation.candidate))
        put("recovery_snapshot", encodeRecoverySnapshot(operation.recoverySnapshot))
        put("phase", operation.phase.wireValue)
        put("deadline_elapsed_ms", operation.deadlineElapsedMillis ?: JSONObject.NULL)
        put("failure_reason", operation.failureReason?.wireValue ?: JSONObject.NULL)
        put("settings_changed", operation.settingsChanged)
        put("policy_epoch", operation.policyEpoch)
    }

    private fun decodeManualOperation(json: JSONObject) = WifiManualOperation(
        operationId = json.getString("operation_id"),
        revision = json.getString("revision"),
        candidate = decodeNetworks(json.getJSONArray("candidate")),
        recoverySnapshot = decodeRecoverySnapshot(json.getJSONObject("recovery_snapshot")),
        phase = WifiManualPhase.entries.firstOrNull { it.wireValue == json.getString("phase") }
            ?: throw IllegalArgumentException("unknown Wi-Fi manual phase"),
        deadlineElapsedMillis = optionalLong(json, "deadline_elapsed_ms"),
        failureReason = optionalString(json, "failure_reason")?.let(::reasonFromWire),
        settingsChanged = requiredBoolean(json, "settings_changed", default = false),
        policyEpoch = json.optLong("policy_epoch", 0L).coerceAtLeast(0L),
    )

    private fun encodeRecoverySnapshot(snapshot: WifiRecoverySnapshot) = JSONObject().apply {
        put("networks", JSONArray().apply {
            snapshot.networks.forEach { network ->
                put(JSONObject().apply {
                    put("network_id", network.networkId)
                    put("ssid", network.ssid)
                    put("securities", JSONArray(network.securities))
                    put("allowed", network.allowed)
                })
            }
        })
        put("known_credentials", encodeNetworks(snapshot.knownCredentials))
    }

    private fun decodeRecoverySnapshot(json: JSONObject): WifiRecoverySnapshot = WifiRecoverySnapshot(
        networks = buildList {
            val array = json.getJSONArray("networks")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(WifiAutoJoinState(
                    networkId = item.getInt("network_id"),
                    ssid = item.getString("ssid"),
                    securities = decodeStringValues(item.getJSONArray("securities")),
                    allowed = item.getBoolean("allowed"),
                ))
            }
        },
        knownCredentials = decodeNetworks(json.getJSONArray("known_credentials")),
    )

    private fun encodeManualResult(result: WifiManualResult) = JSONObject().apply {
        put("operation_id", result.operationId)
        put("revision", result.revision)
        put("status", result.status)
        put("reason", result.reason.wireValue)
        put("rolled_back", result.rolledBack)
    }

    private fun decodeManualResult(json: JSONObject) = WifiManualResult(
        operationId = json.getString("operation_id"),
        revision = json.getString("revision"),
        status = json.getString("status"),
        reason = reasonFromWire(json.getString("reason")),
        rolledBack = json.getBoolean("rolled_back"),
    )

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

    private fun decodeStringValues(array: JSONArray): List<String> = buildList {
        for (index in 0 until array.length()) {
            val item = array.get(index)
            if (item !is String) throw IllegalArgumentException("Wi-Fi identity must be a string")
            add(item)
        }
    }

    private fun decodeOperationReceipts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        if (array.length() > 100_000) throw IllegalArgumentException("too many Wi-Fi operation receipts")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.get(index)
                if (item !is String || !WifiSettingsProtocol.isValidUuid(item)) {
                    throw IllegalArgumentException("invalid Wi-Fi operation receipt")
                }
                add(item)
            }
        }.distinct().takeLast(WIFI_MAX_OPERATION_RECEIPTS)
    }

    private fun reasonFromWire(value: String): WifiReason =
        WifiReason.entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unknown Wi-Fi reason")

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.get(key)
        if (value !is String) throw IllegalArgumentException("$key must be a string")
        return value
    }

    private fun optionalLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.get(key)
        if (value !is Number) throw IllegalArgumentException("$key must be a number")
        return value.toLong()
    }

    private fun requiredBoolean(json: JSONObject, key: String, default: Boolean): Boolean {
        if (!json.has(key) || json.isNull(key)) return default
        val value = json.get(key)
        if (value !is Boolean) throw IllegalArgumentException("$key must be a boolean")
        return json.getBoolean(key)
    }
}
