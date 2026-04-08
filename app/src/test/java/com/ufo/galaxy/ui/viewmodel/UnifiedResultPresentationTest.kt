package com.ufo.galaxy.ui.viewmodel

import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.runtime.TakeoverFallbackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-26: Unified result presentation tests.
 *
 * Verifies that [UnifiedResultPresentation] normalizes all Android execution-path result
 * types into a single, path-agnostic presentation contract.  The tests guarantee that:
 *
 *  1. **Local path** — [LocalLoopResult] outcomes (success / failure / cancelled) are
 *     normalized correctly.
 *  2. **Cross-device / delegated path** — server-message results produce the same
 *     presentation structure (isSuccess=true, summary=message).
 *  3. **Fallback path** — all four [TakeoverFallbackEvent.Cause] variants (FAILED /
 *     TIMEOUT / CANCELLED / DISCONNECT) are normalized to consistent, path-opaque summaries.
 *  4. **No path-specific leakage** — summaries do not contain path labels such as
 *     "cross_device", "local", "delegated", or "takeover".
 *  5. **Stable outcome strings** — `outcome` values match the expected wire-level constants.
 *
 * ## Test matrix
 *
 * ### Local result normalization
 *  - local success produces isSuccess=true and non-empty summary
 *  - local success summary contains step count
 *  - local failure produces isSuccess=false
 *  - local failure summary contains error string
 *  - local cancellation produces isSuccess=false
 *  - local cancellation summary reflects cancelled status
 *  - local success outcome matches STATUS_SUCCESS constant
 *  - local failure outcome matches result status
 *  - local cancellation outcome matches STATUS_CANCELLED constant
 *
 * ### Server-message (cross-device / delegated) normalization
 *  - server message produces isSuccess=true
 *  - server message summary equals the raw message
 *  - server message outcome is "success"
 *  - empty server message still produces isSuccess=true
 *
 * ### Fallback (TakeoverFallbackEvent) normalization
 *  - FAILED cause produces isSuccess=false
 *  - TIMEOUT cause produces isSuccess=false
 *  - CANCELLED cause produces isSuccess=false
 *  - DISCONNECT cause produces isSuccess=false
 *  - FAILED outcome equals "failed" wireValue
 *  - TIMEOUT outcome equals "timeout" wireValue
 *  - CANCELLED outcome equals "cancelled" wireValue
 *  - DISCONNECT outcome equals "disconnect" wireValue
 *  - all fallback summaries are non-empty
 *
 * ### No path-specific leakage
 *  - local result summary does not contain "cross_device" or "delegated"
 *  - fallback summary does not contain "takeover" or "cross_device"
 *  - server message summary does not contain a path prefix
 *
 * ### Presentation consistency across paths
 *  - all failed paths produce isSuccess=false
 *  - all successful paths produce isSuccess=true
 */
class UnifiedResultPresentationTest {

    // ── Helper builders ───────────────────────────────────────────────────────

    private fun localSuccess(stepCount: Int = 3) = LocalLoopResult(
        sessionId = "sess-ok",
        instruction = "open settings",
        status = LocalLoopResult.STATUS_SUCCESS,
        stepCount = stepCount,
        stopReason = null,
        error = null
    )

    private fun localFailure(error: String? = "screenshot failed") = LocalLoopResult(
        sessionId = "sess-fail",
        instruction = "open settings",
        status = LocalLoopResult.STATUS_FAILED,
        stepCount = 1,
        stopReason = "model_error",
        error = error
    )

    private fun localCancelled(error: String? = "preempted") = LocalLoopResult(
        sessionId = "sess-cancel",
        instruction = "send message",
        status = LocalLoopResult.STATUS_CANCELLED,
        stepCount = 0,
        stopReason = null,
        error = error
    )

    private fun takeoverFailure(cause: TakeoverFallbackEvent.Cause) = TakeoverFallbackEvent(
        takeoverId = "to-1",
        taskId = "t-1",
        traceId = "tr-1",
        reason = "test_reason",
        cause = cause
    )

    // ── Local result normalization ─────────────────────────────────────────────

