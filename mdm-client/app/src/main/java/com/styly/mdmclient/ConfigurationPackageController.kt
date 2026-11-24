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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal const val CONFIGURATION_PACKAGES_CAPABILITY = "configuration_packages_v1"
internal const val CONFIGURATION_LBE_MAP_CAPABILITY = "configuration_lbe_map_v1"
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

internal data class ConfigurationResume(val overwriteScalars: Boolean, val checkExistingWifi: Boolean)

internal data class ConfigurationAttempt(
    val version: ConfigurationVersion,
    val bootCount: Int,
    val status: String = "applying",
    val results: JSONObject = JSONObject(),
    val retryIds: Set<String> = emptySet(),
    val bootReapply: Boolean = false,
    val retryMapOnDelivery: Boolean = false,
    // Only a live, superseded operation resumes automatically. A restored crash
    // still requires Retry. Preserve the original write mode across an SDK rebind.
    val resume: ConfigurationResume? = null,
) {
    val waitingHomeAppPackage: String?
        get() = results.optJSONObject("startupApp")
            ?.takeIf { it.optString("status") == "waiting" }?.opt("value") as? String

    val needsRetry: Boolean
        get() = status == "interrupted" || (status == "complete" && results.keys().asSequence().any {
            results.optJSONObject(it)?.optString("status") != "applied" &&
                !(it == "startupApp" && waitingHomeAppPackage != null)
        })

    fun report(): JSONObject = version.toJson().put("status", status).put("results", results)

    fun saved(): JSONObject = report()
        .put("boot_count", bootCount)
        .put("retry_ids", JSONArray(retryIds))
        .put("retry_map_on_delivery", true.takeIf { retryMapOnDelivery })

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
                retryMapOnDelivery = json.optBoolean("retry_map_on_delivery"),
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
            return retryId !in previous.retryIds && previous.needsRetry
        }
        if (!sameBoot) return true
        // A different attempt may have changed the saved settings since the previous result.
        return !isLatest || previous.resume != null
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
    val homeAppEnabled: (String) -> Boolean?,
    val logJournalError: (String) -> Unit = {},
    val onApplied: (bootReapply: Boolean, isCurrent: () -> Boolean) -> Unit = { _, _ -> },
    val lbeMapWorker: Executor = worker,
    val acquireLbeMap: (LbeMapDescriptor, LbeMapTransfer?, ConfigurationVersion, () -> Boolean) -> LbeMapAcquisition =
        { _, _, _, _ -> throw IOException("Map download is unavailable") },
    val importLbeMap: (String) -> LbeMapImportResult = { LbeMapImportResult.FAILED },
    val activeLbeMap: (String) -> ActiveLbeMap = { ActiveLbeMap.UNKNOWN },
    val cancelLbeMapTransfer: (ConfigurationVersion?) -> Unit = {},
)

internal enum class ConfigurationApplyStatus {
    WAITING, WAITING_FOR_HOME_APP, APPLYING, APPLIED, INCOMPLETE, RELEASE_UNSAVED,
}

