package com.ufo.galaxy.runtime

/**
 * PR-7 (Android) — Android canonical execution event owner / emitter / mapper /
 * policy layer for flow-level operator visibility.
 *
 * [AndroidCanonicalExecutionEventOwner] is the unified Android-side entry point for
 * canonical execution event classification, flow binding, emit gating, duplicate
 * suppression, high-frequency noise suppression, post-terminal suppression, and
 * reconnect/resume event alignment.  It extends the result convergence semantics
 * established by [AndroidFlowAwareResultConvergenceParticipant] (PR-6) into a
 * **flow-level canonical event layer** that answers the following questions for every
 * potential outbound execution event:
 *
 *  - Which [CanonicalExecutionEventType] does this runtime observation correspond to?
 *  - What [CanonicalExecutionEventEmitDecision] governs how the event is forwarded?
 *  - Should emission be suppressed because this event ID was already recorded?
 *  - Should emission be suppressed because this is high-frequency local noise?
 *  - Should emission be suppressed because the flow has already terminated?
 *  - Should emission be held because a reconnect / rebind / resume is in progress?
 *
 * ## Background and motivation
 *
 * Before PR-7, Android delegated runtime execution phase transitions were surfaced only
 * through ad-hoc [com.ufo.galaxy.observability.GalaxyLogger] log entries.  This meant:
 *
 *  - planning / grounding / execution / replan / stagnation / gate transitions had no
 *    first-class canonical representation.
 *  - operators could not see "where" a delegated flow was without grepping raw logs.
 *  - high-frequency step progress observations flooded any consumer that tried to follow
 *    the execution path.
 *  - after a flow reached a terminal state, stale "still running" events could still
 *    be emitted by lagging pipeline components.
 *  - reconnect / resume scenarios had no canonical event alignment gate.
 *
 * [AndroidCanonicalExecutionEventOwner] closes these gaps by providing a composable,
 * testable API that every Android runtime component can query before emitting a
 * [CanonicalExecutionEvent], establishing a stable device-side event stream that V2
 * flow-level operator surfaces can reliably consume.
 *
 * ## Event taxonomy
 *
 * The complete canonical execution event taxonomy is defined by [CanonicalExecutionEventType]:
 *
 * | Event family | Event types                                                                                 |
 * |--------------|---------------------------------------------------------------------------------------------|
 * | Planning     | [CanonicalExecutionEventType.PLANNING_STARTED], [CanonicalExecutionEventType.PLANNING_PROGRESS], [CanonicalExecutionEventType.PLANNING_BLOCKED] |
 * | Grounding    | [CanonicalExecutionEventType.GROUNDING_STARTED], [CanonicalExecutionEventType.GROUNDING_PROGRESS], [CanonicalExecutionEventType.GROUNDING_BLOCKED] |
 * | Execution    | [CanonicalExecutionEventType.EXECUTION_STARTED], [CanonicalExecutionEventType.EXECUTION_PROGRESS], [CanonicalExecutionEventType.EXECUTION_BLOCKED] |
 * | Recovery     | [CanonicalExecutionEventType.REPLAN_TRIGGERED], [CanonicalExecutionEventType.STAGNATION_DETECTED] |
 * | Gate         | [CanonicalExecutionEventType.GATE_DECISION_EMITTED]                                         |
 * | Milestones   | [CanonicalExecutionEventType.TAKEOVER_MILESTONE], [CanonicalExecutionEventType.COLLABORATION_MILESTONE], [CanonicalExecutionEventType.PARALLEL_MILESTONE] |
 *
 * ## Emit decision model
 *
 * Five decision layers are applied in [evaluateEventEmit] for each candidate event:
 *
 * 1. **Post-terminal suppression** — if the flow is already terminal
 *    ([isFlowTerminal] returns `true`), the event is suppressed as
 *    [CanonicalExecutionEventEmitDecision.SuppressPostTerminal].
 *
 * 2. **Duplicate suppression** — if the [CanonicalExecutionEvent.eventId] has already
 *    been recorded via [markEventEmitted], the event is suppressed as
 *    [CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent].
 *
 * 3. **High-frequency noise suppression** — if the noise gate for the flow's event type
 *    is active via [isNoiseGated], the event is suppressed as
 *    [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise].
 *
 * 4. **Reconnect alignment hold** — if the event emit gate for the flow is closed
 *    ([isEventGated] returns `true`), the event is held as
 *    [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment].
 *
 * 5. **Emit** — all remaining events are cleared for emission as
 *    [CanonicalExecutionEventEmitDecision.EmitEvent].
 *
 * ## Integration points
 *
 * [AndroidCanonicalExecutionEventOwner] establishes clear integration boundaries with
 * the following existing runtime modules:
 *
 * | Integration point constant         | Module                                                              | Role                                                                  |
 * |------------------------------------|---------------------------------------------------------------------|-----------------------------------------------------------------------|
 * | [INTEGRATION_LOOP_CONTROLLER]      | [com.ufo.galaxy.loop.LoopController]                                | Planning / grounding / execution / replan / stagnation events.        |
 * | [INTEGRATION_PIPELINE]             | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]                  | Execution started / blocked / gate decision events.                   |
 * | [INTEGRATION_UNIT]                 | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]                         | Unit identity binds all events to a stable flow / task context.       |
 * | [INTEGRATION_ACTIVATION_RECORD]    | [com.ufo.galaxy.runtime.DelegatedActivationRecord]                  | Activation record terminal status drives [markFlowTerminal].          |
 * | [INTEGRATION_RECEIVER]             | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]                     | Receipt is the anchor for flow identity; gate can be closed at receipt. |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]    | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]                    | Takeover milestone events.                                            |
 * | [INTEGRATION_COLLABORATION_AGENT]  | [com.ufo.galaxy.agent.LocalCollaborationAgent]                      | Collaboration milestone events.                                       |
 *
 * ## Emit / suppress / dedupe rule summary
 *
 * | Rule                          | Governing method                                    | Decision outcome                                                |
 * |-------------------------------|-----------------------------------------------------|-----------------------------------------------------------------|
 * | Flow already terminal         | [markFlowTerminal] / [isFlowTerminal]               | [CanonicalExecutionEventEmitDecision.SuppressPostTerminal]      |
 * | Duplicate event ID            | [markEventEmitted] / [isEventEmitted]               | [CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent]    |
 * | High-frequency noise gate     | [openNoiseGate] / [closeNoiseGate] / [isNoiseGated] | [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise]|
 * | Reconnect alignment gate open | [openEventGate] / [closeEventGate] / [isEventGated] | [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment] |
 * | All clear                     | N/A                                                 | [CanonicalExecutionEventEmitDecision.EmitEvent]                 |
 *
 * ## Reconnect / resume / rebind event re-entry
 *
 * When a reconnect, transport rebind, or session resume begins:
 * 1. Call [closeEventGate] for the affected flow(s).
 * 2. Continue evaluating events via [evaluateEventEmit]; gated flows will yield
 *    [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment].
 * 3. Once V2 canonical alignment is re-established, call [openEventGate].
 * 4. Callers may optionally re-evaluate buffered events to determine whether they should
 *    be emitted as reconnect-replay events.
 *
 * ## Thread safety
 *
 * [markFlowTerminal], [clearFlowTerminal], [isFlowTerminal], [terminalFlowCount],
 * [clearAllTerminalFlows], [markEventEmitted], [isEventEmitted], [emittedEventCount],
 * [clearAllEmittedEvents], [closeEventGate], [openEventGate], [isEventGated],
 * [eventGatedFlowCount], [clearAllEventGates], [openNoiseGate], [closeNoiseGate],
 * [isNoiseGated], and [noiseGatedEntryCount] all use [synchronized] blocks for safe
 * cross-thread access.  [evaluateEventEmit] and [classifyEventType] are pure functions
 * that rely only on the synchronized accessors above.
 *
 * ## Relationship to [AndroidFlowAwareResultConvergenceParticipant]
 *
 * [AndroidCanonicalExecutionEventOwner] is the *execution event* layer; it governs when
 * execution phase transitions and milestones are projected toward V2.
 * [AndroidFlowAwareResultConvergenceParticipant] governs when *result* artifacts
 * (partial / final / parallel sub-results) are projected.  The two layers are designed
 * to be composed side-by-side in the delegated runtime; they share the same flow identity
 * model but operate on different artifact types.  Execution events are
 * operator-facing canonical projection inputs; they are not free-form logs.
 *
 * @see CanonicalExecutionEvent
 * @see CanonicalExecutionEventType
 * @see CanonicalExecutionEventEmitDecision
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidLocalTruthOwnershipCoordinator
 */
