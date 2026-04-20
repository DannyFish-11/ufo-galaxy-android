package com.ufo.galaxy.runtime

/**
 * PR-51 — Android Participant Runtime Truth Snapshot.
 *
 * Provides a consolidated, structured snapshot of all Android-side participant runtime
 * truth fields that V2 needs to reconcile its canonical orchestration state against the
 * Android participant's self-reported local truth.
 *
 * ## Problem addressed
 *
 * Prior PRs established rich individual contracts for participant identity
 * ([CanonicalParticipantModel]), session state ([AttachedRuntimeHostSessionSnapshot]),
 * health state ([ParticipantHealthState]), readiness ([ParticipantReadinessState]),
 * lifecycle events ([V2MultiDeviceLifecycleEvent]), and protocol signals
 * ([AndroidSessionContribution]).  Each surface reports one dimension of Android-side
 * truth in isolation.
 *
 * Without a single consolidated snapshot, V2 must assemble Android participant truth from
 * multiple orthogonal streams, creating:
 *  - **Reconciliation ambiguity** — V2 cannot determine which combination of signals
 *    represents the authoritative current state.
 *  - **Update ordering risk** — partial snapshots from different streams may be consumed
 *    in different orders, leading to transient inconsistencies.
 *  - **Missing visibility** — V2 cannot determine whether a given Android participant is
 *    transparently observed or partially opaque.
 *
 * [AndroidParticipantRuntimeTruth] closes this gap by providing a single, point-in-time
 * snapshot of all participant-level runtime truth fields that V2 needs for confident
 * reconciliation.
 *
 * ## Relationship to existing surfaces
 *
 * [AndroidParticipantRuntimeTruth] is a **read-only projection** that consolidates
 * existing authoritative surfaces.  It does not own any lifecycle state:
 *  - Participant identity is derived from [CanonicalParticipantModel] / [RuntimeHostDescriptor].
 *  - Session truth is derived from [AttachedRuntimeHostSessionSnapshot] / [AttachedRuntimeSession].
 *  - Health truth is the canonical [ParticipantHealthState] reported by the runtime.
 *  - Readiness is the canonical [ParticipantReadinessState] reported by the selection layer.
 *  - Task status is the in-flight execution status at the time of snapshot creation.
 *  - Posture is the [SourceRuntimePosture] value for the current or most recent task.
 *
 * V2 must treat this snapshot as **Android-owned, point-in-time truth** and reconcile
 * it against its canonical session/participant registry.  Android does not own the
 * global orchestration truth; it only owns what is reported here.
 *
 * ## Wire serialization
 *
 * All fields travel under stable [KEY_*] constants via [toMap].  Optional fields are
 * omitted from the map when null to keep the wire format lean.
 *
 * ## Reconciliation epoch
 *
 * [reconciliationEpoch] is a monotonically increasing counter scoped to this device's
 * runtime process lifetime.  V2 can use it to detect stale snapshots: a snapshot with
 * a lower epoch than the previously received snapshot is superseded and should be
 * discarded.
 *
 * @property participantId           Stable participant node identifier; links this snapshot
 *                                   to the participant registered in V2's participant registry.
 * @property deviceId                Hardware device identifier matching [RuntimeHostDescriptor.deviceId].
 * @property hostId                  Per-process runtime host instance identifier matching
 *                                   [RuntimeHostDescriptor.hostId].
 * @property deviceRole              Logical device role (e.g. `"phone"`, `"tablet"`).
 * @property participationState      Current [RuntimeHostDescriptor.HostParticipationState] of
 *                                   the runtime host.
 * @property coordinationRole        Whether this participant is acting as COORDINATOR or
 *                                   PARTICIPANT in the current formation.
 * @property sourceRuntimePosture    [SourceRuntimePosture] value: CONTROL_ONLY or JOIN_RUNTIME.
 * @property sessionId               Current attached-session identifier; null when not attached.
 * @property sessionState            Current [AttachedRuntimeSession.State]; null when no session
 *                                   is present.
 * @property delegatedExecutionCount Number of delegated tasks accepted under the current session;
 *                                   0 when no session is present.
 * @property healthState             Current [ParticipantHealthState] of the execution environment.
 * @property readinessState          Current [ParticipantReadinessState] for dispatch selection.
 * @property activeTaskId            Identifier of the currently executing task; null if idle.
 * @property activeTaskStatus        Current [ActiveTaskStatus] for the in-flight task; null if idle.
 * @property reportedAtMs            Epoch-millisecond timestamp when this snapshot was created.
 * @property reconciliationEpoch     Monotonically increasing snapshot epoch for V2 staleness detection.
 */
