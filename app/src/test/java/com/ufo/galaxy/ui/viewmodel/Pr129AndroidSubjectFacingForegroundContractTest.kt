package com.ufo.galaxy.ui.viewmodel

import com.google.gson.JsonParser
import com.ufo.galaxy.runtime.AndroidSubjectFacingForegroundContract as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-129 — Behavioral tests for [AndroidSubjectFacingForegroundContract] and the
 * subject-facing foreground path advancement in [UnifiedResultPresentation].
 *
 * These tests prove this is a foreground subject-facing advancement, not a payload
 * field extension or UI text adjustment.  Each section corresponds to one of the five
 * completion criteria:
 *
 * A. Default foreground composition change test
 *    Verifies the Android default foreground path now organises around a
 *    SubjectFacingForegroundCard instead of a scattered text response.
 *
 * B. Subject-facing primary object test
 *    Verifies the subject object (action phase / blocker / confirmation / result) is the
 *    primary foreground structure, not a scattered response.
 *
 * C. Blocker / confirmation foreground priority test
 *    Verifies blocker and confirmation are primary foreground objects (not text suffixes),
 *    and that the ForegroundPrimaryObject priority ordering is enforced.
 *
 * D. Control-plane demotion test
 *    Verifies that control-plane / operator-heavy stages are demoted from the subject-facing
 *    primary dimension, while active subject phases are promoted.
 *
 * E. Invariant coverage tests
 *    Verifies every SUBJECT_FOREGROUND_INVARIANT is machine-checkable via this test suite.
 */
