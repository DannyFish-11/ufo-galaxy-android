package com.ufo.galaxy.agent

import com.google.gson.Gson
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.HandoffEnvelopeV2ResultPayload
import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-H — Android native consumption of [HandoffEnvelopeV2].
 *
 * Validates all Android-side protocol requirements for the HandoffEnvelopeV2 inbound
 * consumption chain: wire-model parsing, contract mapping, [MsgType] registration,
 * outbound result/ACK payload construction, and failure-path behaviour.
 *
 * ## Test sections
 *
 * ### MsgType — HANDOFF_ENVELOPE_V2 registration
 *  - HANDOFF_ENVELOPE_V2 has wire value "handoff_envelope_v2"
 *  - HANDOFF_ENVELOPE_V2_RESULT has wire value "handoff_envelope_v2_result"
 *  - HANDOFF_ENVELOPE_V2 is in ADVANCED_TYPES
 *  - HANDOFF_ENVELOPE_V2_RESULT is NOT in ADVANCED_TYPES (uplink only)
 *  - fromValue("handoff_envelope_v2") resolves HANDOFF_ENVELOPE_V2
 *  - fromValue("handoff_envelope_v2_result") resolves HANDOFF_ENVELOPE_V2_RESULT
 *
 * ### HandoffEnvelopeV2 — JSON deserialization (full fields)
 *  - All required fields are parsed correctly from JSON
 *  - All optional PR-D / PR-E / PR-F / PR-48 fields are parsed when present
 *  - Unknown extra fields in the JSON are tolerated without throwing
 *  - Missing optional fields default to null / empty without throwing
 *
 * ### HandoffEnvelopeV2 — contract mapping to GoalExecutionPayload
 *  - task_id is preserved
 *  - goal is preserved
 *  - constraints are preserved
 *  - source_runtime_posture is mapped
 *  - execution_context is forwarded
 *  - executor_target_type is forwarded
 *  - continuity_token is forwarded
 *  - recovery_context is forwarded
 *  - is_resumable is forwarded
 *  - interruption_reason is forwarded
 *  - dispatch_plan_id is forwarded
 *  - source_dispatch_strategy is forwarded
 *
 * ### HandoffEnvelopeV2ResultPayload — construction (happy path)
 *  - status "success" is preserved
 *  - task_id and trace_id are echoed
 *  - correlation_id equals task_id
 *  - device_id is set
 *  - route_mode defaults to "cross_device"
 *  - All echoed optional fields are carried through correctly
 *
 * ### HandoffEnvelopeV2ResultPayload — construction (error path)
 *  - status "error" is preserved
 *  - error field is populated
 *  - result_summary is optional / null when absent
 *
 * ### HandoffEnvelopeV2ResultPayload — construction (parse-failure path)
 *  - status "error" and error starts with "bad_payload:"
 *  - echoed optional fields are null/empty for missing envelope
 *
 * ### AipMessage — outbound HANDOFF_ENVELOPE_V2_RESULT envelope
 *  - type is HANDOFF_ENVELOPE_V2_RESULT
 *  - correlation_id equals task_id
 *  - trace_id is populated
 *  - route_mode is "cross_device"
 *
 * ### Backward compatibility
 *  - HandoffEnvelopeV2 with only required fields is valid (all optional fields default safely)
 *  - HandoffEnvelopeV2ResultPayload with only required fields is valid
 */
class HandoffEnvelopeV2ConsumptionTest {

