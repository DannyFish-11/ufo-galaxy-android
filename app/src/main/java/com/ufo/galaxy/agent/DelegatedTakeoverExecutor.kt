package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionSignalSink
import com.ufo.galaxy.runtime.DelegatedExecutionTracker
import com.ufo.galaxy.runtime.EmittedSignalLedger
import com.ufo.galaxy.runtime.PersistentEmittedSignalLedgerStore
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * **Canonical Android-side delegated receipt-to-local-takeover executor binding** (PR-12
 * / PR-13 / PR-15, post-#533 dual-repo runtime unification master plan — Canonical
 * Android-Side Delegated Execution Signal Emission Path and Recovery-Readiness
 * Foundations, Android side).
 *
 * [DelegatedTakeoverExecutor] is the single authoritative component that bridges an
 * accepted delegated receipt — a [DelegatedRuntimeUnit] and its initial
 * [DelegatedActivationRecord] — into the real local execution / takeover pipeline,
 * while maintaining canonical lifecycle continuity through [DelegatedExecutionTracker]
 * and emitting [DelegatedExecutionSignal] events at each lifecycle milestone.
 *
 * ## Design intent
 *
 * Before PR-12 the Android-side models for receipt, activation, and execution-tracking
 * existed as isolated layers: [DelegatedRuntimeReceiver] produced a
 * [DelegatedActivationRecord]; [DelegatedExecutionTracker] and [DelegatedExecutionSignal]
 * were well-defined models but were never instantiated or emitted in the actual execution
 * path; and [com.ufo.galaxy.service.GalaxyConnectionService.handleTakeoverRequest] managed
 * execution ad-hoc with direct inline state mutations.
 *
 * [DelegatedTakeoverExecutor] closes this gap by:
 *
 *  1. Creating a [DelegatedExecutionTracker] from the initial [DelegatedActivationRecord]
 *     (PENDING) so there is a single, stable, immutable-chain representation of the
 *     execution state from receipt to terminal result.
 *  2. Emitting a [DelegatedExecutionSignal.Kind.ACK] signal immediately after tracker
 *     creation so the main-repo host can distinguish "Android acknowledged the unit"
 *     from "Android is executing" and "Android has a final result".
 *  3. Advancing the tracker through the full canonical lifecycle:
 *     ```
 *     PENDING → (ack emitted) → ACTIVATING → ACTIVE → (progress emitted) → COMPLETED / FAILED
 *     ```
 *  4. Emitting a [DelegatedExecutionSignal.Kind.PROGRESS] signal when execution enters the
 *     ACTIVE state (before the pipeline is called).  This gives the main-repo host at least
 *     one mid-execution update confirming that Android is actively running the delegated unit.
 *  5. Emitting a [DelegatedExecutionSignal.Kind.RESULT] signal at the terminal step, with
 *     the correct [DelegatedExecutionSignal.ResultKind]:
 *     - [DelegatedExecutionSignal.ResultKind.COMPLETED] on success.
 *     - [DelegatedExecutionSignal.ResultKind.TIMEOUT] when a
 *       [kotlinx.coroutines.TimeoutCancellationException] is thrown by the pipeline.
 *     - [DelegatedExecutionSignal.ResultKind.CANCELLED] when a
 *       [kotlinx.coroutines.CancellationException] (non-timeout) is thrown by the pipeline.
 *     - [DelegatedExecutionSignal.ResultKind.FAILED] for all other exceptions.
 *  6. Preserving identity continuity — `unitId`, `taskId`, `traceId`,
 *     `attachedSessionId`, and `handoffContractVersion` — on every emitted signal so
 *     the main-repo tracker can correlate signals across the entire lifecycle.
 *  7. Attaching recovery-readiness metadata (PR-15): every emitted signal now carries a
 *     [DelegatedExecutionSignal.signalId] (UUID idempotency key) and an
 *     [DelegatedExecutionSignal.emissionSeq] (monotonic sequence position
 *     ACK=[DelegatedExecutionSignal.EMISSION_SEQ_ACK],
 *     PROGRESS=[DelegatedExecutionSignal.EMISSION_SEQ_PROGRESS],
 *     RESULT=[DelegatedExecutionSignal.EMISSION_SEQ_RESULT]) so the host can detect
 *     duplicate deliveries and out-of-order events without Android implementing full
 *     persistence or resend queueing.
 *  8. Returning a typed [ExecutionOutcome] so callers no longer need inline try/catch
 *     logic: [ExecutionOutcome.Completed] carries the [GoalResultPayload] and final
 *     tracker; [ExecutionOutcome.Failed] carries the error message and final tracker.
 *  9. Building and populating a per-execution [EmittedSignalLedger] (PR-18): every
 *     signal emitted via [signalSink] is also recorded in a ledger that may be persisted
 *     through [PersistentEmittedSignalLedgerStore] and is returned in every
 *     [ExecutionOutcome].  Callers that need to replay a signal (e.g. after a send failure,
 *     transport reconnect, or process recreation) must use
 *     [EmittedSignalLedger.replaySignal] or [DelegatedExecutionSignal.replayAt] rather
 *     than calling the factory methods again — preserving the original [signalId] and
 *     [emissionSeq] so the host can identify the re-delivery as a duplicate.
 *
 * ## Relationship to surrounding components
 *
 * ```
 * DelegatedRuntimeReceiver  ─── receipt accepted
 *        │
 *        │  DelegatedRuntimeUnit + DelegatedActivationRecord(PENDING)
 *        ▼
 * DelegatedTakeoverExecutor ─── manages lifecycle + emits signals
 *        │                               │
 *        │ GoalExecutionPipeline         │ DelegatedExecutionSignalSink
 *        ▼                               ▼
 * AutonomousExecutionPipeline       structured ACK / RESULT signals
 *        │
 *        ▼
 * GoalResultPayload ──▶ ExecutionOutcome (returned to GalaxyConnectionService)
 * ```
 *
 * ## Testability
 *
 * Both [GoalExecutionPipeline] and [DelegatedExecutionSignalSink] are functional
 * interfaces and can be supplied as lambdas in unit tests without an Android framework
 * or a real WebSocket connection.
 *
 * @param pipeline   The local goal-execution pipeline; in production this delegates to
 *                   [AutonomousExecutionPipeline.handleGoalExecution].
 * @param signalSink Receives [DelegatedExecutionSignal] events emitted during the
 *                   execution lifecycle (ACK and RESULT); in production this is wired to
 *                   structured logging and future outbound signal transmission.
 * @param emittedSignalLedgerStore Optional durable store for per-execution signal ledgers.
 */
