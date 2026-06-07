package com.ufo.galaxy.runtime

/**
 * PR-7 (Android companion) — Delegated takeover recovery semantics for multi-device scenarios.
 *
 * Provides the canonical, testable declaration of what Android must do when participant state
 * changes interrupt or affect an active or pending delegated takeover.  The V2 PR-7 joint
 * integration harness uses this registry to:
 *  - Assert that Android emits the correct signals when a takeover is interrupted.
 *  - Verify that V2 receives deterministic outcome signals even in failure/recovery paths.
 *  - Confirm that Android does not attempt to resume a delegated task after reconnect
 *    without V2 re-authorization.
 *
 * ## Background
 *
 * A delegated takeover is an incoming task from V2 that Android accepts and executes
 * locally ([DelegatedRuntimeReceiver] → [DelegatedTakeoverExecutor]).  When the participant
 * undergoes a state transition (disconnect, degradation, etc.) while a takeover is active,
 * the joint integration harness must verify that:
 *  1. Android emits a terminal outcome signal ([DelegatedExecutionSignal.Kind.RESULT] with
 *     a failure [DelegatedExecutionSignal.ResultKind]) so V2 can close the task cleanly.
 *  2. Android clears its local execution state — it MUST NOT attempt to continue the task
 *     after reconnect without V2 re-authorization.
 *  3. The [DelegatedActivationRecord] transitions to a terminal status
 *     ([DelegatedActivationRecord.ActivationStatus.FAILED]).
 *  4. V2 receives the correct structured reason string so it can apply the appropriate
 *     fallback policy.
 *
 * ## Contract format
 *
 * Each [RecoveryScenario] entry in [ALL_SCENARIOS] declares:
 *  - Which orchestration state transition triggers the scenario.
 *  - Whether the current takeover (if any) is interrupted.
 *  - What signal must be emitted by Android.
 *  - What reason string must be included in the signal.
 *  - Whether Android may continue the task after re-connection.
 *  - The rationale for the specified behaviour.
 *
 * @see DelegatedActivationRecord
 * @see DelegatedExecutionTracker
 * @see DelegatedExecutionSignal
 * @see MultiDeviceParticipantOrchestrationState
 */
object DelegatedTakeoverRecoveryContract {

    // ── Interruption outcome enum ─────────────────────────────────────────────

    /**
     * What must happen to any active delegated takeover when the participant state changes.
     *
     * @property wireValue Stable string used in joint-harness assertion messages.
     */
    enum class TakeoverInterruptionOutcome(val wireValue: String) {

        /**
         * The active takeover is not affected by the state transition.
         * The participant may continue executing the task normally.
         */
        UNAFFECTED("unaffected"),

        /**
         * The active takeover is interrupted and must be terminated with a FAILED signal.
         * Android must NOT attempt to resume after reconnect without V2 re-authorization.
         */
        TERMINATED_WITH_SIGNAL("terminated_with_signal"),

        /**
         * The active takeover is interrupted but Android may complete it before the state
         * change takes full effect (e.g. during a brief DRAINING transition).
         * Android must emit a RESULT or FAILED signal before the state transition completes.
         */
        DRAIN_THEN_TERMINATE("drain_then_terminate"),

        /**
         * No takeover was active at the time of the state transition; no action required.
         */
        NO_ACTIVE_TAKEOVER("no_active_takeover")
    }

    // ── Recovery scenario ─────────────────────────────────────────────────────

