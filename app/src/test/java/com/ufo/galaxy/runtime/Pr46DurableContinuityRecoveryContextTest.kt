package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.agent.toEnvelopeV2
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.TaskAssignPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-46 — Align Android runtime handling with durable continuity and recovery context
 * for reconnect/handoff execution.
 *
 * V2 introduces durable continuity and recovery context to preserve execution and session
 * association across reconnects, handoffs, and recoverable interruptions.  This test suite
 * validates all Android-side compatibility and semantic clarity requirements defined by PR-F.
 *
 * ## Sections
 *
 * ### ContinuityRecoveryContext — interruption reason constants
 *  - REASON_RECONNECT wire value is `"reconnect"`
 *  - REASON_HANDOFF wire value is `"handoff"`
 *  - REASON_DEVICE_PAUSE wire value is `"device_pause"`
 *  - REASON_TRANSPORT_DEGRADED wire value is `"transport_degraded"`
 *
 * ### ContinuityRecoveryContext — KNOWN_INTERRUPTION_REASONS
 *  - KNOWN_INTERRUPTION_REASONS contains exactly four entries
 *  - KNOWN_INTERRUPTION_REASONS contains every canonical wire value
 *
 * ### ContinuityRecoveryContext — isResumableExecution
 *  - isResumableExecution(true) is true
 *  - isResumableExecution(false) is false
 *  - isResumableExecution(null) is false (legacy default — not resumable)
 *
 * ### ContinuityRecoveryContext — isTerminalExecution
 *  - isTerminalExecution(false) is true
 *  - isTerminalExecution(true) is false
 *  - isTerminalExecution(null) is false (legacy default — not unambiguously terminal)
 *
 * ### ContinuityRecoveryContext — isKnownInterruptionReason
 *  - isKnownInterruptionReason returns true for all canonical values
 *  - isKnownInterruptionReason returns false for null
 *  - isKnownInterruptionReason returns false for unknown / future values
 *
 * ### ContinuityRecoveryContext — isTransportInterruption
 *  - reconnect is a transport interruption
 *  - transport_degraded is a transport interruption
 *  - handoff is NOT a transport interruption
 *  - device_pause is NOT a transport interruption
 *  - null is NOT a transport interruption
 *  - unknown is NOT a transport interruption
 *
 * ### GoalExecutionPayload — PR-F continuity/recovery fields
 *  - continuity_token defaults to null (backward compatibility)
 *  - recovery_context defaults to empty map (backward compatibility)
 *  - is_resumable defaults to null (backward compatibility)
 *  - interruption_reason defaults to null (backward compatibility)
 *  - all PR-F fields can be set simultaneously
 *  - effectiveTimeoutMs is unaffected by PR-F fields
 *
 * ### TaskAssignPayload — PR-F continuity/recovery fields
 *  - continuity_token defaults to null (backward compatibility)
 *  - recovery_context defaults to empty map (backward compatibility)
 *  - is_resumable defaults to null (backward compatibility)
 *  - interruption_reason defaults to null (backward compatibility)
 *  - all PR-F fields can be set simultaneously on TaskAssignPayload
 *
 * ### TakeoverRequestEnvelope — PR-F continuity/recovery fields
 *  - continuity_token defaults to null (backward compatibility)
 *  - recovery_context defaults to empty map (backward compatibility)
 *  - is_resumable defaults to null (backward compatibility)
 *  - interruption_reason defaults to null (backward compatibility)
 *  - all PR-F fields can be set simultaneously on TakeoverRequestEnvelope
 *  - PR-F fields do not affect resolvedPosture
 *
 * ### HandoffEnvelopeV2 — PR-F continuity/recovery fields
 *  - toEnvelopeV2 propagates continuity_token from HandoffRequest
 *  - toEnvelopeV2 propagates recovery_context from HandoffRequest
 *  - toEnvelopeV2 propagates is_resumable from HandoffRequest
 *  - toEnvelopeV2 propagates interruption_reason from HandoffRequest
 *  - toEnvelopeV2 produces null continuity fields when HandoffRequest has none (legacy compat)
 *
 * ### AutonomousExecutionPipeline — continuity/recovery field acceptance (goal_execution)
 *  - pipeline accepts payload with continuity_token set (no failure)
 *  - pipeline accepts payload with recovery_context set (no failure)
 *  - pipeline accepts payload with is_resumable=true (no failure, not blocked)
 *  - pipeline accepts payload with is_resumable=false (no failure, not blocked)
 *  - pipeline accepts payload with all PR-F fields set (no failure)
 *  - pipeline accepts payload with no PR-F fields (legacy backward compat)
 *
 * ### AutonomousExecutionPipeline — continuity/recovery echoed in results (goal_execution)
 *  - success result echoes continuity_token from payload
 *  - success result echoes is_resumable from payload
 *  - success result echoes null continuity_token from legacy payload
 *  - success result echoes null is_resumable from legacy payload
 *  - disabled result echoes continuity_token from payload
 *  - disabled result echoes is_resumable from payload
 *
 * ### AutonomousExecutionPipeline — resumable interruption not collapsed into terminal failure
 *  - is_resumable=true payload that is otherwise blocked returns disabled (not error)
 *  - disabled result with is_resumable=true preserves is_resumable=true
 *
 * ### AutonomousExecutionPipeline — continuity/recovery echoed in results (parallel_subtask)
 *  - parallel success result echoes continuity_token from payload
 *  - parallel success result echoes is_resumable from payload
 *  - parallel disabled result echoes continuity_token from payload
 *  - parallel disabled result echoes is_resumable from payload
 */
class Pr46DurableContinuityRecoveryContextTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private class OkPlanner : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "replan not supported")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f, element_description = "button")
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private fun buildPipeline(
        goalExecutionEnabled: Boolean = true,
        parallelExecutionEnabled: Boolean = true,
        crossDeviceEnabled: Boolean = true,
        deviceId: String = "test-device",
        deviceRole: String = "phone"
    ): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = goalExecutionEnabled,
            parallelExecutionEnabled = parallelExecutionEnabled,
            crossDeviceEnabled = crossDeviceEnabled,
            deviceRole = deviceRole
        )
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = OkPlanner(),
            groundingService = OkGrounder(),
            accessibilityExecutor = OkAccessibility()
        )
        val goalExec = LocalGoalExecutor(edge, deviceId)
        val collab = LocalCollaborationAgent(goalExec)
        return AutonomousExecutionPipeline(
            settings = settings,
            goalExecutor = goalExec,
            collaborationAgent = collab,
            deviceId = deviceId,
            deviceRole = deviceRole
        )
    }

    private fun goalPayload(
        taskId: String = "t-prf",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
        continuityToken: String? = null,
        recoveryContext: Map<String, String> = emptyMap(),
        isResumable: Boolean? = null,
        interruptionReason: String? = null,
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = posture,
        continuity_token = continuityToken,
        recovery_context = recoveryContext,
        is_resumable = isResumable,
        interruption_reason = interruptionReason
    )

    // ── ContinuityRecoveryContext — interruption reason constants ─────────────

    @Test
    fun `REASON_RECONNECT wire value is reconnect`() {
        assertEquals("reconnect", ContinuityRecoveryContext.REASON_RECONNECT)
    }

    @Test
    fun `REASON_HANDOFF wire value is handoff`() {
        assertEquals("handoff", ContinuityRecoveryContext.REASON_HANDOFF)
    }

    @Test
    fun `REASON_DEVICE_PAUSE wire value is device_pause`() {
        assertEquals("device_pause", ContinuityRecoveryContext.REASON_DEVICE_PAUSE)
    }

    @Test
    fun `REASON_TRANSPORT_DEGRADED wire value is transport_degraded`() {
        assertEquals("transport_degraded", ContinuityRecoveryContext.REASON_TRANSPORT_DEGRADED)
    }

    // ── ContinuityRecoveryContext — KNOWN_INTERRUPTION_REASONS ───────────────

    @Test
    fun `KNOWN_INTERRUPTION_REASONS contains exactly four entries`() {
        assertEquals(
            "KNOWN_INTERRUPTION_REASONS must have exactly 4 entries",
            4,
            ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.size
        )
    }

    @Test
    fun `KNOWN_INTERRUPTION_REASONS contains all canonical wire values`() {
        assertTrue(ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.contains(ContinuityRecoveryContext.REASON_RECONNECT))
        assertTrue(ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.contains(ContinuityRecoveryContext.REASON_HANDOFF))
        assertTrue(ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.contains(ContinuityRecoveryContext.REASON_DEVICE_PAUSE))
        assertTrue(ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.contains(ContinuityRecoveryContext.REASON_TRANSPORT_DEGRADED))
    }

    // ── ContinuityRecoveryContext — isResumableExecution ─────────────────────

    @Test
    fun `isResumableExecution returns true for true`() {
        assertTrue(
            "isResumableExecution(true) must be true — V2 explicitly marks as resumable",
            ContinuityRecoveryContext.isResumableExecution(true)
        )
    }

    @Test
    fun `isResumableExecution returns false for false`() {
        assertFalse(
            "isResumableExecution(false) must be false — V2 explicitly marks as terminal",
            ContinuityRecoveryContext.isResumableExecution(false)
        )
    }

    @Test
    fun `isResumableExecution returns false for null`() {
        assertFalse(
            "isResumableExecution(null) must be false — null means legacy/unspecified, not resumable",
            ContinuityRecoveryContext.isResumableExecution(null)
        )
    }

    // ── ContinuityRecoveryContext — isTerminalExecution ───────────────────────

    @Test
    fun `isTerminalExecution returns true for false`() {
        assertTrue(
            "isTerminalExecution(false) must be true — V2 explicitly marks as terminal",
            ContinuityRecoveryContext.isTerminalExecution(false)
        )
    }

    @Test
    fun `isTerminalExecution returns false for true`() {
        assertFalse(
            "isTerminalExecution(true) must be false — V2 explicitly marks as resumable",
            ContinuityRecoveryContext.isTerminalExecution(true)
        )
    }

    @Test
    fun `isTerminalExecution returns false for null`() {
        assertFalse(
            "isTerminalExecution(null) must be false — null means legacy/unspecified, not unambiguously terminal",
            ContinuityRecoveryContext.isTerminalExecution(null)
        )
    }

    // ── ContinuityRecoveryContext — isKnownInterruptionReason ─────────────────

    @Test
    fun `isKnownInterruptionReason returns true for all canonical values`() {
        assertTrue(ContinuityRecoveryContext.isKnownInterruptionReason(ContinuityRecoveryContext.REASON_RECONNECT))
        assertTrue(ContinuityRecoveryContext.isKnownInterruptionReason(ContinuityRecoveryContext.REASON_HANDOFF))
        assertTrue(ContinuityRecoveryContext.isKnownInterruptionReason(ContinuityRecoveryContext.REASON_DEVICE_PAUSE))
        assertTrue(ContinuityRecoveryContext.isKnownInterruptionReason(ContinuityRecoveryContext.REASON_TRANSPORT_DEGRADED))
    }

    @Test
    fun `isKnownInterruptionReason returns false for null`() {
        assertFalse(
            "null interruption_reason (non-recovery dispatch) must not be considered known",
            ContinuityRecoveryContext.isKnownInterruptionReason(null)
        )
    }

    @Test
    fun `isKnownInterruptionReason returns false for unknown future value`() {
        assertFalse(
            "Unknown / future reason must return false; callers must tolerate it without rejection",
            ContinuityRecoveryContext.isKnownInterruptionReason("future_reason_type")
        )
    }

    // ── ContinuityRecoveryContext — isTransportInterruption ───────────────────

    @Test
    fun `isTransportInterruption returns true for reconnect`() {
        assertTrue(ContinuityRecoveryContext.isTransportInterruption(ContinuityRecoveryContext.REASON_RECONNECT))
    }

    @Test
    fun `isTransportInterruption returns true for transport_degraded`() {
        assertTrue(ContinuityRecoveryContext.isTransportInterruption(ContinuityRecoveryContext.REASON_TRANSPORT_DEGRADED))
    }

    @Test
    fun `isTransportInterruption returns false for handoff`() {
        assertFalse(ContinuityRecoveryContext.isTransportInterruption(ContinuityRecoveryContext.REASON_HANDOFF))
    }

    @Test
    fun `isTransportInterruption returns false for device_pause`() {
        assertFalse(ContinuityRecoveryContext.isTransportInterruption(ContinuityRecoveryContext.REASON_DEVICE_PAUSE))
    }

    @Test
    fun `isTransportInterruption returns false for null`() {
        assertFalse(ContinuityRecoveryContext.isTransportInterruption(null))
    }

    @Test
    fun `isTransportInterruption returns false for unknown value`() {
        assertFalse(ContinuityRecoveryContext.isTransportInterruption("future_reason"))
    }

    // ── GoalExecutionPayload — PR-F continuity/recovery fields ────────────────

    @Test
    fun `GoalExecutionPayload continuity_token defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-001", goal = "test")
        assertNull(
            "continuity_token must default to null for backward compatibility",
            payload.continuity_token
        )
    }

    @Test
    fun `GoalExecutionPayload recovery_context defaults to empty map`() {
        val payload = GoalExecutionPayload(task_id = "t-002", goal = "test")
        assertTrue(
            "recovery_context must default to empty map for backward compatibility",
            payload.recovery_context.isEmpty()
        )
    }

    @Test
    fun `GoalExecutionPayload is_resumable defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-003", goal = "test")
        assertNull(
            "is_resumable must default to null for backward compatibility",
            payload.is_resumable
        )
    }

    @Test
    fun `GoalExecutionPayload interruption_reason defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-004", goal = "test")
        assertNull(
            "interruption_reason must default to null for backward compatibility",
            payload.interruption_reason
        )
    }

    @Test
    fun `GoalExecutionPayload accepts all PR-F fields simultaneously`() {
        val payload = GoalExecutionPayload(
            task_id = "t-prf-full",
            goal = "resume task",
            continuity_token = "ctoken-abc123",
            recovery_context = mapOf("checkpoint" to "step-3", "policy" to "retry"),
            is_resumable = true,
            interruption_reason = ContinuityRecoveryContext.REASON_RECONNECT
        )

        assertEquals("ctoken-abc123", payload.continuity_token)
        assertEquals(2, payload.recovery_context.size)
        assertEquals("step-3", payload.recovery_context["checkpoint"])
        assertEquals(true, payload.is_resumable)
        assertEquals(ContinuityRecoveryContext.REASON_RECONNECT, payload.interruption_reason)
    }

    @Test
    fun `GoalExecutionPayload effectiveTimeoutMs is unaffected by PR-F fields`() {
        val payload = GoalExecutionPayload(
            task_id = "t-timeout",
            goal = "test",
            timeout_ms = 60_000L,
            continuity_token = "ctoken-xyz",
            is_resumable = true,
            interruption_reason = ContinuityRecoveryContext.REASON_HANDOFF
        )
        assertEquals(60_000L, payload.effectiveTimeoutMs)
    }

    // ── TaskAssignPayload — PR-F continuity/recovery fields ───────────────────

    @Test
    fun `TaskAssignPayload continuity_token defaults to null`() {
        val payload = TaskAssignPayload(
            task_id = "ta-001",
            goal = "test",
            max_steps = 5,
            require_local_agent = true
        )
        assertNull(
            "TaskAssignPayload continuity_token must default to null for backward compatibility",
            payload.continuity_token
        )
    }

    @Test
    fun `TaskAssignPayload recovery_context defaults to empty map`() {
        val payload = TaskAssignPayload(
            task_id = "ta-002",
            goal = "test",
            max_steps = 5,
            require_local_agent = true
        )
        assertTrue(
            "TaskAssignPayload recovery_context must default to empty map",
            payload.recovery_context.isEmpty()
        )
    }

    @Test
    fun `TaskAssignPayload is_resumable defaults to null`() {
        val payload = TaskAssignPayload(
            task_id = "ta-003",
            goal = "test",
            max_steps = 5,
            require_local_agent = true
        )
        assertNull(
            "TaskAssignPayload is_resumable must default to null for backward compatibility",
            payload.is_resumable
        )
    }

    @Test
    fun `TaskAssignPayload interruption_reason defaults to null`() {
        val payload = TaskAssignPayload(
            task_id = "ta-004",
            goal = "test",
            max_steps = 5,
            require_local_agent = true
        )
        assertNull(
            "TaskAssignPayload interruption_reason must default to null for backward compatibility",
            payload.interruption_reason
        )
    }

    @Test
    fun `TaskAssignPayload accepts all PR-F fields simultaneously`() {
        val payload = TaskAssignPayload(
            task_id = "ta-prf-full",
            goal = "resume task",
            max_steps = 10,
            require_local_agent = false,
            continuity_token = "ctoken-ta-001",
            recovery_context = mapOf("hint" to "step-2"),
            is_resumable = true,
            interruption_reason = ContinuityRecoveryContext.REASON_DEVICE_PAUSE
        )

        assertEquals("ctoken-ta-001", payload.continuity_token)
        assertEquals(1, payload.recovery_context.size)
        assertEquals("step-2", payload.recovery_context["hint"])
        assertEquals(true, payload.is_resumable)
        assertEquals(ContinuityRecoveryContext.REASON_DEVICE_PAUSE, payload.interruption_reason)
    }

    // ── TakeoverRequestEnvelope — PR-F continuity/recovery fields ─────────────

    @Test
    fun `TakeoverRequestEnvelope continuity_token defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-001",
            task_id = "task-001",
            trace_id = "trace-001",
            goal = "continue task"
        )
        assertNull(
            "TakeoverRequestEnvelope continuity_token must default to null for backward compatibility",
            envelope.continuity_token
        )
    }

    @Test
    fun `TakeoverRequestEnvelope recovery_context defaults to empty map`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-002",
            task_id = "task-002",
            trace_id = "trace-002",
            goal = "continue task"
        )
        assertTrue(
            "TakeoverRequestEnvelope recovery_context must default to empty map",
            envelope.recovery_context.isEmpty()
        )
    }

    @Test
    fun `TakeoverRequestEnvelope is_resumable defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-003",
            task_id = "task-003",
            trace_id = "trace-003",
            goal = "continue task"
        )
        assertNull(
            "TakeoverRequestEnvelope is_resumable must default to null for backward compatibility",
            envelope.is_resumable
        )
    }

    @Test
    fun `TakeoverRequestEnvelope interruption_reason defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-004",
            task_id = "task-004",
            trace_id = "trace-004",
            goal = "continue task"
        )
        assertNull(
            "TakeoverRequestEnvelope interruption_reason must default to null for backward compatibility",
            envelope.interruption_reason
        )
    }

    @Test
    fun `TakeoverRequestEnvelope accepts all PR-F fields simultaneously`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-prf",
            task_id = "task-prf",
            trace_id = "trace-prf",
            goal = "resume handoff",
            continuity_token = "ctoken-to-prf",
            recovery_context = mapOf("last_step" to "5"),
            is_resumable = true,
            interruption_reason = ContinuityRecoveryContext.REASON_HANDOFF
        )

        assertEquals("ctoken-to-prf", envelope.continuity_token)
        assertEquals("5", envelope.recovery_context["last_step"])
        assertEquals(true, envelope.is_resumable)
        assertEquals(ContinuityRecoveryContext.REASON_HANDOFF, envelope.interruption_reason)
    }

    @Test
    fun `TakeoverRequestEnvelope PR-F fields do not affect resolvedPosture`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-posture",
            task_id = "task-posture",
            trace_id = "trace-posture",
            goal = "test posture",
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME,
            continuity_token = "some-token",
            is_resumable = true,
            interruption_reason = ContinuityRecoveryContext.REASON_RECONNECT
        )

        assertEquals(
            "resolvedPosture must be unaffected by PR-F fields",
            SourceRuntimePosture.JOIN_RUNTIME,
            envelope.resolvedPosture
        )
    }

    // ── HandoffEnvelopeV2 — PR-F continuity/recovery fields ───────────────────

    @Test
    fun `toEnvelopeV2 propagates continuity_token from HandoffRequest`() {
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-env",
            taskId = "task-env",
            goal = "test",
            continuityToken = "ctoken-env-001"
        )
        val envelope = request.toEnvelopeV2()
        assertEquals(
            "toEnvelopeV2 must propagate continuity_token from HandoffRequest",
            "ctoken-env-001",
            envelope.continuity_token
        )
    }

    @Test
    fun `toEnvelopeV2 propagates recovery_context from HandoffRequest`() {
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-env2",
            taskId = "task-env2",
            goal = "test",
            recoveryContext = mapOf("hint" to "step-4")
        )
        val envelope = request.toEnvelopeV2()
        assertEquals(
            "toEnvelopeV2 must propagate recovery_context from HandoffRequest",
            mapOf("hint" to "step-4"),
            envelope.recovery_context
        )
    }

    @Test
    fun `toEnvelopeV2 propagates is_resumable from HandoffRequest`() {
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-env3",
            taskId = "task-env3",
            goal = "test",
            isResumable = true
        )
        val envelope = request.toEnvelopeV2()
        assertEquals(
            "toEnvelopeV2 must propagate is_resumable=true from HandoffRequest",
            true,
            envelope.is_resumable
        )
    }

    @Test
    fun `toEnvelopeV2 propagates interruption_reason from HandoffRequest`() {
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-env4",
            taskId = "task-env4",
            goal = "test",
            interruptionReason = ContinuityRecoveryContext.REASON_RECONNECT
        )
        val envelope = request.toEnvelopeV2()
        assertEquals(
            "toEnvelopeV2 must propagate interruption_reason from HandoffRequest",
            ContinuityRecoveryContext.REASON_RECONNECT,
            envelope.interruption_reason
        )
    }

    @Test
    fun `toEnvelopeV2 produces null continuity fields when HandoffRequest has none`() {
        val request = AgentRuntimeBridge.HandoffRequest(
            traceId = "trace-legacy",
            taskId = "task-legacy",
            goal = "legacy test"
            // No PR-F fields — all default to null/empty
        )
        val envelope = request.toEnvelopeV2()
        assertNull(
            "Legacy HandoffRequest must produce null continuity_token in envelope",
            envelope.continuity_token
        )
        assertTrue(
            "Legacy HandoffRequest must produce empty recovery_context in envelope",
            envelope.recovery_context.isEmpty()
        )
        assertNull(
            "Legacy HandoffRequest must produce null is_resumable in envelope",
            envelope.is_resumable
        )
        assertNull(
            "Legacy HandoffRequest must produce null interruption_reason in envelope",
            envelope.interruption_reason
        )
    }

    // ── AutonomousExecutionPipeline — PR-F field acceptance (goal_execution) ───

    @Test
    fun `goal_execution accepts payload with continuity_token set`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(continuityToken = "ctoken-accept"))
        assertNotNull("Pipeline must not fail with continuity_token set", result)
    }

    @Test
    fun `goal_execution accepts payload with recovery_context set`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(recoveryContext = mapOf("key" to "val"))
        )
        assertNotNull("Pipeline must not fail with recovery_context set", result)
    }

    @Test
    fun `goal_execution accepts payload with is_resumable=true`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = true))
        assertNotNull("Pipeline must not fail with is_resumable=true", result)
        assertNotEquals(
            "is_resumable=true must NOT block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution accepts payload with is_resumable=false`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = false))
        assertNotNull("Pipeline must not fail with is_resumable=false", result)
        assertNotEquals(
            "is_resumable=false must NOT block execution",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `goal_execution accepts payload with all PR-F fields set`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            goalPayload(
                continuityToken = "full-ctoken",
                recoveryContext = mapOf("cp" to "step-2", "policy" to "retry"),
                isResumable = true,
                interruptionReason = ContinuityRecoveryContext.REASON_RECONNECT
            )
        )
        assertNotNull("Pipeline must not fail with all PR-F fields set", result)
    }

    @Test
    fun `goal_execution accepts payload with no PR-F fields (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(
            GoalExecutionPayload(
                task_id = "t-legacy",
                goal = "legacy goal",
                max_steps = 5,
                source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
                // No PR-F fields — backward compatibility
            )
        )
        assertNotNull("Legacy payload (no PR-F fields) must not fail", result)
    }

    // ── AutonomousExecutionPipeline — continuity/recovery echoed in results ────

    @Test
    fun `goal_execution success result echoes continuity_token from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(continuityToken = "echo-ctoken-001"))
        assertEquals(
            "Success result must echo continuity_token for full-chain V2 correlation",
            "echo-ctoken-001",
            result.continuity_token
        )
    }

    @Test
    fun `goal_execution success result echoes is_resumable=true from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = true))
        assertEquals(
            "Success result must echo is_resumable=true for full-chain V2 correlation",
            true,
            result.is_resumable
        )
    }

    @Test
    fun `goal_execution success result echoes null continuity_token from legacy payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(continuityToken = null))
        assertNull(
            "Legacy payload (null continuity_token) must result in null continuity_token in result",
            result.continuity_token
        )
    }

    @Test
    fun `goal_execution success result echoes null is_resumable from legacy payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = null))
        assertNull(
            "Legacy payload (null is_resumable) must result in null is_resumable in result",
            result.is_resumable
        )
    }

    @Test
    fun `goal_execution disabled result echoes continuity_token from payload`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(continuityToken = "disabled-ctoken"))
        assertEquals(
            "Disabled result must echo continuity_token for full-chain V2 correlation",
            "disabled-ctoken",
            result.continuity_token
        )
    }

    @Test
    fun `goal_execution disabled result echoes is_resumable=true from payload`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = true))
        assertEquals(
            "Disabled result must echo is_resumable=true so V2 knows Android reported resumable",
            true,
            result.is_resumable
        )
    }

    // ── AutonomousExecutionPipeline — resumable not collapsed into terminal ────

    @Test
    fun `is_resumable=true payload blocked by disabled gate returns disabled not error`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = true))
        assertEquals(
            "A blocked resumable execution must return STATUS_DISABLED, not an error status",
            AutonomousExecutionPipeline.STATUS_DISABLED,
            result.status
        )
    }

    @Test
    fun `disabled result for is_resumable=true preserves is_resumable=true`() {
        val pipeline = buildPipeline(goalExecutionEnabled = false)
        val result = pipeline.handleGoalExecution(goalPayload(isResumable = true))
        assertEquals(
            "Android MUST NOT collapse is_resumable=true into false in disabled results",
            true,
            result.is_resumable
        )
    }

    // ── AutonomousExecutionPipeline — continuity in parallel_subtask results ───

    @Test
    fun `parallel_subtask success result echoes continuity_token from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                continuityToken = "parallel-ctoken",
                groupId = "grp-prf",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel success result must echo continuity_token for full-chain V2 correlation",
            "parallel-ctoken",
            result.continuity_token
        )
    }

    @Test
    fun `parallel_subtask success result echoes is_resumable from payload`() {
        val pipeline = buildPipeline()
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                isResumable = true,
                groupId = "grp-prf",
                subtaskIndex = 1
            )
        )
        assertEquals(
            "Parallel success result must echo is_resumable for full-chain V2 correlation",
            true,
            result.is_resumable
        )
    }

    @Test
    fun `parallel_subtask disabled result echoes continuity_token from payload`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                continuityToken = "parallel-disabled-ctoken",
                groupId = "grp-prf",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel disabled result must echo continuity_token",
            "parallel-disabled-ctoken",
            result.continuity_token
        )
    }

    @Test
    fun `parallel_subtask disabled result echoes is_resumable from payload`() {
        val pipeline = buildPipeline(parallelExecutionEnabled = false)
        val result = pipeline.handleParallelSubtask(
            goalPayload(
                isResumable = true,
                groupId = "grp-prf",
                subtaskIndex = 0
            )
        )
        assertEquals(
            "Parallel disabled result must echo is_resumable=true",
            true,
            result.is_resumable
        )
    }
}
