package com.ufo.galaxy.agent

import android.util.Log
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.GoalResultPayload
import com.ufo.galaxy.runtime.DelegatedActivationRecord
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.DelegatedExecutionTracker
import com.ufo.galaxy.runtime.SourceRuntimePosture

/**
 * **Canonical Android-side delegated receipt-to-local-takeover executor binding** (PR-12,
 * post-#533 dual-repo runtime unification master plan — Canonical Android-Side
 * Delegated Receipt-to-Local-Takeover Executor Binding, Android side).
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
 *     PENDING → (ack emitted) → ACTIVATING → ACTIVE → COMPLETED / FAILED
 *     ```
 *  4. Emitting a [DelegatedExecutionSignal.Kind.RESULT] signal at the terminal step, with
 *     the correct [DelegatedExecutionSignal.ResultKind] ([COMPLETED][DelegatedExecutionSignal.ResultKind.COMPLETED]
 *     on success or [FAILED][DelegatedExecutionSignal.ResultKind.FAILED] on exception).
 *  5. Preserving identity continuity — `unitId`, `taskId`, `traceId`,
 *     `attachedSessionId`, and `handoffContractVersion` — on every emitted signal so
 *     the main-repo tracker can correlate signals across the entire lifecycle.
 *  6. Returning a typed [ExecutionOutcome] so callers no longer need inline try/catch
 *     logic: [ExecutionOutcome.Completed] carries the [GoalResultPayload] and final
 *     tracker; [ExecutionOutcome.Failed] carries the error message and final tracker.
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
 */
class DelegatedTakeoverExecutor(
    private val pipeline: GoalExecutionPipeline,
    private val signalSink: DelegatedExecutionSignalSink
) {

    // ── Outcome types ─────────────────────────────────────────────────────────

    /**
     * Typed result of a [execute] invocation.
     *
     * Both variants carry the final [DelegatedExecutionTracker] so callers can inspect
     * or log the terminal lifecycle state.
     */
    sealed class ExecutionOutcome {

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
         */
        data class Completed(
            val tracker: DelegatedExecutionTracker,
            val goalResult: GoalResultPayload
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
         */
        data class Failed(
            val tracker: DelegatedExecutionTracker,
            val error: String
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
     * 5. Advances tracker to ACTIVE — the pipeline is about to be entered.
     * 6. Calls [GoalExecutionPipeline.executeGoal].
     *    - **On success**: records a step, advances to COMPLETED, emits RESULT signal
     *      with [DelegatedExecutionSignal.ResultKind.COMPLETED], returns
     *      [ExecutionOutcome.Completed].
     *    - **On exception**: advances to FAILED, emits RESULT signal with
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

        // ── 1. Create tracker from the accepted PENDING record ────────────────
        var tracker = DelegatedExecutionTracker.create(initialRecord)

        // ── 2. Emit ACK signal — host can mark unit as acknowledged ───────────
        signalSink.onSignal(DelegatedExecutionSignal.ack(tracker, nowMs))

        Log.d(
            TAG,
            "[PR12:TAKEOVER] ACK emitted unit_id=${unit.unitId} task_id=${unit.taskId} " +
                "trace_id=${unit.traceId} session_id=${unit.attachedSessionId}"
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

        // ── 6. Run the pipeline and emit the terminal RESULT signal ───────────
        return try {
            val result = pipeline.executeGoal(goalPayload)

            // Record a step for the completed goal execution and advance to COMPLETED.
            tracker = tracker
                .recordStep(nowMs)
                .advance(DelegatedActivationRecord.ActivationStatus.COMPLETED)

            signalSink.onSignal(
                DelegatedExecutionSignal.result(
                    tracker = tracker,
                    resultKind = DelegatedExecutionSignal.ResultKind.COMPLETED
                )
            )

            Log.d(
                TAG,
                "[PR12:TAKEOVER] RESULT(completed) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} steps=${tracker.stepCount} status=${result.status}"
            )

            ExecutionOutcome.Completed(tracker = tracker, goalResult = result)

        } catch (e: Exception) {
            tracker = tracker.advance(DelegatedActivationRecord.ActivationStatus.FAILED)

            signalSink.onSignal(
                DelegatedExecutionSignal.result(
                    tracker = tracker,
                    resultKind = DelegatedExecutionSignal.ResultKind.FAILED
                )
            )

            val errorMessage = e.message ?: "execution_error"
            Log.w(
                TAG,
                "[PR12:TAKEOVER] RESULT(failed) emitted unit_id=${unit.unitId} " +
                    "task_id=${unit.taskId} error=$errorMessage"
            )

            ExecutionOutcome.Failed(tracker = tracker, error = errorMessage)
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "DelegatedTakeoverExec"
    }
}
