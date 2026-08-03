package com.ufo.galaxy.service.handler

import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DeviceAcceptanceReportPayload
import com.ufo.galaxy.protocol.DeviceGovernanceReportPayload
import com.ufo.galaxy.protocol.DeviceReadinessReportPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.protocol.DeviceStrategyReportPayload
import com.ufo.galaxy.shared.protocol.MsgType
import com.ufo.galaxy.runtime.AndroidGovernanceExecutionPolicyIngressContract
import com.ufo.galaxy.runtime.AndroidNonClosureSignalBoundaryContract
import com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimeGovernanceSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimePostGraduationGovernanceEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeReadinessSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimeStrategyEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeStrategySnapshot
import com.ufo.galaxy.runtime.NativeInferenceLoader
import com.ufo.galaxy.runtime.RuntimeStateTruthSequencer
import com.ufo.galaxy.transport.AipTransportManager

/**
 * SECURITY-FIX (P2): Extracted from GalaxyConnectionService to reduce class size.
 * Handles all device-state reporting: readiness, state snapshots, governance,
 * acceptance, and strategy reports.
 */
class StateHandler(
    private val gson: Gson,
    private val transportManager: AipTransportManager,
    private val runtimeStateTruthSequencer: RuntimeStateTruthSequencer,
    private val delegatedRuntimeReadinessEvaluator: DelegatedRuntimeReadinessEvaluator,
    private val buildIdempotencyKey: (taskId: String, type: MsgType, traceId: String?) -> String,
    private val localDeviceId: String
) {
    companion object {
        private const val TAG = "GalaxyConnectionService:StateHandler"
    }

    // Local evaluators for governance / acceptance / strategy artifacts. These mirror the
    // no-arg evaluators used by GalaxyConnectionService and produce a baseline (all-UNKNOWN)
    // snapshot when no dimension observations have been recorded yet.
    private val delegatedRuntimeGovernanceEvaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()
    private val delegatedRuntimeAcceptanceEvaluator = DelegatedRuntimeAcceptanceEvaluator()
    private val delegatedRuntimeStrategyEvaluator = DelegatedRuntimeStrategyEvaluator()

    // ── Device Readiness Report ───────────────────────────────────────────────

    fun sendDeviceReadinessReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_READINESS_REPORT] skipped — localDeviceId blank")
                return
            }
            val snapshot = delegatedRuntimeReadinessEvaluator.buildSnapshot(deviceId = deviceId)
            val artifact = snapshot.artifact

            val dimensionStates: Map<String, String> = snapshot.dimensionStates.entries
                .associate { (dim, state) -> dim.wireValue to state.status.wireValue }

            val missingDimensions: List<String> =
                snapshot.dimensionStates.entries
                    .filter { (_, state) ->
                        state.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.UNKNOWN
                    }
                    .map { (dim, _) -> dim.wireValue }

            val firstGapReason: String? =
                snapshot.dimensionStates.values
                    .firstOrNull { it.status == DelegatedRuntimeReadinessSnapshot.DimensionStatus.GAP }
                    ?.gapReason

            val ingress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_READINESS_REPORT)

            val payload = DeviceReadinessReportPayload(
                artifact_tag = artifact.semanticTag,
                snapshot_id = snapshot.snapshotId,
                device_id = deviceId,
                session_id = UFOGalaxyApplication.runtimeSessionId,
                reported_at_ms = snapshot.reportedAtMs,
                dimension_states = dimensionStates,
                first_gap_reason = firstGapReason,
                missing_dimensions = missingDimensions,
                ingress_boundary_class = ingress.boundaryClass.wireValue,
                ingress_consumption_kind = ingress.consumptionKind.wireValue,
                ingress_signal_class = ingress.signalClass.wireValue,
                ingress_schema_version = ingress.schemaVersion,
                non_closure_signal_class = AndroidNonClosureSignalBoundaryContract
                    .classify(MsgType.DEVICE_READINESS_REPORT)?.wireValue ?: "unknown",
                non_closure_schema_version = AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_READINESS_REPORT,
                payload = gson.toJsonTree(payload),
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_READINESS_REPORT, null)
            )

            val sent = transportManager.sendJson(gson.toJson(envelope))
            Log.i(TAG, "[DEVICE_READINESS_REPORT] sent snapshot_id=${snapshot.snapshotId} sent=$sent")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_readiness_report_emitted",
                    "snapshot_id" to snapshot.snapshotId,
                    "artifact_tag" to artifact.semanticTag,
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_READINESS_REPORT] error: ${e.message}", e)
        }
    }

    // ── Device State Snapshot ─────────────────────────────────────────────────

    fun sendDeviceStateSnapshot() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] skipped — localDeviceId blank")
                return
            }
            val settings = UFOGalaxyApplication.appSettings
            val snapshotStamp = runtimeStateTruthSequencer.nextSnapshotStamp()

            val llamaCppAvailable = NativeInferenceLoader.isLlamaCppAvailable()
            val ncnnAvailable = NativeInferenceLoader.isNcnnAvailable()

            val activeRuntimeType: String = when {
                llamaCppAvailable && ncnnAvailable -> "HYBRID"
                llamaCppAvailable -> "LLAMA_CPP"
                ncnnAvailable -> "NCNN"
                else -> "CENTER"
            }

            val managerState = UFOGalaxyApplication.localInferenceRuntimeManager.state.value
            val warmupResult: String = when (managerState) {
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running -> "ok"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded -> "degraded"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup -> "failed"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed -> "failed"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Starting -> "not_started"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Recovering -> "not_started"
                else -> "unavailable"
            }

            val modelReady = settings.modelReady
            val accessibilityReady = settings.accessibilityReady
            val overlayReady = settings.overlayReady

            val loopReadiness = try {
                UFOGalaxyApplication.localLoopReadinessProvider.getReadiness()
            } catch (e: Exception) {
                Log.d(TAG, "[DEVICE_STATE_SNAPSHOT] could not read loop readiness: ${e.message}")
                null
            }
            val localLoopReady = loopReadiness?.isFullyReady ?: false
            val degradedReasons: List<String> = loopReadiness?.blockers?.map { it.name } ?: emptyList()

            val assetManager = UFOGalaxyApplication.modelAssetManager
            val modelStatuses = try { assetManager.verifyAll() } catch (e: Exception) { emptyMap() }
            val mobilevlmStatus = modelStatuses[com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_VLM]
            val seeClickStatus = modelStatuses[com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_VLM_MMPROJ]
            val mobilevlmPresent = mobilevlmStatus != null &&
                mobilevlmStatus != com.ufo.galaxy.model.ModelAssetManager.ModelStatus.MISSING
            val seeClickPresent = seeClickStatus != null &&
                seeClickStatus != com.ufo.galaxy.model.ModelAssetManager.ModelStatus.MISSING
            val anyModelReady = modelStatuses.values.any {
                it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.READY ||
                    it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.LOADED
            }
            val pendingFirstDownload = !anyModelReady

            val offlineQueueDepth = UFOGalaxyApplication.webSocketClient.queueSize.value

            val rolloutSnapshot = UFOGalaxyApplication.runtimeController.rolloutControlSnapshot.value
            val currentFallbackTier: String = when {
                !rolloutSnapshot.goalExecutionAllowed -> "no_execution"
                !rolloutSnapshot.crossDeviceAllowed && !rolloutSnapshot.delegatedExecutionAllowed -> "local_only"
                !rolloutSnapshot.delegatedExecutionAllowed -> "local_only"
                rolloutSnapshot.fallbackToLocalAllowed -> "center_delegated_with_local_fallback"
                else -> "center_delegated"
            }

            val localLoopConfigMap: Map<String, Any>? = try {
                UFOGalaxyApplication.localLoopConfig?.let { cfg ->
                    mapOf(
                        "max_steps" to cfg.maxSteps,
                        "max_retries_per_step" to cfg.maxRetriesPerStep,
                        "step_timeout_ms" to cfg.stepTimeoutMs,
                        "goal_timeout_ms" to cfg.goalTimeoutMs,
                        "enable_planner_fallback" to cfg.fallback.enablePlannerFallback,
                        "enable_grounding_fallback" to cfg.fallback.enableGroundingFallback,
                        "enable_remote_handoff" to cfg.fallback.enableRemoteHandoff
                    )
                }
            } catch (e: Exception) { null }

            val payload = DeviceStateSnapshotPayload(
                device_id = deviceId,
                snapshot_ts = snapshotStamp.timestampMs,
                snapshot_sequence = snapshotStamp.snapshotSequence,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                active_runtime_type = activeRuntimeType,
                warmup_result = warmupResult,
                model_ready = modelReady,
                accessibility_ready = accessibilityReady,
                overlay_ready = overlayReady,
                local_loop_ready = localLoopReady,
                degraded_reasons = degradedReasons,
                model_id = null,
                runtime_type = null,
                checksum_ok = null,
                mobilevlm_present = mobilevlmPresent,
                mobilevlm_checksum_ok = null,
                seeclick_present = seeClickPresent,
                pending_first_download = pendingFirstDownload,
                offline_queue_depth = offlineQueueDepth,
                current_fallback_tier = currentFallbackTier,
                active_execution_count = snapshotStamp.activeExecutionCount,
                execution_busy = snapshotStamp.executionBusy,
                local_loop_config = localLoopConfigMap,
                llama_cpp_available = llamaCppAvailable,
                ncnn_available = ncnnAvailable
            )

            val snapshotKey = snapshotStamp.snapshotSequence.toString()
            val envelope = AipMessage(
                type = MsgType.DEVICE_STATE_SNAPSHOT,
                payload = gson.toJsonTree(payload),
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshotKey, MsgType.DEVICE_STATE_SNAPSHOT, null)
            )

            val sent = transportManager.sendJson(gson.toJson(envelope))
            Log.i(TAG, "[DEVICE_STATE_SNAPSHOT] sent snapshot_sequence=${snapshotStamp.snapshotSequence} sent=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_STATE_SNAPSHOT] error: ${e.message}", e)
        }
    }

    // ── Device Governance Report ──────────────────────────────────────────────

    fun sendDeviceGovernanceReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: return
            val snapshot = delegatedRuntimeGovernanceEvaluator.buildSnapshot(deviceId = deviceId)
            val artifact = snapshot.artifact

            val dimensionStates: Map<String, String> = snapshot.dimensionStates.entries
                .associate { (dim, state) -> dim.wireValue to state.status.wireValue }

            val missingDimensions: List<String> =
                snapshot.dimensionStates.entries
                    .filter { (_, state) ->
                        state.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.UNKNOWN
                    }
                    .map { (dim, _) -> dim.wireValue }

            val firstRegressionReason: String? =
                snapshot.dimensionStates.values
                    .firstOrNull { it.status == DelegatedRuntimeGovernanceSnapshot.DimensionStatus.REGRESSION }
                    ?.regressionReason

            val ingress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_GOVERNANCE_REPORT)

            val payload = DeviceGovernanceReportPayload(
                artifact_tag = artifact.semanticTag,
                snapshot_id = snapshot.snapshotId,
                device_id = deviceId,
                session_id = UFOGalaxyApplication.runtimeSessionId,
                reported_at_ms = snapshot.reportedAtMs,
                dimension_states = dimensionStates,
                first_regression_reason = firstRegressionReason,
                missing_dimensions = missingDimensions,
                ingress_boundary_class = ingress.boundaryClass.wireValue,
                ingress_consumption_kind = ingress.consumptionKind.wireValue,
                ingress_signal_class = ingress.signalClass.wireValue,
                ingress_schema_version = ingress.schemaVersion,
                non_closure_signal_class = AndroidNonClosureSignalBoundaryContract
                    .classify(MsgType.DEVICE_GOVERNANCE_REPORT)?.wireValue ?: "unknown",
                non_closure_schema_version = AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_GOVERNANCE_REPORT,
                payload = gson.toJsonTree(payload),
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_GOVERNANCE_REPORT, null)
            )

            val sent = transportManager.sendJson(gson.toJson(envelope))
            Log.i(TAG, "[DEVICE_GOVERNANCE_REPORT] sent snapshot_id=${snapshot.snapshotId} sent=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_GOVERNANCE_REPORT] error: ${e.message}", e)
        }
    }

    // ── Device Acceptance Report ──────────────────────────────────────────────

    fun sendDeviceAcceptanceReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: return
            val snapshot = delegatedRuntimeAcceptanceEvaluator.buildSnapshot(deviceId = deviceId)
            val artifact = snapshot.artifact

            val dimensionStates: Map<String, String> = snapshot.dimensionStates.entries
                .associate { (dim, state) -> dim.wireValue to state.status.wireValue }

            val missingDimensions: List<String> =
                snapshot.dimensionStates.entries
                    .filter { (_, state) ->
                        state.status == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.UNKNOWN
                    }
                    .map { (dim, _) -> dim.wireValue }

            val firstGapReason: String? =
                snapshot.dimensionStates.values
                    .firstOrNull { it.status == DelegatedRuntimeAcceptanceSnapshot.DimensionStatus.GAP }
                    ?.gapReason

            val ingress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_ACCEPTANCE_REPORT)

            val payload = DeviceAcceptanceReportPayload(
                artifact_tag = artifact.semanticTag,
                snapshot_id = snapshot.snapshotId,
                device_id = deviceId,
                session_id = UFOGalaxyApplication.runtimeSessionId,
                reported_at_ms = snapshot.reportedAtMs,
                dimension_states = dimensionStates,
                first_gap_reason = firstGapReason,
                missing_dimensions = missingDimensions,
                ingress_boundary_class = ingress.boundaryClass.wireValue,
                ingress_consumption_kind = ingress.consumptionKind.wireValue,
                ingress_signal_class = ingress.signalClass.wireValue,
                ingress_schema_version = ingress.schemaVersion,
                non_closure_signal_class = AndroidNonClosureSignalBoundaryContract
                    .classify(MsgType.DEVICE_ACCEPTANCE_REPORT)?.wireValue ?: "unknown",
                non_closure_schema_version = AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_ACCEPTANCE_REPORT,
                payload = gson.toJsonTree(payload),
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_ACCEPTANCE_REPORT, null)
            )

            val sent = transportManager.sendJson(gson.toJson(envelope))
            Log.i(TAG, "[DEVICE_ACCEPTANCE_REPORT] sent snapshot_id=${snapshot.snapshotId} sent=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_ACCEPTANCE_REPORT] error: ${e.message}", e)
        }
    }

    // ── Device Strategy Report ────────────────────────────────────────────────

    fun sendDeviceStrategyReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: return
            val snapshot = delegatedRuntimeStrategyEvaluator.buildSnapshot(deviceId = deviceId)
            val artifact = snapshot.artifact

            val dimensionStates: Map<String, String> = snapshot.dimensionStates.entries
                .associate { (dim, state) -> dim.wireValue to state.status.wireValue }

            val missingDimensions: List<String> =
                snapshot.dimensionStates.entries
                    .filter { (_, state) ->
                        state.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.UNKNOWN
                    }
                    .map { (dim, _) -> dim.wireValue }

            val firstRiskReason: String? =
                snapshot.dimensionStates.values
                    .firstOrNull { it.status == DelegatedRuntimeStrategySnapshot.DimensionStatus.AT_RISK }
                    ?.riskReason

            val ingress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_STRATEGY_REPORT)

            val payload = DeviceStrategyReportPayload(
                artifact_tag = artifact.semanticTag,
                snapshot_id = snapshot.snapshotId,
                device_id = deviceId,
                session_id = UFOGalaxyApplication.runtimeSessionId,
                reported_at_ms = snapshot.reportedAtMs,
                dimension_states = dimensionStates,
                first_risk_reason = firstRiskReason,
                missing_dimensions = missingDimensions,
                ingress_boundary_class = ingress.boundaryClass.wireValue,
                ingress_consumption_kind = ingress.consumptionKind.wireValue,
                ingress_signal_class = ingress.signalClass.wireValue,
                ingress_schema_version = ingress.schemaVersion,
                non_closure_signal_class = AndroidNonClosureSignalBoundaryContract
                    .classify(MsgType.DEVICE_STRATEGY_REPORT)?.wireValue ?: "unknown",
                non_closure_schema_version = AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_STRATEGY_REPORT,
                payload = gson.toJsonTree(payload),
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_STRATEGY_REPORT, null)
            )

            val sent = transportManager.sendJson(gson.toJson(envelope))
            Log.i(TAG, "[DEVICE_STRATEGY_REPORT] sent snapshot_id=${snapshot.snapshotId} sent=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_STRATEGY_REPORT] error: ${e.message}", e)
        }
    }
}
