package com.styly.mdmclient

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val CONFIGURATION_PACKAGES_CAPABILITY = "configuration_packages_v1"

// PUI 5.15.7 initializes the sleep policy about 28 seconds into boot, after
// BOOT_COMPLETED and the TobService bind. Wait once, before claiming any write.
private const val PICO_BOOT_SETTLE_MS = 40_000L

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
) {
    val needsRetry: Boolean
        get() = status == "interrupted" || (status == "complete" && results.keys().asSequence().any {
            results.optJSONObject(it)?.optString("status") != "applied"
        })

    fun report(): JSONObject = version.toJson().put("status", status).put("results", results)

    fun saved(): JSONObject = report()
        .put("boot_count", bootCount)
        .put("retry_ids", JSONArray(retryIds))

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
        return previous == null || (!previous.needsRetry && previous.bootCount != bootCount)
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

/** The process owns one apply worker, independent of WebSocket and Service lifetimes. */
internal class ConfigurationPackageController(
    private val context: Context,
    private val wifi: WifiSettingsController,
) {
    private class Policy(
        val version: ConfigurationVersion?,
        val values: JSONObject,
        val retryId: String?,
        val isCurrent: () -> Boolean,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val preferences = context.getSharedPreferences("configuration-packages", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var journal = runCatching {
        preferences.getString("journal", null)?.let(ConfigurationJournal::restore) ?: ConfigurationJournal()
    }.onFailure {
        Log.e("ConfigurationPackages", "Could not read package application journal")
    }.getOrNull()
    @Volatile private var reported: ConfigurationAttempt? = null
    private var pending: Policy? = null
    private var sdkReady = false
    private var wifiOperationId: String? = null

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }

    init {
        wifi.addStateListener { worker.execute(::reconcile) }
    }

    fun snapshot(): JSONObject? = reported?.report()

    fun onSdkReady() {
        val delay = (PICO_BOOT_SETTLE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        worker.schedule({
            sdkReady = true
            reconcile()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun receive(payload: JSONObject, isCurrent: () -> Boolean) {
        val values = payload.getJSONObject("settings")
        val version = if (payload.opt("package_id") === JSONObject.NULL && payload.opt("revision") === JSONObject.NULL) {
            null
        } else {
            ConfigurationVersion.parse(payload) ?: return
        }
        val retryId = payload.opt("retry_id") as? String
        if (payload.has("retry_id") &&
            (retryId == null || version == null || !WifiSettingsProtocol.isValidUuid(retryId))
        ) return
        worker.execute {
            if (!isCurrent()) return@execute
            // A routine repeat of the exact policy should not cancel a queued Retry.
            val queuedRetry = pending?.takeIf { it.version == version && it.isCurrent() }?.retryId
            pending = Policy(version, values, retryId ?: queuedRetry, isCurrent)
            reconcile()
        }
    }

    private fun reconcile() {
        if (!finishWifiIfReady()) return

        val policy = pending ?: return
        if (!policy.isCurrent()) {
            pending = null
            return
        }
        val version = policy.version
        if (version == null) {
            pending = null
            reported = null
            wifi.stopManagingPolicy()
            return
        }

        val networks = policy.values.optJSONArray("wifi")
        // Releasing Wi-Fi ownership requires no settings writes or SDK bind.
        if (networks == null) wifi.stopManagingPolicy(version.revision)
        val current = journal
        if (current == null) {
            pending = null
            return
        }
        val previous = current.find(version)
        if (!current.shouldApply(version, boot, policy.retryId)) {
            pending = null
            if (previous?.needsRetry == true) {
                publish(previous)
            } else if (previous != null && reported?.version != version) {
                publish(previous.copy(status = "already_attempted", results = JSONObject()))
            }
            return
        }
        if (boot < 0 || !sdkReady) return
        val service = ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy ?: return
        if (networks != null && !wifi.readyForPolicy()) return

        pending = null
        // Claim the pair and any explicit Retry before writing. Recovery still owns
        // the Wi-Fi journal if the process dies during a mutation.
        val attempt = ConfigurationAttempt(
            version, boot, retryIds = previous?.retryIds.orEmpty() + listOfNotNull(policy.retryId),
        )
        if (!persist(attempt)) return

        val results = PicoDeviceSettings(context, service).apply(policy.values)
        for (key in policy.values.keys()) {
            if (key != "wifi" && !results.has(key)) {
                results.put(key, settingOutcome("unsupported", reason = "UNSUPPORTED"))
            }
        }
        if (networks == null) {
            finish(attempt.copy(status = "complete", results = results))
        } else {
            updateAttempt(attempt.copy(results = results))
            val operationId = UUID.randomUUID().toString()
            wifiOperationId = operationId
            wifi.applyPolicy(JSONObject()
                .put("operation_id", operationId)
                .put("revision", version.revision)
                .put("mode", "managed")
                .put("networks", networks))
        }
    }

    private fun finishWifiIfReady(): Boolean {
        val operationId = wifiOperationId ?: return true
        val progress = wifi.policyProgress(operationId)
        val result = progress.result
        val outcome = when {
            result != null -> {
                val status = when {
                    result.status == "success" -> "applied"
                    result.reason == WifiReason.UNSUPPORTED -> "unsupported"
                    else -> "failed"
                }
                settingOutcome(status, reason = result.reason.takeUnless { it == WifiReason.NONE }?.wireValue)
                    .put("rolled_back", result.rolledBack)
            }
            progress.storageError -> settingOutcome("failed", reason = "STORAGE_ERROR")
            else -> return false
        }
        val current = checkNotNull(journal?.latest)
        val status = if (progress.settled) "complete" else "applying"
        if (current.status != status || current.results.optJSONObject("wifi")?.toString() != outcome.toString()) {
            val results = JSONObject(current.results.toString()).put("wifi", outcome)
            val next = current.copy(status = status, results = results)
            if (progress.settled) finish(next) else updateAttempt(next)
        }
        if (progress.settled) wifiOperationId = null
        return progress.settled
    }

    private fun persist(next: ConfigurationAttempt): Boolean {
        val updated = checkNotNull(journal).withAttempt(next)
        if (!preferences.edit().putString("journal", updated.encode()).commit()) {
            Log.e("ConfigurationPackages", "Could not persist package application journal")
            return false
        }
        journal = updated
        publish(next)
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
