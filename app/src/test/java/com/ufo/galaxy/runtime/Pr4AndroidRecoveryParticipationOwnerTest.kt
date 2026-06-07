package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-4 (Android) — Unit tests for [AndroidRecoveryParticipationOwner],
 * [LocalRecoveryDecision], and [RecoveryActivationCheckpoint].
 *
 * ## Test matrix
 *
 * ### AndroidRecoveryParticipationOwner — constants
 *  - SEMANTIC_RESUME_LOCAL_EXECUTION wire value is "resume_local_execution"
 *  - SEMANTIC_REHYDRATE_THEN_CONTINUE wire value is "rehydrate_then_continue"
 *  - SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION wire value is "wait_for_v2_replay_decision"
 *  - SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY wire value is "suppress_duplicate_local_recovery"
 *  - SEMANTIC_GATE_POST_RESUME_EMIT wire value is "gate_post_resume_emit"
 *  - All five semantic constants are distinct
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - INTRODUCED_PR is 4
 *  - DESCRIPTION is non-blank
 *
 * ### evaluateRecovery — FreshAttach
 *  - Returns NoRecoveryContext for FreshAttach decision
 *  - NoRecoveryContext.semanticTag is "no_recovery_context"
 *
 * ### evaluateRecovery — ReceiverPipelineRebind
 *  - Returns ResumeLocalExecution for ReceiverPipelineRebind
 *  - ResumeLocalExecution.activeSessionId matches the session ID
 *  - ResumeLocalExecution.checkpoint is null (no prior flow record in rebind)
 *  - ResumeLocalExecution.semanticTag is "resume_local_execution"
 *
 * ### evaluateRecovery — TransportReconnect
 *  - Returns WaitForV2ReplayDecision for TransportReconnect
 *  - WaitForV2ReplayDecision.durableSessionId is propagated from the durable session
 *  - WaitForV2ReplayDecision.continuityToken is null (transport reconnect)
 *  - WaitForV2ReplayDecision.reason is REASON_TRANSPORT_RECONNECT_AWAITING_V2
 *  - WaitForV2ReplayDecision.semanticTag is "wait_for_v2_replay_decision"
 *
 * ### evaluateRecovery — ContinuityResume
 *  - Returns RehydrateThenContinue for ContinuityResume
 *  - RehydrateThenContinue.localContext matches the snapshot from the decision
 *  - RehydrateThenContinue.checkpoint is derived from the snapshot
 *  - RehydrateThenContinue.semanticTag is "rehydrate_then_continue"
 *
 * ### evaluateRecovery — ProcessRecreationReattach with context
 *  - Returns RehydrateThenContinue when localContext is non-null
 *  - checkpoint is derived from the local context
 *
 * ### evaluateRecovery — ProcessRecreationReattach without context
 *  - Returns WaitForV2ReplayDecision when localContext is null
 *  - reason is REASON_PROCESS_RECREATION_NO_LOCAL_CONTEXT
 *
 * ### evaluateRecovery — RejectStaleIdentity
 *  - Returns NoRecoveryContext for RejectStaleIdentity decision
 *
 * ### Duplicate local recovery suppression
 *  - suppressDuplicateLocalRecovery returns false for unregistered key
 *  - suppressDuplicateLocalRecovery returns true after markRecoveryAttemptStarted
 *  - suppressDuplicateLocalRecovery returns false after markRecoveryAttemptFinished
 *  - Multiple distinct keys can all be registered simultaneously
 *  - clearRecoveryAttemptRegistry removes all keys
 *  - inProgressRecoveryCount is 0 initially
 *  - inProgressRecoveryCount increments with markRecoveryAttemptStarted
 *  - inProgressRecoveryCount decrements with markRecoveryAttemptFinished
 *  - inProgressRecoveryCount resets to 0 after clearRecoveryAttemptRegistry
 *  - evaluateRecovery returns SuppressDuplicateLocalRecovery when key is in-progress
 *  - SuppressDuplicateLocalRecovery.attemptKey matches the registered key
 *  - SuppressDuplicateLocalRecovery.semanticTag is "suppress_duplicate_local_recovery"
 *
 * ### Post-resume emit gate
 *  - isPostResumeEmitGated is false initially
 *  - gatePostResumeEmit returns false initially (gate open)
 *  - closePostResumeEmitGate sets the gate to true
 *  - openPostResumeEmitGate resets the gate to false
 *  - gatePostResumeEmit reflects closePostResumeEmitGate
 *  - isPostResumeEmitGated reflects openPostResumeEmitGate
 *
 * ### buildRecoveryCheckpoint
 *  - checkpoint flowId matches snapshot
 *  - checkpoint attachedSessionId matches snapshot
 *  - checkpoint durableSessionId matches snapshot
 *  - checkpoint lastKnownPhase matches snapshot
 *  - checkpoint continuityToken matches snapshot
 *  - checkpoint stepCount matches snapshot
 *  - checkpoint checkpointAtMs uses the provided nowMs
 *
 * ### RecoveryActivationCheckpoint
 *  - isCheckpointResumable is true for non-terminal phases (RECEIVED, ACTIVE_LOOP, ACTIVATING)
 *  - isCheckpointResumable is false for terminal phases (COMPLETED, FAILED, REJECTED)
 *  - isCheckpointResumable is false for UNKNOWN phase
 *  - hasContinuityToken is true when continuityToken is non-blank
 *  - hasContinuityToken is false when continuityToken is null
 *  - hasContinuityToken is false when continuityToken is blank
 *  - toCheckpointMetadataMap contains all mandatory keys
 *  - toCheckpointMetadataMap contains optional continuity token when non-null
 *  - toCheckpointMetadataMap omits continuity token when null
 *  - fromSnapshot produces matching checkpoint from snapshot
 *  - ALL_MANDATORY_KEYS contains exactly the expected keys
 *  - INTRODUCED_PR is 4
 *
 * ### LocalRecoveryDecision — semanticTag values
 *  - ResumeLocalExecution.semanticTag matches SEMANTIC_RESUME_LOCAL_EXECUTION
 *  - RehydrateThenContinue.semanticTag matches SEMANTIC_REHYDRATE_THEN_CONTINUE
 *  - WaitForV2ReplayDecision.semanticTag matches SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION
 *  - SuppressDuplicateLocalRecovery.semanticTag matches SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY
 *  - NoRecoveryContext.semanticTag is "no_recovery_context"
 */
