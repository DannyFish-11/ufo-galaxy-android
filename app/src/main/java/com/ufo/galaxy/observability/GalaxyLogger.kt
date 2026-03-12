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
 * | Tag                  | Meaning                                                     |
 * |----------------------|-------------------------------------------------------------|
 * | `GALAXY:CONNECT`     | WebSocket connection established                            |
 * | `GALAXY:DISCONNECT`  | WebSocket disconnected (with reason / close code)           |
 * | `GALAXY:RECONNECT`   | Reconnect attempt scheduled or in progress                  |
 * | `GALAXY:TASK:RECV`   | `task_assign` or `goal_execution` message received          |
 * | `GALAXY:TASK:EXEC`   | Task execution started by EdgeExecutor                      |
 * | `GALAXY:TASK:RETURN` | Task result returned (status + task_id)                     |
 * | `GALAXY:READINESS`   | Readiness self-check completed                              |
 * | `GALAXY:DEGRADED`    | Device entered or exited degraded mode                      |
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
     * Records a structured log entry.
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
