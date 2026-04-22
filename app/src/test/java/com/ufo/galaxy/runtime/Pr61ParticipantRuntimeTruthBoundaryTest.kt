package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-61 — Participant Runtime Truth Boundary: Acceptance and Regression Test Suite.
 *
 * This test file validates the three acceptance criteria for the Android participant runtime
 * truth and execution semantics PR:
 *
 *  1. **[ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN]** — Android-side truth
 *     ownership: 10 named truth domains with canonical surface, snapshot surface, and V2
 *     consumer action for each.
 *
 *  2. **[ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES]** — Cancel/status/failure/
 *     result execution semantics protocol: 7 outcomes covering all task lifecycle phases,
 *     with phase, isTerminal, emit-condition, and V2-action annotations.
 *
 *  3. **[ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_DECLARATION]** —
 *     Explicit first-class participant runtime declaration: 9 named behaviours distinguishing
 *     Android from a passive executor, plus 5 non-orchestration-authority boundaries.
 *
 *  4. **[StabilizationBaseline]** — exactly three PR-61 entries and exactly three PR-60
 *     entries are registered as CANONICAL_STABLE.
 *
 * ## Test matrix
 *
 * ### ParticipantRuntimeSemanticsBoundary — truth domain inventory
 *  - ANDROID_TRUTH_DOMAIN has exactly TRUTH_DOMAIN_COUNT entries
 *  - all truth domain keys are non-blank
 *  - all truth domains have non-empty fields list
 *  - all truth domains have non-blank canonicalSurface
 *  - all truth domains have non-blank snapshotSurface
 *  - all truth domains have non-blank v2ConsumerAction
 *  - participant_identity domain is present
 *  - session_attachment domain is present
 *  - health_state domain is present
 *  - readiness_state domain is present
 *  - active_task_status domain is present
 *  - task_outcomes domain is present
 *  - runtime_posture domain is present
 *  - lifecycle_transitions domain is present
 *  - hybrid_capability domain is present
 *
 * ### ParticipantRuntimeSemanticsBoundary — execution outcome protocol
 *  - EXECUTION_OUTCOMES has exactly EXECUTION_OUTCOME_COUNT entries
 *  - TERMINAL_OUTCOME_COUNT terminal outcomes are exactly three
 *  - all outcomes have non-blank signalKind
 *  - all signal kinds match ReconciliationSignal.Kind wire values
 *  - all outcomes have non-blank androidEmitCondition
 *  - all outcomes have non-blank v2Action
 *  - TASK_ACCEPTED outcome is PRE_EXECUTION and non-terminal
 *  - TASK_STATUS_UPDATE outcome is IN_PROGRESS and non-terminal
 *  - TASK_RESULT outcome is TERMINAL and isTerminal is true
 *  - TASK_CANCELLED outcome is TERMINAL and isTerminal is true
 *  - TASK_FAILED outcome is TERMINAL and isTerminal is true
 *  - PARTICIPANT_STATE outcome is ANY and non-terminal
 *  - RUNTIME_TRUTH_SNAPSHOT outcome is ANY and non-terminal
 *  - terminal outcomes are exactly TASK_RESULT, TASK_CANCELLED, TASK_FAILED
 *  - non-terminal outcomes count is EXECUTION_OUTCOME_COUNT - TERMINAL_OUTCOME_COUNT
 *
 * ### ParticipantRuntimeSemanticsBoundary — protocol safety rules
 *  - PROTOCOL_SAFETY_RULES has exactly five entries
 *  - RULE_NO_RESULT_AFTER_CANCEL is in PROTOCOL_SAFETY_RULES
 *  - RULE_SIGNAL_ID_UNIQUE is in PROTOCOL_SAFETY_RULES
 *  - RULE_EPOCH_MONOTONIC is in PROTOCOL_SAFETY_RULES
 *  - RULE_SNAPSHOT_AUTHORITATIVE is in PROTOCOL_SAFETY_RULES
 *  - RULE_ANDROID_LOCAL_TRUTH_ONLY is in PROTOCOL_SAFETY_RULES
 *  - all protocol safety rules are non-blank
 *
 * ### ParticipantRuntimeSemanticsBoundary — first-class participant declaration
 *  - FIRST_CLASS_PARTICIPANT_DECLARATION is non-blank
 *  - FIRST_CLASS_PARTICIPANT_BEHAVIOURS has exactly FIRST_CLASS_BEHAVIOUR_COUNT entries
 *  - all behaviour keys are non-blank
 *  - all behaviour values are non-blank
 *  - local_ai_execution_loop behaviour is present
 *  - local_truth_ownership behaviour is present
 *  - lifecycle_authority behaviour is present
 *  - pre_terminal_cancel_signal behaviour is present
 *  - pre_terminal_failure_signal behaviour is present
 *  - posture_elevation behaviour is present
 *  - health_self_assessment behaviour is present
 *  - readiness_self_assessment behaviour is present
 *  - formation_participation behaviour is present
 *
 * ### ParticipantRuntimeSemanticsBoundary — non-orchestration boundaries
 *  - NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES has exactly NOT_ORCHESTRATION_BOUNDARY_COUNT entries
 *  - no_task_assignment boundary is present
 *  - no_unilateral_session_continuation boundary is present
 *  - no_canonical_truth_override boundary is present
 *  - no_barrier_coordination boundary is present
 *  - no_formation_rebalance_authority boundary is present
 *  - all boundary keys are non-blank
 *  - all boundary values are non-blank
 *
 * ### ParticipantRuntimeSemanticsBoundary.ExecutionPhase — wire values
 *  - PRE_EXECUTION wireValue is "pre_execution"
 *  - IN_PROGRESS wireValue is "in_progress"
 *  - TERMINAL wireValue is "terminal"
 *  - ANY wireValue is "any"
 *  - all four ExecutionPhase wire values are distinct
 *
 * ### StabilizationBaseline — PR-60 entries
 *  - exactly three entries with introducedPr == 60
 *  - android-app-lifecycle-transition is CANONICAL_STABLE with EXTEND guidance
 *  - hybrid-participant-capability is CANONICAL_STABLE with EXTEND guidance
 *  - android-lifecycle-recovery-contract is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-60 entries have non-blank rationale
 *  - all PR-60 entries have a com.ufo.galaxy packagePath
 *
 * ### StabilizationBaseline — PR-61 entries
 *  - exactly three entries with introducedPr == 61
 *  - participant-runtime-semantics-boundary is CANONICAL_STABLE with EXTEND guidance
 *  - participant-execution-phase is CANONICAL_STABLE with EXTEND guidance
 *  - participant-truth-domain-entry is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-61 entries have non-blank rationale
 *  - all PR-61 entries have a com.ufo.galaxy packagePath
 */
