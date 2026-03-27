package com.ufo.galaxy.debug

import com.ufo.galaxy.config.LocalLoopConfig
import com.ufo.galaxy.history.SessionHistoryStore
import com.ufo.galaxy.history.SessionHistorySummary
import com.ufo.galaxy.local.LocalLoopReadinessProvider
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.trace.LocalLoopTraceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only ViewModel for the local-loop debug panel.
 *
 * Aggregates readiness, config, and trace data from the existing PR-E abstractions
 * ([LocalLoopReadinessProvider], [LocalLoopTraceStore], [LocalLoopConfig]) into a single
 * [LocalLoopDebugState] that the UI can observe without depending on the production
 * ViewModel.
 *
 * **Lifecycle**: create once per debug panel session; call [refresh] after creation
 * to populate the initial state.
 *
 * **Thread safety**: all state mutations happen through [MutableStateFlow.update] so the
 * flow is safe to collect from any thread.
 *
 * @param readinessProvider  Source of current [com.ufo.galaxy.local.LocalLoopReadiness].
 * @param traceStore         In-memory store of recent [com.ufo.galaxy.trace.LocalLoopTrace]s.
 * @param configProvider     Returns the active [LocalLoopConfig]; `null` if not wired.
 * @param historyStore       Optional persistent session history store. When provided,
 *                           completed traces are automatically saved on [persistCompletedTrace]
 *                           and session history is loaded during [refresh].
 * @param coroutineScope     Scope used for launched coroutines. Defaults to a new
 *                           [CoroutineScope] bound to [Dispatchers.Main].
 * @param ioDispatcher       Dispatcher for blocking work (readiness check). Defaults to
 *                           [Dispatchers.IO].
 * @param lastGoalProvider   Returns the most recently submitted goal string; `null` if
 *                           none has been submitted yet.
 * @param rerunGoalAction    Invoked by [rerunLastGoal] with the last goal string. No-op
 *                           when `null`.
 */
