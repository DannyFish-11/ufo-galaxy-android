package com.ufo.galaxy.history

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ufo.galaxy.runtime.DelegatedFlowContinuityRecord

/**
 * PR-2 (Android) — Persistent store for [DelegatedFlowContinuityRecord] entries.
 *
 * [DelegatedFlowContinuityStore] elevates the delegated flow continuity metadata from
 * the DURABLE_IN_MEMORY tier to the SETTINGS_PERSISTED tier, allowing Android to
 * reconstruct delegated flow continuity context after a process kill, app restart, or
 * reconnect without requiring V2 to re-deliver the full execution envelope.
 *
 * ## Design
 *
 * The store follows the same pattern as [SessionHistoryStore]:
 *
 *  - **Persistence**: optional [SharedPreferences] backing; when [prefs] is `null` the
 *    store operates in in-memory mode (useful for unit tests and environments without
 *    Android APIs).
 *  - **Bounded**: the most recent [maxEntries] records are retained; oldest entries are
 *    evicted when the limit is exceeded (drop-oldest policy).
 *  - **TTL eviction**: records older than [maxAgeMs] are discarded on load and on save.
 *  - **Thread-safe**: all public methods are `@Synchronized` on `this`.
 *  - **Idempotent saves**: saving a record with the same [DelegatedFlowContinuityRecord.flowId]
 *    replaces the existing entry, so callers can checkpoint on every phase transition
 *    without accumulating duplicates.
 *
 * ## Lifecycle integration
 *
 * The intended call pattern is:
 *
 * ```kotlin
 * // On each significant phase transition of a delegated flow:
 * val record = DelegatedFlowContinuityRecord.fromTracker(
 *     tracker = currentTracker,
 *     durableSession = currentDurableSession,
 *     executionPhase = AndroidFlowExecutionPhase.ACTIVE_GOAL_EXECUTION,
 *     continuityToken = inboundContinuityToken
 * )
 * flowContinuityStore.save(record)
 *
 * // On process recreation re-attach (recovery read path):
 * val priorRecords = flowContinuityStore.activeCandidates()
 * // Inspect priorRecords to decide whether to report prior flow context to V2
 * ```
 *
 * ## Authority boundary
 *
 * Records in this store are **advisory hints** for V2 correlation, not canonical flow
 * state.  Android MUST NOT self-authorise flow resumption based solely on persisted
 * records.  V2 remains the authoritative session and flow coordinator.
 *
 * @param prefs       Optional [SharedPreferences] for persistence. `null` = in-memory only.
 * @param gson        [Gson] instance for JSON serialisation. Defaults to a plain [Gson].
 * @param maxEntries  Maximum number of records to retain. Default: [DEFAULT_MAX_ENTRIES].
 * @param maxAgeMs    Maximum age of a retained record in milliseconds.
 *                    Default: [DEFAULT_MAX_AGE_MS] (48 hours — shorter than [SessionHistoryStore]
 *                    because delegated flow continuity hints are only useful within a
 *                    reasonable re-attach window).
 *
 * @see DelegatedFlowContinuityRecord
 * @see SessionHistoryStore
 */
