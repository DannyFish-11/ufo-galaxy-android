package com.ufo.galaxy.runtime

/**
 * PR-5Android — Explicit participant attachment transition semantics for Android.
 *
 * Models the full attachment lifecycle of the Android participant runtime —
 * from unattached through attaching, attached, detaching, and back to unattached —
 * and declares the V2 event expectations and recovery semantics for each transition.
 *
 * ## Motivation
 *
 * [AttachedRuntimeSession] (PR-1) represents the live session object, but the
 * authoritative declaration of **which V2 events are emitted at each attachment transition**
 * and **what V2 must expect to receive when Android re-attaches after a lifecycle disruption**
 * was not explicitly captured.
 *
 * [ParticipantAttachmentTransitionSemantics] fills this gap by:
 * 1. Defining the [AttachmentState] lifecycle with explicit wire values.
 * 2. Declaring the [AttachmentTransition] — the named transitions between [AttachmentState]s —
 *    and the [V2MultiDeviceLifecycleEvent] emitted at each.
 * 3. Declaring the [AttachmentRecoverySemantics] — what V2 should expect when a specific
 *    transition occurs in the context of lifecycle recovery (reconnect, process kill, etc.).
 *
 * ## V2 canonical orchestration boundary
 *
 * Android MUST NOT self-authorize attachment state transitions.  The attachment lifecycle
 * is driven by [RuntimeController], which responds to:
 * - WS connection events (open → attach; close → detach)
 * - Explicit user actions (RUNTIME_STOPPED → detach)
 * - Lifecycle events (process recreation → detach/new era)
 *
 * V2 receives attachment state changes through [V2MultiDeviceLifecycleEvent] emissions.
 * V2 is the canonical authority for session continuation decisions after attachment
 * disruptions — Android only reports its attachment state and waits for V2 decisions.
 *
 * @see AttachedRuntimeSession
 * @see V2MultiDeviceLifecycleEvent
 * @see DurableSessionContinuityRecord
 * @see AppLifecycleParticipantBoundary
 * @see ParticipantPostureLifecycleBoundary
 */
object ParticipantAttachmentTransitionSemantics {

    // ── Attachment state ──────────────────────────────────────────────────────

    /**
     * The current state of the Android participant's attachment to the V2 runtime.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads and V2 events.
     */
    enum class AttachmentState(val wireValue: String) {

        /**
         * The Android participant is not attached to any runtime session.
         * This is the initial state and the state after an explicit stop or session end.
         *
         * In this state: WS may be connected or disconnected; no session is active.
         */
        UNATTACHED("unattached"),

        /**
         * The Android participant is in the process of opening an [AttachedRuntimeSession].
         * This state is transient — it resolves to [ATTACHED] on success or [UNATTACHED] on failure.
         *
         * WS is connected; session open protocol is in progress.
         */
        ATTACHING("attaching"),

        /**
         * The Android participant has a live [AttachedRuntimeSession].
         * The participant is eligible for task dispatch, capability reporting, and distributed
         * execution in this state.
         *
         * WS is connected; session is active; [DurableSessionContinuityRecord] is live.
         */
        ATTACHED("attached"),

        /**
         * The Android participant is in the process of closing or losing its [AttachedRuntimeSession].
         * This state is transient — it resolves to [UNATTACHED].
         *
         * Occurs on: WS disconnect, explicit stop, lifecycle disruption, or V2-side session close.
         */
        DETACHING("detaching"),

        /**
         * The Android participant is reconnecting to re-establish attachment after a prior disconnect.
         * This state is distinct from [ATTACHING]: it implies that a prior attachment existed
         * and the same [DurableSessionContinuityRecord.durableSessionId] may be reused.
         *
         * Resolves to [ATTACHED] on success (epoch incremented, DeviceReconnected emitted)
         * or [UNATTACHED] on failure (all retries exhausted, DeviceDisconnected emitted).
         */
        REATTACHING("reattaching")
    }

    // ── Attachment transition ─────────────────────────────────────────────────

    /**
     * A named transition between [AttachmentState]s, with the associated
     * [V2MultiDeviceLifecycleEvent] emitted by Android and the recovery semantics.
     *
     * @param transitionId          Stable kebab-case identifier.
     * @param fromState             The [AttachmentState] before this transition.
     * @param toState               The [AttachmentState] after this transition.
     * @param v2EventEmitted        The [V2MultiDeviceLifecycleEvent] Android emits when this transition occurs.
     *                              `null` if no V2 event is emitted (e.g. transient internal transitions).
     * @param durableSessionEffect  Whether the [DurableSessionContinuityRecord] is preserved, advanced,
     *                              or reset by this transition.
     * @param recoverySemantics     The [AttachmentRecoverySemantics] for this transition.
     * @param rationale             Explanation of the transition and its V2 visibility.
     */
    data class AttachmentTransitionEntry(
        val transitionId: String,
        val fromState: AttachmentState,
        val toState: AttachmentState,
        val v2EventEmitted: String?,
        val durableSessionEffect: DurableSessionEffect,
        val recoverySemantics: AttachmentRecoverySemantics,
        val rationale: String
    )

