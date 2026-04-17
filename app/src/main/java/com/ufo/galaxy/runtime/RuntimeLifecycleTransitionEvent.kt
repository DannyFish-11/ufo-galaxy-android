package com.ufo.galaxy.runtime

/**
 * PR-37 — Observable runtime lifecycle state transition event.
 *
 * Emitted on [RuntimeController.lifecycleTransitionEvents] for every runtime state
 * transition, carrying both the prior state, the new state, and a machine-readable
 * trigger label.  Consumers may collect this flow for observability, test assertion,
 * lifecycle-aware behavioral adaptation, and cross-layer coordination without polling
 * [RuntimeController.state] directly.
 *
 * ## Classification
 *
 * Two subtypes distinguish governed transitions from unexpected ones:
 *
 * - [Governed] — the runtime transitioned from the anticipated prior state to the new
 *   state.  This is the normal case for all canonical lifecycle paths.
 * - [Unexpected] — the runtime transitioned but the actual prior state did not match
 *   the expected prior state at the call site.  This indicates a potential race
 *   condition, re-entrant call, or ordering issue that should be investigated.
 *
 * ## Transition labels
 *
 * The [trigger] / [reason] strings in [Governed] and [Unexpected] are machine-readable
 * identifiers for the operation that caused the transition.  Stable values:
 *
 * | Value                                | Transition                          |
 * |--------------------------------------|-------------------------------------|
 * | `"start"`                            | user-initiated start()              |
 * | `"start_timeout"`                    | registration timeout                |
 * | `"start_failure"`                    | WS connection failure during start  |
 * | `"ws_connected"`                     | WS connected while Starting         |
 * | `"stop"`                             | explicit stop() or kill-switch      |
 * | `"connect_if_enabled_cross_device"`  | connectIfEnabled with CD=true       |
 * | `"connect_if_enabled_local_only"`    | connectIfEnabled with CD=false      |
 * | `"connect_if_enabled_ws_connected"`  | connectIfEnabled WS connected       |
 * | `"connect_if_enabled_ws_failure"`    | connectIfEnabled WS failure         |
 * | `"failure_fallback"`                 | handleFailure → LocalOnly           |
 * | `"kill_switch"`                      | applyKillSwitch()                   |
 *
 * ## V2 field / semantic compatibility
 *
 * Both [from] and [to] carry their [RuntimeController.RuntimeState] class name via
 * [RuntimeController.RuntimeState.wireLabel] so structured log entries and any
 * host-facing protocol fields that carry lifecycle state can be stable strings.
 */
sealed class RuntimeLifecycleTransitionEvent {

    /** The runtime state before this transition. */
    abstract val from: RuntimeController.RuntimeState

    /** The runtime state after this transition. */
    abstract val to: RuntimeController.RuntimeState

    /** Wall-clock epoch-millisecond timestamp of the transition. */
    abstract val timestampMs: Long

    /**
     * A normal, governed lifecycle transition following the canonical state machine.
     *
     * [from] and [to] represent the prior and new state; [trigger] is the machine-readable
     * label for the operation that caused this transition.
     */
    data class Governed(
        override val from: RuntimeController.RuntimeState,
        override val to: RuntimeController.RuntimeState,
        override val timestampMs: Long = System.currentTimeMillis(),
        /** Machine-readable trigger label for this governed transition. */
        val trigger: String
    ) : RuntimeLifecycleTransitionEvent()

    /**
     * An unexpected state transition — the runtime transitioned from a state that did
     * not match the expected prior state at the call site.
     *
     * Presence of this event in a trace indicates a potential race condition, re-entrant
     * call, or lifecycle ordering issue.  Observers should log these at WARN level and
     * include them in any operator diagnostic bundles.
     *
     * [from] is the actual prior state at transition time; [expectedFrom] is what was
     * expected; [to] is the new state.
     */
    data class Unexpected(
        override val from: RuntimeController.RuntimeState,
        override val to: RuntimeController.RuntimeState,
        override val timestampMs: Long = System.currentTimeMillis(),
        /** The state that was expected as the prior state at the call site. */
        val expectedFrom: RuntimeController.RuntimeState,
        /** Human-readable description of why this transition was unexpected. */
        val reason: String
    ) : RuntimeLifecycleTransitionEvent()
}

/**
 * Returns a stable, machine-readable label for this [RuntimeController.RuntimeState]
 * suitable for use in structured log entries and host-facing protocol fields.
 *
 * Values mirror the wire vocabulary defined in [ReconnectRecoveryState] and aligned
 * with the Android UGCP Constitution § 4.1 runtime lifecycle terms.
 */
val RuntimeController.RuntimeState.wireLabel: String
    get() = when (this) {
        is RuntimeController.RuntimeState.Idle       -> "idle"
        is RuntimeController.RuntimeState.Starting   -> "starting"
        is RuntimeController.RuntimeState.Active     -> "active"
        is RuntimeController.RuntimeState.Failed     -> "failed"
        is RuntimeController.RuntimeState.LocalOnly  -> "local_only"
    }
