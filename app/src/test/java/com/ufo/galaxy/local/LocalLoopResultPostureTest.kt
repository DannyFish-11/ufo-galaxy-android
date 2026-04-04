package com.ufo.galaxy.local

import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [LocalLoopResult] canonical output normalization (PR-4).
 *
 * Covers:
 *  - [LocalLoopResult.source_runtime_posture] field presence and default.
 *  - [LocalLoopResult.STATUS_DISABLED] constant value and semantics.
 *  - [LocalLoopResult.isSuccess] is false for STATUS_DISABLED.
 *  - The five status constants are all distinct.
 */
class LocalLoopResultPostureTest {

    // ── STATUS_DISABLED constant ───────────────────────────────────────────────

    @Test
    fun `STATUS_DISABLED constant value is disabled`() {
        assertEquals("disabled", LocalLoopResult.STATUS_DISABLED)
    }

    @Test
    fun `STATUS_DISABLED is distinct from STATUS_FAILED`() {
        assertNotEquals(LocalLoopResult.STATUS_DISABLED, LocalLoopResult.STATUS_FAILED)
    }

    @Test
    fun `STATUS_DISABLED is distinct from STATUS_CANCELLED`() {
        assertNotEquals(LocalLoopResult.STATUS_DISABLED, LocalLoopResult.STATUS_CANCELLED)
    }

    @Test
    fun `STATUS_DISABLED is distinct from STATUS_SUCCESS`() {
        assertNotEquals(LocalLoopResult.STATUS_DISABLED, LocalLoopResult.STATUS_SUCCESS)
    }

    @Test
    fun `isSuccess is false for STATUS_DISABLED`() {
        val result = LocalLoopResult(
            sessionId = "s-1",
            instruction = "test",
            status = LocalLoopResult.STATUS_DISABLED,
            stepCount = 0,
            stopReason = "posture_control_only",
            error = "disabled by posture gate"
        )
        assertFalse(result.isSuccess)
    }

    // ── source_runtime_posture field ───────────────────────────────────────────

    @Test
    fun `source_runtime_posture defaults to null`() {
        val result = LocalLoopResult(
            sessionId = "s-2",
            instruction = "open camera",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 1,
            stopReason = null,
            error = null
        )
        assertNull(result.source_runtime_posture)
    }

    @Test
    fun `source_runtime_posture can be set to join_runtime`() {
        val result = LocalLoopResult(
            sessionId = "s-3",
            instruction = "open camera",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 1,
            stopReason = null,
            error = null,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, result.source_runtime_posture)
    }

    @Test
    fun `source_runtime_posture can be set to control_only`() {
        val result = LocalLoopResult(
            sessionId = "s-4",
            instruction = "send message",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 0,
            stopReason = "posture_control_only",
            error = "posture gate blocked",
            source_runtime_posture = SourceRuntimePosture.CONTROL_ONLY
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, result.source_runtime_posture)
    }

    @Test
    fun `cancelled result carries source_runtime_posture`() {
        val result = LocalLoopResult(
            sessionId = "s-5",
            instruction = "browse web",
            status = LocalLoopResult.STATUS_CANCELLED,
            stepCount = 2,
            stopReason = "cancelled_by_remote_task",
            error = null,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, result.source_runtime_posture)
        assertEquals(LocalLoopResult.STATUS_CANCELLED, result.status)
    }

    // ── All status constants are distinct ─────────────────────────────────────

    @Test
    fun `all four status constants are distinct`() {
        val statuses = setOf(
            LocalLoopResult.STATUS_SUCCESS,
            LocalLoopResult.STATUS_FAILED,
            LocalLoopResult.STATUS_CANCELLED,
            LocalLoopResult.STATUS_DISABLED
        )
        assertEquals(4, statuses.size)
    }

    // ── isSuccess stays false for all non-success statuses ────────────────────

    @Test
    fun `isSuccess is false for STATUS_FAILED`() {
        val result = LocalLoopResult(
            sessionId = "s-6",
            instruction = "do something",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 0,
            stopReason = "model_unavailable",
            error = "model not loaded"
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `isSuccess is false for STATUS_CANCELLED`() {
        val result = LocalLoopResult(
            sessionId = "s-7",
            instruction = "do something",
            status = LocalLoopResult.STATUS_CANCELLED,
            stepCount = 1,
            stopReason = null,
            error = null
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `isSuccess is true for STATUS_SUCCESS`() {
        val result = LocalLoopResult(
            sessionId = "s-8",
            instruction = "open WeChat",
            status = LocalLoopResult.STATUS_SUCCESS,
            stepCount = 3,
            stopReason = null,
            error = null
        )
        assertTrue(result.isSuccess)
    }
}
