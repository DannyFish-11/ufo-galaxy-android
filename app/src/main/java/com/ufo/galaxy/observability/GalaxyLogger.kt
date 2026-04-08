package com.ufo.galaxy.observability

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Device-side structured logger for key lifecycle events.
 *
 * Each entry is a single-line JSON object written to [LOG_FILE_NAME] inside the
 * app's private `filesDir`.  An in-memory ring buffer of [MAX_MEMORY_ENTRIES]
 * entries is also maintained so that the diagnostics screen can display recent
 * activity without reading from disk.
 *
 * ## Stable log tags
 *
 * | Tag                       | Meaning                                                     |
 * |---------------------------|-------------------------------------------------------------|
 * | `GALAXY:CONNECT`          | WebSocket connection established                            |
 * | `GALAXY:DISCONNECT`       | WebSocket disconnected (with reason / close code)           |
 * | `GALAXY:RECONNECT`        | Reconnect attempt scheduled or in progress                  |
 * | `GALAXY:TASK:RECV`        | `task_assign` or `goal_execution` message received          |
 * | `GALAXY:TASK:EXEC`        | Task execution started by EdgeExecutor                      |
 * | `GALAXY:TASK:RETURN`      | Task result returned (status + task_id)                     |
 * | `GALAXY:READINESS`        | Readiness self-check completed                              |
 * | `GALAXY:DEGRADED`         | Device entered or exited degraded mode                      |
 * | `GALAXY:TASK:TIMEOUT`     | Running task exceeded configured timeout budget             |
 * | `GALAXY:TASK:CANCEL`      | task_cancel instruction received and processed              |
 * | `GALAXY:SIGNAL:START`     | WebRTC signaling session started (WS connect + offer sent)  |
 * | `GALAXY:SIGNAL:STOP`      | WebRTC signaling session stopped (WS close / disconnect)    |
 * | `GALAXY:DISPATCHER:SELECT`| Dispatcher selection for a task (route_mode + exec_mode)    |
 * | `GALAXY:BRIDGE:HANDOFF`   | Bridge handoff to Agent Runtime initiated                   |
 * | `GALAXY:WEBRTC:TURN`      | TURN server config received or relay candidate applied      |
 * | `GALAXY:ERROR`            | Error event; always includes `trace_id` and `cause`        |
 * | `GALAXY:LOCAL_LOOP:START` | Local-loop goal session started                             |
 * | `GALAXY:LOCAL_LOOP:STEP`  | Single step completed within a local-loop session           |
 * | `GALAXY:LOCAL_LOOP:PLAN`  | Planner produced an initial plan or replan                  |
 * | `GALAXY:LOCAL_LOOP:DONE`  | Local-loop goal session ended (success / failure / cancel)  |
 * | `GALAXY:EXEC:ROUTE`       | Execution route decision recorded (PR-30)                   |
 * | `GALAXY:SETUP:RECOVERY`   | Recovery attempt after setup/registration failure (PR-30)   |
 * | `GALAXY:RECONNECT:OUTCOME`| Reconnect attempt concluded with success or failure (PR-30) |
 * | `GALAXY:FALLBACK:DECISION`| Fallback path finalised after delegated failure (PR-30)     |
 * | `GALAXY:ROLLOUT:CONTROL`  | Rollout-control flag changed at runtime (PR-31)             |
 * | `GALAXY:KILL:SWITCH`      | Kill-switch activated — all remote paths disabled (PR-31)   |
 * | `GALAXY:STAGED:MESH`      | Staged-mesh target execution event (PR-32)                  |
 * | `GALAXY:RECONNECT:RECOVERY`| Reconnect recovery state transition (PR-33)                |
 *
 * ## Log-entry format (one JSON object per line)
 * ```json
 * {"ts":1710000000000,"tag":"GALAXY:CONNECT","fields":{"url":"ws://host:8080","attempt":0}}
 * ```
 *
 * ## Usage
 * ```kotlin
 * // In Application.onCreate():
 * GalaxyLogger.init(applicationContext)
 *
 * // Anywhere:
 * GalaxyLogger.log(GalaxyLogger.TAG_CONNECT, mapOf("url" to url, "attempt" to attempt))
 * ```
 */
