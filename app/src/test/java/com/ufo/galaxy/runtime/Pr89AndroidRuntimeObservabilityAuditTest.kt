package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-89 (PR-5 of the 5-part convergence plan — Android) — Unit tests for
 * [AndroidRuntimeObservabilityAuditContract].
 *
 * Validates all acceptance criteria for the Android-side runtime observability and
 * problem-solving audit contract:
 *
 *  1. **ExecutionPathTag** — six tags defined; wire values stable; fromWireValue works.
 *  2. **AuditContributionClass** — nine classes defined; wire values stable; fromWireValue works.
 *  3. **ObservabilityReliabilityClass** — five classes defined; wire values stable; fromWireValue works.
 *  4. **classifyExecutionPath** — correct tag for each combination of inputs.
 *  5. **classifyAuditContribution** — correct class for each event phase / flag combination.
 *  6. **classifyObservabilityReliability** — correct class for each evidence/degraded combination.
 *  7. **OBSERVABILITY_AUDIT_INVARIANTS** — all 10 invariants hold.
 *  8. **StabilizationBaseline** — "android-runtime-observability-audit-contract" registered.
 *  9. **Wire field constants** — all field name constants are non-blank.
 * 10. **SCHEMA_VERSION** — is "1".
 *
 * ## Test matrix
 *
 * ### ExecutionPathTag
 *  - LOCAL_PATH wireValue is local_path
 *  - CROSS_DEVICE_PATH wireValue is cross_device_path
 *  - DELEGATED_PATH wireValue is delegated_path
 *  - TAKEOVER_PATH wireValue is takeover_path
 *  - DEGRADED_PATH wireValue is degraded_path
 *  - UNKNOWN wireValue is unknown
 *  - fromWireValue returns correct tag for each value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly six entries
 *
 * ### AuditContributionClass
 *  - PARTICIPATION_ATTESTATION wireValue is participation_attestation
 *  - EXECUTION_CONTRIBUTION wireValue is execution_contribution
 *  - EXECUTION_OUTCOME wireValue is execution_outcome
 *  - INTERRUPTION_RECORD wireValue is interruption_record
 *  - RECOVERY_CONTRIBUTION wireValue is recovery_contribution
 *  - TAKEOVER_CONTRIBUTION wireValue is takeover_contribution
 *  - DELEGATED_CONTRIBUTION wireValue is delegated_contribution
 *  - OPERATOR_ACTION_OUTCOME wireValue is operator_action_outcome
 *  - INFORMATIONAL wireValue is informational
 *  - fromWireValue returns correct class for each value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly nine entries
 *
 * ### ObservabilityReliabilityClass
 *  - HIGH_FIDELITY wireValue is high_fidelity
 *  - REDUCED_FIDELITY wireValue is reduced_fidelity
 *  - STALE wireValue is stale
 *  - INTERRUPTED wireValue is interrupted
 *  - UNKNOWN wireValue is unknown
 *  - fromWireValue returns correct class for each value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly five entries
 *
 * ### classifyExecutionPath
 *  - spineParticipationKind takeover_interactive → TAKEOVER_PATH
 *  - spineParticipationKind delegated_execution → DELEGATED_PATH
 *  - spineParticipationKind local_assistive → LOCAL_PATH
 *  - spineParticipationKind degraded_fallback → DEGRADED_PATH
 *  - spineParticipationKind unknown → UNKNOWN
 *  - isTakeoverPath=true → TAKEOVER_PATH (no spine kind)
 *  - isDelegated=true → DELEGATED_PATH (no spine kind, no takeover)
 *  - executionModeState cross_device_degraded → DEGRADED_PATH
 *  - executionModeState cross_device_active + crossDeviceEligibility=true → CROSS_DEVICE_PATH
 *  - currentFallbackTier center_delegated_with_local_fallback → DEGRADED_PATH
 *  - executionModeState local_only + crossDeviceEligibility=false → LOCAL_PATH
 *  - all-null inputs → UNKNOWN
 *
 * ### classifyAuditContribution
 *  - isParticipationSnapshot=true + isRecoveryPhase=false → PARTICIPATION_ATTESTATION
 *  - isParticipationSnapshot=true + isRecoveryPhase=true → RECOVERY_CONTRIBUTION
 *  - isOperatorActionEvent=true → OPERATOR_ACTION_OUTCOME
 *  - isInterruptionPhase=true → INTERRUPTION_RECORD
 *  - isRecoveryPhase=true (not snapshot) → RECOVERY_CONTRIBUTION
 *  - isTakeoverEvent=true → TAKEOVER_CONTRIBUTION
 *  - isDelegatedEvent=true → DELEGATED_CONTRIBUTION
 *  - isTerminalPhase=true → EXECUTION_OUTCOME
 *  - phase execution_started → EXECUTION_CONTRIBUTION
 *  - phase execution_progress → EXECUTION_CONTRIBUTION
 *  - phase completed → EXECUTION_OUTCOME
 *  - phase failed → EXECUTION_OUTCOME
 *  - phase stagnation_detected → EXECUTION_OUTCOME
 *  - phase cancelled → EXECUTION_OUTCOME
 *  - phase fallback_transition → RECOVERY_CONTRIBUTION
 *  - phase takeover_milestone → TAKEOVER_CONTRIBUTION
 *  - null phase, not snapshot → INFORMATIONAL
 *
 * ### classifyObservabilityReliability
 *  - reconnectRecoveryState=recovering → INTERRUPTED
 *  - localObservationBasis=cached_state → STALE
 *  - evidencePresenceKind=positive_evidence + nominal → HIGH_FIDELITY
 *  - evidencePresenceKind=positive_evidence + degraded → REDUCED_FIDELITY
 *  - evidencePresenceKind=positive_evidence + recovered → HIGH_FIDELITY
 *  - evidencePresenceKind=delayed → STALE
 *  - evidencePresenceKind=partial → REDUCED_FIDELITY
 *  - evidencePresenceKind=unknown → UNKNOWN
 *  - evidencePresenceKind=unavailable → INTERRUPTED
 *  - evidencePresenceKind=failed_observation → INTERRUPTED
 *  - null evidence + degraded condition → REDUCED_FIDELITY
 *  - null evidence + recovering condition → INTERRUPTED
 *  - null evidence + nominal condition → UNKNOWN
 *  - all-null inputs → UNKNOWN
 *
 * ### OBSERVABILITY_AUDIT_INVARIANTS
 *  - all_invariants_hold is true
 *  - invariant count = 10
 *  - all individual invariants are true
 *
 * ### StabilizationBaseline
 *  - android-runtime-observability-audit-contract is registered
 *  - surface is CANONICAL_STABLE
 *  - extensionGuidance is EXTEND
 *
 * ### Wire field constants
 *  - FIELD_EXECUTION_PATH_TAG is non-blank
 *  - FIELD_AUDIT_CONTRIBUTION_CLASS is non-blank
 *  - FIELD_OBSERVABILITY_RELIABILITY_CLASS is non-blank
 *  - FIELD_OBSERVABILITY_AUDIT_SCHEMA_VERSION is non-blank
 *
 * ### SCHEMA_VERSION
 *  - is "1"
 */
