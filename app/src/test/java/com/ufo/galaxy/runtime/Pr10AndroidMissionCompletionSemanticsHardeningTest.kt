package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr10AndroidMissionCompletionSemanticsHardeningTest {

    private val gson = Gson()

    private fun asJsonObject(any: Any): JsonObject =
        gson.fromJson(gson.toJson(any), JsonObject::class.java)

    @Test
    fun `completion outcome is classified as completion`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            status = EdgeExecutor.STATUS_SUCCESS
        )
        assertEquals("completion", outcome.wireValue)
    }

    @Test
    fun `partial completion is classified from completed details`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            status = EdgeExecutor.STATUS_SUCCESS,
            details = "partial execution due to limited capability"
        )
        assertEquals("partial_completion", outcome.wireValue)
    }

    @Test
    fun `failed timeout is classified as timeout`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            status = EdgeExecutor.STATUS_TIMEOUT
        )
        assertEquals("timeout", outcome.wireValue)
    }

    @Test
    fun `cancelled with interruption reason is classified as interruption`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_CANCELLED,
            status = EdgeExecutor.STATUS_CANCELLED,
            interruptionReason = "ws_disconnect"
        )
        assertEquals("interruption", outcome.wireValue)
    }

    @Test
    fun `cancelled without interruption reason is classified as abort`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_CANCELLED,
            status = EdgeExecutor.STATUS_CANCELLED
        )
        assertEquals("abort", outcome.wireValue)
    }

    @Test
    fun `fallback transition is classified as fallback`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION
        )
        assertEquals("fallback", outcome.wireValue)
    }

    @Test
    fun `completed with recovery evidence is classified as recovery`() {
        val outcome = AndroidMissionCompletionSemanticsContract.classifyLocalTerminalOutcome(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            status = EdgeExecutor.STATUS_SUCCESS,
            interruptionReason = "recovered_after_disconnect"
        )
        assertEquals("recovery", outcome.wireValue)
    }

    @Test
    fun `interruption local outcome maps to authoritative interruption result semantic`() {
        val semantic = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.INTERRUPTION
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION.wireValue,
            semantic.wireValue
        )
    }

    @Test
    fun `recovery local outcome maps to authoritative recovery result semantic`() {
        val semantic = AndroidMissionCompletionSemanticsContract.classifyReportedResultSemantic(
            AndroidMissionCompletionSemanticsContract.TerminalOutcomeKind.RECOVERY
        )
        assertEquals(
            AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY.wireValue,
            semantic.wireValue
        )
    }

    @Test
    fun `execution event terminal_outcome_kind defaults to null when unset`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow",
            task_id = "task",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS
        )
        assertNull(event.terminal_outcome_kind)
    }

    @Test
    fun `execution event terminal semantics fields round-trip with stable wire keys`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow",
            task_id = "task",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            reported_state_semantic_class = "terminal_reporting",
            result_uplink_semantic_class = "authoritative_terminal",
            terminal_outcome_kind = "timeout",
            result_returned = true,
            completion_signaled = true,
            closure_ready_for_acceptance = true,
            authority_runtime_completion_signal_class = "authority_runtime_completion",
            result_completion_signal_class = "result_completed",
            closure_finalization_signal_class = "session_finalization_ready",
            operator_done_projection_class = "operator_visible_done_summary",
            completion_closure_uplink_schema_version = "1",
            local_observation_basis = "cached_state"
        )

        val json = asJsonObject(event)
        assertEquals("terminal_reporting", json["reported_state_semantic_class"].asString)
        assertEquals("authoritative_terminal", json["result_uplink_semantic_class"].asString)
        assertEquals("timeout", json["terminal_outcome_kind"].asString)
        assertTrue(json["result_returned"].asBoolean)
        assertTrue(json["completion_signaled"].asBoolean)
        assertTrue(json["closure_ready_for_acceptance"].asBoolean)
        assertEquals("authority_runtime_completion", json["authority_runtime_completion_signal_class"].asString)
        assertEquals("result_completed", json["result_completion_signal_class"].asString)
        assertEquals("session_finalization_ready", json["closure_finalization_signal_class"].asString)
        assertEquals("operator_visible_done_summary", json["operator_done_projection_class"].asString)
        assertEquals("1", json["completion_closure_uplink_schema_version"].asString)
        assertEquals("cached_state", json["local_observation_basis"].asString)
    }

    @Test
    fun `terminal phase derives completion visibility as true across closure fields`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            lifecycleTerminalPhase = true
        )
        assertTrue(visibility.resultReturned)
        assertTrue(visibility.completionSignaled)
        assertTrue(visibility.closureReadyForAcceptance)
    }

    @Test
    fun `non terminal phase keeps completion visibility false`() {
        val visibility = AndroidMissionCompletionSemanticsContract.deriveExecutionCompletionVisibility(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            lifecycleTerminalPhase = false
        )
        assertFalse(visibility.resultReturned)
        assertFalse(visibility.completionSignaled)
        assertFalse(visibility.closureReadyForAcceptance)
    }
}
