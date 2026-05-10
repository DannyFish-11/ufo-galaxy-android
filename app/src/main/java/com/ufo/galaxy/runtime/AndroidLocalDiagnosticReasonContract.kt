package com.ufo.galaxy.runtime

/**
 * PR-11B — Stable local diagnostics reason contract for V2 reconciliation.
 *
 * Provides a code-backed, versioned reason surface so Android does not rely on loosely
 * formatted logs or ad hoc message strings when emitting diagnostics payloads.
 */
object AndroidLocalDiagnosticReasonContract {

    const val CONTRACT_SCHEMA_VERSION = "1"

    enum class DiagnosticDomain(val wireValue: String) {
        RUNTIME("runtime"),
        CAPABILITY("capability"),
        RECOVERY("recovery"),
        TAKEOVER("takeover"),
        MESH_PARTICIPATION("mesh_participation"),
        UNKNOWN("unknown");

        companion object {
            fun fromWireValue(value: String?): DiagnosticDomain? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    enum class DiagnosticReason(val wireValue: String, val domain: DiagnosticDomain) {
        // Runtime
        WS_DISCONNECTED("ws_disconnected", DiagnosticDomain.RUNTIME),
        WS_ERROR("ws_error", DiagnosticDomain.RUNTIME),
        EXECUTION_CONFLICT("execution_conflict", DiagnosticDomain.RUNTIME),
        TASK_PAYLOAD_PARSE_ERROR("task_payload_parse_error", DiagnosticDomain.RUNTIME),
        GOAL_PAYLOAD_PARSE_ERROR("goal_payload_parse_error", DiagnosticDomain.RUNTIME),
        PARALLEL_PAYLOAD_PARSE_ERROR("parallel_payload_parse_error", DiagnosticDomain.RUNTIME),
        HANDOFF_V2_PARSE_ERROR("handoff_v2_parse_error", DiagnosticDomain.RUNTIME),
        TASK_EXECUTION_FAILED("task_execution_failed", DiagnosticDomain.RUNTIME),
        GOAL_EXECUTION_TIMEOUT("goal_execution_timeout", DiagnosticDomain.RUNTIME),

        // Capability
        BRIDGE_HANDOFF_FAILED("bridge_handoff_failed", DiagnosticDomain.CAPABILITY),
        HANDOFF_CONTRACT_INVALID("handoff_contract_invalid", DiagnosticDomain.CAPABILITY),

        // Recovery
        STALE_ATTACHMENT_SESSION("stale_attachment_session", DiagnosticDomain.RECOVERY),
        STALE_TAKEOVER_RECOVERY_AUTHORITY(
            "stale_takeover_recovery_authority",
            DiagnosticDomain.RECOVERY
        ),
        REJECT_STALE_IDENTITY("reject_stale_identity", DiagnosticDomain.RECOVERY),

        // Takeover
        TAKEOVER_PARSE_ERROR("takeover_parse_error", DiagnosticDomain.TAKEOVER),
        TAKEOVER_INELIGIBLE("takeover_ineligible", DiagnosticDomain.TAKEOVER),
        TAKEOVER_SESSION_REJECTED("takeover_session_rejected", DiagnosticDomain.TAKEOVER),
        TAKEOVER_EXECUTION_FAILED("takeover_execution_failed", DiagnosticDomain.TAKEOVER),
        HANDOFF_V2_EXECUTION_FAILED("handoff_v2_execution_failed", DiagnosticDomain.TAKEOVER),

        // Mesh participation
        PARALLEL_SUBTASK_TIMEOUT("parallel_subtask_timeout", DiagnosticDomain.MESH_PARTICIPATION),

        UNKNOWN("unknown", DiagnosticDomain.UNKNOWN);

        companion object {
            fun fromWireValue(value: String?): DiagnosticReason? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    data class Classification(
        val schemaVersion: String = CONTRACT_SCHEMA_VERSION,
        val domain: DiagnosticDomain,
        val reason: DiagnosticReason,
        val localCause: String
    )

    fun classify(errorType: String, nodeName: String): Classification {
        val normalizedErrorType = errorType.trim()
        val reason = when {
            normalizedErrorType == AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY ->
                DiagnosticReason.REJECT_STALE_IDENTITY
            else -> DiagnosticReason.fromWireValue(normalizedErrorType) ?: DiagnosticReason.UNKNOWN
        }
        return Classification(
            domain = reason.domain,
            reason = reason,
            localCause = nodeName.ifBlank { "unspecified" }
        )
    }

    val DIAGNOSTIC_REASON_INVARIANTS: Map<String, Boolean>
        get() = mapOf(
            "schema_version_non_blank" to CONTRACT_SCHEMA_VERSION.isNotBlank(),
            "domains_have_unique_wire_values" to
                DiagnosticDomain.entries.map { it.wireValue }.toSet().size == DiagnosticDomain.entries.size,
            "reasons_have_unique_wire_values" to
                DiagnosticReason.entries.map { it.wireValue }.toSet().size == DiagnosticReason.entries.size,
            "every_reason_has_domain" to
                DiagnosticReason.entries.all { it.domain in DiagnosticDomain.entries }
        )
}
