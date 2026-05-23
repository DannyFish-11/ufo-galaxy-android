package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-122 — Stage 5 Android completion truth hardening tests.
 *
 * Validates the Stage 5 implementation changes:
 *
 * 1. [AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL]
 *    exists and is a terminal class.
 * 2. [AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic] maps
 *    PARTIAL_COMPLETION and FALLBACK to AUTHORITATIVE_DEGRADED_TERMINAL (not AUTHORITATIVE_TERMINAL).
 * 3. [AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility] suppresses
 *    closureReadyForAcceptance for degraded outcome kinds.
 * 4. [AndroidCompletionClosureUplinkContract] requires real result/completion signal for
 *    RUNTIME_COMPLETION_EVIDENCE; lifecycle terminal phase alone is NOT_RUNTIME_COMPLETION.
 * 5. [AndroidCompletionTruthHardeningContract.COMPLETION_TRUTH_INVARIANTS] all pass.
 * 6. [AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade] grades outcomes
 *    correctly.
 * 7. [StabilizationBaseline] registration.
 */
class Pr122AndroidCompletionTruthHardeningTest {

    // ── AUTHORITATIVE_DEGRADED_TERMINAL enum presence ────────────────────────

    @Test
    fun `AUTHORITATIVE_DEGRADED_TERMINAL exists in ResultUplinkSemanticClass`() {
        val found = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.entries
            .any { it.wireValue == "authoritative_degraded_terminal" }
        assertTrue(found)
    }

    @Test
    fun `AUTHORITATIVE_DEGRADED_TERMINAL isTerminal is true`() {
        assertTrue(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass
                .AUTHORITATIVE_DEGRADED_TERMINAL.isTerminal
        )
    }

    @Test
    fun `AUTHORITATIVE_DEGRADED_TERMINAL wireValue is authoritative_degraded_terminal`() {
        assertEquals(
            "authoritative_degraded_terminal",
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass
                .AUTHORITATIVE_DEGRADED_TERMINAL.wireValue
        )
    }

    @Test
    fun `AUTHORITATIVE_DEGRADED_TERMINAL is in ALL_WIRE_VALUES`() {
        assertTrue(
            "authoritative_degraded_terminal" in
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.ALL_WIRE_VALUES
        )
    }

    // ── classifyReportedResultSemantic — reclassified outcomes ───────────────

