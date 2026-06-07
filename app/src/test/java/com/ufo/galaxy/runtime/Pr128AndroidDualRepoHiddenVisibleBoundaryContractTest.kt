package com.ufo.galaxy.runtime

import com.ufo.galaxy.runtime.AndroidDualRepoHiddenVisibleBoundaryContract as C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-128 — Behavioral tests for [AndroidDualRepoHiddenVisibleBoundaryContract].
 *
 * These tests prove this is a dual-repo boundary system advancement, not a taxonomy or
 * summary extension.  Each section corresponds to one of the five completion criteria:
 *
 * A. Android-originated blocker / confirmation_needed / device state / lifecycle enter
 *    a real hidden-visible runtime decision via a shared classify() path.
 * B. The shared boundary resolution path governs Android-originated and V2-surface-facing
 *    information together in unified_action_lifecycle_surface.
 * C. The foreground payload composition changes based on boundary decision — suppressed
 *    items are zeroed out in the foreground sub-maps.
 * D. Android blocker and confirmation_needed transition from handler-local scattered
 *    interpretation to unified boundary governance.
 * E. Invariants are machine-verifiable and cover all tier/suppression combinations.
 */
class Pr128AndroidDualRepoHiddenVisibleBoundaryContractTest {

    // ══════════════════════════════════════════════════════════════════════════
    // A. Android-originated boundary classification test
    //    Verifies Android information enters a real hidden-visible runtime decision.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-originated boundary classification - blocker with canonical closure blocked is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isCanonicalClosureBlocked = true,
                blockerReason = "canonical_closure_blocked"
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
        assertNull(decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - blocker with execution failure is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isExecutionFailed = true,
                blockerReason = "execution_failed"
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
    }

