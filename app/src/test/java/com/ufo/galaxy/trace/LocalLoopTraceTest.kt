package com.ufo.galaxy.trace

import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.local.StepObservation
import com.ufo.galaxy.nlp.NormalizedGoal
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalLoopTrace] and its supporting value objects.
 *
 * Covers:
 * - Default state of a freshly created trace.
 * - Thread-safe append helpers (recordPlan, recordGrounding, recordAction, recordStep).
 * - [LocalLoopTrace.complete] sets terminalResult and endTimeMs.
 * - Computed properties: [LocalLoopTrace.isRunning], [LocalLoopTrace.stepCount],
 *   [LocalLoopTrace.durationMs].
 */
class LocalLoopTraceTest {

    private fun buildTrace(
        sessionId: String = "session-1",
        goal: String = "Open Settings"
    ) = LocalLoopTrace(
        sessionId = sessionId,
        originalGoal = goal,
        startTimeMs = 1_000L
    )

    // ── Default state ─────────────────────────────────────────────────────────

    @Test
    fun `new trace is running`() {
        assertTrue(buildTrace().isRunning)
    }

    @Test
    fun `new trace has zero steps`() {
        assertEquals(0, buildTrace().stepCount)
    }

    @Test
    fun `new trace terminalResult is null`() {
        assertNull(buildTrace().terminalResult)
    }

    @Test
    fun `new trace durationMs is null`() {
        assertNull(buildTrace().durationMs)
    }

    @Test
    fun `new trace planOutputs is empty`() {
        assertTrue(buildTrace().planOutputs.isEmpty())
    }

    @Test
    fun `new trace groundingOutputs is empty`() {
        assertTrue(buildTrace().groundingOutputs.isEmpty())
    }

    @Test
    fun `new trace actionRecords is empty`() {
        assertTrue(buildTrace().actionRecords.isEmpty())
    }

    @Test
    fun `new trace stepObservations is empty`() {
        assertTrue(buildTrace().stepObservations.isEmpty())
    }

    @Test
    fun `normalizedGoal defaults to null`() {
        assertNull(buildTrace().normalizedGoal)
    }

    @Test
    fun `readinessSnapshot defaults to null`() {
        assertNull(buildTrace().readinessSnapshot)
    }

    // ── recordPlan ────────────────────────────────────────────────────────────

    @Test
    fun `recordPlan appends to planOutputs`() {
        val trace = buildTrace()
        val plan = PlanOutput(stepIndex = 0, isReplan = false, actionCount = 3, latencyMs = 250L)
        trace.recordPlan(plan)
        assertEquals(1, trace.planOutputs.size)
        assertEquals(plan, trace.planOutputs[0])
    }

    @Test
    fun `recordPlan marks replan correctly`() {
        val trace = buildTrace()
        trace.recordPlan(PlanOutput(stepIndex = 1, isReplan = true, actionCount = 2, latencyMs = 180L))
        assertTrue(trace.planOutputs[0].isReplan)
    }

    // ── recordGrounding ───────────────────────────────────────────────────────

    @Test
    fun `recordGrounding appends to groundingOutputs`() {
        val trace = buildTrace()
        val grounding = GroundingOutput(
            stepId = "step_1", actionType = "tap",
            confidence = 0.9f, targetFound = true, latencyMs = 80L
        )
        trace.recordGrounding(grounding)
        assertEquals(1, trace.groundingOutputs.size)
        assertEquals(grounding, trace.groundingOutputs[0])
    }

    // ── recordAction ──────────────────────────────────────────────────────────

    @Test
    fun `recordAction appends to actionRecords`() {
        val trace = buildTrace()
        val record = ActionRecord(
            stepId = "step_1", actionType = "tap",
            intent = "Tap the Settings icon",
            dispatchedAt = 2_000L, succeeded = true
        )
        trace.recordAction(record)
        assertEquals(1, trace.actionRecords.size)
        assertEquals(record, trace.actionRecords[0])
    }

    // ── recordStep ────────────────────────────────────────────────────────────

    @Test
    fun `recordStep increments stepCount`() {
        val trace = buildTrace()
        val obs = StepObservation.success(
            stepId = "step_1", actionType = "tap", intent = "Tap icon"
        )
        trace.recordStep(obs)
        assertEquals(1, trace.stepCount)
    }

    @Test
    fun `recordStep appends to stepObservations`() {
        val trace = buildTrace()
        val obs = StepObservation.success(
            stepId = "step_1", actionType = "tap", intent = "Tap icon"
        )
        trace.recordStep(obs)
        assertEquals(obs, trace.stepObservations[0])
    }

    @Test
    fun `multiple recordStep calls accumulate`() {
        val trace = buildTrace()
        repeat(5) { i ->
            trace.recordStep(StepObservation.success(
                stepId = "step_$i", actionType = "tap", intent = "Step $i"
            ))
        }
        assertEquals(5, trace.stepCount)
    }

    // ── complete ──────────────────────────────────────────────────────────────

    @Test
    fun `complete sets terminalResult`() {
        val trace = buildTrace()
        val result = TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 3
        )
        trace.complete(result)
        assertNotNull(trace.terminalResult)
        assertEquals(TerminalResult.STATUS_SUCCESS, trace.terminalResult!!.status)
    }

    @Test
    fun `complete sets endTimeMs`() {
        val trace = buildTrace()
        trace.complete(TerminalResult(
            status = TerminalResult.STATUS_FAILED,
            stopReason = "max_steps_reached",
            error = "Step budget exhausted",
            totalSteps = 10
        ))
        assertNotNull(trace.endTimeMs)
    }

    @Test
    fun `complete marks trace as not running`() {
        val trace = buildTrace()
        trace.complete(TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 2
        ))
        assertFalse(trace.isRunning)
    }

    @Test
    fun `durationMs is non-negative after complete`() {
        val trace = buildTrace()
        trace.complete(TerminalResult(
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = "task_complete",
            error = null,
            totalSteps = 1
        ))
        val duration = trace.durationMs
        assertNotNull(duration)
        assertTrue("durationMs should be >= 0", duration!! >= 0L)
    }

    // ── NormalizedGoal wiring ─────────────────────────────────────────────────

    @Test
    fun `normalizedGoal is stored when provided`() {
        val norm = NormalizedGoal(
            originalText = "Open Settings",
            normalizedText = "open settings",
            extractedConstraints = listOf("without ads")
        )
        val trace = LocalLoopTrace(
            sessionId = "session-norm",
            originalGoal = "Open Settings",
            normalizedGoal = norm
        )
        assertEquals(norm, trace.normalizedGoal)
    }

    // ── TerminalResult constants ──────────────────────────────────────────────

    @Test
    fun `TerminalResult STATUS_SUCCESS is success`() {
        assertEquals("success", TerminalResult.STATUS_SUCCESS)
    }

    @Test
    fun `TerminalResult STATUS_FAILED is failed`() {
        assertEquals("failed", TerminalResult.STATUS_FAILED)
    }

    @Test
    fun `TerminalResult STATUS_CANCELLED is cancelled`() {
        assertEquals("cancelled", TerminalResult.STATUS_CANCELLED)
    }
}