class DelegatedFlowContinuityStore(
    private val prefs: SharedPreferences? = null,
    private val gson: Gson = Gson(),
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {

    companion object {
        const val TAG = "GALAXY:FLOW_CONTINUITY_STORE"

        /** Default capacity: retain the last 20 delegated flow continuity records. */
        const val DEFAULT_MAX_ENTRIES = 20

        /**
         * Default TTL: 48 hours in milliseconds.
         *
         * Shorter than [SessionHistoryStore.DEFAULT_MAX_AGE_MS] (7 days) because
         * delegated flow continuity hints are actionable only within a reasonable
         * re-attach window.  Stale hints beyond 48 hours are unlikely to be matched
         * by V2 and should not accumulate on the device.
         */
        const val DEFAULT_MAX_AGE_MS = 48L * 60L * 60L * 1000L

        private const val PREFS_KEY = "delegated_flow_continuity"

        private val LIST_TYPE =
            object : TypeToken<List<DelegatedFlowContinuityRecord>>() {}.type
    }

    // Insertion-ordered: newest entries are appended at the end; reversed on read.
    private val entries = ArrayDeque<DelegatedFlowContinuityRecord>()

    init {
        loadFromPrefs()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Saves a [DelegatedFlowContinuityRecord] to the store.
     *
     * If a record with the same [DelegatedFlowContinuityRecord.flowId] already exists it
     * is replaced (idempotent checkpointing on repeated phase transitions).  Oldest
     * records are evicted when [maxEntries] is exceeded.  Stale records are purged
     * before saving.
     *
     * @param record The [DelegatedFlowContinuityRecord] to persist.
     */
    @Synchronized
    fun save(record: DelegatedFlowContinuityRecord) {
        entries.removeAll { it.flowId == record.flowId }
        entries.addLast(record)
        evictStale()
        evictOverflow()
        saveToPrefs()
        Log.d(TAG, "Saved flow continuity record: flowId=${record.flowId} phase=${record.executionPhase}")
    }

    /**
     * Returns all retained records, newest-first.
     *
     * The returned list is a snapshot; subsequent store mutations do not affect it.
     */
    @Synchronized
    fun all(): List<DelegatedFlowContinuityRecord> = entries.toList().reversed()

    /**
     * Returns the most recent [limit] records, newest-first.
     *
     * Equivalent to [all] when the store contains fewer than [limit] entries.
     *
     * @param limit Maximum number of records to return.
     */
    @Synchronized
    fun recent(limit: Int): List<DelegatedFlowContinuityRecord> = all().take(limit)

    /**
     * Looks up the most recent record for the given [flowId], or `null` when not found.
     *
     * This is the primary **recovery read path**: on process-recreation re-attach,
     * callers query by the flow ID that V2 will re-dispatch, allowing Android to
     * include the persisted continuity context in the re-attach event metadata.
     *
     * @param flowId The delegated flow identifier to look up.
     * @return The most recent [DelegatedFlowContinuityRecord] for [flowId], or `null`.
     */
    @Synchronized
    fun findByFlowId(flowId: String): DelegatedFlowContinuityRecord? =
        all().firstOrNull { it.flowId == flowId }

    /**
     * Returns all non-terminal records that are candidates for continuity recovery.
     *
     * "Active candidates" are records whose [DelegatedFlowContinuityRecord.executionPhase]
     * is not a terminal phase (completed / failed / rejected).  On process-recreation
     * re-attach, Android should report these to V2 as flows that were in progress at
     * the time of the process kill.
     *
     * Records are returned newest-first.
     *
     * @return All non-terminal [DelegatedFlowContinuityRecord] entries in the store.
     */
    @Synchronized
    fun activeCandidates(): List<DelegatedFlowContinuityRecord> =
        all().filter { !it.isTerminalPhase }

    /**
     * Returns all records associated with the given [durableSessionId], newest-first.
     *
     * Useful when V2 re-delivers a dispatch under the same durable session era: Android
     * can retrieve all flows it executed under that session to check for prior context.
     *
     * @param durableSessionId The [DelegatedFlowContinuityRecord.durableSessionId] to filter by.
     */
    @Synchronized
    fun findByDurableSessionId(durableSessionId: String): List<DelegatedFlowContinuityRecord> =
        all().filter { it.durableSessionId == durableSessionId }

    /** Number of records currently held in the store. */
    @Synchronized
    fun size(): Int = entries.size

    /**
     * Removes all records from the store and clears the persisted data.
     *
     * Intended for explicit session invalidation or factory reset flows; do not call
     * during normal reconnect handling.
     */
    @Synchronized
    fun clear() {
        entries.clear()
        prefs?.edit()?.remove(PREFS_KEY)?.apply()
        Log.d(TAG, "Delegated flow continuity store cleared")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        entries.removeAll { it.savedAtMs < cutoff }
    }

    private fun evictOverflow() {
        while (entries.size > maxEntries) {
            val removed = entries.removeFirst()
            Log.d(TAG, "Evicted oldest flow continuity record: flowId=${removed.flowId}")
        }
    }

    private fun loadFromPrefs() {
        val prefs = prefs ?: return
        val json = prefs.getString(PREFS_KEY, null) ?: return
        try {
            val loaded: List<DelegatedFlowContinuityRecord> =
                gson.fromJson(json, LIST_TYPE) ?: return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val fresh = loaded.filter { it.savedAtMs >= cutoff }
            entries.addAll(fresh.takeLast(maxEntries))
            Log.d(
                TAG,
                "Loaded ${entries.size} flow continuity records from prefs " +
                    "(${loaded.size - entries.size} stale dropped)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load flow continuity records from prefs: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val prefs = prefs ?: return
        try {
            val json = gson.toJson(entries.toList())
            prefs.edit().putString(PREFS_KEY, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist flow continuity records: ${e.message}")
        }
    }
}