class AndroidCanonicalExecutionEventOwner {

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Registry of flow IDs that have reached a terminal state.
     *
     * Map key is [flowId]; value is the terminal reason string passed to [markFlowTerminal].
     * Added by [markFlowTerminal]; removed by [clearFlowTerminal] or [clearAllTerminalFlows].
     */
    private val _terminalFlows: MutableMap<String, String> = mutableMapOf()

    /**
     * Registry of event IDs that have already been emitted.
     *
     * This set is the idempotent suppression store for [CanonicalExecutionEvent.eventId].
     * Added by [markEventEmitted]; cleared by [clearAllEmittedEvents].
     */
    private val _emittedEventIds: MutableSet<String> = mutableSetOf()

    /**
     * Set of flow IDs for which the event emit gate is currently closed pending
     * reconnect / rebind / resume alignment.
     *
     * A gated flow must not emit any canonical execution event until [openEventGate] is
     * called.
     */
    private val _eventGatedFlows: MutableSet<String> = mutableSetOf()

    /**
     * Registry of flow-level noise gate entries.
     *
     * A noise gate entry for [flowId] + [CanonicalExecutionEventType.wireValue] suppresses
     * high-frequency progress events that would otherwise flood the operator surface.
     * Map key is `"$flowId:$eventTypeWireValue"`.
     */
    private val _noiseGatedEntries: MutableSet<String> = mutableSetOf()

