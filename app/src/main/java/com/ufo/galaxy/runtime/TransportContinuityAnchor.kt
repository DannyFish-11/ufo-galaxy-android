package com.ufo.galaxy.runtime

/**
 * PR-40 — Android Transport Continuity Anchor.
 *
 * Governs the canonical transport continuity semantics for Android-side runtime
 * sessions during the key lifecycle events that change transport connectivity:
 * **attach**, **detach**, **reconnect**, **degradation**, and **recovery**.
 *
 * ## Purpose
 *
 * The Android runtime handles several transport lifecycle events that affect session
 * continuity and task viability.  Prior to PR-40 the continuity policy for each event
 * type was implicit, spread across individual lifecycle methods in [RuntimeController]
 * and the permanent WS listener.  This created:
 *
 * - **Ownership ambiguity** — no single declaration stated which layer (transport vs.
 *   runtime vs. operator) owned each continuity decision.
 * - **Recovery path gaps** — the expected recovery sequence after each event was not
 *   declared, making it difficult to verify that post-event session state was coherent.
 * - **Extension risk** — new transport event handling could evolve independently of the
 *   canonical continuity model, reintroducing divergence over time.
 *
 * [TransportContinuityAnchor] fills that gap by declaring:
 *
 * - The canonical set of transport lifecycle events ([TransportEvent]).
 * - The [ContinuityPolicy] that applies to session state for each event.
 * - The authority that owns the continuity decision ([ContinuityOwnership]).
 * - The expected session state after the event ([PostEventSessionState]).
 * - The recovery path back to full session viability.
 *
 * ## Design intent
 *
 * This is an **additive, compatibility-safe** governance model.  It does not change
 * any existing runtime behavior, wire contracts, or identifier values.  All continuity
 * decisions declared here are consistent with the existing [RuntimeController] lifecycle
 * implementation; this object simply makes them explicit and machine-readable.
 *
 * ## Transport event model
 *
 * | [TransportEvent]                              | Trigger                                                        |
 * |-----------------------------------------------|----------------------------------------------------------------|
 * | [TransportEvent.ATTACH]                       | New session successfully opened; WS connected and registered.  |
 * | [TransportEvent.DETACH]                       | Session explicitly closed (DISABLE or EXPLICIT_DETACH cause).  |
 * | [TransportEvent.RECONNECT]                    | WS reconnected after a prior disconnect; session reopened.     |
 * | [TransportEvent.DEGRADATION]                  | Transport quality declined (see [MediaTransportLifecycleBridge]).|
 * | [TransportEvent.RECOVERY]                     | Transport quality restored after degradation or interruption.  |
 *
 * ## Continuity policy model
 *
 * | [ContinuityPolicy]                            | Session identity behavior                                      |
 * |-----------------------------------------------|----------------------------------------------------------------|
 * | [ContinuityPolicy.PERSIST]                    | Session identity is unchanged; reuse remains valid.            |
 * | [ContinuityPolicy.RESHAPES]                   | New [AttachedRuntimeSession] created; durable identity persists.|
 * | [ContinuityPolicy.TERMINATES]                 | Session terminated; durable era ended.                         |
 * | [ContinuityPolicy.SUSPENDS]                   | Session detached; durable era persists; recovery expected.     |
 *
 * ## Relationship to other governance objects
 *
 * - [MediaTransportLifecycleBridge] — companion PR-40 object that maps transport
 *   conditions to lifecycle adaptations.  [TransportContinuityAnchor] governs the
 *   session continuity policy; [MediaTransportLifecycleBridge] governs the task
 *   lifecycle adaptation.
 * - [DurableSessionContinuityRecord] — the durable session anchor that persists across
 *   reconnects.  [TransportContinuityAnchor.RECONNECT] entries declare that the durable
 *   era survives reconnect with an incremented epoch.
 * - [ReconnectRecoveryState] — user-facing reconnect phase.  [TransportEvent.RECONNECT]
 *   maps to the [ReconnectRecoveryState.RECOVERED] outcome.
 * - [AttachedRuntimeSession.DetachCause] — session termination cause.  Each
 *   [TransportEvent] that terminates a session declares which [AttachedRuntimeSession.DetachCause]
 *   applies.
 * - [StabilizationBaseline] — PR-40 entries registered in [StabilizationBaseline.entries].
 *
 * @see MediaTransportLifecycleBridge
 * @see DurableSessionContinuityRecord
 * @see ReconnectRecoveryState
 */