internal data class ConfigurationScreenState(
    val groupKnown: Boolean,
    val groupName: String?,
    val version: ConfigurationVersion?,
    val deployedAtMillis: Long?,
    val applyStatus: ConfigurationApplyStatus?,
    val waitingHomeAppPackage: String?,
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
        val lbeMapTransfer: LbeMapTransfer?,
    ) {
        fun encode(boot: Int): String = (version?.toJson()
            ?: JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL))
            .put("settings", if (version == null) JSONObject() else values)
            .put("group_name", if (groupKnown) groupName ?: JSONObject.NULL else null)
            .put("deployed_at_ms", deployedAtMillis)
            .put("boot_count", boot)
            .toString()

        companion object {
            fun parse(
                payload: JSONObject,
                supportsApply: Boolean,
                serverOrigin: String?,
                expectedDeviceId: String?,
            ): Policy? {
                val values = payload.optJSONObject("settings") ?: return null
                val version = if (payload.opt("package_id") === JSONObject.NULL &&
                    payload.opt("revision") === JSONObject.NULL
                ) null else ConfigurationVersion.parse(payload) ?: return null
                return Policy(
                    version, values, payload.has("group_name"), payload.opt("group_name") as? String,
                    (payload.opt("deployed_at_ms") as? Number)?.toLong()?.takeIf { it > 0 },
                    supportsApply,
                    (payload.opt("boot_count") as? Int)?.takeIf { it >= 0 },
                    LbeMapTransfer(
                        payload.opt("lbe_map_download") as? String ?: "",
                        payload.opt("lbe_map_token") as? String,
                        serverOrigin,
                        expectedDeviceId,
                    ).takeIf { payload.has("lbe_map_download") },
                )
            }
        }
    }

    private class Assignment(
        val policy: Policy,
        val token: Any = Any(),
        val cached: Boolean = true,
        val resetJournalOnSave: Boolean = false,
    )

    private sealed interface Report {
        data object Unknown : Report
        data object Cleared : Report
        data class Available(val attempt: ConfigurationAttempt) : Report
    }

    private class Delivery(
        val policy: Policy,
        val retryId: String?,
        val isCurrent: () -> Boolean,
        val automatic: Boolean,
        var ready: Boolean,
        val retainedIpd: JSONObject?,
    )

    private val worker = dependencies.worker
    private val boot = dependencies.bootCount
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val screenListeners = CopyOnWriteArraySet<() -> Unit>()
    private val policyAcceptanceLock = Any()
    private var journalCorrupt = false
    private var journal = loadJournal()
    @Volatile private var latestReceivedVersion: ConfigurationVersion? = null
    @Volatile private var report: Report = Report.Unknown
    private val reported get() = (report as? Report.Available)?.attempt
    @Volatile private var screenState: ConfigurationScreenState? = null
    @Volatile private var assignment: Assignment?
    private val latestPolicy get() = assignment?.policy
    private var pending: Delivery? = null
    private class SdkBinding(val isCurrent: () -> Boolean)
    @Volatile private var sdkBinding: SdkBinding? = null
    private val sdkReady get() = sdkBinding?.isCurrent?.invoke() == true
    private var homeAppCheckPending = true
    private var activeAttempt: ConfigurationAttempt? = null
    private val cachedVersionFromEarlierBoot: ConfigurationVersion?

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }
    fun addScreenListener(listener: () -> Unit) { screenListeners.add(listener) }
    fun removeScreenListener(listener: () -> Unit) { screenListeners.remove(listener) }
    fun screenSnapshot(): ConfigurationScreenState? = screenState

    init {
        assignment = runCatching {
            dependencies.readLastPolicy()?.let { saved ->
                val policy = requireNotNull(Policy.parse(JSONObject(saved), supportsApply = true, null, null))
                Assignment(policy)
            }
        }.onFailure {
            dependencies.logJournalError("Could not read the last configuration package")
        }.getOrNull()
        val cached = assignment
        latestReceivedVersion = cached?.policy?.version
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
            sdkBinding = if (ready) SdkBinding(isCurrent) else null
            if (ready) reloadJournal()
            reconcile()
        }
    }

    fun onHomePackageChanged(packageName: String) {
        worker.execute {
            if (latestPolicy?.values?.opt("startupApp") == packageName) {
                homeAppCheckPending = true
                reconcile()
            }
        }
    }

    fun receive(
        payload: JSONObject,
        supportsApply: Boolean = true,
        serverOrigin: String? = null,
        expectedDeviceId: String? = null,
        isCurrent: () -> Boolean,
    ) {
        val policy = Policy.parse(payload, supportsApply, serverOrigin, expectedDeviceId) ?: return
        val retryId = payload.opt("retry_id") as? String
        if (payload.has("retry_id") &&
            (!supportsApply || retryId == null || policy.version == null || !WifiSettingsProtocol.isValidUuid(retryId))
        ) return
        synchronized(policyAcceptanceLock) {
            if (!isCurrent()) return
            // Fence a background map transfer immediately, before this delivery reaches
            // the serial worker. Serialize fencing, cancellation and queueing so an older
            // receive cannot cancel a transfer accepted from a newer socket afterward.
            if (supportsApply) {
                latestReceivedVersion = policy.version
                dependencies.cancelLbeMapTransfer(policy.version)
            }
            worker.execute {
                // A release is a state transition even when a later policy arrived before
                // this worker ran. Applying it in receive order lets the following package
                // reset the journal instead of reusing an older assignment's result. The
                // policy was accepted while its socket was current, so a later disconnect
                // does not revoke the accepted delivery.
                if (!supportsApply && !isCurrent()) return@execute
                if (supportsApply && policy.version != null && latestReceivedVersion != policy.version) return@execute
                reloadJournal()
                val startingAssignment = supportsApply && policy.version != null &&
                    (latestPolicy?.version == null ||
                        (assignment?.cached == false && assignment?.resetJournalOnSave == true))
                val resetJournal = startingAssignment || journalCorrupt
                val cached = supportsApply && savePolicy(policy, resetJournal)
                if (supportsApply && policy.version != null && !cached) {
                    val previous = if (startingAssignment) null else journal?.find(policy.version)
                    assignment = Assignment(policy, Any(), cached = false, resetJournalOnSave = startingAssignment)
                    pending = null
                    publish(storageError(policy, previous))
                    publishScreenState()
                    reconcile()
                    return@execute
                }
                if (cached && resetJournal && policy.version != null) setReport(Report.Cleared)
                val token = assignment?.takeIf { it.policy.version == policy.version && it.policy.supportsApply == supportsApply }
                    ?.token ?: Any()
                val waiting = pending?.takeIf { assignment?.token === token }
                val effectiveRetry = retryId ?: waiting?.retryId
                // A Retry arriving before the first SDK/Wi-Fi-ready apply joins that
                // claim, so it cannot displace the automatic delivery and lose both.
                val automatic = retryId == null || resetJournal || waiting?.automatic == true
                val retainedIpd = retainAcceptedIpd(policy, latestPolicy)
                val selected = Assignment(policy, token, cached)
                assignment = selected
                pending = null
                // An authoritative removal still stops live management if writing its marker failed.
                if (cached || (supportsApply && policy.version == null)) {
                    queue(
                        selected, effectiveRetry,
                        automatic = automatic,
                        retainedIpd = retainedIpd,
                    )
                }
                reconcile()
            }
        }
    }

    private fun queue(
        selected: Assignment,
        retryId: String?,
        automatic: Boolean = retryId == null,
        retainedIpd: JSONObject? = null,
    ) {
        val policy = selected.policy
        val releasing = policy.version == null || policy.values.optJSONObject("wifi") == null
        // An accepted policy belongs to the assignment, even if the delivering socket is replaced.
        val delivery = Delivery(
            policy, retryId,
            { assignment?.token === selected.token },
            automatic, ready = !releasing,
            retainedIpd = retainedIpd,
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

    private fun retainAcceptedIpd(policy: Policy, priorPolicy: Policy?): JSONObject? {
        val requested = policy.values.opt("ipd") as? Number ?: return null
        val priorRequested = priorPolicy?.values?.opt("ipd") as? Number ?: return null
        if (requested.toDouble() != priorRequested.toDouble()) return null
        val priorVersion = priorPolicy.version ?: return null
        val previous = journal?.latest?.takeIf {
            it.version == priorVersion && it.bootCount == boot
        } ?: return null
        val result = previous.results.optJSONObject("ipd") ?: return null
        if (result.optString("status") != "applied") return null
        val accepted = result.opt("value") as? Number ?: return null
        return result.takeIf { accepted.toDouble() == requested.toDouble() }
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
                val applyScalars = dependencies.scalarWriter()
                applyScalars != null &&
                    startAttempt(
                        policy, journal?.find(expected), null, applyScalars,
                        overwriteScalars = true, forceMapRecovery = true,
                    ) {
                        assignment?.token === selected.token
                    }
            } else false
            publishScreenState()
            onFinished(started)
        }
    }

    private fun canReapply(policy: Policy): Boolean =
        policy.supportsApply && policy.version != null && assignment?.cached == true &&
            pending == null && activeAttempt == null && boot >= 0 && sdkReady &&
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

    private fun publishScreenState() {
        val policy = latestPolicy ?: return
        val attempt = policy.version?.let { version ->
            reported?.takeIf { it.version == version } ?: journal?.find(version)
        }
        val waitingHomeAppPackage = if (policy.supportsApply) attempt?.waitingHomeAppPackage else null
        val applyStatus = when {
            !policy.supportsApply -> null
            policy.version == null -> ConfigurationApplyStatus.RELEASE_UNSAVED.takeIf { assignment?.cached == false }
            attempt?.status == "applying" -> ConfigurationApplyStatus.APPLYING
            pending?.policy === policy && pending?.isCurrent() == true -> ConfigurationApplyStatus.WAITING
            attempt == null -> ConfigurationApplyStatus.WAITING
            attempt.needsRetry -> ConfigurationApplyStatus.INCOMPLETE
            waitingHomeAppPackage != null -> ConfigurationApplyStatus.WAITING_FOR_HOME_APP
            else -> ConfigurationApplyStatus.APPLIED
        }
        val next = ConfigurationScreenState(
            policy.groupKnown, policy.groupName, policy.version, policy.deployedAtMillis, applyStatus,
            waitingHomeAppPackage, canReapply(policy),
        )
        if (screenState == next) return
        screenState = next
        notifyListeners(screenListeners)
    }

    private fun reconcile() {
        try {
            reconcilePending()
            reconcileHomeApp()
        } finally {
            publishScreenState()
        }
    }

    private fun reconcilePending() {
        if (activeAttempt != null) return

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
        // A Retry can claim an assignment whose first write failed, even if this
        // version was applied before a different package changed the device.
        val needsFreshAttempt = current.shouldApply(version, boot, null)
        val firstClaim = needsFreshAttempt && (delivery.automatic ||
            reported?.takeIf { it.version == version }?.status == "interrupted")
        val retryId = delivery.retryId?.takeIf {
            (firstClaim && it !in previous?.retryIds.orEmpty()) || current.shouldApply(version, boot, it)
        }
        val resumeMap = delivery.retryId == null && policy.lbeMapTransfer != null && previous?.retryMapOnDelivery == true
        val resumeInterrupted = previous?.resume != null
        val automatic = resumeInterrupted || resumeMap ||
            ((delivery.automatic || afterReboot) && needsFreshAttempt)
        if (retryId == null && !automatic) {
            pending = null
            if (previous != null && !needsFreshAttempt) {
                publish(previous)
            }
            return
        }
        if (boot < 0 || !sdkReady) return
        val applyScalars = dependencies.scalarWriter() ?: return
        if (wifiPolicy != null && !wifi.canAcceptPolicy()) return

        val bootReapply = (resumeInterrupted && previous?.bootReapply == true) ||
            (afterReboot && retryId == null &&
                (cachedVersionFromEarlierBoot == version || (previous != null && previous.bootCount != boot)))
        pending = null
        startAttempt(policy, previous, retryId, applyScalars,
            overwriteScalars = previous?.resume?.overwriteScalars == true || bootReapply || (retryId != null && afterReboot),
            checkExistingWifi = previous?.resume?.checkExistingWifi ?: (retryId == null && !bootReapply),
            bootReapply = bootReapply,
            retainedIpd = delivery.retainedIpd ?: if (resumeMap || resumeInterrupted) retainAcceptedIpd(policy, policy) else null,
            forceMapRecovery = retryId != null || resumeInterrupted,
            retainWifiForMapResume = resumeMap || resumeInterrupted,
            isCurrent = delivery.isCurrent)
    }

    private fun reconcileHomeApp() {
        if (!homeAppCheckPending || !sdkReady || pending != null || activeAttempt != null) return
        val policy = assignment?.takeIf { it.cached }?.policy ?: return
        val version = policy.version ?: return
        val attempt = journal?.latest ?: return
        if (attempt.version != version || attempt.bootCount != boot || attempt.status != "complete") return
        val packageName = (policy.values.opt("startupApp") as? String)?.takeIf { it.isNotEmpty() } ?: return
        if (latestReceivedVersion != version) return
        val applyScalars = dependencies.scalarWriter() ?: return
        homeAppCheckPending = false
        val previous = attempt.results.optJSONObject("startupApp") ?: return
        val previousStatus = previous.optString("status")
        val unavailable = when {
            previousStatus == "waiting" -> true
            previousStatus == "applied" -> {
                // Reconcile removal or disablement even if its package broadcast was missed.
                runCatching { dependencies.homeAppEnabled(packageName) != true }.getOrDefault(false)
            }
            previousStatus == "failed" && previous.optString("reason") == "APP_DISABLED" ->
                // The app may be disabled first and uninstalled later. Only a missing
                // package restores PICO default; re-enabling still waits for Retry.
                runCatching { dependencies.homeAppEnabled(packageName) == null }.getOrDefault(false)
            else -> false
        }
        if (!unavailable) return
        val outcome = try {
            applyScalars(JSONObject().put("startupApp", packageName), false)
                .optJSONObject("startupApp") ?: settingOutcome("unsupported", reason = "UNSUPPORTED")
        } catch (failure: Exception) {
            settingsFailureOutcome(failure, "WRITE_FAILED")
        }
        if (outcome.toString() == previous.toString()) return
        // The saved waiting result can be resumed if the process stops before this update is saved.
        finish(attempt.copy(results = JSONObject(attempt.results.toString()).put("startupApp", outcome)))
    }

    private fun startAttempt(
        policy: Policy,
        previous: ConfigurationAttempt?,
        retryId: String?,
        applyScalars: (JSONObject, Boolean) -> JSONObject,
        overwriteScalars: Boolean = false,
        checkExistingWifi: Boolean = false,
        bootReapply: Boolean = false,
        retainedIpd: JSONObject? = null,
        forceMapRecovery: Boolean = false,
        retainWifiForMapResume: Boolean = false,
        isCurrent: () -> Boolean,
    ): Boolean {
        val version = checkNotNull(policy.version)
        val wifiPolicy = policy.values.optJSONObject("wifi")
        // A same-revision retry need not touch settled Wi-Fi.
        val retainedWifi = previous?.takeIf {
            wifiPolicy != null && (retryId != null || retainWifiForMapResume) &&
                it.bootCount == boot && journal?.latest === it
        }?.results?.optJSONObject("wifi")?.takeIf { it.optString("status") == "applied" }
        val requestedLbeMode = policy.values.opt("lbeMode") == true
        val hasMapDescriptor = policy.values.has("lbeMap") && policy.values.opt("lbeMap") !== JSONObject.NULL
        val mapDescriptor = LbeMapDescriptor.parse(policy.values.opt("lbeMap"))
        val invalidMapPolicy = requestedLbeMode && mapDescriptor == null
        val mapRequested = requestedLbeMode && mapDescriptor != null
        val previousMap = journal?.latest?.takeIf { it.version.packageId == version.packageId }
            ?.results?.optJSONObject("lbeMap")
        val canReuseMap = !forceMapRecovery && previousMap?.optString("status") == "applied" &&
            LbeMapDescriptor.parse(previousMap.optJSONObject("value")) == mapDescriptor
        val results = JSONObject().apply {
            retainedWifi?.let { put("wifi", it) }
            retainedIpd?.let { put("ipd", it) }
        }
        val scalarValues = JSONObject().apply {
            for (key in policy.values.keys()) {
                if (key == "wifi" || key == "lbeMap" || (key == "lbeMode" && requestedLbeMode)) continue
                if (key == "ipd" && retainedIpd != null) continue
                put(key, policy.values.get(key))
            }
        }

        // Claim the pair and any explicit Retry before writing any settings.
        val attempt = ConfigurationAttempt(
            version, boot, retryIds = previous?.retryIds.orEmpty() + listOfNotNull(retryId),
            results = JSONObject().apply {
                retainedWifi?.let { put("wifi", it) }
                retainedIpd?.let { put("ipd", it) }
            },
            bootReapply = bootReapply,
        )
        try {
            if (!persist(attempt)) {
                updateAttempt(storageError(policy, attempt))
                return false
            }
            publishScreenState()

            if (invalidMapPolicy) {
                results.put("lbeMap", settingOutcome(
                    "failed",
                    reason = if (!hasMapDescriptor) "MAP_REQUIRED" else "INVALID_VALUE",
                ))
                results.put("lbeMode", settingOutcome("failed", reason = "MAP_REQUIRED"))
                applyScalarsAndWifi(
                    policy, version, attempt, results, scalarValues, applyScalars, overwriteScalars,
                    checkExistingWifi, isCurrent,
                )
                return true
            }

            if (mapRequested) {
                val descriptor = checkNotNull(mapDescriptor)
                val binding = checkNotNull(sdkBinding)
                val mapIsCurrent = { isCurrent() && latestReceivedVersion == version }
                val sdkIsCurrent = { sdkBinding === binding && binding.isCurrent() }
                activeAttempt = attempt
                dependencies.lbeMapWorker.execute {
                    var retryMapOnDelivery = false
                    val mapOutcome = try {
                        applyMap(descriptor, policy.lbeMapTransfer, version, canReuseMap, mapIsCurrent, sdkIsCurrent)
                    } catch (_: RetryableMapDownloadException) {
                        retryMapOnDelivery = true
                        LbeOutcome(settingOutcome("failed", reason = "MAP_DOWNLOAD_FAILED"))
                    } catch (_: StaleConfigurationException) {
                        null
                    } catch (_: Exception) {
                        LbeOutcome(settingOutcome("failed", reason = "MAP_DOWNLOAD_FAILED"))
                    }
                    worker.execute mapFinished@{
                        if (activeAttempt !== attempt) return@mapFinished
                        activeAttempt = null
                        try {
                            val currentWriter = if (sdkIsCurrent()) dependencies.scalarWriter() else null
                            if (mapOutcome == null || !mapIsCurrent() || currentWriter == null) {
                                if (pending == null && mapIsCurrent()) {
                                    pending = Delivery(policy, retryId, isCurrent, automatic = true,
                                        ready = true, retainedIpd = retainedIpd)
                                }
                                if (journal?.find(version) === attempt) {
                                    finish(
                                        attempt.copy(
                                            status = "interrupted", results = results,
                                            resume = ConfigurationResume(overwriteScalars, checkExistingWifi),
                                        ),
                                        publishResult = mapIsCurrent(),
                                    )
                                }
                            } else {
                                results.put("lbeMap", mapOutcome.map)
                                results.put("lbeMode", mapOutcome.mode)
                                applyScalarsAndWifi(
                                    policy, version, attempt.copy(retryMapOnDelivery = retryMapOnDelivery),
                                    results, scalarValues, currentWriter, overwriteScalars,
                                    checkExistingWifi, isCurrent,
                                )
                            }
                        } catch (failure: Exception) {
                            interruptApplyingAttempt(attempt, results, failure, publishResult = mapIsCurrent())
                        } finally {
                            reconcile()
                        }
                    }
                }
                return true
            }

            applyScalarsAndWifi(
                policy, version, attempt, results, scalarValues, applyScalars, overwriteScalars,
                checkExistingWifi, isCurrent,
            )
        } catch (failure: Exception) {
            activeAttempt = null
            journal?.find(version)?.takeIf { it.status == "applying" }?.let {
                interruptApplyingAttempt(it, results, failure)
            }
        }
        return true
    }

    private data class LbeOutcome(val map: JSONObject, val mode: JSONObject = settingOutcome("failed", reason = "MAP_REQUIRED"))

    private fun applyMap(
        descriptor: LbeMapDescriptor,
        transfer: LbeMapTransfer?,
        version: ConfigurationVersion,
        canReuse: Boolean,
        isCurrent: () -> Boolean,
        sdkIsCurrent: () -> Boolean,
    ): LbeOutcome {
        fun checkCurrent() {
            if (!isCurrent() || !sdkIsCurrent()) throw StaleConfigurationException()
        }
        fun activeMap(file: File): ActiveLbeMap {
            checkCurrent()
            val state = dependencies.activeLbeMap(file.absolutePath)
            checkCurrent()
            return state
        }
        fun verified() = LbeOutcome(
            settingOutcome("applied", descriptor.toJson(), "MAP_ACTIVE_VERIFIED"),
            settingOutcome("applied", true),
        )

        checkCurrent()
        // Acquisition verifies a completed cache entry itself. Reusing that one result
        // avoids reading a corrupt large ZIP twice before deciding to download again.
        val acquisition = dependencies.acquireLbeMap(descriptor, transfer, version, isCurrent)
        checkCurrent()
        val file = acquisition.file
        if (canReuse && acquisition.verifiedCacheHit && activeMap(file) == ActiveLbeMap.MATCH) return verified()
        when (dependencies.importLbeMap(file.absolutePath)) {
            LbeMapImportResult.FAILED -> return LbeOutcome(settingOutcome("failed", reason = "MAP_IMPORT_FAILED"))
            LbeMapImportResult.CALLBACK_TIMEOUT -> return LbeOutcome(settingOutcome("failed", reason = "CALLBACK_TIMEOUT"))
            LbeMapImportResult.ACCEPTED -> Unit
        }

        checkCurrent()
        // A large transfer or import may span a TobService reconnect. Resolve the
        // writer after that wait and use the same live binding for LBE On and scalars.
        val applyCurrentScalars = dependencies.scalarWriter() ?: throw StaleConfigurationException()
        val mode = try {
            applyCurrentScalars(JSONObject().put("lbeMode", true), false).optJSONObject("lbeMode")
                ?: settingOutcome("unsupported", reason = "UNSUPPORTED")
        } catch (error: Exception) {
            settingsFailureOutcome(error, "WRITE_FAILED")
        }
        checkCurrent()
        if (mode.optString("status") != "applied" || mode.opt("value") != true) {
            return LbeOutcome(settingOutcome("failed", descriptor.toJson(), "MAP_MODE_NOT_APPLIED"), mode)
        }
        val state = activeMap(file)
        val reason = when (state) {
            ActiveLbeMap.MATCH -> return verified()
            ActiveLbeMap.DIFFERENT -> "MAP_ACTIVE_MISMATCH"
            ActiveLbeMap.MODE_OFF, ActiveLbeMap.UNKNOWN -> "MAP_VERIFICATION_UNAVAILABLE"
        }
        val modeResult = if (state == ActiveLbeMap.MODE_OFF) settingOutcome("failed", false, "READBACK_MISMATCH")
            else settingOutcome("failed", mode.opt("value"), reason)
        return LbeOutcome(settingOutcome("failed", descriptor.toJson(), reason), modeResult)
    }

    private fun applyScalarsAndWifi(
        policy: Policy,
        version: ConfigurationVersion,
        attempt: ConfigurationAttempt,
        results: JSONObject,
        scalarValues: JSONObject,
        applyScalars: (JSONObject, Boolean) -> JSONObject,
        overwriteScalars: Boolean,
        checkExistingWifi: Boolean,
        isCurrent: () -> Boolean,
    ) {
        val wifiPolicy = policy.values.optJSONObject("wifi")
        if (scalarValues.length() > 0) {
            if (scalarValues.has("startupApp")) homeAppCheckPending = false
            val scalarResults = applyScalars(scalarValues, overwriteScalars)
            for (key in scalarValues.keys()) {
                results.put(key, scalarResults.optJSONObject(key) ?: settingOutcome("unsupported", reason = "UNSUPPORTED"))
            }
        }

        if (wifiPolicy == null || results.optJSONObject("wifi")?.optString("status") == "applied") {
            finish(attempt.copy(status = "complete", results = results))
            return
        }

        val checkpoint = attempt.copy(results = results)
        if (!persist(checkpoint)) {
            results.put("wifi", settingOutcome("failed", reason = "STORAGE_ERROR"))
            updateAttempt(attempt.copy(status = "interrupted", results = results))
            return
        }
        activeAttempt = checkpoint
        wifi.applyManagedPolicy(version.revision, wifiPolicy, checkExistingWifi, isCurrent, onFinished = { result ->
            worker.execute {
                if (activeAttempt !== checkpoint) return@execute
                activeAttempt = null
                try {
                    if (isCurrent()) {
                        finishWifi(checkpoint, result)
                    } else if (journal?.find(version) === checkpoint) {
                        finish(checkpoint.copy(
                            status = "interrupted", resume = ConfigurationResume(overwriteScalars, checkExistingWifi),
                        ), publishResult = false)
                    }
                } catch (failure: Exception) {
                    interruptApplyingAttempt(checkpoint, results, failure, publishResult = isCurrent())
                } finally {
                    reconcile()
                }
            }
        })
    }

    private fun interruptApplyingAttempt(
        attempt: ConfigurationAttempt,
        results: JSONObject,
        failure: Exception,
        publishResult: Boolean = true,
    ) {
        dependencies.logJournalError("Package application interrupted (${failure.javaClass.simpleName})")
        if (journal?.find(attempt.version) !== attempt || attempt.status != "applying") return
        finish(attempt.copy(status = "interrupted", results = results), publishResult)
    }

    private fun storageError(policy: Policy, previous: ConfigurationAttempt?): ConfigurationAttempt {
        val results = JSONObject()
        val retainedWifi = previous?.takeIf { it.version == policy.version && it.bootCount == boot }
            ?.results?.optJSONObject("wifi")?.takeIf { it.optString("status") == "applied" }
        for (key in policy.values.keys()) {
            val result = if (key == "wifi" && retainedWifi != null) retainedWifi
                else settingOutcome("failed", reason = "STORAGE_ERROR")
            results.put(key, result)
        }
        return ConfigurationAttempt(
            checkNotNull(policy.version), boot, status = "interrupted", results = results,
            retryIds = previous?.retryIds.orEmpty(),
        )
    }

    private fun finishWifi(
        attempt: ConfigurationAttempt,
        result: WifiManualResult,
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
        ))
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

    private fun persist(next: ConfigurationAttempt, publishResult: Boolean = true): Boolean {
        if (!saveJournal(checkNotNull(journal).withAttempt(next))) return false
        if (publishResult) publish(next)
        val token = assignment?.token
        if (publishResult && token != null && next.status == "complete" && !next.needsRetry && next.waitingHomeAppPackage == null) {
            try {
                dependencies.onApplied(next.bootReapply) { assignment?.token === token && reported === next }
            } catch (_: Exception) {
                dependencies.logJournalError("Could not show package application notice")
            }
        }
        return true
    }

    private fun finish(next: ConfigurationAttempt, publishResult: Boolean = true) {
        // The durable claim still reads as interrupted on restart if this save fails.
        if (!persist(next, publishResult)) updateAttempt(next.copy(status = "interrupted"), publishResult)
    }

    private fun updateAttempt(next: ConfigurationAttempt, publishResult: Boolean = true) {
        journal = checkNotNull(journal).withAttempt(next)
        if (publishResult) publish(next)
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

private fun productionDependencies(context: Context): ConfigurationPackageDependencies {
    val store = ConfigurationPackageStore(
        File(context.noBackupFilesDir, "configuration-packages.json"),
        syncDirectory = ::syncDirectory,
    )
    val lbeMapStore = LbeMapArtifactStore()
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
        homeAppEnabled = { packageName -> picoHomeAppEnabled(context, packageName) },
        logJournalError = { Log.e("ConfigurationPackages", it) },
        onApplied = appliedNotice::show,
        lbeMapWorker = Executors.newSingleThreadExecutor(),
        acquireLbeMap = { descriptor, transfer, version, isCurrent ->
            lbeMapStore.acquire(descriptor, transfer, version, isCurrent)
        },
        importLbeMap = { path ->
            (ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy)
                ?.let { PicoDeviceSettings(context, it).importLbeMap(path) } ?: LbeMapImportResult.FAILED
        },
        activeLbeMap = { path ->
            (ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy)
                ?.let { PicoDeviceSettings(context, it).activeLbeMap(path) } ?: ActiveLbeMap.UNKNOWN
        },
        cancelLbeMapTransfer = lbeMapStore::cancelLbeMapTransfer,
    )
}
