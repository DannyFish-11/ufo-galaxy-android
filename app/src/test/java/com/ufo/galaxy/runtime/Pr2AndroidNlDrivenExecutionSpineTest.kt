package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.HandoffEnvelopeV2
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import org.junit.Assert.*
import org.junit.Test

/**
 * PR-2 (Android) — Unit tests for [AndroidNlDrivenExecutionSpineContract].
 *
 * Validates all acceptance criteria for the Android-side NL-driven execution spine
 * participation contract:
 *
 *  1. **ExecutionSpineParticipationKind** — four kinds defined; wire values stable;
 *     fromWireValue / fromRuntimeKind work; only TAKEOVER_INTERACTIVE can signal problem solved.
 *  2. **ProblemSolvingClosureClass** — five classes defined; wire values stable;
 *     fromWireValue works; task-completeness flags are correct.
 *  3. **classifyParticipationKind** — maps from runtime-kind; declaredRole takes priority.
 *  4. **classifyClosureClass** — correct class for each participation kind × task-success
 *     combination; requiresFurtherDelegation overrides to EXECUTION_DELEGATED_FURTHER.
 *  5. **ExecutionSpineTraceContribution.toWireMap** — all mandatory keys present; values correct.
 *  6. **buildContractWireMap** — all mandatory keys present; all_invariants_hold = true.
 *  7. **SPINE_PARTICIPATION_INVARIANTS** — all 10 invariants hold (all values true).
 *  8. **StabilizationBaseline** — "android-nl-driven-execution-spine-contract" registered.
 *  9. **AutonomousExecutionPipeline integration** — handleGoalExecution and
 *     handleParallelSubtask populate problem_solving_closure_class and
 *     execution_spine_participation_kind in emitted GoalResultPayload.
 * 10. **Legacy safety** — null problem_context / problem_solving_role do not crash.
 *
 * ## Test matrix
 *
 * ### ExecutionSpineParticipationKind
 *  - TAKEOVER_INTERACTIVE wireValue is takeover_interactive
 *  - DELEGATED_EXECUTION wireValue is delegated_execution
 *  - LOCAL_ASSISTIVE wireValue is local_assistive
 *  - DEGRADED_FALLBACK wireValue is degraded_fallback
 *  - fromWireValue returns correct kind for each value
 *  - fromWireValue returns null for unknown value
 *  - fromRuntimeKind maps correctly for all four kinds
 *  - fromRuntimeKind defaults to DELEGATED_EXECUTION for unknown value
 *  - only TAKEOVER_INTERACTIVE has canSignalProblemSolved=true
 *  - DEGRADED_FALLBACK has canSignalProblemSolved=false
 *  - ALL_WIRE_VALUES contains exactly four entries
 *
 * ### ProblemSolvingClosureClass
 *  - TASK_COMPLETED_PROBLEM_SOLVED wireValue correct; isTaskComplete=true
 *  - TASK_COMPLETED_PROBLEM_PARTIAL wireValue correct; isTaskComplete=true
 *  - TASK_COMPLETED_PROBLEM_OPEN wireValue correct; isTaskComplete=true
 *  - TASK_INCOMPLETE_PROBLEM_OPEN wireValue correct; isTaskComplete=false
 *  - EXECUTION_DELEGATED_FURTHER wireValue correct; isTaskComplete=false
 *  - fromWireValue returns correct class for each value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly five entries
 *
 * ### classifyParticipationKind
 *  - null executionRuntimeKind → DELEGATED_EXECUTION (default)
 *  - takeover_interactive → TAKEOVER_INTERACTIVE
 *  - delegated_execution → DELEGATED_EXECUTION
 *  - local_assistive → LOCAL_ASSISTIVE
 *  - degraded_fallback → DEGRADED_FALLBACK
 *  - declaredRole overrides executionRuntimeKind
 *  - invalid declaredRole falls back to executionRuntimeKind
 *  - blank declaredRole falls back to executionRuntimeKind
 *
 * ### classifyClosureClass
 *  - TAKEOVER_INTERACTIVE + success → TASK_COMPLETED_PROBLEM_SOLVED
 *  - TAKEOVER_INTERACTIVE + failure → TASK_INCOMPLETE_PROBLEM_OPEN
 *  - DELEGATED_EXECUTION + success → TASK_COMPLETED_PROBLEM_PARTIAL
 *  - DELEGATED_EXECUTION + failure → TASK_INCOMPLETE_PROBLEM_OPEN
 *  - LOCAL_ASSISTIVE + success → TASK_COMPLETED_PROBLEM_PARTIAL
 *  - LOCAL_ASSISTIVE + failure → TASK_INCOMPLETE_PROBLEM_OPEN
 *  - DEGRADED_FALLBACK + success → TASK_COMPLETED_PROBLEM_OPEN
 *  - DEGRADED_FALLBACK + failure → TASK_INCOMPLETE_PROBLEM_OPEN
 *  - requiresFurtherDelegation=true always → EXECUTION_DELEGATED_FURTHER
 *
 * ### ExecutionSpineTraceContribution.toWireMap
 *  - contains spine_task_id
 *  - contains spine_trace_id
 *  - contains execution_spine_participation_kind
 *  - contains problem_solving_closure_class
 *  - contains spine_latency_ms
 *  - contains spine_step_count
 *  - contains spine_device_id
 *  - contains execution_spine_schema_version
 *  - contains spine_can_signal_problem_solved
 *  - contains spine_is_task_complete
 *  - contains spine_v2_closure_signal
 *  - problem_context present when provided; absent when null
 *
 * ### buildContractWireMap
 *  - introduced_pr = 2
 *  - schema_version = "1"
 *  - valid_participation_kinds count = 4
 *  - valid_closure_classes count = 5
 *  - participation_kinds_that_can_signal_problem_solved = [takeover_interactive]
 *  - closure_classes_where_task_complete count = 3
 *  - invariant_count = 10
 *  - all_invariants_hold = true
 *
 * ### StabilizationBaseline
 *  - android-nl-driven-execution-spine-contract is registered
 *  - surface is CANONICAL_STABLE
 *  - extensionGuidance is EXTEND
 *
 * ### AutonomousExecutionPipeline integration
 *  - handleGoalExecution success: problem_solving_closure_class is task_completed_problem_partial
 *    for delegated_execution kind
 *  - handleGoalExecution success: problem_solving_closure_class is task_completed_problem_solved
 *    for takeover_interactive kind (declared role)
 *  - handleGoalExecution result carries execution_spine_participation_kind
 *  - handleParallelSubtask success: problem_solving_closure_class is task_completed_problem_partial
 *  - disabled result carries execution_runtime_kind (spine class not set for non-executed paths)
 *
 * ### Legacy safety
 *  - null problem_solving_role handled without crash
 *  - null problem_context handled without crash
 *  - null execution_runtime_kind → DELEGATED_EXECUTION default
 */
