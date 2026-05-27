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
 * @property inflightContinuityState Android-local continuity classification for any prior
 *                                   in-flight execution interrupted by restart/recovery.
 * @property inflightContinuityTaskId Task identifier referenced by [inflightContinuityState], when any.
 * @property inflightContinuityObservedAtMs Timestamp when Android produced the local continuity classification.
 * @property taskAllocationTruth    Android-local authoritative task allocation truth snapshot.
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
    val inflightContinuityState: String? = null,
    val inflightContinuityTaskId: String? = null,
    val inflightContinuitySource: String? = null,
    val inflightContinuityObservedAtMs: Long? = null,
    val taskAllocationTruth: AndroidTaskAllocationTruthSnapshot? = null,
    val authoritativeParticipationState: String = defaultAuthoritativeParticipationState(
        sourceRuntimePosture = sourceRuntimePosture,
        sessionState = sessionState,
        participationState = participationState,
        healthState = healthState,
        readinessState = readinessState,
        activeTaskId = activeTaskId,
        activeTaskStatus = activeTaskStatus
    ),
    val runtimeNodeIdentity: AndroidRuntimeNodeIdentity? = null,
    val authoritativeParticipationTransitionSequence: Long? = null,
    val authoritativeParticipationTransitionTrigger: String? = null,
    val authoritativeParticipationTransitionHistory: List<String> = emptyList(),
    val reportedAtMs: Long,
    val reconciliationEpoch: Int
) {

    val outwardTruthSemantics: AndroidOutwardTruthSurfaceSemantics.SurfaceSemantics
        get() = AndroidOutwardTruthSurfaceSemantics.runtimeVisibleSnapshot(
            RuntimeTruthPrecedenceRules.TruthTier.PROJECTION
        )

    val outwardTruthSurfaceClass: String
        get() = outwardTruthSemantics.outwardTruthSurfaceClass.wireValue

    val truthTier: String
        get() = outwardTruthSemantics.truthTier.wireValue

    val sourceAuthorityClass: String
        get() = outwardTruthSemantics.sourceAuthorityClass

    val isV2ConfirmedCanonicalTruth: Boolean
        get() = outwardTruthSemantics.isV2ConfirmedCanonicalTruth

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

    /**
     * Android runtime availability posture exported to V2/system surfaces.
     *
     * This is participant-runtime availability evidence only; it does not override
     * canonical global availability/ownership adjudication in V2.
     */
    val runtimeAvailabilityTruthState: RuntimeAvailabilityTruthState
        get() = deriveRuntimeAvailabilityTruthState(
            participationState = participationState,
            sessionState = sessionState,
            healthState = healthState,
            readinessState = readinessState,
            inflightContinuityState = inflightContinuityState
        )

    /**
     * Participant-local task allocation/execution visibility state.
     *
     * Exposes whether Android is idle, accepted-pending, executing, interrupted, or
     * waiting for canonical reconciliation so upstream allocation surfaces do not need to
     * infer this from multiple raw fields.
     */
    val taskExecutionVisibilityState: TaskExecutionVisibilityState
        get() = deriveTaskExecutionVisibilityState(
            activeTaskStatus = activeTaskStatus,
            inflightContinuityState = inflightContinuityState,
            runtimeAvailabilityTruthState = runtimeAvailabilityTruthState
        )

    /**
     * Consolidated Android capability-truth level (observable / participant / execution).
     */
    val capabilityTruthLevel: RuntimeNodeCapabilityTruthLevel
        get() = runtimeNodeIdentity?.capabilityTruthLevel
            ?: deriveFallbackCapabilityTruthLevel(
                deviceRole = deviceRole,
                participationState = participationState,
                sourceRuntimePosture = sourceRuntimePosture,
                readinessState = readinessState,
                healthState = healthState
            )

    /**
     * Consolidated Android autonomy-truth level.
     */
    val autonomyTruthLevel: RuntimeNodeAutonomyTruthLevel
        get() = runtimeNodeIdentity?.autonomyTruthLevel
            ?: deriveFallbackAutonomyTruthLevel(
                deviceRole = deviceRole,
                sourceRuntimePosture = sourceRuntimePosture,
                readinessState = readinessState,
                healthState = healthState
            )

    /**
     * Runtime feature/modality readiness truth exported to upstream surfaces.
     */
    val featureReadinessTruthState: FeatureReadinessTruthState
        get() = deriveFeatureReadinessTruthState(
            deviceRole = deviceRole,
            participationState = participationState,
            readinessState = readinessState,
            healthState = healthState,
            runtimeAvailabilityTruthState = runtimeAvailabilityTruthState
        )

    val runtimeParticipationTopology: AndroidRuntimeParticipationTopology
        get() = AndroidRuntimeParticipationTopology.from(this)

    // ── Wire serialization ────────────────────────────────────────────────────

    /**
     * Serialises all participant truth fields to a stable key→value [Map] for wire
     * transmission and V2 reconciliation consumption.
     *
     * Always-present keys: all identity, participation, health, readiness, and
     * reconciliation fields.  Conditional keys: [KEY_SESSION_ID], [KEY_SESSION_STATE],
     * [KEY_ACTIVE_TASK_ID], [KEY_ACTIVE_TASK_STATUS], [KEY_INFLIGHT_CONTINUITY_STATE],
     * [KEY_INFLIGHT_CONTINUITY_TASK_ID], [KEY_INFLIGHT_CONTINUITY_SOURCE],
     * [KEY_INFLIGHT_CONTINUITY_OBSERVED_AT_MS] — omitted when null.
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
        inflightContinuityState?.let { put(KEY_INFLIGHT_CONTINUITY_STATE, it) }
        inflightContinuityTaskId?.let { put(KEY_INFLIGHT_CONTINUITY_TASK_ID, it) }
        inflightContinuitySource?.let { put(KEY_INFLIGHT_CONTINUITY_SOURCE, it) }
        inflightContinuityObservedAtMs?.let { put(KEY_INFLIGHT_CONTINUITY_OBSERVED_AT_MS, it) }
        taskAllocationTruth?.let { put(KEY_TASK_ALLOCATION_TRUTH, it.toMap()) }
        put(KEY_RUNTIME_PARTICIPATION_TOPOLOGY, runtimeParticipationTopology.toMap())
        put(KEY_AUTHORITATIVE_PARTICIPATION_STATE, authoritativeParticipationState)
        authoritativeParticipationTransitionSequence?.let {
            put(KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_SEQUENCE, it)
        }
        authoritativeParticipationTransitionTrigger?.let {
            put(KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_TRIGGER, it)
        }
        if (authoritativeParticipationTransitionHistory.isNotEmpty()) {
            put(
                KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_HISTORY,
                authoritativeParticipationTransitionHistory
            )
        }
        runtimeNodeIdentity?.let { put(KEY_RUNTIME_NODE_IDENTITY, it.toMap()) }
        put(KEY_REPORTED_AT_MS, reportedAtMs)
        put(KEY_RECONCILIATION_EPOCH, reconciliationEpoch)
        put(KEY_IS_FULLY_RECONCILABLE, isFullyReconcilable)
        put(KEY_RUNTIME_AVAILABILITY_TRUTH_STATE, runtimeAvailabilityTruthState.wireValue)
        put(KEY_TASK_EXECUTION_VISIBILITY_STATE, taskExecutionVisibilityState.wireValue)
        put(KEY_CAPABILITY_TRUTH_LEVEL, capabilityTruthLevel.wireValue)
        put(KEY_AUTONOMY_TRUTH_LEVEL, autonomyTruthLevel.wireValue)
        put(KEY_FEATURE_READINESS_TRUTH_STATE, featureReadinessTruthState.wireValue)
        put(KEY_OUTWARD_TRUTH_SURFACE_CLASS, outwardTruthSurfaceClass)
        put(KEY_TRUTH_TIER, truthTier)
        put(KEY_SOURCE_AUTHORITY_CLASS, sourceAuthorityClass)
        put(KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH, isV2ConfirmedCanonicalTruth)
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

        /** Wire key for [outwardTruthSurfaceClass]. */
        const val KEY_OUTWARD_TRUTH_SURFACE_CLASS =
            AndroidOutwardTruthSurfaceSemantics.KEY_OUTWARD_TRUTH_SURFACE_CLASS

        /** Wire key for [truthTier]. */
        const val KEY_TRUTH_TIER = AndroidOutwardTruthSurfaceSemantics.KEY_TRUTH_TIER

        /** Wire key for [sourceAuthorityClass]. */
        const val KEY_SOURCE_AUTHORITY_CLASS =
            AndroidOutwardTruthSurfaceSemantics.KEY_SOURCE_AUTHORITY_CLASS

        /** Wire key for [isV2ConfirmedCanonicalTruth]. */
        const val KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH =
            AndroidOutwardTruthSurfaceSemantics.KEY_IS_V2_CONFIRMED_CANONICAL_TRUTH

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

        /** Wire key for [inflightContinuityState]; absent when null. */
        const val KEY_INFLIGHT_CONTINUITY_STATE = "inflight_continuity_state"

        /** Wire key for [inflightContinuityTaskId]; absent when null. */
        const val KEY_INFLIGHT_CONTINUITY_TASK_ID = "inflight_continuity_task_id"

        /** Wire key for [inflightContinuitySource]; absent when null. */
        const val KEY_INFLIGHT_CONTINUITY_SOURCE = "inflight_continuity_source"

        /** Wire key for [inflightContinuityObservedAtMs]; absent when null. */
        const val KEY_INFLIGHT_CONTINUITY_OBSERVED_AT_MS = "inflight_continuity_observed_at_ms"

        /** Wire key for [taskAllocationTruth]; absent when null. */
        const val KEY_TASK_ALLOCATION_TRUTH = "task_allocation_truth"
        const val KEY_RUNTIME_PARTICIPATION_TOPOLOGY = "runtime_participation_topology"

        /** Wire key for [authoritativeParticipationState]. */
        const val KEY_AUTHORITATIVE_PARTICIPATION_STATE = "authoritative_participation_state"

        /** Wire key for [authoritativeParticipationTransitionSequence]. */
        const val KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_SEQUENCE =
            "authoritative_participation_transition_sequence"

        /** Wire key for [authoritativeParticipationTransitionTrigger]. */
        const val KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_TRIGGER =
            "authoritative_participation_transition_trigger"

        /** Wire key for [authoritativeParticipationTransitionHistory]. */
        const val KEY_AUTHORITATIVE_PARTICIPATION_TRANSITION_HISTORY =
            "authoritative_participation_transition_history"

        /** Wire key for consolidated [runtimeNodeIdentity] map; absent when null. */
        const val KEY_RUNTIME_NODE_IDENTITY = "runtime_node_identity"

        /** Wire key for [reportedAtMs]. */
        const val KEY_REPORTED_AT_MS = "reported_at_ms"

        /** Wire key for [reconciliationEpoch]. */
        const val KEY_RECONCILIATION_EPOCH = "reconciliation_epoch"

        /** Wire key for [isFullyReconcilable] pre-computed boolean. */
        const val KEY_IS_FULLY_RECONCILABLE = "is_fully_reconcilable"

        /** Wire key for [runtimeAvailabilityTruthState]. */
        const val KEY_RUNTIME_AVAILABILITY_TRUTH_STATE = "runtime_availability_truth_state"

        /** Wire key for [taskExecutionVisibilityState]. */
        const val KEY_TASK_EXECUTION_VISIBILITY_STATE = "task_execution_visibility_state"

        /** Wire key for [capabilityTruthLevel]. */
        const val KEY_CAPABILITY_TRUTH_LEVEL = "capability_truth_level"

        /** Wire key for [autonomyTruthLevel]. */
        const val KEY_AUTONOMY_TRUTH_LEVEL = "autonomy_truth_level"

        /** Wire key for [featureReadinessTruthState]. */
        const val KEY_FEATURE_READINESS_TRUTH_STATE = "feature_readiness_truth_state"

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
            inflightContinuityState: String? = null,
            inflightContinuityTaskId: String? = null,
            inflightContinuitySource: String? = null,
            inflightContinuityObservedAtMs: Long? = null,
            taskAllocationTruth: AndroidTaskAllocationTruthSnapshot? = null,
            carrierForegroundVisible: Boolean? = null,
            authoritativeParticipationState: String? = null,
            authoritativeParticipationTransitionSequence: Long? = null,
            authoritativeParticipationTransitionTrigger: String? = null,
            authoritativeParticipationTransitionHistory: List<String> = emptyList(),
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
            val driftCheckedSessionSnapshot = alignSessionSnapshot(descriptor, sessionSnapshot)
            val runtimePosture = driftCheckedSessionSnapshot?.posture ?: SourceRuntimePosture.CONTROL_ONLY
            val derivedParticipationState = authoritativeParticipationState
                ?: defaultAuthoritativeParticipationState(
                    sourceRuntimePosture = runtimePosture,
                    sessionState = driftCheckedSessionSnapshot?.attachmentState?.let {
                        AttachedRuntimeSession.State.fromValue(it)
                    },
                    participationState = descriptor.participationState,
                    healthState = healthState,
                    readinessState = readinessState,
                    activeTaskId = activeTaskId,
                    activeTaskStatus = activeTaskStatus
                )
            return AndroidParticipantRuntimeTruth(
                participantId = participantId,
                deviceId = descriptor.deviceId,
                hostId = descriptor.hostId,
                deviceRole = descriptor.deviceRole,
                participationState = descriptor.participationState,
                coordinationRole = coordinationRole,
                sourceRuntimePosture = runtimePosture,
                sessionId = driftCheckedSessionSnapshot?.sessionId,
                sessionState = driftCheckedSessionSnapshot?.attachmentState?.let {
                    AttachedRuntimeSession.State.fromValue(it)
                },
                delegatedExecutionCount = driftCheckedSessionSnapshot?.delegatedExecutionCount ?: 0,
                healthState = healthState,
                readinessState = readinessState,
                activeTaskId = activeTaskId,
                activeTaskStatus = activeTaskStatus,
                inflightContinuityState = inflightContinuityState,
                inflightContinuityTaskId = inflightContinuityTaskId,
                inflightContinuitySource = inflightContinuitySource,
                inflightContinuityObservedAtMs = inflightContinuityObservedAtMs,
                taskAllocationTruth = taskAllocationTruth,
                authoritativeParticipationState = derivedParticipationState,
                runtimeNodeIdentity = AndroidRuntimeNodeIdentity.from(
                    descriptor = descriptor,
                    sessionSnapshot = driftCheckedSessionSnapshot,
                    healthState = healthState,
                    readinessState = readinessState,
                    carrierForegroundVisible = carrierForegroundVisible,
                    taskAllocationTruth = taskAllocationTruth,
                    inflightContinuityState = inflightContinuityState,
                    reportedAtMs = reportedAtMs
                ),
                authoritativeParticipationTransitionSequence =
                    authoritativeParticipationTransitionSequence,
                authoritativeParticipationTransitionTrigger =
                    authoritativeParticipationTransitionTrigger,
                authoritativeParticipationTransitionHistory =
                    authoritativeParticipationTransitionHistory,
                reportedAtMs = reportedAtMs,
                reconciliationEpoch = reconciliationEpoch
            )
        }

        private fun alignSessionSnapshot(
            descriptor: RuntimeHostDescriptor,
            sessionSnapshot: AttachedRuntimeHostSessionSnapshot?
        ): AttachedRuntimeHostSessionSnapshot? {
            val snapshot = sessionSnapshot ?: return null
            if (snapshot.deviceId != descriptor.deviceId) return null
            if (snapshot.hostRole != descriptor.formationRole.wireValue) return null
            return snapshot
        }

    }
}

