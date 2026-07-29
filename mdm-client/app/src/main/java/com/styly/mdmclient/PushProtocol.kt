package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object PushProtocol {
    const val CAP_PUSH_JOB_ID_V1 = "push_job_id_v1"
    const val ATTEMPT_V1 = 1

    data class Command(
        val jobId: String?,
        val attempt: Int,
        val artifactId: String?,
        val artifactUrl: String,
        val artifactSize: Long?,
        val artifactSha256: String?,
        val bundleFilename: String,
        val destPath: String,
        val deleteExtras: Boolean,
    ) {
        val isJobV1: Boolean get() = jobId != null
        val identity: String get() = if (jobId != null) "$jobId:$attempt" else "legacy"

        fun sameExecution(other: Command): Boolean {
            return jobId == other.jobId && attempt == other.attempt &&
                artifactId == other.artifactId && artifactUrl == other.artifactUrl &&
                artifactSize == other.artifactSize && artifactSha256 == other.artifactSha256 &&
                destPath == other.destPath && deleteExtras == other.deleteExtras
        }

        fun toJson(): JSONObject = JSONObject().apply {
            if (jobId != null) put("job_id", jobId)
            put("attempt", attempt)
            if (artifactId != null) put("artifact_id", artifactId)
            put("artifact_url", artifactUrl)
            if (artifactSize != null) put("artifact_size", artifactSize)
            if (artifactSha256 != null) put("artifact_sha256", artifactSha256)
            put("bundle_filename", bundleFilename)
            put("dest_path", destPath)
            put("delete_extras", deleteExtras)
        }
    }

    data class Active(
        val command: Command,
        val phase: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("command", command.toJson())
            put("phase", phase)
        }
    }

    data class Result(
        val jobId: String?,
        val attempt: Int,
        val status: String,
        val destPath: String,
        val added: Int,
        val updated: Int,
        val deleted: Int,
        val error: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", "PUSH_FILES_RESULT")
            if (jobId != null) {
                put("job_id", jobId)
                put("attempt", attempt)
            }
            put("status", status)
            put("dest_path", destPath)
            put("added", added)
            put("updated", updated)
            put("deleted", deleted)
            if (error.isNotEmpty()) put("error", error)
        }
    }

    /** Durable terminal receipt retains the command fingerprint used for dedupe. */
    data class Receipt(
        val command: Command,
        val result: Result,
    ) {
        fun toStateJson(): JSONObject = JSONObject().apply {
            put("command", command.toJson())
            put("result", result.toJson())
        }
    }

    data class State(
        val active: Active?,
        val pendingResults: List<Receipt>,
        val completedReceipts: List<Receipt>,
    )

    fun parseCommand(payload: JSONObject): Command {
        val jobId = payload.optString("job_id", "").ifBlank { null }
        if (jobId != null) {
            try {
                UUID.fromString(jobId)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("malformed_command: job_id must be a UUID")
            }
        }
        val attempt = if (jobId == null) ATTEMPT_V1 else payload.optInt("attempt", -1)
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val artifactId = payload.optString("artifact_id", "").ifBlank { null }
        if (jobId != null && artifactId == null) {
            throw IllegalArgumentException("malformed_command: artifact_id is required")
        }
        if (artifactId != null) {
            try {
                UUID.fromString(artifactId)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("malformed_command: artifact_id must be a UUID")
            }
        }
        val artifactUrl = payload.optString(
            if (payload.has("artifact_url")) "artifact_url" else "bundle_url",
            ""
        )
        if (artifactUrl.isBlank()) throw IllegalArgumentException("malformed_command: artifact_url is required")
        val destPath = payload.optString("dest_path", "").trim()
        if (destPath.isBlank()) throw IllegalArgumentException("invalid_destination: destination is required")
        val artifactSize = if (payload.has("artifact_size")) payload.optLong("artifact_size", -1L) else null
        if (artifactSize != null && artifactSize < 0) {
            throw IllegalArgumentException("malformed_command: artifact_size must be non-negative")
        }
        return Command(
            jobId = jobId,
            attempt = attempt,
            artifactId = artifactId,
            artifactUrl = artifactUrl,
            artifactSize = artifactSize,
            artifactSha256 = payload.optString("artifact_sha256", "").ifBlank { null },
            bundleFilename = payload.optString("bundle_filename", "bundle.zip"),
            destPath = destPath,
            deleteExtras = payload.optBoolean("delete_extras", false),
        )
    }

    fun commandFromJson(json: JSONObject): Command = Command(
        jobId = json.optString("job_id", "").ifBlank { null },
        attempt = json.optInt("attempt", ATTEMPT_V1),
        artifactId = json.optString("artifact_id", "").ifBlank { null },
        artifactUrl = json.getString("artifact_url"),
        artifactSize = if (json.has("artifact_size")) json.getLong("artifact_size") else null,
        artifactSha256 = json.optString("artifact_sha256", "").ifBlank { null },
        bundleFilename = json.optString("bundle_filename", "bundle.zip"),
        destPath = json.getString("dest_path"),
        deleteExtras = json.optBoolean("delete_extras", false),
    )

    fun resultFromJson(json: JSONObject): Result = Result(
        jobId = json.optString("job_id", "").ifBlank { null },
        attempt = json.optInt("attempt", ATTEMPT_V1),
        status = json.getString("status"),
        destPath = json.getString("dest_path"),
        added = json.optInt("added", 0),
        updated = json.optInt("updated", 0),
        deleted = json.optInt("deleted", 0),
        error = json.optString("error", ""),
    )

    fun stateToJson(state: State): JSONObject = JSONObject().apply {
        put("active", state.active?.toJson() ?: JSONObject.NULL)
        put("pending_results", JSONArray().apply { state.pendingResults.forEach { put(it.toStateJson()) } })
        put("completed_receipts", JSONArray().apply { state.completedReceipts.forEach { put(it.toStateJson()) } })
    }

    fun stateFromJson(json: JSONObject): State {
        val activeJson = json.optJSONObject("active")
        val active = activeJson?.let {
            Active(commandFromJson(it.getJSONObject("command")), it.getString("phase"))
        }
        fun receiptList(name: String): List<Receipt> {
            val array = json.optJSONArray(name) ?: JSONArray()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val commandJson = item.optJSONObject("command") ?: continue
                    val resultJson = item.optJSONObject("result") ?: continue
                    add(Receipt(commandFromJson(commandJson), resultFromJson(resultJson)))
                }
            }
        }
        return State(active, receiptList("pending_results"), receiptList("completed_receipts"))
    }
}
