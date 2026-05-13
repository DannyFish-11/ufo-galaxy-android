package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — canonical operator-action taxonomy and governance gate.
 *
 * Android-side operator requests must be validated against live runtime state before execution.
 * This contract centralizes action naming, eligibility checks, and rejection reasons so the
 * Android runtime behaves as a governed participant (not a blind command executor).
 */
object AndroidOperatorActionGovernanceContract {

    enum class ActionKind(val wireValue: String) {
        REVALIDATE_PARTICIPATION("revalidate_participation"),
        FORCE_REATTACH("force_reattach"),
        RETRY_DELEGATED_EXECUTION("retry_delegated_execution"),
        TRIGGER_RECOVERY("trigger_recovery"),
        REOPEN_REBIND_SESSION("reopen_rebind_session"),
        SUSPEND_ISOLATE_DEVICE("suspend_isolate_device"),
        FINALIZE_CLOSURE("finalize_closure"),
        REJECT_CLOSURE("reject_closure"),
        REOPEN_CLOSURE("reopen_closure");

        companion object {
            private val wireValueIndex = entries.associateBy { it.wireValue.lowercase() }
            fun fromWire(value: String?): ActionKind? =
                value?.trim()?.lowercase()?.let(wireValueIndex::get)
        }
    }

    data class EligibilityContext(
        val runtimeStateWire: String,
        val dispatchEligible: Boolean,
        val hasAttachedSession: Boolean,
        val reconnectRecoveryStateWire: String,
        val activeTaskId: String?,
        val hasActiveTakeover: Boolean,
        val crossDeviceEnabled: Boolean,
        val operatorSuspendedOrIsolated: Boolean
    )

    sealed interface EligibilityDecision {
        data object Accepted : EligibilityDecision
        data class Rejected(val reason: String) : EligibilityDecision
    }

    fun evaluateEligibility(
        action: ActionKind,
        context: EligibilityContext,
        taskId: String?
    ): EligibilityDecision {
        if (context.operatorSuspendedOrIsolated &&
            action != ActionKind.REVALIDATE_PARTICIPATION &&
            action != ActionKind.REOPEN_REBIND_SESSION &&
            action != ActionKind.TRIGGER_RECOVERY
        ) {
            return EligibilityDecision.Rejected("operator_action_blocked:isolation_active")
        }
        return when (action) {
            ActionKind.REVALIDATE_PARTICIPATION -> EligibilityDecision.Accepted
            ActionKind.SUSPEND_ISOLATE_DEVICE -> EligibilityDecision.Accepted
            ActionKind.FORCE_REATTACH -> {
                if (!context.crossDeviceEnabled) {
                    EligibilityDecision.Rejected("operator_action_blocked:cross_device_disabled")
                } else {
                    EligibilityDecision.Accepted
                }
            }
            ActionKind.RETRY_DELEGATED_EXECUTION -> {
                if (context.activeTaskId.isNullOrBlank() && !context.hasActiveTakeover) {
                    EligibilityDecision.Rejected("operator_action_blocked:no_active_delegated_execution")
                } else {
                    EligibilityDecision.Accepted
                }
            }
            ActionKind.TRIGGER_RECOVERY,
            ActionKind.REOPEN_REBIND_SESSION -> {
                if (!context.crossDeviceEnabled && !context.hasAttachedSession) {
                    EligibilityDecision.Rejected("operator_action_blocked:no_runtime_session_to_recover")
                } else {
                    EligibilityDecision.Accepted
                }
            }
            ActionKind.FINALIZE_CLOSURE,
            ActionKind.REJECT_CLOSURE,
            ActionKind.REOPEN_CLOSURE -> {
                if (taskId.isNullOrBlank()) {
                    EligibilityDecision.Rejected("operator_action_blocked:missing_task_id")
                } else {
                    EligibilityDecision.Accepted
                }
            }
        }
    }
}
