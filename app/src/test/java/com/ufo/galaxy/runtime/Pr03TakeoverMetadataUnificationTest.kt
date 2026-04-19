package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.protocol.GoalExecutionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-03 — Dispatch metadata and executor target typing unification across three dispatch paths.
 *
 * Validates that all three Android dispatch entry paths (goal_execution, handoff, takeover)
 * are symmetric with respect to:
 *
 * 1. **Richer dispatch metadata** — `dispatch_plan_id` and `source_dispatch_strategy` (PR-48).
 * 2. **Executor target typing** — `executor_target_type` (PR-E).
 * 3. **Unified validator** — [ExecutionContractCompatibilityValidator.checkPayloadCompatibility]
 *    overloads produce consistent [ExecutionContractCompatibilityValidator.CompatibilityCheckResult]
 *    for all three paths.
 * 4. **Unified target type log label** — [ExecutorTargetType.logLabel] produces stable output.
 *
 * ## Sections
 *
 * ### TakeoverRequestEnvelope — PR-48 dispatch metadata fields
 *  - dispatch_plan_id defaults to null (backward compatibility)
 *  - source_dispatch_strategy defaults to null (backward compatibility)
 *  - dispatch_plan_id can be set on TakeoverRequestEnvelope
 *  - source_dispatch_strategy can be set on TakeoverRequestEnvelope
 *  - both PR-48 fields can be set simultaneously
 *
 * ### TakeoverRequestEnvelope — PR-E executor_target_type field
 *  - executor_target_type defaults to null (backward compatibility)
 *  - executor_target_type can be set on TakeoverRequestEnvelope
 *  - known canonical values are preserved
 *
 * ### HandoffEnvelopeV2 — PR-48 dispatch metadata fields
 *  - dispatch_plan_id defaults to null (backward compatibility)
 *  - source_dispatch_strategy defaults to null (backward compatibility)
 *  - dispatch_plan_id can be set on HandoffEnvelopeV2
 *  - source_dispatch_strategy can be set on HandoffEnvelopeV2
 *
 * ### ExecutionContractCompatibilityValidator — checkPayloadCompatibility (HandoffEnvelopeV2)
 *  - legacy HandoffEnvelopeV2 (all null) produces all false flags
 *  - HandoffEnvelopeV2 with dispatch_plan_id produces hasDispatchMetadata = true
 *  - HandoffEnvelopeV2 with source_dispatch_strategy produces hasDispatchMetadata = true
 *  - HandoffEnvelopeV2 with executor_target_type produces hasExecutorTargetTyping = true
 *  - HandoffEnvelopeV2 with continuity_token produces hasContinuityRecovery = true
 *  - HandoffEnvelopeV2 always produces hasObservabilityTracing = false
 *  - HandoffEnvelopeV2 always produces hasPolicyRouting = false
 *
 * ### ExecutionContractCompatibilityValidator — checkPayloadCompatibility (TakeoverRequestEnvelope)
 *  - legacy TakeoverRequestEnvelope (all null) produces all false flags
 *  - TakeoverRequestEnvelope with dispatch_plan_id produces hasDispatchMetadata = true
 *  - TakeoverRequestEnvelope with source_dispatch_strategy produces hasDispatchMetadata = true
 *  - TakeoverRequestEnvelope with executor_target_type produces hasExecutorTargetTyping = true
 *  - TakeoverRequestEnvelope with continuity_token produces hasContinuityRecovery = true
 *  - TakeoverRequestEnvelope with is_resumable produces hasContinuityRecovery = true
 *  - TakeoverRequestEnvelope with interruption_reason produces hasContinuityRecovery = true
 *  - TakeoverRequestEnvelope with non-empty recovery_context produces hasContinuityRecovery = true
 *  - TakeoverRequestEnvelope always produces hasObservabilityTracing = false
 *  - TakeoverRequestEnvelope always produces hasPolicyRouting = false
 *
 * ### Three-path metadata symmetry
 *  - all three paths with dispatch_plan_id set produce hasDispatchMetadata = true
 *  - all three paths with no dispatch metadata fields produce hasDispatchMetadata = false
 *  - all three paths with executor_target_type set produce hasExecutorTargetTyping = true
 *  - all three paths with no executor_target_type produce hasExecutorTargetTyping = false
 *
 * ### ExecutorTargetType.logLabel — unified log expression
 *  - logLabel for null produces stable format with eligible=true
 *  - logLabel for ANDROID_DEVICE produces canonical=android_device eligible=true
 *  - logLabel for LOCAL produces canonical=local eligible=true
 *  - logLabel for NODE_SERVICE produces canonical=node_service eligible=false
 *  - logLabel for WORKER produces canonical=worker eligible=false
 *  - logLabel for unknown value produces canonical=null eligible=false
 *  - logLabel never throws for any input
 */