class DelegatedTakeoverExecutor(
    private val pipeline: GoalExecutionPipeline,
    private val signalSink: DelegatedExecutionSignalSink,
    private val emittedSignalLedgerStore: PersistentEmittedSignalLedgerStore? = null
) {

    // ── Outcome types ─────────────────────────────────────────────────────────

    /**
     * Typed result of a [execute] invocation.
     *
     * Both variants carry the final [DelegatedExecutionTracker] so callers can inspect
     * or log the terminal lifecycle state.  Both variants also carry the [EmittedSignalLedger]
     * populated during the execution so callers can replay any of the three canonical
     * signals — ACK, PROGRESS, RESULT — with stable identity (same [DelegatedExecutionSignal.signalId]
     * and [DelegatedExecutionSignal.emissionSeq]) via [EmittedSignalLedger.replaySignal].
     */
    sealed class ExecutionOutcome {

        /**
         * The per-execution [EmittedSignalLedger] populated during [execute].
         *
         * Contains the last recorded signal for each [DelegatedExecutionSignal.Kind] emitted
         * during this execution.  Use [EmittedSignalLedger.replaySignal] to re-emit any signal
         * with its original [DelegatedExecutionSignal.signalId] and [DelegatedExecutionSignal.emissionSeq]
         * preserved.
         */
        abstract val ledger: EmittedSignalLedger

        /**
         * The execution pipeline completed without throwing an exception.
         *
         * [tracker] is in [DelegatedActivationRecord.ActivationStatus.COMPLETED] and a
         * [DelegatedExecutionSignal.Kind.RESULT] signal with
         * [DelegatedExecutionSignal.ResultKind.COMPLETED] was emitted before this outcome
         * is returned.
         *
         * @property tracker    Final [DelegatedExecutionTracker] in COMPLETED state.
         * @property goalResult The [GoalResultPayload] returned by the pipeline.
         * @property ledger     The [EmittedSignalLedger] populated during this execution
         *                      (ACK + PROGRESS + RESULT recorded); use it to replay any
         *                      signal with stable identity.
         */
        data class Completed(
            val tracker: DelegatedExecutionTracker,
            val goalResult: GoalResultPayload,
            override val ledger: EmittedSignalLedger
        ) : ExecutionOutcome()

        /**
         * The execution pipeline threw an exception during goal execution.
         *
         * [tracker] is in [DelegatedActivationRecord.ActivationStatus.FAILED] and a
         * [DelegatedExecutionSignal.Kind.RESULT] signal with
         * [DelegatedExecutionSignal.ResultKind.FAILED] was emitted before this outcome
         * is returned.
         *
         * @property tracker  Final [DelegatedExecutionTracker] in FAILED state.
         * @property error    Human-readable error description; echoes [Throwable.message].
         * @property ledger   The [EmittedSignalLedger] populated during this execution
         *                    (ACK + PROGRESS + RESULT recorded); use it to replay any
         *                    signal with stable identity.
         */
        data class Failed(
            val tracker: DelegatedExecutionTracker,
            val error: String,
            override val ledger: EmittedSignalLedger
        ) : ExecutionOutcome()
    }

    // ── Core execution binding ────────────────────────────────────────────────

    /**
     * Executes the delegated unit through the local takeover pipeline with canonical
     * lifecycle management and signal emission.
     *
     * ## Lifecycle sequence
     *
     * 1. Creates [DelegatedExecutionTracker] from [initialRecord] (must be in PENDING).
     * 2. Emits [DelegatedExecutionSignal.Kind.ACK] via [signalSink].
     * 3. Advances tracker: marks execution started + transitions to ACTIVATING.
     * 4. Builds [GoalExecutionPayload] from [unit], using
     *    [SourceRuntimePosture.JOIN_RUNTIME] (delegated work is always executed as a
     *    joined runtime participant on Android).
     * 5. Advances tracker to ACTIVE — emits [DelegatedExecutionSignal.Kind.PROGRESS] to
     *    inform the host that the pipeline is about to be entered.
     * 6. Calls [GoalExecutionPipeline.executeGoal].
     *    - **On success**: records a step, advances to COMPLETED, emits RESULT signal
     *      with [DelegatedExecutionSignal.ResultKind.COMPLETED], returns
     *      [ExecutionOutcome.Completed].
     *    - **On [TimeoutCancellationException]**: advances to FAILED, emits RESULT signal
     *      with [DelegatedExecutionSignal.ResultKind.TIMEOUT], returns
     *      [ExecutionOutcome.Failed].
     *    - **On [CancellationException]**: advances to FAILED, emits RESULT signal
     *      with [DelegatedExecutionSignal.ResultKind.CANCELLED], returns
     *      [ExecutionOutcome.Failed].
     *    - **On other [Exception]**: advances to FAILED, emits RESULT signal with
     *      [DelegatedExecutionSignal.ResultKind.FAILED], returns [ExecutionOutcome.Failed].
     *
     * ## Identity continuity
     *
     * Every emitted signal carries [DelegatedRuntimeUnit.unitId],
     * [DelegatedRuntimeUnit.taskId], [DelegatedRuntimeUnit.traceId],
     * [DelegatedActivationRecord.attachedSessionId], and
     * [DelegatedExecutionTracker.handoffContractVersion] — the full set of correlation
     * keys required by the main-repo tracker to associate signals with its own dispatch
     * record.
     *
     * @param unit          The accepted [DelegatedRuntimeUnit] produced by
     *                      [DelegatedRuntimeReceiver.receive].
     * @param initialRecord The initial [DelegatedActivationRecord] in
     *                      [DelegatedActivationRecord.ActivationStatus.PENDING] produced
     *                      by [DelegatedRuntimeReceiver.receive].
     * @param nowMs         Epoch-ms reference time used for tracker timestamps; defaults
     *                      to the current wall clock.  Pass an explicit value in tests to
     *                      produce deterministic timestamps.
     * @return [ExecutionOutcome.Completed] on successful execution or
     *         [ExecutionOutcome.Failed] when the pipeline throws.
     */
    fun execute(
        unit: DelegatedRuntimeUnit,
        initialRecord: DelegatedActivationRecord,
        nowMs: Long = System.currentTimeMillis()
    ): ExecutionOutcome {

        // ── 0. Create per-execution emitted-signal ledger (PR-18) ─────────────
        // Every signal emitted below is recorded in this ledger so that callers
        // can later replay any of the three canonical signals — ACK, PROGRESS,
        // RESULT — with stable identity (same signalId + emissionSeq) rather than
        // calling the factory methods again and generating a fresh signalId.
        val ledger = EmittedSignalLedger()
        val ledgerExecutionKey = PersistentEmittedSignalLedgerStore.executionKeyFor(
            unitId = unit.unitId,
            taskId = unit.taskId,
            traceId = unit.traceId
        )

        // ── 1. Create tracker from the accepted PENDING record ────────────────
        var tracker = DelegatedExecutionTracker.create(initialRecord)

        // ── 2. Emit ACK signal — host can mark unit as acknowledged ───────────
        val ackSignal = DelegatedExecutionSignal.ack(tracker, nowMs)
        recordEmitted(ledgerExecutionKey, ledger, ackSignal)
        signalSink.onSignal(ackSignal)

        Log.d(
            TAG,
            "[PR15:TAKEOVER] ACK emitted unit_id=${unit.unitId} task_id=${unit.taskId} " +
                "trace_id=${unit.traceId} session_id=${unit.attachedSessionId} " +
                "signal_id=${ackSignal.signalId} emission_seq=${ackSignal.emissionSeq}"
        )

        // ── 3. Mark execution started; advance to ACTIVATING ──────────────────
        tracker = tracker
            .markExecutionStarted(nowMs)
            .advance(DelegatedActivationRecord.ActivationStatus.ACTIVATING)

        // ── 4. Build GoalExecutionPayload from the delegated unit ─────────────
        // Takeover units always execute with JOIN_RUNTIME posture — they are explicitly
        // directed at this device as an active runtime host.  Constraints from the
        // originating envelope are forwarded verbatim so the pipeline can honour them.
        val goalPayload = GoalExecutionPayload(
            task_id = unit.taskId,
            goal = unit.goal,
            constraints = unit.constraints,
            source_runtime_posture = SourceRuntimePosture.JOIN_RUNTIME
        )

        // ── 5. Advance to ACTIVE — pipeline is about to begin ─────────────────
        tracker = tracker.advance(DelegatedActivationRecord.ActivationStatus.ACTIVE)

        // ── 5b. Emit PROGRESS signal — host can record that execution is ACTIVE ─
        // This gives the main-repo host at least one mid-execution update confirming
        // that Android is actively running steps.  Additional PROGRESS signals may
        // be emitted per step in future pipeline integrations; this canonical emission
        // at ACTIVE is guaranteed by the executor regardless of pipeline implementation.
        val progressSignal = DelegatedExecutionSignal.progress(tracker, nowMs)
        recordEmitted(ledgerExecutionKey, ledger, progressSignal)
        signalSink.onSignal(progressSignal)

        Log.d(
            TAG,
            "[PR15:TAKEOVER] PROGRESS emitted unit_id=${unit.unitId} task_id=${unit.taskId} " +
                "trace_id=${unit.traceId} steps=${tracker.stepCount} status=active " +
                "signal_id=${progressSignal.signalId} emission_seq=${progressSignal.emissionSeq}"
        )

        // ── 6. Run the pipeline and emit the terminal RESULT signal ───────────
        return try {
            val result = pipeline.executeGoal(goalPayload)

            // Record a step for the completed goal execution and advance to COMPLETED.
            tracker = tracker
                .recordStep(nowMs)
                .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)

            val resultSignal = DelegatedExecutionSignal.result(
                tracker = tracker,
                resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED
            )
            recordEmitted(ledgerExecutionKey, ledger, resultSignal)
            signalSink.onSignal(resultSignal)

            Log.d(
                TAG,
                "[PR15:TAKEOVER] RESULT(completed) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} steps=${tracker.stepCount} status=${result.status} " +
                    "signal_id=${resultSignal.signalId} emission_seq=${resultSignal.emissionSeq}"
            )

            ExecutionOutcome.Completed(tracker = tracker, goalResult = result, ledger = ledger)

        } catch (e: TimeoutCancellationException) {
            // Execution timed out — emit RESULT(TIMEOUT) so the host knows this was a
            // wall-clock timeout and not a generic failure.
            tracker = tracker.advance(DelegatedActivationRecord.ActivationStatus.FAILED)

            val timeoutSignal = DelegatedExecutionSignal.timeout(tracker = tracker)
            recordEmitted(ledgerExecutionKey, ledger, timeoutSignal)
            signalSink.onSignal(timeoutSignal)

            val errorMessage = e.message ?: "execution_timeout"
            Log.w(
                TAG,
                "[PR15:TAKEOVER] RESULT(timeout) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} error=$errorMessage " +
                    "signal_id=${timeoutSignal.signalId} emission_seq=${timeoutSignal.emissionSeq}"
            )

            ExecutionOutcome.Failed(tracker = tracker, error = errorMessage, ledger = ledger)

        } catch (e: CancellationException) {
            // Execution was cancelled (non-timeout) — emit RESULT(CANCELLED) so the host
            // can distinguish deliberate cancellation from a general failure.
            tracker = tracker.advance(DelegatedActivationRecord.ActivationStatus.FAILED)

            val cancelledSignal = DelegatedExecutionSignal.cancelled(tracker = tracker)
            recordEmitted(ledgerExecutionKey, ledger, cancelledSignal)
            signalSink.onSignal(cancelledSignal)

            val errorMessage = e.message ?: "execution_cancelled"
            Log.w(
                TAG,
                "[PR15:TAKEOVER] RESULT(cancelled) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} error=$errorMessage " +
                    "signal_id=${cancelledSignal.signalId} emission_seq=${cancelledSignal.emissionSeq}"
            )

            ExecutionOutcome.Failed(tracker = tracker, error = errorMessage, ledger = ledger)

        } catch (e: Exception) {
            tracker = tracker.advance(DelegatedActivationRecord.ActivationStatus.FAILED)

            val failedSignal = DelegatedExecutionSignal.result(
                tracker = tracker,
                resultKind = DelegatedExecutionSignal.ResultKind.FAILED
            )
            recordEmitted(ledgerExecutionKey, ledger, failedSignal)
            signalSink.onSignal(failedSignal)

            val errorMessage = e.message ?: "execution_error"
            Log.w(
                TAG,
                "[PR15:TAKEOVER] RESULT(failed) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} error=$errorMessage " +
                    "signal_id=${failedSignal.signalId} emission_seq=${failedSignal.emissionSeq}"
            )

            ExecutionOutcome.Failed(tracker = tracker, error = errorMessage, ledger = ledger)
        }
    }

    private fun recordEmitted(
        executionKey: String,
        ledger: EmittedSignalLedger,
        signal: DelegatedExecutionSignal
    ) {
        ledger.recordEmitted(signal)
        emittedSignalLedgerStore?.save(executionKey, ledger)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "DelegatedTakeoverExec"
    }
}