object GalaxyLogger {

    // ── Stable tag constants ──────────────────────────────────────────────────

    const val TAG_CONNECT    = "GALAXY:CONNECT"
    const val TAG_DISCONNECT = "GALAXY:DISCONNECT"
    const val TAG_RECONNECT  = "GALAXY:RECONNECT"
    const val TAG_TASK_RECV  = "GALAXY:TASK:RECV"
    const val TAG_TASK_EXEC  = "GALAXY:TASK:EXEC"
    const val TAG_TASK_RETURN = "GALAXY:TASK:RETURN"
    const val TAG_READINESS  = "GALAXY:READINESS"
    const val TAG_DEGRADED   = "GALAXY:DEGRADED"
    /** Fired when a running task exceeds its configured timeout budget. */
    const val TAG_TASK_TIMEOUT = "GALAXY:TASK:TIMEOUT"
    /** Fired when a task_cancel instruction is received and processed. */
    const val TAG_TASK_CANCEL  = "GALAXY:TASK:CANCEL"
    /** Fired when a WebRTC signaling session starts (WS connected, offer sent). */
    const val TAG_SIGNAL_START = "GALAXY:SIGNAL:START"
    /** Fired when a WebRTC signaling session stops (WS closed or failed). */
    const val TAG_SIGNAL_STOP  = "GALAXY:SIGNAL:STOP"
    /** Fired when a dispatcher is selected for a task (route_mode + exec_mode resolved). */
    const val TAG_DISPATCHER_SELECT = "GALAXY:DISPATCHER:SELECT"
    /** Fired when a bridge handoff to Agent Runtime is initiated. */
    const val TAG_BRIDGE_HANDOFF = "GALAXY:BRIDGE:HANDOFF"
    /** Fired when TURN server config is received or relay candidates are applied. */
    const val TAG_WEBRTC_TURN = "GALAXY:WEBRTC:TURN"
    /**
     * Fired for any error event.  Fields MUST include `trace_id` and `cause`.
     * Optional fields: `task_id`, `session_id`, `device_id`.
     */
    const val TAG_ERROR = "GALAXY:ERROR"

    // ── Local-loop lifecycle tags ─────────────────────────────────────────────

    /** Fired when a local-loop goal session starts (instruction received). */
    const val TAG_LOCAL_LOOP_START = "GALAXY:LOCAL_LOOP:START"

    /** Fired after each completed step in the local loop (action dispatched + observed). */
    const val TAG_LOCAL_LOOP_STEP = "GALAXY:LOCAL_LOOP:STEP"

    /** Fired when the local loop produces a plan or replan output. */
    const val TAG_LOCAL_LOOP_PLAN = "GALAXY:LOCAL_LOOP:PLAN"

    /** Fired when a local-loop goal session ends (success, failure, or cancel). */
    const val TAG_LOCAL_LOOP_DONE = "GALAXY:LOCAL_LOOP:DONE"

    // ── PR-30: Execution-path observability tags ──────────────────────────────

    /**
     * PR-30 — Fired whenever an execution route decision is recorded.
     *
     * Required fields: `route` (wire value from [com.ufo.galaxy.runtime.ExecutionRouteTag]),
     * `task_id` (or session id when no task id is available).
     *
     * Example:
     * ```json
     * {"ts":…,"tag":"GALAXY:EXEC:ROUTE","fields":{"route":"local","task_id":"sess-abc"}}
     * ```
     */
    const val TAG_EXEC_ROUTE = "GALAXY:EXEC:ROUTE"

    /**
     * PR-30 — Fired when a recovery attempt is initiated after a setup / registration failure.
     *
     * Required fields: `category` (wire value from
     * [com.ufo.galaxy.runtime.CrossDeviceSetupError.Category]), `action`
     * (one of `"retry_connect"`, `"open_settings"`, `"open_permissions"`).
     */
    const val TAG_SETUP_RECOVERY = "GALAXY:SETUP:RECOVERY"