    private val gson = Gson()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fullEnvelope(
        traceId: String = "trace-001",
        taskId: String = "task-001",
        goal: String = "open the settings app",
        execMode: String = "remote",
        routeMode: String = "cross_device",
        capability: String? = "task_execution",
        sessionId: String? = "session-001",
        runtimeSessionId: String? = "rsid-abc",
        idempotencyKey: String? = "idem-001",
        sourceRuntimePosture: String? = "control_only",
        dispatchIntent: String? = "task_execute",
        dispatchOrigin: String? = "v2_orchestrator",
        orchestrationStage: String? = "stage_1",
        executionContext: Map<String, String> = mapOf("locale" to "zh-CN"),
        executorTargetType: String? = "android_device",
        dispatchPlanId: String? = "plan-xyz",
        sourceDispatchStrategy: String? = "remote_handoff",
        continuityToken: String? = "cont-tok-001",
        recoveryContext: Map<String, String> = mapOf("checkpoint" to "step_3"),
        isResumable: Boolean? = true,
        interruptionReason: String? = "reconnect"
    ) = HandoffEnvelopeV2(
        trace_id = traceId,
        task_id = taskId,
        goal = goal,
        exec_mode = execMode,
        route_mode = routeMode,
        capability = capability,
        session_id = sessionId,
        runtime_session_id = runtimeSessionId,
        idempotency_key = idempotencyKey,
        source_runtime_posture = sourceRuntimePosture,
        dispatch_intent = dispatchIntent,
        dispatch_origin = dispatchOrigin,
        orchestration_stage = orchestrationStage,
        execution_context = executionContext,
        executor_target_type = executorTargetType,
        dispatch_plan_id = dispatchPlanId,
        source_dispatch_strategy = sourceDispatchStrategy,
        continuity_token = continuityToken,
        recovery_context = recoveryContext,
        is_resumable = isResumable,
        interruption_reason = interruptionReason
    )

    private fun minimalEnvelope() = HandoffEnvelopeV2(
        trace_id = "trace-min",
        task_id = "task-min",
        goal = "minimal goal",
        exec_mode = "local",
        route_mode = "local"
    )

    // ── Section: MsgType — HANDOFF_ENVELOPE_V2 registration ──────────────────

    @Test
    fun `HANDOFF_ENVELOPE_V2 wire value is handoff_envelope_v2`() {
        assertEquals("handoff_envelope_v2", MsgType.HANDOFF_ENVELOPE_V2.value)
    }

    @Test
    fun `HANDOFF_ENVELOPE_V2_RESULT wire value is handoff_envelope_v2_result`() {
        assertEquals("handoff_envelope_v2_result", MsgType.HANDOFF_ENVELOPE_V2_RESULT.value)
    }

