package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.AppSettings

/**
 * Canonical Android-side runtime host identity descriptor.
 *
 * Models Android's participation as a **first-class runtime host** in the dual-repo
 * system. This is distinct from mere connected-device presence: a device that holds a
 * [RuntimeHostDescriptor] has explicitly registered itself in the runtime host pool and
 * may receive takeover requests, join formation groups, and be projected into by the
 * main OpenClawd runtime.
 *
 * ## PR-5 role
 * Part of the post-PR-#533 dual-repo runtime host unification track (Android PR 5):
 * promote Android to a first-class runtime host in registration, formation, and
 * projection. This descriptor is the Android-side evidence of that promotion.
 *
 * ## Key distinctions from connected-device presence
 * - A *connected device* is registered with the gateway and can receive tasks.
 * - A *first-class runtime host* additionally declares its [formationRole],
 *   [participationState], and [hostCapabilities] so the gateway can include it in
 *   runtime formations, project session-truth onto it, and delegate takeover requests.
 *
 * ## Wire representation
 * The descriptor is surfaced via [toRegistrationMap], which produces a flat map of
 * string/boolean fields merged into the AIP v3 `device_register` and
 * `capability_report` metadata payloads. Fields are additive: they do not conflict
 * with the 8 legacy [com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS].
 *
 * @param hostId              Composite identifier: `"deviceId:runtimeSessionId"`.
 * @param runtimeSessionId    Stable per-app-launch session identifier.
 * @param formationRole       How this device participates in multi-device formations.
 *                            Defaults to [FormationRole.EXECUTOR].
 * @param participationState  Current lifecycle state of the host in the runtime pool.
 *                            Defaults to [HostParticipationState.REGISTERED].
 * @param hostCapabilities    Capability names advertised as a runtime host. Derived
 *                            from live [AppSettings] by [fromSettings].
 */
