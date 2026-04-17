package com.ufo.galaxy.runtime

/**
 * PR-2 — First-class participant health state for the Android runtime.
 *
 * Models the **operational health** of this Android device as a formation participant,
 * beyond the coarser-grained [RuntimeHostDescriptor.HostParticipationState] and the
 * selection-oriented [ParticipantReadinessState].
 *
 * ## Motivation
 *
 * [RuntimeHostDescriptor.HostParticipationState] tracks lifecycle readiness (ACTIVE /
 * STANDBY / DRAINING / INACTIVE), and [ParticipantReadinessState] tracks selection
 * suitability (READY / READY_WITH_FALLBACK / NOT_READY / UNKNOWN).  Neither cleanly
 * models *health*: whether the local execution environment is functioning correctly and
 * expected to produce reliable results.
 *
 * [ParticipantHealthState] fills this gap.  It allows the Android runtime to report an
 * explicit, stable health verdict that the formation coordinator can factor into rebalance
 * and recovery decisions:
 *
 *  - A HEALTHY participant is fully operational; no special treatment required.
 *  - A DEGRADED participant is functional but compromised (e.g. model loaded but inference
 *    is slow, accessibility service available but unreliable, etc.).  Continued participation
 *    is possible but at reduced quality.
 *  - A RECOVERING participant was recently in a DEGRADED or FAILED state and is re-establishing
 *    full capability.  It should not be assigned new tasks until HEALTHY.
 *  - A FAILED participant has encountered an unrecoverable local error and cannot participate.
 *    The formation coordinator should treat this device as unavailable.
 *  - UNKNOWN is the initial state before any health assessment has occurred.
 *
 * ## Relationship to [ReconnectRecoveryState]
 *
 * [ReconnectRecoveryState] tracks the **WS connectivity** recovery lifecycle (IDLE /
 * RECOVERING / RECOVERED / FAILED).  [ParticipantHealthState] tracks **execution environment
 * health** — a device can be WS-RECOVERED but still DEGRADED if its model failed to reload,
 * or still RECOVERING while a local inference warmup completes after reconnect.  The two
 * states are complementary and independently observable.
 *
 * ## Wire values
 *
 * Each state exposes a [wireValue] for inclusion in telemetry payloads and readiness reports.
 * Wire values are stable and must not be renamed after this PR ships.
 *
 * @property wireValue Stable lowercase string used in diagnostics and telemetry payloads.
 */
enum class ParticipantHealthState(val wireValue: String) {

    /**
     * The device's execution environment is fully operational.  All subsystems (model, accessibility,
     * overlay, inference) are functioning within expected parameters.  The participant is ready
     * to accept and reliably execute new tasks within the formation.
     */
    HEALTHY("healthy"),

    /**
     * The device is functional but operating below full capability.  Typical causes:
     * - Inference is slow or producing lower-confidence results.
     * - Accessibility service is available but unreliable.
     * - A non-critical subsystem failed and the device entered degraded mode.
     *
     * Continued participation is possible; the formation coordinator should consider
     * lower-priority assignment or reduced task scope.
     */
    DEGRADED("degraded"),

    /**
     * The device was recently in a [DEGRADED] or [FAILED] state and is in the process of
     * re-establishing full capability.  Examples:
     * - Model is reloading after a crash.
     * - Accessibility service is restarting after a disconnect.
     * - Post-reconnect warmup is in progress.
     *
     * The device should not be assigned new formation tasks until it transitions to [HEALTHY].
     * In-flight tasks from before the degradation may continue if they have not been cancelled.
     */
    RECOVERING("recovering"),

    /**
     * The device encountered an unrecoverable local error and cannot participate in the formation.
     * The formation coordinator should treat this device as unavailable and, if possible, trigger
     * a [FormationRebalanceEvent.DegradedFormationDetected] for the affected session.
     */
    FAILED("failed"),

    /**
     * Health has not yet been assessed.  This is the initial state before the first health check
     * or before the device has completed its startup readiness evaluation.  The formation
     * coordinator should treat UNKNOWN devices conservatively (e.g. equivalent to DEGRADED
     * until a definitive HEALTHY verdict is produced).
     */
    UNKNOWN("unknown");

    companion object {
        /**
         * Parses a wire-value string to a [ParticipantHealthState], returning [UNKNOWN] for
         * unrecognised values.
         *
         * @param value Wire string from a diagnostics payload; may be null.
         */
        fun fromValue(value: String?): ParticipantHealthState =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN

        /** All stable wire values — useful for validation in tests and schema registries. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

        /**
         * Returns `true` when [state] indicates the participant can accept new tasks from
         * the formation coordinator.  Only [HEALTHY] qualifies; all other states should
         * block new assignment until the device re-reports [HEALTHY].
         */
        fun isAcceptingTasks(state: ParticipantHealthState): Boolean = state == HEALTHY

        /**
         * Returns `true` when [state] indicates active degradation or ongoing recovery — i.e.
         * the device is present but not at full capability.  Used by
         * [FormationParticipationRebalancer] to decide whether to emit a
         * [FormationRebalanceEvent.DegradedFormationDetected] event.
         */
        fun isCompromised(state: ParticipantHealthState): Boolean =
            state == DEGRADED || state == RECOVERING || state == FAILED
    }
}
