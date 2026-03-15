package com.ufo.galaxy.webrtc

import android.util.Log

/**
 * Manages the collection, deduplication, priority-ordering, and TURN-fallback
 * strategy for ICE candidates received from the Galaxy Gateway signaling proxy.
 *
 * ## Candidate priority order (consistent with server Round-6 policy)
 * 1. `relay`  – TURN relay candidates (highest priority; most reliable through NAT/firewalls)
 * 2. `srflx`  – STUN server-reflexive candidates
 * 3. `host`   – Direct local candidates (lowest priority)
 *
 * ## Duplicate detection
 * Candidates are deduplicated by their raw SDP `candidate` string; the first
 * occurrence wins and subsequent duplicates are silently dropped.
 *
 * ## TURN fallback
 * When direct connectivity (host/srflx) fails after [CONNECTION_TIMEOUT_MS] ms,
 * [startTurnFallback] switches to relay-only mode and retries connection
 * establishment with exponential backoff up to [MAX_FALLBACK_ATTEMPTS] times.
 *
 * ## Usage
 * ```kotlin
 * val manager = IceCandidateManager(traceId = "abc-123") { candidate ->
 *     peerConnection.addIceCandidate(candidate)  // apply to PeerConnection
 * }
 * manager.addCandidates(message.allCandidates)
 * ```
 *
 * @param traceId          Trace identifier for log correlation and error reporting.
 * @param onApplyCandidate Callback invoked for each candidate that should be applied
 *                         to the underlying [org.webrtc.PeerConnection]. Called on the
 *                         same thread that invokes [addCandidate] / [addCandidates].
 * @param onError          Callback invoked when a non-recoverable error or timeout
 *                         occurs. Receives a human-readable message tagged with [traceId].
 */