    // ── Core API — event type classification ──────────────────────────────────

    /**
     * Classifies an event type string into a [CanonicalExecutionEventType].
     *
     * Maps canonical event type wire strings to the appropriate
     * [CanonicalExecutionEventType] using [CanonicalExecutionEventType.fromValue].
     *
     * Unknown type strings default to [CanonicalExecutionEventType.DEFAULT]
     * ([CanonicalExecutionEventType.EXECUTION_PROGRESS]).
     *
     * @param type  The event type wire string to classify.
     * @return The [CanonicalExecutionEventType] for the given string.
     */
    fun classifyEventType(type: String): CanonicalExecutionEventType =
        CanonicalExecutionEventType.fromValue(type)

    // ── Core API — emit decision ───────────────────────────────────────────────

    /**
     * Evaluates whether and how a canonical execution event should be emitted.
     *
     * ## Decision logic
     *
     * 1. **Post-terminal suppression** — if [isFlowTerminal] is `true` for
     *    [event.flowId][CanonicalExecutionEvent.flowId] →
     *    [CanonicalExecutionEventEmitDecision.SuppressPostTerminal].
     *
     * 2. **Duplicate suppression** — if [isEventEmitted] is `true` for
     *    [event.eventId][CanonicalExecutionEvent.eventId] →
     *    [CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent].
     *
     * 3. **High-frequency noise suppression** — if [isNoiseGated] is `true` for
     *    [event.flowId][CanonicalExecutionEvent.flowId] and
     *    [event.eventType][CanonicalExecutionEvent.eventType] →
     *    [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise].
     *
     * 4. **Reconnect alignment hold** — if [isEventGated] is `true` for
     *    [event.flowId][CanonicalExecutionEvent.flowId] →
     *    [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment].
     *
     * 5. **Emit** — otherwise →
     *    [CanonicalExecutionEventEmitDecision.EmitEvent].
     *
     * @param event  The [CanonicalExecutionEvent] candidate to evaluate.
     * @return The [CanonicalExecutionEventEmitDecision] governing emission.
     */
    fun evaluateEventEmit(event: CanonicalExecutionEvent): CanonicalExecutionEventEmitDecision {

        // ── 1. Post-terminal suppression ───────────────────────────────────────
        val terminalReason = flowTerminalReason(event.flowId)
        if (terminalReason != null) {
            return CanonicalExecutionEventEmitDecision.SuppressPostTerminal(
                flowId = event.flowId,
                terminalReason = terminalReason
            )
        }

        // ── 2. Duplicate suppression ───────────────────────────────────────────
        if (isEventEmitted(event.eventId)) {
            return CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent(
                flowId = event.flowId,
                eventId = event.eventId
            )
        }

        // ── 3. High-frequency noise suppression ────────────────────────────────
        if (isNoiseGated(event.flowId, event.eventType)) {
            return CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise(
                flowId = event.flowId,
                reason = REASON_NOISE_GATE_ACTIVE
            )
        }

        // ── 4. Reconnect alignment hold ────────────────────────────────────────
        if (isEventGated(event.flowId)) {
            return CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment(
                flowId = event.flowId,
                reason = REASON_EVENT_GATE_CLOSED_PENDING_RECONNECT_ALIGNMENT
            )
        }

        // ── 5. Emit ────────────────────────────────────────────────────────────
        return CanonicalExecutionEventEmitDecision.EmitEvent(event = event)
    }

