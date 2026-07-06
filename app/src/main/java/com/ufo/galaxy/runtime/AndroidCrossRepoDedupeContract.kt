package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ufo.galaxy.shared.protocol.MsgType

/**
 * Canonical cross-repo dedupe / idempotency contract for Android → V2 uplinks and replay.
 *
 * This contract makes one Android-side source of truth for:
 *  - which replay-bearing message classes are authority-sensitive
 *  - which message classes require continuity epoch metadata for canonical replay
 *  - which wire field provides the stable duplicate identity for each carrier
 *  - when Android is operating in canonical vs compatibility dedupe mode
 */
object AndroidCrossRepoDedupeContract {

    const val SCHEMA_VERSION = "android_v2_canonical_dedupe_v1"

    enum class ContractStatus(val wireValue: String) {
        CANONICAL("canonical"),
        COMPATIBILITY("compatibility"),
        INVALID("invalid")
    }

    val CANONICAL_REPLAY_TYPES: Set<String> = setOf(
        MsgType.GOAL_EXECUTION_RESULT.value,
        MsgType.DEVICE_EXECUTION_EVENT.value,
        MsgType.DEVICE_STATE_SNAPSHOT.value,
        MsgType.RECONCILIATION_SIGNAL.value
    )

    val REPLAY_EPOCH_REQUIRED_TYPES: Set<String> = setOf(
        MsgType.GOAL_EXECUTION_RESULT.value,
        MsgType.DEVICE_EXECUTION_EVENT.value,
        MsgType.DEVICE_STATE_SNAPSHOT.value,
        MsgType.RECONCILIATION_SIGNAL.value,
        MsgType.DELEGATED_EXECUTION_SIGNAL.value
    )

    val AUTHORITY_SENSITIVE_REPLAY_TYPES: Set<String> = REPLAY_EPOCH_REQUIRED_TYPES

    data class Assessment(
        val messageType: String?,
        val stableKey: String?,
        val status: ContractStatus,
        val stableKeySource: String?,
        val sessionEpoch: Int?,
        val durableSessionId: String?,
        val missingFields: List<String>,
        val reason: String
    ) {
        val isCanonical: Boolean get() = status == ContractStatus.CANONICAL
    }

    fun assessEnvelopeJson(
        json: String,
        gson: Gson = Gson()
    ): Assessment {
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
        return assessEnvelope(root)
    }

    fun assessEnvelope(root: JsonObject?): Assessment {
        val type = root.stringOrNull("type")
        val payload = root.objectOrNull("payload")
        val idempotencyKey = root.stringOrNull("idempotency_key")

        return when (type) {
            MsgType.GOAL_EXECUTION_RESULT.value,
            MsgType.DEVICE_EXECUTION_EVENT.value,
            MsgType.DEVICE_STATE_SNAPSHOT.value -> {
                val missing = buildList {
                    if (payload.stringOrNull(AndroidUplinkLineageMetadataContract.KEY_SCHEMA_VERSION).isNullOrBlank()) {
                        add("payload.${AndroidUplinkLineageMetadataContract.KEY_SCHEMA_VERSION}")
                    }
                    if (payload.stringOrNull(AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY).isNullOrBlank()) {
                        add("payload.${AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY}")
                    }
                    if (payload.stringOrNull(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID).isNullOrBlank()) {
                        add("payload.${DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID}")
                    }
                    if (payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH) == null) {
                        add("payload.${DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH}")
                    }
                }
                val lineageKey = payload.stringOrNull(AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY)
                Assessment(
                    messageType = type,
                    stableKey = lineageKey ?: idempotencyKey,
                    status = when {
                        lineageKey != null && missing.isEmpty() -> ContractStatus.CANONICAL
                        lineageKey != null || idempotencyKey != null -> ContractStatus.COMPATIBILITY
                        else -> ContractStatus.INVALID
                    },
                    stableKeySource = when {
                        lineageKey != null -> AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY
                        idempotencyKey != null -> "idempotency_key"
                        else -> null
                    },
                    sessionEpoch = payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH),
                    durableSessionId = payload.stringOrNull(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID),
                    missingFields = missing,
                    reason = when {
                        lineageKey != null && missing.isEmpty() -> "lineage_bound_replay_identity"
                        lineageKey != null || idempotencyKey != null -> "compatibility_identity_missing_canonical_fields"
                        else -> "no_replay_identity_available"
                    }
                )
            }

