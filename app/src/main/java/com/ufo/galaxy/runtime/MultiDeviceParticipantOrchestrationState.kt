package com.ufo.galaxy.runtime

/**
 * PR-7 (Android companion) — Unified multi-device participant orchestration state surface.
 *
 * Provides the canonical, testable state model that describes the Android participant's
 * position within a multi-device orchestrated formation.  This surface consolidates
 * the orthogonal state dimensions from [ParticipantHealthState], [ReconnectRecoveryState],
 * [ParticipantReadinessState], and [RuntimeHostDescriptor.HostParticipationState] into a
 * single observable record that V2 joint integration tests can drive and verify against
 * expected behaviour.
 *
 * ## Motivation
 *
 * In PR-7 (V2 repo) the multi-device joint integration harness needs to verify that:
 *  - An Android participant in each orchestration state emits the correct lifecycle events.
 *  - Capability reports remain honest (no false positives) under every orchestration state.
 *  - Delegated takeover eligibility is correctly blocked when the participant is degraded
 *    or reconnecting.
 *  - Recovery transitions (degraded → recovering → healthy) drive the correct V2-facing
 *    state changes.
 *
 * Without a single surface that declares all valid orchestration states and the allowed
 * transitions between them, the joint integration harness must assemble these semantics
 * itself — leading to test coupling and semantic drift.
 *
 * [MultiDeviceParticipantOrchestrationState] closes this gap by providing:
 *  - An [OrchestrationState] enum with stable wire values covering all six states that
 *    the PR-7 joint harness must validate.
 *  - A [StateRecord] that combines the constituent runtime sub-states for each
 *    orchestration state.
 *  - A [VALID_TRANSITIONS] registry declaring which state transitions are legal, enabling
 *    the joint harness to assert that no illegal transitions occur.
 *  - Helper predicates ([eligibleForDelegatedTakeover], [shouldAdvertiseFullCapabilities],
 *    [requiresV2Rebalance]) that directly drive joint-harness assertions.
 *
 * ## Relationship to existing state surfaces
 *
 * | Surface | Role |
 * |---------|------|
 * | [ParticipantHealthState] | Execution environment health (sub-state) |
 * | [ReconnectRecoveryState] | WS connectivity recovery phase (sub-state) |
 * | [ParticipantReadinessState] | Task dispatch selection suitability (sub-state) |
 * | [RuntimeHostDescriptor.HostParticipationState] | Lifecycle readiness (sub-state) |
 * | [MultiDeviceParticipantOrchestrationState] | **Unified orchestration view** (this class) |
 *
 * [MultiDeviceParticipantOrchestrationState] does not own lifecycle state; it is a
 * **declarative registry** and a set of **derived predicates** computed from sub-states.
 *
 * ## Usage by the PR-7 joint harness
 *
 * ```kotlin
 * // Joint integration test asserting that a degraded participant blocks new takeover:
 * val state = MultiDeviceParticipantOrchestrationState.from(
 *     healthState = ParticipantHealthState.DEGRADED,
 *     reconnectState = ReconnectRecoveryState.IDLE,
 *     readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
 *     participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
 * )
 * assertFalse(MultiDeviceParticipantOrchestrationState.eligibleForDelegatedTakeover(state))
 * ```
 */
object MultiDeviceParticipantOrchestrationState {

    // ── Orchestration state enum ──────────────────────────────────────────────

    /**
     * Enumeration of the six multi-device orchestration states that the PR-7 joint
     * integration harness must drive and verify on the Android participant side.
     *
     * @property wireValue Stable lowercase string used in V2 integration event payloads.
     * @property description Human-readable description for test diagnostics.
     */
    enum class OrchestrationState(val wireValue: String, val description: String) {

        /**
         * The participant is fully connected, healthy, and ready to accept tasks.
         * WS is live; session is ATTACHED; health is HEALTHY; readiness is READY.
         */
        CONNECTED("connected", "Participant fully connected and ready for task dispatch"),

        /**
         * The participant has lost WS connectivity and no reconnect is in progress.
         * Session is DETACHED; health transitions to UNKNOWN; readiness is NOT_READY.
         */
        DISCONNECTED("disconnected", "Participant WS connection lost; session detached"),

