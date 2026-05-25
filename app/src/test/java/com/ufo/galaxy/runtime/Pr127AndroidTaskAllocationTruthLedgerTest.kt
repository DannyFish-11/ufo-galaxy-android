package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr127AndroidTaskAllocationTruthLedgerTest {

    private fun hostDescriptor() = RuntimeHostDescriptor(
        hostId = "host-127",
        deviceId = "device-127",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    @Test
    fun `ledger records accepted status and closure transitions as authoritative allocation truth`() {
        val ledger = AndroidTaskAllocationTruthLedger()
        ledger.recordAccepted(
            taskId = "task-127",
            participantId = "participant-127",
            hostDescriptor = hostDescriptor(),
            fallbackAllowed = true,
            nowMs = 1_000L
        )
        ledger.recordStatus("task-127", ActiveTaskStatus.CANCELLING, nowMs = 1_100L)
        ledger.recordClosed(
            taskId = "task-127",
            closureClass = TaskAllocationClosureClass.CANCELLED,
            requiresCanonicalReconciliation = false,
            nowMs = 1_200L
        )

        val snapshot = ledger.snapshot(activeTaskId = null)
        val record = snapshot.recentTaskAllocations.firstOrNull { it.taskId == "task-127" }
        assertNotNull(record)
        assertEquals(TaskAllocationPhase.CLOSED, record!!.participantLocalPhase)
        assertEquals(TaskAllocationClosureClass.CANCELLED, record.closureClass)
        assertFalse(record.inFlightOwnership)
        assertEquals(TaskAllocationPathClass.CANONICAL_FALLBACK_LOCAL, record.fallbackPathClass)
        assertTrue(record.transitions.any { it.event == TaskAllocationTransitionEvent.CLOSURE_RECORDED })
    }

    @Test
    fun `ledger snapshot survives durable serialization roundtrip`() {
        val ledger = AndroidTaskAllocationTruthLedger()
        ledger.recordAccepted(
            taskId = "task-restore",
            participantId = "participant-restore",
            hostDescriptor = hostDescriptor(),
            fallbackAllowed = false,
            nowMs = 2_000L
        )
        val raw = ledger.toJson(activeTaskId = "task-restore")

        val restored = AndroidTaskAllocationTruthLedger()
        restored.restore(raw)
        val snapshot = restored.snapshot(activeTaskId = "task-restore")
        assertEquals("task-restore", snapshot.activeTaskId)
        assertEquals(1, snapshot.recentTaskAllocations.size)
        assertEquals(
            TaskAllocationPathClass.CANONICAL_DELEGATED_DISPATCH,
            snapshot.recentTaskAllocations.first().allocationPathClass
        )
    }
}