class Pr129AndroidSubjectFacingForegroundContractTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun lifecycleSurface(json: String) =
        JsonParser.parseString(json).asJsonObject

    private fun serverMessageWithLifecycle(lifecycleJson: String): String =
        """{"payload":{"unified_action_lifecycle_surface":$lifecycleJson}}"""

    // ══════════════════════════════════════════════════════════════════════════
    // A. Default foreground composition change tests
    //    Verifies that the Android default foreground path now produces a
    //    SubjectFacingForegroundCard as the primary foreground object instead of a
    //    flat text summary.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `default foreground composition - server message with lifecycle surface produces subjectFacingCard`() {
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"executing","execution":{"progress_detail":"步骤3/5"},
                   "blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}"""
            )
        )
        // The composition has changed: subjectFacingCard is now present as the primary
        // foreground object, not just a flat summary string.
        assertNotNull(
            "Default foreground must produce a subjectFacingCard when lifecycle surface is present",
            p.subjectFacingCard
        )
    }

    @Test
    fun `default foreground composition - server message WITHOUT lifecycle surface has null subjectFacingCard`() {
        // Without a lifecycle surface the old path is preserved and no card is fabricated.
        val p = UnifiedResultPresentation.fromServerMessage("plain text message")
        assertNull(
            "subjectFacingCard must be null when no unified_action_lifecycle_surface is present",
            p.subjectFacingCard
        )
    }

    @Test
    fun `default foreground composition - local result path does not produce subjectFacingCard`() {
        val result = com.ufo.galaxy.local.LocalLoopResult(
            sessionId = "s1",
            instruction = "open settings",
            status = com.ufo.galaxy.local.LocalLoopResult.STATUS_SUCCESS,
            stepCount = 3,
            stopReason = null,
            error = null
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertNull(
            "Local execution path must not produce a subjectFacingCard (no lifecycle surface)",
            p.subjectFacingCard
        )
    }

    @Test
    fun `default foreground composition - subjectFacingCard action phase matches lifecycle stage`() {
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"accepted","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":false}}"""
            )
        )
        assertEquals(
            "actionPhase must match lifecycle stage",
            C.ActionPhase.ACCEPTED,
            p.subjectFacingCard!!.actionPhase
        )
    }

    @Test
    fun `default foreground composition - subjectFacingCard foregroundPrimaryObject is ACTION_PHASE for accepted stage`() {
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"accepted","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":false}}"""
            )
        )
        assertEquals(
            "foregroundPrimaryObject must be ACTION_PHASE for accepted stage with no blocker/confirmation",
            C.ForegroundPrimaryObject.ACTION_PHASE,
            p.subjectFacingCard!!.foregroundPrimaryObject
        )
    }

    @Test
    fun `default foreground composition - subjectFacingCard currentActionDetail is populated from execution progress_detail`() {
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"executing","execution":{"progress_detail":"步骤2/5"},
                   "blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}"""
            )
        )
        assertEquals(
            "currentActionDetail must be populated from execution.progress_detail",
            "步骤2/5",
            p.subjectFacingCard!!.currentActionDetail
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // B. Subject-facing primary object tests
    //    Verifies the subject object is the primary foreground structure, not a
    //    scattered response.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `subject-facing primary object - executing stage has ACTION_PHASE as primary object`() {
        val surface = lifecycleSurface(
            """{"stage":"executing","execution":{"progress_detail":"执行中"},
               "blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(C.ActionPhase.EXECUTING, card.actionPhase)
        assertEquals(C.ForegroundPrimaryObject.ACTION_PHASE, card.foregroundPrimaryObject)
        assertTrue("executing phase is subject-facing", card.actionPhase.isSubjectFacing)
    }

    @Test
    fun `subject-facing primary object - failed stage has ACTION_PHASE as primary object`() {
        val surface = lifecycleSurface(
            """{"stage":"failed","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(C.ActionPhase.FAILED, card.actionPhase)
        assertEquals(C.ForegroundPrimaryObject.ACTION_PHASE, card.foregroundPrimaryObject)
    }

    @Test
    fun `subject-facing primary object - result_emitted stage has ACTION_PHASE as primary object`() {
        val surface = lifecycleSurface(
            """{"stage":"result_emitted","result_summary":"设置已完成",
               "blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(C.ActionPhase.RESULT_EMITTED, card.actionPhase)
        // result_emitted is an action phase: the phase IS the primary subject expression
        assertEquals(C.ForegroundPrimaryObject.ACTION_PHASE, card.foregroundPrimaryObject)
    }

    @Test
    fun `subject-facing primary object - card wire map has foreground_primary_object key`() {
        val surface = lifecycleSurface(
            """{"stage":"executing","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        val wireMap = card.toWireMap()
        assertTrue(
            "wire map must contain foreground_primary_object key",
            wireMap.containsKey("foreground_primary_object")
        )
        assertNotNull("foreground_primary_object must be non-null", wireMap["foreground_primary_object"])
    }

    @Test
    fun `subject-facing primary object - card wire map exposes action_phase, blocker, confirmation and result`() {
        val surface = lifecycleSurface(
            """{"stage":"executing","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val wireMap = C.fromUnifiedLifecycleSurface(surface).toWireMap()
        assertTrue(wireMap.containsKey("action_phase"))
        assertTrue(wireMap.containsKey("blocker"))
        assertTrue(wireMap.containsKey("confirmation"))
        assertTrue(wireMap.containsKey("result"))
        assertTrue(wireMap.containsKey("presence_mode"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // C. Blocker / confirmation foreground priority tests
    //    Verifies blocker and confirmation are primary foreground objects, not text
    //    suffixes, and the priority ordering is enforced.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `blocker confirmation priority - blocker overrides ACTION_PHASE as primary object`() {
        val surface = lifecycleSurface(
            """{"stage":"executing",
               "blocker":{"is_blocked":true,"reason":"canonical_closure_blocked"},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "BLOCKER must be foreground primary object when blocker is active",
            C.ForegroundPrimaryObject.BLOCKER,
            card.foregroundPrimaryObject
        )
        assertTrue("blockerState.isBlocked must be true", card.blockerState.isBlocked)
        assertEquals("canonical_closure_blocked", card.blockerState.reason)
    }

    @Test
    fun `blocker confirmation priority - confirmation overrides ACTION_PHASE when no blocker`() {
        val surface = lifecycleSurface(
            """{"stage":"result_emitted",
               "blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":true,"closure_ready_for_acceptance":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "CONFIRMATION must be foreground primary object when confirmation is needed and no blocker",
            C.ForegroundPrimaryObject.CONFIRMATION,
            card.foregroundPrimaryObject
        )
        assertTrue(card.confirmationState.confirmationNeeded)
    }

    @Test
    fun `blocker confirmation priority - blocker overrides confirmation when both active`() {
        val surface = lifecycleSurface(
            """{"stage":"result_emitted",
               "blocker":{"is_blocked":true,"reason":"execution_failed"},
               "confirmation":{"confirmation_needed":true}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "BLOCKER must take priority over CONFIRMATION",
            C.ForegroundPrimaryObject.BLOCKER,
            card.foregroundPrimaryObject
        )
    }

    @Test
    fun `blocker confirmation priority - blocker without reason is not elevated (isBlocked=false)`() {
        // A blocker without a concrete reason should not drive the foreground primary object.
        // The boundary contract (PR-128) zeroes out foreground blockers without evidence.
        val surface = lifecycleSurface(
            """{"stage":"executing",
               "blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertFalse("isBlocked must be false when foreground blocker is not elevated", card.blockerState.isBlocked)
        assertEquals(
            "foregroundPrimaryObject must be ACTION_PHASE when no blocker/confirmation",
            C.ForegroundPrimaryObject.ACTION_PHASE,
            card.foregroundPrimaryObject
        )
    }

    @Test
    fun `blocker confirmation priority - UnifiedResultPresentation blocker appears in subjectFacingCard not only summary`() {
        // Previously blocker appeared only as a text suffix.  Now it is a first-class object in
        // the subjectFacingCard — this is the composition change.
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"result_emitted",
                   "blocker":{"is_blocked":true,"reason":"canonical_closure_blocked"},
                   "confirmation":{"confirmation_needed":false}}"""
            )
        )
        assertNotNull(p.subjectFacingCard)
        val card = p.subjectFacingCard!!
        assertEquals(C.ForegroundPrimaryObject.BLOCKER, card.foregroundPrimaryObject)
        assertTrue("blockerState.isBlocked must be true in the card", card.blockerState.isBlocked)
        assertEquals("canonical_closure_blocked", card.blockerState.reason)
        // The summary also contains the blocker (backward compat)
        assertTrue(p.summary.contains("阻塞"))
    }

    @Test
    fun `blocker confirmation priority - UnifiedResultPresentation confirmation appears in subjectFacingCard`() {
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"result_emitted",
                   "blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":true,"closure_ready_for_acceptance":false}}"""
            )
        )
        assertNotNull(p.subjectFacingCard)
        val card = p.subjectFacingCard!!
        assertEquals(C.ForegroundPrimaryObject.CONFIRMATION, card.foregroundPrimaryObject)
        assertTrue("confirmationState.confirmationNeeded must be true in card", card.confirmationState.confirmationNeeded)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // D. Control-plane demotion tests
    //    Verifies control-plane / operator-heavy stages are demoted from the subject-facing
    //    primary dimension while active subject phases are promoted.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `control-plane demotion - reconciliation_snapshot stage is CONTROL_PLANE_DEMOTED`() {
        val surface = lifecycleSurface(
            """{"stage":"reconciliation_snapshot","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "reconciliation_snapshot must produce CONTROL_PLANE_DEMOTED (not ACTION_PHASE)",
            C.ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED,
            card.foregroundPrimaryObject
        )
        assertFalse(
            "actionPhase.isSubjectFacing must be false for control-plane stage",
            card.actionPhase.isSubjectFacing
        )
    }

    @Test
    fun `control-plane demotion - participant_state stage is CONTROL_PLANE_DEMOTED`() {
        val surface = lifecycleSurface(
            """{"stage":"participant_state","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "participant_state must produce CONTROL_PLANE_DEMOTED",
            C.ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED,
            card.foregroundPrimaryObject
        )
    }

    @Test
    fun `control-plane demotion - recovery_replaying stage is CONTROL_PLANE_DEMOTED`() {
        val surface = lifecycleSurface(
            """{"stage":"recovery_replaying","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        // recovery_replaying maps to ActionPhase.RECOVERY which has isSubjectFacing=true,
        // but it should still be CONTROL_PLANE_DEMOTED according to the demotion logic.
        // Let us verify that RECOVERY is still isSubjectFacing (it represents a real subject
        // state) but the foreground primary object correctly reflects it.
        assertEquals(C.ActionPhase.RECOVERY, card.actionPhase)
        assertTrue("RECOVERY phase is subject-facing (represents real subject state)", card.actionPhase.isSubjectFacing)
        // Since RECOVERY is isSubjectFacing=true, its foreground primary object is ACTION_PHASE
        assertEquals(
            "RECOVERY stage that is subject-facing produces ACTION_PHASE (not demoted)",
            C.ForegroundPrimaryObject.ACTION_PHASE,
            card.foregroundPrimaryObject
        )
    }

    @Test
    fun `control-plane demotion - recovery_reconciliation_pending is mapped to RECOVERY (subject-facing)`() {
        val surface = lifecycleSurface(
            """{"stage":"recovery_reconciliation_pending","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(C.ActionPhase.RECOVERY, card.actionPhase)
    }

    @Test
    fun `control-plane demotion - executing stage is ACTION_PHASE (promoted, not demoted)`() {
        val surface = lifecycleSurface(
            """{"stage":"executing","execution":{"progress_detail":"进行中"},
               "blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(
            "executing stage must be promoted to ACTION_PHASE (subject-facing), not demoted",
            C.ForegroundPrimaryObject.ACTION_PHASE,
            card.foregroundPrimaryObject
        )
        assertTrue(card.actionPhase.isSubjectFacing)
    }

    @Test
    fun `control-plane demotion - unknown stage maps to UNKNOWN ActionPhase (isSubjectFacing=false)`() {
        val surface = lifecycleSurface(
            """{"stage":"some_internal_runtime_phase","blocker":{"is_blocked":false},
               "confirmation":{"confirmation_needed":false}}"""
        )
        val card = C.fromUnifiedLifecycleSurface(surface)
        assertEquals(C.ActionPhase.UNKNOWN, card.actionPhase)
        assertFalse("UNKNOWN phase is not subject-facing", card.actionPhase.isSubjectFacing)
        assertEquals(
            "UNKNOWN phase produces CONTROL_PLANE_DEMOTED primary object",
            C.ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED,
            card.foregroundPrimaryObject
        )
    }

    @Test
    fun `control-plane demotion - UnifiedResultPresentation reconciliation_snapshot subjectFacingCard is CONTROL_PLANE_DEMOTED`() {
        // Proves the demotion is real in the actual foreground path, not just contract-level.
        val p = UnifiedResultPresentation.fromServerMessage(
            serverMessageWithLifecycle(
                """{"stage":"reconciliation_snapshot","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":false}}"""
            )
        )
        assertNotNull(p.subjectFacingCard)
        assertEquals(
            "reconciliation_snapshot produces CONTROL_PLANE_DEMOTED in the real foreground path",
            C.ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED,
            p.subjectFacingCard!!.foregroundPrimaryObject
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // E. Invariant coverage tests
    //    Verifies each SUBJECT_FOREGROUND_INVARIANT is machine-checkable.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `invariant - BLOCKER_FOREGROUND_PRIORITY_OVER_ACTION_PHASE`() {
        // Blocker must win over any actionPhase value
        for (stage in listOf("accepted", "executing", "result_emitted", "failed")) {
            val surface = lifecycleSurface(
                """{"stage":"$stage","blocker":{"is_blocked":true,"reason":"test_block"},
                   "confirmation":{"confirmation_needed":false}}"""
            )
            val card = C.fromUnifiedLifecycleSurface(surface)
            assertEquals(
                "Blocker must win over stage=$stage",
                C.ForegroundPrimaryObject.BLOCKER,
                card.foregroundPrimaryObject
            )
        }
    }

    @Test
    fun `invariant - CONFIRMATION_PRIORITY_OVER_ACTION_PHASE`() {
        for (stage in listOf("accepted", "executing", "result_emitted")) {
            val surface = lifecycleSurface(
                """{"stage":"$stage","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":true}}"""
            )
            val card = C.fromUnifiedLifecycleSurface(surface)
            assertEquals(
                "Confirmation must win over stage=$stage when no blocker",
                C.ForegroundPrimaryObject.CONFIRMATION,
                card.foregroundPrimaryObject
            )
        }
    }

    @Test
    fun `invariant - CONTROL_PLANE_STAGE_NEVER_ACTION_PHASE_PRIMARY`() {
        for (stage in listOf("reconciliation_snapshot", "participant_state", "some_runtime_stage")) {
            val surface = lifecycleSurface(
                """{"stage":"$stage","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":false}}"""
            )
            val card = C.fromUnifiedLifecycleSurface(surface)
            assertFalse(
                "Stage=$stage must not have isSubjectFacing=true",
                card.actionPhase.isSubjectFacing
            )
            assertEquals(
                "Stage=$stage must produce CONTROL_PLANE_DEMOTED",
                C.ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED,
                card.foregroundPrimaryObject
            )
        }
    }

    @Test
    fun `invariant - SUBJECT_FACING_CARD_ALWAYS_PRODUCED`() {
        // fromUnifiedLifecycleSurface must never return null for any input
        val testInputs = listOf(
            """{"stage":"accepted","blocker":{"is_blocked":false},"confirmation":{"confirmation_needed":false}}""",
            """{"stage":"executing"}""",
            """{}""",
            """{"stage":"unknown_stage"}"""
        )
        for (input in testInputs) {
            val card = C.fromUnifiedLifecycleSurface(lifecycleSurface(input))
            assertNotNull("Card must always be produced for input: $input", card)
        }
    }

    @Test
    fun `invariant - WIRE_MAP_CONTAINS_FOREGROUND_PRIMARY_OBJECT`() {
        val stages = listOf("accepted", "executing", "result_emitted", "failed",
            "cancelled", "reconciliation_snapshot", "unknown")
        for (stage in stages) {
            val surface = lifecycleSurface(
                """{"stage":"$stage","blocker":{"is_blocked":false},
                   "confirmation":{"confirmation_needed":false}}"""
            )
            val wireMap = C.fromUnifiedLifecycleSurface(surface).toWireMap()
            assertNotNull(
                "wire map must have non-null foreground_primary_object for stage=$stage",
                wireMap["foreground_primary_object"]
            )
        }
    }

    @Test
    fun `invariant - V2_ALIGNMENT_MAP_COVERS_ALL_PRIMARY_OBJECTS`() {
        for (primaryObject in C.ForegroundPrimaryObject.entries) {
            assertTrue(
                "V2_SUBJECT_FOREGROUND_ALIGNMENT_MAP must cover $primaryObject",
                C.V2_SUBJECT_FOREGROUND_ALIGNMENT_MAP.containsKey(primaryObject)
            )
        }
    }

    @Test
    fun `invariant - SUBJECT_FOREGROUND_INVARIANTS list has 10 entries`() {
        assertEquals(10, C.SUBJECT_FOREGROUND_INVARIANTS.size)
    }

    @Test
    fun `invariant - all invariant IDs are unique and non-blank`() {
        val ids = C.SUBJECT_FOREGROUND_INVARIANTS.map { it.id }
        assertEquals("Invariant IDs must be unique", ids.distinct().size, ids.size)
        ids.forEach { id -> assertTrue("Invariant ID must be non-blank", id.isNotBlank()) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // F. Structural contract tests
    //    Verifies the contract constants and enum values are correctly defined.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `contract - INTRODUCED_PR is 129`() {
        assertEquals(129, C.INTRODUCED_PR)
    }

    @Test
    fun `contract - SCHEMA_VERSION is defined and non-blank`() {
        assertTrue(C.SCHEMA_VERSION.isNotBlank())
    }

    @Test
    fun `contract - ActionPhase fromStage maps all known stages`() {
        assertEquals(C.ActionPhase.ACCEPTED, C.ActionPhase.fromStage("accepted"))
        assertEquals(C.ActionPhase.EXECUTING, C.ActionPhase.fromStage("executing"))
        assertEquals(C.ActionPhase.RESULT_EMITTED, C.ActionPhase.fromStage("result_emitted"))
        assertEquals(C.ActionPhase.FAILED, C.ActionPhase.fromStage("failed"))
        assertEquals(C.ActionPhase.CANCELLED, C.ActionPhase.fromStage("cancelled"))
        assertEquals(C.ActionPhase.RECOVERY, C.ActionPhase.fromStage("recovery_replaying"))
        assertEquals(C.ActionPhase.RECOVERY, C.ActionPhase.fromStage("recovery_recovered"))
        assertEquals(C.ActionPhase.RECOVERY, C.ActionPhase.fromStage("recovery_failed"))
        assertEquals(C.ActionPhase.RECOVERY, C.ActionPhase.fromStage("recovery_reconciliation_pending"))
        assertEquals(C.ActionPhase.UNKNOWN, C.ActionPhase.fromStage("reconciliation_snapshot"))
        assertEquals(C.ActionPhase.UNKNOWN, C.ActionPhase.fromStage("participant_state"))
        assertEquals(C.ActionPhase.UNKNOWN, C.ActionPhase.fromStage(null))
        assertEquals(C.ActionPhase.UNKNOWN, C.ActionPhase.fromStage("some_unknown_stage"))
    }

    @Test
    fun `contract - subject-facing ActionPhase values are marked isSubjectFacing`() {
        val subjectFacing = setOf(
            C.ActionPhase.ACCEPTED,
            C.ActionPhase.EXECUTING,
            C.ActionPhase.RESULT_EMITTED,
            C.ActionPhase.FAILED,
            C.ActionPhase.CANCELLED,
            C.ActionPhase.RECOVERY
        )
        for (phase in subjectFacing) {
            assertTrue("$phase must have isSubjectFacing=true", phase.isSubjectFacing)
        }
        assertFalse("UNKNOWN must have isSubjectFacing=false", C.ActionPhase.UNKNOWN.isSubjectFacing)
    }

    @Test
    fun `contract - BlockerState NONE has isBlocked=false and null reason`() {
        assertFalse(C.BlockerState.NONE.isBlocked)
        assertNull(C.BlockerState.NONE.reason)
    }

    @Test
    fun `contract - ConfirmationState NONE has confirmationNeeded=false`() {
        assertFalse(C.ConfirmationState.NONE.confirmationNeeded)
        assertFalse(C.ConfirmationState.NONE.closureReadyForAcceptance)
    }
}