class IceCandidateManager(
    val traceId: String = "",
    private val onApplyCandidate: (SignalingMessage.IceCandidate) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "IceCandidateManager"

    // Ordered insertion map: key = raw candidate string → value = IceCandidate
    private val seen = LinkedHashMap<String, SignalingMessage.IceCandidate>()

    /** Number of candidates currently held (after dedup). */
    val size: Int get() = seen.size

    /** Returns `true` when the manager holds no candidates. */
    val isEmpty: Boolean get() = seen.isEmpty()

    @Volatile
    private var turnFallbackActive: Boolean = false

    /** Number of TURN fallback retry attempts performed so far. */
    @Volatile
    var fallbackAttempts: Int = 0
        private set

    /**
     * Backoff delay (ms) that the caller should honour before triggering the next
     * reconnect attempt after [startTurnFallback] returns `true`.
     *
     * Set to the computed delay of the most recent successful fallback attempt;
     * reset to 0 by [reset].
     */
    @Volatile
    var lastBackoffDelayMs: Long = 0L
        private set

    companion object {
        /** Maximum retry attempts in TURN-only fallback mode. */
        const val MAX_FALLBACK_ATTEMPTS = 3

        /** Backoff delays (ms) between fallback attempts. */
        val FALLBACK_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

        /** Timeout (ms) before triggering TURN fallback when direct connectivity fails. */
        const val CONNECTION_TIMEOUT_MS = 10_000L

        /** Priority value assigned to each candidate type (higher = more preferred). */
        fun priorityOf(type: String): Int = when (type) {
            SignalingMessage.IceCandidate.TYPE_RELAY -> 3
            SignalingMessage.IceCandidate.TYPE_SRFLX -> 2
            SignalingMessage.IceCandidate.TYPE_HOST  -> 1
            else                                     -> 0
        }
    }

    /**
     * Add a single ICE candidate.
     *
     * Duplicates (by raw SDP string) are silently discarded. When [turnFallbackActive]
     * is `true`, non-relay candidates are skipped so that only TURN candidates are
     * applied to the peer connection.
     *
     * @return `true` if the candidate was accepted and [onApplyCandidate] was invoked;
     *         `false` if it was a duplicate or filtered by TURN fallback mode.
     */
    fun addCandidate(candidate: SignalingMessage.IceCandidate): Boolean {
        if (seen.containsKey(candidate.candidate)) {
            Log.d(TAG, "[trace=$traceId] Duplicate candidate dropped: ${candidate.candidate.take(60)}")
            return false
        }
        seen[candidate.candidate] = candidate
        if (turnFallbackActive && candidate.candidateType != SignalingMessage.IceCandidate.TYPE_RELAY) {
            Log.d(TAG, "[trace=$traceId] TURN fallback active – skipping ${candidate.candidateType} candidate")
            return false
        }
        Log.d(TAG, "[trace=$traceId] Applying ${candidate.candidateType} candidate")
        onApplyCandidate(candidate)
        return true
    }

    /**
     * Add multiple ICE candidates in one call (trickle/batch).
     *
     * Candidates are sorted by priority (relay → srflx → host) before being
     * applied, regardless of the order they arrive from the gateway.
     *
     * @return the number of candidates actually applied (excluding duplicates /
     *         filtered entries).
     */
    fun addCandidates(candidates: List<SignalingMessage.IceCandidate>): Int {
        val sorted = candidates.sortedByDescending { priorityOf(it.candidateType) }
        return sorted.count { addCandidate(it) }
    }

    /**
     * Return all held candidates sorted by priority (relay → srflx → host).
     * Within the same type, insertion order is preserved.
     */
    fun getOrderedCandidates(): List<SignalingMessage.IceCandidate> =
        seen.values.sortedByDescending { priorityOf(it.candidateType) }

    /**
     * Return only TURN relay candidates held by the manager.
     */
    fun getTurnCandidates(): List<SignalingMessage.IceCandidate> =
        seen.values.filter { it.candidateType == SignalingMessage.IceCandidate.TYPE_RELAY }

    /**
     * Returns `true` when the manager holds at least one TURN relay candidate.
     */
    fun hasTurnCandidates(): Boolean =
        seen.values.any { it.candidateType == SignalingMessage.IceCandidate.TYPE_RELAY }

    /**
     * Activate TURN-only fallback mode and re-apply all relay candidates already held.
     *
     * Call this when direct connectivity (host/srflx) fails and TURN candidates are
     * available. On each call the [fallbackAttempts] counter is incremented; if
     * [MAX_FALLBACK_ATTEMPTS] is exceeded [onError] is invoked instead.
     *
     * @return `true` if fallback was initiated; `false` if attempts are exhausted or
     *         no TURN candidates are available.
     */
    fun startTurnFallback(): Boolean {
        if (fallbackAttempts >= MAX_FALLBACK_ATTEMPTS) {
            val msg = "[trace=$traceId] TURN fallback exhausted after $fallbackAttempts attempts"
            Log.e(TAG, msg)
            onError(msg)
            return false
        }
        if (!hasTurnCandidates()) {
            val msg = "[trace=$traceId] TURN fallback requested but no relay candidates available"
            Log.w(TAG, msg)
            onError(msg)
            return false
        }
        turnFallbackActive = true
        fallbackAttempts++
        lastBackoffDelayMs = if (fallbackAttempts <= FALLBACK_BACKOFF_MS.size)
            FALLBACK_BACKOFF_MS[fallbackAttempts - 1]
        else
            FALLBACK_BACKOFF_MS.last()
        Log.w(TAG, "[trace=$traceId] Starting TURN fallback attempt $fallbackAttempts/$MAX_FALLBACK_ATTEMPTS " +
                "(backoff=${lastBackoffDelayMs}ms); caller should delay before retrying connectivity")
        // Re-apply all relay candidates held so far
        val relayCount = getTurnCandidates().count { c ->
            Log.d(TAG, "[trace=$traceId] Re-applying relay candidate (fallback)")
            onApplyCandidate(c)
            true
        }
        Log.i(TAG, "[trace=$traceId] TURN fallback: re-applied $relayCount relay candidate(s)")
        return true
    }

    /**
     * Reset to the initial state: clear all candidates, deactivate TURN fallback,
     * and reset the attempt counter.
     */
    fun reset() {
        seen.clear()
        turnFallbackActive = false
        fallbackAttempts = 0
        lastBackoffDelayMs = 0L
        Log.d(TAG, "[trace=$traceId] IceCandidateManager reset")
    }

    /** Returns `true` when TURN-only fallback mode is currently active. */
    val isTurnFallbackActive: Boolean get() = turnFallbackActive
}
