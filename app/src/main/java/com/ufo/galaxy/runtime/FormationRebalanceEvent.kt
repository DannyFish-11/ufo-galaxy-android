package com.ufo.galaxy.runtime

/**
 * PR-2 — Sealed class representing a formation rebalance trigger event on the Android side.
 *
 * Formation rebalance events model the conditions that should prompt the Android runtime
 * to reassess its participation posture, role expectations, or recovery continuity strategy
 * within a multi-device Galaxy formation.
 *
 * ## Design intent
 *
 * This type is **additive and observability-oriented**. It does not introduce a new lifecycle
 * authority or replace existing [RuntimeController] / [ReconnectRecoveryState] ownership.
 * Instead, it provides an explicit, typed surface through which callers can understand *why*
 * a rebalance was triggered and react accordingly — e.g. by updating the UI, logging operator
 * telemetry, or forwarding the event to the center-side formation coordinator.
 *
 * ## Event taxonomy
 *
 * | Subclass                    | Meaning |
 * |-----------------------------|---------|
 * | [ReadinessChanged]          | The Android participant's readiness state changed in a way that may affect formation eligibility. |
 * | [ParticipantLost]           | A known formation participant became unreachable; formation may need to operate in degraded mode. |
 * | [ParticipantRejoined]       | A previously lost participant is back; formation may be able to restore full capacity. |
 * | [RoleReassignmentRequested] | An external signal requests that this device reassess its [RuntimeHostDescriptor.FormationRole]. |
 * | [DegradedFormationDetected] | The formation is operating below full capacity; degraded-continuation policy should apply. |
 * | [RecoveryCompleted]         | A prior degraded/recovery state has been resolved; runtime can return to normal participation. |
 *
 * ## Wire values
 *
 * Each event exposes a [wireValue] string for structured log entries and diagnostics payloads.
 * These values are stable and must not be renamed after this PR ships.
 *
 * @property wireValue Stable lowercase string identifying the event kind in telemetry payloads.
 */
sealed class FormationRebalanceEvent(open val wireValue: String) {

    /**
     * The Android participant's readiness or participation state changed in a way that may
     * affect its eligibility to execute tasks within the formation.
     *
     * @property previousReadiness  The [ParticipantReadinessState] before the change.
     * @property currentReadiness   The [ParticipantReadinessState] after the change.
     * @property previousParticipation The [RuntimeHostDescriptor.HostParticipationState] before the change.
     * @property currentParticipation  The [RuntimeHostDescriptor.HostParticipationState] after the change.
     * @property trigger            Machine-readable description of what caused the change
     *                              (e.g. `"ws_disconnect"`, `"session_invalidation"`,
     *                              `"readiness_check_failure"`, `"user_stop"`).
     */
    data class ReadinessChanged(
        val previousReadiness: ParticipantReadinessState,
        val currentReadiness: ParticipantReadinessState,
        val previousParticipation: RuntimeHostDescriptor.HostParticipationState,
        val currentParticipation: RuntimeHostDescriptor.HostParticipationState,
        val trigger: String
    ) : FormationRebalanceEvent(WIRE_READINESS_CHANGED)

    /**
     * A known formation participant became unreachable or lost.
     *
     * The Android runtime should apply a degraded-continuation strategy for any in-flight
     * or pending tasks that required the lost participant.
     *
     * @property lostParticipantId   Stable participant identifier of the lost node.
     * @property lostDeviceId        Device identifier of the lost participant.
     * @property detachCause         Wire value of the cause from [AttachedRuntimeSession.DetachCause]
     *                               (e.g. `"disconnect"`, `"disable"`, `"invalidation"`).
     * @property affectedMeshId      Optional: the mesh/staged-mesh session identifier affected
     *                               by the loss, if known.
     */
    data class ParticipantLost(
        val lostParticipantId: String,
        val lostDeviceId: String,
        val detachCause: String,
        val affectedMeshId: String? = null
    ) : FormationRebalanceEvent(WIRE_PARTICIPANT_LOST)

    /**
     * A previously lost formation participant has successfully rejoined.
     *
     * The runtime may be able to restore full formation capacity; the formation coordinator
     * should evaluate whether role reassignment or task redistribution is warranted.
     *
     * @property rejoinedParticipantId  Stable participant identifier of the rejoining node.
     * @property rejoinedDeviceId       Device identifier of the rejoining participant.
     * @property sessionContinuityEpoch The [DurableSessionContinuityRecord.sessionContinuityEpoch]
     *                                  of the new session, if durable continuity was preserved.
     * @property affectedMeshId         Optional: mesh session identifier that was affected.
     */
    data class ParticipantRejoined(
        val rejoinedParticipantId: String,
        val rejoinedDeviceId: String,
        val sessionContinuityEpoch: Int?,
        val affectedMeshId: String? = null
    ) : FormationRebalanceEvent(WIRE_PARTICIPANT_REJOINED)

