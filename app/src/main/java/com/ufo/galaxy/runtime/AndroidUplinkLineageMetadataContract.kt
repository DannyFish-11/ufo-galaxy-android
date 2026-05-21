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

    data class Metadata(
        val executionIdentity: String,
        val emissionIdentity: String,
        val dedupeKey: String,
        val recoveryBasis: String
    )

    fun derive(
        executionIdentity: String?,
        emissionIdentity: String?,
        durableSessionId: String?,
        sessionContinuityEpoch: Int?,
        recoveryBasis: String
    ): Metadata {
        val safeExecutionIdentity = executionIdentity?.ifBlank { null } ?: "unknown_execution"
        val safeEmissionIdentity = emissionIdentity?.ifBlank { null } ?: "unknown_emission"
        val safeSession = durableSessionId?.ifBlank { null } ?: "no_durable_session"
        val safeEpoch = sessionContinuityEpoch?.toString() ?: "no_epoch"
        val safeRecoveryBasis = recoveryBasis.ifBlank { "none" }
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
            recoveryBasis = safeRecoveryBasis
        )
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
