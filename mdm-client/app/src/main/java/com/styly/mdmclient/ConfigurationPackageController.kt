package com.styly.mdmclient

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal const val CONFIGURATION_PACKAGES_CAPABILITY = "configuration_packages_v1"
internal const val CONFIGURATION_SUMMARY_CAPABILITY = "configuration_summary_v1"

internal data class ConfigurationVersion(val packageId: String, val revision: String) {
    fun toJson(): JSONObject = JSONObject().put("package_id", packageId).put("revision", revision)

    companion object {
        fun parse(json: JSONObject): ConfigurationVersion? {
            val packageId = json.opt("package_id") as? String ?: return null
            val revision = json.opt("revision") as? String ?: return null
            if (!WifiSettingsProtocol.isValidUuid(packageId) || !WifiSettingsProtocol.isValidUuid(revision)) return null
            return ConfigurationVersion(packageId, revision)
        }
    }
}

internal data class ConfigurationAttempt(
    val version: ConfigurationVersion,
    val bootCount: Int,
    val status: String = "applying",
    val results: JSONObject = JSONObject(),
    val retryIds: Set<String> = emptySet(),
    val wifiOperationId: String? = null,
) {
    val needsRetry: Boolean
        get() = status == "interrupted" || (status == "complete" && results.keys().asSequence().any {
            results.optJSONObject(it)?.optString("status") != "applied"
        })

    fun report(): JSONObject = version.toJson().put("status", status).put("results", results)

    fun saved(): JSONObject = report()
        .put("boot_count", bootCount)
        .put("retry_ids", JSONArray(retryIds))
        .put("wifi_operation_id", wifiOperationId)

    companion object {
        fun restore(json: JSONObject): ConfigurationAttempt {
            val status = json.getString("status")
            val retryIds = json.getJSONArray("retry_ids")
            return ConfigurationAttempt(
                version = requireNotNull(ConfigurationVersion.parse(json)),
                bootCount = json.getInt("boot_count"),
                status = if (status == "applying") "interrupted" else status,
                results = json.getJSONObject("results"),
                retryIds = (0 until retryIds.length()).map(retryIds::getString).toSet(),
                wifiOperationId = (json.opt("wifi_operation_id") as? String)
                    ?.takeIf(WifiSettingsProtocol::isValidUuid),
            )
        }
    }
}

/** The server publishes only the latest revision of a package. Keep one attempt per package. */
internal class ConfigurationJournal(private val attempts: List<ConfigurationAttempt> = emptyList()) {
    val latest: ConfigurationAttempt? get() = attempts.lastOrNull()

    fun find(version: ConfigurationVersion): ConfigurationAttempt? = attempts.find { it.version == version }

    fun withAttempt(next: ConfigurationAttempt): ConfigurationJournal =
        ConfigurationJournal(attempts.filterNot { it.version.packageId == next.version.packageId } + next)

    fun shouldApply(version: ConfigurationVersion, bootCount: Int, retryId: String?): Boolean {
        val previous = find(version)
        if (retryId != null) return previous != null && previous.needsRetry && retryId !in previous.retryIds
        if (previous == null) return true
        // A different attempt may have changed device settings since this revision succeeded.
        return !previous.needsRetry && (previous.bootCount != bootCount || latest?.version != version)
    }

    fun encode(): String = JSONArray(attempts.map(ConfigurationAttempt::saved)).toString()

    companion object {
        fun restore(encoded: String): ConfigurationJournal {
            val attempts = JSONArray(encoded)
            return ConfigurationJournal((0 until attempts.length()).map {
                ConfigurationAttempt.restore(attempts.getJSONObject(it))
            })
        }
    }
}

internal data class ConfigurationPackageDependencies(
    val worker: Executor,
    val bootCount: Int,
    val readJournal: () -> String?,
    val writeJournal: (String) -> Boolean,
    val readLastPolicy: () -> String?,
    val writeLastPolicy: (String?) -> Boolean,
    val scalarWriter: () -> ((JSONObject, Boolean) -> JSONObject)?,
    val logJournalError: (String) -> Unit = {},
)

internal enum class ConfigurationApplyStatus { WAITING, APPLYING, APPLIED, INCOMPLETE }

internal data class ConfigurationScreenState(
    val groupKnown: Boolean,
    val groupName: String?,
    val version: ConfigurationVersion?,
    val deployedAtMillis: Long?,
    val applyStatus: ConfigurationApplyStatus?,
    val canReapply: Boolean,
)

