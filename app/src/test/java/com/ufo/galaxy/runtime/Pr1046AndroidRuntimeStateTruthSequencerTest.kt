package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-1046 Android companion — deterministic runtime-state truth shaping tests.
 *
 * Covers explicit busy/idle transitions and deterministic freshness/ordering stamps on the
 * existing Android emission path used by DEVICE_EXECUTION_EVENT and DEVICE_STATE_SNAPSHOT.
 */
class Pr1046AndroidRuntimeStateTruthSequencerTest {

    @Test
    fun `execution start marks busy and increments active count`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        val stamp = sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            taskId = "task-a"
        )
        assertEquals(1L, stamp.eventSequence)
        assertEquals(1, stamp.activeExecutionCount)
        assertTrue(stamp.executionBusy)
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.wireValue,
            stamp.executionLifecyclePhase
        )
        assertNull(stamp.previousExecutionLifecyclePhase)
        assertTrue(stamp.lifecycleTransitionValid)
        assertFalse(stamp.lifecycleResultUplinkRequired)
        assertTrue(stamp.lifecycleStateUplinkRequired)
        assertFalse(stamp.lifecycleTerminalPhase)
    }

    @Test
    fun `terminal phase decrements count and can transition to idle`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            taskId = "task-a"
        )
        val terminal = sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            taskId = "task-a"
        )
        assertEquals(0, terminal.activeExecutionCount)
        assertFalse(terminal.executionBusy)
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED.wireValue,
            terminal.executionLifecyclePhase
        )
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.wireValue,
            terminal.previousExecutionLifecyclePhase
        )
        assertTrue(terminal.lifecycleTransitionValid)
        assertTrue(terminal.lifecycleResultUplinkRequired)
        assertTrue(terminal.lifecycleStateUplinkRequired)
        assertTrue(terminal.lifecycleTerminalPhase)
    }

    @Test
    fun `invalid lifecycle transition is marked false for remote governance diagnostics`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            taskId = "task-a"
        )
        val invalid = sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            taskId = "task-a"
        )

        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.wireValue,
            invalid.executionLifecyclePhase
        )
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED.wireValue,
            invalid.previousExecutionLifecyclePhase
        )
        assertFalse(invalid.lifecycleTransitionValid)
    }

    @Test
    fun `non-terminal phase keeps current busy state`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED)
        val progress = sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS)
        assertEquals(1, progress.activeExecutionCount)
        assertTrue(progress.executionBusy)
    }

    @Test
    fun `terminal phase cannot make active execution count negative`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        val failed = sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_FAILED)
        assertEquals(0, failed.activeExecutionCount)
        assertFalse(failed.executionBusy)
    }

    @Test
    fun `event timestamps are monotonic even when requested timestamp goes backwards`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        val first = sequencer.nextEventStamp(
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            requestedTimestampMs = 10_000L
        )
        val second = sequencer.nextEventStamp(
            DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            requestedTimestampMs = 9_000L
        )
        assertTrue(second.timestampMs > first.timestampMs)
    }

    @Test
    fun `snapshot timestamps and sequence are monotonic`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        val first = sequencer.nextSnapshotStamp(requestedTimestampMs = 100L)
        val second = sequencer.nextSnapshotStamp(requestedTimestampMs = 90L)
        assertEquals(1L, first.snapshotSequence)
        assertEquals(2L, second.snapshotSequence)
        assertTrue(second.timestampMs > first.timestampMs)
    }

    @Test
    fun `snapshot reflects current busy state derived from execution transitions`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED)
        val busySnapshot = sequencer.nextSnapshotStamp()
        assertEquals(1, busySnapshot.activeExecutionCount)
        assertTrue(busySnapshot.executionBusy)

        sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_CANCELLED)
        val idleSnapshot = sequencer.nextSnapshotStamp()
        assertEquals(0, idleSnapshot.activeExecutionCount)
        assertFalse(idleSnapshot.executionBusy)
    }

    @Test
    fun `fallback and handoff-like phases do not silently clear busy state`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED)
        val fallback = sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION)
        val takeover = sequencer.nextEventStamp(DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE)
        assertEquals(1, fallback.activeExecutionCount)
        assertEquals(1, takeover.activeExecutionCount)
        assertTrue(fallback.executionBusy)
        assertTrue(takeover.executionBusy)
    }

    @Test
    fun `fallback transition is projected as interrupted lifecycle truth`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            taskId = "task-a"
        )
        val fallback = sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            taskId = "task-a"
        )

        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED.wireValue,
            fallback.executionLifecyclePhase
        )
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE.wireValue,
            fallback.previousExecutionLifecyclePhase
        )
        assertTrue(fallback.lifecycleTransitionValid)
        assertTrue(fallback.lifecycleResultUplinkRequired)
        assertTrue(fallback.lifecycleStateUplinkRequired)
        assertFalse(fallback.lifecycleTerminalPhase)
    }

    @Test
    fun `retry re-entry start is projected as activating after retrying`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            taskId = "task-a"
        )
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            taskId = "task-a"
        )
        sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE,
            taskId = "task-a"
        )
        val restart = sequencer.nextEventStamp(
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            taskId = "task-a"
        )

        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING.wireValue,
            restart.executionLifecyclePhase
        )
        assertEquals(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING.wireValue,
            restart.previousExecutionLifecyclePhase
        )
        assertTrue(restart.lifecycleTransitionValid)
        assertFalse(restart.lifecycleResultUplinkRequired)
        assertTrue(restart.lifecycleStateUplinkRequired)
    }

    @Test
    fun `reconnect-like backward clock inputs keep global ordering stable`() {
        val sequencer = RuntimeStateTruthSequencer { 1_700_000_000_000L }
        val e1 = sequencer.nextEventStamp(
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            requestedTimestampMs = 50_000L
        )
        val s1 = sequencer.nextSnapshotStamp(requestedTimestampMs = 50_000L)
        val e2 = sequencer.nextEventStamp(
            DeviceExecutionEventPayload.PHASE_COMPLETED,
            requestedTimestampMs = 49_000L
        )
        val s2 = sequencer.nextSnapshotStamp(requestedTimestampMs = 49_000L)

        assertTrue(e2.timestampMs > e1.timestampMs)
        assertTrue(s2.timestampMs > s1.timestampMs)
        assertEquals(1L, e1.eventSequence)
        assertEquals(2L, e2.eventSequence)
        assertEquals(1L, s1.snapshotSequence)
        assertEquals(2L, s2.snapshotSequence)
    }
}