data class AndroidParticipantRuntimeTruth(
    val participantId: String,
    val deviceId: String,
    val hostId: String,
    val deviceRole: String,
    val participationState: RuntimeHostDescriptor.HostParticipationState,
    val coordinationRole: ParticipantCoordinationRole,
    val sourceRuntimePosture: String,
    val sessionId: String?,
    val sessionState: AttachedRuntimeSession.State?,
    val delegatedExecutionCount: Int,
    val healthState: ParticipantHealthState,
    val readinessState: ParticipantReadinessState,
    val activeTaskId: String?,
    val activeTaskStatus: ActiveTaskStatus?,
    val reportedAtMs: Long,
    val reconciliationEpoch: Int
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this snapshot has enough state for V2 to perform a full
     * reconciliation pass against its canonical participant registry.
     *
     * A snapshot is **fully reconcilable** when:
     *  - [participantId] is non-blank (stable identity is present).
     *  - [participationState] is not [RuntimeHostDescriptor.HostParticipationState.INACTIVE]
     *    (participant is active in the formation).
     *  - [healthState] is not [ParticipantHealthState.UNKNOWN] (health has been assessed).
     *  - [readinessState] is not [ParticipantReadinessState.UNKNOWN] (readiness has been evaluated).
     *
     * A non-reconcilable snapshot should be accepted by V2 but treated as a **partial truth**
     * that cannot fully close the reconciliation loop.  V2 should wait for a subsequent fully-
     * reconcilable snapshot or a [V2MultiDeviceLifecycleEvent] that completes the picture.
     */
    val isFullyReconcilable: Boolean
        get() = participantId.isNotBlank() &&
            participationState != RuntimeHostDescriptor.HostParticipationState.INACTIVE &&
            healthState != ParticipantHealthState.UNKNOWN &&
            readinessState != ParticipantReadinessState.UNKNOWN

    /**
     * Returns `true` when the participant is currently attached to a runtime session and
     * is able to accept delegated tasks from V2.
     */
    val isAttachedAndEligible: Boolean
        get() = sessionState == AttachedRuntimeSession.State.ATTACHED &&
            participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
            healthState == ParticipantHealthState.HEALTHY

    /**
     * Returns `true` when the participant currently has an in-flight task execution.
     */
    val hasActiveTask: Boolean
        get() = activeTaskId != null && activeTaskStatus != null

    // ── Wire serialization ────────────────────────────────────────────────────

    /**
     * Serialises all participant truth fields to a stable key→value [Map] for wire
     * transmission and V2 reconciliation consumption.
     *
     * Always-present keys: all identity, participation, health, readiness, and
     * reconciliation fields.  Conditional keys: [KEY_SESSION_ID], [KEY_SESSION_STATE],
     * [KEY_ACTIVE_TASK_ID], [KEY_ACTIVE_TASK_STATUS] — omitted when null.
     */
    fun toMap(): Map<String, Any> = buildMap {
        put(KEY_PARTICIPANT_ID, participantId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_HOST_ID, hostId)
        put(KEY_DEVICE_ROLE, deviceRole)
        put(KEY_PARTICIPATION_STATE, participationState.wireValue)
        put(KEY_COORDINATION_ROLE, coordinationRole.wireValue)
        put(KEY_SOURCE_RUNTIME_POSTURE, sourceRuntimePosture)
        sessionId?.let { put(KEY_SESSION_ID, it) }
        sessionState?.let { put(KEY_SESSION_STATE, it.wireValue) }
        put(KEY_DELEGATED_EXECUTION_COUNT, delegatedExecutionCount)
        put(KEY_HEALTH_STATE, healthState.wireValue)
        put(KEY_READINESS_STATE, readinessState.wireValue)
        activeTaskId?.let { put(KEY_ACTIVE_TASK_ID, it) }
        activeTaskStatus?.let { put(KEY_ACTIVE_TASK_STATUS, it.wireValue) }
        put(KEY_REPORTED_AT_MS, reportedAtMs)
        put(KEY_RECONCILIATION_EPOCH, reconciliationEpoch)
        put(KEY_IS_FULLY_RECONCILABLE, isFullyReconcilable)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [participantId]. */
        const val KEY_PARTICIPANT_ID = "participant_id"

        /** Wire key for [deviceId]. */
        const val KEY_DEVICE_ID = "device_id"

        /** Wire key for [hostId]. */
        const val KEY_HOST_ID = "host_id"

        /** Wire key for [deviceRole]. */
        const val KEY_DEVICE_ROLE = "device_role"

        /** Wire key for [participationState] ([RuntimeHostDescriptor.HostParticipationState.wireValue]). */
        const val KEY_PARTICIPATION_STATE = "participation_state"

        /** Wire key for [coordinationRole] ([ParticipantCoordinationRole.wireValue]). */
        const val KEY_COORDINATION_ROLE = "coordination_role"

        /** Wire key for [sourceRuntimePosture] ([SourceRuntimePosture] constant). */
        const val KEY_SOURCE_RUNTIME_POSTURE = "source_runtime_posture"

        /** Wire key for [sessionId]; absent when [sessionId] is null. */
        const val KEY_SESSION_ID = "session_id"

        /** Wire key for [sessionState] ([AttachedRuntimeSession.State.wireValue]); absent when null. */
        const val KEY_SESSION_STATE = "session_state"

        /** Wire key for [delegatedExecutionCount]. */
        const val KEY_DELEGATED_EXECUTION_COUNT = "delegated_execution_count"

        /** Wire key for [healthState] ([ParticipantHealthState.wireValue]). */
        const val KEY_HEALTH_STATE = "health_state"

        /** Wire key for [readinessState] ([ParticipantReadinessState.wireValue]). */
        const val KEY_READINESS_STATE = "readiness_state"

        /** Wire key for [activeTaskId]; absent when [activeTaskId] is null. */
        const val KEY_ACTIVE_TASK_ID = "active_task_id"

        /** Wire key for [activeTaskStatus] ([ActiveTaskStatus.wireValue]); absent when null. */
        const val KEY_ACTIVE_TASK_STATUS = "active_task_status"

        /** Wire key for [reportedAtMs]. */
        const val KEY_REPORTED_AT_MS = "reported_at_ms"

        /** Wire key for [reconciliationEpoch]. */
        const val KEY_RECONCILIATION_EPOCH = "reconciliation_epoch"

        /** Wire key for [isFullyReconcilable] pre-computed boolean. */
        const val KEY_IS_FULLY_RECONCILABLE = "is_fully_reconcilable"

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Builds an [AndroidParticipantRuntimeTruth] snapshot from the canonical runtime-host
         * and session truth surfaces.
         *
         * [reconciliationEpoch] must be supplied by the caller (typically an atomic counter
         * scoped to the current runtime process).  [reportedAtMs] defaults to the current
         * system time.
         *
         * @param descriptor             Current [RuntimeHostDescriptor].
         * @param sessionSnapshot        Current [AttachedRuntimeHostSessionSnapshot], or null.
         * @param healthState            Current [ParticipantHealthState] of the participant.
         * @param readinessState         Current [ParticipantReadinessState] for dispatch selection.
         * @param activeTaskId           In-flight task identifier, or null if idle.
         * @param activeTaskStatus       Status of the in-flight task, or null if idle.
         * @param reconciliationEpoch    Monotonically increasing snapshot epoch.
         * @param reportedAtMs           Snapshot creation timestamp; defaults to now.
         */
        fun from(
            descriptor: RuntimeHostDescriptor,
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
            healthState: ParticipantHealthState = ParticipantHealthState.UNKNOWN,
            readinessState: ParticipantReadinessState = ParticipantReadinessState.UNKNOWN,
            activeTaskId: String? = null,
            activeTaskStatus: ActiveTaskStatus? = null,
            reconciliationEpoch: Int = 0,
            reportedAtMs: Long = System.currentTimeMillis()
        ): AndroidParticipantRuntimeTruth {
            val participantId = RuntimeIdentityContracts.participantNodeId(
                deviceId = descriptor.deviceId,
                runtimeHostId = descriptor.hostId
            )
            val coordinationRole = when (descriptor.formationRole) {
                RuntimeHostDescriptor.FormationRole.PRIMARY ->
                    ParticipantCoordinationRole.COORDINATOR
                RuntimeHostDescriptor.FormationRole.SECONDARY,
                RuntimeHostDescriptor.FormationRole.SATELLITE ->
                    ParticipantCoordinationRole.PARTICIPANT
            }
            return AndroidParticipantRuntimeTruth(
                participantId = participantId,
                deviceId = descriptor.deviceId,
                hostId = descriptor.hostId,
                deviceRole = descriptor.deviceRole,
                participationState = descriptor.participationState,
                coordinationRole = coordinationRole,
                sourceRuntimePosture = sessionSnapshot?.posture ?: SourceRuntimePosture.DEFAULT,
                sessionId = sessionSnapshot?.sessionId,
                sessionState = sessionSnapshot?.attachmentState?.let {
                    AttachedRuntimeSession.State.fromValue(it)
                },
                delegatedExecutionCount = sessionSnapshot?.delegatedExecutionCount ?: 0,
                healthState = healthState,
                readinessState = readinessState,
                activeTaskId = activeTaskId,
                activeTaskStatus = activeTaskStatus,
                reportedAtMs = reportedAtMs,
                reconciliationEpoch = reconciliationEpoch
            )
        }
    }
}