class Pr89AndroidRuntimeObservabilityAuditTest {

    // ── ExecutionPathTag ──────────────────────────────────────────────────────

    @Test
    fun `LOCAL_PATH wireValue is local_path`() {
        assertEquals("local_path",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.LOCAL_PATH.wireValue)
    }

    @Test
    fun `CROSS_DEVICE_PATH wireValue is cross_device_path`() {
        assertEquals("cross_device_path",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH.wireValue)
    }

    @Test
    fun `DELEGATED_PATH wireValue is delegated_path`() {
        assertEquals("delegated_path",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DELEGATED_PATH.wireValue)
    }

    @Test
    fun `TAKEOVER_PATH wireValue is takeover_path`() {
        assertEquals("takeover_path",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.TAKEOVER_PATH.wireValue)
    }

    @Test
    fun `DEGRADED_PATH wireValue is degraded_path`() {
        assertEquals("degraded_path",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DEGRADED_PATH.wireValue)
    }

    @Test
    fun `ExecutionPathTag UNKNOWN wireValue is unknown`() {
        assertEquals("unknown",
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.UNKNOWN.wireValue)
    }

    @Test
    fun `ExecutionPathTag fromWireValue returns correct tag for each value`() {
        val t = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag
        assertEquals(t.LOCAL_PATH, t.fromWireValue("local_path"))
        assertEquals(t.CROSS_DEVICE_PATH, t.fromWireValue("cross_device_path"))
        assertEquals(t.DELEGATED_PATH, t.fromWireValue("delegated_path"))
        assertEquals(t.TAKEOVER_PATH, t.fromWireValue("takeover_path"))
        assertEquals(t.DEGRADED_PATH, t.fromWireValue("degraded_path"))
        assertEquals(t.UNKNOWN, t.fromWireValue("unknown"))
    }

