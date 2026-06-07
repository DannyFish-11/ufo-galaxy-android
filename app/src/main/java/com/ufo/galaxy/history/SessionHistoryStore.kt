package com.ufo.galaxy.history

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent store for [SessionHistorySummary] records.
 *
 * Retains the last [maxEntries] completed session summaries, optionally backed by
 * [SharedPreferences] so the history survives app restarts.  When [prefs] is `null`
 * the store operates in in-memory mode — useful for unit tests and environments where
 * Android APIs are unavailable.
 *
 * **Design constraints**
 * - **Bounded**: oldest entries are evicted when [maxEntries] is reached (drop-oldest).
 * - **TTL eviction**: entries older than [maxAgeMs] are discarded when the store is loaded
 *   and when new entries are saved.
 * - **Thread-safe**: all public methods are synchronized on `this`.
 * - **Lightweight**: only [SessionHistorySummary] objects (no raw images or step records)
 *   are persisted.
 *
 * @param prefs       Optional [SharedPreferences] for persistence. `null` = in-memory only.
 * @param gson        [Gson] instance for JSON serialisation. Defaults to a plain [Gson].
 * @param maxEntries  Maximum number of history entries to retain. Default: [DEFAULT_MAX_ENTRIES].
 * @param maxAgeMs    Maximum age of a retained entry in ms. Default: [DEFAULT_MAX_AGE_MS] (7 days).
 */
class SessionHistoryStore(
    private val prefs: SharedPreferences? = null,
    private val gson: Gson = Gson(),
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {

    companion object {
        const val TAG = "GALAXY:SESSION_HISTORY"

        /** Default capacity: retain the last 100 session summaries. */
        const val DEFAULT_MAX_ENTRIES = 100

        /** Default TTL: 7 days in milliseconds. */
        const val DEFAULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        private const val PREFS_KEY = "session_history"

        private val LIST_TYPE = object : TypeToken<List<SessionHistorySummary>>() {}.type
    }

    // Insertion-ordered: newest entries are appended at the end; we reverse on read.
    private val entries = ArrayDeque<SessionHistorySummary>()

    init {
        loadFromPrefs()
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Saves a [SessionHistorySummary] to the history store.
     *
     * If a summary with the same [SessionHistorySummary.sessionId] already exists it is
     * replaced (idempotent for retries / duplicate completions). Oldest entries are
     * evicted when [maxEntries] is exceeded. Stale entries are purged before saving.
     */
    @Synchronized
    fun save(summary: SessionHistorySummary) {
        // Replace existing entry for the same session if present.
        entries.removeAll { it.sessionId == summary.sessionId }
        entries.addLast(summary)
        evictStale()
        evictOverflow()
        saveToPrefs()
        Log.d(TAG, "Saved session history: ${summary.sessionId} status=${summary.status}")
    }

    /**
     * Returns all retained history entries, newest-first.
     *
     * The returned list is a snapshot; subsequent store mutations do not affect it.
     */
    @Synchronized
    fun all(): List<SessionHistorySummary> = entries.toList().reversed()

    /**
     * Returns the [limit] most recent entries, newest-first.
     *
     * Equivalent to [all] when the store contains fewer than [limit] entries.
     *
     * @param limit Maximum number of entries to return.
     */
    @Synchronized
    fun recent(limit: Int): List<SessionHistorySummary> = all().take(limit)

    /**
     * Returns only history entries with the given [status]
     * (e.g. [com.ufo.galaxy.trace.TerminalResult.STATUS_SUCCESS]), newest-first.
     */
    @Synchronized
    fun byStatus(status: String): List<SessionHistorySummary> =
        all().filter { it.status == status }

    /** Number of entries currently held in the store. */
    @Synchronized
    fun size(): Int = entries.size

    /**
     * Removes all entries from the store and clears the persisted data.
     */
    @Synchronized
    fun clear() {
        entries.clear()
        prefs?.edit()?.remove(PREFS_KEY)?.apply()
        Log.d(TAG, "Session history cleared")
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        entries.removeAll { it.savedAtMs < cutoff }
    }

    private fun evictOverflow() {
        while (entries.size > maxEntries) {
            val removed = entries.removeFirst()
            Log.d(TAG, "Evicted oldest session history entry: ${removed.sessionId}")
        }
    }

    private fun loadFromPrefs() {
        val prefs = prefs ?: return
        val json = prefs.getString(PREFS_KEY, null) ?: return
        try {
            val loaded: List<SessionHistorySummary> = gson.fromJson(json, LIST_TYPE) ?: return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val fresh = loaded.filter { it.savedAtMs >= cutoff }
            entries.addAll(fresh.takeLast(maxEntries))
            Log.d(TAG, "Loaded ${entries.size} session history entries from prefs (${loaded.size - entries.size} stale dropped)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session history from prefs: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val prefs = prefs ?: return
        try {
            val json = gson.toJson(entries.toList())
            prefs.edit().putString(PREFS_KEY, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist session history: ${e.message}")
        }
    }
}
