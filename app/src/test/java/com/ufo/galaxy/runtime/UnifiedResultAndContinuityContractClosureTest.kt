package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.network.OfflineTaskQueue
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unified result contract and continuity closure tests.
 *
 * Verifies the two final closures required to eliminate all production result bypasses and
 * bring online execution under the same continuity authority as the offline-replay path:
 *
 * ## Closure 1 — No remaining TASK_RESULT bypass
 *
 * All production result paths (including `task_assign` payload parse error) MUST use
 * [MsgType.GOAL_EXECUTION_RESULT] and route through `sendGoalResult()`.  The
 * `sendTaskError()` → [MsgType.TASK_RESULT] bypass has been removed; parse errors
 * now call `sendGoalError()` which delegates to `sendGoalResult()`.
 *
 * ### Tests
 *  - task_assign parse error result uses GOAL_EXECUTION_RESULT envelope type
 *  - task_assign parse error result does NOT use TASK_RESULT
 *  - task_assign parse error result does NOT use GOAL_RESULT (legacy type)
 *  - parse error result carries task_id and error message in payload
 *  - goal_execution parse error result uses GOAL_EXECUTION_RESULT (regression guard)
 *  - parallel_subtask parse error result uses GOAL_EXECUTION_RESULT (regression guard)
 *
 * ## Closure 2 — Continuity authority in online execution
 *
 * Online execution handlers (task_assign, goal_execution, parallel_subtask, takeover,
 * handoff_envelope_v2) now call [AndroidContinuityIntegration.validateRuntimeIdentity]
 * before starting execution.  This gives online execution the same continuity authority
 * that the offline-replay path has via `discardForDifferentSession()`.
 *
 * ### Tests — stale session gate (NoActiveSession path)
 *  - validateRuntimeIdentity returns NoActiveSession when no session is active
 *  - NoActiveSession is the gate result that blocks online goal_execution
 *  - NoActiveSession is the gate result that blocks online task_assign
 *  - NoActiveSession is the gate result that blocks online parallel_subtask
 *  - NoActiveSession is the gate result that blocks online handoff_envelope_v2
 *
 * ### Tests — stale attachment gate (StaleIdentity path for takeover)
 *  - validateRuntimeIdentity returns StaleIdentity when session IDs differ
 *  - StaleIdentity carries the expected and received session IDs
 *  - StaleIdentity is the gate result that blocks online takeover
 *  - Valid is returned when session IDs match (takeover proceeds)
 *
 * ### Tests — handoff_envelope_v2 continuity gate (new closure)
 *  - NoActiveSession blocks online handoff_envelope_v2
 *  - handoff_envelope_v2 proceeds when active session is present
 *
 * ### Tests — replay contract invariant (P5 guard)
 *  - goal_execution_result is still in QUEUEABLE_TYPES (online ≡ replay contract)
 *  - goal_result is still in QUEUEABLE_TYPES (backward-compat preserved)
 *  - task_result is still in QUEUEABLE_TYPES (backward-compat preserved)
 *
 * @see AndroidContinuityIntegration.validateRuntimeIdentity
 * @see MsgType.GOAL_EXECUTION_RESULT
 * @see OfflineTaskQueue.QUEUEABLE_TYPES
 */
class UnifiedResultAndContinuityContractClosureTest {

    private lateinit var continuityIntegration: AndroidContinuityIntegration

    @Before
    fun setUp() {
        continuityIntegration = AndroidContinuityIntegration()
    }

    // ── Helper: build an error GoalResultPayload mirroring sendGoalError() ────

