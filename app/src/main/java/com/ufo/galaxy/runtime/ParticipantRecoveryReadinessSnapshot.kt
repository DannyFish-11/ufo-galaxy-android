package com.ufo.galaxy.runtime

/**
 * PR-53 — Participant-side recovery readiness snapshot for the Android runtime.
 *
 * Explicitly declares which runtime-critical local state Android can recover independently
 * after a lifecycle disruption, and which state must be re-synchronized from V2.
 *
 * ## Design intent
 *
 * Before PR-53, the recovery semantics of individual runtime-critical state fields were
 * scattered across multiple classes ([DurableSessionContinuityRecord], [ReconnectRecoveryState],
 * [AttachedRuntimeSession], [RuntimeController]) without a single reviewable declaration of
 * durability, survivability, and V2 resync requirements.
 *
 * [ParticipantRecoveryReadinessSnapshot] fills this gap by providing a complete, machine-readable
 * durability/recovery registry that answers two questions per field:
 * 1. **Does this field survive a WS reconnect or process kill?**
 * 2. **Does V2 need to resync this field after recovery?**
 *
 * ## Relationship to other recovery surfaces
 *
 * | Surface | Responsibility |
 * |---------|----------------|
 * | [DurableSessionContinuityRecord] | Live in-memory record of the current session era |
 * | [ReconnectRecoveryState] | Observable WS reconnect phase (RECOVERING / RECOVERED / FAILED) |
 * | [AppLifecycleParticipantBoundary] | App lifecycle event → participant state effect mapping |
 * | [ParticipantRecoveryReadinessSnapshot] | Static durability/V2-resync registry per field (this class) |
 *
 * ## What Android can and cannot recover locally
 *
 * | Recovery scenario     | What Android recovers locally                         | What requires V2 resync |
 * |-----------------------|-------------------------------------------------------|-------------------------|
 * | WS reconnect          | Session epoch increment; readiness/health re-report   | New AttachedRuntimeSession identity; task state |
 * | Process kill/restart  | crossDeviceEnabled flag (from AppSettings)            | Everything else — durable session, task state, session assignment |
 * | Configuration change  | Nothing — all runtime state is preserved intact       | Nothing — no disruption to participant state |
 *
 * ## Constraint
 *
 * Android is a **recovery participant**, not a recovery coordinator.  After any lifecycle
 * disruption, V2 drives the re-synchronization decision.  Android MUST NOT autonomously
 * resume interrupted task execution or self-declare a session as continued without V2
 * re-authorization.
 *
 * @see AppLifecycleParticipantBoundary
 * @see DurableSessionContinuityRecord
 * @see ReconnectRecoveryState
 * @see ContinuityRecoveryContext
 */
object ParticipantRecoveryReadinessSnapshot {

    // ── Durability tiers ──────────────────────────────────────────────────────

    /**
     * Durability tier of a runtime-critical local state field.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class DurabilityTier(val wireValue: String) {

        /**
         * Survives WS reconnects within one activation era; lost on process kill or app restart.
         *
         * Fields at this tier are held in-memory by [RuntimeController] and are re-reported to V2
         * via the reconnect path ([V2MultiDeviceLifecycleEvent.DeviceReconnected] and
         * [ReconciliationSignal.RUNTIME_TRUTH_SNAPSHOT]) without needing persistent storage.
         */
        DURABLE_IN_MEMORY("durable_in_memory"),

        /**
         * Persisted in [com.ufo.galaxy.data.AppSettings] (SharedPreferences).
         *
         * Survives process kill, device restart, and app update.  Restored by
         * [RuntimeController.connectIfEnabled] on next service start.  Android can recover
         * fields at this tier completely autonomously.
         */
        SETTINGS_PERSISTED("settings_persisted"),

        /**
         * Ephemeral; valid only for the current WS connection instance.
         *
         * Reset on any WS disconnect or session invalidation.  Fields at this tier cannot
         * be recovered without a new WS connection.
         */
        EPHEMERAL("ephemeral"),

