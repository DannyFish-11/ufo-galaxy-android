package com.ufo.galaxy.loop

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ActionStep], [ActionSequence], [LoopResult], and [LoopStatus].
 *
 * These tests verify that the data structures carry the correct default values,
 * support copy-and-update semantics, and compose into well-formed sequences.
 * No Android framework classes are required; all tests run on the JVM.
 */
class ActionSequenceTest {

    // ── ActionStep ────────────────────────────────────────────────────────────

    @Test
    fun `ActionStep - default status is PENDING`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        assertEquals(StepStatus.PENDING, step.status)
    }

    @Test
    fun `ActionStep - default retries is 0`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        assertEquals(0, step.retries)
    }

    @Test
    fun `ActionStep - default confidence is 0`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        assertEquals(0f, step.confidence)
    }

    @Test
    fun `ActionStep - default failureReason is null`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        assertNull(step.failureReason)
    }

    @Test
    fun `ActionStep - default parameters is empty`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        assertTrue(step.parameters.isEmpty())
    }

    @Test
    fun `ActionStep - copy updates status to SUCCESS`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        val done = step.copy(status = StepStatus.SUCCESS, confidence = 0.95f)
        assertEquals(StepStatus.SUCCESS, done.status)
        assertEquals(0.95f, done.confidence)
    }

    @Test
    fun `ActionStep - copy updates status to FAILED with failureReason`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        val failed = step.copy(status = StepStatus.FAILED, failureReason = "no element found")
        assertEquals(StepStatus.FAILED, failed.status)
        assertEquals("no element found", failed.failureReason)
    }

    @Test
    fun `ActionStep - copy increments retries`() {
        val step = ActionStep(id = "step_1", actionType = "tap", intent = "tap the button")
        val retried = step.copy(retries = step.retries + 1)
        assertEquals(1, retried.retries)
    }

    @Test
    fun `ActionStep - parameters are preserved in copy`() {
        val step = ActionStep(
            id = "step_1",
            actionType = "type",
            intent = "enter text",
            parameters = mapOf("text" to "hello world")
        )
        val updated = step.copy(status = StepStatus.SUCCESS)
        assertEquals("hello world", updated.parameters["text"])
    }

    @Test
    fun `ActionStep - all StepStatus values are distinct`() {
        val values = StepStatus.values()
        assertEquals(values.size, values.toSet().size)
    }

    // ── ActionSequence ────────────────────────────────────────────────────────

    @Test
    fun `ActionSequence - stores instruction and sessionId`() {
        val seq = ActionSequence(
            sessionId = "sess-001",
            instruction = "open settings",
            steps = emptyList()
        )
        assertEquals("sess-001", seq.sessionId)
        assertEquals("open settings", seq.instruction)
    }

    @Test
    fun `ActionSequence - empty steps list`() {
        val seq = ActionSequence(sessionId = "s", instruction = "i", steps = emptyList())
        assertTrue(seq.steps.isEmpty())
    }

    @Test
    fun `ActionSequence - multi-step sequence preserves order`() {
        val s1 = ActionStep(id = "step_1", actionType = "tap", intent = "tap A")
        val s2 = ActionStep(id = "step_2", actionType = "scroll", intent = "scroll down")
        val s3 = ActionStep(id = "step_3", actionType = "type", intent = "type text")
        val seq = ActionSequence(sessionId = "s", instruction = "i", steps = listOf(s1, s2, s3))
        assertEquals(3, seq.steps.size)
        assertEquals("step_1", seq.steps[0].id)
        assertEquals("step_2", seq.steps[1].id)
        assertEquals("step_3", seq.steps[2].id)
    }

    @Test
    fun `ActionSequence - each step carries its own actionType`() {
        val steps = listOf(
            ActionStep(id = "1", actionType = "tap",    intent = "tap"),
            ActionStep(id = "2", actionType = "scroll", intent = "scroll"),
            ActionStep(id = "3", actionType = "type",   intent = "type"),
            ActionStep(id = "4", actionType = "back",   intent = "back"),
            ActionStep(id = "5", actionType = "home",   intent = "home")
        )
        val seq = ActionSequence(sessionId = "s", instruction = "i", steps = steps)
        val types = seq.steps.map { it.actionType }
        assertEquals(listOf("tap", "scroll", "type", "back", "home"), types)
    }

    // ── LoopResult ────────────────────────────────────────────────────────────

    @Test
    fun `LoopResult - success status with no error`() {
        val result = LoopResult(
            sessionId = "sess-1",
            instruction = "do something",
            status = LoopController.STATUS_SUCCESS,
            steps = emptyList(),
            stopReason = LoopController.STOP_TASK_COMPLETE
        )
        assertEquals(LoopController.STATUS_SUCCESS, result.status)
        assertNull(result.error)
        assertEquals(LoopController.STOP_TASK_COMPLETE, result.stopReason)
    }

    @Test
    fun `LoopResult - failed status carries error message`() {
        val result = LoopResult(
            sessionId = "sess-2",
            instruction = "do something",
            status = LoopController.STATUS_FAILED,
            steps = emptyList(),
            stopReason = LoopController.STOP_SCREENSHOT_FAILED,
            error = "capture error"
        )
        assertEquals(LoopController.STATUS_FAILED, result.status)
        assertEquals("capture error", result.error)
    }

    @Test
    fun `LoopResult - steps list is preserved`() {
        val steps = listOf(
            ActionStep("1", "tap", "a", status = StepStatus.SUCCESS),
            ActionStep("2", "scroll", "b", status = StepStatus.FAILED)
        )
        val result = LoopResult(
            sessionId = "s",
            instruction = "i",
            status = LoopController.STATUS_FAILED,
            steps = steps
        )
        assertEquals(2, result.steps.size)
        assertEquals(StepStatus.SUCCESS, result.steps[0].status)
        assertEquals(StepStatus.FAILED, result.steps[1].status)
    }

    // ── LoopStatus ────────────────────────────────────────────────────────────

    @Test
    fun `LoopStatus Idle is distinct object`() {
        val a = LoopStatus.Idle
        val b = LoopStatus.Idle
        assertEquals(a, b)
    }

    @Test
    fun `LoopStatus Running carries sessionId and step info`() {
        val status = LoopStatus.Running(
            sessionId = "s",
            stepIndex = 3,
            totalSteps = 10,
            currentAction = "tap the button"
        )
        assertEquals("s", status.sessionId)
        assertEquals(3, status.stepIndex)
        assertEquals(10, status.totalSteps)
        assertEquals("tap the button", status.currentAction)
    }

    @Test
    fun `LoopStatus Done carries stepCount and summary`() {
        val status = LoopStatus.Done(sessionId = "s", stepCount = 5, summary = "done")
        assertEquals(5, status.stepCount)
        assertEquals("done", status.summary)
    }

    @Test
    fun `LoopStatus Failed carries reason and stepIndex`() {
        val status = LoopStatus.Failed(sessionId = "s", reason = "screenshot failed", stepIndex = 2)
        assertEquals("screenshot failed", status.reason)
        assertEquals(2, status.stepIndex)
    }
}