        /**
         * The participant is actively attempting to re-establish WS connectivity.
         * Session is DETACHING; [ReconnectRecoveryState] is RECOVERING; readiness is NOT_READY.
         * New task dispatch is blocked; in-flight tasks may be orphaned.
         */
        RECONNECTING("reconnecting", "Participant reconnect in progress; dispatch blocked"),

        /**
         * The participant is connected but operating below full capability.
         * Health is DEGRADED; readiness is READY_WITH_FALLBACK; new complex tasks blocked.
         */
        DEGRADED("degraded", "Participant degraded; reduced capability only"),

        /**
         * The participant is in the process of restoring full capability after degradation.
         * Health is RECOVERING; readiness is NOT_READY; new task assignment blocked.
         */
        RECOVERING("recovering", "Participant recovering from degradation; dispatch blocked"),

        /**
         * The participant is connected but reporting limited capabilities due to a subsystem
         * failure (e.g. local inference not ready, accessibility restricted).
         * Health is DEGRADED; readiness is READY_WITH_FALLBACK; only limited task set accepted.
         */
        CAPABILITY_LIMITED("capability_limited", "Participant connected with limited capability set")
    }

    // ── State record ──────────────────────────────────────────────────────────

    /**
     * A point-in-time record of the Android participant's constituent sub-states from
     * which the canonical [OrchestrationState] is derived.
     *
     * @property orchestrationState   The derived canonical orchestration state for this record.
     * @property healthState          Runtime execution environment health.
     * @property reconnectState       WS connectivity recovery phase.
     * @property readinessState       Task dispatch selection suitability.
     * @property participationState   Lifecycle participation readiness.
     */
    data class StateRecord(
        val orchestrationState: OrchestrationState,
        val healthState: ParticipantHealthState,
        val reconnectState: ReconnectRecoveryState,
        val readinessState: ParticipantReadinessState,
        val participationState: RuntimeHostDescriptor.HostParticipationState
    )

    // ── Transition entry ──────────────────────────────────────────────────────

    /**
     * Declares a valid state transition that the Android participant may undergo.
     *
     * @property fromState      The state before the transition.
     * @property toState        The state after the transition.
     * @property v2EventEmitted The [V2MultiDeviceLifecycleEvent] wire value emitted on
     *                          this transition, or `null` if no lifecycle event is emitted.
     * @property trigger        Human-readable trigger for this transition (for diagnostics).
     * @property rationale      Why this transition is valid.
     */
    data class TransitionEntry(
        val fromState: OrchestrationState,
        val toState: OrchestrationState,
        val v2EventEmitted: String?,
        val trigger: String,
        val rationale: String
    )

    // ── Valid transition registry ─────────────────────────────────────────────

