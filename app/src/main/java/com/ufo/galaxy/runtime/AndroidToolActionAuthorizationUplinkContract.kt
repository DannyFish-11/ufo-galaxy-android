package com.ufo.galaxy.runtime

/**
 * PR-11Android — tool/action/authorization uplink semantic boundary contract.
 *
 * 固定 Android `operator_action_result` 上行在五个语义层次的边界：
 * 1) operator intent capture
 * 2) runtime-approved execution authority
 * 3) actual local side effect / tool invocation truth
 * 4) result reporting signal
 * 5) post-action summary/explanation signal
 */
object AndroidToolActionAuthorizationUplinkContract {

    const val INTRODUCED_PR = 101
    const val SCHEMA_VERSION = "1"

    enum class OperatorIntentCaptureClass(val wireValue: String) {
        OPERATOR_REQUEST_CAPTURED("operator_request_captured")
    }

    enum class RuntimeAuthorityClass(val wireValue: String) {
        RUNTIME_AUTHORITY_APPROVED("runtime_authority_approved"),
        RUNTIME_AUTHORITY_REJECTED("runtime_authority_rejected")
    }

    enum class ActualExecutionSignalClass(val wireValue: String) {
        NOT_EXECUTED("not_executed"),
        EXECUTION_ATTEMPTED_NO_SIDE_EFFECT("execution_attempted_no_side_effect"),
        LOCAL_SIDE_EFFECT_COMMITTED("local_side_effect_committed")
    }

    enum class ToolInvocationSignalClass(val wireValue: String) {
        TOOL_NOT_INVOKED("tool_not_invoked"),
        TOOL_INVOCATION_CONFIRMED("tool_invocation_confirmed")
    }

    enum class ResultReportingSignalClass(val wireValue: String) {
        DECISION_SIGNAL("decision_signal"),
        EXECUTION_RESULT_SIGNAL("execution_result_signal")
    }

    enum class PostActionExplanationClass(val wireValue: String) {
        NONE("none"),
        FACTUAL_RESULT_SUMMARY("factual_result_summary"),
        FAILURE_EXPLANATION("failure_explanation")
    }

    data class DerivationInput(
        val actionKind: String?,
        val phase: String,
        val decisionStatus: String,
        val executionStatus: String,
        val error: String?,
        val details: Map<String, String>
    )

    data class Snapshot(
        val operatorIntentCaptureClass: OperatorIntentCaptureClass,
        val runtimeAuthorityClass: RuntimeAuthorityClass,
        val actualExecutionSignalClass: ActualExecutionSignalClass,
        val toolInvocationSignalClass: ToolInvocationSignalClass,
        val resultReportingSignalClass: ResultReportingSignalClass,
        val postActionExplanationClass: PostActionExplanationClass
    ) {
        fun toWireMap(): Map<String, String> = mapOf(
            "operator_intent_capture_class" to operatorIntentCaptureClass.wireValue,
            "runtime_authority_class" to runtimeAuthorityClass.wireValue,
            "actual_execution_signal_class" to actualExecutionSignalClass.wireValue,
            "tool_invocation_signal_class" to toolInvocationSignalClass.wireValue,
            "result_reporting_signal_class" to resultReportingSignalClass.wireValue,
            "post_action_explanation_class" to postActionExplanationClass.wireValue,
            "tool_action_authorization_schema_version" to SCHEMA_VERSION
        )
    }

    private val localMutationActionKinds = setOf(
        AndroidOperatorActionGovernanceContract.ActionKind.FORCE_REATTACH.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.TRIGGER_RECOVERY.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.REOPEN_REBIND_SESSION.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.SUSPEND_ISOLATE_DEVICE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.REJECT_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.REOPEN_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION.wireValue
    )

    private val toolInvocationActionKinds = setOf(
        AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.REJECT_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.REOPEN_CLOSURE.wireValue,
        AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION.wireValue
    )

