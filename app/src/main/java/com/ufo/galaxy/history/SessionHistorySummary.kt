package com.ufo.galaxy.history

import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.TerminalResult

/**
 * Lightweight, serializable summary of a single completed local-loop session.
 *
 * A [SessionHistorySummary] is derived from a [LocalLoopTrace] when the session ends
 * and is the unit of persistence in [SessionHistoryStore]. It intentionally omits raw
 * step records, screenshots, and any large data to keep the persisted footprint small.
 *
 * **Serialization**: all fields are primitive JVM types or strings so Gson can
 * round-trip instances without any custom adapter.
 *
 * @property sessionId      Unique identifier for the session (UUID v4).
 * @property originalGoal   Unmodified goal string submitted by the caller.
 * @property startTimeMs    Wall-clock session start time (ms since epoch).
 * @property endTimeMs      Wall-clock session end time (ms since epoch).
 * @property durationMs     Session duration; computed from [startTimeMs] and [endTimeMs].
 * @property stepCount      Total number of completed steps.
 * @property status         Terminal status: [TerminalResult.STATUS_SUCCESS],
 *                          [TerminalResult.STATUS_FAILED], or [TerminalResult.STATUS_CANCELLED].
 * @property stopReason     Machine-readable stop reason (e.g. "task_complete", "timeout");
 *                          `null` when not available.
 * @property error          Human-readable error description; `null` on success.
 * @property planCount      Number of plan/replan outputs produced during the session.
 * @property actionCount    Total number of actions dispatched.
 * @property savedAtMs      Wall-clock time when this summary was written to the history store
 *                          (ms since epoch). Used for TTL eviction.
 */
data class SessionHistorySummary(
    val sessionId: String,
    val originalGoal: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val stepCount: Int,
    val status: String,
    val stopReason: String?,
    val error: String?,
    val planCount: Int,
    val actionCount: Int,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Creates a [SessionHistorySummary] from a completed [LocalLoopTrace].
         *
         * Returns `null` when the trace has no [LocalLoopTrace.terminalResult]
         * (i.e. the session is still running) or when [LocalLoopTrace.endTimeMs] is absent.
         */
        fun fromTrace(trace: LocalLoopTrace): SessionHistorySummary? {
            val terminal = trace.terminalResult ?: return null
            val endTime = trace.endTimeMs ?: return null
            return SessionHistorySummary(
                sessionId = trace.sessionId,
                originalGoal = trace.originalGoal,
                startTimeMs = trace.startTimeMs,
                endTimeMs = endTime,
                durationMs = endTime - trace.startTimeMs,
                stepCount = trace.stepCount,
                status = terminal.status,
                stopReason = terminal.stopReason,
                error = terminal.error,
                planCount = trace.planOutputs.size,
                actionCount = trace.actionRecords.size
            )
        }
    }
}
