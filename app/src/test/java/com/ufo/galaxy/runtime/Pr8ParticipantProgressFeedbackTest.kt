package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-63 — Android Participant Progress and Execution Feedback Surfaces:
 * Acceptance and Regression Test Suite.
 *
 * Validates the four PR-63 acceptance criteria and all concrete surfaces introduced:
 *
 *  1. **[ParticipantProgressFeedbackSurface]** — structural completeness and count invariants.
 *  2. **[ParticipantProgressCheckpoint]** — named execution-stage markers: wire format,
 *     known checkpoint IDs, toPayloadMap() output.
 *  3. **[SubtaskProgressReport]** — typed subtask-level progress: status enum, wire format,
 *     toPayloadMap() output.
 *  4. **[ReconciliationSignal]** progress payload key constants — stability and naming.
 *  5. **[StabilizationBaseline]** — exactly four PR-63 entries registered as CANONICAL_STABLE.
 *
 * ## Test matrix
 *
 * ### ParticipantProgressFeedbackSurface — structural invariants
 *  - PROGRESS_FEEDBACK_SURFACES has exactly PROGRESS_FEEDBACK_SURFACES_COUNT entries
 *  - OUTBOUND_EXECUTION_MODELING has exactly OUTBOUND_EXECUTION_MODELING_COUNT entries
 *  - OBSERVABILITY_SURFACES has exactly OBSERVABILITY_SURFACES_COUNT entries
 *  - AUTHORITY_BOUNDARY_DECLARATIONS has exactly AUTHORITY_BOUNDARY_DECLARATIONS_COUNT entries
 *  - all PROGRESS_FEEDBACK_SURFACES keys are non-blank
 *  - all PROGRESS_FEEDBACK_SURFACES values are non-blank
 *  - all OUTBOUND_EXECUTION_MODELING keys are non-blank
 *  - all OUTBOUND_EXECUTION_MODELING values are non-blank
 *  - all OBSERVABILITY_SURFACES keys are non-blank
 *  - all OBSERVABILITY_SURFACES values are non-blank
 *  - all AUTHORITY_BOUNDARY_DECLARATIONS keys are non-blank
 *  - all AUTHORITY_BOUNDARY_DECLARATIONS values are non-blank
 *  - INTRODUCED_PR is 63
 *
 * ### ParticipantProgressFeedbackSurface — known keys
 *  - execution_checkpoint key is in PROGRESS_FEEDBACK_SURFACES
 *  - subtask_progress key is in PROGRESS_FEEDBACK_SURFACES
 *  - task_status_update_structured_payload key is in PROGRESS_FEEDBACK_SURFACES
 *  - checkpoint_known_ids_registry key is in PROGRESS_FEEDBACK_SURFACES
 *  - subtask_status_wire_vocabulary key is in PROGRESS_FEEDBACK_SURFACES
 *  - pre_terminal_stage_progression key is in OUTBOUND_EXECUTION_MODELING
 *  - step_level_progress_tracking key is in OUTBOUND_EXECUTION_MODELING
 *  - replanning_visibility key is in OUTBOUND_EXECUTION_MODELING
 *  - finalization_pre_terminal_signal key is in OUTBOUND_EXECUTION_MODELING
 *  - android_owns_checkpoint_identity key is in AUTHORITY_BOUNDARY_DECLARATIONS
 *  - v2_owns_task_lifecycle_outcomes key is in AUTHORITY_BOUNDARY_DECLARATIONS
 *  - v2_owns_dispatch_and_rebalance key is in AUTHORITY_BOUNDARY_DECLARATIONS
 *
 * ### ParticipantProgressFeedbackSurface — wire-key forwarding constants
 *  - KEY_CHECKPOINT_ID equals ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID
 *  - KEY_STEP_INDEX equals ParticipantProgressCheckpoint.KEY_STEP_INDEX
 *  - KEY_TOTAL_STEPS equals ParticipantProgressCheckpoint.KEY_TOTAL_STEPS
 *  - KEY_SUBTASK_INDEX equals SubtaskProgressReport.KEY_SUBTASK_INDEX
 *  - KEY_SUBTASK_TOTAL equals SubtaskProgressReport.KEY_SUBTASK_TOTAL
 *  - KEY_SUBTASK_STATUS equals SubtaskProgressReport.KEY_SUBTASK_STATUS
 *  - KEY_SUBTASK_LABEL equals SubtaskProgressReport.KEY_SUBTASK_LABEL
 *  - all surface forwarding keys are non-blank
 *
 * ### ParticipantProgressCheckpoint — known checkpoint IDs
 *  - KNOWN_CHECKPOINT_IDS has exactly KNOWN_CHECKPOINT_COUNT entries
 *  - CHECKPOINT_PLANNING_STARTED is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_PLANNING_COMPLETE is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_GROUNDING_STARTED is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_GROUNDING_COMPLETE is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_STEP_EXECUTING is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_STEP_COMPLETE is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_REPLANNING is in KNOWN_CHECKPOINT_IDS
 *  - CHECKPOINT_FINALIZING is in KNOWN_CHECKPOINT_IDS
 *  - all KNOWN_CHECKPOINT_IDS are non-blank
 *  - all KNOWN_CHECKPOINT_IDS are distinct
 *
 * ### ParticipantProgressCheckpoint — wire key constants
 *  - KEY_CHECKPOINT_ID is "checkpoint_id"
 *  - KEY_TASK_ID is "checkpoint_task_id"
 *  - KEY_PARTICIPANT_ID is "checkpoint_participant_id"
 *  - KEY_STEP_INDEX is "checkpoint_step_index"
 *  - KEY_TOTAL_STEPS is "checkpoint_total_steps"
 *  - KEY_DETAIL is "checkpoint_detail"
 *  - KEY_EMITTED_AT_MS is "checkpoint_emitted_at_ms"
 *
 * ### ParticipantProgressCheckpoint — toPayloadMap()
 *  - toPayloadMap contains KEY_CHECKPOINT_ID
 *  - toPayloadMap contains KEY_TASK_ID
 *  - toPayloadMap contains KEY_PARTICIPANT_ID
 *  - toPayloadMap contains KEY_STEP_INDEX
 *  - toPayloadMap contains KEY_EMITTED_AT_MS
 *  - toPayloadMap omits KEY_TOTAL_STEPS when totalSteps is null
 *  - toPayloadMap includes KEY_TOTAL_STEPS when totalSteps is non-null
 *  - toPayloadMap omits KEY_DETAIL when detail is null
 *  - toPayloadMap includes KEY_DETAIL when detail is non-null
 *  - toPayloadMap checkpointId matches provided value
 *  - toPayloadMap stepIndex matches provided value
 *  - toPayloadMap totalSteps matches provided value
 *  - toPayloadMap detail matches provided value
 *
 * ### SubtaskProgressReport.SubtaskStatus — enum
 *  - PENDING wireValue is "pending"
 *  - EXECUTING wireValue is "executing"
 *  - COMPLETE wireValue is "complete"
 *  - FAILED wireValue is "failed"
 *  - SKIPPED wireValue is "skipped"
 *  - ALL_WIRE_VALUES has 5 entries
 *  - all wire values are distinct
 *  - fromValue returns correct enum for each known wire value
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null input
 *
 * ### SubtaskProgressReport — wire key constants
 *  - KEY_TASK_ID is "subtask_task_id"
 *  - KEY_PARTICIPANT_ID is "subtask_participant_id"
 *  - KEY_SUBTASK_INDEX is "subtask_index"
 *  - KEY_SUBTASK_TOTAL is "subtask_total"
 *  - KEY_SUBTASK_LABEL is "subtask_label"
 *  - KEY_SUBTASK_STATUS is "subtask_status"
 *  - KEY_EMITTED_AT_MS is "subtask_emitted_at_ms"
 *
 * ### SubtaskProgressReport — toPayloadMap()
 *  - toPayloadMap contains KEY_TASK_ID
 *  - toPayloadMap contains KEY_PARTICIPANT_ID
 *  - toPayloadMap contains KEY_SUBTASK_INDEX
 *  - toPayloadMap contains KEY_SUBTASK_LABEL
 *  - toPayloadMap contains KEY_SUBTASK_STATUS
 *  - toPayloadMap contains KEY_EMITTED_AT_MS
 *  - toPayloadMap omits KEY_SUBTASK_TOTAL when subtaskTotal is null
 *  - toPayloadMap includes KEY_SUBTASK_TOTAL when subtaskTotal is non-null
 *  - toPayloadMap subtaskIndex matches provided value
 *  - toPayloadMap subtaskLabel matches provided value
 *  - toPayloadMap subtaskStatus wireValue matches provided status
 *  - toPayloadMap subtaskTotal matches provided value
 *
 * ### ReconciliationSignal — progress payload key constants
 *  - KEY_CHECKPOINT_ID is non-blank
 *  - KEY_STEP_INDEX is non-blank
 *  - KEY_TOTAL_STEPS is non-blank
 *  - KEY_SUBTASK_INDEX is non-blank
 *  - KEY_SUBTASK_TOTAL is non-blank
 *  - KEY_SUBTASK_STATUS is non-blank
 *  - KEY_SUBTASK_LABEL is non-blank
 *  - KEY_CHECKPOINT_ID matches ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID
 *  - KEY_STEP_INDEX matches ParticipantProgressCheckpoint.KEY_STEP_INDEX
 *  - KEY_TOTAL_STEPS matches ParticipantProgressCheckpoint.KEY_TOTAL_STEPS
 *  - KEY_SUBTASK_INDEX matches SubtaskProgressReport.KEY_SUBTASK_INDEX
 *  - KEY_SUBTASK_TOTAL matches SubtaskProgressReport.KEY_SUBTASK_TOTAL
 *  - KEY_SUBTASK_STATUS matches SubtaskProgressReport.KEY_SUBTASK_STATUS
 *  - KEY_SUBTASK_LABEL matches SubtaskProgressReport.KEY_SUBTASK_LABEL
 *  - progress keys are distinct from existing ReconciliationSignal wire keys
 *
 * ### StabilizationBaseline — PR-63 entries
 *  - exactly four entries with introducedPr == 63
 *  - participant-progress-feedback-surface is CANONICAL_STABLE with EXTEND guidance
 *  - participant-progress-checkpoint is CANONICAL_STABLE with EXTEND guidance
 *  - subtask-progress-report is CANONICAL_STABLE with EXTEND guidance
 *  - reconciliation-signal-progress-keys is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-63 entries have non-blank rationale
 *  - all PR-63 entries have a com.ufo.galaxy packagePath
 */
