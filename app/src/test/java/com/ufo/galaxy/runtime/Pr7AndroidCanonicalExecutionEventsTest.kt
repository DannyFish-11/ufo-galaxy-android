package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PR-7 (Android) — Unit tests for [AndroidCanonicalExecutionEventOwner],
 * [CanonicalExecutionEventType], [CanonicalExecutionEvent], and
 * [CanonicalExecutionEventEmitDecision].
 *
 * ## Test matrix
 *
 * ### AndroidCanonicalExecutionEventOwner — constants
 *  - DECISION_EMIT_EVENT wire value is "emit_event"
 *  - DECISION_SUPPRESS_DUPLICATE_EVENT wire value is "suppress_duplicate_event"
 *  - DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE wire value is "suppress_high_frequency_noise"
 *  - DECISION_SUPPRESS_POST_TERMINAL wire value is "suppress_post_terminal"
 *  - DECISION_HOLD_FOR_RECONNECT_ALIGNMENT wire value is "hold_for_reconnect_alignment"
 *  - All five decision constants are distinct
 *  - INTEGRATION_LOOP_CONTROLLER is "LoopController"
 *  - INTEGRATION_PIPELINE is "AutonomousExecutionPipeline"
 *  - INTEGRATION_UNIT is "DelegatedRuntimeUnit"
 *  - INTEGRATION_ACTIVATION_RECORD is "DelegatedActivationRecord"
 *  - INTEGRATION_RECEIVER is "DelegatedRuntimeReceiver"
 *  - INTEGRATION_TAKEOVER_EXECUTOR is "DelegatedTakeoverExecutor"
 *  - INTEGRATION_COLLABORATION_AGENT is "LocalCollaborationAgent"
 *  - INTRODUCED_PR is 7
 *  - DESCRIPTION is non-blank
 *
 * ### CanonicalExecutionEventType — wire values
 *  - PLANNING_STARTED wireValue is "planning_started"
 *  - PLANNING_PROGRESS wireValue is "planning_progress"
 *  - PLANNING_BLOCKED wireValue is "planning_blocked"
 *  - GROUNDING_STARTED wireValue is "grounding_started"
 *  - GROUNDING_PROGRESS wireValue is "grounding_progress"
 *  - GROUNDING_BLOCKED wireValue is "grounding_blocked"
 *  - EXECUTION_STARTED wireValue is "execution_started"
 *  - EXECUTION_PROGRESS wireValue is "execution_progress"
 *  - EXECUTION_BLOCKED wireValue is "execution_blocked"
 *  - REPLAN_TRIGGERED wireValue is "replan_triggered"
 *  - STAGNATION_DETECTED wireValue is "stagnation_detected"
 *  - GATE_DECISION_EMITTED wireValue is "gate_decision_emitted"
 *  - TAKEOVER_MILESTONE wireValue is "takeover_milestone"
 *  - COLLABORATION_MILESTONE wireValue is "collaboration_milestone"
 *  - PARALLEL_MILESTONE wireValue is "parallel_milestone"
 *  - All 15 wire values are distinct
 *
 * ### CanonicalExecutionEventType.fromValue — classification
 *  - "planning_started" → PLANNING_STARTED
 *  - "grounding_blocked" → GROUNDING_BLOCKED
 *  - "execution_progress" → EXECUTION_PROGRESS
 *  - "replan_triggered" → REPLAN_TRIGGERED
 *  - "stagnation_detected" → STAGNATION_DETECTED
 *  - "gate_decision_emitted" → GATE_DECISION_EMITTED
 *  - "takeover_milestone" → TAKEOVER_MILESTONE
 *  - "collaboration_milestone" → COLLABORATION_MILESTONE
 *  - "parallel_milestone" → PARALLEL_MILESTONE
 *  - unknown value → DEFAULT (EXECUTION_PROGRESS)
 *  - null → DEFAULT (EXECUTION_PROGRESS)
 *
 * ### classifyEventType
 *  - "planning_started" → PLANNING_STARTED
 *  - "execution_blocked" → EXECUTION_BLOCKED
 *  - "replan_triggered" → REPLAN_TRIGGERED
 *  - unknown → DEFAULT
 *
 * ### evaluateEventEmit — EmitEvent (no suppression)
 *  - Returns EmitEvent for a normal event with no suppression conditions
 *  - EmitEvent.semanticTag is DECISION_EMIT_EVENT
 *  - EmitEvent.event is the original event
 *
 * ### evaluateEventEmit — SuppressPostTerminal
 *  - Returns SuppressPostTerminal after markFlowTerminal
 *  - SuppressPostTerminal.semanticTag is DECISION_SUPPRESS_POST_TERMINAL
 *  - SuppressPostTerminal.flowId matches event flowId
 *  - SuppressPostTerminal.terminalReason matches registered reason
 *  - Post-terminal suppression takes priority over duplicate suppression
 *  - Post-terminal suppression takes priority over noise gate suppression
 *  - Post-terminal suppression takes priority over event gate hold
 *  - Post-terminal suppression takes priority over emit
 *  - Different flowId is not suppressed after marking another flow terminal
 *
 * ### evaluateEventEmit — SuppressDuplicateEvent
 *  - Returns SuppressDuplicateEvent after markEventEmitted
 *  - SuppressDuplicateEvent.semanticTag is DECISION_SUPPRESS_DUPLICATE_EVENT
 *  - SuppressDuplicateEvent.flowId matches event flowId
 *  - SuppressDuplicateEvent.eventId matches the previously emitted event ID
 *  - Duplicate suppression takes priority over noise gate suppression
 *  - Duplicate suppression takes priority over event gate hold
 *  - Duplicate suppression takes priority over emit
 *  - Different eventId is not suppressed after marking another eventId emitted
 *
 * ### evaluateEventEmit — SuppressHighFrequencyNoise
 *  - Returns SuppressHighFrequencyNoise after openNoiseGate
 *  - SuppressHighFrequencyNoise.semanticTag is DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE
 *  - SuppressHighFrequencyNoise.flowId matches event flowId
 *  - Noise suppression takes priority over event gate hold
 *  - After closeNoiseGate, evaluateEventEmit returns normal decision
 *  - Noise gate for EXECUTION_PROGRESS does not affect EXECUTION_STARTED events
 *  - Different flowId is not suppressed by noise gate on another flow
 *
 * ### evaluateEventEmit — HoldForReconnectAlignment
 *  - Returns HoldForReconnectAlignment after closeEventGate
 *  - HoldForReconnectAlignment.semanticTag is DECISION_HOLD_FOR_RECONNECT_ALIGNMENT
 *  - HoldForReconnectAlignment.flowId matches the gated flow
 *  - After openEventGate, evaluateEventEmit returns EmitEvent
 *  - Different flowId is not held after gating another flow
 *
 * ### Terminal flow registry
 *  - isFlowTerminal is false initially
 *  - isFlowTerminal is true after markFlowTerminal
 *  - flowTerminalReason is null when not terminal
 *  - flowTerminalReason returns the registered reason after markFlowTerminal
 *  - isFlowTerminal is false after clearFlowTerminal
 *  - terminalFlowCount is 0 initially
 *  - terminalFlowCount increments with markFlowTerminal
 *  - terminalFlowCount decrements with clearFlowTerminal
 *  - clearAllTerminalFlows resets count to 0
 *  - Multiple distinct flowIds can all be marked terminal simultaneously
 *
 * ### Emitted event ID registry
 *  - isEventEmitted is false initially
 *  - isEventEmitted is true after markEventEmitted
 *  - emittedEventCount is 0 initially
 *  - emittedEventCount increments with markEventEmitted
 *  - clearAllEmittedEvents resets count to 0
 *  - Multiple distinct event IDs can all be marked emitted simultaneously
 *
 * ### Event gate
 *  - isEventGated is false initially
 *  - isEventGated is true after closeEventGate
 *  - isEventGated is false after openEventGate
 *  - eventGatedFlowCount is 0 initially
 *  - eventGatedFlowCount increments with closeEventGate
 *  - eventGatedFlowCount decrements with openEventGate
 *  - clearAllEventGates resets count to 0
 *  - Multiple distinct flowIds can all be gated simultaneously
 *
 * ### Noise gate
 *  - isNoiseGated is false initially
 *  - isNoiseGated is true after openNoiseGate
 *  - isNoiseGated is false after closeNoiseGate
 *  - noiseGatedEntryCount is 0 initially
 *  - noiseGatedEntryCount increments with openNoiseGate
 *  - noiseGatedEntryCount decrements with closeNoiseGate
 *  - clearAllNoiseGates resets count to 0
 *  - Different event types on the same flow are gated independently
 *
 * ### CanonicalExecutionEvent factory methods
 *  - planning() produces an event with phase "planning"
 *  - grounding() produces an event with phase "grounding"
 *  - execution() produces an event with phase "execution"
 *  - replan() produces a REPLAN_TRIGGERED event with replanReason set
 *  - stagnation() produces a STAGNATION_DETECTED event with stagnationReason set
 *  - gate() produces a GATE_DECISION_EMITTED event with gateRationale set
 *  - milestone(TAKEOVER_MILESTONE) produces phase "takeover"
 *  - milestone(COLLABORATION_MILESTONE) produces phase "collaboration"
 *  - milestone(PARALLEL_MILESTONE) produces phase "parallel"
 *
 * ### CanonicalExecutionEvent.toMetadataMap
 *  - toMetadataMap includes event_id, event_type, flow_id, task_id, phase, source_component, timestamp_ms, emission_seq
 *  - toMetadataMap omits null optional fields
 *  - toMetadataMap includes blocking_rationale when non-null
 *  - toMetadataMap includes replan_reason when non-null
 *  - toMetadataMap includes stagnation_reason when non-null
 *  - toMetadataMap includes gate_rationale when non-null
 *
 * ### CanonicalExecutionEvent derived helpers
 *  - isBlocked is true for PLANNING_BLOCKED
 *  - isBlocked is true for GROUNDING_BLOCKED
 *  - isBlocked is true for EXECUTION_BLOCKED
 *  - isBlocked is false for EXECUTION_PROGRESS
 *  - isPhaseStart is true for PLANNING_STARTED
 *  - isPhaseStart is true for GROUNDING_STARTED
 *  - isPhaseStart is true for EXECUTION_STARTED
 *  - isPhaseStart is false for REPLAN_TRIGGERED
 *  - isRecoveryTrigger is true for REPLAN_TRIGGERED
 *  - isRecoveryTrigger is true for STAGNATION_DETECTED
 *  - isRecoveryTrigger is false for EXECUTION_PROGRESS
 *
 * ### CanonicalExecutionEventEmitDecision — semanticTag values
 *  - EmitEvent.semanticTag matches DECISION_EMIT_EVENT
 *  - SuppressDuplicateEvent.semanticTag matches DECISION_SUPPRESS_DUPLICATE_EVENT
 *  - SuppressHighFrequencyNoise.semanticTag matches DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE
 *  - SuppressPostTerminal.semanticTag matches DECISION_SUPPRESS_POST_TERMINAL
 *  - HoldForReconnectAlignment.semanticTag matches DECISION_HOLD_FOR_RECONNECT_ALIGNMENT
 *  - All five semanticTag values are distinct
 */