class Pr4AndroidRecoveryParticipationOwnerTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var owner: AndroidRecoveryParticipationOwner
    private lateinit var continuityIntegration: AndroidContinuityIntegration

    @Before
    fun setUp() {
        owner = AndroidRecoveryParticipationOwner()
        continuityIntegration = AndroidContinuityIntegration()
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
        epoch: Int = 1
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
        stepCount: Int = 3
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
        continuationToken = null,
        activatedAtMs = 1_000L,
        executionStartedAtMs = 2_000L,
        stepCount = stepCount,
        lastStepAtMs = 5_000L,
        savedAtMs = 9_000L
    )

    private fun localContextSnapshot(
        flowId: String = "flow-xyz",
        attachedSessionId: String = "session-abc",
        durableSessionId: String = "durable-uuid",
        lastKnownPhase: AndroidFlowExecutionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP,
        continuityToken: String? = "ct-opaque",
        stepCount: Int = 3,
        rehydratedAtMs: Long = 10_000L
    ) = LocalExecutionContextSnapshot(
        flowId = flowId,
        flowLineageId = "lineage-lmn",
        unitId = "unit-1",
        taskId = "task-1",
        traceId = "trace-1",
        durableSessionId = durableSessionId,
        attachedSessionId = attachedSessionId,
        lastKnownPhase = lastKnownPhase,
        continuityToken = continuityToken,
        continuationToken = null,
        stepCount = stepCount,
        lastStepAtMs = 5_000L,
        rehydratedAtMs = rehydratedAtMs
    )

    private fun processRecreatedHint(
        priorDurableSessionId: String = "durable-uuid"
    ) = ProcessRecreatedReattachHint(
        priorDurableSessionId = priorDurableSessionId,
        deviceId = "device-1"
    )

    private fun freshAttachInput(
        attemptKey: String = "key-fresh"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.FreshAttach,
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun rebindInput(
        sessionId: String = "session-abc",
        attemptKey: String = "key-rebind"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.ReceiverPipelineRebind(
            activeSessionId = sessionId
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun transportReconnectInput(
        durableSessionId: String = "durable-uuid",
        attemptKey: String = "key-reconnect"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.TransportReconnect(
            durableSession = durableSession(durableSessionId = durableSessionId)
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun continuityResumeInput(
        snapshot: LocalExecutionContextSnapshot = localContextSnapshot(),
        attemptKey: String = "key-continuity"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.ContinuityResume(
            localContext = snapshot
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun processRecreationWithContextInput(
        snapshot: LocalExecutionContextSnapshot = localContextSnapshot(),
        attemptKey: String = "key-recreation-ctx"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = processRecreatedHint(),
            localContext = snapshot
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun processRecreationNoContextInput(
        attemptKey: String = "key-recreation-noct"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.ProcessRecreationReattach(
            reattachHint = processRecreatedHint(),
            localContext = null
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    private fun staleIdentityInput(
        attemptKey: String = "key-stale"
    ) = AndroidRecoveryParticipationOwner.RecoveryInput(
        continuityDecision = AndroidContinuityIntegration.ContinuityDecision.RejectStaleIdentity(
            expectedSessionId = "session-current",
            receivedSessionId = "session-old"
        ),
        recoveryAttemptKey = attemptKey,
        nowMs = 10_000L
    )

    // ── Constants tests ───────────────────────────────────────────────────────

    @Test
    fun `SEMANTIC_RESUME_LOCAL_EXECUTION wire value is resume_local_execution`() {
        assertEquals(
            "resume_local_execution",
            AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION
        )
    }

    @Test
    fun `SEMANTIC_REHYDRATE_THEN_CONTINUE wire value is rehydrate_then_continue`() {
        assertEquals(
            "rehydrate_then_continue",
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE
        )
    }

    @Test
    fun `SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION wire value is wait_for_v2_replay_decision`() {
        assertEquals(
            "wait_for_v2_replay_decision",
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION
        )
    }

    @Test
    fun `SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY wire value is suppress_duplicate_local_recovery`() {
        assertEquals(
            "suppress_duplicate_local_recovery",
            AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY
        )
    }

    @Test
    fun `SEMANTIC_GATE_POST_RESUME_EMIT wire value is gate_post_resume_emit`() {
        assertEquals(
            "gate_post_resume_emit",
            AndroidRecoveryParticipationOwner.SEMANTIC_GATE_POST_RESUME_EMIT
        )
    }

    @Test
    fun `all five semantic constants are distinct`() {
        val semantics = setOf(
            AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION,
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE,
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION,
            AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY,
            AndroidRecoveryParticipationOwner.SEMANTIC_GATE_POST_RESUME_EMIT
        )
        assertEquals(5, semantics.size)
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals("DelegatedRuntimeReceiver", AndroidRecoveryParticipationOwner.INTEGRATION_RECEIVER)
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals("DelegatedRuntimeUnit", AndroidRecoveryParticipationOwner.INTEGRATION_UNIT)
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals(
            "DelegatedActivationRecord",
            AndroidRecoveryParticipationOwner.INTEGRATION_ACTIVATION_RECORD
        )
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals(
            "AutonomousExecutionPipeline",
            AndroidRecoveryParticipationOwner.INTEGRATION_PIPELINE
        )
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals("LoopController", AndroidRecoveryParticipationOwner.INTEGRATION_LOOP_CONTROLLER)
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals(
            "DelegatedTakeoverExecutor",
            AndroidRecoveryParticipationOwner.INTEGRATION_TAKEOVER_EXECUTOR
        )
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals(
            "LocalCollaborationAgent",
            AndroidRecoveryParticipationOwner.INTEGRATION_COLLABORATION_AGENT
        )
    }

    @Test
    fun `INTRODUCED_PR is 4`() {
        assertEquals(4, AndroidRecoveryParticipationOwner.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidRecoveryParticipationOwner.DESCRIPTION.isNotBlank())
    }

    // ── evaluateRecovery — FreshAttach ────────────────────────────────────────

    @Test
    fun `evaluateRecovery returns NoRecoveryContext for FreshAttach`() {
        val result = owner.evaluateRecovery(freshAttachInput())
        assertSame(LocalRecoveryDecision.NoRecoveryContext, result)
    }

    @Test
    fun `NoRecoveryContext semanticTag is no_recovery_context`() {
        assertEquals("no_recovery_context", LocalRecoveryDecision.NoRecoveryContext.semanticTag)
    }

    // ── evaluateRecovery — ReceiverPipelineRebind ─────────────────────────────

    @Test
    fun `evaluateRecovery returns ResumeLocalExecution for ReceiverPipelineRebind`() {
        val result = owner.evaluateRecovery(rebindInput())
        assertTrue(result is LocalRecoveryDecision.ResumeLocalExecution)
    }

    @Test
    fun `ResumeLocalExecution activeSessionId matches the session ID`() {
        val result = owner.evaluateRecovery(rebindInput(sessionId = "session-xyz")) as
            LocalRecoveryDecision.ResumeLocalExecution
        assertEquals("session-xyz", result.activeSessionId)
    }

    @Test
    fun `ResumeLocalExecution checkpoint is null for pure rebind`() {
        val result = owner.evaluateRecovery(rebindInput()) as
            LocalRecoveryDecision.ResumeLocalExecution
        assertNull(result.checkpoint)
    }

    @Test
    fun `ResumeLocalExecution semanticTag is resume_local_execution`() {
        val result = owner.evaluateRecovery(rebindInput()) as
            LocalRecoveryDecision.ResumeLocalExecution
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION,
            result.semanticTag
        )
    }

    // ── evaluateRecovery — TransportReconnect ─────────────────────────────────

    @Test
    fun `evaluateRecovery returns WaitForV2ReplayDecision for TransportReconnect`() {
        val result = owner.evaluateRecovery(transportReconnectInput())
        assertTrue(result is LocalRecoveryDecision.WaitForV2ReplayDecision)
    }

    @Test
    fun `WaitForV2ReplayDecision durableSessionId is propagated from durable session`() {
        val result = owner.evaluateRecovery(
            transportReconnectInput(durableSessionId = "durable-12345")
        ) as LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals("durable-12345", result.durableSessionId)
    }

    @Test
    fun `WaitForV2ReplayDecision continuityToken is null for transport reconnect`() {
        val result = owner.evaluateRecovery(transportReconnectInput()) as
            LocalRecoveryDecision.WaitForV2ReplayDecision
        assertNull(result.continuityToken)
    }

    @Test
    fun `WaitForV2ReplayDecision reason is REASON_TRANSPORT_RECONNECT_AWAITING_V2`() {
        val result = owner.evaluateRecovery(transportReconnectInput()) as
            LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals(
            AndroidRecoveryParticipationOwner.REASON_TRANSPORT_RECONNECT_AWAITING_V2,
            result.reason
        )
    }

    @Test
    fun `WaitForV2ReplayDecision semanticTag is wait_for_v2_replay_decision`() {
        val result = owner.evaluateRecovery(transportReconnectInput()) as
            LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION,
            result.semanticTag
        )
    }

    // ── evaluateRecovery — ContinuityResume ───────────────────────────────────

    @Test
    fun `evaluateRecovery returns RehydrateThenContinue for ContinuityResume`() {
        val result = owner.evaluateRecovery(continuityResumeInput())
        assertTrue(result is LocalRecoveryDecision.RehydrateThenContinue)
    }

    @Test
    fun `RehydrateThenContinue localContext matches the snapshot from the decision`() {
        val snapshot = localContextSnapshot(flowId = "flow-resume")
        val result = owner.evaluateRecovery(continuityResumeInput(snapshot = snapshot)) as
            LocalRecoveryDecision.RehydrateThenContinue
        assertEquals("flow-resume", result.localContext.flowId)
    }

    @Test
    fun `RehydrateThenContinue checkpoint flowId derived from snapshot`() {
        val snapshot = localContextSnapshot(flowId = "flow-resume")
        val result = owner.evaluateRecovery(continuityResumeInput(snapshot = snapshot)) as
            LocalRecoveryDecision.RehydrateThenContinue
        assertEquals("flow-resume", result.checkpoint.flowId)
    }

    @Test
    fun `RehydrateThenContinue semanticTag is rehydrate_then_continue`() {
        val result = owner.evaluateRecovery(continuityResumeInput()) as
            LocalRecoveryDecision.RehydrateThenContinue
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE,
            result.semanticTag
        )
    }

    // ── evaluateRecovery — ProcessRecreationReattach with context ─────────────

    @Test
    fun `evaluateRecovery returns RehydrateThenContinue when process recreation has context`() {
        val result = owner.evaluateRecovery(processRecreationWithContextInput())
        assertTrue(result is LocalRecoveryDecision.RehydrateThenContinue)
    }

    @Test
    fun `checkpoint derived from process recreation local context`() {
        val snapshot = localContextSnapshot(flowId = "flow-recreated", stepCount = 7)
        val result = owner.evaluateRecovery(
            processRecreationWithContextInput(snapshot = snapshot)
        ) as LocalRecoveryDecision.RehydrateThenContinue
        assertEquals("flow-recreated", result.checkpoint.flowId)
        assertEquals(7, result.checkpoint.stepCount)
    }

    // ── evaluateRecovery — ProcessRecreationReattach without context ──────────

    @Test
    fun `evaluateRecovery returns WaitForV2ReplayDecision when process recreation has no context`() {
        val result = owner.evaluateRecovery(processRecreationNoContextInput())
        assertTrue(result is LocalRecoveryDecision.WaitForV2ReplayDecision)
    }

    @Test
    fun `WaitForV2ReplayDecision reason is REASON_PROCESS_RECREATION_NO_LOCAL_CONTEXT`() {
        val result = owner.evaluateRecovery(processRecreationNoContextInput()) as
            LocalRecoveryDecision.WaitForV2ReplayDecision
        assertEquals(
            AndroidRecoveryParticipationOwner.REASON_PROCESS_RECREATION_NO_LOCAL_CONTEXT,
            result.reason
        )
    }

    // ── evaluateRecovery — RejectStaleIdentity ────────────────────────────────

    @Test
    fun `evaluateRecovery returns NoRecoveryContext for RejectStaleIdentity`() {
        val result = owner.evaluateRecovery(staleIdentityInput())
        assertSame(LocalRecoveryDecision.NoRecoveryContext, result)
    }

    // ── Duplicate local recovery suppression ──────────────────────────────────

    @Test
    fun `suppressDuplicateLocalRecovery returns false for unregistered key`() {
        assertFalse(owner.suppressDuplicateLocalRecovery("key-unknown"))
    }

    @Test
    fun `suppressDuplicateLocalRecovery returns true after markRecoveryAttemptStarted`() {
        owner.markRecoveryAttemptStarted("key-1")
        assertTrue(owner.suppressDuplicateLocalRecovery("key-1"))
    }

    @Test
    fun `suppressDuplicateLocalRecovery returns false after markRecoveryAttemptFinished`() {
        owner.markRecoveryAttemptStarted("key-2")
        owner.markRecoveryAttemptFinished("key-2")
        assertFalse(owner.suppressDuplicateLocalRecovery("key-2"))
    }

    @Test
    fun `multiple distinct keys can all be registered simultaneously`() {
        owner.markRecoveryAttemptStarted("key-a")
        owner.markRecoveryAttemptStarted("key-b")
        owner.markRecoveryAttemptStarted("key-c")
        assertTrue(owner.suppressDuplicateLocalRecovery("key-a"))
        assertTrue(owner.suppressDuplicateLocalRecovery("key-b"))
        assertTrue(owner.suppressDuplicateLocalRecovery("key-c"))
    }

    @Test
    fun `clearRecoveryAttemptRegistry removes all keys`() {
        owner.markRecoveryAttemptStarted("key-x")
        owner.markRecoveryAttemptStarted("key-y")
        owner.clearRecoveryAttemptRegistry()
        assertFalse(owner.suppressDuplicateLocalRecovery("key-x"))
        assertFalse(owner.suppressDuplicateLocalRecovery("key-y"))
    }

    @Test
    fun `inProgressRecoveryCount is 0 initially`() {
        assertEquals(0, owner.inProgressRecoveryCount)
    }

    @Test
    fun `inProgressRecoveryCount increments with markRecoveryAttemptStarted`() {
        owner.markRecoveryAttemptStarted("key-count-1")
        assertEquals(1, owner.inProgressRecoveryCount)
        owner.markRecoveryAttemptStarted("key-count-2")
        assertEquals(2, owner.inProgressRecoveryCount)
    }

    @Test
    fun `inProgressRecoveryCount decrements with markRecoveryAttemptFinished`() {
        owner.markRecoveryAttemptStarted("key-dec-1")
        owner.markRecoveryAttemptStarted("key-dec-2")
        owner.markRecoveryAttemptFinished("key-dec-1")
        assertEquals(1, owner.inProgressRecoveryCount)
    }

    @Test
    fun `inProgressRecoveryCount resets to 0 after clearRecoveryAttemptRegistry`() {
        owner.markRecoveryAttemptStarted("key-z")
        owner.clearRecoveryAttemptRegistry()
        assertEquals(0, owner.inProgressRecoveryCount)
    }

    @Test
    fun `evaluateRecovery returns SuppressDuplicateLocalRecovery when key is in-progress`() {
        owner.markRecoveryAttemptStarted("key-dup")
        val result = owner.evaluateRecovery(freshAttachInput(attemptKey = "key-dup"))
        assertTrue(result is LocalRecoveryDecision.SuppressDuplicateLocalRecovery)
    }

    @Test
    fun `SuppressDuplicateLocalRecovery attemptKey matches the registered key`() {
        owner.markRecoveryAttemptStarted("key-dup2")
        val result = owner.evaluateRecovery(freshAttachInput(attemptKey = "key-dup2")) as
            LocalRecoveryDecision.SuppressDuplicateLocalRecovery
        assertEquals("key-dup2", result.attemptKey)
    }

    @Test
    fun `SuppressDuplicateLocalRecovery semanticTag is suppress_duplicate_local_recovery`() {
        owner.markRecoveryAttemptStarted("key-dup3")
        val result = owner.evaluateRecovery(freshAttachInput(attemptKey = "key-dup3")) as
            LocalRecoveryDecision.SuppressDuplicateLocalRecovery
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY,
            result.semanticTag
        )
    }

    // ── Post-resume emit gate ─────────────────────────────────────────────────

    @Test
    fun `isPostResumeEmitGated is false initially`() {
        assertFalse(owner.isPostResumeEmitGated)
    }

    @Test
    fun `gatePostResumeEmit returns false initially`() {
        assertFalse(owner.gatePostResumeEmit())
    }

    @Test
    fun `closePostResumeEmitGate sets gate to true`() {
        owner.closePostResumeEmitGate()
        assertTrue(owner.isPostResumeEmitGated)
    }

    @Test
    fun `openPostResumeEmitGate resets gate to false`() {
        owner.closePostResumeEmitGate()
        owner.openPostResumeEmitGate()
        assertFalse(owner.isPostResumeEmitGated)
    }

    @Test
    fun `gatePostResumeEmit returns true after closePostResumeEmitGate`() {
        owner.closePostResumeEmitGate()
        assertTrue(owner.gatePostResumeEmit())
    }

    @Test
    fun `isPostResumeEmitGated is false after openPostResumeEmitGate`() {
        owner.closePostResumeEmitGate()
        owner.openPostResumeEmitGate()
        assertFalse(owner.isPostResumeEmitGated)
    }

    // ── buildRecoveryCheckpoint ───────────────────────────────────────────────

    @Test
    fun `checkpoint flowId matches snapshot`() {
        val snapshot = localContextSnapshot(flowId = "flow-check")
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals("flow-check", checkpoint.flowId)
    }

    @Test
    fun `checkpoint attachedSessionId matches snapshot`() {
        val snapshot = localContextSnapshot(attachedSessionId = "sess-check")
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals("sess-check", checkpoint.attachedSessionId)
    }

    @Test
    fun `checkpoint durableSessionId matches snapshot`() {
        val snapshot = localContextSnapshot(durableSessionId = "durable-check")
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals("durable-check", checkpoint.durableSessionId)
    }

    @Test
    fun `checkpoint lastKnownPhase matches snapshot`() {
        val snapshot = localContextSnapshot(lastKnownPhase = AndroidFlowExecutionPhase.ACTIVE_TAKEOVER)
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals(AndroidFlowExecutionPhase.ACTIVE_TAKEOVER, checkpoint.lastKnownPhase)
    }

    @Test
    fun `checkpoint continuityToken matches snapshot`() {
        val snapshot = localContextSnapshot(continuityToken = "ct-test")
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals("ct-test", checkpoint.continuityToken)
    }

    @Test
    fun `checkpoint stepCount matches snapshot`() {
        val snapshot = localContextSnapshot(stepCount = 12)
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 77_000L)
        assertEquals(12, checkpoint.stepCount)
    }

    @Test
    fun `checkpoint checkpointAtMs uses the provided nowMs`() {
        val snapshot = localContextSnapshot()
        val checkpoint = owner.buildRecoveryCheckpoint(snapshot, nowMs = 99_999L)
        assertEquals(99_999L, checkpoint.checkpointAtMs)
    }

    // ── RecoveryActivationCheckpoint — isCheckpointResumable ─────────────────

    @Test
    fun `isCheckpointResumable is true for RECEIVED phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.RECEIVED)
        assertTrue(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is true for ACTIVATING phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.ACTIVATING)
        assertTrue(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is true for ACTIVE_LOOP phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.ACTIVE_LOOP)
        assertTrue(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is true for ACTIVE_GOAL_EXECUTION phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION)
        assertTrue(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is false for COMPLETED phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.COMPLETED)
        assertFalse(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is false for FAILED phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.FAILED)
        assertFalse(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is false for REJECTED phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.REJECTED)
        assertFalse(cp.isCheckpointResumable)
    }

    @Test
    fun `isCheckpointResumable is false for UNKNOWN phase`() {
        val cp = buildCheckpoint(AndroidFlowExecutionPhase.UNKNOWN)
        assertFalse(cp.isCheckpointResumable)
    }

    // ── RecoveryActivationCheckpoint — hasContinuityToken ────────────────────

    @Test
    fun `hasContinuityToken is true when continuityToken is non-blank`() {
        val cp = buildCheckpoint(continuityToken = "ct-opaque")
        assertTrue(cp.hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken is false when continuityToken is null`() {
        val cp = buildCheckpoint(continuityToken = null)
        assertFalse(cp.hasContinuityToken)
    }

    @Test
    fun `hasContinuityToken is false when continuityToken is blank`() {
        val cp = buildCheckpoint(continuityToken = "   ")
        assertFalse(cp.hasContinuityToken)
    }

    // ── RecoveryActivationCheckpoint — toCheckpointMetadataMap ───────────────

    @Test
    fun `toCheckpointMetadataMap contains all mandatory keys`() {
        val cp = buildCheckpoint()
        val map = cp.toCheckpointMetadataMap()
        RecoveryActivationCheckpoint.ALL_MANDATORY_KEYS.forEach { key ->
            assertTrue("Missing mandatory key: $key", map.containsKey(key))
        }
    }

    @Test
    fun `toCheckpointMetadataMap contains continuity token when non-null`() {
        val cp = buildCheckpoint(continuityToken = "ct-present")
        val map = cp.toCheckpointMetadataMap()
        assertTrue(map.containsKey(RecoveryActivationCheckpoint.KEY_CONTINUITY_TOKEN))
        assertEquals("ct-present", map[RecoveryActivationCheckpoint.KEY_CONTINUITY_TOKEN])
    }

    @Test
    fun `toCheckpointMetadataMap omits continuity token when null`() {
        val cp = buildCheckpoint(continuityToken = null)
        val map = cp.toCheckpointMetadataMap()
        assertFalse(map.containsKey(RecoveryActivationCheckpoint.KEY_CONTINUITY_TOKEN))
    }

    @Test
    fun `toCheckpointMetadataMap values match checkpoint fields`() {
        val cp = buildCheckpoint(
            phase = AndroidFlowExecutionPhase.ACTIVE_COLLABORATION,
            stepCount = 5
        )
        val map = cp.toCheckpointMetadataMap()
        assertEquals("active_collaboration", map[RecoveryActivationCheckpoint.KEY_LAST_KNOWN_PHASE])
        assertEquals(5, map[RecoveryActivationCheckpoint.KEY_STEP_COUNT])
        assertEquals(cp.isCheckpointResumable, map[RecoveryActivationCheckpoint.KEY_IS_RESUMABLE])
    }

    // ── RecoveryActivationCheckpoint — fromSnapshot ───────────────────────────

    @Test
    fun `fromSnapshot produces matching checkpoint from snapshot`() {
        val snapshot = localContextSnapshot(
            flowId = "flow-snap",
            attachedSessionId = "sess-snap",
            stepCount = 9,
            continuityToken = "ct-snap"
        )
        val cp = RecoveryActivationCheckpoint.fromSnapshot(snapshot, checkpointAtMs = 55_000L)
        assertEquals("flow-snap", cp.flowId)
        assertEquals("sess-snap", cp.attachedSessionId)
        assertEquals(9, cp.stepCount)
        assertEquals("ct-snap", cp.continuityToken)
        assertEquals(55_000L, cp.checkpointAtMs)
    }

    // ── RecoveryActivationCheckpoint — ALL_MANDATORY_KEYS ────────────────────

    @Test
    fun `ALL_MANDATORY_KEYS contains exactly the expected set`() {
        val expected = setOf(
            RecoveryActivationCheckpoint.KEY_FLOW_ID,
            RecoveryActivationCheckpoint.KEY_ATTACHED_SESSION_ID,
            RecoveryActivationCheckpoint.KEY_DURABLE_SESSION_ID,
            RecoveryActivationCheckpoint.KEY_LAST_KNOWN_PHASE,
            RecoveryActivationCheckpoint.KEY_STEP_COUNT,
            RecoveryActivationCheckpoint.KEY_IS_RESUMABLE,
            RecoveryActivationCheckpoint.KEY_CHECKPOINT_AT_MS
        )
        assertEquals(expected, RecoveryActivationCheckpoint.ALL_MANDATORY_KEYS)
    }

    @Test
    fun `RecoveryActivationCheckpoint INTRODUCED_PR is 4`() {
        assertEquals(4, RecoveryActivationCheckpoint.INTRODUCED_PR)
    }

    // ── LocalRecoveryDecision — semanticTag values ────────────────────────────

    @Test
    fun `ResumeLocalExecution semanticTag matches SEMANTIC_RESUME_LOCAL_EXECUTION`() {
        val decision = LocalRecoveryDecision.ResumeLocalExecution(activeSessionId = "s")
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_RESUME_LOCAL_EXECUTION,
            decision.semanticTag
        )
    }

    @Test
    fun `RehydrateThenContinue semanticTag matches SEMANTIC_REHYDRATE_THEN_CONTINUE`() {
        val snapshot = localContextSnapshot()
        val checkpoint = buildCheckpoint()
        val decision = LocalRecoveryDecision.RehydrateThenContinue(
            localContext = snapshot,
            checkpoint = checkpoint
        )
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_REHYDRATE_THEN_CONTINUE,
            decision.semanticTag
        )
    }

    @Test
    fun `WaitForV2ReplayDecision semanticTag matches SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION`() {
        val decision = LocalRecoveryDecision.WaitForV2ReplayDecision(
            continuityToken = null,
            durableSessionId = null,
            reason = "test"
        )
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_WAIT_FOR_V2_REPLAY_DECISION,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDuplicateLocalRecovery semanticTag matches SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY`() {
        val decision = LocalRecoveryDecision.SuppressDuplicateLocalRecovery(attemptKey = "k")
        assertEquals(
            AndroidRecoveryParticipationOwner.SEMANTIC_SUPPRESS_DUPLICATE_LOCAL_RECOVERY,
            decision.semanticTag
        )
    }

    @Test
    fun `NoRecoveryContext semanticTag is no_recovery_context`() {
        assertEquals("no_recovery_context", LocalRecoveryDecision.NoRecoveryContext.semanticTag)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildCheckpoint(
        phase: AndroidFlowExecutionPhase = AndroidFlowExecutionPhase.ACTIVE_LOOP,
        continuityToken: String? = "ct-token",
        stepCount: Int = 3
    ) = RecoveryActivationCheckpoint(
        flowId = "flow-test",
        attachedSessionId = "session-test",
        durableSessionId = "durable-test",
        lastKnownPhase = phase,
        continuityToken = continuityToken,
        stepCount = stepCount,
        checkpointAtMs = 10_000L
    )
}