    @Test
    fun `ExecutionPathTag fromWireValue returns null for unknown value`() {
        assertNull(AndroidRuntimeObservabilityAuditContract.ExecutionPathTag
            .fromWireValue("completely_unknown_path"))
    }

    @Test
    fun `ExecutionPathTag ALL_WIRE_VALUES contains exactly six entries`() {
        assertEquals(6,
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.ALL_WIRE_VALUES.size)
    }

    // ── AuditContributionClass ────────────────────────────────────────────────

    @Test
    fun `PARTICIPATION_ATTESTATION wireValue is participation_attestation`() {
        assertEquals("participation_attestation",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.PARTICIPATION_ATTESTATION.wireValue)
    }

    @Test
    fun `EXECUTION_CONTRIBUTION wireValue is execution_contribution`() {
        assertEquals("execution_contribution",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.EXECUTION_CONTRIBUTION.wireValue)
    }

    @Test
    fun `EXECUTION_OUTCOME wireValue is execution_outcome`() {
        assertEquals("execution_outcome",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.EXECUTION_OUTCOME.wireValue)
    }

    @Test
    fun `INTERRUPTION_RECORD wireValue is interruption_record`() {
        assertEquals("interruption_record",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.INTERRUPTION_RECORD.wireValue)
    }

    @Test
    fun `RECOVERY_CONTRIBUTION wireValue is recovery_contribution`() {
        assertEquals("recovery_contribution",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.RECOVERY_CONTRIBUTION.wireValue)
    }

    @Test
    fun `TAKEOVER_CONTRIBUTION wireValue is takeover_contribution`() {
        assertEquals("takeover_contribution",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.TAKEOVER_CONTRIBUTION.wireValue)
    }

    @Test
    fun `DELEGATED_CONTRIBUTION wireValue is delegated_contribution`() {
        assertEquals("delegated_contribution",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.DELEGATED_CONTRIBUTION.wireValue)
    }

    @Test
    fun `OPERATOR_ACTION_OUTCOME wireValue is operator_action_outcome`() {
        assertEquals("operator_action_outcome",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.OPERATOR_ACTION_OUTCOME.wireValue)
    }

    @Test
    fun `INFORMATIONAL wireValue is informational`() {
        assertEquals("informational",
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.INFORMATIONAL.wireValue)
    }

    @Test
    fun `AuditContributionClass fromWireValue returns correct class for each value`() {
        val c = AndroidRuntimeObservabilityAuditContract.AuditContributionClass
        assertEquals(c.PARTICIPATION_ATTESTATION, c.fromWireValue("participation_attestation"))
        assertEquals(c.EXECUTION_CONTRIBUTION, c.fromWireValue("execution_contribution"))
        assertEquals(c.EXECUTION_OUTCOME, c.fromWireValue("execution_outcome"))
        assertEquals(c.INTERRUPTION_RECORD, c.fromWireValue("interruption_record"))
        assertEquals(c.RECOVERY_CONTRIBUTION, c.fromWireValue("recovery_contribution"))
        assertEquals(c.TAKEOVER_CONTRIBUTION, c.fromWireValue("takeover_contribution"))
        assertEquals(c.DELEGATED_CONTRIBUTION, c.fromWireValue("delegated_contribution"))
        assertEquals(c.OPERATOR_ACTION_OUTCOME, c.fromWireValue("operator_action_outcome"))
        assertEquals(c.INFORMATIONAL, c.fromWireValue("informational"))
    }

