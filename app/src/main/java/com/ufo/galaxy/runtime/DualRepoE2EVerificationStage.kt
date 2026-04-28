package com.ufo.galaxy.runtime

/**
 * PR-72 (Android) — Enum enumerating the seven stages of the dual-repo E2E verification chain.
 *
 * [DualRepoE2EVerificationStage] covers the **complete** lifecycle path that a real Android
 * participant must traverse in a dual-repo E2E verification run.  It extends the three-
 * required-scenario model of [RealDeviceVerificationScenario] by adding two stages that are
 * required for a true closed-loop E2E verdict but were not previously covered:
 *
 *  - [CAPABILITY_REPORT] — the device must send a structured capability report to the V2
 *    gateway (not just register).
 *  - [TASK_RESULT_RETURN] — the task result must actually be returned to V2 (not just
 *    executed locally).
 *
 * ## Full chain overview
 *
 * | Stage                             | [wireValue]                       | Required | Description                                                        |
 * |-----------------------------------|-----------------------------------|----------|--------------------------------------------------------------------|
 * | [DEVICE_REGISTER]                 | `device_register`                 | ✓        | Device registers with V2 gateway.                                  |
 * | [CAPABILITY_REPORT]               | `capability_report`               | ✓        | Capability report sent to V2 gateway.                              |
 * | [TASK_ASSIGNMENT_RECEPTION]       | `task_assignment_reception`       | ✓        | Task assignment received and acknowledged from V2 gateway.         |
 * | [DELEGATED_EXECUTION_AVAILABLE]   | `delegated_execution_available`   | ✓        | Delegated execution runtime available and dispatched work.         |
 * | [TASK_RESULT_RETURN]              | `task_result_return`              | ✓        | Task result returned to V2 gateway.                                |
 * | [RECONNECT_RECOVERY]              | `reconnect_recovery`              | ✗        | Participant survived disconnect and reconnected successfully.       |
 * | [DEGRADED_OUTCOME_RECORDING]      | `degraded_outcome_recording`      | ✗        | Degraded or recovered outcome recorded as a structured artifact.   |
 *
 * ## Required vs. optional stages
 *
 * All five of [DEVICE_REGISTER], [CAPABILITY_REPORT], [TASK_ASSIGNMENT_RECEPTION],
 * [DELEGATED_EXECUTION_AVAILABLE], and [TASK_RESULT_RETURN] are **required** for a
 * [DualRepoE2EVerificationArtifact.E2EFullyVerified] verdict.  [RECONNECT_RECOVERY] and
 * [DEGRADED_OUTCOME_RECORDING] are **optional** — recorded when the reconnect/degraded
 * recovery capability is available.
 *
 * ## Prohibition on optimistic promotion
 *
 * A [DualRepoE2EVerificationHarness] implementation **must not** produce
 * [DualRepoE2EVerificationArtifact.E2EFullyVerified] unless:
 *  - A physical Android device was detected and connected.
 *  - All five required stages were observed and passed on that device.
 *
 * Partial, simulated, emulator, or missing-device states **must** produce the appropriate
 * non-verified artifact.  An absent or unavailable device **must** produce
 * [DualRepoE2EVerificationArtifact.E2EBlockedNoDevice].
 *
 * ## Relationship to [RealDeviceVerificationScenario]
 *
 * [toBridgeScenario] maps the overlapping stages back to the existing
 * [RealDeviceVerificationScenario] entries so that a [RealDeviceParticipantVerificationBridge]
 * can be populated from harness stage outcomes.  [CAPABILITY_REPORT] and
 * [TASK_RESULT_RETURN] do not have corresponding bridge scenarios (they are new to this
 * harness) and return `null` from [toBridgeScenario].
 *
 * @property wireValue    Stable lowercase string for wire transport.
 * @property description  Human-readable description of this stage.
 * @property isRequired   `true` when this stage is mandatory for an
 *                        [DualRepoE2EVerificationArtifact.E2EFullyVerified] verdict.
 *
 * @see DualRepoE2EVerificationHarness
 * @see DualRepoE2EVerificationReport
 * @see RealDeviceVerificationScenario
 */
