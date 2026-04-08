package com.ufo.galaxy.agent

import com.ufo.galaxy.data.AppSettings

/**
 * Canonical assessor for Android-side takeover eligibility (PR-3).
 *
 * Encapsulates all pre-conditions that must be satisfied before Android accepts
 * an inbound [TakeoverRequestEnvelope] from the main runtime.  The assessor
 * produces a structured [EligibilityResult] so that [com.ufo.galaxy.service.GalaxyConnectionService]
 * can send accurate, machine-readable rejection reasons rather than a generic fallback string.
 *
 * ## Assessment order (first failing check wins)
 * 1. [AppSettings.crossDeviceEnabled] must be `true`.
 * 2. [AppSettings.goalExecutionEnabled] must be `true`.
 * 3. [AppSettings.accessibilityReady] must be `true`.
 * 4. [AppSettings.overlayReady] must be `true`.
 * 5. No other takeover may currently be active on this device.
 *
 * Source posture ([TakeoverRequestEnvelope.source_runtime_posture]) is **not** a blocking
 * condition — Android can accept a takeover regardless of whether the originating device is
 * `control_only` or `join_runtime`.  Callers should echo the resolved posture in the
 * response for main-runtime correlation purposes.
 *
 * ## Why a dedicated assessor?
 * Centralising the eligibility logic makes it testable in isolation from the full
 * [com.ufo.galaxy.service.GalaxyConnectionService] (which requires a live WS context).
 * It also makes the canonical path explicit: every acceptance decision must go through
 * this class, eliminating ad-hoc per-message-handler decisions.
 *
 * @param settings Live [AppSettings] instance used to read device readiness flags at
 *                 assessment time.
 */
class TakeoverEligibilityAssessor(private val settings: AppSettings) {

    /**
     * Structured outcome code for a takeover eligibility assessment.
     *
     * Each variant carries a stable [reason] string that is safe to include in
     * [TakeoverResponseEnvelope.rejection_reason] and in structured telemetry.
     */
    enum class EligibilityOutcome(val reason: String) {
        /** All preconditions satisfied; Android may accept the takeover. */
        ELIGIBLE("eligible"),

        /** [AppSettings.crossDeviceEnabled] is `false`; cross-device path is disabled. */
        BLOCKED_CROSS_DEVICE_DISABLED("cross_device_disabled"),

        /**
         * [AppSettings.goalExecutionEnabled] is `false`; goal-execution feature flag is off.
         * The device will not run autonomous goal tasks in this state.
         */
        BLOCKED_GOAL_EXECUTION_DISABLED("goal_execution_disabled"),

        /**
         * PR-31 — [AppSettings.delegatedExecutionAllowed] is `false`; the rollout-control
         * flag that governs whether this device accepts inbound delegated tasks is currently
         * disabled.  Cross-device connectivity (WS session) remains active.
         */
        BLOCKED_DELEGATED_EXECUTION_DISABLED("delegated_execution_disabled"),

        /**
         * [AppSettings.accessibilityReady] is `false`; the accessibility service is not
         * running or has not been granted the required permissions.
         */
        BLOCKED_ACCESSIBILITY_NOT_READY("accessibility_not_ready"),

        /**
         * [AppSettings.overlayReady] is `false`; the overlay (floating window) permission
         * has not been granted or the service is not ready.
         */
        BLOCKED_OVERLAY_NOT_READY("overlay_not_ready"),

        /**
         * Another takeover is already active on this device.  Concurrent takeovers are not
         * supported; the main runtime must wait for the active takeover to complete before
         * issuing a new one.
         */
        BLOCKED_CONCURRENT_TAKEOVER("concurrent_takeover_active")
    }

    /**
     * Result of a single eligibility assessment.
     *
     * @param eligible          `true` when Android should accept the takeover.
     * @param outcome           Machine-readable outcome code.
     * @param reason            Human-readable reason string; mirrors [EligibilityOutcome.reason]
     *                          and is suitable for [TakeoverResponseEnvelope.rejection_reason].
     *                          When [outcome] is [EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER]
     *                          the active takeover ID is appended: `"concurrent_takeover_active:<id>"`.
     * @param activeTakeoverId  Non-null when [outcome] is
     *                          [EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER]; carries the
     *                          `takeover_id` currently being handled on this device.
     */
    data class EligibilityResult(
        val eligible: Boolean,
        val outcome: EligibilityOutcome,
        val reason: String,
        val activeTakeoverId: String? = null
    )

    /**
     * Assesses whether Android should accept [envelope].
     *
     * Evaluates device readiness and concurrent-takeover state in order, returning the
     * first [EligibilityResult] with [EligibilityResult.eligible] = `false` when a
     * blocking condition is found.  Returns `eligible = true` only when all checks pass.
     *
     * @param envelope          The inbound takeover request to evaluate.
     * @param activeTakeoverId  The `takeover_id` currently being processed on this device,
     *                          or `null` when no takeover is active.
     * @return [EligibilityResult] describing the decision.
     */
    fun assess(envelope: TakeoverRequestEnvelope, activeTakeoverId: String? = null): EligibilityResult {
        if (!settings.crossDeviceEnabled) {
            return blocked(EligibilityOutcome.BLOCKED_CROSS_DEVICE_DISABLED)
        }
        if (!settings.goalExecutionEnabled) {
            return blocked(EligibilityOutcome.BLOCKED_GOAL_EXECUTION_DISABLED)
        }
        // PR-31: Check the rollout-control delegation flag before capability checks.
        // This allows operators to disable delegation without affecting cross-device
        // connectivity or requiring a full reconnect cycle.
        if (!settings.delegatedExecutionAllowed) {
            return blocked(EligibilityOutcome.BLOCKED_DELEGATED_EXECUTION_DISABLED)
        }
        if (!settings.accessibilityReady) {
            return blocked(EligibilityOutcome.BLOCKED_ACCESSIBILITY_NOT_READY)
        }
        if (!settings.overlayReady) {
            return blocked(EligibilityOutcome.BLOCKED_OVERLAY_NOT_READY)
        }
        if (activeTakeoverId != null) {
            return EligibilityResult(
                eligible = false,
                outcome = EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER,
                reason = "${EligibilityOutcome.BLOCKED_CONCURRENT_TAKEOVER.reason}:$activeTakeoverId",
                activeTakeoverId = activeTakeoverId
            )
        }
        return EligibilityResult(
            eligible = true,
            outcome = EligibilityOutcome.ELIGIBLE,
            reason = EligibilityOutcome.ELIGIBLE.reason
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun blocked(outcome: EligibilityOutcome) = EligibilityResult(
        eligible = false,
        outcome = outcome,
        reason = outcome.reason
    )
}
