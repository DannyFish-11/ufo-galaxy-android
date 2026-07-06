package com.ufo.galaxy.runtime

import com.ufo.galaxy.shared.protocol.MsgType

/**
 * PR-04v2 (Android) — Canonical ingress boundary contract for governance/evaluator/posture signals.
 *
 * Distinguishes:
 * 1) canonical governance / execution-policy ingress inputs
 * 2) generic transport/reporting inputs
 * 3) diagnostics/audit/summary inputs
 */
object AndroidGovernanceExecutionPolicyIngressContract {

    const val SCHEMA_VERSION = "1.0"

    enum class IngressBoundaryClass(val wireValue: String) {
        CANONICAL_GOVERNANCE_EXECUTION_POLICY("canonical_governance_execution_policy"),
        GENERIC_TRANSPORT_REPORTING("generic_transport_reporting"),
        DIAGNOSTICS_AUDIT_SUMMARY("diagnostics_audit_summary")
    }

    enum class IngressConsumptionKind(val wireValue: String) {
        GOVERNANCE_EVALUATOR_INGRESS("governance_evaluator_ingress"),
        EXECUTION_POLICY_INGRESS("execution_policy_ingress"),
        NONE("none")
    }

    enum class IngressSignalClass(val wireValue: String) {
        READINESS_ARTIFACT("readiness_artifact"),
        GOVERNANCE_ARTIFACT("governance_artifact"),
        ACCEPTANCE_ARTIFACT("acceptance_artifact"),
        STRATEGY_ARTIFACT("strategy_artifact"),
        RESULT_TRUTH_ARTIFACT("result_truth_artifact"),
        EXECUTION_RUNTIME_EVENT("execution_runtime_event"),
        PARTICIPANT_STATE_POSTURE("participant_state_posture"),
        RUNTIME_TRUTH_SNAPSHOT("runtime_truth_snapshot"),
        DEVICE_STATE_POSTURE("device_state_posture"),
        TASK_LIFECYCLE_TRANSPORT("task_lifecycle_transport"),
        DIAGNOSTICS_SIGNAL("diagnostics_signal"),
        AUDIT_SIGNAL("audit_signal"),
        OTHER("other")
    }

    data class IngressClassification(
        val boundaryClass: IngressBoundaryClass,
        val consumptionKind: IngressConsumptionKind,
        val signalClass: IngressSignalClass,
        val schemaVersion: String = SCHEMA_VERSION
    )

    fun classifyReconciliation(kind: ReconciliationSignal.Kind): IngressClassification = when (kind) {
        ReconciliationSignal.Kind.PARTICIPANT_STATE -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.GOVERNANCE_EVALUATOR_INGRESS,
            signalClass = IngressSignalClass.PARTICIPANT_STATE_POSTURE
        )
        ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.EXECUTION_POLICY_INGRESS,
            signalClass = IngressSignalClass.RUNTIME_TRUTH_SNAPSHOT
        )
        else -> IngressClassification(
            boundaryClass = IngressBoundaryClass.GENERIC_TRANSPORT_REPORTING,
            consumptionKind = IngressConsumptionKind.NONE,
            signalClass = IngressSignalClass.TASK_LIFECYCLE_TRANSPORT
        )
    }

    fun classifyMsgType(msgType: MsgType): IngressClassification = when (msgType) {
        MsgType.DEVICE_READINESS_REPORT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.GOVERNANCE_EVALUATOR_INGRESS,
            signalClass = IngressSignalClass.READINESS_ARTIFACT
        )
        MsgType.DEVICE_GOVERNANCE_REPORT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.GOVERNANCE_EVALUATOR_INGRESS,
            signalClass = IngressSignalClass.GOVERNANCE_ARTIFACT
        )
        MsgType.DEVICE_ACCEPTANCE_REPORT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.GOVERNANCE_EVALUATOR_INGRESS,
            signalClass = IngressSignalClass.ACCEPTANCE_ARTIFACT
        )
        MsgType.DEVICE_STRATEGY_REPORT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.EXECUTION_POLICY_INGRESS,
            signalClass = IngressSignalClass.STRATEGY_ARTIFACT
        )
        MsgType.GOAL_EXECUTION_RESULT,
        MsgType.GOAL_RESULT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.EXECUTION_POLICY_INGRESS,
            signalClass = IngressSignalClass.RESULT_TRUTH_ARTIFACT
        )
        MsgType.DEVICE_EXECUTION_EVENT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.EXECUTION_POLICY_INGRESS,
            signalClass = IngressSignalClass.EXECUTION_RUNTIME_EVENT
        )
        MsgType.DEVICE_STATE_SNAPSHOT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.CANONICAL_GOVERNANCE_EXECUTION_POLICY,
            consumptionKind = IngressConsumptionKind.EXECUTION_POLICY_INGRESS,
            signalClass = IngressSignalClass.DEVICE_STATE_POSTURE
        )
        MsgType.DEVICE_AUDIT_REPORT -> IngressClassification(
            boundaryClass = IngressBoundaryClass.DIAGNOSTICS_AUDIT_SUMMARY,
            consumptionKind = IngressConsumptionKind.NONE,
            signalClass = IngressSignalClass.AUDIT_SIGNAL
        )
        MsgType.DIAGNOSTICS_PAYLOAD -> IngressClassification(
            boundaryClass = IngressBoundaryClass.DIAGNOSTICS_AUDIT_SUMMARY,
            consumptionKind = IngressConsumptionKind.NONE,
            signalClass = IngressSignalClass.DIAGNOSTICS_SIGNAL
        )
        else -> IngressClassification(
            boundaryClass = IngressBoundaryClass.GENERIC_TRANSPORT_REPORTING,
            consumptionKind = IngressConsumptionKind.NONE,
            signalClass = IngressSignalClass.OTHER
        )
    }
}
