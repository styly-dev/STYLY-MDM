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
    val failedWifiFingerprint: String? = null,
    val scalarTargets: JSONObject = JSONObject(),
    val wifiConnectionCheckRevision: String? = null,
    val bootReapply: Boolean = false,
) {
    private val unappliedKeys: Sequence<String>
        get() = results.keys().asSequence().filter { results.optJSONObject(it)?.optString("status") != "applied" }

    val needsRetry: Boolean
        get() = status == "interrupted" || (status == "complete" && unappliedKeys.any())

    val wifiAwaitingReboot: JSONObject?
        get() = results.optJSONObject("wifi")?.takeIf {
            it.optString("status") == "failed" && it.optString("reason") == WifiReason.VERIFY_AFTER_REBOOT.wireValue
        }

    fun canRetry(deferWifiVerification: Boolean): Boolean = needsRetry &&
        (!deferWifiVerification || status == "interrupted" || wifiAwaitingReboot == null || unappliedKeys.any { it != "wifi" })

    fun report(): JSONObject = version.toJson().put("status", status).put("results", results)

    fun saved(): JSONObject = report()
        .put("boot_count", bootCount)
        .put("retry_ids", JSONArray(retryIds))
        .put("failed_wifi_fingerprint", failedWifiFingerprint)
        .put("scalar_targets", scalarTargets)
        .put("wifi_connection_check_revision", wifiConnectionCheckRevision)
        .put("boot_reapply", bootReapply.takeIf { it && wifiConnectionCheckRevision != null })

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
                failedWifiFingerprint = json.opt("failed_wifi_fingerprint") as? String,
                scalarTargets = json.getJSONObject("scalar_targets"),
                wifiConnectionCheckRevision = (json.opt("wifi_connection_check_revision") as? String)
                    ?.takeIf(WifiSettingsProtocol::isValidUuid),
                bootReapply = json.optBoolean("boot_reapply"),
            )
        }
    }
}

/** Keep the most recent attempt for each package. */
internal class ConfigurationJournal(private val attempts: List<ConfigurationAttempt> = emptyList()) {
    val latest: ConfigurationAttempt? get() = attempts.lastOrNull()

    fun find(version: ConfigurationVersion): ConfigurationAttempt? = attempts.find { it.version == version }

    fun withAttempt(next: ConfigurationAttempt): ConfigurationJournal =
        ConfigurationJournal(attempts.filterNot { it.version.packageId == next.version.packageId } + next)

