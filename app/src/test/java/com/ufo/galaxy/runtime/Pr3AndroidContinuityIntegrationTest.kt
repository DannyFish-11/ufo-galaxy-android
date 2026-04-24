package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-3 (Android) — Unit tests for [AndroidContinuityIntegration] and
 * [LocalExecutionContextSnapshot].
 *
 * ## Test matrix
 *
 * ### AndroidContinuityIntegration — constants
 *  - SEMANTIC_FRESH_ATTACH wire value is "fresh_attach"
 *  - SEMANTIC_CONTINUITY_RESUME wire value is "continuity_resume"
 *  - SEMANTIC_REHYDRATE_LOCAL_CONTEXT wire value is "rehydrate_local_context"
 *  - SEMANTIC_REJECT_STALE_IDENTITY wire value is "reject_stale_runtime_identity"
 *  - SEMANTIC_SUPPRESS_DUPLICATE_EMIT wire value is "suppress_duplicate_local_emit"
 *  - All five semantic constants are distinct
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - INTRODUCED_PR is 3
 *  - DESCRIPTION is non-blank
 *
 * ### AttachIntentKind enum
 *  - FRESH_ATTACH wireValue is "fresh_attach"
 *  - CONTINUITY_RESUME wireValue is "continuity_resume"
 *  - PROCESS_RECREATION_REATTACH wireValue is "process_recreation_reattach"
 *  - TRANSPORT_RECONNECT wireValue is "transport_reconnect"
 *  - RECEIVER_PIPELINE_REBIND wireValue is "receiver_pipeline_rebind"
 *  - All five wireValues are distinct
 *
 * ### evaluateAttachIntent — fresh attach
 *  - Returns FreshAttach when no context is available
 *  - Returns FreshAttach when all parameters are null
 *  - FreshAttach.kind is FRESH_ATTACH
 *
 * ### evaluateAttachIntent — process recreation re-attach
 *  - Returns ProcessRecreationReattach when processRecreatedHint is non-null
 *  - ProcessRecreationReattach carries the reattachHint
 *  - ProcessRecreationReattach.localContext is non-null when priorFlowRecord is provided
 *  - ProcessRecreationReattach.localContext is null when priorFlowRecord is null
 *  - ProcessRecreationReattach.kind is PROCESS_RECREATION_REATTACH
 *
 * ### evaluateAttachIntent — transport reconnect
 *  - Returns TransportReconnect when durableSession.sessionContinuityEpoch > 0
 *  - TransportReconnect carries the durableSession
 *  - TransportReconnect.kind is TRANSPORT_RECONNECT
 *  - Epoch = 0 does NOT produce TransportReconnect
 *
 * ### evaluateAttachIntent — continuity resume
 *  - Returns ContinuityResume when priorFlowRecord is non-null and no process-recreation hint
 *  - ContinuityResume.localContext is populated from the prior flow record
 *  - ContinuityResume.kind is CONTINUITY_RESUME
 *  - Process-recreation hint takes precedence over continuity resume
 *
 * ### evaluateAttachIntent — receiver/pipeline rebind
 *  - Returns ReceiverPipelineRebind when activeSession is ATTACHED and no other context
 *  - ReceiverPipelineRebind.activeSessionId matches the session's sessionId
 *  - ReceiverPipelineRebind.kind is RECEIVER_PIPELINE_REBIND
 *  - DETACHING session does NOT produce ReceiverPipelineRebind (FreshAttach instead)
 *
 * ### validateRuntimeIdentity — valid
 *  - Returns Valid when sessionIds match
 *
 * ### validateRuntimeIdentity — stale identity
 *  - Returns StaleIdentity when sessionIds differ
 *  - StaleIdentity.expectedSessionId is the active session's ID
 *  - StaleIdentity.receivedSessionId is the unit's session ID
 *
 * ### validateRuntimeIdentity — no active session
 *  - Returns NoActiveSession when activeSession is null
 *
 * ### suppressDuplicateLocalEmit and markLocalEmitSeen
 *  - suppressDuplicateLocalEmit returns false for unseen signal ID
 *  - suppressDuplicateLocalEmit returns true after markLocalEmitSeen
 *  - Multiple distinct signal IDs can all be marked seen
 *  - clearEmitSeenSet resets the seen-set
 *  - emitSeenCount is 0 initially
 *  - emitSeenCount increments after markLocalEmitSeen
 *  - emitSeenCount resets to 0 after clearEmitSeenSet
 *
 * ### rehydrateLocalContext
 *  - Returns LocalExecutionContextSnapshot from the provided record
 *  - rehydratedAtMs uses the provided nowMs
 *
 * ### LocalExecutionContextSnapshot — fromContinuityRecord factory
 *  - All identity fields propagated correctly
 *  - lastKnownPhase resolved from executionPhase wire value
 *  - Unknown executionPhase wire value resolves to UNKNOWN
 *  - continuityToken and continuationToken are propagated
 *  - stepCount and lastStepAtMs are propagated
 *  - rehydratedAtMs defaults to a reasonable timestamp
 *
 * ### LocalExecutionContextSnapshot — derived helpers
 *  - wasTerminalWhenPersisted is true for COMPLETED phase
 *  - wasTerminalWhenPersisted is true for FAILED phase
 *  - wasTerminalWhenPersisted is true for REJECTED phase
 *  - wasTerminalWhenPersisted is false for RECEIVED phase
 *  - wasTerminalWhenPersisted is false for ACTIVE_LOOP phase
 *  - hasContinuityToken is true when continuityToken is non-blank
 *  - hasContinuityToken is false when continuityToken is null
 *  - hasContinuityToken is false when continuityToken is blank
 *
 * ### LocalExecutionContextSnapshot — toReattachMetadataMap
 *  - All mandatory keys are present
 *  - Optional keys absent when null (continuityToken, continuationToken, lastStepAtMs)
 *  - Optional keys present when non-null
 *  - Values match the snapshot fields
 *
 * ### LocalExecutionContextSnapshot — wire key constants
 *  - All KEY_* constants have expected stable string values
 *  - ALL_MANDATORY_KEYS contains exactly the expected set
 */
class Pr3AndroidContinuityIntegrationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var integration: AndroidContinuityIntegration

    @Before
    fun setUp() {
        integration = AndroidContinuityIntegration()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun attachedSession(
        sessionId: String = "session-abc",
        state: AttachedRuntimeSession.State = AttachedRuntimeSession.State.ATTACHED
    ) = AttachedRuntimeSession(
        sessionId = sessionId,
        hostId = "host-1",
        deviceId = "device-1",
        state = state
    )

    private fun durableSession(
        durableSessionId: String = "durable-uuid",
        epoch: Int = 0
    ) = DurableSessionContinuityRecord(
        durableSessionId = durableSessionId,
        sessionContinuityEpoch = epoch,
        activationEpochMs = 1_000L,
        activationSource = "user_activation"
    )

    private fun flowContinuityRecord(
        flowId: String = "flow-xyz",
        flowLineageId: String = "lineage-lmn",
        unitId: String = "unit-1",
        taskId: String = "task-1",
        traceId: String = "trace-1",
        durableSessionId: String = "durable-uuid",
        attachedSessionId: String = "session-abc",
        executionPhase: String = AndroidFlowExecutionPhase.ACTIVE_LOOP.wireValue,
        continuityToken: String? = "ct-opaque",
        continuationToken: String? = null,
        stepCount: Int = 3,
        lastStepAtMs: Long? = 5_000L
    ) = DelegatedFlowContinuityRecord(
        flowId = flowId,
        flowLineageId = flowLineageId,
        unitId = unitId,
        taskId = taskId,
        traceId = traceId,
        durableSessionId = durableSessionId,
        attachedSessionId = attachedSessionId,
        executionPhase = executionPhase,
        continuityToken = continuityToken,
        continuationToken = continuationToken,
        activatedAtMs = 1_000L,
        executionStartedAtMs = 2_000L,
        stepCount = stepCount,
        lastStepAtMs = lastStepAtMs,
        savedAtMs = 9_000L
    )

    private fun processRecreatedHint(
        priorDurableSessionId: String = "durable-uuid",
        deviceId: String = "device-1"
    ) = ProcessRecreatedReattachHint(
        priorDurableSessionId = priorDurableSessionId,
        deviceId = deviceId
    )

    // ── Constants tests ───────────────────────────────────────────────────────

    @Test
    fun `SEMANTIC_FRESH_ATTACH wire value is fresh_attach`() {
        assertEquals("fresh_attach", AndroidContinuityIntegration.SEMANTIC_FRESH_ATTACH)
    }

    @Test
    fun `SEMANTIC_CONTINUITY_RESUME wire value is continuity_resume`() {
        assertEquals("continuity_resume", AndroidContinuityIntegration.SEMANTIC_CONTINUITY_RESUME)
    }

    @Test
    fun `SEMANTIC_REHYDRATE_LOCAL_CONTEXT wire value is rehydrate_local_context`() {
        assertEquals(
            "rehydrate_local_context",
            AndroidContinuityIntegration.SEMANTIC_REHYDRATE_LOCAL_CONTEXT
        )
    }

    @Test
    fun `SEMANTIC_REJECT_STALE_IDENTITY wire value is reject_stale_runtime_identity`() {
        assertEquals(
            "reject_stale_runtime_identity",
            AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY
        )
    }

    @Test
    fun `SEMANTIC_SUPPRESS_DUPLICATE_EMIT wire value is suppress_duplicate_local_emit`() {
        assertEquals(
            "suppress_duplicate_local_emit",
            AndroidContinuityIntegration.SEMANTIC_SUPPRESS_DUPLICATE_EMIT
        )
    }

    @Test
    fun `all five semantic constants are distinct`() {
        val semantics = setOf(
            AndroidContinuityIntegration.SEMANTIC_FRESH_ATTACH,
            AndroidContinuityIntegration.SEMANTIC_CONTINUITY_RESUME,
            AndroidContinuityIntegration.SEMANTIC_REHYDRATE_LOCAL_CONTEXT,
            AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
            AndroidContinuityIntegration.SEMANTIC_SUPPRESS_DUPLICATE_EMIT
        )
        assertEquals(5, semantics.size)
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals("DelegatedRuntimeReceiver", AndroidContinuityIntegration.INTEGRATION_RECEIVER)
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals("DelegatedRuntimeUnit", AndroidContinuityIntegration.INTEGRATION_UNIT)
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals(
            "DelegatedActivationRecord",
            AndroidContinuityIntegration.INTEGRATION_ACTIVATION_RECORD
        )
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals(
            "AutonomousExecutionPipeline",
            AndroidContinuityIntegration.INTEGRATION_PIPELINE
        )
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals("LoopController", AndroidContinuityIntegration.INTEGRATION_LOOP_CONTROLLER)
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals(
            "DelegatedTakeoverExecutor",
            AndroidContinuityIntegration.INTEGRATION_TAKEOVER_EXECUTOR
        )
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals(
            "LocalCollaborationAgent",
            AndroidContinuityIntegration.INTEGRATION_COLLABORATION_AGENT
        )
    }

    @Test
    fun `INTRODUCED_PR is 3`() {
        assertEquals(3, AndroidContinuityIntegration.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidContinuityIntegration.DESCRIPTION.isNotBlank())
    }

    // ── AttachIntentKind enum tests ────────────────────────────────────────────

    @Test
    fun `FRESH_ATTACH wireValue is fresh_attach`() {
        assertEquals(
            "fresh_attach",
            AndroidContinuityIntegration.AttachIntentKind.FRESH_ATTACH.wireValue
        )
    }

    @Test
    fun `CONTINUITY_RESUME wireValue is continuity_resume`() {
        assertEquals(
            "continuity_resume",
            AndroidContinuityIntegration.AttachIntentKind.CONTINUITY_RESUME.wireValue
        )
    }

    @Test
    fun `PROCESS_RECREATION_REATTACH wireValue is process_recreation_reattach`() {
        assertEquals(
            "process_recreation_reattach",
            AndroidContinuityIntegration.AttachIntentKind.PROCESS_RECREATION_REATTACH.wireValue
        )
    }

    @Test
    fun `TRANSPORT_RECONNECT wireValue is transport_reconnect`() {
        assertEquals(
            "transport_reconnect",
            AndroidContinuityIntegration.AttachIntentKind.TRANSPORT_RECONNECT.wireValue
        )
    }

    @Test
    fun `RECEIVER_PIPELINE_REBIND wireValue is receiver_pipeline_rebind`() {
        assertEquals(
            "receiver_pipeline_rebind",
            AndroidContinuityIntegration.AttachIntentKind.RECEIVER_PIPELINE_REBIND.wireValue
        )
    }

    @Test
    fun `all five AttachIntentKind wireValues are distinct`() {
        val values = AndroidContinuityIntegration.AttachIntentKind.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    // ── evaluateAttachIntent — fresh attach ────────────────────────────────────

    @Test
    fun `evaluateAttachIntent returns FreshAttach when no context is available`() {
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertSame(AndroidContinuityIntegration.ContinuityDecision.FreshAttach, result)
    }

    @Test
    fun `evaluateAttachIntent returns FreshAttach when all parameters are null`() {
        val result = integration.evaluateAttachIntent(null, null, null, null)
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.FRESH_ATTACH,
            result.kind
        )
    }

    @Test
    fun `FreshAttach kind is FRESH_ATTACH`() {
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.FRESH_ATTACH,
            AndroidContinuityIntegration.ContinuityDecision.FreshAttach.kind
        )
    }

    // ── evaluateAttachIntent — process recreation re-attach ────────────────────

    @Test
    fun `evaluateAttachIntent returns ProcessRecreationReattach when processRecreatedHint is non-null`() {
        val hint = processRecreatedHint()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = hint
        )
        assertIs<AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach>(result)
    }

    @Test
    fun `ProcessRecreationReattach carries the reattachHint`() {
        val hint = processRecreatedHint(priorDurableSessionId = "prior-123")
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = hint
        ) as AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach
        assertEquals("prior-123", result.reattachHint.priorDurableSessionId)
    }

    @Test
    fun `ProcessRecreationReattach localContext is non-null when priorFlowRecord is provided`() {
        val hint = processRecreatedHint()
        val record = flowContinuityRecord()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = record,
            processRecreatedHint = hint
        ) as AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach
        assertNotNull(result.localContext)
    }

    @Test
    fun `ProcessRecreationReattach localContext is null when priorFlowRecord is null`() {
        val hint = processRecreatedHint()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = hint
        ) as AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach
        assertNull(result.localContext)
    }

    @Test
    fun `ProcessRecreationReattach kind is PROCESS_RECREATION_REATTACH`() {
        val hint = processRecreatedHint()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = hint
        )
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.PROCESS_RECREATION_REATTACH,
            result.kind
        )
    }

    // ── evaluateAttachIntent — transport reconnect ─────────────────────────────

    @Test
    fun `evaluateAttachIntent returns TransportReconnect when epoch is 1`() {
        val durable = durableSession(epoch = 1)
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = durable,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertIs<AndroidContinuityIntegration.ContinuityDecision.TransportReconnect>(result)
    }

    @Test
    fun `TransportReconnect carries the durableSession`() {
        val durable = durableSession(durableSessionId = "ds-xyz", epoch = 2)
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = durable,
            priorFlowRecord = null,
            processRecreatedHint = null
        ) as AndroidContinuityIntegration.ContinuityDecision.TransportReconnect
        assertEquals("ds-xyz", result.durableSession.durableSessionId)
        assertEquals(2, result.durableSession.sessionContinuityEpoch)
    }

    @Test
    fun `TransportReconnect kind is TRANSPORT_RECONNECT`() {
        val durable = durableSession(epoch = 3)
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = durable,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.TRANSPORT_RECONNECT,
            result.kind
        )
    }

    @Test
    fun `epoch 0 does NOT produce TransportReconnect`() {
        val durable = durableSession(epoch = 0)
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = durable,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertFalse(result is AndroidContinuityIntegration.ContinuityDecision.TransportReconnect)
    }

    // ── evaluateAttachIntent — continuity resume ───────────────────────────────

    @Test
    fun `evaluateAttachIntent returns ContinuityResume when priorFlowRecord is non-null`() {
        val record = flowContinuityRecord()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = record,
            processRecreatedHint = null
        )
        assertIs<AndroidContinuityIntegration.ContinuityDecision.ContinuityResume>(result)
    }

    @Test
    fun `ContinuityResume localContext is populated from prior flow record`() {
        val record = flowContinuityRecord(flowId = "flow-rehydrated", taskId = "task-99")
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = record,
            processRecreatedHint = null
        ) as AndroidContinuityIntegration.ContinuityDecision.ContinuityResume
        assertEquals("flow-rehydrated", result.localContext.flowId)
        assertEquals("task-99", result.localContext.taskId)
    }

    @Test
    fun `ContinuityResume kind is CONTINUITY_RESUME`() {
        val record = flowContinuityRecord()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = record,
            processRecreatedHint = null
        )
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.CONTINUITY_RESUME,
            result.kind
        )
    }

    @Test
    fun `process recreation hint takes precedence over continuity resume`() {
        val record = flowContinuityRecord()
        val hint = processRecreatedHint()
        val result = integration.evaluateAttachIntent(
            activeSession = null,
            durableSession = null,
            priorFlowRecord = record,
            processRecreatedHint = hint
        )
        assertIs<AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach>(result)
    }

    // ── evaluateAttachIntent — receiver/pipeline rebind ────────────────────────

    @Test
    fun `evaluateAttachIntent returns ReceiverPipelineRebind when session is ATTACHED and no other context`() {
        val session = attachedSession(sessionId = "session-rebind")
        val result = integration.evaluateAttachIntent(
            activeSession = session,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertIs<AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind>(result)
    }

    @Test
    fun `ReceiverPipelineRebind activeSessionId matches session sessionId`() {
        val session = attachedSession(sessionId = "session-rebind")
        val result = integration.evaluateAttachIntent(
            activeSession = session,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = null
        ) as AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind
        assertEquals("session-rebind", result.activeSessionId)
    }

    @Test
    fun `ReceiverPipelineRebind kind is RECEIVER_PIPELINE_REBIND`() {
        val session = attachedSession()
        val result = integration.evaluateAttachIntent(
            activeSession = session,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertEquals(
            AndroidContinuityIntegration.AttachIntentKind.RECEIVER_PIPELINE_REBIND,
            result.kind
        )
    }

    @Test
    fun `DETACHING session produces FreshAttach not ReceiverPipelineRebind`() {
        val session = attachedSession(state = AttachedRuntimeSession.State.DETACHING)
        val result = integration.evaluateAttachIntent(
            activeSession = session,
            durableSession = null,
            priorFlowRecord = null,
            processRecreatedHint = null
        )
        assertFalse(result is AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind)
        assertSame(AndroidContinuityIntegration.ContinuityDecision.FreshAttach, result)
    }

    // ── validateRuntimeIdentity ────────────────────────────────────────────────

    @Test
    fun `validateRuntimeIdentity returns Valid when sessionIds match`() {
        val session = attachedSession(sessionId = "session-abc")
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-abc",
            activeSession = session
        )
        assertSame(AndroidContinuityIntegration.IdentityValidationResult.Valid, result)
    }

    @Test
    fun `validateRuntimeIdentity returns StaleIdentity when sessionIds differ`() {
        val session = attachedSession(sessionId = "session-current")
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-stale",
            activeSession = session
        )
        assertIs<AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity>(result)
    }

    @Test
    fun `StaleIdentity carries expectedSessionId and receivedSessionId`() {
        val session = attachedSession(sessionId = "session-current")
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-stale",
            activeSession = session
        ) as AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity
        assertEquals("session-current", result.expectedSessionId)
        assertEquals("session-stale", result.receivedSessionId)
    }

    @Test
    fun `validateRuntimeIdentity returns NoActiveSession when activeSession is null`() {
        val result = integration.validateRuntimeIdentity(
            unitAttachedSessionId = "any-session",
            activeSession = null
        )
        assertSame(AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession, result)
    }

    // ── suppressDuplicateLocalEmit / markLocalEmitSeen ─────────────────────────

    @Test
    fun `suppressDuplicateLocalEmit returns false for unseen signal ID`() {
        assertFalse(integration.suppressDuplicateLocalEmit("signal-new"))
    }

    @Test
    fun `suppressDuplicateLocalEmit returns true after markLocalEmitSeen`() {
        integration.markLocalEmitSeen("signal-1")
        assertTrue(integration.suppressDuplicateLocalEmit("signal-1"))
    }

    @Test
    fun `multiple distinct signal IDs can all be marked seen`() {
        integration.markLocalEmitSeen("sig-a")
        integration.markLocalEmitSeen("sig-b")
        integration.markLocalEmitSeen("sig-c")
        assertTrue(integration.suppressDuplicateLocalEmit("sig-a"))
        assertTrue(integration.suppressDuplicateLocalEmit("sig-b"))
        assertTrue(integration.suppressDuplicateLocalEmit("sig-c"))
        assertFalse(integration.suppressDuplicateLocalEmit("sig-d"))
    }

    @Test
    fun `clearEmitSeenSet resets the seen-set`() {
        integration.markLocalEmitSeen("signal-to-clear")
        assertTrue(integration.suppressDuplicateLocalEmit("signal-to-clear"))
        integration.clearEmitSeenSet()
        assertFalse(integration.suppressDuplicateLocalEmit("signal-to-clear"))
    }

    @Test
    fun `emitSeenCount is 0 initially`() {
        assertEquals(0, integration.emitSeenCount)
    }

    @Test
    fun `emitSeenCount increments after markLocalEmitSeen`() {
        integration.markLocalEmitSeen("sig-1")
        integration.markLocalEmitSeen("sig-2")
        assertEquals(2, integration.emitSeenCount)
    }

    @Test
    fun `emitSeenCount resets to 0 after clearEmitSeenSet`() {
        integration.markLocalEmitSeen("sig-1")
        integration.clearEmitSeenSet()
        assertEquals(0, integration.emitSeenCount)
    }

    // ── rehydrateLocalContext ──────────────────────────────────────────────────

    @Test
    fun `rehydrateLocalContext returns LocalExecutionContextSnapshot from the record`() {
        val record = flowContinuityRecord(flowId = "flow-rh", taskId = "task-rh")
        val snapshot = integration.rehydrateLocalContext(record = record, nowMs = 12_000L)
        assertEquals("flow-rh", snapshot.flowId)
        assertEquals("task-rh", snapshot.taskId)
    }

    @Test
    fun `rehydrateLocalContext uses the provided nowMs as rehydratedAtMs`() {
        val record = flowContinuityRecord()
        val snapshot = integration.rehydrateLocalContext(record = record, nowMs = 99_999L)
        assertEquals(99_999L, snapshot.rehydratedAtMs)
    }

    // ── LocalExecutionContextSnapshot — fromContinuityRecord factory ───────────

    @Test
    fun `fromContinuityRecord propagates all identity fields`() {
        val record = flowContinuityRecord(
            flowId = "f1",
            flowLineageId = "l1",
            unitId = "u1",
            taskId = "t1",
            traceId = "tr1",
            durableSessionId = "ds1",
            attachedSessionId = "as1"
        )
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertEquals("f1", snapshot.flowId)
        assertEquals("l1", snapshot.flowLineageId)
        assertEquals("u1", snapshot.unitId)
        assertEquals("t1", snapshot.taskId)
        assertEquals("tr1", snapshot.traceId)
        assertEquals("ds1", snapshot.durableSessionId)
        assertEquals("as1", snapshot.attachedSessionId)
    }

    @Test
    fun `fromContinuityRecord resolves lastKnownPhase from executionPhase wire value`() {
        val record = flowContinuityRecord(
            executionPhase = AndroidFlowExecutionPhase.ACTIVE_TAKEOVER.wireValue
        )
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER, snapshot.lastKnownPhase)
    }

    @Test
    fun `fromContinuityRecord unknown executionPhase wire value resolves to UNKNOWN`() {
        val record = flowContinuityRecord(executionPhase = "future_unknown_phase")
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertEquals(AndroidFlowExecutionPhase.UNKNOWN, snapshot.lastKnownPhase)
    }

    @Test
    fun `fromContinuityRecord propagates continuityToken and continuationToken`() {
        val record = flowContinuityRecord(
            continuityToken = "ct-abc",
            continuationToken = "ctn-xyz"
        )
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertEquals("ct-abc", snapshot.continuityToken)
        assertEquals("ctn-xyz", snapshot.continuationToken)
    }

    @Test
    fun `fromContinuityRecord propagates null continuityToken`() {
        val record = flowContinuityRecord(continuityToken = null)
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertNull(snapshot.continuityToken)
    }

    @Test
    fun `fromContinuityRecord propagates stepCount and lastStepAtMs`() {
        val record = flowContinuityRecord(stepCount = 7, lastStepAtMs = 8_000L)
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record)
        assertEquals(7, snapshot.stepCount)
        assertEquals(8_000L, snapshot.lastStepAtMs)
    }

    @Test
    fun `fromContinuityRecord rehydratedAtMs defaults to a reasonable timestamp`() {
        val before = System.currentTimeMillis()
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(flowContinuityRecord())
        val after = System.currentTimeMillis()
        assertTrue(snapshot.rehydratedAtMs in before..after)
    }

    // ── LocalExecutionContextSnapshot — derived helpers ────────────────────────

    @Test
    fun `wasTerminalWhenPersisted is true for COMPLETED phase`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(executionPhase = AndroidFlowExecutionPhase.COMPLETED.wireValue)
        )
        assertTrue(snapshot.wasTerminalWhenPersisted)
    }

    @Test
    fun `wasTerminalWhenPersisted is true for FAILED phase`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(executionPhase = AndroidFlowExecutionPhase.FAILED.wireValue)
        )
        assertTrue(snapshot.wasTerminalWhenPersisted)
    }

    @Test
    fun `wasTerminalWhenPersisted is true for REJECTED phase`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(executionPhase = AndroidFlowExecutionPhase.REJECTED.wireValue)
        )
        assertTrue(snapshot.wasTerminalWhenPersisted)
    }

    @Test
    fun `wasTerminalWhenPersisted is false for RECEIVED phase`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(executionPhase = AndroidFlowExecutionPhase.RECEIVED.wireValue)
        )
        assertFalse(snapshot.wasTerminalWhenPersisted)
    }

    @Test
    fun `wasTerminalWhenPersisted is false for ACTIVE_LOOP phase`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(executionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP.wireValue)
        )
        assertFalse(snapshot.wasTerminalWhenPersisted)
    }

    @Test
    fun `hasContinuityToken is true when continuityToken is non-blank`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(continuityToken = "ct-opaque")
        )
        assertTrue(snapshot.hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken is false when continuityToken is null`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(continuityToken = null)
        )
        assertFalse(snapshot.hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken is false when continuityToken is blank`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(continuityToken = "   ")
        )
        assertFalse(snapshot.hasContinuityToken)
    }

    // ── LocalExecutionContextSnapshot — toReattachMetadataMap ─────────────────

    @Test
    fun `toReattachMetadataMap contains all mandatory keys`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(continuityToken = null, continuationToken = null, lastStepAtMs = null)
        )
        val map = snapshot.toReattachMetadataMap()
        for (key in LocalExecutionContextSnapshot.ALL_MANDATORY_KEYS) {
            assertTrue("Missing key: $key", map.containsKey(key))
        }
    }

    @Test
    fun `toReattachMetadataMap optional keys absent when null`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(continuityToken = null, continuationToken = null, lastStepAtMs = null)
        )
        val map = snapshot.toReattachMetadataMap()
        assertFalse(map.containsKey(LocalExecutionContextSnapshot.KEY_CONTINUITY_TOKEN))
        assertFalse(map.containsKey(LocalExecutionContextSnapshot.KEY_CONTINUATION_TOKEN))
        assertFalse(map.containsKey(LocalExecutionContextSnapshot.KEY_LAST_STEP_AT_MS))
    }

    @Test
    fun `toReattachMetadataMap optional keys present when non-null`() {
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(
            flowContinuityRecord(
                continuityToken = "ct-abc",
                continuationToken = "ctn-xyz",
                lastStepAtMs = 7_000L
            )
        )
        val map = snapshot.toReattachMetadataMap()
        assertTrue(map.containsKey(LocalExecutionContextSnapshot.KEY_CONTINUITY_TOKEN))
        assertTrue(map.containsKey(LocalExecutionContextSnapshot.KEY_CONTINUATION_TOKEN))
        assertTrue(map.containsKey(LocalExecutionContextSnapshot.KEY_LAST_STEP_AT_MS))
    }

    @Test
    fun `toReattachMetadataMap values match snapshot fields`() {
        val record = flowContinuityRecord(
            flowId = "flow-map",
            taskId = "task-map",
            stepCount = 5,
            executionPhase = AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION.wireValue
        )
        val snapshot = LocalExecutionContextSnapshot.fromContinuityRecord(record, rehydratedAtMs = 11_000L)
        val map = snapshot.toReattachMetadataMap()
        assertEquals("flow-map", map[LocalExecutionContextSnapshot.KEY_FLOW_ID])
        assertEquals("task-map", map[LocalExecutionContextSnapshot.KEY_TASK_ID])
        assertEquals(5, map[LocalExecutionContextSnapshot.KEY_STEP_COUNT])
        assertEquals(
            AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION.wireValue,
            map[LocalExecutionContextSnapshot.KEY_LAST_KNOWN_PHASE]
        )
        assertEquals(11_000L, map[LocalExecutionContextSnapshot.KEY_REHYDRATED_AT_MS])
    }

    // ── LocalExecutionContextSnapshot — wire key constants ─────────────────────

    @Test
    fun `KEY_FLOW_ID is local_ctx_flow_id`() {
        assertEquals("local_ctx_flow_id", LocalExecutionContextSnapshot.KEY_FLOW_ID)
    }

    @Test
    fun `KEY_FLOW_LINEAGE_ID is local_ctx_flow_lineage_id`() {
        assertEquals("local_ctx_flow_lineage_id", LocalExecutionContextSnapshot.KEY_FLOW_LINEAGE_ID)
    }

    @Test
    fun `KEY_UNIT_ID is local_ctx_unit_id`() {
        assertEquals("local_ctx_unit_id", LocalExecutionContextSnapshot.KEY_UNIT_ID)
    }

    @Test
    fun `KEY_TASK_ID is local_ctx_task_id`() {
        assertEquals("local_ctx_task_id", LocalExecutionContextSnapshot.KEY_TASK_ID)
    }

    @Test
    fun `KEY_TRACE_ID is local_ctx_trace_id`() {
        assertEquals("local_ctx_trace_id", LocalExecutionContextSnapshot.KEY_TRACE_ID)
    }

    @Test
    fun `KEY_DURABLE_SESSION_ID is local_ctx_durable_session_id`() {
        assertEquals("local_ctx_durable_session_id", LocalExecutionContextSnapshot.KEY_DURABLE_SESSION_ID)
    }

    @Test
    fun `KEY_ATTACHED_SESSION_ID is local_ctx_attached_session_id`() {
        assertEquals(
            "local_ctx_attached_session_id",
            LocalExecutionContextSnapshot.KEY_ATTACHED_SESSION_ID
        )
    }

    @Test
    fun `KEY_LAST_KNOWN_PHASE is local_ctx_last_known_phase`() {
        assertEquals("local_ctx_last_known_phase", LocalExecutionContextSnapshot.KEY_LAST_KNOWN_PHASE)
    }

    @Test
    fun `KEY_CONTINUITY_TOKEN is local_ctx_continuity_token`() {
        assertEquals("local_ctx_continuity_token", LocalExecutionContextSnapshot.KEY_CONTINUITY_TOKEN)
    }

    @Test
    fun `KEY_CONTINUATION_TOKEN is local_ctx_continuation_token`() {
        assertEquals(
            "local_ctx_continuation_token",
            LocalExecutionContextSnapshot.KEY_CONTINUATION_TOKEN
        )
    }

    @Test
    fun `KEY_STEP_COUNT is local_ctx_step_count`() {
        assertEquals("local_ctx_step_count", LocalExecutionContextSnapshot.KEY_STEP_COUNT)
    }

    @Test
    fun `KEY_LAST_STEP_AT_MS is local_ctx_last_step_at_ms`() {
        assertEquals("local_ctx_last_step_at_ms", LocalExecutionContextSnapshot.KEY_LAST_STEP_AT_MS)
    }

    @Test
    fun `KEY_REHYDRATED_AT_MS is local_ctx_rehydrated_at_ms`() {
        assertEquals("local_ctx_rehydrated_at_ms", LocalExecutionContextSnapshot.KEY_REHYDRATED_AT_MS)
    }

    @Test
    fun `ALL_MANDATORY_KEYS contains exactly the expected set`() {
        val expected = setOf(
            LocalExecutionContextSnapshot.KEY_FLOW_ID,
            LocalExecutionContextSnapshot.KEY_FLOW_LINEAGE_ID,
            LocalExecutionContextSnapshot.KEY_UNIT_ID,
            LocalExecutionContextSnapshot.KEY_TASK_ID,
            LocalExecutionContextSnapshot.KEY_TRACE_ID,
            LocalExecutionContextSnapshot.KEY_DURABLE_SESSION_ID,
            LocalExecutionContextSnapshot.KEY_ATTACHED_SESSION_ID,
            LocalExecutionContextSnapshot.KEY_LAST_KNOWN_PHASE,
            LocalExecutionContextSnapshot.KEY_STEP_COUNT,
            LocalExecutionContextSnapshot.KEY_REHYDRATED_AT_MS
        )
        assertEquals(expected, LocalExecutionContextSnapshot.ALL_MANDATORY_KEYS)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private inline fun <reified T> assertIs(value: Any?) {
        assertTrue(
            "Expected ${T::class.simpleName} but got ${value?.javaClass?.simpleName}",
            value is T
        )
    }
}