    /**
     * An external signal requests that this device reassess or accept a new
     * [RuntimeHostDescriptor.FormationRole].
     *
     * The Android runtime should evaluate whether the requested role is acceptable given the
     * current [RuntimeHostDescriptor.HostParticipationState] and readiness signals, then
     * acknowledge or decline via [FormationParticipationRebalancer.evaluateRoleReassignment].
     *
     * @property requestedRole         The role being proposed by the formation coordinator.
     * @property previousRole          The role this device was previously operating under.
     * @property requestingCoordinator Identifier of the entity requesting the reassignment.
     * @property sessionId             Optional session identity scoping the reassignment.
     */
    data class RoleReassignmentRequested(
        val requestedRole: RuntimeHostDescriptor.FormationRole,
        val previousRole: RuntimeHostDescriptor.FormationRole,
        val requestingCoordinator: String,
        val sessionId: String? = null
    ) : FormationRebalanceEvent(WIRE_ROLE_REASSIGNMENT_REQUESTED)

    /**
     * The formation is operating in a degraded state: one or more expected participants are
     * absent or unreachable, and the remaining formation is proceeding below full capacity.
     *
     * The Android runtime should apply degraded-continuation semantics (e.g. accepting
     * additional task scope, adjusting readiness signals) until the formation recovers.
     *
     * @property presentParticipantCount  Number of participants currently present.
     * @property expectedParticipantCount Number of participants the formation expected.
     * @property absentParticipantIds     Identifiers of the absent participants, if known.
     * @property degradedMeshId           Optional: mesh session identifier in degraded state.
     */
    data class DegradedFormationDetected(
        val presentParticipantCount: Int,
        val expectedParticipantCount: Int,
        val absentParticipantIds: List<String> = emptyList(),
        val degradedMeshId: String? = null
    ) : FormationRebalanceEvent(WIRE_DEGRADED_FORMATION_DETECTED)

    /**
     * A prior degraded or recovery state has been fully resolved.
     *
     * The formation has returned to expected capacity; the Android runtime can restore
     * normal participation semantics (standard readiness signals, original role, etc.).
     *
     * @property restoredParticipantCount  Number of participants now present.
     * @property recoveryTrigger           Machine-readable description of what resolved the
     *                                     degraded state (e.g. `"participant_rejoined"`,
     *                                     `"role_reassignment_accepted"`, `"manual_recovery"`).
     * @property affectedMeshId            Optional: mesh session identifier that was recovered.
     */
    data class RecoveryCompleted(
        val restoredParticipantCount: Int,
        val recoveryTrigger: String,
        val affectedMeshId: String? = null
    ) : FormationRebalanceEvent(WIRE_RECOVERY_COMPLETED)

    // ── Companion / wire-value constants ──────────────────────────────────────

    companion object {
        /** Wire value for [ReadinessChanged] events. */
        const val WIRE_READINESS_CHANGED = "formation_readiness_changed"

        /** Wire value for [ParticipantLost] events. */
        const val WIRE_PARTICIPANT_LOST = "formation_participant_lost"

        /** Wire value for [ParticipantRejoined] events. */
        const val WIRE_PARTICIPANT_REJOINED = "formation_participant_rejoined"

        /** Wire value for [RoleReassignmentRequested] events. */
        const val WIRE_ROLE_REASSIGNMENT_REQUESTED = "formation_role_reassignment_requested"

        /** Wire value for [DegradedFormationDetected] events. */
        const val WIRE_DEGRADED_FORMATION_DETECTED = "formation_degraded_detected"

        /** Wire value for [RecoveryCompleted] events. */
        const val WIRE_RECOVERY_COMPLETED = "formation_recovery_completed"

        /** All stable wire values — useful for validation in tests and schema registries. */
        val ALL_WIRE_VALUES: Set<String> = setOf(
            WIRE_READINESS_CHANGED,
            WIRE_PARTICIPANT_LOST,
            WIRE_PARTICIPANT_REJOINED,
            WIRE_ROLE_REASSIGNMENT_REQUESTED,
            WIRE_DEGRADED_FORMATION_DETECTED,
            WIRE_RECOVERY_COMPLETED
        )
    }
}