    // ── Terminal flow registry ─────────────────────────────────────────────────

    /**
     * Marks the given [flowId] as having reached a local terminal state, recording
     * [terminalReason] as the cause.
     *
     * Once registered, [evaluateEventEmit] will return
     * [CanonicalExecutionEventEmitDecision.SuppressPostTerminal] for all subsequent
     * canonical execution event evaluations for this flow.
     *
     * Callers should invoke this **after** emitting the terminal result signal and
     * **before** any subsequent event evaluation, to ensure the suppression guard is
     * in place.
     *
     * @param flowId         The delegated flow ID to mark as terminal.
     * @param terminalReason Human-readable description of the terminal cause
     *                       (e.g. `"completed"`, `"failed"`, `"cancelled"`).
     */
    fun markFlowTerminal(flowId: String, terminalReason: String) {
        synchronized(_terminalFlows) { _terminalFlows[flowId] = terminalReason }
    }

    /**
     * Returns `true` when the given [flowId] has been registered as terminal.
     *
     * @param flowId  The delegated flow ID to check.
     */
    fun isFlowTerminal(flowId: String): Boolean =
        synchronized(_terminalFlows) { _terminalFlows.containsKey(flowId) }

    /**
     * Returns the terminal reason string registered for [flowId], or `null` if the flow
     * has not yet been marked terminal.
     *
     * @param flowId  The delegated flow ID to query.
     */
    fun flowTerminalReason(flowId: String): String? =
        synchronized(_terminalFlows) { _terminalFlows[flowId] }

    /**
     * Clears the terminal registration for a single [flowId].
     *
     * Typically called when a new execution era begins for this flow after a completed
     * or failed one (e.g. replay / re-dispatch).
     *
     * @param flowId  The delegated flow ID to clear.
     */
    fun clearFlowTerminal(flowId: String) {
        synchronized(_terminalFlows) { _terminalFlows.remove(flowId) }
    }

    /**
     * Returns the count of currently registered terminal flow IDs.
     */
    val terminalFlowCount: Int
        get() = synchronized(_terminalFlows) { _terminalFlows.size }

    /**
     * Clears the entire terminal flow registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).
     */
    fun clearAllTerminalFlows() {
        synchronized(_terminalFlows) { _terminalFlows.clear() }
    }

    // ── Emitted event ID registry ─────────────────────────────────────────────

    /**
     * Records [eventId] as having been emitted.
     *
     * After this call, [evaluateEventEmit] will return
     * [CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent] for any event whose
     * [CanonicalExecutionEvent.eventId] matches [eventId].  This provides the idempotency
     * suppression entry point for event replay / resend scenarios.
     *
     * @param eventId  The stable idempotency key of the event that was emitted.
     */
    fun markEventEmitted(eventId: String) {
        synchronized(_emittedEventIds) { _emittedEventIds.add(eventId) }
    }

    /**
     * Returns `true` when [eventId] has already been recorded as emitted.
     *
     * @param eventId  The idempotency key to check.
     */
    fun isEventEmitted(eventId: String): Boolean =
        synchronized(_emittedEventIds) { _emittedEventIds.contains(eventId) }

    /**
     * Returns the count of currently recorded emitted event IDs.
     */
    val emittedEventCount: Int
        get() = synchronized(_emittedEventIds) { _emittedEventIds.size }

    /**
     * Clears the entire emitted event ID registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).
     */
    fun clearAllEmittedEvents() {
        synchronized(_emittedEventIds) { _emittedEventIds.clear() }
    }

    // ── Event emit gate ───────────────────────────────────────────────────────

    /**
     * Closes the canonical execution event emit gate for the given [flowId].
     *
     * While the gate is closed, [evaluateEventEmit] returns
     * [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment] for all event
     * evaluations for this flow (after terminal and duplicate checks).  Call this at
     * the start of a reconnect / rebind / resume sequence to prevent event emission
     * before V2 canonical alignment is established.
     *
     * @param flowId  The delegated flow ID for which to close the event gate.
     */
    fun closeEventGate(flowId: String) {
        synchronized(_eventGatedFlows) { _eventGatedFlows.add(flowId) }
    }