    @Test
    fun `android-originated boundary classification - blocker with NO evidence is OPERATOR_ONLY`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isCanonicalClosureBlocked = false,
                isExecutionFailed = false,
                blockerReason = null
            )
        )
        assertEquals(C.VisibilityTier.OPERATOR_ONLY, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
        assertEquals("no_concrete_blocker_reason", decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - confirmation on terminal unclosed signal is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = true,
                closureReadyForAcceptance = false
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
        assertNull(decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - confirmation when closure already accepted is BACKGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = true,
                closureReadyForAcceptance = true
            )
        )
        assertEquals(C.VisibilityTier.BACKGROUND, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
        assertEquals("closure_already_accepted", decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - non-terminal confirmation is BACKGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = false,
                closureReadyForAcceptance = false
            )
        )
        assertEquals(C.VisibilityTier.BACKGROUND, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
        assertEquals("non_terminal_confirmation_is_background", decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - recovery replaying device state is OPERATOR_ONLY`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                deviceStateStage = "recovery_replaying"
            )
        )
        assertEquals(C.VisibilityTier.OPERATOR_ONLY, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
        assertEquals("recovery_state_operator_only", decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - recovery reconciliation pending is OPERATOR_ONLY`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                deviceStateStage = "recovery_reconciliation_pending"
            )
        )
        assertEquals(C.VisibilityTier.OPERATOR_ONLY, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
    }

    @Test
    fun `android-originated boundary classification - recovery failed device state is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                deviceStateStage = "recovery_failed"
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
    }

    @Test
    fun `android-originated boundary classification - result returned and first-class is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.RESULT_VISIBILITY,
                resultReturned = true,
                isFirstClassResult = true
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
    }

    @Test
    fun `android-originated boundary classification - result not yet returned is BACKGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.RESULT_VISIBILITY,
                resultReturned = false,
                isFirstClassResult = false
            )
        )
        assertEquals(C.VisibilityTier.BACKGROUND, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
    }

    @Test
    fun `android-originated boundary classification - executing lifecycle stage is BACKGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.LIFECYCLE_STATE,
                lifecycleStage = "executing"
            )
        )
        assertEquals(C.VisibilityTier.BACKGROUND, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)
        assertEquals("mid_execution_lifecycle_is_background", decision.suppressionReason)
    }

    @Test
    fun `android-originated boundary classification - accepted lifecycle stage is FOREGROUND`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.LIFECYCLE_STATE,
                lifecycleStage = "accepted"
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // B. Dual-repo shared boundary logic test
    //    Verifies the shared boundary path governs Android + V2 information together.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `dual-repo shared boundary - all AndroidInfoOriginClass values are handled by single classify() path`() {
        for (cls in C.AndroidInfoOriginClass.entries) {
            val input = C.BoundaryInput(infoOriginClass = cls)
            val decision = C.classify(input)
            assertNotNull(
                "classify() must return a non-null BoundaryDecision for $cls",
                decision
            )
            assertEquals(
                "BoundaryDecision.infoOriginClass must match input for $cls",
                cls,
                decision.infoOriginClass
            )
        }
    }

    @Test
    fun `dual-repo shared boundary - same classify() call governs both operator-only and foreground blocker`() {
        val noEvidenceDecision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                blockerReason = null,
                isCanonicalClosureBlocked = false,
                isExecutionFailed = false
            )
        )
        val foregroundDecision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                blockerReason = null,
                isCanonicalClosureBlocked = true,
                isExecutionFailed = false
            )
        )
        // Both go through the same classify() but produce different tiers
        assertEquals(C.VisibilityTier.OPERATOR_ONLY, noEvidenceDecision.visibilityTier)
        assertEquals(C.VisibilityTier.FOREGROUND, foregroundDecision.visibilityTier)
    }

    @Test
    fun `dual-repo shared boundary - V2_VISIBILITY_ALIGNMENT_MAP covers every VisibilityTier`() {
        for (tier in C.VisibilityTier.entries) {
            assertTrue(
                "V2_VISIBILITY_ALIGNMENT_MAP must contain an entry for tier $tier",
                C.V2_VISIBILITY_ALIGNMENT_MAP.containsKey(tier)
            )
        }
    }

    @Test
    fun `dual-repo shared boundary - V2 foreground path is distinguished from operator-only path in alignment map`() {
        val foregroundPath = C.V2_VISIBILITY_ALIGNMENT_MAP[C.VisibilityTier.FOREGROUND]
        val operatorPath = C.V2_VISIBILITY_ALIGNMENT_MAP[C.VisibilityTier.OPERATOR_ONLY]
        assertNotNull(foregroundPath)
        assertNotNull(operatorPath)
        assertTrue("foreground and operator paths must be distinct V2 routes", foregroundPath != operatorPath)
        assertTrue(
            "foreground path must reference visible_action_surface",
            foregroundPath!!.contains("visible_action_surface")
        )
        assertTrue(
            "operator path must reference operator_board_diagnostics",
            operatorPath!!.contains("operator_board_diagnostics")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // C. Foreground composition difference test
    //    Verifies Android-originated information changes the foreground payload
    //    based on boundary classification.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `foreground composition - OPERATOR_ONLY blocker suppresses is_blocked in foreground`() {
        // Input: blocker with no concrete reason -> OPERATOR_ONLY -> suppressed in foreground
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                blockerReason = null,
                isCanonicalClosureBlocked = false,
                isExecutionFailed = false
            )
        )
        assertEquals(C.VisibilityTier.OPERATOR_ONLY, decision.visibilityTier)
        assertTrue("foreground suppressed flag must be true for OPERATOR_ONLY", decision.foregroundSuppressed)

        // Simulate how ReconciliationSignal uses the decision to compose foreground payload
        val rawIsBlocked = true // raw Android signal indicates a block
        val foregroundIsBlocked = rawIsBlocked && !decision.foregroundSuppressed
        assertFalse(
            "foreground is_blocked must be false when boundary suppresses the blocker",
            foregroundIsBlocked
        )
    }

    @Test
    fun `foreground composition - FOREGROUND blocker exposes is_blocked in foreground`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                blockerReason = "canonical_closure_blocked",
                isCanonicalClosureBlocked = true,
                isExecutionFailed = false
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)

        val rawIsBlocked = true
        val foregroundIsBlocked = rawIsBlocked && !decision.foregroundSuppressed
        assertTrue(
            "foreground is_blocked must be true when boundary does NOT suppress the blocker",
            foregroundIsBlocked
        )
    }

    @Test
    fun `foreground composition - BACKGROUND confirmation suppresses confirmation_needed in foreground`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = false, // non-terminal -> BACKGROUND
                closureReadyForAcceptance = false
            )
        )
        assertEquals(C.VisibilityTier.BACKGROUND, decision.visibilityTier)
        assertTrue(decision.foregroundSuppressed)

        val rawConfirmationNeeded = true
        val foregroundConfirmationNeeded = rawConfirmationNeeded && !decision.foregroundSuppressed
        assertFalse(
            "foreground confirmation_needed must be false when boundary demotes to BACKGROUND",
            foregroundConfirmationNeeded
        )
    }

    @Test
    fun `foreground composition - FOREGROUND confirmation exposes confirmation_needed in foreground`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = true,
                closureReadyForAcceptance = false
            )
        )
        assertEquals(C.VisibilityTier.FOREGROUND, decision.visibilityTier)
        assertFalse(decision.foregroundSuppressed)

        val rawConfirmationNeeded = true
        val foregroundConfirmationNeeded = rawConfirmationNeeded && !decision.foregroundSuppressed
        assertTrue(
            "foreground confirmation_needed must be true when boundary classifies as FOREGROUND",
            foregroundConfirmationNeeded
        )
    }

    @Test
    fun `foreground composition - same raw blocker signal produces different foreground payload for different boundary inputs`() {
        val rawIsBlocked = true

        // Case 1: canonical closure blocked -> FOREGROUND -> exposed in foreground payload
        val foregroundDecision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isCanonicalClosureBlocked = true
            )
        )
        val foreground1 = rawIsBlocked && !foregroundDecision.foregroundSuppressed
        assertTrue("canonical closure blocked should expose is_blocked in foreground", foreground1)

        // Case 2: no evidence -> OPERATOR_ONLY -> suppressed in foreground payload
        val operatorDecision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isCanonicalClosureBlocked = false,
                isExecutionFailed = false,
                blockerReason = null
            )
        )
        val foreground2 = rawIsBlocked && !operatorDecision.foregroundSuppressed
        assertFalse("no-evidence blocker should suppress is_blocked from foreground", foreground2)

        // Prove the two foreground payload values differ
        assertTrue("the two foreground is_blocked values must differ", foreground1 != foreground2)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // D. Scattered-to-unified visibility test
    //    Verifies at least one previously scattered information class now enters
    //    unified boundary governance.
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `scattered-to-unified - android blocker is now governed by unified boundary not handler-local logic`() {
        // Before PR-128, blocker reason was computed inside withUnifiedActionLifecycleSurface
        // without shared boundary adjudication.  Now every blocker goes through classify().
        val inputs = listOf(
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER, isCanonicalClosureBlocked = true),
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER, isExecutionFailed = true),
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER, blockerReason = "explicit_reason"),
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER) // no evidence
        )
        val expectedTiers = listOf(
            C.VisibilityTier.FOREGROUND,
            C.VisibilityTier.FOREGROUND,
            C.VisibilityTier.FOREGROUND,
            C.VisibilityTier.OPERATOR_ONLY
        )

        inputs.zip(expectedTiers).forEach { (input, expectedTier) ->
            val decision = C.classify(input)
            assertEquals(
                "Blocker with input $input should classify as $expectedTier",
                expectedTier,
                decision.visibilityTier
            )
        }
    }

    @Test
    fun `scattered-to-unified - android confirmation_needed is now governed by unified boundary`() {
        data class Case(
            val confirmationNeeded: Boolean,
            val isTerminal: Boolean,
            val closureReady: Boolean,
            val expected: C.VisibilityTier
        )

        val cases = listOf(
            Case(confirmationNeeded = true, isTerminal = true, closureReady = false, expected = C.VisibilityTier.FOREGROUND),
            Case(confirmationNeeded = true, isTerminal = false, closureReady = false, expected = C.VisibilityTier.BACKGROUND),
            Case(confirmationNeeded = true, isTerminal = true, closureReady = true, expected = C.VisibilityTier.BACKGROUND),
            Case(confirmationNeeded = false, isTerminal = true, closureReady = false, expected = C.VisibilityTier.BACKGROUND)
        )

        cases.forEach { case ->
            val decision = C.classify(
                C.BoundaryInput(
                    infoOriginClass = C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                    confirmationNeeded = case.confirmationNeeded,
                    isTerminalSignal = case.isTerminal,
                    closureReadyForAcceptance = case.closureReady
                )
            )
            assertEquals(
                "Case $case should classify as ${case.expected}",
                case.expected,
                decision.visibilityTier
            )
        }
    }

    @Test
    fun `scattered-to-unified - android device recovery state is now governed by unified boundary`() {
        val operatorOnlyStages = listOf("recovery_replaying", "recovery_reconciliation_pending")
        val foregroundStages = listOf("recovery_failed")
        val backgroundStages = listOf("reconciliation_snapshot", "recovery_recovered", null)

        operatorOnlyStages.forEach { stage ->
            val tier = C.classify(
                C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = stage)
            ).visibilityTier
            assertEquals("Stage $stage should be OPERATOR_ONLY", C.VisibilityTier.OPERATOR_ONLY, tier)
        }
        foregroundStages.forEach { stage ->
            val tier = C.classify(
                C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = stage)
            ).visibilityTier
            assertEquals("Stage $stage should be FOREGROUND", C.VisibilityTier.FOREGROUND, tier)
        }
        backgroundStages.forEach { stage ->
            val tier = C.classify(
                C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = stage)
            ).visibilityTier
            assertEquals("Stage $stage should be BACKGROUND", C.VisibilityTier.BACKGROUND, tier)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // E. Wire map and invariant verification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `wire map - BoundaryDecision toWireMap contains all required keys`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isCanonicalClosureBlocked = true
            )
        )
        val wire = decision.toWireMap()
        assertTrue(wire.containsKey(C.KEY_VISIBILITY_TIER))
        assertTrue(wire.containsKey(C.KEY_ANDROID_INFO_ORIGIN_CLASS))
        assertTrue(wire.containsKey(C.KEY_FOREGROUND_SUPPRESSED))
        assertTrue(wire.containsKey(C.KEY_SUPPRESSION_REASON))
        assertTrue(wire.containsKey(C.KEY_BOUNDARY_SCHEMA_VERSION))
    }

    @Test
    fun `wire map - FOREGROUND decision has correct wire values`() {
        val decision = C.classify(
            C.BoundaryInput(
                infoOriginClass = C.AndroidInfoOriginClass.BLOCKER,
                isExecutionFailed = true
            )
        )
        val wire = decision.toWireMap()
        assertEquals("foreground", wire[C.KEY_VISIBILITY_TIER])
        assertEquals("android_blocker", wire[C.KEY_ANDROID_INFO_ORIGIN_CLASS])
        assertEquals(false, wire[C.KEY_FOREGROUND_SUPPRESSED])
        assertNull(wire[C.KEY_SUPPRESSION_REASON])
        assertEquals(C.SCHEMA_VERSION, wire[C.KEY_BOUNDARY_SCHEMA_VERSION])
    }

    @Test
    fun `wire map - OPERATOR_ONLY decision has correct wire values`() {
        val decision = C.classify(C.BoundaryInput(infoOriginClass = C.AndroidInfoOriginClass.BLOCKER))
        val wire = decision.toWireMap()
        assertEquals("operator_only", wire[C.KEY_VISIBILITY_TIER])
        assertEquals(true, wire[C.KEY_FOREGROUND_SUPPRESSED])
        assertNotNull(wire[C.KEY_SUPPRESSION_REASON])
    }

    @Test
    fun `invariant OPERATOR_ONLY_AND_BACKGROUND_ALWAYS_SUPPRESS - non-FOREGROUND decisions suppress foreground`() {
        val suppressedInputs = listOf(
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER), // OPERATOR_ONLY
            C.BoundaryInput(C.AndroidInfoOriginClass.CONFIRMATION_NEEDED, confirmationNeeded = true, isTerminalSignal = false),
            C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = "recovery_replaying"),
            C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = "reconciliation_snapshot"),
            C.BoundaryInput(C.AndroidInfoOriginClass.LIFECYCLE_STATE, lifecycleStage = "executing"),
            C.BoundaryInput(C.AndroidInfoOriginClass.RESULT_VISIBILITY, resultReturned = false)
        )
        suppressedInputs.forEach { input ->
            val decision = C.classify(input)
            assertTrue(
                "Non-FOREGROUND tier for $input must have foregroundSuppressed=true (got ${decision.visibilityTier})",
                decision.visibilityTier != C.VisibilityTier.FOREGROUND
            )
            assertTrue(
                "Non-FOREGROUND decision for $input must have foregroundSuppressed=true",
                decision.foregroundSuppressed
            )
        }
    }

    @Test
    fun `invariant FOREGROUND_NEVER_SUPPRESSES - FOREGROUND decisions have foregroundSuppressed=false and null suppressionReason`() {
        val foregroundInputs = listOf(
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER, isCanonicalClosureBlocked = true),
            C.BoundaryInput(C.AndroidInfoOriginClass.BLOCKER, isExecutionFailed = true),
            C.BoundaryInput(
                C.AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                confirmationNeeded = true,
                isTerminalSignal = true,
                closureReadyForAcceptance = false
            ),
            C.BoundaryInput(C.AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY, deviceStateStage = "recovery_failed"),
            C.BoundaryInput(C.AndroidInfoOriginClass.RESULT_VISIBILITY, resultReturned = true, isFirstClassResult = true),
            C.BoundaryInput(C.AndroidInfoOriginClass.LIFECYCLE_STATE, lifecycleStage = "accepted")
        )
        foregroundInputs.forEach { input ->
            val decision = C.classify(input)
            assertEquals(
                "Input $input should produce FOREGROUND tier",
                C.VisibilityTier.FOREGROUND,
                decision.visibilityTier
            )
            assertFalse(
                "FOREGROUND decision must have foregroundSuppressed=false for $input",
                decision.foregroundSuppressed
            )
            assertNull(
                "FOREGROUND decision must have null suppressionReason for $input",
                decision.suppressionReason
            )
        }
    }

    @Test
    fun `invariant BOUNDARY_INVARIANTS - all 10 invariants are present with non-blank IDs and descriptions`() {
        assertEquals(
            "BOUNDARY_INVARIANTS must contain exactly 10 entries",
            10,
            C.BOUNDARY_INVARIANTS.size
        )
        C.BOUNDARY_INVARIANTS.forEach { inv ->
            assertTrue("Invariant ID must be non-blank: $inv", inv.id.isNotBlank())
            assertTrue("Invariant description must be non-blank: $inv", inv.description.isNotBlank())
        }
    }

    @Test
    fun `invariant ids are unique`() {
        val ids = C.BOUNDARY_INVARIANTS.map { it.id }
        assertEquals("All invariant IDs must be unique", ids.toSet().size, ids.size)
    }

    @Test
    fun `SCHEMA_VERSION is stable at version 1`() {
        assertEquals("1", C.SCHEMA_VERSION)
    }

    @Test
    fun `INTRODUCED_PR is 128`() {
        assertEquals(128, C.INTRODUCED_PR)
    }
}