class Pr61ParticipantRuntimeTruthBoundaryTest {

    // ── ParticipantRuntimeSemanticsBoundary — truth domain inventory ──────────

    @Test
    fun `ANDROID_TRUTH_DOMAIN has exactly TRUTH_DOMAIN_COUNT entries`() {
        assertEquals(
            ParticipantRuntimeSemanticsBoundary.TRUTH_DOMAIN_COUNT,
            ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.size
        )
    }

    @Test
    fun `all truth domain keys are non-blank`() {
        for ((key, _) in ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN) {
            assertTrue("Truth domain key must be non-blank", key.isNotBlank())
        }
    }

    @Test
    fun `all truth domains have non-empty fields list`() {
        for ((key, entry) in ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN) {
            assertTrue(
                "Truth domain '$key' must have non-empty fields list",
                entry.fields.isNotEmpty()
            )
        }
    }

    @Test
    fun `all truth domains have non-blank canonicalSurface`() {
        for ((key, entry) in ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN) {
            assertTrue(
                "Truth domain '$key' must have non-blank canonicalSurface",
                entry.canonicalSurface.isNotBlank()
            )
        }
    }

    @Test
    fun `all truth domains have non-blank snapshotSurface`() {
        for ((key, entry) in ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN) {
            assertTrue(
                "Truth domain '$key' must have non-blank snapshotSurface",
                entry.snapshotSurface.isNotBlank()
            )
        }
    }

