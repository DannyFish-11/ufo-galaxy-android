package com.ufo.galaxy.capability

import com.ufo.galaxy.runtime.RuntimeHostDescriptor

/**
 * **Canonical Android scheduling eligibility** (PR-6, post-#533 dual-repo runtime
 * unification master plan — Canonical Device Capability & Scheduling Basis).
 *
 * Derives a structured placement decision from an [AndroidCapabilityVector] so that
 * the main runtime can choose an execution surface using a stable, machine-readable
 * basis rather than interpreting raw metadata flags.
 *
 * ## Scope
 * This class produces the *input signals* a scheduler would use.  It does **not**
 * implement a full policy engine or dynamic load-balancing algorithm — that is
 * deferred to a later PR.  Placement preference is determined purely from the
 * device's current capability dimensions and participation state.
 *
 * ## [PlacementPreference]
 * | Preference              | Condition |
 * |-------------------------|-----------|
 * | [PlacementPreference.LOCAL_PREFERRED]   | Local execution eligible; cross-device not eligible. |
 * | [PlacementPreference.REMOTE_PREFERRED]  | Cross-device eligible; local execution not eligible. |
 * | [PlacementPreference.INDIFFERENT]       | Both local and cross-device eligible. |
 * | [PlacementPreference.INELIGIBLE]        | Neither local nor cross-device eligible. |
 *
 * ## Obtaining an instance
 * ```kotlin
 * val eligibility = SchedulingEligibility.from(capabilityVector)
 * ```
 *
 * @property canAcceptLocalTasks        `true` when local autonomous execution is available.
 * @property canAcceptCrossDeviceTasks  `true` when cross-device participation is available.
 * @property canAcceptParallelSubtasks  `true` when parallel subtask routing is available.
 * @property placementPreference        Derived placement signal for the main runtime.
 * @property reason                     Stable [PlacementPreference.wireValue] string; safe to
 *                                      include in telemetry and wire payloads.
 */
data class SchedulingEligibility(
    val canAcceptLocalTasks: Boolean,
    val canAcceptCrossDeviceTasks: Boolean,
    val canAcceptParallelSubtasks: Boolean,
    val placementPreference: PlacementPreference,
    val reason: String
) {

    /**
     * Placement preference signal derived from an [AndroidCapabilityVector].
     *
     * The main runtime uses this to decide whether to route a task locally,
     * cross-device, or to flag the device as unavailable.
     *
     * @property wireValue Stable lowercase string used in wire metadata payloads.
     */
    enum class PlacementPreference(val wireValue: String) {
        /**
         * The device prefers local execution (local eligible, remote not eligible).
         * Tasks should be run on this device when possible.
         */
        LOCAL_PREFERRED("local_preferred"),

        /**
         * The device prefers cross-device participation (remote eligible, local not eligible).
         * Tasks should be routed to this device via the cross-device path.
         */
        REMOTE_PREFERRED("remote_preferred"),

        /**
         * Both local execution and cross-device participation are available.
         * The main runtime may choose either path; further policy is deferred.
         */
        INDIFFERENT("indifferent"),

        /**
         * The device is not eligible for any task placement.
         * Typical causes: [RuntimeHostDescriptor.HostParticipationState] is not ACTIVE,
         * or required capabilities are not satisfied.
         */
        INELIGIBLE("ineligible");

        companion object {
            /**
             * Parses [value] to a [PlacementPreference], or returns [INELIGIBLE] for
             * unknown values.
             *
             * @param value Wire string from a metadata payload; may be null.
             */
            fun fromValue(value: String?): PlacementPreference =
                entries.firstOrNull { it.wireValue == value } ?: INELIGIBLE
        }
    }

    companion object {

        /**
         * Derives a [SchedulingEligibility] from a fully-constructed [AndroidCapabilityVector].
         *
         * Placement preference is resolved as follows:
         *  1. If neither local nor cross-device is eligible → [PlacementPreference.INELIGIBLE].
         *  2. If only local is eligible                    → [PlacementPreference.LOCAL_PREFERRED].
         *  3. If only cross-device is eligible             → [PlacementPreference.REMOTE_PREFERRED].
         *  4. If both are eligible                         → [PlacementPreference.INDIFFERENT].
         *
         * @param vector Fully-constructed [AndroidCapabilityVector] representing the current
         *               device state.
         */
        fun from(vector: AndroidCapabilityVector): SchedulingEligibility {
            val localOk = vector.isEligibleForLocalExecution
            val remoteOk = vector.isEligibleForCrossDeviceParticipation
            val parallelOk = vector.isEligibleForParallelSubtask

            val preference = when {
                !localOk && !remoteOk -> PlacementPreference.INELIGIBLE
                localOk && !remoteOk -> PlacementPreference.LOCAL_PREFERRED
                !localOk && remoteOk -> PlacementPreference.REMOTE_PREFERRED
                else -> PlacementPreference.INDIFFERENT
            }

            return SchedulingEligibility(
                canAcceptLocalTasks = localOk,
                canAcceptCrossDeviceTasks = remoteOk,
                canAcceptParallelSubtasks = parallelOk,
                placementPreference = preference,
                reason = preference.wireValue
            )
        }
    }
}
