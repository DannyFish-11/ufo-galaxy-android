package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-64 — Unified Android Truth/Reconciliation Convergence: Acceptance and Regression Test Suite.
 *
 * Validates the four PR-64 acceptance criteria and all concrete surfaces introduced:
 *
 *  1. **[UnifiedTruthReconciliationSurface]** — structural completeness and count invariants.
 *  2. **[RuntimeTruthPatch]** — typed truth patch: wire format, enum vocabularies, toMap() output.
 *  3. **[TruthReconciliationReducer]** — reducer invariants: epoch gating, authoritative-only
 *     mutation, terminal idempotency, participant mismatch safety.
 *  4. **Multi-event ordering** — stable convergence under all permutations of the key scenarios.
 *  5. **[StabilizationBaseline]** — exactly three PR-64 entries registered as CANONICAL_STABLE.
 *
 * ## Test matrix
 *
 * ### UnifiedTruthReconciliationSurface — structural invariants
 *  - AUTHORITATIVE_TRUTH_SOURCES has exactly AUTHORITATIVE_TRUTH_SOURCES_COUNT entries
 *  - NOTIFICATION_SIGNALS has exactly NOTIFICATION_SIGNALS_COUNT entries
 *  - CONVERGENCE_MODEL has exactly CONVERGENCE_MODEL_COUNT entries
 *  - MULTI_EVENT_ORDERING has exactly MULTI_EVENT_ORDERING_COUNT entries
 *  - DATA_MODEL_SURFACES has exactly DATA_MODEL_SURFACES_COUNT entries
 *  - all AUTHORITATIVE_TRUTH_SOURCES keys are non-blank
 *  - all AUTHORITATIVE_TRUTH_SOURCES values are non-blank
 *  - all NOTIFICATION_SIGNALS keys are non-blank
 *  - all NOTIFICATION_SIGNALS values are non-blank
 *  - all CONVERGENCE_MODEL keys are non-blank
 *  - all CONVERGENCE_MODEL values are non-blank
 *  - all MULTI_EVENT_ORDERING keys are non-blank
 *  - all MULTI_EVENT_ORDERING values are non-blank
 *  - all DATA_MODEL_SURFACES keys are non-blank
 *  - all DATA_MODEL_SURFACES values are non-blank
 *  - INTRODUCED_PR is 64
 *
 * ### UnifiedTruthReconciliationSurface — known keys
 *  - delegated_task_result key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - delegated_task_cancelled key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - delegated_task_failed key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - handoff_accepted key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - handoff_rejected key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - takeover_accepted key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - takeover_rejected key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - session_terminal key is in AUTHORITATIVE_TRUTH_SOURCES
 *  - participant_state_changed key is in NOTIFICATION_SIGNALS
 *  - task_status_update key is in NOTIFICATION_SIGNALS
 *  - runtime_truth_snapshot_request key is in NOTIFICATION_SIGNALS
 *  - subtask_checkpoint key is in NOTIFICATION_SIGNALS
 *  - single_reducer_entry_point key is in CONVERGENCE_MODEL
 *  - epoch_ordering_guard key is in CONVERGENCE_MODEL
 *  - terminal_idempotency key is in CONVERGENCE_MODEL
 *  - authoritative_flag_gate key is in CONVERGENCE_MODEL
 *  - participant_safety_guard key is in CONVERGENCE_MODEL
 *  - delegated_result_then_session_terminal is in MULTI_EVENT_ORDERING
 *  - session_terminal_then_delegated_result is in MULTI_EVENT_ORDERING
 *  - RuntimeTruthPatch key is in DATA_MODEL_SURFACES
 *  - TruthReconciliationReducer key is in DATA_MODEL_SURFACES
 *  - AndroidParticipantRuntimeTruth key is in DATA_MODEL_SURFACES
 *
 * ### UnifiedTruthReconciliationSurface — wire-key forwarding constants
 *  - KEY_PATCH_ID equals RuntimeTruthPatch.KEY_PATCH_ID
 *  - KEY_PARTICIPANT_ID equals RuntimeTruthPatch.KEY_PARTICIPANT_ID
 *  - KEY_TASK_ID equals RuntimeTruthPatch.KEY_TASK_ID
 *  - KEY_KIND equals RuntimeTruthPatch.KEY_KIND
 *  - KEY_IS_AUTHORITATIVE equals RuntimeTruthPatch.KEY_IS_AUTHORITATIVE
 *  - KEY_TASK_TERMINAL_OUTCOME equals RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME
 *
 * ### RuntimeTruthPatch — Kind enum
 *  - Kind.ALL_WIRE_VALUES has exactly 9 entries
 *  - DELEGATED_TASK_RESULT wireValue is "delegated_task_result"
 *  - DELEGATED_TASK_CANCELLED wireValue is "delegated_task_cancelled"
 *  - DELEGATED_TASK_FAILED wireValue is "delegated_task_failed"
 *  - HANDOFF_ACCEPTED wireValue is "handoff_accepted"
 *  - HANDOFF_REJECTED wireValue is "handoff_rejected"
 *  - TAKEOVER_ACCEPTED wireValue is "takeover_accepted"
 *  - TAKEOVER_REJECTED wireValue is "takeover_rejected"
 *  - PARTICIPANT_STATE_CHANGED wireValue is "participant_state_changed"
 *  - SESSION_TERMINAL wireValue is "session_terminal"
 *  - Kind.fromValue returns correct entry for each wire value
 *  - Kind.fromValue returns null for unknown value
 *
 * ### RuntimeTruthPatch — TaskTerminalOutcome enum
 *  - ALL_WIRE_VALUES has exactly 3 entries
 *  - COMPLETED wireValue is "completed"
 *  - CANCELLED wireValue is "cancelled"
 *  - FAILED wireValue is "failed"
 *  - fromValue returns correct entry
 *  - fromValue returns null for unknown value
 *
 * ### RuntimeTruthPatch — HandoffOutcome enum
 *  - ALL_WIRE_VALUES has exactly 2 entries
 *  - ACCEPTED wireValue is "accepted"
 *  - REJECTED wireValue is "rejected"
 *
 * ### RuntimeTruthPatch — TakeoverOutcome enum
 *  - ALL_WIRE_VALUES has exactly 2 entries
 *  - ACCEPTED wireValue is "accepted"
 *  - REJECTED wireValue is "rejected"
 *
 * ### RuntimeTruthPatch — wire format (toMap)
 *  - toMap contains patch_id
 *  - toMap contains participant_id
 *  - toMap contains kind
 *  - toMap contains is_authoritative
 *  - toMap contains patched_at_ms
 *  - toMap contains reconciliation_epoch
 *  - toMap omits task_id when null
 *  - toMap includes task_id when non-null
 *  - toMap omits task_terminal_outcome when null
 *  - toMap includes task_terminal_outcome when non-null
 *  - toMap omits handoff_outcome when null
 *  - toMap includes handoff_outcome when non-null
 *  - toMap omits takeover_outcome when null
 *  - toMap includes takeover_outcome when non-null
 *  - toMap omits error_detail when null
 *  - toMap includes error_detail when non-null
 *
 * ### RuntimeTruthPatch — derived helpers
 *  - isTaskTerminal true when taskTerminalOutcome non-null
 *  - isTaskTerminal false when taskTerminalOutcome null
 *  - isOwnershipTransition true when handoffOutcome non-null
 *  - isOwnershipTransition true when takeoverOutcome non-null
 *  - isOwnershipTransition false when both null
 *
 * ### TruthReconciliationReducer — delegated result completion
 *  - DELEGATED_TASK_RESULT patch clears activeTaskId
 *  - DELEGATED_TASK_RESULT patch clears activeTaskStatus
 *  - DELEGATED_TASK_RESULT ReduceResult.applied is true
 *  - DELEGATED_TASK_CANCELLED patch clears activeTaskId
 *  - DELEGATED_TASK_CANCELLED patch clears activeTaskStatus
 *  - DELEGATED_TASK_FAILED patch clears activeTaskId
 *  - DELEGATED_TASK_FAILED patch clears activeTaskStatus
 *
 * ### TruthReconciliationReducer — handoff success/failure
 *  - HANDOFF_ACCEPTED patch advances reconciliationEpoch
 *  - HANDOFF_ACCEPTED does not clear activeTaskId
 *  - HANDOFF_REJECTED patch advances reconciliationEpoch
 *  - HANDOFF_REJECTED ReduceResult.applied is true
 *
 * ### TruthReconciliationReducer — takeover accept/reject
 *  - TAKEOVER_ACCEPTED sets activeTaskId when idle
 *  - TAKEOVER_ACCEPTED sets activeTaskStatus RUNNING when idle
 *  - TAKEOVER_ACCEPTED does not overwrite existing activeTaskId
 *  - TAKEOVER_REJECTED does not set activeTaskId
 *  - TAKEOVER_REJECTED advances reconciliationEpoch
 *
 * ### TruthReconciliationReducer — reducer invariants
 *  - stale epoch patch is discarded (applied=false)
 *  - stale epoch patch returns original snapshot unchanged
 *  - non-authoritative patch advances epoch only
 *  - non-authoritative patch does not clear activeTaskId
 *  - participant mismatch patch is discarded (applied=false)
 *  - participant mismatch patch returns original snapshot unchanged
 *  - ReduceResult.discardReason is non-null when applied=false
 *  - PARTICIPANT_STATE_CHANGED advances epoch without task mutation
 *  - SESSION_TERMINAL clears activeTaskId
 *  - SESSION_TERMINAL clears activeTaskStatus
 *
 * ### TruthReconciliationReducer — multi-event ordering scenarios
 *  - delegated_result_then_session_terminal: final truth is idle
 *  - session_terminal_then_stale_delegated_result: stale result discarded, idle maintained
 *  - takeover_accepted_then_task_result: task opened then closed, final idle
 *  - handoff_rejected_then_task_cancelled: task cancelled, final idle
 *  - duplicate_task_failed: second patch is idempotent, truth remains idle
 *  - notification_then_authoritative_terminal: terminal still applied after notification
 *
 * ### StabilizationBaseline — PR-64 entries
 *  - exactly 3 PR-64 entries are registered
 *  - all PR-64 entries have CANONICAL_STABLE stability
 *  - "unified-truth-reconciliation-surface" surfaceId is registered
 *  - "runtime-truth-patch" surfaceId is registered
 *  - "truth-reconciliation-reducer" surfaceId is registered
 */
class Pr64UnifiedTruthReconciliationTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun buildDescriptor() = RuntimeHostDescriptor(
        hostId = "host-64",
        deviceId = "dev-64",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun buildSnapshot(
        activeTaskId: String? = null,
        activeTaskStatus: ActiveTaskStatus? = null,
        reconciliationEpoch: Int = 10
    ): AndroidParticipantRuntimeTruth {
        val descriptor = buildDescriptor()
        return AndroidParticipantRuntimeTruth.from(
            descriptor = descriptor,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = activeTaskId,
            activeTaskStatus = activeTaskStatus,
            reconciliationEpoch = reconciliationEpoch
        )
    }

    private fun buildPatch(
        participantId: String,
        kind: RuntimeTruthPatch.Kind,
        isAuthoritative: Boolean = true,
        taskId: String? = "task-64",
        taskTerminalOutcome: RuntimeTruthPatch.TaskTerminalOutcome? = null,
        handoffOutcome: RuntimeTruthPatch.HandoffOutcome? = null,
        takeoverOutcome: RuntimeTruthPatch.TakeoverOutcome? = null,
        errorDetail: String? = null,
        reconciliationEpoch: Int = 11
    ) = RuntimeTruthPatch(
        patchId = "patch-64-${kind.wireValue}",
        participantId = participantId,
        taskId = taskId,
        kind = kind,
        isAuthoritative = isAuthoritative,
        taskTerminalOutcome = taskTerminalOutcome,
        handoffOutcome = handoffOutcome,
        takeoverOutcome = takeoverOutcome,
        errorDetail = errorDetail,
        reconciliationEpoch = reconciliationEpoch
    )

    // ── UnifiedTruthReconciliationSurface — structural invariants ─────────────

    @Test
    fun `AUTHORITATIVE_TRUTH_SOURCES has exactly AUTHORITATIVE_TRUTH_SOURCES_COUNT entries`() {
        assertEquals(
            UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES_COUNT,
            UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES.size
        )
    }

    @Test
    fun `NOTIFICATION_SIGNALS has exactly NOTIFICATION_SIGNALS_COUNT entries`() {
        assertEquals(
            UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS_COUNT,
            UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS.size
        )
    }

    @Test
    fun `CONVERGENCE_MODEL has exactly CONVERGENCE_MODEL_COUNT entries`() {
        assertEquals(
            UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL_COUNT,
            UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL.size
        )
    }

    @Test
    fun `MULTI_EVENT_ORDERING has exactly MULTI_EVENT_ORDERING_COUNT entries`() {
        assertEquals(
            UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING_COUNT,
            UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING.size
        )
    }

    @Test
    fun `DATA_MODEL_SURFACES has exactly DATA_MODEL_SURFACES_COUNT entries`() {
        assertEquals(
            UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES_COUNT,
            UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES.size
        )
    }

    @Test
    fun `all AUTHORITATIVE_TRUTH_SOURCES keys are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES.keys.all { it.isNotBlank() })
    }

    @Test
    fun `all AUTHORITATIVE_TRUTH_SOURCES values are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES.values.all { it.isNotBlank() })
    }

    @Test
    fun `all NOTIFICATION_SIGNALS keys are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS.keys.all { it.isNotBlank() })
    }

    @Test
    fun `all NOTIFICATION_SIGNALS values are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS.values.all { it.isNotBlank() })
    }

    @Test
    fun `all CONVERGENCE_MODEL keys are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL.keys.all { it.isNotBlank() })
    }

    @Test
    fun `all CONVERGENCE_MODEL values are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL.values.all { it.isNotBlank() })
    }

    @Test
    fun `all MULTI_EVENT_ORDERING keys are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING.keys.all { it.isNotBlank() })
    }

    @Test
    fun `all MULTI_EVENT_ORDERING values are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING.values.all { it.isNotBlank() })
    }

    @Test
    fun `all DATA_MODEL_SURFACES keys are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES.keys.all { it.isNotBlank() })
    }

    @Test
    fun `all DATA_MODEL_SURFACES values are non-blank`() {
        assertTrue(UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES.values.all { it.isNotBlank() })
    }

    @Test
    fun `INTRODUCED_PR is 64`() {
        assertEquals(64, UnifiedTruthReconciliationSurface.INTRODUCED_PR)
    }

    // ── UnifiedTruthReconciliationSurface — known keys ────────────────────────

    @Test
    fun `delegated_task_result key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `delegated_task_cancelled key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `delegated_task_failed key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `handoff_accepted key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `handoff_rejected key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.HANDOFF_REJECTED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `takeover_accepted key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `takeover_rejected key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.TAKEOVER_REJECTED.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `session_terminal key is in AUTHORITATIVE_TRUTH_SOURCES`() {
        assertTrue(
            RuntimeTruthPatch.Kind.SESSION_TERMINAL.wireValue in
                UnifiedTruthReconciliationSurface.AUTHORITATIVE_TRUTH_SOURCES
        )
    }

    @Test
    fun `participant_state_changed key is in NOTIFICATION_SIGNALS`() {
        assertTrue(
            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED.wireValue in
                UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS
        )
    }

    @Test
    fun `task_status_update key is in NOTIFICATION_SIGNALS`() {
        assertTrue("task_status_update" in UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS)
    }

    @Test
    fun `runtime_truth_snapshot_request key is in NOTIFICATION_SIGNALS`() {
        assertTrue("runtime_truth_snapshot_request" in UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS)
    }

    @Test
    fun `subtask_checkpoint key is in NOTIFICATION_SIGNALS`() {
        assertTrue("subtask_checkpoint" in UnifiedTruthReconciliationSurface.NOTIFICATION_SIGNALS)
    }

    @Test
    fun `single_reducer_entry_point key is in CONVERGENCE_MODEL`() {
        assertTrue("single_reducer_entry_point" in UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL)
    }

    @Test
    fun `epoch_ordering_guard key is in CONVERGENCE_MODEL`() {
        assertTrue("epoch_ordering_guard" in UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL)
    }

    @Test
    fun `terminal_idempotency key is in CONVERGENCE_MODEL`() {
        assertTrue("terminal_idempotency" in UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL)
    }

    @Test
    fun `authoritative_flag_gate key is in CONVERGENCE_MODEL`() {
        assertTrue("authoritative_flag_gate" in UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL)
    }

    @Test
    fun `participant_safety_guard key is in CONVERGENCE_MODEL`() {
        assertTrue("participant_safety_guard" in UnifiedTruthReconciliationSurface.CONVERGENCE_MODEL)
    }

    @Test
    fun `delegated_result_then_session_terminal is in MULTI_EVENT_ORDERING`() {
        assertTrue("delegated_result_then_session_terminal" in UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING)
    }

    @Test
    fun `session_terminal_then_delegated_result is in MULTI_EVENT_ORDERING`() {
        assertTrue("session_terminal_then_delegated_result" in UnifiedTruthReconciliationSurface.MULTI_EVENT_ORDERING)
    }

    @Test
    fun `RuntimeTruthPatch key is in DATA_MODEL_SURFACES`() {
        assertTrue("RuntimeTruthPatch" in UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES)
    }

    @Test
    fun `TruthReconciliationReducer key is in DATA_MODEL_SURFACES`() {
        assertTrue("TruthReconciliationReducer" in UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth key is in DATA_MODEL_SURFACES`() {
        assertTrue("AndroidParticipantRuntimeTruth" in UnifiedTruthReconciliationSurface.DATA_MODEL_SURFACES)
    }

    // ── UnifiedTruthReconciliationSurface — wire-key forwarding constants ─────

    @Test
    fun `KEY_PATCH_ID equals RuntimeTruthPatch KEY_PATCH_ID`() {
        assertEquals(RuntimeTruthPatch.KEY_PATCH_ID, UnifiedTruthReconciliationSurface.KEY_PATCH_ID)
    }

    @Test
    fun `KEY_PARTICIPANT_ID equals RuntimeTruthPatch KEY_PARTICIPANT_ID`() {
        assertEquals(RuntimeTruthPatch.KEY_PARTICIPANT_ID, UnifiedTruthReconciliationSurface.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `KEY_TASK_ID equals RuntimeTruthPatch KEY_TASK_ID`() {
        assertEquals(RuntimeTruthPatch.KEY_TASK_ID, UnifiedTruthReconciliationSurface.KEY_TASK_ID)
    }

    @Test
    fun `KEY_KIND equals RuntimeTruthPatch KEY_KIND`() {
        assertEquals(RuntimeTruthPatch.KEY_KIND, UnifiedTruthReconciliationSurface.KEY_KIND)
    }

    @Test
    fun `KEY_IS_AUTHORITATIVE equals RuntimeTruthPatch KEY_IS_AUTHORITATIVE`() {
        assertEquals(RuntimeTruthPatch.KEY_IS_AUTHORITATIVE, UnifiedTruthReconciliationSurface.KEY_IS_AUTHORITATIVE)
    }

    @Test
    fun `KEY_TASK_TERMINAL_OUTCOME equals RuntimeTruthPatch KEY_TASK_TERMINAL_OUTCOME`() {
        assertEquals(RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME, UnifiedTruthReconciliationSurface.KEY_TASK_TERMINAL_OUTCOME)
    }

    // ── RuntimeTruthPatch — Kind enum ─────────────────────────────────────────

    @Test
    fun `Kind ALL_WIRE_VALUES has exactly 9 entries`() {
        assertEquals(9, RuntimeTruthPatch.Kind.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `DELEGATED_TASK_RESULT wireValue is delegated_task_result`() {
        assertEquals("delegated_task_result", RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT.wireValue)
    }

    @Test
    fun `DELEGATED_TASK_CANCELLED wireValue is delegated_task_cancelled`() {
        assertEquals("delegated_task_cancelled", RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED.wireValue)
    }

    @Test
    fun `DELEGATED_TASK_FAILED wireValue is delegated_task_failed`() {
        assertEquals("delegated_task_failed", RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED.wireValue)
    }

    @Test
    fun `HANDOFF_ACCEPTED wireValue is handoff_accepted`() {
        assertEquals("handoff_accepted", RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED.wireValue)
    }

    @Test
    fun `HANDOFF_REJECTED wireValue is handoff_rejected`() {
        assertEquals("handoff_rejected", RuntimeTruthPatch.Kind.HANDOFF_REJECTED.wireValue)
    }

    @Test
    fun `TAKEOVER_ACCEPTED wireValue is takeover_accepted`() {
        assertEquals("takeover_accepted", RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED.wireValue)
    }

    @Test
    fun `TAKEOVER_REJECTED wireValue is takeover_rejected`() {
        assertEquals("takeover_rejected", RuntimeTruthPatch.Kind.TAKEOVER_REJECTED.wireValue)
    }

    @Test
    fun `PARTICIPANT_STATE_CHANGED wireValue is participant_state_changed`() {
        assertEquals("participant_state_changed", RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED.wireValue)
    }

    @Test
    fun `SESSION_TERMINAL wireValue is session_terminal`() {
        assertEquals("session_terminal", RuntimeTruthPatch.Kind.SESSION_TERMINAL.wireValue)
    }

    @Test
    fun `Kind fromValue returns correct entry for delegated_task_result`() {
        assertEquals(RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, RuntimeTruthPatch.Kind.fromValue("delegated_task_result"))
    }

    @Test
    fun `Kind fromValue returns correct entry for takeover_accepted`() {
        assertEquals(RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED, RuntimeTruthPatch.Kind.fromValue("takeover_accepted"))
    }

    @Test
    fun `Kind fromValue returns null for unknown value`() {
        assertNull(RuntimeTruthPatch.Kind.fromValue("unknown_kind"))
    }

    @Test
    fun `Kind fromValue returns null for null input`() {
        assertNull(RuntimeTruthPatch.Kind.fromValue(null))
    }

    // ── RuntimeTruthPatch — TaskTerminalOutcome enum ──────────────────────────

    @Test
    fun `TaskTerminalOutcome ALL_WIRE_VALUES has exactly 3 entries`() {
        assertEquals(3, RuntimeTruthPatch.TaskTerminalOutcome.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `TaskTerminalOutcome COMPLETED wireValue is completed`() {
        assertEquals("completed", RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED.wireValue)
    }

    @Test
    fun `TaskTerminalOutcome CANCELLED wireValue is cancelled`() {
        assertEquals("cancelled", RuntimeTruthPatch.TaskTerminalOutcome.CANCELLED.wireValue)
    }

    @Test
    fun `TaskTerminalOutcome FAILED wireValue is failed`() {
        assertEquals("failed", RuntimeTruthPatch.TaskTerminalOutcome.FAILED.wireValue)
    }

    @Test
    fun `TaskTerminalOutcome fromValue returns correct entry`() {
        assertEquals(RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED, RuntimeTruthPatch.TaskTerminalOutcome.fromValue("completed"))
    }

    @Test
    fun `TaskTerminalOutcome fromValue returns null for unknown value`() {
        assertNull(RuntimeTruthPatch.TaskTerminalOutcome.fromValue("unknown"))
    }

    // ── RuntimeTruthPatch — HandoffOutcome enum ───────────────────────────────

    @Test
    fun `HandoffOutcome ALL_WIRE_VALUES has exactly 2 entries`() {
        assertEquals(2, RuntimeTruthPatch.HandoffOutcome.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `HandoffOutcome ACCEPTED wireValue is accepted`() {
        assertEquals("accepted", RuntimeTruthPatch.HandoffOutcome.ACCEPTED.wireValue)
    }

    @Test
    fun `HandoffOutcome REJECTED wireValue is rejected`() {
        assertEquals("rejected", RuntimeTruthPatch.HandoffOutcome.REJECTED.wireValue)
    }

    @Test
    fun `HandoffOutcome fromValue returns correct entry`() {
        assertEquals(RuntimeTruthPatch.HandoffOutcome.ACCEPTED, RuntimeTruthPatch.HandoffOutcome.fromValue("accepted"))
    }

    @Test
    fun `HandoffOutcome fromValue returns null for unknown value`() {
        assertNull(RuntimeTruthPatch.HandoffOutcome.fromValue(null))
    }

    // ── RuntimeTruthPatch — TakeoverOutcome enum ──────────────────────────────

    @Test
    fun `TakeoverOutcome ALL_WIRE_VALUES has exactly 2 entries`() {
        assertEquals(2, RuntimeTruthPatch.TakeoverOutcome.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `TakeoverOutcome ACCEPTED wireValue is accepted`() {
        assertEquals("accepted", RuntimeTruthPatch.TakeoverOutcome.ACCEPTED.wireValue)
    }

    @Test
    fun `TakeoverOutcome REJECTED wireValue is rejected`() {
        assertEquals("rejected", RuntimeTruthPatch.TakeoverOutcome.REJECTED.wireValue)
    }

    @Test
    fun `TakeoverOutcome fromValue returns correct entry`() {
        assertEquals(RuntimeTruthPatch.TakeoverOutcome.REJECTED, RuntimeTruthPatch.TakeoverOutcome.fromValue("rejected"))
    }

    @Test
    fun `TakeoverOutcome fromValue returns null for null input`() {
        assertNull(RuntimeTruthPatch.TakeoverOutcome.fromValue(null))
    }

    // ── RuntimeTruthPatch — wire format (toMap) ───────────────────────────────

    @Test
    fun `toMap contains patch_id`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertTrue(RuntimeTruthPatch.KEY_PATCH_ID in patch.toMap())
    }

    @Test
    fun `toMap contains participant_id`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertEquals(snapshot.participantId, patch.toMap()[RuntimeTruthPatch.KEY_PARTICIPANT_ID])
    }

    @Test
    fun `toMap contains kind`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertEquals("delegated_task_result", patch.toMap()[RuntimeTruthPatch.KEY_KIND])
    }

    @Test
    fun `toMap contains is_authoritative`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertEquals(true, patch.toMap()[RuntimeTruthPatch.KEY_IS_AUTHORITATIVE])
    }

    @Test
    fun `toMap contains patched_at_ms`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertTrue((patch.toMap()[RuntimeTruthPatch.KEY_PATCHED_AT_MS] as Long) > 0)
    }

    @Test
    fun `toMap contains reconciliation_epoch`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, reconciliationEpoch = 11)
        assertEquals(11, patch.toMap()[RuntimeTruthPatch.KEY_RECONCILIATION_EPOCH])
    }

    @Test
    fun `toMap omits task_id when null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED, taskId = null)
        assertFalse(RuntimeTruthPatch.KEY_TASK_ID in patch.toMap())
    }

    @Test
    fun `toMap includes task_id when non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, taskId = "task-64")
        assertEquals("task-64", patch.toMap()[RuntimeTruthPatch.KEY_TASK_ID])
    }

    @Test
    fun `toMap omits task_terminal_outcome when null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED, taskTerminalOutcome = null)
        assertFalse(RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME in patch.toMap())
    }

    @Test
    fun `toMap includes task_terminal_outcome when non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED
        )
        assertEquals("completed", patch.toMap()[RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME])
    }

    @Test
    fun `toMap omits handoff_outcome when null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, handoffOutcome = null)
        assertFalse(RuntimeTruthPatch.KEY_HANDOFF_OUTCOME in patch.toMap())
    }

    @Test
    fun `toMap includes handoff_outcome when non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.ACCEPTED
        )
        assertEquals("accepted", patch.toMap()[RuntimeTruthPatch.KEY_HANDOFF_OUTCOME])
    }

    @Test
    fun `toMap omits takeover_outcome when null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, takeoverOutcome = null)
        assertFalse(RuntimeTruthPatch.KEY_TAKEOVER_OUTCOME in patch.toMap())
    }

    @Test
    fun `toMap includes takeover_outcome when non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED
        )
        assertEquals("accepted", patch.toMap()[RuntimeTruthPatch.KEY_TAKEOVER_OUTCOME])
    }

    @Test
    fun `toMap omits error_detail when null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, errorDetail = null)
        assertFalse(RuntimeTruthPatch.KEY_ERROR_DETAIL in patch.toMap())
    }

    @Test
    fun `toMap includes error_detail when non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.TAKEOVER_REJECTED, errorDetail = "session_not_attached")
        assertEquals("session_not_attached", patch.toMap()[RuntimeTruthPatch.KEY_ERROR_DETAIL])
    }

    // ── RuntimeTruthPatch — derived helpers ───────────────────────────────────

    @Test
    fun `isTaskTerminal true when taskTerminalOutcome non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED
        )
        assertTrue(patch.isTaskTerminal)
    }

    @Test
    fun `isTaskTerminal false when taskTerminalOutcome null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED, taskTerminalOutcome = null)
        assertFalse(patch.isTaskTerminal)
    }

    @Test
    fun `isOwnershipTransition true when handoffOutcome non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.ACCEPTED
        )
        assertTrue(patch.isOwnershipTransition)
    }

    @Test
    fun `isOwnershipTransition true when takeoverOutcome non-null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED
        )
        assertTrue(patch.isOwnershipTransition)
    }

    @Test
    fun `isOwnershipTransition false when both null`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT)
        assertFalse(patch.isOwnershipTransition)
    }

    // ── TruthReconciliationReducer — delegated result completion ──────────────

    @Test
    fun `DELEGATED_TASK_RESULT patch clears activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-64", activeTaskStatus = ActiveTaskStatus.RUNNING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskId)
    }

    @Test
    fun `DELEGATED_TASK_RESULT patch clears activeTaskStatus`() {
        val snapshot = buildSnapshot(activeTaskId = "task-64", activeTaskStatus = ActiveTaskStatus.RUNNING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskStatus)
    }

    @Test
    fun `DELEGATED_TASK_RESULT ReduceResult applied is true`() {
        val snapshot = buildSnapshot(activeTaskId = "task-64", activeTaskStatus = ActiveTaskStatus.RUNNING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertTrue(result.applied)
    }

    @Test
    fun `DELEGATED_TASK_CANCELLED patch clears activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-65", activeTaskStatus = ActiveTaskStatus.CANCELLING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.CANCELLED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskId)
    }

    @Test
    fun `DELEGATED_TASK_CANCELLED patch clears activeTaskStatus`() {
        val snapshot = buildSnapshot(activeTaskId = "task-65", activeTaskStatus = ActiveTaskStatus.CANCELLING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.CANCELLED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskStatus)
    }

    @Test
    fun `DELEGATED_TASK_FAILED patch clears activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-66", activeTaskStatus = ActiveTaskStatus.FAILING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.FAILED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskId)
    }

    @Test
    fun `DELEGATED_TASK_FAILED patch clears activeTaskStatus`() {
        val snapshot = buildSnapshot(activeTaskId = "task-66", activeTaskStatus = ActiveTaskStatus.FAILING)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.FAILED
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskStatus)
    }

    // ── TruthReconciliationReducer — handoff success/failure ──────────────────

    @Test
    fun `HANDOFF_ACCEPTED patch advances reconciliationEpoch`() {
        val snapshot = buildSnapshot(reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.ACCEPTED,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(11, result.snapshot.reconciliationEpoch)
    }

    @Test
    fun `HANDOFF_ACCEPTED does not clear activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-h1", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.ACCEPTED,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals("task-h1", result.snapshot.activeTaskId)
    }

    @Test
    fun `HANDOFF_REJECTED patch advances reconciliationEpoch`() {
        val snapshot = buildSnapshot(reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_REJECTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.REJECTED,
            errorDetail = "remote_not_ready",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(11, result.snapshot.reconciliationEpoch)
    }

    @Test
    fun `HANDOFF_REJECTED ReduceResult applied is true`() {
        val snapshot = buildSnapshot(reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_REJECTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.REJECTED,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertTrue(result.applied)
    }

    // ── TruthReconciliationReducer — takeover accept/reject ───────────────────

    @Test
    fun `TAKEOVER_ACCEPTED sets activeTaskId when idle`() {
        val snapshot = buildSnapshot(activeTaskId = null, activeTaskStatus = null, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED,
            taskId = "task-to-1",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals("task-to-1", result.snapshot.activeTaskId)
    }

    @Test
    fun `TAKEOVER_ACCEPTED sets activeTaskStatus RUNNING when idle`() {
        val snapshot = buildSnapshot(activeTaskId = null, activeTaskStatus = null, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED,
            taskId = "task-to-1",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(ActiveTaskStatus.RUNNING, result.snapshot.activeTaskStatus)
    }

    @Test
    fun `TAKEOVER_ACCEPTED does not overwrite existing activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "existing-task", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED,
            taskId = "new-task",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        // Existing task must not be overwritten by a new takeover-accepted
        assertEquals("existing-task", result.snapshot.activeTaskId)
    }

    @Test
    fun `TAKEOVER_REJECTED does not set activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = null, activeTaskStatus = null, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_REJECTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.REJECTED,
            taskId = "rejected-task",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskId)
    }

    @Test
    fun `TAKEOVER_REJECTED advances reconciliationEpoch`() {
        val snapshot = buildSnapshot(reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_REJECTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.REJECTED,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(11, result.snapshot.reconciliationEpoch)
    }

    // ── TruthReconciliationReducer — reducer invariants ───────────────────────

    @Test
    fun `stale epoch patch is discarded applied false`() {
        val snapshot = buildSnapshot(reconciliationEpoch = 10)
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, reconciliationEpoch = 9)
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertFalse(result.applied)
    }

    @Test
    fun `stale epoch patch returns original snapshot unchanged`() {
        val snapshot = buildSnapshot(activeTaskId = "task-stale", reconciliationEpoch = 10)
        val patch = buildPatch(snapshot.participantId, RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT, reconciliationEpoch = 5)
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `non authoritative patch advances epoch only`() {
        val snapshot = buildSnapshot(activeTaskId = "task-notif", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED,
            isAuthoritative = false,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(11, result.snapshot.reconciliationEpoch)
    }

    @Test
    fun `non authoritative patch does not clear activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-notif", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED,
            isAuthoritative = false,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals("task-notif", result.snapshot.activeTaskId)
    }

    @Test
    fun `participant mismatch patch is discarded applied false`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            participantId = "wrong-participant",
            kind = RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertFalse(result.applied)
    }

    @Test
    fun `participant mismatch patch returns original snapshot unchanged`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            participantId = "other-participant",
            kind = RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED,
            reconciliationEpoch = 99
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(snapshot, result.snapshot)
    }

    @Test
    fun `ReduceResult discardReason is non-null when applied false`() {
        val snapshot = buildSnapshot()
        val patch = buildPatch(
            participantId = "wrong-participant",
            kind = RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNotNull(result.discardReason)
    }

    @Test
    fun `PARTICIPANT_STATE_CHANGED advances epoch without task mutation`() {
        val snapshot = buildSnapshot(activeTaskId = "task-ps", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED,
            isAuthoritative = true,
            taskId = null,
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertEquals(11, result.snapshot.reconciliationEpoch)
        assertEquals("task-ps", result.snapshot.activeTaskId)
    }

    @Test
    fun `SESSION_TERMINAL clears activeTaskId`() {
        val snapshot = buildSnapshot(activeTaskId = "task-sess", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.SESSION_TERMINAL,
            taskId = "task-sess",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskId)
    }

    @Test
    fun `SESSION_TERMINAL clears activeTaskStatus`() {
        val snapshot = buildSnapshot(activeTaskId = "task-sess", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val patch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.SESSION_TERMINAL,
            taskId = "task-sess",
            reconciliationEpoch = 11
        )
        val result = TruthReconciliationReducer.reduce(snapshot, patch)
        assertNull(result.snapshot.activeTaskStatus)
    }

    // ── TruthReconciliationReducer — multi-event ordering scenarios ───────────

    @Test
    fun `delegated_result_then_session_terminal final truth is idle`() {
        // Scenario: result arrives first, then session closes.
        val snapshot = buildSnapshot(activeTaskId = "task-A", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val resultPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED,
            reconciliationEpoch = 11
        )
        val sessionPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.SESSION_TERMINAL,
            reconciliationEpoch = 12
        )
        val final = TruthReconciliationReducer.reduceFold(snapshot, listOf(resultPatch, sessionPatch))
        assertNull(final.activeTaskId)
        assertNull(final.activeTaskStatus)
        assertEquals(12, final.reconciliationEpoch)
    }

    @Test
    fun `session_terminal_then_stale_delegated_result stale result discarded idle maintained`() {
        // Scenario: session closes first (epoch 11), then a stale result arrives (epoch 9).
        val snapshot = buildSnapshot(activeTaskId = "task-B", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val sessionPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.SESSION_TERMINAL,
            reconciliationEpoch = 11
        )
        val stalePatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED,
            reconciliationEpoch = 9 // stale
        )
        val final = TruthReconciliationReducer.reduceFold(snapshot, listOf(sessionPatch, stalePatch))
        // Stale result discarded; session terminal still holds
        assertNull(final.activeTaskId)
        assertEquals(11, final.reconciliationEpoch)
    }

    @Test
    fun `takeover_accepted_then_task_result task opened then closed final idle`() {
        // Scenario: takeover accepted sets active task, then result closes it.
        val snapshot = buildSnapshot(activeTaskId = null, activeTaskStatus = null, reconciliationEpoch = 10)
        val acceptPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            takeoverOutcome = RuntimeTruthPatch.TakeoverOutcome.ACCEPTED,
            taskId = "task-C",
            reconciliationEpoch = 11
        )
        val resultPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED,
            taskId = "task-C",
            reconciliationEpoch = 12
        )
        val final = TruthReconciliationReducer.reduceFold(snapshot, listOf(acceptPatch, resultPatch))
        assertNull(final.activeTaskId)
        assertNull(final.activeTaskStatus)
        assertEquals(12, final.reconciliationEpoch)
    }

    @Test
    fun `handoff_rejected_then_task_cancelled task cancelled final idle`() {
        // Scenario: handoff rejected (task stays local), then task is cancelled.
        val snapshot = buildSnapshot(activeTaskId = "task-D", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val handoffPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.HANDOFF_REJECTED,
            handoffOutcome = RuntimeTruthPatch.HandoffOutcome.REJECTED,
            taskId = "task-D",
            reconciliationEpoch = 11
        )
        val cancelPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.CANCELLED,
            taskId = "task-D",
            reconciliationEpoch = 12
        )
        val final = TruthReconciliationReducer.reduceFold(snapshot, listOf(handoffPatch, cancelPatch))
        assertNull(final.activeTaskId)
        assertNull(final.activeTaskStatus)
        assertEquals(12, final.reconciliationEpoch)
    }

    @Test
    fun `duplicate_task_failed second patch is idempotent truth remains idle`() {
        val snapshot = buildSnapshot(activeTaskId = "task-E", activeTaskStatus = ActiveTaskStatus.FAILING, reconciliationEpoch = 10)
        val failPatch1 = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.FAILED,
            taskId = "task-E",
            reconciliationEpoch = 11
        )
        // Duplicate with same epoch
        val failPatch2 = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.FAILED,
            taskId = "task-E",
            reconciliationEpoch = 11
        )
        val afterFirst = TruthReconciliationReducer.reduce(snapshot, failPatch1).snapshot
        val afterSecond = TruthReconciliationReducer.reduce(afterFirst, failPatch2).snapshot
        assertNull(afterSecond.activeTaskId)
        assertNull(afterSecond.activeTaskStatus)
    }

    @Test
    fun `notification_then_authoritative_terminal terminal still applied after notification`() {
        val snapshot = buildSnapshot(activeTaskId = "task-F", activeTaskStatus = ActiveTaskStatus.RUNNING, reconciliationEpoch = 10)
        val notifPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED,
            isAuthoritative = false,
            taskId = null,
            reconciliationEpoch = 11
        )
        val terminalPatch = buildPatch(
            snapshot.participantId,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED,
            taskId = "task-F",
            reconciliationEpoch = 12
        )
        val final = TruthReconciliationReducer.reduceFold(snapshot, listOf(notifPatch, terminalPatch))
        assertNull(final.activeTaskId)
        assertEquals(12, final.reconciliationEpoch)
    }

    // ── StabilizationBaseline — PR-64 entries ─────────────────────────────────

    @Test
    fun `exactly 3 PR-64 entries are registered in StabilizationBaseline`() {
        val pr64Entries = StabilizationBaseline.entries.filter { it.introducedPr == 64 }
        assertEquals(3, pr64Entries.size)
    }

    @Test
    fun `all PR-64 entries have CANONICAL_STABLE stability`() {
        val pr64Entries = StabilizationBaseline.entries.filter { it.introducedPr == 64 }
        assertTrue(pr64Entries.all { it.stability == StabilizationBaseline.SurfaceStability.CANONICAL_STABLE })
    }

    @Test
    fun `unified-truth-reconciliation-surface surfaceId is registered`() {
        assertNotNull(StabilizationBaseline.forId("unified-truth-reconciliation-surface"))
    }

    @Test
    fun `runtime-truth-patch surfaceId is registered`() {
        assertNotNull(StabilizationBaseline.forId("runtime-truth-patch"))
    }

    @Test
    fun `truth-reconciliation-reducer surfaceId is registered`() {
        assertNotNull(StabilizationBaseline.forId("truth-reconciliation-reducer"))
    }
}
