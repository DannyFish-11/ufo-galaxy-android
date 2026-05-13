package com.ufo.galaxy.runtime

/**
 * PR-1 (Android counterpart): single Android-authoritative runtime participation truth.
 *
 * This contract collapses scattered runtime/connectivity/readiness/session signals into one
 * canonical state label that V2 can consume directly.
 */
object AndroidAuthoritativeParticipationTruth {

    enum class State(val wireValue: String) {
        LOCAL_ONLY("local_only"),
        CONTROL_ONLY("control_only"),
        CROSS_DEVICE_CAPABLE("cross_device_capable"),
        CROSS_DEVICE_ENABLED("cross_device_enabled"),
        FULLY_ATTACHED("fully_attached"),
        DISPATCH_ELIGIBLE("dispatch_eligible"),
        DISTRIBUTED_PARTICIPANT("distributed_participant")
    }

    data class DerivationInput(
        val crossDeviceEnabled: Boolean,
        val wsConnected: Boolean,
        val registrationInFlight: Boolean,
        val capabilityVisible: Boolean,
        val readinessSatisfied: Boolean,
        val runtimeSessionAvailable: Boolean,
        val fullyAttached: Boolean,
        val dispatchEligible: Boolean,
        val continuityIntact: Boolean,
        val operatorSuspendedOrIsolated: Boolean,
        val distributedRuntimeActivity: Boolean
    )

    enum class TransitionTrigger(val wireValue: String) {
        INITIALIZED("initialized"),
        CROSS_DEVICE_ENABLED("cross_device_enabled"),
        CROSS_DEVICE_DISABLED("cross_device_disabled"),
        WS_CONNECTED("ws_connected"),
        WS_DISCONNECTED("ws_disconnected"),
        REGISTRATION_STARTED("registration_started"),
        REGISTRATION_RESOLVED("registration_resolved"),
        CAPABILITY_VISIBLE("capability_visible"),
        CAPABILITY_LOST("capability_lost"),
        SESSION_ESTABLISHED("session_established"),
        SESSION_BROKEN("session_broken"),
        READINESS_SATISFIED("readiness_satisfied"),
        READINESS_LOST("readiness_lost"),
        CONTINUITY_BROKEN("continuity_broken"),
        CONTINUITY_RESTORED("continuity_restored"),
        OPERATOR_SUSPENDED("operator_suspended"),
        OPERATOR_RESUMED("operator_resumed"),
        DISTRIBUTED_ACTIVITY_STARTED("distributed_activity_started"),
        DISTRIBUTED_ACTIVITY_STOPPED("distributed_activity_stopped"),
        STATE_REEVALUATED("state_reevaluated")
    }

    data class TransitionRecord(
        val sequence: Long,
        val fromState: String,
        val toState: String,
        val trigger: String,
        val occurredAtMs: Long
    ) {
        fun toWireString(): String =
            "$sequence|$fromState->$toState|$trigger|$occurredAtMs"
    }

    data class Snapshot(
        val state: State,
        val connected: Boolean,
        val attached: Boolean,
        val dispatchEligible: Boolean,
        val distributedParticipant: Boolean,
        val transitionSequence: Long,
        val lastTransitionTrigger: String,
        val transitionHistory: List<TransitionRecord>
    ) {
        val transitionHistoryWire: List<String>
            get() = transitionHistory.map { it.toWireString() }
    }

    class Tracker(
        private val historyLimit: Int = DEFAULT_HISTORY_LIMIT
    ) {
        private val lock = Any()
        private var previousInput: DerivationInput? = null
        private var currentState: State? = null
        private var transitionSequence: Long = 0L
        private var lastTrigger: TransitionTrigger = TransitionTrigger.INITIALIZED
        private val transitionHistory = ArrayDeque<TransitionRecord>()

        fun evaluate(
            input: DerivationInput,
            timestampMs: Long = System.currentTimeMillis()
        ): Snapshot = synchronized(lock) {
            val nextState = derive(input)
            val trigger = inferTrigger(previousInput, input)
            val previousState = currentState

            if (previousState == null || previousState != nextState) {
                transitionSequence += 1L
                if (previousState != null) {
                    transitionHistory.addLast(
                        TransitionRecord(
                            sequence = transitionSequence,
                            fromState = previousState.wireValue,
                            toState = nextState.wireValue,
                            trigger = trigger.wireValue,
                            occurredAtMs = timestampMs
                        )
                    )
                    while (transitionHistory.size > historyLimit) {
                        transitionHistory.removeFirst()
                    }
                }
                currentState = nextState
            }

            previousInput = input
            lastTrigger = trigger

            Snapshot(
                state = nextState,
                connected = input.wsConnected,
                attached = input.fullyAttached,
                dispatchEligible = input.readinessSatisfied && input.dispatchEligible,
                distributedParticipant = nextState == State.DISTRIBUTED_PARTICIPANT,
                transitionSequence = transitionSequence,
                lastTransitionTrigger = lastTrigger.wireValue,
                transitionHistory = transitionHistory.toList()
            )
        }
    }