object TransportContinuityAnchor {

    // ── Transport event ───────────────────────────────────────────────────────

    /**
     * A canonical transport lifecycle event that affects session continuity.
     *
     * Each value maps to one [TransportAnchorEntry] in [anchorEntries].
     *
     * @property wireValue Stable lowercase string used in structured log entries and
     *                     any host-facing transport-event protocol fields.
     */
    enum class TransportEvent(val wireValue: String) {

        /**
         * New session successfully opened.
         *
         * Triggered when [RuntimeController] transitions to [RuntimeController.RuntimeState.Active]
         * and [RuntimeController.openAttachedSession] creates a new [AttachedRuntimeSession].
         * Corresponds to [RuntimeController.SessionOpenSource.USER_ACTIVATION] or
         * [RuntimeController.SessionOpenSource.BACKGROUND_RESTORE].
         */
        ATTACH("attach"),

        /**
         * Session explicitly closed by operator or user.
         *
         * Triggered by [RuntimeController.stop] ([AttachedRuntimeSession.DetachCause.DISABLE])
         * or by an explicit remote detach signal ([AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH]).
         * The durable session era ends; no automatic recovery is expected.
         */
        DETACH("detach"),

        /**
         * WS reconnected after a prior disconnect; session reopened.
         *
         * Triggered by the permanent WS listener in [RuntimeController] when the WS client
         * successfully reconnects after a [TransportEvent.DEGRADATION] or transport interruption.
         * [RuntimeController.openAttachedSession] is called with
         * [RuntimeController.SessionOpenSource.RECONNECT_RECOVERY].  The durable session era
         * persists with an incremented [DurableSessionContinuityRecord.sessionContinuityEpoch].
         */
        RECONNECT("reconnect"),

        /**
         * Transport quality declined.
         *
         * Triggered when [MediaTransportLifecycleBridge] detects a transition to
         * [MediaTransportLifecycleBridge.TransportCondition.DEGRADED] or
         * [MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED].
         * The session may remain open (DEGRADED) or be closed pending reconnect (INTERRUPTED).
         */
        DEGRADATION("degradation"),

        /**
         * Transport quality restored after prior degradation or interruption.
         *
         * Triggered when the transport layer signals that conditions have returned to
         * [MediaTransportLifecycleBridge.TransportCondition.STABLE].  The runtime resumes
         * full task acceptance.  [ReconnectRecoveryState] transitions to [ReconnectRecoveryState.RECOVERED].
         */
        RECOVERY("recovery");

