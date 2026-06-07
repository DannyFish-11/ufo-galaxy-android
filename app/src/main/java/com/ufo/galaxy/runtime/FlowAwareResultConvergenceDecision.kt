package com.ufo.galaxy.runtime

/**
 * PR-6 (Android) — Sealed class representing the Android-side flow-aware result
 * convergence decision.
 *
 * [FlowAwareResultConvergenceDecision] is the typed output of
 * [AndroidFlowAwareResultConvergenceParticipant.evaluateResultEmit].  Every result
 * emission point on Android must evaluate a convergence decision before forwarding any
 * partial, final, parallel, or replayed result outward.  Callers must handle all variants
 * and must not emit without inspecting the decision.
 *
 * ## Convergence decision semantics
 *
 * Each concrete subtype corresponds to a named Android-side result convergence semantic:
 *
 * | Subtype                              | Semantic tag                            | Description                                                                |
 * |--------------------------------------|-----------------------------------------|----------------------------------------------------------------------------|
 * | [EmitPartialForFlow]                 | `emit_partial_for_flow`                 | Emit a partial result; the flow is still in progress.                      |
 * | [EmitFinalForFlow]                   | `emit_final_for_flow`                   | Emit a final (terminal) result; the flow is now closed on Android.         |
 * | [BindParallelResultToParentFlow]     | `bind_parallel_result_to_parent_flow`   | Bind a parallel/collaboration sub-result to its parent flow context.       |
 * | [SuppressDuplicateResultEmit]        | `suppress_duplicate_result_emit`        | This result has already been emitted; suppress the duplicate.              |
 * | [SuppressLatePartialAfterFinal]      | `suppress_late_partial_after_final`     | Final result already emitted for this flow; late partial must be dropped.  |
 * | [HoldResultForConvergenceAlignment]  | `hold_result_for_convergence_alignment` | Result must not be emitted until V2 convergence alignment completes.       |
 *
 * ## Android convergence authority boundary
 *
 * Only [EmitFinalForFlow] carries the claim that Android has locally reached a canonical
 * terminal convergence for this flow.  [EmitPartialForFlow] and
 * [BindParallelResultToParentFlow] represent intermediate or collaborative convergence
 * steps that V2 must still reconcile.  Suppression and hold decisions mean the result
 * must not be forwarded at all.
 *
 * @see AndroidFlowAwareResultConvergenceParticipant
 * @see AndroidFlowAwareResultConvergenceParticipant.FlowResultKind
 */
sealed class FlowAwareResultConvergenceDecision {

    /**
     * Stable wire tag that identifies the convergence semantic for this decision.
     *
     * Matches one of the [AndroidFlowAwareResultConvergenceParticipant] `DECISION_*`
     * constants.
     */
    abstract val semanticTag: String

    // ── EmitPartialForFlow ────────────────────────────────────────────────────

    /**
     * Android may emit a partial result for this flow.
     *
     * The flow is still active and no final result has yet been emitted.  The partial
     * result is non-terminal and V2 must treat it as an intermediate convergence artifact.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW].
     *
     * @property flowId     The delegated flow ID for which the partial result is emitted.
     * @property resultKey  The stable idempotency key for this specific partial result
     *                      emission (typically `flowId + partial sequence`).
     */
    data class EmitPartialForFlow(
        val flowId: String,
        val resultKey: String
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_PARTIAL_FOR_FLOW
    }

    // ── EmitFinalForFlow ──────────────────────────────────────────────────────

