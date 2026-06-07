package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — unified Android-side execution governance contract.
 *
 * Defines one place for acceptance, priority, concurrency, cancellation, timeout, and
 * failure semantics across Android's delegated execution entry types.
 */
object AndroidExecutionGovernanceContract {

    const val SEMANTIC_REJECT_ACTIVE_TAKEOVER_CONFLICT = "reject_active_takeover_conflict"

    enum class ExecutionType(
        val wireValue: String,
        val priority: Int,
        val supportsTaskCancel: Boolean,
        val timeoutAuthority: String,
        val failureAuthority: String
    ) {
        GOAL_EXECUTION(
            wireValue = "goal_execution",
            priority = 50,
            supportsTaskCancel = true,
            timeoutAuthority = "goal_execution.timeout_ms",
            failureAuthority = "goal_result.status"
        ),
        PARALLEL_SUBTASK(
            wireValue = "parallel_subtask",
            priority = 50,
            supportsTaskCancel = true,
            timeoutAuthority = "goal_execution.timeout_ms",
            failureAuthority = "goal_result.status"
        ),
        TAKEOVER_REQUEST(
            wireValue = "takeover_request",
            priority = 100,
            supportsTaskCancel = false,
            timeoutAuthority = "takeover_request.timeout_ms/default",
            failureAuthority = "delegated_takeover_executor.result_kind"
        )
    }

    data class AcceptanceContext(
        val activeTakeoverId: String?
    )

    sealed interface AcceptanceDecision {
        data object Accepted : AcceptanceDecision
        data class Rejected(val reason: String) : AcceptanceDecision
    }

    /**
     * Unified active-takeover conflict rule:
     * - While takeover is active, Android rejects new `goal_execution` / `parallel_subtask`.
     * - `takeover_request` is handled by takeover-specific eligibility/session gates.
     */
    fun evaluateAcceptance(
        executionType: ExecutionType,
        context: AcceptanceContext
    ): AcceptanceDecision {
        val activeTakeoverId = context.activeTakeoverId?.takeIf { it.isNotBlank() }
        if (activeTakeoverId == null) return AcceptanceDecision.Accepted
        if (executionType == ExecutionType.GOAL_EXECUTION || executionType == ExecutionType.PARALLEL_SUBTASK) {
            return AcceptanceDecision.Rejected(
                "$SEMANTIC_REJECT_ACTIVE_TAKEOVER_CONFLICT:$activeTakeoverId"
            )
        }
        return AcceptanceDecision.Accepted
    }
}
