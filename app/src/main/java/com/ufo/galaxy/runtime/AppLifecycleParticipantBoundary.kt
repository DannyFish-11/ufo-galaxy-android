package com.ufo.galaxy.runtime

/**
 * PR-53 — Android app lifecycle → participant-side runtime state boundary.
 *
 * Explicitly declares how standard Android app lifecycle events (Activity/Service
 * foreground/background/kill, process recreation, memory pressure) affect participant
 * runtime state, and what each transition means for distributed execution participation.
 *
 * ## Design intent
 *
 * Before PR-53, the mapping from Android app lifecycle to participant state was
 * implicit: consumers had to infer the effects from examining multiple independent
 * surfaces ([RuntimeController.state], [AttachedRuntimeSession], [ReconnectRecoveryState])
 * without a single authoritative declaration of the mapping.
 *
 * [AppLifecycleParticipantBoundary] fills this gap by:
 * 1. Declaring all relevant Android app lifecycle events as a typed enum ([AppLifecycleEvent]).
 * 2. Mapping each event to the canonical participant state effect ([ParticipantStateEffect]).
 * 3. Specifying the session continuity behavior for each event ([SessionContinuityBehavior]).
 * 4. Declaring what Android can recover locally vs what requires V2 re-synchronization.
 *
 * ## Android as participant, not orchestrator
 *
 * Android lifecycle events do NOT re-order or override V2 session assignments.
 * After any lifecycle disruption, Android:
 * 1. Reports the new state via [V2MultiDeviceLifecycleEvent] or [ReconciliationSignal].
 * 2. Waits for V2 to re-synchronize session truth if needed.
 * 3. Does NOT autonomously resume execution after BACKGROUND_KILL or PROCESS_RECREATION.
 *
 * ## Lifecycle authority model
 *
 * | Lifecycle trigger                       | Android action                                                                |
 * |-----------------------------------------|-------------------------------------------------------------------------------|
 * | App foregrounded                        | `connectIfEnabled()` — restore WS if needed; no new session                   |
 * | App backgrounded (process alive)        | No action — WS and session stay active                                        |
 * | App background-killed by OS             | All in-memory state lost; new era starts on next `connectIfEnabled()`          |
 * | Activity config change (rotation, etc.) | No action — RuntimeController is service/app-scoped, not Activity-scoped      |
 * | Memory pressure (critical)              | Readiness degrades; V2 notified via PARTICIPANT_STATE; no autonomous execution |
 * | Process recreation after kill           | New activation era; V2 must reconcile new session via published truth snapshot |
 *
 * @see RuntimeController
 * @see ReconnectRecoveryState
 * @see DurableSessionContinuityRecord
 * @see ParticipantRecoveryReadinessSnapshot
 */
object AppLifecycleParticipantBoundary {

    // ── Android app lifecycle events ──────────────────────────────────────────

    /**
     * Android app lifecycle events with direct participant-runtime impact.
     *
     * @property wireValue Stable lowercase string used in diagnostics and structured log entries.
     */
    enum class AppLifecycleEvent(val wireValue: String) {
        /** App moved to foreground (Activity.onResume or Service started). */
        FOREGROUND("foreground"),

        /** App moved to background (Activity.onStop with process kept alive). */
        BACKGROUND("background"),

        /**
         * Process killed by Android OS (OOM, user kill, or system eviction).
         * All in-memory state is lost; session must be re-established from scratch.
         */
        BACKGROUND_KILL("background_kill"),

        /**
         * Activity destroyed and recreated due to configuration change
         * (screen rotation, locale, window resize, etc.).
         */
        CONFIGURATION_CHANGE("configuration_change"),

        /**
         * System-delivered memory pressure (onTrimMemory with TRIM_MEMORY_RUNNING_CRITICAL
         * or higher).  Execution should pause and readiness should be downgraded.
         */
        MEMORY_PRESSURE("memory_pressure"),

        /**
         * Service or Activity restarted after a prior process kill or explicit restart.
         * A new activation era begins; no prior session state is recoverable.
         */
        PROCESS_RECREATION("process_recreation");