    @Test
    fun `partial completion does not map to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        assertFalse(
            result == AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL
        )
    }

    @Test
    fun `partial completion maps to AUTHORITATIVE_DEGRADED_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL,
            result
        )
    }

    @Test
    fun `fallback does not map to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
        )
        assertFalse(
            result == AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL
        )
    }

    @Test
    fun `fallback maps to AUTHORITATIVE_DEGRADED_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL,
            result
        )
    }

    @Test
    fun `clean completion still maps to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            result
        )
    }

    @Test
    fun `failure still maps to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FAILURE
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            result
        )
    }

    @Test
    fun `timeout still maps to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.TIMEOUT
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            result
        )
    }

    @Test
    fun `abort still maps to AUTHORITATIVE_TERMINAL`() {
        val result = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.ABORT
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            result
        )
    }

    // ── deriveExecutionCompletionVisibility — degraded outcome suppresses closure ──

    @Test
    fun `partial completion outcome suppresses closureReadyForAcceptance`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true,
            terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertFalse("partial completion must not set closureReadyForAcceptance", visibility.closureReadyForAcceptance)
    }

    @Test
    fun `fallback outcome suppresses closureReadyForAcceptance`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true,
            terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertFalse("fallback must not set closureReadyForAcceptance", visibility.closureReadyForAcceptance)
    }

    @Test
    fun `recovery outcome suppresses closureReadyForAcceptance`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true,
            terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertFalse("recovery must not set closureReadyForAcceptance", visibility.closureReadyForAcceptance)
    }

    @Test
    fun `clean completion outcome keeps closureReadyForAcceptance true`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true,
            terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertTrue(visibility.closureReadyForAcceptance)
    }

    @Test
    fun `null terminalOutcomeKind retains existing visibility semantics`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertTrue(visibility.closureReadyForAcceptance)
    }

    // ── AndroidCompletionClosureUplinkContract — lifecycle terminal phase alone ──

    @Test
    fun `lifecycle terminal phase alone without result is NOT_RUNTIME_COMPLETION`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForReconciliationSignal(
            isTerminalSignal = true,
            resultReturned = false,
            completionSignaled = false,
            closureReadyForAcceptance = false
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .NOT_RUNTIME_COMPLETION,
            snapshot.authorityRuntimeCompletionSignalClass
        )
    }

    @Test
    fun `result returned alone grants RUNTIME_COMPLETION_EVIDENCE`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForReconciliationSignal(
            isTerminalSignal = false,
            resultReturned = true,
            completionSignaled = false,
            closureReadyForAcceptance = false
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .RUNTIME_COMPLETION_EVIDENCE,
            snapshot.authorityRuntimeCompletionSignalClass
        )
    }

    @Test
    fun `completion signaled alone grants RUNTIME_COMPLETION_EVIDENCE`() {
        val snapshot = AndroidCompletionClosureUplinkContract.deriveForReconciliationSignal(
            isTerminalSignal = false,
            resultReturned = false,
            completionSignaled = true,
            closureReadyForAcceptance = false
        )
        assertEquals(
            AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass
                .RUNTIME_COMPLETION_EVIDENCE,
            snapshot.authorityRuntimeCompletionSignalClass
        )
    }

    // ── COMPLETION_TRUTH_INVARIANTS all pass ─────────────────────────────────

    @Test
    fun `all COMPLETION_TRUTH_INVARIANTS are true`() {
        val failures = AndroidCompletionTruthHardeningContract.COMPLETION_TRUTH_INVARIANTS
            .filterValues { !it }
        assertTrue(
            "COMPLETION_TRUTH_INVARIANTS failures: ${failures.keys}",
            failures.isEmpty()
        )
    }

    // ── CompletionTruthGrade classification ───────────────────────────────────

    @Test
    fun `clean completion grades as VERIFIED_COMPLETE`() {
        val grade = AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
        )
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.VERIFIED_COMPLETE, grade)
        assertTrue(grade.isFullAuthoritativeCompletion)
    }

    @Test
    fun `partial completion grades as DEGRADED_COMPLETE not VERIFIED_COMPLETE`() {
        val grade = AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
        )
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.DEGRADED_COMPLETE, grade)
        assertFalse(grade.isFullAuthoritativeCompletion)
    }

    @Test
    fun `fallback grades as DEGRADED_COMPLETE`() {
        val grade = AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
        )
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.DEGRADED_COMPLETE, grade)
        assertFalse(grade.isFullAuthoritativeCompletion)
    }

    @Test
    fun `interruption grades as TERMINAL_INCOMPLETE`() {
        val grade = AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION
        )
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.TERMINAL_INCOMPLETE, grade)
        assertFalse(grade.isFullAuthoritativeCompletion)
    }

    @Test
    fun `non terminal grades as NON_TERMINAL`() {
        val grade = AndroidCompletionTruthHardeningContract.classifyCompletionTruthGrade(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL,
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL
        )
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.NON_TERMINAL, grade)
        assertFalse(grade.isFullAuthoritativeCompletion)
    }

    @Test
    fun `only VERIFIED_COMPLETE has isFullAuthoritativeCompletion true`() {
        val fullAuthGrades = AndroidCompletionTruthHardeningContract.CompletionTruthGrade.entries
            .filter { it.isFullAuthoritativeCompletion }
        assertEquals(1, fullAuthGrades.size)
        assertEquals(AndroidCompletionTruthHardeningContract.CompletionTruthGrade.VERIFIED_COMPLETE, fullAuthGrades[0])
    }

    // ── StabilizationBaseline registration ────────────────────────────────────

    @Test
    fun `android-completion-truth-hardening is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-completion-truth-hardening")
        assertFalse("android-completion-truth-hardening must be registered", entry == null)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }
}
