package com.ufo.galaxy.runtime

/**
 * PR-04 — Android-side barrier participation state.
 *
 * Models Android's position in V2-coordinated barrier/release cycles for mesh execution.
 * Android is not a barrier authority — V2 owns barrier coordination.  This enum models
 * the Android participant's *response* to barrier signals: whether it is waiting, has
 * been released, has timed out, or the barrier concept is not applicable to the current
 * participation context.
 *
 * ## Motivation
 *
 * Prior to PR-04, [AndroidMeshParticipationContract] explicitly declared
 * `barrier_coordination` as [HybridParticipantCapability.SupportLevel.NOT_YET_IMPLEMENTED].
 * This was correct — Android is not a barrier coordinator — but it left a gap: Android
 * had no way to report its barrier wait/release *response* state to V2 for operator
 * observability and for V2's barrier completion tracking.
 *
 * [BarrierParticipationState] closes this gap.  Android does not coordinate barriers,
 * but it can report:
 *  - [WAITING]: "I have reached the barrier and am waiting for V2 to release me."
 *  - [RELEASED]: "V2 has released the barrier; I am proceeding."
 *  - [TIMED_OUT]: "I waited but did not receive a release signal in time."
 *
 * ## Wire values
 *
 * | [BarrierParticipationState] | Wire value         | Description |
 * |-----------------------------|--------------------|-------------|
 * | [NOT_APPLICABLE]            | `not_applicable`   | Barrier not relevant to current participation context |
 * | [WAITING]                   | `waiting`          | Waiting for V2 barrier release |
 * | [RELEASED]                  | `released`         | V2 released the barrier; proceeding |
 * | [TIMED_OUT]                 | `timed_out`        | Barrier wait exceeded local timeout |
 *
 * @property wireValue Stable lowercase wire value for diagnostics and state snapshots.
 * @property description Human-readable description.
 */
enum class BarrierParticipationState(
    val wireValue: String,
    val description: String
) {

    /**
     * Barrier coordination is not applicable to the current participation context.
     * This is the default state when no mesh session is active or when the current
     * session does not involve a barrier rendezvous point.
     */
    NOT_APPLICABLE(
        wireValue = "not_applicable",
        description = "Barrier coordination not applicable to current participation context"
    ),

    /**
     * Android has reached a barrier point and is waiting for the V2 coordinator to
     * release the barrier before proceeding with further execution.
     *
     * V2 should receive this state via [DeviceStateSnapshotPayload.barrier_participation_state]
     * and track it as part of barrier completion evaluation.
     */
    WAITING(
        wireValue = "waiting",
        description = "Waiting at barrier for V2 coordinator release signal"
    ),

    /**
     * V2 has released the barrier and Android is proceeding with execution.
     * This is a transient state that resolves to [NOT_APPLICABLE] once the execution
     * following the barrier completes.
     */
    RELEASED(
        wireValue = "released",
        description = "Barrier released by V2 coordinator; execution proceeding"
    ),

    /**
     * Android waited for a barrier release signal but did not receive it within the
     * local timeout period.  The barrier wait was abandoned.
     *
     * V2 should treat this as an indication that the participant may have diverged
     * from the expected barrier rendezvous timeline.
     */
    TIMED_OUT(
        wireValue = "timed_out",
        description = "Barrier wait exceeded local timeout; barrier wait abandoned"
    );

    companion object {

        /**
         * Returns the [BarrierParticipationState] with the given [wireValue], or `null`
         * if no match is found.
         */
        fun fromWireValue(value: String?): BarrierParticipationState? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Returns `true` when [state] indicates Android is in an active barrier position
         * (either waiting or just released).
         */
        fun isActive(state: BarrierParticipationState): Boolean =
            state == WAITING || state == RELEASED

        /** All stable wire values for schema validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
