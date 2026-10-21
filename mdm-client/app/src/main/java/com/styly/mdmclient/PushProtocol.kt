package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Receipts may be the only evidence that a command was already applied. */
internal class InvalidPushReceiptsException(name: String, cause: Exception) :
    IllegalArgumentException("Invalid durable receipt collection: $name", cause)

internal class InvalidPushActiveException(cause: Exception) :
    IllegalArgumentException("Invalid durable active command", cause)

internal class UnsupportedPushStateVersionException(version: String) :
    IllegalArgumentException("Unsupported durable Push/Sync state version: $version")

object PushProtocol {
    const val DURABLE_STATE_VERSION = 1
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
        fun sameExecution(other: Command): Boolean = executionFingerprint() == other.executionFingerprint()

        private fun executionFingerprint() = ExecutionFingerprint(
            jobId = jobId,
            attempt = attempt,
            artifactId = artifactId,
            artifactSize = artifactSize,
            artifactSha256 = artifactSha256,
            bundleFilename = bundleFilename,
            destPath = destPath,
            deleteExtras = deleteExtras,
            revision = revision,
            artifactEtag = artifactEtag,
        )

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

    /** Fields that authorize one execution; artifactUrl is only a replaceable locator. */
    private data class ExecutionFingerprint(
        val jobId: String?,
        val attempt: Int,
        val artifactId: String?,
        val artifactSize: Long?,
        val artifactSha256: String?,
        val bundleFilename: String,
        val destPath: String,
        val deleteExtras: Boolean,
        val revision: Long,
        val artifactEtag: String?,
    )

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

    /** Parses a command received from the server using the current wire contract. */
    fun parseWireCommand(payload: JSONObject): Command =
        validateWireCommand(decodeCommandFields(payload))

    /** Parses a command stored by this client, including the pre-revision Issue #91 form. */
    fun parseStoredCommand(payload: JSONObject): Command =
        validateStoredCommand(decodeCommandFields(payload))

    /** Typed JSON fields only; each boundary owns its own semantic validation rules. */
    private data class CommandFields(
        val jobId: String?,
        val attemptValue: Any?,
        val artifactId: String?,
        val artifactUrl: String?,
        val hasArtifactUrl: Boolean,
        val bundleUrl: String?,
        val artifactSize: Long?,
        val artifactSha256: String?,
        val revisionValue: Any?,
        val artifactEtag: String?,
        val bundleFilename: String?,
        val destPath: String,
        val deleteExtras: Boolean,
    )

    private fun decodeCommandFields(payload: JSONObject): CommandFields {
        val jobId = optionalString(payload, "job_id")
        val hasArtifactUrl = payload.has("artifact_url")
        return CommandFields(
            jobId = jobId,
            attemptValue = payload.opt("attempt"),
            artifactId = optionalString(payload, "artifact_id"),
            artifactUrl = if (hasArtifactUrl) optionalString(payload, "artifact_url") else null,
            hasArtifactUrl = hasArtifactUrl,
            bundleUrl = if (!hasArtifactUrl && jobId == null) {
                optionalString(payload, "bundle_url")
            } else {
                null
            },
            artifactSize = optionalLong(payload, "artifact_size"),
            artifactSha256 = optionalString(payload, "artifact_sha256"),
            revisionValue = payload.opt("revision"),
            artifactEtag = optionalString(payload, "artifact_etag"),
            bundleFilename = optionalString(payload, "bundle_filename"),
            destPath = requiredString(payload, "dest_path").trim(),
            deleteExtras = strictBoolean(payload, "delete_extras", false),
        )
    }

    private fun validateWireCommand(fields: CommandFields): Command {
        val jobId = fields.jobId?.also {
            if (it.isBlank()) throw malformed("job_id must not be blank")
            requireUuidV4(it, "job_id")
        }
        val attempt = if (jobId == null) ATTEMPT_V1 else strictIntValue(fields.attemptValue, "attempt")
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val artifactId = fields.artifactId?.also {
            if (it.isBlank()) throw malformed("artifact_id must not be blank")
            requireUuidV4(it, "artifact_id")
        }
        if (jobId != null && artifactId == null) {
            throw malformed("artifact_id is required")
        }

        val artifactUrl = when {
            fields.hasArtifactUrl -> fields.artifactUrl ?: throw malformed("artifact_url is required")
            jobId != null -> throw malformed("artifact_url is required")
            else -> fields.bundleUrl ?: throw malformed("bundle_url is required")
        }
        validateArtifactUrl(artifactUrl)
        if (fields.destPath.isBlank()) {
            throw IllegalArgumentException("invalid_destination: destination is required")
        }
        if (fields.artifactSize != null && fields.artifactSize < 0L) {
            throw malformed("artifact_size must be non-negative")
        }
        val artifactSha256 = fields.artifactSha256?.ifBlank { null }
        if (artifactSha256 != null && !SHA256.matches(artifactSha256)) {
            throw malformed("artifact_sha256 must be 64 hexadecimal characters")
        }
        if (jobId != null && (fields.artifactSize == null || artifactSha256 == null)) {
            throw malformed("job-v1 artifact size and SHA-256 are required")
        }
        val revision = if (jobId == null) 0L else optionalLongValue(fields.revisionValue, "revision") ?: 0L
        if (revision < 0L) throw malformed("revision must be non-negative")
        val artifactEtag = fields.artifactEtag?.also { validateStrongEtag(it) }
        if (jobId != null && (revision == 0L || artifactEtag == null)) {
            throw malformed("job-v1 revision and artifact_etag are required")
        }
        return buildCommand(fields, jobId, attempt, artifactId, artifactUrl, artifactSha256, revision, artifactEtag)
    }

