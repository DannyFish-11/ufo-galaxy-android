package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment

/**
 * Canonical Android-side semantic classes for distributed execution-spine participation.
 */
object AndroidExecutionSpineSemanticsContract {

    enum class ExecutionSemanticClass(val wireValue: String) {
        DELEGATED_EXECUTION("delegated_execution"),
        TAKEOVER_INTERACTIVE_EXECUTION("takeover_interactive_execution"),
        LOCAL_ASSISTIVE_EXECUTION("local_assistive_execution"),
        DEGRADED_FALLBACK_EXECUTION("degraded_fallback_execution")
    }

    enum class ProblemProgressSemanticClass(val wireValue: String) {
        IN_PROGRESS("in_progress"),
        TASK_TERMINAL_ONLY("task_terminal_only"),
        PROBLEM_PROGRESS_ADVANCED("problem_progress_advanced"),
        PROBLEM_CLOSURE_CANDIDATE("problem_closure_candidate"),
        PROBLEM_CLOSURE_DEFERRED("problem_closure_deferred")
    }

    enum class ClosureReportingSemanticClass(val wireValue: String) {
        TASK_RESULT_ONLY("task_result_only"),
        PROBLEM_PROGRESS_SIGNAL("problem_progress_signal"),
        CLOSURE_CANDIDATE_SIGNAL("closure_candidate_signal"),
        CLOSURE_DEFERRED_SIGNAL("closure_deferred_signal")
    }

    fun classifyProblemProgress(status: String, holdReason: String?): ProblemProgressSemanticClass {
        if (!holdReason.isNullOrBlank()) return ProblemProgressSemanticClass.PROBLEM_CLOSURE_DEFERRED
        return when (UgcpSharedSchemaAlignment.normalizeLifecycleStatus(status)) {
            "success" -> ProblemProgressSemanticClass.PROBLEM_CLOSURE_CANDIDATE
            "cancelled", "disabled", "error", "timeout" ->
                ProblemProgressSemanticClass.TASK_TERMINAL_ONLY
            "hold" -> ProblemProgressSemanticClass.PROBLEM_CLOSURE_DEFERRED
            else -> ProblemProgressSemanticClass.IN_PROGRESS
        }
    }

    fun classifyClosureReporting(problemProgress: ProblemProgressSemanticClass): ClosureReportingSemanticClass =
        when (problemProgress) {
            ProblemProgressSemanticClass.PROBLEM_CLOSURE_CANDIDATE ->
                ClosureReportingSemanticClass.CLOSURE_CANDIDATE_SIGNAL
            ProblemProgressSemanticClass.PROBLEM_CLOSURE_DEFERRED ->
                ClosureReportingSemanticClass.CLOSURE_DEFERRED_SIGNAL
            ProblemProgressSemanticClass.PROBLEM_PROGRESS_ADVANCED,
            ProblemProgressSemanticClass.IN_PROGRESS ->
                ClosureReportingSemanticClass.PROBLEM_PROGRESS_SIGNAL
            ProblemProgressSemanticClass.TASK_TERMINAL_ONLY ->
                ClosureReportingSemanticClass.TASK_RESULT_ONLY
        }
}
