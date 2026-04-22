package com.ufo.galaxy.runtime

/**
 * PR-5Android — Posture-aware participant lifecycle boundary for the Android runtime.
 *
 * Extends [AppLifecycleParticipantBoundary] with explicit declarations of how each
 * Android app lifecycle event behaves **differently depending on the current
 * [SourceRuntimePosture]** of the participant.
 *
 * ## Motivation
 *
 * [AppLifecycleParticipantBoundary] (PR-53) declares lifecycle → participant-state effects
 * independent of runtime posture.  However, when Android is operating in
 * [SourceRuntimePosture.JOIN_RUNTIME] posture (joined as a first-class participant in a
 * distributed or hybrid runtime), several lifecycle events carry **stronger consequences**
 * than when Android is in [SourceRuntimePosture.CONTROL_ONLY] posture:
 *
 * - A [AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL] while in
 *   [SourceRuntimePosture.JOIN_RUNTIME] posture means an active distributed execution
 *   participant has vanished — V2 must apply participant-loss policy, not just stale-session
 *   policy.
 * - A WS disconnect while in [SourceRuntimePosture.JOIN_RUNTIME] posture may require V2 to
 *   pause a multi-participant execution until Android reconnects, vs simply noting the
 *   participant is temporarily unreachable.
 * - A [AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE] event while in
 *   [SourceRuntimePosture.JOIN_RUNTIME] posture should trigger an urgent capability-degradation
 *   signal to V2 so the coordinator can reroute in-progress distributed tasks.
 *
 * [ParticipantPostureLifecycleBoundary] fills this gap by:
 * 1. Classifying each lifecycle event with a [PostureLifecycleImpact] that is either
 *    [PostureLifecycleImpact.POSTURE_NEUTRAL] (same behavior regardless of posture) or
 *    [PostureLifecycleImpact.POSTURE_AMPLIFIED] (stronger V2 action needed in JOIN_RUNTIME).
 * 2. Declaring the [JoinRuntimeRecoveryExpectation] — the specific V2 re-synchronization
 *    expectation when Android re-establishes participation after a posture-amplified event.
 * 3. Documenting the [V2ParticipantLossPolicy] that V2 must apply when an Android participant
 *    is lost while in [SourceRuntimePosture.JOIN_RUNTIME] posture.
 *
 * ## Posture authority model
 *
 * Android reports its current posture to V2 via [ReconciliationSignal.PARTICIPANT_STATE] and
 * [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged].  Android MUST NOT autonomously
 * change its posture after a lifecycle disruption.  Posture re-assignment after reconnect
 * belongs to V2 (via the coordinator-driven re-attachment flow).
 *
 * ## Constraint: participant, not orchestrator
 *
 * This class does NOT give Android orchestration authority.  It only makes Android's
 * posture-aware lifecycle behavior explicit so V2 can apply the correct policy.
 *
 * @see AppLifecycleParticipantBoundary
 * @see SourceRuntimePosture
 * @see HybridRuntimeContinuityContract
 * @see ParticipantAttachmentTransitionSemantics
 */
object ParticipantPostureLifecycleBoundary {

    // ── Posture lifecycle impact ───────────────────────────────────────────────

    /**
     * Indicates whether a lifecycle event's impact on participant state is the same
     * regardless of the current [SourceRuntimePosture], or whether the impact is amplified
     * when Android is in [SourceRuntimePosture.JOIN_RUNTIME] posture.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class PostureLifecycleImpact(val wireValue: String) {

        /**
         * The lifecycle event has the same participant-state impact regardless of whether
         * Android is in [SourceRuntimePosture.CONTROL_ONLY] or [SourceRuntimePosture.JOIN_RUNTIME]
         * posture.
         *
         * V2 needs no extra action beyond the standard [AppLifecycleParticipantBoundary] policy.
         */
        POSTURE_NEUTRAL("posture_neutral"),

