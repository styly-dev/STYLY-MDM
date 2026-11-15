package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject

internal const val WIFI_MANUAL_TIMEOUT_MS = 60_000L
internal const val WIFI_MAX_NETWORKS = 4

internal enum class WifiReason(val wireValue: String) {
    NONE("NONE"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    UNSUPPORTED("UNSUPPORTED"),
    UNSUPPORTED_HIDDEN_NETWORK("UNSUPPORTED_HIDDEN_NETWORK"),
    UNSUPPORTED_NETWORK_LIMIT("UNSUPPORTED_PRIORITY_NETWORK_LIMIT"),
    CONFIG_READ_FAILED("CONFIG_READ_FAILED"),
    UPDATE_FAILED("UPDATE_FAILED"),
    VERIFY_FAILED("VERIFY_FAILED"),
    TIMEOUT_ROLLED_BACK("TIMEOUT_ROLLED_BACK"),
    RESTORE_FAILED("RESTORE_FAILED"),
    RESTORE_UNCONFIRMED("RESTORE_UNCONFIRMED"),
    BUSY("BUSY"),
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
) {
    fun hasSameSettings(other: WifiSavedProfile): Boolean =
        networks == other.networks && autoReturn == other.autoReturn
}

internal data class WifiRollbackTarget(val ssid: String, val security: String)

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
    RESTORING_CONNECTION("restoring_connection"),
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
    val autoReturn: Boolean = false,
    val selectionOnly: Boolean = false,
    val selectionIndexes: List<Int>? = null,
    val retryDisconnected: Boolean = false,
    val priorConnectionHadUsableIp: Boolean = false,
    val restoreTarget: WifiRollbackTarget? = null,
    val settingsRestored: Boolean = false,
    val restoreDeadlineElapsedMillis: Long? = null,
    val restoreBootCount: Int? = null,
)

internal data class WifiManualResult(
    val operationId: String,
    val revision: String,
    val status: String,
    val reason: WifiReason,
    val rolledBack: Boolean,
)

/** One package handoff. Automatic selection has its own telemetry and cannot replace this receipt. */
internal data class WifiPolicyReceipt(
    val operationId: String,
    val revision: String,
    val result: WifiManualResult? = null,
)

/**
 * A received release of Wi-Fi management that waits for the active operation to settle.
 * It is journaled so a process restart cannot resume the released policy.
 */
internal data class WifiPendingRelease(val revision: String?)

internal data class WifiLocalState(
    val lastApplied: WifiSavedProfile? = null,
    val manualOperation: WifiManualOperation? = null,
    val latestResult: WifiManualResult? = null,
    val policyReceipt: WifiPolicyReceipt? = null,
    val managedWifi: Boolean = true,
    val policyEpoch: Long = 0L,
    val policyRevision: String? = null,
    val pendingRelease: WifiPendingRelease? = null,
)

internal class WifiPolicyFailure(val reason: WifiReason) : Exception()

