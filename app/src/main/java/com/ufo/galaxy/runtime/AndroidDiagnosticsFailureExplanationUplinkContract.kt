package com.ufo.galaxy.runtime

/**
 * PR-10Android — diagnostics/failure/explanation uplink semantic boundary contract.
 *
 * Keeps Android authority runtime signals, failure/diagnostics signals, artifact/result signals,
 * and operator-visible summary/explanation/local-interpretation signals explicitly separated.
 */
object AndroidDiagnosticsFailureExplanationUplinkContract {

    const val INTRODUCED_PR = 100
    const val SCHEMA_VERSION = "1"

    const val KEY_UPLINK_SEMANTIC_BOUNDARY_CLASS = "uplink_semantic_boundary_class"
    const val KEY_OPERATOR_PROJECTION_CLASS = "operator_projection_class"
    const val KEY_DIAGNOSTICS_FAILURE_EXPLANATION_SCHEMA_VERSION =
        "diagnostics_failure_explanation_schema_version"

    enum class UplinkSemanticBoundaryClass(val wireValue: String) {
        AUTHORITY_RUNTIME_SIGNAL("authority_runtime_signal"),
        FAILURE_DIAGNOSTICS_SIGNAL("failure_diagnostics_signal"),
        ARTIFACT_RESULT_SIGNAL("artifact_result_signal"),
        OPERATOR_SUMMARY_EXPLANATION_SIGNAL("operator_summary_explanation_signal")
    }

    enum class OperatorProjectionClass(val wireValue: String) {
        NONE("none"),
        OPERATOR_VISIBLE_SUMMARY("operator_visible_summary"),
        POST_RUN_EXPLANATION("post_run_explanation"),
        LOCAL_INTERPRETATION("local_interpretation")
    }

    data class UplinkSemanticBoundarySnapshot(
        val uplinkSemanticBoundaryClass: UplinkSemanticBoundaryClass,
        val operatorProjectionClass: OperatorProjectionClass
    ) {
        fun toWireMap(): Map<String, String> = mapOf(
            KEY_UPLINK_SEMANTIC_BOUNDARY_CLASS to uplinkSemanticBoundaryClass.wireValue,
            KEY_OPERATOR_PROJECTION_CLASS to operatorProjectionClass.wireValue,
            KEY_DIAGNOSTICS_FAILURE_EXPLANATION_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    fun forGoalResult(
        resultSummary: String?,
        result: String?,
        details: String?,
        error: String?
    ): UplinkSemanticBoundarySnapshot {
        val projection = when {
            !error.isNullOrBlank() || !details.isNullOrBlank() -> OperatorProjectionClass.POST_RUN_EXPLANATION
            !resultSummary.isNullOrBlank() || !result.isNullOrBlank() ->
                OperatorProjectionClass.OPERATOR_VISIBLE_SUMMARY
            else -> OperatorProjectionClass.NONE
        }
        return UplinkSemanticBoundarySnapshot(
            uplinkSemanticBoundaryClass = UplinkSemanticBoundaryClass.ARTIFACT_RESULT_SIGNAL,
            operatorProjectionClass = projection
        )
    }

    fun forExecutionEvent(
        lifecycleTerminalPhase: Boolean,
        resultSignalClass: AndroidResultUplinkBoundaryContract.ResultSignalClass,
        sourceComponent: String?,
        executionModeStateWire: String?
    ): UplinkSemanticBoundarySnapshot {
        val sourceIsCanonicalRuntimeMainchain = isCanonicalRuntimeMainchainSource(sourceComponent)
        val modeState = LocalExecutionModeGate.ExecutionModeState.fromWireValue(executionModeStateWire)
        val runtimeIsExecutionCapable = modeState == LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE ||
            modeState == LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED
        val boundaryClass = if (lifecycleTerminalPhase &&
            resultSignalClass == AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT &&
            sourceIsCanonicalRuntimeMainchain &&
            runtimeIsExecutionCapable
        ) {
            UplinkSemanticBoundaryClass.AUTHORITY_RUNTIME_SIGNAL
        } else {
            UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL
        }
        return UplinkSemanticBoundarySnapshot(
            uplinkSemanticBoundaryClass = boundaryClass,
            operatorProjectionClass = OperatorProjectionClass.NONE
        )
    }

    fun forDeviceStateSnapshot(): UplinkSemanticBoundarySnapshot =
        UplinkSemanticBoundarySnapshot(
            uplinkSemanticBoundaryClass = UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL,
            operatorProjectionClass = OperatorProjectionClass.NONE
        )

    fun forDiagnosticsPayload(): UplinkSemanticBoundarySnapshot =
        UplinkSemanticBoundarySnapshot(
            uplinkSemanticBoundaryClass = UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL,
            operatorProjectionClass = OperatorProjectionClass.LOCAL_INTERPRETATION
        )

    private val runtimeMainchainSourceHints: Set<String> by lazy {
        AndroidMinimalRuntimeAccessChainContract.minimalMainChainEntries
            .map { it.ownerClass.substringAfterLast('.') }
            .toSet()
    }

    // Accepts source forms like:
    // - "GalaxyConnectionService.handleGoalExecution"
    // - "com.ufo.galaxy.runtime.RuntimeController.update"
    // - "LocalLoopExecutor:progress"
    private val sourceRootRegex = Regex("\\b([A-Z][A-Za-z0-9_]*)\\b")

    private fun isCanonicalRuntimeMainchainSource(sourceComponent: String?): Boolean {
        if (sourceComponent.isNullOrBlank()) return false
        val sourceRoot = sourceRootRegex.find(sourceComponent.trim())?.groupValues?.get(1) ?: return false
        return runtimeMainchainSourceHints.contains(sourceRoot)
    }

    val V2_CONSUMPTION_PATH_MAP: Map<UplinkSemanticBoundaryClass, String> = mapOf(
        UplinkSemanticBoundaryClass.AUTHORITY_RUNTIME_SIGNAL to
            "core/task_result_canonical_truth_chain.py",
        UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL to
            "core/android_device_state_store.py",
        UplinkSemanticBoundaryClass.ARTIFACT_RESULT_SIGNAL to
            "core/artifact_result_ingress.py",
        UplinkSemanticBoundaryClass.OPERATOR_SUMMARY_EXPLANATION_SIGNAL to
            "board/operator_perception_surface.py"
    )

    val DIAGNOSTICS_FAILURE_EXPLANATION_INVARIANTS: List<String> = listOf(
        "INV-DFE-01: uplink_semantic_boundary_class MUST be set on diagnostics/result/state/event uplinks.",
        "INV-DFE-02: operator_projection_class MUST NOT upgrade projection payloads into authority truth.",
        "INV-DFE-03: diagnostics payload MUST be failure_diagnostics_signal + local_interpretation.",
        "INV-DFE-04: goal result payload MUST be artifact_result_signal; summary/explanation are subordinate projection.",
        "INV-DFE-05: execution_event authority_runtime_signal MUST originate from " +
            "AndroidMinimalRuntimeAccessChainContract.minimalMainChainEntries " +
            "and an execution-capable LocalExecutionModeGate state.",
        "INV-DFE-06: schema version MUST equal SCHEMA_VERSION."
    )
}