class Pr03TakeoverMetadataUnificationTest {

    // ── TakeoverRequestEnvelope — PR-48 dispatch metadata fields ─────────────

    @Test
    fun `TakeoverRequestEnvelope dispatch_plan_id defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-1", task_id = "t-1", trace_id = "tr-1", goal = "open app"
        )
        assertNull(
            "dispatch_plan_id must default to null for backward compatibility with legacy senders",
            envelope.dispatch_plan_id
        )
    }

    @Test
    fun `TakeoverRequestEnvelope source_dispatch_strategy defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-2", task_id = "t-2", trace_id = "tr-2", goal = "open app"
        )
        assertNull(
            "source_dispatch_strategy must default to null for backward compatibility with legacy senders",
            envelope.source_dispatch_strategy
        )
    }

    @Test
    fun `TakeoverRequestEnvelope dispatch_plan_id can be set`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-3", task_id = "t-3", trace_id = "tr-3", goal = "open app",
            dispatch_plan_id = "plan-abc-001"
        )
        assertEquals(
            "dispatch_plan_id must be preserved when explicitly provided",
            "plan-abc-001",
            envelope.dispatch_plan_id
        )
    }

    @Test
    fun `TakeoverRequestEnvelope source_dispatch_strategy can be set`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-4", task_id = "t-4", trace_id = "tr-4", goal = "open app",
            source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.REMOTE_HANDOFF.wireValue
        )
        assertEquals(
            "source_dispatch_strategy must be preserved when explicitly provided",
            "remote_handoff",
            envelope.source_dispatch_strategy
        )
    }

    @Test
    fun `TakeoverRequestEnvelope both PR-48 fields can be set simultaneously`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-5", task_id = "t-5", trace_id = "tr-5", goal = "open app",
            dispatch_plan_id = "plan-xyz-002",
            source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.STAGED_MESH.wireValue
        )
        assertEquals("plan-xyz-002", envelope.dispatch_plan_id)
        assertEquals("staged_mesh", envelope.source_dispatch_strategy)
    }

    // ── TakeoverRequestEnvelope — PR-E executor_target_type field ────────────

    @Test
    fun `TakeoverRequestEnvelope executor_target_type defaults to null`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-6", task_id = "t-6", trace_id = "tr-6", goal = "open app"
        )
        assertNull(
            "executor_target_type must default to null for backward compatibility",
            envelope.executor_target_type
        )
    }

    @Test
    fun `TakeoverRequestEnvelope executor_target_type can be set to ANDROID_DEVICE`() {
        val envelope = TakeoverRequestEnvelope(
            takeover_id = "to-7", task_id = "t-7", trace_id = "tr-7", goal = "open app",
            executor_target_type = ExecutorTargetType.ANDROID_DEVICE
        )
        assertEquals(
            "executor_target_type must be preserved when explicitly set",
            ExecutorTargetType.ANDROID_DEVICE,
            envelope.executor_target_type
        )
    }

    @Test
    fun `TakeoverRequestEnvelope executor_target_type preserves all canonical values`() {
        for (value in ExecutorTargetType.ALL_VALUES) {
            val envelope = TakeoverRequestEnvelope(
                takeover_id = "to-8", task_id = "t-8", trace_id = "tr-8", goal = "open app",
                executor_target_type = value
            )
            assertEquals(
                "TakeoverRequestEnvelope must preserve canonical executor_target_type=$value",
                value,
                envelope.executor_target_type
            )
        }
    }

    // ── HandoffEnvelopeV2 — PR-48 dispatch metadata fields ───────────────────

    @Test
    fun `HandoffEnvelopeV2 dispatch_plan_id defaults to null`() {
        val envelope = HandoffEnvelopeV2(
            trace_id = "tr-h1", task_id = "t-h1", goal = "test",
            exec_mode = "remote", route_mode = "cross_device"
        )
        assertNull(
            "dispatch_plan_id must default to null for backward compatibility",
            envelope.dispatch_plan_id
        )
    }

    @Test
    fun `HandoffEnvelopeV2 source_dispatch_strategy defaults to null`() {
        val envelope = HandoffEnvelopeV2(
            trace_id = "tr-h2", task_id = "t-h2", goal = "test",
            exec_mode = "remote", route_mode = "cross_device"
        )
        assertNull(
            "source_dispatch_strategy must default to null for backward compatibility",
            envelope.source_dispatch_strategy
        )
    }

    @Test
    fun `HandoffEnvelopeV2 dispatch_plan_id can be set`() {
        val envelope = HandoffEnvelopeV2(
            trace_id = "tr-h3", task_id = "t-h3", goal = "test",
            exec_mode = "remote", route_mode = "cross_device",
            dispatch_plan_id = "plan-handoff-001"
        )
        assertEquals("plan-handoff-001", envelope.dispatch_plan_id)
    }

    @Test
    fun `HandoffEnvelopeV2 source_dispatch_strategy can be set`() {
        val envelope = HandoffEnvelopeV2(
            trace_id = "tr-h4", task_id = "t-h4", goal = "test",
            exec_mode = "remote", route_mode = "cross_device",
            source_dispatch_strategy = ExecutionContractCompatibilityValidator.DispatchStrategyHint.FALLBACK_LOCAL.wireValue
        )
        assertEquals("fallback_local", envelope.source_dispatch_strategy)
    }

    // ── checkPayloadCompatibility(HandoffEnvelopeV2) ─────────────────────────

    @Test
    fun `legacy HandoffEnvelopeV2 produces all false flags`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-1", task_id = "t-chk-1", goal = "test",
                exec_mode = "remote", route_mode = "cross_device"
            )
        )
        assertFalse("Legacy HandoffEnvelopeV2 must not signal hasDispatchMetadata", result.hasDispatchMetadata)
        assertFalse("Legacy HandoffEnvelopeV2 must not signal hasExecutorTargetTyping", result.hasExecutorTargetTyping)
        assertFalse("Legacy HandoffEnvelopeV2 must not signal hasContinuityRecovery", result.hasContinuityRecovery)
        assertFalse("Legacy HandoffEnvelopeV2 must not signal hasObservabilityTracing", result.hasObservabilityTracing)
        assertFalse("Legacy HandoffEnvelopeV2 must not signal hasPolicyRouting", result.hasPolicyRouting)
    }

    @Test
    fun `HandoffEnvelopeV2 with dispatch_plan_id produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-2", task_id = "t-chk-2", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                dispatch_plan_id = "plan-001"
            )
        )
        assertTrue("dispatch_plan_id must activate DISPATCH_METADATA area", result.hasDispatchMetadata)
    }

    @Test
    fun `HandoffEnvelopeV2 with source_dispatch_strategy produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-3", task_id = "t-chk-3", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                source_dispatch_strategy = "local"
            )
        )
        assertTrue("source_dispatch_strategy must activate DISPATCH_METADATA area", result.hasDispatchMetadata)
    }

    @Test
    fun `HandoffEnvelopeV2 with executor_target_type produces hasExecutorTargetTyping = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-4", task_id = "t-chk-4", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE
            )
        )
        assertTrue("executor_target_type must activate EXECUTOR_TARGET_TYPING area", result.hasExecutorTargetTyping)
    }

    @Test
    fun `HandoffEnvelopeV2 with continuity_token produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-5", task_id = "t-chk-5", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                continuity_token = "ct-handoff-001"
            )
        )
        assertTrue("continuity_token must activate CONTINUITY_RECOVERY area", result.hasContinuityRecovery)
    }

    @Test
    fun `HandoffEnvelopeV2 always produces hasObservabilityTracing = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-6", task_id = "t-chk-6", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                dispatch_plan_id = "plan-any"
            )
        )
        assertFalse(
            "HandoffEnvelopeV2 does not carry observability/tracing fields; hasObservabilityTracing must be false",
            result.hasObservabilityTracing
        )
    }

    @Test
    fun `HandoffEnvelopeV2 always produces hasPolicyRouting = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-chk-7", task_id = "t-chk-7", goal = "test",
                exec_mode = "remote", route_mode = "cross_device",
                dispatch_plan_id = "plan-any"
            )
        )
        assertFalse(
            "HandoffEnvelopeV2 does not carry policy routing fields; hasPolicyRouting must be false",
            result.hasPolicyRouting
        )
    }

    // ── checkPayloadCompatibility(TakeoverRequestEnvelope) ───────────────────

    @Test
    fun `legacy TakeoverRequestEnvelope produces all false flags`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-1", task_id = "t-chk-1",
                trace_id = "tr-chk-1", goal = "test"
            )
        )
        assertFalse("Legacy TakeoverRequestEnvelope must not signal hasDispatchMetadata", result.hasDispatchMetadata)
        assertFalse("Legacy TakeoverRequestEnvelope must not signal hasExecutorTargetTyping", result.hasExecutorTargetTyping)
        assertFalse("Legacy TakeoverRequestEnvelope must not signal hasContinuityRecovery", result.hasContinuityRecovery)
        assertFalse("Legacy TakeoverRequestEnvelope must not signal hasObservabilityTracing", result.hasObservabilityTracing)
        assertFalse("Legacy TakeoverRequestEnvelope must not signal hasPolicyRouting", result.hasPolicyRouting)
    }

    @Test
    fun `TakeoverRequestEnvelope with dispatch_plan_id produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-2", task_id = "t-chk-2",
                trace_id = "tr-chk-2", goal = "test",
                dispatch_plan_id = "plan-takeover-001"
            )
        )
        assertTrue("dispatch_plan_id must activate DISPATCH_METADATA area", result.hasDispatchMetadata)
    }

    @Test
    fun `TakeoverRequestEnvelope with source_dispatch_strategy produces hasDispatchMetadata = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-3", task_id = "t-chk-3",
                trace_id = "tr-chk-3", goal = "test",
                source_dispatch_strategy = "remote_handoff"
            )
        )
        assertTrue("source_dispatch_strategy must activate DISPATCH_METADATA area", result.hasDispatchMetadata)
    }

    @Test
    fun `TakeoverRequestEnvelope with executor_target_type produces hasExecutorTargetTyping = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-4", task_id = "t-chk-4",
                trace_id = "tr-chk-4", goal = "test",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE
            )
        )
        assertTrue("executor_target_type must activate EXECUTOR_TARGET_TYPING area", result.hasExecutorTargetTyping)
    }

    @Test
    fun `TakeoverRequestEnvelope with continuity_token produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-5", task_id = "t-chk-5",
                trace_id = "tr-chk-5", goal = "test",
                continuity_token = "ct-takeover-001"
            )
        )
        assertTrue("continuity_token must activate CONTINUITY_RECOVERY area", result.hasContinuityRecovery)
    }

    @Test
    fun `TakeoverRequestEnvelope with is_resumable produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-6", task_id = "t-chk-6",
                trace_id = "tr-chk-6", goal = "test",
                is_resumable = true
            )
        )
        assertTrue("is_resumable must activate CONTINUITY_RECOVERY area", result.hasContinuityRecovery)
    }

    @Test
    fun `TakeoverRequestEnvelope with interruption_reason produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-7", task_id = "t-chk-7",
                trace_id = "tr-chk-7", goal = "test",
                interruption_reason = ContinuityRecoveryContext.REASON_RECONNECT
            )
        )
        assertTrue("interruption_reason must activate CONTINUITY_RECOVERY area", result.hasContinuityRecovery)
    }

    @Test
    fun `TakeoverRequestEnvelope with non-empty recovery_context produces hasContinuityRecovery = true`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-8", task_id = "t-chk-8",
                trace_id = "tr-chk-8", goal = "test",
                recovery_context = mapOf("step" to "3")
            )
        )
        assertTrue("non-empty recovery_context must activate CONTINUITY_RECOVERY area", result.hasContinuityRecovery)
    }

    @Test
    fun `TakeoverRequestEnvelope always produces hasObservabilityTracing = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-9", task_id = "t-chk-9",
                trace_id = "tr-chk-9", goal = "test",
                dispatch_plan_id = "plan-any"
            )
        )
        assertFalse(
            "TakeoverRequestEnvelope does not carry observability/tracing fields; hasObservabilityTracing must be false",
            result.hasObservabilityTracing
        )
    }

    @Test
    fun `TakeoverRequestEnvelope always produces hasPolicyRouting = false`() {
        val result = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-chk-10", task_id = "t-chk-10",
                trace_id = "tr-chk-10", goal = "test",
                dispatch_plan_id = "plan-any"
            )
        )
        assertFalse(
            "TakeoverRequestEnvelope does not carry policy routing fields; hasPolicyRouting must be false",
            result.hasPolicyRouting
        )
    }

    // ── Three-path metadata symmetry ──────────────────────────────────────────

    @Test
    fun `all three paths with dispatch_plan_id produce hasDispatchMetadata = true`() {
        val planId = "plan-sym-001"

        val goalResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-sym-g", goal = "test", dispatch_plan_id = planId)
        )
        val handoffResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-sym-h", task_id = "t-sym-h", goal = "test",
                exec_mode = "remote", route_mode = "cross_device", dispatch_plan_id = planId
            )
        )
        val takeoverResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-sym-1", task_id = "t-sym-t",
                trace_id = "tr-sym-t", goal = "test", dispatch_plan_id = planId
            )
        )

        assertTrue("goal_execution path must activate DISPATCH_METADATA when dispatch_plan_id is set", goalResult.hasDispatchMetadata)
        assertTrue("handoff path must activate DISPATCH_METADATA when dispatch_plan_id is set", handoffResult.hasDispatchMetadata)
        assertTrue("takeover path must activate DISPATCH_METADATA when dispatch_plan_id is set", takeoverResult.hasDispatchMetadata)
    }

    @Test
    fun `all three paths with no dispatch metadata fields produce hasDispatchMetadata = false`() {
        val goalResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-sym-g2", goal = "test")
        )
        val handoffResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-sym-h2", task_id = "t-sym-h2", goal = "test",
                exec_mode = "remote", route_mode = "cross_device"
            )
        )
        val takeoverResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-sym-2", task_id = "t-sym-t2",
                trace_id = "tr-sym-t2", goal = "test"
            )
        )

        assertFalse("goal_execution path must not signal DISPATCH_METADATA for legacy payload", goalResult.hasDispatchMetadata)
        assertFalse("handoff path must not signal DISPATCH_METADATA for legacy envelope", handoffResult.hasDispatchMetadata)
        assertFalse("takeover path must not signal DISPATCH_METADATA for legacy envelope", takeoverResult.hasDispatchMetadata)
    }

    @Test
    fun `all three paths with executor_target_type produce hasExecutorTargetTyping = true`() {
        val targetType = ExecutorTargetType.ANDROID_DEVICE

        val goalResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-sym-g3", goal = "test", executor_target_type = targetType)
        )
        val handoffResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-sym-h3", task_id = "t-sym-h3", goal = "test",
                exec_mode = "remote", route_mode = "cross_device", executor_target_type = targetType
            )
        )
        val takeoverResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-sym-3", task_id = "t-sym-t3",
                trace_id = "tr-sym-t3", goal = "test", executor_target_type = targetType
            )
        )

        assertTrue("goal_execution path must activate EXECUTOR_TARGET_TYPING", goalResult.hasExecutorTargetTyping)
        assertTrue("handoff path must activate EXECUTOR_TARGET_TYPING", handoffResult.hasExecutorTargetTyping)
        assertTrue("takeover path must activate EXECUTOR_TARGET_TYPING", takeoverResult.hasExecutorTargetTyping)
    }

    @Test
    fun `all three paths with no executor_target_type produce hasExecutorTargetTyping = false`() {
        val goalResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            GoalExecutionPayload(task_id = "t-sym-g4", goal = "test", executor_target_type = null)
        )
        val handoffResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            HandoffEnvelopeV2(
                trace_id = "tr-sym-h4", task_id = "t-sym-h4", goal = "test",
                exec_mode = "remote", route_mode = "cross_device"
            )
        )
        val takeoverResult = ExecutionContractCompatibilityValidator.checkPayloadCompatibility(
            TakeoverRequestEnvelope(
                takeover_id = "to-sym-4", task_id = "t-sym-t4",
                trace_id = "tr-sym-t4", goal = "test"
            )
        )

        assertFalse("goal_execution path must not signal EXECUTOR_TARGET_TYPING for legacy payload", goalResult.hasExecutorTargetTyping)
        assertFalse("handoff path must not signal EXECUTOR_TARGET_TYPING for legacy envelope", handoffResult.hasExecutorTargetTyping)
        assertFalse("takeover path must not signal EXECUTOR_TARGET_TYPING for legacy envelope", takeoverResult.hasExecutorTargetTyping)
    }

    // ── ExecutorTargetType.logLabel — unified log expression ──────────────────

    @Test
    fun `logLabel for null produces stable format with eligible=true`() {
        val label = ExecutorTargetType.logLabel(null)
        assertTrue("logLabel must include 'executor_target_type=null'", label.contains("executor_target_type=null"))
        assertTrue("logLabel must include 'canonical=null'", label.contains("canonical=null"))
        assertTrue("logLabel must include 'eligible=true' for null (legacy/unspecified)", label.contains("eligible=true"))
    }

    @Test
    fun `logLabel for ANDROID_DEVICE produces canonical=android_device eligible=true`() {
        val label = ExecutorTargetType.logLabel(ExecutorTargetType.ANDROID_DEVICE)
        assertTrue("logLabel must include canonical=android_device", label.contains("canonical=android_device"))
        assertTrue("logLabel must include eligible=true for ANDROID_DEVICE", label.contains("eligible=true"))
    }

    @Test
    fun `logLabel for LOCAL produces canonical=local eligible=true`() {
        val label = ExecutorTargetType.logLabel(ExecutorTargetType.LOCAL)
        assertTrue("logLabel must include canonical=local", label.contains("canonical=local"))
        assertTrue("logLabel must include eligible=true for LOCAL", label.contains("eligible=true"))
    }

    @Test
    fun `logLabel for NODE_SERVICE produces canonical=node_service eligible=false`() {
        val label = ExecutorTargetType.logLabel(ExecutorTargetType.NODE_SERVICE)
        assertTrue("logLabel must include canonical=node_service", label.contains("canonical=node_service"))
        assertTrue("logLabel must include eligible=false for NODE_SERVICE", label.contains("eligible=false"))
    }

    @Test
    fun `logLabel for WORKER produces canonical=worker eligible=false`() {
        val label = ExecutorTargetType.logLabel(ExecutorTargetType.WORKER)
        assertTrue("logLabel must include canonical=worker", label.contains("canonical=worker"))
        assertTrue("logLabel must include eligible=false for WORKER", label.contains("eligible=false"))
    }

    @Test
    fun `logLabel for unrecognized value produces canonical=null eligible=false`() {
        val label = ExecutorTargetType.logLabel("unknown_value")
        assertTrue("logLabel must include canonical=null for unrecognized values", label.contains("canonical=null"))
        assertTrue("logLabel must include eligible=false for unrecognized target types", label.contains("eligible=false"))
    }

    @Test
    fun `logLabel never throws for any input`() {
        // Cover all known values plus boundary cases
        val testValues = listOf(
            null, "", "  ", ExecutorTargetType.ANDROID_DEVICE, ExecutorTargetType.LOCAL,
            ExecutorTargetType.NODE_SERVICE, ExecutorTargetType.WORKER, "unknown_xyz",
            "ANDROID_DEVICE", "Android_Device"
        )
        for (rawValue in testValues) {
            assertNotNull(
                "logLabel must return non-null for rawValue=$rawValue",
                ExecutorTargetType.logLabel(rawValue)
            )
        }
    }

    // ── checkPayloadCompatibility never throws ────────────────────────────────

    @Test
    fun `checkPayloadCompatibility for HandoffEnvelopeV2 never throws for any field combination`() {
        val envelopes = listOf(
            HandoffEnvelopeV2(trace_id = "t", task_id = "t", goal = "g", exec_mode = "r", route_mode = "c"),
            HandoffEnvelopeV2(
                trace_id = "t", task_id = "t", goal = "g", exec_mode = "r", route_mode = "c",
                dispatch_plan_id = "p", source_dispatch_strategy = "staged_mesh",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct", is_resumable = true, interruption_reason = "reconnect",
                recovery_context = mapOf("k" to "v")
            )
        )
        for (envelope in envelopes) {
            assertNotNull(
                "checkPayloadCompatibility must not throw for HandoffEnvelopeV2",
                ExecutionContractCompatibilityValidator.checkPayloadCompatibility(envelope)
            )
        }
    }

    @Test
    fun `checkPayloadCompatibility for TakeoverRequestEnvelope never throws for any field combination`() {
        val envelopes = listOf(
            TakeoverRequestEnvelope(takeover_id = "to", task_id = "t", trace_id = "tr", goal = "g"),
            TakeoverRequestEnvelope(
                takeover_id = "to", task_id = "t", trace_id = "tr", goal = "g",
                dispatch_plan_id = "p", source_dispatch_strategy = "remote_handoff",
                executor_target_type = ExecutorTargetType.ANDROID_DEVICE,
                continuity_token = "ct", is_resumable = false,
                interruption_reason = "heartbeat_miss",
                recovery_context = mapOf("step" to "5")
            )
        )
        for (envelope in envelopes) {
            assertNotNull(
                "checkPayloadCompatibility must not throw for TakeoverRequestEnvelope",
                ExecutionContractCompatibilityValidator.checkPayloadCompatibility(envelope)
            )
        }
    }
}