    /**
     * Opens the canonical execution event emit gate for the given [flowId].
     *
     * After the gate is opened, [evaluateEventEmit] will no longer return
     * [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment] for the gate check.
     * Call this once the reconnect / rebind / resume sequence has been resolved and V2
     * canonical event stream alignment is complete.
     *
     * @param flowId  The delegated flow ID for which to open the event gate.
     */
    fun openEventGate(flowId: String) {
        synchronized(_eventGatedFlows) { _eventGatedFlows.remove(flowId) }
    }

    /**
     * Returns `true` when the event emit gate for [flowId] is currently closed.
     *
     * @param flowId  The delegated flow ID to check.
     */
    fun isEventGated(flowId: String): Boolean =
        synchronized(_eventGatedFlows) { _eventGatedFlows.contains(flowId) }

    /**
     * Returns the count of flow IDs for which the event emit gate is currently closed.
     */
    val eventGatedFlowCount: Int
        get() = synchronized(_eventGatedFlows) { _eventGatedFlows.size }

    /**
     * Clears all event emit gates.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).
     */
    fun clearAllEventGates() {
        synchronized(_eventGatedFlows) { _eventGatedFlows.clear() }
    }

    // ── High-frequency noise gate ─────────────────────────────────────────────

    /**
     * Opens (activates) the high-frequency noise suppression gate for the given
     * [flowId] and [eventType] combination.
     *
     * While the noise gate is active, [evaluateEventEmit] returns
     * [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise] for all events
     * of [eventType] for [flowId].  Use this to suppress high-rate progress events that
     * would flood the V2 operator surface without adding operator-visible information.
     *
     * Typical call sites:
     *  - Open the noise gate for [CanonicalExecutionEventType.EXECUTION_PROGRESS] after
     *    every N steps so that only every Nth step event reaches V2.
     *  - Open the noise gate for [CanonicalExecutionEventType.GROUNDING_PROGRESS] when
     *    the grounding fallback ladder is cycling rapidly.
     *
     * @param flowId     The delegated flow ID.
     * @param eventType  The [CanonicalExecutionEventType] to suppress for this flow.
     */
    fun openNoiseGate(flowId: String, eventType: CanonicalExecutionEventType) {
        synchronized(_noiseGatedEntries) {
            _noiseGatedEntries.add(noiseGateKey(flowId, eventType))
        }
    }

    /**
     * Closes (deactivates) the high-frequency noise suppression gate for the given
     * [flowId] and [eventType] combination.
     *
     * After this call, [evaluateEventEmit] will no longer return
     * [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise] for this
     * combination.
     *
     * @param flowId     The delegated flow ID.
     * @param eventType  The [CanonicalExecutionEventType] to stop suppressing.
     */
    fun closeNoiseGate(flowId: String, eventType: CanonicalExecutionEventType) {
        synchronized(_noiseGatedEntries) {
            _noiseGatedEntries.remove(noiseGateKey(flowId, eventType))
        }
    }

    /**
     * Returns `true` when the noise gate is currently active for the given [flowId]
     * and [eventType] combination.
     *
     * @param flowId     The delegated flow ID.
     * @param eventType  The [CanonicalExecutionEventType] to check.
     */
    fun isNoiseGated(flowId: String, eventType: CanonicalExecutionEventType): Boolean =
        synchronized(_noiseGatedEntries) {
            _noiseGatedEntries.contains(noiseGateKey(flowId, eventType))
        }

    /**
     * Returns the total count of currently active noise gate entries across all flows
     * and event types.
     */
    val noiseGatedEntryCount: Int
        get() = synchronized(_noiseGatedEntries) { _noiseGatedEntries.size }

    /**
     * Clears all noise gate entries.
     *
     * Use only when the full execution era is being reset.
     */
    fun clearAllNoiseGates() {
        synchronized(_noiseGatedEntries) { _noiseGatedEntries.clear() }
    }

    private fun noiseGateKey(flowId: String, eventType: CanonicalExecutionEventType): String =
        "$flowId:${eventType.wireValue}"

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Emit decision semantic constants ─────────────────────────────────

        /**
         * Canonical wire value for the "emit event" decision.
         *
         * All suppression and hold conditions were evaluated and none applied.  The
         * event should be forwarded to V2 as a canonical execution event projection.
         */
        const val DECISION_EMIT_EVENT = "emit_event"

