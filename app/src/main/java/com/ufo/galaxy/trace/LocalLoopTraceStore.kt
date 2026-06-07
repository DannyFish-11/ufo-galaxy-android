package com.ufo.galaxy.trace

import com.ufo.galaxy.observability.GalaxyLogger

/**
 * In-memory store for [LocalLoopTrace] sessions.
 *
 * Retains the last [maxTraces] completed (and in-flight) session traces so that
 * diagnostics, replay, and post-mortem analysis tools can access recent history
 * without querying external storage.
 *
 * **Design constraints**
 * - Lightweight: stores only the trace model objects; no raw images or large blobs.
 * - Bounded: oldest traces are evicted when [maxTraces] is reached.
 * - Thread-safe: all public methods are synchronized on `this`.
 * - No persistence: traces are held in memory only; they are lost on process restart.
 *   Use [GalaxyLogger] or an external sink for durable storage.
 *
 * **Typical lifecycle**
 * ```kotlin
 * // Session start:
 * val trace = LocalLoopTrace(sessionId = UUID.randomUUID().toString(), originalGoal = goal)
 * traceStore.beginTrace(trace)
 *
 * // During execution — append to trace directly via LocalLoopTrace helpers:
 * trace.recordPlan(planOutput)
 * trace.recordStep(stepObservation)
 *
 * // Session end:
 * trace.complete(TerminalResult(status = "success", ...))
 * traceStore.completeTrace(trace.sessionId)
 * ```
 *
 * @param maxTraces Maximum number of traces to retain. Defaults to [DEFAULT_MAX_TRACES].
 */
class LocalLoopTraceStore(val maxTraces: Int = DEFAULT_MAX_TRACES) {

    companion object {
        /** Default capacity: retain the last 20 traces. */
        const val DEFAULT_MAX_TRACES = 20

        private const val TAG = "GALAXY:LOCAL_LOOP:TRACE"
    }

    // Insertion-ordered map: sessionId → trace. LinkedHashMap in access-order=false
    // (insertion order) makes it easy to evict the oldest entry.
    private val traces = LinkedHashMap<String, LocalLoopTrace>(maxTraces + 1, 0.75f, false)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Registers a newly started [LocalLoopTrace] with the store.
     *
     * If [maxTraces] would be exceeded the oldest entry is evicted first.
     * If a trace with the same [LocalLoopTrace.sessionId] already exists it is
     * replaced (idempotent for retries).
     *
     * @param trace The trace to register. Must have a non-blank [LocalLoopTrace.sessionId].
     */
    @Synchronized
    fun beginTrace(trace: LocalLoopTrace) {
        evictIfNeeded()
        traces[trace.sessionId] = trace
        GalaxyLogger.log(TAG, mapOf(
            "event" to "trace_begin",
            "session_id" to trace.sessionId,
            "goal_len" to trace.originalGoal.length
        ))
    }

    /**
     * Marks a trace as complete by recording its completion timestamp in the store's log.
     *
     * The trace object itself should already have been completed via
     * [LocalLoopTrace.complete] before calling this method.
     *
     * @param sessionId The [LocalLoopTrace.sessionId] of the trace to mark complete.
     *                  No-op (with a warning log) if not found.
     */
    @Synchronized
    fun completeTrace(sessionId: String) {
        val trace = traces[sessionId]
        if (trace == null) {
            GalaxyLogger.log(TAG, mapOf(
                "event" to "trace_complete_not_found",
                "session_id" to sessionId
            ))
            return
        }
        GalaxyLogger.log(TAG, mapOf(
            "event" to "trace_complete",
            "session_id" to sessionId,
            "status" to (trace.terminalResult?.status ?: "unknown"),
            "steps" to trace.stepCount,
            "duration_ms" to (trace.durationMs ?: -1L)
        ))
    }

    /**
     * Returns the [LocalLoopTrace] for [sessionId], or `null` if not found.
     */
    @Synchronized
    fun getTrace(sessionId: String): LocalLoopTrace? = traces[sessionId]

    /**
     * Returns all currently retained traces, newest-first.
     *
     * The returned list is a snapshot; subsequent mutations to the store do not
     * affect it. The [LocalLoopTrace] objects themselves are **not** copied — callers
     * should treat them as read-only.
     */
    @Synchronized
    fun allTraces(): List<LocalLoopTrace> = traces.values.toList().reversed()

    /**
     * Returns only the traces that have a [LocalLoopTrace.terminalResult] set
     * (i.e. sessions that have completed), newest-first.
     */
    @Synchronized
    fun completedTraces(): List<LocalLoopTrace> =
        traces.values.filter { !it.isRunning }.reversed()

    /**
     * Returns only traces that are still running ([LocalLoopTrace.isRunning] == true).
     */
    @Synchronized
    fun runningTraces(): List<LocalLoopTrace> =
        traces.values.filter { it.isRunning }.toList()

    /** Number of traces currently held in the store. */
    @Synchronized
    fun size(): Int = traces.size

    /**
     * Removes all traces from the store.
     * Useful for test setup / teardown or a "clear diagnostics" action.
     */
    @Synchronized
    fun clear() {
        traces.clear()
        GalaxyLogger.log(TAG, mapOf("event" to "trace_store_cleared"))
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun evictIfNeeded() {
        while (traces.size >= maxTraces) {
            val oldest = traces.keys.firstOrNull() ?: break
            traces.remove(oldest)
            GalaxyLogger.log(TAG, mapOf("event" to "trace_evicted", "session_id" to oldest))
        }
    }
}
