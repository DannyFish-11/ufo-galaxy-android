package com.ufo.galaxy.runtime

/**
 * **Canonical Android runtime-host identity descriptor** (PR-5, post-#533 dual-repo
 * runtime unification master plan).
 *
 * Before PR-5 the Android side identified itself only as a generic "Android_Agent"
 * device endpoint.  [RuntimeHostDescriptor] elevates that identity to a first-class
 * *runtime host*: a structured declaration that this Android instance is capable of
 * participating as a formal executor surface in the multi-device Galaxy formation,
 * not merely as a transport endpoint.
 *
 * ## Key design decisions
 *
 * ### [FormationRole]
 * Expresses the device's *structural* place in the formation:
 *  - [FormationRole.PRIMARY]   – sole or lead execution surface for a session.
 *  - [FormationRole.SECONDARY] – auxiliary surface that handles overflow or
 *    parallel subtasks directed from a PRIMARY node.
 *  - [FormationRole.SATELLITE] – lightweight participant; may receive task
 *    fragments but is not a full execution surface.
 *
 * The role is configured at startup and remains stable for the lifetime of the
 * application process.  It is included in the `device_register` and
 * `capability_report` handshake payloads so the gateway can route tasks
 * appropriately.
 *
 * ### [HostParticipationState]
 * Expresses the device's *runtime* lifecycle readiness:
 *  - [HostParticipationState.ACTIVE]   – ready and willing to accept new tasks.
 *  - [HostParticipationState.STANDBY]  – running but not currently accepting new tasks
 *    (e.g. another takeover is in progress or the device is warming up).
 *  - [HostParticipationState.DRAINING] – completing in-flight work; will not accept
 *    new tasks until back to [HostParticipationState.ACTIVE].
 *  - [HostParticipationState.INACTIVE] – not participating; cross-device is disabled
 *    or required readiness conditions are not met.
 *
 * ### Wire representation
 * When merged into the AIP v3 handshake both [formationRole] and [participationState]
 * travel as their [FormationRole.wireValue] / [HostParticipationState.wireValue] strings
 * under the keys `runtime_host_formation_role` and `runtime_host_participation_state`
 * in the `metadata` object of `device_register` and `capability_report`.
 *
 * ### Scope
 * PR-5 introduces the *model and representation* layer only.  Full capability
 * scheduling (dynamic role promotion, load-based state transitions) is deferred to a
 * later PR.
 *
 * ## Obtaining an instance
 * Use the [of] factory or the [Builder] DSL:
 * ```kotlin
 * val descriptor = RuntimeHostDescriptor.of(
 *     deviceId    = Build.SERIAL,
 *     deviceRole  = settings.deviceRole,
 *     formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
 * )
 * ```
 *
 * @property hostId             Stable, unique identifier for this runtime host instance.
 *                              Generated once per application process (UUID v4).
 * @property deviceId           Hardware-level device identifier (e.g. Build.MANUFACTURER + model).
 * @property deviceRole         Logical device role from [com.ufo.galaxy.data.AppSettings.deviceRole]
 *                              (e.g. "phone", "tablet").
 * @property formationRole      Structural role in the multi-device formation.
 * @property participationState Current runtime lifecycle readiness of this host.
 * @property registeredAtMs     Epoch-millisecond timestamp when this descriptor was created.
 *                              Used by the gateway for TTL and staleness detection.
 */
