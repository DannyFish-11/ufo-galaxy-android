package com.ufo.galaxy.runtime

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * SharedPreferences-backed store for [EmittedSignalLedger] snapshots.
 *
 * This closes the cross-process replay gap for delegated execution signals: after each
 * emitted ACK/PROGRESS/RESULT is recorded, callers can save the ledger under a stable
 * execution key and restore the same signal identities after process recreation.
 */
class PersistentEmittedSignalLedgerStore(
    private val prefs: SharedPreferences? = null,
    private val gson: Gson = Gson(),
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {

    data class Entry(
        val executionKey: String,
        val snapshot: EmittedSignalLedger.Snapshot
    )

    private val entries = ArrayDeque<Entry>()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun save(executionKey: String, ledger: EmittedSignalLedger) {
        if (executionKey.isBlank()) return
        entries.removeAll { it.executionKey == executionKey }
        entries.addLast(Entry(executionKey, ledger.toSnapshot()))
        evictStale()
        evictOverflow()
        saveToPrefs()
    }

    @Synchronized
    fun restore(executionKey: String): EmittedSignalLedger? =
        restoreSnapshot(executionKey)?.let { EmittedSignalLedger.fromSnapshot(it) }

    @Synchronized
    fun restoreSnapshot(executionKey: String): EmittedSignalLedger.Snapshot? =
        entries.lastOrNull { it.executionKey == executionKey }?.snapshot

    @Synchronized
    fun keys(): List<String> = entries.map { it.executionKey }.reversed()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        prefs?.edit()?.remove(PREFS_KEY)?.apply()
    }

    private fun evictStale() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        entries.removeAll { it.snapshot.savedAtMs < cutoff }
    }

    private fun evictOverflow() {
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }

    private fun loadFromPrefs() {
        val prefs = prefs ?: return
        val json = prefs.getString(PREFS_KEY, null) ?: return
        try {
            val loaded: List<Entry> = gson.fromJson(json, LIST_TYPE) ?: return
            val cutoff = System.currentTimeMillis() - maxAgeMs
            entries.addAll(
                loaded
                    .filter { it.executionKey.isNotBlank() && it.snapshot.savedAtMs >= cutoff }
                    .takeLast(maxEntries)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load emitted signal ledger snapshots: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val prefs = prefs ?: return
        try {
            prefs.edit().putString(PREFS_KEY, gson.toJson(entries.toList())).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist emitted signal ledger snapshots: ${e.message}")
        }
    }

    companion object {
        const val TAG = "GALAXY:EMIT_LEDGER_STORE"
        const val DEFAULT_MAX_ENTRIES = 20
        const val DEFAULT_MAX_AGE_MS = 48L * 60L * 60L * 1000L

        private const val PREFS_KEY = "emitted_signal_ledgers"

        private val LIST_TYPE =
            object : TypeToken<List<Entry>>() {}.type

        fun executionKeyFor(unitId: String, taskId: String, traceId: String): String =
            "$unitId|$taskId|$traceId"
    }
}