        /**
         * Not held by Android; V2 is the canonical source of truth.
         *
         * Android does not own or persist this state.  It must be re-fetched or resynchronized
         * from V2 after any lifecycle disruption before execution can resume.
         */
        V2_CANONICAL("v2_canonical")
    }

    // ── Recovery field entry ──────────────────────────────────────────────────

    /**
     * Describes the durability and recovery requirements of a single runtime-critical
     * local state field.
     *
     * @param fieldId                         Stable kebab-case identifier for this field.
     * @param displayName                     Human-readable surface and field description.
     * @param durabilityTier                  How durable this field is ([DurabilityTier]).
     * @param survivesProcessKill             `true` when the field value is preserved after the
     *                                        Android process is killed and recreated.
     * @param survivesWsReconnect             `true` when the field value is preserved after a WS
     *                                        disconnect-reconnect cycle within an activation era.
     * @param requiresV2ResyncAfterProcessKill `true` when V2 must reconcile this field after a
     *                                        process kill before dispatch can resume.
     * @param requiresV2ResyncAfterWsReconnect `true` when V2 must reconcile this field after a WS
     *                                        reconnect before dispatch can resume.
     * @param rationale                       Explanation of the durability semantics and recovery path.
     */
    data class RecoveryFieldEntry(
        val fieldId: String,
        val displayName: String,
        val durabilityTier: DurabilityTier,
        val survivesProcessKill: Boolean,
        val survivesWsReconnect: Boolean,
        val requiresV2ResyncAfterProcessKill: Boolean,
        val requiresV2ResyncAfterWsReconnect: Boolean,
        val rationale: String
    )

    // ── Recovery field registry ───────────────────────────────────────────────

    /**
     * Complete registry of runtime-critical local state fields and their recovery semantics.
     *
     * Fields are grouped by their [DurabilityTier].
     */
    val fields: List<RecoveryFieldEntry> = listOf(

        // ── SETTINGS_PERSISTED fields ─────────────────────────────────────────

        RecoveryFieldEntry(
            fieldId = "cross_device_enabled_flag",
            displayName = "AppSettings.crossDeviceEnabled",
            durabilityTier = DurabilityTier.SETTINGS_PERSISTED,
            survivesProcessKill = true,
            survivesWsReconnect = true,
            requiresV2ResyncAfterProcessKill = false,
            requiresV2ResyncAfterWsReconnect = false,
            rationale = "crossDeviceEnabled is persisted in AppSettings (SharedPreferences). " +
                "RuntimeController.connectIfEnabled() reads this value on startup to decide " +
                "whether to re-establish the WS connection. No V2 resync is needed — Android " +
                "autonomously re-registers using the same device identity on next activation."
        ),

        // ── DURABLE_IN_MEMORY fields ──────────────────────────────────────────

        RecoveryFieldEntry(
            fieldId = "durable_session_id",
            displayName = "DurableSessionContinuityRecord.durableSessionId",
            durabilityTier = DurabilityTier.DURABLE_IN_MEMORY,
            survivesProcessKill = false,
            survivesWsReconnect = true,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = false,
            rationale = "durableSessionId is held in-memory in RuntimeController. It survives WS " +
                "reconnects within the same activation era (the sessionContinuityEpoch is incremented " +
                "on each reconnect). After process kill, a new durableSessionId is generated on the " +
                "next activation. V2 must reconcile the new ID against its participant registry via " +
                "the DeviceConnected lifecycle event."
        ),
        RecoveryFieldEntry(
            fieldId = "participant_health_readiness",
            displayName = "ParticipantHealthState / ParticipantReadinessState",
            durabilityTier = DurabilityTier.DURABLE_IN_MEMORY,
            survivesProcessKill = false,
            survivesWsReconnect = true,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = false,
            rationale = "Health and readiness state are held in-memory by RuntimeController. " +
                "They survive WS reconnects — Android re-reports via V2MultiDeviceLifecycleEvent " +
                "and the first publishRuntimeTruthSnapshot call after reconnect. After process kill, " +
                "Android re-evaluates readiness on startup and reports via publishRuntimeTruthSnapshot."
        ),
        RecoveryFieldEntry(
            fieldId = "session_participation_state",
            displayName = "RuntimeHostDescriptor.HostParticipationState",
            durabilityTier = DurabilityTier.DURABLE_IN_MEMORY,
            survivesProcessKill = false,
            survivesWsReconnect = true,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = false,
            rationale = "HostParticipationState (ACTIVE/INACTIVE) is set by RuntimeController based " +
                "on session state. It is ACTIVE on attach and INACTIVE on detach. Survives WS " +
                "reconnects (restored to ACTIVE on new session open). After process kill, " +
                "re-established on next activation via the standard open-attached-session path."
        ),

        // ── EPHEMERAL fields ──────────────────────────────────────────────────

        RecoveryFieldEntry(
            fieldId = "attached_runtime_session",
            displayName = "AttachedRuntimeSession (sessionId, state)",
            durabilityTier = DurabilityTier.EPHEMERAL,
            survivesProcessKill = false,
            survivesWsReconnect = false,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = true,
            rationale = "AttachedRuntimeSession is an in-memory, per-connection session object. " +
                "On WS reconnect, a new session is opened (SessionOpenSource.RECONNECT_RECOVERY) " +
                "with a new sessionId under the same durableSessionId era. V2 must reconcile the " +
                "new session via DeviceReconnected and DurableSessionContinuityRecord correlation. " +
                "After process kill, a completely new session is started on next activation."
        ),
        RecoveryFieldEntry(
            fieldId = "active_task_state",
            displayName = "In-flight task state (activeTaskId, activeTaskStatus)",
            durabilityTier = DurabilityTier.EPHEMERAL,
            survivesProcessKill = false,
            survivesWsReconnect = false,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = true,
            rationale = "Active task state is ephemeral in-memory. Any task in progress at the time " +
                "of WS disconnect or process kill is considered lost from Android's perspective. " +
                "Android MUST NOT attempt to resume interrupted task execution. V2 must apply fallback " +
                "policy for any tasks dispatched to Android that did not receive a TASK_RESULT or " +
                "TASK_CANCELLED signal before the disruption."
        ),

        // ── V2_CANONICAL fields ───────────────────────────────────────────────

        RecoveryFieldEntry(
            fieldId = "global_session_assignment",
            displayName = "Global session assignment (which participants, which tasks)",
            durabilityTier = DurabilityTier.V2_CANONICAL,
            survivesProcessKill = false,
            survivesWsReconnect = false,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = true,
            rationale = "V2 is the canonical source of global session assignment truth. Android does " +
                "NOT hold or recover this information. After any lifecycle disruption, V2 re-synchronizes " +
                "session assignments based on Android's reconnect events and the published " +
                "AndroidParticipantRuntimeTruth snapshot."
        ),
        RecoveryFieldEntry(
            fieldId = "barrier_merge_tracking",
            displayName = "Barrier / merge / completion tracking",
            durabilityTier = DurabilityTier.V2_CANONICAL,
            survivesProcessKill = false,
            survivesWsReconnect = false,
            requiresV2ResyncAfterProcessKill = true,
            requiresV2ResyncAfterWsReconnect = true,
            rationale = "Barrier and merge tracking is a V2-side concern. Android reports health " +
                "and readiness transitions that may cross barrier-relevant thresholds, but Android " +
                "does not track cross-participant barrier state. V2 re-evaluates barrier state " +
                "based on participant re-registration after any lifecycle disruption."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [RecoveryFieldEntry] for the given [fieldId], or `null` if not found.
     */
    fun fieldFor(fieldId: String): RecoveryFieldEntry? =
        fields.firstOrNull { it.fieldId == fieldId }

    /**
     * Returns all fields that Android can recover locally after a WS reconnect without
     * requiring V2 resync ([RecoveryFieldEntry.requiresV2ResyncAfterWsReconnect] == false).
     */
    val locallyRecoverableAfterWsReconnect: List<RecoveryFieldEntry> =
        fields.filter { !it.requiresV2ResyncAfterWsReconnect }

    /**
     * Returns all fields that require V2 resync after a process kill.
     */
    val requiresV2ResyncAfterProcessKillList: List<RecoveryFieldEntry> =
        fields.filter { it.requiresV2ResyncAfterProcessKill }

    /**
     * Returns all fields that require V2 resync after a WS reconnect.
     */
    val requiresV2ResyncAfterWsReconnectList: List<RecoveryFieldEntry> =
        fields.filter { it.requiresV2ResyncAfterWsReconnect }

    /**
     * Returns all fields with [DurabilityTier.SETTINGS_PERSISTED] tier — the only fields
     * that survive a process kill autonomously.
     */
    val settingsPersistedFields: List<RecoveryFieldEntry> =
        fields.filter { it.durabilityTier == DurabilityTier.SETTINGS_PERSISTED }

    /**
     * Returns all fields with [DurabilityTier.V2_CANONICAL] tier — fields that Android
     * never holds and that always require V2 resync.
     */
    val v2CanonicalFields: List<RecoveryFieldEntry> =
        fields.filter { it.durabilityTier == DurabilityTier.V2_CANONICAL }

    /**
     * Total number of recovery field entries in this registry.
     */
    val fieldCount: Int get() = fields.size

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key for the field identifier in diagnostics payloads. */
    const val KEY_FIELD_ID = "recovery_field_id"

    /** Wire key for the durability tier in diagnostics payloads. */
    const val KEY_DURABILITY_TIER = "recovery_durability_tier"

    /** Wire key for the V2 resync requirement in diagnostics payloads. */
    const val KEY_REQUIRES_V2_RESYNC = "requires_v2_resync"

    /** Wire key for the process-kill survival flag in diagnostics payloads. */
    const val KEY_SURVIVES_PROCESS_KILL = "survives_process_kill"

    /** Wire key for the WS reconnect survival flag in diagnostics payloads. */
    const val KEY_SURVIVES_WS_RECONNECT = "survives_ws_reconnect"

    /**
     * PR number that introduced this recovery readiness snapshot declaration.
     */
    const val INTRODUCED_PR: Int = 53
}