enum class DualRepoE2EVerificationStage(
    val wireValue: String,
    val description: String,
    val isRequired: Boolean
) {

    /**
     * The Android participant successfully completed device registration with the V2 gateway.
     *
     * Evidence: participant transitions from [ParticipantLifecycleTruthState.REGISTERING] to
     * [ParticipantLifecycleTruthState.ACTIVE] on the device, confirmed by registration ACK
     * from V2.
     *
     * Maps to bridge scenario: [RealDeviceVerificationScenario.REGISTRATION].
     *
     * This is a **required** stage.
     */
    DEVICE_REGISTER(
        wireValue = "device_register",
        description = "Device registered with V2 gateway",
        isRequired = true
    ),

    /**
     * The Android participant sent a structured capability report to the V2 gateway.
     *
     * Evidence: a `capability_report` frame was transmitted to V2 containing at minimum the
     * required metadata fields (platform, device_id, version, capabilities, metadata).  The
     * gateway must have acknowledged the report or updated the participant capability record.
     *
     * This stage is **new to this harness** — it was not covered by the existing
     * [RealDeviceVerificationScenario] set.  V2 cannot route tasks to a participant whose
     * capability report has not been confirmed.
     *
     * Does not map to a bridge scenario ([toBridgeScenario] returns `null`).
     *
     * This is a **required** stage.
     */
    CAPABILITY_REPORT(
        wireValue = "capability_report",
        description = "Capability report sent to V2 gateway",
        isRequired = true
    ),

    /**
     * The Android participant received and acknowledged at least one inbound task assignment
     * from the V2 gateway.
     *
     * Evidence: a [DelegatedExecutionSignal] or equivalent task assignment message was
     * received and dispatched on the device without rejection.
     *
     * Maps to bridge scenario: [RealDeviceVerificationScenario.COMMAND_RECEPTION].
     *
     * This is a **required** stage.
     */
    TASK_ASSIGNMENT_RECEPTION(
        wireValue = "task_assignment_reception",
        description = "Task assignment received and acknowledged from V2 gateway",
        isRequired = true
    ),

    /**
     * The delegated execution runtime was available and dispatched work to the participant.
     *
     * Evidence: [DelegatedRuntimeReadinessEvaluator] produced a ready artifact on the device
     * during the verification window and at least one delegated task was dispatched to an
     * executor.
     *
     * Maps to bridge scenario: [RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY].
     *
     * This is a **required** stage.
     */
    DELEGATED_EXECUTION_AVAILABLE(
        wireValue = "delegated_execution_available",
        description = "Delegated execution runtime available and dispatched work",
        isRequired = true
    ),

    /**
     * The task result was returned from the Android participant back to the V2 gateway.
     *
     * Evidence: a `task_result` (or equivalent result envelope) was transmitted from the
     * device to V2 and the gateway received a non-error response or logged the result.
     * This closes the outbound task → result feedback loop.
     *
     * This stage is **new to this harness** — it was not covered by the existing
     * [RealDeviceVerificationScenario] set.  A participant may execute tasks but fail to
     * return results; this stage verifies the full round-trip.
     *
     * Does not map to a bridge scenario ([toBridgeScenario] returns `null`).
     *
     * This is a **required** stage.
     */
    TASK_RESULT_RETURN(
        wireValue = "task_result_return",
        description = "Task result returned to V2 gateway",
        isRequired = true
    ),

    /**
     * The participant survived a disconnect event and successfully reconnected to V2.
     *
     * Evidence: [ReconnectRecoveryState] transitioned through RECONNECTING to a stable
     * connected state and [ParticipantLifecycleTruthReport.reconnectObserved] is `true`.
     *
     * Maps to bridge scenario: [RealDeviceVerificationScenario.DISCONNECT_RECONNECT].
     *
     * This is an **optional** stage.
     */
    RECONNECT_RECOVERY(
        wireValue = "reconnect_recovery",
        description = "Participant survived disconnect and reconnected successfully",
        isRequired = false
    ),

    /**
     * A degraded or recovered outcome was recorded as a structured artifact.
     *
     * Evidence: [ParticipantLifecycleTruthReport.lifecycleTruthState] reached
     * [ParticipantLifecycleTruthState.DEGRADED], [ParticipantLifecycleTruthState.RECOVERING],
     * or [ParticipantLifecycleTruthState.RECOVERED] and the transition was captured in a
     * structured report (not only in logs).
     *
     * Maps to bridge scenario: [RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING].
     *
     * This is an **optional** stage.
     */
    DEGRADED_OUTCOME_RECORDING(
        wireValue = "degraded_outcome_recording",
        description = "Degraded or recovered outcome recorded as structured artifact",
        isRequired = false
    );

    companion object {

        /**
         * Returns the [DualRepoE2EVerificationStage] matching [value], or `null`.
         *
         * @param value  The wire-value string to look up.
         */
        fun fromWireValue(value: String?): DualRepoE2EVerificationStage? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * The five stages that must all pass for a
         * [DualRepoE2EVerificationArtifact.E2EFullyVerified] verdict.
         */
        val REQUIRED_STAGES: Set<DualRepoE2EVerificationStage> =
            entries.filter { it.isRequired }.toSet()

        /** All stable wire values for this enum, for validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
    }
}

/**
 * Maps this [DualRepoE2EVerificationStage] to the corresponding
 * [RealDeviceVerificationScenario] for bridge wiring, or returns `null` for stages that
 * have no bridge equivalent ([CAPABILITY_REPORT] and [TASK_RESULT_RETURN]).
 *
 * Used by [DualRepoE2EVerificationHarness.buildBridgeReport] to populate a
 * [RealDeviceParticipantVerificationBridge] from harness stage outcomes.
 */
fun DualRepoE2EVerificationStage.toBridgeScenario(): RealDeviceVerificationScenario? =
    when (this) {
        DualRepoE2EVerificationStage.DEVICE_REGISTER ->
            RealDeviceVerificationScenario.REGISTRATION

        DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION ->
            RealDeviceVerificationScenario.COMMAND_RECEPTION

        DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE ->
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY

        DualRepoE2EVerificationStage.RECONNECT_RECOVERY ->
            RealDeviceVerificationScenario.DISCONNECT_RECONNECT

        DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING ->
            RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING

        // New stages with no bridge equivalent
        DualRepoE2EVerificationStage.CAPABILITY_REPORT -> null
        DualRepoE2EVerificationStage.TASK_RESULT_RETURN -> null
    }
