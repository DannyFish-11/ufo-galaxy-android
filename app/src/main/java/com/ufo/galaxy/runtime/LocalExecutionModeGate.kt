package com.ufo.galaxy.runtime

/**
 * PR-79 (Android) — Local execution mode gate: explicit, stable transition semantics.
 *
 * [LocalExecutionModeGate] is the single, machine-verifiable authority for **how Android
 * describes its execution mode state and transitions** to V2 canonical consumers.
 *
 * ## Problem addressed (R7 / R11)
 *
 * Before this gate, V2's `android_runtime_transition_reducer.py` and
 * `unified_governance_semantics.py` had to infer Android's current execution mode from a
 * combination of `mode_state` ("local_only" | "cross_device"), `mode_readiness_state`
 * ("ready" | "degraded"), and carrier lifecycle fields (`carrier_runtime_state`,
 * `reconnect_recovery_state`).  Each field was independently stable, but:
 *
 *  - No single surface named the **complete set** of valid mode states and their exact
 *    semantics for V2 consumption.
 *  - No surface named the **events** that drive mode transitions, so V2 consumers had to
 *    infer causality from field-level diffs across successive snapshots.
 *  - The boundary between "transitioning" and "stable" states was implicit, creating
 *    drift risk when V2 applied transition-reduction logic.
 *
 * [LocalExecutionModeGate] closes all three gaps:
 *  1. [ExecutionModeState] — the complete set of named, wire-stable mode states.
 *  2. [ModeTransitionEvent] — the named events that may trigger a mode transition.
 *  3. [ModeGateDecision] — the gate's verdict, including whether V2 governance is active,
 *     whether Android is ready to accept cross-device tasks, and the canonical semantic tag.
 *
 * ## Relationship to existing surfaces
 *
 *  - [AndroidAutonomyBoundary] (PR-78) declares *what* Android may decide autonomously
 *    in each mode.  This gate declares *when* and *how* the mode changes and what that
 *    means for V2.
 *  - [ParticipantRuntimeSemanticsBoundary] (PR-61) declares Android's truth domains.
 *    Mode-state is part of the `runtime_posture` truth domain that Android owns.
 *  - [CanonicalDispatchChain] (PR-11) documents the execution paths available in each
 *    mode.  This gate is the mode-transition authority that guards those paths.
 *
 * ## V2 consumption contract
 *
 * V2's `android_runtime_transition_reducer.py` must:
 *  1. Read `execution_mode_state` from the device-state snapshot wire map.
 *  2. Treat [ExecutionModeState.CROSS_DEVICE_ACTIVE] as the only state where
 *     Android can accept new delegated tasks.
 *  3. Treat [ExecutionModeState.TRANSITIONING] as an explicit hold signal —
 *     do not dispatch tasks until the state resolves.
 *  4. Treat [ExecutionModeState.LOCAL_ONLY] as "Android is live but not accepting
 *     cross-device tasks — do not count this as a disconnection".
 *
 * @see AndroidAutonomyBoundary
 * @see ParticipantRuntimeSemanticsBoundary
 * @see AndroidAuthorityBoundaryClosure
 */
object LocalExecutionModeGate {

    /** PR number that introduced this gate. */
    const val INTRODUCED_PR = 79

    /** Wire-map schema version for [ModeGateDecision.toWireMap]. */
    const val SCHEMA_VERSION = "1.0"

    // ── Execution mode state ──────────────────────────────────────────────────

