package com.ufo.galaxy.runtime

/**
 * Android-side runtime hook bridge that feeds real runtime events into the dual-repo
 * E2E harness and exposes cross-repo regression readiness across five critical flows.
 */
enum class AndroidCrossRepoRegressionFlow(val wireValue: String) {
    CAPABILITY("capability"),
    EXECUTION("execution"),
    RECOVERY("recovery"),
    TAKEOVER("takeover"),
    MESH("mesh");

    companion object {
        val REQUIRED_FLOWS: Set<AndroidCrossRepoRegressionFlow> = entries.toSet()
    }
}

data class AndroidCrossRepoRegressionSnapshot(
    val e2eReport: DualRepoE2EVerificationReport,
    val flowOutcomes: Map<AndroidCrossRepoRegressionFlow, ScenarioOutcomeStatus>,
    val reportedAtMs: Long,
    val schemaVersion: String = SCHEMA_VERSION
) {
    val isDualRuntimeRegressionReady: Boolean
        get() = AndroidCrossRepoRegressionFlow.REQUIRED_FLOWS.all { flow ->
            flowOutcomes[flow] == ScenarioOutcomeStatus.PASSED
        } &&
            e2eReport.isRuntimeClosedEvidence &&
            e2eReport.hasCanonicalRoundTripHooks

    fun toWireMap(): Map<String, Any?> = mapOf(
        "schema_version" to schemaVersion,
        "reported_at_ms" to reportedAtMs,
        "is_dual_runtime_regression_ready" to isDualRuntimeRegressionReady,
        "flow_outcomes" to flowOutcomes.mapKeys { it.key.wireValue }.mapValues { it.value.wireValue },
        "dual_repo_e2e" to e2eReport.toWireMap()
    )

    companion object {
        const val SCHEMA_VERSION = "1.0"
    }
}

class AndroidCrossRepoRegressionRuntimeHooks(
    deviceId: String,
    participantId: String,
    verificationKind: RealDeviceVerificationKind
) {
    private val harness = DualRepoE2EVerificationHarness(
        deviceId = deviceId,
        participantId = participantId,
        verificationKind = verificationKind
    )
    private val flowOutcomes: MutableMap<AndroidCrossRepoRegressionFlow, ScenarioOutcomeStatus> =
        mutableMapOf()

    fun recordDeviceRegisterSent() {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.DEVICE_REGISTER,
            status = ScenarioOutcomeStatus.PASSED
        )
    }

    fun recordCapabilityReportSent() {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            status = ScenarioOutcomeStatus.PASSED
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.CAPABILITY, ScenarioOutcomeStatus.PASSED)
    }

    fun recordExecutionReceipt(
        taskId: String,
        traceId: String?,
        runtimeSessionId: String?,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION,
            status = status,
            reason = reason
        )
        harness.recordVerificationHook(
            kind = DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED,
            status = status,
            traceId = traceId,
            runtimeSessionId = runtimeSessionId,
            taskId = taskId,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status)
    }

    fun recordExecutionSignal(
        taskId: String,
        traceId: String?,
        runtimeSessionId: String?,
        signalKind: String?,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE,
            status = status,
            reason = reason
        )
        harness.recordVerificationHook(
            kind = DualRepoE2EVerificationHookKind.SIGNAL_EMITTED,
            status = status,
            traceId = traceId,
            runtimeSessionId = runtimeSessionId,
            taskId = taskId,
            delegatedSignalKind = signalKind,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status)
    }

    fun recordGoalResultFeedback(
        taskId: String,
        traceId: String?,
        runtimeSessionId: String?,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.TASK_RESULT_RETURN,
            status = status,
            reason = reason
        )
        harness.recordVerificationHook(
            kind = DualRepoE2EVerificationHookKind.RESULT_FEEDBACK,
            status = status,
            traceId = traceId,
            runtimeSessionId = runtimeSessionId,
            taskId = taskId,
            delegatedSignalKind = "result",
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status)
    }

    fun recordReconnectRecovery(status: ScenarioOutcomeStatus, reason: String? = null) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.RECONNECT_RECOVERY,
            status = status,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.RECOVERY, status)
    }

    fun recordTakeover(status: ScenarioOutcomeStatus, reason: String? = null) {
        harness.recordVerificationHook(
            kind = DualRepoE2EVerificationHookKind.STATE_CORRELATED,
            status = status,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.TAKEOVER, status)
    }

    fun recordMesh(status: ScenarioOutcomeStatus, reason: String? = null) {
        setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, status)
        if (status == ScenarioOutcomeStatus.PASSED) {
            harness.recordStageOutcome(
                stage = DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING,
                status = ScenarioOutcomeStatus.PASSED,
                reason = reason
            )
        }
    }

    fun buildSnapshot(nowMs: Long = System.currentTimeMillis()): AndroidCrossRepoRegressionSnapshot =
        AndroidCrossRepoRegressionSnapshot(
            e2eReport = harness.buildReport(nowMs = nowMs),
            flowOutcomes = flowOutcomes.toMap(),
            reportedAtMs = nowMs
        )

    private fun setFlowOutcome(flow: AndroidCrossRepoRegressionFlow, status: ScenarioOutcomeStatus) {
        val existing = flowOutcomes[flow]
        if (existing == ScenarioOutcomeStatus.FAILED && status == ScenarioOutcomeStatus.PASSED) {
            return
        }
        flowOutcomes[flow] = status
    }
}
