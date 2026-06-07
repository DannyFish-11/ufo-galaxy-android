package com.ufo.galaxy.local

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalLoopReadiness], [LocalLoopFailureType], and [LocalLoopState].
 *
 * All logic under test is pure Kotlin; no Android framework required.
 */
class LocalLoopReadinessTest {

    // ── isFullyReady ──────────────────────────────────────────────────────────

    @Test
    fun `isFullyReady is true when all subsystems are ready`() {
        val r = allReady()
        assertTrue(r.isFullyReady)
    }

    @Test
    fun `isFullyReady is false when modelFilesReady is false`() {
        assertFalse(allReady().copy(modelFilesReady = false).isFullyReady)
    }

    @Test
    fun `isFullyReady is false when plannerLoaded is false`() {
        assertFalse(allReady().copy(plannerLoaded = false).isFullyReady)
    }

    @Test
    fun `isFullyReady is false when groundingLoaded is false`() {
        assertFalse(allReady().copy(groundingLoaded = false).isFullyReady)
    }

    @Test
    fun `isFullyReady is false when accessibilityReady is false`() {
        assertFalse(allReady().copy(accessibilityReady = false).isFullyReady)
    }

    @Test
    fun `isFullyReady is false when screenshotReady is false`() {
        assertFalse(allReady().copy(screenshotReady = false).isFullyReady)
    }

    @Test
    fun `isFullyReady is false when actionExecutorReady is false`() {
        assertFalse(allReady().copy(actionExecutorReady = false).isFullyReady)
    }

    // ── state derivation ──────────────────────────────────────────────────────

    @Test
    fun `state is READY when isFullyReady`() {
        assertEquals(LocalLoopState.READY, allReady().state)
    }

    @Test
    fun `state is DEGRADED when only non-critical blockers present`() {
        val r = allReady().copy(
            modelFilesReady = false,
            plannerLoaded = false,
            blockers = listOf(
                LocalLoopFailureType.MODEL_FILES_MISSING,
                LocalLoopFailureType.PLANNER_UNAVAILABLE
            )
        )
        assertEquals(LocalLoopState.DEGRADED, r.state)
    }

    @Test
    fun `state is UNAVAILABLE when any critical blocker is present`() {
        val r = allReady().copy(
            accessibilityReady = false,
            screenshotReady = false,
            actionExecutorReady = false,
            blockers = listOf(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED)
        )
        assertEquals(LocalLoopState.UNAVAILABLE, r.state)
    }

    @Test
    fun `state is UNAVAILABLE even with mixed critical and non-critical blockers`() {
        val r = allReady().copy(
            modelFilesReady = false,
            accessibilityReady = false,
            screenshotReady = false,
            actionExecutorReady = false,
            blockers = listOf(
                LocalLoopFailureType.MODEL_FILES_MISSING,
                LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED,
                LocalLoopFailureType.SCREENSHOT_UNAVAILABLE,
                LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE
            )
        )
        assertEquals(LocalLoopState.UNAVAILABLE, r.state)
    }

    // ── LocalLoopFailureType criticality ─────────────────────────────────────

    @Test
    fun `MODEL_FILES_MISSING is not critical`() {
        assertFalse(LocalLoopFailureType.MODEL_FILES_MISSING.isCritical)
    }

    @Test
    fun `PLANNER_UNAVAILABLE is not critical`() {
        assertFalse(LocalLoopFailureType.PLANNER_UNAVAILABLE.isCritical)
    }

    @Test
    fun `GROUNDING_UNAVAILABLE is not critical`() {
        assertFalse(LocalLoopFailureType.GROUNDING_UNAVAILABLE.isCritical)
    }

    @Test
    fun `ACCESSIBILITY_SERVICE_DISABLED is critical`() {
        assertTrue(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED.isCritical)
    }

    @Test
    fun `SCREENSHOT_UNAVAILABLE is critical`() {
        assertTrue(LocalLoopFailureType.SCREENSHOT_UNAVAILABLE.isCritical)
    }

    @Test
    fun `ACTION_EXECUTOR_UNAVAILABLE is critical`() {
        assertTrue(LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE.isCritical)
    }

    // ── unavailable factory ───────────────────────────────────────────────────

    @Test
    fun `unavailable factory returns all-false readiness`() {
        val r = LocalLoopReadiness.unavailable()
        assertFalse(r.modelFilesReady)
        assertFalse(r.plannerLoaded)
        assertFalse(r.groundingLoaded)
        assertFalse(r.accessibilityReady)
        assertFalse(r.screenshotReady)
        assertFalse(r.actionExecutorReady)
        assertFalse(r.isFullyReady)
    }

    @Test
    fun `unavailable factory state is UNAVAILABLE`() {
        assertEquals(LocalLoopState.UNAVAILABLE, LocalLoopReadiness.unavailable().state)
    }

    @Test
    fun `unavailable factory contains all failure types`() {
        val blockers = LocalLoopReadiness.unavailable().blockers
        val allTypes = LocalLoopFailureType.entries
        assertTrue(
            "Expected all failure types, missing: ${allTypes - blockers.toSet()}",
            blockers.containsAll(allTypes)
        )
    }

    // ── data-class equality and copy ──────────────────────────────────────────

    @Test
    fun `equal readiness instances are equal`() {
        assertEquals(allReady(), allReady())
    }

    @Test
    fun `copy with single field change is not equal to original`() {
        val original = allReady()
        val modified = original.copy(plannerLoaded = false)
        assertNotEquals(original, modified)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun allReady() = LocalLoopReadiness(
        modelFilesReady = true,
        plannerLoaded = true,
        groundingLoaded = true,
        accessibilityReady = true,
        screenshotReady = true,
        actionExecutorReady = true,
        blockers = emptyList()
    )
}
