package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.DelegatedRuntimeReceiver
import com.ufo.galaxy.agent.DelegatedTakeoverExecutor
import com.ufo.galaxy.agent.GoalExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.HandoffContractValidator
import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.TaskCancelRegistry
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.agent.TakeoverResponseEnvelope
import com.ufo.galaxy.agent.TakeoverHandlingResult
import com.ufo.galaxy.runtime.AndroidContinuityIntegration
import com.ufo.galaxy.runtime.AndroidClosedLoopGovernanceContract
import com.ufo.galaxy.runtime.AndroidExecutionGovernanceContract
import com.ufo.galaxy.runtime.AndroidCanonicalRuntimeTruthContract
import com.ufo.galaxy.runtime.AndroidAuthoritativeParticipationTruth
import com.ufo.galaxy.runtime.AndroidMissionCompletionSemanticsContract
import com.ufo.galaxy.runtime.AndroidNlDrivenExecutionSpineContract
import com.ufo.galaxy.runtime.AndroidOperationalStateSurfaceContract
import com.ufo.galaxy.runtime.AndroidOperatorActionGovernanceContract
import com.ufo.galaxy.runtime.OperatorActionReceiver
import com.ufo.galaxy.runtime.AndroidTakeoverOwnershipTransferContract
import com.ufo.galaxy.runtime.AndroidTruthPublicationSemanticsContract
import com.ufo.galaxy.runtime.AndroidCrossRepoRegressionRuntimeHooks
import com.ufo.galaxy.runtime.AndroidMeshDirectRuntimeContract
import com.ufo.galaxy.runtime.AndroidRuntimeObservabilityAuditContract
import com.ufo.galaxy.runtime.AndroidDeviceSurfaceSourceContract
import com.ufo.galaxy.runtime.AndroidGovernanceExecutionPolicyIngressContract
import com.ufo.galaxy.runtime.AndroidUnifiedTruthUplinkContract
import com.ufo.galaxy.runtime.AndroidUnifiedParticipantLifecyclePhase
import com.ufo.galaxy.runtime.AndroidParticipationSemanticNormalizationContract
import com.ufo.galaxy.runtime.AndroidBoundaryReliabilityContract
import com.ufo.galaxy.runtime.AndroidCrossDeviceDispatchBoundaryContract
import com.ufo.galaxy.runtime.AndroidDistributedRuntimeParticipationBoundaryContract
import com.ufo.galaxy.runtime.AndroidDistributedTruthOwnershipUplinkContract
import com.ufo.galaxy.runtime.AndroidResultUplinkBoundaryContract
import com.ufo.galaxy.runtime.AndroidDiagnosticsFailureExplanationUplinkContract
import com.ufo.galaxy.runtime.AndroidCompletionClosureUplinkContract
import com.ufo.galaxy.runtime.AndroidToolActionAuthorizationUplinkContract
import com.ufo.galaxy.runtime.AndroidContinuityRecoveryStateModel
import com.ufo.galaxy.runtime.AndroidUplinkLineageMetadataContract
import com.ufo.galaxy.runtime.AndroidMeshLifecycleEmissionChain
import com.ufo.galaxy.runtime.FormalParticipantLifecycleState
import com.ufo.galaxy.runtime.LocalExecutionModeGate
import com.ufo.galaxy.runtime.LocalRecoveryDecision
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeReadinessSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimePostGraduationGovernanceEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeGovernanceSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceSnapshot
import com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceDimension
import com.ufo.galaxy.runtime.DelegatedRuntimeStrategyEvaluator
import com.ufo.galaxy.runtime.DelegatedRuntimeStrategySnapshot
import com.ufo.galaxy.runtime.PersistentEmittedSignalLedgerStore
import com.ufo.galaxy.runtime.PolicyRoutingContext
import com.ufo.galaxy.runtime.ReconciliationSignal
import com.ufo.galaxy.runtime.TakeoverFallbackEvent
import com.ufo.galaxy.runtime.toOutboundPayload
import com.ufo.galaxy.runtime.wireLabel
import com.ufo.galaxy.runtime.SourceRuntimePosture
import com.ufo.galaxy.runtime.LocalRuntimeContext
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import com.ufo.galaxy.runtime.RuntimeStateTruthSequencer
import com.ufo.galaxy.runtime.RealDeviceVerificationKind
import com.ufo.galaxy.runtime.ScenarioOutcomeStatus
import com.ufo.galaxy.session.DurableParticipantIdentity
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.AckPayload
import com.ufo.galaxy.protocol.CoordSyncAckPayload
import com.ufo.galaxy.protocol.CancelResultPayload
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.HybridDegradePayload
import com.ufo.galaxy.protocol.HybridExecutePayload
import com.ufo.galaxy.protocol.HybridResultPayload
import com.ufo.galaxy.protocol.MeshTopologyPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.MeshSubtaskResult
import com.ufo.galaxy.protocol.PeerExchangePayload
import com.ufo.galaxy.protocol.PeerAnnouncePayload
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskCancelPayload
import com.ufo.galaxy.protocol.HandoffEnvelopeV2ResultPayload
import com.ufo.galaxy.protocol.OperatorActionRequestPayload
import com.ufo.galaxy.protocol.OperatorActionResultPayload
import com.ufo.galaxy.protocol.ReconciliationSignalPayload
import com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment
import com.ufo.galaxy.protocol.DeviceReadinessReportPayload
import com.ufo.galaxy.protocol.DeviceGovernanceReportPayload
import com.ufo.galaxy.protocol.DeviceAcceptanceReportPayload
import com.ufo.galaxy.protocol.DeviceStrategyReportPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.runtime.BarrierCoordinationParticipant
import com.ufo.galaxy.runtime.CollaborationLifecycleState
import com.ufo.galaxy.runtime.DeviceExecutionEventSink
import com.ufo.galaxy.runtime.DevicePerceptionEmissionSink
import com.ufo.galaxy.runtime.HybridExecuteFullCoordinator
import com.ufo.galaxy.runtime.NativeInferenceLoader
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.memory.MemoryEntry
import com.ufo.galaxy.memory.OpenClawdMemoryBackflow
import com.ufo.galaxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * **Canonical inbound gateway-message dispatcher** for the cross-device pipeline.
 *
 * This Android [Service] owns the **inbound WebSocket message loop**: it registers a
 * [GalaxyWebSocketClient.Listener] and dispatches every inbound AIP v3 message to the
 * correct handler. It is the sole component that processes gateway-originated tasks on
 * behalf of this device.
 *
 * ## Ownership boundaries
 *  - **Runtime lifecycle**: all WS connect/disconnect and `crossDeviceEnabled` changes go
 *    through [com.ufo.galaxy.runtime.RuntimeController] (the sole lifecycle authority).
 *    This service calls [RuntimeController.connectIfEnabled] on start and never touches
 *    the WebSocket client directly for connection management.
 *  - **Inbound dispatch**: parses each [AipMessage] and routes by type:
 *    - `task_assign`      → [AgentRuntimeBridge.handoff] (when eligible) or [EdgeExecutor]
 *    - `goal_execution`   → [AutonomousExecutionPipeline.handleGoalExecution]
 *    - `parallel_subtask` → [AutonomousExecutionPipeline.handleParallelSubtask]
 *    - `task_cancel`      → [TaskCancelRegistry.cancel]
 *  - **Cancellation**: every goal/subtask coroutine is registered in [TaskCancelRegistry]
 *    so in-flight tasks can be cooperatively cancelled via `task_cancel`.
 *  - **Remote task handoff**: calls [RuntimeController.onRemoteTaskStarted] when a
 *    gateway task arrives (blocking the local [LoopController]) and
 *    [RuntimeController.onRemoteTaskFinished] when the result is sent back.
 *
 * On start: loads MobileVLM and SeeClick models via [GalaxyWebSocketClient.setModelCapabilities].
 * On destroy: unloads models and removes the WS listener.
 */
class GalaxyConnectionService : Service() {
    
    companion object {
        private const val TAG = "GalaxyConnectionService"
        private const val NOTIFICATION_ID = 1001
        const val ENTRYPOINT_ROLE = "main_entry"

        // ── Route-mode constants ──────────────────────────────────────────────
        /**
         * Route mode for gateway-delivered tasks (goal_execution, parallel_subtask, task_assign
         * via bridge). All results from these paths carry this value so the main-repo
         * session-truth layer can correlate results by route without re-parsing envelopes.
         *
         * Aliased from [AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE] to keep intra-class
         * usages concise and to document that this service always treats its inbound
         * gateway tasks as cross-device-routed.
         */
        const val ROUTE_MODE_CROSS_DEVICE = AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE

        // ── PR-3: Canonical takeover defaults ─────────────────────────────────
        /**
         * Default maximum number of goal-execution steps allowed inside a single
         * takeover session.  Consumers may override this per-request; this constant
         * acts as the safe fallback when no explicit limit is supplied.
         */
        const val TAKEOVER_DEFAULT_MAX_STEPS = 10

        /**
         * Default timeout in milliseconds for a single takeover session.
         * `0L` means "no timeout" — the takeover runs until it completes or is
         * cancelled externally.  Consumers may supply a positive value to impose a
         * wall-clock limit.
         */
        const val TAKEOVER_DEFAULT_TIMEOUT_MS = 0L

        // ── PR-04: Fallback tier wire values ──────────────────────────────────
        // Used in sendDeviceStateSnapshot() to derive mesh participation fallback state.
        private const val FALLBACK_TIER_CENTER_DELEGATED_WITH_LOCAL = "center_delegated_with_local_fallback"
        private const val FALLBACK_TIER_ACTIVE = "active"
        private const val BARRIER_SESSION_PREFIX_HYBRID = "hybrid"
        private const val DELEGATED_SIGNAL_ATTEMPT_INITIAL_CAPACITY = 512
        private const val DELEGATED_SIGNAL_ATTEMPT_MAX_ENTRIES = 2048
        private const val SIGNAL_ATTEMPT_FIRST = 1

        fun createMainEntryIntent(context: Context): Intent =
            Intent(context, GalaxyConnectionService::class.java)
    }
    
    private val binder = LocalBinder()
    private lateinit var webSocketClient: GalaxyWebSocketClient
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeStateTruthSequencer = RuntimeStateTruthSequencer()
    private val barrierCoordinationParticipant = BarrierCoordinationParticipant()
    private val meshRuntimeSignalLock = Any()
    @Volatile
    private var meshCollaborationLifecycleState: CollaborationLifecycleState =
        CollaborationLifecycleState.IDLE
    @Volatile
    private var meshRuntimeParticipationActive: Boolean = false

    /** Stable device identifier used in all outbound AIP v3 message envelopes. */
    private val localDeviceId: String by lazy {
        "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
    }

    private val hybridExecuteCoordinator: HybridExecuteFullCoordinator by lazy {
        HybridExecuteFullCoordinator(
            localExecutor = HybridExecuteFullCoordinator.LocalStepExecutor { payload ->
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            },
            deviceId = localDeviceId
        )
    }

    private fun updateMeshRuntimeSignalState(
        collaborationState: CollaborationLifecycleState? = null,
        participationActive: Boolean? = null
    ) {
        synchronized(meshRuntimeSignalLock) {
            collaborationState?.let { meshCollaborationLifecycleState = it }
            participationActive?.let { meshRuntimeParticipationActive = it }
        }
    }

    private fun readMeshRuntimeSignalState(): Pair<CollaborationLifecycleState, Boolean> =
        synchronized(meshRuntimeSignalLock) {
            meshCollaborationLifecycleState to meshRuntimeParticipationActive
        }

    /** Tracks active goal_execution / parallel_subtask coroutine jobs for cancel support. */
    private val taskCancelRegistry = TaskCancelRegistry()
    @Volatile
    private var hasObservedWsConnection = false