        companion object {
            /** All wire values for this enum. Useful for stability assertions in tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /**
             * Returns the [AppLifecycleEvent] matching [wireValue], or `null` if not recognised.
             * Callers MUST tolerate null (future lifecycle events may be added).
             */
            fun fromValue(wireValue: String?): AppLifecycleEvent? =
                entries.firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── Participant state effects ──────────────────────────────────────────────

    /**
     * Effect on participant-side runtime state for a given app lifecycle event.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class ParticipantStateEffect(val wireValue: String) {
        /** No immediate change to participant/session state. */
        NO_CHANGE("no_change"),

        /** Session is preserved; V2 may be notified of reduced dispatch readiness. */
        READINESS_REDUCED("readiness_reduced"),

        /** Session must be detached; a reconnect/restore sequence will follow. */
        SESSION_DETACHED("session_detached"),

        /** Session is fully terminated; V2 resync is required before any new dispatch. */
        SESSION_TERMINATED("session_terminated"),

        /** In-flight execution must be paused; no new tasks accepted until resolved. */
        EXECUTION_PAUSED("execution_paused")
    }

    // ── Session continuity behavior ───────────────────────────────────────────

    /**
     * Session continuity behavior across an app lifecycle disruption.
     *
     * Specifies whether the [DurableSessionContinuityRecord] survives, and what
     * V2 coordination is needed to restore normal dispatch.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class SessionContinuityBehavior(val wireValue: String) {
        /**
         * The durable session era persists across this lifecycle event.
         * [DurableSessionContinuityRecord.durableSessionId] is unchanged.
         * No V2 resync is required for session identity.
         */
        DURABLE_SESSION_PRESERVED("durable_session_preserved"),

        /**
         * The durable session era is ended by this lifecycle event.
         * Android starts a new era on reconnect (new [DurableSessionContinuityRecord]).
         * V2 must assign/reconcile a new session for the participant.
         */
        DURABLE_SESSION_ENDED("durable_session_ended"),

        /**
         * Session continuity is conditional — the WS reconnect may restore the session
         * if it completes before the V2-side session timeout expires.
         * If the session expires on V2's side, a new era begins.
         */
        RECONNECT_DEPENDENT("reconnect_dependent")
    }

    // ── Boundary entry ────────────────────────────────────────────────────────

    /**
     * Describes the participant-runtime impact of a single Android app lifecycle event.
     *
     * @param event                        The [AppLifecycleEvent] described by this entry.
     * @param participantStateEffect       Canonical participant-state effect of this event.
     * @param sessionContinuityBehavior    How durable session continuity is affected.
     * @param androidCanRecoverLocally     `true` when Android can restore runtime participation
     *                                     without a V2 resync (e.g. connectIfEnabled() is enough).
     * @param requiresV2Resync             `true` when V2 must reconcile its participant registry
     *                                     after this lifecycle event before dispatch can resume.
     * @param rationale                    Explanation of the effect and recovery semantics.
     */
    data class LifecycleBoundaryEntry(
        val event: AppLifecycleEvent,
        val participantStateEffect: ParticipantStateEffect,
        val sessionContinuityBehavior: SessionContinuityBehavior,
        val androidCanRecoverLocally: Boolean,
        val requiresV2Resync: Boolean,
        val rationale: String
    )

    // ── Lifecycle boundary registry ────────────────────────────────────────────

