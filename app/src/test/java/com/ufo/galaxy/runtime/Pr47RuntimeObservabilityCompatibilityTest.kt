package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-47 — Android-side observability compatibility for runtime lifecycle, dispatch,
 * and recovery metadata.
 *
 * V2 is adding production-grade runtime observability across device lifecycle, mesh
 * session transitions, dispatch decisions, executor target typing, and continuity/recovery
 * decisions.  This test suite validates all Android-side compatibility and semantic clarity
 * requirements defined by PR-G.
 *
 * ## Sections
 *
 * ### RuntimeObservabilityMetadata — field name constants
 *  - FIELD_DISPATCH_TRACE_ID wire value is `"dispatch_trace_id"`
 *  - FIELD_LIFECYCLE_EVENT_ID wire value is `"lifecycle_event_id"`
 *  - FIELD_SESSION_CORRELATION_ID wire value is `"session_correlation_id"`
 *
 * ### RuntimeObservabilityMetadata — log event kind constants
 *  - EVENT_DISPATCH_DECISION_RECORDED is `"dispatch_decision_recorded"`
 *  - EVENT_LIFECYCLE_OBSERVE_EMITTED is `"lifecycle_observe_emitted"`
 *  - EVENT_RECOVERY_OBSERVE_RECORDED is `"recovery_observe_recorded"`
 *
 * ### RuntimeObservabilityMetadata — INTRODUCED_PR
 *  - INTRODUCED_PR is 47
 *
 * ### RuntimeObservabilityMetadata.LifecycleObservabilityKind — wire values
 *  - DEVICE_ATTACH wire value is `"device_attach"`
 *  - DEVICE_RECONNECT wire value is `"device_reconnect"`
 *  - DEVICE_DETACH wire value is `"device_detach"`
 *  - DEVICE_DEGRADED wire value is `"device_degraded"`
 *  - READINESS_CHANGED wire value is `"readiness_changed"`
 *  - HEALTH_CHANGED wire value is `"health_changed"`
 *
 * ### RuntimeObservabilityMetadata.LifecycleObservabilityKind — ALL_WIRE_VALUES
 *  - ALL_WIRE_VALUES contains exactly six entries
 *  - ALL_WIRE_VALUES contains every canonical wire value
 *  - All six wire values are distinct
 *
 * ### RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue — known values
 *  - fromValue returns DEVICE_ATTACH for canonical string
 *  - fromValue returns DEVICE_RECONNECT for canonical string
 *  - fromValue returns DEVICE_DETACH for canonical string
 *  - fromValue returns DEVICE_DEGRADED for canonical string
 *  - fromValue returns READINESS_CHANGED for canonical string
 *  - fromValue returns HEALTH_CHANGED for canonical string
 *
 * ### RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue — safe-unknown handling
 *  - fromValue returns null for null
 *  - fromValue returns null for blank string
 *  - fromValue returns null for unknown string
 *  - fromValue returns null for mixed-case variant
 *
 * ### RuntimeObservabilityMetadata — hasDispatchTraceId
 *  - hasDispatchTraceId returns false for null
 *  - hasDispatchTraceId returns false for blank string
 *  - hasDispatchTraceId returns true for non-blank string
 *
 * ### RuntimeObservabilityMetadata — hasLifecycleEventId
 *  - hasLifecycleEventId returns false for null
 *  - hasLifecycleEventId returns false for blank string
 *  - hasLifecycleEventId returns true for non-blank string
 *
 * ### RuntimeObservabilityMetadata — hasSessionCorrelationId
 *  - hasSessionCorrelationId returns false for null
 *  - hasSessionCorrelationId returns false for blank string
 *  - hasSessionCorrelationId returns true for non-blank string
 *
 * ### RuntimeObservabilityMetadata — resolveTraceId
 *  - resolveTraceId returns dispatch_trace_id when present
 *  - resolveTraceId returns session_correlation_id when dispatch_trace_id is null
 *  - resolveTraceId returns session_correlation_id when dispatch_trace_id is blank
 *  - resolveTraceId returns contextTraceId when both higher-priority IDs are null
 *  - resolveTraceId returns contextTraceId when both higher-priority IDs are blank
 *  - resolveTraceId always returns non-null (contextTraceId fallback is unconditional)
 *
 * ### GalaxyLogger — PR-G tag constants
 *  - TAG_DISPATCH_DECISION value is "GALAXY:DISPATCH:DECISION"
 *  - TAG_LIFECYCLE_OBSERVE value is "GALAXY:LIFECYCLE:OBSERVE"
 *  - TAG_RECOVERY_OBSERVE value is "GALAXY:RECOVERY:OBSERVE"
 *  - All three PR-G tags are distinct from each other
 *  - All three PR-G tags are distinct from all pre-PR-47 tags
 *
 * ### GoalExecutionPayload — PR-G observability fields
 *  - dispatch_trace_id defaults to null (backward compatibility)
 *  - lifecycle_event_id defaults to null (backward compatibility)
 *  - all PR-G fields can be set simultaneously
 *  - effectiveTimeoutMs is unaffected by PR-G fields
 *
 * ### TaskAssignPayload — PR-G observability fields
 *  - dispatch_trace_id defaults to null (backward compatibility)
 *  - lifecycle_event_id defaults to null (backward compatibility)
 *  - all PR-G fields can be set simultaneously on TaskAssignPayload
 *
 * ### AipMessage — PR-G observability fields
 *  - dispatch_trace_id defaults to null (backward compatibility)
 *  - session_correlation_id defaults to null (backward compatibility)
 *  - both PR-G fields can be set simultaneously
 *  - existing AipMessage fields are unaffected by PR-G additions
 *
 * ### GoalResultPayload — PR-G dispatch_trace_id echo field
 *  - dispatch_trace_id defaults to null (backward compatibility)
 *  - dispatch_trace_id can be set on GoalResultPayload
 *
 * ### AutonomousExecutionPipeline — observability field acceptance (goal_execution)
 *  - pipeline accepts payload with dispatch_trace_id set (no failure)
 *  - pipeline accepts payload with lifecycle_event_id set (no failure)
 *  - pipeline accepts payload with all PR-G fields set (no failure)
 *  - pipeline accepts payload with no PR-G fields (legacy backward compat)
 *  - disabled result echoes dispatch_trace_id from payload
 *  - success result echoes dispatch_trace_id from payload
 *  - disabled result echoes null dispatch_trace_id from legacy payload
 *
 * ### AutonomousExecutionPipeline — observability field acceptance (parallel_subtask)
 *  - parallel pipeline accepts payload with dispatch_trace_id set (no failure)
 *  - parallel pipeline echoes dispatch_trace_id in disabled result
 *  - parallel pipeline echoes dispatch_trace_id in success result
 *
 * ### StabilizationBaseline — PR-47 entries registered
 *  - runtime-observability-metadata is registered as CANONICAL_STABLE
 *  - goal-execution-payload-observability-fields is registered as CANONICAL_STABLE
 *  - task-assign-payload-observability-fields is registered as CANONICAL_STABLE
 *  - aip-message-observability-fields is registered as CANONICAL_STABLE
 *  - galaxy-logger-tag-dispatch-decision is registered as CANONICAL_STABLE
 *  - galaxy-logger-tag-lifecycle-observe is registered as CANONICAL_STABLE
 *  - galaxy-logger-tag-recovery-observe is registered as CANONICAL_STABLE
 *  - all PR-47 entries have introducedPr = 47
 *  - all PR-47 entries have EXTEND guidance
 */