    @Test
    fun `HANDOFF_ENVELOPE_V2 is in ADVANCED_TYPES`() {
        assertTrue(
            "HANDOFF_ENVELOPE_V2 must be in ADVANCED_TYPES for backward-compat fallback routing",
            MsgType.HANDOFF_ENVELOPE_V2 in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `HANDOFF_ENVELOPE_V2_RESULT is not in ADVANCED_TYPES`() {
        assertFalse(
            "HANDOFF_ENVELOPE_V2_RESULT is an uplink-only type and must not appear in ADVANCED_TYPES",
            MsgType.HANDOFF_ENVELOPE_V2_RESULT in MsgType.ADVANCED_TYPES
        )
    }

    @Test
    fun `fromValue resolves handoff_envelope_v2`() {
        assertEquals(MsgType.HANDOFF_ENVELOPE_V2, MsgType.fromValue("handoff_envelope_v2"))
    }

    @Test
    fun `fromValue resolves handoff_envelope_v2_result`() {
        assertEquals(MsgType.HANDOFF_ENVELOPE_V2_RESULT, MsgType.fromValue("handoff_envelope_v2_result"))
    }

    @Test
    fun `fromValue returns null for unknown type`() {
        assertNull(MsgType.fromValue("handoff_envelope_v3_hypothetical"))
    }

    // ── Section: HandoffEnvelopeV2 — JSON deserialization ────────────────────

    @Test
    fun `full envelope JSON round-trips without loss`() {
        val original = fullEnvelope()
        val json = gson.toJson(original)
        val parsed = gson.fromJson(json, HandoffEnvelopeV2::class.java)
        assertEquals("trace_id round-trip", original.trace_id, parsed.trace_id)
        assertEquals("task_id round-trip", original.task_id, parsed.task_id)
        assertEquals("goal round-trip", original.goal, parsed.goal)
        assertEquals("exec_mode round-trip", original.exec_mode, parsed.exec_mode)
        assertEquals("route_mode round-trip", original.route_mode, parsed.route_mode)
        assertEquals("capability round-trip", original.capability, parsed.capability)
        assertEquals("session_id round-trip", original.session_id, parsed.session_id)
        assertEquals("runtime_session_id round-trip", original.runtime_session_id, parsed.runtime_session_id)
        assertEquals("source_runtime_posture round-trip", original.source_runtime_posture, parsed.source_runtime_posture)
        assertEquals("dispatch_intent round-trip", original.dispatch_intent, parsed.dispatch_intent)
        assertEquals("dispatch_origin round-trip", original.dispatch_origin, parsed.dispatch_origin)
        assertEquals("orchestration_stage round-trip", original.orchestration_stage, parsed.orchestration_stage)
        assertEquals("execution_context round-trip", original.execution_context, parsed.execution_context)
        assertEquals("executor_target_type round-trip", original.executor_target_type, parsed.executor_target_type)
        assertEquals("dispatch_plan_id round-trip", original.dispatch_plan_id, parsed.dispatch_plan_id)
        assertEquals("source_dispatch_strategy round-trip", original.source_dispatch_strategy, parsed.source_dispatch_strategy)
        assertEquals("continuity_token round-trip", original.continuity_token, parsed.continuity_token)
        assertEquals("recovery_context round-trip", original.recovery_context, parsed.recovery_context)
        assertEquals("is_resumable round-trip", original.is_resumable, parsed.is_resumable)
        assertEquals("interruption_reason round-trip", original.interruption_reason, parsed.interruption_reason)
    }

    @Test
    fun `minimal envelope JSON round-trips with safe defaults`() {
        val original = minimalEnvelope()
        val json = gson.toJson(original)
        val parsed = gson.fromJson(json, HandoffEnvelopeV2::class.java)
        assertEquals("trace_id", original.trace_id, parsed.trace_id)
        assertEquals("task_id", original.task_id, parsed.task_id)
        assertEquals("goal", original.goal, parsed.goal)
        assertNull("capability defaults to null", parsed.capability)
        assertNull("session_id defaults to null", parsed.session_id)
        assertNull("dispatch_intent defaults to null", parsed.dispatch_intent)
        assertNull("dispatch_plan_id defaults to null", parsed.dispatch_plan_id)
        assertNull("continuity_token defaults to null", parsed.continuity_token)
        assertNull("is_resumable defaults to null", parsed.is_resumable)
        assertTrue("execution_context defaults to empty", parsed.execution_context.isEmpty())
        assertTrue("recovery_context defaults to empty", parsed.recovery_context.isEmpty())
        assertTrue("constraints defaults to empty", parsed.constraints.isEmpty())
    }

    @Test
    fun `extra unknown JSON fields are tolerated`() {
        val json = """
            {
              "trace_id": "t1",
              "task_id": "task1",
              "goal": "do something",
              "exec_mode": "local",
              "route_mode": "local",
              "future_field_v3": "some_value",
              "unknown_nested": {"key": "val"}
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, HandoffEnvelopeV2::class.java)
        assertNotNull("parsing must not throw for unknown fields", parsed)
        assertEquals("t1", parsed.trace_id)
        assertEquals("task1", parsed.task_id)
    }

    @Test
    fun `missing optional fields produce safe defaults`() {
        val json = """{"trace_id":"t2","task_id":"tsk2","goal":"g","exec_mode":"local","route_mode":"local"}"""
        val parsed = gson.fromJson(json, HandoffEnvelopeV2::class.java)
        assertNull(parsed.capability)
        assertNull(parsed.source_runtime_posture)
        assertNull(parsed.dispatch_intent)
        assertNull(parsed.dispatch_plan_id)
        assertNull(parsed.continuity_token)
        assertNull(parsed.is_resumable)
        assertNull(parsed.interruption_reason)
        assertTrue(parsed.execution_context.isEmpty())
        assertTrue(parsed.recovery_context.isEmpty())
        assertTrue(parsed.constraints.isEmpty())
    }

    // ── Section: contract mapping to GoalExecutionPayload ────────────────────

    private fun mapToGoalExecutionPayload(envelope: HandoffEnvelopeV2) =
        com.ufo.galaxy.protocol.GoalExecutionPayload(
            task_id = envelope.task_id,
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

    @Test
    fun `task_id is preserved in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(taskId = "task-mapping-001")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("task-mapping-001", goal.task_id)
    }

    @Test
    fun `goal is preserved in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(goal = "navigate to main screen")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("navigate to main screen", goal.goal)
    }

    @Test
    fun `constraints are forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope().copy(constraints = listOf("do not close app", "timeout 5s"))
        val goal = mapToGoalExecutionPayload(env)
        assertEquals(listOf("do not close app", "timeout 5s"), goal.constraints)
    }

    @Test
    fun `source_runtime_posture is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(sourceRuntimePosture = "join_runtime")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("join_runtime", goal.source_runtime_posture)
    }

    @Test
    fun `execution_context is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(executionContext = mapOf("locale" to "en-US", "priority" to "high"))
        val goal = mapToGoalExecutionPayload(env)
        assertEquals(mapOf("locale" to "en-US", "priority" to "high"), goal.execution_context)
    }

    @Test
    fun `executor_target_type is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(executorTargetType = "android_device")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("android_device", goal.executor_target_type)
    }

    @Test
    fun `continuity_token is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(continuityToken = "stable-cont-token")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("stable-cont-token", goal.continuity_token)
    }

    @Test
    fun `recovery_context is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(recoveryContext = mapOf("last_step" to "step_4"))
        val goal = mapToGoalExecutionPayload(env)
        assertEquals(mapOf("last_step" to "step_4"), goal.recovery_context)
    }

    @Test
    fun `is_resumable is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(isResumable = true)
        val goal = mapToGoalExecutionPayload(env)
        assertEquals(true, goal.is_resumable)
    }

    @Test
    fun `is_resumable false is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(isResumable = false)
        val goal = mapToGoalExecutionPayload(env)
        assertEquals(false, goal.is_resumable)
    }

    @Test
    fun `interruption_reason is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(interruptionReason = "transport_degraded")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("transport_degraded", goal.interruption_reason)
    }

    @Test
    fun `dispatch_plan_id is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(dispatchPlanId = "plan-456")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("plan-456", goal.dispatch_plan_id)
    }

    @Test
    fun `source_dispatch_strategy is forwarded in GoalExecutionPayload mapping`() {
        val env = fullEnvelope(sourceDispatchStrategy = "fallback_local")
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("fallback_local", goal.source_dispatch_strategy)
    }

    @Test
    fun `null optional fields produce null in GoalExecutionPayload mapping`() {
        val env = minimalEnvelope()
        val goal = mapToGoalExecutionPayload(env)
        assertNull(goal.source_runtime_posture)
        assertNull(goal.executor_target_type)
        assertNull(goal.continuity_token)
        assertNull(goal.is_resumable)
        assertNull(goal.interruption_reason)
        assertNull(goal.dispatch_plan_id)
        assertNull(goal.source_dispatch_strategy)
        assertTrue(goal.execution_context.isEmpty())
        assertTrue(goal.recovery_context.isEmpty())
    }

    // ── Section: HandoffEnvelopeV2ResultPayload — happy path ──────────────────

    private fun buildSuccessResult(
        taskId: String = "task-r",
        traceId: String = "trace-r",
        env: HandoffEnvelopeV2 = fullEnvelope(taskId = "task-r", traceId = "trace-r")
    ) = HandoffEnvelopeV2ResultPayload(
        task_id = taskId,
        trace_id = traceId,
        correlation_id = taskId,
        status = "success",
        result_summary = "handoff_v2: success",
        error = null,
        device_id = "Samsung_Galaxy",
        route_mode = "cross_device",
        dispatch_plan_id = env.dispatch_plan_id,
        continuity_token = env.continuity_token,
        dispatch_intent = env.dispatch_intent,
        execution_context = env.execution_context,
        executor_target_type = env.executor_target_type,
        source_runtime_posture = env.source_runtime_posture
    )

    @Test
    fun `result status success is preserved`() {
        assertEquals("success", buildSuccessResult().status)
    }

    @Test
    fun `result task_id is echoed`() {
        val r = buildSuccessResult(taskId = "task-echo-001")
        assertEquals("task-echo-001", r.task_id)
    }

    @Test
    fun `result trace_id is echoed`() {
        val r = buildSuccessResult(traceId = "trace-echo-001")
        assertEquals("trace-echo-001", r.trace_id)
    }

    @Test
    fun `result correlation_id equals task_id`() {
        val r = buildSuccessResult(taskId = "task-corr")
        assertEquals(r.task_id, r.correlation_id)
    }

    @Test
    fun `result device_id is set`() {
        val r = buildSuccessResult()
        assertTrue("device_id must be non-blank", r.device_id.isNotBlank())
    }

    @Test
    fun `result route_mode is cross_device`() {
        assertEquals("cross_device", buildSuccessResult().route_mode)
    }

    @Test
    fun `result dispatch_plan_id is echoed`() {
        val env = fullEnvelope(dispatchPlanId = "plan-echo-007")
        val r = buildSuccessResult(env = env)
        assertEquals("plan-echo-007", r.dispatch_plan_id)
    }

    @Test
    fun `result continuity_token is echoed`() {
        val env = fullEnvelope(continuityToken = "ct-echo")
        val r = buildSuccessResult(env = env)
        assertEquals("ct-echo", r.continuity_token)
    }

    @Test
    fun `result dispatch_intent is echoed`() {
        val env = fullEnvelope(dispatchIntent = "task_execute")
        val r = buildSuccessResult(env = env)
        assertEquals("task_execute", r.dispatch_intent)
    }

    @Test
    fun `result execution_context is echoed`() {
        val env = fullEnvelope(executionContext = mapOf("k" to "v"))
        val r = buildSuccessResult(env = env)
        assertEquals(mapOf("k" to "v"), r.execution_context)
    }

    @Test
    fun `result executor_target_type is echoed`() {
        val env = fullEnvelope(executorTargetType = "android_device")
        val r = buildSuccessResult(env = env)
        assertEquals("android_device", r.executor_target_type)
    }

    @Test
    fun `result source_runtime_posture is echoed`() {
        val env = fullEnvelope(sourceRuntimePosture = "join_runtime")
        val r = buildSuccessResult(env = env)
        assertEquals("join_runtime", r.source_runtime_posture)
    }

    @Test
    fun `result null error field for success`() {
        assertNull(buildSuccessResult().error)
    }

    // ── Section: HandoffEnvelopeV2ResultPayload — error path ──────────────────

    private fun buildErrorResult(
        taskId: String = "task-err",
        traceId: String = "trace-err",
        errorMsg: String = "handoff_v2_execution_failed: NullPointerException"
    ) = HandoffEnvelopeV2ResultPayload(
        task_id = taskId,
        trace_id = traceId,
        correlation_id = taskId,
        status = "error",
        error = errorMsg,
        device_id = "Samsung_Galaxy",
        route_mode = "cross_device"
    )

    @Test
    fun `result status error is preserved`() {
        assertEquals("error", buildErrorResult().status)
    }

    @Test
    fun `result error field is populated for error status`() {
        val r = buildErrorResult(errorMsg = "execution_failed: timeout")
        assertNotNull(r.error)
        assertTrue("error must be non-blank for error status", r.error!!.isNotBlank())
    }

    @Test
    fun `result result_summary defaults to null when not set`() {
        assertNull(buildErrorResult().result_summary)
    }

    // ── Section: HandoffEnvelopeV2ResultPayload — parse-failure path ──────────

    @Test
    fun `bad_payload error status starts with bad_payload prefix`() {
        val r = HandoffEnvelopeV2ResultPayload(
            task_id = "task-bad",
            trace_id = "trace-bad",
            correlation_id = "task-bad",
            status = "error",
            error = "bad_payload: Gson parse failed",
            device_id = "test_device",
            route_mode = "cross_device"
        )
        assertEquals("error", r.status)
        assertTrue("error must start with bad_payload:", r.error!!.startsWith("bad_payload:"))
    }

    @Test
    fun `parse-failure result has null optional echo fields`() {
        val r = HandoffEnvelopeV2ResultPayload(
            task_id = "task-parse-fail",
            trace_id = "trace-pf",
            correlation_id = "task-parse-fail",
            status = "error",
            error = "bad_payload: malformed JSON",
            device_id = "test",
            route_mode = "cross_device"
        )
        assertNull(r.dispatch_plan_id)
        assertNull(r.continuity_token)
        assertNull(r.dispatch_intent)
        assertTrue(r.execution_context.isEmpty())
        assertNull(r.executor_target_type)
        assertNull(r.source_runtime_posture)
    }

    // ── Section: AipMessage outbound envelope ─────────────────────────────────

    @Test
    fun `outbound AipMessage type is HANDOFF_ENVELOPE_V2_RESULT`() {
        val result = buildSuccessResult()
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = result.device_id,
            trace_id = result.trace_id,
            route_mode = result.route_mode,
            runtime_session_id = "rsid-test"
        )
        assertEquals(MsgType.HANDOFF_ENVELOPE_V2_RESULT, envelope.type)
    }

    @Test
    fun `outbound AipMessage correlation_id equals task_id`() {
        val result = buildSuccessResult(taskId = "task-aip")
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = result.device_id,
            trace_id = result.trace_id,
            route_mode = result.route_mode
        )
        assertEquals("task-aip", envelope.correlation_id)
    }

    @Test
    fun `outbound AipMessage trace_id is populated`() {
        val result = buildSuccessResult(traceId = "trace-aip-001")
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = result.device_id,
            trace_id = result.trace_id,
            route_mode = result.route_mode
        )
        assertEquals("trace-aip-001", envelope.trace_id)
    }

    @Test
    fun `outbound AipMessage route_mode is cross_device`() {
        val result = buildSuccessResult()
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = result.device_id,
            trace_id = result.trace_id,
            route_mode = result.route_mode
        )
        assertEquals("cross_device", envelope.route_mode)
    }

    @Test
    fun `outbound AipMessage JSON round-trips via Gson`() {
        val result = buildSuccessResult()
        val envelope = AipMessage(
            type = MsgType.HANDOFF_ENVELOPE_V2_RESULT,
            payload = result,
            correlation_id = result.correlation_id,
            device_id = result.device_id,
            trace_id = result.trace_id,
            route_mode = result.route_mode
        )
        val json = gson.toJson(envelope)
        assertTrue("serialised envelope must contain type field", json.contains("handoff_envelope_v2_result"))
        assertTrue("serialised envelope must contain task_id in payload", json.contains(result.task_id))
    }

    // ── Section: backward compatibility ───────────────────────────────────────

    @Test
    fun `minimal HandoffEnvelopeV2 with only required fields is valid`() {
        val env = minimalEnvelope()
        assertEquals("trace-min", env.trace_id)
        assertEquals("task-min", env.task_id)
        assertEquals("minimal goal", env.goal)
        assertNull(env.capability)
        assertNull(env.session_id)
        assertNull(env.source_runtime_posture)
        assertNull(env.dispatch_intent)
        assertNull(env.dispatch_plan_id)
        assertNull(env.continuity_token)
        assertNull(env.is_resumable)
        assertNull(env.interruption_reason)
        assertTrue(env.execution_context.isEmpty())
        assertTrue(env.recovery_context.isEmpty())
        assertTrue(env.constraints.isEmpty())
    }

    @Test
    fun `minimal HandoffEnvelopeV2ResultPayload with only required fields is valid`() {
        val r = HandoffEnvelopeV2ResultPayload(
            task_id = "t",
            trace_id = "tr",
            correlation_id = "t",
            status = "success"
        )
        assertEquals("success", r.status)
        assertTrue("route_mode defaults to cross_device", r.route_mode == "cross_device")
        assertTrue("device_id defaults to empty string", r.device_id.isEmpty())
        assertNull(r.result_summary)
        assertNull(r.error)
        assertNull(r.dispatch_plan_id)
        assertNull(r.continuity_token)
        assertNull(r.dispatch_intent)
        assertTrue(r.execution_context.isEmpty())
        assertNull(r.executor_target_type)
        assertNull(r.source_runtime_posture)
    }

    @Test
    fun `unknown source_dispatch_strategy value is tolerated`() {
        val env = fullEnvelope(sourceDispatchStrategy = "future_strategy_v4")
        assertEquals("future_strategy_v4", env.source_dispatch_strategy)
        // Should not throw or reject when mapped to GoalExecutionPayload
        val goal = mapToGoalExecutionPayload(env)
        assertEquals("future_strategy_v4", goal.source_dispatch_strategy)
    }

    @Test
    fun `unknown executor_target_type value is tolerated`() {
        val env = fullEnvelope(executorTargetType = "quantum_device")
        assertEquals("quantum_device", env.executor_target_type)
    }
}
