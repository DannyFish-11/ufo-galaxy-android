package com.ufo.galaxy.runtime

/**
 * PR-12Android — completion / closure uplink boundary convergence contract.
 *
 * Reuses existing Android result/diagnostics contracts to project four non-overlapping uplink
 * semantics for V2 canonical consumption:
 *  1) authority runtime completion signal
 *  2) result completion signal
 *  3) closure/session-finalization signal
 *  4) operator-visible done summary/explanation projection
 */
object AndroidCompletionClosureUplinkContract {

    const val INTRODUCED_PR = 102
    const val SCHEMA_VERSION = "1"
    const val PAYLOAD_SCHEMA_VERSION = "1"

    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_COMPLETION_CLOSURE_CONTRACT_VERSION = "completion_closure_contract_version"
    const val KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS =
        "authority_runtime_completion_signal_class"
    const val KEY_RESULT_COMPLETION_SIGNAL_CLASS = "result_completion_signal_class"
    const val KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS = "closure_finalization_signal_class"
    const val KEY_OPERATOR_DONE_PROJECTION_CLASS = "operator_done_projection_class"
    const val KEY_COMPLETION_EMISSION_ID = "completion_emission_id"
    const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
    const val KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION = "completion_closure_uplink_schema_version"
    const val KEY_LOCAL_EXECUTION_COMPLETED = "local_execution_completed"
    const val KEY_ADVISORY_EVIDENCE_SENT = "advisory_evidence_sent"
    const val KEY_V2_UPLINK_ACKNOWLEDGED = "v2_uplink_acknowledged"
    const val KEY_V2_RECONCILIATION_ACKNOWLEDGED = "v2_reconciliation_acknowledged"
    const val KEY_V2_CANONICAL_TRUTH_COMPLETED = "v2_canonical_truth_completed"
    const val KEY_V2_MATURE_CLOSURE_ACHIEVED = "v2_mature_closure_achieved"
    const val KEY_OUTWARD_TRUTH_SURFACE_CLASS = "outward_truth_surface_class"
    const val KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH = "is_v2_confirmed_canonical_truth"

    enum class AuthorityRuntimeCompletionSignalClass(val wireValue: String) {
        AUTHORITY_RUNTIME_COMPLETION("authority_runtime_completion"),
        RUNTIME_COMPLETION_EVIDENCE("runtime_completion_evidence"),
        NOT_RUNTIME_COMPLETION("not_runtime_completion")
    }

    enum class ResultCompletionSignalClass(val wireValue: String) {
        RESULT_COMPLETED("result_completed"),
        RESULT_PENDING("result_pending"),
        RESULT_INFORMATIONAL("result_informational")
    }

    enum class ClosureFinalizationSignalClass(val wireValue: String) {
        SESSION_FINALIZATION_READY("session_finalization_ready"),
        SESSION_FINALIZATION_PENDING("session_finalization_pending"),
        SESSION_FINALIZATION_BLOCKED("session_finalization_blocked"),
        SESSION_FINALIZATION_NOT_APPLICABLE("session_finalization_not_applicable")
    }

    enum class OperatorDoneProjectionClass(val wireValue: String) {
        NONE("none"),
        OPERATOR_VISIBLE_DONE_SUMMARY("operator_visible_done_summary"),
        POST_RUN_COMPLETION_EXPLANATION("post_run_completion_explanation"),
        LOCAL_INTERPRETATION("local_interpretation")
    }

    enum class OutwardTruthSurfaceClass(val wireValue: String) {
        ANDROID_ADVISORY_EVIDENCE("android_advisory_evidence"),
        ANDROID_RUNTIME_VISIBLE_STATE("android_runtime_visible_state"),
        ANDROID_DIAGNOSTICS_VISIBLE_STATE("android_diagnostics_visible_state"),
        V2_CONFIRMED_CANONICAL_TRUTH("v2_confirmed_canonical_truth")
    }