    /**
     * Complete boundary map from Android app lifecycle events to participant-side effects.
     *
     * All six [AppLifecycleEvent] values are covered exactly once.
     */
    val entries: List<LifecycleBoundaryEntry> = listOf(
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.FOREGROUND,
            participantStateEffect = ParticipantStateEffect.NO_CHANGE,
            sessionContinuityBehavior = SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            androidCanRecoverLocally = true,
            requiresV2Resync = false,
            rationale = "App foregrounding does not affect the WS connection or session state. " +
                "RuntimeController.connectIfEnabled() restores the WS if it was previously " +
                "disconnected while backgrounded. No new session or V2 resync required if WS " +
                "is already active."
        ),
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.BACKGROUND,
            participantStateEffect = ParticipantStateEffect.NO_CHANGE,
            sessionContinuityBehavior = SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            androidCanRecoverLocally = true,
            requiresV2Resync = false,
            rationale = "Moving to background with process kept alive does not tear down the session. " +
                "The WS connection and AttachedRuntimeSession remain active. In-flight tasks may " +
                "complete. Android does not notify V2 of background-only transitions unless a " +
                "WS disconnect follows."
        ),
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.BACKGROUND_KILL,
            participantStateEffect = ParticipantStateEffect.SESSION_TERMINATED,
            sessionContinuityBehavior = SessionContinuityBehavior.DURABLE_SESSION_ENDED,
            androidCanRecoverLocally = false,
            requiresV2Resync = true,
            rationale = "Process kill ends all in-memory state including AttachedRuntimeSession, " +
                "DurableSessionContinuityRecord, and any in-flight task state. Android cannot " +
                "recover task execution results or session truth. On next start, a new " +
                "activation era begins. V2 must treat any previously dispatched-but-unreported " +
                "tasks as failed/lost and apply fallback policy."
        ),
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.CONFIGURATION_CHANGE,
            participantStateEffect = ParticipantStateEffect.NO_CHANGE,
            sessionContinuityBehavior = SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            androidCanRecoverLocally = true,
            requiresV2Resync = false,
            rationale = "Activity recreation due to configuration change (rotation, locale change) " +
                "does not affect RuntimeController or the WS connection because RuntimeController is " +
                "scoped to a Service or Application context, not to an Activity. Session and runtime " +
                "state are fully preserved. No V2 action required."
        ),
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.MEMORY_PRESSURE,
            participantStateEffect = ParticipantStateEffect.EXECUTION_PAUSED,
            sessionContinuityBehavior = SessionContinuityBehavior.RECONNECT_DEPENDENT,
            androidCanRecoverLocally = false,
            requiresV2Resync = true,
            rationale = "Severe memory pressure (TRIM_MEMORY_RUNNING_CRITICAL or higher) may cause " +
                "the OS to kill the process. In-flight task state may be lost. Android should " +
                "downgrade readiness to ParticipantHealthState.DEGRADED before pressure becomes " +
                "critical, allowing V2 to redirect dispatch. If the process survives, session " +
                "continuity depends on WS reconnect success. V2 must be prepared to resync after " +
                "the memory-pressure episode resolves."
        ),
        LifecycleBoundaryEntry(
            event = AppLifecycleEvent.PROCESS_RECREATION,
            participantStateEffect = ParticipantStateEffect.SESSION_TERMINATED,
            sessionContinuityBehavior = SessionContinuityBehavior.DURABLE_SESSION_ENDED,
            androidCanRecoverLocally = false,
            requiresV2Resync = true,
            rationale = "Process recreation (after OOM kill, explicit restart, or system restart) " +
                "starts a new activation era. The prior DurableSessionContinuityRecord is not persisted " +
                "across process death (it is held in-memory only). RuntimeController.connectIfEnabled() " +
                "will restore the WS if AppSettings.crossDeviceEnabled is true. A new " +
                "AttachedRuntimeSession will be opened under a new durableSessionId. V2 must " +
                "reconcile the new session against its participant registry via " +
                "V2MultiDeviceLifecycleEvent.DeviceConnected and publishRuntimeTruthSnapshot."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [LifecycleBoundaryEntry] for the given [event].
     * All six [AppLifecycleEvent] values are guaranteed to have an entry.
     */
    fun boundaryFor(event: AppLifecycleEvent): LifecycleBoundaryEntry =
        entries.first { it.event == event }

    /**
     * Returns all lifecycle events for which Android can recover locally
     * without requiring a V2 resync.
     */
    val locallyRecoverableEvents: List<AppLifecycleEvent> =
        entries.filter { it.androidCanRecoverLocally }.map { it.event }

    /**
     * Returns all lifecycle events that require V2 re-synchronization
     * before dispatch can resume.
     */
    val v2ResyncRequiredEvents: List<AppLifecycleEvent> =
        entries.filter { it.requiresV2Resync }.map { it.event }

    /**
     * Returns all lifecycle events that cause the durable session era to end
     * ([SessionContinuityBehavior.DURABLE_SESSION_ENDED]).
     */
    val sessionTerminatingEvents: List<AppLifecycleEvent> =
        entries.filter {
            it.sessionContinuityBehavior == SessionContinuityBehavior.DURABLE_SESSION_ENDED
        }.map { it.event }

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key used in diagnostics for the app lifecycle event that triggered a transition. */
    const val KEY_APP_LIFECYCLE_EVENT = "app_lifecycle_event"

    /** Wire key used in diagnostics for the participant state effect. */
    const val KEY_PARTICIPANT_STATE_EFFECT = "participant_state_effect"

    /** Wire key used in diagnostics for the session continuity behavior. */
    const val KEY_SESSION_CONTINUITY_BEHAVIOR = "session_continuity_behavior"

    /** Wire key used in diagnostics to indicate whether Android can recover locally. */
    const val KEY_ANDROID_CAN_RECOVER_LOCALLY = "android_can_recover_locally"

    /** Wire key used in diagnostics to indicate whether V2 resync is required. */
    const val KEY_REQUIRES_V2_RESYNC = "requires_v2_resync"

    /**
     * Expected number of lifecycle boundary entries in this registry.
     * Must equal the number of [AppLifecycleEvent] values.
     */
    const val ENTRY_COUNT: Int = 6

    /**
     * PR number that introduced this boundary declaration.
     */
    const val INTRODUCED_PR: Int = 53
}