    /**
     * Android may emit a final (terminal) result for this flow.
     *
     * The flow is reaching its canonical terminal convergence on Android.  After this
     * decision, any further partial emission for the same [flowId] must be suppressed.
     * V2 must treat this as the Android-side canonical result for the flow.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW].
     *
     * @property flowId      The delegated flow ID for which the final result is emitted.
     * @property resultKey   The stable idempotency key for this specific final result
     *                       emission.
     * @property resultKind  The [AndroidFlowAwareResultConvergenceParticipant.FlowResultKind]
     *                       that triggered this final decision (FINAL, FAILURE_TERMINAL,
     *                       CANCEL_TERMINAL, or REPLAYED_RESENT).
     */
    data class EmitFinalForFlow(
        val flowId: String,
        val resultKey: String,
        val resultKind: AndroidFlowAwareResultConvergenceParticipant.FlowResultKind
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_EMIT_FINAL_FOR_FLOW
    }

    // ── BindParallelResultToParentFlow ────────────────────────────────────────

    /**
     * Android must bind this parallel/collaboration sub-result to its parent flow context.
     *
     * The result originates from a parallel subtask or collaboration sub-execution.
     * The [groupId] and [subtaskIndex] must be used to route the result to the correct
     * parent flow aggregation bucket in V2.  The [parentFlowId] anchors the sub-result
     * to the owning parallel group.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW].
     *
     * @property flowId        The delegated flow ID of the subtask emitting this result.
     * @property resultKey     The stable idempotency key for this sub-result emission.
     * @property groupId       The parallel group identifier (from `GoalExecutionPayload.group_id`).
     * @property subtaskIndex  The zero-based subtask index within the parallel group (from
     *                         `GoalExecutionPayload.subtask_index`).
     * @property parentFlowId  The parent flow ID that owns this parallel group.
     */
    data class BindParallelResultToParentFlow(
        val flowId: String,
        val resultKey: String,
        val groupId: String,
        val subtaskIndex: Int,
        val parentFlowId: String
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW
    }

    // ── SuppressDuplicateResultEmit ───────────────────────────────────────────

    /**
     * This result has already been emitted; the current emission must be suppressed.
     *
     * The [resultKey] was previously recorded via
     * [AndroidFlowAwareResultConvergenceParticipant.markResultEmitted].  Re-emitting the
     * same result would violate the idempotent convergence contract and could cause V2 to
     * absorb the same result multiple times.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT].
     *
     * @property flowId     The delegated flow ID.
     * @property resultKey  The stable idempotency key that was already recorded as emitted.
     */
    data class SuppressDuplicateResultEmit(
        val flowId: String,
        val resultKey: String
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT
    }

    // ── SuppressLatePartialAfterFinal ─────────────────────────────────────────

    /**
     * The final result for this flow has already been emitted; this late partial must be
     * dropped.
     *
     * Partial results that arrive after the flow has already reached its canonical final
     * convergence (via [AndroidFlowAwareResultConvergenceParticipant.markFlowFinal])
     * MUST NOT be forwarded, as they could introduce confusion in V2's canonical model
     * after convergence has closed.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL].
     *
     * @property flowId     The delegated flow ID whose final result is already recorded.
     * @property resultKey  The idempotency key of the suppressed late partial.
     */
    data class SuppressLatePartialAfterFinal(
        val flowId: String,
        val resultKey: String
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL
    }

    // ── HoldResultForConvergenceAlignment ─────────────────────────────────────

    /**
     * This result must be held and not emitted until V2 convergence alignment completes.
     *
     * The convergence gate for [flowId] is currently closed
     * ([AndroidFlowAwareResultConvergenceParticipant.isConvergenceGated] returns `true`),
     * indicating that a reconnect, rebind, or resume is in progress and the alignment
     * state with V2 is not yet established.  Android must buffer or discard the result
     * until the gate opens.
     *
     * Android-side semantic:
     * [AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT].
     *
     * @property flowId  The delegated flow ID whose result emission is held.
     * @property reason  Human-readable explanation of why the convergence hold is in effect.
     */
    data class HoldResultForConvergenceAlignment(
        val flowId: String,
        val reason: String
    ) : FlowAwareResultConvergenceDecision() {
        override val semanticTag: String =
            AndroidFlowAwareResultConvergenceParticipant.DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT
    }
}