    /**
     * Canonical registry of all valid multi-device orchestration state transitions.
     *
     * The PR-7 joint integration harness uses this registry to assert that only
     * declared transitions occur during automated test sequences, and that each
     * transition produces the correct V2 lifecycle event.
     */
    val VALID_TRANSITIONS: List<TransitionEntry> = listOf(

        TransitionEntry(
            fromState = OrchestrationState.DISCONNECTED,
            toState = OrchestrationState.RECONNECTING,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "ws_reconnect_attempt_started",
            rationale = "Automatic reconnect begins; participant emits DeviceDegraded " +
                "(degradationKind=ws_recovering) to signal V2 that recovery is in progress."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CONNECTED,
            toState = OrchestrationState.RECONNECTING,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "ws_disconnect_while_active",
            rationale = "Active participant loses WS; emits DeviceDegraded " +
                "(degradationKind=ws_recovering) and enters reconnect recovery."
        ),
        TransitionEntry(
            fromState = OrchestrationState.RECONNECTING,
            toState = OrchestrationState.CONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED,
            trigger = "ws_reconnect_succeeded",
            rationale = "WS reconnect succeeded; DeviceReconnected emitted with " +
                "updated sessionContinuityEpoch; participant returns to CONNECTED."
        ),
        TransitionEntry(
            fromState = OrchestrationState.RECONNECTING,
            toState = OrchestrationState.DISCONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED,
            trigger = "ws_reconnect_attempts_exhausted",
            rationale = "Reconnect attempts exhausted; emits DeviceDisconnected and " +
                "DeviceDegraded (degradationKind=ws_recovery_failed); participant is unavailable."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CONNECTED,
            toState = OrchestrationState.DEGRADED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "health_state_degraded",
            rationale = "Health transitions to DEGRADED; DeviceDegraded " +
                "(degradationKind=health_degraded) emitted; participant accepts limited work."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CONNECTED,
            toState = OrchestrationState.CAPABILITY_LIMITED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "capability_subset_only",
            rationale = "A subsystem (e.g. local inference) is not ready; participant " +
                "emits DeviceDegraded and reports reduced capability set."
        ),
        TransitionEntry(
            fromState = OrchestrationState.DEGRADED,
            toState = OrchestrationState.RECOVERING,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "health_state_recovering",
            rationale = "Health transitions to RECOVERING after degradation; DeviceDegraded " +
                "(degradationKind=health_recovering) emitted."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CAPABILITY_LIMITED,
            toState = OrchestrationState.RECOVERING,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED,
            trigger = "capability_restore_in_progress",
            rationale = "Missing capability subsystem is being restored; participant " +
                "transitions to RECOVERING and blocks new task dispatch."
        ),
        TransitionEntry(
            fromState = OrchestrationState.RECOVERING,
            toState = OrchestrationState.CONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED,
            trigger = "health_state_healthy",
            rationale = "Full capability restored; DeviceHealthChanged emitted with " +
                "currentHealth=healthy; participant returns to CONNECTED."
        ),
        TransitionEntry(
            fromState = OrchestrationState.DEGRADED,
            toState = OrchestrationState.CONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED,
            trigger = "health_state_healthy_from_degraded",
            rationale = "Degraded participant restored to full health without explicit " +
                "RECOVERING phase; DeviceHealthChanged emitted."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CAPABILITY_LIMITED,
            toState = OrchestrationState.CONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED,
            trigger = "capability_fully_restored",
            rationale = "Previously limited capability is now fully available; " +
                "DeviceHealthChanged emitted; participant returns to CONNECTED."
        ),
        TransitionEntry(
            fromState = OrchestrationState.CONNECTED,
            toState = OrchestrationState.DISCONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED,
            trigger = "explicit_stop_or_clean_detach",
            rationale = "Participant explicitly stopped or cleanly detached; " +
                "DeviceDisconnected emitted; no recovery expected."
        ),
        TransitionEntry(
            fromState = OrchestrationState.DEGRADED,
            toState = OrchestrationState.DISCONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED,
            trigger = "ws_disconnect_while_degraded",
            rationale = "Degraded participant also loses WS; DeviceDisconnected emitted."
        ),
        TransitionEntry(
            fromState = OrchestrationState.RECOVERING,
            toState = OrchestrationState.DISCONNECTED,
            v2EventEmitted = V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED,
            trigger = "ws_disconnect_while_recovering",
            rationale = "Recovering participant loses WS before health restoration; " +
                "DeviceDisconnected emitted."
        )
    )

    // ── Derivation helper ─────────────────────────────────────────────────────

    /**
     * Derives the canonical [OrchestrationState] from the given combination of sub-states.
     *
     * Derivation rules (evaluated in priority order):
     * 1. If [reconnectState] is RECOVERING → RECONNECTING
     * 2. If [participationState] is INACTIVE and [reconnectState] is IDLE → DISCONNECTED
     * 3. If [healthState] is RECOVERING → RECOVERING
     * 4. If [healthState] is DEGRADED and [readinessState] is READY_WITH_FALLBACK → CAPABILITY_LIMITED
     * 5. If [healthState] is DEGRADED → DEGRADED
     * 6. Otherwise → CONNECTED (healthy, attached, ready)
     *
     * @param healthState       Current [ParticipantHealthState].
     * @param reconnectState    Current [ReconnectRecoveryState].
     * @param readinessState    Current [ParticipantReadinessState].
     * @param participationState Current [RuntimeHostDescriptor.HostParticipationState].
     * @return The derived [OrchestrationState].
     */
    fun deriveState(
        healthState: ParticipantHealthState,
        reconnectState: ReconnectRecoveryState,
        readinessState: ParticipantReadinessState,
        participationState: RuntimeHostDescriptor.HostParticipationState
    ): OrchestrationState = when {
        reconnectState == ReconnectRecoveryState.RECOVERING ->
            OrchestrationState.RECONNECTING
        participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE &&
            reconnectState == ReconnectRecoveryState.IDLE ->
            OrchestrationState.DISCONNECTED
        healthState == ParticipantHealthState.RECOVERING ->
            OrchestrationState.RECOVERING
        healthState == ParticipantHealthState.DEGRADED &&
            readinessState == ParticipantReadinessState.READY_WITH_FALLBACK ->
            OrchestrationState.CAPABILITY_LIMITED
        healthState == ParticipantHealthState.DEGRADED ->
            OrchestrationState.DEGRADED
        else ->
            OrchestrationState.CONNECTED
    }

