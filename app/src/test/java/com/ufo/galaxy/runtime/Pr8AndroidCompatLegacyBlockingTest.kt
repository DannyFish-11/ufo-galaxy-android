package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-8 (Android) — Unit tests for [AndroidCompatLegacyBlockingParticipant],
 * [CompatLegacyInfluenceClass], and [CompatLegacyBlockingDecision].
 *
 * ## Test matrix
 *
 * ### AndroidCompatLegacyBlockingParticipant — constants
 *  - DECISION_SUPPRESS_LEGACY_EMIT wire value is "suppress_legacy_emit"
 *  - DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION wire value is "block_noncanonical_runtime_transition"
 *  - DECISION_QUARANTINE_LEGACY_EXECUTION_STATE wire value is "quarantine_legacy_execution_state"
 *  - DECISION_ALLOW_OBSERVATION_ONLY wire value is "allow_observation_only"
 *  - DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION wire value is "confirm_canonical_runtime_transition"
 *  - All five decision constants are distinct
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_TRUTH_OWNER is "AndroidLocalTruthOwnershipCoordinator"
 *  - INTEGRATION_RESULT_CONVERGENCE is "AndroidFlowAwareResultConvergenceParticipant"
 *  - INTEGRATION_COMPAT_VALIDATOR is "ExecutionContractCompatibilityValidator"
 *  - INTEGRATION_RECONNECT_RECOVERY is "AndroidRecoveryParticipationOwner"
 *  - INTEGRATION_EXECUTION_EVENT_OWNER is "AndroidCanonicalExecutionEventOwner"
 *  - INTRODUCED_PR is 8
 *  - DESCRIPTION is non-blank
 *
 * ### CompatLegacyInfluenceClass — wire values
 *  - ALLOW_FOR_OBSERVATION_ONLY wireValue is "allow_for_observation_only"
 *  - BLOCK_DUE_TO_LEGACY_RUNTIME_PATH wireValue is "block_due_to_legacy_runtime_path"
 *  - BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE wireValue is "block_due_to_compat_contract_influence"
 *  - QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE wireValue is "quarantine_due_to_ambiguous_legacy_state"
 *  - CANONICAL_RUNTIME_PATH_CONFIRMED wireValue is "canonical_runtime_path_confirmed"
 *  - All five wire values are distinct
 *
 * ### CompatLegacyInfluenceClass.fromValue — classification
 *  - "allow_for_observation_only" → ALLOW_FOR_OBSERVATION_ONLY
 *  - "block_due_to_legacy_runtime_path" → BLOCK_DUE_TO_LEGACY_RUNTIME_PATH
 *  - "block_due_to_compat_contract_influence" → BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE
 *  - "quarantine_due_to_ambiguous_legacy_state" → QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE
 *  - "canonical_runtime_path_confirmed" → CANONICAL_RUNTIME_PATH_CONFIRMED
 *  - unknown value → null
 *
 * ### classifyInfluence
 *  - "allow_for_observation_only" → ALLOW_FOR_OBSERVATION_ONLY
 *  - "canonical_runtime_path_confirmed" → CANONICAL_RUNTIME_PATH_CONFIRMED
 *  - unknown string → BLOCK_DUE_TO_LEGACY_RUNTIME_PATH (safe default)
 *
 * ### evaluateBlockingDecision — ConfirmCanonicalRuntimeTransition
 *  - Returns ConfirmCanonicalRuntimeTransition for CANONICAL_RUNTIME_PATH_CONFIRMED
 *  - ConfirmCanonicalRuntimeTransition.semanticTag is DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION
 *  - ConfirmCanonicalRuntimeTransition.unitId matches context unitId
 *
 * ### evaluateBlockingDecision — QuarantineLegacyExecutionState
 *  - Returns QuarantineLegacyExecutionState for QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE
 *  - QuarantineLegacyExecutionState.semanticTag is DECISION_QUARANTINE_LEGACY_EXECUTION_STATE
 *  - QuarantineLegacyExecutionState.unitId matches context unitId
 *  - QuarantineLegacyExecutionState.legacyStateTag matches context legacyStateTag
 *  - QuarantineLegacyExecutionState.reason matches context reason
 *  - Quarantine registers the unit in the quarantine registry
 *  - Quarantine with no legacyStateTag uses LEGACY_STATE_TAG_UNKNOWN fallback
 *  - Quarantine with no reason uses REASON_QUARANTINE_AMBIGUOUS_LEGACY_STATE fallback
 *
 * ### evaluateBlockingDecision — BlockNoncanonicalRuntimeTransition
 *  - Returns BlockNoncanonicalRuntimeTransition for BLOCK_DUE_TO_LEGACY_RUNTIME_PATH
 *  - BlockNoncanonicalRuntimeTransition.semanticTag is DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION
 *  - BlockNoncanonicalRuntimeTransition.unitId matches context unitId
 *  - BlockNoncanonicalRuntimeTransition.legacyPathTag matches context legacyPathTag
 *  - BlockNoncanonicalRuntimeTransition.reason matches context reason
 *  - Block registers the legacyPathTag in the blocked path registry
 *  - Block with no legacyPathTag uses LEGACY_PATH_TAG_UNKNOWN fallback
 *  - Block with no reason uses REASON_BLOCK_NONCANONICAL_RUNTIME_PATH fallback
 *
 * ### evaluateBlockingDecision — SuppressLegacyEmit
 *  - Returns SuppressLegacyEmit for BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE
 *  - SuppressLegacyEmit.semanticTag is DECISION_SUPPRESS_LEGACY_EMIT
 *  - SuppressLegacyEmit.unitId matches context unitId
 *  - SuppressLegacyEmit.reason matches context reason
 *  - SuppressLegacyEmit with no reason uses REASON_SUPPRESS_COMPAT_CONTRACT_INFLUENCE fallback
 *
 * ### evaluateBlockingDecision — AllowObservationOnly
 *  - Returns AllowObservationOnly for ALLOW_FOR_OBSERVATION_ONLY
 *  - AllowObservationOnly.semanticTag is DECISION_ALLOW_OBSERVATION_ONLY
 *  - AllowObservationOnly.unitId matches context unitId
 *  - AllowObservationOnly.note matches context reason when provided
 *  - AllowObservationOnly.note is null when no reason supplied
 *
 * ### Quarantined unit registry
 *  - isUnitQuarantined is false initially
 *  - isUnitQuarantined is true after registerQuarantinedUnit
 *  - quarantinedStateTag is null when not quarantined
 *  - quarantinedStateTag returns the registered tag after registerQuarantinedUnit
 *  - isUnitQuarantined is false after releaseQuarantinedUnit
 *  - quarantinedUnitCount is 0 initially
 *  - quarantinedUnitCount increments with registerQuarantinedUnit
 *  - quarantinedUnitCount decrements with releaseQuarantinedUnit
 *  - clearAllQuarantinedUnits resets count to 0
 *  - Multiple distinct unitIds can all be quarantined simultaneously
 *
 * ### Blocked path registry
 *  - isPathBlocked is false initially
 *  - isPathBlocked is true after registerBlockedPath
 *  - blockedPathCount is 0 initially
 *  - blockedPathCount increments with registerBlockedPath
 *  - clearAllBlockedPaths resets count to 0
 *  - Multiple distinct path tags can all be registered simultaneously
 *
 * ### CompatLegacyBlockingDecision — semanticTag values
 *  - SuppressLegacyEmit.semanticTag matches DECISION_SUPPRESS_LEGACY_EMIT
 *  - BlockNoncanonicalRuntimeTransition.semanticTag matches DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION
 *  - QuarantineLegacyExecutionState.semanticTag matches DECISION_QUARANTINE_LEGACY_EXECUTION_STATE
 *  - AllowObservationOnly.semanticTag matches DECISION_ALLOW_OBSERVATION_ONLY
 *  - ConfirmCanonicalRuntimeTransition.semanticTag matches DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION
 *  - All five semanticTag values are distinct
 */
class Pr8AndroidCompatLegacyBlockingTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var participant: AndroidCompatLegacyBlockingParticipant

    @Before
    fun setUp() {
        participant = AndroidCompatLegacyBlockingParticipant()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeContext(
        unitId: String = "unit-8",
        influenceClass: CompatLegacyInfluenceClass = CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED,
        legacyPathTag: String? = "legacy_executor_path",
        legacyStateTag: String? = "prior_reconnect_era_state",
        reason: String? = "test reason",
        integrationPoint: String? = AndroidCompatLegacyBlockingParticipant.INTEGRATION_RECEIVER
    ) = AndroidCompatLegacyBlockingParticipant.BlockingEvalContext(
        unitId = unitId,
        influenceClass = influenceClass,
        legacyPathTag = legacyPathTag,
        legacyStateTag = legacyStateTag,
        reason = reason,
        integrationPoint = integrationPoint
    )

    // ══════════════════════════════════════════════════════════════════════════
    // AndroidCompatLegacyBlockingParticipant — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DECISION_SUPPRESS_LEGACY_EMIT wire value is suppress_legacy_emit`() {
        assertEquals(
            "suppress_legacy_emit",
            AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT
        )
    }

    @Test
    fun `DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION wire value is block_noncanonical_runtime_transition`() {
        assertEquals(
            "block_noncanonical_runtime_transition",
            AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION
        )
    }

    @Test
    fun `DECISION_QUARANTINE_LEGACY_EXECUTION_STATE wire value is quarantine_legacy_execution_state`() {
        assertEquals(
            "quarantine_legacy_execution_state",
            AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE
        )
    }

    @Test
    fun `DECISION_ALLOW_OBSERVATION_ONLY wire value is allow_observation_only`() {
        assertEquals(
            "allow_observation_only",
            AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY
        )
    }

    @Test
    fun `DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION wire value is confirm_canonical_runtime_transition`() {
        assertEquals(
            "confirm_canonical_runtime_transition",
            AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION
        )
    }

    @Test
    fun `All five decision constants are distinct`() {
        val constants = listOf(
            AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT,
            AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION,
            AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE,
            AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY,
            AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION
        )
        assertEquals("All five decision constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals("DelegatedRuntimeReceiver", AndroidCompatLegacyBlockingParticipant.INTEGRATION_RECEIVER)
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals("AutonomousExecutionPipeline", AndroidCompatLegacyBlockingParticipant.INTEGRATION_PIPELINE)
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals("DelegatedActivationRecord", AndroidCompatLegacyBlockingParticipant.INTEGRATION_ACTIVATION_RECORD)
    }

    @Test
    fun `INTEGRATION_TRUTH_OWNER is AndroidLocalTruthOwnershipCoordinator`() {
        assertEquals(
            "AndroidLocalTruthOwnershipCoordinator",
            AndroidCompatLegacyBlockingParticipant.INTEGRATION_TRUTH_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RESULT_CONVERGENCE is AndroidFlowAwareResultConvergenceParticipant`() {
        assertEquals(
            "AndroidFlowAwareResultConvergenceParticipant",
            AndroidCompatLegacyBlockingParticipant.INTEGRATION_RESULT_CONVERGENCE
        )
    }

    @Test
    fun `INTEGRATION_COMPAT_VALIDATOR is ExecutionContractCompatibilityValidator`() {
        assertEquals(
            "ExecutionContractCompatibilityValidator",
            AndroidCompatLegacyBlockingParticipant.INTEGRATION_COMPAT_VALIDATOR
        )
    }

    @Test
    fun `INTEGRATION_RECONNECT_RECOVERY is AndroidRecoveryParticipationOwner`() {
        assertEquals(
            "AndroidRecoveryParticipationOwner",
            AndroidCompatLegacyBlockingParticipant.INTEGRATION_RECONNECT_RECOVERY
        )
    }

    @Test
    fun `INTEGRATION_EXECUTION_EVENT_OWNER is AndroidCanonicalExecutionEventOwner`() {
        assertEquals(
            "AndroidCanonicalExecutionEventOwner",
            AndroidCompatLegacyBlockingParticipant.INTEGRATION_EXECUTION_EVENT_OWNER
        )
    }

    @Test
    fun `INTRODUCED_PR is 8`() {
        assertEquals(8, AndroidCompatLegacyBlockingParticipant.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidCompatLegacyBlockingParticipant.DESCRIPTION.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CompatLegacyInfluenceClass — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ALLOW_FOR_OBSERVATION_ONLY wireValue is allow_for_observation_only`() {
        assertEquals("allow_for_observation_only", CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY.wireValue)
    }

    @Test
    fun `BLOCK_DUE_TO_LEGACY_RUNTIME_PATH wireValue is block_due_to_legacy_runtime_path`() {
        assertEquals(
            "block_due_to_legacy_runtime_path",
            CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH.wireValue
        )
    }

    @Test
    fun `BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE wireValue is block_due_to_compat_contract_influence`() {
        assertEquals(
            "block_due_to_compat_contract_influence",
            CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE.wireValue
        )
    }

    @Test
    fun `QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE wireValue is quarantine_due_to_ambiguous_legacy_state`() {
        assertEquals(
            "quarantine_due_to_ambiguous_legacy_state",
            CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE.wireValue
        )
    }

    @Test
    fun `CANONICAL_RUNTIME_PATH_CONFIRMED wireValue is canonical_runtime_path_confirmed`() {
        assertEquals(
            "canonical_runtime_path_confirmed",
            CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED.wireValue
        )
    }

    @Test
    fun `All five influence class wire values are distinct`() {
        val values = CompatLegacyInfluenceClass.entries.map { it.wireValue }
        assertEquals("All five wire values must be distinct", values.size, values.toSet().size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CompatLegacyInfluenceClass.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `fromValue allow_for_observation_only returns ALLOW_FOR_OBSERVATION_ONLY`() {
        assertEquals(
            CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY,
            CompatLegacyInfluenceClass.fromValue("allow_for_observation_only")
        )
    }

    @Test
    fun `fromValue block_due_to_legacy_runtime_path returns BLOCK_DUE_TO_LEGACY_RUNTIME_PATH`() {
        assertEquals(
            CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
            CompatLegacyInfluenceClass.fromValue("block_due_to_legacy_runtime_path")
        )
    }

    @Test
    fun `fromValue block_due_to_compat_contract_influence returns BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE`() {
        assertEquals(
            CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE,
            CompatLegacyInfluenceClass.fromValue("block_due_to_compat_contract_influence")
        )
    }

    @Test
    fun `fromValue quarantine_due_to_ambiguous_legacy_state returns QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE`() {
        assertEquals(
            CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
            CompatLegacyInfluenceClass.fromValue("quarantine_due_to_ambiguous_legacy_state")
        )
    }

    @Test
    fun `fromValue canonical_runtime_path_confirmed returns CANONICAL_RUNTIME_PATH_CONFIRMED`() {
        assertEquals(
            CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED,
            CompatLegacyInfluenceClass.fromValue("canonical_runtime_path_confirmed")
        )
    }

    @Test
    fun `fromValue unknown returns null`() {
        assertNull(CompatLegacyInfluenceClass.fromValue("totally_unknown_value"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // classifyInfluence
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `classifyInfluence allow_for_observation_only returns ALLOW_FOR_OBSERVATION_ONLY`() {
        assertEquals(
            CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY,
            participant.classifyInfluence("allow_for_observation_only")
        )
    }

    @Test
    fun `classifyInfluence canonical_runtime_path_confirmed returns CANONICAL_RUNTIME_PATH_CONFIRMED`() {
        assertEquals(
            CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED,
            participant.classifyInfluence("canonical_runtime_path_confirmed")
        )
    }

    @Test
    fun `classifyInfluence unknown string returns BLOCK_DUE_TO_LEGACY_RUNTIME_PATH`() {
        assertEquals(
            CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
            participant.classifyInfluence("completely_unknown")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateBlockingDecision — ConfirmCanonicalRuntimeTransition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns ConfirmCanonicalRuntimeTransition for CANONICAL_RUNTIME_PATH_CONFIRMED`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED)
        )
        assertTrue(decision is CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition)
    }

    @Test
    fun `ConfirmCanonicalRuntimeTransition semanticTag is DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED)
        ) as CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION,
            decision.semanticTag
        )
    }

    @Test
    fun `ConfirmCanonicalRuntimeTransition unitId matches context unitId`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-canonical",
                influenceClass = CompatLegacyInfluenceClass.CANONICAL_RUNTIME_PATH_CONFIRMED
            )
        ) as CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition
        assertEquals("unit-canonical", decision.unitId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateBlockingDecision — QuarantineLegacyExecutionState
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns QuarantineLegacyExecutionState for QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                legacyStateTag = "incomplete_replay_state"
            )
        )
        assertTrue(decision is CompatLegacyBlockingDecision.QuarantineLegacyExecutionState)
    }

    @Test
    fun `QuarantineLegacyExecutionState semanticTag is DECISION_QUARANTINE_LEGACY_EXECUTION_STATE`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE)
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE,
            decision.semanticTag
        )
    }

    @Test
    fun `QuarantineLegacyExecutionState unitId matches context unitId`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-quarantine",
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE
            )
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals("unit-quarantine", decision.unitId)
    }

    @Test
    fun `QuarantineLegacyExecutionState legacyStateTag matches context legacyStateTag`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                legacyStateTag = "prior_reconnect_era_state"
            )
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals("prior_reconnect_era_state", decision.legacyStateTag)
    }

    @Test
    fun `QuarantineLegacyExecutionState reason matches context reason`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                reason = "interrupted recovery detected"
            )
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals("interrupted recovery detected", decision.reason)
    }

    @Test
    fun `Quarantine registers the unit in the quarantine registry`() {
        participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-q",
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                legacyStateTag = "state-q"
            )
        )
        assertTrue(participant.isUnitQuarantined("unit-q"))
    }

    @Test
    fun `Quarantine with no legacyStateTag uses LEGACY_STATE_TAG_UNKNOWN fallback`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                legacyStateTag = null
            )
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals(AndroidCompatLegacyBlockingParticipant.LEGACY_STATE_TAG_UNKNOWN, decision.legacyStateTag)
    }

    @Test
    fun `Quarantine with no reason uses REASON_QUARANTINE_AMBIGUOUS_LEGACY_STATE fallback`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE,
                reason = null
            )
        ) as CompatLegacyBlockingDecision.QuarantineLegacyExecutionState
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.REASON_QUARANTINE_AMBIGUOUS_LEGACY_STATE,
            decision.reason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateBlockingDecision — BlockNoncanonicalRuntimeTransition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns BlockNoncanonicalRuntimeTransition for BLOCK_DUE_TO_LEGACY_RUNTIME_PATH`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH)
        )
        assertTrue(decision is CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition)
    }

    @Test
    fun `BlockNoncanonicalRuntimeTransition semanticTag is DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH)
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION,
            decision.semanticTag
        )
    }

    @Test
    fun `BlockNoncanonicalRuntimeTransition unitId matches context unitId`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-block",
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH
            )
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals("unit-block", decision.unitId)
    }

    @Test
    fun `BlockNoncanonicalRuntimeTransition legacyPathTag matches context legacyPathTag`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
                legacyPathTag = "old_executor_path"
            )
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals("old_executor_path", decision.legacyPathTag)
    }

    @Test
    fun `BlockNoncanonicalRuntimeTransition reason matches context reason`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
                reason = "legacy executor bypasses V2 flow controls"
            )
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals("legacy executor bypasses V2 flow controls", decision.reason)
    }

    @Test
    fun `Block registers the legacyPathTag in the blocked path registry`() {
        participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
                legacyPathTag = "old_pipeline_activation"
            )
        )
        assertTrue(participant.isPathBlocked("old_pipeline_activation"))
    }

    @Test
    fun `Block with no legacyPathTag uses LEGACY_PATH_TAG_UNKNOWN fallback`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
                legacyPathTag = null
            )
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals(AndroidCompatLegacyBlockingParticipant.LEGACY_PATH_TAG_UNKNOWN, decision.legacyPathTag)
    }

    @Test
    fun `Block with no reason uses REASON_BLOCK_NONCANONICAL_RUNTIME_PATH fallback`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_LEGACY_RUNTIME_PATH,
                reason = null
            )
        ) as CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.REASON_BLOCK_NONCANONICAL_RUNTIME_PATH,
            decision.reason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateBlockingDecision — SuppressLegacyEmit
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressLegacyEmit for BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE)
        )
        assertTrue(decision is CompatLegacyBlockingDecision.SuppressLegacyEmit)
    }

    @Test
    fun `SuppressLegacyEmit semanticTag is DECISION_SUPPRESS_LEGACY_EMIT`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE)
        ) as CompatLegacyBlockingDecision.SuppressLegacyEmit
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressLegacyEmit unitId matches context unitId`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-suppress",
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE
            )
        ) as CompatLegacyBlockingDecision.SuppressLegacyEmit
        assertEquals("unit-suppress", decision.unitId)
    }

    @Test
    fun `SuppressLegacyEmit reason matches context reason`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE,
                reason = "old validator accepted payload that canonical validator rejects"
            )
        ) as CompatLegacyBlockingDecision.SuppressLegacyEmit
        assertEquals("old validator accepted payload that canonical validator rejects", decision.reason)
    }

    @Test
    fun `SuppressLegacyEmit with no reason uses REASON_SUPPRESS_COMPAT_CONTRACT_INFLUENCE fallback`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE,
                reason = null
            )
        ) as CompatLegacyBlockingDecision.SuppressLegacyEmit
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.REASON_SUPPRESS_COMPAT_CONTRACT_INFLUENCE,
            decision.reason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateBlockingDecision — AllowObservationOnly
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns AllowObservationOnly for ALLOW_FOR_OBSERVATION_ONLY`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY)
        )
        assertTrue(decision is CompatLegacyBlockingDecision.AllowObservationOnly)
    }

    @Test
    fun `AllowObservationOnly semanticTag is DECISION_ALLOW_OBSERVATION_ONLY`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(influenceClass = CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY)
        ) as CompatLegacyBlockingDecision.AllowObservationOnly
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY,
            decision.semanticTag
        )
    }

    @Test
    fun `AllowObservationOnly unitId matches context unitId`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                unitId = "unit-obs",
                influenceClass = CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY
            )
        ) as CompatLegacyBlockingDecision.AllowObservationOnly
        assertEquals("unit-obs", decision.unitId)
    }

    @Test
    fun `AllowObservationOnly note matches context reason when provided`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY,
                reason = "diagnostic surface for legacy status"
            )
        ) as CompatLegacyBlockingDecision.AllowObservationOnly
        assertEquals("diagnostic surface for legacy status", decision.note)
    }

    @Test
    fun `AllowObservationOnly note is null when no reason supplied`() {
        val decision = participant.evaluateBlockingDecision(
            makeContext(
                influenceClass = CompatLegacyInfluenceClass.ALLOW_FOR_OBSERVATION_ONLY,
                reason = null
            )
        ) as CompatLegacyBlockingDecision.AllowObservationOnly
        assertNull(decision.note)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Quarantined unit registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isUnitQuarantined is false initially`() {
        assertFalse(participant.isUnitQuarantined("unit-x"))
    }

    @Test
    fun `isUnitQuarantined is true after registerQuarantinedUnit`() {
        participant.registerQuarantinedUnit("unit-x", "legacy_state_x")
        assertTrue(participant.isUnitQuarantined("unit-x"))
    }

    @Test
    fun `quarantinedStateTag is null when not quarantined`() {
        assertNull(participant.quarantinedStateTag("unit-x"))
    }

    @Test
    fun `quarantinedStateTag returns the registered tag after registerQuarantinedUnit`() {
        participant.registerQuarantinedUnit("unit-x", "my_legacy_state")
        assertEquals("my_legacy_state", participant.quarantinedStateTag("unit-x"))
    }

    @Test
    fun `isUnitQuarantined is false after releaseQuarantinedUnit`() {
        participant.registerQuarantinedUnit("unit-x", "state")
        participant.releaseQuarantinedUnit("unit-x")
        assertFalse(participant.isUnitQuarantined("unit-x"))
    }

    @Test
    fun `quarantinedUnitCount is 0 initially`() {
        assertEquals(0, participant.quarantinedUnitCount)
    }

    @Test
    fun `quarantinedUnitCount increments with registerQuarantinedUnit`() {
        participant.registerQuarantinedUnit("unit-a", "state-a")
        assertEquals(1, participant.quarantinedUnitCount)
        participant.registerQuarantinedUnit("unit-b", "state-b")
        assertEquals(2, participant.quarantinedUnitCount)
    }

    @Test
    fun `quarantinedUnitCount decrements with releaseQuarantinedUnit`() {
        participant.registerQuarantinedUnit("unit-a", "state-a")
        participant.registerQuarantinedUnit("unit-b", "state-b")
        participant.releaseQuarantinedUnit("unit-a")
        assertEquals(1, participant.quarantinedUnitCount)
    }

    @Test
    fun `clearAllQuarantinedUnits resets count to 0`() {
        participant.registerQuarantinedUnit("unit-a", "state-a")
        participant.registerQuarantinedUnit("unit-b", "state-b")
        participant.clearAllQuarantinedUnits()
        assertEquals(0, participant.quarantinedUnitCount)
    }

    @Test
    fun `Multiple distinct unitIds can all be quarantined simultaneously`() {
        participant.registerQuarantinedUnit("unit-1", "s1")
        participant.registerQuarantinedUnit("unit-2", "s2")
        participant.registerQuarantinedUnit("unit-3", "s3")
        assertTrue(participant.isUnitQuarantined("unit-1"))
        assertTrue(participant.isUnitQuarantined("unit-2"))
        assertTrue(participant.isUnitQuarantined("unit-3"))
        assertEquals(3, participant.quarantinedUnitCount)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Blocked path registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isPathBlocked is false initially`() {
        assertFalse(participant.isPathBlocked("some_path"))
    }

    @Test
    fun `isPathBlocked is true after registerBlockedPath`() {
        participant.registerBlockedPath("old_receiver_contract")
        assertTrue(participant.isPathBlocked("old_receiver_contract"))
    }

    @Test
    fun `blockedPathCount is 0 initially`() {
        assertEquals(0, participant.blockedPathCount)
    }

    @Test
    fun `blockedPathCount increments with registerBlockedPath`() {
        participant.registerBlockedPath("path-a")
        assertEquals(1, participant.blockedPathCount)
        participant.registerBlockedPath("path-b")
        assertEquals(2, participant.blockedPathCount)
    }

    @Test
    fun `clearAllBlockedPaths resets count to 0`() {
        participant.registerBlockedPath("path-a")
        participant.registerBlockedPath("path-b")
        participant.clearAllBlockedPaths()
        assertEquals(0, participant.blockedPathCount)
    }

    @Test
    fun `Multiple distinct path tags can all be registered simultaneously`() {
        participant.registerBlockedPath("legacy_executor")
        participant.registerBlockedPath("legacy_receiver_contract")
        participant.registerBlockedPath("old_pipeline_activation")
        assertTrue(participant.isPathBlocked("legacy_executor"))
        assertTrue(participant.isPathBlocked("legacy_receiver_contract"))
        assertTrue(participant.isPathBlocked("old_pipeline_activation"))
        assertEquals(3, participant.blockedPathCount)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CompatLegacyBlockingDecision — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SuppressLegacyEmit semanticTag matches DECISION_SUPPRESS_LEGACY_EMIT`() {
        val d = CompatLegacyBlockingDecision.SuppressLegacyEmit(unitId = "u", reason = "r")
        assertEquals(AndroidCompatLegacyBlockingParticipant.DECISION_SUPPRESS_LEGACY_EMIT, d.semanticTag)
    }

    @Test
    fun `BlockNoncanonicalRuntimeTransition semanticTag matches DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION`() {
        val d = CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition(
            unitId = "u", legacyPathTag = "p", reason = "r"
        )
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_BLOCK_NONCANONICAL_RUNTIME_TRANSITION,
            d.semanticTag
        )
    }

    @Test
    fun `QuarantineLegacyExecutionState semanticTag matches DECISION_QUARANTINE_LEGACY_EXECUTION_STATE`() {
        val d = CompatLegacyBlockingDecision.QuarantineLegacyExecutionState(
            unitId = "u", legacyStateTag = "s", reason = "r"
        )
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_QUARANTINE_LEGACY_EXECUTION_STATE,
            d.semanticTag
        )
    }

    @Test
    fun `AllowObservationOnly semanticTag matches DECISION_ALLOW_OBSERVATION_ONLY`() {
        val d = CompatLegacyBlockingDecision.AllowObservationOnly(unitId = "u")
        assertEquals(AndroidCompatLegacyBlockingParticipant.DECISION_ALLOW_OBSERVATION_ONLY, d.semanticTag)
    }

    @Test
    fun `ConfirmCanonicalRuntimeTransition semanticTag matches DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION`() {
        val d = CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition(unitId = "u")
        assertEquals(
            AndroidCompatLegacyBlockingParticipant.DECISION_CONFIRM_CANONICAL_RUNTIME_TRANSITION,
            d.semanticTag
        )
    }

    @Test
    fun `All five semanticTag values are distinct`() {
        val tags = listOf(
            CompatLegacyBlockingDecision.SuppressLegacyEmit(unitId = "u", reason = "r").semanticTag,
            CompatLegacyBlockingDecision.BlockNoncanonicalRuntimeTransition(
                unitId = "u", legacyPathTag = "p", reason = "r"
            ).semanticTag,
            CompatLegacyBlockingDecision.QuarantineLegacyExecutionState(
                unitId = "u", legacyStateTag = "s", reason = "r"
            ).semanticTag,
            CompatLegacyBlockingDecision.AllowObservationOnly(unitId = "u").semanticTag,
            CompatLegacyBlockingDecision.ConfirmCanonicalRuntimeTransition(unitId = "u").semanticTag
        )
        assertEquals("All five semanticTag values must be distinct", tags.size, tags.toSet().size)
    }
}