data class RuntimeHostDescriptor(
    val hostId: String,
    val runtimeSessionId: String,
    val formationRole: FormationRole = FormationRole.EXECUTOR,
    val participationState: HostParticipationState = HostParticipationState.REGISTERED,
    val hostCapabilities: List<String> = emptyList()
) {

    /**
     * Formation role this Android device takes within a multi-device runtime host group.
     *
     * | Role          | Wire value      | Meaning                                              |
     * |---------------|-----------------|------------------------------------------------------|
     * | EXECUTOR      | `"executor"`    | Receives and executes subtasks assigned by the group.|
     * | COORDINATOR   | `"coordinator"` | Coordinates subtask dispatch within the group.       |
     * | OBSERVER      | `"observer"`    | Observes session truth without executing.            |
     *
     * Gateways and main-repo runtimes that do not yet model formation roles will
     * ignore this field gracefully.
     */
    enum class FormationRole(val wireValue: String) {
        EXECUTOR("executor"),
        COORDINATOR("coordinator"),
        OBSERVER("observer");

        companion object {
            /**
             * Derives the appropriate [FormationRole] from [AppSettings.deviceRole].
             * Devices with role `"hub"` are registered as coordinators; all others
             * as executors — reflecting that Android phones/tablets are task executors
             * in the typical multi-device cluster topology.
             */
            fun fromDeviceRole(deviceRole: String): FormationRole =
                if (deviceRole == "hub") COORDINATOR else EXECUTOR

            /** Parses a wire value; returns [EXECUTOR] for any unrecognised string. */
            fun fromWireValue(value: String?): FormationRole =
                entries.firstOrNull { it.wireValue == value } ?: EXECUTOR
        }
    }

    /**
     * Lifecycle state of this device in the runtime host pool.
     *
     * | State        | Wire value       | Meaning                                           |
     * |--------------|------------------|---------------------------------------------------|
     * | REGISTERED   | `"registered"`   | Announced but not yet executing.                  |
     * | ACTIVE       | `"active"`       | Currently executing a task as part of a formation.|
     * | SUSPENDED    | `"suspended"`    | Temporarily unavailable (e.g. low battery).       |
     * | WITHDRAWN    | `"withdrawn"`    | Explicitly removed from the host pool.            |
     */
    enum class HostParticipationState(val wireValue: String) {
        REGISTERED("registered"),
        ACTIVE("active"),
        SUSPENDED("suspended"),
        WITHDRAWN("withdrawn");

        companion object {
            /** Parses a wire value; returns [REGISTERED] for any unrecognised string. */
            fun fromWireValue(value: String?): HostParticipationState =
                entries.firstOrNull { it.wireValue == value } ?: REGISTERED
        }
    }

    // ── Computed properties ───────────────────────────────────────────────────

    /**
     * `true` when this device is eligible to receive a task assignment or takeover.
     *
     * Eligibility requires the device to be in either [HostParticipationState.REGISTERED]
     * or [HostParticipationState.ACTIVE]; suspended or withdrawn hosts are not eligible.
     */
    val isEligibleForTaskAssignment: Boolean
        get() = participationState == HostParticipationState.REGISTERED ||
            participationState == HostParticipationState.ACTIVE

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Returns a flat [Map] suitable for merging into the AIP v3 `device_register` or
     * `capability_report` metadata payload.
     *
     * | Key                        | Type    | Value                                      |
     * |----------------------------|---------|--------------------------------------------|
     * | `runtime_host_eligible`    | Boolean | Always `true` when this descriptor exists. |
     * | `host_formation_role`      | String  | [FormationRole.wireValue].                 |
     * | `host_participation_state` | String  | [HostParticipationState.wireValue].        |
     * | `host_capabilities`        | String  | Comma-separated [hostCapabilities].        |
     *
     * These keys are additive and do not conflict with the 8 legacy required metadata
     * keys in [com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS]. Gateways
     * and main-repo runtimes that do not yet understand them will ignore them safely.
     */
    fun toRegistrationMap(): Map<String, Any> = mapOf(
        "runtime_host_eligible" to true,
        "host_formation_role" to formationRole.wireValue,
        "host_participation_state" to participationState.wireValue,
        "host_capabilities" to hostCapabilities.joinToString(",")
    )

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {

        /**
         * The set of keys emitted by [toRegistrationMap].
         * Useful for validation in tests and logging.
         */
        val REGISTRATION_MAP_KEYS: Set<String> = setOf(
            "runtime_host_eligible",
            "host_formation_role",
            "host_participation_state",
            "host_capabilities"
        )

        /**
         * Derives a [RuntimeHostDescriptor] from the current [AppSettings] and session context.
         *
         * **Host capabilities** are built from the live settings flags:
         * - `goal_execution` — when [AppSettings.goalExecutionEnabled] is `true`.
         * - `local_model_inference` — when [AppSettings.localModelEnabled] is `true`.
         * - `parallel_subtask_execution` — when [AppSettings.parallelExecutionEnabled] is `true`.
         * - `accessibility_control` — when [AppSettings.accessibilityReady] is `true`.
         *
         * **Formation role** is derived from [AppSettings.deviceRole] via
         * [FormationRole.fromDeviceRole]: `"hub"` → [FormationRole.COORDINATOR],
         * all others → [FormationRole.EXECUTOR].
         *
         * The initial [participationState] is always [HostParticipationState.REGISTERED];
         * it transitions to [HostParticipationState.ACTIVE] when the device accepts a
         * takeover request (managed by [com.ufo.galaxy.runtime.RuntimeController]).
         *
         * @param deviceId         The device's unique identifier.
         * @param runtimeSessionId The stable per-app-launch session identifier
         *                         (from [com.ufo.galaxy.UFOGalaxyApplication.runtimeSessionId]).
         * @param settings         Current application settings snapshot.
         */
        fun fromSettings(
            deviceId: String,
            runtimeSessionId: String,
            settings: AppSettings
        ): RuntimeHostDescriptor {
            val caps = buildList {
                if (settings.goalExecutionEnabled) add("goal_execution")
                if (settings.localModelEnabled) add("local_model_inference")
                if (settings.parallelExecutionEnabled) add("parallel_subtask_execution")
                if (settings.accessibilityReady) add("accessibility_control")
            }

            return RuntimeHostDescriptor(
                hostId = "$deviceId:$runtimeSessionId",
                runtimeSessionId = runtimeSessionId,
                formationRole = FormationRole.fromDeviceRole(settings.deviceRole),
                participationState = HostParticipationState.REGISTERED,
                hostCapabilities = caps
            )
        }
    }
}