            MsgType.RECONCILIATION_SIGNAL.value -> {
                val reliablePayload = payload.objectOrNull("payload")
                val stableDedupeKey = reliablePayload.stringOrNull(ReconciliationSignal.KEY_STABLE_DEDUPE_KEY)
                val lineageKey = payload.stringOrNull(AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY)
                val missing = buildList {
                    if (idempotencyKey.isNullOrBlank()) add("idempotency_key")
                    if (stableDedupeKey.isNullOrBlank()) {
                        add("payload.payload.${ReconciliationSignal.KEY_STABLE_DEDUPE_KEY}")
                    }
                    if (payload.stringOrNull(AndroidUplinkLineageMetadataContract.KEY_SCHEMA_VERSION).isNullOrBlank()) {
                        add("payload.${AndroidUplinkLineageMetadataContract.KEY_SCHEMA_VERSION}")
                    }
                    if (lineageKey.isNullOrBlank()) {
                        add("payload.${AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY}")
                    }
                    if (payload.stringOrNull(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID).isNullOrBlank()) {
                        add("payload.${DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID}")
                    }
                    if (payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH) == null) {
                        add("payload.${DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH}")
                    }
                }
                val stableKeyMatchesIdempotency =
                    stableDedupeKey != null && stableDedupeKey == idempotencyKey
                Assessment(
                    messageType = type,
                    stableKey = stableDedupeKey ?: idempotencyKey,
                    status = when {
                        stableKeyMatchesIdempotency &&
                            lineageKey != null &&
                            missing.isEmpty() -> ContractStatus.CANONICAL
                        stableDedupeKey != null || idempotencyKey != null -> ContractStatus.COMPATIBILITY
                        else -> ContractStatus.INVALID
                    },
                    stableKeySource = when {
                        stableDedupeKey != null -> ReconciliationSignal.KEY_STABLE_DEDUPE_KEY
                        idempotencyKey != null -> "idempotency_key"
                        else -> null
                    },
                    sessionEpoch = payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH),
                    durableSessionId = payload.stringOrNull(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID),
                    missingFields = missing,
                    reason = when {
                        stableKeyMatchesIdempotency &&
                            lineageKey != null &&
                            missing.isEmpty() ->
                            "reconciliation_stable_dedupe_key_matches_envelope"
                        stableDedupeKey != null || idempotencyKey != null ->
                            "compatibility_identity_missing_canonical_reconciliation_fields"
                        else -> "no_reconciliation_identity_available"
                    }
                )
            }

            MsgType.DELEGATED_EXECUTION_SIGNAL.value -> {
                val signalId = payload.stringOrNull("signal_id")
                val stableKey = signalId ?: idempotencyKey
                Assessment(
                    messageType = type,
                    stableKey = stableKey,
                    status = assessCompatibilityStatus(stableKey),
                    stableKeySource = when {
                        signalId != null -> "payload.signal_id"
                        idempotencyKey != null -> "idempotency_key"
                        else -> null
                    },
                    sessionEpoch = payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH),
                    durableSessionId = null,
                    missingFields = emptyList(),
                    reason = if (signalId != null || idempotencyKey != null) {
                        "delegated_signal_identity_compatibility"
                    } else {
                        "no_delegated_signal_identity_available"
                    }
                )
            }

            else -> Assessment(
                messageType = type,
                stableKey = idempotencyKey,
                status = assessCompatibilityStatus(idempotencyKey),
                stableKeySource = if (idempotencyKey != null) "idempotency_key" else null,
                sessionEpoch = payload.intOrNull(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH),
                durableSessionId = payload.stringOrNull(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID),
                missingFields = emptyList(),
                reason = if (idempotencyKey != null) {
                    "legacy_envelope_idempotency_identity"
                } else {
                    "no_supported_identity_fields"
                }
            )
        }
    }

    private fun JsonObject?.stringOrNull(key: String): String? =
        this?.get(key).stringOrNull()

    private fun JsonObject?.objectOrNull(key: String): JsonObject? =
        runCatching { this?.getAsJsonObject(key) }.getOrNull()

    private fun JsonObject?.intOrNull(key: String): Int? =
        this?.get(key).intOrNull()

    private fun JsonElement?.stringOrNull(): String? =
        this?.takeUnless { it.isJsonNull }?.asString?.ifBlank { null }

    private fun JsonElement?.intOrNull(): Int? =
        this?.takeUnless { it.isJsonNull }?.asInt

    private fun assessCompatibilityStatus(stableKey: String?): ContractStatus =
        if (stableKey != null) ContractStatus.COMPATIBILITY else ContractStatus.INVALID
}