    @Test
    fun `all truth domains have non-blank v2ConsumerAction`() {
        for ((key, entry) in ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN) {
            assertTrue(
                "Truth domain '$key' must have non-blank v2ConsumerAction",
                entry.v2ConsumerAction.isNotBlank()
            )
        }
    }

    @Test
    fun `participant_identity domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("participant_identity"))
    }

    @Test
    fun `session_attachment domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("session_attachment"))
    }

    @Test
    fun `health_state domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("health_state"))
    }

    @Test
    fun `readiness_state domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("readiness_state"))
    }

    @Test
    fun `active_task_status domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("active_task_status"))
    }

    @Test
    fun `task_outcomes domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("task_outcomes"))
    }

    @Test
    fun `runtime_posture domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("runtime_posture"))
    }

    @Test
    fun `lifecycle_transitions domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("lifecycle_transitions"))
    }

    @Test
    fun `hybrid_capability domain is present`() {
        assertTrue(ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN.containsKey("hybrid_capability"))
    }

    // ── ParticipantRuntimeSemanticsBoundary — execution outcome protocol ──────

    @Test
    fun `EXECUTION_OUTCOMES has exactly EXECUTION_OUTCOME_COUNT entries`() {
        assertEquals(
            ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOME_COUNT,
            ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES.size
        )
    }

    @Test
    fun `TERMINAL_OUTCOME_COUNT is exactly three`() {
        assertEquals(3, ParticipantRuntimeSemanticsBoundary.TERMINAL_OUTCOME_COUNT)
    }

    @Test
    fun `all outcomes have non-blank signalKind`() {
        for (outcome in ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES) {
            assertTrue(
                "Outcome signalKind must be non-blank (phase=${outcome.phase})",
                outcome.signalKind.isNotBlank()
            )
        }
    }

    @Test
    fun `all signal kinds match ReconciliationSignal Kind wire values`() {
        val allWireValues = ReconciliationSignal.Kind.ALL_WIRE_VALUES
        for (outcome in ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES) {
            assertTrue(
                "signalKind '${outcome.signalKind}' must be a known ReconciliationSignal.Kind wire value",
                allWireValues.contains(outcome.signalKind)
            )
        }
    }

    @Test
    fun `all outcomes have non-blank androidEmitCondition`() {
        for (outcome in ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES) {
            assertTrue(
                "Outcome for '${outcome.signalKind}' must have non-blank androidEmitCondition",
                outcome.androidEmitCondition.isNotBlank()
            )
        }
    }

    @Test
    fun `all outcomes have non-blank v2Action`() {
        for (outcome in ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES) {
            assertTrue(
                "Outcome for '${outcome.signalKind}' must have non-blank v2Action",
                outcome.v2Action.isNotBlank()
            )
        }
    }

    @Test
    fun `TASK_ACCEPTED outcome is PRE_EXECUTION and non-terminal`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.TASK_ACCEPTED.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.PRE_EXECUTION, outcome.phase)
        assertFalse("TASK_ACCEPTED must not be terminal", outcome.isTerminal)
    }

    @Test
    fun `TASK_STATUS_UPDATE outcome is IN_PROGRESS and non-terminal`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.TASK_STATUS_UPDATE.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.IN_PROGRESS, outcome.phase)
        assertFalse("TASK_STATUS_UPDATE must not be terminal", outcome.isTerminal)
    }

    @Test
    fun `TASK_RESULT outcome is TERMINAL and isTerminal true`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.TASK_RESULT.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.TERMINAL, outcome.phase)
        assertTrue("TASK_RESULT must be terminal", outcome.isTerminal)
    }

    @Test
    fun `TASK_CANCELLED outcome is TERMINAL and isTerminal true`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.TASK_CANCELLED.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.TERMINAL, outcome.phase)
        assertTrue("TASK_CANCELLED must be terminal", outcome.isTerminal)
    }

    @Test
    fun `TASK_FAILED outcome is TERMINAL and isTerminal true`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.TASK_FAILED.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.TERMINAL, outcome.phase)
        assertTrue("TASK_FAILED must be terminal", outcome.isTerminal)
    }

    @Test
    fun `PARTICIPANT_STATE outcome is ANY and non-terminal`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.PARTICIPANT_STATE.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.ANY, outcome.phase)
        assertFalse("PARTICIPANT_STATE must not be terminal", outcome.isTerminal)
    }

    @Test
    fun `RUNTIME_TRUTH_SNAPSHOT outcome is ANY and non-terminal`() {
        val outcome = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .first { it.signalKind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT.wireValue }
        assertEquals(ParticipantRuntimeSemanticsBoundary.ExecutionPhase.ANY, outcome.phase)
        assertFalse("RUNTIME_TRUTH_SNAPSHOT must not be terminal", outcome.isTerminal)
    }

    @Test
    fun `terminal outcomes are exactly TASK_RESULT TASK_CANCELLED TASK_FAILED`() {
        val terminalKinds = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .filter { it.isTerminal }
            .map { it.signalKind }
            .toSet()
        assertEquals(
            setOf(
                ReconciliationSignal.Kind.TASK_RESULT.wireValue,
                ReconciliationSignal.Kind.TASK_CANCELLED.wireValue,
                ReconciliationSignal.Kind.TASK_FAILED.wireValue
            ),
            terminalKinds
        )
    }

    @Test
    fun `non-terminal outcomes count is EXECUTION_OUTCOME_COUNT minus TERMINAL_OUTCOME_COUNT`() {
        val nonTerminalCount = ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOMES
            .count { !it.isTerminal }
        assertEquals(
            ParticipantRuntimeSemanticsBoundary.EXECUTION_OUTCOME_COUNT -
                ParticipantRuntimeSemanticsBoundary.TERMINAL_OUTCOME_COUNT,
            nonTerminalCount
        )
    }

    // ── ParticipantRuntimeSemanticsBoundary — protocol safety rules ───────────

    @Test
    fun `PROTOCOL_SAFETY_RULES has exactly five entries`() {
        assertEquals(5, ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES.size)
    }

    @Test
    fun `RULE_NO_RESULT_AFTER_CANCEL is in PROTOCOL_SAFETY_RULES`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES
                .contains(ParticipantRuntimeSemanticsBoundary.RULE_NO_RESULT_AFTER_CANCEL)
        )
    }

    @Test
    fun `RULE_SIGNAL_ID_UNIQUE is in PROTOCOL_SAFETY_RULES`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES
                .contains(ParticipantRuntimeSemanticsBoundary.RULE_SIGNAL_ID_UNIQUE)
        )
    }

    @Test
    fun `RULE_EPOCH_MONOTONIC is in PROTOCOL_SAFETY_RULES`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES
                .contains(ParticipantRuntimeSemanticsBoundary.RULE_EPOCH_MONOTONIC)
        )
    }

    @Test
    fun `RULE_SNAPSHOT_AUTHORITATIVE is in PROTOCOL_SAFETY_RULES`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES
                .contains(ParticipantRuntimeSemanticsBoundary.RULE_SNAPSHOT_AUTHORITATIVE)
        )
    }

    @Test
    fun `RULE_ANDROID_LOCAL_TRUTH_ONLY is in PROTOCOL_SAFETY_RULES`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES
                .contains(ParticipantRuntimeSemanticsBoundary.RULE_ANDROID_LOCAL_TRUTH_ONLY)
        )
    }

    @Test
    fun `all protocol safety rules are non-blank`() {
        for (rule in ParticipantRuntimeSemanticsBoundary.PROTOCOL_SAFETY_RULES) {
            assertTrue("Protocol safety rule must be non-blank", rule.isNotBlank())
        }
    }

    // ── ParticipantRuntimeSemanticsBoundary — first-class participant declaration

    @Test
    fun `FIRST_CLASS_PARTICIPANT_DECLARATION is non-blank`() {
        assertTrue(
            "FIRST_CLASS_PARTICIPANT_DECLARATION must be non-blank",
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_DECLARATION.isNotBlank()
        )
    }

    @Test
    fun `FIRST_CLASS_PARTICIPANT_BEHAVIOURS has exactly FIRST_CLASS_BEHAVIOUR_COUNT entries`() {
        assertEquals(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_BEHAVIOUR_COUNT,
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS.size
        )
    }

    @Test
    fun `all behaviour keys are non-blank`() {
        for ((key, _) in ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS) {
            assertTrue("Behaviour key must be non-blank", key.isNotBlank())
        }
    }

    @Test
    fun `all behaviour values are non-blank`() {
        for ((key, value) in ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS) {
            assertTrue("Behaviour value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    @Test
    fun `local_ai_execution_loop behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("local_ai_execution_loop")
        )
    }

    @Test
    fun `local_truth_ownership behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("local_truth_ownership")
        )
    }

    @Test
    fun `lifecycle_authority behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("lifecycle_authority")
        )
    }

    @Test
    fun `pre_terminal_cancel_signal behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("pre_terminal_cancel_signal")
        )
    }

    @Test
    fun `pre_terminal_failure_signal behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("pre_terminal_failure_signal")
        )
    }

    @Test
    fun `posture_elevation behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("posture_elevation")
        )
    }

    @Test
    fun `health_self_assessment behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("health_self_assessment")
        )
    }

    @Test
    fun `readiness_self_assessment behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("readiness_self_assessment")
        )
    }

    @Test
    fun `formation_participation behaviour is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.FIRST_CLASS_PARTICIPANT_BEHAVIOURS
                .containsKey("formation_participation")
        )
    }

    // ── ParticipantRuntimeSemanticsBoundary — non-orchestration boundaries ────

    @Test
    fun `NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES has exactly NOT_ORCHESTRATION_BOUNDARY_COUNT entries`() {
        assertEquals(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_BOUNDARY_COUNT,
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES.size
        )
    }

    @Test
    fun `no_task_assignment boundary is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES
                .containsKey("no_task_assignment")
        )
    }

    @Test
    fun `no_unilateral_session_continuation boundary is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES
                .containsKey("no_unilateral_session_continuation")
        )
    }

    @Test
    fun `no_canonical_truth_override boundary is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES
                .containsKey("no_canonical_truth_override")
        )
    }

    @Test
    fun `no_barrier_coordination boundary is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES
                .containsKey("no_barrier_coordination")
        )
    }

    @Test
    fun `no_formation_rebalance_authority boundary is present`() {
        assertTrue(
            ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES
                .containsKey("no_formation_rebalance_authority")
        )
    }

    @Test
    fun `all boundary keys are non-blank`() {
        for ((key, _) in ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES) {
            assertTrue("Non-orchestration boundary key must be non-blank", key.isNotBlank())
        }
    }

    @Test
    fun `all boundary values are non-blank`() {
        for ((key, value) in ParticipantRuntimeSemanticsBoundary.NOT_ORCHESTRATION_AUTHORITY_BOUNDARIES) {
            assertTrue("Non-orchestration boundary value for '$key' must be non-blank", value.isNotBlank())
        }
    }

    // ── ParticipantRuntimeSemanticsBoundary.ExecutionPhase — wire values ──────

    @Test
    fun `PRE_EXECUTION wireValue is pre_execution`() {
        assertEquals(
            "pre_execution",
            ParticipantRuntimeSemanticsBoundary.ExecutionPhase.PRE_EXECUTION.wireValue
        )
    }

    @Test
    fun `IN_PROGRESS wireValue is in_progress`() {
        assertEquals(
            "in_progress",
            ParticipantRuntimeSemanticsBoundary.ExecutionPhase.IN_PROGRESS.wireValue
        )
    }

    @Test
    fun `TERMINAL wireValue is terminal`() {
        assertEquals(
            "terminal",
            ParticipantRuntimeSemanticsBoundary.ExecutionPhase.TERMINAL.wireValue
        )
    }

    @Test
    fun `ANY wireValue is any`() {
        assertEquals(
            "any",
            ParticipantRuntimeSemanticsBoundary.ExecutionPhase.ANY.wireValue
        )
    }

    @Test
    fun `all four ExecutionPhase wire values are distinct`() {
        val values = ParticipantRuntimeSemanticsBoundary.ExecutionPhase.entries.map { it.wireValue }
        assertEquals(
            "All ExecutionPhase wire values must be distinct",
            values.size,
            values.distinct().size
        )
    }

    // ── StabilizationBaseline — PR-60 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three entries with introducedPr == 60`() {
        val pr60Entries = StabilizationBaseline.entries.filter { it.introducedPr == 60 }
        assertEquals(
            "Expected exactly 3 StabilizationBaseline entries for PR-60",
            3,
            pr60Entries.size
        )
    }

    @Test
    fun `android-app-lifecycle-transition is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-app-lifecycle-transition")
        assertNotNull("android-app-lifecycle-transition must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `hybrid-participant-capability is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("hybrid-participant-capability")
        assertNotNull("hybrid-participant-capability must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `android-lifecycle-recovery-contract is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-lifecycle-recovery-contract")
        assertNotNull("android-lifecycle-recovery-contract must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-60 entries have non-blank rationale`() {
        val pr60Entries = StabilizationBaseline.entries.filter { it.introducedPr == 60 }
        for (entry in pr60Entries) {
            assertTrue(
                "PR-60 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-60 entries have a com dot ufo dot galaxy packagePath`() {
        val pr60Entries = StabilizationBaseline.entries.filter { it.introducedPr == 60 }
        for (entry in pr60Entries) {
            assertTrue(
                "PR-60 entry '${entry.surfaceId}' packagePath must reference com.ufo.galaxy",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }

    // ── StabilizationBaseline — PR-61 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three entries with introducedPr == 61`() {
        val pr61Entries = StabilizationBaseline.entries.filter { it.introducedPr == 61 }
        assertEquals(
            "Expected exactly 3 StabilizationBaseline entries for PR-61",
            3,
            pr61Entries.size
        )
    }

    @Test
    fun `participant-runtime-semantics-boundary is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-runtime-semantics-boundary")
        assertNotNull("participant-runtime-semantics-boundary must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `participant-execution-phase is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-execution-phase")
        assertNotNull("participant-execution-phase must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `participant-truth-domain-entry is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-truth-domain-entry")
        assertNotNull("participant-truth-domain-entry must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-61 entries have non-blank rationale`() {
        val pr61Entries = StabilizationBaseline.entries.filter { it.introducedPr == 61 }
        for (entry in pr61Entries) {
            assertTrue(
                "PR-61 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-61 entries have a com dot ufo dot galaxy packagePath`() {
        val pr61Entries = StabilizationBaseline.entries.filter { it.introducedPr == 61 }
        for (entry in pr61Entries) {
            assertTrue(
                "PR-61 entry '${entry.surfaceId}' packagePath must reference com.ufo.galaxy",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }
}