        companion object {
            /**
             * Returns the [TransportEvent] matching [value], or `null` for unknown values.
             */
            fun fromValue(value: String?): TransportEvent? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Continuity policy ─────────────────────────────────────────────────────

    /**
     * Session continuity policy applied when a [TransportEvent] occurs.
     *
     * @property wireValue Stable lowercase string for structured log entries.
     */
    enum class ContinuityPolicy(val wireValue: String) {

        /**
         * Session identity is unchanged; [AttachedRuntimeSession.isReuseValid] remains `true`.
         *
         * The [AttachedRuntimeSession] state is unaffected by this event.  In-flight and
         * future tasks may continue using the same session without interruption.
         */
        PERSIST("persist"),

        /**
         * A new [AttachedRuntimeSession] is created with a fresh [AttachedRuntimeSession.sessionId].
         *
         * The prior session is closed; a new session is opened.  The durable session era
         * persists across the reshape — [DurableSessionContinuityRecord.durableSessionId]
         * is unchanged; only [DurableSessionContinuityRecord.sessionContinuityEpoch] increments.
         * This is the normal reconnect policy.
         */
        RESHAPES("reshapes"),

        /**
         * The session is fully terminated.
         *
         * [AttachedRuntimeSession] transitions to [AttachedRuntimeSession.State.DETACHED].
         * The [DurableSessionContinuityRecord] era ends (cleared to `null`).  A new
         * activation era must be started for future cross-device participation.
         */
        TERMINATES("terminates"),

        /**
         * The session is detached but the durable era persists.
         *
         * [AttachedRuntimeSession] transitions to [AttachedRuntimeSession.State.DETACHED]
         * with [AttachedRuntimeSession.DetachCause.DISCONNECT].
         * [DurableSessionContinuityRecord] is retained so that a subsequent
         * [TransportEvent.RECONNECT] can resume the durable era with an incremented epoch.
         */
        SUSPENDS("suspends");

        companion object {
            /**
             * Returns the [ContinuityPolicy] matching [value], or `null` for unknown values.
             */
            fun fromValue(value: String?): ContinuityPolicy? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Continuity ownership ──────────────────────────────────────────────────

    /**
     * The layer that owns the continuity decision for a given [TransportEvent].
     *
     * All continuity decisions are **applied** by [RuntimeController]; this enum
     * records which layer is authorised to **initiate** the transition.
     *
     * @property wireValue Stable lowercase string for structured log entries.
     */
    enum class ContinuityOwnership(val wireValue: String) {

        /**
         * [RuntimeController] itself applies the continuity policy as part of its
         * internal lifecycle methods.  No external component initiates this.
         */
        RUNTIME_CONTROLLER("runtime_controller"),

        /**
         * The transport/network layer (e.g. [GalaxyWebSocketClient] WS listener callback)
         * detects the event and notifies [RuntimeController] which then applies the policy.
         */
        TRANSPORT_LAYER("transport_layer"),

        /**
         * The user or operator initiates the event (e.g. toggle cross-device off,
         * explicit detach command from host).  Delivered to [RuntimeController] via
         * its public API ([RuntimeController.stop], [RuntimeController.invalidateSession]).
         */
        OPERATOR_OR_USER("operator_or_user")
    }

    // ── Post-event session state ──────────────────────────────────────────────

    /**
     * The expected [AttachedRuntimeSession.State] after a [TransportEvent] is applied.
     *
     * @property wireValue Stable lowercase string for structured log entries.
     */
    enum class PostEventSessionState(val wireValue: String) {

        /**
         * [AttachedRuntimeSession.State.ATTACHED] — session is live and reuse is valid.
         */
        ATTACHED("attached"),

        /**
         * [AttachedRuntimeSession.State.DETACHED] — session has terminated.
         */
        DETACHED("detached"),

        /**
         * A new [AttachedRuntimeSession] in [AttachedRuntimeSession.State.ATTACHED] has
         * replaced the prior session (reconnect scenario).
         */
        REATTACHED("reattached")
    }

    // ── Transport anchor entry ────────────────────────────────────────────────

    /**
     * A single entry in the [anchorEntries] registry, declaring the continuity
     * policy and ownership for one [TransportEvent].
     *
     * @param event              The transport lifecycle event this entry governs.
     * @param policy             The [ContinuityPolicy] applied when [event] occurs.
     * @param ownership          The layer that owns / initiates the continuity decision.
     * @param postEventState     Expected [PostEventSessionState] after [event] is applied.
     * @param detachCause        The [AttachedRuntimeSession.DetachCause] associated with
     *                           this event, or `null` if the session is not detached.
     * @param durableEraRetained `true` if [DurableSessionContinuityRecord] survives this event.
     * @param recoverySequence   Human-readable description of the canonical recovery sequence.
     * @param note               One-sentence governance rationale for this entry.
     */
    data class TransportAnchorEntry(
        val event: TransportEvent,
        val policy: ContinuityPolicy,
        val ownership: ContinuityOwnership,
        val postEventState: PostEventSessionState,
        val detachCause: AttachedRuntimeSession.DetachCause?,
        val durableEraRetained: Boolean,
        val recoverySequence: String,
        val note: String
    )

    // ── Anchor entry registry ─────────────────────────────────────────────────

    /**
     * Canonical registry of transport continuity anchor entries.
     *
     * One entry per [TransportEvent] value.  All session continuity behavior in
     * response to transport lifecycle events must be consistent with the entries
     * declared here.
     *
     * Use [anchorFor] to look up the entry for a specific event.
     */
    val anchorEntries: List<TransportAnchorEntry> = listOf(

        TransportAnchorEntry(
            event             = TransportEvent.ATTACH,
            policy            = ContinuityPolicy.PERSIST,
            ownership         = ContinuityOwnership.RUNTIME_CONTROLLER,
            postEventState    = PostEventSessionState.ATTACHED,
            detachCause       = null,
            durableEraRetained = true,
            recoverySequence  = "No recovery needed; this is the normal activation transition.",
            note = "ATTACH: new session created in ATTACHED state; durable era initialised at epoch=0 " +
                "(USER_ACTIVATION / BACKGROUND_RESTORE)."
        ),

        TransportAnchorEntry(
            event             = TransportEvent.DETACH,
            policy            = ContinuityPolicy.TERMINATES,
            ownership         = ContinuityOwnership.OPERATOR_OR_USER,
            postEventState    = PostEventSessionState.DETACHED,
            detachCause       = AttachedRuntimeSession.DetachCause.DISABLE,
            durableEraRetained = false,
            recoverySequence  = "User or operator must explicitly re-enable cross-device via " +
                "RuntimeController.start() to start a new activation era.",
            note = "DETACH: session terminated with DISABLE or EXPLICIT_DETACH cause; durable era " +
                "cleared; runtime transitions to LocalOnly."
        ),

        TransportAnchorEntry(
            event             = TransportEvent.RECONNECT,
            policy            = ContinuityPolicy.RESHAPES,
            ownership         = ContinuityOwnership.TRANSPORT_LAYER,
            postEventState    = PostEventSessionState.REATTACHED,
            detachCause       = null,
            durableEraRetained = true,
            recoverySequence  = "GalaxyWebSocketClient reconnects; RuntimeController permanent WS " +
                "listener calls openAttachedSession(RECONNECT_RECOVERY); DurableSessionContinuityRecord " +
                "epoch increments; ReconnectRecoveryState transitions to RECOVERED.",
            note = "RECONNECT: prior session closed; new session opened under the same durable era " +
                "(epoch++); runtime_session_id refreshed; attached_session_id replaced."
        ),

        TransportAnchorEntry(
            event             = TransportEvent.DEGRADATION,
            policy            = ContinuityPolicy.SUSPENDS,
            ownership         = ContinuityOwnership.TRANSPORT_LAYER,
            postEventState    = PostEventSessionState.ATTACHED,
            detachCause       = AttachedRuntimeSession.DetachCause.DISCONNECT,
            durableEraRetained = true,
            recoverySequence  = "Transport layer monitors quality; on stabilisation MediaTransportLifecycleBridge " +
                "transitions to STABLE and RuntimeController re-enables task acceptance. " +
                "If transport is fully interrupted a RECONNECT event follows.",
            note = "DEGRADATION: session may remain ATTACHED (quality-degraded) or transition to " +
                "DETACHED (fully interrupted); new task acceptance suspended per SUSPEND_NEW_TASKS; " +
                "durable era retained pending recovery."
        ),

        TransportAnchorEntry(
            event             = TransportEvent.RECOVERY,
            policy            = ContinuityPolicy.PERSIST,
            ownership         = ContinuityOwnership.TRANSPORT_LAYER,
            postEventState    = PostEventSessionState.ATTACHED,
            detachCause       = null,
            durableEraRetained = true,
            recoverySequence  = "Transport layer signals STABLE condition; RuntimeController " +
                "re-enables task acceptance; ReconnectRecoveryState transitions to RECOVERED if " +
                "a prior RECOVERING phase was active.",
            note = "RECOVERY: transport quality restored; session reuse re-enabled; no new session " +
                "identity required unless a prior RECONNECT reshaped the session."
        )
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    private val eventIndex: Map<TransportEvent, TransportAnchorEntry> =
        anchorEntries.associateBy { it.event }

    /**
     * Returns the [TransportAnchorEntry] for [event], or `null` if the event is
     * not registered (defensive; all five events are registered).
     */
    fun anchorFor(event: TransportEvent): TransportAnchorEntry? =
        eventIndex[event]

    /**
     * Returns all entries with the given [policy].
     */
    fun entriesForPolicy(policy: ContinuityPolicy): List<TransportAnchorEntry> =
        anchorEntries.filter { it.policy == policy }

    /**
     * Returns all entries owned by [ownership].
     */
    fun entriesForOwnership(ownership: ContinuityOwnership): List<TransportAnchorEntry> =
        anchorEntries.filter { it.ownership == ownership }

    /**
     * Returns the set of [TransportEvent] values for which the durable session era
     * is retained ([TransportAnchorEntry.durableEraRetained]`=true`).
     *
     * Consumers can use this set to verify that a [DurableSessionContinuityRecord]
     * is still valid after a transport event without re-checking the full entry.
     */
    val eventsDurableEraRetained: Set<TransportEvent> =
        anchorEntries
            .filter { it.durableEraRetained }
            .map { it.event }
            .toSet()

    /**
     * Returns the set of [TransportEvent] values that terminate the durable session era
     * ([TransportAnchorEntry.durableEraRetained]`=false`).
     */
    val eventsDurableEraTerminated: Set<TransportEvent> =
        anchorEntries
            .filter { !it.durableEraRetained }
            .map { it.event }
            .toSet()

    // ── System-level constants ────────────────────────────────────────────────

    /**
     * The PR number that introduced this anchor.
     */
    const val INTRODUCED_PR: Int = 40

    /**
     * One-sentence description of this anchor's purpose.
     */
    const val DESCRIPTION: String =
        "Canonical transport continuity anchor governing session continuity policy across " +
            "Android-side transport lifecycle events: ATTACH, DETACH, RECONNECT, DEGRADATION, " +
            "and RECOVERY (PR-40 — Android Media Transport and Task-Lifecycle Convergence)."

    /**
     * Invariant: the durable session era must be retained across all reconnect events.
     *
     * A [TransportEvent.RECONNECT] must never clear the [DurableSessionContinuityRecord];
     * it must only increment [DurableSessionContinuityRecord.sessionContinuityEpoch].
     * Violation of this invariant would break the center-side durable session correlation
     * that relies on the stable [DurableSessionContinuityRecord.durableSessionId] across
     * reconnect cycles.
     */
    const val DURABLE_ERA_RECONNECT_INVARIANT: String =
        "DurableSessionContinuityRecord must be retained (epoch incremented) on " +
            "TransportEvent.RECONNECT; it must not be cleared or replaced."

    /**
     * Invariant: only [RuntimeController] may apply continuity policy transitions.
     *
     * No transport-layer component, surface layer, or external caller may independently
     * modify [AttachedRuntimeSession] state or clear [DurableSessionContinuityRecord] in
     * response to a transport event.  All continuity transitions must be applied by
     * [RuntimeController] through its declared lifecycle methods.
     */
    const val CONTINUITY_AUTHORITY_INVARIANT: String =
        "RuntimeController is the sole authority for applying continuity policy transitions; " +
            "transport-layer components must notify RuntimeController and must not modify " +
            "session or durable-era state independently."
}
