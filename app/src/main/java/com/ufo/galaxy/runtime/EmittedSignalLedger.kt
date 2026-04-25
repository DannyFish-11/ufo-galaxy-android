package com.ufo.galaxy.runtime

/**
 * **Lightweight in-memory ledger for emitted delegated-execution signals** (PR-18,
 * post-#533 dual-repo runtime unification master plan — Replay-Safe Delegated Signal
 * Re-Emission Foundations, Android side).
 *
 * [EmittedSignalLedger] is the authoritative Android-side store for the most recently
 * emitted [DelegatedExecutionSignal] per [DelegatedExecutionSignal.Kind].  It exists
 * solely to support **stable-identity replay** — the ability to re-emit a previously
 * emitted signal with the **same [DelegatedExecutionSignal.signalId] and
 * [DelegatedExecutionSignal.emissionSeq]** — which is the foundational requirement for
 * host-side idempotency and ordering-guard safety during recovery scenarios.
 *
 * ## Why a ledger is needed
 *
 * PR-15 established that every emitted signal carries a stable [DelegatedExecutionSignal.signalId]
 * and a monotonic [DelegatedExecutionSignal.emissionSeq].  If a replay re-calls the
 * factory methods ([DelegatedExecutionSignal.ack], [progress], [result]) it would
 * generate a **fresh** [signalId], causing the host to see an apparently new emission
 * rather than a re-delivery of the original.  This would corrupt host-side idempotency
 * state and could confuse the host ordering guard.
 *
 * [EmittedSignalLedger] solves this by retaining the original signal object.  Replay is
 * then a matter of calling [DelegatedExecutionSignal.replayAt] on the stored signal,
 * which produces a copy with the **original identity preserved** and only [timestampMs]
 * updated to reflect the re-emission time.
 *
 * ## Scope and lifecycle
 *
 * One [EmittedSignalLedger] instance is expected per delegated-execution invocation.
 * [DelegatedTakeoverExecutor] creates a fresh ledger at the start of [execute] and passes
 * it back to callers via [DelegatedTakeoverExecutor.ExecutionOutcome.ledger], so that
 * callers can later replay any of the three canonical signals without re-invoking the
 * factory methods.
 *
 * ## Non-goals
 *
 * - **Not a persistent resend queue**: signals are stored only in memory and are not
 *   serialised to disk.  Replay across process restarts is out of scope.
 * - **Not thread-safe by design**: [EmittedSignalLedger] is intended to be used on a
 *   single thread (or within a structured-concurrency coroutine scope) that serialises
 *   all emission and replay operations.
 */
class EmittedSignalLedger {

    // ── Internal storage ──────────────────────────────────────────────────────

    private var _ack: DelegatedExecutionSignal? = null
    private var _progress: DelegatedExecutionSignal? = null
    private var _result: DelegatedExecutionSignal? = null

    // ── Public API — recording ────────────────────────────────────────────────

    /**
     * Records [signal] in this ledger, replacing any previously stored signal of the same
     * [DelegatedExecutionSignal.Kind].
     *
     * Call this immediately after each [DelegatedExecutionSignalSink.onSignal] invocation
     * so that the ledger always holds the most recently emitted signal for each kind.
     *
     * @param signal The signal that was just emitted.
     */
    fun recordEmitted(signal: DelegatedExecutionSignal) {
        when (signal.kind) {
            DelegatedExecutionSignal.Kind.ACK      -> _ack = signal
            DelegatedExecutionSignal.Kind.PROGRESS -> _progress = signal
            DelegatedExecutionSignal.Kind.RESULT   -> _result = signal
        }
    }

    // ── Public API — retrieval ────────────────────────────────────────────────

    /**
     * Returns the last recorded [DelegatedExecutionSignal.Kind.ACK] signal, or `null` if
     * no ACK has been recorded yet.
     */
    val lastAck: DelegatedExecutionSignal?
        get() = _ack

    /**
     * Returns the last recorded [DelegatedExecutionSignal.Kind.PROGRESS] signal, or `null`
     * if no PROGRESS has been recorded yet.
     */
    val lastProgress: DelegatedExecutionSignal?
        get() = _progress

    /**
     * Returns the last recorded [DelegatedExecutionSignal.Kind.RESULT] signal, or `null` if
     * no RESULT has been recorded yet.
     */
    val lastResult: DelegatedExecutionSignal?
        get() = _result

    /**
     * Returns the last recorded signal for [kind], or `null` if no signal of that kind has
     * been recorded yet.
     *
     * @param kind The [DelegatedExecutionSignal.Kind] to look up.
     */
    fun getForReplay(kind: DelegatedExecutionSignal.Kind): DelegatedExecutionSignal? =
        when (kind) {
            DelegatedExecutionSignal.Kind.ACK      -> _ack
            DelegatedExecutionSignal.Kind.PROGRESS -> _progress
            DelegatedExecutionSignal.Kind.RESULT   -> _result
        }