class Pr8ParticipantProgressFeedbackTest {

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressFeedbackSurface — structural invariants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `PROGRESS_FEEDBACK_SURFACES has exactly PROGRESS_FEEDBACK_SURFACES_COUNT entries`() {
        assertEquals(
            ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES_COUNT,
            ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES.size
        )
    }

    @Test
    fun `OUTBOUND_EXECUTION_MODELING has exactly OUTBOUND_EXECUTION_MODELING_COUNT entries`() {
        assertEquals(
            ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING_COUNT,
            ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING.size
        )
    }

    @Test
    fun `OBSERVABILITY_SURFACES has exactly OBSERVABILITY_SURFACES_COUNT entries`() {
        assertEquals(
            ParticipantProgressFeedbackSurface.OBSERVABILITY_SURFACES_COUNT,
            ParticipantProgressFeedbackSurface.OBSERVABILITY_SURFACES.size
        )
    }

    @Test
    fun `AUTHORITY_BOUNDARY_DECLARATIONS has exactly AUTHORITY_BOUNDARY_DECLARATIONS_COUNT entries`() {
        assertEquals(
            ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS_COUNT,
            ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS.size
        )
    }

    @Test
    fun `all PROGRESS_FEEDBACK_SURFACES keys are non-blank`() {
        for (key in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES.keys) {
            assertTrue("PROGRESS_FEEDBACK_SURFACES key must be non-blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all PROGRESS_FEEDBACK_SURFACES values are non-blank`() {
        for ((key, value) in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES) {
            assertTrue("PROGRESS_FEEDBACK_SURFACES value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    @Test
    fun `all OUTBOUND_EXECUTION_MODELING keys are non-blank`() {
        for (key in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING.keys) {
            assertTrue("OUTBOUND_EXECUTION_MODELING key must be non-blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all OUTBOUND_EXECUTION_MODELING values are non-blank`() {
        for ((key, value) in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING) {
            assertTrue("OUTBOUND_EXECUTION_MODELING value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    @Test
    fun `all OBSERVABILITY_SURFACES keys are non-blank`() {
        for (key in ParticipantProgressFeedbackSurface.OBSERVABILITY_SURFACES.keys) {
            assertTrue("OBSERVABILITY_SURFACES key must be non-blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all OBSERVABILITY_SURFACES values are non-blank`() {
        for ((key, value) in ParticipantProgressFeedbackSurface.OBSERVABILITY_SURFACES) {
            assertTrue("OBSERVABILITY_SURFACES value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    @Test
    fun `all AUTHORITY_BOUNDARY_DECLARATIONS keys are non-blank`() {
        for (key in ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS.keys) {
            assertTrue("AUTHORITY_BOUNDARY_DECLARATIONS key must be non-blank: '$key'", key.isNotBlank())
        }
    }

    @Test
    fun `all AUTHORITY_BOUNDARY_DECLARATIONS values are non-blank`() {
        for ((key, value) in ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS) {
            assertTrue("AUTHORITY_BOUNDARY_DECLARATIONS value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    @Test
    fun `INTRODUCED_PR is 63`() {
        assertEquals(63, ParticipantProgressFeedbackSurface.INTRODUCED_PR)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressFeedbackSurface — known keys
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `execution_checkpoint key is in PROGRESS_FEEDBACK_SURFACES`() {
        assertTrue(
            "execution_checkpoint must be in PROGRESS_FEEDBACK_SURFACES",
            "execution_checkpoint" in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES
        )
    }

    @Test
    fun `subtask_progress key is in PROGRESS_FEEDBACK_SURFACES`() {
        assertTrue(
            "subtask_progress must be in PROGRESS_FEEDBACK_SURFACES",
            "subtask_progress" in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES
        )
    }

    @Test
    fun `task_status_update_structured_payload key is in PROGRESS_FEEDBACK_SURFACES`() {
        assertTrue(
            "task_status_update_structured_payload must be in PROGRESS_FEEDBACK_SURFACES",
            "task_status_update_structured_payload" in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES
        )
    }

    @Test
    fun `checkpoint_known_ids_registry key is in PROGRESS_FEEDBACK_SURFACES`() {
        assertTrue(
            "checkpoint_known_ids_registry must be in PROGRESS_FEEDBACK_SURFACES",
            "checkpoint_known_ids_registry" in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES
        )
    }

    @Test
    fun `subtask_status_wire_vocabulary key is in PROGRESS_FEEDBACK_SURFACES`() {
        assertTrue(
            "subtask_status_wire_vocabulary must be in PROGRESS_FEEDBACK_SURFACES",
            "subtask_status_wire_vocabulary" in ParticipantProgressFeedbackSurface.PROGRESS_FEEDBACK_SURFACES
        )
    }

    @Test
    fun `pre_terminal_stage_progression key is in OUTBOUND_EXECUTION_MODELING`() {
        assertTrue(
            "pre_terminal_stage_progression must be in OUTBOUND_EXECUTION_MODELING",
            "pre_terminal_stage_progression" in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING
        )
    }

    @Test
    fun `step_level_progress_tracking key is in OUTBOUND_EXECUTION_MODELING`() {
        assertTrue(
            "step_level_progress_tracking must be in OUTBOUND_EXECUTION_MODELING",
            "step_level_progress_tracking" in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING
        )
    }

    @Test
    fun `replanning_visibility key is in OUTBOUND_EXECUTION_MODELING`() {
        assertTrue(
            "replanning_visibility must be in OUTBOUND_EXECUTION_MODELING",
            "replanning_visibility" in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING
        )
    }

    @Test
    fun `finalization_pre_terminal_signal key is in OUTBOUND_EXECUTION_MODELING`() {
        assertTrue(
            "finalization_pre_terminal_signal must be in OUTBOUND_EXECUTION_MODELING",
            "finalization_pre_terminal_signal" in ParticipantProgressFeedbackSurface.OUTBOUND_EXECUTION_MODELING
        )
    }

    @Test
    fun `android_owns_checkpoint_identity key is in AUTHORITY_BOUNDARY_DECLARATIONS`() {
        assertTrue(
            "android_owns_checkpoint_identity must be in AUTHORITY_BOUNDARY_DECLARATIONS",
            "android_owns_checkpoint_identity" in ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS
        )
    }

    @Test
    fun `v2_owns_task_lifecycle_outcomes key is in AUTHORITY_BOUNDARY_DECLARATIONS`() {
        assertTrue(
            "v2_owns_task_lifecycle_outcomes must be in AUTHORITY_BOUNDARY_DECLARATIONS",
            "v2_owns_task_lifecycle_outcomes" in ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS
        )
    }

    @Test
    fun `v2_owns_dispatch_and_rebalance key is in AUTHORITY_BOUNDARY_DECLARATIONS`() {
        assertTrue(
            "v2_owns_dispatch_and_rebalance must be in AUTHORITY_BOUNDARY_DECLARATIONS",
            "v2_owns_dispatch_and_rebalance" in ParticipantProgressFeedbackSurface.AUTHORITY_BOUNDARY_DECLARATIONS
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressFeedbackSurface — wire-key forwarding constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `surface KEY_CHECKPOINT_ID equals ParticipantProgressCheckpoint KEY_CHECKPOINT_ID`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID,
            ParticipantProgressFeedbackSurface.KEY_CHECKPOINT_ID
        )
    }

    @Test
    fun `surface KEY_STEP_INDEX equals ParticipantProgressCheckpoint KEY_STEP_INDEX`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_STEP_INDEX,
            ParticipantProgressFeedbackSurface.KEY_STEP_INDEX
        )
    }

    @Test
    fun `surface KEY_TOTAL_STEPS equals ParticipantProgressCheckpoint KEY_TOTAL_STEPS`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_TOTAL_STEPS,
            ParticipantProgressFeedbackSurface.KEY_TOTAL_STEPS
        )
    }

    @Test
    fun `surface KEY_SUBTASK_INDEX equals SubtaskProgressReport KEY_SUBTASK_INDEX`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_INDEX,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_INDEX
        )
    }

    @Test
    fun `surface KEY_SUBTASK_TOTAL equals SubtaskProgressReport KEY_SUBTASK_TOTAL`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_TOTAL,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_TOTAL
        )
    }

    @Test
    fun `surface KEY_SUBTASK_STATUS equals SubtaskProgressReport KEY_SUBTASK_STATUS`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_STATUS,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_STATUS
        )
    }

    @Test
    fun `surface KEY_SUBTASK_LABEL equals SubtaskProgressReport KEY_SUBTASK_LABEL`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_LABEL,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_LABEL
        )
    }

    @Test
    fun `all surface forwarding keys are non-blank`() {
        val keys = listOf(
            ParticipantProgressFeedbackSurface.KEY_CHECKPOINT_ID,
            ParticipantProgressFeedbackSurface.KEY_STEP_INDEX,
            ParticipantProgressFeedbackSurface.KEY_TOTAL_STEPS,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_INDEX,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_TOTAL,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_STATUS,
            ParticipantProgressFeedbackSurface.KEY_SUBTASK_LABEL
        )
        for (key in keys) {
            assertTrue("Surface forwarding key must be non-blank: '$key'", key.isNotBlank())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressCheckpoint — known checkpoint IDs
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `KNOWN_CHECKPOINT_IDS has exactly KNOWN_CHECKPOINT_COUNT entries`() {
        assertEquals(
            ParticipantProgressFeedbackSurface.KNOWN_CHECKPOINT_COUNT,
            ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS.size
        )
    }

    @Test
    fun `CHECKPOINT_PLANNING_STARTED is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_PLANNING_STARTED in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_PLANNING_COMPLETE is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_PLANNING_COMPLETE in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_GROUNDING_STARTED is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_GROUNDING_STARTED in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_GROUNDING_COMPLETE is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_GROUNDING_COMPLETE in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_STEP_EXECUTING is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_STEP_EXECUTING in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_STEP_COMPLETE is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_STEP_COMPLETE in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_REPLANNING is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_REPLANNING in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `CHECKPOINT_FINALIZING is in KNOWN_CHECKPOINT_IDS`() {
        assertTrue(
            ParticipantProgressCheckpoint.CHECKPOINT_FINALIZING in
                ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        )
    }

    @Test
    fun `all KNOWN_CHECKPOINT_IDS are non-blank`() {
        for (id in ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS) {
            assertTrue("Checkpoint ID must be non-blank: '$id'", id.isNotBlank())
        }
    }

    @Test
    fun `all KNOWN_CHECKPOINT_IDS are distinct`() {
        val ids = ParticipantProgressCheckpoint.KNOWN_CHECKPOINT_IDS
        assertEquals(
            "All checkpoint IDs must be distinct",
            ids.size,
            ids.toSet().size
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressCheckpoint — wire key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `KEY_CHECKPOINT_ID is checkpoint_id`() {
        assertEquals("checkpoint_id", ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID)
    }

    @Test
    fun `KEY_TASK_ID is checkpoint_task_id`() {
        assertEquals("checkpoint_task_id", ParticipantProgressCheckpoint.KEY_TASK_ID)
    }

    @Test
    fun `KEY_PARTICIPANT_ID is checkpoint_participant_id`() {
        assertEquals("checkpoint_participant_id", ParticipantProgressCheckpoint.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `KEY_STEP_INDEX is checkpoint_step_index`() {
        assertEquals("checkpoint_step_index", ParticipantProgressCheckpoint.KEY_STEP_INDEX)
    }

    @Test
    fun `KEY_TOTAL_STEPS is checkpoint_total_steps`() {
        assertEquals("checkpoint_total_steps", ParticipantProgressCheckpoint.KEY_TOTAL_STEPS)
    }

    @Test
    fun `KEY_DETAIL is checkpoint_detail`() {
        assertEquals("checkpoint_detail", ParticipantProgressCheckpoint.KEY_DETAIL)
    }

    @Test
    fun `KEY_EMITTED_AT_MS is checkpoint_emitted_at_ms`() {
        assertEquals("checkpoint_emitted_at_ms", ParticipantProgressCheckpoint.KEY_EMITTED_AT_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantProgressCheckpoint — toPayloadMap()
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildCheckpoint(
        checkpointId: String = ParticipantProgressCheckpoint.CHECKPOINT_PLANNING_COMPLETE,
        taskId: String = "task-pr8",
        participantId: String = "device-pr8:host-pr8",
        stepIndex: Int = 1,
        totalSteps: Int? = null,
        detail: String? = null
    ) = ParticipantProgressCheckpoint(
        checkpointId = checkpointId,
        taskId = taskId,
        participantId = participantId,
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        detail = detail
    )

    @Test
    fun `toPayloadMap contains KEY_CHECKPOINT_ID`() {
        val map = buildCheckpoint().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_CHECKPOINT_ID",
            ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID in map
        )
    }

    @Test
    fun `toPayloadMap contains KEY_TASK_ID`() {
        val map = buildCheckpoint().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_TASK_ID",
            ParticipantProgressCheckpoint.KEY_TASK_ID in map
        )
    }

    @Test
    fun `toPayloadMap contains KEY_PARTICIPANT_ID`() {
        val map = buildCheckpoint().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_PARTICIPANT_ID",
            ParticipantProgressCheckpoint.KEY_PARTICIPANT_ID in map
        )
    }

    @Test
    fun `toPayloadMap contains KEY_STEP_INDEX`() {
        val map = buildCheckpoint().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_STEP_INDEX",
            ParticipantProgressCheckpoint.KEY_STEP_INDEX in map
        )
    }

    @Test
    fun `toPayloadMap contains KEY_EMITTED_AT_MS`() {
        val map = buildCheckpoint().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_EMITTED_AT_MS",
            ParticipantProgressCheckpoint.KEY_EMITTED_AT_MS in map
        )
    }

    @Test
    fun `toPayloadMap omits KEY_TOTAL_STEPS when totalSteps is null`() {
        val map = buildCheckpoint(totalSteps = null).toPayloadMap()
        assertFalse(
            "toPayloadMap must omit KEY_TOTAL_STEPS when totalSteps is null",
            ParticipantProgressCheckpoint.KEY_TOTAL_STEPS in map
        )
    }

    @Test
    fun `toPayloadMap includes KEY_TOTAL_STEPS when totalSteps is non-null`() {
        val map = buildCheckpoint(totalSteps = 5).toPayloadMap()
        assertTrue(
            "toPayloadMap must include KEY_TOTAL_STEPS when totalSteps is non-null",
            ParticipantProgressCheckpoint.KEY_TOTAL_STEPS in map
        )
    }

    @Test
    fun `toPayloadMap omits KEY_DETAIL when detail is null`() {
        val map = buildCheckpoint(detail = null).toPayloadMap()
        assertFalse(
            "toPayloadMap must omit KEY_DETAIL when detail is null",
            ParticipantProgressCheckpoint.KEY_DETAIL in map
        )
    }

    @Test
    fun `toPayloadMap includes KEY_DETAIL when detail is non-null`() {
        val map = buildCheckpoint(detail = "plan revision after step 2").toPayloadMap()
        assertTrue(
            "toPayloadMap must include KEY_DETAIL when detail is non-null",
            ParticipantProgressCheckpoint.KEY_DETAIL in map
        )
    }

    @Test
    fun `toPayloadMap checkpointId matches provided value`() {
        val expected = ParticipantProgressCheckpoint.CHECKPOINT_GROUNDING_COMPLETE
        val map = buildCheckpoint(checkpointId = expected).toPayloadMap()
        assertEquals(expected, map[ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID])
    }

    @Test
    fun `toPayloadMap stepIndex matches provided value`() {
        val map = buildCheckpoint(stepIndex = 3).toPayloadMap()
        assertEquals(3, map[ParticipantProgressCheckpoint.KEY_STEP_INDEX])
    }

    @Test
    fun `toPayloadMap totalSteps matches provided value`() {
        val map = buildCheckpoint(totalSteps = 7).toPayloadMap()
        assertEquals(7, map[ParticipantProgressCheckpoint.KEY_TOTAL_STEPS])
    }

    @Test
    fun `toPayloadMap detail matches provided value`() {
        val expected = "re-plan after step 2 failed"
        val map = buildCheckpoint(detail = expected).toPayloadMap()
        assertEquals(expected, map[ParticipantProgressCheckpoint.KEY_DETAIL])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SubtaskProgressReport.SubtaskStatus — enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SubtaskStatus PENDING wireValue is pending`() {
        assertEquals("pending", SubtaskProgressReport.SubtaskStatus.PENDING.wireValue)
    }

    @Test
    fun `SubtaskStatus EXECUTING wireValue is executing`() {
        assertEquals("executing", SubtaskProgressReport.SubtaskStatus.EXECUTING.wireValue)
    }

    @Test
    fun `SubtaskStatus COMPLETE wireValue is complete`() {
        assertEquals("complete", SubtaskProgressReport.SubtaskStatus.COMPLETE.wireValue)
    }

    @Test
    fun `SubtaskStatus FAILED wireValue is failed`() {
        assertEquals("failed", SubtaskProgressReport.SubtaskStatus.FAILED.wireValue)
    }

    @Test
    fun `SubtaskStatus SKIPPED wireValue is skipped`() {
        assertEquals("skipped", SubtaskProgressReport.SubtaskStatus.SKIPPED.wireValue)
    }

    @Test
    fun `SubtaskStatus ALL_WIRE_VALUES has 5 entries`() {
        assertEquals(5, SubtaskProgressReport.SubtaskStatus.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `SubtaskStatus all wire values are distinct`() {
        val values = SubtaskProgressReport.SubtaskStatus.ALL_WIRE_VALUES
        assertEquals("SubtaskStatus wire values must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `SubtaskStatus fromValue returns correct enum for each known wire value`() {
        for (status in SubtaskProgressReport.SubtaskStatus.entries) {
            val parsed = SubtaskProgressReport.SubtaskStatus.fromValue(status.wireValue)
            assertEquals("fromValue must return ${status.name} for '${status.wireValue}'", status, parsed)
        }
    }

    @Test
    fun `SubtaskStatus fromValue returns null for unknown value`() {
        assertNull(SubtaskProgressReport.SubtaskStatus.fromValue("not_a_status"))
    }

    @Test
    fun `SubtaskStatus fromValue returns null for null input`() {
        assertNull(SubtaskProgressReport.SubtaskStatus.fromValue(null))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SubtaskProgressReport — wire key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SubtaskProgressReport KEY_TASK_ID is subtask_task_id`() {
        assertEquals("subtask_task_id", SubtaskProgressReport.KEY_TASK_ID)
    }

    @Test
    fun `SubtaskProgressReport KEY_PARTICIPANT_ID is subtask_participant_id`() {
        assertEquals("subtask_participant_id", SubtaskProgressReport.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `SubtaskProgressReport KEY_SUBTASK_INDEX is subtask_index`() {
        assertEquals("subtask_index", SubtaskProgressReport.KEY_SUBTASK_INDEX)
    }

    @Test
    fun `SubtaskProgressReport KEY_SUBTASK_TOTAL is subtask_total`() {
        assertEquals("subtask_total", SubtaskProgressReport.KEY_SUBTASK_TOTAL)
    }

    @Test
    fun `SubtaskProgressReport KEY_SUBTASK_LABEL is subtask_label`() {
        assertEquals("subtask_label", SubtaskProgressReport.KEY_SUBTASK_LABEL)
    }

    @Test
    fun `SubtaskProgressReport KEY_SUBTASK_STATUS is subtask_status`() {
        assertEquals("subtask_status", SubtaskProgressReport.KEY_SUBTASK_STATUS)
    }

    @Test
    fun `SubtaskProgressReport KEY_EMITTED_AT_MS is subtask_emitted_at_ms`() {
        assertEquals("subtask_emitted_at_ms", SubtaskProgressReport.KEY_EMITTED_AT_MS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SubtaskProgressReport — toPayloadMap()
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildReport(
        taskId: String = "task-pr8",
        participantId: String = "device-pr8:host-pr8",
        subtaskIndex: Int = 2,
        subtaskTotal: Int? = null,
        subtaskLabel: String = "Tap settings icon",
        subtaskStatus: SubtaskProgressReport.SubtaskStatus = SubtaskProgressReport.SubtaskStatus.EXECUTING
    ) = SubtaskProgressReport(
        taskId = taskId,
        participantId = participantId,
        subtaskIndex = subtaskIndex,
        subtaskTotal = subtaskTotal,
        subtaskLabel = subtaskLabel,
        subtaskStatus = subtaskStatus
    )

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_TASK_ID`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_TASK_ID",
            SubtaskProgressReport.KEY_TASK_ID in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_PARTICIPANT_ID`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_PARTICIPANT_ID",
            SubtaskProgressReport.KEY_PARTICIPANT_ID in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_SUBTASK_INDEX`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_SUBTASK_INDEX",
            SubtaskProgressReport.KEY_SUBTASK_INDEX in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_SUBTASK_LABEL`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_SUBTASK_LABEL",
            SubtaskProgressReport.KEY_SUBTASK_LABEL in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_SUBTASK_STATUS`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_SUBTASK_STATUS",
            SubtaskProgressReport.KEY_SUBTASK_STATUS in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap contains KEY_EMITTED_AT_MS`() {
        val map = buildReport().toPayloadMap()
        assertTrue(
            "toPayloadMap must contain KEY_EMITTED_AT_MS",
            SubtaskProgressReport.KEY_EMITTED_AT_MS in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap omits KEY_SUBTASK_TOTAL when subtaskTotal is null`() {
        val map = buildReport(subtaskTotal = null).toPayloadMap()
        assertFalse(
            "toPayloadMap must omit KEY_SUBTASK_TOTAL when subtaskTotal is null",
            SubtaskProgressReport.KEY_SUBTASK_TOTAL in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap includes KEY_SUBTASK_TOTAL when subtaskTotal is non-null`() {
        val map = buildReport(subtaskTotal = 5).toPayloadMap()
        assertTrue(
            "toPayloadMap must include KEY_SUBTASK_TOTAL when subtaskTotal is non-null",
            SubtaskProgressReport.KEY_SUBTASK_TOTAL in map
        )
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap subtaskIndex matches provided value`() {
        val map = buildReport(subtaskIndex = 4).toPayloadMap()
        assertEquals(4, map[SubtaskProgressReport.KEY_SUBTASK_INDEX])
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap subtaskLabel matches provided value`() {
        val expected = "Type search query"
        val map = buildReport(subtaskLabel = expected).toPayloadMap()
        assertEquals(expected, map[SubtaskProgressReport.KEY_SUBTASK_LABEL])
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap subtaskStatus wireValue matches provided status`() {
        val map = buildReport(subtaskStatus = SubtaskProgressReport.SubtaskStatus.COMPLETE).toPayloadMap()
        assertEquals("complete", map[SubtaskProgressReport.KEY_SUBTASK_STATUS])
    }

    @Test
    fun `SubtaskProgressReport toPayloadMap subtaskTotal matches provided value`() {
        val map = buildReport(subtaskTotal = 6).toPayloadMap()
        assertEquals(6, map[SubtaskProgressReport.KEY_SUBTASK_TOTAL])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ReconciliationSignal — progress payload key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ReconciliationSignal KEY_CHECKPOINT_ID is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_CHECKPOINT_ID.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_STEP_INDEX is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_STEP_INDEX.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_TOTAL_STEPS is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_TOTAL_STEPS.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_INDEX is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_SUBTASK_INDEX.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_TOTAL is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_SUBTASK_TOTAL.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_STATUS is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_SUBTASK_STATUS.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_LABEL is non-blank`() {
        assertTrue(ReconciliationSignal.KEY_SUBTASK_LABEL.isNotBlank())
    }

    @Test
    fun `ReconciliationSignal KEY_CHECKPOINT_ID matches ParticipantProgressCheckpoint KEY_CHECKPOINT_ID`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_CHECKPOINT_ID,
            ReconciliationSignal.KEY_CHECKPOINT_ID
        )
    }

    @Test
    fun `ReconciliationSignal KEY_STEP_INDEX matches ParticipantProgressCheckpoint KEY_STEP_INDEX`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_STEP_INDEX,
            ReconciliationSignal.KEY_STEP_INDEX
        )
    }

    @Test
    fun `ReconciliationSignal KEY_TOTAL_STEPS matches ParticipantProgressCheckpoint KEY_TOTAL_STEPS`() {
        assertEquals(
            ParticipantProgressCheckpoint.KEY_TOTAL_STEPS,
            ReconciliationSignal.KEY_TOTAL_STEPS
        )
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_INDEX matches SubtaskProgressReport KEY_SUBTASK_INDEX`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_INDEX,
            ReconciliationSignal.KEY_SUBTASK_INDEX
        )
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_TOTAL matches SubtaskProgressReport KEY_SUBTASK_TOTAL`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_TOTAL,
            ReconciliationSignal.KEY_SUBTASK_TOTAL
        )
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_STATUS matches SubtaskProgressReport KEY_SUBTASK_STATUS`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_STATUS,
            ReconciliationSignal.KEY_SUBTASK_STATUS
        )
    }

    @Test
    fun `ReconciliationSignal KEY_SUBTASK_LABEL matches SubtaskProgressReport KEY_SUBTASK_LABEL`() {
        assertEquals(
            SubtaskProgressReport.KEY_SUBTASK_LABEL,
            ReconciliationSignal.KEY_SUBTASK_LABEL
        )
    }

    @Test
    fun `progress keys are distinct from existing ReconciliationSignal wire keys`() {
        val existingKeys = setOf(
            ReconciliationSignal.KEY_KIND,
            ReconciliationSignal.KEY_PARTICIPANT_ID,
            ReconciliationSignal.KEY_TASK_ID,
            ReconciliationSignal.KEY_CORRELATION_ID,
            ReconciliationSignal.KEY_STATUS,
            ReconciliationSignal.KEY_SIGNAL_ID,
            ReconciliationSignal.KEY_EMITTED_AT_MS,
            ReconciliationSignal.KEY_RECONCILIATION_EPOCH
        )
        val progressKeys = setOf(
            ReconciliationSignal.KEY_CHECKPOINT_ID,
            ReconciliationSignal.KEY_STEP_INDEX,
            ReconciliationSignal.KEY_TOTAL_STEPS,
            ReconciliationSignal.KEY_SUBTASK_INDEX,
            ReconciliationSignal.KEY_SUBTASK_TOTAL,
            ReconciliationSignal.KEY_SUBTASK_STATUS,
            ReconciliationSignal.KEY_SUBTASK_LABEL
        )
        for (pk in progressKeys) {
            assertFalse(
                "Progress key '$pk' must not collide with existing wire key",
                pk in existingKeys
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // StabilizationBaseline — PR-63 entries
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `exactly four entries with introducedPr 63`() {
        val pr63Entries = StabilizationBaseline.entries.filter { it.introducedPr == 63 }
        assertEquals(
            "Exactly four StabilizationBaseline entries must have introducedPr == 63",
            4,
            pr63Entries.size
        )
    }

    @Test
    fun `participant-progress-feedback-surface is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-progress-feedback-surface")
        assertNotNull("participant-progress-feedback-surface must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `participant-progress-checkpoint is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-progress-checkpoint")
        assertNotNull("participant-progress-checkpoint must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `subtask-progress-report is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("subtask-progress-report")
        assertNotNull("subtask-progress-report must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `reconciliation-signal-progress-keys is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("reconciliation-signal-progress-keys")
        assertNotNull("reconciliation-signal-progress-keys must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-63 entries have non-blank rationale`() {
        val pr63Entries = StabilizationBaseline.entries.filter { it.introducedPr == 63 }
        for (entry in pr63Entries) {
            assertTrue(
                "PR-63 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-63 entries have a com dot ufo dot galaxy packagePath`() {
        val pr63Entries = StabilizationBaseline.entries.filter { it.introducedPr == 63 }
        for (entry in pr63Entries) {
            assertTrue(
                "PR-63 entry '${entry.surfaceId}' must have a com.ufo.galaxy packagePath, got: ${entry.packagePath}",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }
}
