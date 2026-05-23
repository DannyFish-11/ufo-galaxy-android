package com.ufo.galaxy.runtime

/**
 * PR-122 — Stage 5 Android-side completion truth hardening contract.
 *
 * Enforces machine-verifiable invariants for the Stage 5 completion truth tightening:
 *
 * 1. Partial and fallback terminal outcomes are classified as
 *    [AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL],
 *    not as [AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL].
 *    This prevents downstream systems from over-crediting degraded terminal execution.
 *
 * 2. Lifecycle-terminal-phase alone does not grant [RUNTIME_COMPLETION_EVIDENCE] in
 *    [AndroidCompletionClosureUplinkContract]; actual result delivery (resultReturned or
 *    completionSignaled) is required.  A terminal lifecycle phase without result evidence
 *    is reported as [NOT_RUNTIME_COMPLETION].
 *
 * 3. Degraded terminal outcome kinds (PARTIAL_COMPLETION, FALLBACK, RECOVERY) suppress
 *    [AndroidMissionCompletionSemanticsContract.CompletionVisibility.closureReadyForAcceptance],
 *    preventing premature closure acceptance for structurally incomplete executions.
 *
 * These invariants are checked at class-load time through [COMPLETION_TRUTH_INVARIANTS].
 */
object AndroidCompletionTruthHardeningContract {

    const val INTRODUCED_PR = 122

    /**
     * Grades the completion truth quality of an execution outcome.
     *
     * Used to gate downstream V2 interpretation so weak or partial terminal states
     * are not misread as strong authoritative completion.
     *
     * @property wireValue Stable lowercase string for wire transport.
     * @property isFullAuthoritativeCompletion True only when the execution completed with
     *   full authoritative fidelity, i.e. not degraded, partial, recovered, or replayed.
     */
    enum class CompletionTruthGrade(val wireValue: String, val isFullAuthoritativeCompletion: Boolean) {
        VERIFIED_COMPLETE("verified_complete", true),
        DEGRADED_COMPLETE("degraded_complete", false),
        TERMINAL_INCOMPLETE("terminal_incomplete", false),
        NON_TERMINAL("non_terminal", false);

        companion object {
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    /**
     * Classifies the completion truth grade from a [ResultUplinkSemanticClass] and the
     * local terminal outcome kind.
     *
     * This is the canonical entry point for assigning a [CompletionTruthGrade] before
     * emitting completion-related signals to V2.  Callers must use this function — not
     * raw [AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass] comparisons —
     * when making downstream dispatch or closure decisions, so degraded outcomes are
     * not promoted to full authoritative completion grade.
     */
    fun classifyCompletionTruthGrade(
        resultUplinkSemanticClass: AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass,
        terminalOutcomeKind: AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind
    ): CompletionTruthGrade = when {
        resultUplinkSemanticClass ==
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL ||
            terminalOutcomeKind == AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.NON_TERMINAL ->
            CompletionTruthGrade.NON_TERMINAL
        resultUplinkSemanticClass ==
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL ||
            terminalOutcomeKind in setOf(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY
            ) ->
            CompletionTruthGrade.DEGRADED_COMPLETE
        resultUplinkSemanticClass ==
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION ->
            CompletionTruthGrade.TERMINAL_INCOMPLETE
        resultUplinkSemanticClass.isTerminal ->
            CompletionTruthGrade.VERIFIED_COMPLETE
        else ->
            CompletionTruthGrade.NON_TERMINAL
    }

    /**
     * Machine-verifiable invariants for the Stage 5 completion truth hardening.
     * All values must be `true`; any `false` indicates a regression.
     */
    val COMPLETION_TRUTH_INVARIANTS: Map<String, Boolean> = mapOf(

        // PARTIAL_COMPLETION must not map to AUTHORITATIVE_TERMINAL
        "partial_completion_is_not_authoritative_terminal" to
            (AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ) != AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL),

        // FALLBACK must not map to AUTHORITATIVE_TERMINAL
        "fallback_is_not_authoritative_terminal" to
            (AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
            ) != AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL),

        // PARTIAL_COMPLETION must map to AUTHORITATIVE_DEGRADED_TERMINAL
        "partial_completion_maps_to_authoritative_degraded_terminal" to
            (AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ) == AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL),

        // FALLBACK must map to AUTHORITATIVE_DEGRADED_TERMINAL
        "fallback_maps_to_authoritative_degraded_terminal" to
            (AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
            ) == AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL),

        // PARTIAL_COMPLETION outcome suppresses closureReadyForAcceptance
        "partial_completion_suppresses_closure_ready_for_acceptance" to
            (!AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
                phase = com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_COMPLETED,
                lifecycleTerminalPhase = true,
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ).closureReadyForAcceptance),

        // FALLBACK outcome suppresses closureReadyForAcceptance
        "fallback_suppresses_closure_ready_for_acceptance" to
            (!AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
                phase = com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_COMPLETED,
                lifecycleTerminalPhase = true,
                terminalOutcomeKind = AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.FALLBACK
            ).closureReadyForAcceptance),

        // Lifecycle terminal phase alone (without result/completion signal) is NOT runtime completion evidence
        "lifecycle_terminal_phase_alone_is_not_runtime_completion_evidence" to
            (AndroidCompletionClosureUplinkContract.deriveForReconciliationSignal(
                isTerminalSignal = true,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false
            ).authorityRuntimeCompletionSignalClass ==
                AndroidCompletionClosureUplinkContract.AuthorityRuntimeCompletionSignalClass.NOT_RUNTIME_COMPLETION),

        // AUTHORITATIVE_DEGRADED_TERMINAL is a terminal result class
        "authoritative_degraded_terminal_is_terminal" to
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL.isTerminal,

        // VERIFIED_COMPLETE is the only full authoritative completion grade
        "only_verified_complete_is_full_authoritative_completion" to
            (CompletionTruthGrade.entries.count { it.isFullAuthoritativeCompletion } == 1 &&
                CompletionTruthGrade.VERIFIED_COMPLETE.isFullAuthoritativeCompletion),

        // COMPLETION outcome with clean phase grades as VERIFIED_COMPLETE
        "clean_completion_grades_as_verified_complete" to
            (classifyCompletionTruthGrade(
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.COMPLETION
            ) == CompletionTruthGrade.VERIFIED_COMPLETE),

        // PARTIAL_COMPLETION grades as DEGRADED_COMPLETE (not VERIFIED_COMPLETE)
        "partial_completion_grades_as_degraded_complete" to
            (classifyCompletionTruthGrade(
                AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_DEGRADED_TERMINAL,
                AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.PARTIAL_COMPLETION
            ) == CompletionTruthGrade.DEGRADED_COMPLETE)
    )
}
