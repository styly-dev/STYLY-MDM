package com.styly.mdmclient

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
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
                version = ConfigurationVersion.parse(json) ?: throw JSONException("Invalid saved package version"),
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
        return !isLatest
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
    val writePolicy: (lastPolicy: String, journal: String?) -> Boolean,
    val scalarWriter: () -> ((JSONObject, Boolean) -> JSONObject)?,
    val logJournalError: (String) -> Unit = {},
    val onApplied: (bootReapply: Boolean, isCurrent: () -> Boolean) -> Unit = { _, _ -> },
)

internal enum class ConfigurationApplyStatus { WAITING, APPLYING, APPLIED, INCOMPLETE, RELEASE_UNSAVED }

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
            .put("settings", if (version == null) JSONObject() else values)
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

    private sealed interface Report {
        data object Unknown : Report
        data object Cleared : Report
        data class Available(val attempt: ConfigurationAttempt) : Report
    }

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
    private var journalCorrupt = false
    private var journal = loadJournal()
    @Volatile private var report: Report = Report.Unknown
    private val reported get() = (report as? Report.Available)?.attempt
    @Volatile private var screenState: ConfigurationScreenState? = null
    @Volatile private var assignment: Assignment?
    private val latestPolicy get() = assignment?.policy
    private var pending: Delivery? = null
    private var sdkReady = false
    private var wifiAttempt: ConfigurationAttempt? = null
    private val applyingWifi get() = wifiAttempt != null
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

    // Kotlin null omits the field. JSON null explicitly clears the server's report.
    fun reportValue(): Any? = when (val current = report) {
        Report.Unknown -> null
        Report.Cleared -> JSONObject.NULL
        is Report.Available -> current.attempt.report()
    }

    fun onSdkReady(ready: Boolean, isCurrent: () -> Boolean = { true }) {
        worker.execute {
            if (!isCurrent()) return@execute
            sdkReady = ready
            if (ready) reloadJournal()
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
            reloadJournal()
            val startingAssignment = supportsApply && policy.version != null && latestPolicy?.version == null
            val resetJournal = startingAssignment || journalCorrupt
            val cached = supportsApply && savePolicy(policy, resetJournal)
            if (startingAssignment && !cached) {
                publish(storageError(policy, previous = null))
                return@execute
            }
            if (cached && resetJournal && policy.version != null) setReport(Report.Cleared)
            val token = assignment?.takeIf { it.policy.version == policy.version && it.policy.supportsApply == supportsApply }
                ?.token ?: Any()
            val resumesSupersededApply = pending?.takeIf { assignment?.token === token }?.forceApply == true ||
                (applyingWifi && assignment?.policy?.version != policy.version &&
                    policy.version?.let { journal?.find(it)?.status == "applying" } == true)
            val effectiveRetry = retryId ?: pending?.takeIf { assignment?.token === token }?.retryId
            val selected = Assignment(policy, token, cached)
            assignment = selected
            pending = null
            // An authoritative removal still stops live management if writing its marker failed.
            if (cached || (supportsApply && policy.version == null)) {
                queue(selected, effectiveRetry, forceApply = resumesSupersededApply || (resetJournal && retryId != null))
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
        // The saved assignment governs the next boot. Wait here for the local Wi-Fi stop attempt.
        wifi.stopManagingPolicy {
            worker.execute releaseRecorded@{
                if (pending !== delivery) return@releaseRecorded
                delivery.ready = true
                reconcile()
            }
        }
    }

    private fun savePolicy(policy: Policy, resetJournal: Boolean): Boolean {
        // A read error can be temporary. Only replace an unreadable journal after a format error.
        if (resetJournal && journal == null && !journalCorrupt) return false
        val nextJournal = if (resetJournal) ConfigurationJournal() else journal
        val saved = try {
            dependencies.writePolicy(policy.encode(boot), nextJournal?.encode())
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            dependencies.logJournalError("Could not save the configuration package")
        } else if (resetJournal) {
            journal = nextJournal
            journalCorrupt = false
        }
        return saved
    }

    fun reapply(expected: ConfigurationVersion, onFinished: (Boolean) -> Unit) {
        worker.execute {
            val journalLoaded = reloadJournal()
            val selected = assignment
            val policy = selected?.policy
            val started = if (journalLoaded && selected != null && policy?.version == expected && canReapply(policy)) {
                val applyScalars = scalarWriter()
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
            pending == null && !applyingWifi && boot >= 0 && sdkReady &&
            journal?.find(policy.version)?.status != "applying" &&
            (policy.values.optJSONObject("wifi") == null || wifi.canAcceptPolicy())

    private fun reloadJournal(): Boolean {
        if (journal != null) return true
        journal = loadJournal()
        return journal != null
    }

    private fun loadJournal(): ConfigurationJournal? {
        journalCorrupt = false
        return try {
            dependencies.readJournal()?.let(ConfigurationJournal::restore) ?: ConfigurationJournal()
        } catch (_: JSONException) {
            journalCorrupt = true
            dependencies.logJournalError("Invalid configuration package state; waiting for a policy delivery")
            null
        } catch (_: Exception) {
            dependencies.logJournalError("Could not read package application journal")
            null
        }
    }

    private fun scalarWriter(): ((JSONObject, Boolean) -> JSONObject)? = try {
        dependencies.scalarWriter()
    } catch (_: LinkageError) {
        { values, _ -> JSONObject().apply {
            for (key in values.keys()) put(key, settingOutcome("failed", reason = "READ_FAILED"))
        } }
    }

    private fun publishScreenState() {
        val policy = latestPolicy ?: return
        val attempt = policy.version?.let { version ->
            reported?.takeIf { it.version == version } ?: journal?.find(version)
        }
        val applyStatus = when {
            !policy.supportsApply -> null
            policy.version == null -> ConfigurationApplyStatus.RELEASE_UNSAVED.takeIf { assignment?.cached == false }
            attempt?.status == "applying" -> ConfigurationApplyStatus.APPLYING
            pending?.policy === policy && pending?.isCurrent() == true -> ConfigurationApplyStatus.WAITING
            attempt == null -> ConfigurationApplyStatus.WAITING
            attempt.needsRetry -> ConfigurationApplyStatus.INCOMPLETE
            else -> ConfigurationApplyStatus.APPLIED
        }
        val next = ConfigurationScreenState(
            policy.groupKnown, policy.groupName, policy.version, policy.deployedAtMillis, applyStatus,
            canReapply(policy),
        )
        if (screenState == next) return
        screenState = next
        notifyListeners(screenListeners)
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
            if (assignment?.cached == true) setReport(Report.Cleared)
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
        // A Retry can be the first claim after a journal reset or a failed assignment write.
        val firstClaim = previous == null && (delivery.forceApply ||
            reported?.takeIf { it.version == version }?.status == "interrupted")
        val retryId = delivery.retryId?.takeIf { firstClaim || current.shouldApply(version, boot, it) }
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
        val applyScalars = scalarWriter() ?: return
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
        val results = JSONObject().apply { retainedWifiFailure?.let { put("wifi", it) } }
        for (key in policy.values.keys()) {
            if (key == "wifi") continue
            val value = policy.values.get(key)
            scalarTargets.put(key, value)
            val previousResult = baseline?.results?.optJSONObject(key)
            val previousValue = baseline?.scalarTargets?.opt(key)
            if (previousResult != null && previousResult.optString("status") != "failed" &&
                sameScalarTarget(key, previousValue, value)
            ) {
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
        try {
            if (!persist(attempt)) {
                updateAttempt(storageError(policy, attempt))
                return false
            }
            publishScreenState()

            if (scalarValues.length() > 0) {
                val scalarResults = try {
                    applyScalars(scalarValues, overwriteScalars)
                } catch (failure: LinkageError) {
                    JSONObject().apply {
                        for (key in scalarValues.keys()) put(key, settingOutcome("failed", reason = "WRITE_FAILED"))
                    }
                }
                for (key in scalarValues.keys()) {
                    results.put(key, scalarResults.optJSONObject(key) ?: settingOutcome("unsupported", reason = "UNSUPPORTED"))
                }
            }

            if (wifiPolicy == null || retainedWifiFailure != null) {
                finish(attempt.copy(status = "complete", results = results))
                return true
            }

            val checkpoint = attempt.copy(results = results)
            if (!persist(checkpoint)) {
                // The durable claim still fences this attempt. Wi-Fi has not been queued.
                results.put("wifi", settingOutcome("failed", reason = "STORAGE_ERROR"))
                updateAttempt(attempt.copy(status = "interrupted", results = results))
                return true
            }
            wifiAttempt = checkpoint
            val reuseMatchingWifi = baseline?.results?.optJSONObject("wifi")?.optString("status") == "applied"
            wifi.applyManagedPolicy(version.revision, wifiPolicy, reuseMatchingWifi, isCurrent, onFinished = { result ->
                worker.execute {
                    if (wifiAttempt !== checkpoint) return@execute
                    wifiAttempt = null
                    try {
                        // A released or replaced assignment cannot restore its old deduplication history.
                        if (isCurrent()) {
                            finishWifi(checkpoint, result, fingerprint, confirmWifiAfterTimeout)
                        } else if (journal?.find(version) === checkpoint) {
                            // Fence the stale operation while allowing a later reassignment of
                            // this version to start a fresh attempt in the same boot.
                            finish(checkpoint.copy(status = "interrupted"))
                        }
                    } catch (failure: Exception) {
                        interruptApplyingAttempt(version, results, failure)
                    } finally {
                        reconcile()
                    }
                }
            })
        } catch (failure: Exception) {
            wifiAttempt = null
            interruptApplyingAttempt(version, results, failure)
        }
        return true
    }

    private fun interruptApplyingAttempt(version: ConfigurationVersion, results: JSONObject, failure: Exception) {
        dependencies.logJournalError("Package application interrupted (${failure.javaClass.simpleName})")
        val claimed = journal?.find(version)?.takeIf { it.status == "applying" } ?: return
        finish(claimed.copy(status = "interrupted", results = results))
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
            try {
                dependencies.onApplied(next.bootReapply) { assignment?.token === token && reported === next }
            } catch (_: Exception) {
                dependencies.logJournalError("Could not show package application notice")
            }
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

    private fun publish(next: ConfigurationAttempt) = setReport(Report.Available(next))

    private fun setReport(next: Report) {
        if (report == next) return
        report = next
        notifyListeners(listeners)
    }

    private fun notifyListeners(listeners: Set<() -> Unit>) {
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Exception) {
                dependencies.logJournalError("Configuration package listener failed")
            }
        }
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
    val store = ConfigurationPackageStore(
        File(context.noBackupFilesDir, "configuration-packages.json"),
        syncDirectory = ::syncDirectory,
        readLegacy = {
            ConfigurationPackageState(
                lastPolicy = preferences.getString("last_policy", null),
                journal = preferences.getString("journal", null),
            )
        },
        clearLegacy = {
            if (preferences.contains("last_policy") || preferences.contains("journal")) {
                if (!preferences.edit().remove("last_policy").remove("journal").commit()) {
                    Log.w("ConfigurationPackages", "Could not remove the legacy configuration cache")
                }
            }
        },
    )
    val appliedNotice = PicoPackageAppliedNotice(context.applicationContext)
    return ConfigurationPackageDependencies(
        worker = Executors.newSingleThreadExecutor(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        readJournal = { store.read().journal },
        writeJournal = {
            store.save(store.read().copy(journal = it))
            true
        },
        readLastPolicy = {
            if (supportsPico4UltraEnterpriseSettings()) store.read().lastPolicy else null
        },
        writePolicy = { lastPolicy, journal ->
            store.savePolicy(lastPolicy, journal)
            true
        },
        scalarWriter = {
            (ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy)
                ?.let { PicoDeviceSettings(context, it)::apply }
        },
        logJournalError = { Log.e("ConfigurationPackages", it) },
        onApplied = appliedNotice::show,
    )
}