    /**
     * PR-30 — Fired when a reconnect attempt concludes (success or failure).
     *
     * Fields: `outcome` (`"success"` or `"failure"`), `state` (the [RuntimeController.RuntimeState]
     * simple class name after the attempt).
     */
    const val TAG_RECONNECT_OUTCOME = "GALAXY:RECONNECT:OUTCOME"

    /**
     * PR-30 — Fired when a fallback path decision is finalised after a delegated-takeover
     * failure.  Provides richer operator context alongside the existing
     * [GALAXY:TASK:RETURN] / takeover failure events.
     *
     * Required fields: `takeover_id`, `task_id`, `cause` (wire value from
     * [com.ufo.galaxy.runtime.TakeoverFallbackEvent.Cause]), `reason`.
     */
    const val TAG_FALLBACK_DECISION = "GALAXY:FALLBACK:DECISION"

    // ── PR-31: Rollout-control and kill-switch tags ───────────────────────────

    /**
     * PR-31 — Fired when any rollout-control flag changes at runtime.
     *
     * Required fields: `flag` (wire key from [com.ufo.galaxy.runtime.RolloutControlSnapshot]),
     * `value` (`true` or `false`), `source` (caller that triggered the change, e.g.
     * `"kill_switch"`, `"settings"`, `"operator"`).
     *
     * Example:
     * ```json
     * {"ts":…,"tag":"GALAXY:ROLLOUT:CONTROL","fields":{"flag":"cross_device_allowed","value":false,"source":"kill_switch"}}
     * ```
     */
    const val TAG_ROLLOUT_CONTROL = "GALAXY:ROLLOUT:CONTROL"

    /**
     * PR-31 — Fired when the kill-switch is activated via
     * [com.ufo.galaxy.runtime.RuntimeController.applyKillSwitch].
     *
     * Indicates that all remote execution paths (cross-device, delegated, goal execution)
     * have been atomically disabled.  No required fields beyond the stable tag; optional
     * field `reason` may be included by the caller for operator traceability.
     */
    const val TAG_KILL_SWITCH = "GALAXY:KILL:SWITCH"

    // ── PR-32: Staged-mesh target execution tag ───────────────────────────────

    /**
     * PR-32 — Fired for key events in the staged-mesh target execution path.
     *
     * Emitted by [com.ufo.galaxy.runtime.StagedMeshExecutionTarget] for:
     * - `staged_mesh_accept`  — subtask accepted and dispatched to the pipeline.
     * - `staged_mesh_blocked` — subtask rejected by a rollout-control gate.
     * - `staged_mesh_result`  — subtask execution completed (success, failure, or cancelled).
     *
     * Required fields: `event`, `mesh_id`, `subtask_id`, `task_id`.
     * Result events additionally include: `status`, `step_count`, `latency_ms`.
     *
     * Example:
     * ```json
     * {"ts":…,"tag":"GALAXY:STAGED:MESH","fields":{"event":"staged_mesh_result","mesh_id":"sm-1","subtask_id":"sub-0","task_id":"t-abc","status":"success","step_count":3,"latency_ms":1200}}
     * ```
     */
    const val TAG_STAGED_MESH = "GALAXY:STAGED:MESH"

    // ── PR-33: Reconnect resilience and recovery tag ──────────────────────────

    /**
     * PR-33 — Fired when the reconnect recovery state transitions between phases.
     *
     * Emitted by [com.ufo.galaxy.runtime.RuntimeController]'s permanent WS listener
     * on each [com.ufo.galaxy.runtime.ReconnectRecoveryState] transition:
     *
     * - `idle→recovering`        — WS dropped while Active; reconnect started.
     * - `recovering→recovered`   — WS reconnect succeeded; session resumed.
     * - `recovering→failed`      — WS error or max attempts exhausted.
     *
     * Required fields: `transition` (e.g. `"idle→recovering"`),
     * `trigger` (e.g. `"ws_disconnect_active"`).
     * Error transitions additionally include: `error`.
     *
     * Example:
     * ```json
     * {"ts":…,"tag":"GALAXY:RECONNECT:RECOVERY","fields":{"transition":"recovering→recovered","trigger":"ws_reconnected_active"}}
     * ```
     */
    const val TAG_RECONNECT_RECOVERY = "GALAXY:RECONNECT:RECOVERY"

