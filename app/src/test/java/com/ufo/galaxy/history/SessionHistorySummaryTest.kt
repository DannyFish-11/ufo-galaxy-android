package com.ufo.galaxy.history

import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.TerminalResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SessionHistorySummary].
 *
 * Verifies the data model semantics, [SessionHistorySummary.fromTrace] factory, and
 * field correctness for both happy-path and edge cases.
 */
class SessionHistorySummaryTest {

    // ── fromTrace ─────────────────────────────────────────────────────────────

    @Test
    fun `fromTrace returns null for running trace`() {
        val trace = LocalLoopTrace(sessionId = "s1", originalGoal = "tap save")
        // no complete() called → isRunning = true
        assertNull(SessionHistorySummary.fromTrace(trace))
    }

    @Test
    fun `fromTrace returns non-null for completed trace`() {
        val trace = completedTrace(
            sessionId = "s2",
            goal = "open settings",
            status = TerminalResult.STATUS_SUCCESS,
            steps = 3
        )
        assertNotNull(SessionHistorySummary.fromTrace(trace))
    }

    @Test
    fun `fromTrace copies sessionId and originalGoal`() {
        val trace = completedTrace(sessionId = "abc-123", goal = "send message")
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertEquals("abc-123", summary.sessionId)
        assertEquals("send message", summary.originalGoal)
    }

    @Test
    fun `fromTrace copies terminal status and stopReason`() {
        val trace = completedTrace(
            status = TerminalResult.STATUS_FAILED,
            stopReason = "timeout",
            error = "step_timeout"
        )
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertEquals(TerminalResult.STATUS_FAILED, summary.status)
        assertEquals("timeout", summary.stopReason)
        assertEquals("step_timeout", summary.error)
    }

    @Test
    fun `fromTrace sets error to null for successful trace`() {
        val trace = completedTrace(status = TerminalResult.STATUS_SUCCESS)
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertNull(summary.error)
    }

    @Test
    fun `fromTrace computes durationMs`() {
        val start = 1_000_000L
        val trace = LocalLoopTrace(
            sessionId = "dur",
            originalGoal = "g",
            startTimeMs = start
        )
        trace.complete(TerminalResult(TerminalResult.STATUS_SUCCESS, null, null, 0))
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertTrue("durationMs should be >= 0", summary.durationMs >= 0)
        assertEquals(summary.endTimeMs - summary.startTimeMs, summary.durationMs)
    }

    @Test
    fun `fromTrace copies stepCount`() {
        val trace = completedTrace(steps = 5)
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertEquals(5, summary.stepCount)
    }

    @Test
    fun `fromTrace copies planCount and actionCount`() {
        val trace = LocalLoopTrace(sessionId = "pc", originalGoal = "g")
        trace.recordPlan(com.ufo.galaxy.trace.PlanOutput(0, false, 2, 100))
        trace.recordPlan(com.ufo.galaxy.trace.PlanOutput(3, true, 1, 80))
        trace.recordAction(com.ufo.galaxy.trace.ActionRecord("s1", "tap", "intent", System.currentTimeMillis(), true))
        trace.complete(TerminalResult(TerminalResult.STATUS_SUCCESS, null, null, 1))
        val summary = SessionHistorySummary.fromTrace(trace)!!
        assertEquals(2, summary.planCount)
        assertEquals(1, summary.actionCount)
    }

    // ── Data class copy semantics ─────────────────────────────────────────────

    @Test
    fun `copy preserves all fields`() {
        val original = SessionHistorySummary(
            sessionId = "id",
            originalGoal = "goal",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            durationMs = 1000L,
            stepCount = 3,
            status = TerminalResult.STATUS_SUCCESS,
            stopReason = null,
            error = null,
            planCount = 1,
            actionCount = 2,
            savedAtMs = 9000L
        )
        val copy = original.copy(status = TerminalResult.STATUS_CANCELLED)
        assertEquals(TerminalResult.STATUS_CANCELLED, copy.status)
        assertEquals(original.sessionId, copy.sessionId)
        assertEquals(original.stepCount, copy.stepCount)
    }

    @Test
    fun `equality based on all fields`() {
        val a = SessionHistorySummary(
            sessionId = "x", originalGoal = "g", startTimeMs = 1L, endTimeMs = 2L,
            durationMs = 1L, stepCount = 0, status = "success", stopReason = null,
            error = null, planCount = 0, actionCount = 0, savedAtMs = 100L
        )
        val b = a.copy()
        assertEquals(a, b)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun completedTrace(
        sessionId: String = "test-session",
        goal: String = "do something",
        status: String = TerminalResult.STATUS_SUCCESS,
        stopReason: String? = null,
        error: String? = null,
        steps: Int = 2
    ): LocalLoopTrace {
        val trace = LocalLoopTrace(sessionId = sessionId, originalGoal = goal)
        repeat(steps) { i ->
            trace.recordStep(
                com.ufo.galaxy.local.StepObservation.success(
                    stepId = "step_$i",
                    actionType = "tap",
                    intent = "tap target $i"
                )
            )
        }
        trace.complete(TerminalResult(status, stopReason, error, steps))
        return trace
    }
}
