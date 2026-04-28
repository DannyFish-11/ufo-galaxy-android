package com.ufo.galaxy.runtime

/**
 * PR-70 (Android) — Enum enumerating the five participant verification scenarios that
 * a [RealDeviceParticipantVerificationBridge] run must cover.
 *
 * Each scenario corresponds to a distinct observable lifecycle moment of the Android
 * participant.  The bridge records pass/fail evidence for each scenario independently,
 * enabling V2 to audit which aspects of the participant lifecycle have been validated on
 * a real device and which remain unconfirmed.
 *
 * ## Scenario overview
 *
 * | [RealDeviceVerificationScenario]  | [wireValue]                       | Description                                                         |
 * |-----------------------------------|-----------------------------------|---------------------------------------------------------------------|
 * | [REGISTRATION]                    | `registration`                    | Participant successfully registered with the V2 gateway.            |
 * | [COMMAND_RECEPTION]               | `command_reception`               | Participant received and acknowledged an inbound delegated command. |
 * | [DELEGATED_EXECUTION_AVAILABILITY]| `delegated_execution_availability`| Delegated execution runtime was available and dispatched work.      |
 * | [DISCONNECT_RECONNECT]            | `disconnect_reconnect`            | Participant survived a disconnect and reconnected successfully.      |
 * | [DEGRADED_OUTCOME_RECORDING]      | `degraded_outcome_recording`      | Degraded or recovered outcome was recorded as a structured artifact.|
 *
 * ## Required vs. optional scenarios
 *
 * [REGISTRATION], [COMMAND_RECEPTION], and [DELEGATED_EXECUTION_AVAILABILITY] are the three
 * **required** scenarios for a [RealDeviceVerificationKind.REAL_DEVICE] verdict.
 * [DISCONNECT_RECONNECT] and [DEGRADED_OUTCOME_RECORDING] are **optional** — they are
 * included when the repository's existing recovery capability allows it, and their absence
 * downgrades the overall kind to [RealDeviceVerificationKind.INCOMPLETE] only if one of
 * the required scenarios also failed.
 *
 * @property wireValue       Stable lowercase string for wire transport.
 * @property description     Human-readable description of this scenario.
 * @property isRequired      `true` when this scenario is mandatory for a [RealDeviceVerificationKind.REAL_DEVICE] verdict.
 *
 * @see RealDeviceParticipantVerificationBridge
 * @see RealDeviceParticipantVerificationReport
 * @see RealDeviceVerificationKind
 */
enum class RealDeviceVerificationScenario(
    val wireValue: String,
    val description: String,
    val isRequired: Boolean
) {

    /**
     * The Android participant successfully completed device registration with the V2 gateway.
     *
     * Evidence: participant transitions from [ParticipantLifecycleTruthState.REGISTERING] to
     * [ParticipantLifecycleTruthState.ACTIVE] on a real device, confirmed by a
     * [RegistrationTruthStatus.REGISTERED] signal in [ParticipantLifecycleTruthReport].
     *
     * This is a **required** scenario.
     */
    REGISTRATION(
        wireValue = "registration",
        description = "Participant successfully registered with the V2 gateway",
        isRequired = true
    ),

    /**
     * The Android participant received and acknowledged at least one inbound delegated command.
     *
     * Evidence: a [DelegatedExecutionSignal] was received and processed on the real device
     * without rejection.  The command did not have to succeed end-to-end; reception and
     * initial dispatch acknowledgement is sufficient.
     *
     * This is a **required** scenario.
     */
    COMMAND_RECEPTION(
        wireValue = "command_reception",
        description = "Participant received and acknowledged an inbound delegated command",
        isRequired = true
    ),

    /**
     * The delegated execution runtime was available and dispatched work to the participant.
     *
     * Evidence: [DelegatedRuntimeReadinessEvaluator] produced
     * [DeviceReadinessArtifact.DeviceReadyForRelease] on the real device during the
     * verification window, and at least one delegated task was dispatched.
     *
     * This is a **required** scenario.
     */
    DELEGATED_EXECUTION_AVAILABILITY(
        wireValue = "delegated_execution_availability",
        description = "Delegated execution runtime was available and dispatched work",
        isRequired = true
    ),

    /**
     * The participant survived a disconnect event and successfully reconnected.
     *
     * Evidence: [ReconnectRecoveryState] transitioned through RECONNECTING to a stable
     * connected state on the real device, and [ParticipantLifecycleTruthReport.reconnectObserved]
     * is `true` after recovery.
     *
     * This is an **optional** scenario (included when reconnect recovery capability allows it).
     */
    DISCONNECT_RECONNECT(
        wireValue = "disconnect_reconnect",
        description = "Participant survived a disconnect and reconnected successfully",
        isRequired = false
    ),

    /**
     * A degraded or recovered outcome was recorded as a structured artifact.
     *
     * Evidence: [ParticipantLifecycleTruthReport.lifecycleTruthState] reached
     * [ParticipantLifecycleTruthState.DEGRADED], [ParticipantLifecycleTruthState.RECOVERING],
     * or [ParticipantLifecycleTruthState.RECOVERED] and the transition was captured in
     * the structured [ParticipantLifecycleTruthReport] (not only in logs).
     *
     * This is an **optional** scenario.
     */
    DEGRADED_OUTCOME_RECORDING(
        wireValue = "degraded_outcome_recording",
        description = "Degraded or recovered outcome recorded as a structured artifact",
        isRequired = false
    );

    companion object {

        /**
         * Returns the [RealDeviceVerificationScenario] matching [value], or `null`.
         *
         * @param value  The wire-value string to look up.
         */
        fun fromWireValue(value: String?): RealDeviceVerificationScenario? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * The three scenarios that must all pass for a
         * [RealDeviceVerificationKind.REAL_DEVICE] verdict.
         */
        val REQUIRED_SCENARIOS: Set<RealDeviceVerificationScenario> =
            entries.filter { it.isRequired }.toSet()

        /** All stable wire values for this enum, for validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}
