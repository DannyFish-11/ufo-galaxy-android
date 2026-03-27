package com.ufo.galaxy.debug

import com.ufo.galaxy.config.LocalLoopConfig
import com.ufo.galaxy.local.LocalLoopReadiness
import com.ufo.galaxy.trace.LocalLoopTrace
import com.ufo.galaxy.trace.TerminalResult

/**
 * Immutable snapshot of all debug-relevant local-loop state surfaced by the
 * [LocalLoopDebugViewModel] for the debug panel.
 *
 * Each field is nullable so the panel can clearly distinguish "not yet loaded"
 * from a real zero/false value.
 *
 * @property readinessSnapshot   Latest [LocalLoopReadiness] from the readiness provider,
 *                               `null` before the first refresh.
 * @property configSnapshot      Active [LocalLoopConfig] at refresh time, `null` if not
 *                               available in the current app wiring.
 * @property latestTrace         The most recently started or completed [LocalLoopTrace],
 *                               `null` when the trace store is empty.
 * @property lastTerminalResult  Terminal result from [latestTrace], or `null` when the
 *                               session is still running or no trace exists yet.
 * @property lastGoal            Original goal string from [latestTrace]; `null` when no
 *                               trace is available.
 * @property traceCount          Total number of traces currently retained by the store.
 * @property isRefreshing        True while a readiness refresh is in progress.
 * @property diagnosticSnapshot  Plain-text snapshot emitted by the last "Emit Snapshot"
 *                               action; `null` before the action is first triggered.
 * @property debugFlagsEnabled   Opaque map of debug-only flag names → current values.
 *                               Only flags that are supported by the active runtime are
 *                               included. Consumers must not assume any particular key.
 */
data class LocalLoopDebugState(
    val readinessSnapshot: LocalLoopReadiness? = null,
    val configSnapshot: LocalLoopConfig? = null,
    val latestTrace: LocalLoopTrace? = null,
    val lastTerminalResult: TerminalResult? = null,
    val lastGoal: String? = null,
    val traceCount: Int = 0,
    val isRefreshing: Boolean = false,
    val diagnosticSnapshot: String? = null,
    val debugFlagsEnabled: Map<String, Boolean> = emptyMap()
)