    /**
     * The complete, stable set of execution mode states that Android may be in.
     *
     * V2 canonical consumers MUST NOT infer mode state from field combinations; they
     * MUST read the [wireValue] emitted in the `execution_mode_state` snapshot field.
     *
     * @property wireValue        Stable lowercase string emitted in wire payloads.
     * @property displayName      Short human-readable name.
     * @property description      One-sentence semantic description.
     * @property acceptsCrossDeviceTasks Whether V2 may dispatch new tasks to Android in
     *                                   this state.
     * @property v2GovernanceActive      Whether V2 is the active governing authority in
     *                                   this state.
     */
    enum class ExecutionModeState(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val acceptsCrossDeviceTasks: Boolean,
        val v2GovernanceActive: Boolean
    ) {

        /**
         * Android is not running as a runtime participant.  No tasks are accepted.
         * No V2 connection exists or has been attempted.
         *
         * This is the initial state before [ModeTransitionEvent.RUNTIME_STARTED].
         */
        INACTIVE(
            wireValue = "inactive",
            displayName = "Inactive",
            description = "Android runtime is not active; no task acceptance or V2 connection.",
            acceptsCrossDeviceTasks = false,
            v2GovernanceActive = false
        ),

        /**
         * Android is running in local-only mode (`cross_device_enabled = false`).
         * It is **live and operational** but not connected to V2.
         *
         * V2 MUST distinguish this from [INACTIVE]: local-only means the participant is
         * healthy but has deliberately opted out of cross-device routing.  V2 must not
         * dispatch tasks in this state but must not treat the device as disconnected.
         *
         * Semantically maps to [AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS].
         */
        LOCAL_ONLY(
            wireValue = "local_only",
            displayName = "Local Only",
            description = "Android is operational in local-only mode; not accepting cross-device tasks.",
            acceptsCrossDeviceTasks = false,
            v2GovernanceActive = false
        ),

        /**
         * Android is connected to V2 and fully ready to accept cross-device tasks.
         *
         * Both `cross_device_enabled` and `goal_execution_enabled` are `true`,
         * the WebSocket is established, and no capability degradation is present.
         *
         * Semantically maps to [AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED] and
         * [CanonicalDispatchChain.DispatchPathMode.CROSS_DEVICE].
         */
        CROSS_DEVICE_ACTIVE(
            wireValue = "cross_device_active",
            displayName = "Cross-Device Active",
            description = "Android is connected to V2 and ready to accept cross-device tasks.",
            acceptsCrossDeviceTasks = true,
            v2GovernanceActive = true
        ),

        /**
         * Android is connected to V2 but operating with reduced capability (e.g., inference
         * is unavailable, the model is not loaded, or a subsystem is in a degraded state).
         *
         * V2 MAY dispatch non-inference tasks but MUST NOT dispatch tasks that require
         * `local_model_inference`.  The degradation reason is communicated via
         * [ModeGateDecision.degradationReasons] and the `capability_authority_snapshot`.
         */
        CROSS_DEVICE_DEGRADED(
            wireValue = "cross_device_degraded",
            displayName = "Cross-Device Degraded",
            description = "Android is connected to V2 but capability is reduced; limited task acceptance.",
            acceptsCrossDeviceTasks = true,
            v2GovernanceActive = true
        ),

        /**
         * Android is actively transitioning between modes (e.g., from LOCAL_ONLY to
         * CROSS_DEVICE_ACTIVE as the WebSocket is establishing).
         *
         * V2 MUST NOT dispatch new tasks during a TRANSITIONING state.  The state will
         * resolve to a stable state within one round-trip of the triggering event.
         *
         * This state is emitted with an explicit [ModeGateDecision.transitioningTo] field
         * indicating the expected stable target state.
         */
        TRANSITIONING(
            wireValue = "transitioning",
            displayName = "Transitioning",
            description = "Android is actively changing execution modes; task dispatch is on hold.",
            acceptsCrossDeviceTasks = false,
            v2GovernanceActive = false
        );

        companion object {
            /** Parses [value] to an [ExecutionModeState], or returns `null` for unknown values. */
            fun fromWireValue(value: String?): ExecutionModeState? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Mode transition event ─────────────────────────────────────────────────

    /**
     * A named event that may trigger an execution mode transition.
     *
     * V2's `android_runtime_transition_reducer.py` SHOULD record the triggering event
     * alongside the resulting state change so that transition causality is traceable.
     *
     * @property wireValue    Stable lowercase string for wire serialization.
     * @property displayName  Short human-readable event name.
     * @property description  One-sentence description of when this event fires.
     * @property causesHold   Whether this event places task dispatch on hold during
     *                        the transition (i.e., the system moves to [ExecutionModeState.TRANSITIONING]).
     */
    enum class ModeTransitionEvent(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val causesHold: Boolean
    ) {

        /**
         * User or system enabled cross-device mode (`cross_device_enabled = true`).
         * Android will attempt to establish a WebSocket connection to V2.
         */
        CROSS_DEVICE_ENABLED(
            wireValue = "cross_device_enabled",
            displayName = "Cross-Device Enabled",
            description = "cross_device_enabled transitioned to true; WebSocket connection started.",
            causesHold = true
        ),

        /**
         * User or system disabled cross-device mode (`cross_device_enabled = false`).
         * Android drops the WebSocket connection and reverts to local-only operation.
         */
        CROSS_DEVICE_DISABLED(
            wireValue = "cross_device_disabled",
            displayName = "Cross-Device Disabled",
            description = "cross_device_enabled transitioned to false; V2 connection dropped.",
            causesHold = false
        ),

        /**
         * WebSocket connection to V2 was successfully established.
         * Android is now able to receive and execute cross-device tasks.
         */
        WS_CONNECTED(
            wireValue = "ws_connected",
            displayName = "WebSocket Connected",
            description = "WebSocket connection to V2 established; cross-device tasks may now be dispatched.",
            causesHold = false
        ),

        /**
         * WebSocket connection to V2 was lost.
         * Android moves to reconnect-recovery; task dispatch is held until reconnect succeeds.
         */
        WS_DISCONNECTED(
            wireValue = "ws_disconnected",
            displayName = "WebSocket Disconnected",
            description = "WebSocket connection to V2 lost; reconnect-recovery started.",
            causesHold = true
        ),

        /**
         * One or more capability dimensions degraded (e.g., inference model unloaded,
         * grounding model failed, accessibility service disconnected).
         */
        CAPABILITY_DEGRADED(
            wireValue = "capability_degraded",
            displayName = "Capability Degraded",
            description = "One or more capability dimensions became unavailable; mode degrades.",
            causesHold = false
        ),

        /**
         * All previously degraded capability dimensions are restored (e.g., inference model
         * reloaded, grounding model recovered, accessibility service reconnected).
         */
        CAPABILITY_RESTORED(
            wireValue = "capability_restored",
            displayName = "Capability Restored",
            description = "All capability dimensions fully restored; mode may upgrade from degraded.",
            causesHold = false
        ),

        /**
         * [RuntimeController] was started (e.g., service onCreate, user activation,
         * background restore).  Android begins from INACTIVE.
         */
        RUNTIME_STARTED(
            wireValue = "runtime_started",
            displayName = "Runtime Started",
            description = "RuntimeController started; Android transitions out of INACTIVE.",
            causesHold = true
        ),

        /**
         * [RuntimeController] was explicitly stopped (e.g., service onDestroy, user deactivation).
         * Android returns to INACTIVE.
         */
        RUNTIME_STOPPED(
            wireValue = "runtime_stopped",
            displayName = "Runtime Stopped",
            description = "RuntimeController stopped; Android returns to INACTIVE.",
            causesHold = false
        );

        companion object {
            /** Parses [value] to a [ModeTransitionEvent], or returns `null` for unknown values. */
            fun fromWireValue(value: String?): ModeTransitionEvent? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Mode gate decision ────────────────────────────────────────────────────

    /**
     * The gate's verdict for a given execution mode snapshot.
     *
     * Produced by [decide]; consumed by V2 `android_runtime_transition_reducer.py` via
     * [toWireMap] entries in device-state snapshot payloads.
     *
     * @property state             Current [ExecutionModeState].
     * @property triggeringEvent   The [ModeTransitionEvent] that caused this decision, if known.
     * @property transitioningTo   When [state] is [ExecutionModeState.TRANSITIONING], the expected
     *                             stable target state; `null` otherwise.
     * @property degradationReasons Non-empty list of machine-readable strings identifying why the
     *                             mode is [ExecutionModeState.CROSS_DEVICE_DEGRADED]; empty otherwise.
     * @property semanticTag       Compact, dot-delimited semantic tag for V2 log correlation
     *                             (e.g., `"mode.cross_device_active.v2_governed"`).
     * @property rationale         Human-readable rationale for this decision.
     * @property schemaVersion     Always [SCHEMA_VERSION]; allows V2 to detect gate-contract drift.
     */
    data class ModeGateDecision(
        val state: ExecutionModeState,
        val triggeringEvent: ModeTransitionEvent?,
        val transitioningTo: ExecutionModeState?,
        val degradationReasons: List<String>,
        val semanticTag: String,
        val rationale: String,
        val schemaVersion: String = SCHEMA_VERSION
    ) {

        /** `true` when [state] accepts cross-device task dispatch. */
        val acceptsCrossDeviceTasks: Boolean
            get() = state.acceptsCrossDeviceTasks

        /** `true` when V2 is the active governing authority in [state]. */
        val v2GovernanceActive: Boolean
            get() = state.v2GovernanceActive

        /** `true` when this decision represents a transitional, hold-dispatch state. */
        val isHoldState: Boolean
            get() = state == ExecutionModeState.TRANSITIONING

        /**
         * Builds the canonical wire map for this decision.
         *
         * V2 `android_runtime_transition_reducer.py` should absorb all keys from this map when
         * present in a device-state snapshot:
         *
         *  - [KEY_EXECUTION_MODE_STATE]       — [ExecutionModeState.wireValue]
         *  - [KEY_TRIGGERING_EVENT]           — [ModeTransitionEvent.wireValue], or absent if null
         *  - [KEY_TRANSITIONING_TO]           — [ExecutionModeState.wireValue] of [transitioningTo], or absent
         *  - [KEY_ACCEPTS_CROSS_DEVICE_TASKS] — [acceptsCrossDeviceTasks]
         *  - [KEY_V2_GOVERNANCE_ACTIVE]       — [v2GovernanceActive]
         *  - [KEY_IS_HOLD_STATE]              — [isHoldState]
         *  - [KEY_DEGRADATION_REASONS]        — comma-separated reasons, or empty string
         *  - [KEY_SEMANTIC_TAG]               — [semanticTag]
         *  - [KEY_SCHEMA_VERSION]             — [schemaVersion]
         */
        fun toWireMap(): Map<String, Any> = buildMap {
            put(KEY_EXECUTION_MODE_STATE, state.wireValue)
            put(KEY_ACCEPTS_CROSS_DEVICE_TASKS, acceptsCrossDeviceTasks)
            put(KEY_V2_GOVERNANCE_ACTIVE, v2GovernanceActive)
            put(KEY_IS_HOLD_STATE, isHoldState)
            put(KEY_DEGRADATION_REASONS, degradationReasons.joinToString(","))
            put(KEY_SEMANTIC_TAG, semanticTag)
            put(KEY_SCHEMA_VERSION, schemaVersion)
            triggeringEvent?.let { put(KEY_TRIGGERING_EVENT, it.wireValue) }
            transitioningTo?.let { put(KEY_TRANSITIONING_TO, it.wireValue) }
        }
    }

    // ── Transition table ──────────────────────────────────────────────────────

    /**
     * A named, registered mode transition entry.
     *
     * The complete [TRANSITION_TABLE] covers every valid (from → to) pair so that
     * V2 can detect unexpected or unknown transitions without parsing free-form logs.
     *
     * @property from            State before the transition.
     * @property to              State after the transition.
     * @property trigger         The [ModeTransitionEvent] that drives this transition.
     * @property semanticClass   High-level semantic class for V2 categorization.
     * @property v2ConsumerNote  Short guidance for V2 `android_runtime_transition_reducer.py`.
     */
    data class ModeTransitionEntry(
        val from: ExecutionModeState,
        val to: ExecutionModeState,
        val trigger: ModeTransitionEvent,
        val semanticClass: TransitionSemanticClass,
        val v2ConsumerNote: String
    )

    /**
     * High-level semantic classification for a mode transition.
     *
     * @property wireValue Stable string for wire or log serialization.
     */
    enum class TransitionSemanticClass(val wireValue: String) {
        /** Android is becoming available as a cross-device participant. */
        ACTIVATION("activation"),
        /** Android is voluntarily leaving cross-device mode (remains live locally). */
        DEACTIVATION("deactivation"),
        /** Android's capability dropped; cross-device mode is still active but degraded. */
        DEGRADATION("degradation"),
        /** Android's capability fully recovered after a degraded or reconnecting period. */
        RECOVERY("recovery"),
        /** Android is changing modes and task dispatch is on hold. */
        HOLD("hold"),
        /** Android runtime is starting up or shutting down. */
        LIFECYCLE("lifecycle")
    }

    /**
     * The complete, registered set of valid mode transitions.
     *
     * V2 `android_runtime_transition_reducer.py` should validate any observed transition
     * against this table.  Transitions not present here are unexpected and should be
     * treated as contract-drift signals.
     */
    val TRANSITION_TABLE: List<ModeTransitionEntry> = listOf(

        // ── Lifecycle transitions ─────────────────────────────────────────────

        ModeTransitionEntry(
            from = ExecutionModeState.INACTIVE,
            to = ExecutionModeState.LOCAL_ONLY,
            trigger = ModeTransitionEvent.RUNTIME_STARTED,
            semanticClass = TransitionSemanticClass.LIFECYCLE,
            v2ConsumerNote = "Android runtime started in local-only mode; do not count as disconnection."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.INACTIVE,
            to = ExecutionModeState.TRANSITIONING,
            trigger = ModeTransitionEvent.RUNTIME_STARTED,
            semanticClass = TransitionSemanticClass.LIFECYCLE,
            v2ConsumerNote = "Android runtime started with cross-device enabled; WS connection in progress."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.LOCAL_ONLY,
            to = ExecutionModeState.INACTIVE,
            trigger = ModeTransitionEvent.RUNTIME_STOPPED,
            semanticClass = TransitionSemanticClass.LIFECYCLE,
            v2ConsumerNote = "Android runtime stopped from local-only; device is now fully inactive."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            to = ExecutionModeState.INACTIVE,
            trigger = ModeTransitionEvent.RUNTIME_STOPPED,
            semanticClass = TransitionSemanticClass.LIFECYCLE,
            v2ConsumerNote = "Android runtime stopped while cross-device active; treat as participant departure."
        ),

        // ── Activation transitions ────────────────────────────────────────────

        ModeTransitionEntry(
            from = ExecutionModeState.LOCAL_ONLY,
            to = ExecutionModeState.TRANSITIONING,
            trigger = ModeTransitionEvent.CROSS_DEVICE_ENABLED,
            semanticClass = TransitionSemanticClass.HOLD,
            v2ConsumerNote = "User enabled cross-device; WS connection starting; hold task dispatch."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.TRANSITIONING,
            to = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            trigger = ModeTransitionEvent.WS_CONNECTED,
            semanticClass = TransitionSemanticClass.ACTIVATION,
            v2ConsumerNote = "WS established; Android now accepts cross-device tasks. V2 governance active."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.TRANSITIONING,
            to = ExecutionModeState.LOCAL_ONLY,
            trigger = ModeTransitionEvent.CROSS_DEVICE_DISABLED,
            semanticClass = TransitionSemanticClass.DEACTIVATION,
            v2ConsumerNote = "Cross-device disabled during transition; device remains live in local-only."
        ),

        // ── Deactivation transitions ──────────────────────────────────────────

        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            to = ExecutionModeState.LOCAL_ONLY,
            trigger = ModeTransitionEvent.CROSS_DEVICE_DISABLED,
            semanticClass = TransitionSemanticClass.DEACTIVATION,
            v2ConsumerNote = "Cross-device disabled; Android reverts to local-only. Not a disconnect."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_DEGRADED,
            to = ExecutionModeState.LOCAL_ONLY,
            trigger = ModeTransitionEvent.CROSS_DEVICE_DISABLED,
            semanticClass = TransitionSemanticClass.DEACTIVATION,
            v2ConsumerNote = "Cross-device disabled from degraded state; device reverts to local-only."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            to = ExecutionModeState.TRANSITIONING,
            trigger = ModeTransitionEvent.WS_DISCONNECTED,
            semanticClass = TransitionSemanticClass.HOLD,
            v2ConsumerNote = "WS lost; reconnect-recovery in progress. Hold dispatch until reconnect or final disconnect."
        ),

        // ── Degradation / recovery transitions ───────────────────────────────

        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            to = ExecutionModeState.CROSS_DEVICE_DEGRADED,
            trigger = ModeTransitionEvent.CAPABILITY_DEGRADED,
            semanticClass = TransitionSemanticClass.DEGRADATION,
            v2ConsumerNote = "Capability degraded; do not dispatch inference-dependent tasks. Check degradation_reasons."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.CROSS_DEVICE_DEGRADED,
            to = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            trigger = ModeTransitionEvent.CAPABILITY_RESTORED,
            semanticClass = TransitionSemanticClass.RECOVERY,
            v2ConsumerNote = "All capability dimensions restored; full cross-device task acceptance resumed."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.TRANSITIONING,
            to = ExecutionModeState.CROSS_DEVICE_ACTIVE,
            trigger = ModeTransitionEvent.CAPABILITY_RESTORED,
            semanticClass = TransitionSemanticClass.RECOVERY,
            v2ConsumerNote = "Reconnect succeeded and capability fully restored; cross-device active again."
        ),
        ModeTransitionEntry(
            from = ExecutionModeState.TRANSITIONING,
            to = ExecutionModeState.CROSS_DEVICE_DEGRADED,
            trigger = ModeTransitionEvent.WS_CONNECTED,
            semanticClass = TransitionSemanticClass.ACTIVATION,
            v2ConsumerNote = "WS reconnected but capability still degraded; limited cross-device acceptance only."
        )
    )

    // ── Factory / decision logic ──────────────────────────────────────────────

    /**
     * Derives the [ModeGateDecision] for the given runtime inputs.
     *
     * This is the canonical factory used at device-state snapshot emission time.  The
     * returned decision's [ModeGateDecision.toWireMap] output should be merged into the
     * `DeviceStateSnapshotPayload` metadata before it is sent to V2.
     *
     * ## Decision rules
     *
     * | [crossDeviceEnabled] | [wsConnected] | [capabilityDegraded] | → [ExecutionModeState]     |
     * |----------------------|---------------|----------------------|----------------------------|
     * | false                | false         | any                  | LOCAL_ONLY                 |
     * | true                 | false         | any                  | TRANSITIONING              |
     * | true                 | true          | false                | CROSS_DEVICE_ACTIVE        |
     * | true                 | true          | true                 | CROSS_DEVICE_DEGRADED      |
     *
     * @param crossDeviceEnabled  `true` when `AppSettings.crossDeviceEnabled` is `true`.
     * @param wsConnected         `true` when the WebSocket connection to V2 is established.
     * @param capabilityDegraded  `true` when at least one execution dimension is unavailable.
     * @param degradationReasons  Machine-readable degradation reasons; non-empty when [capabilityDegraded].
     * @param triggeringEvent     The [ModeTransitionEvent] that caused this snapshot, or `null`
     *                            when emitting a scheduled/periodic snapshot.
     * @param runtimeActive       `true` when [RuntimeController] is started.  When `false`,
     *                            returns [ExecutionModeState.INACTIVE] regardless of other inputs.
     * @return The canonical [ModeGateDecision] for V2 consumption.
     */
    fun decide(
        crossDeviceEnabled: Boolean,
        wsConnected: Boolean,
        capabilityDegraded: Boolean,
        degradationReasons: List<String> = emptyList(),
        triggeringEvent: ModeTransitionEvent? = null,
        runtimeActive: Boolean = true
    ): ModeGateDecision {
        if (!runtimeActive) {
            return ModeGateDecision(
                state = ExecutionModeState.INACTIVE,
                triggeringEvent = triggeringEvent,
                transitioningTo = null,
                degradationReasons = emptyList(),
                semanticTag = "mode.inactive.runtime_not_active",
                rationale = "RuntimeController is not active; device is fully inactive."
            )
        }

        val state = when {
            !crossDeviceEnabled -> ExecutionModeState.LOCAL_ONLY
            crossDeviceEnabled && !wsConnected -> ExecutionModeState.TRANSITIONING
            crossDeviceEnabled && wsConnected && capabilityDegraded -> ExecutionModeState.CROSS_DEVICE_DEGRADED
            else -> ExecutionModeState.CROSS_DEVICE_ACTIVE
        }

        val transitioningTo = when (state) {
            ExecutionModeState.TRANSITIONING ->
                if (capabilityDegraded) ExecutionModeState.CROSS_DEVICE_DEGRADED
                else ExecutionModeState.CROSS_DEVICE_ACTIVE
            else -> null
        }

        val semanticTag = buildSemanticTag(state)
        val rationale = buildRationale(state, crossDeviceEnabled, wsConnected, capabilityDegraded, degradationReasons)

        return ModeGateDecision(
            state = state,
            triggeringEvent = triggeringEvent,
            transitioningTo = transitioningTo,
            degradationReasons = if (capabilityDegraded) degradationReasons else emptyList(),
            semanticTag = semanticTag,
            rationale = rationale
        )
    }

    /**
     * Returns `true` when the given state/event pair is a registered valid transition.
     *
     * Used by V2 `android_runtime_transition_reducer.py` to validate observed transitions.
     * An unregistered transition indicates potential Android-side or V2-side contract drift.
     *
     * @param from    The state before the transition.
     * @param to      The state after the transition.
     * @param trigger The event that triggered the transition.
     * @return `true` if the transition is registered in [TRANSITION_TABLE].
     */
    fun isRegisteredTransition(
        from: ExecutionModeState,
        to: ExecutionModeState,
        trigger: ModeTransitionEvent
    ): Boolean = TRANSITION_TABLE.any { it.from == from && it.to == to && it.trigger == trigger }

    // ── Invariants ────────────────────────────────────────────────────────────

    /**
     * Machine-verifiable gate invariants.
     *
     * All values in this map must be `true`.  Any `false` indicates a contract regression.
     * Tests MUST assert all invariants are `true`.
     */
    val GATE_INVARIANTS: Map<String, Boolean> = mapOf(
        "local_only_never_accepts_cross_device_tasks" to
            !ExecutionModeState.LOCAL_ONLY.acceptsCrossDeviceTasks,
        "inactive_never_accepts_cross_device_tasks" to
            !ExecutionModeState.INACTIVE.acceptsCrossDeviceTasks,
        "transitioning_never_accepts_cross_device_tasks" to
            !ExecutionModeState.TRANSITIONING.acceptsCrossDeviceTasks,
        "cross_device_active_always_accepts_tasks" to
            ExecutionModeState.CROSS_DEVICE_ACTIVE.acceptsCrossDeviceTasks,
        "cross_device_degraded_accepts_tasks" to
            ExecutionModeState.CROSS_DEVICE_DEGRADED.acceptsCrossDeviceTasks,
        "cross_device_active_v2_governance_active" to
            ExecutionModeState.CROSS_DEVICE_ACTIVE.v2GovernanceActive,
        "cross_device_degraded_v2_governance_active" to
            ExecutionModeState.CROSS_DEVICE_DEGRADED.v2GovernanceActive,
        "local_only_v2_governance_not_active" to
            !ExecutionModeState.LOCAL_ONLY.v2GovernanceActive,
        "inactive_v2_governance_not_active" to
            !ExecutionModeState.INACTIVE.v2GovernanceActive,
        "transitioning_v2_governance_not_active" to
            !ExecutionModeState.TRANSITIONING.v2GovernanceActive,
        "all_wire_values_distinct" to
            (ExecutionModeState.ALL_WIRE_VALUES.size == ExecutionModeState.entries.size),
        "all_event_wire_values_distinct" to
            (ModeTransitionEvent.ALL_WIRE_VALUES.size == ModeTransitionEvent.entries.size),
        "transition_table_non_empty" to
            TRANSITION_TABLE.isNotEmpty()
    )

    // ── Wire key constants ────────────────────────────────────────────────────

    /** Wire key for the execution mode state value. */
    const val KEY_EXECUTION_MODE_STATE = "execution_mode_state"

    /** Wire key for the triggering event, when present. */
    const val KEY_TRIGGERING_EVENT = "mode_triggering_event"

    /** Wire key for the transitioning-to target state, when in TRANSITIONING. */
    const val KEY_TRANSITIONING_TO = "mode_transitioning_to"

    /** Wire key for whether Android accepts cross-device tasks in the current state. */
    const val KEY_ACCEPTS_CROSS_DEVICE_TASKS = "mode_accepts_cross_device_tasks"

    /** Wire key for whether V2 governance is active in the current state. */
    const val KEY_V2_GOVERNANCE_ACTIVE = "mode_v2_governance_active"

    /** Wire key for whether this is a hold-dispatch state. */
    const val KEY_IS_HOLD_STATE = "mode_is_hold_state"

    /** Wire key for comma-separated degradation reasons (empty string when none). */
    const val KEY_DEGRADATION_REASONS = "mode_degradation_reasons"

    /** Wire key for the semantic tag. */
    const val KEY_SEMANTIC_TAG = "mode_semantic_tag"

    /** Wire key for the schema version. */
    const val KEY_SCHEMA_VERSION = "mode_gate_schema_version"

    /** All wire keys defined by this gate, for completeness checking in tests. */
    val ALL_WIRE_KEYS: Set<String> = setOf(
        KEY_EXECUTION_MODE_STATE,
        KEY_TRIGGERING_EVENT,
        KEY_TRANSITIONING_TO,
        KEY_ACCEPTS_CROSS_DEVICE_TASKS,
        KEY_V2_GOVERNANCE_ACTIVE,
        KEY_IS_HOLD_STATE,
        KEY_DEGRADATION_REASONS,
        KEY_SEMANTIC_TAG,
        KEY_SCHEMA_VERSION
    )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildSemanticTag(state: ExecutionModeState): String = when (state) {
        ExecutionModeState.INACTIVE -> "mode.inactive.runtime_not_active"
        ExecutionModeState.LOCAL_ONLY -> "mode.local_only.v2_not_governed"
        ExecutionModeState.CROSS_DEVICE_ACTIVE -> "mode.cross_device_active.v2_governed"
        ExecutionModeState.CROSS_DEVICE_DEGRADED -> "mode.cross_device_degraded.v2_governed.capability_limited"
        ExecutionModeState.TRANSITIONING -> "mode.transitioning.dispatch_hold"
    }

    private fun buildRationale(
        state: ExecutionModeState,
        crossDeviceEnabled: Boolean,
        wsConnected: Boolean,
        capabilityDegraded: Boolean,
        degradationReasons: List<String>
    ): String = when (state) {
        ExecutionModeState.INACTIVE ->
            "RuntimeController is not active."
        ExecutionModeState.LOCAL_ONLY ->
            "cross_device_enabled=false: Android is live in local-only mode, not a V2 participant."
        ExecutionModeState.CROSS_DEVICE_ACTIVE ->
            "cross_device_enabled=true, ws_connected=true, capability_degraded=false: fully operational."
        ExecutionModeState.CROSS_DEVICE_DEGRADED ->
            "cross_device_enabled=true, ws_connected=true, capability_degraded=true: " +
                "limited to non-inference tasks. Reasons: ${degradationReasons.joinToString("; ")}."
        ExecutionModeState.TRANSITIONING ->
            "cross_device_enabled=true, ws_connected=false: WS establishing or recovering; " +
                "hold task dispatch until WS_CONNECTED."
    }
}