class Pr47RuntimeObservabilityCompatibilityTest {

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
        taskId: String = "t-prg",
        goal: String = "open WeChat",
        posture: String? = SourceRuntimePosture.JOIN_RUNTIME,
        dispatchTraceId: String? = null,
        lifecycleEventId: String? = null,
        groupId: String? = null,
        subtaskIndex: Int? = null
    ) = GoalExecutionPayload(
        task_id = taskId,
        goal = goal,
        group_id = groupId,
        subtask_index = subtaskIndex,
        max_steps = 5,
        source_runtime_posture = posture,
        dispatch_trace_id = dispatchTraceId,
        lifecycle_event_id = lifecycleEventId
    )

    // ── RuntimeObservabilityMetadata — field name constants ───────────────────

    @Test
    fun `FIELD_DISPATCH_TRACE_ID wire value is dispatch_trace_id`() {
        assertEquals("dispatch_trace_id", RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID)
    }

    @Test
    fun `FIELD_LIFECYCLE_EVENT_ID wire value is lifecycle_event_id`() {
        assertEquals("lifecycle_event_id", RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID)
    }

    @Test
    fun `FIELD_SESSION_CORRELATION_ID wire value is session_correlation_id`() {
        assertEquals("session_correlation_id", RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID)
    }

    // ── RuntimeObservabilityMetadata — log event kind constants ──────────────

    @Test
    fun `EVENT_DISPATCH_DECISION_RECORDED is dispatch_decision_recorded`() {
        assertEquals(
            "dispatch_decision_recorded",
            RuntimeObservabilityMetadata.EVENT_DISPATCH_DECISION_RECORDED
        )
    }

    @Test
    fun `EVENT_LIFECYCLE_OBSERVE_EMITTED is lifecycle_observe_emitted`() {
        assertEquals(
            "lifecycle_observe_emitted",
            RuntimeObservabilityMetadata.EVENT_LIFECYCLE_OBSERVE_EMITTED
        )
    }

    @Test
    fun `EVENT_RECOVERY_OBSERVE_RECORDED is recovery_observe_recorded`() {
        assertEquals(
            "recovery_observe_recorded",
            RuntimeObservabilityMetadata.EVENT_RECOVERY_OBSERVE_RECORDED
        )
    }

    // ── RuntimeObservabilityMetadata — INTRODUCED_PR ─────────────────────────

    @Test
    fun `INTRODUCED_PR is 47`() {
        assertEquals(
            "RuntimeObservabilityMetadata must be introduced at PR-47",
            47,
            RuntimeObservabilityMetadata.INTRODUCED_PR
        )
    }

    // ── LifecycleObservabilityKind — wire values ──────────────────────────────

    @Test
    fun `DEVICE_ATTACH wire value is device_attach`() {
        assertEquals(
            "device_attach",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_ATTACH.wireValue
        )
    }

    @Test
    fun `DEVICE_RECONNECT wire value is device_reconnect`() {
        assertEquals(
            "device_reconnect",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_RECONNECT.wireValue
        )
    }

    @Test
    fun `DEVICE_DETACH wire value is device_detach`() {
        assertEquals(
            "device_detach",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_DETACH.wireValue
        )
    }

    @Test
    fun `DEVICE_DEGRADED wire value is device_degraded`() {
        assertEquals(
            "device_degraded",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_DEGRADED.wireValue
        )
    }

    @Test
    fun `READINESS_CHANGED wire value is readiness_changed`() {
        assertEquals(
            "readiness_changed",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.READINESS_CHANGED.wireValue
        )
    }

    @Test
    fun `HEALTH_CHANGED wire value is health_changed`() {
        assertEquals(
            "health_changed",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.HEALTH_CHANGED.wireValue
        )
    }

    // ── LifecycleObservabilityKind — ALL_WIRE_VALUES ──────────────────────────

    @Test
    fun `ALL_WIRE_VALUES contains exactly six entries`() {
        assertEquals(
            "ALL_WIRE_VALUES must have exactly 6 entries — one per LifecycleObservabilityKind",
            6,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains every canonical wire value`() {
        val all = RuntimeObservabilityMetadata.LifecycleObservabilityKind.ALL_WIRE_VALUES
        assertTrue(all.contains("device_attach"))
        assertTrue(all.contains("device_reconnect"))
        assertTrue(all.contains("device_detach"))
        assertTrue(all.contains("device_degraded"))
        assertTrue(all.contains("readiness_changed"))
        assertTrue(all.contains("health_changed"))
    }

    @Test
    fun `All six LifecycleObservabilityKind wire values are distinct`() {
        val wireValues = RuntimeObservabilityMetadata.LifecycleObservabilityKind.entries
            .map { it.wireValue }
        assertEquals(
            "All wire values must be unique — duplicates would break V2 observability routing",
            wireValues.toSet().size,
            wireValues.size
        )
    }

    // ── LifecycleObservabilityKind.fromValue — known values ───────────────────

    @Test
    fun `fromValue returns DEVICE_ATTACH for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_ATTACH,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("device_attach")
        )
    }

    @Test
    fun `fromValue returns DEVICE_RECONNECT for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_RECONNECT,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("device_reconnect")
        )
    }

    @Test
    fun `fromValue returns DEVICE_DETACH for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_DETACH,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("device_detach")
        )
    }

    @Test
    fun `fromValue returns DEVICE_DEGRADED for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.DEVICE_DEGRADED,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("device_degraded")
        )
    }

    @Test
    fun `fromValue returns READINESS_CHANGED for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.READINESS_CHANGED,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("readiness_changed")
        )
    }

    @Test
    fun `fromValue returns HEALTH_CHANGED for canonical string`() {
        assertEquals(
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.HEALTH_CHANGED,
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("health_changed")
        )
    }

    // ── LifecycleObservabilityKind.fromValue — safe-unknown handling ──────────

    @Test
    fun `fromValue returns null for null`() {
        assertNull(
            "null must return null — no lifecycle kind can be resolved",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue(null)
        )
    }

    @Test
    fun `fromValue returns null for blank string`() {
        assertNull(
            "blank string must return null",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("")
        )
    }

    @Test
    fun `fromValue returns null for unknown string`() {
        assertNull(
            "Unknown / future kind must return null; callers must tolerate it without rejection",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("future_lifecycle_kind")
        )
    }

    @Test
    fun `fromValue returns null for mixed-case variant`() {
        assertNull(
            "Wire format is strictly lower-snake-case; mixed-case must not match",
            RuntimeObservabilityMetadata.LifecycleObservabilityKind.fromValue("Device_Attach")
        )
    }

    // ── RuntimeObservabilityMetadata — hasDispatchTraceId ────────────────────

    @Test
    fun `hasDispatchTraceId returns false for null`() {
        assertFalse(
            "null dispatch_trace_id means legacy sender — should return false",
            RuntimeObservabilityMetadata.hasDispatchTraceId(null)
        )
    }

    @Test
    fun `hasDispatchTraceId returns false for blank string`() {
        assertFalse(
            "blank dispatch_trace_id is treated as null (not provided)",
            RuntimeObservabilityMetadata.hasDispatchTraceId("   ")
        )
    }

    @Test
    fun `hasDispatchTraceId returns true for non-blank string`() {
        assertTrue(
            "Non-blank dispatch_trace_id should return true",
            RuntimeObservabilityMetadata.hasDispatchTraceId("dt-abc-123")
        )
    }

    // ── RuntimeObservabilityMetadata — hasLifecycleEventId ───────────────────

    @Test
    fun `hasLifecycleEventId returns false for null`() {
        assertFalse(
            "null lifecycle_event_id means no lifecycle-triggered dispatch",
            RuntimeObservabilityMetadata.hasLifecycleEventId(null)
        )
    }

    @Test
    fun `hasLifecycleEventId returns false for blank string`() {
        assertFalse(
            "blank lifecycle_event_id is treated as null",
            RuntimeObservabilityMetadata.hasLifecycleEventId("")
        )
    }

    @Test
    fun `hasLifecycleEventId returns true for non-blank string`() {
        assertTrue(
            "Non-blank lifecycle_event_id should return true",
            RuntimeObservabilityMetadata.hasLifecycleEventId("le-xyz-789")
        )
    }

    // ── RuntimeObservabilityMetadata — hasSessionCorrelationId ───────────────

    @Test
    fun `hasSessionCorrelationId returns false for null`() {
        assertFalse(
            "null session_correlation_id means no session-level correlation context",
            RuntimeObservabilityMetadata.hasSessionCorrelationId(null)
        )
    }

    @Test
    fun `hasSessionCorrelationId returns false for blank string`() {
        assertFalse(
            "blank session_correlation_id is treated as null",
            RuntimeObservabilityMetadata.hasSessionCorrelationId("")
        )
    }

    @Test
    fun `hasSessionCorrelationId returns true for non-blank string`() {
        assertTrue(
            "Non-blank session_correlation_id should return true",
            RuntimeObservabilityMetadata.hasSessionCorrelationId("sc-correlation-456")
        )
    }

    // ── RuntimeObservabilityMetadata — resolveTraceId ────────────────────────

    @Test
    fun `resolveTraceId returns dispatch_trace_id when present`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = "dt-priority",
            sessionTraceId = "sc-fallback",
            contextTraceId = "ctx-default"
        )
        assertEquals(
            "dispatch_trace_id is most specific and should take priority",
            "dt-priority",
            result
        )
    }

    @Test
    fun `resolveTraceId returns session_correlation_id when dispatch_trace_id is null`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = null,
            sessionTraceId = "sc-fallback",
            contextTraceId = "ctx-default"
        )
        assertEquals(
            "session_correlation_id should be used when dispatch_trace_id is not available",
            "sc-fallback",
            result
        )
    }

    @Test
    fun `resolveTraceId returns session_correlation_id when dispatch_trace_id is blank`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = "   ",
            sessionTraceId = "sc-fallback",
            contextTraceId = "ctx-default"
        )
        assertEquals(
            "blank dispatch_trace_id must be skipped; session_correlation_id used instead",
            "sc-fallback",
            result
        )
    }

    @Test
    fun `resolveTraceId returns contextTraceId when both higher-priority IDs are null`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = null,
            sessionTraceId = null,
            contextTraceId = "ctx-default"
        )
        assertEquals(
            "contextTraceId is the unconditional fallback",
            "ctx-default",
            result
        )
    }

    @Test
    fun `resolveTraceId returns contextTraceId when both higher-priority IDs are blank`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = "",
            sessionTraceId = "",
            contextTraceId = "ctx-default"
        )
        assertEquals(
            "blank IDs must be treated as absent; contextTraceId fallback applied",
            "ctx-default",
            result
        )
    }

    @Test
    fun `resolveTraceId always returns non-null via contextTraceId fallback`() {
        val result = RuntimeObservabilityMetadata.resolveTraceId(
            dispatchTraceId = null,
            sessionTraceId = null,
            contextTraceId = "ctx-non-null"
        )
        assertNotNull(
            "resolveTraceId must never return null — contextTraceId fallback is unconditional",
            result
        )
    }

    // ── GalaxyLogger — PR-G tag constants ────────────────────────────────────

    @Test
    fun `TAG_DISPATCH_DECISION value is GALAXY DISPATCH DECISION`() {
        assertEquals("GALAXY:DISPATCH:DECISION", GalaxyLogger.TAG_DISPATCH_DECISION)
    }

    @Test
    fun `TAG_LIFECYCLE_OBSERVE value is GALAXY LIFECYCLE OBSERVE`() {
        assertEquals("GALAXY:LIFECYCLE:OBSERVE", GalaxyLogger.TAG_LIFECYCLE_OBSERVE)
    }

    @Test
    fun `TAG_RECOVERY_OBSERVE value is GALAXY RECOVERY OBSERVE`() {
        assertEquals("GALAXY:RECOVERY:OBSERVE", GalaxyLogger.TAG_RECOVERY_OBSERVE)
    }

    @Test
    fun `All three PR-G tags are distinct from each other`() {
        val prGTags = setOf(
            GalaxyLogger.TAG_DISPATCH_DECISION,
            GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            GalaxyLogger.TAG_RECOVERY_OBSERVE
        )
        assertEquals(
            "All three PR-G GalaxyLogger tags must be unique — duplicates would break observability routing",
            3,
            prGTags.size
        )
    }

    @Test
    fun `All three PR-G tags are distinct from all pre-PR-47 tags`() {
        val prGTags = listOf(
            GalaxyLogger.TAG_DISPATCH_DECISION,
            GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            GalaxyLogger.TAG_RECOVERY_OBSERVE
        )
        val prePR47Tags = listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_READINESS,
            GalaxyLogger.TAG_DEGRADED,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_RUNTIME_LIFECYCLE,
            GalaxyLogger.TAG_V2_LIFECYCLE
        )
        prGTags.forEach { tag ->
            assertFalse(
                "PR-G tag '$tag' must not duplicate any pre-PR-47 tag",
                prePR47Tags.contains(tag)
            )
        }
    }

    // ── GoalExecutionPayload — PR-G observability fields ─────────────────────

    @Test
    fun `GoalExecutionPayload dispatch_trace_id defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-001", goal = "test")
        assertNull(
            "dispatch_trace_id must default to null for backward compatibility",
            payload.dispatch_trace_id
        )
    }

    @Test
    fun `GoalExecutionPayload lifecycle_event_id defaults to null`() {
        val payload = GoalExecutionPayload(task_id = "t-002", goal = "test")
        assertNull(
            "lifecycle_event_id must default to null for backward compatibility",
            payload.lifecycle_event_id
        )
    }

    @Test
    fun `GoalExecutionPayload accepts all PR-G fields simultaneously`() {
        val payload = GoalExecutionPayload(
            task_id = "t-003",
            goal = "test",
            dispatch_trace_id = "dt-abc",
            lifecycle_event_id = "le-xyz"
        )
        assertEquals("dt-abc", payload.dispatch_trace_id)
        assertEquals("le-xyz", payload.lifecycle_event_id)
    }

    @Test
    fun `GoalExecutionPayload effectiveTimeoutMs is unaffected by PR-G fields`() {
        val legacyPayload = GoalExecutionPayload(task_id = "t-004", goal = "test")
        val observabilityPayload = GoalExecutionPayload(
            task_id = "t-005",
            goal = "test",
            dispatch_trace_id = "dt-abc",
            lifecycle_event_id = "le-xyz"
        )
        assertEquals(
            "PR-G fields must not affect effectiveTimeoutMs",
            legacyPayload.effectiveTimeoutMs,
            observabilityPayload.effectiveTimeoutMs
        )
    }

    // ── TaskAssignPayload — PR-G observability fields ─────────────────────────

    @Test
    fun `TaskAssignPayload dispatch_trace_id defaults to null`() {
        val payload = TaskAssignPayload(
            task_id = "t-ta-001",
            goal = "test",
            max_steps = 10,
            require_local_agent = false
        )
        assertNull(
            "dispatch_trace_id must default to null for backward compatibility",
            payload.dispatch_trace_id
        )
    }

    @Test
    fun `TaskAssignPayload lifecycle_event_id defaults to null`() {
        val payload = TaskAssignPayload(
            task_id = "t-ta-002",
            goal = "test",
            max_steps = 10,
            require_local_agent = false
        )
        assertNull(
            "lifecycle_event_id must default to null for backward compatibility",
            payload.lifecycle_event_id
        )
    }

    @Test
    fun `TaskAssignPayload accepts all PR-G fields simultaneously`() {
        val payload = TaskAssignPayload(
            task_id = "t-ta-003",
            goal = "test",
            max_steps = 10,
            require_local_agent = false,
            dispatch_trace_id = "dt-abc",
            lifecycle_event_id = "le-xyz"
        )
        assertEquals("dt-abc", payload.dispatch_trace_id)
        assertEquals("le-xyz", payload.lifecycle_event_id)
    }

    // ── AipMessage — PR-G observability fields ────────────────────────────────

    @Test
    fun `AipMessage dispatch_trace_id defaults to null`() {
        val msg = AipMessage(
            type = MsgType.TASK_ASSIGN,
            payload = "test"
        )
        assertNull(
            "dispatch_trace_id must default to null for backward compatibility",
            msg.dispatch_trace_id
        )
    }

    @Test
    fun `AipMessage session_correlation_id defaults to null`() {
        val msg = AipMessage(
            type = MsgType.TASK_ASSIGN,
            payload = "test"
        )
        assertNull(
            "session_correlation_id must default to null for backward compatibility",
            msg.session_correlation_id
        )
    }

    @Test
    fun `AipMessage accepts both PR-G fields simultaneously`() {
        val msg = AipMessage(
            type = MsgType.TASK_ASSIGN,
            payload = "test",
            dispatch_trace_id = "dt-msg-abc",
            session_correlation_id = "sc-msg-xyz"
        )
        assertEquals("dt-msg-abc", msg.dispatch_trace_id)
        assertEquals("sc-msg-xyz", msg.session_correlation_id)
    }

    @Test
    fun `Existing AipMessage fields are unaffected by PR-G additions`() {
        val msg = AipMessage(
            type = MsgType.TASK_ASSIGN,
            payload = "test",
            trace_id = "trace-abc",
            session_id = "sess-xyz",
            device_id = "dev-123",
            dispatch_trace_id = "dt-new",
            session_correlation_id = "sc-new"
        )
        assertEquals(
            "Existing trace_id must be unaffected by PR-G fields",
            "trace-abc",
            msg.trace_id
        )
        assertEquals("sess-xyz", msg.session_id)
        assertEquals("dev-123", msg.device_id)
    }

    // ── GoalResultPayload — PR-G dispatch_trace_id echo field ────────────────

    @Test
    fun `GoalResultPayload dispatch_trace_id defaults to null`() {
        val result = GoalResultPayload(task_id = "t-r-001", status = "success")
        assertNull(
            "dispatch_trace_id must default to null for backward compatibility",
            result.dispatch_trace_id
        )
    }

    @Test
    fun `GoalResultPayload dispatch_trace_id can be set`() {
        val result = GoalResultPayload(
            task_id = "t-r-002",
            status = "success",
            dispatch_trace_id = "dt-echoed-abc"
        )
        assertEquals(
            "dispatch_trace_id should be preserved in the result for full-chain correlation",
            "dt-echoed-abc",
            result.dispatch_trace_id
        )
    }

    // ── AutonomousExecutionPipeline — observability field acceptance (goal_execution) ──

    @Test
    fun `pipeline accepts payload with dispatch_trace_id set (no failure)`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(dispatchTraceId = "dt-prg-001")
        val result = pipeline.handleGoalExecution(payload)
        assertNotNull("Result must not be null when dispatch_trace_id is present", result)
    }

    @Test
    fun `pipeline accepts payload with lifecycle_event_id set (no failure)`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(lifecycleEventId = "le-prg-001")
        val result = pipeline.handleGoalExecution(payload)
        assertNotNull("Result must not be null when lifecycle_event_id is present", result)
    }

    @Test
    fun `pipeline accepts payload with all PR-G fields set (no failure)`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(
            dispatchTraceId = "dt-prg-all",
            lifecycleEventId = "le-prg-all"
        )
        val result = pipeline.handleGoalExecution(payload)
        assertNotNull("Result must not be null when all PR-G fields are present", result)
    }

    @Test
    fun `pipeline accepts payload with no PR-G fields (legacy backward compat)`() {
        val pipeline = buildPipeline()
        val payload = goalPayload() // no dispatch_trace_id or lifecycle_event_id
        val result = pipeline.handleGoalExecution(payload)
        assertNotNull("Pipeline must work correctly with legacy payloads (no PR-G fields)", result)
    }

    @Test
    fun `disabled result echoes dispatch_trace_id from payload`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val payload = goalPayload(dispatchTraceId = "dt-echo-disabled")
        val result = pipeline.handleGoalExecution(payload)
        assertEquals(
            "dispatch_trace_id must be echoed in disabled results for full-chain correlation",
            "dt-echo-disabled",
            result.dispatch_trace_id
        )
    }

    @Test
    fun `success result echoes dispatch_trace_id from payload`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(dispatchTraceId = "dt-echo-success")
        val result = pipeline.handleGoalExecution(payload)
        assertEquals(
            "dispatch_trace_id must be echoed in success results for full-chain correlation",
            "dt-echo-success",
            result.dispatch_trace_id
        )
    }

    @Test
    fun `disabled result echoes null dispatch_trace_id from legacy payload`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val payload = goalPayload() // no dispatch_trace_id
        val result = pipeline.handleGoalExecution(payload)
        assertNull(
            "Legacy result must preserve null dispatch_trace_id — no value should be injected",
            result.dispatch_trace_id
        )
    }

    // ── AutonomousExecutionPipeline — observability field acceptance (parallel_subtask) ──

    @Test
    fun `parallel pipeline accepts payload with dispatch_trace_id set (no failure)`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(
            taskId = "t-parallel-prg",
            dispatchTraceId = "dt-parallel-001",
            groupId = "group-1",
            subtaskIndex = 0
        )
        val result = pipeline.handleParallelSubtask(payload)
        assertNotNull("Parallel result must not be null when dispatch_trace_id is present", result)
    }

    @Test
    fun `parallel pipeline echoes dispatch_trace_id in disabled result`() {
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val payload = goalPayload(
            taskId = "t-parallel-disabled",
            dispatchTraceId = "dt-parallel-disabled",
            groupId = "group-1",
            subtaskIndex = 0
        )
        val result = pipeline.handleParallelSubtask(payload)
        assertEquals(
            "dispatch_trace_id must be echoed in parallel disabled results",
            "dt-parallel-disabled",
            result.dispatch_trace_id
        )
    }

    @Test
    fun `parallel pipeline echoes dispatch_trace_id in success result`() {
        val pipeline = buildPipeline()
        val payload = goalPayload(
            taskId = "t-parallel-success",
            dispatchTraceId = "dt-parallel-success",
            groupId = "group-1",
            subtaskIndex = 0
        )
        val result = pipeline.handleParallelSubtask(payload)
        assertEquals(
            "dispatch_trace_id must be echoed in parallel success results",
            "dt-parallel-success",
            result.dispatch_trace_id
        )
    }

    // ── StabilizationBaseline — PR-47 entries registered ─────────────────────

    @Test
    fun `runtime-observability-metadata is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-observability-metadata")
        assertNotNull("runtime-observability-metadata must be registered in StabilizationBaseline", entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `goal-execution-payload-observability-fields is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("goal-execution-payload-observability-fields")
        assertNotNull("goal-execution-payload-observability-fields must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `task-assign-payload-observability-fields is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("task-assign-payload-observability-fields")
        assertNotNull("task-assign-payload-observability-fields must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `aip-message-observability-fields is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("aip-message-observability-fields")
        assertNotNull("aip-message-observability-fields must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `galaxy-logger-tag-dispatch-decision is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-dispatch-decision")
        assertNotNull("galaxy-logger-tag-dispatch-decision must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `galaxy-logger-tag-lifecycle-observe is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-lifecycle-observe")
        assertNotNull("galaxy-logger-tag-lifecycle-observe must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `galaxy-logger-tag-recovery-observe is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-recovery-observe")
        assertNotNull("galaxy-logger-tag-recovery-observe must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-47 entries have introducedPr equal to 47`() {
        val pr47Ids = listOf(
            "runtime-observability-metadata",
            "goal-execution-payload-observability-fields",
            "task-assign-payload-observability-fields",
            "aip-message-observability-fields",
            "galaxy-logger-tag-dispatch-decision",
            "galaxy-logger-tag-lifecycle-observe",
            "galaxy-logger-tag-recovery-observe"
        )
        pr47Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have introducedPr = 47",
                47,
                entry!!.introducedPr
            )
        }
    }

    @Test
    fun `all PR-47 entries have EXTEND guidance`() {
        val pr47Ids = listOf(
            "runtime-observability-metadata",
            "goal-execution-payload-observability-fields",
            "task-assign-payload-observability-fields",
            "aip-message-observability-fields",
            "galaxy-logger-tag-dispatch-decision",
            "galaxy-logger-tag-lifecycle-observe",
            "galaxy-logger-tag-recovery-observe"
        )
        pr47Ids.forEach { id ->
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry '$id' must be registered in StabilizationBaseline", entry)
            assertEquals(
                "Entry '$id' must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry!!.extensionGuidance
            )
        }
    }
}