    fun shouldApply(version: ConfigurationVersion, bootCount: Int, retryId: String?): Boolean {
        val previous = find(version) ?: return retryId == null
        val sameBoot = previous.bootCount == bootCount
        val isLatest = latest?.version == version
        if (retryId != null) {
            return retryId !in previous.retryIds && previous.canRetry(deferWifiVerification = sameBoot && isLatest)
        }
        if (!sameBoot) return true
        // A different attempt may have changed the saved settings since the previous result.
        return !isLatest && (!previous.needsRetry || previous.wifiAwaitingReboot != null)
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
    val onApplied: (bootReapply: Boolean, isCurrent: () -> Boolean) -> Unit = { _, _ -> },
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
        val receivedBootCount: Int?,
    ) {
        fun encode(boot: Int): String = (version?.toJson()
            ?: JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL))
            .put("settings", values)
            .put("group_name", if (groupKnown) groupName ?: JSONObject.NULL else null)
            .put("deployed_at_ms", deployedAtMillis)
            .put("boot_count", boot)
            .toString()

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
                    (payload.opt("boot_count") as? Int)?.takeIf { it >= 0 },
                )
            }
        }
    }

    private class Assignment(val policy: Policy, val token: Any = Any(), val cached: Boolean = true)

    private class Delivery(
        val policy: Policy,
        val retryId: String?,
        val isCurrent: () -> Boolean,
        val forceApply: Boolean,
        var ready: Boolean,
    )

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
    @Volatile private var assignment: Assignment?
    private val latestPolicy get() = assignment?.policy
    private var pending: Delivery? = null
    private var sdkReady = false
    private var applyingWifi = false
    private var watchedWifiAttempt: ConfigurationAttempt? = null
    private val cachedVersionFromEarlierBoot: ConfigurationVersion?

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }
    fun addScreenListener(listener: () -> Unit) { screenListeners.add(listener) }
    fun removeScreenListener(listener: () -> Unit) { screenListeners.remove(listener) }
    fun screenSnapshot(): ConfigurationScreenState? = screenState

    init {
        assignment = runCatching {
            dependencies.readLastPolicy()?.let { saved ->
                val policy = requireNotNull(Policy.parse(JSONObject(saved), supportsApply = true))
                Assignment(policy)
            }
        }.onFailure {
            dependencies.logJournalError("Could not read the last configuration package")
        }.getOrNull()
        val cached = assignment
        cachedVersionFromEarlierBoot = cached?.policy?.takeIf {
            it.receivedBootCount != null && it.receivedBootCount != boot
        }?.version
        publishScreenState()
        // Queue a cached release before the service starts monitoring Wi-Fi.
        if (cached != null) queue(cached, retryId = null)
        wifi.addStateListener { worker.execute(::reconcile) }
        if (cached != null) worker.execute(::reconcile)
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
            val startingAssignment = supportsApply && policy.version != null && latestPolicy?.version == null
            if (startingAssignment) {
                // A new assignment must not inherit attempts from before management was released.
                if (journal?.latest != null && !saveJournal(ConfigurationJournal())) {
                    publish(storageError(policy, policy.version?.let { journal?.find(it) }))
                    return@execute
                }
                reported = null
            }
            val token = assignment?.takeIf { it.policy.version == policy.version && it.policy.supportsApply == supportsApply }
                ?.token ?: Any()
            val resumesSupersededApply = pending?.takeIf { assignment?.token === token }?.forceApply == true ||
                (applyingWifi && assignment?.policy?.version != policy.version &&
                    policy.version?.let { journal?.find(it)?.status == "applying" } == true)
            val effectiveRetry = if (startingAssignment) null
                else retryId ?: pending?.takeIf { assignment?.token === token }?.retryId
            // Try to cache the new assignment, including a removal, before the Wi-Fi worker acts on it.
            val cached = supportsApply && saveLastPolicy(policy.encode(boot))
            if (startingAssignment && !cached) {
                publish(storageError(policy, previous = null))
                return@execute
            }
            val selected = Assignment(policy, token, cached)
            assignment = selected
            pending = null
            // An authoritative removal still stops live management if writing its marker failed.
            if (cached || (supportsApply && policy.version == null)) {
                queue(selected, effectiveRetry, forceApply = resumesSupersededApply)
            } else if (supportsApply && policy.version != null) {
                publish(storageError(policy, journal?.find(policy.version)))
            }
            reconcile()
        }
    }

    private fun queue(selected: Assignment, retryId: String?, forceApply: Boolean = false) {
        val policy = selected.policy
        val releasing = policy.version == null || policy.values.optJSONObject("wifi") == null
        // An accepted policy belongs to the assignment, even if the delivering socket is replaced.
        val delivery = Delivery(
            policy, retryId, { assignment?.token === selected.token }, forceApply, ready = !releasing,
        )
        pending = delivery
        if (!releasing) return
        wifi.stopManagingPolicy { recorded ->
            worker.execute releaseRecorded@{
                if (pending !== delivery) return@releaseRecorded
                // Clear the cached policy only after Wi-Fi has durably recorded the release.
                if (recorded && policy.version == null) saveLastPolicy(null)
                delivery.ready = true
                reconcile()
            }
        }
    }

    private fun saveLastPolicy(encoded: String?): Boolean {
        if (runCatching { dependencies.writeLastPolicy(encoded) }.getOrDefault(false)) return true
        dependencies.logJournalError("Could not save the last configuration package")
        return false
    }

    fun reapply(expected: ConfigurationVersion, onFinished: (Boolean) -> Unit) {
        worker.execute {
            val selected = assignment
            val policy = selected?.policy
            val started = if (selected != null && policy?.version == expected && canReapply(policy)) {
                val applyScalars = dependencies.scalarWriter()
                applyScalars != null &&
                    startAttempt(policy, journal?.find(expected), null, applyScalars, overwriteScalars = true) {
                        assignment?.token === selected.token
                    }
            } else false
            reconcileWifiConnection()
            publishScreenState()
            onFinished(started)
        }
    }

    private fun canReapply(policy: Policy): Boolean =
        policy.supportsApply && policy.version != null && assignment?.cached == true &&
            pending == null && !applyingWifi && journal != null && boot >= 0 && sdkReady &&
            journal?.find(policy.version)?.status != "applying" &&
            (policy.values.optJSONObject("wifi") == null || wifi.canReapplyPolicy())

    private fun publishScreenState() {
        val policy = latestPolicy ?: return
        val attempt = policy.version?.let { version ->
            reported?.takeIf { it.version == version } ?: journal?.find(version)
        }
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
            reconcileWifiConnection()
            publishScreenState()
        }
    }

    private fun reconcilePending() {
        if (applyingWifi) return

        val delivery = pending ?: return
        if (!delivery.ready) return
        if (!delivery.isCurrent()) {
            pending = null
            return
        }
        val policy = delivery.policy
        val version = policy.version
        if (version == null) {
            pending = null
            reported = null
            return
        }

        val wifiPolicy = policy.values.optJSONObject("wifi")
        val current = journal
        if (current == null) {
            pending = null
            if (reported?.version != version) publish(storageError(policy, previous = null))
            return
        }
        val previous = current.find(version)
        val afterReboot = current.latest?.let { it.bootCount != boot } ?: (cachedVersionFromEarlierBoot != null)
        // An interrupted report can precede the first journal claim if caching the assignment failed.
        val interruptedBeforeClaim = previous == null &&
            reported?.takeIf { it.version == version }?.status == "interrupted"
        val retryId = delivery.retryId?.takeIf { interruptedBeforeClaim || current.shouldApply(version, boot, it) }
        val automatic = delivery.forceApply ||
            ((delivery.retryId == null || afterReboot) && current.shouldApply(version, boot, null))
        if (retryId == null && !automatic) {
            pending = null
            if (previous != null) {
                publish(previous)
            }
            return
        }
        if (boot < 0 || !sdkReady) return
        val applyScalars = dependencies.scalarWriter() ?: return
        if (wifiPolicy != null && !wifi.canAcceptPolicy()) return

        val baseline = current.latest?.takeIf {
            retryId == null && it.bootCount == boot
        }
        val bootReapply = afterReboot && retryId == null &&
            (cachedVersionFromEarlierBoot == version || (previous != null && previous.bootCount != boot))
        pending = null
        startAttempt(policy, previous, retryId, applyScalars, baseline, overwriteScalars = afterReboot,
            confirmWifiAfterTimeout = afterReboot && retryId == null, bootReapply = bootReapply,
            isCurrent = delivery.isCurrent)
    }

    private fun startAttempt(
        policy: Policy,
        previous: ConfigurationAttempt?,
        retryId: String?,
        applyScalars: (JSONObject, Boolean) -> JSONObject,
        baseline: ConfigurationAttempt? = null,
        overwriteScalars: Boolean = false,
        confirmWifiAfterTimeout: Boolean = false,
        bootReapply: Boolean = false,
        isCurrent: () -> Boolean,
    ): Boolean {
        val version = checkNotNull(policy.version)
        val wifiPolicy = policy.values.optJSONObject("wifi")
        val fingerprint = wifiPolicy?.let { wifiFingerprint(version.revision, it) }
        // Keep unchanged Wi-Fi failures on automatic deltas. A same-boot Retry can
        // rerun other settings while leaving credentials that await reboot in place.
        val previousWifi = baseline ?: previous?.takeIf {
            retryId != null && it.bootCount == boot && journal?.latest === it && it.wifiAwaitingReboot != null
        }
        val retainedWifiFailure = previousWifi?.takeIf {
            fingerprint != null && it.failedWifiFingerprint == fingerprint
        }?.results?.optJSONObject("wifi")
        val scalarTargets = JSONObject()
        val scalarValues = JSONObject()
        val results = JSONObject()
        for (key in policy.values.keys()) {
            if (key == "wifi") continue
            val value = policy.values.get(key)
            scalarTargets.put(key, value)
            val previousResult = baseline?.results?.optJSONObject(key)
            val previousValue = baseline?.scalarTargets?.opt(key)
            if (previousResult != null && sameScalarTarget(key, previousValue, value)) {
                results.put(key, previousResult)
            } else scalarValues.put(key, value)
        }

        // Claim the pair and any explicit Retry before writing any settings.
        val attempt = ConfigurationAttempt(
            version, boot, retryIds = previous?.retryIds.orEmpty() + listOfNotNull(retryId),
            results = JSONObject().apply { retainedWifiFailure?.let { put("wifi", it) } },
            failedWifiFingerprint = fingerprint.takeIf { retainedWifiFailure != null },
            scalarTargets = scalarTargets,
            wifiConnectionCheckRevision = previousWifi?.wifiConnectionCheckRevision.takeIf { retainedWifiFailure != null },
            // A later Wi-Fi confirmation can complete the original boot reapply.
            bootReapply = bootReapply || (retainedWifiFailure != null && previousWifi?.bootReapply == true),
        )
        if (!persist(attempt)) {
            updateAttempt(storageError(policy, attempt))
            return false
        }
        publishScreenState()

        if (scalarValues.length() > 0) {
            val scalarResults = applyScalars(scalarValues, overwriteScalars)
            for (key in scalarValues.keys()) {
                results.put(key, scalarResults.optJSONObject(key) ?: settingOutcome("unsupported", reason = "UNSUPPORTED"))
            }
        }

        if (retainedWifiFailure != null) results.put("wifi", retainedWifiFailure)
        if (wifiPolicy == null || retainedWifiFailure != null) {
            finish(attempt.copy(status = "complete", results = results))
        } else {
            val checkpoint = attempt.copy(results = results)
            if (!persist(checkpoint)) {
                // The durable claim still fences this attempt. Wi-Fi has not been queued.
                results.put("wifi", settingOutcome("failed", reason = "STORAGE_ERROR"))
                updateAttempt(attempt.copy(status = "interrupted", results = results))
                return true
            }
            applyingWifi = true
            val reuseMatchingWifi = baseline?.results?.optJSONObject("wifi")?.optString("status") == "applied"
            wifi.applyManagedPolicy(version.revision, wifiPolicy, reuseMatchingWifi, isCurrent, onFinished = { result ->
                worker.execute {
                    applyingWifi = false
                    // A released or replaced assignment cannot restore its old deduplication history.
                    if (isCurrent()) {
                        finishWifi(checkpoint, result, fingerprint, confirmWifiAfterTimeout)
                    } else if (journal?.find(version) === checkpoint) {
                        // Fence the stale operation while allowing a later reassignment of
                        // this version to start a fresh attempt in the same boot.
                        persist(checkpoint.copy(status = "interrupted"))
                    }
                    reconcile()
                }
            })
        }
        return true
    }

    private fun storageError(policy: Policy, previous: ConfigurationAttempt?): ConfigurationAttempt {
        val results = JSONObject()
        val retainedWifi = previous?.takeIf { it.version == policy.version && it.bootCount == boot }?.wifiAwaitingReboot
        for (key in policy.values.keys()) {
            val result = if (key == "wifi" && retainedWifi != null) retainedWifi
                else settingOutcome("failed", reason = "STORAGE_ERROR")
            results.put(key, result)
        }
        return ConfigurationAttempt(
            checkNotNull(policy.version), boot, status = "interrupted", results = results,
            retryIds = previous?.retryIds.orEmpty(),
            failedWifiFingerprint = previous?.failedWifiFingerprint.takeIf { retainedWifi != null },
        )
    }

    private fun finishWifi(
        attempt: ConfigurationAttempt,
        result: WifiManualResult,
        fingerprint: String?,
        confirmWifiAfterTimeout: Boolean,
    ) {
        val status = when {
            result.status == "success" -> "applied"
            result.reason.isUnsupported -> "unsupported"
            else -> "failed"
        }
        val outcome = settingOutcome(status, reason = result.reason.takeUnless { it == WifiReason.NONE }?.wireValue)
        finish(attempt.copy(
            status = if (result.status == "unknown") "interrupted" else "complete",
            results = JSONObject(attempt.results.toString()).put("wifi", outcome),
            failedWifiFingerprint = fingerprint.takeIf { result.status == "failed" },
            wifiConnectionCheckRevision = result.revision.takeIf { confirmWifiAfterTimeout && result.canConfirmConnectionLater },
        ))
    }

    private fun reconcileWifiConnection() {
        val selected = assignment?.takeIf { it.cached }
        val attempt = journal?.latest?.takeIf {
            !applyingWifi && it.version == selected?.policy?.version && it.bootCount == boot &&
                it.status == "complete" && it.wifiConnectionCheckRevision != null &&
                it.results.optJSONObject("wifi")?.optString("reason") == WifiReason.UNREACHABLE.wireValue
        }
        if (watchedWifiAttempt === attempt) return
        watchedWifiAttempt = attempt
        if (attempt == null || selected == null) {
            wifi.watchSavedConnection(null)
            return
        }
        val profile = runCatching {
            WifiSettingsProtocol.parseManagedPolicy(
                checkNotNull(attempt.wifiConnectionCheckRevision), selected.policy.values.getJSONObject("wifi"),
            )
        }.getOrNull()
        wifi.watchSavedConnection(profile) { reason ->
            worker.execute {
                if (watchedWifiAttempt !== attempt || journal?.latest !== attempt || assignment?.token !== selected.token) {
                    return@execute
                }
                val outcome = settingOutcome("applied", reason = reason.wireValue.takeUnless { reason == WifiReason.NONE })
                val updated = attempt.copy(
                    results = JSONObject(attempt.results.toString()).put("wifi", outcome),
                    failedWifiFingerprint = null,
                    wifiConnectionCheckRevision = null,
                )
                if (persist(updated)) {
                    reconcileWifiConnection()
                    publishScreenState()
                }
            }
        }
    }

    private fun saveJournal(updated: ConfigurationJournal): Boolean {
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
        return true
    }

    private fun persist(next: ConfigurationAttempt): Boolean {
        if (!saveJournal(checkNotNull(journal).withAttempt(next))) return false
        publish(next)
        val token = assignment?.token
        if (token != null && next.status == "complete" && !next.needsRetry) {
            dependencies.onApplied(next.bootReapply) { assignment?.token === token && reported === next }
        }
        return true
    }

    private fun finish(next: ConfigurationAttempt) {
        // The durable claim still reads as interrupted on restart if this save fails.
        if (!persist(next)) updateAttempt(next.copy(status = "interrupted"))
    }

    private fun updateAttempt(next: ConfigurationAttempt) {
        journal = checkNotNull(journal).withAttempt(next)
        publish(next)
    }

    private fun publish(next: ConfigurationAttempt) {
        if (reported == next) return
        reported = next
        listeners.forEach { it() }
    }
}

private fun sameScalarTarget(key: String, previous: Any?, requested: Any): Boolean {
    if (key == "shutdown" && previous is JSONObject && requested is JSONObject) {
        return runCatching { PicoShutdownSchedule.requested(previous) == PicoShutdownSchedule.requested(requested) }
            .getOrDefault(false)
    }
    return previous == requested
}

/** Identify a failed Wi-Fi target without saving another copy of its password. */
private fun wifiFingerprint(revision: String, settings: JSONObject): String? {
    val profile = runCatching { WifiSettingsProtocol.parseManagedPolicy(revision, settings) }.getOrNull() ?: return null
    val values = JSONArray().put(profile.autoReturn)
    for (network in profile.networks) {
        values.put(JSONArray().put(network.ssid).put(network.security).put(network.password).put(network.hidden))
    }
    if (!profile.prioritizeNetworks) values.put(false)
    val digest = MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(digest)
}

private fun productionDependencies(context: Context): ConfigurationPackageDependencies {
    val preferences = context.getSharedPreferences("configuration-packages", Context.MODE_PRIVATE)
    val appliedNotice = PicoPackageAppliedNotice(context.applicationContext)
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
        onApplied = appliedNotice::show,
    )
}