private fun defaultAuthoritativeParticipationState(
    sourceRuntimePosture: String,
    sessionState: AttachedRuntimeSession.State?,
    participationState: RuntimeHostDescriptor.HostParticipationState,
    healthState: ParticipantHealthState,
    readinessState: ParticipantReadinessState,
    activeTaskId: String?,
    activeTaskStatus: ActiveTaskStatus?
): String =
    AndroidAuthoritativeParticipationTruth.derive(
        AndroidAuthoritativeParticipationTruth.DerivationInput(
            crossDeviceEnabled = sourceRuntimePosture == SourceRuntimePosture.JOIN_RUNTIME,
            wsConnected = sessionState == AttachedRuntimeSession.State.ATTACHED,
            registrationInFlight = false,
            capabilityVisible = participationState !=
                RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            readinessSatisfied = readinessState == ParticipantReadinessState.READY,
            continuousIngressReady = readinessState == ParticipantReadinessState.READY,
            runtimeSessionAvailable = sessionState != null,
            fullyAttached = sessionState == AttachedRuntimeSession.State.ATTACHED,
            dispatchEligible = readinessState == ParticipantReadinessState.READY &&
                participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
                healthState == ParticipantHealthState.HEALTHY,
            continuityIntact = true,
            operatorSuspendedOrIsolated = participationState ==
                RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            distributedRuntimeActivity = activeTaskId != null && activeTaskStatus != null
        )
    ).wireValue