    /**
     * Constructs a [StateRecord] from the given sub-states, deriving the
     * [OrchestrationState] automatically.
     */
    fun from(
        healthState: ParticipantHealthState,
        reconnectState: ReconnectRecoveryState,
        readinessState: ParticipantReadinessState,
        participationState: RuntimeHostDescriptor.HostParticipationState
    ): StateRecord = StateRecord(
        orchestrationState = deriveState(healthState, reconnectState, readinessState, participationState),
        healthState = healthState,
        reconnectState = reconnectState,
        readinessState = readinessState,
        participationState = participationState
    )

    // ── Joint-harness predicates ──────────────────────────────────────────────

    /**
     * Returns `true` when the participant's current [StateRecord] makes it eligible to
     * accept a new delegated takeover request.
     *
     * Eligibility requires:
     * - [OrchestrationState.CONNECTED] (all other states block new takeover acceptance).
     *
     * Note: additional device-level checks ([TakeoverEligibilityAssessor]) are still
     * required; this predicate only enforces the orchestration-state gate.
     */
    fun eligibleForDelegatedTakeover(record: StateRecord): Boolean =
        record.orchestrationState == OrchestrationState.CONNECTED

    /**
     * Returns `true` when the participant should advertise its full capability set to V2.
     *
     * Full capability advertisement is only appropriate in [OrchestrationState.CONNECTED].
     * All other states must either suppress capability claims or limit them to reflect
     * actual available capacity.
     */
    fun shouldAdvertiseFullCapabilities(record: StateRecord): Boolean =
        record.orchestrationState == OrchestrationState.CONNECTED

    /**
     * Returns `true` when the participant's state transition requires V2 to trigger a
     * formation rebalance evaluation.
     *
     * V2 rebalance is required when the participant transitions to or from a state that
     * affects task routing: DEGRADED, RECOVERING, RECONNECTING, or DISCONNECTED.
     */
    fun requiresV2Rebalance(record: StateRecord): Boolean =
        record.orchestrationState != OrchestrationState.CONNECTED

    /**
     * Returns all valid [TransitionEntry] objects that depart from [fromState].
     */
    fun transitionsFrom(fromState: OrchestrationState): List<TransitionEntry> =
        VALID_TRANSITIONS.filter { it.fromState == fromState }

    /**
     * Returns all valid [TransitionEntry] objects that arrive at [toState].
     */
    fun transitionsTo(toState: OrchestrationState): List<TransitionEntry> =
        VALID_TRANSITIONS.filter { it.toState == toState }

    /**
     * Returns `true` if a direct transition from [from] to [to] is in [VALID_TRANSITIONS].
     */
    fun isValidTransition(from: OrchestrationState, to: OrchestrationState): Boolean =
        VALID_TRANSITIONS.any { it.fromState == from && it.toState == to }

    // ── Invariant constants ───────────────────────────────────────────────────

    /** Total number of orchestration states (must remain 6 for PR-7 joint harness). */
    const val ORCHESTRATION_STATE_COUNT: Int = 6

    /** Total number of valid transitions in the registry. */
    const val VALID_TRANSITION_COUNT: Int = 14

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 7

    /** Human-readable title for this surface in release notes and PR descriptions. */
    const val INTRODUCED_PR_TITLE: String =
        "PR-7 Android companion: multi-device participant orchestration state surface"
}