    /**
     * Describes the required Android behaviour for a delegated takeover when the participant
     * undergoes a specific state transition.
     *
     * @property scenarioId              Stable kebab-case identifier for this scenario.
     * @property fromState               The orchestration state before the transition.
     * @property toState                 The orchestration state after the transition.
     * @property interruptionOutcome     What must happen to any active takeover.
     * @property requiredSignalKind      The [DelegatedExecutionSignal.Kind] that must be emitted,
     *                                   or `null` if no signal is required.
     * @property requiredResultKind      The [DelegatedExecutionSignal.ResultKind] for the RESULT
     *                                   signal, or `null` if no RESULT signal is required.
     * @property requiredReasonPrefix    The string prefix that must appear in the signal's reason
     *                                   field so V2 can apply the correct fallback policy.
     * @property mayResumeAfterReconnect `true` when Android is allowed to resume the task after
     *                                   a successful reconnect without V2 re-authorization.
     *                                   This is always `false` in the current contract.
     * @property rationale               Explanation of the required behaviour.
     */
    data class RecoveryScenario(
        val scenarioId: String,
        val fromState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        val toState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        val interruptionOutcome: TakeoverInterruptionOutcome,
        val requiredSignalKind: String?,
        val requiredResultKind: String?,
        val requiredReasonPrefix: String?,
        val mayResumeAfterReconnect: Boolean,
        val rationale: String
    )

    // ── Scenario registry ─────────────────────────────────────────────────────