enum class RuntimeAvailabilityTruthState(val wireValue: String) {
    AVAILABLE("available"),
    AVAILABLE_DEGRADED("available_degraded"),
    RECOVERING_INTERRUPTED("recovering_interrupted"),
    RECONCILIATION_PENDING("reconciliation_pending"),
    RECOVERY_FAILED_UNAVAILABLE("recovery_failed_unavailable"),
    OFFLINE_OR_UNATTACHED("offline_or_unattached")
}

enum class TaskExecutionVisibilityState(val wireValue: String) {
    IDLE_UNASSIGNED("idle_unassigned"),
    ASSIGNMENT_ACCEPTED_PENDING_EXECUTION("assignment_accepted_pending_execution"),
    EXECUTING_IN_FLIGHT("executing_in_flight"),
    EXECUTING_TERMINALIZING("executing_terminalizing"),
    RESUMED_INFLIGHT_EXECUTION("resumed_inflight_execution"),
    INTERRUPTED_LOST_INFLIGHT_PENDING_CANONICAL("interrupted_lost_inflight_pending_canonical"),
    LOCAL_COMPLETION_PENDING_CANONICAL_RECONCILIATION("local_completion_pending_canonical_reconciliation"),
    EXECUTION_UNAVAILABLE("execution_unavailable")
}

