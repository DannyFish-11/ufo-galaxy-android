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
    fun `单条感知通道缺失不致命，两条都缺才致命`() {
        // 这两条原本各自 isCritical=true —— 于是只要截不了图，整条闭环就被判死。
        // 可截图在三种真实情况下本来就拿不到：设备 API < 30（takeScreenshot 是 API 30
        // 才引入的，而本模块 minSdk 26）、当前是 FLAG_SECURE 窗口（银行/密码页）、
        // 撞上平台的 333ms 节流。这些时候只要无障碍树读得到，定位走树救场、
        // 变化检测走树指纹，闭环完全跑得动。
        //
        // 把「截不了图」当成「跑不了」，等于在 minSdk 与实际可用区间之间凭空挖了一个洞。
        assertFalse(
            "截不了图不等于跑不了 —— 树还在时闭环成立",
            LocalLoopFailureType.SCREENSHOT_UNAVAILABLE.isCritical,
        )
        assertFalse(
            "读不到树也不单独致命 —— 有截图 + 有视觉模型时纯视觉路径照样跑",
            LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED.isCritical,
        )
    }

    @Test
    fun `只有截图没有树，照样可以跑`() {
        val r = LocalLoopReadiness(
            modelFilesReady = true, plannerLoaded = true, groundingLoaded = true,
            accessibilityReady = false, screenshotReady = true, actionExecutorReady = true,
            blockers = listOf(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED),
        )

        assertEquals(LocalLoopState.DEGRADED, r.state)
    }

    @Test
    fun `只有树没有截图，照样可以跑`() {
        // 这就是 API 26~29 的设备、以及任何 FLAG_SECURE 页面上的真实形态。
        val r = LocalLoopReadiness(
            modelFilesReady = false, plannerLoaded = false, groundingLoaded = false,
            accessibilityReady = true, screenshotReady = false, actionExecutorReady = true,
            blockers = listOf(
                LocalLoopFailureType.MODEL_FILES_MISSING,
                LocalLoopFailureType.PLANNER_UNAVAILABLE,
                LocalLoopFailureType.GROUNDING_UNAVAILABLE,
                LocalLoopFailureType.SCREENSHOT_UNAVAILABLE,
            ),
        )

        assertEquals(
            "没有模型、没有截图，但树读得到 —— 这正是「装上就能跑」的那条路",
            LocalLoopState.DEGRADED, r.state,
        )
    }

    @Test
    fun `两条感知通道都没有就不许跑`() {
        val r = LocalLoopReadiness(
            modelFilesReady = true, plannerLoaded = true, groundingLoaded = true,
            accessibilityReady = false, screenshotReady = false, actionExecutorReady = true,
            blockers = listOf(
                LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED,
                LocalLoopFailureType.SCREENSHOT_UNAVAILABLE,
            ),
        )

        assertEquals(
            "看不见屏幕却还让它跑，就是让它闭着眼睛点",
            LocalLoopState.UNAVAILABLE, r.state,
        )
    }

    @Test
    fun `没有执行通道就不许跑`() {
        val r = LocalLoopReadiness(
            modelFilesReady = true, plannerLoaded = true, groundingLoaded = true,
            accessibilityReady = true, screenshotReady = true, actionExecutorReady = false,
            blockers = listOf(LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE),
        )

        assertEquals(LocalLoopState.UNAVAILABLE, r.state)
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