        /**
         * The lifecycle event has amplified consequences when Android is in
         * [SourceRuntimePosture.JOIN_RUNTIME] posture.
         *
         * V2 must apply the [JoinRuntimeRecoveryExpectation] policy declared for this event,
         * which typically involves more urgent participant-loss or capability-degradation handling
         * than the standard lifecycle policy.
         */
        POSTURE_AMPLIFIED("posture_amplified")
    }

    // ── JOIN_RUNTIME recovery expectation ────────────────────────────────────

    /**
     * V2's expected re-synchronization expectation when Android re-establishes participation
     * after a lifecycle disruption while it was in [SourceRuntimePosture.JOIN_RUNTIME] posture.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class JoinRuntimeRecoveryExpectation(val wireValue: String) {

        /**
         * Android may resume its JOIN_RUNTIME participation in the same distributed execution
         * context after reconnect.
         *
         * V2 SHOULD wait for Android to reconnect before declaring the participant lost, subject
         * to the V2-side participant-loss timeout.  If the reconnect succeeds before the timeout,
         * V2 may reassign the participant to the same or an equivalent distributed task.
         */
        RESUME_ELIGIBLE("resume_eligible"),

        /**
         * Android cannot resume its JOIN_RUNTIME participation in the same distributed execution
         * context.  The prior posture era is terminated.
         *
         * V2 MUST declare the participant lost and apply the V2-side participant-loss policy
         * (e.g. redirect distributed subtasks, re-evaluate formation, or abort the task).
         * If Android reconnects, it will enter a NEW participation era — it does NOT resume.
         */
        NEW_ERA_REQUIRED("new_era_required"),

        /**
         * Whether Android can resume or must start a new era depends on whether the WS
         * reconnect completes before V2's participant-loss timeout.
         *
         * V2 SHOULD start a reconnect-wait timer.  If Android reconnects within the timeout,
         * V2 MAY attempt to resume the participant's distributed task.  If the timeout expires,
         * V2 MUST apply the participant-loss policy.
         */
        TIMEOUT_DEPENDENT("timeout_dependent")
    }

    // ── V2 participant loss policy ────────────────────────────────────────────

    /**
     * V2's expected policy when an Android participant is **permanently lost** from a
     * distributed execution context while in [SourceRuntimePosture.JOIN_RUNTIME] posture.
     *
     * These are the expected V2-side actions.  Android's only role is to report the
     * lifecycle event accurately; V2 drives all loss-recovery decisions.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class V2ParticipantLossPolicy(val wireValue: String) {

        /**
         * V2 should redirect any in-progress distributed subtasks assigned to the lost Android
         * participant to another eligible participant runtime (or re-queue on the canonical host).
         */
        REDIRECT_SUBTASKS("redirect_subtasks"),

        /**
         * V2 should abort the current distributed execution and apply its task-level retry or
         * fallback policy.
         */
        ABORT_AND_RETRY("abort_and_retry"),

        /**
         * V2 should re-evaluate the current formation and rebalance participant roles to
         * compensate for the lost Android participant.
         */
        REBALANCE_FORMATION("rebalance_formation"),

        /**
         * V2 should wait for the lost participant to reconnect before deciding.
         * Used when reconnect is expected (e.g. transient network drop) and the task is
         * tolerant of a short wait.
         */
        WAIT_FOR_RECONNECT("wait_for_reconnect")
    }

    // ── Boundary entry ────────────────────────────────────────────────────────

    /**
     * Posture-aware lifecycle boundary entry for a single [AppLifecycleParticipantBoundary.AppLifecycleEvent].
     *
     * @param event                     The [AppLifecycleParticipantBoundary.AppLifecycleEvent] described by this entry.
     * @param postureLifecycleImpact    Whether this event is [PostureLifecycleImpact.POSTURE_NEUTRAL] or
     *                                  [PostureLifecycleImpact.POSTURE_AMPLIFIED] in JOIN_RUNTIME posture.
     * @param joinRuntimeRecoveryExpectation
     *                                  The [JoinRuntimeRecoveryExpectation] when this event occurs while
     *                                  Android is in JOIN_RUNTIME posture.
     * @param v2ParticipantLossPolicy   The [V2ParticipantLossPolicy] V2 should apply when Android in
     *                                  JOIN_RUNTIME posture is lost due to this lifecycle event.
     * @param joinRuntimeRationale      Explanation of the posture-specific impact and V2 policy expectation.
     */
    data class PostureLifecycleBoundaryEntry(
        val event: AppLifecycleParticipantBoundary.AppLifecycleEvent,
        val postureLifecycleImpact: PostureLifecycleImpact,
        val joinRuntimeRecoveryExpectation: JoinRuntimeRecoveryExpectation,
        val v2ParticipantLossPolicy: V2ParticipantLossPolicy,
        val joinRuntimeRationale: String
    )

    // ── Posture lifecycle boundary registry ───────────────────────────────────

    /**
     * Complete posture-aware lifecycle boundary registry.
     *
     * All six [AppLifecycleParticipantBoundary.AppLifecycleEvent] values are covered exactly once.
     */
    val entries: List<PostureLifecycleBoundaryEntry> = listOf(

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_NEUTRAL,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.RESUME_ELIGIBLE,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.WAIT_FOR_RECONNECT,
            joinRuntimeRationale = "App foregrounding does not affect WS or session state in either posture. " +
                "In JOIN_RUNTIME posture, the participant is still connected and fully eligible. " +
                "No V2 loss policy needed; posture impact is neutral."
        ),

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_NEUTRAL,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.RESUME_ELIGIBLE,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.WAIT_FOR_RECONNECT,
            joinRuntimeRationale = "Moving to background with process alive does not tear down the WS or session " +
                "in either posture. In JOIN_RUNTIME posture, the participant remains eligible for distributed " +
                "subtask execution via the foreground service. No posture-specific action is needed."
        ),

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_AMPLIFIED,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.REDIRECT_SUBTASKS,
            joinRuntimeRationale = "POSTURE_AMPLIFIED: While in JOIN_RUNTIME posture, a background kill means " +
                "an active distributed execution participant has vanished without completing its subtasks. " +
                "V2 MUST NOT wait for reconnect — it MUST redirect any in-progress subtasks assigned to " +
                "this Android participant to another eligible participant immediately. " +
                "When Android reconnects, it starts a NEW participation era (new durableSessionId); " +
                "V2 must NOT attempt to resume the prior distributed execution on this participant."
        ),

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_NEUTRAL,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.RESUME_ELIGIBLE,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.WAIT_FOR_RECONNECT,
            joinRuntimeRationale = "Configuration changes (rotation, locale) do not affect RuntimeController " +
                "in either posture, as it is scoped to a Service/Application context. In JOIN_RUNTIME posture, " +
                "the participant remains fully connected and eligible. Posture impact is neutral."
        ),

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_AMPLIFIED,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.TIMEOUT_DEPENDENT,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.REBALANCE_FORMATION,
            joinRuntimeRationale = "POSTURE_AMPLIFIED: In JOIN_RUNTIME posture, memory pressure requires " +
                "urgent capability-degradation signaling to V2 so the coordinator can reroute in-progress " +
                "distributed subtasks before the process is killed. " +
                "Android MUST downgrade health to ParticipantHealthState.DEGRADED and emit " +
                "ReconciliationSignal.PARTICIPANT_STATE immediately on critical memory pressure events. " +
                "V2 SHOULD start rebalancing the formation to reduce the load on this participant. " +
                "Recovery is timeout-dependent: if the process survives, V2 may restore the participant's " +
                "distributed role; if killed, V2 must apply the participant-loss policy."
        ),

        PostureLifecycleBoundaryEntry(
            event = AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION,
            postureLifecycleImpact = PostureLifecycleImpact.POSTURE_AMPLIFIED,
            joinRuntimeRecoveryExpectation = JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED,
            v2ParticipantLossPolicy = V2ParticipantLossPolicy.ABORT_AND_RETRY,
            joinRuntimeRationale = "POSTURE_AMPLIFIED: Process recreation while in JOIN_RUNTIME posture " +
                "means all distributed execution state held by the Android participant is lost. " +
                "The participant was a first-class runtime node in the distributed execution; its loss " +
                "may require the current distributed task to be aborted and retried (with V2 applying " +
                "its own task-level fallback policy). " +
                "Android will reconnect with a NEW participation era (new durableSessionId, " +
                "DeviceConnected event). V2 must NOT restore the prior JOIN_RUNTIME posture assignment " +
                "automatically — posture re-assignment requires explicit re-authorization from V2."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [PostureLifecycleBoundaryEntry] for the given [event].
     * All six [AppLifecycleParticipantBoundary.AppLifecycleEvent] values are guaranteed to have an entry.
     */
    fun boundaryFor(event: AppLifecycleParticipantBoundary.AppLifecycleEvent): PostureLifecycleBoundaryEntry =
        entries.first { it.event == event }

    /**
     * Returns all lifecycle events that are [PostureLifecycleImpact.POSTURE_AMPLIFIED] —
     * i.e., events where V2 must apply stronger JOIN_RUNTIME-specific policy.
     */
    val postureAmplifiedEvents: List<AppLifecycleParticipantBoundary.AppLifecycleEvent> =
        entries.filter { it.postureLifecycleImpact == PostureLifecycleImpact.POSTURE_AMPLIFIED }
            .map { it.event }

    /**
     * Returns all lifecycle events that are [PostureLifecycleImpact.POSTURE_NEUTRAL] —
     * events where JOIN_RUNTIME posture has no additional consequence beyond the standard
     * [AppLifecycleParticipantBoundary] policy.
     */
    val postureNeutralEvents: List<AppLifecycleParticipantBoundary.AppLifecycleEvent> =
        entries.filter { it.postureLifecycleImpact == PostureLifecycleImpact.POSTURE_NEUTRAL }
            .map { it.event }

    /**
     * Returns all lifecycle events where V2 must start a new participation era after
     * the Android participant recovers — i.e., events with
     * [JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED].
     */
    val newEraRequiredEvents: List<AppLifecycleParticipantBoundary.AppLifecycleEvent> =
        entries.filter {
            it.joinRuntimeRecoveryExpectation == JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED
        }.map { it.event }

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key for the posture-lifecycle-impact value in diagnostics payloads. */
    const val KEY_POSTURE_LIFECYCLE_IMPACT = "posture_lifecycle_impact"

    /** Wire key for the JOIN_RUNTIME recovery expectation in diagnostics payloads. */
    const val KEY_JOIN_RUNTIME_RECOVERY_EXPECTATION = "join_runtime_recovery_expectation"

    /** Wire key for the V2 participant-loss policy in diagnostics payloads. */
    const val KEY_V2_PARTICIPANT_LOSS_POLICY = "v2_participant_loss_policy"

    /**
     * Expected number of posture-lifecycle boundary entries.
     * Must equal the number of [AppLifecycleParticipantBoundary.AppLifecycleEvent] values.
     */
    const val ENTRY_COUNT: Int = 6

    /**
     * PR number that introduced this posture-aware lifecycle boundary declaration.
     */
    const val INTRODUCED_PR: String = "PR-5Android"
}