enum class FeatureReadinessTruthState(val wireValue: String) {
    READY_OPERATIONAL("ready_operational"),
    PARTIALLY_READY_DEGRADED("partially_ready_degraded"),
    NOT_READY("not_ready"),
    UNAVAILABLE("unavailable")
}

private fun deriveRuntimeAvailabilityTruthState(
    participationState: RuntimeHostDescriptor.HostParticipationState,
    sessionState: AttachedRuntimeSession.State?,
    healthState: ParticipantHealthState,
    readinessState: ParticipantReadinessState,
    inflightContinuityState: String?
): RuntimeAvailabilityTruthState {
    if (participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE ||
        sessionState != AttachedRuntimeSession.State.ATTACHED
    ) {
        return RuntimeAvailabilityTruthState.OFFLINE_OR_UNATTACHED
    }
    return when (AndroidContinuityRecoveryStateModel.RecoveryPhase.fromWireValue(inflightContinuityState)) {
        AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED ->
            RuntimeAvailabilityTruthState.RECOVERY_FAILED_UNAVAILABLE
        AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING ->
            RuntimeAvailabilityTruthState.RECOVERING_INTERRUPTED
        AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION,
        AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT ->
            RuntimeAvailabilityTruthState.RECONCILIATION_PENDING
        else -> {
            if (healthState == ParticipantHealthState.HEALTHY &&
                readinessState == ParticipantReadinessState.READY
            ) {
                RuntimeAvailabilityTruthState.AVAILABLE
            } else {
                RuntimeAvailabilityTruthState.AVAILABLE_DEGRADED
            }
        }
    }
}