internal object WifiSettingsProtocol {
    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun parseManagedPolicy(revision: String, wifi: JSONObject): WifiSavedProfile {
        requireUuid(revision, "revision")
        val networkArray = wifi.optJSONArray("networks")
            ?: throw IllegalArgumentException("wifi.networks must be an array")
        val autoReturn = requiredBoolean(wifi, "auto_return")
        if (networkArray.length() > WIFI_MAX_NETWORKS) throw WifiPolicyFailure(WifiReason.UNSUPPORTED_NETWORK_LIMIT)
        if (autoReturn && networkArrayHasHidden(networkArray)) {
            throw WifiPolicyFailure(WifiReason.UNSUPPORTED_HIDDEN_NETWORK)
        }
        val networks = parseNetworks(networkArray)
        return WifiSavedProfile(revision, networks, autoReturn)
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
    private const val VERSION = 2

    fun encode(state: WifiLocalState): String = JSONObject().apply {
        put("version", VERSION)
        put("last_applied", state.lastApplied?.let(::encodeSavedProfile) ?: JSONObject.NULL)
        put("manual_operation", state.manualOperation?.let(::encodeManualOperation) ?: JSONObject.NULL)
        put("latest_result", state.latestResult?.let(::encodeManualResult) ?: JSONObject.NULL)
        put("policy_receipt", state.policyReceipt?.let {
            JSONObject()
                .put("operation_id", it.operationId)
                .put("revision", it.revision)
                .put("result", it.result?.let(::encodeOutcome) ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        put("managed_wifi", state.managedWifi)
        put("policy_epoch", state.policyEpoch)
        put("policy_revision", state.policyRevision ?: JSONObject.NULL)
        put("pending_release", state.pendingRelease?.let {
            JSONObject().put("revision", it.revision ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
    }.toString()

    fun decode(text: String): WifiLocalState {
        val root = JSONObject(text)
        val version = root.optInt("version", -1)
        if (version != VERSION) {
            throw IllegalArgumentException("unsupported Wi-Fi state version")
        }
        return WifiLocalState(
            lastApplied = root.optJSONObject("last_applied")?.let(::decodeSavedProfile),
            manualOperation = root.optJSONObject("manual_operation")?.let(::decodeManualOperation),
            latestResult = root.optJSONObject("latest_result")?.let { decodeManualResult(it) },
            policyReceipt = decodePolicyReceipt(root),
            managedWifi = requiredBoolean(root, "managed_wifi"),
            policyEpoch = root.optLong("policy_epoch", 0L).coerceAtLeast(0L),
            policyRevision = optionalString(root, "policy_revision"),
            pendingRelease = root.optJSONObject("pending_release")?.let { release ->
                val revision = optionalString(release, "revision")
                require(revision == null || WifiSettingsProtocol.isValidUuid(revision)) { "invalid pending release revision" }
                WifiPendingRelease(revision)
            },
        )
    }

    private fun decodePolicyReceipt(root: JSONObject): WifiPolicyReceipt? {
        if (root.isNull("policy_receipt")) return null
        val receipt = root.getJSONObject("policy_receipt")
        val operationId = receipt.getString("operation_id")
        val revision = receipt.getString("revision")
        require(WifiSettingsProtocol.isValidUuid(operationId) && WifiSettingsProtocol.isValidUuid(revision)) {
            "invalid Wi-Fi package receipt identity"
        }
        val result = receipt.optJSONObject("result")?.let { decodeManualResult(it, operationId, revision) }
        return WifiPolicyReceipt(operationId, revision, result)
    }

    private fun encodeSavedProfile(profile: WifiSavedProfile) = JSONObject().apply {
        put("revision", profile.revision)
        put("networks", encodeNetworks(profile.networks))
        put("auto_return", profile.autoReturn)
    }

    private fun decodeSavedProfile(json: JSONObject) = WifiSavedProfile(
        revision = json.getString("revision"),
        networks = decodeNetworks(json.getJSONArray("networks")),
        autoReturn = requiredBoolean(json, "auto_return"),
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
        put("auto_return", operation.autoReturn)
        put("selection_only", operation.selectionOnly)
        operation.selectionIndexes?.let { put("selection_indexes", JSONArray(it)) }
        put("retry_disconnected", operation.retryDisconnected)
        put("prior_connection_had_usable_ip", operation.priorConnectionHadUsableIp)
        operation.restoreTarget?.let {
            put("restore_target", JSONObject().put("ssid", it.ssid).put("security", it.security))
        } ?: put("restore_target", JSONObject.NULL)
        put("settings_restored", operation.settingsRestored)
        put("restore_deadline_elapsed_ms", operation.restoreDeadlineElapsedMillis ?: JSONObject.NULL)
        put("restore_boot_count", operation.restoreBootCount ?: JSONObject.NULL)
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
        settingsChanged = requiredBoolean(json, "settings_changed"),
        policyEpoch = json.optLong("policy_epoch", 0L).coerceAtLeast(0L),
        autoReturn = requiredBoolean(json, "auto_return"),
        selectionOnly = requiredBoolean(json, "selection_only"),
        selectionIndexes = json.optJSONArray("selection_indexes")?.let { array ->
            val candidateCount = json.getJSONArray("candidate").length()
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.get(index)
                    require(value is Int && value in 0 until candidateCount) {
                        "invalid Wi-Fi selection index"
                    }
                    add(value)
                }
            }.also { indexes ->
                require(indexes.isNotEmpty() && indexes.distinct().size == indexes.size) {
                    "invalid Wi-Fi selection order"
                }
            }
        },
        retryDisconnected = requiredBoolean(json, "retry_disconnected"),
        priorConnectionHadUsableIp = requiredBoolean(json, "prior_connection_had_usable_ip"),
        restoreTarget = json.optJSONObject("restore_target")?.let {
            WifiRollbackTarget(it.getString("ssid"), it.getString("security"))
        },
        settingsRestored = requiredBoolean(json, "settings_restored"),
        restoreDeadlineElapsedMillis = optionalLong(json, "restore_deadline_elapsed_ms"),
        restoreBootCount = optionalLong(json, "restore_boot_count")?.toInt()?.takeIf { it >= 0 },
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

    private fun encodeManualResult(result: WifiManualResult) = encodeOutcome(result)
        .put("operation_id", result.operationId)
        .put("revision", result.revision)

    private fun encodeOutcome(result: WifiManualResult) = JSONObject().apply {
        put("status", result.status)
        put("reason", result.reason.wireValue)
        put("rolled_back", result.rolledBack)
    }

    private fun decodeManualResult(
        json: JSONObject,
        operationId: String = json.getString("operation_id"),
        revision: String = json.getString("revision"),
    ) = WifiManualResult(
        operationId = operationId,
        revision = revision,
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

}