    // ── PR-34: Runtime and interaction acceptance tag ─────────────────────────

    /**
     * PR-34 — Fired at key acceptance checkpoints across the runtime and interaction surfaces.
     *
     * Emitted when a product-grade acceptance boundary is exercised:
     *
     * - `text_input_submitted`    — user text input was accepted and routed.
     * - `voice_input_submitted`   — voice transcript was accepted and routed.
     * - `floating_entry_invoked`  — floating secondary entry point was activated.
     * - `cross_device_toggled`    — cross-device mode was enabled or disabled by the user.
     * - `delegated_target_evaluated` — delegated target suitability was projected.
     * - `result_presented`        — unified result was presented to a surface layer.
     *
     * Required fields: `event`, `route` (one of `"local"`, `"cross_device"`, `"delegated"`,
     * `"fallback"`).
     *
     * Example:
     * ```json
     * {"ts":…,"tag":"GALAXY:INTERACTION:ACCEPTANCE","fields":{"event":"text_input_submitted","route":"local"}}
     * ```
     */
    const val TAG_INTERACTION_ACCEPTANCE = "GALAXY:INTERACTION:ACCEPTANCE"

    // ── Internal constants ────────────────────────────────────────────────────

    private const val ANDROID_TAG     = "GalaxyLogger"
    const val LOG_FILE_NAME           = "galaxy_observability.log"
    private const val MAX_MEMORY_ENTRIES = 500
    /** Maximum log file size (bytes) before the file is rotated (truncated). */
    private const val MAX_FILE_BYTES  = 2 * 1024 * 1024L   // 2 MB

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var logFile: File? = null
    private val fileLock = ReentrantLock()

    /**
     * Active sampling configuration.  Defaults to [SamplingConfig.debug] (all events
     * logged).  Override in [android.app.Application.onCreate] before any log calls:
     * ```kotlin
     * GalaxyLogger.samplingConfig = if (BuildConfig.DEBUG) SamplingConfig.debug()
     *                               else SamplingConfig.production()
     * ```
     */
    @Volatile
    var samplingConfig: SamplingConfig = SamplingConfig.debug()

    /**
     * In-memory ring buffer holding the latest [MAX_MEMORY_ENTRIES] log entries.
     * Using [LinkedBlockingDeque] so that older entries can be polled from the
     * front when the buffer is full.
     */
    private val memoryBuffer = LinkedBlockingDeque<LogEntry>(MAX_MEMORY_ENTRIES)

    // ── Data model ────────────────────────────────────────────────────────────