class LocalLoopDebugViewModel(
    private val readinessProvider: LocalLoopReadinessProvider,
    private val traceStore: LocalLoopTraceStore,
    private val configProvider: (() -> LocalLoopConfig?)? = null,
    private val historyStore: SessionHistoryStore? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lastGoalProvider: (() -> String?)? = null,
    private val rerunGoalAction: ((String) -> Unit)? = null
) {

    companion object {
        internal const val TAG = "GALAXY:DEBUG:LOCAL_LOOP"
    }

    private val _state = MutableStateFlow(LocalLoopDebugState())

    /** Observable debug state consumed by the debug panel composable. */
    val state: StateFlow<LocalLoopDebugState> = _state.asStateFlow()

    // ── Developer actions ─────────────────────────────────────────────────────

    /**
     * Refreshes all fields of [state] by querying readiness, trace store, and config.
     *
     * Sets [LocalLoopDebugState.isRefreshing] to `true` while the refresh is in-flight,
     * then `false` on completion. Safe to call multiple times.
     */
    fun refresh() {
        coroutineScope.launch(ioDispatcher) {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val readiness = readinessProvider.getReadiness()
                val config = configProvider?.invoke()
                val traces = traceStore.allTraces()
                val latestTrace = traces.firstOrNull()
                val lastGoal = lastGoalProvider?.invoke() ?: latestTrace?.originalGoal
                val history = historyStore?.all() ?: emptyList()
                val historyCount = historyStore?.size() ?: 0

                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "debug_refresh",
                        "readiness_state" to readiness.state.name,
                        "trace_count" to traces.size,
                        "history_count" to historyCount
                    )
                )

                _state.update {
                    it.copy(
                        isRefreshing = false,
                        readinessSnapshot = readiness,
                        configSnapshot = config,
                        latestTrace = latestTrace,
                        lastTerminalResult = latestTrace?.terminalResult,
                        lastGoal = lastGoal,
                        traceCount = traces.size,
                        sessionHistory = history,
                        historyCount = historyCount
                    )
                }
            } catch (e: Exception) {
                GalaxyLogger.log(TAG, mapOf("event" to "debug_refresh_error", "error" to (e.message ?: "unknown")))
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * Re-submits the last known goal via [rerunGoalAction].
     *
     * No-op (with a log warning) when there is no known last goal or no rerun action is wired.
     */
    fun rerunLastGoal() {
        val goal = _state.value.lastGoal
        if (goal.isNullOrBlank()) {
            GalaxyLogger.log(TAG, mapOf("event" to "rerun_skipped", "reason" to "no_last_goal"))
            return
        }
        if (rerunGoalAction == null) {
            GalaxyLogger.log(TAG, mapOf("event" to "rerun_skipped", "reason" to "no_rerun_action"))
            return
        }
        GalaxyLogger.log(TAG, mapOf("event" to "rerun_last_goal", "goal_len" to goal.length))
        rerunGoalAction.invoke(goal)
    }

    /**
     * Clears all traces from the [traceStore] and refreshes the debug state.
     *
     * Corresponds to the "Clear Trace State" developer action.
     */
    fun clearTraceState() {
        traceStore.clear()
        GalaxyLogger.log(TAG, mapOf("event" to "clear_trace_state"))
        _state.update { it.copy(latestTrace = null, lastTerminalResult = null, traceCount = 0) }
    }

    /**
     * Persists a completed [com.ufo.galaxy.trace.LocalLoopTrace] to the [historyStore].
     *
     * Creates a [SessionHistorySummary] from the trace and saves it. No-op when
     * [historyStore] is not wired or the trace has no terminal result.
     *
     * Call this from the trace completion site (e.g. [LocalLoopTraceStore.completeTrace]) to
     * automatically capture completed sessions in the history store.
     *
     * @param trace The completed trace to persist.
     */
    fun persistCompletedTrace(trace: com.ufo.galaxy.trace.LocalLoopTrace) {
        val store = historyStore ?: return
        val summary = SessionHistorySummary.fromTrace(trace) ?: run {
            GalaxyLogger.log(TAG, mapOf(
                "event" to "persist_trace_skipped",
                "session_id" to trace.sessionId,
                "reason" to "no_terminal_result"
            ))
            return
        }
        store.save(summary)
        GalaxyLogger.log(TAG, mapOf(
            "event" to "trace_persisted_to_history",
            "session_id" to trace.sessionId,
            "status" to summary.status
        ))
        _state.update { it.copy(historyCount = store.size()) }
    }

    /**
     * Clears all entries from the persistent [historyStore] and resets the history fields
     * in [state].
     *
     * No-op (with a log) when [historyStore] is not wired.
     */
    fun clearSessionHistory() {
        val store = historyStore
        if (store == null) {
            GalaxyLogger.log(TAG, mapOf("event" to "clear_history_skipped", "reason" to "no_history_store"))
            return
        }
        store.clear()
        GalaxyLogger.log(TAG, mapOf("event" to "session_history_cleared"))
        _state.update { it.copy(sessionHistory = emptyList(), historyCount = 0) }
    }

    /**
     * Forces a fresh readiness check and updates [LocalLoopDebugState.readinessSnapshot].
     *
     * Equivalent to [refresh] but only queries readiness — cheaper than a full refresh
     * when the caller only needs the latest readiness gate result.
     */
    fun forceReadinessRefresh() {
        coroutineScope.launch(ioDispatcher) {
            _state.update { it.copy(isRefreshing = true) }
            try {
                val readiness = readinessProvider.getReadiness()
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "force_readiness_refresh",
                        "readiness_state" to readiness.state.name,
                        "blocker_count" to readiness.blockers.size
                    )
                )
                _state.update { it.copy(isRefreshing = false, readinessSnapshot = readiness) }
            } catch (e: Exception) {
                GalaxyLogger.log(TAG, mapOf("event" to "force_readiness_error", "error" to (e.message ?: "unknown")))
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * Builds and stores a plain-text diagnostic snapshot in [LocalLoopDebugState.diagnosticSnapshot].
     *
     * The snapshot is also emitted as a structured [GalaxyLogger] event so it lands in the
     * ring-buffer log and can be exported via the existing "Share Logs" flow.
     */
    fun emitDiagnosticSnapshot() {
        val s = _state.value
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val snapshot = buildString {
            appendLine("=== Local-Loop Debug Snapshot ===")
            appendLine("Time: $ts")
            appendLine()

            appendLine("-- Readiness --")
            val r = s.readinessSnapshot
            if (r == null) {
                appendLine("  (not yet refreshed)")
            } else {
                appendLine("  State       : ${r.state.name}")
                appendLine("  Model files : ${r.modelFilesReady}")
                appendLine("  Planner     : ${r.plannerLoaded}")
                appendLine("  Grounding   : ${r.groundingLoaded}")
                appendLine("  Accessibility: ${r.accessibilityReady}")
                appendLine("  Screenshot  : ${r.screenshotReady}")
                appendLine("  Action exec : ${r.actionExecutorReady}")
                if (r.blockers.isNotEmpty()) {
                    appendLine("  Blockers    : ${r.blockers.joinToString { it.name }}")
                }
            }
            appendLine()

            appendLine("-- Config --")
            val c = s.configSnapshot
            if (c == null) {
                appendLine("  (not available)")
            } else {
                appendLine("  maxSteps         : ${c.maxSteps}")
                appendLine("  maxRetriesPerStep: ${c.maxRetriesPerStep}")
                appendLine("  stepTimeoutMs    : ${c.stepTimeoutMs}")
                appendLine("  goalTimeoutMs    : ${c.goalTimeoutMs}")
                appendLine("  plannerFallback  : ${c.fallback.enablePlannerFallback}")
                appendLine("  groundingFallback: ${c.fallback.enableGroundingFallback}")
                appendLine("  remoteHandoff    : ${c.fallback.enableRemoteHandoff}")
            }
            appendLine()

            appendLine("-- Latest Trace --")
            val t = s.latestTrace
            if (t == null) {
                appendLine("  (no trace available)")
            } else {
                appendLine("  Session     : ${t.sessionId}")
                appendLine("  Goal        : ${t.originalGoal.take(80)}${if (t.originalGoal.length > 80) "…" else ""}")
                appendLine("  Running     : ${t.isRunning}")
                appendLine("  Steps       : ${t.stepCount}")
                appendLine("  Duration ms : ${t.durationMs ?: "running"}")
                val term = t.terminalResult
                if (term != null) {
                    appendLine("  Status      : ${term.status}")
                    appendLine("  Stop reason : ${term.stopReason ?: "—"}")
                    if (term.error != null) appendLine("  Error       : ${term.error}")
                }
            }
            appendLine()

            appendLine("-- Trace Store --")
            appendLine("  Retained traces: ${s.traceCount}")
            appendLine()

            appendLine("-- Session History --")
            appendLine("  Persisted sessions: ${s.historyCount}")
            if (s.sessionHistory.isNotEmpty()) {
                s.sessionHistory.take(5).forEachIndexed { i, h ->
                    appendLine("  [${i + 1}] ${h.sessionId.take(12)}… | ${h.status} | steps=${h.stepCount} | ${h.durationMs}ms")
                }
                if (s.sessionHistory.size > 5) {
                    appendLine("  … and ${s.sessionHistory.size - 5} more")
                }
            }
        }.trimEnd()

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "diagnostic_snapshot",
                "readiness_state" to (s.readinessSnapshot?.state?.name ?: "unknown"),
                "trace_count" to s.traceCount
            )
        )
        _state.update { it.copy(diagnosticSnapshot = snapshot) }
    }
}