    /**
     * Canonical registry of all delegated takeover recovery scenarios that the PR-7 joint
     * harness must validate on the Android participant side.
     */
    val ALL_SCENARIOS: List<RecoveryScenario> = listOf(

        RecoveryScenario(
            scenarioId = "connected_to_reconnecting_with_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING,
            interruptionOutcome = TakeoverInterruptionOutcome.TERMINATED_WITH_SIGNAL,
            requiredSignalKind = DelegatedExecutionSignal.Kind.RESULT.wireValue,
            requiredResultKind = DelegatedExecutionSignal.ResultKind.FAILED.wireValue,
            requiredReasonPrefix = "participant_disconnected",
            mayResumeAfterReconnect = false,
            rationale = "WS disconnect while a delegated task is running requires Android to emit " +
                "a RESULT/FAILED signal with reason='participant_disconnected:ws_reconnecting'. " +
                "V2 applies its fallback policy. Android must NOT resume after reconnect."
        ),
        RecoveryScenario(
            scenarioId = "connected_to_disconnected_with_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.DISCONNECTED,
            interruptionOutcome = TakeoverInterruptionOutcome.TERMINATED_WITH_SIGNAL,
            requiredSignalKind = DelegatedExecutionSignal.Kind.RESULT.wireValue,
            requiredResultKind = DelegatedExecutionSignal.ResultKind.FAILED.wireValue,
            requiredReasonPrefix = "participant_disconnected",
            mayResumeAfterReconnect = false,
            rationale = "Clean disconnect while a delegated task is running requires Android to emit " +
                "RESULT/FAILED before the WS closes. Android must NOT resume after any later " +
                "reconnect without V2 re-authorizing the task."
        ),
        RecoveryScenario(
            scenarioId = "connected_to_degraded_with_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.DEGRADED,
            interruptionOutcome = TakeoverInterruptionOutcome.DRAIN_THEN_TERMINATE,
            requiredSignalKind = DelegatedExecutionSignal.Kind.RESULT.wireValue,
            requiredResultKind = DelegatedExecutionSignal.ResultKind.FAILED.wireValue,
            requiredReasonPrefix = "participant_degraded",
            mayResumeAfterReconnect = false,
            rationale = "Health degradation during task execution: Android may attempt to complete " +
                "the task at degraded quality, but must emit RESULT/FAILED with " +
                "reason='participant_degraded' if the task cannot be completed normally."
        ),
        RecoveryScenario(
            scenarioId = "reconnecting_to_connected_no_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            interruptionOutcome = TakeoverInterruptionOutcome.NO_ACTIVE_TAKEOVER,
            requiredSignalKind = null,
            requiredResultKind = null,
            requiredReasonPrefix = null,
            mayResumeAfterReconnect = false,
            rationale = "Successful reconnect with no active takeover: Android re-reports capability " +
                "and readiness via DeviceReconnected; V2 may re-dispatch if needed."
        ),
        RecoveryScenario(
            scenarioId = "reconnecting_to_disconnected_with_interrupted_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.RECONNECTING,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.DISCONNECTED,
            interruptionOutcome = TakeoverInterruptionOutcome.NO_ACTIVE_TAKEOVER,
            requiredSignalKind = null,
            requiredResultKind = null,
            requiredReasonPrefix = null,
            mayResumeAfterReconnect = false,
            rationale = "Reconnect failure: any takeover that was active before the disconnect " +
                "was already terminated when the disconnect began (see connected_to_reconnecting). " +
                "No additional signal is needed; V2 observes DeviceDisconnected and applies fallback."
        ),
        RecoveryScenario(
            scenarioId = "recovering_to_connected_no_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.RECOVERING,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            interruptionOutcome = TakeoverInterruptionOutcome.NO_ACTIVE_TAKEOVER,
            requiredSignalKind = null,
            requiredResultKind = null,
            requiredReasonPrefix = null,
            mayResumeAfterReconnect = false,
            rationale = "Health recovery complete: Android reports HEALTHY via DeviceHealthChanged; " +
                "V2 may now re-dispatch. Any previously interrupted tasks must be re-authorized by V2."
        ),
        RecoveryScenario(
            scenarioId = "capability_limited_no_active_takeover",
            fromState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CONNECTED,
            toState = MultiDeviceParticipantOrchestrationState.OrchestrationState.CAPABILITY_LIMITED,
            interruptionOutcome = TakeoverInterruptionOutcome.NO_ACTIVE_TAKEOVER,
            requiredSignalKind = null,
            requiredResultKind = null,
            requiredReasonPrefix = null,
            mayResumeAfterReconnect = false,
            rationale = "Capability limitation with no active takeover: Android emits DeviceDegraded " +
                "and re-reports reduced capability set. V2 updates routing table accordingly."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [RecoveryScenario] for the given [fromState] and [toState] combination,
     * or `null` if no scenario is registered for that transition.
     */
    fun scenarioFor(
        fromState: MultiDeviceParticipantOrchestrationState.OrchestrationState,
        toState: MultiDeviceParticipantOrchestrationState.OrchestrationState
    ): RecoveryScenario? = ALL_SCENARIOS.firstOrNull {
        it.fromState == fromState && it.toState == toState
    }

    /**
     * Returns all scenarios where Android must emit a terminal signal for an active takeover.
     */
    val terminatingScenarios: List<RecoveryScenario> =
        ALL_SCENARIOS.filter {
            it.interruptionOutcome == TakeoverInterruptionOutcome.TERMINATED_WITH_SIGNAL ||
                it.interruptionOutcome == TakeoverInterruptionOutcome.DRAIN_THEN_TERMINATE
        }

    /**
     * Returns all scenarios where [RecoveryScenario.mayResumeAfterReconnect] is `false`.
     *
     * In the current contract this is every scenario — Android never resumes a delegated
     * task after reconnect without V2 re-authorization.
     */
    val noResumeAfterReconnectScenarios: List<RecoveryScenario> =
        ALL_SCENARIOS.filter { !it.mayResumeAfterReconnect }

    // ── Invariant constants ───────────────────────────────────────────────────

    /** Total number of recovery scenarios in this registry. */
    const val SCENARIO_COUNT: Int = 7

    /**
     * Wire-value prefix applied to reason strings in RESULT/FAILED signals when a
     * takeover is interrupted by a participant disconnect.
     */
    const val REASON_PARTICIPANT_DISCONNECTED: String = "participant_disconnected"

    /**
     * Wire-value prefix applied to reason strings in RESULT/FAILED signals when a
     * takeover is interrupted by a health degradation.
     */
    const val REASON_PARTICIPANT_DEGRADED: String = "participant_degraded"

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 7

    /** Human-readable title for this surface. */
    const val INTRODUCED_PR_TITLE: String =
        "PR-7 Android companion: delegated takeover recovery contract for multi-device scenarios"
}
