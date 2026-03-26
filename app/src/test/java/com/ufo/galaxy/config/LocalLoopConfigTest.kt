package com.ufo.galaxy.config

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalLoopConfig], [PlannerConfig], [GroundingConfig], and [FallbackConfig].
 *
 * Covers:
 * - Default values match documented constants.
 * - [LocalLoopConfig.defaults] factory method returns a config equivalent to the zero-arg ctor.
 * - Custom overrides are applied correctly.
 */
class LocalLoopConfigTest {

    // ── LocalLoopConfig defaults ──────────────────────────────────────────────

    @Test
    fun `LocalLoopConfig defaults maxSteps`() {
        assertEquals(LocalLoopConfig.DEFAULT_MAX_STEPS, LocalLoopConfig().maxSteps)
    }

    @Test
    fun `LocalLoopConfig defaults maxRetriesPerStep`() {
        assertEquals(LocalLoopConfig.DEFAULT_MAX_RETRIES_PER_STEP, LocalLoopConfig().maxRetriesPerStep)
    }

    @Test
    fun `LocalLoopConfig defaults stepTimeoutMs is 0 (disabled)`() {
        assertEquals(0L, LocalLoopConfig().stepTimeoutMs)
    }

    @Test
    fun `LocalLoopConfig defaults goalTimeoutMs is 0 (disabled)`() {
        assertEquals(0L, LocalLoopConfig().goalTimeoutMs)
    }

    @Test
    fun `LocalLoopConfig defaults factory equals zero-arg ctor`() {
        assertEquals(LocalLoopConfig(), LocalLoopConfig.defaults())
    }

    // ── LocalLoopConfig custom values ─────────────────────────────────────────

    @Test
    fun `LocalLoopConfig custom maxSteps`() {
        val cfg = LocalLoopConfig(maxSteps = 20)
        assertEquals(20, cfg.maxSteps)
    }

    @Test
    fun `LocalLoopConfig custom goalTimeoutMs`() {
        val cfg = LocalLoopConfig(goalTimeoutMs = 60_000L)
        assertEquals(60_000L, cfg.goalTimeoutMs)
    }

    // ── PlannerConfig defaults ────────────────────────────────────────────────

    @Test
    fun `PlannerConfig defaults maxTokens`() {
        assertEquals(PlannerConfig.DEFAULT_MAX_TOKENS, PlannerConfig().maxTokens)
    }

    @Test
    fun `PlannerConfig defaults temperature`() {
        assertEquals(PlannerConfig.DEFAULT_TEMPERATURE, PlannerConfig().temperature, 0.0001)
    }

    @Test
    fun `PlannerConfig defaults timeoutMs`() {
        assertEquals(PlannerConfig.DEFAULT_TIMEOUT_MS, PlannerConfig().timeoutMs)
    }

    // ── PlannerConfig custom values ───────────────────────────────────────────

    @Test
    fun `PlannerConfig custom maxTokens`() {
        val cfg = PlannerConfig(maxTokens = 1024)
        assertEquals(1024, cfg.maxTokens)
    }

    // ── GroundingConfig defaults ──────────────────────────────────────────────

    @Test
    fun `GroundingConfig defaults timeoutMs`() {
        assertEquals(GroundingConfig.DEFAULT_TIMEOUT_MS, GroundingConfig().timeoutMs)
    }

    @Test
    fun `GroundingConfig defaults scaledMaxEdge`() {
        assertEquals(GroundingConfig.DEFAULT_SCALED_MAX_EDGE, GroundingConfig().scaledMaxEdge)
    }

    // ── GroundingConfig custom values ─────────────────────────────────────────

    @Test
    fun `GroundingConfig zero scaledMaxEdge means disabled`() {
        val cfg = GroundingConfig(scaledMaxEdge = 0)
        assertEquals(0, cfg.scaledMaxEdge)
    }

    // ── FallbackConfig defaults ───────────────────────────────────────────────

    @Test
    fun `FallbackConfig defaults enablePlannerFallback is true`() {
        assertTrue(FallbackConfig().enablePlannerFallback)
    }

    @Test
    fun `FallbackConfig defaults enableGroundingFallback is true`() {
        assertTrue(FallbackConfig().enableGroundingFallback)
    }

    @Test
    fun `FallbackConfig defaults enableRemoteHandoff is false`() {
        assertFalse(FallbackConfig().enableRemoteHandoff)
    }

    @Test
    fun `FallbackConfig defaults maxFallbackAttempts`() {
        assertEquals(FallbackConfig.DEFAULT_MAX_FALLBACK_ATTEMPTS, FallbackConfig().maxFallbackAttempts)
    }

    // ── FallbackConfig custom values ──────────────────────────────────────────

    @Test
    fun `FallbackConfig custom enableRemoteHandoff`() {
        val cfg = FallbackConfig(enableRemoteHandoff = true)
        assertTrue(cfg.enableRemoteHandoff)
    }

    // ── Nested config wiring ──────────────────────────────────────────────────

    @Test
    fun `LocalLoopConfig planner is PlannerConfig with defaults`() {
        assertEquals(PlannerConfig(), LocalLoopConfig().planner)
    }

    @Test
    fun `LocalLoopConfig grounding is GroundingConfig with defaults`() {
        assertEquals(GroundingConfig(), LocalLoopConfig().grounding)
    }

    @Test
    fun `LocalLoopConfig fallback is FallbackConfig with defaults`() {
        assertEquals(FallbackConfig(), LocalLoopConfig().fallback)
    }

    @Test
    fun `LocalLoopConfig custom nested planner`() {
        val cfg = LocalLoopConfig(planner = PlannerConfig(maxTokens = 256))
        assertEquals(256, cfg.planner.maxTokens)
    }
}