    @Test
    fun `AuditContributionClass fromWireValue returns null for unknown value`() {
        assertNull(AndroidRuntimeObservabilityAuditContract.AuditContributionClass
            .fromWireValue("completely_unknown_audit_class"))
    }

    @Test
    fun `AuditContributionClass ALL_WIRE_VALUES contains exactly nine entries`() {
        assertEquals(9,
            AndroidRuntimeObservabilityAuditContract.AuditContributionClass.ALL_WIRE_VALUES.size)
    }

    // ── ObservabilityReliabilityClass ─────────────────────────────────────────

    @Test
    fun `HIGH_FIDELITY wireValue is high_fidelity`() {
        assertEquals("high_fidelity",
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.HIGH_FIDELITY.wireValue)
    }

    @Test
    fun `REDUCED_FIDELITY wireValue is reduced_fidelity`() {
        assertEquals("reduced_fidelity",
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.REDUCED_FIDELITY.wireValue)
    }

    @Test
    fun `STALE wireValue is stale`() {
        assertEquals("stale",
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.STALE.wireValue)
    }

    @Test
    fun `INTERRUPTED wireValue is interrupted`() {
        assertEquals("interrupted",
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.INTERRUPTED.wireValue)
    }

    @Test
    fun `ObservabilityReliabilityClass UNKNOWN wireValue is unknown`() {
        assertEquals("unknown",
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.UNKNOWN.wireValue)
    }

    @Test
    fun `ObservabilityReliabilityClass fromWireValue returns correct class for each value`() {
        val r = AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass
        assertEquals(r.HIGH_FIDELITY, r.fromWireValue("high_fidelity"))
        assertEquals(r.REDUCED_FIDELITY, r.fromWireValue("reduced_fidelity"))
        assertEquals(r.STALE, r.fromWireValue("stale"))
        assertEquals(r.INTERRUPTED, r.fromWireValue("interrupted"))
        assertEquals(r.UNKNOWN, r.fromWireValue("unknown"))
    }

    @Test
    fun `ObservabilityReliabilityClass fromWireValue returns null for unknown value`() {
        assertNull(AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass
            .fromWireValue("completely_unknown_reliability"))
    }

    @Test
    fun `ObservabilityReliabilityClass ALL_WIRE_VALUES contains exactly five entries`() {
        assertEquals(5,
            AndroidRuntimeObservabilityAuditContract.ObservabilityReliabilityClass.ALL_WIRE_VALUES.size)
    }

    // ── classifyExecutionPath ─────────────────────────────────────────────────

    private val contract = AndroidRuntimeObservabilityAuditContract

    @Test
    fun `classifyExecutionPath spineParticipationKind takeover_interactive gives TAKEOVER_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.TAKEOVER_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = "takeover_interactive",
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath spineParticipationKind delegated_execution gives DELEGATED_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.DELEGATED_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = "delegated_execution",
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath spineParticipationKind local_assistive gives LOCAL_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.LOCAL_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = "local_assistive",
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath spineParticipationKind degraded_fallback gives DEGRADED_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.DEGRADED_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = "degraded_fallback",
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath unknown spineParticipationKind gives UNKNOWN`() {
        assertEquals(
            contract.ExecutionPathTag.UNKNOWN,
            contract.classifyExecutionPath(
                spineParticipationKind = "something_unrecognized",
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath isTakeoverPath=true gives TAKEOVER_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.TAKEOVER_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = true,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath isDelegated=true gives DELEGATED_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.DELEGATED_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = true,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath cross_device_degraded mode gives DEGRADED_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.DEGRADED_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = "cross_device_degraded",
                crossDeviceEligibility = true,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath cross_device_active with eligibility=true gives CROSS_DEVICE_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.CROSS_DEVICE_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = "cross_device_active",
                crossDeviceEligibility = true,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath center_delegated_with_local_fallback gives DEGRADED_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.DEGRADED_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = "center_delegated_with_local_fallback"
            )
        )
    }

