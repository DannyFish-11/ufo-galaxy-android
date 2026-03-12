package com.ufo.galaxy.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ReadinessState].
 *
 * [ReadinessChecker] methods rely on the Android framework and [UFOGalaxyApplication],
 * so they are tested through integration/manual verification described in README.md.
 * This file covers the pure-Kotlin [ReadinessState] logic that can be verified without
 * the Android runtime.
 *
 * ## Manual verification steps for ReadinessChecker
 *
 * 1. **Model self-check:**
 *    - Launch app without model files in internal storage → "本地模型未就绪" banner appears.
 *    - Download/copy model files → restart app → banner disappears.
 *
 * 2. **Accessibility self-check:**
 *    - Disable "UFO Galaxy" in Settings → Accessibility → "无障碍服务未启用" banner appears.
 *    - Re-enable service → bring app to foreground (onResume) → banner disappears.
 *
 * 3. **Overlay self-check:**
 *    - Revoke "Display over other apps" permission → "悬浮窗权限未授予" banner appears.
 *    - Tapping the permission prompt notification opens the overlay settings page.
 *    - Grant permission → bring app to foreground → banner disappears.
 *
 * 4. **Degraded mode banner:**
 *    - Trigger any of the above → top banner appears with list of issues (non-blocking).
 *    - Fix all issues → banner is hidden.
 *
 * 5. **Capability report metadata:**
 *    - Enable cross-device and inspect the capability_report WebSocket message sent to
 *      the gateway; it must include `model_ready`, `accessibility_ready`, `overlay_ready`
 *      keys reflecting the current state.
 */
class ReadinessCheckerTest {

    // ── ReadinessState ────────────────────────────────────────────────────────

    @Test
    fun `degradedMode is false when all checks pass`() {
        val state = ReadinessState(
            modelReady = true,
            accessibilityReady = true,
            overlayReady = true
        )
        assertFalse(state.degradedMode)
    }

    @Test
    fun `degradedMode is true when modelReady is false`() {
        val state = ReadinessState(
            modelReady = false,
            accessibilityReady = true,
            overlayReady = true
        )
        assertTrue(state.degradedMode)
    }

    @Test
    fun `degradedMode is true when accessibilityReady is false`() {
        val state = ReadinessState(
            modelReady = true,
            accessibilityReady = false,
            overlayReady = true
        )
        assertTrue(state.degradedMode)
    }

    @Test
    fun `degradedMode is true when overlayReady is false`() {
        val state = ReadinessState(
            modelReady = true,
            accessibilityReady = true,
            overlayReady = false
        )
        assertTrue(state.degradedMode)
    }

    @Test
    fun `degradedMode is true when all checks fail`() {
        val state = ReadinessState(
            modelReady = false,
            accessibilityReady = false,
            overlayReady = false
        )
        assertTrue(state.degradedMode)
    }

    @Test
    fun `ReadinessState equality works correctly`() {
        val a = ReadinessState(modelReady = true, accessibilityReady = true, overlayReady = true)
        val b = ReadinessState(modelReady = true, accessibilityReady = true, overlayReady = true)
        assertEquals(a, b)
    }

    @Test
    fun `ReadinessState copy reflects change`() {
        val original = ReadinessState(modelReady = true, accessibilityReady = true, overlayReady = false)
        assertTrue(original.degradedMode)
        val fixed = original.copy(overlayReady = true)
        assertFalse(fixed.degradedMode)
    }
}