data class RuntimeHostDescriptor(
    val hostId: String,
    val deviceId: String,
    val deviceRole: String,
    val formationRole: FormationRole,
    val participationState: HostParticipationState,
    val registeredAtMs: Long = System.currentTimeMillis()
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Structural role of this Android device within the multi-device formation.
     *
     * The role is stable for the lifetime of the application process and is set at
     * startup based on device configuration.
     *
     * @property wireValue Stable lowercase string used in JSON payloads.
     */
    enum class FormationRole(val wireValue: String) {
        /**
         * This device is the primary (lead) execution surface.
         * It may coordinate subtasks to [SECONDARY] nodes.
         */
        PRIMARY("primary"),

        /**
         * This device is a secondary (auxiliary) execution surface.
         * It executes subtasks delegated by a [PRIMARY] node.
         */
        SECONDARY("secondary"),

        /**
         * This device is a lightweight satellite participant.
         * It may receive task fragments but is not a full execution surface.
         */
        SATELLITE("satellite");

        companion object {
            /**
             * Parses [value] to a [FormationRole], returning [DEFAULT] for unknown values.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): FormationRole =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default role when not explicitly configured. */
            val DEFAULT: FormationRole = PRIMARY
        }
    }

    /**
     * Runtime lifecycle readiness state of this Android host.
     *
     * Unlike [FormationRole], which is static, [HostParticipationState] reflects the
     * *current* execution-surface availability and may change during the app lifecycle.
     *
     * @property wireValue Stable lowercase string used in JSON payloads.
     */
    enum class HostParticipationState(val wireValue: String) {
        /** Ready and willing to accept new tasks from the gateway. */
        ACTIVE("active"),

        /**
         * Running but not accepting new tasks.
         * Typical causes: warm-up in progress, another takeover is active.
         */
        STANDBY("standby"),

        /**
         * Completing in-flight work; no new tasks will be accepted until
         * the device transitions back to [ACTIVE].
         */
        DRAINING("draining"),

        /**
         * Not participating in the runtime.
         * Typical causes: cross-device is disabled, readiness checks have failed.
         */
        INACTIVE("inactive");

        companion object {
            /**
             * Parses [value] to a [HostParticipationState], returning [DEFAULT] for unknown values.
             *
             * @param value Wire string from a JSON payload; may be null.
             */
            fun fromValue(value: String?): HostParticipationState =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default state before the first explicit state assignment. */
            val DEFAULT: HostParticipationState = INACTIVE
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this host is in a state that allows accepting new tasks
     * ([HostParticipationState.ACTIVE]).
     */
    val isAcceptingTasks: Boolean
        get() = participationState == HostParticipationState.ACTIVE

    /**
     * Returns `true` when this host holds the [FormationRole.PRIMARY] role.
     */
    val isPrimary: Boolean
        get() = formationRole == FormationRole.PRIMARY

    /**
     * Returns `true` when this host is in a participation state that is actively contributing
     * to the formation (ACTIVE or STANDBY), regardless of formation role.
     *
     * Useful for formation-level checks that need to distinguish "present in formation"
     * (ACTIVE/STANDBY) from "absent from formation" (INACTIVE/DRAINING).
     *
     * PR-2: Added to support formation rebalance evaluation in
     * [FormationParticipationRebalancer][com.ufo.galaxy.runtime.FormationParticipationRebalancer].
     */
    val isFormationPresent: Boolean
        get() = participationState == HostParticipationState.ACTIVE
            || participationState == HostParticipationState.STANDBY

    /**
     * Returns a copy of this descriptor with [participationState] set to [newState].
     *
     * Use this to produce updated descriptors on lifecycle transitions without
     * mutating the original.
     *
     * @param newState The new participation state to apply.
     */
    fun withState(newState: HostParticipationState): RuntimeHostDescriptor =
        copy(participationState = newState)

    /**
     * Returns a copy of this descriptor with [formationRole] set to [newRole].
     *
     * Use this to produce an updated descriptor when a role reassignment is accepted via
     * [FormationParticipationRebalancer.evaluateRoleReassignment][com.ufo.galaxy.runtime.FormationParticipationRebalancer.evaluateRoleReassignment].
     * The caller is responsible for propagating the updated descriptor to
     * [GalaxyWebSocketClient.setRuntimeHostDescriptor] so that the Gateway observes the new role.
     *
     * PR-2: Added to support role reassignment in the formation rebalance path.
     *
     * @param newRole The new [FormationRole] to apply.
     */
    fun withRole(newRole: FormationRole): RuntimeHostDescriptor =
        copy(formationRole = newRole)

    /**
     * Builds the canonical metadata map that is merged into the AIP v3 `device_register`
     * and `capability_report` handshake payloads.
     *
     * Keys:
     *  - `runtime_host_id`                  – stable host instance identifier.
     *  - `runtime_host_formation_role`       – [FormationRole.wireValue].
     *  - `runtime_host_participation_state`  – [HostParticipationState.wireValue].
     *  - `runtime_host_registered_at_ms`     – epoch-ms registration timestamp.
     *
     * The caller is responsible for merging this map into the existing metadata
     * object before sending; no existing metadata keys are overwritten except
     * the four runtime-host-specific keys defined here.
     */
    fun toMetadataMap(): Map<String, Any> = mapOf(
        KEY_HOST_ID to hostId,
        KEY_FORMATION_ROLE to formationRole.wireValue,
        KEY_PARTICIPATION_STATE to participationState.wireValue,
        KEY_REGISTERED_AT_MS to registeredAtMs
    )

    // ── Factory / companion ───────────────────────────────────────────────────

    companion object {

        // ── Metadata key constants ────────────────────────────────────────────

        /** Metadata key for the stable runtime host instance identifier. */
        const val KEY_HOST_ID = "runtime_host_id"

        /** Metadata key for the [FormationRole.wireValue] string. */
        const val KEY_FORMATION_ROLE = "runtime_host_formation_role"

        /** Metadata key for the [HostParticipationState.wireValue] string. */
        const val KEY_PARTICIPATION_STATE = "runtime_host_participation_state"

        /** Metadata key for the epoch-ms registration timestamp. */
        const val KEY_REGISTERED_AT_MS = "runtime_host_registered_at_ms"

        /**
         * Convenience factory for the most common case: a device that acts as the
         * primary host and starts in the [HostParticipationState.INACTIVE] state
         * (waiting for [RuntimeController][com.ufo.galaxy.runtime.RuntimeController]
         * to activate it).
         *
         * @param deviceId    Hardware device identifier (e.g. Build.MANUFACTURER + model).
         * @param deviceRole  Logical device role from settings (e.g. "phone", "tablet").
         * @param formationRole Structural role; defaults to [FormationRole.PRIMARY].
         * @param hostId      Stable per-process UUID; auto-generated if not provided.
         */
        fun of(
            deviceId: String,
            deviceRole: String,
            formationRole: FormationRole = FormationRole.DEFAULT,
            hostId: String = java.util.UUID.randomUUID().toString()
        ): RuntimeHostDescriptor = RuntimeHostDescriptor(
            hostId = hostId,
            deviceId = deviceId,
            deviceRole = deviceRole,
            formationRole = formationRole,
            participationState = HostParticipationState.INACTIVE
        )
    }
}