    /**
     * Stored-state rules are versioned independently from the wire contract. In
     * particular, Issue #91 active records may omit revision and artifact_etag.
     */
    private fun validateStoredCommand(fields: CommandFields): Command {
        val jobId = fields.jobId?.also {
            if (it.isBlank()) throw malformed("job_id must not be blank")
            requireUuidV4(it, "job_id")
        }
        val attempt = if (jobId == null) ATTEMPT_V1 else strictIntValue(fields.attemptValue, "attempt")
        if (attempt != ATTEMPT_V1) {
            throw IllegalArgumentException("stale_attempt: only attempt=1 is supported")
        }
        val artifactId = fields.artifactId?.also {
            if (it.isBlank()) throw malformed("artifact_id must not be blank")
            requireUuidV4(it, "artifact_id")
        }
        if (jobId != null && artifactId == null) {
            throw malformed("artifact_id is required")
        }
        val artifactUrl = when {
            fields.hasArtifactUrl -> fields.artifactUrl ?: throw malformed("artifact_url is required")
            jobId != null -> throw malformed("artifact_url is required")
            else -> fields.bundleUrl ?: throw malformed("bundle_url is required")
        }
        validateArtifactUrl(artifactUrl)
        if (fields.destPath.isBlank()) {
            throw IllegalArgumentException("invalid_destination: destination is required")
        }
        if (fields.artifactSize != null && fields.artifactSize < 0L) {
            throw malformed("artifact_size must be non-negative")
        }
        val artifactSha256 = fields.artifactSha256?.ifBlank { null }
        if (artifactSha256 != null && !SHA256.matches(artifactSha256)) {
            throw malformed("artifact_sha256 must be 64 hexadecimal characters")
        }
        if (jobId != null && (fields.artifactSize == null || artifactSha256 == null)) {
            throw malformed("job-v1 artifact size and SHA-256 are required")
        }
        val revision = if (jobId == null) 0L else optionalLongValue(fields.revisionValue, "revision") ?: 0L
        if (revision < 0L) throw malformed("revision must be non-negative")
        val artifactEtag = fields.artifactEtag?.also { validateStrongEtag(it) }
        return buildCommand(fields, jobId, attempt, artifactId, artifactUrl, artifactSha256, revision, artifactEtag)
    }

    private fun buildCommand(
        fields: CommandFields,
        jobId: String?,
        attempt: Int,
        artifactId: String?,
        artifactUrl: String,
        artifactSha256: String?,
        revision: Long,
        artifactEtag: String?,
    ): Command {
        return Command(
            jobId = jobId,
            attempt = attempt,
            artifactId = artifactId,
            artifactUrl = artifactUrl,
            artifactSize = fields.artifactSize,
            artifactSha256 = artifactSha256?.lowercase(),
            bundleFilename = fields.bundleFilename?.takeIf { it.isNotBlank() } ?: "bundle.zip",
            destPath = fields.destPath,
            deleteExtras = fields.deleteExtras,
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
        put("state_version", DURABLE_STATE_VERSION)
        put("active", state.active?.toJson() ?: JSONObject.NULL)
        put("pending_results", JSONArray().apply {
            state.pendingResults.forEach { put(it.toStateJson()) }
        })
        put("completed_receipts", JSONArray().apply {
            state.completedReceipts.forEach { put(it.toStateJson()) }
        })
    }

    fun stateFromJson(json: JSONObject): State {
        validateStateVersion(json)
        if (
            json.has("active") && !json.isNull("active") &&
            json.optJSONObject("active") == null
        ) {
            throw InvalidPushActiveException(IllegalArgumentException("active must be an object"))
        }
        val active = try {
            json.optJSONObject("active")?.let {
                Active(
                    parseStoredCommand(it.getJSONObject("command")),
                    it.getString("phase"),
                    it.optBoolean("interrupted", false),
                    it.optLong("interrupted_at", 0L).takeIf { value -> value > 0L },
                    optionalString(it, "interruption_reason")?.ifBlank { null },
                )
            }
        } catch (error: Exception) {
            throw InvalidPushActiveException(error)
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
                    add(Receipt(parseStoredCommand(command), resultFromJson(result)))
                }
            }
        } catch (error: Exception) {
            // Never silently skip a receipt: that could allow an already-applied
            // command to execute again or falsely report it absent to the server.
            throw InvalidPushReceiptsException(name, error)
        }
        return State(active, receipts("pending_results"), receipts("completed_receipts"))
    }

    private fun validateStateVersion(json: JSONObject) {
        if (!json.has("state_version")) return // Existing unversioned and Issue #91 files.
        val version = when (val value = json.opt("state_version")) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw UnsupportedPushStateVersionException(value?.toString() ?: "null")
        }
        if (version !in 0L..DURABLE_STATE_VERSION.toLong()) {
            throw UnsupportedPushStateVersionException(version.toString())
        }
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
        return strictIntValue(payload.opt(field), field)
    }

    private fun strictIntValue(value: Any?, field: String): Int {
        val longValue = when (value) {
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
        return optionalLongValue(payload.opt(field), field)
    }

    private fun optionalLongValue(value: Any?, field: String): Long? {
        if (value == null || value === JSONObject.NULL) return null
        return when (value) {
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