class Pr7AndroidCanonicalExecutionEventsTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private lateinit var owner: AndroidCanonicalExecutionEventOwner

    @Before
    fun setUp() {
        owner = AndroidCanonicalExecutionEventOwner()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeEvent(
        flowId: String = "flow-7",
        taskId: String = "task-7",
        eventType: CanonicalExecutionEventType = CanonicalExecutionEventType.EXECUTION_PROGRESS,
        eventId: String = "evt-7-unique",
        phase: String = CanonicalExecutionEvent.PHASE_EXECUTION,
        stepIndex: Int? = 1,
        reason: String? = "step completed",
        blockingRationale: String? = null,
        gateRationale: String? = null,
        replanReason: String? = null,
        stagnationReason: String? = null,
        traceId: String? = "trace-7",
        delegatedLineageId: String? = "lineage-7",
        attachedSessionId: String? = "sess-7",
        postureHint: String? = null,
        executionContextHint: String? = null,
        sourceComponent: String = AndroidCanonicalExecutionEventOwner.INTEGRATION_LOOP_CONTROLLER,
        timestampMs: Long = 1_000L,
        emissionSeq: Long = 1L
    ) = CanonicalExecutionEvent(
        eventId = eventId,
        eventType = eventType,
        flowId = flowId,
        taskId = taskId,
        traceId = traceId,
        delegatedLineageId = delegatedLineageId,
        attachedSessionId = attachedSessionId,
        phase = phase,
        stepIndex = stepIndex,
        reason = reason,
        blockingRationale = blockingRationale,
        gateRationale = gateRationale,
        replanReason = replanReason,
        stagnationReason = stagnationReason,
        postureHint = postureHint,
        executionContextHint = executionContextHint,
        sourceComponent = sourceComponent,
        timestampMs = timestampMs,
        emissionSeq = emissionSeq
    )

    // ══════════════════════════════════════════════════════════════════════════
    // AndroidCanonicalExecutionEventOwner — constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DECISION_EMIT_EVENT wire value is emit_event`() {
        assertEquals("emit_event", AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT)
    }

    @Test
    fun `DECISION_SUPPRESS_DUPLICATE_EVENT wire value is suppress_duplicate_event`() {
        assertEquals(
            "suppress_duplicate_event",
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT
        )
    }

    @Test
    fun `DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE wire value is suppress_high_frequency_noise`() {
        assertEquals(
            "suppress_high_frequency_noise",
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE
        )
    }

    @Test
    fun `DECISION_SUPPRESS_POST_TERMINAL wire value is suppress_post_terminal`() {
        assertEquals(
            "suppress_post_terminal",
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL
        )
    }

    @Test
    fun `DECISION_HOLD_FOR_RECONNECT_ALIGNMENT wire value is hold_for_reconnect_alignment`() {
        assertEquals(
            "hold_for_reconnect_alignment",
            AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT
        )
    }

    @Test
    fun `All five decision constants are distinct`() {
        val constants = listOf(
            AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT,
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT,
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE,
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL,
            AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT
        )
        assertEquals("All five decision constants must be distinct", constants.size, constants.toSet().size)
    }

    @Test
    fun `INTEGRATION_LOOP_CONTROLLER is LoopController`() {
        assertEquals("LoopController", AndroidCanonicalExecutionEventOwner.INTEGRATION_LOOP_CONTROLLER)
    }

    @Test
    fun `INTEGRATION_PIPELINE is AutonomousExecutionPipeline`() {
        assertEquals(
            "AutonomousExecutionPipeline",
            AndroidCanonicalExecutionEventOwner.INTEGRATION_PIPELINE
        )
    }

    @Test
    fun `INTEGRATION_UNIT is DelegatedRuntimeUnit`() {
        assertEquals("DelegatedRuntimeUnit", AndroidCanonicalExecutionEventOwner.INTEGRATION_UNIT)
    }

    @Test
    fun `INTEGRATION_ACTIVATION_RECORD is DelegatedActivationRecord`() {
        assertEquals(
            "DelegatedActivationRecord",
            AndroidCanonicalExecutionEventOwner.INTEGRATION_ACTIVATION_RECORD
        )
    }

    @Test
    fun `INTEGRATION_RECEIVER is DelegatedRuntimeReceiver`() {
        assertEquals(
            "DelegatedRuntimeReceiver",
            AndroidCanonicalExecutionEventOwner.INTEGRATION_RECEIVER
        )
    }

    @Test
    fun `INTEGRATION_TAKEOVER_EXECUTOR is DelegatedTakeoverExecutor`() {
        assertEquals(
            "DelegatedTakeoverExecutor",
            AndroidCanonicalExecutionEventOwner.INTEGRATION_TAKEOVER_EXECUTOR
        )
    }

    @Test
    fun `INTEGRATION_COLLABORATION_AGENT is LocalCollaborationAgent`() {
        assertEquals(
            "LocalCollaborationAgent",
            AndroidCanonicalExecutionEventOwner.INTEGRATION_COLLABORATION_AGENT
        )
    }

    @Test
    fun `INTRODUCED_PR is 7`() {
        assertEquals(7, AndroidCanonicalExecutionEventOwner.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(AndroidCanonicalExecutionEventOwner.DESCRIPTION.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEventType — wire values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PLANNING_STARTED wireValue is planning_started`() {
        assertEquals("planning_started", CanonicalExecutionEventType.PLANNING_STARTED.wireValue)
    }

    @Test
    fun `PLANNING_PROGRESS wireValue is planning_progress`() {
        assertEquals("planning_progress", CanonicalExecutionEventType.PLANNING_PROGRESS.wireValue)
    }

    @Test
    fun `PLANNING_BLOCKED wireValue is planning_blocked`() {
        assertEquals("planning_blocked", CanonicalExecutionEventType.PLANNING_BLOCKED.wireValue)
    }

    @Test
    fun `GROUNDING_STARTED wireValue is grounding_started`() {
        assertEquals("grounding_started", CanonicalExecutionEventType.GROUNDING_STARTED.wireValue)
    }

    @Test
    fun `GROUNDING_PROGRESS wireValue is grounding_progress`() {
        assertEquals("grounding_progress", CanonicalExecutionEventType.GROUNDING_PROGRESS.wireValue)
    }

    @Test
    fun `GROUNDING_BLOCKED wireValue is grounding_blocked`() {
        assertEquals("grounding_blocked", CanonicalExecutionEventType.GROUNDING_BLOCKED.wireValue)
    }

    @Test
    fun `EXECUTION_STARTED wireValue is execution_started`() {
        assertEquals("execution_started", CanonicalExecutionEventType.EXECUTION_STARTED.wireValue)
    }

    @Test
    fun `EXECUTION_PROGRESS wireValue is execution_progress`() {
        assertEquals("execution_progress", CanonicalExecutionEventType.EXECUTION_PROGRESS.wireValue)
    }

    @Test
    fun `EXECUTION_BLOCKED wireValue is execution_blocked`() {
        assertEquals("execution_blocked", CanonicalExecutionEventType.EXECUTION_BLOCKED.wireValue)
    }

    @Test
    fun `REPLAN_TRIGGERED wireValue is replan_triggered`() {
        assertEquals("replan_triggered", CanonicalExecutionEventType.REPLAN_TRIGGERED.wireValue)
    }

    @Test
    fun `STAGNATION_DETECTED wireValue is stagnation_detected`() {
        assertEquals("stagnation_detected", CanonicalExecutionEventType.STAGNATION_DETECTED.wireValue)
    }

    @Test
    fun `GATE_DECISION_EMITTED wireValue is gate_decision_emitted`() {
        assertEquals("gate_decision_emitted", CanonicalExecutionEventType.GATE_DECISION_EMITTED.wireValue)
    }

    @Test
    fun `TAKEOVER_MILESTONE wireValue is takeover_milestone`() {
        assertEquals("takeover_milestone", CanonicalExecutionEventType.TAKEOVER_MILESTONE.wireValue)
    }

    @Test
    fun `COLLABORATION_MILESTONE wireValue is collaboration_milestone`() {
        assertEquals(
            "collaboration_milestone",
            CanonicalExecutionEventType.COLLABORATION_MILESTONE.wireValue
        )
    }

    @Test
    fun `PARALLEL_MILESTONE wireValue is parallel_milestone`() {
        assertEquals("parallel_milestone", CanonicalExecutionEventType.PARALLEL_MILESTONE.wireValue)
    }

    @Test
    fun `All 15 wire values are distinct`() {
        val wireValues = CanonicalExecutionEventType.entries.map { it.wireValue }
        assertEquals(
            "All 15 event type wire values must be distinct",
            wireValues.size, wireValues.toSet().size
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEventType.fromValue — classification
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `fromValue planning_started returns PLANNING_STARTED`() {
        assertEquals(
            CanonicalExecutionEventType.PLANNING_STARTED,
            CanonicalExecutionEventType.fromValue("planning_started")
        )
    }

    @Test
    fun `fromValue grounding_blocked returns GROUNDING_BLOCKED`() {
        assertEquals(
            CanonicalExecutionEventType.GROUNDING_BLOCKED,
            CanonicalExecutionEventType.fromValue("grounding_blocked")
        )
    }

    @Test
    fun `fromValue execution_progress returns EXECUTION_PROGRESS`() {
        assertEquals(
            CanonicalExecutionEventType.EXECUTION_PROGRESS,
            CanonicalExecutionEventType.fromValue("execution_progress")
        )
    }

    @Test
    fun `fromValue replan_triggered returns REPLAN_TRIGGERED`() {
        assertEquals(
            CanonicalExecutionEventType.REPLAN_TRIGGERED,
            CanonicalExecutionEventType.fromValue("replan_triggered")
        )
    }

    @Test
    fun `fromValue stagnation_detected returns STAGNATION_DETECTED`() {
        assertEquals(
            CanonicalExecutionEventType.STAGNATION_DETECTED,
            CanonicalExecutionEventType.fromValue("stagnation_detected")
        )
    }

    @Test
    fun `fromValue gate_decision_emitted returns GATE_DECISION_EMITTED`() {
        assertEquals(
            CanonicalExecutionEventType.GATE_DECISION_EMITTED,
            CanonicalExecutionEventType.fromValue("gate_decision_emitted")
        )
    }

    @Test
    fun `fromValue takeover_milestone returns TAKEOVER_MILESTONE`() {
        assertEquals(
            CanonicalExecutionEventType.TAKEOVER_MILESTONE,
            CanonicalExecutionEventType.fromValue("takeover_milestone")
        )
    }

    @Test
    fun `fromValue collaboration_milestone returns COLLABORATION_MILESTONE`() {
        assertEquals(
            CanonicalExecutionEventType.COLLABORATION_MILESTONE,
            CanonicalExecutionEventType.fromValue("collaboration_milestone")
        )
    }

    @Test
    fun `fromValue parallel_milestone returns PARALLEL_MILESTONE`() {
        assertEquals(
            CanonicalExecutionEventType.PARALLEL_MILESTONE,
            CanonicalExecutionEventType.fromValue("parallel_milestone")
        )
    }

    @Test
    fun `fromValue unknown string returns DEFAULT`() {
        assertEquals(
            CanonicalExecutionEventType.DEFAULT,
            CanonicalExecutionEventType.fromValue("unknown_type")
        )
    }

    @Test
    fun `fromValue null returns DEFAULT`() {
        assertEquals(
            CanonicalExecutionEventType.DEFAULT,
            CanonicalExecutionEventType.fromValue(null)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // classifyEventType
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `classifyEventType planning_started returns PLANNING_STARTED`() {
        assertEquals(
            CanonicalExecutionEventType.PLANNING_STARTED,
            owner.classifyEventType("planning_started")
        )
    }

    @Test
    fun `classifyEventType execution_blocked returns EXECUTION_BLOCKED`() {
        assertEquals(
            CanonicalExecutionEventType.EXECUTION_BLOCKED,
            owner.classifyEventType("execution_blocked")
        )
    }

    @Test
    fun `classifyEventType replan_triggered returns REPLAN_TRIGGERED`() {
        assertEquals(
            CanonicalExecutionEventType.REPLAN_TRIGGERED,
            owner.classifyEventType("replan_triggered")
        )
    }

    @Test
    fun `classifyEventType unknown returns DEFAULT`() {
        assertEquals(
            CanonicalExecutionEventType.DEFAULT,
            owner.classifyEventType("no_such_type")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateEventEmit — EmitEvent (no suppression)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns EmitEvent for a normal event with no suppression conditions`() {
        val decision = owner.evaluateEventEmit(makeEvent())
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `EmitEvent semanticTag is DECISION_EMIT_EVENT`() {
        val decision = owner.evaluateEventEmit(makeEvent())
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT,
            decision.semanticTag
        )
    }

    @Test
    fun `EmitEvent event is the original event`() {
        val evt = makeEvent()
        val decision = owner.evaluateEventEmit(evt) as CanonicalExecutionEventEmitDecision.EmitEvent
        assertEquals(evt, decision.event)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateEventEmit — SuppressPostTerminal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressPostTerminal after markFlowTerminal`() {
        owner.markFlowTerminal("flow-7", "completed")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressPostTerminal)
    }

    @Test
    fun `SuppressPostTerminal semanticTag is DECISION_SUPPRESS_POST_TERMINAL`() {
        owner.markFlowTerminal("flow-7", "completed")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressPostTerminal flowId matches event flowId`() {
        owner.markFlowTerminal("flow-7", "completed")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
            as CanonicalExecutionEventEmitDecision.SuppressPostTerminal
        assertEquals("flow-7", decision.flowId)
    }

    @Test
    fun `SuppressPostTerminal terminalReason matches registered reason`() {
        owner.markFlowTerminal("flow-7", "failed")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
            as CanonicalExecutionEventEmitDecision.SuppressPostTerminal
        assertEquals("failed", decision.terminalReason)
    }

    @Test
    fun `Post-terminal suppression takes priority over duplicate suppression`() {
        owner.markFlowTerminal("flow-7", "completed")
        owner.markEventEmitted("evt-7-unique")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7", eventId = "evt-7-unique"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressPostTerminal)
    }

    @Test
    fun `Post-terminal suppression takes priority over noise gate suppression`() {
        owner.markFlowTerminal("flow-7", "completed")
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(flowId = "flow-7", eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressPostTerminal)
    }

    @Test
    fun `Post-terminal suppression takes priority over event gate hold`() {
        owner.markFlowTerminal("flow-7", "completed")
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressPostTerminal)
    }

    @Test
    fun `Post-terminal suppression takes priority over emit`() {
        owner.markFlowTerminal("flow-7", "cancelled")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
        assertFalse(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `Different flowId is not suppressed after marking another flow terminal`() {
        owner.markFlowTerminal("flow-A", "completed")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-B"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateEventEmit — SuppressDuplicateEvent
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressDuplicateEvent after markEventEmitted`() {
        owner.markEventEmitted("evt-dup")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-dup"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent)
    }

    @Test
    fun `SuppressDuplicateEvent semanticTag is DECISION_SUPPRESS_DUPLICATE_EVENT`() {
        owner.markEventEmitted("evt-dup")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-dup"))
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressDuplicateEvent flowId matches event flowId`() {
        owner.markEventEmitted("evt-dup")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7", eventId = "evt-dup"))
            as CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent
        assertEquals("flow-7", decision.flowId)
    }

    @Test
    fun `SuppressDuplicateEvent eventId matches the previously emitted event ID`() {
        owner.markEventEmitted("evt-dup")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-dup"))
            as CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent
        assertEquals("evt-dup", decision.eventId)
    }

    @Test
    fun `Duplicate suppression takes priority over noise gate suppression`() {
        owner.markEventEmitted("evt-dup")
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(eventId = "evt-dup", eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent)
    }

    @Test
    fun `Duplicate suppression takes priority over event gate hold`() {
        owner.markEventEmitted("evt-dup")
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-dup"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent)
    }

    @Test
    fun `Duplicate suppression takes priority over emit`() {
        owner.markEventEmitted("evt-dup")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-dup"))
        assertFalse(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `Different eventId is not suppressed after marking another eventId emitted`() {
        owner.markEventEmitted("evt-A")
        val decision = owner.evaluateEventEmit(makeEvent(eventId = "evt-B"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateEventEmit — SuppressHighFrequencyNoise
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns SuppressHighFrequencyNoise after openNoiseGate`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise)
    }

    @Test
    fun `SuppressHighFrequencyNoise semanticTag is DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressHighFrequencyNoise flowId matches event flowId`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(flowId = "flow-7", eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        ) as CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise
        assertEquals("flow-7", decision.flowId)
    }

    @Test
    fun `Noise suppression takes priority over event gate hold`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise)
    }

    @Test
    fun `After closeNoiseGate evaluateEventEmit returns normal decision`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.closeNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `Noise gate for EXECUTION_PROGRESS does not affect EXECUTION_STARTED events`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(
                eventType = CanonicalExecutionEventType.EXECUTION_STARTED,
                phase = CanonicalExecutionEvent.PHASE_EXECUTION
            )
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `Different flowId is not suppressed by noise gate on another flow`() {
        owner.openNoiseGate("flow-A", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        val decision = owner.evaluateEventEmit(
            makeEvent(flowId = "flow-B", eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // evaluateEventEmit — HoldForReconnectAlignment
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns HoldForReconnectAlignment after closeEventGate`() {
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent())
        assertTrue(decision is CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment)
    }

    @Test
    fun `HoldForReconnectAlignment semanticTag is DECISION_HOLD_FOR_RECONNECT_ALIGNMENT`() {
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent())
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT,
            decision.semanticTag
        )
    }

    @Test
    fun `HoldForReconnectAlignment flowId matches the gated flow`() {
        owner.closeEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-7"))
            as CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment
        assertEquals("flow-7", decision.flowId)
    }

    @Test
    fun `After openEventGate evaluateEventEmit returns EmitEvent`() {
        owner.closeEventGate("flow-7")
        owner.openEventGate("flow-7")
        val decision = owner.evaluateEventEmit(makeEvent())
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    @Test
    fun `Different flowId is not held after gating another flow`() {
        owner.closeEventGate("flow-A")
        val decision = owner.evaluateEventEmit(makeEvent(flowId = "flow-B"))
        assertTrue(decision is CanonicalExecutionEventEmitDecision.EmitEvent)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Terminal flow registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isFlowTerminal is false initially`() {
        assertFalse(owner.isFlowTerminal("flow-7"))
    }

    @Test
    fun `isFlowTerminal is true after markFlowTerminal`() {
        owner.markFlowTerminal("flow-7", "completed")
        assertTrue(owner.isFlowTerminal("flow-7"))
    }

    @Test
    fun `flowTerminalReason is null when not terminal`() {
        assertNull(owner.flowTerminalReason("flow-7"))
    }

    @Test
    fun `flowTerminalReason returns the registered reason after markFlowTerminal`() {
        owner.markFlowTerminal("flow-7", "failed")
        assertEquals("failed", owner.flowTerminalReason("flow-7"))
    }

    @Test
    fun `isFlowTerminal is false after clearFlowTerminal`() {
        owner.markFlowTerminal("flow-7", "completed")
        owner.clearFlowTerminal("flow-7")
        assertFalse(owner.isFlowTerminal("flow-7"))
    }

    @Test
    fun `terminalFlowCount is 0 initially`() {
        assertEquals(0, owner.terminalFlowCount)
    }

    @Test
    fun `terminalFlowCount increments with markFlowTerminal`() {
        owner.markFlowTerminal("flow-X", "completed")
        owner.markFlowTerminal("flow-Y", "failed")
        assertEquals(2, owner.terminalFlowCount)
    }

    @Test
    fun `terminalFlowCount decrements with clearFlowTerminal`() {
        owner.markFlowTerminal("flow-X", "completed")
        owner.markFlowTerminal("flow-Y", "failed")
        owner.clearFlowTerminal("flow-X")
        assertEquals(1, owner.terminalFlowCount)
    }

    @Test
    fun `clearAllTerminalFlows resets count to 0`() {
        owner.markFlowTerminal("flow-X", "completed")
        owner.markFlowTerminal("flow-Y", "failed")
        owner.clearAllTerminalFlows()
        assertEquals(0, owner.terminalFlowCount)
    }

    @Test
    fun `Multiple distinct flowIds can all be marked terminal simultaneously`() {
        for (i in 1..5) {
            owner.markFlowTerminal("flow-$i", "completed")
        }
        assertEquals(5, owner.terminalFlowCount)
        for (i in 1..5) {
            assertTrue(owner.isFlowTerminal("flow-$i"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Emitted event ID registry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isEventEmitted is false initially`() {
        assertFalse(owner.isEventEmitted("evt-new"))
    }

    @Test
    fun `isEventEmitted is true after markEventEmitted`() {
        owner.markEventEmitted("evt-new")
        assertTrue(owner.isEventEmitted("evt-new"))
    }

    @Test
    fun `emittedEventCount is 0 initially`() {
        assertEquals(0, owner.emittedEventCount)
    }

    @Test
    fun `emittedEventCount increments with markEventEmitted`() {
        owner.markEventEmitted("evt-A")
        owner.markEventEmitted("evt-B")
        assertEquals(2, owner.emittedEventCount)
    }

    @Test
    fun `clearAllEmittedEvents resets count to 0`() {
        owner.markEventEmitted("evt-A")
        owner.markEventEmitted("evt-B")
        owner.clearAllEmittedEvents()
        assertEquals(0, owner.emittedEventCount)
    }

    @Test
    fun `Multiple distinct event IDs can all be marked emitted simultaneously`() {
        for (i in 1..5) {
            owner.markEventEmitted("evt-$i")
        }
        assertEquals(5, owner.emittedEventCount)
        for (i in 1..5) {
            assertTrue(owner.isEventEmitted("evt-$i"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Event gate
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isEventGated is false initially`() {
        assertFalse(owner.isEventGated("flow-7"))
    }

    @Test
    fun `isEventGated is true after closeEventGate`() {
        owner.closeEventGate("flow-7")
        assertTrue(owner.isEventGated("flow-7"))
    }

    @Test
    fun `isEventGated is false after openEventGate`() {
        owner.closeEventGate("flow-7")
        owner.openEventGate("flow-7")
        assertFalse(owner.isEventGated("flow-7"))
    }

    @Test
    fun `eventGatedFlowCount is 0 initially`() {
        assertEquals(0, owner.eventGatedFlowCount)
    }

    @Test
    fun `eventGatedFlowCount increments with closeEventGate`() {
        owner.closeEventGate("flow-A")
        owner.closeEventGate("flow-B")
        assertEquals(2, owner.eventGatedFlowCount)
    }

    @Test
    fun `eventGatedFlowCount decrements with openEventGate`() {
        owner.closeEventGate("flow-A")
        owner.closeEventGate("flow-B")
        owner.openEventGate("flow-A")
        assertEquals(1, owner.eventGatedFlowCount)
    }

    @Test
    fun `clearAllEventGates resets count to 0`() {
        owner.closeEventGate("flow-A")
        owner.closeEventGate("flow-B")
        owner.clearAllEventGates()
        assertEquals(0, owner.eventGatedFlowCount)
    }

    @Test
    fun `Multiple distinct flowIds can all be gated simultaneously`() {
        for (i in 1..4) {
            owner.closeEventGate("flow-$i")
        }
        assertEquals(4, owner.eventGatedFlowCount)
        for (i in 1..4) {
            assertTrue(owner.isEventGated("flow-$i"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Noise gate
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isNoiseGated is false initially`() {
        assertFalse(
            owner.isNoiseGated("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
    }

    @Test
    fun `isNoiseGated is true after openNoiseGate`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        assertTrue(
            owner.isNoiseGated("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
    }

    @Test
    fun `isNoiseGated is false after closeNoiseGate`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.closeNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        assertFalse(
            owner.isNoiseGated("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
    }

    @Test
    fun `noiseGatedEntryCount is 0 initially`() {
        assertEquals(0, owner.noiseGatedEntryCount)
    }

    @Test
    fun `noiseGatedEntryCount increments with openNoiseGate`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.PLANNING_PROGRESS)
        assertEquals(2, owner.noiseGatedEntryCount)
    }

    @Test
    fun `noiseGatedEntryCount decrements with closeNoiseGate`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.PLANNING_PROGRESS)
        owner.closeNoiseGate("flow-7", CanonicalExecutionEventType.PLANNING_PROGRESS)
        assertEquals(1, owner.noiseGatedEntryCount)
    }

    @Test
    fun `clearAllNoiseGates resets count to 0`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.PLANNING_PROGRESS)
        owner.clearAllNoiseGates()
        assertEquals(0, owner.noiseGatedEntryCount)
    }

    @Test
    fun `Different event types on the same flow are gated independently`() {
        owner.openNoiseGate("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        assertTrue(
            owner.isNoiseGated("flow-7", CanonicalExecutionEventType.EXECUTION_PROGRESS)
        )
        assertFalse(
            owner.isNoiseGated("flow-7", CanonicalExecutionEventType.PLANNING_PROGRESS)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEvent factory methods
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `planning() produces an event with phase planning`() {
        val evt = CanonicalExecutionEvent.planning(
            eventType = CanonicalExecutionEventType.PLANNING_STARTED,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            reason = "planning started",
            sourceComponent = "LoopController",
            emissionSeq = 1L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_PLANNING, evt.phase)
        assertEquals(CanonicalExecutionEventType.PLANNING_STARTED, evt.eventType)
    }

    @Test
    fun `grounding() produces an event with phase grounding`() {
        val evt = CanonicalExecutionEvent.grounding(
            eventType = CanonicalExecutionEventType.GROUNDING_STARTED,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = 0,
            reason = "grounding started",
            sourceComponent = "LoopController",
            emissionSeq = 2L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_GROUNDING, evt.phase)
        assertEquals(CanonicalExecutionEventType.GROUNDING_STARTED, evt.eventType)
    }

    @Test
    fun `execution() produces an event with phase execution`() {
        val evt = CanonicalExecutionEvent.execution(
            eventType = CanonicalExecutionEventType.EXECUTION_STARTED,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = null,
            reason = "execution started",
            sourceComponent = "AutonomousExecutionPipeline",
            emissionSeq = 3L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_EXECUTION, evt.phase)
        assertEquals(CanonicalExecutionEventType.EXECUTION_STARTED, evt.eventType)
    }

    @Test
    fun `replan() produces a REPLAN_TRIGGERED event with replanReason set`() {
        val evt = CanonicalExecutionEvent.replan(
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = 3,
            replanReason = "step_retry_exhausted",
            sourceComponent = "LoopController",
            emissionSeq = 4L
        )
        assertEquals(CanonicalExecutionEventType.REPLAN_TRIGGERED, evt.eventType)
        assertEquals("step_retry_exhausted", evt.replanReason)
        assertEquals(CanonicalExecutionEvent.PHASE_REPLAN, evt.phase)
    }

    @Test
    fun `stagnation() produces a STAGNATION_DETECTED event with stagnationReason set`() {
        val evt = CanonicalExecutionEvent.stagnation(
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = 5,
            stagnationReason = "repeated_action_no_ui_change",
            sourceComponent = "LoopController",
            emissionSeq = 5L
        )
        assertEquals(CanonicalExecutionEventType.STAGNATION_DETECTED, evt.eventType)
        assertEquals("repeated_action_no_ui_change", evt.stagnationReason)
        assertEquals(CanonicalExecutionEvent.PHASE_STAGNATION, evt.phase)
    }

    @Test
    fun `gate() produces a GATE_DECISION_EMITTED event with gateRationale set`() {
        val evt = CanonicalExecutionEvent.gate(
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            gateRationale = "eligibility_assessor_passed",
            sourceComponent = "AutonomousExecutionPipeline",
            emissionSeq = 6L
        )
        assertEquals(CanonicalExecutionEventType.GATE_DECISION_EMITTED, evt.eventType)
        assertEquals("eligibility_assessor_passed", evt.gateRationale)
        assertEquals(CanonicalExecutionEvent.PHASE_GATE, evt.phase)
    }

    @Test
    fun `milestone(TAKEOVER_MILESTONE) produces phase takeover`() {
        val evt = CanonicalExecutionEvent.milestone(
            eventType = CanonicalExecutionEventType.TAKEOVER_MILESTONE,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            milestoneName = "ack_accepted",
            sourceComponent = "DelegatedTakeoverExecutor",
            emissionSeq = 7L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_TAKEOVER, evt.phase)
        assertEquals(CanonicalExecutionEventType.TAKEOVER_MILESTONE, evt.eventType)
    }

    @Test
    fun `milestone(COLLABORATION_MILESTONE) produces phase collaboration`() {
        val evt = CanonicalExecutionEvent.milestone(
            eventType = CanonicalExecutionEventType.COLLABORATION_MILESTONE,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            milestoneName = "subtask_started",
            sourceComponent = "LocalCollaborationAgent",
            emissionSeq = 8L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_COLLABORATION, evt.phase)
    }

    @Test
    fun `milestone(PARALLEL_MILESTONE) produces phase parallel`() {
        val evt = CanonicalExecutionEvent.milestone(
            eventType = CanonicalExecutionEventType.PARALLEL_MILESTONE,
            flowId = "flow-7",
            taskId = "task-7",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            milestoneName = "arm_completed",
            sourceComponent = "LocalCollaborationAgent",
            emissionSeq = 9L
        )
        assertEquals(CanonicalExecutionEvent.PHASE_PARALLEL, evt.phase)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEvent.toMetadataMap
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toMetadataMap includes required fields`() {
        val evt = makeEvent(
            flowId = "flow-map",
            taskId = "task-map",
            eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS,
            eventId = "evt-map",
            timestampMs = 9_000L,
            emissionSeq = 42L
        )
        val map = evt.toMetadataMap()
        assertEquals("evt-map", map["event_id"])
        assertEquals("execution_progress", map["event_type"])
        assertEquals("flow-map", map["flow_id"])
        assertEquals("task-map", map["task_id"])
        assertEquals(CanonicalExecutionEvent.PHASE_EXECUTION, map["phase"])
        assertEquals(AndroidCanonicalExecutionEventOwner.INTEGRATION_LOOP_CONTROLLER, map["source_component"])
        assertEquals(9_000L, map["timestamp_ms"])
        assertEquals(42L, map["emission_seq"])
    }

    @Test
    fun `toMetadataMap omits null optional fields`() {
        val evt = makeEvent(traceId = null, delegatedLineageId = null, attachedSessionId = null)
        val map = evt.toMetadataMap()
        assertFalse("trace_id should be absent when null", map.containsKey("trace_id"))
        assertFalse(
            "delegated_lineage_id should be absent when null",
            map.containsKey("delegated_lineage_id")
        )
        assertFalse(
            "attached_session_id should be absent when null",
            map.containsKey("attached_session_id")
        )
    }

    @Test
    fun `toMetadataMap includes blocking_rationale when non-null`() {
        val evt = makeEvent(blockingRationale = "model_unavailable")
        val map = evt.toMetadataMap()
        assertEquals("model_unavailable", map["blocking_rationale"])
    }

    @Test
    fun `toMetadataMap includes replan_reason when non-null`() {
        val evt = CanonicalExecutionEvent.replan(
            flowId = "f",
            taskId = "t",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = 1,
            replanReason = "retry_exhausted",
            sourceComponent = "LoopController",
            emissionSeq = 1L
        )
        val map = evt.toMetadataMap()
        assertEquals("retry_exhausted", map["replan_reason"])
    }

    @Test
    fun `toMetadataMap includes stagnation_reason when non-null`() {
        val evt = CanonicalExecutionEvent.stagnation(
            flowId = "f",
            taskId = "t",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            stepIndex = 2,
            stagnationReason = "no_ui_change",
            sourceComponent = "LoopController",
            emissionSeq = 2L
        )
        val map = evt.toMetadataMap()
        assertEquals("no_ui_change", map["stagnation_reason"])
    }

    @Test
    fun `toMetadataMap includes gate_rationale when non-null`() {
        val evt = CanonicalExecutionEvent.gate(
            flowId = "f",
            taskId = "t",
            traceId = null,
            delegatedLineageId = null,
            attachedSessionId = null,
            gateRationale = "posture_gate_passed",
            sourceComponent = "AutonomousExecutionPipeline",
            emissionSeq = 3L
        )
        val map = evt.toMetadataMap()
        assertEquals("posture_gate_passed", map["gate_rationale"])
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEvent derived helpers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isBlocked is true for PLANNING_BLOCKED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.PLANNING_BLOCKED).isBlocked
        )
    }

    @Test
    fun `isBlocked is true for GROUNDING_BLOCKED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.GROUNDING_BLOCKED).isBlocked
        )
    }

    @Test
    fun `isBlocked is true for EXECUTION_BLOCKED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_BLOCKED).isBlocked
        )
    }

    @Test
    fun `isBlocked is false for EXECUTION_PROGRESS`() {
        assertFalse(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS).isBlocked
        )
    }

    @Test
    fun `isPhaseStart is true for PLANNING_STARTED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.PLANNING_STARTED).isPhaseStart
        )
    }

    @Test
    fun `isPhaseStart is true for GROUNDING_STARTED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.GROUNDING_STARTED).isPhaseStart
        )
    }

    @Test
    fun `isPhaseStart is true for EXECUTION_STARTED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_STARTED).isPhaseStart
        )
    }

    @Test
    fun `isPhaseStart is false for REPLAN_TRIGGERED`() {
        assertFalse(
            makeEvent(eventType = CanonicalExecutionEventType.REPLAN_TRIGGERED).isPhaseStart
        )
    }

    @Test
    fun `isRecoveryTrigger is true for REPLAN_TRIGGERED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.REPLAN_TRIGGERED).isRecoveryTrigger
        )
    }

    @Test
    fun `isRecoveryTrigger is true for STAGNATION_DETECTED`() {
        assertTrue(
            makeEvent(eventType = CanonicalExecutionEventType.STAGNATION_DETECTED).isRecoveryTrigger
        )
    }

    @Test
    fun `isRecoveryTrigger is false for EXECUTION_PROGRESS`() {
        assertFalse(
            makeEvent(eventType = CanonicalExecutionEventType.EXECUTION_PROGRESS).isRecoveryTrigger
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CanonicalExecutionEventEmitDecision — semanticTag values
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EmitEvent semanticTag matches DECISION_EMIT_EVENT`() {
        val decision = CanonicalExecutionEventEmitDecision.EmitEvent(event = makeEvent())
        assertEquals(AndroidCanonicalExecutionEventOwner.DECISION_EMIT_EVENT, decision.semanticTag)
    }

    @Test
    fun `SuppressDuplicateEvent semanticTag matches DECISION_SUPPRESS_DUPLICATE_EVENT`() {
        val decision = CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent(
            flowId = "f", eventId = "e"
        )
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_DUPLICATE_EVENT,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressHighFrequencyNoise semanticTag matches DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE`() {
        val decision = CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise(
            flowId = "f", reason = "r"
        )
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_HIGH_FREQUENCY_NOISE,
            decision.semanticTag
        )
    }

    @Test
    fun `SuppressPostTerminal semanticTag matches DECISION_SUPPRESS_POST_TERMINAL`() {
        val decision = CanonicalExecutionEventEmitDecision.SuppressPostTerminal(
            flowId = "f", terminalReason = "completed"
        )
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_SUPPRESS_POST_TERMINAL,
            decision.semanticTag
        )
    }

    @Test
    fun `HoldForReconnectAlignment semanticTag matches DECISION_HOLD_FOR_RECONNECT_ALIGNMENT`() {
        val decision = CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment(
            flowId = "f", reason = "reconnect"
        )
        assertEquals(
            AndroidCanonicalExecutionEventOwner.DECISION_HOLD_FOR_RECONNECT_ALIGNMENT,
            decision.semanticTag
        )
    }

    @Test
    fun `All five semanticTag values are distinct`() {
        val tags = listOf(
            CanonicalExecutionEventEmitDecision.EmitEvent(event = makeEvent()).semanticTag,
            CanonicalExecutionEventEmitDecision.SuppressDuplicateEvent("f", "e").semanticTag,
            CanonicalExecutionEventEmitDecision.SuppressHighFrequencyNoise("f", "r").semanticTag,
            CanonicalExecutionEventEmitDecision.SuppressPostTerminal("f", "completed").semanticTag,
            CanonicalExecutionEventEmitDecision.HoldForReconnectAlignment("f", "r").semanticTag
        )
        assertEquals("All five semanticTag values must be distinct", tags.size, tags.toSet().size)
    }
}