    fun derive(input: DerivationInput): State {
        if (!input.crossDeviceEnabled) return State.LOCAL_ONLY
        if (input.operatorSuspendedOrIsolated) return State.CONTROL_ONLY
        if (input.registrationInFlight || !input.wsConnected) return State.CONTROL_ONLY
        if (!input.capabilityVisible) return State.CROSS_DEVICE_CAPABLE
        if (!input.runtimeSessionAvailable || !input.continuityIntact) return State.CROSS_DEVICE_ENABLED
        if (!input.fullyAttached) return State.CROSS_DEVICE_ENABLED
        if (!input.readinessSatisfied || !input.dispatchEligible) return State.FULLY_ATTACHED
        if (input.distributedRuntimeActivity) return State.DISTRIBUTED_PARTICIPANT
        return State.DISPATCH_ELIGIBLE
    }

    private fun inferTrigger(
        previous: DerivationInput?,
        current: DerivationInput
    ): TransitionTrigger {
        if (previous == null) return TransitionTrigger.INITIALIZED
        return when {
            !previous.crossDeviceEnabled && current.crossDeviceEnabled ->
                TransitionTrigger.CROSS_DEVICE_ENABLED
            previous.crossDeviceEnabled && !current.crossDeviceEnabled ->
                TransitionTrigger.CROSS_DEVICE_DISABLED
            !previous.wsConnected && current.wsConnected ->
                TransitionTrigger.WS_CONNECTED
            previous.wsConnected && !current.wsConnected ->
                TransitionTrigger.WS_DISCONNECTED
            !previous.registrationInFlight && current.registrationInFlight ->
                TransitionTrigger.REGISTRATION_STARTED
            previous.registrationInFlight && !current.registrationInFlight ->
                TransitionTrigger.REGISTRATION_RESOLVED
            !previous.capabilityVisible && current.capabilityVisible ->
                TransitionTrigger.CAPABILITY_VISIBLE
            previous.capabilityVisible && !current.capabilityVisible ->
                TransitionTrigger.CAPABILITY_LOST
            !previous.runtimeSessionAvailable && current.runtimeSessionAvailable ->
                TransitionTrigger.SESSION_ESTABLISHED
            previous.runtimeSessionAvailable && !current.runtimeSessionAvailable ->
                TransitionTrigger.SESSION_BROKEN
            !previous.readinessSatisfied && current.readinessSatisfied ->
                TransitionTrigger.READINESS_SATISFIED
            previous.readinessSatisfied && !current.readinessSatisfied ->
                TransitionTrigger.READINESS_LOST
            previous.continuityIntact && !current.continuityIntact ->
                TransitionTrigger.CONTINUITY_BROKEN
            !previous.continuityIntact && current.continuityIntact ->
                TransitionTrigger.CONTINUITY_RESTORED
            !previous.operatorSuspendedOrIsolated && current.operatorSuspendedOrIsolated ->
                TransitionTrigger.OPERATOR_SUSPENDED
            previous.operatorSuspendedOrIsolated && !current.operatorSuspendedOrIsolated ->
                TransitionTrigger.OPERATOR_RESUMED
            !previous.distributedRuntimeActivity && current.distributedRuntimeActivity ->
                TransitionTrigger.DISTRIBUTED_ACTIVITY_STARTED
            previous.distributedRuntimeActivity && !current.distributedRuntimeActivity ->
                TransitionTrigger.DISTRIBUTED_ACTIVITY_STOPPED
            else ->
                TransitionTrigger.STATE_REEVALUATED
        }
    }

    private const val DEFAULT_HISTORY_LIMIT = 24
}