        /**
         * Canonical wire value for the "suppress duplicate event" decision.
         *
         * This event ID was previously recorded via [markEventEmitted].  The duplicate
         * emission must be suppressed to maintain idempotent canonical projection.
         */
        const val DECISION_SUPPRESS_DUPLICATE_EVENT = "suppress_duplicate_event"

        /**
         * Canonical wire value for the "suppress high frequency noise" decision.
         *
         * The noise gate for this flow and event type is active.  The event would
         * constitute unbounded local state noise and must be suppressed to avoid
         * flooding the V2 operator surface.
         */
        const val DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE = "suppress_high_frequency_noise"

        /**
         * Canonical wire value for the "suppress post terminal" decision.
         *
         * The delegated flow has already reached a terminal state.  Conflicting
         * execution events that would show the flow as still active must be suppressed.
         */
        const val DECISION_SUPPRESS_POST_TERMINAL = "suppress_post_terminal"

        /**
         * Canonical wire value for the "hold for reconnect alignment" decision.
         *
         * The event emit gate is closed pending V2 canonical event stream alignment
         * after a reconnect / rebind / resume sequence.  The event must be buffered
         * or discarded until the gate opens.
         */
        const val DECISION_HOLD_FOR_RECONNECT_ALIGNMENT = "hold_for_reconnect_alignment"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * [LoopController] is the primary emitter of planning, grounding, execution,
         * replan, and stagnation canonical execution events.  Each planning cycle start,
         * grounding attempt, step completion, replan trigger, and stagnation detection
         * should produce a corresponding [CanonicalExecutionEvent] via [evaluateEventEmit]
         * before being forwarded.
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * [AutonomousExecutionPipeline] emits [CanonicalExecutionEventType.EXECUTION_STARTED],
         * [CanonicalExecutionEventType.EXECUTION_BLOCKED], and
         * [CanonicalExecutionEventType.GATE_DECISION_EMITTED] events when the pipeline
         * accepts, gates, or blocks a delegated goal execution.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * Unit identity ([DelegatedRuntimeUnit.unitId] / [DelegatedRuntimeUnit.taskId] /
         * [DelegatedRuntimeUnit.traceId]) is the stable anchor for all canonical execution
         * events emitted for this delegated flow.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * When [DelegatedActivationRecord.activationStatus] transitions to a terminal
         * status (COMPLETED, FAILED, REJECTED), callers must invoke [markFlowTerminal]
         * with the corresponding [flowId] and terminal reason to enable post-terminal
         * suppression.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * Receipt acceptance is the anchor for [CanonicalExecutionEvent.flowId] and is
         * the earliest point at which [closeEventGate] may be called to protect the
         * canonical event stream during reconnect.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * [DelegatedTakeoverExecutor] emits [CanonicalExecutionEventType.TAKEOVER_MILESTONE]
         * events at key lifecycle transitions: ACK accepted, execution started, takeover
         * active, result produced.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * [LocalCollaborationAgent] emits [CanonicalExecutionEventType.COLLABORATION_MILESTONE]
         * events at key collaboration state transitions and
         * [CanonicalExecutionEventType.PARALLEL_MILESTONE] events for parallel subtask arms.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        // ── Reason constants ──────────────────────────────────────────────────

        /**
         * Reason used in [CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment]
         * when the event gate is closed pending V2 canonical alignment after a reconnect.
         */
        const val REASON_EVENT_GATE_CLOSED_PENDING_RECONNECT_ALIGNMENT =
            "event_gate_closed_pending_v2_reconnect_alignment"

        /**
         * Reason used in [CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise]
         * when the noise gate is active for the given flow and event type.
         */
        const val REASON_NOISE_GATE_ACTIVE = "noise_gate_active_for_flow_and_event_type"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this component.
         */
        const val INTRODUCED_PR = 7

        /**
         * Human-readable description of this component's role in the canonical execution
         * event model.
         */
        const val DESCRIPTION =
            "Android-side canonical execution event owner: classifies planning/grounding/" +
                "execution/replan/stagnation/gate/takeover/collaboration/parallel events, " +
                "gates emit during reconnect/resume, suppresses post-terminal events, " +
                "suppresses high-frequency noise, suppresses duplicate event emissions, " +
                "and projects canonical execution events to V2 flow-level operator surfaces."
    }
}