/**
 * In-flight task execution status reported as part of [AndroidParticipantRuntimeTruth].
 *
 * Captures the Android-side view of a currently executing task so V2 can determine
 * whether Android is actively running a delegated task or is idle.
 *
 * @property wireValue Stable lowercase string used in wire-format maps.
 */
enum class ActiveTaskStatus(val wireValue: String) {

    /**
     * Task has been accepted by Android and execution is in progress.
     * V2 should expect a result, cancellation, or failure signal in the near future.
     */
    RUNNING("running"),

    /**
     * Android has accepted the task but execution has not yet started (e.g. queued
     * behind a prior task or waiting for a subsystem to become ready).
     */
    PENDING("pending"),

    /**
     * Android received a cancellation request for the currently executing task and
     * is in the process of stopping execution.  A [ReconciliationSignal] with
     * [ReconciliationSignal.Kind.CANCELLATION] will follow.
     */
    CANCELLING("cancelling"),

    /**
     * Task execution encountered an error condition.  Android will emit a
     * [ReconciliationSignal] with [ReconciliationSignal.Kind.FAILURE] immediately.
     */
    FAILING("failing");

    companion object {
        /**
         * Parses [value] to an [ActiveTaskStatus], returning null for unknown values.
         *
         * @param value Wire string from a runtime snapshot; may be null.
         */
        fun fromValue(value: String?): ActiveTaskStatus? =
            entries.firstOrNull { it.wireValue == value }

        /** All stable wire values. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