    private val crossRepoRegressionHooks: AndroidCrossRepoRegressionRuntimeHooks by lazy {
        val participantId = UFOGalaxyApplication.appSettings.durableParticipantId
            .takeIf { it.isNotBlank() }
            ?: localDeviceId
        AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = localDeviceId,
            participantId = participantId,
            verificationKind = detectVerificationKind()
        )
    }

    // ── PR-3: Canonical takeover state ────────────────────────────────────────

    /**
     * The `takeover_id` of the takeover request currently being processed, or `null`
     * when no takeover is active.  Written on the service's IO dispatcher; volatile
     * for safe reads from any thread.
     *
     * Used by [TakeoverEligibilityAssessor] to block concurrent takeovers: the main
     * runtime must receive a [TakeoverResponseEnvelope] (accepted or rejected) before
     * a new [MsgType.TAKEOVER_REQUEST] will be accepted.
     */
    @Volatile
    private var activeTakeoverId: String? = null
    private val activeTakeoverLock = Any()
    private val operatorRecoveryActionMutex = Mutex()
    private val delegatedSignalAttemptLock = Any()
    private val delegatedSignalAttemptCounts: LinkedHashMap<String, Int> =
        object : LinkedHashMap<String, Int>(DELEGATED_SIGNAL_ATTEMPT_INITIAL_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
                size > DELEGATED_SIGNAL_ATTEMPT_MAX_ENTRIES
        }

    private fun currentActiveTakeoverId(): String? =
        synchronized(activeTakeoverLock) { activeTakeoverId }

    private fun updateActiveTakeoverId(value: String?) {
        synchronized(activeTakeoverLock) {
            activeTakeoverId = value
        }
    }

    private fun currentGovernanceTruth(
        activeTaskId: String? = UFOGalaxyApplication.runtimeController.activeTaskId,
        activeTakeoverId: String? = currentActiveTakeoverId()
    ): AndroidUnifiedTruthUplinkContract.GovernanceTruth {
        val runtimeController = UFOGalaxyApplication.runtimeController
        val eligibilityContext = runtimeController.buildOperatorActionEligibilityContext(activeTakeoverId)
        return AndroidUnifiedTruthUplinkContract.deriveGovernanceTruth(
            crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
            sessionAttached = runtimeController.attachedSession.value?.isAttached == true,
            activeTaskId = activeTaskId,
            activeTakeoverId = activeTakeoverId,
            operatorSuspendedOrIsolated = eligibilityContext.operatorSuspendedOrIsolated
        )
    }

    private fun deriveTruthOwnershipBoundary(
        executionBusy: Boolean,
        sourceRuntimePosture: String,
        takeoverActive: Boolean,
        sessionId: String?,
        isSessionRecoveryActive: Boolean,
        isCapabilityDegraded: Boolean,
        isRecoveryActive: Boolean,
        isDiagnosticsSignal: Boolean,
        isOperatorVisibleSummary: Boolean,
        isHandoffInitiator: Boolean = false,
        isOwnershipReturnPending: Boolean = false
    ): AndroidDistributedTruthOwnershipUplinkContract.TruthOwnershipUplinkSnapshot =
        AndroidDistributedTruthOwnershipUplinkContract.derive(
            AndroidDistributedTruthOwnershipUplinkContract.TruthOwnershipUplinkDerivationInput(
                executionBusy = executionBusy,
                crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                sourceRuntimePosture = SourceRuntimePosture.fromValue(sourceRuntimePosture),
                takeoverActive = takeoverActive,
                isHandoffInitiator = isHandoffInitiator,
                isOwnershipReturnPending = isOwnershipReturnPending,
                sessionId = sessionId,
                isSessionRecoveryActive = isSessionRecoveryActive,
                isCapabilityDegraded = isCapabilityDegraded,
                isRecoveryActive = isRecoveryActive,
                isDiagnosticsSignal = isDiagnosticsSignal,
                isOperatorVisibleSummary = isOperatorVisibleSummary
            )
        )

    private fun clearActiveTakeoverIdIfMatches(expectedTakeoverId: String) {
        synchronized(activeTakeoverLock) {
            if (activeTakeoverId == expectedTakeoverId) {
                activeTakeoverId = null
            }
        }
    }

    private fun getAndIncrementDelegatedSignalAttempt(signalId: String): Int =
        synchronized(delegatedSignalAttemptLock) {
            val next = (delegatedSignalAttemptCounts[signalId] ?: 0) + 1
            delegatedSignalAttemptCounts[signalId] = next
            next
        }

    private fun delegatedSignalAttempt(signal: DelegatedExecutionSignal): Int =
        if (signal.isResult) getAndIncrementDelegatedSignalAttempt(signal.signalId) else SIGNAL_ATTEMPT_FIRST

    private fun resolveExecutionTraceId(inboundTraceId: String?): String =
        inboundTraceId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()

    private fun detectVerificationKind(): RealDeviceVerificationKind {
        val fingerprint = android.os.Build.FINGERPRINT?.lowercase().orEmpty()
        val model = android.os.Build.MODEL?.lowercase().orEmpty()
        val isEmulator = fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("sdk_gphone") ||
            model.contains("emulator")
        return if (isEmulator) RealDeviceVerificationKind.EMULATOR else RealDeviceVerificationKind.REAL_DEVICE
    }

    private fun emitCrossRepoRegressionSnapshot(event: String) {
        val snapshot = crossRepoRegressionHooks.buildSnapshot()
        GalaxyLogger.log(
            TAG,
            snapshot.toWireMap() + mapOf("event" to event)
        )
    }

    /** Canonical assessor that evaluates takeover eligibility based on device readiness. */
    private val takeoverEligibilityAssessor: TakeoverEligibilityAssessor by lazy {
        TakeoverEligibilityAssessor(UFOGalaxyApplication.appSettings)
    }

    /**
     * Canonical gate for delegated runtime receipt under an attached session (PR-8).
     *
     * Called inside [handleTakeoverRequest] after [TakeoverEligibilityAssessor] confirms
     * device readiness, to enforce that delegated work is accepted only when an explicit
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] is active.
     */
    private val delegatedRuntimeReceiver = DelegatedRuntimeReceiver()
    private val handoffContractValidator = HandoffContractValidator()

    /**
     * Canonical continuity authority gate for online execution (PR: unified result + continuity).
     *
     * Called inside [handleTaskAssign], [handleGoalExecution], [handleParallelSubtask],
     * [handleTakeoverRequest], and [handleHandoffEnvelopeV2] to block stale-session or
     * stale-identity requests before execution begins.  This ensures the online execution path
     * and the offline-replay path both enforce the same
     * [com.ufo.galaxy.runtime.AttachedRuntimeSession] continuity contract.
     */
    private val continuityIntegration = AndroidContinuityIntegration()

    /**
     * Evaluator for Android delegated-runtime readiness (Android-side signal closure).
     *
     * Holds per-dimension gate state and produces [com.ufo.galaxy.runtime.DeviceReadinessArtifact]
     * / [com.ufo.galaxy.runtime.DelegatedRuntimeReadinessSnapshot] outputs that are forwarded to
     * V2 via [sendDeviceReadinessReport].
     *
     * Dimension states are populated during runtime lifecycle events:
     *  - Service start / capability_report handshake — an initial report is emitted so V2 has
     *    a baseline Android-side readiness signal immediately on connection.
     *  - Any subsequent dimension change may trigger a follow-up report as needed.
     */
    private val delegatedRuntimeReadinessEvaluator = DelegatedRuntimeReadinessEvaluator()

    /**
     * Evaluator for Android delegated-runtime post-graduation governance (PR-4 artifact emission).
     *
     * Holds per-dimension observation state and produces
     * [com.ufo.galaxy.runtime.DeviceGovernanceArtifact] /
     * [com.ufo.galaxy.runtime.DelegatedRuntimeGovernanceSnapshot] outputs forwarded to V2 via
     * [sendDeviceGovernanceReport].
     *
     * All dimensions start as UNKNOWN at service start, which is itself a valid structured
     * signal — V2 can distinguish "not yet observed" from "regression detected".
     */
    private val delegatedRuntimeGovernanceEvaluator = DelegatedRuntimePostGraduationGovernanceEvaluator()

    /**
     * Evaluator for Android delegated-runtime final acceptance / graduation (PR-4 artifact emission).
     *
     * Holds per-dimension evidence state and produces
     * [com.ufo.galaxy.runtime.DeviceAcceptanceArtifact] /
     * [com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceSnapshot] outputs forwarded to V2 via
     * [sendDeviceAcceptanceReport].
     *
     * All dimensions start as UNKNOWN at service start, which is itself a valid structured
     * signal — V2 can distinguish "not yet evidenced" from "evidence gap".
     */
    private val delegatedRuntimeAcceptanceEvaluator = DelegatedRuntimeAcceptanceEvaluator()

    /**
     * Evaluator for Android delegated-runtime program strategy / evolution posture
     * (PR-4 artifact emission).
     *
     * Holds per-dimension posture state and produces
     * [com.ufo.galaxy.runtime.DeviceStrategyArtifact] /
     * [com.ufo.galaxy.runtime.DelegatedRuntimeStrategySnapshot] outputs forwarded to V2 via
     * [sendDeviceStrategyReport].
     *
     * Strategy artifacts are advisory/observation-only by default — V2 retains full
     * orchestration authority over program strategy decisions.
     * All dimensions start as UNKNOWN at service start.
     */
    private val delegatedRuntimeStrategyEvaluator = DelegatedRuntimeStrategyEvaluator()

    /**
     * Signal sink for delegated-execution lifecycle events (PR-12 / PR-16).
     *
     * Receives [com.ufo.galaxy.runtime.DelegatedExecutionSignal] events (ACK / PROGRESS /
     * RESULT / TIMEOUT / CANCELLED) emitted by [delegatedTakeoverExecutor] and:
     *  1. Writes them to structured telemetry via [GalaxyLogger] (observability).
     *  2. Transmits each signal as a [com.ufo.galaxy.protocol.MsgType.DELEGATED_EXECUTION_SIGNAL]
     *     AIP v3 message uplink via [webSocketClient] (PR-16 transport closure).
     *
     * Signal send failure never interrupts the delegated execution lifecycle — any error
     * is caught internally and logged for diagnostics observability.
     */
    private val delegatedSignalSink = DelegatedExecutionSignalSink { signal ->
        GalaxyLogger.log(TAG, signal.toMetadataMap())
        sendDelegatedExecutionSignal(signal)
    }

    // ── PR-2 (Android): Device execution-event sink ───────────────────────────

    /**
     * Sink for real Android execution-phase events on the canonical Android→V2 path (PR-2).
     *
     * Receives [DeviceExecutionEventPayload] events from the execution lifecycle entry points
     * in this service ([handleGoalExecution], [executeLocalTaskAssign], [handleParallelSubtask],
     * [handleHandoffEnvelopeV2], [handleTakeoverRequest]), logs each event via
     * [GalaxyLogger.TAG_DEVICE_EXECUTION_EVENT], and transmits it as a
     * [MsgType.DEVICE_EXECUTION_EVENT] AIP v3 uplink message via [webSocketClient].
     *
     * Send failure **never interrupts the execution lifecycle** — any error is caught
     * inside [webSocketClient.sendDeviceExecutionEvent] and logged for diagnostics.
     */
    private val deviceExecutionEventSink = DeviceExecutionEventSink { payload ->
        try {
            val settings = UFOGalaxyApplication.appSettings
            val modeState = settings.authoritativeModeState(
                wsConnected = webSocketClient.isConnected(),
                capabilityDegraded = managerStateDegraded()
            )
            val runtimeController = UFOGalaxyApplication.runtimeController
            val dispatchReadiness = runtimeController.currentDispatchReadiness()
            val distributedRuntimeActivity =
                isDistributedParticipationActivePhase(payload.phase)
            val governanceTruth = currentGovernanceTruth()
            val participationSnapshot = runtimeController.evaluateAuthoritativeParticipationSnapshot(
                readinessSatisfied = modeState.crossDeviceEligibility,
                distributedRuntimeActivity = distributedRuntimeActivity,
                capabilityVisible = hasVisibleCrossDeviceCapability(
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    sessionIsAttached = dispatchReadiness.sessionIsAttached
                )
            )
            val eventStamp = runtimeStateTruthSequencer.nextEventStamp(
                phase = payload.phase,
                requestedTimestampMs = payload.timestamp_ms,
                taskId = payload.task_id
            )
            val completionVisibility =
                AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
                    phase = payload.phase,
                    lifecycleTerminalPhase = eventStamp.lifecycleTerminalPhase
                )
            val reconnectState = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value.wireValue
            val eventOfflineQueueDepth = offlineTaskQueue.queueDepth()
            val eventDegradedReasons = if (managerStateDegraded()) {
                listOf("runtime_manager_degraded")
            } else {
                emptyList()
            }
            val eventSemanticProjection = AndroidCanonicalRuntimeTruthContract
                .deriveEventSemanticProjection(
                    reportedStateSemanticClassWire = payload.reported_state_semantic_class,
                    isTerminalLifecyclePhase = eventStamp.lifecycleTerminalPhase == true,
                    carrierRuntimeState = payload.carrier_runtime_state
                        ?: UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    reconnectRecoveryState = reconnectState,
                    executionModeState = modeState.executionModeState,
                    executionBusy = eventStamp.executionBusy,
                    carrierForegroundVisible = payload.carrier_foreground_visible
                        ?: runtimeController.appForegroundVisible.value,
                    plannerFallbackTier = payload.fallback_tier,
                    groundingFallbackTier = null,
                    degradedReasons = eventDegradedReasons,
                    currentFallbackTier = payload.fallback_tier,
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    offlineQueueDepth = eventOfflineQueueDepth,
                    crossDeviceEnabled = settings.crossDeviceEnabled,
                    wsConnected = webSocketClient.isConnected()
                )
            val eventConstraintSem = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
                isConstrained = eventSemanticProjection.degradedConditionClass ==
                    AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED,
                isDeferred = eventSemanticProjection.degradedConditionClass ==
                    AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED,
                isLocalModeGateHold = modeState.isHoldState,
                isExecutionBusy = eventStamp.executionBusy == true,
                isHold = false
            )
            // ── PR-3Android: 执行事件参与语义规范化（预计算，避免 copy() 中三次重复 derive() 调用）──
            // localCapabilityStateWire 从 LocalCapabilityState.derive() 在事件发射时计算，
            // 确保降级/不可用路径在执行事件的参与模式分类中同样被正确检测，
            // 与 sendDeviceStateSnapshot() 的 snapshotLocalCapState 逻辑保持一致。
            val eventLocalLlmReady = localLlmReady()
            val eventLocalCapState = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
                localLlmReady = eventLocalLlmReady,
                localInferenceAvailable = localInferenceAvailable(),
                accessibilityReady = UFOGalaxyApplication.appSettings.accessibilityReady,
                isDegraded = managerStateDegraded()
            )
            val meshDirectSnapshot = currentMeshDirectRuntimeSnapshotForEvent(payload)
            val eventNormalization = AndroidParticipationSemanticNormalizationContract.derive(
                AndroidParticipationSemanticNormalizationContract.NormalizationDerivationInput(
                    localModeActive = modeState.executionModeState ==
                        LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                    executionBusy = eventStamp.executionBusy == true,
                    distributedParticipant = AndroidAuthoritativeParticipationTruth
                        .participationTierFor(participationSnapshot.state) ==
                        AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT,
                    delegatedExecutionActive = governanceTruth.delegated_execution_active,
                    takeoverStateWire = governanceTruth.takeover_state,
                    runtimeConstrained = eventConstraintSem.isConstraint,
                    runtimeDeferred = eventConstraintSem.isDeferred,
                    governanceBlocked = governanceTruth.governance_blocked,
                    crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                    dispatchEligible = modeState.crossDeviceEligibility == true,
                    localCapabilityStateWire = eventLocalCapState.wireValue
                )
            )

            // ── PR-02v2 (Android): 预先推导执行事件级 dispatch 边界收束快照 ─────────────────────
            // 在 payload.copy() 前唯一计算，使 V2 可将执行事件关联到正确的 dispatch 边界类型。
            //
            // 执行事件级推导说明：
            //   isLegacyCompatRemapped: 通过 phase == PHASE_FALLBACK_TRANSITION 近似检测
            //     （compat 映射消息触发 fallback_transition 阶段）
            //   isAgentBridgeCompatEntry: 通过 fallback_tier 非空判断
            //     （AgentRuntimeBridge compat 入口通常携带有效的 fallback_tier）
            //   isAgentBridgeFallback: 通过 phase == PHASE_FALLBACK_TRANSITION 检测
            //   isLegacyBypassEntry: 快照和事件均无法从发射层检测，默认 false
            val eventIsFallbackTransition = payload.phase == DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION
            val eventExecutionPathTag = AndroidRuntimeObservabilityAuditContract.classifyExecutionPath(
                spineParticipationKind = null,
                executionModeState = modeState.executionModeState,
                crossDeviceEligibility = modeState.crossDeviceEligibility,
                isTakeoverPath = payload.phase == DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                isDelegated = modeState.crossDeviceEligibility == true &&
                    payload.phase != DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                currentFallbackTier = payload.fallback_tier
            )
            val eventDispatchBoundary = AndroidCrossDeviceDispatchBoundaryContract.derive(
                AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryDerivationInput(
                    isCrossDeviceMode = UFOGalaxyApplication.appSettings.crossDeviceEnabled &&
                        modeState.executionModeState !=
                        LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                    executionPathTag = eventExecutionPathTag,
                    isFallbackTierActive = payload.fallback_tier != null,
                    isAgentBridgeFallback = eventIsFallbackTransition,
                    isLegacyCompatRemapped = eventIsFallbackTransition && payload.fallback_tier == null,
                    isAgentBridgeCompatEntry = !eventIsFallbackTransition && payload.fallback_tier != null,
                    isLegacyBypassEntry = false,
                    hasDelegatedOrTakeoverExecution = governanceTruth.delegated_execution_active == true ||
                        governanceTruth.takeover_state == AndroidUnifiedTruthUplinkContract.TAKEOVER_STATE_ACTIVE
                )
            )
            // ── PR-05v2 (Android): 预先推导执行事件级结果上行闭环边界快照 ─────────────────────────
            // 在 payload.copy() 前唯一计算，避免在 copy() 内重复调用 derive()。
            val eventUplinkBoundary = AndroidResultUplinkBoundaryContract.derive(
                AndroidResultUplinkBoundaryContract.UplinkBoundaryDerivationInput(
                    isTerminalPhase = eventStamp.lifecycleTerminalPhase == true,
                    resultReturned = completionVisibility.resultReturned,
                    completionSignaled = completionVisibility.completionSignaled,
                    closureReadyForAcceptance = completionVisibility.closureReadyForAcceptance,
                    isGovernanceBlocked = governanceTruth.governance_blocked == true,
                    isRuntimeConstrained = false,
                    isHoldState = false
                )
            )
            val eventSemanticBoundary = AndroidDiagnosticsFailureExplanationUplinkContract
                .forExecutionEvent(
                    lifecycleTerminalPhase = eventStamp.lifecycleTerminalPhase == true,
                    resultSignalClass = eventUplinkBoundary.resultSignalClass,
                    sourceComponent = payload.source_component,
                    executionModeStateWire = modeState.executionModeState
                )
            val eventIngress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_EXECUTION_EVENT)
            val eventCompletionClosureBoundary = AndroidCompletionClosureUplinkContract
                .deriveForExecutionEvent(
                    isLifecycleTerminalPhase = eventStamp.lifecycleTerminalPhase == true,
                    resultSignalClass = eventUplinkBoundary.resultSignalClass,
                    acceptanceCandidateClass = eventUplinkBoundary.acceptanceCandidateClass,
                    resultReturned = completionVisibility.resultReturned,
                    completionSignaled = completionVisibility.completionSignaled,
                    closureReadyForAcceptance = completionVisibility.closureReadyForAcceptance,
                    operatorProjectionClass = eventSemanticBoundary.operatorProjectionClass
                )
            val eventHasCanonicalTruthSignal =
                eventStamp.lifecycleTerminalPhase == true &&
                    eventUplinkBoundary.resultSignalClass !=
                    AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL
            val eventRepresentsHandoff =
                payload.phase == DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
            val eventTruthOwnershipBoundary = deriveTruthOwnershipBoundary(
                executionBusy = (eventStamp.executionBusy == true) || eventHasCanonicalTruthSignal,
                sourceRuntimePosture = runtimeController.hostSessionSnapshot?.posture
                    ?: SourceRuntimePosture.CONTROL_ONLY,
                takeoverActive = eventRepresentsHandoff,
                sessionId = UFOGalaxyApplication.runtimeSessionId,
                isSessionRecoveryActive = reconnectState == ReconnectRecoveryState.RECOVERING.wireValue,
                isCapabilityDegraded = managerStateDegraded(),
                isRecoveryActive = reconnectState == ReconnectRecoveryState.RECOVERING.wireValue,
                isDiagnosticsSignal = !eventRepresentsHandoff && !eventHasCanonicalTruthSignal,
                isOperatorVisibleSummary = false
            )
            // ── PR-08v2 (Android): 预先推导执行事件级分布式运行参与边界快照 ────────────────────────
            // 在 payload.copy() 前唯一计算，避免在 copy() 内重复调用 derive()。
            // isDiagnosticsSignal=false 使推导规则真实反映 Android 在执行事件发射时的角色，
            // 区分正在分布式执行（DISTRIBUTED_RUNTIME_PARTICIPANT）、fallback 本地执行
            // （REMOTE_LOCAL_MODE_FALLBACK）与 handoff 参与（HANDOFF_PARTICIPANT）。
            val eventParticipationBoundary = try {
                val eventFallbackActive = payload.fallback_tier != null
                val eventCapabilityDegraded = modeState.executionModeState ==
                    LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue &&
                    payload.fallback_tier != null
                AndroidDistributedRuntimeParticipationBoundaryContract.derive(
                    AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                        sourceRuntimePosture = UFOGalaxyApplication.runtimeController
                            .hostSessionSnapshot?.posture ?: SourceRuntimePosture.CONTROL_ONLY,
                        executionBusy = eventStamp.executionBusy == true,
                        executionModeStateWire = modeState.executionModeState,
                        crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                        isFallbackTierActive = eventFallbackActive,
                        isCapabilityDegraded = eventCapabilityDegraded,
                        takeoverActive = payload.phase == DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                        isDiagnosticsSignal = false
                    )
                )
            } catch (e: Exception) {
                null
            }
            val eventLineage = AndroidUplinkLineageMetadataContract.derive(
                executionIdentity = payload.task_id.ifBlank { payload.flow_id },
                emissionIdentity = payload.event_id,
                durableSessionId = payload.durable_session_id,
                sessionContinuityEpoch = payload.session_continuity_epoch,
                recoveryBasis = "${reconnectState}:${eventSemanticProjection.degradedConditionClass.wireValue}"
            )
            val enrichedPayload = payload.copy(
                timestamp_ms = eventStamp.timestampMs,
                execution_event_sequence = eventStamp.eventSequence,
                active_execution_count = eventStamp.activeExecutionCount,
                execution_busy = eventStamp.executionBusy,
                execution_lifecycle_phase = eventStamp.executionLifecyclePhase,
                previous_execution_lifecycle_phase = eventStamp.previousExecutionLifecyclePhase,
                lifecycle_transition_valid = eventStamp.lifecycleTransitionValid,
                lifecycle_result_uplink_required = eventStamp.lifecycleResultUplinkRequired,
                lifecycle_state_uplink_required = eventStamp.lifecycleStateUplinkRequired,
                lifecycle_terminal_phase = eventStamp.lifecycleTerminalPhase,
                result_returned = completionVisibility.resultReturned,
                completion_signaled = completionVisibility.completionSignaled,
                closure_ready_for_acceptance = completionVisibility.closureReadyForAcceptance,
                mode_state = modeState.modeState,
                mode_readiness_state = modeState.modeReadinessState,
                cross_device_eligibility = modeState.crossDeviceEligibility,
                goal_execution_eligibility = modeState.goalExecutionEligibility,
                parallel_execution_eligibility = modeState.parallelExecutionEligibility,
                authoritative_participation_state = participationSnapshot.state.wireValue,
                participation_tier = AndroidAuthoritativeParticipationTruth
                    .participationTierFor(participationSnapshot.state)
                    .wireValue,
                authoritative_participation_transition_sequence =
                    participationSnapshot.transitionSequence,
                authoritative_participation_transition_trigger =
                    participationSnapshot.lastTransitionTrigger,
                authoritative_participation_transition_history =
                    participationSnapshot.transitionHistoryWire,
                execution_mode_state = modeState.executionModeState,
                governance_state = governanceTruth.governance_state,
                governance_blocked = governanceTruth.governance_blocked,
                delegated_execution_active = governanceTruth.delegated_execution_active,
                takeover_state = governanceTruth.takeover_state,
                // PR-5 (Android): observability audit fields enriched at the canonical sink
                // so every execution event carries coherent path tagging, audit class, and
                // fidelity classification regardless of which execution entry point produced it.
                observability_audit_schema_version = AndroidRuntimeObservabilityAuditContract.SCHEMA_VERSION,
                execution_path_tag = AndroidRuntimeObservabilityAuditContract.classifyExecutionPath(
                    spineParticipationKind = null,
                    executionModeState = modeState.executionModeState,
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    isTakeoverPath = payload.phase == DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                    isDelegated = modeState.crossDeviceEligibility == true &&
                        payload.phase != DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                    currentFallbackTier = payload.fallback_tier
                ).wireValue,
                audit_contribution_class = AndroidRuntimeObservabilityAuditContract.classifyAuditContribution(
                    eventPhase = payload.phase,
                    isTerminalPhase = eventStamp.lifecycleTerminalPhase == true,
                    isTakeoverEvent = payload.phase == DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                    isDelegatedEvent = modeState.crossDeviceEligibility == true &&
                        payload.phase != DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                    isInterruptionPhase = payload.is_blocking && payload.blocking_reason.isNotBlank() ||
                        payload.phase == DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
                    isRecoveryPhase = payload.phase == DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
                    isParticipationSnapshot = false,
                    isOperatorActionEvent = false
                ).wireValue,
                observability_reliability_class = AndroidRuntimeObservabilityAuditContract.classifyObservabilityReliability(
                    evidencePresenceKind = payload.evidence_presence_kind,
                    degradedConditionClass = eventSemanticProjection.degradedConditionClass.wireValue,
                    reconnectRecoveryState = reconnectState,
                    localObservationBasis = eventSemanticProjection.localObservationBasis.wireValue
                ).wireValue,
                reported_state_semantic_class =
                    eventSemanticProjection.reportedStateSemanticClass.wireValue,
                degraded_condition_class = eventSemanticProjection.degradedConditionClass.wireValue,
                local_observation_basis = eventSemanticProjection.localObservationBasis.wireValue,
                // ── 统一参与者生命周期阶段字段（在执行事件发射层填充）─────────────────────────────
                // 确保 V2 可将每个执行事件关联到 Android 在发射时的精确生命周期阶段，
                // 无需通过 participation_tier + carrier_runtime_state 组合推断。
                unified_lifecycle_phase = run {
                    val eventFormalLifecycle = FormalParticipantLifecycleState.fromManagerState(
                        UFOGalaxyApplication.localInferenceRuntimeManager.state.value
                    )
                    val eventCapabilityVisible = hasVisibleCrossDeviceCapability(
                        crossDeviceEligibility = modeState.crossDeviceEligibility,
                        sessionIsAttached = dispatchReadiness.sessionIsAttached
                    )
                    val eventDurableId = try {
                        UFOGalaxyApplication.appSettings.durableParticipantId
                            .takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null }
                    val eventInteractionSurface = UFOGalaxyApplication.appSettings.let {
                        it.accessibilityReady && it.overlayReady
                    }
                    val eventReconnectState = UFOGalaxyApplication.runtimeController
                        .reconnectRecoveryState.value.wireValue
                    AndroidUnifiedParticipantLifecyclePhase.derive(
                        AndroidUnifiedParticipantLifecyclePhase.DerivationInput(
                            formalLifecycleState = eventFormalLifecycle,
                            reconnectRecoveryStateWire = eventReconnectState,
                            crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                            wsConnected = webSocketClient.isConnected(),
                            hasDurableParticipantId = eventDurableId != null,
                            capabilityVisible = eventCapabilityVisible,
                            sessionAttached = dispatchReadiness.sessionIsAttached,
                            readinessSatisfied = modeState.crossDeviceEligibility,
                            executionBusy = eventStamp.executionBusy == true,
                            takeoverActive = currentActiveTakeoverId() != null,
                            interactionSurfaceReady = eventInteractionSurface,
                            governanceBlocked = governanceTruth.governance_blocked
                        )
                    ).wireValue
                },
                unified_lifecycle_schema_version = AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION,
                // ── PR-3Android: 参与语义规范化字段（在执行事件发射层填充）────────────────────────────
                // 从 eventNormalization（已在 copy() 前预计算）直接读取，
                // 确保 V2 在消费执行事件时可直接读取参与模式分类，
                // 无需跨域字段组合推断（distributed_participant + delegated_execution_active +
                // local_mode_active）即可区分本地辅助执行与委托/接管执行路径。
                participation_mode_class = eventNormalization.participationModeClass.wireValue,
                local_execution_active = eventNormalization.localExecutionActive,
                local_execution_activity_kind = eventNormalization.localExecutionActivityKind.wireValue,
                participation_semantic_schema_version = AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION,
                mesh_direct_schema_version = meshDirectSnapshot?.schemaVersion,
                mesh_direct_state = meshDirectSnapshot?.state?.wireValue,
                mesh_direct_route = meshDirectSnapshot?.route?.wireValue,
                mesh_direct_channel_ready = meshDirectSnapshot?.channelReady,
                mesh_direct_peer_count = meshDirectSnapshot?.peerCount,
                mesh_direct_ready_peer_count = meshDirectSnapshot?.readyPeerCount,
                mesh_direct_reason_codes = meshDirectSnapshot?.reasonCodes ?: emptyList(),
                mesh_direct_last_attempt_stage = meshDirectSnapshot?.lastAttemptStage,
                mesh_direct_last_attempt_succeeded = meshDirectSnapshot?.lastAttemptSucceeded,
                // ── PR-4Android: 工程边界可靠性字段（在执行事件发射层填充）──────────────────────────
                // 在单一发射层唯一计算，使 V2 可识别每条执行事件的异步边界类型、
                // 来源字段完整性等级与权限检查模式，无需 V2 侧推断或容忍缺失字段。
                //
                // async_scope_class: 执行事件发射层始终从 serviceScope 发送（SERVICE_SCOPED）；
                //   仅当 blocking_reason 含 "timeout_ms=" 时才为 TIMEOUT_GUARDED（任务有超时守护）。
                //   这是 GalaxyConnectionService 中执行事件发射的标准边界。
                async_scope_class = AndroidBoundaryReliabilityContract.classifyAsyncScope(
                    isServiceScoped = true,
                    hasTimeoutGuard = payload.blocking_reason.contains("timeout_ms="),
                    isLifecycleBound = false
                ).wireValue,
                // source_field_coverage_class: 从事件发射时的实际字段存在性推导，
                //   确保 V2 可直接判断此事件是否满足最低路由和审计字段要求。
                source_field_coverage_class = AndroidBoundaryReliabilityContract.classifySourceFieldCoverage(
                    deviceId = payload.device_id.takeIf { it.isNotBlank() },
                    sourceComponent = payload.source_component.takeIf { it.isNotBlank() },
                    taskId = payload.task_id.takeIf { it.isNotBlank() },
                    runtimeSessionId = payload.runtime_session_id
                ).wireValue,
                // authority_boundary_check_mode: 治理上下文存在时即为 GOVERNANCE_VALIDATED；
                //   这反映了 GalaxyConnectionService 中所有委托执行路径在接受任务时
                //   均已通过 DelegatedRuntimeAcceptanceEvaluator 进行治理评估的事实。
                authority_boundary_check_mode = AndroidBoundaryReliabilityContract.classifyAuthorityBoundaryCheckMode(
                    hasGovernanceContext = payload.governance_state != null,
                    hasExplicitContractGate = false
                ).wireValue,
                boundary_reliability_schema_version = AndroidBoundaryReliabilityContract.SCHEMA_VERSION,
                // ── PR-02v2 (Android): 跨设备 dispatch 边界收束字段（在执行事件发射层填充）──────────
                // 从已预计算的 eventDispatchBoundary 直接读取，确保 V2 可将执行事件关联到
                // 正确的 dispatch 边界类型而无需字段组合推断。
                dispatch_boundary_class = eventDispatchBoundary.dispatchBoundaryClass.wireValue,
                dispatch_path_consumption_kind = eventDispatchBoundary.dispatchPathConsumptionKind.wireValue,
                dispatch_boundary_schema_version = AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION,
                ingress_boundary_class = eventIngress.boundaryClass.wireValue,
                ingress_consumption_kind = eventIngress.consumptionKind.wireValue,
                ingress_signal_class = eventIngress.signalClass.wireValue,
                ingress_schema_version = eventIngress.schemaVersion,
                // ── PR-05v2 (Android): 结果上行闭环边界字段（在执行事件发射层填充）────────────────────
                // 从已预计算的 eventUplinkBoundary 直接读取，使 V2 可无歧义地将执行事件路由至
                // truth closure 链、acceptance adjudication 或诊断存储，无需字段组合推断。
                result_signal_class = eventUplinkBoundary.resultSignalClass.wireValue,
                acceptance_candidate_class = eventUplinkBoundary.acceptanceCandidateClass.wireValue,
                result_uplink_boundary_schema_version = AndroidResultUplinkBoundaryContract.SCHEMA_VERSION,
                uplink_semantic_boundary_class =
                    eventSemanticBoundary.uplinkSemanticBoundaryClass.wireValue,
                operator_projection_class = eventSemanticBoundary.operatorProjectionClass.wireValue,
                diagnostics_failure_explanation_schema_version =
                    AndroidDiagnosticsFailureExplanationUplinkContract.SCHEMA_VERSION,
                authority_runtime_completion_signal_class = eventCompletionClosureBoundary
                    .authorityRuntimeCompletionSignalClass.wireValue,
                result_completion_signal_class = eventCompletionClosureBoundary
                    .resultCompletionSignalClass.wireValue,
                closure_finalization_signal_class = eventCompletionClosureBoundary
                    .closureFinalizationSignalClass.wireValue,
                operator_done_projection_class = eventCompletionClosureBoundary
                    .operatorDoneProjectionClass.wireValue,
                completion_closure_uplink_schema_version =
                    AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
                // ── PR-08v2 (Android): 分布式运行参与边界收束字段（在执行事件发射层填充）────────────────
                // 从已预计算的 eventParticipationBoundary 直接读取，使 V2 可无歧义地将执行事件
                // 路由至正确的分布式运行参与链，无需字段组合推断。
                // isDiagnosticsSignal=false 确保真实角色（DISTRIBUTED_RUNTIME_PARTICIPANT /
                // REMOTE_LOCAL_MODE_FALLBACK / HANDOFF_PARTICIPANT）得到正确反映。
                participation_boundary_role = eventParticipationBoundary?.participationBoundaryRole?.wireValue,
                ownership_posture_class = eventParticipationBoundary?.ownershipPostureClass?.wireValue,
                remote_local_mode_class = eventParticipationBoundary?.remoteLocalModeClass?.wireValue,
                participation_boundary_schema_version = AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION,
                authority_signal_class = eventTruthOwnershipBoundary.authoritySignalClass.wireValue,
                ownership_uplink_class = eventTruthOwnershipBoundary.ownershipUplinkClass.wireValue,
                session_continuity_class = eventTruthOwnershipBoundary.sessionContinuityClass.wireValue,
                device_posture_signal_class =
                    eventTruthOwnershipBoundary.devicePostureSignalClass.wireValue,
                distributed_truth_ownership_uplink_schema_version =
                    AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION,
                uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
                uplink_lineage_execution_id = eventLineage.executionIdentity,
                uplink_lineage_emission_id = eventLineage.emissionIdentity,
                uplink_lineage_dedupe_key = eventLineage.dedupeKey,
                uplink_lineage_recovery_basis = eventLineage.recoveryBasis
            )
            val closedLoopPayload =
                AndroidClosedLoopGovernanceContract.canonicalizeExecutionEvent(enrichedPayload)
            GalaxyLogger.log(
                GalaxyLogger.TAG_DEVICE_EXECUTION_EVENT,
                mapOf(
                    "event" to "device_execution_event_sent",
                    "device_id" to localDeviceId,
                    "task_id" to closedLoopPayload.task_id,
                    "phase" to closedLoopPayload.phase,
                    "flow_id" to closedLoopPayload.flow_id,
                    "step_index" to closedLoopPayload.step_index,
                    "is_blocking" to closedLoopPayload.is_blocking,
                    "blocking_reason" to closedLoopPayload.blocking_reason,
                    "stagnation_detected" to closedLoopPayload.stagnation_detected,
                    "fallback_tier" to (closedLoopPayload.fallback_tier ?: ""),
                    "execution_event_sequence" to (closedLoopPayload.execution_event_sequence ?: -1L),
                    "active_execution_count" to (closedLoopPayload.active_execution_count ?: -1),
                    "execution_busy" to (closedLoopPayload.execution_busy ?: false),
                    "execution_lifecycle_phase" to (closedLoopPayload.execution_lifecycle_phase ?: ""),
                    "previous_execution_lifecycle_phase" to (closedLoopPayload.previous_execution_lifecycle_phase ?: ""),
                    "lifecycle_transition_valid" to (closedLoopPayload.lifecycle_transition_valid ?: true),
                    "lifecycle_result_uplink_required" to (closedLoopPayload.lifecycle_result_uplink_required ?: false),
                    "lifecycle_state_uplink_required" to (closedLoopPayload.lifecycle_state_uplink_required ?: false),
                    "lifecycle_terminal_phase" to (closedLoopPayload.lifecycle_terminal_phase ?: false),
                    "event_id" to closedLoopPayload.event_id,
                    "source_component" to closedLoopPayload.source_component
                )
            )
            val sent = webSocketClient.sendDeviceExecutionEvent(closedLoopPayload)
            delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
                DelegatedRuntimeAcceptanceDimension.CANONICAL_EXECUTION_EVENT_EVIDENCE
            )
            if (closedLoopPayload.lifecycle_terminal_phase == true) {
                sendDeviceAcceptanceReport()
            }
            crossRepoRegressionHooks.recordExecutionSignal(
                taskId = closedLoopPayload.task_id,
                traceId = closedLoopPayload.trace_id,
                runtimeSessionId = closedLoopPayload.runtime_session_id,
                signalKind = closedLoopPayload.phase,
                status = if (sent) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED,
                reason = if (sent) null else "device_execution_event_send_failed"
            )
            emitCrossRepoRegressionSnapshot("cross_repo_execution_event")
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_EXEC_EVENT] sink error task_id=${payload.task_id} phase=${payload.phase}: ${e.message}", e)
        }
    }

    /**
     * Sink for structured Android perception emissions on the canonical Android→V2 path.
     *
     * Enriches EdgeExecutor-produced perception payloads with real runtime carrier/session state,
     * then logs and forwards them as [MsgType.DEVICE_PERCEPTION_EMISSION] uplinks.
     */
    private val devicePerceptionEmissionSink = DevicePerceptionEmissionSink { payload ->
        try {
            val settings = UFOGalaxyApplication.appSettings
            val modeState = settings.authoritativeModeState(
                wsConnected = webSocketClient.isConnected(),
                capabilityDegraded = managerStateDegraded()
            )
            val durableRecord = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            val attachedSession = UFOGalaxyApplication.runtimeController.attachedSession.value
            val foregroundVisible = runCatching {
                UFOGalaxyApplication.runtimeController.appForegroundVisible.value
            }.getOrNull()
            val interactionSurfaceReady = runCatching {
                val settings = UFOGalaxyApplication.appSettings
                settings.accessibilityReady && settings.overlayReady
            }.getOrNull()
            val enrichedPayload = payload.copy(
                device_id = payload.device_id.ifBlank { localDeviceId },
                durable_session_id = durableRecord?.durableSessionId,
                session_continuity_epoch = durableRecord?.sessionContinuityEpoch,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                attached_session_id = attachedSession?.sessionId,
                carrier_foreground_visible = foregroundVisible,
                interaction_surface_ready = interactionSurfaceReady,
                mode_state = modeState.modeState,
                mode_readiness_state = modeState.modeReadinessState,
                cross_device_eligibility = modeState.crossDeviceEligibility,
                goal_execution_eligibility = modeState.goalExecutionEligibility,
                parallel_execution_eligibility = modeState.parallelExecutionEligibility,
                carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_DEVICE_PERCEPTION_EMISSION,
                mapOf(
                    "event" to "device_perception_emission_sent",
                    "device_id" to localDeviceId,
                    "task_id" to enrichedPayload.task_id,
                    "flow_id" to enrichedPayload.flow_id,
                    "emission_kind" to enrichedPayload.emission_kind,
                    "perception_stage" to enrichedPayload.perception_stage,
                    "participates_in_multimodal_main_chain" to enrichedPayload.participates_in_multimodal_main_chain,
                    "carrier_semantics" to enrichedPayload.carrier_semantics,
                    "participation_semantics" to enrichedPayload.participation_semantics,
                    "trace_id" to (enrichedPayload.trace_id ?: ""),
                    "dispatch_trace_id" to (enrichedPayload.dispatch_trace_id ?: "")
                )
            )
            webSocketClient.sendDevicePerceptionEmission(enrichedPayload)
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_PERCEPTION] sink error task_id=${payload.task_id}: ${e.message}", e)
        }
    }

    /**
     * Canonical binding from accepted delegated receipt into local takeover execution (PR-12).
     *
     * [DelegatedTakeoverExecutor] manages the full lifecycle of an accepted
     * [com.ufo.galaxy.agent.DelegatedRuntimeUnit]: creates the
     * [com.ufo.galaxy.runtime.DelegatedExecutionTracker], emits ACK and RESULT signals via
     * [delegatedSignalSink], and returns a typed [DelegatedTakeoverExecutor.ExecutionOutcome]
     * so [handleTakeoverRequest] no longer needs inline try/catch logic.
     */
    private val delegatedTakeoverExecutor: DelegatedTakeoverExecutor by lazy {
        DelegatedTakeoverExecutor(
            pipeline = GoalExecutionPipeline { payload ->
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            },
            signalSink = delegatedSignalSink,
            emittedSignalLedgerStore = PersistentEmittedSignalLedgerStore(
                prefs = getSharedPreferences("emitted_signal_ledgers", MODE_PRIVATE)
            )
        )
    }

    // ── PR-35: Promoted long-tail lifecycle state ─────────────────────────────

    /**
     * Per-peer capability record from the most recent PEER_EXCHANGE message received from
     * each peer device in this session.  Keyed by `source_device_id`; value is the list of
     * capability names that peer advertised.
     *
     * Volatile so that any thread can safely read the latest snapshot.  Written only on the
     * IO dispatcher inside [handlePeerExchange].
     */
    @Volatile
    private var lastPeerCapabilities: Map<String, List<String>> = emptyMap()

    /**
     * Most recent mesh topology snapshot received via MESH_TOPOLOGY.
     *
     * Updated by [handleMeshTopology] each time a topology message arrives; later messages
     * with a lower [MeshTopologyPayload.topology_seq] are silently ignored so out-of-order
     * delivery does not overwrite a newer snapshot.
     */
    @Volatile
    private var lastMeshTopologyNodes: List<String> = emptyList()
    @Volatile
    private var lastMeshTopologyMeshId: String? = null

    /**
     * The [MeshTopologyPayload.topology_seq] of the last retained topology snapshot.
     * Initialised to `-1` so that the first arriving topology (seq ≥ 0) is always accepted.
     */
    @Volatile
    private var lastMeshTopologySeq: Int = -1
    @Volatile
    private var lastMeshDirectRuntimeSnapshot =
        AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot()

    /**
     * Monotonic count of COORD_SYNC ticks received in this service lifecycle.
     * Incremented by [handleCoordSync] and echoed in every [CoordSyncAckPayload] so the
     * coordinator can detect gaps in the device's acknowledgement sequence.
     */
    @Volatile
    private var coordSyncTickCount: Int = 0

    /**
     * Per-peer presence record from the most recent PEER_ANNOUNCE message received from
     * each peer device in this session.  Keyed by `peer_device_id`; value is the
     * [PeerAnnouncePayload] carrying role and session context for that peer.
     *
     * Retained so that higher-level components can inspect which peers have announced
     * themselves without re-parsing raw messages.  Written only on the IO dispatcher
     * inside [handlePeerAnnounce].
     */
    @Volatile
    private var lastPeerAnnouncements: Map<String, PeerAnnouncePayload> = emptyMap()

    private lateinit var wsListener: GalaxyWebSocketClient.Listener
    
    inner class LocalBinder : Binder() {
        fun getService(): GalaxyConnectionService = this@GalaxyConnectionService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        
        webSocketClient = UFOGalaxyApplication.webSocketClient
        UFOGalaxyApplication.edgeExecutor.setPerceptionEmissionSink(devicePerceptionEmissionSink)
        
        // 设置监听器：处理连接状态和 task_assign 路由
        wsListener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "已连接到 Galaxy")
                updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot())
                updateNotification("已连接")
                crossRepoRegressionHooks.recordConnection(ScenarioOutcomeStatus.PASSED)
                emitCrossRepoRegressionSnapshot("cross_repo_connection_established")
                if (hasObservedWsConnection) {
                    crossRepoRegressionHooks.recordReconnectRecovery(
                        status = ScenarioOutcomeStatus.PASSED
                    )
                    emitCrossRepoRegressionSnapshot("cross_repo_reconnect_recovery")
                }
                hasObservedWsConnection = true
                // PR-RT: emit a device_state_snapshot on every WS connect / reconnect so V2
                // always has a fresh runtime-state projection immediately after the connection
                // is established.  This covers both the initial connect and reconnect/recovery
                // paths so V2 never operates on stale snapshot data after a transparent WS drop.
                serviceScope.launch {
                    sendDeviceStateSnapshot()
                }
            }
            
            override fun onDisconnected() {
                Log.d(TAG, "与 Galaxy 断开连接")
                updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot())
                updateNotification("已断开")
                crossRepoRegressionHooks.recordConnection(
                    status = ScenarioOutcomeStatus.FAILED,
                    reason = "ws_disconnected"
                )
                emitCrossRepoRegressionSnapshot("cross_repo_connection_lost")
                delegatedRuntimeAcceptanceEvaluator.markDimensionGap(
                    DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
                    "ws_disconnected_runtime_snapshot_stale_risk"
                )
                emitRuntimeDiagnostics(
                    taskId = activeTakeoverId ?: "runtime",
                    nodeName = "ws_runtime",
                    errorType = "ws_disconnected",
                    errorContext = "websocket disconnected during runtime operation"
                )
                serviceScope.launch {
                    sendDeviceStateSnapshot()
                    sendDeviceAcceptanceReport()
                }
                // Safety: unblock the local LoopController in case the Gateway disconnected
                // while a remote task was in flight. The finally blocks in handleTaskAssign,
                // handleGoalExecution, and handleParallelSubtask cover normal completion;
                // this covers unexpected drops (network outage, server crash) so the loop
                // is never blocked permanently.
                UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
                // PR-23: If a takeover was actively in-flight when the WS dropped, emit a
                // DISCONNECT failure event through RuntimeController so surface layers can
                // clear any stale "active" or "in-control" state.  The takeover ID is
                // captured before clearing it so the event carries the correct identity.
                val activeId = activeTakeoverId
                if (activeId != null) {
                    serviceScope.launch {
                        UFOGalaxyApplication.runtimeController.notifyTakeoverFailed(
                            takeoverId = activeId,
                            taskId = "",
                            traceId = "",
                            reason = "ws_disconnect_during_takeover",
                            cause = TakeoverFallbackEvent.Cause.DISCONNECT
                        )
                    }
                }
            }
            
            override fun onMessage(message: String) {
                Log.d(TAG, "收到消息: ${message.take(50)}...")
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "连接错误: $error")
                updateNotification("连接错误")
                crossRepoRegressionHooks.recordConnection(
                    status = ScenarioOutcomeStatus.FAILED,
                    reason = error
                )
                emitCrossRepoRegressionSnapshot("cross_repo_connection_error")
                emitRuntimeDiagnostics(
                    taskId = activeTakeoverId ?: "runtime",
                    nodeName = "ws_runtime",
                    errorType = "ws_error",
                    errorContext = error
                )
            }

            override fun onDeviceRegisterSent(deviceId: String, traceId: String?) {
                crossRepoRegressionHooks.recordDeviceRegisterSent()
                emitCrossRepoRegressionSnapshot("cross_repo_device_register_sent")
            }

            override fun onCapabilityReportSent(deviceId: String, traceId: String?) {
                crossRepoRegressionHooks.recordCapabilityReportSent()
                emitCrossRepoRegressionSnapshot("cross_repo_capability_report_sent")
            }

            override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 task_assign: task_id=$taskId trace_id=$traceId")
                GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to taskId, "type" to "task_assign", "trace_id" to (traceId ?: "")))
                serviceScope.launch {
                    handleTaskAssign(taskId, taskAssignPayloadJson, traceId)
                }
            }

            override fun onGoalExecution(taskId: String, goalPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 goal_execution: task_id=$taskId trace_id=$traceId")
                GalaxyLogger.log(GalaxyLogger.TAG_TASK_RECV, mapOf("task_id" to taskId, "type" to "goal_execution", "trace_id" to (traceId ?: "")))
                crossRepoRegressionHooks.recordExecutionReceipt(
                    taskId = taskId,
                    traceId = traceId,
                    runtimeSessionId = UFOGalaxyApplication.runtimeSessionId,
                    status = ScenarioOutcomeStatus.PASSED
                )
                emitCrossRepoRegressionSnapshot("cross_repo_goal_execution_received")
                serviceScope.launch {
                    // Register inside the coroutine to avoid any race between launch and register.
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleGoalExecution(taskId, goalPayloadJson, traceId)
                }
            }

            override fun onParallelSubtask(taskId: String, subtaskPayloadJson: String, traceId: String?) {
                Log.i(TAG, "收到 parallel_subtask: task_id=$taskId trace_id=$traceId")
                serviceScope.launch {
                    taskCancelRegistry.register(taskId, coroutineContext[Job]!!)
                    handleParallelSubtask(taskId, subtaskPayloadJson, traceId)
                }
            }

            override fun onTaskCancel(taskId: String, cancelPayloadJson: String) {
                Log.i(TAG, "收到 task_cancel: task_id=$taskId")
                serviceScope.launch {
                    handleTaskCancel(taskId, cancelPayloadJson)
                }
            }

            // ── PR-H: HandoffEnvelopeV2 native consumption dispatch ───────────────────
            override fun onHandoffEnvelopeV2(taskId: String, envelopePayloadJson: String, traceId: String?) {
                Log.i(TAG, "[PR-H] 收到 handoff_envelope_v2: task_id=$taskId trace_id=$traceId")
                GalaxyLogger.log(
                    GalaxyLogger.TAG_TASK_RECV, mapOf(
                        "task_id" to taskId,
                        "type" to "handoff_envelope_v2",
                        "trace_id" to (traceId ?: ""),
                        "event" to "handoff_envelope_v2_dispatch"
                    )
                )
                serviceScope.launch {
                    handleHandoffEnvelopeV2(taskId, envelopePayloadJson, traceId)
                }
            }

            override fun onOperatorAction(
                actionId: String,
                operatorActionPayloadJson: String,
                traceId: String?
            ) {
                Log.i(TAG, "[OPERATOR_ACTION] received action_id=$actionId trace_id=$traceId")
                serviceScope.launch {
                    handleOperatorActionRequest(actionId, operatorActionPayloadJson, traceId)
                }
            }

            /**
             * Advanced message dispatcher for PR-4 capability channels.
             *
             * ## PR-35 routing
             * The three highest-value long-tail types are dispatched to dedicated handlers that
             * provide stateful handling and stronger lifecycle semantics:
             * - [MsgType.PEER_EXCHANGE]  → [handlePeerExchange] (peer capability record + ack)
             * - [MsgType.MESH_TOPOLOGY]  → [handleMeshTopology] (topology snapshot + ack)
             * - [MsgType.COORD_SYNC]     → [handleCoordSync] (sequence-aware CoordSyncAckPayload)
             *
             * ## PR-36 routing
             * - [MsgType.PEER_ANNOUNCE]  → [handlePeerAnnounce] (per-session peer presence record + ack)
             *
             * ## Generic minimal-compat path (transitional)
             * All other types in [MsgType.ADVANCED_TYPES] continue to use the minimal-compat
             * path: a generic [AckPayload] is sent for [MsgType.ACK_ON_RECEIPT_TYPES], and all
             * types are logged via [GalaxyLogger.TAG_LONG_TAIL_COMPAT].  These transitional
             * surfaces must not be extended as canonical architecture.
             *
             * ## TAKEOVER_REQUEST
             * [MsgType.TAKEOVER_REQUEST] is dispatched to [handleTakeoverRequest] (PR-5+).
             *
             * @see com.ufo.galaxy.runtime.LongTailCompatibilityRegistry
             */
            override fun onAdvancedMessage(
                type: MsgType,
                messageId: String?,
                rawJson: String
            ) {
                Log.i(TAG, "[ADVANCED:RECV] type=${type.value} message_id=$messageId")

                // ── PR-35: Route promoted types to dedicated stateful handlers ─────────
                when (type) {
                    MsgType.PEER_EXCHANGE -> {
                        serviceScope.launch { handlePeerExchange(messageId, rawJson) }
                        return
                    }
                    MsgType.MESH_TOPOLOGY -> {
                        serviceScope.launch { handleMeshTopology(messageId, rawJson) }
                        return
                    }
                    MsgType.COORD_SYNC -> {
                        serviceScope.launch { handleCoordSync(messageId, rawJson) }
                        return
                    }
                    // ── PR-36: Route promoted PEER_ANNOUNCE to stateful handler ─────────
                    MsgType.PEER_ANNOUNCE -> {
                        serviceScope.launch { handlePeerAnnounce(messageId, rawJson) }
                        return
                    }
                    MsgType.HYBRID_EXECUTE -> {
                        serviceScope.launch {
                            sendAdvancedAck(type, messageId)
                            handleHybridExecute(messageId, rawJson)
                        }
                        return
                    }
                    else -> Unit
                }

                // ── Generic minimal-compat path (transitional) ────────────────────────
                GalaxyLogger.log(
                    GalaxyLogger.TAG_LONG_TAIL_COMPAT, mapOf(
                        "event" to "transitional_path_exercised",
                        "type" to type.value,
                        "message_id" to (messageId ?: ""),
                        "tier" to "transitional"
                    )
                )
                // Send ack for types that require delivery confirmation.
                if (type in MsgType.ACK_ON_RECEIPT_TYPES) {
                    serviceScope.launch {
                        sendAdvancedAck(type, messageId)
                    }
                }
                // TAKEOVER_REQUEST (PR-5): parse the canonical TakeoverRequestEnvelope,
                // evaluate eligibility, and accept when all readiness conditions are met.
                // When accepted, dispatches the takeover goal to the execution pipeline
                // and includes runtime_host_id/formation_role in the acceptance response.
                // When not eligible, sends a structured rejection.
                if (type == MsgType.TAKEOVER_REQUEST) {
                    serviceScope.launch {
                        handleTakeoverRequest(messageId, rawJson)
                    }
                }
            }

            /**
             * Fallback for completely unrecognised message types.
             * Logs a structured warning so failures are never silent.
             */
            override fun onUnknownMessage(rawType: String?, rawJson: String) {
                Log.w(TAG, "[UNKNOWN:RECV] type=$rawType — unrecognised AIP v3 message type; ignored")
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "unknown_message_ignored",
                        "type" to (rawType ?: "null"),
                        "handler" to "fallback"
                    )
                )
            }
        }
        webSocketClient.addListener(wsListener)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动")

        // Restore cross-device runtime state via RuntimeController — the sole lifecycle
        // authority.  RuntimeController reads settings.crossDeviceEnabled internally;
        // direct webSocketClient.setCrossDeviceEnabled() / connect() calls are not
        // permitted outside RuntimeController.
        val settings = UFOGalaxyApplication.appSettings
        val savedCrossDevice = settings.crossDeviceEnabled
        UFOGalaxyApplication.runtimeController.connectIfEnabled()
        Log.i(TAG, "服务启动：通过 RuntimeController 恢复跨设备状态 crossDeviceEnabled=$savedCrossDevice")

        // 启动前台服务（常驻通知，防止后台进程被系统回收）
        startForeground(NOTIFICATION_ID, createNotification(
            if (savedCrossDevice) "跨设备模式已启用" else "本地模式"
        ))

        // Pre-warm and then load models in background.
        // Pre-warming sends a lightweight health ping + optional dry-run to reduce cold start
        // latency on the first real inference call.
        serviceScope.launch {
            ensureModels()
            prewarmServices()
            loadModels()
            // Re-run all readiness checks after models are loaded so the UI and
            // capability_report reflect the final state (including overlay/accessibility).
            UFOGalaxyApplication.instance.refreshReadiness()
        }

        // PR-06: Collect RuntimeController.reconciliationSignals and forward each signal
        // to V2 via the RECONCILIATION_SIGNAL wire uplink.  This coroutine runs for the
        // lifetime of the service and is cancelled automatically when serviceScope is
        // cancelled in onDestroy.
        serviceScope.launch {
            UFOGalaxyApplication.runtimeController.reconciliationSignals.collect { signal ->
                sendReconciliationSignal(signal)
            }
        }

        // Android-side signal closure: emit the initial device readiness report so V2
        // readiness-gate and governance paths have a baseline Android-side artifact
        // immediately after service start.  All dimensions start as UNKNOWN at this point,
        // which is itself a valid structured signal — V2 can distinguish "not yet reported"
        // from "reported with a gap".  Follow-up reports are emitted whenever dimension
        // states change.
        serviceScope.launch {
            sendDeviceReadinessReport()
        }

        // PR-RT: emit the baseline device_state_snapshot so V2's android_device_state_store
        // has a real runtime-state projection for this device immediately on service start.
        // The snapshot is built from real current local state sources (NativeInferenceLoader,
        // LocalInferenceRuntimeManager, AppSettings, LocalLoopReadinessProvider,
        // ModelAssetManager, offlineQueue, rolloutControlSnapshot).
        serviceScope.launch {
            sendDeviceStateSnapshot()
        }

        // PR-10: Re-emit a device_state_snapshot whenever the carrier's foreground-visibility
        // state changes (FOREGROUND / BACKGROUND lifecycle transitions) so V2's
        // android_device_state_store always holds a fresh carrier_foreground_visible value
        // and is not left with stale foreground state after the app enters the background.
        //
        // drop(1) avoids emitting a duplicate snapshot at service start (the baseline snapshot
        // above already captures the initial value).  The send is a no-op when the WS is
        // not connected (sendJson drops it) so this path is safe to trigger unconditionally.
        serviceScope.launch {
            UFOGalaxyApplication.runtimeController.appForegroundVisible
                .drop(1)
                .collect {
                    sendDeviceStateSnapshot()
                }
        }

        // PR-4 (Android): emit baseline governance / acceptance / strategy reports on service
        // start so V2 post-graduation governance, graduation gate, and program strategy paths
        // each have a structured Android-side baseline artifact immediately on connection.
        // All dimensions start as UNKNOWN at initial report time — V2 can distinguish "not yet
        // observed" from "regression detected" / "evidence gap" / "strategic risk".
        serviceScope.launch {
            sendDeviceGovernanceReport()
        }
        serviceScope.launch {
            sendDeviceAcceptanceReport()
        }
        serviceScope.launch {
            sendDeviceStrategyReport()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "服务销毁")
        UFOGalaxyApplication.edgeExecutor.setPerceptionEmissionSink(null)
        webSocketClient.removeListener(wsListener)
        webSocketClient.disconnect()
        unloadModels()
        serviceScope.cancel()
    }

    /**
     * 处理 task_assign：反序列化 payload、执行本地 EdgeExecutor 或委托 AgentRuntimeBridge、回传 GOAL_EXECUTION_RESULT。
     * 在 IO 线程中执行；EdgeExecutor 内部所有异常均已捕获并映射为 ERROR 结果。
     *
     * When a task_assign arrives (Round 5 bridge flow):
     * 1. Notifies [RuntimeController.onRemoteTaskStarted] to cancel any running local
     *    [com.ufo.galaxy.loop.LoopController] session.
     * 2a. If cross-device is ON **and** [TaskAssignPayload.require_local_agent] is `false`:
     *     delegates to [AgentRuntimeBridge.handoff] with exec_mode=REMOTE, carrying
     *     trace_id, route_mode, capability, and session context.
     *     - If handoff succeeds ([HandoffResult.isHandoff] = true): task has been forwarded
     *       to Agent Runtime; no local execution is needed. The Agent Runtime will send the
     *       result directly to the Gateway.
     *     - If handoff fails / times out (all retries exhausted): falls back to local
     *       EdgeExecutor with an explicit error log; no silent swallowing.
     * 2b. If cross-device is OFF or [require_local_agent] is `true`: executes locally via
     *     [EdgeExecutor] as before (full backward compatibility).
     * 3. Sends back the AIP v3 GOAL_EXECUTION_RESULT envelope (for local execution or
     *    fallback path); the envelope includes [trace_id] and [route_mode] for full-chain
     *    traceability.  All error and parse-failure paths also use GOAL_EXECUTION_RESULT
     *    via [sendGoalError], ensuring a single canonical result contract for every outcome.
     * 4. Notifies [RuntimeController.onRemoteTaskFinished] to unblock local execution.
     * 5. Persists the result to OpenClawd memory.
     */
    private suspend fun handleTaskAssign(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val payload = try {
            gson.fromJson(payloadJson, TaskAssignPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_assign payload 解析失败: ${e.message}", e)
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "task_assign_ingress",
                errorType = "task_payload_parse_error",
                errorContext = e.message ?: "unknown parse error"
            )
            val errorTraceId = inboundTraceId?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}", errorTraceId)
            return
        }

        // Continuity gate: reject execution if no active attached session is present.
        // This aligns online task_assign with the replay/recovery continuity authority contract
        // and ensures stale-runtime requests are blocked before any execution begins.
        val activeSession = UFOGalaxyApplication.runtimeController.attachedSession.value
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = activeSession?.sessionId ?: "",
            activeSession = activeSession
        )
        if (identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession) {
            Log.w(TAG, "[CONTINUITY] task_assign rejected: no active attached session task_id=$taskId")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "task_assign_continuity_gate",
                errorType = AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
                errorContext = "no_active_session"
            )
            val gateTraceId = inboundTraceId?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "no_active_session", gateTraceId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve trace_id from the inbound task_assign envelope for full-chain correlation.
        // Generate a new UUID only when the gateway did not supply one.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        val crossDevice = UFOGalaxyApplication.appSettings.crossDeviceEnabled
        val routeMode = if (crossDevice) AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE
                        else AgentRuntimeBridge.ROUTE_MODE_LOCAL

        if (crossDevice && !payload.require_local_agent) {
            // ── Bridge path: cross-device ON + task does not require local execution ──
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "bridge_handoff_attempt",
                    "task_id" to taskId,
                    "trace_id" to traceId,
                    "route_mode" to routeMode
                )
            )
            val handoffRequest = AgentRuntimeBridge.HandoffRequest(
                traceId = traceId,
                taskId = taskId,
                goal = payload.goal,
                execMode = AgentRuntimeBridge.EXEC_MODE_REMOTE,
                routeMode = routeMode,
                capability = "task_execution",
                constraints = payload.constraints,
                sourceRuntimePosture = SourceRuntimePosture.fromValue(payload.source_runtime_posture),
                // ── PR-D: propagate V2 source dispatch metadata into handoff ──────────
                dispatchIntent = payload.dispatch_intent,
                dispatchOrigin = payload.dispatch_origin,
                orchestrationStage = payload.orchestration_stage,
                executionContext = payload.execution_context
            )
            val handoffResult = UFOGalaxyApplication.agentRuntimeBridge.handoff(handoffRequest)

            if (handoffResult.isHandoff) {
                // Task successfully handed off to Agent Runtime.
                // Agent Runtime will send the result directly; no local execution needed.
                updateNotification("任务 ${taskId.take(8)}: 已转交 Agent Runtime")
                Log.i(
                    TAG,
                    "task_assign bridge handoff OK task_id=$taskId trace_id=$traceId"
                )
                // Unblock local loop after scheduling the handoff.
                UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
            } else {
                // Handoff failed (fallback to local execution).
                Log.w(
                    TAG,
                    "task_assign bridge handoff failed — falling back to local execution " +
                        "task_id=$taskId trace_id=$traceId error=${handoffResult.error}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "bridge_fallback",
                        "task_id" to taskId,
                        "trace_id" to traceId,
                        "error" to handoffResult.error
                    )
                )
                emitRuntimeDiagnostics(
                    taskId = taskId,
                    nodeName = "bridge_handoff",
                    errorType = "bridge_handoff_failed",
                    errorContext = handoffResult.error ?: "unknown handoff failure"
                )
                // ── PR-2: emit fallback_transition when delegated path falls back ─────
                // PR-6: capture session identity at fallback emission point.
                val durFallback = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
                val fallbackOutcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
                    phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
                    blockingReason = handoffResult.error,
                    fallbackTier = "local_fallback"
                )
                deviceExecutionEventSink.onEvent(
                    DeviceExecutionEventPayload(
                        flow_id = taskId,
                        task_id = taskId,
                        phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
                        blocking_reason = handoffResult.error ?: "bridge_handoff_failed",
                        device_id = localDeviceId,
                        source_component = "GalaxyConnectionService.handleTaskAssign",
                        durable_session_id = durFallback?.durableSessionId,
                        session_continuity_epoch = durFallback?.sessionContinuityEpoch,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                        // PR-8: carrier manifestation/presence hints at fallback point.
                        carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                        interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                            UFOGalaxyApplication.appSettings.overlayReady,
                        // PR-10: carrier runtime state at fallback point.
                        carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                        reported_state_semantic_class =
                            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
                        result_uplink_semantic_class =
                            AndroidMissionCompletionSemanticsContract
                                .classifyReportedResultSemantic(fallbackOutcome)
                                .wireValue,
                        terminal_outcome_kind = fallbackOutcome.wireValue,
                        // PR-7B: explicit evidence presence kind for fallback transition.
                        evidence_presence_kind =
                            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                                DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION
                            ).wireValue
                    )
                )
                executeLocalTaskAssign(taskId, payload, traceId, routeMode)
            }
        } else {
            // ── Local path: cross-device OFF or require_local_agent=true ─────────────
            executeLocalTaskAssign(taskId, payload, traceId, routeMode)
        }
    }

    /**
     * Executes a task_assign payload locally via [EdgeExecutor] and sends the
     * GOAL_EXECUTION_RESULT back to the Gateway. Both trace_id and route_mode are propagated
     * in the reply envelope.
     *
     * Called from:
     *  - The local execution path (cross-device OFF or require_local_agent=true).
     *  - The bridge fallback path (bridge handoff failed after all retries).
     */
    private suspend fun executeLocalTaskAssign(
        taskId: String,
        payload: TaskAssignPayload,
        traceId: String,
        routeMode: String
    ) {
        // Build a canonical LocalRuntimeContext at the ingress point so posture is
        // available as a typed, normalised value throughout this execution scope.
        val runtimeContext = LocalRuntimeContext.of(
            taskId = taskId,
            sessionId = null,
            sourceRuntimePosture = payload.source_runtime_posture,
            traceId = traceId,
            deviceRole = UFOGalaxyApplication.appSettings.deviceRole
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "local_task_assign_ingress",
                "task_id" to taskId,
                "posture" to runtimeContext.sourceRuntimePosture,
                "is_join_runtime" to runtimeContext.isJoinRuntime,
                "trace_id" to traceId,
                "route_mode" to routeMode
            )
        )

        var goalResult: GoalResultPayload? = null
        try {
            updateNotification("执行任务 ${taskId.take(8)}…")

            // ── 统一通过 LocalGoalExecutor 处理（与 goal_execution 路径一致）─────────
            // 将 TaskAssignPayload 规范化为 GoalExecutionPayload，
            // 经过 GoalNormalizer.normalize() 清洗自然语言指令 + 合并 constraints，
            // 保证 task_assign 和 goal_execution 执行相同的规范化逻辑。
            val goalPayload = GoalExecutionPayload(
                goal = payload.goal,
                task_id = taskId,
                group_id = null,
                subtask_index = null,
                max_steps = payload.max_steps,
                timeout_ms = 0L,
                constraints = payload.constraints,
                source_runtime_posture = payload.source_runtime_posture,
                // ── PR-D: propagate V2 source dispatch metadata into goal execution ──
                execution_context = payload.execution_context
            )

            // ── PR-2 (Android): emit execution_started before LocalGoalExecutor ─────
            // PR-6: capture session identity at event emission.
            val durLocalStart = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = taskId,
                    task_id = taskId,
                    phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.executeLocalTaskAssign",
                    durable_session_id = durLocalStart?.durableSessionId,
                    session_continuity_epoch = durLocalStart?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at local-start emission.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at local-start emission.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at execution start.
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
                        ).wireValue
                )
            )

            val rawResult = UFOGalaxyApplication.localGoalExecutor.executeGoal(goalPayload)
            goalResult = rawResult.copy(
                correlation_id = taskId,
                device_id = localDeviceId,
                device_role = UFOGalaxyApplication.appSettings.deviceRole,
                latency_ms = rawResult.latency_ms ?: 0L,
                source_runtime_posture = payload.source_runtime_posture
            )

            // ── 通过 GOAL_EXECUTION_RESULT 回传（与 goal_execution 路径一致）────────
            // Android → Gateway 的结果回传统一使用 GOAL_EXECUTION_RESULT，
            // 与 goal_execution / parallel_subtask 共用同一 handler（_handle_goal_execution_result）。
            sendGoalResult(goalResult, traceId, routeMode)
            Log.i(
                TAG,
                "GOAL_EXECUTION_RESULT(task_assign) 已回传 task_id=$taskId " +
                    "status=${goalResult.status} latency=${goalResult.latency_ms}ms " +
                    "trace_id=$traceId route_mode=$routeMode"
            )
            updateNotification("任务 ${taskId.take(8)}: ${goalResult.status}")
            // ── PR-2: emit terminal execution event from task_assign result ──────────
            deviceExecutionEventSink.onEvent(
                buildTerminalExecutionEvent(
                    taskId = taskId,
                    result = goalResult,
                    stepIndex = goalResult.steps.size - 1,
                    source = "GalaxyConnectionService.executeLocalTaskAssign"
                )
            )
        } catch (err: Exception) {
            Log.e(TAG, "executeLocalTaskAssign 执行失败 task_id=$taskId", err)
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "task_assign_local_execution",
                errorType = "task_execution_failed",
                errorContext = err.message ?: "unknown execution error"
            )
            val errorResult = GoalResultPayload(
                task_id = taskId,
                correlation_id = taskId,
                status = EdgeExecutor.STATUS_ERROR,
                error = err.message ?: "unknown",
                group_id = null,
                subtask_index = null,
                latency_ms = 0L,
                device_id = localDeviceId,
                device_role = UFOGalaxyApplication.appSettings.deviceRole,
                // Carry source_runtime_posture from the inbound payload so the error result
                // has the same context fields as a normal execution result.
                source_runtime_posture = payload.source_runtime_posture
            )
            sendGoalResult(errorResult, traceId, routeMode)
            // ── PR-2: emit failed event on exception ─────────────────────────────────
            // PR-6: capture session identity at error emission.
            val durLocalFail = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = taskId,
                    task_id = taskId,
                    phase = DeviceExecutionEventPayload.PHASE_FAILED,
                    is_blocking = true,
                    blocking_reason = err.message ?: "execution_exception",
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.executeLocalTaskAssign",
                    durable_session_id = durLocalFail?.durableSessionId,
                    session_continuity_epoch = durLocalFail?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at exception point.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at exception point.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at execution failure.
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_FAILED
                        ).wireValue
                )
            )
        } finally {
            // Unblock local loop: always called even if edgeExecutor throws.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist result to OpenClawd memory.
        goalResult?.let { result ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = result.status,
                    summary = "task_assign (via LocalGoalExecutor): ${result.steps.size} step(s) executed",
                    steps = result.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = routeMode
                )
            }
        }
    }

    /**
     * Handles a [MsgType.GOAL_EXECUTION] message by delegating to
     * [AutonomousExecutionPipeline], which gates execution behind
     * [AppSettings.goalExecutionEnabled] and sends the structured
     * [GoalResultPayload] back to the gateway.
     *
     * Enforces [GoalExecutionPayload.effectiveTimeoutMs] via [withTimeout]. On timeout
     * a structured [EdgeExecutor.STATUS_TIMEOUT] result is returned so the server can
     * still perform correct aggregation. Deregisters the task from [taskCancelRegistry]
     * in its `finally` block.
     *
     * Result envelopes are sent as [MsgType.GOAL_EXECUTION_RESULT] with full trace context
     * ([traceId] from the inbound envelope, route_mode="cross_device") so every hop in the
     * AIP v3 pipeline carries consistent correlation metadata.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     *
     * @param taskId       task_id extracted from the inbound payload.
     * @param payloadJson  Raw JSON of the [GoalExecutionPayload].
     * @param inboundTraceId  trace_id from the inbound AIP envelope; null if absent.
     */
    private suspend fun handleGoalExecution(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val governanceDecision = AndroidExecutionGovernanceContract.evaluateAcceptance(
            executionType = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION,
            context = AndroidExecutionGovernanceContract.AcceptanceContext(
                activeTakeoverId = currentActiveTakeoverId()
            )
        )
        if (governanceDecision is AndroidExecutionGovernanceContract.AcceptanceDecision.Rejected) {
            val traceId = resolveExecutionTraceId(inboundTraceId)
            crossRepoRegressionHooks.recordExecutionReceipt(
                taskId = taskId,
                traceId = traceId,
                runtimeSessionId = UFOGalaxyApplication.runtimeSessionId,
                status = ScenarioOutcomeStatus.FAILED,
                reason = governanceDecision.reason
            )
            emitCrossRepoRegressionSnapshot("cross_repo_goal_execution_rejected")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "goal_execution_governance_gate",
                errorType = "execution_conflict",
                errorContext = governanceDecision.reason
            )
            sendGoalError(taskId, null, null, governanceDecision.reason, traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "goal_execution payload 解析失败: ${e.message}", e)
            val traceId = inboundTraceId ?: java.util.UUID.randomUUID().toString()
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "goal_execution_ingress",
                errorType = "goal_payload_parse_error",
                errorContext = e.message ?: "unknown parse error"
            )
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}", traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Continuity gate: reject execution if no active attached session is present.
        // Online goal_execution now enforces the same session continuity authority as the
        // offline-replay path (discardForDifferentSession), ensuring Android is never
        // "execution-time unprotected" while replay is fully continuity-gated.
        val activeSession = UFOGalaxyApplication.runtimeController.attachedSession.value
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = activeSession?.sessionId ?: "",
            activeSession = activeSession
        )
        if (identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession) {
            Log.w(TAG, "[CONTINUITY] goal_execution rejected: no active attached session task_id=$taskId")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "goal_execution_continuity_gate",
                errorType = AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
                errorContext = "no_active_session"
            )
            val gateTraceId = inboundTraceId?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "no_active_session", gateTraceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve inbound trace_id for full-chain correlation; generate only when absent.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null

        // ── PR-2 (Android): emit execution_started before entering the pipeline ──────
        // PR-6: capture session identity at event emission.
        val durGoalStart = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
        deviceExecutionEventSink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = taskId,
                task_id = taskId,
                phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
                device_id = localDeviceId,
                source_component = "GalaxyConnectionService.handleGoalExecution",
                durable_session_id = durGoalStart?.durableSessionId,
                session_continuity_epoch = durGoalStart?.sessionContinuityEpoch,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                // PR-8: carrier manifestation/presence hints at goal-start emission.
                carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                    UFOGalaxyApplication.appSettings.overlayReady,
                // PR-10: carrier runtime state at goal-start emission.
                carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                // PR-7B: explicit evidence presence kind at execution start.
                evidence_presence_kind =
                    AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                        DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
                    ).wireValue
            )
        )

        try {
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleGoalExecution(payload)
            }
            if (isActive) {
                // Enrich result with posture and send with full trace context.
                val enriched = result.takeIf { it.source_runtime_posture != null }
                    ?: result.copy(source_runtime_posture = payload.source_runtime_posture)
                val disposition = sendGoalResult(enriched, traceId, ROUTE_MODE_CROSS_DEVICE)
                finalResult = enriched
                Log.i(
                    TAG,
                    "goal_result 已产出 task_id=$taskId status=${enriched.status} " +
                        "latency=${enriched.latency_ms}ms trace_id=$traceId " +
                        "delivery=${disposition.name.lowercase()}"
                )
                // ── PR-2: emit terminal execution event from goal_execution result ─────
                deviceExecutionEventSink.onEvent(
                    buildTerminalExecutionEvent(
                        taskId = taskId,
                        result = enriched,
                        stepIndex = enriched.steps.size - 1,
                        source = "GalaxyConnectionService.handleGoalExecution"
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_TIMEOUT,
                mapOf("task_id" to taskId, "timeout_ms" to timeoutMs, "type" to "goal_execution")
            )
            Log.w(TAG, "[TASK:TIMEOUT] goal_execution timed out task_id=$taskId timeout_ms=$timeoutMs")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "goal_execution_pipeline",
                errorType = "goal_execution_timeout",
                errorContext = "timeout_ms=$timeoutMs"
            )
            val timeoutResult = buildTimeoutGoalResult(taskId, payload, timeoutMs)
            sendGoalResult(timeoutResult, traceId, ROUTE_MODE_CROSS_DEVICE)
            finalResult = timeoutResult
            // ── PR-2: emit failed event on timeout ────────────────────────────────────
            // PR-6: capture session identity at timeout emission.
            val durGoalTimeout = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = taskId,
                    task_id = taskId,
                    phase = DeviceExecutionEventPayload.PHASE_FAILED,
                    is_blocking = true,
                    blocking_reason = "timeout_ms=$timeoutMs",
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.handleGoalExecution",
                    durable_session_id = durGoalTimeout?.durableSessionId,
                    session_continuity_epoch = durGoalTimeout?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at timeout point.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at timeout point.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at execution failure (timeout).
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_FAILED
                        ).wireValue
                )
            )
        } finally {
            taskCancelRegistry.deregister(taskId)
            // Unblock local loop.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist to OpenClawd memory outside the try/finally to avoid blocking the finally.
        finalResult?.let { r ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = r.status,
                    summary = "goal_execution: latency=${r.latency_ms}ms",
                    steps = r.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = ROUTE_MODE_CROSS_DEVICE
                )
            }
        }
    }

    /**
     * Handles a [MsgType.PARALLEL_SUBTASK] message by delegating to
     * [AutonomousExecutionPipeline], which gates execution behind
     * [AppSettings.parallelExecutionEnabled] and sends the structured
     * [GoalResultPayload] back.
     *
     * Enforces [GoalExecutionPayload.effectiveTimeoutMs] via [withTimeout]. On timeout
     * a structured [EdgeExecutor.STATUS_TIMEOUT] result is returned. Deregisters the
     * task from [taskCancelRegistry] in its `finally` block.
     *
     * Result envelopes are sent as [MsgType.GOAL_EXECUTION_RESULT] with full trace context
     * ([traceId] from the inbound envelope, route_mode="cross_device") so every hop in the
     * AIP v3 pipeline carries consistent correlation metadata.
     *
     * Runs on [serviceScope] (IO dispatcher); all exceptions inside
     * [AutonomousExecutionPipeline] and [LocalGoalExecutor] are already mapped to
     * ERROR status.
     *
     * @param taskId       task_id extracted from the inbound payload.
     * @param payloadJson  Raw JSON of the [GoalExecutionPayload].
     * @param inboundTraceId  trace_id from the inbound AIP envelope; null if absent.
     */
    private suspend fun handleParallelSubtask(taskId: String, payloadJson: String, inboundTraceId: String?) {
        val governanceDecision = AndroidExecutionGovernanceContract.evaluateAcceptance(
            executionType = AndroidExecutionGovernanceContract.ExecutionType.PARALLEL_SUBTASK,
            context = AndroidExecutionGovernanceContract.AcceptanceContext(
                activeTakeoverId = currentActiveTakeoverId()
            )
        )
        if (governanceDecision is AndroidExecutionGovernanceContract.AcceptanceDecision.Rejected) {
            val traceId = resolveExecutionTraceId(inboundTraceId)
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "parallel_subtask_governance_gate",
                errorType = "execution_conflict",
                errorContext = governanceDecision.reason
            )
            sendGoalError(taskId, null, null, governanceDecision.reason, traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        val payload = try {
            gson.fromJson(payloadJson, GoalExecutionPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "parallel_subtask payload 解析失败: ${e.message}", e)
            val traceId = inboundTraceId ?: java.util.UUID.randomUUID().toString()
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "parallel_subtask_ingress",
                errorType = "parallel_payload_parse_error",
                errorContext = e.message ?: "unknown parse error"
            )
            sendGoalError(taskId, null, null, "bad_payload: ${e.message}", traceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Continuity gate: reject execution if no active attached session is present.
        // Ensures parallel_subtask online execution is protected by the same continuity
        // authority as the offline-replay path.
        val activeSession = UFOGalaxyApplication.runtimeController.attachedSession.value
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = activeSession?.sessionId ?: "",
            activeSession = activeSession
        )
        if (identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession) {
            Log.w(TAG, "[CONTINUITY] parallel_subtask rejected: no active attached session task_id=$taskId")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "parallel_subtask_continuity_gate",
                errorType = AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
                errorContext = "no_active_session"
            )
            val gateTraceId = inboundTraceId?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            sendGoalError(taskId, null, null, "no_active_session", gateTraceId, ROUTE_MODE_CROSS_DEVICE)
            taskCancelRegistry.deregister(taskId)
            return
        }

        // Pause any running local LoopController session.
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // Preserve inbound trace_id for full-chain correlation; generate only when absent.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        val meshId = payload.group_id?.takeIf { it.isNotBlank() }
        var meshLifecycleSession = meshId?.let {
            AndroidMeshLifecycleEmissionChain.create(meshId = it, taskId = taskId)
        }
        var meshDirectRuntime = updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot(meshId))

        val timeoutMs = payload.effectiveTimeoutMs
        var finalResult: GoalResultPayload? = null

        // ── PR-2 (Android): emit execution_started before parallel pipeline ───────
        // PR-6: capture session identity at event emission.
        val durParallelStart = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
        deviceExecutionEventSink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = payload.group_id?.takeIf { it.isNotBlank() } ?: taskId,
                task_id = taskId,
                phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
                device_id = localDeviceId,
                source_component = "GalaxyConnectionService.handleParallelSubtask",
                durable_session_id = durParallelStart?.durableSessionId,
                session_continuity_epoch = durParallelStart?.sessionContinuityEpoch,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                // PR-8: carrier manifestation/presence hints at parallel-start emission.
                carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                    UFOGalaxyApplication.appSettings.overlayReady,
                // PR-10: carrier runtime state at parallel-start emission.
                carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                // PR-7B: explicit evidence presence kind at execution start.
                evidence_presence_kind =
                    AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                        DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
                    ).wireValue
            )
        )

        try {
            meshLifecycleSession?.let { meshSession ->
                updateMeshRuntimeSignalState(
                    collaborationState = CollaborationLifecycleState.SUBTASK_ASSIGNED,
                    participationActive = true
                )
                val activeMeshId = meshSession.meshId
                if (meshDirectRuntime.route == AndroidMeshDirectRuntimeContract.DirectPathRoute.DIRECT_PEER) {
                    val joinSent = webSocketClient.sendMeshJoin(
                        meshId = activeMeshId,
                        role = "participant",
                        capabilities = listOf("parallel_subtask")
                    )
                    meshDirectRuntime = updateMeshDirectRuntimeSnapshot(
                        AndroidMeshDirectRuntimeContract.onDirectSendAttempt(
                            snapshot = meshDirectRuntime,
                            stage = AndroidMeshDirectRuntimeContract.AttemptStage.JOIN,
                            succeeded = joinSent
                        )
                    )
                    meshLifecycleSession = AndroidMeshLifecycleEmissionChain.onJoin(meshSession, emitted = joinSent)
                    if (!joinSent) {
                        emitMeshDirectFallbackTransition(
                            taskId = taskId,
                            flowId = activeMeshId,
                            fallbackReason = AndroidMeshDirectRuntimeContract.REASON_SEND_FAILED_JOIN
                        )
                        emitRuntimeDiagnostics(
                            taskId = taskId,
                            nodeName = "parallel_subtask_mesh_direct",
                            errorType = "mesh_direct_join_failed",
                            errorContext = formatMeshDirectErrorContext(activeMeshId, meshDirectRuntime.reasonCodes)
                        )
                    }
                } else {
                    emitMeshDirectFallbackTransition(
                        taskId = taskId,
                        flowId = activeMeshId,
                        fallbackReason = formatMeshDirectReasonCodes(meshDirectRuntime.reasonCodes)
                    )
                    emitRuntimeDiagnostics(
                        taskId = taskId,
                        nodeName = "parallel_subtask_mesh_direct",
                        errorType = "mesh_direct_unavailable",
                        errorContext = formatMeshDirectErrorContext(activeMeshId, meshDirectRuntime.reasonCodes)
                    )
                }
                updateMeshRuntimeSignalState(collaborationState = CollaborationLifecycleState.EXECUTING)
                sendDeviceStateSnapshot()
            }
            val result = withTimeout(timeoutMs) {
                UFOGalaxyApplication.autonomousExecutionPipeline.handleParallelSubtask(payload)
            }
            if (isActive) {
                // Enrich result with posture and send with full trace context.
                val enriched = result.takeIf { it.source_runtime_posture != null }
                    ?: result.copy(source_runtime_posture = payload.source_runtime_posture)
                val disposition = sendGoalResult(enriched, traceId, ROUTE_MODE_CROSS_DEVICE)
                finalResult = enriched
                Log.i(
                    TAG,
                    "goal_result (parallel) 已产出 task_id=$taskId status=${enriched.status} " +
                        "group_id=${enriched.group_id} subtask_index=${enriched.subtask_index} " +
                        "latency=${enriched.latency_ms}ms trace_id=$traceId " +
                        "delivery=${disposition.name.lowercase()}"
                )
                meshLifecycleSession?.let { meshSession ->
                    val activeMeshId = meshSession.meshId
                    if (meshDirectRuntime.route == AndroidMeshDirectRuntimeContract.DirectPathRoute.DIRECT_PEER) {
                        val meshResultSent = webSocketClient.sendMeshResult(
                            meshId = activeMeshId,
                            taskId = taskId,
                            status = if (enriched.status == EdgeExecutor.STATUS_SUCCESS) "success" else "error",
                            results = listOf(
                                MeshSubtaskResult(
                                    device_id = localDeviceId,
                                    subtask_id = "${meshId}_${enriched.subtask_index ?: 0}",
                                    status = if (enriched.status == EdgeExecutor.STATUS_SUCCESS) "success" else "error",
                                    output = enriched.result_summary,
                                    error = enriched.error
                                )
                            ),
                            summary = "parallel_subtask:${enriched.status}",
                            latencyMs = enriched.latency_ms ?: 0L
                        )
                        meshDirectRuntime = updateMeshDirectRuntimeSnapshot(
                            AndroidMeshDirectRuntimeContract.onDirectSendAttempt(
                                snapshot = meshDirectRuntime,
                                stage = AndroidMeshDirectRuntimeContract.AttemptStage.RESULT,
                                succeeded = meshResultSent
                            )
                        )
                        meshLifecycleSession = AndroidMeshLifecycleEmissionChain.onResult(meshSession, emitted = meshResultSent)
                        if (!meshResultSent) {
                            emitMeshDirectFallbackTransition(
                                taskId = taskId,
                                flowId = activeMeshId,
                                fallbackReason = AndroidMeshDirectRuntimeContract.REASON_SEND_FAILED_RESULT
                            )
                            emitRuntimeDiagnostics(
                                taskId = taskId,
                                nodeName = "parallel_subtask_mesh_direct",
                                errorType = "mesh_direct_result_failed",
                                errorContext = formatMeshDirectErrorContext(activeMeshId, meshDirectRuntime.reasonCodes)
                            )
                        }
                    }
                }
                // ── PR-2: emit terminal execution event from parallel result ──────────
                deviceExecutionEventSink.onEvent(
                    buildTerminalExecutionEvent(
                        taskId = taskId,
                        result = enriched,
                        stepIndex = enriched.steps.size - 1,
                        source = "GalaxyConnectionService.handleParallelSubtask",
                        flowId = payload.group_id?.takeIf { it.isNotBlank() } ?: taskId
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_TIMEOUT,
                mapOf(
                    "task_id" to taskId, "timeout_ms" to timeoutMs,
                    "type" to "parallel_subtask",
                    "group_id" to payload.group_id, "subtask_index" to payload.subtask_index
                )
            )
            Log.w(
                TAG,
                "[TASK:TIMEOUT] parallel_subtask timed out task_id=$taskId " +
                    "group_id=${payload.group_id} subtask_index=${payload.subtask_index} timeout_ms=$timeoutMs"
            )
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "parallel_subtask_pipeline",
                errorType = "parallel_subtask_timeout",
                errorContext = "timeout_ms=$timeoutMs group_id=${payload.group_id ?: ""}"
            )
            val timeoutResult = buildTimeoutGoalResult(taskId, payload, timeoutMs)
            sendGoalResult(timeoutResult, traceId, ROUTE_MODE_CROSS_DEVICE)
            finalResult = timeoutResult
            // ── PR-2: emit failed event on parallel subtask timeout ───────────────────
            // PR-6: capture session identity at timeout emission.
            val durParallelTimeout = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = payload.group_id?.takeIf { it.isNotBlank() } ?: taskId,
                    task_id = taskId,
                    phase = DeviceExecutionEventPayload.PHASE_FAILED,
                    is_blocking = true,
                    blocking_reason = "timeout_ms=$timeoutMs",
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.handleParallelSubtask",
                    durable_session_id = durParallelTimeout?.durableSessionId,
                    session_continuity_epoch = durParallelTimeout?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at parallel timeout.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at parallel timeout.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at execution failure (timeout).
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_FAILED
                        ).wireValue
                )
            )
        } finally {
            meshLifecycleSession?.let { meshSessionAtClose ->
                val leaveReason = when (finalResult?.status) {
                    EdgeExecutor.STATUS_SUCCESS -> "task_complete"
                    EdgeExecutor.STATUS_CANCELLED -> "cancelled"
                    else -> "error"
                }
                updateMeshRuntimeSignalState(
                    collaborationState = when (finalResult?.status) {
                        EdgeExecutor.STATUS_SUCCESS -> CollaborationLifecycleState.COMPLETED
                        EdgeExecutor.STATUS_CANCELLED -> CollaborationLifecycleState.CANCELLED
                        else -> CollaborationLifecycleState.FAILED
                    },
                    participationActive = false
                )
                if (meshSessionAtClose.shouldAttemptLeave) {
                    if (meshDirectRuntime.lastAttemptSucceeded == true) {
                        val leaveSent = webSocketClient.sendMeshLeave(meshSessionAtClose.meshId, leaveReason)
                        meshDirectRuntime = updateMeshDirectRuntimeSnapshot(
                            AndroidMeshDirectRuntimeContract.onDirectSendAttempt(
                                snapshot = meshDirectRuntime,
                                stage = AndroidMeshDirectRuntimeContract.AttemptStage.LEAVE,
                                succeeded = leaveSent
                            )
                        )
                        meshLifecycleSession = AndroidMeshLifecycleEmissionChain.onLeave(
                            state = meshSessionAtClose,
                            emitted = leaveSent,
                            reason = leaveReason
                        )
                    }
                }
                meshLifecycleSession?.let { crossRepoRegressionHooks.recordMeshLifecycle(it) }
                emitCrossRepoRegressionSnapshot("cross_repo_mesh_lifecycle_state")
                meshLifecycleSession?.toWireMap()?.let { payload ->
                    GalaxyLogger.log(
                        TAG,
                        payload + mapOf("event" to "parallel_subtask_mesh_lifecycle_state")
                    )
                    if (payload["mesh_leave_emitted"] == false) {
                        emitRuntimeDiagnostics(
                            taskId = taskId,
                            nodeName = "parallel_subtask_mesh_lifecycle",
                            errorType = "mesh_leave_unsent",
                            errorContext = "mesh_id=${meshSessionAtClose.meshId} leave_reason=$leaveReason"
                        )
                    }
                }
                sendDeviceStateSnapshot()
            }
            taskCancelRegistry.deregister(taskId)
            // Unblock local loop.
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist to OpenClawd memory.
        finalResult?.let { r ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = payload.goal,
                    status = r.status,
                    summary = "parallel_subtask idx=${payload.subtask_index}: latency=${r.latency_ms}ms",
                    steps = r.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = ROUTE_MODE_CROSS_DEVICE
                )
            }
        }
    }

    /**
     * Handles a [MsgType.TASK_CANCEL] request.
     *
     * Looks up the task in [taskCancelRegistry]:
     * - If found: cancels the coroutine and sends a `cancelled` [CancelResultPayload].
     * - If not found: the task already completed or never existed; sends a `no_op` reply.
     *
     * This operation is idempotent — repeated cancel requests for the same task_id will
     * return `no_op` after the first cancel succeeds.
     */
    private fun handleTaskCancel(taskId: String, cancelPayloadJson: String) {
        val cancelPayload = try {
            gson.fromJson(cancelPayloadJson, TaskCancelPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_cancel payload 解析失败: ${e.message}", e)
            // Best-effort: still try to cancel by taskId
            TaskCancelPayload(task_id = taskId)
        }

        val wasRunning = taskCancelRegistry.cancel(taskId)

        if (wasRunning) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_CANCEL,
                mapOf(
                    "task_id" to taskId,
                    "group_id" to cancelPayload.group_id,
                    "subtask_index" to cancelPayload.subtask_index,
                    "was_running" to true
                )
            )
            Log.i(TAG, "[TASK:CANCEL] 已取消运行中的任务 task_id=$taskId")
        } else {
            GalaxyLogger.log(
                GalaxyLogger.TAG_TASK_CANCEL,
                mapOf("task_id" to taskId, "was_running" to false, "result" to "no_op")
            )
            Log.i(TAG, "[TASK:CANCEL] 任务未找到（已完成或未启动）task_id=$taskId")
        }

        val cancelResult = CancelResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = if (wasRunning) EdgeExecutor.STATUS_CANCELLED else "no_op",
            was_running = wasRunning,
            group_id = cancelPayload.group_id,
            subtask_index = cancelPayload.subtask_index,
            device_id = localDeviceId,
            error = if (!wasRunning) "task not found or already completed" else null
        )
        val envelope = AipMessage(
            type = MsgType.CANCEL_RESULT,
            payload = cancelResult,
            correlation_id = taskId,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId, MsgType.CANCEL_RESULT)
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        if (!sent) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "cancel_result_send_failed",
                    "task_id" to taskId
                )
            )
        }
    }

    // ── PR-H: HandoffEnvelopeV2 native consumption handler ───────────────────────────────

    /**
     * Handles an inbound [MsgType.HANDOFF_ENVELOPE_V2] message by executing the enclosed
     * goal via the canonical Android execution chain and sending back a
     * [MsgType.HANDOFF_ENVELOPE_V2_RESULT] uplink with the ACK / outcome / failure state.
     *
     * ## Execution path
     * 1. Log the envelope-received event for V2 observability.
     * 2. Parse the raw JSON into [HandoffEnvelopeV2] — reject with "bad_payload" on failure.
     * 3. Send an immediate [MsgType.HANDOFF_ENVELOPE_V2_RESULT] ACK (status="ack") so V2
     *    knows the envelope was consumed on-device.
     * 4. Continuity gate: reject with a failure result (status="failure",
     *    error="no_active_session") if no active [com.ufo.galaxy.runtime.AttachedRuntimeSession]
     *    is present.  Applies the same continuity authority as [handleTaskAssign],
     *    [handleGoalExecution], [handleParallelSubtask], and [handleTakeoverRequest], closing
     *    the split reality where those entry points were continuity-gated but
     *    handoff_envelope_v2 was not.
     * 5. Pause the local [com.ufo.galaxy.loop.LoopController] session via
     *    [RuntimeController.onRemoteTaskStarted].
     * 6. Map the envelope to a [GoalExecutionPayload] and delegate to
     *    [com.ufo.galaxy.agent.LocalGoalExecutor.executeGoal] (same path as task_assign /
     *    goal_execution for consistency).
     * 7. Send [MsgType.HANDOFF_ENVELOPE_V2_RESULT] with status, result_summary, and all
     *    echoed identity fields for V2 end-to-end correlation.
     * 8. Unblock the local loop via [RuntimeController.onRemoteTaskFinished].
     *
     * ## Error / exception handling
     * - Parse failure: immediate failure result with reason="bad_payload".
     * - Continuity gate failure: failure result with error="no_active_session".
     * - Execution failure: failure result with the exception message.
     * - Timeout: failure result with status="timeout".
     * All non-success outcomes are also routed through [emitRuntimeDiagnostics] so the
     * gateway can classify failure types.
     *
     * ## Contract alignment with V2
     * All fields forwarded in the inbound envelope are echoed in the result payload:
     * [dispatch_plan_id], [continuity_token], [dispatch_intent], [execution_context],
     * [executor_target_type], and [source_runtime_posture].  This ensures V2 can perform
     * full-chain correlation without re-querying Android for metadata.
     *
     * @param taskId          task_id extracted from the inbound payload.
     * @param payloadJson     Raw JSON of the [HandoffEnvelopeV2] payload.
     * @param inboundTraceId  trace_id from the inbound AIP envelope; null if absent.
     */
    private suspend fun handleHandoffEnvelopeV2(
        taskId: String,
        payloadJson: String,
        inboundTraceId: String?
    ) {
        val consumedAtMs = System.currentTimeMillis()

        // ── Step 1: structured envelope-received log ──────────────────────────────
        Log.i(TAG, "[PR-H:HANDOFF_V2] envelope received task_id=$taskId trace_id=$inboundTraceId")
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "handoff_envelope_v2_received",
                "task_id" to taskId,
                "trace_id" to (inboundTraceId ?: ""),
                "consumed_at_ms" to consumedAtMs
            )
        )

        // ── Step 2: parse ─────────────────────────────────────────────────────────
        val envelope = try {
            gson.fromJson(payloadJson, HandoffEnvelopeV2::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "[PR-H:HANDOFF_V2] envelope parse failed task_id=$taskId: ${e.message}", e)
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "handoff_v2_ingress",
                errorType = "handoff_v2_parse_error",
                errorContext = e.message ?: "unknown parse error"
            )
            val traceId = inboundTraceId ?: java.util.UUID.randomUUID().toString()
            sendHandoffEnvelopeV2Result(
                HandoffEnvelopeV2ResultPayload(
                    handoff_id = taskId,
                    task_id = taskId,
                    trace_id = traceId,
                    correlation_id = taskId,
                    status = HandoffEnvelopeV2ResultPayload.STATUS_FAILURE,
                    error = "bad_payload: ${e.message}",
                    consumed_at_ms = consumedAtMs,
                    device_id = localDeviceId,
                    route_mode = ROUTE_MODE_CROSS_DEVICE
                ),
                traceId
            )
            return
        }

        // Preserve inbound trace_id; generate only when absent.
        val traceId = inboundTraceId?.takeIf { it.isNotBlank() }
            ?: envelope.trace_id.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        // Resolve stable handoff identifier; fall back to task_id for legacy senders.
        val handoffId = envelope.handoff_id?.takeIf { it.isNotBlank() } ?: taskId

        Log.i(
            TAG,
            "[PR-H:HANDOFF_V2] envelope parsed task_id=$taskId trace_id=$traceId " +
                "handoff_id=$handoffId goal=${envelope.goal.take(60)} exec_mode=${envelope.exec_mode} " +
                "route_mode=${envelope.route_mode} dispatch_plan_id=${envelope.dispatch_plan_id}"
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "handoff_envelope_v2_parsed",
                "task_id" to taskId,
                "trace_id" to traceId,
                "handoff_id" to handoffId,
                "exec_mode" to envelope.exec_mode,
                "route_mode" to envelope.route_mode,
                "dispatch_intent" to (envelope.dispatch_intent ?: ""),
                "dispatch_plan_id" to (envelope.dispatch_plan_id ?: ""),
                "continuity_token" to (envelope.continuity_token ?: ""),
                "executor_target_type" to (envelope.executor_target_type ?: ""),
                "is_resumable" to (envelope.is_resumable?.toString() ?: "")
            )
        )

        // ── Step 2.5: send immediate ACK ─────────────────────────────────────────
        sendHandoffEnvelopeV2Result(
            HandoffEnvelopeV2ResultPayload(
                handoff_id = handoffId,
                task_id = taskId,
                trace_id = traceId,
                correlation_id = taskId,
                status = HandoffEnvelopeV2ResultPayload.STATUS_ACK,
                consumed_at_ms = consumedAtMs,
                device_id = localDeviceId,
                route_mode = ROUTE_MODE_CROSS_DEVICE,
                dispatch_plan_id = envelope.dispatch_plan_id,
                continuity_token = envelope.continuity_token,
                dispatch_intent = envelope.dispatch_intent,
                execution_context = envelope.execution_context,
                executor_target_type = envelope.executor_target_type,
                source_runtime_posture = envelope.source_runtime_posture
            ),
            traceId
        )

        // ── Step 4: continuity gate ───────────────────────────────────────────────
        // Reject execution if no active attached session is present.
        // Aligns handoff_envelope_v2 online execution with the replay/recovery continuity
        // authority enforced on all other execution entry points (task_assign, goal_execution,
        // parallel_subtask, takeover), closing the split reality where those paths were
        // continuity-gated but handoff_envelope_v2 was not.
        val handoffActiveSession = UFOGalaxyApplication.runtimeController.attachedSession.value
        val handoffIdentityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = handoffActiveSession?.sessionId ?: "",
            activeSession = handoffActiveSession
        )
        if (handoffIdentityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession) {
            Log.w(TAG, "[CONTINUITY] handoff_envelope_v2 rejected: no active attached session task_id=$taskId trace_id=$traceId")
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "handoff_v2_continuity_gate",
                errorType = AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
                errorContext = "no_active_session"
            )
            sendHandoffEnvelopeV2Result(
                HandoffEnvelopeV2ResultPayload(
                    handoff_id = handoffId,
                    task_id = taskId,
                    trace_id = traceId,
                    correlation_id = taskId,
                    status = HandoffEnvelopeV2ResultPayload.STATUS_FAILURE,
                    error = "no_active_session",
                    consumed_at_ms = consumedAtMs,
                    device_id = localDeviceId,
                    route_mode = ROUTE_MODE_CROSS_DEVICE,
                    dispatch_plan_id = envelope.dispatch_plan_id,
                    continuity_token = envelope.continuity_token,
                    dispatch_intent = envelope.dispatch_intent,
                    execution_context = envelope.execution_context,
                    executor_target_type = envelope.executor_target_type,
                    source_runtime_posture = envelope.source_runtime_posture
                ),
                traceId
            )
            return
        }

        // ── Step 5: pause local loop ──────────────────────────────────────────────
        UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

        // ── Step 6: build GoalExecutionPayload and execute ────────────────────────
        var goalResult: GoalResultPayload? = null
        try {
            Log.i(TAG, "[PR-H:HANDOFF_V2] dispatch start task_id=$taskId trace_id=$traceId")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_v2_dispatch_start",
                    "task_id" to taskId,
                    "trace_id" to traceId
                )
            )
            updateNotification("HandoffV2 任务 ${taskId.take(8)}…")

            val goalPayload = GoalExecutionPayload(
                task_id = taskId,
                goal = envelope.goal,
                constraints = envelope.constraints,
                max_steps = 10,
                timeout_ms = 0L,
                source_runtime_posture = envelope.source_runtime_posture,
                execution_context = envelope.execution_context,
                executor_target_type = envelope.executor_target_type,
                continuity_token = envelope.continuity_token,
                recovery_context = envelope.recovery_context,
                is_resumable = envelope.is_resumable,
                interruption_reason = envelope.interruption_reason,
                dispatch_plan_id = envelope.dispatch_plan_id,
                source_dispatch_strategy = envelope.source_dispatch_strategy
            )

            // ── PR-2: emit execution_started before handoff_v2 pipeline ─────────────
            // PR-6: capture session identity at event emission.
            val durHandoffStart = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = taskId,
                    task_id = taskId,
                    phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.handleHandoffEnvelopeV2",
                    durable_session_id = durHandoffStart?.durableSessionId,
                    session_continuity_epoch = durHandoffStart?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at handoff-start emission.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at handoff-start emission.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at execution start.
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
                        ).wireValue
                )
            )

            val rawResult = UFOGalaxyApplication.localGoalExecutor.executeGoal(goalPayload)
            goalResult = rawResult.copy(
                correlation_id = taskId,
                device_id = localDeviceId,
                device_role = UFOGalaxyApplication.appSettings.deviceRole,
                latency_ms = rawResult.latency_ms ?: 0L,
                source_runtime_posture = envelope.source_runtime_posture,
                executor_target_type = envelope.executor_target_type,
                continuity_token = envelope.continuity_token,
                dispatch_plan_id = envelope.dispatch_plan_id
            )

            Log.i(
                TAG,
                "[PR-H:HANDOFF_V2] dispatch end task_id=$taskId trace_id=$traceId " +
                    "status=${goalResult.status} latency=${goalResult.latency_ms}ms"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_v2_dispatch_end",
                    "task_id" to taskId,
                    "trace_id" to traceId,
                    "status" to goalResult.status,
                    "latency_ms" to (goalResult.latency_ms ?: 0L)
                )
            )
            updateNotification("HandoffV2 ${taskId.take(8)}: ${goalResult.status}")
            // ── PR-2: emit terminal execution event from handoff_v2 result ───────────
            deviceExecutionEventSink.onEvent(
                buildTerminalExecutionEvent(
                    taskId = taskId,
                    result = goalResult,
                    stepIndex = goalResult.steps.size - 1,
                    source = "GalaxyConnectionService.handleHandoffEnvelopeV2"
                )
            )

            sendHandoffEnvelopeV2Result(
                HandoffEnvelopeV2ResultPayload(
                    handoff_id = handoffId,
                    task_id = taskId,
                    trace_id = traceId,
                    correlation_id = taskId,
                    // Map EdgeExecutor terminal statuses to the stable V2 protocol vocabulary.
                    // STATUS_SUCCESS ("success") → STATUS_RESULT ("result").
                    // All other terminal statuses (STATUS_ERROR, STATUS_TIMEOUT, STATUS_CANCELLED)
                    // map to STATUS_FAILURE ("failure") so V2 sees a single, unambiguous failure
                    // signal regardless of the on-device error class.
                    status = if (goalResult.status == EdgeExecutor.STATUS_SUCCESS)
                        HandoffEnvelopeV2ResultPayload.STATUS_RESULT
                    else
                        HandoffEnvelopeV2ResultPayload.STATUS_FAILURE,
                    result_summary = goalResult.result
                        ?: "handoff_v2: ${goalResult.status}",
                    error = goalResult.error,
                    consumed_at_ms = consumedAtMs,
                    device_id = localDeviceId,
                    route_mode = ROUTE_MODE_CROSS_DEVICE,
                    dispatch_plan_id = envelope.dispatch_plan_id,
                    continuity_token = envelope.continuity_token,
                    dispatch_intent = envelope.dispatch_intent,
                    execution_context = envelope.execution_context,
                    executor_target_type = envelope.executor_target_type,
                    source_runtime_posture = envelope.source_runtime_posture
                ),
                traceId
            )
        } catch (err: Exception) {
            Log.e(TAG, "[PR-H:HANDOFF_V2] execution failed task_id=$taskId trace_id=$traceId", err)
            emitRuntimeDiagnostics(
                taskId = taskId,
                nodeName = "handoff_v2_execution",
                errorType = "handoff_v2_execution_failed",
                errorContext = err.message ?: "unknown execution error"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "handoff_v2_execution_failed",
                    "task_id" to taskId,
                    "trace_id" to traceId,
                    "error" to (err.message ?: "unknown")
                )
            )
            sendHandoffEnvelopeV2Result(
                HandoffEnvelopeV2ResultPayload(
                    handoff_id = handoffId,
                    task_id = taskId,
                    trace_id = traceId,
                    correlation_id = taskId,
                    status = HandoffEnvelopeV2ResultPayload.STATUS_FAILURE,
                    error = "handoff_v2_execution_failed: ${err.message ?: "unknown"}",
                    consumed_at_ms = consumedAtMs,
                    device_id = localDeviceId,
                    route_mode = ROUTE_MODE_CROSS_DEVICE,
                    dispatch_plan_id = envelope.dispatch_plan_id,
                    continuity_token = envelope.continuity_token,
                    dispatch_intent = envelope.dispatch_intent,
                    execution_context = envelope.execution_context,
                    executor_target_type = envelope.executor_target_type,
                    source_runtime_posture = envelope.source_runtime_posture
                ),
                traceId
            )
        } finally {
            // ── Step 8: unblock local loop ────────────────────────────────────────
            UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
        }

        // Persist to OpenClawd memory (non-blocking, outside try/finally).
        goalResult?.let { result ->
            serviceScope.launch(Dispatchers.IO) {
                storeMemoryEntry(
                    taskId = taskId,
                    goal = envelope.goal,
                    status = result.status,
                    summary = "handoff_envelope_v2: ${result.steps.size} step(s)",
                    steps = result.steps.map { "${it.action}: ${if (it.success) "ok" else (it.error ?: "fail")}" },
                    routeMode = ROUTE_MODE_CROSS_DEVICE
                )
            }
        }
    }

    /**
     * Sends a [HandoffEnvelopeV2ResultPayload] as a [MsgType.HANDOFF_ENVELOPE_V2_RESULT]
     * AIP v3 envelope uplink.
     *
     * Emits a structured log entry at each call so V2 can correlate the result with its
     * original dispatch without relying on gateway-side state.
     *
     * @param result   The populated [HandoffEnvelopeV2ResultPayload] to transmit.
     * @param traceId  End-to-end trace identifier; echoed in the AIP v3 envelope.
     */
    private fun sendHandoffEnvelopeV2Result(
        result: HandoffEnvelopeV2ResultPayload,
        traceId: String
    ) {
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = localDeviceId,
            trace_id = traceId,
            route_mode = result.route_mode,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(
                result.task_id, MsgType.HANDOFF_ENVELOPE_V2_RESULT, traceId
            )
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(
            TAG,
            "[PR-H:HANDOFF_V2_RESULT] ack/result emitted task_id=${result.task_id} " +
                "trace_id=$traceId status=${result.status} sent=$sent"
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "handoff_envelope_v2_result_emitted",
                "task_id" to result.task_id,
                "trace_id" to traceId,
                "status" to result.status,
                "sent" to sent,
                "dispatch_plan_id" to (result.dispatch_plan_id ?: ""),
                "continuity_token" to (result.continuity_token ?: ""),
                "executor_target_type" to (result.executor_target_type ?: "")
            )
        )
    }
    /**
     * Sends a [GoalResultPayload] as a [MsgType.GOAL_EXECUTION_RESULT] envelope.
     *
     * This is the **single canonical result-send path** for all uplink result types:
     * - task_assign → GOAL_EXECUTION_RESULT
     * - goal_execution → GOAL_EXECUTION_RESULT
     * - parallel_subtask → GOAL_EXECUTION_RESULT
     * - error / timeout results → GOAL_EXECUTION_RESULT
     *
     * At emission time, this method enriches [result] with three unified-contract fields
     * that must be set centrally rather than by individual callers:
     * - [GoalResultPayload.normalized_status]: canonical result kind ("final_completion" /
     *   "cancellation" / "disabled" / "failure") derived via [canonicalResultKind].  All
     *   result paths — success, error, timeout, cancellation, disabled — map to one of
     *   these four stable values so V2 can classify results without raw-status guessing.
     * - [GoalResultPayload.runtime_session_id]: stable per-app-launch session identifier
     *   from [UFOGalaxyApplication.runtimeSessionId] echoed into the payload for replay
     *   self-containment.  When a result is later replayed from the offline queue, V2 can
     *   correlate it with the originating runtime session without re-querying the device.
     * - [GoalResultPayload.result_summary]: normalised human-readable one-line summary,
     *   populated from [GoalResultPayload.result] when not already set by the caller.
     *   This mirrors the same field in [HandoffEnvelopeV2ResultPayload]
     *   so all uplink result payloads carry a consistent named summary field.
     * - [GoalResultPayload.result_returned], [GoalResultPayload.completion_signaled],
     *   [GoalResultPayload.closure_ready_for_acceptance]: explicit completion visibility
     *   booleans aligned with execution-event and reconciliation-signal closure semantics.
     * - [GoalResultPayload.unified_lifecycle_phase] /
     *   [GoalResultPayload.unified_lifecycle_schema_version]: lifecycle phase at result
     *   emission time, aligned with snapshot/event lifecycle phase surfaces.
     * - Android-side runtime context fields:
     *   [GoalResultPayload.participation_tier], [GoalResultPayload.execution_mode_state],
     *   [GoalResultPayload.cross_device_eligibility], [GoalResultPayload.local_mode_gate_deferred],
     *   and [GoalResultPayload.local_inference_available], allowing V2 closure/governance paths
     *   to consume Android-side mode/participation truth from result uplinks directly.
     *
     * The AIP v3 envelope also propagates [GoalResultPayload.dispatch_trace_id] and
     * [GoalResultPayload.source_runtime_posture] so that V2 observability and posture
     * routing have consistent field values at both the payload and envelope layer.
     *
     * [traceId] and [routeMode] are nullable: when the inbound envelope does not carry
     * trace context (e.g. legacy callers or parse-error paths), both fields are omitted
     * from the outbound envelope rather than defaulting to an empty string.
     * The V2 gateway handler (_handle_goal_execution_result) accepts missing trace context.
     */
    private enum class ResultDeliveryDisposition {
        DIRECT_SENT,
        OFFLINE_QUEUED,
        SEND_FAILED
    }

    private fun sendGoalResult(
        result: GoalResultPayload,
        traceId: String?,
        routeMode: String?
    ): ResultDeliveryDisposition {
        val runtimeSession = UFOGalaxyApplication.runtimeSessionId
        val needsRuntimeContext =
            result.participation_tier == null ||
                result.execution_mode_state == null ||
                result.cross_device_eligibility == null ||
                result.local_mode_gate_deferred == null ||
                result.local_inference_available == null ||
                result.unified_lifecycle_phase == null ||
                result.unified_lifecycle_schema_version == null
        val modeState = if (needsRuntimeContext) {
            UFOGalaxyApplication.appSettings.authoritativeModeState(
                wsConnected = webSocketClient.isConnected(),
                capabilityDegraded = managerStateDegraded()
            )
        } else {
            null
        }
        val participationSnapshot = if (needsRuntimeContext && modeState != null) {
            val dispatchReadiness = UFOGalaxyApplication.runtimeController.currentDispatchReadiness()
            UFOGalaxyApplication.runtimeController.evaluateAuthoritativeParticipationSnapshot(
                readinessSatisfied = modeState.crossDeviceEligibility,
                distributedRuntimeActivity = UFOGalaxyApplication.runtimeController.activeTaskId != null,
                capabilityVisible = hasVisibleCrossDeviceCapability(
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    sessionIsAttached = dispatchReadiness.sessionIsAttached
                )
            )
        } else {
            null
        }
        val localInferenceAvailable = if (needsRuntimeContext) {
            localInferenceAvailable()
        } else {
            null
        }
        val governanceTruth = currentGovernanceTruth()
        // ── Enrich payload with unified-contract fields (always set at the single emission layer) ──
        // Resolve participation tier with guaranteed non-null fallback (INV-UTU-01).
        val resolvedParticipationTier = result.participation_tier
            ?: participationSnapshot?.let {
                AndroidAuthoritativeParticipationTruth.participationTierFor(it.state).wireValue
            }
            ?: AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH.wireValue
        val resolvedModeState = result.execution_mode_state ?: modeState?.executionModeState
            ?: LocalExecutionModeGate.ExecutionModeState.INACTIVE.wireValue
        val resolvedCrossDeviceElig = result.cross_device_eligibility ?: modeState?.crossDeviceEligibility ?: false
        val resolvedIsHoldState = result.local_mode_gate_deferred ?: modeState?.isHoldState ?: false
        // Derive unified constraint semantics for coherent boolean fields.
        // isConstrained: manager degraded state signals runtime resource/health constraints.
        // isDeferred: hold state from the mode gate signals temporary unavailability.
        val resultManagerDegraded = managerStateDegraded()
        val constraintSem = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isConstrained = resultManagerDegraded,
            isDeferred = false,
            isLocalModeGateHold = resolvedIsHoldState,
            isExecutionBusy = UFOGalaxyApplication.runtimeController.activeTaskId != null,
            isHold = false
        )
        // Derive unified local capability state.
        val resolvedLocalInference = result.local_inference_available ?: localInferenceAvailable
        val localLlmReady = result.local_llm_ready ?: localLlmReady()
        val accessibilityReady = result.accessibility_ready
            ?: UFOGalaxyApplication.appSettings.accessibilityReady
        val localCapState = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = localLlmReady,
            localInferenceAvailable = resolvedLocalInference,
            accessibilityReady = accessibilityReady,
            isDegraded = managerStateDegraded()
        )
        val normalizedLifecycleStatus =
            UgcpSharedSchemaAlignment.normalizeLifecycleStatus(result.status)
        val isHoldStatus = PolicyRoutingContext.isHoldStatus(result.status)
        // A hold status is non-terminal ("temporarily unavailable; retry later"), so it must not
        // trigger completion/acceptance closure semantics. Non-hold goal results are treated as
        // terminal contributions for this execution chain.
        val goalResultCompletionVisibility = if (isHoldStatus) {
            AndroidMissionCompletionSemanticsContract.CompletionVisibility(
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false
            )
        } else {
            AndroidMissionCompletionSemanticsContract.CompletionVisibility(
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true
            )
        }
        val resultDispatchReadiness = UFOGalaxyApplication.runtimeController.currentDispatchReadiness()
        val resultCapabilityVisible = hasVisibleCrossDeviceCapability(
            crossDeviceEligibility = resolvedCrossDeviceElig,
            sessionIsAttached = resultDispatchReadiness.sessionIsAttached
        )
        val resultDurableParticipantId = try {
            UFOGalaxyApplication.appSettings.durableParticipantId.takeIf { it.isNotBlank() }
        } catch (e: IllegalStateException) {
            Log.d(TAG, "[GOAL_RESULT] Failed to resolve durableParticipantId during result emission (IllegalStateException)", e)
            null
        } catch (e: SecurityException) {
            Log.d(TAG, "[GOAL_RESULT] Failed to resolve durableParticipantId due to security restrictions during result emission", e)
            null
        }
        val resultUnifiedLifecyclePhase = AndroidUnifiedParticipantLifecyclePhase.derive(
            AndroidUnifiedParticipantLifecyclePhase.DerivationInput(
                formalLifecycleState = FormalParticipantLifecycleState.fromManagerState(
                    UFOGalaxyApplication.localInferenceRuntimeManager.state.value
                ),
                reconnectRecoveryStateWire = UFOGalaxyApplication.runtimeController
                    .reconnectRecoveryState.value.wireValue,
                crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                wsConnected = webSocketClient.isConnected(),
                hasDurableParticipantId = resultDurableParticipantId != null,
                capabilityVisible = resultCapabilityVisible,
                sessionAttached = resultDispatchReadiness.sessionIsAttached,
                readinessSatisfied = resolvedCrossDeviceElig,
                executionBusy = UFOGalaxyApplication.runtimeController.activeTaskId != null,
                takeoverActive = currentActiveTakeoverId() != null,
                interactionSurfaceReady = UFOGalaxyApplication.appSettings.let {
                    it.accessibilityReady && it.overlayReady
                },
                governanceBlocked = governanceTruth.governance_blocked
            )
        ).wireValue
        val resolvedSpineParticipation = result.execution_spine_participation_kind
            ?: AndroidNlDrivenExecutionSpineContract.classifyParticipationKind(
                executionRuntimeKind = result.execution_runtime_kind
            ).wireValue
        val participationKind = AndroidNlDrivenExecutionSpineContract
            .ExecutionSpineParticipationKind
            .fromWireValue(resolvedSpineParticipation)
            ?: AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION
        val resolvedProblemClosureClass = result.problem_solving_closure_class
            ?: AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                participationKind = participationKind,
                taskSucceeded = normalizedLifecycleStatus == EdgeExecutor.STATUS_SUCCESS
            ).wireValue
        // ── PR-05v2 (Android): 预先推导结果上行闭环边界快照 ─────────────────────────────────────
        // 在 result.copy() 前唯一计算，使 V2 可无歧义地将 goal_execution_result 路由至
        // truth closure 链、acceptance adjudication 或诊断存储，无需字段组合推断。
        val resultUplinkBoundary = AndroidResultUplinkBoundaryContract.derive(
            AndroidResultUplinkBoundaryContract.UplinkBoundaryDerivationInput(
                isTerminalPhase = true,
                resultReturned = result.result_returned ?: goalResultCompletionVisibility.resultReturned,
                completionSignaled = result.completion_signaled ?: goalResultCompletionVisibility.completionSignaled,
                closureReadyForAcceptance = result.closure_ready_for_acceptance
                    ?: goalResultCompletionVisibility.closureReadyForAcceptance,
                isGovernanceBlocked = (result.governance_blocked ?: governanceTruth.governance_blocked) == true,
                isRuntimeConstrained = constraintSem.isConstraint,
                isHoldState = resolvedIsHoldState
            )
        )
        val resultSemanticBoundary = AndroidDiagnosticsFailureExplanationUplinkContract
            .forGoalResult(
                resultSummary = result.result_summary,
                result = result.result,
                details = result.details,
                error = result.error
            )
        val resultIngress = AndroidGovernanceExecutionPolicyIngressContract
            .classifyMsgType(MsgType.GOAL_EXECUTION_RESULT)
        val resolvedGoalResultReturned = result.result_returned ?: goalResultCompletionVisibility.resultReturned
        val resolvedGoalCompletionSignaled = result.completion_signaled
            ?: goalResultCompletionVisibility.completionSignaled
        val resolvedGoalClosureReady = result.closure_ready_for_acceptance
            ?: goalResultCompletionVisibility.closureReadyForAcceptance
        val resultCompletionClosureBoundary = AndroidCompletionClosureUplinkContract
            .deriveForGoalResult(
                isLifecycleTerminalPhase = !isHoldStatus,
                resultSignalClass = resultUplinkBoundary.resultSignalClass,
                acceptanceCandidateClass = resultUplinkBoundary.acceptanceCandidateClass,
                resultReturned = resolvedGoalResultReturned,
                completionSignaled = resolvedGoalCompletionSignaled,
                closureReadyForAcceptance = resolvedGoalClosureReady,
                operatorProjectionClass = resultSemanticBoundary.operatorProjectionClass
            )
        val resultTruthOwnershipBoundary = deriveTruthOwnershipBoundary(
            executionBusy = resolvedGoalResultReturned ||
                resolvedGoalCompletionSignaled ||
                resolvedGoalClosureReady,
            sourceRuntimePosture = result.source_runtime_posture
                ?: UFOGalaxyApplication.runtimeController.hostSessionSnapshot?.posture
                ?: SourceRuntimePosture.CONTROL_ONLY,
            takeoverActive = false,
            sessionId = runtimeSession,
            isSessionRecoveryActive = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value == ReconnectRecoveryState.RECOVERING,
            isCapabilityDegraded = resultManagerDegraded,
            isRecoveryActive = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value == ReconnectRecoveryState.RECOVERING,
            isDiagnosticsSignal = resultUplinkBoundary.resultSignalClass ==
                AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            isOperatorVisibleSummary = false
        )
        // ── PR-08v2 (Android): 结果上行分布式运行参与边界快照 ─────────────────────────────────────
        // 在 result.copy() 前唯一计算，确保 goal_execution_result 与执行事件/状态快照在
        // distributed runtime participation 语义上保持同一合约。
        val resultParticipationBoundary = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = result.source_runtime_posture
                    ?: UFOGalaxyApplication.runtimeController.hostSessionSnapshot?.posture
                    ?: SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = resolvedGoalResultReturned && !isHoldStatus,
                executionModeStateWire = resolvedModeState,
                crossDeviceEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled,
                isFallbackTierActive = false,
                isCapabilityDegraded = resultManagerDegraded,
                takeoverActive = currentActiveTakeoverId() != null,
                isDiagnosticsSignal = false
            )
        )
        val resultDurableRecord = UFOGalaxyApplication.runtimeController
            .durableSessionContinuityRecord.value
        val resultInflightRecovery = UFOGalaxyApplication.runtimeController
            .inflightContinuityRecovery.value
        val resultRecoveryPhase = AndroidContinuityRecoveryStateModel.derive(
            reconnectRecoveryState = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value,
            inflightDisposition = resultInflightRecovery.disposition
        ).wireValue
        val resultRecoverySource = result.continuity_recovery_source ?: resultInflightRecovery.source
        val resultLineage = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = result.task_id,
            emissionIdentity = buildResultLineageEmissionIdentity(result, normalizedLifecycleStatus),
            durableSessionId = resultDurableRecord?.durableSessionId,
            sessionContinuityEpoch = resultDurableRecord?.sessionContinuityEpoch,
            recoveryBasis = "$resultRecoveryPhase:${resultRecoverySource.ifBlank { "none" }}"
        )
        val enriched = result.copy(
            normalized_status = canonicalResultKind(result.status),
            runtime_session_id = runtimeSession,
            // Populate result_summary from result when not already set, so all result payloads
            // carry a consistent human-readable summary field regardless of the caller path.
            result_summary = result.result_summary ?: result.result,
            // participation_tier is guaranteed non-null (INV-UTU-01): defaults to pre_attach.
            participation_tier = resolvedParticipationTier,
            execution_mode_state = resolvedModeState,
            cross_device_eligibility = resolvedCrossDeviceElig,
            local_mode_gate_deferred = resolvedIsHoldState,
            local_inference_available = resolvedLocalInference,
            // ── 统一真相上行合约布尔字段（在单一发送层强制填充）──
            dispatch_eligible = result.dispatch_eligible
                ?: (resolvedParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISPATCH_ELIGIBLE.wireValue ||
                    resolvedParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT.wireValue),
            distributed_participant = result.distributed_participant
                ?: (resolvedParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT.wireValue),
            session_attached = result.session_attached
                ?: (resolvedParticipationTier !=
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH.wireValue),
            local_mode_active = result.local_mode_active
                ?: (resolvedModeState ==
                    LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue),
            runtime_constrained = result.runtime_constrained ?: constraintSem.isConstraint,
            runtime_deferred = result.runtime_deferred ?: constraintSem.isDeferred,
            governance_state = result.governance_state ?: governanceTruth.governance_state,
            governance_blocked = result.governance_blocked ?: governanceTruth.governance_blocked,
            delegated_execution_active = result.delegated_execution_active
                ?: governanceTruth.delegated_execution_active,
            takeover_state = result.takeover_state ?: governanceTruth.takeover_state,
            local_llm_ready = localLlmReady,
            accessibility_ready = accessibilityReady,
            local_mode_capable = result.local_mode_capable ?: localCapState.isLocalModeCapable,
            ingress_boundary_class = result.ingress_boundary_class ?: resultIngress.boundaryClass.wireValue,
            ingress_consumption_kind = result.ingress_consumption_kind ?: resultIngress.consumptionKind.wireValue,
            ingress_signal_class = result.ingress_signal_class ?: resultIngress.signalClass.wireValue,
            ingress_schema_version = result.ingress_schema_version ?: resultIngress.schemaVersion,
            result_returned = resolvedGoalResultReturned,
            completion_signaled = resolvedGoalCompletionSignaled,
            closure_ready_for_acceptance = resolvedGoalClosureReady,
            unified_lifecycle_phase = result.unified_lifecycle_phase ?: resultUnifiedLifecyclePhase,
            unified_lifecycle_schema_version = result.unified_lifecycle_schema_version
                ?: AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION,
            execution_spine_participation_kind = resolvedSpineParticipation,
            problem_solving_closure_class = resolvedProblemClosureClass,
            // ── PR-05v2 (Android): 结果上行闭环边界字段（在单一发送层强制填充）────────────────────────
            // 从已预计算的 resultUplinkBoundary 直接读取，使 V2 可无歧义地将 goal_execution_result
            // 路由至 truth closure 链、acceptance adjudication 或诊断存储，无需字段组合推断。
            result_signal_class = resultUplinkBoundary.resultSignalClass.wireValue,
            acceptance_candidate_class = resultUplinkBoundary.acceptanceCandidateClass.wireValue,
            result_uplink_boundary_schema_version = AndroidResultUplinkBoundaryContract.SCHEMA_VERSION,
            uplink_semantic_boundary_class =
                resultSemanticBoundary.uplinkSemanticBoundaryClass.wireValue,
            operator_projection_class = resultSemanticBoundary.operatorProjectionClass.wireValue,
            diagnostics_failure_explanation_schema_version =
                AndroidDiagnosticsFailureExplanationUplinkContract.SCHEMA_VERSION,
            authority_runtime_completion_signal_class = resultCompletionClosureBoundary
                .authorityRuntimeCompletionSignalClass.wireValue,
            result_completion_signal_class = resultCompletionClosureBoundary
                .resultCompletionSignalClass.wireValue,
            closure_finalization_signal_class = resultCompletionClosureBoundary
                .closureFinalizationSignalClass.wireValue,
            operator_done_projection_class = resultCompletionClosureBoundary
                .operatorDoneProjectionClass.wireValue,
            completion_closure_uplink_schema_version =
                AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
            participation_boundary_role = resultParticipationBoundary.participationBoundaryRole.wireValue,
            ownership_posture_class = resultParticipationBoundary.ownershipPostureClass.wireValue,
            remote_local_mode_class = resultParticipationBoundary.remoteLocalModeClass.wireValue,
            participation_boundary_schema_version =
                AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION,
            authority_signal_class = resultTruthOwnershipBoundary.authoritySignalClass.wireValue,
            ownership_uplink_class = resultTruthOwnershipBoundary.ownershipUplinkClass.wireValue,
            session_continuity_class = resultTruthOwnershipBoundary.sessionContinuityClass.wireValue,
            device_posture_signal_class =
                resultTruthOwnershipBoundary.devicePostureSignalClass.wireValue,
            distributed_truth_ownership_uplink_schema_version =
                AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION,
            durable_session_id = result.durable_session_id ?: resultDurableRecord?.durableSessionId,
            session_continuity_epoch =
                result.session_continuity_epoch ?: resultDurableRecord?.sessionContinuityEpoch,
            // ── PR-116: unified continuity recovery state (always populated at emission layer) ──
            // Derives the unified recovery phase from authoritative runtime sources so V2 can
            // consume Android-side recovery state without combining fields across carriers.
            continuity_recovery_state = result.continuity_recovery_state
                ?: resultRecoveryPhase,
            continuity_recovery_source = resultRecoverySource,
            continuity_recovery_schema_version = result.continuity_recovery_schema_version
                ?: AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
            uplink_lineage_schema_version = result.uplink_lineage_schema_version
                ?: AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
            uplink_lineage_execution_id = result.uplink_lineage_execution_id
                ?: resultLineage.executionIdentity,
            uplink_lineage_emission_id = result.uplink_lineage_emission_id
                ?: resultLineage.emissionIdentity,
            uplink_lineage_dedupe_key = result.uplink_lineage_dedupe_key
                ?: resultLineage.dedupeKey,
            uplink_lineage_recovery_basis = result.uplink_lineage_recovery_basis
                ?: resultLineage.recoveryBasis
        )
        val envelope = AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = enriched,
            correlation_id = enriched.correlation_id ?: enriched.task_id,
            device_id = enriched.device_id.ifEmpty { localDeviceId },
            trace_id = traceId,
            route_mode = routeMode,
            runtime_session_id = runtimeSession,
            idempotency_key = buildIdempotencyKey(enriched.task_id, MsgType.GOAL_EXECUTION_RESULT, traceId),
            // Propagate trace/posture fields from the result payload into the envelope so
            // V2 observability and posture routing see consistent values at both layers.
            dispatch_trace_id = enriched.dispatch_trace_id,
            source_runtime_posture = enriched.source_runtime_posture
        )
        val envelopeJson = gson.toJson(envelope)
        val connectedAtSendAttempt = webSocketClient.isConnected()
        val sent = webSocketClient.sendJson(envelopeJson)
        val deliveryDisposition = when {
            sent -> ResultDeliveryDisposition.DIRECT_SENT
            else -> {
                val durableSessionTag = UFOGalaxyApplication.runtimeController
                    .durableSessionContinuityRecord.value
                    ?.durableSessionId
                val queueResult = runCatching {
                    webSocketClient.offlineQueue.enqueue(
                        type = MsgType.GOAL_EXECUTION_RESULT.value,
                        json = envelopeJson,
                        sessionTag = durableSessionTag
                    )
                }
                val queueSucceeded = queueResult.isSuccess
                if (queueSucceeded) {
                    ResultDeliveryDisposition.OFFLINE_QUEUED
                } else {
                    Log.e(
                        TAG,
                        "goal_execution_result enqueue failed task_id=${enriched.task_id} " +
                            "trace_id=${traceId ?: ""} error=${queueResult.exceptionOrNull()?.message}",
                        queueResult.exceptionOrNull()
                    )
                    ResultDeliveryDisposition.SEND_FAILED
                }
            }
        }
        updateAcceptanceEvidenceFromResult(
            result = enriched,
            deliveryDisposition = deliveryDisposition
        )
        sendDeviceAcceptanceReport()
        crossRepoRegressionHooks.recordGoalResultFeedback(
            taskId = enriched.task_id,
            traceId = traceId,
            runtimeSessionId = runtimeSession,
            status = if (deliveryDisposition == ResultDeliveryDisposition.SEND_FAILED) {
                ScenarioOutcomeStatus.FAILED
            } else {
                ScenarioOutcomeStatus.PASSED
            },
            reason = when (deliveryDisposition) {
                ResultDeliveryDisposition.DIRECT_SENT -> null
                ResultDeliveryDisposition.OFFLINE_QUEUED -> "goal_execution_result_offline_queued"
                ResultDeliveryDisposition.SEND_FAILED -> "goal_execution_result_send_failed"
            }
        )
        GalaxyLogger.log(
            TAG,
            mapOf(
                "event" to "goal_result_delivery_disposition",
                "task_id" to enriched.task_id,
                "trace_id" to (traceId ?: ""),
                "delivery_disposition" to deliveryDisposition.name.lowercase(),
                "connected_at_send_attempt" to connectedAtSendAttempt
            )
        )
        emitCrossRepoRegressionSnapshot("cross_repo_goal_result_return")
        return deliveryDisposition
    }

    /**
     * Maps a raw result [status] string to the canonical result kind for the unified
     * uplink contract.  Used exclusively by [sendGoalResult] to set
     * [GoalResultPayload.normalized_status] at emission time.
     *
     * | Raw status (after lifecycle normalization) | Canonical kind    |
     * |--------------------------------------------|-------------------|
     * | `"success"`                                | `"final_completion"` |
     * | `"cancelled"`                              | `"cancellation"`  |
     * | `"disabled"`                               | `"disabled"`      |
     * | anything else (`"error"`, `"timeout"`, …)  | `"failure"`       |
     */
    private fun canonicalResultKind(status: String): String =
        when (com.ufo.galaxy.protocol.UgcpSharedSchemaAlignment.normalizeLifecycleStatus(status)) {
            "success" -> "final_completion"
            "cancelled" -> "cancellation"
            "disabled" -> "disabled"
            else -> "failure"
        }

    /**
     * Sends an error [GoalResultPayload] as [MsgType.GOAL_EXECUTION_RESULT] when payload
     * parsing fails or an error occurs before the full inbound payload is available.
     *
     * [sourceRuntimePosture] should be passed whenever the caller has extracted it from the
     * inbound envelope (e.g. during parse-error paths where the payload JSON is available
     * but invalid).  Passing it keeps the context fields consistent with normal-path results
     * so V2 sees the same set of fields regardless of the error kind.
     */
    private fun sendGoalError(
        taskId: String,
        groupId: String?,
        subtaskIndex: Int?,
        errorMsg: String,
        traceId: String? = null,
        routeMode: String? = null,
        sourceRuntimePosture: String? = null
    ) {
        val errorResult = GoalResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = com.ufo.galaxy.agent.EdgeExecutor.STATUS_ERROR,
            error = errorMsg,
            group_id = groupId,
            subtask_index = subtaskIndex,
            latency_ms = 0L,
            device_id = localDeviceId,
            source_runtime_posture = sourceRuntimePosture
        )
        sendGoalResult(errorResult, traceId, routeMode)
    }

    /**
     * Builds a standardised timeout [GoalResultPayload].
     * All required aggregation fields (correlation_id, device_id, group_id,
     * subtask_index, source_runtime_posture, executor_target_type) are populated so the
     * gateway can still converge results and correlate them with the originating request.
     */
    private fun buildTimeoutGoalResult(
        taskId: String,
        payload: GoalExecutionPayload,
        timeoutMs: Long
    ) = GoalResultPayload(
        task_id = taskId,
        correlation_id = taskId,
        status = EdgeExecutor.STATUS_TIMEOUT,
        error = "Task exceeded timeout of ${timeoutMs}ms",
        group_id = payload.group_id,
        subtask_index = payload.subtask_index,
        latency_ms = timeoutMs,
        device_id = localDeviceId,
        source_runtime_posture = payload.source_runtime_posture,
        executor_target_type = payload.executor_target_type
    )

    private fun buildIdempotencyKey(taskId: String, type: MsgType, traceId: String? = null): String {
        val runtimeSession = UFOGalaxyApplication.runtimeSessionId
        val nonce = java.util.UUID.randomUUID().toString()
        return "$runtimeSession:${type.value}:$taskId:${traceId ?: "no_trace"}:$nonce"
    }

    private fun buildResultLineageEmissionIdentity(
        result: GoalResultPayload,
        normalizedLifecycleStatus: String
    ): String {
        val summary = result.result_summary
            ?.takeIf { it.isNotBlank() }
            ?: result.result?.takeIf { it.isNotBlank() }
            ?: result.error?.takeIf { it.isNotBlank() }
            ?: "none"
        return listOf(result.task_id, normalizedLifecycleStatus, summary).joinToString(":")
    }

    private fun emitRuntimeDiagnostics(
        taskId: String,
        nodeName: String,
        errorType: String,
        errorContext: String
    ) {
        val safeTaskId = taskId.ifBlank { "runtime" }
        val diagnosticsSent = webSocketClient.sendDiagnostics(
            taskId = safeTaskId,
            nodeName = nodeName,
            errorType = errorType,
            errorContext = errorContext
        )
        crossRepoRegressionHooks.recordDiagnostics(
            status = if (diagnosticsSent) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED,
            reason = if (diagnosticsSent) null else "runtime_diagnostics_send_failed"
        )
        emitCrossRepoRegressionSnapshot("cross_repo_runtime_diagnostics")
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "runtime_diagnostics_emitted",
                "task_id" to safeTaskId,
                "node_name" to nodeName,
                "error_type" to errorType
            )
        )
    }

    // ── PR-4 advanced-capability minimal helpers ──────────────────────────────────────────

    /**
     * Sends an [AckPayload] confirming receipt of an advanced-capability message.
     *
     * Called for message types in [MsgType.ACK_ON_RECEIPT_TYPES] that require delivery
     * confirmation even though full business logic is not yet implemented.
     *
     * @param type      The type of the inbound message being acknowledged.
     * @param messageId The `message_id` from the inbound AIP v3 envelope; used as the
     *                  payload's [AckPayload.message_id]. Falls back to a new UUID when null.
     */
    private fun sendAdvancedAck(type: MsgType, messageId: String?) {
        val ackPayload = AckPayload(
            message_id = messageId ?: java.util.UUID.randomUUID().toString(),
            type_acked = type.value,
            device_id = localDeviceId
        )
        val envelope = AipMessage(
            type = MsgType.ACK,
            payload = ackPayload,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(ackPayload.message_id, MsgType.ACK)
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.d(TAG, "[ADVANCED:ACK] type_acked=${type.value} message_id=${ackPayload.message_id} sent=$sent")
    }

    /**
     * Transmits a [DelegatedExecutionSignal] as a [MsgType.DELEGATED_EXECUTION_SIGNAL]
     * AIP v3 message uplink (PR-16).
     *
     * Called by [delegatedSignalSink] after structured logging so that every ACK /
     * PROGRESS / RESULT / TIMEOUT / CANCELLED lifecycle event is delivered to the
     * main-repo host as a stable, parseable outbound message.
     *
     * Send failure **never throws** — any error is caught and logged for diagnostics
     * observability so the executor's lifecycle progression is not interrupted.
     *
     * @param signal The [DelegatedExecutionSignal] emitted by [delegatedTakeoverExecutor].
     */
    private fun sendDelegatedExecutionSignal(signal: DelegatedExecutionSignal) {
        try {
            val takeoverAttempt = delegatedSignalAttempt(signal)
            val takeoverSemantics = AndroidTakeoverOwnershipTransferContract.classify(
                signal = signal,
                takeoverResultUplinkAttempt = takeoverAttempt
            )
            val payload = signal.toOutboundPayload(
                deviceId = localDeviceId,
                takeoverClosureSemantics = takeoverSemantics
            )
            val envelope = AipMessage(
                type = MsgType.DELEGATED_EXECUTION_SIGNAL,
                payload = payload,
                device_id = localDeviceId,
                trace_id = signal.traceId,
                correlation_id = signal.taskId,
                idempotency_key = signal.signalId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId
            )
            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            if (sent) {
                Log.d(TAG, "[DELEGATED_SIGNAL] sent signal_id=${signal.signalId} kind=${signal.kind.wireValue} task_id=${signal.taskId}")
            } else {
                val failureOwnershipState = if (signal.isResult) {
                    AndroidTakeoverOwnershipTransferContract
                        .OwnershipReturnState.OWNERSHIP_RETURN_PENDING_UPLINK.wireValue
                } else {
                    takeoverSemantics.ownershipReturnState.wireValue
                }
                Log.w(
                    TAG,
                    "[DELEGATED_SIGNAL] send failed signal_id=${signal.signalId} kind=${signal.kind.wireValue} task_id=${signal.taskId}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "delegated_signal_send_failed",
                        "signal_id" to signal.signalId,
                        "signal_kind" to signal.kind.wireValue,
                        "task_id" to signal.taskId,
                        "trace_id" to signal.traceId,
                        "emission_seq" to signal.emissionSeq,
                        "takeover_completion_kind" to takeoverSemantics.takeoverCompletionKind.wireValue,
                        "ownership_return_state" to failureOwnershipState,
                        "takeover_outcome_visibility" to takeoverSemantics.takeoverOutcomeVisibility.wireValue,
                        "takeover_result_uplink_attempt" to takeoverSemantics.takeoverResultUplinkAttempt
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DELEGATED_SIGNAL] unexpected error sending signal signal_id=${signal.signalId}: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "delegated_signal_send_error",
                    "signal_id" to signal.signalId,
                    "signal_kind" to signal.kind.wireValue,
                    "task_id" to signal.taskId,
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    // ── PR-06: Reconciliation signal uplink ───────────────────────────────────

    // ── PR-2 (Android): Terminal execution-event builder helper ──────────────

    /**
     * Builds a [DeviceExecutionEventPayload] for a terminal execution result.
     *
     * Derives the correct [DeviceExecutionEventPayload.phase] from the [GoalResultPayload]
     * status and available stop-reason metadata, so callers never need to duplicate the
     * phase-derivation logic:
     *
     * - `status="success"` → [DeviceExecutionEventPayload.PHASE_COMPLETED]
     * - `status="cancelled"` → [DeviceExecutionEventPayload.PHASE_CANCELLED]
     * - `stop_reason` contains `"stagnation"` → [DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED]
     *   with `is_blocking=true` and `stagnation_detected=true`
     * - `status="error"/"timeout"/other` → [DeviceExecutionEventPayload.PHASE_FAILED]
     *   with `is_blocking=true` and `blocking_reason` from `result.error`
     *
     * @param taskId   Task identifier for correlation.
     * @param result   Terminal [GoalResultPayload] from which to derive phase and metadata.
     * @param stepIndex Zero-based index of the last executed step (use `result.steps.size - 1`).
     * @param source   Canonical name of the originating component for traceability.
     * @param flowId   Delegated flow identifier; defaults to [taskId] when absent.
     */
    private fun emitMeshDirectFallbackTransition(
        taskId: String,
        flowId: String,
        fallbackReason: String
    ) {
        deviceExecutionEventSink.onEvent(
            DeviceExecutionEventPayload(
                flow_id = flowId,
                task_id = taskId,
                phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
                is_blocking = true,
                blocking_reason = fallbackReason,
                fallback_tier = "gateway_mesh_fallback",
                device_id = localDeviceId,
                source_component = "GalaxyConnectionService.handleParallelSubtask"
            )
        )
    }

    private fun formatMeshDirectErrorContext(meshId: String, reasonCodes: List<String>): String =
        "mesh_id=$meshId reasons=${formatMeshDirectReasonCodes(reasonCodes)}"

    private fun formatMeshDirectReasonCodes(reasonCodes: List<String>): String =
        reasonCodes.joinToString(",")

    private fun buildTerminalExecutionEvent(
        taskId: String,
        result: GoalResultPayload,
        stepIndex: Int,
        source: String,
        flowId: String = taskId
    ): DeviceExecutionEventPayload {
        val phase: String
        val isBlocking: Boolean
        val blockingReason: String
        val stagnationDetected: Boolean

        when {
            result.status == EdgeExecutor.STATUS_SUCCESS -> {
                phase = DeviceExecutionEventPayload.PHASE_COMPLETED
                isBlocking = false
                blockingReason = ""
                stagnationDetected = false
            }
            result.status == EdgeExecutor.STATUS_CANCELLED -> {
                phase = DeviceExecutionEventPayload.PHASE_CANCELLED
                isBlocking = false
                blockingReason = ""
                stagnationDetected = false
            }
            result.details?.contains("stagnation") == true ||
                result.error?.contains("stagnation") == true -> {
                phase = DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED
                isBlocking = true
                blockingReason = result.error ?: "stagnation_detected"
                stagnationDetected = true
            }
            else -> {
                phase = DeviceExecutionEventPayload.PHASE_FAILED
                isBlocking = true
                blockingReason = result.error ?: result.status
                stagnationDetected = false
            }
        }

        // PR-6: capture session identity at terminal event build time so V2 can correlate
        // this execution outcome with the ongoing session axis.
        val durableRecord = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value

        // PR-8: capture carrier manifestation/presence hints at event build time.
        val carrierForegroundVisible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value
        val interactionSurfaceReady = UFOGalaxyApplication.appSettings.accessibilityReady &&
            UFOGalaxyApplication.appSettings.overlayReady

        // PR-10: capture carrier runtime state for coherent event-snapshot alignment.
        val carrierRuntimeState = UFOGalaxyApplication.runtimeController.state.value.wireLabel
        val localTerminalOutcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = phase,
            status = result.status,
            blockingReason = blockingReason,
            details = result.details,
            interruptionReason = result.interruption_reason,
            fallbackTier = result.policy_routing_outcome
        )
        val reportedResultSemantic =
            AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(localTerminalOutcome)

        return DeviceExecutionEventPayload(
            flow_id = flowId,
            task_id = taskId,
            phase = phase,
            step_index = stepIndex,
            is_blocking = isBlocking,
            blocking_reason = blockingReason,
            stagnation_detected = stagnationDetected,
            device_id = localDeviceId,
            source_component = source,
            // PR-6: session identity continuity fields — null when no durable era is active.
            durable_session_id = durableRecord?.durableSessionId,
            session_continuity_epoch = durableRecord?.sessionContinuityEpoch,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
            // PR-8: carrier manifestation/presence hints — backed by real Android state.
            carrier_foreground_visible = carrierForegroundVisible,
            interaction_surface_ready = interactionSurfaceReady,
            // PR-10: carrier runtime state for coherent carrier status in every event.
            carrier_runtime_state = carrierRuntimeState,
            // PR-10 completion hardening: separate local terminal observation from reported semantics.
            reported_state_semantic_class =
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue,
            result_uplink_semantic_class = reportedResultSemantic.wireValue,
            terminal_outcome_kind = localTerminalOutcome.wireValue,
            // PR-7B: explicit evidence presence kind for this terminal event so V2 applies
            // the correct governance policy without relying on optimistic defaults.
            evidence_presence_kind =
                AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(phase).wireValue
        )
    }

    /**
     * Returns `true` when takeover failure should still emit runtime diagnostics.
     *
     * Structured non-success terminal results that already describe an expected closure shape
     * (`cancelled`, `timeout`, `disabled`, `hold`) do not need an extra diagnostics emission.
     * We still emit diagnostics when no structured result was returned, or when Android surfaced
     * any other status that implies an unexpected execution failure and needs extra observability.
     */
    private fun shouldEmitTakeoverFailureDiagnostics(
        returnedGoalResult: GoalResultPayload?,
        normalizedReturnedStatus: String?
    ): Boolean =
        returnedGoalResult == null ||
            (normalizedReturnedStatus != EdgeExecutor.STATUS_CANCELLED &&
                normalizedReturnedStatus != EdgeExecutor.STATUS_TIMEOUT &&
                normalizedReturnedStatus != AutonomousExecutionPipeline.STATUS_DISABLED &&
                !PolicyRoutingContext.isHoldStatus(normalizedReturnedStatus))

    /**
     * Transmits a [ReconciliationSignal] as a [MsgType.RECONCILIATION_SIGNAL] AIP v3
     * uplink message (PR-06).
     *
     * Serialises the signal into a [ReconciliationSignalPayload] and sends it via
     * [webSocketClient].  Called for every signal emitted by
     * [com.ufo.galaxy.runtime.RuntimeController.reconciliationSignals].
     *
     * Send failure **never throws** — any error is caught and logged so the
     * reconciliation signal stream is never interrupted.
     *
     * @param signal The [ReconciliationSignal] to transmit.
     */
    private fun sendReconciliationSignal(signal: ReconciliationSignal) {
        try {
            val runtimeTruth = signal.runtimeTruth?.toMap()
            val sessionId = UFOGalaxyApplication.runtimeSessionId
            val stableDedupeKey = signal.stableDedupeKey
            val ingress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyReconciliation(signal.kind)
            val reliablePayload = signal.payload.toMutableMap().apply {
                put(ReconciliationSignal.KEY_STABLE_DEDUPE_KEY, stableDedupeKey)
            }
            val recoveryState = reliablePayload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_STATE]
                ?.toString()
                ?.ifBlank { null }
                ?: UFOGalaxyApplication.runtimeController.inflightContinuityRecovery.value
                    .disposition.wireValue
            val recoverySource = reliablePayload[ReconciliationSignal.KEY_CONTINUITY_RECOVERY_SOURCE]
                ?.toString()
                ?.ifBlank { null }
                ?: UFOGalaxyApplication.runtimeController.inflightContinuityRecovery.value.source
            val lineage = AndroidUplinkLineageMetadataContract.derive(
                executionIdentity = signal.taskId ?: signal.kind.wireValue,
                emissionIdentity = signal.signalId,
                durableSessionId = signal.durableSessionId,
                sessionContinuityEpoch = signal.sessionContinuityEpoch,
                recoveryBasis = "$recoveryState:${recoverySource.ifBlank { "none" }}"
            )
            val payload = ReconciliationSignalPayload(
                signal_id = signal.signalId,
                kind = signal.kind.wireValue,
                participant_id = signal.participantId,
                status = signal.status,
                emitted_at_ms = signal.emittedAtMs,
                reconciliation_epoch = signal.reconciliationEpoch,
                device_id = localDeviceId,
                task_id = signal.taskId,
                correlation_id = signal.correlationId,
                session_id = sessionId,
                durable_session_id = signal.durableSessionId,
                session_continuity_epoch = signal.sessionContinuityEpoch,
                payload = reliablePayload,
                runtime_truth = runtimeTruth,
                ingress_boundary_class = ingress.boundaryClass.wireValue,
                ingress_consumption_kind = ingress.consumptionKind.wireValue,
                ingress_signal_class = ingress.signalClass.wireValue,
                ingress_schema_version = ingress.schemaVersion,
                uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
                uplink_lineage_execution_id = lineage.executionIdentity,
                uplink_lineage_emission_id = lineage.emissionIdentity,
                uplink_lineage_dedupe_key = lineage.dedupeKey,
                uplink_lineage_recovery_basis = lineage.recoveryBasis
            )
            val envelope = AipMessage(
                type = MsgType.RECONCILIATION_SIGNAL,
                payload = payload,
                device_id = localDeviceId,
                correlation_id = signal.taskId,
                idempotency_key = stableDedupeKey,
                runtime_session_id = sessionId
            )
            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            if (sent) {
                Log.d(
                    TAG,
                    "[RECONCILIATION_SIGNAL] sent signal_id=${signal.signalId} dedupe_key=$stableDedupeKey " +
                        "kind=${signal.kind.wireValue} task_id=${signal.taskId}"
                )
            } else {
                Log.w(
                    TAG,
                    "[RECONCILIATION_SIGNAL] send failed signal_id=${signal.signalId} dedupe_key=$stableDedupeKey " +
                        "kind=${signal.kind.wireValue} task_id=${signal.taskId}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "reconciliation_signal_send_failed",
                        "signal_id" to signal.signalId,
                        "dedupe_key" to stableDedupeKey,
                        "signal_kind" to signal.kind.wireValue,
                        "task_id" to (signal.taskId ?: ""),
                        "participant_id" to signal.participantId,
                        "is_terminal" to signal.isTerminal
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RECONCILIATION_SIGNAL] unexpected error sending signal signal_id=${signal.signalId}: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "reconciliation_signal_send_error",
                    "signal_id" to signal.signalId,
                    "dedupe_key" to signal.stableDedupeKey,
                    "signal_kind" to signal.kind.wireValue,
                    "task_id" to (signal.taskId ?: ""),
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    // ── Android-side signal closure: device readiness report uplink ───────────

    /**
     * Builds a [DeviceReadinessReportPayload] from the current
     * [delegatedRuntimeReadinessEvaluator] state and transmits it as a
     * [MsgType.DEVICE_READINESS_REPORT] AIP v3 uplink message.
     *
     * Called during service start so V2 readiness-gate and governance paths have a
     * baseline Android-side readiness artifact available immediately on connection.
     * At this stage all dimensions are typically UNKNOWN, which is itself a valid
     * structured signal — V2 can distinguish "not yet reported" from "reported with a
     * gap".  Follow-up reports are emitted whenever dimension states change.
     * Send failure never throws — any error is caught and logged.
     */
    private fun sendDeviceReadinessReport() {
        try {
            // Guard: skip if device ID is not yet available to avoid sending malformed reports.
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_READINESS_REPORT] skipped — localDeviceId is blank")
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
                ingress_schema_version = ingress.schemaVersion
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_READINESS_REPORT,
                payload = payload,
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_READINESS_REPORT)
            )

            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            Log.i(
                TAG,
                "[DEVICE_READINESS_REPORT] sent snapshot_id=${snapshot.snapshotId} " +
                    "artifact=${artifact.semanticTag} sent=$sent"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_readiness_report_emitted",
                    "snapshot_id" to snapshot.snapshotId,
                    "artifact_tag" to artifact.semanticTag,
                    "missing_dimension_count" to missingDimensions.size,
                    "gap_reason" to (firstGapReason ?: ""),
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_READINESS_REPORT] unexpected error: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_readiness_report_error",
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    // ── PR-RT: Device runtime-state snapshot uplink ───────────────────────────

    /**
     * Builds a [DeviceStateSnapshotPayload] from the current real local runtime state sources
     * and transmits it as a [MsgType.DEVICE_STATE_SNAPSHOT] AIP v3 uplink message on the
     * canonical Android→V2 control-plane WebSocket path.
     *
     * All fields are sourced exclusively from real Android local state:
     *  - [NativeInferenceLoader]: llama.cpp / NCNN native library availability.
     *  - [UFOGalaxyApplication.localInferenceRuntimeManager]: active runtime type, warmup
     *    result, and runtime health snapshot.
     *  - [UFOGalaxyApplication.appSettings]: model/accessibility/overlay readiness flags and
     *    pending-first-download sentinel.
     *  - [UFOGalaxyApplication.localLoopReadinessProvider]: full local-loop pipeline readiness
     *    (six subsystems: model files, planner, grounding, accessibility, screenshot, action
     *    executor) and degraded-reason list.
     *  - [UFOGalaxyApplication.modelAssetManager]: per-model file presence and checksum state.
     *  - [UFOGalaxyApplication.localLoopConfig]: active LocalLoopConfig serialised as a map.
     *  - [GalaxyWebSocketClient.queueSize]: current offline queue depth.
     *  - [UFOGalaxyApplication.runtimeController.rolloutControlSnapshot]: fallback tier.
     *
     * V2 absorbs this payload via `core.android_device_state_store.absorb_device_state_snapshot()`
     * and makes it available at:
     *  - `GET /api/v1/operator/devices/ecosystem`
     *  - `GET /api/v1/operator/devices/ecosystem/{device_id}`
     *
     * Emission points:
     *  - Service start (baseline) — in [onStartCommand].
     *  - WS reconnect / recovery — in [wsListener.onConnected].
     *
     * Send failure never throws — any error is caught and logged with
     * [GalaxyLogger.TAG_DEVICE_STATE_SNAPSHOT].
     */
    private fun sendDeviceStateSnapshot() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] skipped — localDeviceId is blank")
                return
            }
            val settings = UFOGalaxyApplication.appSettings
            val snapshotStamp = runtimeStateTruthSequencer.nextSnapshotStamp()

            // ── Native runtime availability ───────────────────────────────────
            val llamaCppAvailable = NativeInferenceLoader.isLlamaCppAvailable()
            val ncnnAvailable = NativeInferenceLoader.isNcnnAvailable()

            // ── Local inference runtime state ─────────────────────────────────
            val managerState = UFOGalaxyApplication.localInferenceRuntimeManager.state.value
            val activeRuntimeType: String = when {
                llamaCppAvailable && ncnnAvailable -> "HYBRID"
                llamaCppAvailable -> "LLAMA_CPP"
                ncnnAvailable -> "NCNN"
                else -> "CENTER"
            }
            val warmupResult: String = when (managerState) {
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running -> "ok"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded -> "degraded"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup -> "failed"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed -> "failed"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Starting -> "not_started"
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Recovering -> "not_started"
                else -> "unavailable"
            }
            val runtimeHealthMap: Map<String, Any>? = when (managerState) {
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running ->
                    mapOf(
                        "planner_health" to managerState.snapshot.plannerHealth.name,
                        "grounding_health" to managerState.snapshot.groundingHealth.name
                    )
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded ->
                    mapOf(
                        "planner_health" to managerState.snapshot.plannerHealth.name,
                        "grounding_health" to managerState.snapshot.groundingHealth.name,
                        "reason" to managerState.reason
                    )
                else -> null
            }

            // ── Readiness flags from AppSettings (persisted by ReadinessChecker) ──
            val modelReady = settings.modelReady
            val accessibilityReady = settings.accessibilityReady
            val overlayReady = settings.overlayReady

            // ── Full local-loop readiness (six-subsystem) ─────────────────────
            val loopReadiness = try {
                UFOGalaxyApplication.localLoopReadinessProvider.getReadiness()
            } catch (e: Exception) {
                Log.d(TAG, "[DEVICE_STATE_SNAPSHOT] could not read loop readiness: ${e.message}")
                null
            }
            val localLoopReady = loopReadiness?.isFullyReady ?: false
            val degradedReasons: List<String> = loopReadiness?.blockers?.map { it.name } ?: emptyList()

            // ── Model identity from ModelAssetManager ────────────────────────
            val assetManager = UFOGalaxyApplication.modelAssetManager
            val modelStatuses = try { assetManager.verifyAll() } catch (e: Exception) { emptyMap() }
            val mobilevlmStatus = modelStatuses[com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM]
            val seeClickStatus = modelStatuses[com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK]
            val mobilevlmPresent = mobilevlmStatus != null &&
                mobilevlmStatus != com.ufo.galaxy.model.ModelAssetManager.ModelStatus.MISSING
            val seeClickPresent = seeClickStatus != null &&
                seeClickStatus != com.ufo.galaxy.model.ModelAssetManager.ModelStatus.MISSING
            // checksum_ok: true only when all known models are READY (checksum-verified) or LOADED.
            // Returns false when the registry is empty (no models registered), because an
            // empty registry cannot confirm checksum validity.
            val checksumOk = modelStatuses.isNotEmpty() && modelStatuses.values.all {
                it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.READY ||
                    it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.LOADED
            }
            // mobilevlm_checksum_ok follows the same READY/LOADED check for the mobilevlm model
            val mobilevlmChecksumOk = mobilevlmStatus == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.READY ||
                mobilevlmStatus == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.LOADED
            // model_id: the canonical identifier of the primary on-device model when present;
            // null when the model is not yet present (awaiting first download or missing).
            val modelId: String? = if (mobilevlmPresent) com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM else null
            // pending_first_download: true when no model has been successfully downloaded
            // (no model in the registry is READY or LOADED).  This is the canonical condition:
            // a device awaiting its first download has no READY/LOADED models.
            val anyModelReady = modelStatuses.values.any {
                it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.READY ||
                    it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.LOADED
            }
            val pendingFirstDownload = !anyModelReady

            // ── Local loop config ─────────────────────────────────────────────
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
            } catch (e: Exception) {
                null
            }

            // ── Offline queue depth ───────────────────────────────────────────
            val offlineQueueDepth = webSocketClient.queueSize.value

            // ── Fallback tier derived from rollout-control snapshot ───────────
            val rolloutSnapshot = UFOGalaxyApplication.runtimeController.rolloutControlSnapshot.value
            val currentFallbackTier: String = when {
                !rolloutSnapshot.goalExecutionAllowed -> "no_execution"
                !rolloutSnapshot.crossDeviceAllowed && !rolloutSnapshot.delegatedExecutionAllowed -> "local_only"
                !rolloutSnapshot.delegatedExecutionAllowed -> "local_only"
                rolloutSnapshot.fallbackToLocalAllowed -> "center_delegated_with_local_fallback"
                else -> "center_delegated"
            }

            // ── PR-3: Per-subsystem fallback tier fields (V2 _parse_state_snapshot) ────────
            // planner_fallback_tier and grounding_fallback_tier are derived from the active
            // LocalLoopConfig fallback flags, which are the real Android-side source of truth
            // for each subsystem's fallback ladder state.  V2's _parse_state_snapshot accepts
            // these as separate fields alongside current_fallback_tier.
            val plannerFallbackTier: String? = try {
                UFOGalaxyApplication.localLoopConfig?.let { cfg ->
                    if (cfg.fallback.enablePlannerFallback) "active" else "disabled"
                }
            } catch (e: Exception) {
                Log.d(TAG, "[DEVICE_STATE_SNAPSHOT] could not read planner fallback tier: ${e.message}")
                null
            }
            val groundingFallbackTier: String? = try {
                UFOGalaxyApplication.localLoopConfig?.let { cfg ->
                    if (cfg.fallback.enableGroundingFallback) "active" else "disabled"
                }
            } catch (e: Exception) {
                Log.d(TAG, "[DEVICE_STATE_SNAPSHOT] could not read grounding fallback tier: ${e.message}")
                null
            }

            // ── PR-6: Session/invocation identity continuity fields ───────────────────────
            // Read atomically so the snapshot carries a consistent view of session identity.
            // All four values are null when the backing Android state is not yet active —
            // no fake placeholder values are emitted.
            val durableRecord = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            val snapshotAttachedSessionId = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId
            val (snapshotCollaborationState, snapshotParticipationActive) =
                readMeshRuntimeSignalState()

            // ── PR-8: Carrier manifestation/presence hints ────────────────────────────────
            // Both values are sourced from existing real Android state; null is emitted when
            // the backing state is unavailable — no fake placeholder values.
            val carrierForegroundVisible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value
            // interaction_surface_ready = true only when BOTH overlay and accessibility are
            // ready, matching the pre-flight condition in CrossDeviceEnablementError and
            // TakeoverEligibilityAssessor.assess().
            val interactionSurfaceReady = accessibilityReady == true && overlayReady == true

            // ── PR-10: Final carrier consolidation — cross-cutting carrier state fields ─────
            // carrier_runtime_state: always populated from the real RuntimeController state so
            // V2 can distinguish snapshots emitted from an Active carrier from those emitted
            // during local-only or startup state.
            // reconnect_recovery_state: always populated so V2 can contextualise snapshots
            // emitted during a reconnect recovery cycle.
            val carrierRuntimeState = UFOGalaxyApplication.runtimeController.state.value.wireLabel
            val reconnectRecoveryState =
                UFOGalaxyApplication.runtimeController.reconnectRecoveryState.value.wireValue
            // ── PR-4: Capability authority snapshot ────────────────────────────────────────
            // Build the authoritative capability authority snapshot from live runtime state.
            // This collapses the scattered grounding/planning/inference/checksum signals into
            // a single, versioned, testable structure that V2 can consume for dispatch scoring
            // without cross-referencing multiple fields.
            val capabilityAuthority = try {
                com.ufo.galaxy.runtime.CapabilityAuthoritySnapshot.from(
                    managerState  = managerState,
                    checksumValid = checksumOk ?: false
                )
            } catch (e: Exception) {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] could not build capability authority snapshot: ${e.message}")
                null
            }

            // ── PR-04: Mesh participation runtime closure fields ──────────────────────────────
            // Derive the structured mesh participation runtime state from live Android state.
            // These fields close Android-side mesh participation as a stable runtime lifecycle
            // contract aligned with the center-side mesh runtime state contract.
            //
            // participantHealthState: derived from the manager state that is already read above.
            //   This avoids a second read of the same state and keeps derivation consistent.
            //
            // participationHostState: ACTIVE only while a real mesh runtime cycle is active
            // and an attached session is present.  This avoids declaration-only "always active"
            // semantics and allows terminal cycles to truthfully close.
            //
            // barrierState: sourced from BarrierCoordinationParticipant runtime state.
            //
            // collaborationState: sourced from live mesh collaboration lifecycle tracking.
            //
            // fallbackActive: true when the active fallback tier is not the primary tier.
            //   Matches the condition under which Android reports a degraded/fallback path.
            val snapshotParticipantHealth = when (managerState) {
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.HEALTHY
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.DEGRADED
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.FAILED
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.FAILED
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Starting ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.STARTING
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Recovering ->
                    com.ufo.galaxy.runtime.ParticipantHealthState.RECOVERING
                else -> com.ufo.galaxy.runtime.ParticipantHealthState.UNKNOWN
            }
            val snapshotParticipationHostState =
                if (snapshotParticipationActive && snapshotAttachedSessionId != null) {
                    com.ufo.galaxy.runtime.RuntimeHostDescriptor.HostParticipationState.ACTIVE
                } else {
                    com.ufo.galaxy.runtime.RuntimeHostDescriptor.HostParticipationState.INACTIVE
                }
            val snapshotFallbackActive = currentFallbackTier == FALLBACK_TIER_CENTER_DELEGATED_WITH_LOCAL ||
                plannerFallbackTier == FALLBACK_TIER_ACTIVE || groundingFallbackTier == FALLBACK_TIER_ACTIVE

            val meshRuntimeStateReport = try {
                com.ufo.galaxy.runtime.AndroidMeshParticipationRuntimeContract.derive(
                    rollout = rolloutSnapshot,
                    healthState = snapshotParticipantHealth,
                    barrierState = barrierCoordinationParticipant.currentState,
                    collaborationState = snapshotCollaborationState,
                    fallbackActive = snapshotFallbackActive,
                    participationState = snapshotParticipationHostState
                )
            } catch (e: Exception) {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] could not build mesh runtime state report: ${e.message}")
                null
            }
            val meshDirectRuntimeSnapshot = snapshotViewOfMeshDirectRuntime(
                lastMeshDirectRuntimeSnapshot.meshId ?: lastMeshTopologyMeshId
            )

            val capabilityDegradedForMode = when (managerState) {
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded,
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup,
                is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed -> true
                else -> false
            }
            val wsConnected = webSocketClient.isConnected()
            val modeState = settings.authoritativeModeState(
                wsConnected = wsConnected,
                capabilityDegraded = capabilityDegradedForMode,
                degradationReasons = degradedReasons
            )

            // ── PR-8Android: Durable participant identity fields ───────────────────────────
            // durable_participant_id and participant_identity_freshness close the R8 gap:
            // V2's android_device_state_store.py MUST use the stable durable_participant_id
            // as the primary key for cross-era record correlation, and MUST read
            // participant_identity_freshness rather than applying its own staleness heuristics.
            //
            // Freshness is derived from DurableParticipantIdentity.createFromGap() using the
            // durable session continuity record as the primary timing reference:
            //  - No durable record → FRESH (first activation)
            //  - Offline gap < STALE_THRESHOLD_MS → RECOVERED (reconnect within same era)
            //  - Offline gap >= STALE_THRESHOLD_MS → STALE (staleness self-reported by Android)
            val snapshotDurableParticipantId: String? = try {
                val id = settings.durableParticipantId
                id.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] could not read durable participant ID: ${e.message}")
                null
            }
            val snapshotParticipantFreshness: String? = try {
                val pid = snapshotDurableParticipantId
                if (pid == null) {
                    null
                } else {
                    val activationRecord = durableRecord
                    val continuityRecord = activationRecord ?: com.ufo.galaxy.runtime.DurableSessionContinuityRecord(
                        durableSessionId = "",
                        sessionContinuityEpoch = 0,
                        activationEpochMs = System.currentTimeMillis(),
                        activationSource = "snapshot_fallback"
                    )
                    val identity = DurableParticipantIdentity.createFromGap(
                        existingParticipantId = pid,
                        deviceId = deviceId,
                        deviceRole = settings.deviceRole,
                        continuityRecord = continuityRecord,
                        lastActiveEpochMs = activationRecord?.activationEpochMs
                            ?: System.currentTimeMillis()
                    )
                    identity.freshness.wireValue
                }
            } catch (e: Exception) {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] could not derive participant freshness: ${e.message}")
                null
            }
            val reportedStateSemanticClass = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
                carrierRuntimeState = carrierRuntimeState,
                reconnectRecoveryState = reconnectRecoveryState,
                executionModeState = modeState.executionModeState,
                executionBusy = snapshotStamp.executionBusy,
                meshParticipationLifecycle = meshRuntimeStateReport?.participationLifecycle?.wireValue,
                carrierForegroundVisible = carrierForegroundVisible,
                plannerFallbackTier = plannerFallbackTier,
                groundingFallbackTier = groundingFallbackTier
            )
            val localObservationBasis =
                AndroidDeviceSurfaceSourceContract.deriveSnapshotObservationBasis(
                    crossDeviceEnabled = settings.crossDeviceEnabled,
                    wsConnected = wsConnected,
                    reconnectRecoveryState = reconnectRecoveryState,
                    offlineQueueDepth = offlineQueueDepth,
                    reportedStateSemanticClass = reportedStateSemanticClass
                )
            val degradedConditionClass = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
                reconnectRecoveryState = reconnectRecoveryState,
                degradedReasons = degradedReasons,
                executionModeState = modeState.executionModeState,
                plannerFallbackTier = plannerFallbackTier,
                groundingFallbackTier = groundingFallbackTier,
                currentFallbackTier = currentFallbackTier,
                meshConstrainedReasons = meshRuntimeStateReport?.constrainedReasons ?: emptyList(),
                crossDeviceEligibility = modeState.crossDeviceEligibility,
                plannerReady = capabilityAuthority?.plannerReady,
                groundingReady = capabilityAuthority?.groundingReady,
                offlineQueueDepth = offlineQueueDepth
            )
            // PR-7B: Derive evidence_presence_kind so V2 can determine what governance
            // policy to apply without relying on optimistic defaults for missing evidence.
            val evidencePresenceKind = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
                warmupResult = warmupResult,
                pendingFirstDownload = pendingFirstDownload,
                localLoopReady = if (localLoopReady) true else null,
                plannerReady = capabilityAuthority?.plannerReady,
                groundingReady = capabilityAuthority?.groundingReady,
                offlineQueueDepth = offlineQueueDepth,
                managerStateIsRunning = managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running,
                managerStateIsFailed = managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed ||
                    managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup,
                managerStateIsStarting = managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Starting ||
                    managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Recovering
            )

            // PR-12: Derive reconnect/recovery participation classification so V2 can apply
            // the correct recovery closure path without inferring it from field combinations.
            // The classification is derived from live Android runtime state at snapshot time:
            //   - reconnectRecoveryState: the current WS recovery cycle phase.
            //   - durableParticipantId / freshness: from AppSettings (persisted across process kills).
            //   - offlineQueueDepth / queueSessionTagMatchesCurrent: from the offline queue.
            // The LocalRecoveryDecision is approximated from reconnectRecoveryState + durableRecord
            // so that no additional round-trip into the recovery owner is required at snapshot time.
            val snapshotLocalRecoveryDecision: LocalRecoveryDecision? = when {
                // Transport reconnect: WS is recovering or just recovered, and a durable session exists
                (reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue ||
                    reconnectRecoveryState == ReconnectRecoveryState.RECOVERED.wireValue) &&
                    durableRecord != null ->
                    LocalRecoveryDecision.WaitForV2ReplayDecision(
                        continuityToken = null,
                        durableSessionId = durableRecord.durableSessionId,
                        reason = "transport_reconnect_snapshot_classification"
                    )
                // No durable session present: fresh attach (or session not yet initialised)
                durableRecord == null ->
                    LocalRecoveryDecision.NoRecoveryContext
                // Default: no recovery context needed for classification
                else -> null
            }
            val snapshotQueueSessionTagMatches = run {
                val currentSession = durableRecord?.durableSessionId
                // Replay eligibility requires a non-empty queue whose messages were enqueued
                // during the current durable session era.  We conservatively consider the queue
                // session-matched only when both conditions hold:
                //   1. A current durable session ID exists (messages could only be tagged with it).
                //   2. The WS is in a transport reconnect state (recovering/recovered), which means
                //      the offline queue accumulated during the same durable era — the session ID
                //      at enqueue time equals the current durable session ID.
                // Outside a transport reconnect (fresh attach / idle), any queued messages may be
                // from a prior era and must be treated as stale-session (STALE_SESSION_BLOCKED or
                // QUEUE_EMPTY by the classifier).
                val isTransportReconnect = reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue ||
                    reconnectRecoveryState == ReconnectRecoveryState.RECOVERED.wireValue
                currentSession != null && offlineQueueDepth > 0 && isTransportReconnect
            }
            val reconnectParticipationSnapshot = try {
                com.ufo.galaxy.runtime.AndroidReconnectRecoveryParticipationContract.classify(
                    com.ufo.galaxy.runtime.AndroidReconnectRecoveryParticipationContract.ReconnectClassificationInput(
                        reconnectRecoveryState = reconnectRecoveryState,
                        localRecoveryDecision = snapshotLocalRecoveryDecision,
                        durableSessionId = durableRecord?.durableSessionId,
                        durableParticipantId = snapshotDurableParticipantId,
                        participantIdentityFreshness = snapshotParticipantFreshness,
                        sessionContinuityEpoch = durableRecord?.sessionContinuityEpoch,
                        offlineQueueDepth = offlineQueueDepth,
                        queueSessionTagMatchesCurrent = snapshotQueueSessionTagMatches
                    )
                )
            } catch (e: Exception) {
                Log.d(TAG, "[DEVICE_STATE_SNAPSHOT] could not build reconnect participation snapshot: ${e.message}")
                null
            }
            when (reconnectParticipationSnapshot?.replayEligibility?.wireValue) {
                "stale_session_blocked" -> delegatedRuntimeAcceptanceEvaluator.markDimensionGap(
                    DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE,
                    "replay_stale_session_blocked"
                )
                else -> if (wsConnected) {
                    delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
                        DelegatedRuntimeAcceptanceDimension.CONTINUITY_REPLAY_RECONNECT_EVIDENCE
                    )
                }
            }
            val readinessSurfaceSnapshot = delegatedRuntimeReadinessEvaluator.buildSnapshot(deviceId)
            val acceptanceSurfaceSnapshot = delegatedRuntimeAcceptanceEvaluator.buildSnapshot(deviceId)
            val dispatchReadiness = runtimeController.currentDispatchReadiness()
            val authoritativeParticipationSnapshot =
                runtimeController.evaluateAuthoritativeParticipationSnapshot(
                    readinessSatisfied = modeState.crossDeviceEligibility,
                    distributedRuntimeActivity = snapshotStamp.executionBusy,
                    capabilityVisible = hasVisibleCrossDeviceCapability(
                        crossDeviceEligibility = modeState.crossDeviceEligibility,
                        sessionIsAttached = dispatchReadiness.sessionIsAttached
                    )
                )
            val operationalSurface = AndroidOperationalStateSurfaceContract.derive(
                AndroidOperationalStateSurfaceContract.DerivationInput(
                    deviceId = deviceId,
                    durableParticipantId = snapshotDurableParticipantId,
                    runtimeSessionId = UFOGalaxyApplication.runtimeSessionId,
                    attachedSessionId = snapshotAttachedSessionId,
                    capabilitySchemaVersion = capabilityAuthority?.schemaVersion,
                    localIntelligenceStatus = capabilityAuthority?.localIntelligenceStatus,
                    readinessArtifact = readinessSurfaceSnapshot.artifact,
                    acceptanceArtifact = acceptanceSurfaceSnapshot.artifact,
                    executionModeState = modeState.executionModeState,
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    goalExecutionEligibility = modeState.goalExecutionEligibility,
                    localLoopReady = localLoopReady,
                    degradedConditionClass = degradedConditionClass.wireValue,
                    reconnectRecoveryState = reconnectRecoveryState,
                    evidencePresenceKind = evidencePresenceKind.wireValue,
                    replayEligibility = reconnectParticipationSnapshot?.replayEligibility?.wireValue,
                    participantIdentityFreshness = snapshotParticipantFreshness,
                    meshRuntimeClosed = meshRuntimeStateReport?.isRuntimeClosed,
                    executionBusy = snapshotStamp.executionBusy,
                    activeExecutionCount = snapshotStamp.activeExecutionCount
                )
            )
            // ── 统一真相上行合约：预先计算快照级约束语义（消除重复 derive() 调用）──────────────
            // 单次 derive() 调用同时提供 wireValue/isConstraint/isDeferred，
            // 替代之前三个独立的 run { derive(...).isConstraint/isDeferred/wireValue } 块。
            val snapshotConstraintSem = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
                isConstrained = degradedConditionClass ==
                    AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED,
                isDeferred = degradedConditionClass ==
                    AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED,
                isLocalModeGateHold = modeState.isHoldState,
                isExecutionBusy = snapshotStamp.executionBusy == true,
                isHold = false
            )
            // ── 统一真相上行合约：预先计算本地能力状态（消除重复 LocalCapabilityState.derive() 调用）──
            val snapshotLocalLlmReady = localLlmReady()
            val snapshotLocalCapState = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
                localLlmReady = snapshotLocalLlmReady,
                localInferenceAvailable = localInferenceAvailable(),
                accessibilityReady = accessibilityReady,
                isDegraded = managerStateDegraded()
            )
            // ── 统一真相上行合约：预先计算参与层级（消除 4 次重复的 participationTierFor 调用）──
            val snapshotParticipationTier = AndroidAuthoritativeParticipationTruth
                .participationTierFor(authoritativeParticipationSnapshot.state)

            val governanceTruth = currentGovernanceTruth()

            // ── 统一参与者生命周期阶段推导 ─────────────────────────────────────────────────────
            // 从已计算的运行时信号推导 unified_lifecycle_phase，无需额外探针。
            // 该字段为 V2 提供单一权威阶段字段，消除多字段组合推断的歧义。
            val snapshotFormalLifecycle = FormalParticipantLifecycleState.fromManagerState(managerState)
            val snapshotCapabilityVisible = hasVisibleCrossDeviceCapability(
                crossDeviceEligibility = modeState.crossDeviceEligibility,
                sessionIsAttached = dispatchReadiness.sessionIsAttached
            )
            val snapshotUnifiedLifecyclePhase = AndroidUnifiedParticipantLifecyclePhase.derive(
                AndroidUnifiedParticipantLifecyclePhase.DerivationInput(
                    formalLifecycleState = snapshotFormalLifecycle,
                    reconnectRecoveryStateWire = reconnectRecoveryState,
                    crossDeviceEnabled = settings.crossDeviceEnabled,
                    wsConnected = wsConnected,
                    hasDurableParticipantId = snapshotDurableParticipantId != null,
                    capabilityVisible = snapshotCapabilityVisible,
                    sessionAttached = dispatchReadiness.sessionIsAttached,
                    readinessSatisfied = modeState.crossDeviceEligibility,
                    executionBusy = snapshotStamp.executionBusy == true,
                    takeoverActive = currentActiveTakeoverId() != null,
                    interactionSurfaceReady = interactionSurfaceReady,
                    governanceBlocked = governanceTruth.governance_blocked
                )
            )

            // ── PR-3Android: 参与语义规范化快照（在快照发送层唯一推导）────────────────────────
            // 从已计算的运行时信号派生，无需额外探针。
            // 单次 derive() 调用替代三个独立的 run {} 块，消除重复计算开销。
            val snapshotNormalization = AndroidParticipationSemanticNormalizationContract.derive(
                AndroidParticipationSemanticNormalizationContract.NormalizationDerivationInput(
                    localModeActive = modeState.executionModeState ==
                        LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                    executionBusy = snapshotStamp.executionBusy == true,
                    distributedParticipant = snapshotParticipationTier ==
                        AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT,
                    delegatedExecutionActive = governanceTruth.delegated_execution_active,
                    takeoverStateWire = governanceTruth.takeover_state,
                    runtimeConstrained = snapshotConstraintSem.isConstraint,
                    runtimeDeferred = snapshotConstraintSem.isDeferred,
                    governanceBlocked = governanceTruth.governance_blocked,
                    crossDeviceEnabled = settings.crossDeviceEnabled,
                    dispatchEligible = (snapshotParticipationTier ==
                        AndroidAuthoritativeParticipationTruth.ParticipationTier.DISPATCH_ELIGIBLE ||
                        snapshotParticipationTier ==
                        AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT),
                    localCapabilityStateWire = snapshotLocalCapState.wireValue
                )
            )

            // ── PR-02v2 (Android): 预先推导快照级 dispatch 边界收束快照 ─────────────────────────
            // 在 DeviceStateSnapshotPayload 构造前唯一计算，使 V2 可将快照关联到正确的
            // dispatch 边界类型（canonical / controlled_canonical_fallback / compat_fallback /
            // legacy_bypass / not_cross_device），无需字段组合推断。
            //
            // 快照级推导说明：
            //   isLegacyCompatRemapped / isAgentBridgeCompatEntry / isLegacyBypassEntry
            //   在快照发送层均为 false，因为这三类场景只在单次消息处理时可检测；
            //   快照发送时无单次消息上下文，采用保守默认值。
            //   isAgentBridgeFallback：通过 currentFallbackTier + snapshotExecutionPathTag 组合判断。
            val snapshotExecutionPathTagForBoundary =
                AndroidRuntimeObservabilityAuditContract.classifyExecutionPath(
                    spineParticipationKind = null,
                    executionModeState = modeState.executionModeState,
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    isTakeoverPath = false,
                    isDelegated = false,
                    currentFallbackTier = currentFallbackTier
                )
            val snapshotDispatchBoundary = AndroidCrossDeviceDispatchBoundaryContract.derive(
                AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryDerivationInput(
                    isCrossDeviceMode = settings.crossDeviceEnabled &&
                        modeState.executionModeState !=
                        LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                    executionPathTag = snapshotExecutionPathTagForBoundary,
                    isFallbackTierActive = currentFallbackTier != "center_delegated" &&
                        currentFallbackTier != "no_execution",
                    isAgentBridgeFallback = currentFallbackTier == FALLBACK_TIER_CENTER_DELEGATED_WITH_LOCAL &&
                        snapshotExecutionPathTagForBoundary ==
                        AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DEGRADED_PATH,
                    isLegacyCompatRemapped = false,
                    isAgentBridgeCompatEntry = false,
                    isLegacyBypassEntry = false,
                    hasDelegatedOrTakeoverExecution = governanceTruth.delegated_execution_active == true ||
                        governanceTruth.takeover_state == AndroidUnifiedTruthUplinkContract.TAKEOVER_STATE_ACTIVE
                )
            )
            val snapshotIngress = AndroidGovernanceExecutionPolicyIngressContract
                .classifyMsgType(MsgType.DEVICE_STATE_SNAPSHOT)
            val snapshotSemanticBoundary = AndroidDiagnosticsFailureExplanationUplinkContract
                .forDeviceStateSnapshot()
            val snapshotCompletionClosureBoundary = AndroidCompletionClosureUplinkContract
                .deriveForDeviceStateSnapshot()

            // ── PR-08v2 (Android): 预先推导分布式运行参与边界快照（在快照发送层唯一计算）────────────
            // device_state_snapshot 为诊断性信号，isDiagnosticsSignal=true 确保 role 始终为
            // DIAGNOSTICS_SUMMARY_ONLY，同时填充 ownership_posture_class 和 remote_local_mode_class
            // 供 V2 建立 Android 模式/姿态的历史轨迹，而不误用为 authority 决策依据。
            val snapshotParticipationBoundary = try {
                AndroidDistributedRuntimeParticipationBoundaryContract.derive(
                    AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                        sourceRuntimePosture = UFOGalaxyApplication.runtimeController
                            .hostSessionSnapshot?.posture ?: SourceRuntimePosture.CONTROL_ONLY,
                        executionBusy = snapshotStamp.executionBusy == true,
                        executionModeStateWire = modeState.executionModeState,
                        crossDeviceEnabled = settings.crossDeviceEnabled,
                        isFallbackTierActive = snapshotFallbackActive,
                        isCapabilityDegraded = capabilityDegradedForMode,
                        takeoverActive = currentActiveTakeoverId() != null,
                        isDiagnosticsSignal = true
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "[DEVICE_STATE_SNAPSHOT] could not derive participation boundary snapshot: ${e.message}")
                null
            }
            val snapshotTruthOwnershipBoundary = deriveTruthOwnershipBoundary(
                executionBusy = snapshotStamp.executionBusy == true,
                sourceRuntimePosture = UFOGalaxyApplication.runtimeController
                    .hostSessionSnapshot?.posture ?: SourceRuntimePosture.CONTROL_ONLY,
                takeoverActive = false,
                sessionId = UFOGalaxyApplication.runtimeSessionId,
                isSessionRecoveryActive =
                    reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue,
                isCapabilityDegraded = capabilityDegradedForMode,
                isRecoveryActive =
                    reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue ||
                        managerState is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Recovering,
                isDiagnosticsSignal = true,
                isOperatorVisibleSummary = false
            )
            val snapshotInflightRecovery = UFOGalaxyApplication.runtimeController
                .inflightContinuityRecovery.value
            val snapshotRecoveryPhase = AndroidContinuityRecoveryStateModel.derive(
                reconnectRecoveryState = UFOGalaxyApplication.runtimeController
                    .reconnectRecoveryState.value,
                inflightDisposition = snapshotInflightRecovery.disposition
            ).wireValue
            val snapshotRecoverySource = snapshotInflightRecovery.source
            val snapshotLineage = AndroidUplinkLineageMetadataContract.derive(
                executionIdentity = MsgType.DEVICE_STATE_SNAPSHOT.value,
                emissionIdentity = "snapshot:${snapshotStamp.snapshotSequence}",
                durableSessionId = durableRecord?.durableSessionId,
                sessionContinuityEpoch = durableRecord?.sessionContinuityEpoch,
                recoveryBasis = "$snapshotRecoveryPhase:${snapshotRecoverySource.ifBlank { "none" }}"
            )

            val payload = DeviceStateSnapshotPayload(
                device_id = deviceId,
                snapshot_ts = snapshotStamp.timestampMs,
                snapshot_sequence = snapshotStamp.snapshotSequence,
                llama_cpp_available = llamaCppAvailable,
                ncnn_available = ncnnAvailable,
                active_runtime_type = activeRuntimeType,
                model_ready = modelReady,
                accessibility_ready = accessibilityReady,
                overlay_ready = overlayReady,
                local_loop_ready = localLoopReady,
                degraded_reasons = degradedReasons,
                // model_id is null when the primary model is not yet present on disk, so V2
                // can distinguish "no model installed" from "mobilevlm installed".
                model_id = modelId,
                // runtime_type mirrors active_runtime_type so V2 can use either field without
                // ambiguity. When no native runtime is loaded, both fields report "CENTER",
                // indicating execution routes through the center (V2 remote) rather than
                // local inference. V2's _parse_state_snapshot accepts "CENTER" as a valid
                // runtime_type value for this state.
                runtime_type = activeRuntimeType,
                checksum_ok = checksumOk,
                mobilevlm_present = mobilevlmPresent,
                mobilevlm_checksum_ok = mobilevlmChecksumOk,
                seeclick_present = seeClickPresent,
                pending_first_download = pendingFirstDownload,
                local_loop_config = localLoopConfigMap,
                warmup_result = warmupResult,
                runtime_health_snapshot = runtimeHealthMap,
                offline_queue_depth = offlineQueueDepth,
                current_fallback_tier = currentFallbackTier,
                active_execution_count = snapshotStamp.activeExecutionCount,
                execution_busy = snapshotStamp.executionBusy,
                planner_fallback_tier = plannerFallbackTier,
                grounding_fallback_tier = groundingFallbackTier,
                // PR-6: propagate session identity so V2 can correlate this snapshot with the
                // ongoing session axis.  All four fields are null when no durable era or attached
                // session is currently active — null is the correct value, not a placeholder.
                durable_session_id = durableRecord?.durableSessionId,
                session_continuity_epoch = durableRecord?.sessionContinuityEpoch,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                attached_session_id = snapshotAttachedSessionId,
                // PR-8: carrier manifestation/presence hints backed by real Android state.
                carrier_foreground_visible = carrierForegroundVisible,
                interaction_surface_ready = interactionSurfaceReady,
                mode_state = modeState.modeState,
                mode_readiness_state = modeState.modeReadinessState,
                cross_device_eligibility = modeState.crossDeviceEligibility,
                goal_execution_eligibility = modeState.goalExecutionEligibility,
                parallel_execution_eligibility = modeState.parallelExecutionEligibility,
                authoritative_participation_state =
                    authoritativeParticipationSnapshot.state.wireValue,
                participation_tier = AndroidAuthoritativeParticipationTruth
                    .participationTierFor(authoritativeParticipationSnapshot.state)
                    .wireValue,
                authoritative_participation_transition_sequence =
                    authoritativeParticipationSnapshot.transitionSequence,
                authoritative_participation_transition_trigger =
                    authoritativeParticipationSnapshot.lastTransitionTrigger,
                authoritative_participation_transition_history =
                    authoritativeParticipationSnapshot.transitionHistoryWire,
                // PR-10: cross-cutting carrier state backed by real RuntimeController state.
                carrier_runtime_state = carrierRuntimeState,
                reconnect_recovery_state = reconnectRecoveryState,
                // PR-4: authoritative capability authority fields.
                // These are derived from CapabilityAuthoritySnapshot so the field relationship
                // logic is centralised, versioned, and independently testable.
                capability_schema_version = capabilityAuthority?.schemaVersion,
                local_intelligence_status = capabilityAuthority?.localIntelligenceStatus,
                planner_ready = capabilityAuthority?.plannerReady,
                grounding_ready = capabilityAuthority?.groundingReady,
                // PR-04: mesh participation runtime closure fields.
                // Derived from live Android runtime state via AndroidMeshParticipationRuntimeContract.
                mesh_participation_lifecycle_state = meshRuntimeStateReport?.participationLifecycle?.wireValue,
                barrier_participation_state = meshRuntimeStateReport?.barrierState?.wireValue,
                collaboration_lifecycle_state = meshRuntimeStateReport?.collaborationLifecycle?.wireValue,
                mesh_constrained_reasons = meshRuntimeStateReport?.constrainedReasons ?: emptyList(),
                mesh_participation_ready = meshRuntimeStateReport?.isParticipationReady,
                mesh_runtime_engaged = meshRuntimeStateReport?.isRuntimeEngaged,
                mesh_runtime_closed = meshRuntimeStateReport?.isRuntimeClosed,
                mesh_runtime_proof_quality = meshRuntimeStateReport?.proofQuality?.wireValue,
                mesh_direct_schema_version = meshDirectRuntimeSnapshot.schemaVersion,
                mesh_direct_state = meshDirectRuntimeSnapshot.state.wireValue,
                mesh_direct_route = meshDirectRuntimeSnapshot.route.wireValue,
                mesh_direct_channel_ready = meshDirectRuntimeSnapshot.channelReady,
                mesh_direct_peer_count = meshDirectRuntimeSnapshot.peerCount,
                mesh_direct_ready_peer_count = meshDirectRuntimeSnapshot.readyPeerCount,
                mesh_direct_reason_codes = meshDirectRuntimeSnapshot.reasonCodes,
                mesh_direct_last_attempt_stage = meshDirectRuntimeSnapshot.lastAttemptStage,
                mesh_direct_last_attempt_succeeded = meshDirectRuntimeSnapshot.lastAttemptSucceeded,
                // PR-8Android: canonical execution mode state and participant identity fields.
                // These close the R8 gap: V2 can read execution_mode_state and
                // durable_participant_id without inferring them from field combinations.
                execution_mode_state = modeState.executionModeState,
                durable_participant_id = snapshotDurableParticipantId,
                participant_identity_freshness = snapshotParticipantFreshness,
                reported_state_semantic_class = reportedStateSemanticClass.wireValue,
                degraded_condition_class = degradedConditionClass.wireValue,
                local_observation_basis = localObservationBasis.wireValue,
                // PR-7B: explicit evidence presence kind so V2 applies the correct governance
                // policy without relying on optimistic defaults for missing or failed evidence.
                evidence_presence_kind = evidencePresenceKind.wireValue,
                operational_surface_schema_version = operationalSurface.schemaVersion,
                operational_surface_states = operationalSurface.states,
                operational_surface_authority = operationalSurface.authority,
                operational_surface_limitations = operationalSurface.limitations,
                // PR-12: real reconnect/recovery participation fields for cross-repo closure.
                // These close the gap between scattered reconnect components and expose a
                // coherent, wire-stable participation model that V2 can act on during recovery.
                reconnect_participation_kind = reconnectParticipationSnapshot?.participationKind?.wireValue,
                identity_reuse_decision = reconnectParticipationSnapshot?.identityReuseDecision?.wireValue,
                replay_eligibility = reconnectParticipationSnapshot?.replayEligibility?.wireValue,
                // PR-5 (Android): runtime observability and problem-solving audit fields.
                // All three fields are derived from existing runtime signals via the
                // AndroidRuntimeObservabilityAuditContract classifiers.  No new probes are
                // required; the classifiers produce coherent, machine-readable audit metadata
                // from signal combinations already computed above.
                observability_audit_schema_version = AndroidRuntimeObservabilityAuditContract.SCHEMA_VERSION,
                execution_path_tag = AndroidRuntimeObservabilityAuditContract.classifyExecutionPath(
                    spineParticipationKind = null,
                    executionModeState = modeState.executionModeState,
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    isTakeoverPath = false,
                    isDelegated = false,
                    currentFallbackTier = currentFallbackTier
                ).wireValue,
                audit_contribution_class = AndroidRuntimeObservabilityAuditContract.classifyAuditContribution(
                    eventPhase = null,
                    isTerminalPhase = false,
                    isTakeoverEvent = false,
                    isDelegatedEvent = false,
                    isInterruptionPhase = false,
                    isRecoveryPhase = reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue ||
                        reconnectRecoveryState == ReconnectRecoveryState.RECOVERED.wireValue,
                    isParticipationSnapshot = true,
                    isOperatorActionEvent = false
                ).wireValue,
                observability_reliability_class = AndroidRuntimeObservabilityAuditContract.classifyObservabilityReliability(
                    evidencePresenceKind = evidencePresenceKind.wireValue,
                    degradedConditionClass = degradedConditionClass.wireValue,
                    reconnectRecoveryState = reconnectRecoveryState,
                    localObservationBasis = localObservationBasis.wireValue
                ).wireValue,
                // ── 统一真相上行合约字段（AndroidUnifiedTruthUplinkContract）────────────────────────
                // 在快照发送层唯一填充，确保 V2 快照消费无需字段组合推断。
                unified_truth_schema_version = AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION,
                dispatch_eligible = (snapshotParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISPATCH_ELIGIBLE ||
                    snapshotParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT),
                distributed_participant = (snapshotParticipationTier ==
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT),
                session_attached = (snapshotParticipationTier !=
                    AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH),
                local_mode_active = (modeState.executionModeState ==
                    LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue),
                runtime_constrained = snapshotConstraintSem.isConstraint,
                runtime_deferred = snapshotConstraintSem.isDeferred,
                constraint_semantics = snapshotConstraintSem.wireValue,
                governance_state = governanceTruth.governance_state,
                governance_blocked = governanceTruth.governance_blocked,
                delegated_execution_active = governanceTruth.delegated_execution_active,
                takeover_state = governanceTruth.takeover_state,
                local_llm_ready = snapshotLocalLlmReady,
                local_mode_capable = snapshotLocalCapState.isLocalModeCapable,
                local_capability_state = snapshotLocalCapState.wireValue,
                // ── 统一参与者生命周期阶段字段 ─────────────────────────────────────
                // 在快照发送层唯一填充，提供 V2 可直接消费的单一权威阶段字段。
                unified_lifecycle_phase = snapshotUnifiedLifecyclePhase.wireValue,
                unified_lifecycle_schema_version = AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION,
                // ── PR-3Android: 参与语义规范化字段 ──────────────────────────────────────
                // 从 snapshotNormalization（已在快照发送层唯一推导）直接读取，
                // 无需 run {} 块，消除重复 derive() 调用开销。
                participation_mode_class = snapshotNormalization.participationModeClass.wireValue,
                local_execution_active = snapshotNormalization.localExecutionActive,
                local_execution_activity_kind = snapshotNormalization.localExecutionActivityKind.wireValue,
                participation_semantic_schema_version = AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION,
                // ── PR-4Android: 工程边界可靠性字段（在快照发送层填充）─────────────────────────────
                // 在单一发送层唯一计算，使 V2 可直接判断快照来源字段的完整性，
                // 无需对每条快照进行字段存在性检查。
                //
                // source_field_coverage_class: 快照不携带 source_component 和 task_id，
                //   因此采用快照路径规则：device_id + runtime_session_id 均存在 → COMPLETE；
                //   仅 device_id 存在 → PARTIAL；device_id 缺失 → ABSENT。
                source_field_coverage_class = AndroidBoundaryReliabilityContract.classifySourceFieldCoverage(
                    deviceId = deviceId.takeIf { it.isNotBlank() },
                    sourceComponent = null,
                    taskId = null,
                    runtimeSessionId = UFOGalaxyApplication.runtimeSessionId
                ).wireValue,
                boundary_reliability_schema_version = AndroidBoundaryReliabilityContract.SCHEMA_VERSION,
                // ── PR-02v2 (Android): 跨设备 dispatch 边界收束字段（在快照发送层填充）──────────────
                // 从已计算的运行时信号推导，使 V2 可将快照关联到正确的 dispatch 边界类型，
                // 无需通过 execution_path_tag / participation_tier 等字段组合推断。
                dispatch_boundary_class = snapshotDispatchBoundary.dispatchBoundaryClass.wireValue,
                dispatch_path_consumption_kind = snapshotDispatchBoundary.dispatchPathConsumptionKind.wireValue,
                dispatch_boundary_schema_version = AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION,
                ingress_boundary_class = snapshotIngress.boundaryClass.wireValue,
                ingress_consumption_kind = snapshotIngress.consumptionKind.wireValue,
                ingress_signal_class = snapshotIngress.signalClass.wireValue,
                ingress_schema_version = snapshotIngress.schemaVersion,
                // ── PR-05v2 (Android): 结果上行闭环边界字段（在快照发送层填充）──────────────────────
                // device_state_snapshot 始终为诊断性信号：无任务结果语义，V2 MUST NOT 用于任务关闭。
                // 这使 V2 core/android_device_state_store.py 可直接读取字段而无需推断快照信号类型。
                result_signal_class = AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.wireValue,
                acceptance_candidate_class = AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE.wireValue,
                result_uplink_boundary_schema_version = AndroidResultUplinkBoundaryContract.SCHEMA_VERSION,
                uplink_semantic_boundary_class = snapshotSemanticBoundary.uplinkSemanticBoundaryClass.wireValue,
                operator_projection_class = snapshotSemanticBoundary.operatorProjectionClass.wireValue,
                diagnostics_failure_explanation_schema_version =
                    AndroidDiagnosticsFailureExplanationUplinkContract.SCHEMA_VERSION,
                authority_runtime_completion_signal_class = snapshotCompletionClosureBoundary
                    .authorityRuntimeCompletionSignalClass.wireValue,
                result_completion_signal_class = snapshotCompletionClosureBoundary
                    .resultCompletionSignalClass.wireValue,
                closure_finalization_signal_class = snapshotCompletionClosureBoundary
                    .closureFinalizationSignalClass.wireValue,
                operator_done_projection_class = snapshotCompletionClosureBoundary
                    .operatorDoneProjectionClass.wireValue,
                completion_closure_uplink_schema_version =
                    AndroidCompletionClosureUplinkContract.SCHEMA_VERSION,
                // ── PR-08v2 (Android): 分布式运行参与边界收束字段（在快照发送层填充）────────────────────
                // device_state_snapshot 为诊断性信号，participation_boundary_role 始终为
                // DIAGNOSTICS_SUMMARY_ONLY，明确声明本快照不参与 distributed runtime 决策，
                // 防止 V2 将 operator-visible 摘要误用为 authority basis。
                // ownership_posture_class 和 remote_local_mode_class 从快照发送时的真实运行时状态
                // 推导，供 V2 在存储快照时建立 Android 模式/姿态的历史轨迹。
                participation_boundary_role = snapshotParticipationBoundary?.participationBoundaryRole?.wireValue,
                ownership_posture_class = snapshotParticipationBoundary?.ownershipPostureClass?.wireValue,
                remote_local_mode_class = snapshotParticipationBoundary?.remoteLocalModeClass?.wireValue,
                participation_boundary_schema_version = AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION,
                authority_signal_class = snapshotTruthOwnershipBoundary.authoritySignalClass.wireValue,
                ownership_uplink_class = snapshotTruthOwnershipBoundary.ownershipUplinkClass.wireValue,
                session_continuity_class = snapshotTruthOwnershipBoundary.sessionContinuityClass.wireValue,
                device_posture_signal_class =
                    snapshotTruthOwnershipBoundary.devicePostureSignalClass.wireValue,
                distributed_truth_ownership_uplink_schema_version =
                    AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION,
                // ── PR-116: unified continuity recovery state ──────────────────────────────
                // Derives the unified recovery phase from authoritative runtime sources so V2
                // can consume Android recovery state without combining fields across carriers.
                continuity_recovery_state = snapshotRecoveryPhase,
                continuity_recovery_source = snapshotRecoverySource,
                continuity_recovery_schema_version =
                    AndroidContinuityRecoveryStateModel.SCHEMA_VERSION,
                uplink_lineage_schema_version = AndroidUplinkLineageMetadataContract.SCHEMA_VERSION,
                uplink_lineage_execution_id = snapshotLineage.executionIdentity,
                uplink_lineage_emission_id = snapshotLineage.emissionIdentity,
                uplink_lineage_dedupe_key = snapshotLineage.dedupeKey,
                uplink_lineage_recovery_basis = snapshotLineage.recoveryBasis
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_STATE_SNAPSHOT,
                payload = payload,
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(deviceId, MsgType.DEVICE_STATE_SNAPSHOT)
            )

            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            Log.i(
                TAG,
                "[DEVICE_STATE_SNAPSHOT] device_id=$deviceId model_ready=$modelReady " +
                    "local_loop_ready=$localLoopReady active_runtime=$activeRuntimeType " +
                    "offline_queue_depth=$offlineQueueDepth fallback_tier=$currentFallbackTier " +
                    "mesh_direct_state=${meshDirectRuntimeSnapshot.state.wireValue} " +
                    "mesh_direct_route=${meshDirectRuntimeSnapshot.route.wireValue} " +
                    "mode_state=${modeState.modeState} mode_readiness_state=${modeState.modeReadinessState} " +
                    "execution_mode_state=${modeState.executionModeState} " +
                    "cross_device_eligibility=${modeState.crossDeviceEligibility} " +
                    "unified_lifecycle_phase=${snapshotUnifiedLifecyclePhase.wireValue} sent=$sent"
            )
            GalaxyLogger.log(
                GalaxyLogger.TAG_DEVICE_STATE_SNAPSHOT, mapOf(
                    "event" to "device_state_snapshot_sent",
                    "device_id" to deviceId,
                    "model_ready" to modelReady,
                    "accessibility_ready" to accessibilityReady,
                    "overlay_ready" to overlayReady,
                    "local_loop_ready" to localLoopReady,
                    "active_runtime_type" to activeRuntimeType,
                    "warmup_result" to warmupResult,
                    "offline_queue_depth" to offlineQueueDepth,
                    "current_fallback_tier" to currentFallbackTier,
                    "mesh_direct_state" to meshDirectRuntimeSnapshot.state.wireValue,
                    "mesh_direct_route" to meshDirectRuntimeSnapshot.route.wireValue,
                    "snapshot_sequence" to snapshotStamp.snapshotSequence,
                    "active_execution_count" to snapshotStamp.activeExecutionCount,
                    "execution_busy" to snapshotStamp.executionBusy,
                    "mode_state" to modeState.modeState,
                    "mode_readiness_state" to modeState.modeReadinessState,
                    "execution_mode_state" to modeState.executionModeState,
                    "cross_device_eligibility" to modeState.crossDeviceEligibility,
                    "goal_execution_eligibility" to modeState.goalExecutionEligibility,
                    "parallel_execution_eligibility" to modeState.parallelExecutionEligibility,
                    "llama_cpp_available" to llamaCppAvailable,
                    "ncnn_available" to ncnnAvailable,
                    "unified_lifecycle_phase" to snapshotUnifiedLifecyclePhase.wireValue,
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_STATE_SNAPSHOT] unexpected error: ${e.message}", e)
            GalaxyLogger.log(
                GalaxyLogger.TAG_DEVICE_STATE_SNAPSHOT, mapOf(
                    "event" to "device_state_snapshot_error",
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    // ── PR-4 (Android): Governance / acceptance / strategy artifact uplinks ──────────────────

    /**
     * Builds a [DeviceGovernanceReportPayload] from the current
     * [delegatedRuntimeGovernanceEvaluator] state and transmits it as a
     * [MsgType.DEVICE_GOVERNANCE_REPORT] AIP v3 uplink message.
     *
     * Called during service start so V2 post-graduation governance and enforcement paths have
     * a baseline Android-side governance artifact available immediately on connection.  At
     * this stage all dimensions are typically UNKNOWN, which is itself a valid structured
     * signal — V2 can distinguish "not yet observed" from "regression detected".
     * Send failure never throws — any error is caught and logged.
     */
    private fun sendDeviceGovernanceReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_GOVERNANCE_REPORT] skipped — localDeviceId is blank")
                return
            }
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
                ingress_schema_version = ingress.schemaVersion
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_GOVERNANCE_REPORT,
                payload = payload,
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_GOVERNANCE_REPORT)
            )

            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            Log.i(
                TAG,
                "[DEVICE_GOVERNANCE_REPORT] sent snapshot_id=${snapshot.snapshotId} " +
                    "artifact=${artifact.semanticTag} sent=$sent"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_governance_report_emitted",
                    "snapshot_id" to snapshot.snapshotId,
                    "artifact_tag" to artifact.semanticTag,
                    "missing_dimension_count" to missingDimensions.size,
                    "first_regression_reason" to (firstRegressionReason ?: ""),
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_GOVERNANCE_REPORT] unexpected error: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_governance_report_error",
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    /**
     * Builds a [DeviceAcceptanceReportPayload] from the current
     * [delegatedRuntimeAcceptanceEvaluator] state and transmits it as a
     * [MsgType.DEVICE_ACCEPTANCE_REPORT] AIP v3 uplink message.
     *
     * Called during service start so V2 final acceptance / graduation gate paths have a
     * baseline Android-side acceptance artifact available immediately on connection.  At this
     * stage all dimensions are typically UNKNOWN — V2 can distinguish "not yet evidenced"
     * from "evidence gap detected".  Send failure never throws.
     */
    private fun sendDeviceAcceptanceReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_ACCEPTANCE_REPORT] skipped — localDeviceId is blank")
                return
            }
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
                ingress_schema_version = ingress.schemaVersion
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_ACCEPTANCE_REPORT,
                payload = payload,
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_ACCEPTANCE_REPORT)
            )

            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            Log.i(
                TAG,
                "[DEVICE_ACCEPTANCE_REPORT] sent snapshot_id=${snapshot.snapshotId} " +
                    "artifact=${artifact.semanticTag} sent=$sent"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_acceptance_report_emitted",
                    "snapshot_id" to snapshot.snapshotId,
                    "artifact_tag" to artifact.semanticTag,
                    "missing_dimension_count" to missingDimensions.size,
                    "gap_reason" to (firstGapReason ?: ""),
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_ACCEPTANCE_REPORT] unexpected error: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_acceptance_report_error",
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    /**
     * Builds a [DeviceStrategyReportPayload] from the current
     * [delegatedRuntimeStrategyEvaluator] state and transmits it as a
     * [MsgType.DEVICE_STRATEGY_REPORT] AIP v3 uplink message.
     *
     * Called during service start so V2 program strategy / evolution control paths have a
     * baseline Android-side strategy posture artifact available immediately on connection.
     * Strategy artifacts are **advisory/observation-only** — V2 retains full orchestration
     * authority over program strategy decisions.  Send failure never throws.
     */
    private fun sendDeviceStrategyReport() {
        try {
            val deviceId = localDeviceId.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "[DEVICE_STRATEGY_REPORT] skipped — localDeviceId is blank")
                return
            }
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
                ingress_schema_version = ingress.schemaVersion
            )

            val envelope = AipMessage(
                type = MsgType.DEVICE_STRATEGY_REPORT,
                payload = payload,
                device_id = deviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                idempotency_key = buildIdempotencyKey(snapshot.snapshotId, MsgType.DEVICE_STRATEGY_REPORT)
            )

            val sent = webSocketClient.sendJson(gson.toJson(envelope))
            Log.i(
                TAG,
                "[DEVICE_STRATEGY_REPORT] sent snapshot_id=${snapshot.snapshotId} " +
                    "artifact=${artifact.semanticTag} sent=$sent"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_strategy_report_emitted",
                    "snapshot_id" to snapshot.snapshotId,
                    "artifact_tag" to artifact.semanticTag,
                    "missing_dimension_count" to missingDimensions.size,
                    "first_risk_reason" to (firstRiskReason ?: ""),
                    "sent" to sent
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DEVICE_STRATEGY_REPORT] unexpected error: ${e.message}", e)
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "device_strategy_report_error",
                    "error" to (e.message ?: "unknown")
                )
            )
        }
    }

    private suspend fun handleHybridExecute(messageId: String?, rawHybridJson: String) {
        val payload = try {
            val jsonObj = gson.fromJson(rawHybridJson, com.google.gson.JsonObject::class.java)
            val p = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            gson.fromJson(p, HybridExecutePayload::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "[ADVANCED:HYBRID_EXECUTE] failed to parse payload message_id=$messageId: ${e.message}")
            null
        }

        if (payload == null || payload.task_id.isBlank()) {
            sendHybridDegrade(
                rawHybridJson = rawHybridJson,
                reason = "hybrid_execute_bad_payload"
            )
            return
        }

        val taskId = payload.task_id
        val barrierSessionId = "$BARRIER_SESSION_PREFIX_HYBRID:$taskId"
        val rolloutSnapshot = UFOGalaxyApplication.runtimeController.rolloutControlSnapshot.value

        updateMeshRuntimeSignalState(
            collaborationState = CollaborationLifecycleState.SUBTASK_ASSIGNED,
            participationActive = true
        )
        barrierCoordinationParticipant.enterBarrierWait(barrierSessionId)
        updateMeshRuntimeSignalState(collaborationState = CollaborationLifecycleState.EXECUTING)
        // Emit the live barrier-wait/executing state before terminal handling.
        sendDeviceStateSnapshot()

        val executionResult = try {
            hybridExecuteCoordinator.acceptHybridExecute(payload, rolloutSnapshot)
        } catch (e: Exception) {
            Log.e(TAG, "[ADVANCED:HYBRID_EXECUTE] task_id=$taskId execution error: ${e.message}", e)
            com.ufo.galaxy.runtime.HybridExecutionResult(
                taskId = taskId,
                deviceId = localDeviceId,
                status = com.ufo.galaxy.runtime.HybridExecutionResult.Status.LOCAL_FAILURE,
                localResult = null,
                error = e.message ?: "hybrid_execute_runtime_error",
                localStepCount = 0,
                latencyMs = 0L
            )
        }

        val terminalCollaborationState = when (executionResult.status) {
            com.ufo.galaxy.runtime.HybridExecutionResult.Status.LOCAL_SUCCESS ->
                CollaborationLifecycleState.COMPLETED
            com.ufo.galaxy.runtime.HybridExecutionResult.Status.CANCELLED ->
                CollaborationLifecycleState.CANCELLED
            com.ufo.galaxy.runtime.HybridExecutionResult.Status.BLOCKED,
            com.ufo.galaxy.runtime.HybridExecutionResult.Status.LOCAL_FAILURE ->
                CollaborationLifecycleState.FAILED
        }
        updateMeshRuntimeSignalState(
            collaborationState = terminalCollaborationState,
            participationActive = false
        )

        if (executionResult.status == com.ufo.galaxy.runtime.HybridExecutionResult.Status.BLOCKED) {
            barrierCoordinationParticipant.handleBarrierTimeout()
        } else {
            barrierCoordinationParticipant.acknowledgeBarrierRelease(barrierSessionId)
        }
        // Emit the terminal collaboration + barrier outcome (released or timed_out).
        sendDeviceStateSnapshot()

        // Reset local barrier state for the next cycle after terminal emission.
        barrierCoordinationParticipant.resetBarrier(barrierSessionId)

        sendHybridResult(hybridExecuteCoordinator.toHybridResultPayload(executionResult))
    }

    private fun sendHybridResult(payload: HybridResultPayload) {
        val envelope = AipMessage(
            type = MsgType.HYBRID_RESULT,
            payload = payload,
            correlation_id = payload.correlation_id ?: payload.task_id,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(payload.task_id, MsgType.HYBRID_RESULT)
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(
            TAG,
            "[ADVANCED:HYBRID_RESULT] task_id=${payload.task_id} status=${payload.status} sent=$sent"
        )
    }

    /**
     * Emits HYBRID_DEGRADE for malformed HYBRID_EXECUTE payloads.
     */
    private fun sendHybridDegrade(rawHybridJson: String, reason: String) {
        val taskId = try {
            gson.fromJson(rawHybridJson, com.google.gson.JsonObject::class.java)
                ?.getAsJsonObject("payload")?.get("task_id")?.asString ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "[ADVANCED:HYBRID_DEGRADE] failed to extract task_id from hybrid_execute payload: ${e.message}")
            ""
        }

        val degradePayload = HybridDegradePayload(
            task_id = taskId,
            correlation_id = taskId.ifEmpty { null },
            reason = reason,
            fallback_mode = "local_only",
            device_id = localDeviceId
        )
        val envelope = AipMessage(
            type = MsgType.HYBRID_DEGRADE,
            payload = degradePayload,
            correlation_id = taskId.ifEmpty { null },
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(taskId.ifBlank { "hybrid_degrade" }, MsgType.HYBRID_DEGRADE)
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(TAG, "[ADVANCED:HYBRID_DEGRADE] task_id=$taskId sent=$sent reason=$reason")
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "hybrid_degrade_sent",
                "task_id" to taskId,
                "reason" to reason
            )
        )
    }

    // ── PR-35: Promoted long-tail stateful handlers ───────────────────────────

    /**
     * Extracts a list of non-null strings from a named JSON array field inside [obj].
     *
     * Returns an empty list when [obj] is null or the field is absent or not a JSON array.
     * Null / non-string elements within the array are silently skipped.
     *
     * Used by [handlePeerExchange] and [handleMeshTopology] to parse capability and node
     * list fields without duplicating the Gson array-traversal logic.
     *
     * @param obj   JSON object from which to extract the array.
     * @param key   Name of the array field inside [obj].
     */
    private fun parseJsonStringArray(obj: com.google.gson.JsonObject?, key: String): List<String> {
        val arr = obj?.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { it?.asString }
    }

    /**
     * Dedicated handler for inbound [MsgType.PEER_EXCHANGE] messages (PR-35 promoted).
     *
     * Replaces the minimal-compat (log-only) path with structured capability exchange:
     * 1. Parses the inbound message into a [PeerExchangePayload].
     * 2. Updates [lastPeerCapabilities] with the advertised capability list for this peer.
     * 3. Sends a delivery [AckPayload] back to the gateway.
     * 4. Emits a structured [GalaxyLogger.TAG_PEER_EXCHANGE] log entry.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound peer_exchange message.
     */
    private fun handlePeerExchange(messageId: String?, rawJson: String) {
        val payload = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val p = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            PeerExchangePayload(
                source_device_id = p?.get("source_device_id")?.asString ?: "",
                capabilities = parseJsonStringArray(p, "capabilities"),
                mesh_id = p?.get("mesh_id")?.asString,
                exchange_id = p?.get("exchange_id")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "[PEER_EXCHANGE] failed to parse payload: ${e.message}")
            PeerExchangePayload(source_device_id = "")
        }

        // Update per-peer capability record when we have a valid source device.
        if (payload.source_device_id.isNotBlank()) {
            lastPeerCapabilities = lastPeerCapabilities + (payload.source_device_id to payload.capabilities)
            updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot(payload.mesh_id ?: lastMeshTopologyMeshId))
        }

        // Send delivery ack.
        sendAdvancedAck(MsgType.PEER_EXCHANGE, messageId)

        GalaxyLogger.log(
            GalaxyLogger.TAG_PEER_EXCHANGE, mapOf(
                "event" to "peer_exchange_received",
                "source_device_id" to payload.source_device_id,
                "exchange_id" to (payload.exchange_id ?: ""),
                "capability_count" to payload.capabilities.size,
                "mesh_id" to (payload.mesh_id ?: ""),
                "message_id" to (messageId ?: ""),
                "known_peer_count" to lastPeerCapabilities.size
            )
        )
        Log.i(TAG, "[PEER_EXCHANGE] source=${payload.source_device_id} capabilities=${payload.capabilities.size} known_peers=${lastPeerCapabilities.size}")
    }

    /**
     * Dedicated handler for inbound [MsgType.PEER_ANNOUNCE] messages (PR-36 promoted).
     *
     * Replaces the minimal-compat (log-only) path with structured peer presence tracking:
     * 1. Parses the inbound message into a [PeerAnnouncePayload].
     * 2. Updates [lastPeerAnnouncements] with the latest announce record for this peer,
     *    retaining the entry with the highest [PeerAnnouncePayload.announce_seq] so that
     *    out-of-order delivery does not overwrite a newer record.
     * 3. Sends a delivery [AckPayload] back to the gateway.
     * 4. Emits a structured [GalaxyLogger.TAG_PEER_ANNOUNCE] log entry.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound peer_announce message.
     */
    private fun handlePeerAnnounce(messageId: String?, rawJson: String) {
        val payload = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val p = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            PeerAnnouncePayload(
                peer_device_id = p?.get("peer_device_id")?.asString ?: "",
                peer_role = p?.get("peer_role")?.asString,
                session_id = p?.get("session_id")?.asString,
                announce_seq = p?.get("announce_seq")?.asInt ?: 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "[PEER_ANNOUNCE] failed to parse payload: ${e.message}")
            PeerAnnouncePayload(peer_device_id = "")
        }

        // Update the per-peer presence record when we have a valid peer device id,
        // but only when the incoming announce_seq is not stale.
        if (payload.peer_device_id.isNotBlank()) {
            val existing = lastPeerAnnouncements[payload.peer_device_id]
            if (existing == null || payload.announce_seq >= existing.announce_seq) {
                lastPeerAnnouncements = lastPeerAnnouncements + (payload.peer_device_id to payload)
                updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot(lastMeshTopologyMeshId))
            }
        }

        // Send delivery ack.
        sendAdvancedAck(MsgType.PEER_ANNOUNCE, messageId)

        GalaxyLogger.log(
            GalaxyLogger.TAG_PEER_ANNOUNCE, mapOf(
                "event" to "peer_announce_received",
                "peer_device_id" to payload.peer_device_id,
                "peer_role" to (payload.peer_role ?: ""),
                "session_id" to (payload.session_id ?: ""),
                "announce_seq" to payload.announce_seq,
                "message_id" to (messageId ?: ""),
                "known_peer_count" to lastPeerAnnouncements.size
            )
        )
        Log.i(TAG, "[PEER_ANNOUNCE] peer=${payload.peer_device_id} role=${payload.peer_role} seq=${payload.announce_seq} known_peers=${lastPeerAnnouncements.size}")
    }

    /**
     * Dedicated handler for inbound [MsgType.MESH_TOPOLOGY] messages (PR-35 promoted).
     *
     * Replaces the minimal-compat (log-only) path with structured topology tracking:
     * 1. Parses the inbound message into a [MeshTopologyPayload].
     * 2. Updates [lastMeshTopologyNodes] and [lastMeshTopologySeq] when the incoming
     *    [MeshTopologyPayload.topology_seq] is greater than the last retained value
     *    (out-of-order messages are silently ignored).
     * 3. Sends a delivery [AckPayload] back to the gateway.
     * 4. Emits a structured [GalaxyLogger.TAG_MESH_TOPOLOGY] log entry.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound mesh_topology message.
     */
    private fun handleMeshTopology(messageId: String?, rawJson: String) {
        val payload = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val p = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            MeshTopologyPayload(
                mesh_id = p?.get("mesh_id")?.asString ?: "",
                nodes = parseJsonStringArray(p, "nodes"),
                topology_seq = p?.get("topology_seq")?.asInt ?: 0,
                coordinator = p?.get("coordinator")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "[MESH_TOPOLOGY] failed to parse payload: ${e.message}")
            MeshTopologyPayload(mesh_id = "")
        }

        // Only retain topology updates that are newer than what we already have.
        val accepted = payload.topology_seq > lastMeshTopologySeq
        if (accepted) {
            lastMeshTopologyMeshId = payload.mesh_id.takeIf { it.isNotBlank() }
            lastMeshTopologyNodes = payload.nodes
            lastMeshTopologySeq = payload.topology_seq
            updateMeshDirectRuntimeSnapshot(deriveMeshDirectRuntimeSnapshot(lastMeshTopologyMeshId))
        }

        // Send delivery ack regardless of whether we retained the snapshot.
        sendAdvancedAck(MsgType.MESH_TOPOLOGY, messageId)

        GalaxyLogger.log(
            GalaxyLogger.TAG_MESH_TOPOLOGY, mapOf(
                "event" to "mesh_topology_received",
                "mesh_id" to payload.mesh_id,
                "node_count" to payload.nodes.size,
                "topology_seq" to payload.topology_seq,
                "accepted" to accepted,
                "coordinator" to (payload.coordinator ?: ""),
                "message_id" to (messageId ?: "")
            )
        )
        crossRepoRegressionHooks.recordMesh(
            status = if (payload.mesh_id.isNotBlank()) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED,
            reason = if (payload.mesh_id.isNotBlank()) null else "mesh_topology_missing_mesh_id"
        )
        emitCrossRepoRegressionSnapshot("cross_repo_mesh_topology")
        Log.i(TAG, "[MESH_TOPOLOGY] mesh_id=${payload.mesh_id} nodes=${payload.nodes.size} seq=${payload.topology_seq} accepted=$accepted")
    }

    /**
     * Dedicated handler for inbound [MsgType.COORD_SYNC] messages (PR-35 promoted).
     *
     * Replaces the minimal-compat generic [AckPayload] response with a sequence-aware
     * [CoordSyncAckPayload] that enables the coordinator to detect gaps in the
     * acknowledgement sequence:
     * 1. Increments [coordSyncTickCount].
     * 2. Parses `sync_seq` from the inbound payload (defaults to 0 when absent).
     * 3. Sends a [CoordSyncAckPayload] carrying `sync_id`, `sync_seq`, and `tick_count`.
     * 4. Emits a structured [GalaxyLogger.TAG_COORD_SYNC] log entry.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound coord_sync message.
     */
    private fun handleCoordSync(messageId: String?, rawJson: String) {
        coordSyncTickCount++
        val currentTickCount = coordSyncTickCount

        val syncSeq = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val p = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            p?.get("sync_seq")?.asInt ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "[COORD_SYNC] failed to extract sync_seq: ${e.message}")
            0
        }
        val syncId = messageId ?: java.util.UUID.randomUUID().toString()

        val ackPayload = CoordSyncAckPayload(
            sync_id = syncId,
            device_id = localDeviceId,
            sync_seq = syncSeq,
            tick_count = currentTickCount,
            phase = "active"
        )
        val envelope = AipMessage(
            type = MsgType.ACK,
            payload = ackPayload,
            device_id = localDeviceId,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(syncId, MsgType.COORD_SYNC)
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))

        GalaxyLogger.log(
            GalaxyLogger.TAG_COORD_SYNC, mapOf(
                "event" to "coord_sync_ack_sent",
                "sync_id" to syncId,
                "sync_seq" to syncSeq,
                "tick_count" to currentTickCount,
                "sent" to sent
            )
        )
        Log.i(TAG, "[COORD_SYNC] sync_id=$syncId seq=$syncSeq tick=$currentTickCount sent=$sent")
    }

    // ── PR-3: Canonical takeover request/response path ────────────────────────────────────

    /**
     * Handles an inbound [MsgType.TAKEOVER_REQUEST] message via the canonical path.
     *
     * Parses the raw JSON into a [TakeoverRequestEnvelope], evaluates device eligibility
     * via [TakeoverEligibilityAssessor], gates delegated receipt via [DelegatedRuntimeReceiver],
     * and dispatches accepted takeover work through [delegatedTakeoverExecutor].
     *
     * ## Decision flow
     * 1. Parse the inbound JSON into a [TakeoverRequestEnvelope].
     * 2. Validate the handoff contract; reject with `"handoff_contract_invalid:…"` if invalid.
     * 3. Invoke [TakeoverEligibilityAssessor.assess] with the current [activeTakeoverId].
     * 4. If **not eligible**: send rejection with the assessor's structured reason and return.
     * 5. Gate delegated receipt via [DelegatedRuntimeReceiver.receive]:
     *    if the session is null, DETACHING, or DETACHED — send rejection with the receiver's
     *    reason and return.
     * 6. Send acceptance [TakeoverResponseEnvelope] (accepted=true) with runtime_host_id and
     *    formation_role; launch a coroutine to call [delegatedTakeoverExecutor.execute].
     * 7. On executor completion, send goal_result (success) or goal_error + notify
     *    [com.ufo.galaxy.runtime.RuntimeController.notifyTakeoverFailed] (failure / timeout /
     *    cancellation).
     *
     * ## Concurrent-takeover protection
     * [activeTakeoverId] is set to the incoming `takeover_id` while the request is being
     * processed and cleared when the response has been sent.  This prevents a second inbound
     * [MsgType.TAKEOVER_REQUEST] from being accepted while one is already in progress.
     *
     * ## Delivery acknowledgement
     * The delivery ack is sent by the generic [MsgType.ACK_ON_RECEIPT_TYPES] path in
     * [onAdvancedMessage] before this function is called.  This function sends the
     * richer [MsgType.TAKEOVER_RESPONSE] envelope which carries the structured decision
     * so the main runtime can update its session truth immediately.
     *
     * @param messageId Optional message_id from the inbound AIP v3 envelope.
     * @param rawJson   Raw JSON string of the inbound takeover_request message.
     */
    private fun handleTakeoverRequest(messageId: String?, rawJson: String): TakeoverHandlingResult {
        val envelope = try {
            val jsonObj = gson.fromJson(rawJson, com.google.gson.JsonObject::class.java)
            val payload = jsonObj?.getAsJsonObject("payload") ?: jsonObj
            TakeoverRequestEnvelope(
                takeover_id = payload?.get("takeover_id")?.asString
                    ?: (messageId ?: java.util.UUID.randomUUID().toString()),
                task_id = payload?.get("task_id")?.asString ?: "",
                trace_id = payload?.get("trace_id")?.asString ?: java.util.UUID.randomUUID().toString(),
                goal = payload?.get("goal")?.asString ?: "",
                source_device_id = payload?.get("source_device_id")?.asString,
                source_runtime_posture = payload?.get("source_runtime_posture")?.asString,
                exec_mode = payload?.get("exec_mode")?.asString ?: AgentRuntimeBridge.EXEC_MODE_REMOTE,
                route_mode = payload?.get("route_mode")?.asString ?: AgentRuntimeBridge.ROUTE_MODE_CROSS_DEVICE,
                session_id = payload?.get("session_id")?.asString,
                runtime_session_id = payload?.get("runtime_session_id")?.asString,
                checkpoint = payload?.get("checkpoint")?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "[PR3:TAKEOVER] Failed to parse takeover_request: ${e.message}")
            emitRuntimeDiagnostics(
                taskId = messageId ?: "unknown",
                nodeName = "takeover_ingress",
                errorType = "takeover_parse_error",
                errorContext = e.message ?: "unknown parse error"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_request_parse_error",
                    "error" to (e.message ?: "unknown"),
                    "message_id" to (messageId ?: "")
                )
            )
            // Return a safe failure result; ack was already sent by the generic path.
            return TakeoverHandlingResult(
                takeoverId = messageId ?: "unknown",
                taskId = "",
                traceId = "",
                accepted = false,
                reason = "invalid_takeover_request:parse_error"
            )
        }

        val resolvedPosture = envelope.resolvedPosture
        val contractValidation = handoffContractValidator.validate(envelope)
        if (contractValidation.isInvalid) {
            val reason = "handoff_contract_invalid:${contractValidation.summary()}"
            emitRuntimeDiagnostics(
                taskId = envelope.task_id,
                nodeName = "takeover_preflight",
                errorType = "handoff_contract_invalid",
                errorContext = contractValidation.summary()
            )
            sendTakeoverResponse(
                TakeoverResponseEnvelope(
                    takeover_id = envelope.takeover_id,
                    task_id = envelope.task_id,
                    trace_id = envelope.trace_id,
                    accepted = false,
                    rejection_reason = reason,
                    device_id = localDeviceId,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    source_runtime_posture = envelope.source_runtime_posture,
                    exec_mode = envelope.exec_mode
                )
            )
            return TakeoverHandlingResult(
                takeoverId = envelope.takeover_id,
                taskId = envelope.task_id,
                traceId = envelope.trace_id,
                accepted = false,
                reason = reason
            )
        }

        // ── Eligibility assessment (canonical PR-3 path) ──────────────────────
        // Capture the existing active takeover before setting the new one.
        // The assessor uses the captured value to detect concurrent takeovers.
        val existingActiveTakeoverId = currentActiveTakeoverId()
        var takeoverExecutionStarted = false
        // Mark this request as in-flight so any concurrent inbound request is blocked.
        updateActiveTakeoverId(envelope.takeover_id)
        try {
            val runtimeModeState = UFOGalaxyApplication.appSettings.authoritativeModeState(
                wsConnected = webSocketClient.isConnected(),
                runtimeActive = UFOGalaxyApplication.runtimeController.state.value !is
                    com.ufo.galaxy.runtime.RuntimeController.RuntimeState.Idle,
                capabilityDegraded = managerStateDegraded()
            )
            val eligibility = takeoverEligibilityAssessor.assess(
                envelope = envelope,
                activeTakeoverId = existingActiveTakeoverId,
                modeContext = TakeoverEligibilityAssessor.RuntimeModeContext(
                    executionModeState = runtimeModeState.executionModeState,
                    acceptsCrossDeviceTasks = runtimeModeState.acceptsCrossDeviceTasks,
                    isHoldState = runtimeModeState.isHoldState
                )
            )

            Log.i(
                TAG,
                "[PR3:TAKEOVER] takeover_request received takeover_id=${envelope.takeover_id} " +
                    "task_id=${envelope.task_id} trace_id=${envelope.trace_id} " +
                    "source_device=${envelope.source_device_id} posture=$resolvedPosture " +
                    "mode_state=${runtimeModeState.executionModeState} " +
                    "mode_accepts_cross_device=${runtimeModeState.acceptsCrossDeviceTasks} " +
                    "mode_hold=${runtimeModeState.isHoldState} " +
                    "eligible=${eligibility.eligible} reason=${eligibility.reason}"
            )
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_request_received",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "source_device_id" to (envelope.source_device_id ?: ""),
                    "source_runtime_posture" to resolvedPosture,
                    "exec_mode" to envelope.exec_mode,
                    "route_mode" to envelope.route_mode,
                    "eligible" to eligibility.eligible,
                    "eligibility_reason" to eligibility.reason
                )
            )

            // If the device is not eligible, reject with the assessor's specific reason so
            // the main runtime can distinguish device-not-ready from executor-not-implemented.
            if (!eligibility.eligible) {
                emitRuntimeDiagnostics(
                    taskId = envelope.task_id,
                    nodeName = "takeover_preflight",
                    errorType = "takeover_ineligible",
                    errorContext = eligibility.reason
                )
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = eligibility.reason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = eligibility.reason
                )
            }

            // PR-8: Gate delegated receipt on an active AttachedRuntimeSession.
            // Device readiness (eligibility) is necessary but not sufficient: the session
            // must also be explicitly attached before Android accepts delegated work.
            val currentSession = UFOGalaxyApplication.runtimeController.attachedSession.value
            val receiptResult = delegatedRuntimeReceiver.receive(envelope, currentSession)
            if (receiptResult is DelegatedRuntimeReceiver.ReceiptResult.Rejected) {
                Log.w(
                    TAG,
                    "[PR8:DELEGATE] Delegated receipt rejected — no active attached session: " +
                        "takeover_id=${envelope.takeover_id} reason=${receiptResult.reason}"
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "delegated_receipt_rejected",
                        "takeover_id" to envelope.takeover_id,
                        "task_id" to envelope.task_id,
                        "trace_id" to envelope.trace_id,
                        "rejection_outcome" to receiptResult.outcome.reason,
                        "reason" to receiptResult.reason
                    )
                )
                emitRuntimeDiagnostics(
                    taskId = envelope.task_id,
                    nodeName = "takeover_session_gate",
                    errorType = "takeover_session_rejected",
                    errorContext = receiptResult.reason
                )
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = receiptResult.reason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = receiptResult.reason
                )
            }
            // Session gate passed — extract the delegated unit and activation record.
            val delegatedUnit = (receiptResult as DelegatedRuntimeReceiver.ReceiptResult.Accepted).unit
            val activationRecord = receiptResult.record
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "delegated_receipt_accepted",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "attached_session_id" to delegatedUnit.attachedSessionId,
                    "resolved_posture" to delegatedUnit.resolvedPosture,
                    "activation_status" to activationRecord.activationStatus.wireValue
                )
            )

            // Continuity identity gate: re-read the active session at execution time to catch
            // any session change that occurred between receipt and execution (race condition).
            // Uses validateRuntimeIdentity — the canonical stale-identity gate — so the
            // takeover path enforces the same continuity contract as the replay path.
            val latestSession = UFOGalaxyApplication.runtimeController.attachedSession.value
            val takeoverIdentityResult = continuityIntegration.validateRuntimeIdentity(
                unitAttachedSessionId = delegatedUnit.attachedSessionId,
                activeSession = latestSession
            )
            if (takeoverIdentityResult !is AndroidContinuityIntegration.IdentityValidationResult.Valid) {
                val rejectReason = when (takeoverIdentityResult) {
                    is AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity ->
                        "${AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY}: " +
                            "received=${takeoverIdentityResult.receivedSessionId}"
                    else -> AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY
                }
                Log.w(
                    TAG,
                    "[CONTINUITY] takeover rejected: stale identity " +
                        "takeover_id=${envelope.takeover_id} reason=$rejectReason"
                )
                emitRuntimeDiagnostics(
                    taskId = envelope.task_id,
                    nodeName = "takeover_continuity_gate",
                    errorType = "stale_attachment_session",
                    errorContext = rejectReason
                )
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = rejectReason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = rejectReason
                )
            }

            val takeoverRecoveryAuthority =
                continuityIntegration.validateTakeoverRecoveryAuthority(
                    recoveryContext = envelope.recovery_context,
                    activeSession = latestSession,
                    durableSession = UFOGalaxyApplication.runtimeController
                        .durableSessionContinuityRecord.value,
                    isRecoveryDispatch = envelope.is_resumable == true ||
                        !envelope.interruption_reason.isNullOrBlank() ||
                        envelope.recovery_context.isNotEmpty()
                )
            if (takeoverRecoveryAuthority is AndroidContinuityIntegration
                .TakeoverRecoveryAuthorityResult.Rejected
            ) {
                Log.w(
                    TAG,
                    "[CONTINUITY] takeover recovery rejected: " +
                        "takeover_id=${envelope.takeover_id} reason=${takeoverRecoveryAuthority.reason}"
                )
                emitRuntimeDiagnostics(
                    taskId = envelope.task_id,
                    nodeName = "takeover_recovery_authority_gate",
                    errorType = "stale_takeover_recovery_authority",
                    errorContext = takeoverRecoveryAuthority.reason
                )
                sendTakeoverResponse(
                    TakeoverResponseEnvelope(
                        takeover_id = envelope.takeover_id,
                        task_id = envelope.task_id,
                        trace_id = envelope.trace_id,
                        accepted = false,
                        rejection_reason = takeoverRecoveryAuthority.reason,
                        device_id = localDeviceId,
                        runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                        source_runtime_posture = envelope.source_runtime_posture,
                        exec_mode = envelope.exec_mode
                    )
                )
                return TakeoverHandlingResult(
                    takeoverId = envelope.takeover_id,
                    taskId = envelope.task_id,
                    traceId = envelope.trace_id,
                    accepted = false,
                    reason = takeoverRecoveryAuthority.reason
                )
            }

            // PR-14: Record that a delegated execution has been accepted under the current
            // attached session.  This increments the session's delegatedExecutionCount without
            // re-creating the session or changing its identity — multiple tasks can flow
            // through the same session without any per-task session re-init.
            UFOGalaxyApplication.runtimeController.recordDelegatedExecutionAccepted()

            // PR-12: The executor owns all lifecycle transitions (PENDING → ACTIVATING →
            // ACTIVE → COMPLETED/FAILED); do not pre-advance the record here.

            // PR-5: Device is eligible — accept the takeover as a first-class runtime host.
            // Include runtime_host_id and formation_role in the acceptance response so the
            // main runtime can record this Android instance as a formal execution surface.
            val hostDescriptor = UFOGalaxyApplication.runtimeHostDescriptor
            val acceptanceResponse = TakeoverResponseEnvelope(
                takeover_id = envelope.takeover_id,
                task_id = envelope.task_id,
                trace_id = envelope.trace_id,
                accepted = true,
                rejection_reason = null,
                device_id = localDeviceId,
                runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                source_runtime_posture = envelope.source_runtime_posture,
                exec_mode = envelope.exec_mode,
                runtime_host_id = hostDescriptor?.hostId,
                formation_role = hostDescriptor?.formationRole?.wireValue
            )
            sendTakeoverResponse(acceptanceResponse)

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "takeover_accepted",
                    "takeover_id" to envelope.takeover_id,
                    "task_id" to envelope.task_id,
                    "trace_id" to envelope.trace_id,
                    "runtime_host_id" to (hostDescriptor?.hostId ?: ""),
                    "formation_role" to (hostDescriptor?.formationRole?.wireValue ?: ""),
                    "attached_session_id" to delegatedUnit.attachedSessionId,
                    "activation_status" to activationRecord.activationStatus.wireValue
                )
            )

            // PR-12/PR-13: Dispatch through the canonical delegated takeover executor.
            // DelegatedTakeoverExecutor manages the full lifecycle: creates the
            // DelegatedExecutionTracker, emits ACK/PROGRESS/RESULT signals, advances tracker
            // through PENDING → ACTIVATING → ACTIVE → COMPLETED/FAILED, and returns a typed
            // outcome.  PR-13 adds PROGRESS signal emission at ACTIVE and distinguishes
            // TIMEOUT/CANCELLED outcomes from generic FAILED.
            UFOGalaxyApplication.runtimeController.onRemoteTaskStarted()

            // ── PR-2: emit takeover_milestone at execution start ──────────────────────
            // PR-6: capture session identity at milestone emission.
            val durTakeover = UFOGalaxyApplication.runtimeController.durableSessionContinuityRecord.value
            deviceExecutionEventSink.onEvent(
                DeviceExecutionEventPayload(
                    flow_id = envelope.takeover_id,
                    task_id = envelope.task_id,
                    phase = DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
                    device_id = localDeviceId,
                    source_component = "GalaxyConnectionService.handleTakeoverRequest",
                    durable_session_id = durTakeover?.durableSessionId,
                    session_continuity_epoch = durTakeover?.sessionContinuityEpoch,
                    runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
                    attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                    // PR-8: carrier manifestation/presence hints at takeover start.
                    carrier_foreground_visible = UFOGalaxyApplication.runtimeController.appForegroundVisible.value,
                    interaction_surface_ready = UFOGalaxyApplication.appSettings.accessibilityReady &&
                        UFOGalaxyApplication.appSettings.overlayReady,
                    // PR-10: carrier runtime state at takeover start.
                    carrier_runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                    // PR-7B: explicit evidence presence kind at takeover milestone.
                    evidence_presence_kind =
                        AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                            DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
                        ).wireValue
                )
            )

            takeoverExecutionStarted = true
            serviceScope.launch {
                try {
                    val outcome = delegatedTakeoverExecutor.execute(delegatedUnit, activationRecord)
                    when (outcome) {
                        is DelegatedTakeoverExecutor.ExecutionOutcome.Completed -> {
                            val enriched = outcome.goalResult.copy(
                                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
                            )
                            val disposition =
                                sendGoalResult(enriched, envelope.trace_id, ROUTE_MODE_CROSS_DEVICE)
                            Log.i(
                                TAG,
                                "[PR13:TAKEOVER] goal_result produced takeover_id=${envelope.takeover_id} " +
                                    "task_id=${envelope.task_id} status=${enriched.status} " +
                                    "trace_id=${envelope.trace_id} " +
                                    "attached_session_id=${delegatedUnit.attachedSessionId} " +
                                    "steps=${outcome.tracker.stepCount} " +
                                    "delivery=${disposition.name.lowercase()}"
                            )
                            // ── PR-2: emit terminal takeover execution event on completion ─
                            deviceExecutionEventSink.onEvent(
                                buildTerminalExecutionEvent(
                                    taskId = envelope.task_id,
                                    result = enriched,
                                    stepIndex = outcome.tracker.stepCount - 1,
                                    source = "GalaxyConnectionService.handleTakeoverRequest",
                                    flowId = envelope.takeover_id
                                )
                            )
                        }
                        is DelegatedTakeoverExecutor.ExecutionOutcome.Failed -> {
                            val returnedGoalResult = outcome.goalResult?.copy(
                                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
                            )
                            val failureReason = returnedGoalResult?.error
                                ?: returnedGoalResult?.hold_reason
                                ?: outcome.error
                            Log.w(
                                TAG,
                                "[PR13:TAKEOVER] delegated execution failed takeover_id=${envelope.takeover_id} " +
                                    "task_id=${envelope.task_id} error=$failureReason " +
                                    "status=${returnedGoalResult?.status ?: "thrown_exception"}"
                            )
                            if (returnedGoalResult != null) {
                                sendGoalResult(returnedGoalResult, envelope.trace_id, ROUTE_MODE_CROSS_DEVICE)
                            } else {
                                sendGoalError(
                                    envelope.task_id, null, null,
                                    "takeover_error: ${outcome.error}", envelope.trace_id,
                                    ROUTE_MODE_CROSS_DEVICE
                                )
                            }
                            val normalizedReturnedStatus = returnedGoalResult?.let {
                                UgcpSharedSchemaAlignment.normalizeLifecycleStatus(it.status)
                            }
                            if (shouldEmitTakeoverFailureDiagnostics(
                                    returnedGoalResult = returnedGoalResult,
                                    normalizedReturnedStatus = normalizedReturnedStatus
                                )
                            ) {
                                emitRuntimeDiagnostics(
                                    taskId = envelope.task_id,
                                    nodeName = "takeover_execution",
                                    errorType = "takeover_execution_failed",
                                    errorContext = failureReason
                                )
                            }
                            val terminalFailureResult = returnedGoalResult ?: GoalResultPayload(
                                task_id = envelope.task_id,
                                correlation_id = envelope.task_id,
                                status = when (outcome.ledger.lastResult?.resultKind) {
                                    DelegatedExecutionSignal.ResultKind.TIMEOUT -> EdgeExecutor.STATUS_TIMEOUT
                                    DelegatedExecutionSignal.ResultKind.CANCELLED -> EdgeExecutor.STATUS_CANCELLED
                                    else -> EdgeExecutor.STATUS_ERROR
                                },
                                error = failureReason,
                                device_id = localDeviceId,
                                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
                            )
                            deviceExecutionEventSink.onEvent(
                                buildTerminalExecutionEvent(
                                    taskId = envelope.task_id,
                                    result = terminalFailureResult,
                                    stepIndex = maxOf(0, outcome.tracker.stepCount - 1),
                                    source = "GalaxyConnectionService.handleTakeoverRequest",
                                    flowId = envelope.takeover_id
                                )
                            )
                            // PR-23: Notify RuntimeController — the canonical failure path —
                            // so all surface layers can clear stale "active" or "in-control"
                            // state.  Derive the cause from the last RESULT signal recorded
                            // in the emitted-signal ledger.
                            val failureCause = when (outcome.ledger.lastResult?.resultKind) {
                                DelegatedExecutionSignal.ResultKind.TIMEOUT -> TakeoverFallbackEvent.Cause.TIMEOUT
                                DelegatedExecutionSignal.ResultKind.CANCELLED -> TakeoverFallbackEvent.Cause.CANCELLED
                                else -> TakeoverFallbackEvent.Cause.FAILED
                            }
                            UFOGalaxyApplication.runtimeController.notifyTakeoverFailed(
                                takeoverId = envelope.takeover_id,
                                taskId = envelope.task_id,
                                traceId = envelope.trace_id,
                                reason = failureReason,
                                cause = failureCause
                            )
                        }
                    }
                } finally {
                    UFOGalaxyApplication.runtimeController.onRemoteTaskFinished()
                    clearActiveTakeoverIdIfMatches(envelope.takeover_id)
                }
            }

            return TakeoverHandlingResult(
                takeoverId = envelope.takeover_id,
                taskId = envelope.task_id,
                traceId = envelope.trace_id,
                accepted = true,
                reason = "accepted"
            )
        } finally {
            if (!takeoverExecutionStarted) {
                clearActiveTakeoverIdIfMatches(envelope.takeover_id)
            }
        }
    }

    /**
     * Sends a [TakeoverResponseEnvelope] wrapped in an AIP v3 [MsgType.TAKEOVER_RESPONSE]
     * envelope to the gateway.
     *
     * The response carries the takeover decision (accepted / rejected), rejection reason,
     * and echoes [TakeoverResponseEnvelope.source_runtime_posture] for posture correlation
     * on the main-runtime side.
     *
     * @param response The populated [TakeoverResponseEnvelope] to send.
     */
    private fun sendTakeoverResponse(response: TakeoverResponseEnvelope) {
        val envelope = AipMessage(
            type = MsgType.TAKEOVER_RESPONSE,
            payload = response,
            correlation_id = response.task_id.ifEmpty { null },
            device_id = localDeviceId,
            trace_id = response.trace_id,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(
                taskId = response.task_id.ifBlank { response.takeover_id },
                type = MsgType.TAKEOVER_RESPONSE,
                traceId = response.trace_id
            ),
            source_runtime_posture = response.source_runtime_posture
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        crossRepoRegressionHooks.recordOwnershipTransfer(
            status = if (sent) ScenarioOutcomeStatus.PASSED else ScenarioOutcomeStatus.FAILED,
            reason = if (sent) null else "takeover_response_send_failed"
        )
        emitCrossRepoRegressionSnapshot("cross_repo_takeover_response")
        Log.i(
            TAG,
            "[PR3:TAKEOVER] takeover_response sent takeover_id=${response.takeover_id} " +
                "task_id=${response.task_id} accepted=${response.accepted} " +
                "reason=${response.rejection_reason} sent=$sent"
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "takeover_response_sent",
                "takeover_id" to response.takeover_id,
                "task_id" to response.task_id,
                "trace_id" to response.trace_id,
                "accepted" to response.accepted,
                "rejection_reason" to (response.rejection_reason ?: ""),
                "source_runtime_posture" to (response.source_runtime_posture ?: ""),
                "sent" to sent
            )
        )
    }

    private data class OperatorActionExecutionOutcome(
        val executionStatus: String,
        val rollbackStatus: String = OperatorActionResultPayload.ROLLBACK_NOT_REQUIRED,
        val error: String? = null,
        val details: Map<String, String> = emptyMap()
    )

    private fun resolveOperatorActionId(
        requestActionId: String?,
        inboundActionId: String?
    ): String {
        return requestActionId
            ?.takeIf { it.isNotBlank() }
            ?: inboundActionId?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
    }

    private suspend fun handleOperatorActionRequest(
        inboundActionId: String?,
        payloadJson: String,
        traceId: String?
    ) {
        val request = try {
            gson.fromJson(payloadJson, OperatorActionRequestPayload::class.java)
        } catch (e: Exception) {
            val actionId = resolveOperatorActionId(null, inboundActionId)
            val uplinkBoundary = AndroidToolActionAuthorizationUplinkContract.derive(
                AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                    actionKind = "unknown",
                    phase = OperatorActionResultPayload.PHASE_DECISION,
                    decisionStatus = OperatorActionResultPayload.DECISION_REJECTED,
                    executionStatus = OperatorActionResultPayload.EXECUTION_REJECTED,
                    error = "operator_action_parse_error:failed_to_parse_operator_action_request_payload",
                    details = emptyMap()
                )
            )
            sendOperatorActionResult(
                OperatorActionResultPayload(
                    action_id = actionId,
                    action_kind = "unknown",
                    phase = OperatorActionResultPayload.PHASE_DECISION,
                    decision_status = OperatorActionResultPayload.DECISION_REJECTED,
                    execution_status = OperatorActionResultPayload.EXECUTION_REJECTED,
                    error = "operator_action_parse_error:failed_to_parse_operator_action_request_payload",
                    operator_intent_capture_class = uplinkBoundary.operatorIntentCaptureClass.wireValue,
                    runtime_authority_class = uplinkBoundary.runtimeAuthorityClass.wireValue,
                    actual_execution_signal_class = uplinkBoundary.actualExecutionSignalClass.wireValue,
                    tool_invocation_signal_class = uplinkBoundary.toolInvocationSignalClass.wireValue,
                    result_reporting_signal_class = uplinkBoundary.resultReportingSignalClass.wireValue,
                    post_action_explanation_class = uplinkBoundary.postActionExplanationClass.wireValue,
                    tool_action_authorization_schema_version =
                        AndroidToolActionAuthorizationUplinkContract.SCHEMA_VERSION
                )
            )
            return
        }
        val actionId = resolveOperatorActionId(request.action_id, inboundActionId)
        val resolvedTraceId = resolveExecutionTraceId(traceId ?: request.trace_id)
        val actionKind = AndroidOperatorActionGovernanceContract.ActionKind.fromWire(request.action_kind)
        if (actionKind == null) {
            val uplinkBoundary = AndroidToolActionAuthorizationUplinkContract.derive(
                AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                    actionKind = request.action_kind,
                    phase = OperatorActionResultPayload.PHASE_DECISION,
                    decisionStatus = OperatorActionResultPayload.DECISION_REJECTED,
                    executionStatus = OperatorActionResultPayload.EXECUTION_REJECTED,
                    error = "operator_action_rejected:unknown_action_kind",
                    details = emptyMap()
                )
            )
            sendOperatorActionResult(
                OperatorActionResultPayload(
                    action_id = actionId,
                    action_kind = request.action_kind,
                    phase = OperatorActionResultPayload.PHASE_DECISION,
                    decision_status = OperatorActionResultPayload.DECISION_REJECTED,
                    execution_status = OperatorActionResultPayload.EXECUTION_REJECTED,
                    trace_id = resolvedTraceId,
                    error = "operator_action_rejected:unknown_action_kind",
                    operator_intent_capture_class = uplinkBoundary.operatorIntentCaptureClass.wireValue,
                    runtime_authority_class = uplinkBoundary.runtimeAuthorityClass.wireValue,
                    actual_execution_signal_class = uplinkBoundary.actualExecutionSignalClass.wireValue,
                    tool_invocation_signal_class = uplinkBoundary.toolInvocationSignalClass.wireValue,
                    result_reporting_signal_class = uplinkBoundary.resultReportingSignalClass.wireValue,
                    post_action_explanation_class = uplinkBoundary.postActionExplanationClass.wireValue,
                    tool_action_authorization_schema_version =
                        AndroidToolActionAuthorizationUplinkContract.SCHEMA_VERSION
                )
            )
            return
        }
        val governanceContext = UFOGalaxyApplication.runtimeController
            .buildOperatorActionEligibilityContext(currentActiveTakeoverId())
        val targetTaskId = request.task_id ?: UFOGalaxyApplication.runtimeController.activeTaskId
        val participationSnapshot = UFOGalaxyApplication.runtimeController
            .evaluateAuthoritativeParticipationSnapshot(
                readinessSatisfied = governanceContext.dispatchEligible,
                distributedRuntimeActivity = governanceContext.activeTaskId != null
            )
        // ── PR-B2: 在 action 接收时刻构建完整参与上下文快照 ────────────────────────────
        // 参与上下文在此处一次性捕获，并原样传入 DECISION 和 EXECUTION 两个阶段的上行结果载体，
        // 确保 V2 下游消费方可以将 operator action 决策与执行结果关联到同一参与语境。
        val governanceTruth = currentGovernanceTruth()
        val receiveTimeParticipationContext = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = participationSnapshot,
            isLocalModeActive = !governanceContext.crossDeviceEnabled,
            isRuntimeConstrained = !governanceContext.dispatchEligible,
            isRuntimeDeferred = governanceContext.reconnectRecoveryStateWire ==
                ReconnectRecoveryState.RECOVERING.wireValue,
            isDelegatedExecutionActive = governanceTruth.delegated_execution_active
        )
        // ── PR-B2: 通过 OperatorActionReceiver 进行治理门控 ────────────────────────────
        // 所有 V2 下行 directed operator action 必须经过 OperatorActionReceiver 的治理门控，
        // 并通过 GovernanceDecision 返回携带完整参与上下文的决策结果。
        val governanceDecision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = actionKind,
            context = governanceContext,
            taskId = targetTaskId,
            participationContext = receiveTimeParticipationContext
        )
        val eligibility = governanceDecision.eligibility
        val decisionBoundary = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = actionKind.wireValue,
                phase = OperatorActionResultPayload.PHASE_DECISION,
                decisionStatus = when (eligibility) {
                    AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted ->
                        OperatorActionResultPayload.DECISION_ACCEPTED
                    is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected ->
                        OperatorActionResultPayload.DECISION_REJECTED
                },
                executionStatus = when (eligibility) {
                    AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted ->
                        OperatorActionResultPayload.EXECUTION_PENDING
                    is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected ->
                        OperatorActionResultPayload.EXECUTION_REJECTED
                },
                error = governanceDecision.rejectionReason,
                details = emptyMap()
            )
        )
        val decisionPayloadBase = OperatorActionResultPayload(
            action_id = actionId,
            action_kind = actionKind.wireValue,
            phase = OperatorActionResultPayload.PHASE_DECISION,
            decision_status = when (eligibility) {
                AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted ->
                    OperatorActionResultPayload.DECISION_ACCEPTED
                is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected ->
                    OperatorActionResultPayload.DECISION_REJECTED
            },
            execution_status = when (eligibility) {
                AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted ->
                    OperatorActionResultPayload.EXECUTION_PENDING
                is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected ->
                    OperatorActionResultPayload.EXECUTION_REJECTED
            },
            task_id = targetTaskId,
            trace_id = resolvedTraceId,
            runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
            reconnect_recovery_state = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value.wireValue,
            authoritative_participation_state = governanceDecision.participationContext.authoritativeParticipationState,
            // ── PR-B2: 参与上下文字段——保留完整上下文供 V2 消费 ───────────────────────
            participation_tier = governanceDecision.participationContext.participationTier,
            local_mode_active = governanceDecision.participationContext.localModeActive,
            runtime_constrained = governanceDecision.participationContext.runtimeConstrained,
            runtime_deferred = governanceDecision.participationContext.runtimeDeferred,
            delegated_execution_active = governanceDecision.participationContext.delegatedExecutionActive,
            operator_intent_capture_class = decisionBoundary.operatorIntentCaptureClass.wireValue,
            runtime_authority_class = decisionBoundary.runtimeAuthorityClass.wireValue,
            actual_execution_signal_class = decisionBoundary.actualExecutionSignalClass.wireValue,
            tool_invocation_signal_class = decisionBoundary.toolInvocationSignalClass.wireValue,
            result_reporting_signal_class = decisionBoundary.resultReportingSignalClass.wireValue,
            post_action_explanation_class = decisionBoundary.postActionExplanationClass.wireValue,
            tool_action_authorization_schema_version =
                AndroidToolActionAuthorizationUplinkContract.SCHEMA_VERSION,
            attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
            active_takeover_id = currentActiveTakeoverId(),
            error = governanceDecision.rejectionReason
        )
        sendOperatorActionResult(decisionPayloadBase)
        if (!governanceDecision.isAccepted) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "operator_action_rejected",
                    "action_id" to actionId,
                    "action_kind" to actionKind.wireValue,
                    "reason" to (governanceDecision.rejectionReason ?: ""),
                    "participation_tier" to governanceDecision.participationContext.participationTier
                )
            )
            return
        }

        val executionOutcome = executeOperatorAction(actionKind, targetTaskId, actionId)
        val refreshedParticipation = UFOGalaxyApplication.runtimeController
            .refreshOperatorGovernanceTruthSnapshot()
        // ── PR-B2: 刷新执行阶段参与上下文 ─────────────────────────────────────────────
        // 执行后可能发生参与状态迁移，刷新参与上下文以确保 EXECUTION 阶段结果
        // 携带执行完成后的真实参与语境，而非仅保留 DECISION 阶段的快照。
        val refreshedGovernanceTruth = currentGovernanceTruth()
        val execPhaseParticipationContext = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = refreshedParticipation,
            isLocalModeActive = !governanceContext.crossDeviceEnabled,
            isRuntimeConstrained = !UFOGalaxyApplication.runtimeController
                .currentDispatchReadiness().isEligible,
            isRuntimeDeferred = UFOGalaxyApplication.runtimeController
                .reconnectRecoveryState.value == ReconnectRecoveryState.RECOVERING,
            isDelegatedExecutionActive = refreshedGovernanceTruth.delegated_execution_active
        )
        val executionBoundary = AndroidToolActionAuthorizationUplinkContract.derive(
            AndroidToolActionAuthorizationUplinkContract.DerivationInput(
                actionKind = actionKind.wireValue,
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                decisionStatus = OperatorActionResultPayload.DECISION_ACCEPTED,
                executionStatus = executionOutcome.executionStatus,
                error = executionOutcome.error,
                details = executionOutcome.details
            )
        )
        sendOperatorActionResult(
            decisionPayloadBase.copy(
                phase = OperatorActionResultPayload.PHASE_EXECUTION,
                execution_status = executionOutcome.executionStatus,
                rollback_status = executionOutcome.rollbackStatus,
                authoritative_participation_state = execPhaseParticipationContext.authoritativeParticipationState,
                participation_tier = execPhaseParticipationContext.participationTier,
                local_mode_active = execPhaseParticipationContext.localModeActive,
                runtime_constrained = execPhaseParticipationContext.runtimeConstrained,
                runtime_deferred = execPhaseParticipationContext.runtimeDeferred,
                delegated_execution_active = execPhaseParticipationContext.delegatedExecutionActive,
                runtime_state = UFOGalaxyApplication.runtimeController.state.value.wireLabel,
                reconnect_recovery_state = UFOGalaxyApplication.runtimeController
                    .reconnectRecoveryState.value.wireValue,
                attached_session_id = UFOGalaxyApplication.runtimeController.attachedSession.value?.sessionId,
                active_takeover_id = currentActiveTakeoverId(),
                actual_execution_signal_class = executionBoundary.actualExecutionSignalClass.wireValue,
                tool_invocation_signal_class = executionBoundary.toolInvocationSignalClass.wireValue,
                result_reporting_signal_class = executionBoundary.resultReportingSignalClass.wireValue,
                post_action_explanation_class = executionBoundary.postActionExplanationClass.wireValue,
                error = executionOutcome.error,
                details = executionOutcome.details
            )
        )
    }

    private suspend fun executeOperatorAction(
        action: AndroidOperatorActionGovernanceContract.ActionKind,
        taskId: String?,
        actionId: String
    ): OperatorActionExecutionOutcome {
        val runtimeController = UFOGalaxyApplication.runtimeController
        return when (action) {
            AndroidOperatorActionGovernanceContract.ActionKind.REVALIDATE_PARTICIPATION -> {
                val snapshot = runtimeController.refreshOperatorGovernanceTruthSnapshot()
                OperatorActionExecutionOutcome(
                    executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                    details = mapOf("participation_state" to snapshot.state.wireValue)
                )
            }
            AndroidOperatorActionGovernanceContract.ActionKind.FORCE_REATTACH -> {
                if (!UFOGalaxyApplication.appSettings.crossDeviceEnabled) {
                    return OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                        error = "operator_action_failed:cross_device_disabled"
                    )
                }
                runtimeController.invalidateSession()
                runtimeController.connectIfEnabled()
                val attached = runtimeController.attachedSession.value?.isAttached == true
                OperatorActionExecutionOutcome(
                    executionStatus = if (attached) {
                        OperatorActionResultPayload.EXECUTION_EXECUTED
                    } else {
                        OperatorActionResultPayload.EXECUTION_PARTIAL
                    },
                    error = if (attached) null else "operator_action_partial:reattach_pending_connect",
                    details = mapOf("attached_session" to attached.toString())
                )
            }
            AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION -> {
                val effectiveTaskId = taskId ?: runtimeController.activeTaskId
                if (effectiveTaskId.isNullOrBlank()) {
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                        error = "operator_action_failed:missing_active_task"
                    )
                } else {
                    runtimeController.publishTaskStatusUpdate(
                        taskId = effectiveTaskId,
                        correlationId = effectiveTaskId,
                        progressDetail = "operator_retry_delegated_execution_requested"
                    )
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                        details = mapOf("task_id" to effectiveTaskId)
                    )
                }
            }
            AndroidOperatorActionGovernanceContract.ActionKind.TRIGGER_RECOVERY,
            AndroidOperatorActionGovernanceContract.ActionKind.REOPEN_REBIND_SESSION -> {
                operatorRecoveryActionMutex.withLock {
                    val wasEnabled = UFOGalaxyApplication.appSettings.crossDeviceEnabled
                    UFOGalaxyApplication.appSettings.crossDeviceEnabled = true
                    val recovered = runtimeController.reconnect()
                    if (recovered) {
                        OperatorActionExecutionOutcome(
                            executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED
                        )
                    } else {
                        UFOGalaxyApplication.appSettings.crossDeviceEnabled = wasEnabled
                        if (wasEnabled) {
                            runtimeController.connectIfEnabled()
                        }
                        OperatorActionExecutionOutcome(
                            executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                            rollbackStatus = if (wasEnabled) {
                                OperatorActionResultPayload.ROLLBACK_COMPENSATING_ACTION_REQUESTED
                            } else {
                                OperatorActionResultPayload.ROLLBACK_COMPLETED
                            },
                            error = "operator_action_failed:reconnect_unsuccessful"
                        )
                    }
                }
            }
            AndroidOperatorActionGovernanceContract.ActionKind.SUSPEND_ISOLATE_DEVICE -> {
                runtimeController.applyKillSwitch("operator_action:$actionId")
                OperatorActionExecutionOutcome(
                    executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED
                )
            }
            AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE -> {
                val effectiveTaskId = taskId ?: runtimeController.activeTaskId
                if (effectiveTaskId.isNullOrBlank()) {
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                        error = "operator_action_failed:missing_task_id"
                    )
                } else {
                    runtimeController.publishTaskResult(
                        taskId = effectiveTaskId,
                        correlationId = effectiveTaskId
                    )
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                        details = mapOf("task_id" to effectiveTaskId)
                    )
                }
            }
            AndroidOperatorActionGovernanceContract.ActionKind.REJECT_CLOSURE -> {
                val effectiveTaskId = taskId ?: runtimeController.activeTaskId
                if (effectiveTaskId.isNullOrBlank()) {
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                        error = "operator_action_failed:missing_task_id"
                    )
                } else {
                    runtimeController.publishTaskCancelled(
                        taskId = effectiveTaskId,
                        correlationId = effectiveTaskId
                    )
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_EXECUTED,
                        details = mapOf("task_id" to effectiveTaskId)
                    )
                }
            }
            AndroidOperatorActionGovernanceContract.ActionKind.REOPEN_CLOSURE -> {
                val effectiveTaskId = taskId ?: runtimeController.activeTaskId
                if (effectiveTaskId.isNullOrBlank()) {
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_FAILED,
                        error = "operator_action_failed:missing_task_id"
                    )
                } else {
                    runtimeController.publishTaskStatusUpdate(
                        taskId = effectiveTaskId,
                        correlationId = effectiveTaskId,
                        progressDetail = "operator_reopen_closure_requested"
                    )
                    OperatorActionExecutionOutcome(
                        executionStatus = OperatorActionResultPayload.EXECUTION_PARTIAL,
                        details = mapOf("task_id" to effectiveTaskId)
                    )
                }
            }
        }
    }

    private fun sendOperatorActionResult(result: OperatorActionResultPayload) {
        val envelope = AipMessage(
            type = MsgType.OPERATOR_ACTION_RESULT,
            payload = result,
            correlation_id = result.task_id,
            device_id = localDeviceId,
            trace_id = result.trace_id,
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = buildIdempotencyKey(
                taskId = result.task_id?.takeIf { it.isNotBlank() } ?: result.action_id,
                type = MsgType.OPERATOR_ACTION_RESULT,
                traceId = result.trace_id?.ifBlank { null } ?: result.action_id
            )
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "operator_action_result_sent",
                "action_id" to result.action_id,
                "action_kind" to result.action_kind,
                "phase" to result.phase,
                "decision_status" to result.decision_status,
                "execution_status" to result.execution_status,
                "rollback_status" to result.rollback_status,
                "sent" to sent
            )
        )
    }

    /**
     * Checks whether all required model files are present and, if any are missing or
     * corrupted, enqueues their downloads via [ModelDownloader].
     *
     * Download URLs are configured in [com.ufo.galaxy.model.ModelAssetManager] companion
     * object constants. When a URL is empty the download is silently skipped and the
     * caller must install the file manually before inference is possible.
     *
     * This method suspends until all enqueued downloads finish. It can also be called
     * directly from UI code to let the user trigger a re-download on demand.
     */
    suspend fun ensureModels() {
        val assetManager = UFOGalaxyApplication.modelAssetManager
        val downloader = UFOGalaxyApplication.modelDownloader

        // Re-verify file system state before deciding what to download.
        assetManager.verifyAll()

        val specs = assetManager.downloadSpecsForMissing()
        if (specs.isEmpty()) {
            Log.d(TAG, "ensureModels: all model files present, no download needed")
            return
        }

        Log.i(TAG, "ensureModels: downloading ${specs.size} missing/corrupted model file(s)")
        for (spec in specs) {
            var lastLoggedPct = -1
            val ok = downloader.downloadSync(spec) { status ->
                when (status) {
                    is ModelDownloader.DownloadStatus.Progress -> {
                        // Log at most once per 10% to avoid flooding logcat.
                        if (status.totalBytes > 0) {
                            val pct = (status.bytesDownloaded * 10 / status.totalBytes).toInt()
                            if (pct != lastLoggedPct) {
                                lastLoggedPct = pct
                                Log.d(TAG, "Downloading ${spec.modelId}: ${pct * 10}%")
                            }
                        }
                    }
                    is ModelDownloader.DownloadStatus.Success ->
                        Log.i(TAG, "Downloaded ${spec.modelId} → ${status.file.absolutePath}")
                    is ModelDownloader.DownloadStatus.Failure ->
                        Log.e(TAG, "Download failed for ${spec.modelId}: ${status.error}")
                }
            }
            if (!ok) {
                Log.e(TAG, "ensureModels: failed to download ${spec.modelId}; inference may be unavailable")
            }
        }
        // Refresh status after downloads complete.
        assetManager.verifyAll()
    }

    /**
     * Pre-warms the MobileVLM and SeeClick inference servers before full model loading.
     * Sends a lightweight health ping to each server to establish a warm TCP connection
     * and surface any startup failures early.
     */
    private fun prewarmServices() {
        Log.i(TAG, "预热推理服务...")
        val plannerReady = UFOGalaxyApplication.plannerService.prewarm()
        val groundingReady = UFOGalaxyApplication.groundingService.prewarm()
        Log.i(TAG, "预热完成: planner=$plannerReady grounding=$groundingReady")
    }

    /**
     * Starts the local inference runtime via [LocalInferenceRuntimeManager] and updates
     * [GalaxyWebSocketClient] capabilities and metadata to reflect the **actual** runtime
     * readiness rather than an unconditional static list.
     *
     * This is a `suspend` function and must be called from a coroutine (e.g., the
     * `serviceScope.launch` block in [onStartCommand]).
     *
     * Capability contract:
     * - `local_model_inference` is included in high-level capabilities **only** when the
     *   runtime reports [RuntimeStartResult.Success] or [RuntimeStartResult.Degraded] (i.e.
     *   at least one inference component is operational, checked via [RuntimeStartResult.isUsable]).
     * - When the runtime reports [RuntimeStartResult.Failure] (both components down) or file-
     *   system checks indicate missing/corrupted models, `local_model_inference` is **omitted**
     *   so the gateway can accurately determine local AI availability.
     * - `inference_runtime_state` in the device metadata carries the canonical string label
     *   (`"running"` | `"degraded"` | `"unavailable"`) for observability and debugging.
     * - `local_inference_ready` is `true` only when both planner and grounding are healthy.
     *
     * Model asset tracking is delegated to [ModelAssetManager]; [LocalInferenceRuntimeManager]
     * is the sole authority for runtime lifecycle state.
     */
    private suspend fun loadModels() {
        Log.i(TAG, "开始加载本地模型 (via LocalInferenceRuntimeManager)...")
        val runtimeManager = UFOGalaxyApplication.localInferenceRuntimeManager
        val startResult = runtimeManager.start()
        crossRepoRegressionHooks.recordLocalRuntimeBehavior(startResult)
        emitCrossRepoRegressionSnapshot("cross_repo_local_runtime_behavior")

        val plannerLoaded = UFOGalaxyApplication.plannerService.isModelLoaded()
        val groundingLoaded = UFOGalaxyApplication.groundingService.isModelLoaded()
        Log.i(
            TAG,
            "模型加载完成: planner=$plannerLoaded grounding=$groundingLoaded " +
                "runtimeResult=${startResult::class.simpleName}"
        )

        val assetManager = UFOGalaxyApplication.modelAssetManager
        if (plannerLoaded) assetManager.markLoaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        else assetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        if (groundingLoaded) assetManager.markLoaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)
        else assetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)

        // Low-level model capabilities are advertised only for components that are actually loaded.
        val lowLevelCaps = mutableListOf<String>()
        if (plannerLoaded) lowLevelCaps.add("local_planning")
        if (groundingLoaded) lowLevelCaps.add("local_grounding")
        webSocketClient.setModelCapabilities(lowLevelCaps)

        // Persist actual model state.
        // `local_model_enabled` reflects whether the inference servers have loaded the models.
        // `model_ready` (file-level readiness) is set by ReadinessChecker.checkAll() called
        // after this method returns; we do not set it here to avoid a two-sources-of-truth conflict.
        val localModelEnabled = plannerLoaded && groundingLoaded
        val settings = UFOGalaxyApplication.appSettings
        settings.localModelEnabled = localModelEnabled

        // Honest high-level capability list: `local_model_inference` is included only when
        // the runtime manager confirms at least one inference component is operational.
        // A Failure result (both components down) must not claim local inference capability.
        val highLevelCaps = mutableListOf(
            "autonomous_goal_execution",
            "local_task_planning",
            "local_ui_reasoning",
            "cross_device_coordination"
        )
        if (startResult.isUsable) {
            highLevelCaps.add("local_model_inference")
        }
        webSocketClient.setHighLevelCapabilities(highLevelCaps)

        // Derive canonical local-intelligence labels for observability and gate consumers.
        val inferenceRuntimeState = when {
            startResult.isSuccess -> "running"
            startResult.isUsable -> "degraded"
            else -> "unavailable"
        }
        val localIntelligenceStatus =
            com.ufo.galaxy.runtime.LocalIntelligenceCapabilityStatus.from(startResult).wireValue
        val modeState = settings.authoritativeModeState(
            wsConnected = webSocketClient.isConnected(),
            capabilityDegraded = managerStateDegraded()
        )
        val dispatchReadiness = UFOGalaxyApplication.runtimeController.currentDispatchReadiness()
        val participationSnapshot =
            UFOGalaxyApplication.runtimeController.evaluateAuthoritativeParticipationSnapshot(
                readinessSatisfied = modeState.crossDeviceEligibility,
                distributedRuntimeActivity = UFOGalaxyApplication.runtimeController.activeTaskId != null,
                capabilityVisible = hasVisibleCrossDeviceCapability(
                    crossDeviceEligibility = modeState.crossDeviceEligibility,
                    sessionIsAttached = dispatchReadiness.sessionIsAttached
                )
            )
        webSocketClient.setDeviceMetadata(
            settings.toMetadataMap() + mapOf(
                "inference_runtime_state" to inferenceRuntimeState,
                "local_intelligence_status" to localIntelligenceStatus,
                // local_inference_ready requires full local runtime readiness (both components).
                "local_inference_ready" to localModelEnabled,
                // local_inference_available is a broader availability gate input:
                // true for Success/Degraded, false for Failure.
                "local_inference_available" to startResult.isUsable,
                "authoritative_participation_state" to participationSnapshot.state.wireValue,
                "authoritative_participation_transition_sequence" to
                    participationSnapshot.transitionSequence,
                "authoritative_participation_transition_trigger" to
                    participationSnapshot.lastTransitionTrigger,
                "authoritative_participation_transition_history" to
                    participationSnapshot.transitionHistoryWire,
                "authoritative_participation_connected" to participationSnapshot.connected,
                "authoritative_participation_attached" to participationSnapshot.attached,
                "authoritative_participation_dispatch_eligible" to
                    participationSnapshot.canDispatch,
                "authoritative_participation_distributed_participant" to
                    participationSnapshot.distributedParticipant,
                // Keep mode-gate projections explicit in capability metadata.
            ) + modeState.toMetadataMap()
        )
        Log.i(
            TAG,
            "已更新模型能力: lowLevel=$lowLevelCaps highLevel=$highLevelCaps " +
                "localModelEnabled=$localModelEnabled modelReady=${settings.modelReady} " +
                "inferenceRuntimeState=$inferenceRuntimeState localIntelligenceStatus=$localIntelligenceStatus"
        )
    }

    /**
     * Stops both local inference runtimes via [LocalInferenceRuntimeManager] and releases
     * device memory. The manager transitions to [ManagerState.Stopped] so that capability
     * reporting on subsequent reconnects starts from a clean, honest baseline.
     */
    private fun unloadModels() {
        UFOGalaxyApplication.localInferenceRuntimeManager.stop()
        UFOGalaxyApplication.modelAssetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_MOBILEVLM)
        UFOGalaxyApplication.modelAssetManager.markUnloaded(com.ufo.galaxy.model.ModelAssetManager.MODEL_ID_SEECLICK)
        Log.i(TAG, "本地模型已卸载 (LocalInferenceRuntimeManager stopped)")
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, UFOGalaxyApplication.CHANNEL_SERVICE)
            .setContentTitle("UFO Galaxy")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = webSocketClient.isConnected()

    /**
     * Persists a task result to the OpenClawd memory store via [OpenClawdMemoryBackflow].
     *
     * This is a best-effort, fire-and-forget operation: errors are logged but never
     * re-thrown. All cross-device task results (task_assign, goal_execution,
     * parallel_subtask) are stored with [routeMode] = "cross_device".
     *
     * The [route_mode] field lets the gateway and memory indexer distinguish between
     * locally-executed tasks and tasks dispatched via the AIP v3 cross-device pipeline.
     */
    /**
     * PR-8Android — Returns `true` when the local inference runtime manager is in a
     * degraded or failed state, used to derive [LocalExecutionModeGate.ExecutionModeState].
     *
     * Called from [deviceExecutionEventSink] and [sendDeviceStateSnapshot] to populate
     * `execution_mode_state` without re-reading the full manager state.
     */
    private fun deriveMeshDirectRuntimeSnapshot(
        meshId: String? = lastMeshTopologyMeshId
    ): AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot =
        AndroidMeshDirectRuntimeContract.derive(
            AndroidMeshDirectRuntimeContract.DerivationInput(
                meshId = meshId,
                localDeviceId = localDeviceId,
                wsConnected = webSocketClient.isConnected(),
                topologyMeshId = lastMeshTopologyMeshId,
                topologyNodes = lastMeshTopologyNodes,
                peerAnnouncements = lastPeerAnnouncements,
                peerCapabilities = lastPeerCapabilities
            )
        )

    private fun updateMeshDirectRuntimeSnapshot(
        snapshot: AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot
    ): AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot {
        lastMeshDirectRuntimeSnapshot = snapshot
        return snapshot
    }

    private fun snapshotViewOfMeshDirectRuntime(
        meshId: String? = lastMeshTopologyMeshId
    ): AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot {
        val derived = deriveMeshDirectRuntimeSnapshot(meshId)
        val remembered = lastMeshDirectRuntimeSnapshot
        return if (
            remembered.meshId == derived.meshId &&
            remembered.lastAttemptStage != null
        ) {
            derived.copy(
                state = when {
                    remembered.lastAttemptSucceeded == false ->
                        AndroidMeshDirectRuntimeContract.DirectPathState.FALLBACK
                    remembered.lastAttemptSucceeded == true &&
                        remembered.lastAttemptStage != AndroidMeshDirectRuntimeContract.AttemptStage.LEAVE.wireValue ->
                        AndroidMeshDirectRuntimeContract.DirectPathState.ACTIVE
                    else -> derived.state
                },
                route = if (remembered.lastAttemptSucceeded == false) {
                    AndroidMeshDirectRuntimeContract.DirectPathRoute.GATEWAY_FALLBACK
                } else {
                    remembered.route
                },
                reasonCodes = (derived.reasonCodes + remembered.reasonCodes).distinct(),
                lastAttemptStage = remembered.lastAttemptStage,
                lastAttemptSucceeded = remembered.lastAttemptSucceeded
            )
        } else {
            derived
        }
    }

    private fun currentMeshDirectRuntimeSnapshotForEvent(
        payload: DeviceExecutionEventPayload
    ): AndroidMeshDirectRuntimeContract.MeshDirectRuntimeSnapshot? {
        val snapshot = lastMeshDirectRuntimeSnapshot
        return if ((payload.source_component.contains("handleParallelSubtask") ||
                payload.phase == DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION) &&
            (snapshot.meshId != null || snapshot.lastAttemptStage != null)
        ) {
            snapshot
        } else {
            null
        }
    }

    private fun managerStateDegraded(): Boolean {
        return when (UFOGalaxyApplication.localInferenceRuntimeManager.state.value) {
            is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded,
            is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.FailedStartup,
            is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Failed -> true
            else -> false
        }
    }

    private fun localInferenceAvailable(): Boolean {
        return when (UFOGalaxyApplication.localInferenceRuntimeManager.state.value) {
            is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Running,
            is com.ufo.galaxy.runtime.LocalInferenceRuntimeManager.ManagerState.Degraded -> true
            else -> false
        }
    }

    /**
     * Returns true when the local LLM is ready: model weights are loaded and at least one of
     * llama.cpp or NCNN native runtime is available.  Used by both [sendGoalResult] and
     * [sendDeviceStateSnapshot] to populate the [GoalResultPayload.local_llm_ready] and
     * [DeviceStateSnapshotPayload.local_llm_ready] fields from a single authoritative source.
     */
    private fun localLlmReady(): Boolean =
        UFOGalaxyApplication.appSettings.modelReady == true &&
            (NativeInferenceLoader.isLlamaCppAvailable() || NativeInferenceLoader.isNcnnAvailable())

    private fun isDistributedParticipationActivePhase(phase: String): Boolean =
        phase !in setOf(
            DeviceExecutionEventPayload.PHASE_COMPLETED,
            DeviceExecutionEventPayload.PHASE_FAILED,
            DeviceExecutionEventPayload.PHASE_CANCELLED,
            DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED
        )

    private fun hasVisibleCrossDeviceCapability(
        crossDeviceEligibility: Boolean,
        sessionIsAttached: Boolean
    ): Boolean = crossDeviceEligibility || sessionIsAttached

    private fun updateAcceptanceEvidenceFromResult(
        result: GoalResultPayload,
        deliveryDisposition: ResultDeliveryDisposition
    ) {
        if (result.local_mode_active == true || result.cross_device_eligibility == true) {
            delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
                DelegatedRuntimeAcceptanceDimension.READINESS_PREREQUISITE
            )
        }

        if (!result.unified_lifecycle_phase.isNullOrBlank()) {
            delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
                DelegatedRuntimeAcceptanceDimension.TRUTH_OWNERSHIP_ALIGNMENT_EVIDENCE
            )
        }

        if (result.result_returned == true && result.completion_signaled == true) {
            if (deliveryDisposition == ResultDeliveryDisposition.SEND_FAILED) {
                delegatedRuntimeAcceptanceEvaluator.markDimensionGap(
                    DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE,
                    "goal_execution_result_send_failed"
                )
            } else {
                delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
                    DelegatedRuntimeAcceptanceDimension.RESULT_CONVERGENCE_EVIDENCE
                )
            }
        }

        delegatedRuntimeAcceptanceEvaluator.markDimensionEvidenced(
            DelegatedRuntimeAcceptanceDimension.COMPAT_LEGACY_BLOCKING_EVIDENCE
        )
    }

    private fun storeMemoryEntry(
        taskId: String,
        goal: String,
        status: String,
        summary: String,
        steps: List<String> = emptyList(),
        routeMode: String = "cross_device"
    ) {
        try {
            val restBaseUrl = UFOGalaxyApplication.appSettings.restBaseUrl
            val backflow = OpenClawdMemoryBackflow(restBaseUrl = restBaseUrl)
            val entry = MemoryEntry(
                task_id = taskId,
                goal = goal,
                status = status,
                summary = summary,
                steps = steps,
                route_mode = routeMode
            )
            val ok = backflow.store(entry)
            Log.d(TAG, "[MEMORY] storeMemoryEntry task_id=$taskId status=$status ok=$ok")
        } catch (e: Exception) {
            Log.w(TAG, "[MEMORY] storeMemoryEntry failed task_id=$taskId: ${e.message}")
        }
    }
}