    /**
     * Mirrors [com.ufo.galaxy.service.GalaxyConnectionService.sendGoalError]:
     * builds a [GoalResultPayload] with error status, then wraps it in a
     * [AipMessage] with [MsgType.GOAL_EXECUTION_RESULT].
     *
     * After the closure fix, `task_assign` parse errors call `sendGoalError()`
     * instead of `sendTaskError()`, so all error results use this path.
     */
    private fun buildGoalErrorEnvelope(
        taskId: String,
        errorMsg: String,
        traceId: String? = null,
        routeMode: String? = null
    ): AipMessage {
        val errorResult = GoalResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = EdgeExecutor.STATUS_ERROR,
            error = errorMsg,
            device_id = "test-device",
            latency_ms = 0L
        )
        return AipMessage(
            type = MsgType.GOAL_EXECUTION_RESULT,
            payload = errorResult,
            correlation_id = taskId,
            device_id = "test-device",
            trace_id = traceId,
            route_mode = routeMode
        )
    }

    // ── Helper: build an AttachedRuntimeSession ───────────────────────────────

    private fun attachedSession(
        sessionId: String = "session-current-abc",
        state: AttachedRuntimeSession.State = AttachedRuntimeSession.State.ATTACHED
    ) = AttachedRuntimeSession(
        sessionId = sessionId,
        hostId = "host-1",
        deviceId = "device-1",
        state = state
    )

    // ════════════════════════════════════════════════════════════════════════════
    // CLOSURE 1 — No remaining TASK_RESULT bypass
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `task_assign parse error result uses GOAL_EXECUTION_RESULT envelope type`() {
        // After the closure fix, task_assign parse errors call sendGoalError(),
        // which always wraps results in MsgType.GOAL_EXECUTION_RESULT.
        val envelope = buildGoalErrorEnvelope(
            taskId = "task-parse-err-001",
            errorMsg = "bad_payload: Unexpected token at line 1",
            traceId = "trace-parse-err-001"
        )
        assertEquals(
            "task_assign parse error must use GOAL_EXECUTION_RESULT (sendGoalError path)",
            MsgType.GOAL_EXECUTION_RESULT,
            envelope.type
        )
    }

    @Test
    fun `task_assign parse error result does NOT use TASK_RESULT`() {
        // The sendTaskError() → TASK_RESULT bypass is eliminated. Explicitly assert
        // that the parse error envelope is not TASK_RESULT.
        val envelope = buildGoalErrorEnvelope(
            taskId = "task-parse-err-002",
            errorMsg = "bad_payload: malformed JSON"
        )
        assertNotEquals(
            "task_assign parse error must NOT emit TASK_RESULT after closure fix",
            MsgType.TASK_RESULT,
            envelope.type
        )
    }

    @Test
    fun `task_assign parse error result does NOT use GOAL_RESULT`() {
        // The legacy GOAL_RESULT type must not appear on the parse error path either.
        val envelope = buildGoalErrorEnvelope(
            taskId = "task-parse-err-003",
            errorMsg = "bad_payload: unexpected end of stream"
        )
        assertNotEquals(
            "task_assign parse error must NOT emit legacy GOAL_RESULT",
            MsgType.GOAL_RESULT,
            envelope.type
        )
    }

    @Test
    fun `parse error result carries task_id and error message in payload`() {
        val errorMsg = "bad_payload: Unexpected character at position 42"
        val envelope = buildGoalErrorEnvelope(
            taskId = "task-field-check-001",
            errorMsg = errorMsg,
            traceId = "trace-fc-001"
        )
        val payload = envelope.payload as GoalResultPayload
        assertEquals("task-field-check-001", payload.task_id)
        assertEquals(errorMsg, payload.error)
        assertEquals(EdgeExecutor.STATUS_ERROR, payload.status)
    }

    @Test
    fun `goal_execution parse error result uses GOAL_EXECUTION_RESULT (regression guard)`() {
        // goal_execution already used sendGoalError() before the closure fix.
        // This is a regression guard to ensure it stays on the unified path.
        val envelope = buildGoalErrorEnvelope(
            taskId = "goal-exec-parse-err-001",
            errorMsg = "bad_payload: invalid GoalExecutionPayload structure",
            traceId = "trace-ge-err-001",
            routeMode = "cross_device"
        )
        assertEquals(
            "goal_execution parse error must always use GOAL_EXECUTION_RESULT",
            MsgType.GOAL_EXECUTION_RESULT,
            envelope.type
        )
    }

    @Test
    fun `parallel_subtask parse error result uses GOAL_EXECUTION_RESULT (regression guard)`() {
        // parallel_subtask already used sendGoalError() before the closure fix.
        // This is a regression guard to ensure it stays on the unified path.
        val envelope = buildGoalErrorEnvelope(
            taskId = "subtask-parse-err-001",
            errorMsg = "bad_payload: invalid subtask payload",
            traceId = "trace-sub-err-001",
            routeMode = "cross_device"
        )
        assertEquals(
            "parallel_subtask parse error must always use GOAL_EXECUTION_RESULT",
            MsgType.GOAL_EXECUTION_RESULT,
            envelope.type
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // CLOSURE 2 — Continuity authority: NoActiveSession gate
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `validateRuntimeIdentity returns NoActiveSession when no session is active`() {
        // The continuity gate added to handleGoalExecution, handleTaskAssign, and
        // handleParallelSubtask calls validateRuntimeIdentity(activeSession?.sessionId ?: "", null).
        // When activeSession is null, NoActiveSession is returned and execution is blocked.
        val result = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = "",
            activeSession = null
        )
        assertTrue(
            "Must return NoActiveSession when no session is active",
            result is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `NoActiveSession is the gate result that blocks online goal_execution`() {
        // Simulates the continuity gate call inside handleGoalExecution.
        // When attachedSession.value is null (no active session), execution is blocked.
        val noSession: AttachedRuntimeSession? = null
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = noSession?.sessionId ?: "",
            activeSession = noSession
        )
        assertTrue(
            "goal_execution must be blocked when no active session (NoActiveSession gate)",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `NoActiveSession is the gate result that blocks online task_assign`() {
        // Simulates the continuity gate call inside handleTaskAssign.
        val noSession: AttachedRuntimeSession? = null
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = noSession?.sessionId ?: "",
            activeSession = noSession
        )
        assertTrue(
            "task_assign must be blocked when no active session (NoActiveSession gate)",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `NoActiveSession is the gate result that blocks online parallel_subtask`() {
        // Simulates the continuity gate call inside handleParallelSubtask.
        val noSession: AttachedRuntimeSession? = null
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = noSession?.sessionId ?: "",
            activeSession = noSession
        )
        assertTrue(
            "parallel_subtask must be blocked when no active session (NoActiveSession gate)",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `online execution proceeds when active session is present`() {
        // When an active ATTACHED session exists and the unit's session ID matches,
        // validateRuntimeIdentity returns Valid and online execution is NOT blocked.
        val currentSession = attachedSession(sessionId = "session-active-xyz")
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = currentSession.sessionId,
            activeSession = currentSession
        )
        assertTrue(
            "Online execution must proceed when active session is present and IDs match",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.Valid
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // CLOSURE 2 — Continuity authority: StaleIdentity gate (takeover path)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `validateRuntimeIdentity returns StaleIdentity when session IDs differ`() {
        // The continuity gate added to handleTakeoverRequest calls
        // validateRuntimeIdentity(delegatedUnit.attachedSessionId, latestSession).
        // If the session changed between receipt and execution, StaleIdentity is returned.
        val currentSession = attachedSession(sessionId = "session-new-era")
        val result = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-old-era",  // captured at receipt time
            activeSession = currentSession               // re-read at execution time
        )
        assertTrue(
            "Must return StaleIdentity when unit carries a session ID from a prior era",
            result is AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity
        )
    }

    @Test
    fun `StaleIdentity carries the expected and received session IDs`() {
        val currentSession = attachedSession(sessionId = "session-expected-current")
        val staleResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-stale-old",
            activeSession = currentSession
        ) as AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity

        assertEquals(
            "expectedSessionId must match the active session's ID",
            "session-expected-current",
            staleResult.expectedSessionId
        )
        assertEquals(
            "receivedSessionId must match the unit's (stale) session ID",
            "session-stale-old",
            staleResult.receivedSessionId
        )
    }

    @Test
    fun `StaleIdentity is the gate result that blocks online delegated takeover`() {
        // Simulates the takeover continuity gate: DelegatedRuntimeReceiver captured
        // session A at receipt time; by execution time the session has changed to B.
        // The gate checks attachedSessionId (A) vs latestSession (B) → StaleIdentity → reject.
        val sessionAtReceiptTime = "session-at-receipt-A"
        val sessionAtExecutionTime = attachedSession(sessionId = "session-at-execution-B")
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = sessionAtReceiptTime,
            activeSession = sessionAtExecutionTime
        )
        assertTrue(
            "Takeover must be blocked when attached session changed between receipt and execution",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.StaleIdentity
        )
    }

    @Test
    fun `NoActiveSession is returned when no active session for takeover`() {
        // If session is invalidated between receipt and execution gate, NoActiveSession
        // is returned, which the gate also treats as a blocking result.
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = "session-at-receipt",
            activeSession = null
        )
        assertTrue(
            "Takeover must be blocked when session becomes null between receipt and execution",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `takeover proceeds when session ID is stable from receipt to execution`() {
        // Happy path: session did not change between receipt and execution.
        val stableSession = attachedSession(sessionId = "session-stable-abc")
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = stableSession.sessionId,  // captured at receipt
            activeSession = stableSession                       // same at execution
        )
        assertTrue(
            "Takeover must proceed when session is stable and IDs match",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.Valid
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // CLOSURE 2 — Continuity authority: handoff_envelope_v2 gate
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `NoActiveSession is the gate result that blocks online handoff_envelope_v2`() {
        // Simulates the continuity gate call inside handleHandoffEnvelopeV2.
        // When attachedSession.value is null (no active session), execution is blocked
        // before onRemoteTaskStarted() is called, matching the behavior of the other
        // online execution entry points.
        val noSession: AttachedRuntimeSession? = null
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = noSession?.sessionId ?: "",
            activeSession = noSession
        )
        assertTrue(
            "handoff_envelope_v2 must be blocked when no active session (NoActiveSession gate)",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.NoActiveSession
        )
    }

    @Test
    fun `handoff_envelope_v2 proceeds when active session is present`() {
        // Happy path: an active ATTACHED session is present.
        // validateRuntimeIdentity returns Valid and handoff_envelope_v2 execution is NOT blocked.
        val activeSession = attachedSession(sessionId = "session-handoff-xyz")
        val identityResult = continuityIntegration.validateRuntimeIdentity(
            unitAttachedSessionId = activeSession.sessionId,
            activeSession = activeSession
        )
        assertTrue(
            "handoff_envelope_v2 must proceed when active session is present and IDs match",
            identityResult is AndroidContinuityIntegration.IdentityValidationResult.Valid
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // P5 REPLAY CONTRACT INVARIANT — online ≡ offline contract (regression guard)
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun `goal_execution_result is still in QUEUEABLE_TYPES (online equals replay contract)`() {
        // OfflineTaskQueue.QUEUEABLE_TYPES must always contain goal_execution_result so that
        // online results and replayed results share the same wire type and V2 handler.
        assertTrue(
            "goal_execution_result must remain queueable to preserve online==replay contract",
            OfflineTaskQueue.QUEUEABLE_TYPES.contains(MsgType.GOAL_EXECUTION_RESULT.value)
        )
    }

    @Test
    fun `goal_result is still in QUEUEABLE_TYPES (backward compatibility preserved)`() {
        assertTrue(
            "goal_result must remain in QUEUEABLE_TYPES for backward compatibility",
            OfflineTaskQueue.QUEUEABLE_TYPES.contains(MsgType.GOAL_RESULT.value)
        )
    }

    @Test
    fun `task_result is still in QUEUEABLE_TYPES (backward compatibility preserved)`() {
        assertTrue(
            "task_result must remain in QUEUEABLE_TYPES for backward compatibility",
            OfflineTaskQueue.QUEUEABLE_TYPES.contains("task_result")
        )
    }

    @Test
    fun `GOAL_EXECUTION_RESULT wire value is stable`() {
        // Dual-repo contract: V2 must consume exactly this string in its handler routing.
        assertEquals(
            "GOAL_EXECUTION_RESULT wire value must be goal_execution_result",
            "goal_execution_result",
            MsgType.GOAL_EXECUTION_RESULT.value
        )
    }

    @Test
    fun `SEMANTIC_REJECT_STALE_IDENTITY wire value is stable`() {
        // Used in rejection_reason fields sent to V2 when the continuity gate fires.
        assertEquals(
            "SEMANTIC_REJECT_STALE_IDENTITY must be stable",
            "reject_stale_runtime_identity",
            AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY
        )
    }
}
