package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.AppSettings

/**
 * PR-31 — Canonical, typed snapshot of all rollout-control flags.
 *
 * Captures the current state of every feature toggle and kill-switch that governs
 * whether the various execution paths (local, cross-device, delegated, fallback) are
 * permitted to run.  Consumers use a single [RolloutControlSnapshot] instance rather
 * than reading individual [AppSettings] flags in a non-atomic fashion.
 *
 * ## Field semantics
 *
 * | Field                        | Meaning                                                      | Safe default |
 * |------------------------------|--------------------------------------------------------------|--------------|
 * | [crossDeviceAllowed]         | Cross-device collaboration is currently enabled in settings. | `false`      |
 * | [delegatedExecutionAllowed]  | Device may accept inbound delegated takeover tasks.          | `true`       |
 * | [fallbackToLocalAllowed]     | Local-only fallback is permitted after a delegated failure.  | `true`       |
 * | [goalExecutionAllowed]       | Autonomous goal-execution feature is enabled in settings.    | `false`      |
 *
 * ## Computed properties
 *
 * - [killSwitchActive] — `true` when both [crossDeviceAllowed] and [goalExecutionAllowed]
 *   are `false`; indicates that all remote execution paths are effectively disabled and
 *   the device is operating in the safest possible local-only mode.
 * - [isFullyEnabled] — `true` only when every flag is enabled; useful for asserting a
 *   fully-capable production configuration in test environments.
 *
 * ## Wire-key constants
 *
 * Each field has a companion [KEY_*] constant so the snapshot can be serialised to a
 * stable [Map] via [toMetadataMap] for inclusion in diagnostics and telemetry payloads.
 *
 * @param crossDeviceAllowed        Mirrors [AppSettings.crossDeviceEnabled].
 * @param delegatedExecutionAllowed Mirrors [AppSettings.delegatedExecutionAllowed].
 * @param fallbackToLocalAllowed    Mirrors [AppSettings.fallbackToLocalAllowed].
 * @param goalExecutionAllowed      Mirrors [AppSettings.goalExecutionEnabled].
 */
data class RolloutControlSnapshot(
    val crossDeviceAllowed: Boolean,
    val delegatedExecutionAllowed: Boolean,
    val fallbackToLocalAllowed: Boolean,
    val goalExecutionAllowed: Boolean
) {
    /**
     * `true` when both [crossDeviceAllowed] and [goalExecutionAllowed] are `false`.
     *
     * Indicates that all remote execution paths are disabled — the device is in the
     * safest possible local-only mode and will reject any inbound delegated task.
     * Operators can use this flag to confirm that a kill-switch activation has taken
     * full effect before reducing monitoring pressure.
     */
    val killSwitchActive: Boolean
        get() = !crossDeviceAllowed && !goalExecutionAllowed

    /**
     * `true` when every controllable execution path is enabled.
     *
     * Useful as a fast sanity-check in tests or readiness surfaces to assert that a
     * device has been configured for full production capability.
     */
    val isFullyEnabled: Boolean
        get() = crossDeviceAllowed && delegatedExecutionAllowed && fallbackToLocalAllowed && goalExecutionAllowed

    /**
     * Serialises this snapshot to a stable key→value [Map] suitable for inclusion in
     * diagnostics payloads and [GalaxyLogger] structured log entries.
     *
     * All four runtime-control fields are always present.  The computed properties
     * [killSwitchActive] and [isFullyEnabled] are not included in the map to keep the
     * wire format minimal and deterministic.
     */
    fun toMetadataMap(): Map<String, Any> = mapOf(
        KEY_CROSS_DEVICE_ALLOWED        to crossDeviceAllowed,
        KEY_DELEGATED_EXECUTION_ALLOWED to delegatedExecutionAllowed,
        KEY_FALLBACK_TO_LOCAL_ALLOWED   to fallbackToLocalAllowed,
        KEY_GOAL_EXECUTION_ALLOWED      to goalExecutionAllowed
    )

    companion object {
        // ── Wire-key constants ────────────────────────────────────────────────
        const val KEY_CROSS_DEVICE_ALLOWED        = "cross_device_allowed"
        const val KEY_DELEGATED_EXECUTION_ALLOWED = "delegated_execution_allowed"
        const val KEY_FALLBACK_TO_LOCAL_ALLOWED   = "fallback_to_local_allowed"
        const val KEY_GOAL_EXECUTION_ALLOWED      = "goal_execution_allowed"

        /**
         * Maximally conservative default: cross-device and goal execution are both
         * disabled; delegated execution and local fallback are allowed (they have
         * no effect when cross-device is off, so leaving them `true` is harmless and
         * avoids silent surprise when the operator re-enables cross-device).
         *
         * Use this as a baseline for production deployments where rollout must be
         * explicitly opted into rather than being on-by-default.
         */
        val SAFE_DEFAULTS = RolloutControlSnapshot(
            crossDeviceAllowed        = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed    = true,
            goalExecutionAllowed      = false
        )

        /**
         * Creates a [RolloutControlSnapshot] from the current [AppSettings] state.
         *
         * This is the canonical factory for producing a snapshot at any point in time.
         * [RuntimeController] calls this internally whenever settings change to keep
         * the observable [RuntimeController.rolloutControlSnapshot] in sync.
         */
        fun from(settings: AppSettings): RolloutControlSnapshot = RolloutControlSnapshot(
            crossDeviceAllowed        = settings.crossDeviceEnabled,
            delegatedExecutionAllowed = settings.delegatedExecutionAllowed,
            fallbackToLocalAllowed    = settings.fallbackToLocalAllowed,
            goalExecutionAllowed      = settings.goalExecutionEnabled
        )
    }
}
