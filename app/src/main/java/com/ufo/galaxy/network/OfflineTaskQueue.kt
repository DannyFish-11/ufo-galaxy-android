package com.ufo.galaxy.network

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.runtime.AndroidContinuityIntegration
import com.ufo.galaxy.runtime.UnifiedReplayRecoveryContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

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
     * [QUEUEABLE_TYPES] ("task_result", "goal_result", "goal_execution_result",
     * "delegated_execution_signal", "device_execution_event", "reconciliation_signal") are candidates
 * for queuing.  Heartbeats, handshakes, and diagnostics are never queued.
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

    data class ReplayGovernanceDecision(
        val message: QueuedMessage,
        val disposition: AndroidContinuityIntegration.ContinuityDisposition,
        val action: AndroidContinuityIntegration.ContinuityGovernanceAction,
        val shouldForward: Boolean,
        val reason: String
    )

    companion object {
        const val TAG = "WS:OfflineQueue"
        const val MAX_QUEUE_SIZE = 50
        private const val PREFS_KEY = "offline_task_queue"

        /** Messages older than this threshold are discarded when the queue is loaded from prefs. */
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** JSON `type` values that should be queued during offline periods.
         *
         * "goal_execution_result" is the canonical uplink result type used on all main
         * production paths (task_assign / goal_execution / parallel_subtask).  It must be
         * queueable so that results produced while the WebSocket is temporarily disconnected
         * are buffered and retransmitted on reconnect rather than silently dropped.
         *
         * "delegated_execution_signal" carries delegated takeover ACK / PROGRESS / RESULT
         * lifecycle updates.  It must also be queueable so takeover recovery remains
         * observable and idempotent across disconnect/reconnect transport interruptions.
         *
         * "device_execution_event" carries canonical Android execution lifecycle truth.
         * It must be queueable so start/progress/retry/interruption/terminal evidence
         * can be replayed on reconnect instead of being silently dropped.
         *
         * "reconciliation_signal" carries continuity-repair / state-reconciliation truth.
         * It must be queueable so continuity repair survives transient transport failures
         * instead of silently degrading into best-effort logging.
         *
         * "task_result" and "goal_result" are retained for backward-compatibility with legacy
         * paths and REST-fallback callers.
         */
        val QUEUEABLE_TYPES: Set<String> = setOf(
            "task_result",
            "goal_result",
            "goal_execution_result",
            "delegated_execution_signal",
            "device_execution_event",
            "device_state_snapshot",
            "device_acceptance_report",
            MsgType.RECONCILIATION_SIGNAL.value
        )

        /**
         * Queue types that require continuity-epoch metadata to be replay-forwardable.
         *
         * These message classes influence canonical result, runtime-truth, or reconciliation
         * continuity and therefore must not be replayed without explicit ordering scope.
         */
        val ORDERING_METADATA_REQUIRED_TYPES: Set<String> =
            com.ufo.galaxy.runtime.AndroidCrossRepoDedupeContract.REPLAY_EPOCH_REQUIRED_TYPES
    }

    /**
     * A single queued outgoing message.
     *
     * @param type       The AIP message type string (e.g. "task_result").
     * @param json       The fully-serialised JSON payload ready to transmit.
     * @param queuedAt   Epoch-millis timestamp used for stale-message eviction on load.
     * @param sessionTag Optional session/authority tag identifying the durable session that
     *                   was active when this message was enqueued.  When non-null, the tag
     *                   is compared against the current active session during drain to detect
     *                   stale authority.  Messages tagged with a session that has since
     *                   changed are discarded by [discardForDifferentSession] before flush.
     *                   Null for messages enqueued without session tracking (pre-PR-66
     *                   callers). Authority-sensitive null-tag replay is blocked by
     *                   [classifyForReplay] to avoid promoting historical canonical outputs
     *                   into new authority events during restore/replay.
     */
    data class QueuedMessage(
        val type: String,
        val json: String,
        val queuedAt: Long = System.currentTimeMillis(),
        val sessionTag: String? = null,
        val sessionEpoch: Int? = null,
        val dedupeKey: String? = null,
        val queueSequence: Long = 0L
    )

    private val queue = ArrayDeque<QueuedMessage>()
    private val lock = Any()
    private val queueSequenceCounter = AtomicLong(0L)

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
     *
     * @param type       The AIP message type string (e.g. "task_result").
     * @param json       The fully-serialised JSON payload.
     * @param sessionTag Optional durable-session identity tag for authority bounding.
     *                   Pass the current [DurableSessionContinuityRecord.durableSessionId]
     *                   (or equivalent) so that [discardForDifferentSession] can purge this
     *                   message if the session has changed before the queue is drained.
     *                   Omit (default `null`) if the caller does not need session tracking.
     * @param sessionEpoch Optional continuity epoch within [sessionTag].  Used by
     *                     [discardReconciliationSignalsForDifferentEpoch] to block replay of
     *                     stale reconciliation signals after reconnect.
     * @param dedupeKey Optional stable delivery key. When present, duplicate detection prefers
     *                  `(type, sessionTag, sessionEpoch, dedupeKey)` over raw JSON equality so
     *                  retries and re-emits of the same logical reconciliation event collapse.
     */
    fun enqueue(
        type: String,
        json: String,
        sessionTag: String? = null,
        sessionEpoch: Int? = null,
        dedupeKey: String? = null
    ) {
        val newSize = synchronized(lock) {
            val duplicate = queue.any {
                if (dedupeKey != null && it.dedupeKey != null) {
                    it.type == type &&
                        it.sessionTag == sessionTag &&
                        it.sessionEpoch == sessionEpoch &&
                        it.dedupeKey == dedupeKey
                } else {
                    it.type == type && it.json == json && it.sessionTag == sessionTag
                }
            }
            if (duplicate) {
                Log.i(
                    TAG,
                    "[WS:OfflineQueue] Suppressed duplicate queued message " +
                        "type=$type session_tag=${sessionTag ?: "none"} " +
                        "session_epoch=${sessionEpoch ?: "none"} dedupe_key=${dedupeKey ?: "none"}"
                )
                return@synchronized queue.size
            }
            if (queue.size >= maxQueueSize) {
                val dropped = queue.poll()
                Log.w(
                    TAG,
                    "[WS:OfflineQueue] Queue full ($maxQueueSize); dropped oldest " +
                        "type=${dropped?.type} queuedAt=${dropped?.queuedAt}"
                )
            }
            queue.add(
                QueuedMessage(
                    type = type,
                    json = json,
                    sessionTag = sessionTag,
                    sessionEpoch = sessionEpoch,
                    dedupeKey = dedupeKey,
                    queueSequence = queueSequenceCounter.incrementAndGet()
                )
            )
            Log.d(TAG, "[WS:OfflineQueue] Enqueued type=$type queue_size=${queue.size}")
            saveToPrefsLocked()
            queue.size
        }
        _sizeFlow.value = newSize
    }

    fun classifyForReplay(
        message: QueuedMessage,
        currentTag: String?
    ): ReplayGovernanceDecision {
        val authorityDecision = UnifiedReplayRecoveryContract.evaluateMessageAuthority(
            message = message,
            currentDurableSessionId = currentTag
        )
        return when (authorityDecision) {
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_CURRENT_AUTHORITY_BLOCKED ->
            ReplayGovernanceDecision(
                message = message,
                disposition = AndroidContinuityIntegration.ContinuityDisposition.ATTACHMENT_ONLY_RECOVERY,
                action = AndroidContinuityIntegration.ContinuityGovernanceAction.REQUIRE_V2_REVALIDATION,
                shouldForward = false,
                reason = "queued_result_without_current_durable_authority"
            )
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_SESSION_TAG_AUTHORITY_REPLAY_BLOCKED ->
            ReplayGovernanceDecision(
                message = message,
                disposition = AndroidContinuityIntegration.ContinuityDisposition.ATTACHMENT_ONLY_RECOVERY,
                action = AndroidContinuityIntegration.ContinuityGovernanceAction.REQUIRE_V2_REVALIDATION,
                shouldForward = false,
                reason = "authority_sensitive_replay_requires_session_tag"
            )
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.STALE_SESSION_BLOCKED ->
            ReplayGovernanceDecision(
                message = message,
                disposition = AndroidContinuityIntegration.ContinuityDisposition.STALE_RESULT,
                action = AndroidContinuityIntegration.ContinuityGovernanceAction.REJECT_STALE,
                shouldForward = false,
                reason = "stale_durable_session_tag"
            )
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.NO_SESSION_TAG_FORWARDED,
            UnifiedReplayRecoveryContract.MessageAuthorityDecision.SAME_SESSION_REPLAY_ALLOWED ->
            if (message.type in ORDERING_METADATA_REQUIRED_TYPES && message.sessionEpoch == null) {
                ReplayGovernanceDecision(
                    message = message,
                    disposition = AndroidContinuityIntegration.ContinuityDisposition.ATTACHMENT_ONLY_RECOVERY,
                    action = AndroidContinuityIntegration.ContinuityGovernanceAction.REQUIRE_V2_REVALIDATION,
                    shouldForward = false,
                    reason = "replay_order_metadata_missing_session_epoch"
                )
            } else {
                ReplayGovernanceDecision(
                    message = message,
                    disposition = AndroidContinuityIntegration.ContinuityDisposition.REPLAY,
                    action = AndroidContinuityIntegration.ContinuityGovernanceAction.ALLOW_REPLAY,
                    shouldForward = true,
                    reason = if (message.sessionTag == null) {
                        "legacy_queue_entry_without_session_tag"
                    } else {
                        "durable_session_tag_matches_current_authority"
                    }
                )
            }
        }
    }

    fun previewReplayGovernance(currentTag: String?): List<ReplayGovernanceDecision> =
        synchronized(lock) {
            queue.map { classifyForReplay(it, currentTag) }
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

    /**
     * Removes all queued messages whose [QueuedMessage.sessionTag] is non-null and does
     * not equal [currentTag].
     *
     * This method is the Android-side authority-bounding gate for offline queue drain.
     * Call it immediately before [drainAll] on reconnect to ensure that messages enqueued
     * under an earlier durable session are not sent after the authority window has changed:
     *
     * ```kotlin
     * // On reconnect — purge stale-session messages before drain:
     * val discarded = offlineQueue.discardForDifferentSession(currentDurableSessionId)
     * if (discarded > 0) {
     *     Log.i(TAG, "Discarded $discarded offline message(s) from prior session era")
     * }
     * val pending = offlineQueue.drainAll()
     * pending.forEach { ws.sendJson(it.json) }
     * ```
     *
     * Messages with a **null** [QueuedMessage.sessionTag] are evaluated by
     * [classifyForReplay]. Authority-sensitive replay types are blocked to avoid replaying
     * historical canonical outputs as new authority events, while non-authority types remain
     * compatible.
     *
     * @param currentTag  The active durable session identity tag.  Messages tagged with a
     *                    **different** non-null value are discarded.
     * @return            The number of messages removed.
     */
    fun discardForDifferentSession(currentTag: String): Int {
        val result = synchronized(lock) {
            val before = queue.size
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                val decision = classifyForReplay(msg, currentTag)
                if (!decision.shouldForward) {
                    iter.remove()
                }
            }
            val discarded = before - queue.size
            val newSize = queue.size
            if (discarded > 0) {
                saveToPrefsLocked()
                Log.i(
                    TAG,
                    "[WS:OfflineQueue] Discarded $discarded stale-session message(s) " +
                        "(currentTag=$currentTag)"
                )
            }
            Pair(discarded, newSize)
        }
        _sizeFlow.value = result.second
        return result.first
    }

    /**
     * Removes all queued messages that carry a non-null session tag when no current
     * session authority is available.
     *
     * This is the load/reconnect recovery guard for persisted queues: a tagged message
     * proves it belongs to a specific durable session era, so replaying it while Android
     * cannot identify the current era would bypass authority validation.  Null-tagged
     * legacy messages remain compatible and are preserved.
     */
    fun discardSessionTaggedWithoutAuthority(): Int {
        val result = synchronized(lock) {
            val before = queue.size
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                val decision = classifyForReplay(msg, currentTag = null)
                if (!decision.shouldForward) {
                    iter.remove()
                }
            }
            val discarded = before - queue.size
            val newSize = queue.size
            if (discarded > 0) {
                saveToPrefsLocked()
                Log.i(
                    TAG,
                    "[WS:OfflineQueue] Discarded $discarded session-tagged message(s) " +
                        "because current session authority is unavailable"
                )
            }
            Pair(discarded, newSize)
        }
        _sizeFlow.value = result.second
        return result.first
    }

    /**
     * Removes queued reconciliation signals whose bounded continuity epoch does not match the
     * current authority epoch.
     *
     * This prevents a reconciliation signal emitted under an older reconnect epoch from being
     * replayed after Android has already advanced to a newer continuity era within the same
     * durable session.
     */
    fun discardReconciliationSignalsForDifferentEpoch(currentEpoch: Int?): Int {
        val result = synchronized(lock) {
            val before = queue.size
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                if (msg.type != MsgType.RECONCILIATION_SIGNAL.value) continue
                val stale =
                    msg.sessionEpoch == null ||
                        currentEpoch == null ||
                        msg.sessionEpoch != currentEpoch
                if (stale) {
                    iter.remove()
                }
            }
            val discarded = before - queue.size
            val newSize = queue.size
            if (discarded > 0) {
                saveToPrefsLocked()
                Log.i(
                    TAG,
                    "[WS:OfflineQueue] Discarded $discarded stale reconciliation_signal message(s) " +
                        "(currentEpoch=${currentEpoch ?: "none"})"
                )
            }
            Pair(discarded, newSize)
        }
        _sizeFlow.value = result.second
        return result.first
    }

    /**
     * Removes queued lineage-bound messages whose recorded epoch does not match [currentEpoch].
     *
     * Applies to all queued messages that carry a non-null [QueuedMessage.sessionEpoch], not only
     * reconciliation signals. This blocks replay of old-epoch artifacts for result/completion/event/
     * snapshot uplinks after continuity advances.
     */
    fun discardLineageBoundMessagesForDifferentEpoch(currentEpoch: Int?): Int {
        val result = synchronized(lock) {
            val before = queue.size
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val msg = iter.next()
                val messageEpoch = msg.sessionEpoch ?: continue
                val stale = currentEpoch == null || messageEpoch != currentEpoch
                if (stale) {
                    iter.remove()
                }
            }
            val discarded = before - queue.size
            val newSize = queue.size
            if (discarded > 0) {
                saveToPrefsLocked()
                Log.i(
                    TAG,
                    "[WS:OfflineQueue] Discarded $discarded stale lineage-bound message(s) " +
                        "(currentEpoch=${currentEpoch ?: "none"})"
                )
            }
            Pair(discarded, newSize)
        }
        _sizeFlow.value = result.second
        return result.first
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
                val maxLoadedSequence = queue.maxOfOrNull { it.queueSequence } ?: 0L
                queueSequenceCounter.updateAndGet { current -> maxOf(current, maxLoadedSequence) }
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
