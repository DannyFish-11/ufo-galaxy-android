package com.ufo.galaxy.runtime

import java.security.MessageDigest

/**
 * Unified minimal lineage metadata for Android runtime uplinks.
 */
object AndroidUplinkLineageMetadataContract {
    const val SCHEMA_VERSION = "1"

    const val KEY_SCHEMA_VERSION = "uplink_lineage_schema_version"
    const val KEY_EXECUTION_IDENTITY = "uplink_lineage_execution_id"
    const val KEY_EMISSION_IDENTITY = "uplink_lineage_emission_id"
    const val KEY_DEDUPE_KEY = "uplink_lineage_dedupe_key"
    const val KEY_RECOVERY_BASIS = "uplink_lineage_recovery_basis"

    enum class LineageStrengthClass(val wireValue: String) {
        CLOSURE_GRADE("closure_grade"),
        DEGRADED_MISSING_EXECUTION_IDENTITY("degraded_missing_execution_identity"),
        DEGRADED_MISSING_EMISSION_IDENTITY("degraded_missing_emission_identity"),
        DEGRADED_MISSING_SESSION_LINEAGE("degraded_missing_session_lineage")
    }

    data class Metadata(
        val executionIdentity: String,
        val emissionIdentity: String,
        val dedupeKey: String,
        val recoveryBasis: String,
        val lineageStrengthClass: LineageStrengthClass,
        val isClosureLineageComplete: Boolean
    )

    fun derive(
        executionIdentity: String?,
        emissionIdentity: String?,
        durableSessionId: String?,
        sessionContinuityEpoch: Int?,
        recoveryBasis: String
    ): Metadata {
        val hasExecutionIdentity = !executionIdentity.isNullOrBlank()
        val hasEmissionIdentity = !emissionIdentity.isNullOrBlank()
        val hasSessionLineage = !durableSessionId.isNullOrBlank() && sessionContinuityEpoch != null

        val safeSession = durableSessionId?.ifBlank { null } ?: "no_durable_session"
        val safeEpoch = sessionContinuityEpoch?.toString() ?: "no_epoch"
        val safeRecoveryBasis = recoveryBasis.ifBlank { "none" }
        val identitySeed = sha256(
            listOf(safeSession, safeEpoch, safeRecoveryBasis).joinToString("|")
        )
        val safeExecutionIdentity = executionIdentity?.ifBlank { null }
            ?: "synthetic_execution_$identitySeed"
        val safeEmissionIdentity = emissionIdentity?.ifBlank { null }
            ?: "synthetic_emission_${sha256("$safeExecutionIdentity|$safeRecoveryBasis")}"
        val lineageStrengthClass = when {
            !hasExecutionIdentity ->
                LineageStrengthClass.DEGRADED_MISSING_EXECUTION_IDENTITY
            !hasEmissionIdentity ->
                LineageStrengthClass.DEGRADED_MISSING_EMISSION_IDENTITY
            !hasSessionLineage ->
                LineageStrengthClass.DEGRADED_MISSING_SESSION_LINEAGE
            else ->
                LineageStrengthClass.CLOSURE_GRADE
        }
        val isClosureLineageComplete =
            lineageStrengthClass == LineageStrengthClass.CLOSURE_GRADE
        val dedupeKey = sha256(
            listOf(
                safeSession,
                safeEpoch,
                safeExecutionIdentity,
                safeEmissionIdentity,
                safeRecoveryBasis
            ).joinToString("|")
        )
        return Metadata(
            executionIdentity = safeExecutionIdentity,
            emissionIdentity = safeEmissionIdentity,
            dedupeKey = dedupeKey,
            recoveryBasis = safeRecoveryBasis,
            lineageStrengthClass = lineageStrengthClass,
            isClosureLineageComplete = isClosureLineageComplete
        )
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isClosureLineageComplete(
        executionIdentity: String?,
        emissionIdentity: String?,
        durableSessionId: String?,
        sessionContinuityEpoch: Int?
    ): Boolean =
        !executionIdentity.isNullOrBlank() &&
            !emissionIdentity.isNullOrBlank() &&
            !durableSessionId.isNullOrBlank() &&
            sessionContinuityEpoch != null
}