    @Test
    fun `local success produces isSuccess true`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertTrue(p.isSuccess)
    }

    @Test
    fun `local success summary is non-empty`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertTrue(p.summary.isNotBlank())
    }

    @Test
    fun `local success summary contains step count`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess(stepCount = 5))
        assertTrue("Expected step count in summary: ${p.summary}", p.summary.contains("5"))
    }

    @Test
    fun `local failure produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromLocalResult(localFailure())
        assertFalse(p.isSuccess)
    }

    @Test
    fun `local failure summary contains error string`() {
        val p = UnifiedResultPresentation.fromLocalResult(localFailure(error = "screenshot failed"))
        assertTrue("Expected error in summary: ${p.summary}", p.summary.contains("screenshot failed"))
    }

    @Test
    fun `local failure with no error uses stopReason`() {
        val result = LocalLoopResult(
            sessionId = "s",
            instruction = "g",
            status = LocalLoopResult.STATUS_FAILED,
            stepCount = 0,
            stopReason = "step_limit",
            error = null
        )
        val p = UnifiedResultPresentation.fromLocalResult(result)
        assertTrue("Expected stopReason in summary: ${p.summary}", p.summary.contains("step_limit"))
    }

    @Test
    fun `local cancellation produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromLocalResult(localCancelled())
        assertFalse(p.isSuccess)
    }

    @Test
    fun `local cancellation summary is non-empty`() {
        val p = UnifiedResultPresentation.fromLocalResult(localCancelled())
        assertTrue(p.summary.isNotBlank())
    }

    @Test
    fun `local cancellation summary contains error when present`() {
        val p = UnifiedResultPresentation.fromLocalResult(localCancelled(error = "preempted"))
        assertTrue("Expected error in summary: ${p.summary}", p.summary.contains("preempted"))
    }

    @Test
    fun `local success outcome matches STATUS_SUCCESS`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertEquals(LocalLoopResult.STATUS_SUCCESS, p.outcome)
    }

    @Test
    fun `local failure outcome matches result status`() {
        val p = UnifiedResultPresentation.fromLocalResult(localFailure())
        assertEquals(LocalLoopResult.STATUS_FAILED, p.outcome)
    }

    @Test
    fun `local cancellation outcome matches STATUS_CANCELLED`() {
        val p = UnifiedResultPresentation.fromLocalResult(localCancelled())
        assertEquals(LocalLoopResult.STATUS_CANCELLED, p.outcome)
    }

    // ── Server-message (cross-device / delegated) normalization ───────────────

    @Test
    fun `server message produces isSuccess true`() {
        val p = UnifiedResultPresentation.fromServerMessage("Task completed successfully.")
        assertTrue(p.isSuccess)
    }

    @Test
    fun `server message summary equals the raw message`() {
        val msg = "Setting changed: Wi-Fi enabled."
        val p = UnifiedResultPresentation.fromServerMessage(msg)
        assertEquals(msg, p.summary)
    }

    @Test
    fun `server message outcome is success`() {
        val p = UnifiedResultPresentation.fromServerMessage("ok")
        assertEquals("success", p.outcome)
    }

    @Test
    fun `empty server message still produces isSuccess true`() {
        val p = UnifiedResultPresentation.fromServerMessage("")
        assertTrue(p.isSuccess)
    }

    // ── Fallback (TakeoverFallbackEvent) normalization ────────────────────────

    @Test
    fun `FAILED cause produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.FAILED))
        assertFalse(p.isSuccess)
    }

    @Test
    fun `TIMEOUT cause produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.TIMEOUT))
        assertFalse(p.isSuccess)
    }

    @Test
    fun `CANCELLED cause produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.CANCELLED))
        assertFalse(p.isSuccess)
    }

    @Test
    fun `DISCONNECT cause produces isSuccess false`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT))
        assertFalse(p.isSuccess)
    }

    @Test
    fun `FAILED outcome equals failed wireValue`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.FAILED))
        assertEquals("failed", p.outcome)
    }

    @Test
    fun `TIMEOUT outcome equals timeout wireValue`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.TIMEOUT))
        assertEquals("timeout", p.outcome)
    }

    @Test
    fun `CANCELLED outcome equals cancelled wireValue`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.CANCELLED))
        assertEquals("cancelled", p.outcome)
    }

    @Test
    fun `DISCONNECT outcome equals disconnect wireValue`() {
        val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT))
        assertEquals("disconnect", p.outcome)
    }

    @Test
    fun `all fallback causes produce non-empty summary`() {
        TakeoverFallbackEvent.Cause.entries.forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(cause))
            assertTrue("Summary empty for cause $cause", p.summary.isNotBlank())
        }
    }

    // ── No path-specific leakage ──────────────────────────────────────────────

    @Test
    fun `local result summary does not contain cross_device`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertFalse("Local summary must not leak 'cross_device': ${p.summary}",
            p.summary.contains("cross_device", ignoreCase = true))
    }

    @Test
    fun `local result summary does not contain delegated`() {
        val p = UnifiedResultPresentation.fromLocalResult(localSuccess())
        assertFalse("Local summary must not leak 'delegated': ${p.summary}",
            p.summary.contains("delegated", ignoreCase = true))
    }

    @Test
    fun `fallback summary does not contain takeover`() {
        TakeoverFallbackEvent.Cause.entries.forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(cause))
            assertFalse("Fallback summary must not leak 'takeover': ${p.summary}",
                p.summary.contains("takeover", ignoreCase = true))
        }
    }

    @Test
    fun `fallback summary does not contain cross_device`() {
        TakeoverFallbackEvent.Cause.entries.forEach { cause ->
            val p = UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(cause))
            assertFalse("Fallback summary must not leak 'cross_device': ${p.summary}",
                p.summary.contains("cross_device", ignoreCase = true))
        }
    }

    @Test
    fun `server message summary does not add a path prefix`() {
        val msg = "Done."
        val p = UnifiedResultPresentation.fromServerMessage(msg)
        assertEquals("Server message summary must be the raw message without prefix", msg, p.summary)
    }

    // ── Presentation consistency across paths ─────────────────────────────────

    @Test
    fun `all failed paths produce isSuccess false`() {
        val failedResults = listOf(
            UnifiedResultPresentation.fromLocalResult(localFailure()),
            UnifiedResultPresentation.fromLocalResult(localCancelled()),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.FAILED)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.TIMEOUT)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.CANCELLED)),
            UnifiedResultPresentation.fromFallbackEvent(takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT))
        )
        failedResults.forEachIndexed { i, p ->
            assertFalse("Result[$i] should be isSuccess=false but was true: ${p.summary}", p.isSuccess)
        }
    }

    @Test
    fun `all successful paths produce isSuccess true`() {
        val successResults = listOf(
            UnifiedResultPresentation.fromLocalResult(localSuccess()),
            UnifiedResultPresentation.fromServerMessage("Task done."),
            UnifiedResultPresentation.fromServerMessage("Step complete.")
        )
        successResults.forEachIndexed { i, p ->
            assertTrue("Result[$i] should be isSuccess=true but was false: ${p.summary}", p.isSuccess)
        }
    }

    // ── Result identity stability ─────────────────────────────────────────────

    @Test
    fun `local success presentation is stable across identical inputs`() {
        val r = localSuccess(stepCount = 7)
        val p1 = UnifiedResultPresentation.fromLocalResult(r)
        val p2 = UnifiedResultPresentation.fromLocalResult(r)
        assertEquals("Presentations from identical inputs must be equal", p1, p2)
    }

    @Test
    fun `fallback presentation is stable across identical events`() {
        val event = takeoverFailure(TakeoverFallbackEvent.Cause.DISCONNECT)
        val p1 = UnifiedResultPresentation.fromFallbackEvent(event)
        val p2 = UnifiedResultPresentation.fromFallbackEvent(event)
        assertEquals("Presentations from identical events must be equal", p1, p2)
    }

    @Test
    fun `server message presentation is stable across identical messages`() {
        val msg = "All done."
        val p1 = UnifiedResultPresentation.fromServerMessage(msg)
        val p2 = UnifiedResultPresentation.fromServerMessage(msg)
        assertEquals("Presentations from identical messages must be equal", p1, p2)
    }
}