    // ── Durable session effect ────────────────────────────────────────────────

    /**
     * Effect on the [DurableSessionContinuityRecord] when an attachment transition occurs.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class DurableSessionEffect(val wireValue: String) {

        /**
         * The [DurableSessionContinuityRecord.durableSessionId] is unchanged.
         * Session era continuity is preserved.
         */
        SESSION_PRESERVED("session_preserved"),

        /**
         * The [DurableSessionContinuityRecord.sessionContinuityEpoch] is incremented.
         * The [DurableSessionContinuityRecord.durableSessionId] is unchanged.
         * Session era is preserved; reconnect epoch is advanced.
         */
        EPOCH_ADVANCED("epoch_advanced"),

        /**
         * The [DurableSessionContinuityRecord] is reset.
         * A new [DurableSessionContinuityRecord.durableSessionId] will be generated on
         * the next activation.  The prior session era is terminated.
         */
        SESSION_RESET("session_reset")
    }

    // ── Attachment recovery semantics ─────────────────────────────────────────

    /**
     * Recovery semantics for an attachment transition — what V2 and Android must do.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class AttachmentRecoverySemantics(val wireValue: String) {

        /**
         * Normal attachment establishment.  No prior state to reconcile.
         * V2 receives the DeviceConnected event and adds the participant to its registry.
         */
        FRESH_ATTACH("fresh_attach"),

        /**
         * Attachment is being re-established after a transient disconnect within the same
         * activation era.  V2 receives DeviceReconnected and may correlate the returning
         * participant to its prior session using [DurableSessionContinuityRecord.durableSessionId].
         */
        RECONNECT_RECOVERY("reconnect_recovery"),

        /**
         * Attachment is being established for the first time in a new activation era
         * (after process kill or explicit stop).  V2 receives DeviceConnected (not Reconnected)
         * and must treat this as a new participant registration, not a resume.
         */
        NEW_ERA_ATTACH("new_era_attach"),

        /**
         * Normal session detachment.  The participant is cleanly leaving the runtime.
         * V2 receives DeviceDisconnected and may clean up the participant's session state.
         */
        CLEAN_DETACH("clean_detach"),

        /**
         * Session detachment due to a disruption (WS drop, process kill).
         * V2 must apply its participant-loss policy if the participant does not reconnect
         * within the V2-side reconnect timeout.
         */
        DISRUPTED_DETACH("disrupted_detach")
    }

    // ── Attachment transition registry ────────────────────────────────────────

    /**
     * Complete registry of Android participant attachment transitions.
     *
     * Covers the full attachment lifecycle: initial attach, normal detach, reconnect
     * recovery, process-kill re-attach, and disrupted detach.
     */
    val transitions: List<AttachmentTransitionEntry> = listOf(

        AttachmentTransitionEntry(
            transitionId = "initial_attach",
            fromState = AttachmentState.UNATTACHED,
            toState = AttachmentState.ATTACHED,
            v2EventEmitted = "DeviceConnected",
            durableSessionEffect = DurableSessionEffect.SESSION_PRESERVED,
            recoverySemantics = AttachmentRecoverySemantics.FRESH_ATTACH,
            rationale = "First-time attachment in a new activation era. Android opens an AttachedRuntimeSession " +
                "under a freshly generated durableSessionId. V2 receives DeviceConnected and registers the " +
                "participant. No prior session state to reconcile."
        ),

        AttachmentTransitionEntry(
            transitionId = "clean_detach",
            fromState = AttachmentState.ATTACHED,
            toState = AttachmentState.UNATTACHED,
            v2EventEmitted = "DeviceDisconnected",
            durableSessionEffect = DurableSessionEffect.SESSION_RESET,
            recoverySemantics = AttachmentRecoverySemantics.CLEAN_DETACH,
            rationale = "Explicit session termination via RuntimeController.stop() or user disabling " +
                "cross-device. The WS is closed cleanly; the session era ends. V2 receives DeviceDisconnected " +
                "and can clean up the participant's registry entry."
        ),

        AttachmentTransitionEntry(
            transitionId = "disrupted_detach",
            fromState = AttachmentState.ATTACHED,
            toState = AttachmentState.REATTACHING,
            v2EventEmitted = null,
            durableSessionEffect = DurableSessionEffect.SESSION_PRESERVED,
            recoverySemantics = AttachmentRecoverySemantics.DISRUPTED_DETACH,
            rationale = "WS drop while Active. Android transitions to REATTACHING rather than UNATTACHED " +
                "because automatic reconnect is in progress. The durableSessionId is preserved for the " +
                "potential reconnect recovery. V2 does not immediately receive a DeviceDisconnected event — " +
                "it must wait for the reconnect result. If reconnect succeeds, V2 receives DeviceReconnected; " +
                "if it fails, V2 receives DeviceDisconnected."
        ),

        AttachmentTransitionEntry(
            transitionId = "reconnect_recovery_attach",
            fromState = AttachmentState.REATTACHING,
            toState = AttachmentState.ATTACHED,
            v2EventEmitted = "DeviceReconnected",
            durableSessionEffect = DurableSessionEffect.EPOCH_ADVANCED,
            recoverySemantics = AttachmentRecoverySemantics.RECONNECT_RECOVERY,
            rationale = "Successful WS reconnect after a transient disconnect. Android opens a new " +
                "AttachedRuntimeSession using the preserved runtime_attachment_session_id. " +
                "DurableSessionContinuityRecord.sessionContinuityEpoch is incremented. " +
                "V2 receives DeviceReconnected with the updated epoch and can correlate the " +
                "returning participant to its prior session."
        ),

        AttachmentTransitionEntry(
            transitionId = "reconnect_failure_detach",
            fromState = AttachmentState.REATTACHING,
            toState = AttachmentState.UNATTACHED,
            v2EventEmitted = "DeviceDisconnected",
            durableSessionEffect = DurableSessionEffect.SESSION_RESET,
            recoverySemantics = AttachmentRecoverySemantics.DISRUPTED_DETACH,
            rationale = "All WS reconnect attempts exhausted. Android transitions to UNATTACHED; " +
                "the session era ends. V2 receives DeviceDisconnected and must apply its participant-loss " +
                "policy. User action is required to restore connectivity."
        ),

        AttachmentTransitionEntry(
            transitionId = "new_era_attach",
            fromState = AttachmentState.UNATTACHED,
            toState = AttachmentState.ATTACHED,
            v2EventEmitted = "DeviceConnected",
            durableSessionEffect = DurableSessionEffect.SESSION_PRESERVED,
            recoverySemantics = AttachmentRecoverySemantics.NEW_ERA_ATTACH,
            rationale = "First attachment after a process kill, explicit restart, or app re-enable. " +
                "A new durableSessionId is generated. V2 receives DeviceConnected (NOT DeviceReconnected) " +
                "because the attachment identity is fresh. V2 must NOT attempt to resume any prior " +
                "session state for this participant — this is a new registration, not a resume."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [AttachmentTransitionEntry] for the given [transitionId], or `null` if not found.
     */
    fun transitionFor(transitionId: String): AttachmentTransitionEntry? =
        transitions.firstOrNull { it.transitionId == transitionId }

    /**
     * Returns all transitions that emit the given [v2EventType] to V2.
     *
     * @param v2EventType The event type string to search for (e.g. "DeviceConnected").
     */
    fun transitionsEmitting(v2EventType: String): List<AttachmentTransitionEntry> =
        transitions.filter { it.v2EventEmitted == v2EventType }

    /**
     * Returns all transitions that advance the [DurableSessionContinuityRecord.sessionContinuityEpoch].
     */
    val epochAdvancingTransitions: List<AttachmentTransitionEntry> =
        transitions.filter { it.durableSessionEffect == DurableSessionEffect.EPOCH_ADVANCED }

    /**
     * Returns all transitions that reset the [DurableSessionContinuityRecord] (session era ends).
     */
    val sessionResettingTransitions: List<AttachmentTransitionEntry> =
        transitions.filter { it.durableSessionEffect == DurableSessionEffect.SESSION_RESET }

    /**
     * Returns all transitions that correspond to lifecycle recovery
     * ([AttachmentRecoverySemantics.RECONNECT_RECOVERY] or [AttachmentRecoverySemantics.NEW_ERA_ATTACH]).
     */
    val recoveryTransitions: List<AttachmentTransitionEntry> =
        transitions.filter {
            it.recoverySemantics == AttachmentRecoverySemantics.RECONNECT_RECOVERY ||
                it.recoverySemantics == AttachmentRecoverySemantics.NEW_ERA_ATTACH
        }

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key for the attachment state value in diagnostics payloads. */
    const val KEY_ATTACHMENT_STATE = "participant_attachment_state"

    /** Wire key for the attachment transition ID in diagnostics payloads. */
    const val KEY_TRANSITION_ID = "participant_attachment_transition_id"

    /** Wire key for the durable session effect in diagnostics payloads. */
    const val KEY_DURABLE_SESSION_EFFECT = "durable_session_effect"

    /** Wire key for the attachment recovery semantics in diagnostics payloads. */
    const val KEY_RECOVERY_SEMANTICS = "participant_attachment_recovery_semantics"

    /**
     * Total number of attachment transition entries in this registry.
     */
    val transitionCount: Int get() = transitions.size

    /**
     * PR number that introduced this attachment transition semantics declaration.
     */
    const val INTRODUCED_PR: String = "PR5Android"
}