/** The process owns one apply worker, independent of WebSocket and Service lifetimes. */
internal class ConfigurationPackageController(
    private val dependencies: ConfigurationPackageDependencies,
    private val wifi: WifiSettingsController,
) {
    constructor(context: Context, wifi: WifiSettingsController) : this(productionDependencies(context), wifi)

    private class Policy(
        val version: ConfigurationVersion?,
        val values: JSONObject,
        val groupKnown: Boolean,
        val groupName: String?,
        val deployedAtMillis: Long?,
        val supportsApply: Boolean,
    ) {
        fun encode(previousAttempt: AttemptedSettings?): String = checkNotNull(version).toJson()
            .put("settings", values)
            .put("group_name", if (groupKnown) groupName ?: JSONObject.NULL else null)
            .put("deployed_at_ms", deployedAtMillis)
            .put("previous_attempt", previousAttempt?.takeUnless { it.version == version }?.encode())
            .toString()

        fun attemptedSettings(): AttemptedSettings {
            val digest = MessageDigest.getInstance("SHA-256")
            val encoder = Base64.getEncoder()
            val fingerprints = JSONObject()
            for (key in values.keys()) {
                val canonical = canonicalSettingValue(values.get(key)).toByteArray(Charsets.UTF_8)
                fingerprints.put(key, encoder.encodeToString(digest.digest(canonical)))
            }
            return AttemptedSettings(checkNotNull(version), fingerprints)
        }

        companion object {
            fun parse(payload: JSONObject, supportsApply: Boolean): Policy? {
                val values = payload.optJSONObject("settings") ?: return null
                val version = if (payload.opt("package_id") === JSONObject.NULL &&
                    payload.opt("revision") === JSONObject.NULL
                ) null else ConfigurationVersion.parse(payload) ?: return null
                return Policy(
                    version, values, payload.has("group_name"), payload.opt("group_name") as? String,
                    (payload.opt("deployed_at_ms") as? Number)?.toLong()?.takeIf { it > 0 },
                    supportsApply,
                )
            }
        }
    }

    // Compare against the last attempt even if a newer delivery is saved but still waiting.
    // Fingerprints keep a second copy of the previous Wi-Fi credentials out of the cache.
    private class AttemptedSettings(val version: ConfigurationVersion, val fingerprints: JSONObject) {
        fun encode(): JSONObject = version.toJson().put("fingerprints", fingerprints)

        companion object {
            fun restore(json: JSONObject): AttemptedSettings? {
                val version = ConfigurationVersion.parse(json) ?: return null
                val fingerprints = json.optJSONObject("fingerprints") ?: return null
                return AttemptedSettings(version, fingerprints)
            }
        }
    }

    private class Delivery(
        val policy: Policy,
        val retryId: String?,
        val isCurrent: () -> Boolean,
    )

    private class WifiHandoff(val attempt: ConfigurationAttempt) {
        var completion: ConfigurationAttempt? = null
    }

    private val worker = dependencies.worker
    private val boot = dependencies.bootCount
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val screenListeners = CopyOnWriteArraySet<() -> Unit>()
    private var journal = runCatching {
        dependencies.readJournal()?.let(ConfigurationJournal::restore) ?: ConfigurationJournal()
    }.onFailure {
        dependencies.logJournalError("Could not read package application journal")
    }.getOrNull()
    @Volatile private var reported: ConfigurationAttempt? = null
    @Volatile private var screenState: ConfigurationScreenState? = null
    @Volatile private var latestPolicy: Policy?
    private var lastAttemptedSettings: AttemptedSettings?
    private var pending: Delivery? = null
    private var sdkReady = false
    private var wifiHandoff = journal?.latest
        ?.takeIf { it.status == "interrupted" && it.wifiOperationId != null }
        ?.let(::WifiHandoff)

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }
    fun addScreenListener(listener: () -> Unit) { screenListeners.add(listener) }
    fun removeScreenListener(listener: () -> Unit) { screenListeners.remove(listener) }
    fun screenSnapshot(): ConfigurationScreenState? = screenState

    init {
        val restored = runCatching {
            dependencies.readLastPolicy()?.let { saved ->
                val json = JSONObject(saved)
                val policy = requireNotNull(Policy.parse(json, supportsApply = true)?.takeIf { it.version != null })
                val attemptedVersion = journal?.latest?.version
                val attempted = if (policy.version == attemptedVersion) policy.attemptedSettings() else
                    json.optJSONObject("previous_attempt")?.let(AttemptedSettings::restore)
                        ?.takeIf { it.version == attemptedVersion }
                policy to attempted
            }
        }.onFailure {
            dependencies.logJournalError("Could not read the last configuration package")
        }.getOrNull()
        latestPolicy = restored?.first
        lastAttemptedSettings = restored?.second
        publishScreenState()
        wifi.addStateListener { worker.execute(::reconcile) }
    }

    fun snapshot(): JSONObject? = reported?.report()

    fun onSdkReady() {
        worker.execute {
            sdkReady = true
            reconcile()
        }
    }

    fun receive(payload: JSONObject, supportsApply: Boolean = true, isCurrent: () -> Boolean) {
        val policy = Policy.parse(payload, supportsApply) ?: return
        val retryId = payload.opt("retry_id") as? String
        if (payload.has("retry_id") &&
            (!supportsApply || retryId == null || policy.version == null || !WifiSettingsProtocol.isValidUuid(retryId))
        ) return
        worker.execute {
            if (!isCurrent()) return@execute
            // A routine repeat of the exact policy should not cancel a queued Retry.
            val queuedRetry = pending?.takeIf { it.policy.version == policy.version && it.isCurrent() }?.retryId
            latestPolicy = policy
            if (supportsApply) {
                // The Wi-Fi journal must record a release before the cached assignment changes.
                if (policy.version == null || policy.values.optJSONObject("wifi") == null) {
                    wifi.stopManagingPolicy(policy.version?.revision)
                }
                saveLastPolicy(policy)
                pending = Delivery(policy, retryId ?: queuedRetry, isCurrent)
            }
            reconcile()
        }
    }

    private fun saveLastPolicy(policy: Policy) {
        val encoded = policy.version?.let { policy.encode(lastAttemptedSettings) }
        if (runCatching { dependencies.writeLastPolicy(encoded) }.getOrDefault(false)) return
        dependencies.logJournalError("Could not save the last configuration package")
        // On a failed replacement, try to remove the older copy before a restart.
        if (encoded != null && !runCatching { dependencies.writeLastPolicy(null) }.getOrDefault(false)) {
            dependencies.logJournalError("Could not remove the stale configuration package")
        }
    }

    fun reapply(expected: ConfigurationVersion, onFinished: (Boolean) -> Unit) {
        worker.execute {
            val policy = latestPolicy
            val started = if (policy?.version == expected && canReapply(policy)) {
                val applyScalars = dependencies.scalarWriter()
                applyScalars != null &&
                    startAttempt(policy, journal?.find(expected), null, applyScalars, overwriteScalars = true)
            } else false
            publishScreenState()
            onFinished(started)
        }
    }

    private fun canReapply(policy: Policy): Boolean =
        policy.supportsApply && policy.version != null &&
            pending == null && wifiHandoff == null && journal != null && boot >= 0 && sdkReady &&
            journal?.find(policy.version)?.status != "applying" &&
            (policy.values.optJSONObject("wifi") == null || wifi.readyForPolicy())

    private fun publishScreenState() {
        val policy = latestPolicy ?: return
        val attempt = policy.version?.let { journal?.find(it) }
        val applyStatus = if (policy.supportsApply && policy.version != null) when {
            attempt?.status == "applying" -> ConfigurationApplyStatus.APPLYING
            pending?.policy === policy && pending?.isCurrent() == true -> ConfigurationApplyStatus.WAITING
            attempt == null -> ConfigurationApplyStatus.WAITING
            attempt.needsRetry -> ConfigurationApplyStatus.INCOMPLETE
            else -> ConfigurationApplyStatus.APPLIED
        } else null
        val next = ConfigurationScreenState(
            policy.groupKnown, policy.groupName, policy.version, policy.deployedAtMillis, applyStatus,
            canReapply(policy),
        )
        if (screenState == next) return
        screenState = next
        screenListeners.forEach { it() }
    }

    private fun reconcile() {
        try {
            reconcilePending()
        } finally {
            publishScreenState()
        }
    }

    private fun reconcilePending() {
        if (!finishWifiIfReady()) return

        val delivery = pending ?: return
        if (!delivery.isCurrent()) {
            pending = null
            return
        }
        val policy = delivery.policy
        val version = policy.version
        if (version == null) {
            pending = null
            reported = null
            lastAttemptedSettings = null
            return
        }

        val wifiPolicy = policy.values.optJSONObject("wifi")
        val current = journal
        if (current == null) {
            pending = null
            return
        }
        val previous = current.find(version)
        if (!current.shouldApply(version, boot, delivery.retryId)) {
            if (delivery.retryId == null && previous?.status == "complete") {
                if (previous.bootCount == boot && lastAttemptedSettings?.version != version &&
                    current.latest?.version == version
                ) {
                    lastAttemptedSettings = policy.attemptedSettings()
                }
                if (wifiPolicy != null && previous.results.optJSONObject("wifi")?.optString("status") == "applied") {
                    wifi.resumeAppliedPolicy(version.revision, wifiPolicy) {
                        latestPolicy === policy && delivery.isCurrent()
                    }
                }
            }
            pending = null
            if (previous?.needsRetry == true) {
                publish(previous)
            } else if (previous != null && reported?.version != version) {
                publish(previous.copy(status = "already_attempted", results = JSONObject()))
            }
            return
        }
        if (boot < 0 || !sdkReady) return
        val applyScalars = dependencies.scalarWriter() ?: return
        if (wifiPolicy != null && !wifi.readyForPolicy()) return

        val baseline = current.latest?.takeIf {
            delivery.retryId == null && lastAttemptedSettings?.version == it.version &&
                it.bootCount == boot && it.status == "complete"
        }
        val afterReboot = current.latest?.let { it.bootCount != boot } == true
        pending = null
        if (delivery.isCurrent()) {
            startAttempt(policy, previous, delivery.retryId, applyScalars, baseline, overwriteScalars = afterReboot)
        }
    }

    private fun startAttempt(
        policy: Policy,
        previous: ConfigurationAttempt?,
        retryId: String?,
        applyScalars: (JSONObject, Boolean) -> JSONObject,
        baseline: ConfigurationAttempt? = null,
        overwriteScalars: Boolean = false,
    ): Boolean {
        val version = checkNotNull(policy.version)
        val values = policy.values
        val targets = policy.attemptedSettings()
        val priorFingerprints = lastAttemptedSettings?.takeIf { it.version == baseline?.version }?.fingerprints
        fun previousOutcome(key: String): JSONObject? {
            val prior = priorFingerprints?.opt(key) as? String ?: return null
            if (prior != targets.fingerprints.opt(key)) return null
            return baseline?.results?.optJSONObject(key)
        }

        // Claim the pair and any explicit Retry before writing. Recovery still owns
        // the Wi-Fi journal if the process dies during a mutation.
        val attempt = ConfigurationAttempt(
            version, boot, retryIds = previous?.retryIds.orEmpty() + listOfNotNull(retryId),
        )
        if (!persist(attempt)) return false
        lastAttemptedSettings = targets
        publishScreenState()

        val results = JSONObject()
        val scalarChanges = JSONObject()
        for (key in values.keys()) {
            if (key == "wifi") continue
            val outcome = previousOutcome(key)
            if (outcome != null) results.put(key, outcome)
            else scalarChanges.put(key, values.get(key))
        }
        if (scalarChanges.length() > 0) {
            val scalarResults = applyScalars(scalarChanges, overwriteScalars)
            for (key in scalarChanges.keys()) {
                results.put(key, scalarResults.optJSONObject(key) ?: settingOutcome("unsupported", reason = "UNSUPPORTED"))
            }
        }

        val wifiPolicy = values.optJSONObject("wifi")
        // An unchanged failed setting remains eligible for an explicit Retry.
        val unchangedWifiFailure = previousOutcome("wifi")?.takeUnless { it.optString("status") == "applied" }
        if (unchangedWifiFailure != null) results.put("wifi", unchangedWifiFailure)
        if (wifiPolicy == null || unchangedWifiFailure != null) {
            finish(attempt.copy(status = "complete", results = results))
        } else {
            val operationId = UUID.randomUUID().toString()
            val checkpoint = attempt.copy(results = results, wifiOperationId = operationId)
            if (!persist(checkpoint)) {
                // The durable claim still fences this attempt. Wi-Fi has not been queued.
                updateAttempt(attempt.copy(status = "interrupted", results = results))
                return true
            }
            wifiHandoff = WifiHandoff(checkpoint)
            val reuseMatchingWifi = baseline?.results?.optJSONObject("wifi")?.optString("status") == "applied"
            wifi.applyManagedPolicy(operationId, version.revision, values, reuseMatchingWifi)
        }
        return true
    }

    private fun finishWifiIfReady(): Boolean {
        val handoff = wifiHandoff ?: return true
        val attempt = handoff.attempt
        val recovering = attempt.status == "interrupted"
        // Settle a restored handoff before a live or manual application can overwrite the receipt.
        if (recovering && latestPolicy == null) return true
        val shouldReport = !recovering || latestPolicy?.takeIf { it.supportsApply }?.version == attempt.version
        handoff.completion?.let { return persistWifiCompletion(it, shouldReport) }

        val progress = wifi.policyProgress(checkNotNull(attempt.wifiOperationId), attempt.version.revision)
        val result = progress.result
        val outcome = result?.let {
            val status = when {
                it.status == "success" -> "applied"
                it.reason.isUnsupported -> "unsupported"
                else -> "failed"
            }
            settingOutcome(status, reason = it.reason.takeUnless { reason -> reason == WifiReason.NONE }?.wireValue)
                .put("rolled_back", it.rolledBack)
        }
        val results = outcome?.let { JSONObject(attempt.results.toString()).put("wifi", it) } ?: attempt.results
        if (!progress.settled) {
            if (!recovering && outcome != null &&
                reported?.results?.optJSONObject("wifi")?.toString() != outcome.toString()
            ) publish(attempt.copy(results = results))
            return false
        }

        when {
            result?.status == "success" || result?.status == "failed" -> {
                val complete = attempt.copy(status = "complete", results = results, wifiOperationId = null)
                // Wi-Fi can publish other results while this exact package completion awaits a save.
                handoff.completion = complete
                return persistWifiCompletion(complete, shouldReport)
            }
            result != null || progress.storageError -> {
                val interruptedResults = if (outcome != null) results else JSONObject(results.toString())
                    .put("wifi", settingOutcome("failed", reason = "STORAGE_ERROR"))
                finish(attempt.copy(status = "interrupted", results = interruptedResults, wifiOperationId = null), shouldReport)
            }
            !recovering -> return false // The live handoff may still be queued on the Wi-Fi worker.
            // A restored attempt without exact terminal proof stays interrupted.
        }
        wifiHandoff = null
        return true
    }

    private fun persistWifiCompletion(complete: ConfigurationAttempt, shouldReport: Boolean): Boolean {
        // Keep the handoff and its proof while storage is unavailable. A later event can retry.
        if (!persist(complete, shouldReport)) return false
        wifiHandoff = null
        return true
    }

    private fun persist(next: ConfigurationAttempt, shouldReport: Boolean = true): Boolean {
        val updated = checkNotNull(journal).withAttempt(next)
        val saved = try {
            dependencies.writeJournal(updated.encode())
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            dependencies.logJournalError("Could not persist package application journal")
            return false
        }
        journal = updated
        if (shouldReport) publish(next)
        return true
    }

    private fun finish(next: ConfigurationAttempt, shouldReport: Boolean = true) {
        // The durable claim still reads as interrupted on restart if this save fails.
        if (!persist(next, shouldReport)) updateAttempt(next.copy(status = "interrupted"), shouldReport)
    }

    private fun updateAttempt(next: ConfigurationAttempt, shouldReport: Boolean = true) {
        journal = checkNotNull(journal).withAttempt(next)
        if (shouldReport) publish(next)
    }

    private fun publish(next: ConfigurationAttempt) {
        if (reported == next) return
        reported = next
        listeners.forEach { it() }
    }
}

private fun canonicalSettingValue(value: Any): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
        "${JSONObject.quote(key)}:${canonicalSettingValue(value.get(key))}"
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
        canonicalSettingValue(value.get(index))
    }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

private fun productionDependencies(context: Context): ConfigurationPackageDependencies {
    val preferences = context.getSharedPreferences("configuration-packages", Context.MODE_PRIVATE)
    return ConfigurationPackageDependencies(
        worker = Executors.newSingleThreadExecutor(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        readJournal = { preferences.getString("journal", null) },
        writeJournal = { preferences.edit().putString("journal", it).commit() },
        readLastPolicy = {
            if (supportsPico4UltraEnterpriseSettings()) preferences.getString("last_policy", null) else null
        },
        writeLastPolicy = { preferences.edit().putString("last_policy", it).commit() },
        scalarWriter = {
            (ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy)
                ?.let { PicoDeviceSettings(context, it)::apply }
        },
        logJournalError = { Log.e("ConfigurationPackages", it) },
    )
}