    data class CompletionClosureUplinkSnapshot(
        val authorityRuntimeCompletionSignalClass: AuthorityRuntimeCompletionSignalClass,
        val resultCompletionSignalClass: ResultCompletionSignalClass,
        val closureFinalizationSignalClass: ClosureFinalizationSignalClass,
        val operatorDoneProjectionClass: OperatorDoneProjectionClass
    ) {
        fun toWireMap(): Map<String, String> = mapOf(
            KEY_SCHEMA_VERSION to PAYLOAD_SCHEMA_VERSION,
            KEY_COMPLETION_CLOSURE_CONTRACT_VERSION to SCHEMA_VERSION,
            KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS to
                authorityRuntimeCompletionSignalClass.wireValue,
            KEY_RESULT_COMPLETION_SIGNAL_CLASS to resultCompletionSignalClass.wireValue,
            KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS to closureFinalizationSignalClass.wireValue,
            KEY_OPERATOR_DONE_PROJECTION_CLASS to operatorDoneProjectionClass.wireValue,
            KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    data class V2CanonicalBoundarySnapshot(
        val localExecutionCompleted: Boolean,
        val advisoryEvidenceSent: Boolean,
        val v2UplinkAcknowledged: Boolean,
        val v2ReconciliationAcknowledged: Boolean,
        val v2CanonicalTruthCompleted: Boolean,
        val v2MatureClosureAchieved: Boolean,
        val outwardTruthSurfaceClass: OutwardTruthSurfaceClass
    ) {
        init {
            require(
                !v2CanonicalTruthCompleted ||
                    outwardTruthSurfaceClass == OutwardTruthSurfaceClass.V2_CONFIRMED_CANONICAL_TRUTH
            ) {
                "Only v2_confirmed_canonical_truth surface may mark v2_canonical_truth_completed=true"
            }
            require(
                !v2MatureClosureAchieved ||
                    outwardTruthSurfaceClass == OutwardTruthSurfaceClass.V2_CONFIRMED_CANONICAL_TRUTH
            ) {
                "Only v2_confirmed_canonical_truth surface may mark v2_mature_closure_achieved=true"
            }
        }

        fun toWireMap(): Map<String, Boolean> = mapOf(
            KEY_LOCAL_EXECUTION_COMPLETED to localExecutionCompleted,
            KEY_ADVISORY_EVIDENCE_SENT to advisoryEvidenceSent,
            KEY_V2_UPLINK_ACKNOWLEDGED to v2UplinkAcknowledged,
            KEY_V2_RECONCILIATION_ACKNOWLEDGED to v2ReconciliationAcknowledged,
            KEY_V2_CANONICAL_TRUTH_COMPLETED to v2CanonicalTruthCompleted,
            KEY_V2_MATURE_CLOSURE_ACHIEVED to v2MatureClosureAchieved
        )
    }

    fun deriveV2CanonicalBoundary(
        localExecutionCompleted: Boolean,
        advisoryEvidenceSent: Boolean = true,
        outwardTruthSurfaceClass: OutwardTruthSurfaceClass =
            OutwardTruthSurfaceClass.ANDROID_ADVISORY_EVIDENCE
    ): V2CanonicalBoundarySnapshot = V2CanonicalBoundarySnapshot(
        localExecutionCompleted = localExecutionCompleted,
        advisoryEvidenceSent = advisoryEvidenceSent,
        v2UplinkAcknowledged = false,
        v2ReconciliationAcknowledged = false,
        v2CanonicalTruthCompleted = false,
        v2MatureClosureAchieved = false,
        outwardTruthSurfaceClass = outwardTruthSurfaceClass
    )

    fun deriveForGoalResult(
        isLifecycleTerminalPhase: Boolean,
        resultSignalClass: AndroidResultUplinkBoundaryContract.ResultSignalClass,
        acceptanceCandidateClass: AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass,
        resultReturned: Boolean,
        completionSignaled: Boolean,
        closureReadyForAcceptance: Boolean,
        operatorProjectionClass: AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass
    ): CompletionClosureUplinkSnapshot = derive(
        isLifecycleTerminalPhase = isLifecycleTerminalPhase,
        resultSignalClass = resultSignalClass,
        acceptanceCandidateClass = acceptanceCandidateClass,
        resultReturned = resultReturned,
        completionSignaled = completionSignaled,
        closureReadyForAcceptance = closureReadyForAcceptance,
        operatorProjectionClass = operatorProjectionClass
    )

    fun deriveForExecutionEvent(
        isLifecycleTerminalPhase: Boolean,
        resultSignalClass: AndroidResultUplinkBoundaryContract.ResultSignalClass,
        acceptanceCandidateClass: AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass,
        resultReturned: Boolean,
        completionSignaled: Boolean,
        closureReadyForAcceptance: Boolean,
        operatorProjectionClass: AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass
    ): CompletionClosureUplinkSnapshot = derive(
        isLifecycleTerminalPhase = isLifecycleTerminalPhase,
        resultSignalClass = resultSignalClass,
        acceptanceCandidateClass = acceptanceCandidateClass,
        resultReturned = resultReturned,
        completionSignaled = completionSignaled,
        closureReadyForAcceptance = closureReadyForAcceptance,
        operatorProjectionClass = operatorProjectionClass
    )

    fun deriveForDeviceStateSnapshot(): CompletionClosureUplinkSnapshot =
        CompletionClosureUplinkSnapshot(
            authorityRuntimeCompletionSignalClass =
                AuthorityRuntimeCompletionSignalClass.NOT_RUNTIME_COMPLETION,
            resultCompletionSignalClass = ResultCompletionSignalClass.RESULT_INFORMATIONAL,
            closureFinalizationSignalClass =
                ClosureFinalizationSignalClass.SESSION_FINALIZATION_NOT_APPLICABLE,
            operatorDoneProjectionClass = OperatorDoneProjectionClass.NONE
        )

    fun deriveForReconciliationSignal(
        isTerminalSignal: Boolean,
        resultReturned: Boolean,
        completionSignaled: Boolean,
        closureReadyForAcceptance: Boolean
    ): CompletionClosureUplinkSnapshot {
        val resultSignalClass =
            if (!resultReturned && !completionSignaled) {
                AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL
            } else {
                AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL
            }
        val acceptanceClass =
            if (closureReadyForAcceptance) {
                AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE
            } else if (isTerminalSignal) {
                AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.PENDING_RESULT_RETURN
            } else {
                AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE
            }
        return derive(
            isLifecycleTerminalPhase = isTerminalSignal,
            resultSignalClass = resultSignalClass,
            acceptanceCandidateClass = acceptanceClass,
            resultReturned = resultReturned,
            completionSignaled = completionSignaled,
            closureReadyForAcceptance = closureReadyForAcceptance,
            operatorProjectionClass =
                AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.NONE
        )
    }

    private fun derive(
        isLifecycleTerminalPhase: Boolean,
        resultSignalClass: AndroidResultUplinkBoundaryContract.ResultSignalClass,
        acceptanceCandidateClass: AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass,
        resultReturned: Boolean,
        completionSignaled: Boolean,
        closureReadyForAcceptance: Boolean,
        operatorProjectionClass: AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass
    ): CompletionClosureUplinkSnapshot {
        val authorityClass = when {
            isLifecycleTerminalPhase &&
                resultSignalClass == AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT ->
                AuthorityRuntimeCompletionSignalClass.AUTHORITY_RUNTIME_COMPLETION
            resultReturned || completionSignaled ->
                AuthorityRuntimeCompletionSignalClass.RUNTIME_COMPLETION_EVIDENCE
            else ->
                AuthorityRuntimeCompletionSignalClass.NOT_RUNTIME_COMPLETION
        }
        val resultCompletionClass = when {
            resultSignalClass == AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL &&
                !resultReturned &&
                !completionSignaled ->
                ResultCompletionSignalClass.RESULT_INFORMATIONAL
            resultReturned && completionSignaled ->
                ResultCompletionSignalClass.RESULT_COMPLETED
            else ->
                ResultCompletionSignalClass.RESULT_PENDING
        }
        val closureClass = when {
            acceptanceCandidateClass ==
                AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED ->
                ClosureFinalizationSignalClass.SESSION_FINALIZATION_BLOCKED
            closureReadyForAcceptance &&
                acceptanceCandidateClass ==
                AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE ->
                ClosureFinalizationSignalClass.SESSION_FINALIZATION_READY
            closureReadyForAcceptance || resultReturned || completionSignaled ->
                ClosureFinalizationSignalClass.SESSION_FINALIZATION_PENDING
            else ->
                ClosureFinalizationSignalClass.SESSION_FINALIZATION_NOT_APPLICABLE
        }
        val doneProjectionClass = when (operatorProjectionClass) {
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.NONE ->
                OperatorDoneProjectionClass.NONE
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.OPERATOR_VISIBLE_SUMMARY ->
                OperatorDoneProjectionClass.OPERATOR_VISIBLE_DONE_SUMMARY
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.POST_RUN_EXPLANATION ->
                OperatorDoneProjectionClass.POST_RUN_COMPLETION_EXPLANATION
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.LOCAL_INTERPRETATION ->
                OperatorDoneProjectionClass.LOCAL_INTERPRETATION
        }
        return CompletionClosureUplinkSnapshot(
            authorityRuntimeCompletionSignalClass = authorityClass,
            resultCompletionSignalClass = resultCompletionClass,
            closureFinalizationSignalClass = closureClass,
            operatorDoneProjectionClass = doneProjectionClass
        )
    }
}