    /**
     * A single structured log entry.
     *
     * @param ts     Unix timestamp in milliseconds.
     * @param tag    Stable tag constant (e.g. [TAG_CONNECT]).
     * @param fields Arbitrary key→value pairs providing event context.
     */
    data class LogEntry(
        val ts: Long,
        val tag: String,
        val fields: Map<String, Any?>
    ) {
        /** Serialises this entry to a compact single-line JSON string. */
        fun toJsonLine(): String {
            val obj = JSONObject()
            obj.put("ts", ts)
            obj.put("tag", tag)
            val fieldsObj = JSONObject()
            fields.forEach { (k, v) -> fieldsObj.put(k, v) }
            obj.put("fields", fieldsObj)
            return obj.toString()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initialises the logger.  Must be called once from [android.app.Application.onCreate]
     * before any [log] calls that should persist to disk.
     *
     * Safe to call multiple times; subsequent calls update [logFile] only.
     * Safe to skip in unit tests — all [log] calls will still populate the in-memory
     * buffer and emit Android log lines; only file writes are skipped when [context]
     * has not been provided.
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        Log.d(ANDROID_TAG, "GalaxyLogger initialised – log file: ${logFile?.absolutePath}")
    }

    /**
     * Records a structured log entry (same as [log]).
     *
     * - Always writes to the Android logcat.
     * - Always adds the entry to the in-memory ring buffer (dropping the oldest when full).
     * - Writes to the log file if [init] was called (best-effort; errors are swallowed).
     *
     * Thread-safe; may be called from any thread.
     *
     * @param tag    One of the `TAG_*` constants defined in this object.
     * @param fields Map of key→value pairs to include in the `fields` JSON object.
     */
    fun log(tag: String, fields: Map<String, Any?> = emptyMap()) {
        val entry = LogEntry(ts = System.currentTimeMillis(), tag = tag, fields = fields)
        val line = entry.toJsonLine()

        // 1. Logcat
        Log.i(ANDROID_TAG, "[$tag] $line")

        // 2. In-memory ring buffer — drop oldest entry when full
        if (!memoryBuffer.offer(entry)) {
            memoryBuffer.poll()
            memoryBuffer.offer(entry)
        }

        // 3. File (best-effort)
        writeToFile(line)
    }

    /**
     * Returns all entries currently held in the in-memory ring buffer, ordered
     * oldest-first.  The returned list is a snapshot; subsequent [log] calls do
     * not affect it.
     */
    fun getEntries(): List<LogEntry> = memoryBuffer.toList()

    /**
     * Records a structured log entry **only** when [SamplingConfig.shouldSample]
     * returns `true` for [tag].  Use this for high-frequency events where sampling
     * is desirable.
     *
     * Error events ([TAG_ERROR]) are always written regardless of sampling rate.
     *
     * @param tag    One of the `TAG_*` constants defined in this object.
     * @param fields Map of key→value pairs to include in the `fields` JSON object.
     */
    fun logSampled(tag: String, fields: Map<String, Any?> = emptyMap()) {
        if (tag == TAG_ERROR || samplingConfig.shouldSample(tag)) {
            log(tag, fields)
        }
    }

    /**
     * Convenience helper for error events.
     *
     * Calls [log] with [TAG_ERROR] and always includes `trace_id` and `cause` in
     * `fields`.  Additional context fields may be passed via [extraFields].
     *
     * @param traceId    Current trace identifier from [TraceContext.currentTraceId].
     * @param cause      Human-readable error description.
     * @param extraFields Optional additional context (e.g. `task_id`, `session_id`).
     */
    fun logError(
        traceId: String,
        cause: String,
        extraFields: Map<String, Any?> = emptyMap()
    ) {
        log(
            TAG_ERROR,
            buildMap {
                put("trace_id", traceId)
                put("cause", cause)
                putAll(extraFields)
            }
        )
    }

    /**
     * Returns the log file if it exists and has been initialised via [init].
     * Returns `null` when [init] was not called (e.g. in unit tests).
     *
     * Callers should use this method to obtain the [File] reference when building
     * a share [android.content.Intent].
     */
    fun getLogFile(): File? = logFile?.takeIf { it.exists() }

    /**
     * Clears the in-memory ring buffer and deletes the log file.
     * Useful for "Clear logs" actions in a debug/settings screen.
     */
    fun clear() {
        memoryBuffer.clear()
        fileLock.withLock {
            try { logFile?.delete() } catch (_: IOException) { /* best-effort */ }
        }
        Log.d(ANDROID_TAG, "GalaxyLogger cleared")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun writeToFile(line: String) {
        val file = logFile ?: return
        fileLock.withLock {
            try {
                // Rotate if the file is too large
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    file.delete()
                }
                FileWriter(file, /* append = */ true).use { fw ->
                    fw.appendLine(line)
                }
            } catch (e: IOException) {
                Log.w(ANDROID_TAG, "Failed to write log entry to file: ${e.message}")
            }
        }
    }
}