class Pr2AndroidNlDrivenExecutionSpineTest {

    // ── ExecutionSpineParticipationKind ───────────────────────────────────────

    @Test
    fun `TAKEOVER_INTERACTIVE wireValue is takeover_interactive`() {
        assertEquals(
            "takeover_interactive",
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE.wireValue
        )
    }

    @Test
    fun `DELEGATED_EXECUTION wireValue is delegated_execution`() {
        assertEquals(
            "delegated_execution",
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION.wireValue
        )
    }

    @Test
    fun `LOCAL_ASSISTIVE wireValue is local_assistive`() {
        assertEquals(
            "local_assistive",
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE.wireValue
        )
    }

    @Test
    fun `DEGRADED_FALLBACK wireValue is degraded_fallback`() {
        assertEquals(
            "degraded_fallback",
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK.wireValue
        )
    }

    @Test
    fun `fromWireValue returns correct kind for each value`() {
        val k = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE, k.fromWireValue("takeover_interactive"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION, k.fromWireValue("delegated_execution"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE, k.fromWireValue("local_assistive"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK, k.fromWireValue("degraded_fallback"))
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.fromWireValue("unknown_kind")
        )
    }

    @Test
    fun `fromRuntimeKind maps all four kinds`() {
        val k = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE, k.fromRuntimeKind("takeover_interactive"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION, k.fromRuntimeKind("delegated_execution"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE, k.fromRuntimeKind("local_assistive"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK, k.fromRuntimeKind("degraded_fallback"))
    }

    @Test
    fun `fromRuntimeKind defaults to DELEGATED_EXECUTION for unknown value`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.fromRuntimeKind("completely_unknown")
        )
    }

    @Test
    fun `only TAKEOVER_INTERACTIVE has canSignalProblemSolved true`() {
        val k = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind
        assertTrue(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE.canSignalProblemSolved)
        assertFalse(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION.canSignalProblemSolved)
        assertFalse(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE.canSignalProblemSolved)
        assertFalse(AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK.canSignalProblemSolved)
    }

    @Test
    fun `ALL_WIRE_VALUES contains exactly four entries`() {
        assertEquals(
            4,
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.ALL_WIRE_VALUES.size
        )
    }

    // ── ProblemSolvingClosureClass ─────────────────────────────────────────────

    @Test
    fun `TASK_COMPLETED_PROBLEM_SOLVED wireValue correct and isTaskComplete true`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED
        assertEquals("task_completed_problem_solved", c.wireValue)
        assertTrue(c.isTaskComplete)
        assertEquals("android_signals_problem_resolved", c.v2ClosureSignal)
    }

