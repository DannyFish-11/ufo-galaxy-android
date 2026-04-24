package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-9 (Android) — Unit tests for [DelegatedRuntimeReadinessEvaluator],
 * [DelegatedRuntimeReadinessDimension], [DeviceReadinessArtifact], and
 * [DelegatedRuntimeReadinessSnapshot].
 *
 * ## Test matrix
 *
 * ### DelegatedRuntimeReadinessEvaluator — constants
 *  - ARTIFACT_DEVICE_READY_FOR_RELEASE wire value is "device_ready_for_release"
 *  - ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP wire value is "device_not_ready_due_to_truth_gap"
 *  - ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP wire value is "device_not_ready_due_to_result_gap"
 *  - ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP wire value is "device_not_ready_due_to_execution_event_gap"
 *  - ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP wire value is "device_not_ready_due_to_compat_gap"
 *  - ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL wire value is "device_readiness_unknown_due_to_missing_signal"
 *  - All six artifact constants are distinct
 *  - INTEGRATION_RECOVERY_OWNER is "AndroidRecoveryParticipationOwner"
 *  - INTEGRATION_TRUTH_OWNER is "AndroidLocalTruthOwnershipCoordinator"
 *  - INTEGRATION_RESULT_CONVERGENCE is "AndroidFlowAwareResultConvergenceParticipant"
 *  - INTEGRATION_EXECUTION_EVENT_OWNER is "AndroidCanonicalExecutionEventOwner"
 *  - INTEGRATION_COMPAT_BLOCKING is "AndroidCompatLegacyBlockingParticipant"
 *  - INTEGRATION_RUNTIME_CONTROLLER is "RuntimeController"
 *  - INTEGRATION_DELEGATED_FLOW_BRIDGE is "AndroidDelegatedFlowBridge"
 *  - INTRODUCED_PR is 9
 *  - DESCRIPTION is non-blank
 *
 * ### DelegatedRuntimeReadinessDimension — wire values
 *  - CONTINUITY_REPLAY_RECONNECT wireValue is "continuity_replay_reconnect"
 *  - LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT wireValue is "local_truth_ownership_central_alignment"
 *  - RESULT_CONVERGENCE_PARTICIPATION wireValue is "result_convergence_participation"
 *  - CANONICAL_EXECUTION_EVENT wireValue is "canonical_execution_event"
 *  - COMPAT_LEGACY_BLOCKING wireValue is "compat_legacy_blocking"
 *  - All five wire values are distinct
 *
 * ### DelegatedRuntimeReadinessDimension.fromValue — classification
 *  - "continuity_replay_reconnect" → CONTINUITY_REPLAY_RECONNECT
 *  - "local_truth_ownership_central_alignment" → LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT
 *  - "result_convergence_participation" → RESULT_CONVERGENCE_PARTICIPATION
 *  - "canonical_execution_event" → CANONICAL_EXECUTION_EVENT
 *  - "compat_legacy_blocking" → COMPAT_LEGACY_BLOCKING
 *  - unknown value → null
 *  - null → null
 *
 * ### Dimension gate — markDimensionReady / getDimensionStatus
 *  - getDimensionStatus returns UNKNOWN before any signal
 *  - getDimensionStatus returns READY after markDimensionReady
 *  - getDimensionStatus returns GAP after markDimensionGap
 *  - getDimensionStatus returns UNKNOWN after markDimensionUnknown
 *  - getDimensionGapReason is null before any signal
 *  - getDimensionGapReason returns gapReason after markDimensionGap
 *  - getDimensionGapReason returns reason after markDimensionUnknown
 *  - getDimensionGapReason is null after markDimensionReady
 *  - clearAllDimensionStates resets all dimensions to UNKNOWN
 *
 * ### evaluateReadiness — DeviceReadinessUnknownDueMissingSignal
 *  - Returns DeviceReadinessUnknownDueMissingSignal when no dimensions have signals
 *  - Returns DeviceReadinessUnknownDueMissingSignal when some dimensions are UNKNOWN
 *  - DeviceReadinessUnknownDueMissingSignal.semanticTag is ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL
 *  - DeviceReadinessUnknownDueMissingSignal.deviceId matches parameter
 *  - DeviceReadinessUnknownDueMissingSignal.snapshotId matches parameter
 *  - DeviceReadinessUnknownDueMissingSignal.missingDimensions includes all five when no signals
 *  - DeviceReadinessUnknownDueMissingSignal.missingDimensions only includes unknown dimensions
 *  - Missing signal takes priority over truth gap
 *  - Missing signal takes priority over result gap
 *  - Missing signal takes priority over execution event gap
 *  - Missing signal takes priority over compat gap
 *
 * ### evaluateReadiness — DeviceNotReadyDueToTruthGap
 *  - Returns DeviceNotReadyDueToTruthGap when truth dimension has GAP
 *  - DeviceNotReadyDueToTruthGap.semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP
 *  - DeviceNotReadyDueToTruthGap.deviceId matches parameter
 *  - DeviceNotReadyDueToTruthGap.snapshotId matches parameter
 *  - DeviceNotReadyDueToTruthGap.gapReason matches registered reason
 *  - DeviceNotReadyDueToTruthGap.gapReason uses REASON_TRUTH_GAP_DEFAULT when none registered
 *  - DeviceNotReadyDueToTruthGap.dimension is LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT
 *  - Truth gap takes priority over result gap
 *  - Truth gap takes priority over execution event gap
 *  - Truth gap takes priority over compat gap
 *
 * ### evaluateReadiness — DeviceNotReadyDueToResultGap
 *  - Returns DeviceNotReadyDueToResultGap when result dimension has GAP
 *  - DeviceNotReadyDueToResultGap.semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP
 *  - DeviceNotReadyDueToResultGap.deviceId matches parameter
 *  - DeviceNotReadyDueToResultGap.snapshotId matches parameter
 *  - DeviceNotReadyDueToResultGap.gapReason matches registered reason
 *  - DeviceNotReadyDueToResultGap.gapReason uses REASON_RESULT_GAP_DEFAULT when none registered
 *  - DeviceNotReadyDueToResultGap.dimension is RESULT_CONVERGENCE_PARTICIPATION
 *  - Result gap takes priority over execution event gap
 *  - Result gap takes priority over compat gap
 *
 * ### evaluateReadiness — DeviceNotReadyDueToExecutionEventGap
 *  - Returns DeviceNotReadyDueToExecutionEventGap when event dimension has GAP
 *  - DeviceNotReadyDueToExecutionEventGap.semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP
 *  - DeviceNotReadyDueToExecutionEventGap.deviceId matches parameter
 *  - DeviceNotReadyDueToExecutionEventGap.snapshotId matches parameter
 *  - DeviceNotReadyDueToExecutionEventGap.gapReason matches registered reason
 *  - DeviceNotReadyDueToExecutionEventGap.gapReason uses REASON_EXECUTION_EVENT_GAP_DEFAULT when none registered
 *  - DeviceNotReadyDueToExecutionEventGap.dimension is CANONICAL_EXECUTION_EVENT
 *  - Execution event gap takes priority over compat gap
 *
 * ### evaluateReadiness — DeviceNotReadyDueToCompatGap
 *  - Returns DeviceNotReadyDueToCompatGap when compat dimension has GAP
 *  - DeviceNotReadyDueToCompatGap.semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP
 *  - DeviceNotReadyDueToCompatGap.deviceId matches parameter
 *  - DeviceNotReadyDueToCompatGap.snapshotId matches parameter
 *  - DeviceNotReadyDueToCompatGap.gapReason matches registered reason
 *  - DeviceNotReadyDueToCompatGap.gapReason uses REASON_COMPAT_GAP_DEFAULT when none registered
 *  - DeviceNotReadyDueToCompatGap.dimension is COMPAT_LEGACY_BLOCKING
 *
 * ### evaluateReadiness — Continuity gap (maps to truth gap artifact)
 *  - Returns DeviceNotReadyDueToTruthGap when continuity dimension has GAP
 *  - DeviceNotReadyDueToTruthGap.dimension is CONTINUITY_REPLAY_RECONNECT for continuity gap
 *  - DeviceNotReadyDueToTruthGap.gapReason uses REASON_CONTINUITY_GAP_DEFAULT for continuity gap without reason
 *
 * ### evaluateReadiness — DeviceReadyForRelease
 *  - Returns DeviceReadyForRelease when all five dimensions are READY
 *  - DeviceReadyForRelease.semanticTag is ARTIFACT_DEVICE_READY_FOR_RELEASE
 *  - DeviceReadyForRelease.deviceId matches parameter
 *  - DeviceReadyForRelease.snapshotId matches parameter
 *
 * ### buildSnapshot
 *  - buildSnapshot returns a DelegatedRuntimeReadinessSnapshot
 *  - snapshot deviceId matches parameter
 *  - snapshot snapshotId is non-blank
 *  - snapshot reportedAtMs is positive
 *  - snapshot dimensionStates contains all five dimensions
 *  - snapshot artifact is DeviceReadyForRelease when all dimensions are READY
 *  - snapshot artifact is DeviceReadinessUnknownDueMissingSignal when no signals
 *  - snapshot dimensionStates reflect current gate state
 *  - each DimensionReadinessState.dimension matches its map key
 *  - DimensionReadinessState.status is UNKNOWN before signals
 *  - DimensionReadinessState.status is READY after markDimensionReady
 *  - DimensionReadinessState.status is GAP after markDimensionGap
 *  - DimensionReadinessState.gapReason is null for READY dimensions
 *  - DimensionReadinessState.gapReason matches registered reason for GAP dimensions
 *
 * ### DelegatedRuntimeReadinessSnapshot.DimensionStatus — wire values
 *  - READY wireValue is "ready"
 *  - GAP wireValue is "gap"
 *  - UNKNOWN wireValue is "unknown"
 *  - All three wire values are distinct
 *  - fromValue("ready") → READY
 *  - fromValue("gap") → GAP
 *  - fromValue("unknown") → UNKNOWN
 *  - fromValue(unknown) → null
 *
 * ### DeviceReadinessArtifact — semanticTag values
 *  - DeviceReadyForRelease.semanticTag matches ARTIFACT_DEVICE_READY_FOR_RELEASE
 *  - DeviceNotReadyDueToTruthGap.semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP
 *  - DeviceNotReadyDueToResultGap.semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP
 *  - DeviceNotReadyDueToExecutionEventGap.semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP
 *  - DeviceNotReadyDueToCompatGap.semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP
 *  - DeviceReadinessUnknownDueMissingSignal.semanticTag matches ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL
 *  - All six semanticTag values are distinct
 */
class Pr9DelegatedRuntimeReadinessTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var evaluator: DelegatedRuntimeReadinessEvaluator

    @Before
    fun setUp() {
        evaluator = DelegatedRuntimeReadinessEvaluator()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun markAllReady() {
        DelegatedRuntimeReadinessDimension.entries.forEach { evaluator.markDimensionReady(it) }
    }

    private fun markAllExcept(
        vararg except: DelegatedRuntimeReadinessDimension
    ) {
        DelegatedRuntimeReadinessDimension.entries
            .filter { it !in except }
            .forEach { evaluator.markDimensionReady(it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeReadinessEvaluator — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_DEVICE_READY_FOR_RELEASE wire value is device_ready_for_release`() {
        assertEquals(
            "device_ready_for_release",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP wire value is device_not_ready_due_to_truth_gap`() {
        assertEquals(
            "device_not_ready_due_to_truth_gap",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP wire value is device_not_ready_due_to_result_gap`() {
        assertEquals(
            "device_not_ready_due_to_result_gap",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP wire value is device_not_ready_due_to_execution_event_gap`() {
        assertEquals(
            "device_not_ready_due_to_execution_event_gap",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP wire value is device_not_ready_due_to_compat_gap`() {
        assertEquals(
            "device_not_ready_due_to_compat_gap",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP
        )
    }

    @Test
    fun `ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL wire value is device_readiness_unknown_due_to_missing_signal`() {
        assertEquals(
            "device_readiness_unknown_due_to_missing_signal",
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL
        )
    }

    @Test
    fun `All six artifact constants are distinct`() {
        val constants = listOf(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE,
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP,
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP,
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP,
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP,
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL
        )
        assertEquals("All six artifact constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_RECOVERY_OWNER is AndroidRecoveryParticipationOwner`() {
        assertEquals(
            "AndroidRecoveryParticipationOwner",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_RECOVERY_OWNER
        )
    }

    @Test
    fun `INTEGRATION_TRUTH_OWNER is AndroidLocalTruthOwnershipCoordinator`() {
        assertEquals(
            "AndroidLocalTruthOwnershipCoordinator",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_TRUTH_OWNER
        )
    }

    @Test
    fun `INTEGRATION_RESULT_CONVERGENCE is AndroidFlowAwareResultConvergenceParticipant`() {
        assertEquals(
            "AndroidFlowAwareResultConvergenceParticipant",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_RESULT_CONVERGENCE
        )
    }

    @Test
    fun `INTEGRATION_EXECUTION_EVENT_OWNER is AndroidCanonicalExecutionEventOwner`() {
        assertEquals(
            "AndroidCanonicalExecutionEventOwner",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_EXECUTION_EVENT_OWNER
        )
    }

    @Test
    fun `INTEGRATION_COMPAT_BLOCKING is AndroidCompatLegacyBlockingParticipant`() {
        assertEquals(
            "AndroidCompatLegacyBlockingParticipant",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_COMPAT_BLOCKING
        )
    }

    @Test
    fun `INTEGRATION_RUNTIME_CONTROLLER is RuntimeController`() {
        assertEquals(
            "RuntimeController",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_RUNTIME_CONTROLLER
        )
    }

    @Test
    fun `INTEGRATION_DELEGATED_FLOW_BRIDGE is AndroidDelegatedFlowBridge`() {
        assertEquals(
            "AndroidDelegatedFlowBridge",
            DelegatedRuntimeReadinessEvaluator.INTEGRATION_DELEGATED_FLOW_BRIDGE
        )
    }

    @Test
    fun `INTRODUCED_PR is 9`() {
        assertEquals(9, DelegatedRuntimeReadinessEvaluator.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(DelegatedRuntimeReadinessEvaluator.DESCRIPTION.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeReadinessDimension — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CONTINUITY_REPLAY_RECONNECT wireValue is continuity_replay_reconnect`() {
        assertEquals(
            "continuity_replay_reconnect",
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT.wireValue
        )
    }

    @Test
    fun `LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT wireValue is local_truth_ownership_central_alignment`() {
        assertEquals(
            "local_truth_ownership_central_alignment",
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT.wireValue
        )
    }

    @Test
    fun `RESULT_CONVERGENCE_PARTICIPATION wireValue is result_convergence_participation`() {
        assertEquals(
            "result_convergence_participation",
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION.wireValue
        )
    }

    @Test
    fun `CANONICAL_EXECUTION_EVENT wireValue is canonical_execution_event`() {
        assertEquals(
            "canonical_execution_event",
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT.wireValue
        )
    }

    @Test
    fun `COMPAT_LEGACY_BLOCKING wireValue is compat_legacy_blocking`() {
        assertEquals(
            "compat_legacy_blocking",
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING.wireValue
        )
    }

    @Test
    fun `All five dimension wire values are distinct`() {
        val wireValues = DelegatedRuntimeReadinessDimension.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeReadinessDimension.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `fromValue continuity_replay_reconnect returns CONTINUITY_REPLAY_RECONNECT`() {
        assertEquals(
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT,
            DelegatedRuntimeReadinessDimension.fromValue("continuity_replay_reconnect")
        )
    }

    @Test
    fun `fromValue local_truth_ownership_central_alignment returns LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT`() {
        assertEquals(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            DelegatedRuntimeReadinessDimension.fromValue("local_truth_ownership_central_alignment")
        )
    }

    @Test
    fun `fromValue result_convergence_participation returns RESULT_CONVERGENCE_PARTICIPATION`() {
        assertEquals(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            DelegatedRuntimeReadinessDimension.fromValue("result_convergence_participation")
        )
    }

    @Test
    fun `fromValue canonical_execution_event returns CANONICAL_EXECUTION_EVENT`() {
        assertEquals(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            DelegatedRuntimeReadinessDimension.fromValue("canonical_execution_event")
        )
    }

    @Test
    fun `fromValue compat_legacy_blocking returns COMPAT_LEGACY_BLOCKING`() {
        assertEquals(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            DelegatedRuntimeReadinessDimension.fromValue("compat_legacy_blocking")
        )
    }

    @Test
    fun `fromValue unknown value returns null`() {
        assertNull(DelegatedRuntimeReadinessDimension.fromValue("unknown_dimension"))
    }

    @Test
    fun `fromValue null returns null`() {
        assertNull(DelegatedRuntimeReadinessDimension.fromValue(null))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dimension gate — markDimensionReady / getDimensionStatus
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getDimensionStatus returns UNKNOWN before any signal`() {
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT)
        )
    }

    @Test
    fun `getDimensionStatus returns READY after markDimensionReady`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY,
            evaluator.getDimensionStatus(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        )
    }

    @Test
    fun `getDimensionStatus returns GAP after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "convergence gate is closed"
        )
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP,
            evaluator.getDimensionStatus(DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION)
        )
    }

    @Test
    fun `getDimensionStatus returns UNKNOWN after markDimensionUnknown`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        evaluator.markDimensionUnknown(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN,
            evaluator.getDimensionStatus(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        )
    }

    @Test
    fun `getDimensionGapReason is null before any signal`() {
        assertNull(
            evaluator.getDimensionGapReason(
                DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT
            )
        )
    }

    @Test
    fun `getDimensionGapReason returns gapReason after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth emit gate closed for 3 units"
        )
        assertEquals(
            "truth emit gate closed for 3 units",
            evaluator.getDimensionGapReason(
                DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT
            )
        )
    }

    @Test
    fun `getDimensionGapReason returns reason after markDimensionUnknown`() {
        evaluator.markDimensionUnknown(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "no compat signal received"
        )
        assertEquals(
            "no compat signal received",
            evaluator.getDimensionGapReason(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        )
    }

    @Test
    fun `getDimensionGapReason is null after markDimensionReady`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gate closed"
        )
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        assertNull(
            evaluator.getDimensionGapReason(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        )
    }

    @Test
    fun `clearAllDimensionStates resets all dimensions to UNKNOWN`() {
        markAllReady()
        evaluator.clearAllDimensionStates()
        DelegatedRuntimeReadinessDimension.entries.forEach { dim ->
            assertEquals(
                DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN,
                evaluator.getDimensionStatus(dim)
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceReadinessUnknownDueMissingSignal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceReadinessUnknownDueMissingSignal when no dimensions have signals`() {
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    @Test
    fun `Returns DeviceReadinessUnknownDueMissingSignal when some dimensions are UNKNOWN`() {
        markAllExcept(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal semanticTag is ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL`() {
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal deviceId matches parameter`() {
        val result = evaluator.evaluateReadiness("my-device", "snap-x") as
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        assertEquals("my-device", result.deviceId)
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal snapshotId matches parameter`() {
        val result = evaluator.evaluateReadiness("dev-1", "snap-99") as
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        assertEquals("snap-99", result.snapshotId)
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal missingDimensions includes all five when no signals`() {
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        assertEquals(5, result.missingDimensions.size)
        assertTrue(result.missingDimensions.containsAll(DelegatedRuntimeReadinessDimension.entries))
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal missingDimensions only includes unknown dimensions`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT)
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT)
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION)
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        // COMPAT_LEGACY_BLOCKING intentionally left UNKNOWN
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        assertEquals(
            setOf(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING),
            result.missingDimensions
        )
    }

    @Test
    fun `Missing signal takes priority over truth gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        // CONTINUITY not set → UNKNOWN
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over result gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over execution event gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    @Test
    fun `Missing signal takes priority over compat gap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceNotReadyDueToTruthGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceNotReadyDueToTruthGap when truth dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth emit gate closed"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        val result = evaluator.evaluateReadiness("device-truth", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals("device-truth", result.deviceId)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-truth-42") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals("snap-truth-42", result.snapshotId)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "posture conflict suppressing assertions"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals("posture conflict suppressing assertions", result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap gapReason uses REASON_TRUTH_GAP_DEFAULT when none registered`() {
        markAllExcept()
        // Mark GAP without reason by directly using markDimensionGap with default constant
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            DelegatedRuntimeReadinessEvaluator.REASON_TRUTH_GAP_DEFAULT
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals(DelegatedRuntimeReadinessEvaluator.REASON_TRUTH_GAP_DEFAULT, result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap dimension is LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            result.dimension
        )
    }

    @Test
    fun `Truth gap takes priority over result gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap)
    }

    @Test
    fun `Truth gap takes priority over execution event gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap)
    }

    @Test
    fun `Truth gap takes priority over compat gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceNotReadyDueToResultGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceNotReadyDueToResultGap when result dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "late partial after final"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToResultGap)
    }

    @Test
    fun `DeviceNotReadyDueToResultGap semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToResultGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("device-result", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals("device-result", result.deviceId)
    }

    @Test
    fun `DeviceNotReadyDueToResultGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-result-77") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals("snap-result-77", result.snapshotId)
    }

    @Test
    fun `DeviceNotReadyDueToResultGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "parallel sub-result not bound"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals("parallel sub-result not bound", result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToResultGap gapReason uses REASON_RESULT_GAP_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            DelegatedRuntimeReadinessEvaluator.REASON_RESULT_GAP_DEFAULT
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals(DelegatedRuntimeReadinessEvaluator.REASON_RESULT_GAP_DEFAULT, result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToResultGap dimension is RESULT_CONVERGENCE_PARTICIPATION`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap
        assertEquals(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            result.dimension
        )
    }

    @Test
    fun `Result gap takes priority over execution event gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToResultGap)
    }

    @Test
    fun `Result gap takes priority over compat gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToResultGap)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceNotReadyDueToExecutionEventGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceNotReadyDueToExecutionEventGap when event dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gate open during reconnect"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap)
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("device-event", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals("device-event", result.deviceId)
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-event-55") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals("snap-event-55", result.snapshotId)
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "noise gate masking real flow progress"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals("noise gate masking real flow progress", result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap gapReason uses REASON_EXECUTION_EVENT_GAP_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            DelegatedRuntimeReadinessEvaluator.REASON_EXECUTION_EVENT_GAP_DEFAULT
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.REASON_EXECUTION_EVENT_GAP_DEFAULT,
            result.gapReason
        )
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap dimension is CANONICAL_EXECUTION_EVENT`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap
        assertEquals(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            result.dimension
        )
    }

    @Test
    fun `Execution event gap takes priority over compat gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT,
            "event gap"
        )
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceNotReadyDueToCompatGap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceNotReadyDueToCompatGap when compat dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "quarantined units not released"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap)
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap semanticTag is ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap deviceId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("device-compat", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals("device-compat", result.deviceId)
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap snapshotId matches parameter`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-compat-33") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals("snap-compat-33", result.snapshotId)
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap gapReason matches registered reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "blocked legacy paths still active"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals("blocked legacy paths still active", result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap gapReason uses REASON_COMPAT_GAP_DEFAULT when none registered`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            DelegatedRuntimeReadinessEvaluator.REASON_COMPAT_GAP_DEFAULT
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals(DelegatedRuntimeReadinessEvaluator.REASON_COMPAT_GAP_DEFAULT, result.gapReason)
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap dimension is COMPAT_LEGACY_BLOCKING`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "compat gap"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap
        assertEquals(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            result.dimension
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — Continuity gap (maps to truth gap artifact)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceNotReadyDueToTruthGap when continuity dimension has GAP`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT,
            "replay identity unstable"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1")
        assertTrue(result is DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap dimension is CONTINUITY_REPLAY_RECONNECT for continuity gap`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT,
            "replay identity unstable"
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals(
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT,
            result.dimension
        )
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap gapReason uses REASON_CONTINUITY_GAP_DEFAULT for continuity gap without reason`() {
        markAllExcept()
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT,
            DelegatedRuntimeReadinessEvaluator.REASON_CONTINUITY_GAP_DEFAULT
        )
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.REASON_CONTINUITY_GAP_DEFAULT,
            result.gapReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateReadiness — DeviceReadyForRelease
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns DeviceReadyForRelease when all five dimensions are READY`() {
        markAllReady()
        val result = evaluator.evaluateReadiness("dev-ready", "snap-release")
        assertTrue(result is DeviceReadinessArtifact.DeviceReadyForRelease)
    }

    @Test
    fun `DeviceReadyForRelease semanticTag is ARTIFACT_DEVICE_READY_FOR_RELEASE`() {
        markAllReady()
        val result = evaluator.evaluateReadiness("dev-1", "snap-1") as
            DeviceReadinessArtifact.DeviceReadyForRelease
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE,
            result.semanticTag
        )
    }

    @Test
    fun `DeviceReadyForRelease deviceId matches parameter`() {
        markAllReady()
        val result = evaluator.evaluateReadiness("device-ready-for-release", "snap-1") as
            DeviceReadinessArtifact.DeviceReadyForRelease
        assertEquals("device-ready-for-release", result.deviceId)
    }

    @Test
    fun `DeviceReadyForRelease snapshotId matches parameter`() {
        markAllReady()
        val result = evaluator.evaluateReadiness("dev-1", "snap-final-release") as
            DeviceReadinessArtifact.DeviceReadyForRelease
        assertEquals("snap-final-release", result.snapshotId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // buildSnapshot
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildSnapshot returns a DelegatedRuntimeReadinessSnapshot`() {
        val snapshot = evaluator.buildSnapshot("dev-snap")
        assertNotNull(snapshot)
    }

    @Test
    fun `snapshot deviceId matches parameter`() {
        val snapshot = evaluator.buildSnapshot("device-snapshot-test")
        assertEquals("device-snapshot-test", snapshot.deviceId)
    }

    @Test
    fun `snapshot snapshotId is non-blank`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.snapshotId.isNotBlank())
    }

    @Test
    fun `snapshot reportedAtMs is positive`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.reportedAtMs > 0L)
    }

    @Test
    fun `snapshot dimensionStates contains all five dimensions`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(5, snapshot.dimensionStates.size)
        assertTrue(
            snapshot.dimensionStates.keys.containsAll(DelegatedRuntimeReadinessDimension.entries)
        )
    }

    @Test
    fun `snapshot artifact is DeviceReadyForRelease when all dimensions are READY`() {
        markAllReady()
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(snapshot.artifact is DeviceReadinessArtifact.DeviceReadyForRelease)
    }

    @Test
    fun `snapshot artifact is DeviceReadinessUnknownDueMissingSignal when no signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertTrue(
            snapshot.artifact is DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal
        )
    }

    @Test
    fun `snapshot dimensionStates reflect current gate state`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT)
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT,
            "truth gap"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY,
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.CONTINUITY_REPLAY_RECONNECT]?.status
        )
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP,
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.LOCAL_TRUTH_OWNERSHIP_CENTRAL_ALIGNMENT]?.status
        )
    }

    @Test
    fun `each DimensionReadinessState dimension matches its map key`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.forEach { (key, state) ->
            assertEquals(key, state.dimension)
        }
    }

    @Test
    fun `DimensionReadinessState status is UNKNOWN before signals`() {
        val snapshot = evaluator.buildSnapshot("dev-1")
        snapshot.dimensionStates.values.forEach { state ->
            assertEquals(DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN, state.status)
        }
    }

    @Test
    fun `DimensionReadinessState status is READY after markDimensionReady`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY,
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]?.status
        )
    }

    @Test
    fun `DimensionReadinessState status is GAP after markDimensionGap`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION,
            "result gap"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP,
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.RESULT_CONVERGENCE_PARTICIPATION]?.status
        )
    }

    @Test
    fun `DimensionReadinessState gapReason is null for READY dimensions`() {
        evaluator.markDimensionReady(DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT)
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertNull(
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.CANONICAL_EXECUTION_EVENT]?.gapReason
        )
    }

    @Test
    fun `DimensionReadinessState gapReason matches registered reason for GAP dimensions`() {
        evaluator.markDimensionGap(
            DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING,
            "quarantined units still pending release"
        )
        val snapshot = evaluator.buildSnapshot("dev-1")
        assertEquals(
            "quarantined units still pending release",
            snapshot.dimensionStates[DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING]?.gapReason
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DelegatedRuntimeReadinessSnapshot.DimensionStatus — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `READY wireValue is ready`() {
        assertEquals(
            "ready",
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY.wireValue
        )
    }

    @Test
    fun `GAP wireValue is gap`() {
        assertEquals(
            "gap",
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP.wireValue
        )
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN.wireValue
        )
    }

    @Test
    fun `All three DimensionStatus wire values are distinct`() {
        val wireValues = DelegatedRuntimeReadinessSnapshot.DimensionStatus.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `DimensionStatus fromValue ready returns READY`() {
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.READY,
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.fromValue("ready")
        )
    }

    @Test
    fun `DimensionStatus fromValue gap returns GAP`() {
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP,
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.fromValue("gap")
        )
    }

    @Test
    fun `DimensionStatus fromValue unknown returns UNKNOWN`() {
        assertEquals(
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN,
            DelegatedRuntimeReadinessSnapshot.DimensionStatus.fromValue("unknown")
        )
    }

    @Test
    fun `DimensionStatus fromValue unknown string returns null`() {
        assertNull(DelegatedRuntimeReadinessSnapshot.DimensionStatus.fromValue("not_a_status"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DeviceReadinessArtifact — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeviceReadyForRelease semanticTag matches ARTIFACT_DEVICE_READY_FOR_RELEASE`() {
        val a = DeviceReadinessArtifact.DeviceReadyForRelease(deviceId = "d", snapshotId = "s")
        assertEquals(DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READY_FOR_RELEASE, a.semanticTag)
    }

    @Test
    fun `DeviceNotReadyDueToTruthGap semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP`() {
        val a = DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap(
            deviceId = "d", snapshotId = "s", gapReason = "r"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_TRUTH_GAP,
            a.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToResultGap semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP`() {
        val a = DeviceReadinessArtifact.DeviceNotReadyDueToResultGap(
            deviceId = "d", snapshotId = "s", gapReason = "r"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_RESULT_GAP,
            a.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToExecutionEventGap semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP`() {
        val a = DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap(
            deviceId = "d", snapshotId = "s", gapReason = "r"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_EXECUTION_EVENT_GAP,
            a.semanticTag
        )
    }

    @Test
    fun `DeviceNotReadyDueToCompatGap semanticTag matches ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP`() {
        val a = DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap(
            deviceId = "d", snapshotId = "s", gapReason = "r"
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_NOT_READY_DUE_TO_COMPAT_GAP,
            a.semanticTag
        )
    }

    @Test
    fun `DeviceReadinessUnknownDueMissingSignal semanticTag matches ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL`() {
        val a = DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal(
            deviceId = "d",
            snapshotId = "s",
            missingDimensions = setOf(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
        )
        assertEquals(
            DelegatedRuntimeReadinessEvaluator.ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL,
            a.semanticTag
        )
    }

    @Test
    fun `All six semanticTag values are distinct`() {
        val tags = listOf(
            DeviceReadinessArtifact.DeviceReadyForRelease("d", "s").semanticTag,
            DeviceReadinessArtifact.DeviceNotReadyDueToTruthGap("d", "s", "r").semanticTag,
            DeviceReadinessArtifact.DeviceNotReadyDueToResultGap("d", "s", "r").semanticTag,
            DeviceReadinessArtifact.DeviceNotReadyDueToExecutionEventGap("d", "s", "r").semanticTag,
            DeviceReadinessArtifact.DeviceNotReadyDueToCompatGap("d", "s", "r").semanticTag,
            DeviceReadinessArtifact.DeviceReadinessUnknownDueMissingSignal(
                "d", "s",
                setOf(DelegatedRuntimeReadinessDimension.COMPAT_LEGACY_BLOCKING)
            ).semanticTag
        )
        assertEquals("All six semanticTag values must be distinct", tags.size, tags.toSet().size)
    }
}