private fun deriveTaskExecutionVisibilityState(
    activeTaskStatus: ActiveTaskStatus?,
    inflightContinuityState: String?,
    runtimeAvailabilityTruthState: RuntimeAvailabilityTruthState
): TaskExecutionVisibilityState {
    if (runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.RECOVERY_FAILED_UNAVAILABLE ||
        runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.OFFLINE_OR_UNATTACHED
    ) {
        return TaskExecutionVisibilityState.EXECUTION_UNAVAILABLE
    }
    return when (activeTaskStatus) {
        ActiveTaskStatus.PENDING ->
            TaskExecutionVisibilityState.ASSIGNMENT_ACCEPTED_PENDING_EXECUTION
        ActiveTaskStatus.RUNNING ->
            TaskExecutionVisibilityState.EXECUTING_IN_FLIGHT
        ActiveTaskStatus.CANCELLING,
        ActiveTaskStatus.FAILING ->
            TaskExecutionVisibilityState.EXECUTING_TERMINALIZING
        null -> when (AndroidContinuityRecoveryStateModel.RecoveryPhase.fromWireValue(inflightContinuityState)) {
            AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT ->
                TaskExecutionVisibilityState.RESUMED_INFLIGHT_EXECUTION
            AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT ->
                TaskExecutionVisibilityState.INTERRUPTED_LOST_INFLIGHT_PENDING_CANONICAL
            AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION,
            AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT ->
                TaskExecutionVisibilityState.LOCAL_COMPLETION_PENDING_CANONICAL_RECONCILIATION
            else ->
                TaskExecutionVisibilityState.IDLE_UNASSIGNED
        }
    }
}