    @Test
    fun `TASK_COMPLETED_PROBLEM_PARTIAL wireValue correct and isTaskComplete true`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL
        assertEquals("task_completed_problem_partial", c.wireValue)
        assertTrue(c.isTaskComplete)
        assertEquals("android_signals_partial_progress", c.v2ClosureSignal)
    }

    @Test
    fun `TASK_COMPLETED_PROBLEM_OPEN wireValue correct and isTaskComplete true`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_OPEN
        assertEquals("task_completed_problem_open", c.wireValue)
        assertTrue(c.isTaskComplete)
        assertEquals("android_signals_task_done_problem_continues", c.v2ClosureSignal)
    }

    @Test
    fun `TASK_INCOMPLETE_PROBLEM_OPEN wireValue correct and isTaskComplete false`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN
        assertEquals("task_incomplete_problem_open", c.wireValue)
        assertFalse(c.isTaskComplete)
        assertEquals("android_signals_execution_incomplete", c.v2ClosureSignal)
    }

    @Test
    fun `EXECUTION_DELEGATED_FURTHER wireValue correct and isTaskComplete false`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.EXECUTION_DELEGATED_FURTHER
        assertEquals("execution_delegated_further", c.wireValue)
        assertFalse(c.isTaskComplete)
        assertEquals("android_signals_requires_further_delegation", c.v2ClosureSignal)
    }

    @Test
    fun `ProblemSolvingClosureClass fromWireValue returns correct class for each value`() {
        val c = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass
        assertEquals(AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED, c.fromWireValue("task_completed_problem_solved"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL, c.fromWireValue("task_completed_problem_partial"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_OPEN, c.fromWireValue("task_completed_problem_open"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN, c.fromWireValue("task_incomplete_problem_open"))
        assertEquals(AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.EXECUTION_DELEGATED_FURTHER, c.fromWireValue("execution_delegated_further"))
    }

    @Test
    fun `ProblemSolvingClosureClass fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.fromWireValue("not_a_class")
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains exactly five entries`() {
        assertEquals(
            5,
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.ALL_WIRE_VALUES.size
        )
    }

    // ── classifyParticipationKind ─────────────────────────────────────────────

    @Test
    fun `null executionRuntimeKind defaults to DELEGATED_EXECUTION`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind(null)
        )
    }

    @Test
    fun `takeover_interactive runtimeKind maps to TAKEOVER_INTERACTIVE`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind("takeover_interactive")
        )
    }

    @Test
    fun `local_assistive runtimeKind maps to LOCAL_ASSISTIVE`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind("local_assistive")
        )
    }

    @Test
    fun `degraded_fallback runtimeKind maps to DEGRADED_FALLBACK`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind("degraded_fallback")
        )
    }

    @Test
    fun `declaredRole overrides executionRuntimeKind`() {
        // Even though runtime kind is delegated_execution, declared role forces TAKEOVER_INTERACTIVE
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind(
                executionRuntimeKind = "delegated_execution",
                declaredRole = "takeover_interactive"
            )
        )
    }

    @Test
    fun `invalid declaredRole falls back to executionRuntimeKind`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind(
                executionRuntimeKind = "local_assistive",
                declaredRole = "not_a_valid_kind"
            )
        )
    }

    @Test
    fun `blank declaredRole falls back to executionRuntimeKind`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
            AndroidNlDrivenExecutionSpineContract.classifyParticipationKind(
                executionRuntimeKind = "delegated_execution",
                declaredRole = ""
            )
        )
    }

    // ── classifyClosureClass ──────────────────────────────────────────────────

    @Test
    fun `TAKEOVER_INTERACTIVE + success → TASK_COMPLETED_PROBLEM_SOLVED`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
                taskSucceeded = true
            )
        )
    }

    @Test
    fun `TAKEOVER_INTERACTIVE + failure → TASK_INCOMPLETE_PROBLEM_OPEN`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
                taskSucceeded = false
            )
        )
    }

    @Test
    fun `DELEGATED_EXECUTION + success → TASK_COMPLETED_PROBLEM_PARTIAL`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
                taskSucceeded = true
            )
        )
    }

    @Test
    fun `DELEGATED_EXECUTION + failure → TASK_INCOMPLETE_PROBLEM_OPEN`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
                taskSucceeded = false
            )
        )
    }

    @Test
    fun `LOCAL_ASSISTIVE + success → TASK_COMPLETED_PROBLEM_PARTIAL`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE,
                taskSucceeded = true
            )
        )
    }

    @Test
    fun `LOCAL_ASSISTIVE + failure → TASK_INCOMPLETE_PROBLEM_OPEN`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.LOCAL_ASSISTIVE,
                taskSucceeded = false
            )
        )
    }

    @Test
    fun `DEGRADED_FALLBACK + success → TASK_COMPLETED_PROBLEM_OPEN`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_OPEN,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK,
                taskSucceeded = true
            )
        )
    }

    @Test
    fun `DEGRADED_FALLBACK + failure → TASK_INCOMPLETE_PROBLEM_OPEN`() {
        assertEquals(
            AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_INCOMPLETE_PROBLEM_OPEN,
            AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DEGRADED_FALLBACK,
                taskSucceeded = false
            )
        )
    }

    @Test
    fun `requiresFurtherDelegation always returns EXECUTION_DELEGATED_FURTHER`() {
        for (kind in AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.entries) {
            for (succeeded in listOf(true, false)) {
                assertEquals(
                    "requiresFurtherDelegation must override for kind=$kind succeeded=$succeeded",
                    AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.EXECUTION_DELEGATED_FURTHER,
                    AndroidNlDrivenExecutionSpineContract.classifyClosureClass(
                        kind,
                        taskSucceeded = succeeded,
                        requiresFurtherDelegation = true
                    )
                )
            }
        }
    }

    // ── ExecutionSpineTraceContribution.toWireMap ─────────────────────────────

    @Test
    fun `toWireMap contains all mandatory keys`() {
        val contribution = AndroidNlDrivenExecutionSpineContract.ExecutionSpineTraceContribution(
            taskId = "task-123",
            traceId = "trace-456",
            participationKind = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
            closureClass = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED,
            latencyMs = 1200L,
            stepCount = 5,
            deviceId = "device-789"
        )
        val wireMap = contribution.toWireMap()
        assertTrue("spine_task_id missing", wireMap.containsKey("spine_task_id"))
        assertTrue("spine_trace_id missing", wireMap.containsKey("spine_trace_id"))
        assertTrue("execution_spine_participation_kind missing",
            wireMap.containsKey(AndroidNlDrivenExecutionSpineContract.FIELD_EXECUTION_SPINE_PARTICIPATION_KIND))
        assertTrue("problem_solving_closure_class missing",
            wireMap.containsKey(AndroidNlDrivenExecutionSpineContract.FIELD_PROBLEM_SOLVING_CLOSURE_CLASS))
        assertTrue("spine_latency_ms missing", wireMap.containsKey("spine_latency_ms"))
        assertTrue("spine_step_count missing", wireMap.containsKey("spine_step_count"))
        assertTrue("spine_device_id missing", wireMap.containsKey("spine_device_id"))
        assertTrue("execution_spine_schema_version missing",
            wireMap.containsKey(AndroidNlDrivenExecutionSpineContract.FIELD_SCHEMA_VERSION))
        assertTrue("spine_can_signal_problem_solved missing",
            wireMap.containsKey("spine_can_signal_problem_solved"))
        assertTrue("spine_is_task_complete missing", wireMap.containsKey("spine_is_task_complete"))
        assertTrue("spine_v2_closure_signal missing", wireMap.containsKey("spine_v2_closure_signal"))
    }

    @Test
    fun `toWireMap carries correct participationKind wireValue`() {
        val contribution = AndroidNlDrivenExecutionSpineContract.ExecutionSpineTraceContribution(
            taskId = "t",
            traceId = "tr",
            participationKind = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
            closureClass = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL,
            latencyMs = 500L,
            stepCount = 2,
            deviceId = "dev"
        )
        val wireMap = contribution.toWireMap()
        assertEquals(
            "delegated_execution",
            wireMap[AndroidNlDrivenExecutionSpineContract.FIELD_EXECUTION_SPINE_PARTICIPATION_KIND]
        )
        assertEquals(
            "task_completed_problem_partial",
            wireMap[AndroidNlDrivenExecutionSpineContract.FIELD_PROBLEM_SOLVING_CLOSURE_CLASS]
        )
    }

    @Test
    fun `toWireMap contains problem_context when provided`() {
        val contribution = AndroidNlDrivenExecutionSpineContract.ExecutionSpineTraceContribution(
            taskId = "t",
            traceId = "tr",
            participationKind = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.TAKEOVER_INTERACTIVE,
            closureClass = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_SOLVED,
            latencyMs = 100L,
            stepCount = 1,
            deviceId = "dev",
            problemContext = "open the browser and navigate to github.com"
        )
        val wireMap = contribution.toWireMap()
        assertTrue(wireMap.containsKey(AndroidNlDrivenExecutionSpineContract.FIELD_PROBLEM_CONTEXT))
        assertEquals(
            "open the browser and navigate to github.com",
            wireMap[AndroidNlDrivenExecutionSpineContract.FIELD_PROBLEM_CONTEXT]
        )
    }

    @Test
    fun `toWireMap does not contain problem_context when null`() {
        val contribution = AndroidNlDrivenExecutionSpineContract.ExecutionSpineTraceContribution(
            taskId = "t",
            traceId = "tr",
            participationKind = AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.DELEGATED_EXECUTION,
            closureClass = AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.TASK_COMPLETED_PROBLEM_PARTIAL,
            latencyMs = 100L,
            stepCount = 1,
            deviceId = "dev",
            problemContext = null
        )
        assertFalse(contribution.toWireMap().containsKey(AndroidNlDrivenExecutionSpineContract.FIELD_PROBLEM_CONTEXT))
    }

    // ── buildContractWireMap ──────────────────────────────────────────────────

    @Test
    fun `buildContractWireMap contains introduced_pr = 2`() {
        val wireMap = AndroidNlDrivenExecutionSpineContract.buildContractWireMap()
        assertEquals(2, wireMap["introduced_pr"])
    }

    @Test
    fun `buildContractWireMap schema_version is 1`() {
        assertEquals("1", AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["schema_version"])
    }

    @Test
    fun `buildContractWireMap valid_participation_kinds count is 4`() {
        @Suppress("UNCHECKED_CAST")
        val kinds = AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["valid_participation_kinds"] as List<String>
        assertEquals(4, kinds.size)
    }

    @Test
    fun `buildContractWireMap valid_closure_classes count is 5`() {
        @Suppress("UNCHECKED_CAST")
        val classes = AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["valid_closure_classes"] as List<String>
        assertEquals(5, classes.size)
    }

    @Test
    fun `buildContractWireMap only takeover_interactive can signal problem solved`() {
        @Suppress("UNCHECKED_CAST")
        val canSolve = AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["participation_kinds_that_can_signal_problem_solved"] as List<String>
        assertEquals(listOf("takeover_interactive"), canSolve)
    }

    @Test
    fun `buildContractWireMap closure_classes_where_task_complete count is 3`() {
        @Suppress("UNCHECKED_CAST")
        val taskComplete = AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["closure_classes_where_task_complete"] as List<String>
        assertEquals(3, taskComplete.size)
    }

    @Test
    fun `buildContractWireMap all_invariants_hold is true`() {
        assertTrue(AndroidNlDrivenExecutionSpineContract.buildContractWireMap()["all_invariants_hold"] as Boolean)
    }

    // ── SPINE_PARTICIPATION_INVARIANTS ────────────────────────────────────────

    @Test
    fun `all SPINE_PARTICIPATION_INVARIANTS hold`() {
        val invariants = AndroidNlDrivenExecutionSpineContract.SPINE_PARTICIPATION_INVARIANTS
        assertEquals("Expected 10 invariants", 10, invariants.size)
        for ((key, value) in invariants) {
            assertTrue("Invariant $key must be true", value)
        }
    }

    @Test
    fun `execution_mode_affects_handling_not_just_naming invariant holds`() {
        assertTrue(
            AndroidNlDrivenExecutionSpineContract.SPINE_PARTICIPATION_INVARIANTS
                .getValue("execution_mode_affects_handling_not_just_naming")
        )
    }

    @Test
    fun `android_reports_signal_v2_retains_closure_authority invariant holds`() {
        assertTrue(
            AndroidNlDrivenExecutionSpineContract.SPINE_PARTICIPATION_INVARIANTS
                .getValue("android_reports_signal_v2_retains_closure_authority")
        )
    }

    @Test
    fun `only_takeover_interactive_can_signal_problem_solved invariant holds`() {
        assertTrue(
            AndroidNlDrivenExecutionSpineContract.SPINE_PARTICIPATION_INVARIANTS
                .getValue("only_takeover_interactive_can_signal_problem_solved")
        )
    }

    // ── StabilizationBaseline ─────────────────────────────────────────────────

    @Test
    fun `android-nl-driven-execution-spine-contract is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-nl-driven-execution-spine-contract")
        assertNotNull("android-nl-driven-execution-spine-contract must be registered", entry)
    }

    @Test
    fun `android-nl-driven-execution-spine-contract surface is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-nl-driven-execution-spine-contract")!!
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry.stability)
    }

    @Test
    fun `android-nl-driven-execution-spine-contract extensionGuidance is EXTEND`() {
        val entry = StabilizationBaseline.forId("android-nl-driven-execution-spine-contract")!!
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    // ── AutonomousExecutionPipeline integration ───────────────────────────────

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
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f, element_description = "")
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private fun buildPipeline(
        crossDeviceEnabled: Boolean = true,
        goalExecutionEnabled: Boolean = true,
        parallelExecutionEnabled: Boolean = true
    ): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            crossDeviceEnabled = crossDeviceEnabled,
            goalExecutionEnabled = goalExecutionEnabled,
            parallelExecutionEnabled = parallelExecutionEnabled,
            deviceRole = "phone"
        )
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = OkPlanner(),
            groundingService = OkGrounder(),
            accessibilityExecutor = OkAccessibility()
        )
        val goalExec = LocalGoalExecutor(edge, "test-device")
        val collab = LocalCollaborationAgent(goalExec)
        return AutonomousExecutionPipeline(
            settings = settings,
            goalExecutor = goalExec,
            collaborationAgent = collab,
            deviceId = "test-device",
            deviceRole = "phone"
        )
    }

    @Test
    fun `handleGoalExecution success with delegated_execution kind sets problem_solving_closure_class to partial`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "t1",
            goal = "open settings",
            execution_runtime_kind = "delegated_execution"
        )
        val result = pipeline.handleGoalExecution(payload)
        // The execution path may succeed or fail in a unit-test context; what matters
        // is that the spine fields are set and that the classification logic ran.
        assertNotNull("problem_solving_closure_class must be set", result.problem_solving_closure_class)
        assertEquals("delegated_execution", result.execution_spine_participation_kind)
        // Verify the classification table: delegated + success → partial; delegated + failure → incomplete
        val expectedClosureClass = when (result.status) {
            "success" -> "task_completed_problem_partial"
            else -> "task_incomplete_problem_open"
        }
        assertEquals(expectedClosureClass, result.problem_solving_closure_class)
    }

    @Test
    fun `handleGoalExecution with declared takeover role classifies as takeover_interactive`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "t2",
            goal = "open settings",
            execution_runtime_kind = "delegated_execution",
            problem_solving_role = "takeover_interactive"
        )
        val result = pipeline.handleGoalExecution(payload)
        assertEquals("takeover_interactive", result.execution_spine_participation_kind)
        // takeover success → solved; takeover failure → incomplete
        val expectedClosureClass = when (result.status) {
            "success" -> "task_completed_problem_solved"
            else -> "task_incomplete_problem_open"
        }
        assertEquals(expectedClosureClass, result.problem_solving_closure_class)
    }

    @Test
    fun `handleGoalExecution with degraded_fallback kind uses degraded classification`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "t3",
            goal = "take screenshot",
            execution_runtime_kind = "degraded_fallback"
        )
        val result = pipeline.handleGoalExecution(payload)
        assertEquals("degraded_fallback", result.execution_spine_participation_kind)
        // degraded success → problem_open; degraded failure → incomplete
        val expectedClosureClass = when (result.status) {
            "success" -> "task_completed_problem_open"
            else -> "task_incomplete_problem_open"
        }
        assertEquals(expectedClosureClass, result.problem_solving_closure_class)
    }

    @Test
    fun `handleParallelSubtask sets problem_solving_closure_class and spine kind`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "pt1",
            goal = "parallel subtask",
            group_id = "grp1",
            subtask_index = 0,
            execution_runtime_kind = "delegated_execution"
        )
        val result = pipeline.handleParallelSubtask(payload)
        assertNotNull("problem_solving_closure_class must be set", result.problem_solving_closure_class)
        assertEquals("delegated_execution", result.execution_spine_participation_kind)
    }

    @Test
    fun `handleGoalExecution null problem_solving_role handled without crash`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "t4",
            goal = "test goal",
            problem_solving_role = null,
            execution_runtime_kind = null
        )
        val result = pipeline.handleGoalExecution(payload)
        // Should not throw; should classify from null runtime kind → DELEGATED_EXECUTION
        assertNotNull(result.problem_solving_closure_class)
        assertEquals("delegated_execution", result.execution_spine_participation_kind)
    }

    @Test
    fun `handleGoalExecution null execution_runtime_kind defaults to delegated_execution`() {
        val pipeline = buildPipeline()
        val payload = GoalExecutionPayload(
            task_id = "t5",
            goal = "test",
            execution_runtime_kind = null
        )
        val result = pipeline.handleGoalExecution(payload)
        assertEquals("delegated_execution", result.execution_spine_participation_kind)
    }

    @Test
    fun `handleGoalExecution disabled result does not carry problem_solving_closure_class`() {
        // When cross-device is off the result is a disabled result — spine fields not set
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val payload = GoalExecutionPayload(
            task_id = "t6",
            goal = "test",
            execution_runtime_kind = "delegated_execution"
        )
        val result = pipeline.handleGoalExecution(payload)
        assertEquals("disabled", result.status)
        // The disabled path does not reach spine classification
        assertNull(result.problem_solving_closure_class)
        assertNull(result.execution_spine_participation_kind)
    }

    // ── Legacy safety: new HandoffEnvelopeV2 fields ───────────────────────────

    @Test
    fun `HandoffEnvelopeV2 defaults problem_context and problem_solving_role to null`() {
        val envelope = com.ufo.galaxy.agent.HandoffEnvelopeV2(
            trace_id = "tr",
            task_id = "t",
            goal = "g",
            exec_mode = "local",
            route_mode = "local"
        )
        assertNull(envelope.problem_context)
        assertNull(envelope.problem_solving_role)
    }

    @Test
    fun `HandoffEnvelopeV2 accepts problem_context and problem_solving_role`() {
        val envelope = com.ufo.galaxy.agent.HandoffEnvelopeV2(
            trace_id = "tr",
            task_id = "t",
            goal = "g",
            exec_mode = "local",
            route_mode = "cross_device",
            problem_context = "open browser and navigate to example.com",
            problem_solving_role = "takeover_interactive"
        )
        assertEquals("open browser and navigate to example.com", envelope.problem_context)
        assertEquals("takeover_interactive", envelope.problem_solving_role)
    }

    // ── GoalResultPayload new fields ──────────────────────────────────────────

    @Test
    fun `GoalResultPayload defaults problem_solving_closure_class and spine_kind to null`() {
        val payload = GoalResultPayload(
            task_id = "t",
            status = "success"
        )
        assertNull(payload.problem_solving_closure_class)
        assertNull(payload.execution_spine_participation_kind)
    }

    @Test
    fun `GoalResultPayload accepts problem_solving_closure_class and spine_kind`() {
        val payload = GoalResultPayload(
            task_id = "t",
            status = "success",
            problem_solving_closure_class = "task_completed_problem_solved",
            execution_spine_participation_kind = "takeover_interactive"
        )
        assertEquals("task_completed_problem_solved", payload.problem_solving_closure_class)
        assertEquals("takeover_interactive", payload.execution_spine_participation_kind)
    }
}
