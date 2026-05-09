package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Produces deterministic Android runtime-state ordering and busy/idle projections for
 * DEVICE_STATE_SNAPSHOT and DEVICE_EXECUTION_EVENT emissions.
 *
 * This class intentionally does not create a parallel reporting path; it only shapes fields on
 * the existing canonical Android -> V2 uplink path.
 */
class RuntimeStateTruthSequencer(
    private val wallClockMs: () -> Long = { System.currentTimeMillis() }
) {
    data class SnapshotStamp(
        val timestampMs: Long,
        val snapshotSequence: Long,
        val activeExecutionCount: Int,
        val executionBusy: Boolean
    )

    data class EventStamp(
        val timestampMs: Long,
        val eventSequence: Long,
        val activeExecutionCount: Int,
        val executionBusy: Boolean
    )

    private val snapshotSequenceCounter = AtomicLong(0L)
    private val executionEventSequenceCounter = AtomicLong(0L)
    private val snapshotTimestampClock = AtomicLong(0L)
    private val executionTimestampClock = AtomicLong(0L)
    private val activeExecutionCount = AtomicInteger(0)

    fun nextSnapshotStamp(requestedTimestampMs: Long? = null): SnapshotStamp {
        val timestamp = nextMonotonicTimestamp(
            clock = snapshotTimestampClock,
            candidate = requestedTimestampMs ?: wallClockMs()
        )
        val sequence = snapshotSequenceCounter.incrementAndGet()
        val count = activeExecutionCount.get()
        return SnapshotStamp(
            timestampMs = timestamp,
            snapshotSequence = sequence,
            activeExecutionCount = count,
            executionBusy = count > 0
        )
    }

    fun nextEventStamp(phase: String, requestedTimestampMs: Long? = null): EventStamp {
        val countAfterTransition = applyExecutionPhase(phase)
        val timestamp = nextMonotonicTimestamp(
            clock = executionTimestampClock,
            candidate = requestedTimestampMs ?: wallClockMs()
        )
        val sequence = executionEventSequenceCounter.incrementAndGet()
        return EventStamp(
            timestampMs = timestamp,
            eventSequence = sequence,
            activeExecutionCount = countAfterTransition,
            executionBusy = countAfterTransition > 0
        )
    }

    private fun applyExecutionPhase(phase: String): Int {
        return when (phase) {
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED -> activeExecutionCount.incrementAndGet()
            DeviceExecutionEventPayload.PHASE_COMPLETED,
            DeviceExecutionEventPayload.PHASE_FAILED,
            DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            DeviceExecutionEventPayload.PHASE_CANCELLED -> activeExecutionCount.updateAndGet { current ->
                if (current <= 0) 0 else current - 1
            }
            else -> activeExecutionCount.get()
        }
    }

    private fun nextMonotonicTimestamp(clock: AtomicLong, candidate: Long): Long {
        while (true) {
            val previous = clock.get()
            val next = if (candidate > previous) candidate else previous + 1L
            if (clock.compareAndSet(previous, next)) return next
        }
    }
}