private fun deriveFallbackCapabilityTruthLevel(
    deviceRole: String,
    participationState: RuntimeHostDescriptor.HostParticipationState,
    sourceRuntimePosture: String,
    readinessState: ParticipantReadinessState,
    healthState: ParticipantHealthState
): RuntimeNodeCapabilityTruthLevel {
    val supportTruth = AndroidOperationalDeviceSupport.classify(deviceRole)
    if (participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE) {
        return RuntimeNodeCapabilityTruthLevel.UNAVAILABLE
    }
    if (sourceRuntimePosture != SourceRuntimePosture.JOIN_RUNTIME) {
        return RuntimeNodeCapabilityTruthLevel.CONNECTED_OBSERVABILITY_ONLY
    }
    if (!supportTruth.supportsCrossDeviceExecutionNearPeer) {
        return if (supportTruth.supportsControlPlaneParticipation) {
            RuntimeNodeCapabilityTruthLevel.PARTICIPANT_RUNTIME_CAPABLE
        } else {
            RuntimeNodeCapabilityTruthLevel.CONNECTED_OBSERVABILITY_ONLY
        }
    }
    return if (readinessState == ParticipantReadinessState.READY &&
        healthState == ParticipantHealthState.HEALTHY
    ) {
        RuntimeNodeCapabilityTruthLevel.EXECUTION_CAPABLE
    } else {
        RuntimeNodeCapabilityTruthLevel.PARTICIPANT_RUNTIME_CAPABLE
    }
}

private fun deriveFallbackAutonomyTruthLevel(
    deviceRole: String,
    sourceRuntimePosture: String,
    readinessState: ParticipantReadinessState,
    healthState: ParticipantHealthState
): RuntimeNodeAutonomyTruthLevel {
    if (sourceRuntimePosture != SourceRuntimePosture.JOIN_RUNTIME ||
        !AndroidOperationalDeviceSupport.classify(deviceRole).supportsCrossDeviceExecutionNearPeer
    ) {
        return RuntimeNodeAutonomyTruthLevel.OBSERVATION_ONLY
    }
    return RuntimeNodeAutonomyTruthLevel.ASSISTED_PARTICIPANT
}

private fun deriveFeatureReadinessTruthState(
    deviceRole: String,
    participationState: RuntimeHostDescriptor.HostParticipationState,
    readinessState: ParticipantReadinessState,
    healthState: ParticipantHealthState,
    runtimeAvailabilityTruthState: RuntimeAvailabilityTruthState
): FeatureReadinessTruthState {
    val supportTruth = AndroidOperationalDeviceSupport.classify(deviceRole)
    if (participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE ||
        runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.RECOVERY_FAILED_UNAVAILABLE ||
        runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.OFFLINE_OR_UNATTACHED
    ) {
        return FeatureReadinessTruthState.UNAVAILABLE
    }
    if (!supportTruth.supportsCrossDeviceExecutionNearPeer) {
        return if (supportTruth.supportsControlPlaneParticipation) {
            FeatureReadinessTruthState.PARTIALLY_READY_DEGRADED
        } else {
            FeatureReadinessTruthState.UNAVAILABLE
        }
    }
    return when {
        readinessState == ParticipantReadinessState.READY &&
            healthState == ParticipantHealthState.HEALTHY &&
            runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.AVAILABLE ->
            FeatureReadinessTruthState.READY_OPERATIONAL
        readinessState == ParticipantReadinessState.READY_WITH_FALLBACK ||
            healthState == ParticipantHealthState.DEGRADED ||
            runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.RECONCILIATION_PENDING ||
            runtimeAvailabilityTruthState == RuntimeAvailabilityTruthState.RECOVERING_INTERRUPTED ->
            FeatureReadinessTruthState.PARTIALLY_READY_DEGRADED
        else -> FeatureReadinessTruthState.NOT_READY
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
     * [ReconciliationSignal.Kind.TASK_CANCELLED] will follow.
     */
    CANCELLING("cancelling"),

    /**
     * Task execution encountered an error condition.  Android will emit a
     * [ReconciliationSignal] with [ReconciliationSignal.Kind.TASK_FAILED] immediately.
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
