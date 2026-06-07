package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalRuntimeContext] — per-task runtime context carrying
 * [SourceRuntimePosture] semantics for Android-local task execution.
 *
 * Test matrix:
 *  - [LocalRuntimeContext.of] normalises raw posture values via [SourceRuntimePosture.fromValue].
 *  - [LocalRuntimeContext.defaultFor] creates a safe-default context (control_only).
 *  - [LocalRuntimeContext.isJoinRuntime] and [isControlOnly] convenience properties are correct.
 *  - Null and unknown posture values are handled safely by the factory.
 *  - Data-class structural equality works as expected.
 */
class LocalRuntimeContextTest {

    // ── Factory: of() ─────────────────────────────────────────────────────────

    @Test
    fun `of() with join_runtime posture sets sourceRuntimePosture correctly`() {
        val ctx = LocalRuntimeContext.of(
            taskId = "t1",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, ctx.sourceRuntimePosture)
    }

    @Test
    fun `of() with control_only posture sets sourceRuntimePosture correctly`() {
        val ctx = LocalRuntimeContext.of(
            taskId = "t1",
            sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, ctx.sourceRuntimePosture)
    }

    @Test
    fun `of() with null posture defaults to control_only`() {
        val ctx = LocalRuntimeContext.of(taskId = "t1", sourceRuntimePosture = null)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, ctx.sourceRuntimePosture)
    }

    @Test
    fun `of() with unknown posture defaults to control_only`() {
        val ctx = LocalRuntimeContext.of(taskId = "t1", sourceRuntimePosture = "future_value")
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, ctx.sourceRuntimePosture)
    }

    @Test
    fun `of() propagates all optional fields correctly`() {
        val ctx = LocalRuntimeContext.of(
            taskId = "task-99",
            sessionId = "sess-42",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            traceId = "trace-abc",
            deviceRole = "tablet"
        )
        assertEquals("task-99", ctx.taskId)
        assertEquals("sess-42", ctx.sessionId)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, ctx.sourceRuntimePosture)
        assertEquals("trace-abc", ctx.traceId)
        assertEquals("tablet", ctx.deviceRole)
    }

    // ── Factory: defaultFor() ─────────────────────────────────────────────────

    @Test
    fun `defaultFor() creates context with control_only posture`() {
        val ctx = LocalRuntimeContext.defaultFor("task-default")
        assertEquals("task-default", ctx.taskId)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, ctx.sourceRuntimePosture)
        assertNull(ctx.sessionId)
        assertNull(ctx.traceId)
        assertNull(ctx.deviceRole)
    }

    // ── Convenience properties ────────────────────────────────────────────────

    @Test
    fun `isJoinRuntime is true when posture is join_runtime`() {
        val ctx = LocalRuntimeContext.of(taskId = "t", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)
        assertTrue(ctx.isJoinRuntime)
        assertFalse(ctx.isControlOnly)
    }

    @Test
    fun `isControlOnly is true when posture is control_only`() {
        val ctx = LocalRuntimeContext.of(taskId = "t", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)
        assertTrue(ctx.isControlOnly)
        assertFalse(ctx.isJoinRuntime)
    }

    @Test
    fun `isControlOnly is true for default context`() {
        val ctx = LocalRuntimeContext.defaultFor("t")
        assertTrue(ctx.isControlOnly)
        assertFalse(ctx.isJoinRuntime)
    }

    // ── Data-class equality ───────────────────────────────────────────────────

    @Test
    fun `two contexts with same fields are equal`() {
        val a = LocalRuntimeContext.of(
            taskId = "same-task",
            sessionId = "s1",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        val b = LocalRuntimeContext.of(
            taskId = "same-task",
            sessionId = "s1",
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(a, b)
    }

    @Test
    fun `two contexts with different postures are not equal`() {
        val a = LocalRuntimeContext.of(taskId = "t", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)
        val b = LocalRuntimeContext.of(taskId = "t", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)
        assertNotEquals(a, b)
    }
}
