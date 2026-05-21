package com.ufo.galaxy.runtime

import org.json.JSONObject

/**
 * Android-local continuity classification for a previously in-flight delegated execution.
 *
 * This is intentionally **advisory Android-side recovery evidence**, not canonical V2 truth.
 * It exists so Android can deterministically report what happened to its local in-flight
 * execution knowledge after process recreation, runtime restart, or service rebind.
 */
enum class InflightContinuityDisposition(val wireValue: String) {
    RECOVERED_INFLIGHT("recovered-inflight"),
    LOST_INFLIGHT("lost-inflight"),
    REQUIRES_RECONCILIATION("requires-reconciliation"),
    RESUMED_CLEANLY("resumed-cleanly");

    companion object {
        fun fromValue(value: String?): InflightContinuityDisposition? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Minimal durable recovery artifact for an Android-side delegated execution that was in flight.
 *
 * Persisted before Android enters execution so a later process or service restart can report
 * whether the prior local in-flight state was recovered, lost, or requires reconciliation.
 */
data class InflightContinuityRecoveryArtifact(
    val taskId: String,
    val activeTaskStatus: String,
    val durableSessionId: String?,
    val sessionContinuityEpoch: Int?,
    val runtimeAttachmentSessionId: String?,
    val attachedSessionId: String?,
    val persistedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put(KEY_TASK_ID, taskId)
        put(KEY_ACTIVE_TASK_STATUS, activeTaskStatus)
        put(KEY_PERSISTED_AT_MS, persistedAtMs)
        durableSessionId?.let { put(KEY_DURABLE_SESSION_ID, it) }
        sessionContinuityEpoch?.let { put(KEY_SESSION_CONTINUITY_EPOCH, it) }
        runtimeAttachmentSessionId?.let { put(KEY_RUNTIME_ATTACHMENT_SESSION_ID, it) }
        attachedSessionId?.let { put(KEY_ATTACHED_SESSION_ID, it) }
    }.toString()

    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_TASK_ID, taskId)
        put(KEY_ACTIVE_TASK_STATUS, activeTaskStatus)
        put(KEY_PERSISTED_AT_MS, persistedAtMs)
        durableSessionId?.let { put(KEY_DURABLE_SESSION_ID, it) }
        sessionContinuityEpoch?.let { put(KEY_SESSION_CONTINUITY_EPOCH, it) }
        runtimeAttachmentSessionId?.let { put(KEY_RUNTIME_ATTACHMENT_SESSION_ID, it) }
        attachedSessionId?.let { put(KEY_ATTACHED_SESSION_ID, it) }
    }

    companion object {
        const val KEY_TASK_ID = "inflight_task_id"
        const val KEY_ACTIVE_TASK_STATUS = "inflight_task_status"
        const val KEY_DURABLE_SESSION_ID = "inflight_durable_session_id"
        const val KEY_SESSION_CONTINUITY_EPOCH = "inflight_session_continuity_epoch"
        const val KEY_RUNTIME_ATTACHMENT_SESSION_ID = "inflight_runtime_attachment_session_id"
        const val KEY_ATTACHED_SESSION_ID = "inflight_attached_session_id"
        const val KEY_PERSISTED_AT_MS = "inflight_persisted_at_ms"

        fun fromJson(raw: String?): InflightContinuityRecoveryArtifact? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val taskId = json.optString(KEY_TASK_ID).takeIf { it.isNotBlank() } ?: return null
                val status = json.optString(KEY_ACTIVE_TASK_STATUS).takeIf { it.isNotBlank() } ?: return null
                InflightContinuityRecoveryArtifact(
                    taskId = taskId,
                    activeTaskStatus = status,
                    durableSessionId = json.optString(KEY_DURABLE_SESSION_ID).takeIf { it.isNotBlank() },
                    sessionContinuityEpoch = json.takeIf { it.has(KEY_SESSION_CONTINUITY_EPOCH) }
                        ?.optInt(KEY_SESSION_CONTINUITY_EPOCH),
                    runtimeAttachmentSessionId = json.optString(KEY_RUNTIME_ATTACHMENT_SESSION_ID)
                        .takeIf { it.isNotBlank() },
                    attachedSessionId = json.optString(KEY_ATTACHED_SESSION_ID).takeIf { it.isNotBlank() },
                    persistedAtMs = json.optLong(KEY_PERSISTED_AT_MS, 0L)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Observable Android-local recovery result for prior in-flight execution continuity.
 */
data class InflightContinuityRecoverySnapshot(
    val disposition: InflightContinuityDisposition,
    val artifact: InflightContinuityRecoveryArtifact? = null,
    val source: String,
    val observedAtMs: Long = System.currentTimeMillis()
) {
    val taskId: String? get() = artifact?.taskId
    val activeTaskStatus: String? get() = artifact?.activeTaskStatus
    val durableSessionId: String? get() = artifact?.durableSessionId
    val sessionContinuityEpoch: Int? get() = artifact?.sessionContinuityEpoch

    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_DISPOSITION, disposition.wireValue)
        put(KEY_SOURCE, source)
        put(KEY_OBSERVED_AT_MS, observedAtMs)
        artifact?.toMetadataMap()?.forEach { (key, value) -> put(key, value) }
    }

    companion object {
        const val KEY_DISPOSITION = "inflight_continuity_state"
        const val KEY_SOURCE = "inflight_continuity_source"
        const val KEY_OBSERVED_AT_MS = "inflight_continuity_observed_at_ms"

        fun resumedCleanly(source: String): InflightContinuityRecoverySnapshot =
            InflightContinuityRecoverySnapshot(
                disposition = InflightContinuityDisposition.RESUMED_CLEANLY,
                source = source
            )
    }
}
