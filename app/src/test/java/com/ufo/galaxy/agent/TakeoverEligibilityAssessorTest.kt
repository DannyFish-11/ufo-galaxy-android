package com.ufo.galaxy.agent

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [TakeoverEligibilityAssessor] — the canonical PR-3 evaluator for
 * Android-side takeover eligibility.
 *
 * ## Test matrix
 *
 * ### Full eligibility (all flags true)
 *  - Returns ELIGIBLE when all readiness flags are set and no concurrent takeover.
 *
 * ### Individual blocking conditions
 *  - BLOCKED_CROSS_DEVICE_DISABLED when crossDeviceEnabled=false.
 *  - BLOCKED_GOAL_EXECUTION_DISABLED when goalExecutionEnabled=false.
 *  - BLOCKED_ACCESSIBILITY_NOT_READY when accessibilityReady=false.
 *  - BLOCKED_OVERLAY_NOT_READY when overlayReady=false.
 *
 * ### Check ordering (first failing check wins)
 *  - cross_device_disabled takes precedence over goal_execution_disabled.
 *  - goal_execution_disabled takes precedence over accessibility_not_ready.
 *  - accessibility_not_ready takes precedence over overlay_not_ready.
 *  - overlay_not_ready takes precedence over concurrent_takeover_active.
 *
 * ### Concurrent takeover protection
 *  - BLOCKED_CONCURRENT_TAKEOVER when activeTakeoverId is non-null.
 *  - Reason string includes the active takeover ID for traceability.
 *  - activeTakeoverId=null clears concurrent block.
 *
 * ### EligibilityResult structure
 *  - ELIGIBLE result has eligible=true.
 *  - Non-eligible results have eligible=false.
 *  - ELIGIBLE reason string is "eligible".
 *  - Each blocking outcome has a stable reason string.
 *  - activeTakeoverId field is null except for BLOCKED_CONCURRENT_TAKEOVER.
 *
 * ### Posture transparency
 *  - Eligibility is not affected by source_runtime_posture (posture is informational only).
 *  - JOIN_RUNTIME posture does not grant or restrict eligibility.
 *  - CONTROL_ONLY posture does not block an otherwise eligible device.
 */
class TakeoverEligibilityAssessorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fullyReadySettings() = InMemoryAppSettings(
        crossDeviceEnabled = true,
        goalExecutionEnabled = true,
        accessibilityReady = true,
        overlayReady = true
    )

    private fun minimalEnvelope(posture: String? = null) = TakeoverRequestEnvelope(
        takeover_id = "to-test",
        task_id = "t-test",
        trace_id = "tr-test",
        goal = "open camera",
        source_runtime_posture = posture
    )

    // ── Full eligibility ──────────────────────────────────────────────────────

    @Test
    fun `all flags true and no concurrent takeover returns ELIGIBLE`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope())
        assertTrue("fully-ready device must be eligible", result.eligible)
        assertEquals(TakeoverEligibilityAssessor.EligibilityOutcome.ELIGIBLE, result.outcome)
        assertEquals("eligible", result.reason)
        assertNull("activeTakeoverId must be null for eligible result", result.activeTakeoverId)
    }

    // ── Individual blocking conditions ────────────────────────────────────────

    @Test
    fun `crossDeviceEnabled=false returns BLOCKED_CROSS_DEVICE_DISABLED`() {
        val settings = fullyReadySettings().also { it.crossDeviceEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            result.outcome
        )
        assertEquals("cross_device_disabled", result.reason)
    }

    @Test
    fun `goalExecutionEnabled=false returns BLOCKED_GOAL_EXECUTION_DISABLED`() {
        val settings = fullyReadySettings().also { it.goalExecutionEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED,
            result.outcome
        )
        assertEquals("goal_execution_disabled", result.reason)
    }

    @Test
    fun `accessibilityReady=false returns BLOCKED_ACCESSIBILITY_NOT_READY`() {
        val settings = fullyReadySettings().also { it.accessibilityReady = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY,
            result.outcome
        )
        assertEquals("accessibility_not_ready", result.reason)
    }

    @Test
    fun `overlayReady=false returns BLOCKED_OVERLAY_NOT_READY`() {
        val settings = fullyReadySettings().also { it.overlayReady = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY,
            result.outcome
        )
        assertEquals("overlay_not_ready", result.reason)
    }

    // ── Check ordering ────────────────────────────────────────────────────────

    @Test
    fun `cross_device_disabled takes precedence over goal_execution_disabled`() {
        val settings = fullyReadySettings().also {
            it.crossDeviceEnabled = false
            it.goalExecutionEnabled = false
        }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertEquals(
            "cross_device check must run before goal_execution check",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `goal_execution_disabled takes precedence over accessibility_not_ready`() {
        val settings = fullyReadySettings().also {
            it.goalExecutionEnabled = false
            it.accessibilityReady = false
        }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertEquals(
            "goal_execution check must run before accessibility check",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED,
            result.outcome
        )
    }

    @Test
    fun `accessibility_not_ready takes precedence over overlay_not_ready`() {
        val settings = fullyReadySettings().also {
            it.accessibilityReady = false
            it.overlayReady = false
        }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope())
        assertEquals(
            "accessibility check must run before overlay check",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY,
            result.outcome
        )
    }

    @Test
    fun `overlay_not_ready takes precedence over concurrent_takeover_active`() {
        val settings = fullyReadySettings().also { it.overlayReady = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = "other-takeover-id")
        assertEquals(
            "overlay check must run before concurrent-takeover check",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY,
            result.outcome
        )
    }

    // ── Concurrent takeover protection ────────────────────────────────────────

    @Test
    fun `activeTakeoverId non-null returns BLOCKED_CONCURRENT_TAKEOVER`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = "active-to-99")
        assertFalse(result.eligible)
        assertEquals(
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER,
            result.outcome
        )
    }

    @Test
    fun `BLOCKED_CONCURRENT_TAKEOVER reason includes active takeover ID`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = "active-to-99")
        assertTrue(
            "reason must include the active takeover ID for traceability",
            result.reason.contains("active-to-99")
        )
    }

    @Test
    fun `BLOCKED_CONCURRENT_TAKEOVER result carries activeTakeoverId field`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = "active-to-99")
        assertEquals(
            "activeTakeoverId must be echoed in the result for caller correlation",
            "active-to-99",
            result.activeTakeoverId
        )
    }

    @Test
    fun `activeTakeoverId=null does not block an otherwise eligible device`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(), activeTakeoverId = null)
        assertTrue("null activeTakeoverId must not block eligibility", result.eligible)
    }

    // ── EligibilityResult structure ───────────────────────────────────────────

    @Test
    fun `ELIGIBLE result has eligible=true`() {
        val result = TakeoverEligibilityAssessor(fullyReadySettings()).assess(minimalEnvelope())
        assertTrue(result.eligible)
    }

    @Test
    fun `non-eligible result has eligible=false`() {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertFalse(result.eligible)
    }

    @Test
    fun `activeTakeoverId is null for non-concurrent blocking outcomes`() {
        val settings = fullyReadySettings().also { it.goalExecutionEnabled = false }
        val result = TakeoverEligibilityAssessor(settings).assess(minimalEnvelope())
        assertNull(
            "activeTakeoverId must be null for non-concurrent blocking outcomes",
            result.activeTakeoverId
        )
    }

    @Test
    fun `EligibilityOutcome reason strings are stable`() {
        assertEquals("eligible", TakeoverEligibilityAssessor.EligibilityOutcome.ELIGIBLE.reason)
        assertEquals(
            "cross_device_disabled",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED.reason
        )
        assertEquals(
            "goal_execution_disabled",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED.reason
        )
        assertEquals(
            "accessibility_not_ready",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY.reason
        )
        assertEquals(
            "overlay_not_ready",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY.reason
        )
        assertEquals(
            "concurrent_takeover_active",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER.reason
        )
    }

    // ── Posture transparency ──────────────────────────────────────────────────

    @Test
    fun `JOIN_RUNTIME posture does not affect eligibility`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val joinResult = assessor.assess(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        assertTrue(
            "JOIN_RUNTIME posture must not change eligibility — posture is informational only",
            joinResult.eligible
        )
    }

    @Test
    fun `CONTROL_ONLY posture does not block an otherwise eligible device`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val ctrlResult = assessor.assess(minimalEnvelope(posture = SourceRuntimePosture.CONTROL_ONLY))
        assertTrue(
            "CONTROL_ONLY posture must not block an eligible device",
            ctrlResult.eligible
        )
    }

    @Test
    fun `null posture does not block an otherwise eligible device`() {
        val assessor = TakeoverEligibilityAssessor(fullyReadySettings())
        val result = assessor.assess(minimalEnvelope(posture = null))
        assertTrue("null posture must not block eligibility", result.eligible)
    }

    @Test
    fun `posture does not change BLOCKED outcome when device is not ready`() {
        val settings = fullyReadySettings().also { it.crossDeviceEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val joinResult = assessor.assess(minimalEnvelope(posture = SourceRuntimePosture.JOIN_RUNTIME))
        assertEquals(
            "JOIN_RUNTIME posture must not override a BLOCKED result",
            TakeoverEligibilityAssessor.EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED,
            joinResult.outcome
        )
    }

    // ── GalaxyConnectionService constant contract ─────────────────────────────

    @Test
    fun `TAKEOVER_DEFAULT_MAX_STEPS constant is positive`() {
        assertTrue(
            "TAKEOVER_DEFAULT_MAX_STEPS must be a positive step limit",
            com.ufo.galaxy.service.GalaxyConnectionService.TAKEOVER_DEFAULT_MAX_STEPS > 0
        )
    }

    @Test
    fun `TAKEOVER_DEFAULT_TIMEOUT_MS constant is zero or positive`() {
        assertTrue(
            "TAKEOVER_DEFAULT_TIMEOUT_MS must be >= 0",
            com.ufo.galaxy.service.GalaxyConnectionService.TAKEOVER_DEFAULT_TIMEOUT_MS >= 0L
        )
    }

    @Test
    fun `TAKEOVER_DEFAULT_MAX_STEPS is 10`() {
        assertEquals(10, com.ufo.galaxy.service.GalaxyConnectionService.TAKEOVER_DEFAULT_MAX_STEPS)
    }

    @Test
    fun `TAKEOVER_DEFAULT_TIMEOUT_MS is 0 (no timeout)`() {
        assertEquals(0L, com.ufo.galaxy.service.GalaxyConnectionService.TAKEOVER_DEFAULT_TIMEOUT_MS)
    }
}