    // ── Public API — terminal-state bounding ─────────────────────────────────

    /**
     * `true` when a terminal [DelegatedExecutionSignal.Kind.RESULT] signal has been
     * recorded in this ledger, indicating that execution has reached a terminal state.
     *
     * Once `true`, replaying [DelegatedExecutionSignal.Kind.ACK] or
     * [DelegatedExecutionSignal.Kind.PROGRESS] via [replayBounded] is suppressed because
     * those signals belong to pre-terminal execution phases.  Sending pre-terminal signals
     * after a terminal RESULT would produce stale participant influence toward V2.
     *
     * [replaySignal] is unaffected and will still return replays for any kind regardless
     * of terminal state.  Prefer [replayBounded] in recovery contexts where terminal-state
     * safety is required.
     */
    val hasTerminalResult: Boolean
        get() = _result != null

    // ── Public API — replay ───────────────────────────────────────────────────

    /**
     * Returns a **replay copy** of the last recorded signal for [kind], or `null` if no
     * signal of that kind has been recorded yet.
     *
     * The replay copy preserves the **original [DelegatedExecutionSignal.signalId] and
     * [DelegatedExecutionSignal.emissionSeq]** so the host can identify it as a re-delivery
     * of the same logical emission.  Only [DelegatedExecutionSignal.timestampMs] is
     * updated to [replayTimestampMs] so the host can distinguish the original send time
     * from the re-emission time.
     *
     * This is the **canonical replay path** — callers must use this method (or
     * [DelegatedExecutionSignal.replayAt] on a retrieved signal) rather than calling the
     * factory methods again, which would generate a fresh [signalId] and break
     * host-side idempotency.
     *
     * @param kind              The [DelegatedExecutionSignal.Kind] to replay.
     * @param replayTimestampMs Epoch-ms re-emission timestamp; defaults to the current
     *                          wall clock.
     * @return A replay copy of the last recorded signal of [kind], or `null` if no signal
     *         of that kind has been recorded yet.
     */
    fun replaySignal(
        kind: DelegatedExecutionSignal.Kind,
        replayTimestampMs: Long = System.currentTimeMillis()
    ): DelegatedExecutionSignal? = getForReplay(kind)?.replayAt(replayTimestampMs)

    /**
     * Returns a **bounded replay copy** of the last recorded signal for [kind], applying
     * terminal-state suppression.
     *
     * Differs from [replaySignal] in one key respect: if [hasTerminalResult] is `true`
     * (a terminal [DelegatedExecutionSignal.Kind.RESULT] has been recorded) and [kind] is
     * [DelegatedExecutionSignal.Kind.ACK] or [DelegatedExecutionSignal.Kind.PROGRESS],
     * this method returns `null`.  Replaying pre-terminal signals after execution has
     * already terminated would produce **stale participant influence toward V2** — V2 has
     * already seen the RESULT; receiving ACK or PROGRESS after it would be out-of-order
     * and semantically invalid.
     *
     * For [DelegatedExecutionSignal.Kind.RESULT] replay after terminal state this method
     * behaves identically to [replaySignal] — replaying the terminal RESULT is safe and
     * idempotent (same [DelegatedExecutionSignal.signalId] preserved).
     *
     * ## Recommended usage in recovery contexts
     *
     * Use [replayBounded] instead of [replaySignal] whenever replaying signals during a
     * reconnect or resume sequence:
     * ```kotlin
     * // During reconnect recovery — safe replay with terminal-state bounding:
     * val replayedAck = ledger.replayBounded(Kind.ACK)        // null if terminal
     * val replayedProgress = ledger.replayBounded(Kind.PROGRESS)  // null if terminal
     * val replayedResult = ledger.replayBounded(Kind.RESULT)  // always non-null if recorded
     * ```
     *
     * @param kind              The [DelegatedExecutionSignal.Kind] to replay.
     * @param replayTimestampMs Epoch-ms re-emission timestamp; defaults to the current
     *                          wall clock.
     * @return A bounded replay copy of the last signal of [kind], or `null` if:
     *         - no signal of [kind] has been recorded yet, or
     *         - [hasTerminalResult] is `true` and [kind] is [DelegatedExecutionSignal.Kind.ACK]
     *           or [DelegatedExecutionSignal.Kind.PROGRESS] (stale after terminal state).
     */
    fun replayBounded(
        kind: DelegatedExecutionSignal.Kind,
        replayTimestampMs: Long = System.currentTimeMillis()
    ): DelegatedExecutionSignal? {
        if (hasTerminalResult && kind != DelegatedExecutionSignal.Kind.RESULT) {
            return null
        }
        return replaySignal(kind, replayTimestampMs)
    }
}
