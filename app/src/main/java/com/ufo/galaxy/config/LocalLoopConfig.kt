package com.ufo.galaxy.config

import com.ufo.galaxy.data.AppSettings

/**
 * Central configuration model for the local closed-loop execution pipeline.
 *
 * Consolidates timeouts, retry budgets, scaling parameters, and fallback flags
 * that were previously scattered across [com.ufo.galaxy.loop.LoopController],
 * [com.ufo.galaxy.data.AppConfig], and call sites.
 *
 * All properties carry safe defaults that reproduce the prior behavior so that
 * callers that do not wire a custom [LocalLoopConfig] continue to work unchanged.
 *
 * @property maxSteps         Hard cap on total dispatched steps (including replans).
 * @property maxRetriesPerStep Maximum retries for a single failing step before giving up.
 * @property stepTimeoutMs    Per-step wall-clock timeout in milliseconds. 0 = disabled.
 * @property goalTimeoutMs    Total session wall-clock timeout in milliseconds. 0 = disabled.
 * @property planner          Configuration for the MobileVLM planner component.
 * @property grounding        Configuration for the SeeClick grounding component.
 * @property fallback         Configuration for fallback behaviour when the primary path fails.
 */
data class LocalLoopConfig(
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxRetriesPerStep: Int = DEFAULT_MAX_RETRIES_PER_STEP,
    val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    val goalTimeoutMs: Long = DEFAULT_GOAL_TIMEOUT_MS,
    val planner: PlannerConfig = PlannerConfig(),
    val grounding: GroundingConfig = GroundingConfig(),
    val fallback: FallbackConfig = FallbackConfig()
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val DEFAULT_MAX_RETRIES_PER_STEP = 2
        const val DEFAULT_STEP_TIMEOUT_MS = 0L   // disabled
        const val DEFAULT_GOAL_TIMEOUT_MS = 0L   // disabled

        /** Returns a [LocalLoopConfig] with all default values. */
        fun defaults(): LocalLoopConfig = LocalLoopConfig()

        /**
         * Creates a [LocalLoopConfig] whose planner and grounding sub-configs are
         * sourced from the effective [AppSettings] authority.
         *
         * Configuration precedence (handled by [AppSettings]):
         *   SharedPreferences runtime override →
         *   `assets/config.properties` packaged defaults →
         *   compile-time [SharedPrefsAppSettings] constants.
         *
         * Loop-level parameters ([maxSteps], [maxRetriesPerStep], [stepTimeoutMs],
         * [goalTimeoutMs]) and [FallbackConfig] retain their defaults; those are
         * not yet exposed in [AppSettings] and can be added in a later PR.
         */
        fun from(settings: AppSettings): LocalLoopConfig = LocalLoopConfig(
            planner = PlannerConfig(
                maxTokens = settings.plannerMaxTokens,
                temperature = settings.plannerTemperature,
                timeoutMs = settings.plannerTimeoutMs
            ),
            grounding = GroundingConfig(
                timeoutMs = settings.groundingTimeoutMs,
                scaledMaxEdge = settings.scaledMaxEdge
            )
        )
    }
}

/**
 * Configuration for the MobileVLM planner component.
 *
 * @property maxTokens    Maximum tokens the planner may generate per call.
 * @property temperature  Sampling temperature (lower = more deterministic).
 * @property timeoutMs    HTTP connect+read timeout for inference calls (ms).
 */
data class PlannerConfig(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.1
        const val DEFAULT_TIMEOUT_MS = 30_000
    }
}

/**
 * Configuration for the SeeClick grounding component.
 *
 * @property timeoutMs    HTTP connect+read timeout for grounding calls (ms).
 * @property scaledMaxEdge Longest edge (px) for screenshot downscaling before grounding.
 *                         0 = disabled (pass full-resolution image).
 */
data class GroundingConfig(
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val scaledMaxEdge: Int = DEFAULT_SCALED_MAX_EDGE
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val DEFAULT_SCALED_MAX_EDGE = 720
    }
}

/**
 * Configuration for the local-loop fallback behaviour.
 *
 * Fallback policies apply when the primary planner or grounding path fails:
 * - The rule-based planner fallback ladder is used when [enablePlannerFallback] is true.
 * - The grounding fallback ladder is used when [enableGroundingFallback] is true.
 * - When the fallback ladder is also exhausted and [enableRemoteHandoff] is true,
 *   the task may be escalated to the remote Agent Runtime bridge.
 *
 * @property enablePlannerFallback   Whether the planner fallback ladder is active.
 * @property enableGroundingFallback Whether the grounding fallback ladder is active.
 * @property enableRemoteHandoff     Whether failed local executions may be escalated remotely.
 * @property maxFallbackAttempts     Maximum number of times any fallback tier is tried
 *                                   before the session is declared failed.
 */
data class FallbackConfig(
    val enablePlannerFallback: Boolean = true,
    val enableGroundingFallback: Boolean = true,
    val enableRemoteHandoff: Boolean = false,
    val maxFallbackAttempts: Int = DEFAULT_MAX_FALLBACK_ATTEMPTS
) {
    companion object {
        const val DEFAULT_MAX_FALLBACK_ATTEMPTS = 3
    }
}