    @Test
    fun `classifyExecutionPath local_only mode with no cross-device gives LOCAL_PATH`() {
        assertEquals(
            contract.ExecutionPathTag.LOCAL_PATH,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = "local_only",
                crossDeviceEligibility = false,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    @Test
    fun `classifyExecutionPath all-null inputs gives UNKNOWN`() {
        assertEquals(
            contract.ExecutionPathTag.UNKNOWN,
            contract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = null,
                crossDeviceEligibility = null,
                isTakeoverPath = false,
                isDelegated = false,
                currentFallbackTier = null
            )
        )
    }

    // ── classifyAuditContribution ─────────────────────────────────────────────

    @Test
    fun `classifyAuditContribution participation snapshot non-recovery gives PARTICIPATION_ATTESTATION`() {
        assertEquals(
            contract.AuditContributionClass.PARTICIPATION_ATTESTATION,
            contract.classifyAuditContribution(
                eventPhase = null,
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = true,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution participation snapshot with recovery gives RECOVERY_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.RECOVERY_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = null,
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = true,
                isParticipationSnapshot = true,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution operator action event gives OPERATOR_ACTION_OUTCOME`() {
        assertEquals(
            contract.AuditContributionClass.OPERATOR_ACTION_OUTCOME,
            contract.classifyAuditContribution(
                eventPhase = null,
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = true
            )
        )
    }

    @Test
    fun `classifyAuditContribution interruption phase gives INTERRUPTION_RECORD`() {
        assertEquals(
            contract.AuditContributionClass.INTERRUPTION_RECORD,
            contract.classifyAuditContribution(
                eventPhase = "failed",
                isTerminalPhase = true,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = true,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution recovery phase non-snapshot gives RECOVERY_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.RECOVERY_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "fallback_transition",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = true,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution takeover event gives TAKEOVER_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.TAKEOVER_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "takeover_milestone",
                isTerminalPhase = false,
                isTakeoverEvent = true,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution delegated event gives DELEGATED_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.DELEGATED_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "execution_started",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = true,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution terminal phase gives EXECUTION_OUTCOME`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_OUTCOME,
            contract.classifyAuditContribution(
                eventPhase = "completed",
                isTerminalPhase = true,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution execution_started phase gives EXECUTION_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "execution_started",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution execution_progress phase gives EXECUTION_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "execution_progress",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution completed phase gives EXECUTION_OUTCOME`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_OUTCOME,
            contract.classifyAuditContribution(
                eventPhase = "completed",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution failed phase gives EXECUTION_OUTCOME`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_OUTCOME,
            contract.classifyAuditContribution(
                eventPhase = "failed",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution cancelled phase gives EXECUTION_OUTCOME`() {
        assertEquals(
            contract.AuditContributionClass.EXECUTION_OUTCOME,
            contract.classifyAuditContribution(
                eventPhase = "cancelled",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution fallback_transition phase gives RECOVERY_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.RECOVERY_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "fallback_transition",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution takeover_milestone phase gives TAKEOVER_CONTRIBUTION`() {
        assertEquals(
            contract.AuditContributionClass.TAKEOVER_CONTRIBUTION,
            contract.classifyAuditContribution(
                eventPhase = "takeover_milestone",
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    @Test
    fun `classifyAuditContribution null phase non-snapshot gives INFORMATIONAL`() {
        assertEquals(
            contract.AuditContributionClass.INFORMATIONAL,
            contract.classifyAuditContribution(
                eventPhase = null,
                isTerminalPhase = false,
                isTakeoverEvent = false,
                isDelegatedEvent = false,
                isInterruptionPhase = false,
                isRecoveryPhase = false,
                isParticipationSnapshot = false,
                isOperatorActionEvent = false
            )
        )
    }

    // ── classifyObservabilityReliability ──────────────────────────────────────

    @Test
    fun `classifyObservabilityReliability recovering state gives INTERRUPTED`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.INTERRUPTED,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "positive_evidence",
                degradedConditionClass = "nominal",
                reconnectRecoveryState = "recovering",
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability cached_state basis gives STALE`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.STALE,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "positive_evidence",
                degradedConditionClass = "nominal",
                reconnectRecoveryState = null,
                localObservationBasis = "cached_state"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability positive_evidence nominal gives HIGH_FIDELITY`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.HIGH_FIDELITY,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "positive_evidence",
                degradedConditionClass = "nominal",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability positive_evidence degraded gives REDUCED_FIDELITY`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.REDUCED_FIDELITY,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "positive_evidence",
                degradedConditionClass = "degraded",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability positive_evidence recovered gives HIGH_FIDELITY`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.HIGH_FIDELITY,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "positive_evidence",
                degradedConditionClass = "recovered",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability delayed evidence gives STALE`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.STALE,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "delayed",
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability partial evidence gives REDUCED_FIDELITY`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.REDUCED_FIDELITY,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "partial",
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability unknown evidence gives UNKNOWN`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.UNKNOWN,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "unknown",
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability unavailable evidence gives INTERRUPTED`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.INTERRUPTED,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "unavailable",
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability failed_observation evidence gives INTERRUPTED`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.INTERRUPTED,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = "failed_observation",
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability null evidence degraded condition gives REDUCED_FIDELITY`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.REDUCED_FIDELITY,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = null,
                degradedConditionClass = "degraded",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability null evidence recovering condition gives INTERRUPTED`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.INTERRUPTED,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = null,
                degradedConditionClass = "recovering",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability null evidence nominal condition gives UNKNOWN`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.UNKNOWN,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = null,
                degradedConditionClass = "nominal",
                reconnectRecoveryState = null,
                localObservationBasis = "live_runtime"
            )
        )
    }

    @Test
    fun `classifyObservabilityReliability all-null inputs gives UNKNOWN`() {
        assertEquals(
            contract.ObservabilityReliabilityClass.UNKNOWN,
            contract.classifyObservabilityReliability(
                evidencePresenceKind = null,
                degradedConditionClass = null,
                reconnectRecoveryState = null,
                localObservationBasis = null
            )
        )
    }

    // ── OBSERVABILITY_AUDIT_INVARIANTS ────────────────────────────────────────

    @Test
    fun `all_invariants_hold is true`() {
        assertTrue(contract.OBSERVABILITY_AUDIT_INVARIANTS["all_invariants_hold"] == true)
    }

    @Test
    fun `invariant count is 10`() {
        assertEquals(10, contract.OBSERVABILITY_AUDIT_INVARIANTS.size)
    }

    @Test
    fun `all individual invariants are true`() {
        contract.OBSERVABILITY_AUDIT_INVARIANTS.forEach { (key, value) ->
            assertTrue("Invariant '$key' should be true", value)
        }
    }

    // ── StabilizationBaseline ─────────────────────────────────────────────────

    @Test
    fun `android-runtime-observability-audit-contract is registered in StabilizationBaseline`() {
        assertNotNull(
            StabilizationBaseline.forId("android-runtime-observability-audit-contract")
        )
    }

    @Test
    fun `android-runtime-observability-audit-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-runtime-observability-audit-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
    }

    @Test
    fun `android-runtime-observability-audit-contract extensionGuidance is EXTEND`() {
        val entry = StabilizationBaseline.forId("android-runtime-observability-audit-contract")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }

    // ── Wire field constants ──────────────────────────────────────────────────

    @Test
    fun `FIELD_EXECUTION_PATH_TAG is non-blank`() {
        assertTrue(contract.FIELD_EXECUTION_PATH_TAG.isNotBlank())
    }

    @Test
    fun `FIELD_AUDIT_CONTRIBUTION_CLASS is non-blank`() {
        assertTrue(contract.FIELD_AUDIT_CONTRIBUTION_CLASS.isNotBlank())
    }

    @Test
    fun `FIELD_OBSERVABILITY_RELIABILITY_CLASS is non-blank`() {
        assertTrue(contract.FIELD_OBSERVABILITY_RELIABILITY_CLASS.isNotBlank())
    }

    @Test
    fun `FIELD_OBSERVABILITY_AUDIT_SCHEMA_VERSION is non-blank`() {
        assertTrue(contract.FIELD_OBSERVABILITY_AUDIT_SCHEMA_VERSION.isNotBlank())
    }

    // ── SCHEMA_VERSION ────────────────────────────────────────────────────────

    @Test
    fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", contract.SCHEMA_VERSION)
    }

    // ── INTRODUCED_PR ─────────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 89`() {
        assertEquals(89, contract.INTRODUCED_PR)
    }
}
