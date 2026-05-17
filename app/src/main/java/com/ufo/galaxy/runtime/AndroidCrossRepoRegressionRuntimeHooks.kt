package com.ufo.galaxy.runtime

/**
 * Android-side runtime hook bridge that feeds real runtime events into the dual-repo
 * E2E harness and exposes cross-repo regression readiness across five critical flows.
 */
enum class AndroidCrossRepoRegressionFlow(val wireValue: String) {
    CONNECTION("connection"),
    CAPABILITY("capability"),
    EXECUTION("execution"),
    LOCAL_RUNTIME("local_runtime"),
    DIAGNOSTICS("diagnostics"),
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
    val flowReasons: Map<AndroidCrossRepoRegressionFlow, String>,
    val meshLifecycleState: AndroidMeshLifecycleEmissionChain.SessionState?,
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
        "flow_reasons" to flowReasons.mapKeys { it.key.wireValue },
        "stage_chain" to buildStageChainWireMap(),
        "mesh_lifecycle_state" to meshLifecycleState?.toWireMap(),
        "dual_repo_e2e" to e2eReport.toWireMap()
    )

    private fun buildStageChainWireMap(): Map<String, Any?> = mapOf(
        "connection" to buildStageEvidence(
            observedStatuses = mapOf(
                "connection" to flowOutcomes[AndroidCrossRepoRegressionFlow.CONNECTION]
            ),
            observedReasons = mapOf(
                "connection" to flowReasons[AndroidCrossRepoRegressionFlow.CONNECTION]
            )
        ),
        "registration_facing" to buildStageEvidence(
            observedStatuses = mapOf(
                DualRepoE2EVerificationStage.DEVICE_REGISTER.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.DEVICE_REGISTER]?.outcomeStatus,
                DualRepoE2EVerificationStage.CAPABILITY_REPORT.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.CAPABILITY_REPORT]?.outcomeStatus
            ),
            observedReasons = mapOf(
                DualRepoE2EVerificationStage.DEVICE_REGISTER.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.DEVICE_REGISTER]?.reason,
                DualRepoE2EVerificationStage.CAPABILITY_REPORT.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.CAPABILITY_REPORT]?.reason
            )
        ),
        "participation_execution" to buildStageEvidence(
            observedStatuses = mapOf(
                DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION]?.outcomeStatus,
                DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE]?.outcomeStatus,
                DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED]?.outcomeStatus,
                DualRepoE2EVerificationHookKind.SIGNAL_EMITTED.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.SIGNAL_EMITTED]?.outcomeStatus,
                AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME.wireValue to
                    flowOutcomes[AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME],
                AndroidCrossRepoRegressionFlow.DIAGNOSTICS.wireValue to
                    flowOutcomes[AndroidCrossRepoRegressionFlow.DIAGNOSTICS],
                AndroidCrossRepoRegressionFlow.TAKEOVER.wireValue to
                    flowOutcomes[AndroidCrossRepoRegressionFlow.TAKEOVER]
            ),
            observedReasons = mapOf(
                DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION]?.reason,
                DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE]?.reason,
                DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.EXECUTION_RECEIVED]?.reason,
                DualRepoE2EVerificationHookKind.SIGNAL_EMITTED.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.SIGNAL_EMITTED]?.reason,
                AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME.wireValue to
                    flowReasons[AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME],
                AndroidCrossRepoRegressionFlow.DIAGNOSTICS.wireValue to
                    flowReasons[AndroidCrossRepoRegressionFlow.DIAGNOSTICS],
                AndroidCrossRepoRegressionFlow.TAKEOVER.wireValue to
                    flowReasons[AndroidCrossRepoRegressionFlow.TAKEOVER]
            ),
            supplemental = mapOf(
                "is_participation_ready_evidence" to e2eReport.isParticipationReadyEvidence
            )
        ),
        "result_return" to buildStageEvidence(
            observedStatuses = mapOf(
                DualRepoE2EVerificationStage.TASK_RESULT_RETURN.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.TASK_RESULT_RETURN]?.outcomeStatus,
                DualRepoE2EVerificationHookKind.RESULT_FEEDBACK.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.RESULT_FEEDBACK]?.outcomeStatus
            ),
            observedReasons = mapOf(
                DualRepoE2EVerificationStage.TASK_RESULT_RETURN.wireValue to
                    e2eReport.stageOutcomes[DualRepoE2EVerificationStage.TASK_RESULT_RETURN]?.reason,
                DualRepoE2EVerificationHookKind.RESULT_FEEDBACK.wireValue to
                    e2eReport.verificationHooks[DualRepoE2EVerificationHookKind.RESULT_FEEDBACK]?.reason
            ),
            supplemental = mapOf(
                "is_runtime_closed_evidence" to e2eReport.isRuntimeClosedEvidence,
                "correlated_task_id" to e2eReport.correlatedTaskId
            )
        ),
        "mesh_lifecycle" to buildStageEvidence(
            observedStatuses = buildMeshObservedStatuses(),
            observedReasons = buildMeshObservedReasons(),
            supplemental = mapOf(
                "mesh_lifecycle_phase" to meshLifecycleState?.phase?.wireValue
            )
        )
    )

    private fun buildStageEvidence(
        observedStatuses: Map<String, ScenarioOutcomeStatus?>,
        observedReasons: Map<String, String?>,
        supplemental: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val normalizedStatuses = observedStatuses.filterValues { it != null }
            .mapValues { it.value!!.wireValue }
        val normalizedReasons = observedReasons.filterValues { !it.isNullOrBlank() }
            .mapValues { it.value!! }
        val missingEvidence = observedStatuses
            .filterValues { it == null }
            .keys
            .sorted()
        val status = when {
            normalizedStatuses.isEmpty() -> ScenarioOutcomeStatus.SKIPPED
            observedStatuses.values.any { it == ScenarioOutcomeStatus.FAILED || it == ScenarioOutcomeStatus.TIMED_OUT } ->
                ScenarioOutcomeStatus.FAILED
            missingEvidence.isNotEmpty() -> ScenarioOutcomeStatus.FAILED
            observedStatuses.values.all { it == ScenarioOutcomeStatus.PASSED } -> ScenarioOutcomeStatus.PASSED
            observedStatuses.values.any { it == ScenarioOutcomeStatus.SKIPPED } -> ScenarioOutcomeStatus.SKIPPED
            else -> ScenarioOutcomeStatus.FAILED
        }
        val reason = normalizedReasons.values.firstOrNull()
            ?: missingEvidence.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "missing_stage_evidence:",
                separator = ","
            )

        return buildMap {
            put("status", status.wireValue)
            put("observed_statuses", normalizedStatuses)
            put("observed_reasons", normalizedReasons)
            if (missingEvidence.isNotEmpty()) {
                put("missing_evidence", missingEvidence)
            }
            if (reason != null) {
                put("reason", reason)
            }
            supplemental.forEach { (key, value) ->
                if (value != null) {
                    put(key, value)
                }
            }
        }
    }

    private fun buildMeshObservedStatuses(): Map<String, ScenarioOutcomeStatus?> = buildMap {
        put(AndroidCrossRepoRegressionFlow.MESH.wireValue, flowOutcomes[AndroidCrossRepoRegressionFlow.MESH])
        meshLifecycleState?.let { state ->
            put(
                "mesh_join",
                if (state.joinAttempted) {
                    if (state.joinEmitted) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED
                } else {
                    null
                }
            )
            put(
                "mesh_result",
                if (state.resultAttempted) {
                    if (state.resultEmitted) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED
                } else {
                    null
                }
            )
            put(
                "mesh_leave",
                if (state.leaveAttempted) {
                    if (state.leaveEmitted) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED
                } else {
                    null
                }
            )
        }
    }

    private fun buildMeshObservedReasons(): Map<String, String?> = buildMap {
        put(AndroidCrossRepoRegressionFlow.MESH.wireValue, flowReasons[AndroidCrossRepoRegressionFlow.MESH])
        meshLifecycleState?.let { state ->
            if (state.joinAttempted && !state.joinEmitted) {
                put("mesh_join", "mesh_join_not_emitted")
            }
            if (state.resultAttempted && !state.resultEmitted) {
                put("mesh_result", "mesh_result_not_emitted")
            }
            if (state.leaveAttempted && !state.leaveEmitted) {
                put("mesh_leave", state.leaveReason ?: "mesh_leave_not_emitted")
            }
        }
    }

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
    private val flowReasons: MutableMap<AndroidCrossRepoRegressionFlow, String> = mutableMapOf()
    private var meshLifecycleState: AndroidMeshLifecycleEmissionChain.SessionState? = null

    /**
     * Records the current WebSocket connection stage for the Android↔V2 acceptance chain.
     *
     * Call this from the connection lifecycle callbacks so the regression snapshot can expose
     * an explicit connection-stage verdict and reason instead of requiring raw log inspection.
     */
    fun recordConnection(status: ScenarioOutcomeStatus, reason: String? = null) {
        setFlowOutcome(AndroidCrossRepoRegressionFlow.CONNECTION, status, reason)
    }

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
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status, reason)
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
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status, reason)
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
        setFlowOutcome(AndroidCrossRepoRegressionFlow.EXECUTION, status, reason)
    }

    fun recordReconnectRecovery(status: ScenarioOutcomeStatus, reason: String? = null) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.RECONNECT_RECOVERY,
            status = status,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.RECOVERY, status, reason)
    }

    fun recordLocalRuntimeBehavior(
        startResult: RuntimeStartResult,
        reason: String? = null
    ) {
        val status = when (startResult) {
            RuntimeStartResult.Success,
            is RuntimeStartResult.Degraded -> ScenarioOutcomeStatus.PASSED
            is RuntimeStartResult.Failure -> ScenarioOutcomeStatus.FAILED
        }
        val resolvedReason = when (startResult) {
            RuntimeStartResult.Success,
            is RuntimeStartResult.Degraded -> reason
            is RuntimeStartResult.Failure -> reason ?: "${startResult.stage}:${startResult.message}"
        }
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE,
            status = status,
            reason = resolvedReason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME, status, resolvedReason)
    }

    fun recordDiagnostics(status: ScenarioOutcomeStatus, reason: String? = null) {
        harness.recordStageOutcome(
            stage = DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING,
            status = status,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.DIAGNOSTICS, status, reason)
    }

    fun recordTakeover(status: ScenarioOutcomeStatus, reason: String? = null) {
        harness.recordVerificationHook(
            kind = DualRepoE2EVerificationHookKind.STATE_CORRELATED,
            status = status,
            reason = reason
        )
        setFlowOutcome(AndroidCrossRepoRegressionFlow.TAKEOVER, status, reason)
    }

    /**
     * Semantic alias for ownership-transfer lifecycle reporting.
     *
     * The underlying dual-runtime harness signal is still the canonical TAKEOVER flow.
     */
    fun recordOwnershipTransfer(status: ScenarioOutcomeStatus, reason: String? = null) {
        recordTakeover(status = status, reason = reason)
    }

    fun recordMesh(status: ScenarioOutcomeStatus, reason: String? = null) {
        setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, status, reason)
        if (status == ScenarioOutcomeStatus.PASSED) {
            harness.recordStageOutcome(
                stage = DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING,
                status = ScenarioOutcomeStatus.PASSED,
                reason = reason
            )
        }
    }

    /**
     * Records the latest structured mesh lifecycle emission state for a parallel subtask run.
     *
     * The provided [state] is preserved in the exported regression snapshot and is also used
     * to derive a concrete mesh flow verdict when join/result/leave emission attempts fail or
     * when the full lifecycle closes successfully.
     */
    fun recordMeshLifecycle(state: AndroidMeshLifecycleEmissionChain.SessionState) {
        meshLifecycleState = state
        when {
            state.leaveAttempted && !state.leaveEmitted ->
                setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, ScenarioOutcomeStatus.FAILED, state.leaveReason ?: "mesh_leave_not_emitted")
            state.resultAttempted && !state.resultEmitted ->
                setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, ScenarioOutcomeStatus.FAILED, "mesh_result_not_emitted")
            state.joinAttempted && !state.joinEmitted ->
                setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, ScenarioOutcomeStatus.FAILED, "mesh_join_not_emitted")
            state.leaveEmitted && state.resultEmitted && state.joinEmitted ->
                setFlowOutcome(AndroidCrossRepoRegressionFlow.MESH, ScenarioOutcomeStatus.PASSED)
        }
    }

    fun buildSnapshot(nowMs: Long = System.currentTimeMillis()): AndroidCrossRepoRegressionSnapshot =
        AndroidCrossRepoRegressionSnapshot(
            e2eReport = harness.buildReport(nowMs = nowMs),
            flowOutcomes = flowOutcomes.toMap(),
            flowReasons = flowReasons.toMap(),
            meshLifecycleState = meshLifecycleState,
            reportedAtMs = nowMs
        )

    private fun setFlowOutcome(
        flow: AndroidCrossRepoRegressionFlow,
        status: ScenarioOutcomeStatus,
        reason: String? = null
    ) {
        val existing = flowOutcomes[flow]
        if (existing == ScenarioOutcomeStatus.FAILED && status == ScenarioOutcomeStatus.PASSED) {
            return
        }
        flowOutcomes[flow] = status
        if (!reason.isNullOrBlank()) {
            flowReasons[flow] = reason
        } else if (status == ScenarioOutcomeStatus.PASSED) {
            flowReasons.remove(flow)
        }
    }
}
