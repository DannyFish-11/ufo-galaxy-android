package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr7AndroidExecutionGovernanceContractTest {

    @Test
    fun `takeover has highest priority and does not support task_cancel`() {
        val takeover = AndroidExecutionGovernanceContract.ExecutionType.TAKEOVER_REQUEST
        val goal = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION
        val parallel = AndroidExecutionGovernanceContract.ExecutionType.PARALLEL_SUBTASK

        assertTrue(takeover.priority > goal.priority)
        assertTrue(takeover.priority > parallel.priority)
        assertFalse(takeover.supportsTaskCancel)
    }

    @Test
    fun `goal and parallel support task_cancel and share timeout authority`() {
        val goal = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION
        val parallel = AndroidExecutionGovernanceContract.ExecutionType.PARALLEL_SUBTASK

        assertTrue(goal.supportsTaskCancel)
        assertTrue(parallel.supportsTaskCancel)
        assertEquals(goal.timeoutAuthority, parallel.timeoutAuthority)
        assertEquals(goal.failureAuthority, parallel.failureAuthority)
    }

    @Test
    fun `takeover timeout and failure semantics are delegated-executor based`() {
        val takeover = AndroidExecutionGovernanceContract.ExecutionType.TAKEOVER_REQUEST

        assertEquals("takeover_request.timeout_ms/default", takeover.timeoutAuthority)
        assertEquals("delegated_takeover_executor.result_kind", takeover.failureAuthority)
    }

    @Test
    fun `active takeover rejects goal_execution with unified conflict reason`() {
        val decision = AndroidExecutionGovernanceContract.evaluateAcceptance(
            executionType = AndroidExecutionGovernanceContract.ExecutionType.GOAL_EXECUTION,
            context = AndroidExecutionGovernanceContract.AcceptanceContext(activeTakeoverId = "to-1")
        )

        val rejected = decision as AndroidExecutionGovernanceContract.AcceptanceDecision.Rejected
        assertEquals(
            "reject_active_takeover_conflict:to-1",
            rejected.reason
        )
    }

    @Test
    fun `active takeover rejects parallel_subtask with unified conflict reason`() {
        val decision = AndroidExecutionGovernanceContract.evaluateAcceptance(
            executionType = AndroidExecutionGovernanceContract.ExecutionType.PARALLEL_SUBTASK,
            context = AndroidExecutionGovernanceContract.AcceptanceContext(activeTakeoverId = "to-2")
        )

        val rejected = decision as AndroidExecutionGovernanceContract.AcceptanceDecision.Rejected
        assertEquals(
            "reject_active_takeover_conflict:to-2",
            rejected.reason
        )
    }

    @Test
    fun `active takeover does not auto-reject takeover_request`() {
        val decision = AndroidExecutionGovernanceContract.evaluateAcceptance(
            executionType = AndroidExecutionGovernanceContract.ExecutionType.TAKEOVER_REQUEST,
            context = AndroidExecutionGovernanceContract.AcceptanceContext(activeTakeoverId = "to-3")
        )

        assertTrue(decision is AndroidExecutionGovernanceContract.AcceptanceDecision.Accepted)
    }
}
