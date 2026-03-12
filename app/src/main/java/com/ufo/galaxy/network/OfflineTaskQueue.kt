package com.ufo.galaxy.network

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Thread-safe offline task queue for outgoing task result messages.
 *
 * When the WebSocket is disconnected, callers can [enqueue] outgoing
 * `task_result` / `goal_result` payloads here.  On reconnect the client
 * calls [drainAll] and re-sends messages in FIFO order.
 *
 * **Drop policy**: When [maxQueueSize] is reached the *oldest* entry is removed
 * and a WARN log is emitted with tag [TAG].  This prevents unbounded memory
 * growth during prolonged outages.
 *
 * **Persistence**: When a [SharedPreferences] instance is provided, the queue
 * is serialised to JSON after every mutation and restored on construction.
 * Messages older than [MAX_AGE_MS] (24 hours) are silently discarded on load.
 * If [prefs] is `null` (the default), the queue is in-memory only and does
 * not survive process restart — document this limitation in the README.
 *
 * **Types that are queued**: Only messages whose JSON `type` field is listed in
 * [QUEUEABLE_TYPES] ("task_result", "goal_result") are candidates for queuing.
 * Heartbeats, handshakes, and diagnostics are never queued.
 *
 * **JVM / unit-test compatible**: no Android framework references other than
 * the optional [SharedPreferences] and [Log] stub (log calls silently compile
 * because `returnDefaultValues = true` is set in `testOptions`).
 */
class OfflineTaskQueue(
    private val prefs: SharedPreferences? = null,
    private val gson: Gson = Gson(),
    val maxQueueSize: Int = MAX_QUEUE_SIZE
) {

    companion object {
        const val TAG = "WS:OfflineQueue"
        const val MAX_QUEUE_SIZE = 50
        private const val PREFS_KEY = "offline_task_queue"

        /** Messages older than this threshold are discarded when the queue is loaded from prefs. */
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** JSON `type` values that should be queued during offline periods. */
        val QUEUEABLE_TYPES: Set<String> = setOf("task_result", "goal_result")
    }

    /**
     * A single queued outgoing message.
     *
     * @param type      The AIP message type string (e.g. "task_result").
     * @param json      The fully-serialised JSON payload ready to transmit.
     * @param queuedAt  Epoch-millis timestamp used for stale-message eviction on load.
     */
    data class QueuedMessage(
        val type: String,
        val json: String,
        val queuedAt: Long = System.currentTimeMillis()
    )

    private val queue = ArrayDeque<QueuedMessage>()
    private val lock = Any()

    private val _sizeFlow = MutableStateFlow(0)

    /** Observable queue depth; updates synchronously after every mutation. */
    val sizeFlow: StateFlow<Int> = _sizeFlow.asStateFlow()

    /** Current number of queued messages. */
    val size: Int get() = synchronized(lock) { queue.size }

    init {
        loadFromPrefs()
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Enqueues [json] for delivery on the next reconnect.
     *
     * If the queue is already at [maxQueueSize], the oldest entry is dropped
     * and a WARN is emitted before the new message is appended.
     */
    fun enqueue(type: String, json: String) {
        val newSize = synchronized(lock) {
            if (queue.size >= maxQueueSize) {
                val dropped = queue.poll()
                Log.w(
                    TAG,
                    "[WS:OfflineQueue] Queue full ($maxQueueSize); dropped oldest " +
                        "type=${dropped?.type} queuedAt=${dropped?.queuedAt}"
                )
            }
            queue.add(QueuedMessage(type, json))
            Log.d(TAG, "[WS:OfflineQueue] Enqueued type=$type queue_size=${queue.size}")
            saveToPrefsLocked()
            queue.size
        }
        _sizeFlow.value = newSize
    }

    /**
     * Removes and returns all queued messages in FIFO order.
     * The queue is empty after this call.
     */
    fun drainAll(): List<QueuedMessage> {
        val messages = synchronized(lock) {
            val copy = queue.toList()
            queue.clear()
            saveToPrefsLocked()
            copy
        }
        _sizeFlow.value = 0
        return messages
    }

    /** Discards all queued messages without returning them. */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            saveToPrefsLocked()
        }
        _sizeFlow.value = 0
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Must be called while holding [lock]. */
    private fun saveToPrefsLocked() {
        prefs ?: return
        try {
            prefs.edit().putString(PREFS_KEY, gson.toJson(queue.toList())).apply()
        } catch (e: Exception) {
            Log.e(TAG, "[WS:OfflineQueue] Failed to persist queue", e)
        }
    }

    private fun loadFromPrefs() {
        prefs ?: return
        try {
            val json = prefs.getString(PREFS_KEY, null) ?: return
            val listType = object : TypeToken<List<QueuedMessage>>() {}.type
            val list: List<QueuedMessage> = gson.fromJson(json, listType)
            val now = System.currentTimeMillis()
            var stale = 0
            val loaded = synchronized(lock) {
                for (msg in list) {
                    // Discard messages with future timestamps (corrupted) or older than MAX_AGE_MS.
                    val age = now - msg.queuedAt
                    if (age in 0..MAX_AGE_MS) {
                        queue.add(msg)
                    } else {
                        stale++
                    }
                }
                queue.size
            }
            _sizeFlow.value = loaded
            if (stale > 0) Log.i(TAG, "[WS:OfflineQueue] Discarded $stale stale message(s) on load")
            if (loaded > 0) Log.i(TAG, "[WS:OfflineQueue] Restored $loaded offline message(s) from prefs")
        } catch (e: Exception) {
            Log.e(TAG, "[WS:OfflineQueue] Failed to load persisted queue", e)
        }
    }
}