    fun derive(input: DerivationInput): Snapshot {
        val isDecisionPhase = input.phase == com.ufo.galaxy.protocol.OperatorActionResultPayload.PHASE_DECISION
        val executionCommitted = !isDecisionPhase &&
            (input.executionStatus == com.ufo.galaxy.protocol.OperatorActionResultPayload.EXECUTION_EXECUTED ||
                input.executionStatus == com.ufo.galaxy.protocol.OperatorActionResultPayload.EXECUTION_PARTIAL)
        val mutatesLocalRuntime = input.actionKind in localMutationActionKinds
        val invokesToolPath = input.actionKind in toolInvocationActionKinds

        val runtimeAuthorityClass = if (
            input.decisionStatus == com.ufo.galaxy.protocol.OperatorActionResultPayload.DECISION_ACCEPTED
        ) RuntimeAuthorityClass.RUNTIME_AUTHORITY_APPROVED
        else RuntimeAuthorityClass.RUNTIME_AUTHORITY_REJECTED

        val actualExecutionSignalClass = when {
            isDecisionPhase -> ActualExecutionSignalClass.NOT_EXECUTED
            executionCommitted && mutatesLocalRuntime -> ActualExecutionSignalClass.LOCAL_SIDE_EFFECT_COMMITTED
            else -> ActualExecutionSignalClass.EXECUTION_ATTEMPTED_NO_SIDE_EFFECT
        }

        val toolInvocationSignalClass = if (
            executionCommitted && invokesToolPath
        ) ToolInvocationSignalClass.TOOL_INVOCATION_CONFIRMED
        else ToolInvocationSignalClass.TOOL_NOT_INVOKED

        val explanationClass = when {
            !input.error.isNullOrBlank() || input.details.isNotEmpty() ->
                PostActionExplanationClass.FAILURE_EXPLANATION
            !isDecisionPhase -> PostActionExplanationClass.FACTUAL_RESULT_SUMMARY
            else -> PostActionExplanationClass.NONE
        }

        return Snapshot(
            operatorIntentCaptureClass = OperatorIntentCaptureClass.OPERATOR_REQUEST_CAPTURED,
            runtimeAuthorityClass = runtimeAuthorityClass,
            actualExecutionSignalClass = actualExecutionSignalClass,
            toolInvocationSignalClass = toolInvocationSignalClass,
            resultReportingSignalClass = if (isDecisionPhase) {
                ResultReportingSignalClass.DECISION_SIGNAL
            } else {
                ResultReportingSignalClass.EXECUTION_RESULT_SIGNAL
            },
            postActionExplanationClass = explanationClass
        )
    }

    val V2_CANONICAL_CHAIN_PATH_MAP: Map<String, String> = mapOf(
        "operator_intent_capture_class" to
            "board/operator_action_intent_plane.py",
        "runtime_authority_class" to
            "core/operator_action_authorization_chain.py",
        "actual_execution_signal_class" to
            "core/task_result_canonical_truth_chain.py",
        "tool_invocation_signal_class" to
            "core/tool_invocation_signal_chain.py",
        "result_reporting_signal_class" to
            "core/operator_action_result_ingress.py",
        "post_action_explanation_class" to
            "board/operator_perception_surface.py"
    )

    val TOOL_ACTION_AUTHORIZATION_INVARIANTS: List<String> = listOf(
        "INV-TAA-01: operator_intent_capture_class MUST represent intent capture only; MUST NOT imply execution.",
        "INV-TAA-02: runtime_authority_class MUST be derived from governance decision, not from explanation fields.",
        "INV-TAA-03: local side effect / tool invocation truth MUST be derived from execution phase and action kind.",
        "INV-TAA-04: result_reporting_signal_class MUST distinguish decision_signal vs execution_result_signal.",
        "INV-TAA-05: post_action_explanation_class MUST NOT be consumed as execution truth or runtime authority.",
        "INV-TAA-06: schema version MUST equal SCHEMA_VERSION."
    )
}
