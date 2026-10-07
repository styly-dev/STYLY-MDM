package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Receipts may be the only evidence that a command was already applied. */
internal class InvalidPushReceiptsException(name: String, cause: Exception) :
    IllegalArgumentException("Invalid durable receipt collection: $name", cause)

object PushProtocol {
    const val CAP_PUSH_JOB_ID_V1 = "push_job_id_v1"
    const val CAP_PUSH_RESUME_V1 = "push_resume_v1"
    const val ATTEMPT_V1 = 1
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_VALIDATING = "validating"
    const val PHASE_APPLYING = "applying"

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

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
        /** Server revision of the immutable job assignment. Required for job-v1 wire commands. */
        val revision: Long = 0L,
        /** A strong HTTP ETag for the immutable artifact. Required for job-v1 wire commands. */
        val artifactEtag: String? = null,
    ) {
        val isJobV1: Boolean get() = jobId != null
        val identity: String get() = if (jobId != null) "$jobId:$attempt" else "legacy"

        /** Exact execution fingerprint, excluding the replaceable artifact URL. */
        fun sameExecution(other: Command): Boolean =
            copy(artifactUrl = "") == other.copy(artifactUrl = "")

        fun toJson(): JSONObject = JSONObject().apply {
            if (jobId != null) put("job_id", jobId)
            put("attempt", attempt)
            if (artifactId != null) put("artifact_id", artifactId)
            put("artifact_url", artifactUrl)
            if (artifactSize != null) put("artifact_size", artifactSize)
            if (artifactSha256 != null) put("artifact_sha256", artifactSha256)
            if (jobId != null) put("revision", revision)
            if (artifactEtag != null) put("artifact_etag", artifactEtag)
            put("bundle_filename", bundleFilename)
            put("dest_path", destPath)
            put("delete_extras", deleteExtras)
        }
    }

    data class Active(
        val command: Command,
        val phase: String,
        /** True when the process restarted while the durable worker was active. */
        val interrupted: Boolean = false,
        /** First recovery time; bounds how long stale work can fence the device. */
        val interruptedAt: Long? = null,
        /** Why the worker paused; retained only while [interrupted] is true. */
        val interruptionReason: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("command", command.toJson())
            put("phase", phase)
            put("interrupted", interrupted)
            if (interruptedAt != null) put("interrupted_at", interruptedAt)
            if (interruptionReason != null) put("interruption_reason", interruptionReason)
        }
    }

    data class Result(
        val jobId: String?,
        val attempt: Int,
        val status: String,
        val destPath: String,
        val added: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        val failureCode: String? = null,
        val detail: String? = null,
        val completedAt: Long = System.currentTimeMillis(),
    ) {
        init {
            require(status == "success" || status == "fail")
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", "PUSH_FILES_RESULT")
            if (jobId != null) {
                put("job_id", jobId)
                put("attempt", attempt)
            }
            put("status", status)
            put("dest_path", destPath)
            if (status == "success") {
                put("added", added)
                put("updated", updated)
                put("deleted", deleted)
            } else {
                if (!failureCode.isNullOrBlank()) put("failure_code", failureCode)
                if (!detail.isNullOrBlank()) {
                    put("detail", detail)
                    // Old servers display `error`; keep it only on identity-less fallback.
                    if (jobId == null) put("error", detail)
                }
            }
        }
    }

    /** Durable terminal receipt retains the original fingerprint and completion age. */
    data class Receipt(val command: Command, val result: Result) {
        fun toStateJson(): JSONObject = JSONObject().apply {
            put("command", command.toJson())
            put("result", resultToStateJson(result))
        }
    }

    data class State(
        val active: Active?,
        val pendingResults: List<Receipt>,
        val completedReceipts: List<Receipt>,
    )

    data class ResultAck(
        val jobId: String,
        val attempt: Int,
        val accepted: Boolean,
        val retryable: Boolean,
        val reason: String?,
    )

    data class ReconcileIdentity(
        val jobId: String,
        val attempt: Int,
        val artifactId: String?,
    )

    /**
     * Parses a live command. [durableState] accepts records persisted by the Issue #91
     * client, which have no dispatch revision or artifact ETag.
     */
    fun parseCommand(payload: JSONObject, durableState: Boolean = false): Command {
        val jobId = optionalString(payload, "job_id")?.also {
            if (it.isBlank()) throw malformed("job_id must not be blank")
            requireUuidV4(it, "job_id")
        }
        val attempt = if (jobId == null) ATTEMPT_V1 else strictInt(payload, "attempt")
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val artifactId = optionalString(payload, "artifact_id")?.also {
            if (it.isBlank()) throw malformed("artifact_id must not be blank")
            requireUuidV4(it, "artifact_id")
        }
        if (jobId != null && artifactId == null) {
            throw malformed("artifact_id is required")
        }

        val urlKey = when {
            payload.has("artifact_url") -> "artifact_url"
            jobId != null -> throw malformed("artifact_url is required")
            else -> "bundle_url"
        }
        val artifactUrl = requiredString(payload, urlKey)
        validateArtifactUrl(artifactUrl)
        val destPath = requiredString(payload, "dest_path").trim()
        if (destPath.isBlank()) {
            throw IllegalArgumentException("invalid_destination: destination is required")
        }
        val artifactSize = optionalLong(payload, "artifact_size")
        if (artifactSize != null && artifactSize < 0L) {
            throw malformed("artifact_size must be non-negative")
        }
        val artifactSha256 = optionalString(payload, "artifact_sha256")?.ifBlank { null }
        if (artifactSha256 != null && !SHA256.matches(artifactSha256)) {
            throw malformed("artifact_sha256 must be 64 hexadecimal characters")
        }
        if (jobId != null && (artifactSize == null || artifactSha256 == null)) {
            throw malformed("job-v1 artifact size and SHA-256 are required")
        }
        val revision = if (jobId == null) 0L else optionalLong(payload, "revision") ?: 0L
        if (revision < 0L) throw malformed("revision must be non-negative")
        val artifactEtag = optionalString(payload, "artifact_etag")?.also { validateStrongEtag(it) }
        // Push runs only between a server and client of the same version, so a live
        // job-v1 command always carries both. Only durable state written by the Issue
        // #91 client lacks them; recovery closes that work instead of resuming it.
        if (jobId != null && !durableState && (revision == 0L || artifactEtag == null)) {
            throw malformed("job-v1 revision and artifact_etag are required")
        }
        val bundleFilename = optionalString(payload, "bundle_filename")
            ?.takeIf { it.isNotBlank() } ?: "bundle.zip"
        return Command(
            jobId = jobId,
            attempt = attempt,
            artifactId = artifactId,
            artifactUrl = artifactUrl,
            artifactSize = artifactSize,
            artifactSha256 = artifactSha256?.lowercase(),
            bundleFilename = bundleFilename,
            destPath = destPath,
            deleteExtras = strictBoolean(payload, "delete_extras", false),
            revision = revision,
            artifactEtag = artifactEtag,
        )
    }

    fun parseResultAck(payload: JSONObject): ResultAck {
        val jobId = requiredString(payload, "job_id")
        requireUuidV4(jobId, "job_id")
        val attempt = strictInt(payload, "attempt")
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val accepted = strictBoolean(payload, "accepted", false)
        val retryable = strictBoolean(payload, "retryable", !accepted)
        val reason = optionalString(payload, "reason")?.ifBlank { null }
        return ResultAck(jobId, attempt, accepted, retryable, reason)
    }

    fun parseReconcileIdentity(payload: JSONObject): ReconcileIdentity {
        val jobId = requiredString(payload, "job_id")
        requireUuidV4(jobId, "job_id")
        val attempt = strictInt(payload, "attempt")
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val artifactId = optionalString(payload, "artifact_id")?.also {
            if (it.isBlank()) throw malformed("artifact_id must not be blank")
            requireUuidV4(it, "artifact_id")
        }
        return ReconcileIdentity(jobId, attempt, artifactId)
    }

    fun resultFromJson(json: JSONObject): Result = Result(
        jobId = json.optString("job_id", "").ifBlank { null },
        attempt = json.optInt("attempt", ATTEMPT_V1),
        status = json.getString("status"),
        destPath = json.getString("dest_path"),
        added = json.optInt("added", 0),
        updated = json.optInt("updated", 0),
        deleted = json.optInt("deleted", 0),
        failureCode = json.optString("failure_code", "").ifBlank { null },
        detail = json.optString("detail", json.optString("error", "")).ifBlank { null },
        completedAt = json.optLong("completed_at", 0L),
    )

    fun stateToJson(state: State): JSONObject = JSONObject().apply {
        put("active", state.active?.toJson() ?: JSONObject.NULL)
        put("pending_results", JSONArray().apply {
            state.pendingResults.forEach { put(it.toStateJson()) }
        })
        put("completed_receipts", JSONArray().apply {
            state.completedReceipts.forEach { put(it.toStateJson()) }
        })
    }

    fun stateFromJson(json: JSONObject): State {
        if (
            json.has("active") && !json.isNull("active") &&
            json.optJSONObject("active") == null
        ) {
            throw IllegalArgumentException("malformed durable active state")
        }
        val active = json.optJSONObject("active")?.let {
            Active(
                parseCommand(it.getJSONObject("command"), durableState = true),
                it.getString("phase"),
                it.optBoolean("interrupted", false),
                it.optLong("interrupted_at", 0L).takeIf { value -> value > 0L },
                optionalString(it, "interruption_reason")?.ifBlank { null },
            )
        }
        fun receipts(name: String): List<Receipt> = try {
            val parsed = json.optJSONArray(name)
            if (
                json.has(name) && !json.isNull(name) && parsed == null
            ) {
                throw IllegalArgumentException("malformed durable receipt collection: $name")
            }
            val array = parsed ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    if (item == null) {
                        throw IllegalArgumentException("malformed durable receipt")
                    }
                    val command = item.optJSONObject("command")
                    if (command == null) {
                        throw IllegalArgumentException("malformed durable receipt command")
                    }
                    val result = item.optJSONObject("result")
                    if (result == null) {
                        throw IllegalArgumentException("malformed durable receipt result")
                    }
                    add(Receipt(parseCommand(command, durableState = true), resultFromJson(result)))
                }
            }
        } catch (error: Exception) {
            // Never silently skip a receipt: that could allow an already-applied
            // command to execute again or falsely report it absent to the server.
            throw InvalidPushReceiptsException(name, error)
        }
        return State(active, receipts("pending_results"), receipts("completed_receipts"))
    }

    private fun resultToStateJson(result: Result): JSONObject = result.toJson().apply {
        put("completed_at", result.completedAt)
    }

    private fun malformed(detail: String) =
        IllegalArgumentException("malformed_command: $detail")

    private fun optionalString(payload: JSONObject, field: String): String? {
        if (!payload.has(field) || payload.isNull(field)) return null
        return payload.opt(field) as? String
            ?: throw malformed("$field must be a string")
    }

    internal fun requiredString(payload: JSONObject, field: String): String =
        optionalString(payload, field) ?: throw malformed("$field is required")

    internal fun strictInt(payload: JSONObject, field: String): Int {
        val longValue = when (val value = payload.opt(field)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw malformed("$field must be a JSON integer")
        }
        if (longValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw malformed("$field is outside the supported integer range")
        }
        return longValue.toInt()
    }

    internal fun isStrongEtag(value: String): Boolean =
        value.isNotBlank() && !value.startsWith("W/") &&
            value.startsWith("\"") && value.endsWith("\"") && value.length >= 2

    private fun validateStrongEtag(value: String) {
        if (!isStrongEtag(value)) {
            throw malformed("artifact_etag must be a strong quoted ETag")
        }
    }

    internal fun optionalLong(payload: JSONObject, field: String): Long? {
        if (!payload.has(field) || payload.isNull(field)) return null
        return when (val value = payload.opt(field)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw malformed("$field must be a JSON integer")
        }
    }

    private fun strictBoolean(payload: JSONObject, field: String, default: Boolean): Boolean {
        if (!payload.has(field) || payload.isNull(field)) return default
        return payload.opt(field) as? Boolean
            ?: throw malformed("$field must be a JSON boolean")
    }

    private fun requireUuidV4(value: String, field: String): String {
        val parsed = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("malformed_command: $field must be a UUIDv4")
        }
        if (parsed.version() != 4) {
            throw IllegalArgumentException("malformed_command: $field must be a UUIDv4")
        }
        return parsed.toString()
    }

    private fun validateArtifactUrl(value: String) {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("malformed_command: artifact_url is invalid")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("malformed_command: artifact_url must be an absolute HTTP(S) URL")
        }
    }
}
