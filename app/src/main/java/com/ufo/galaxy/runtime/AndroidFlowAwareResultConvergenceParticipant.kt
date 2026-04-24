package com.ufo.galaxy.runtime

/**
 * PR-6 (Android) — Android flow-aware result convergence participation owner / coordinator
 * / policy layer.
 *
 * [AndroidFlowAwareResultConvergenceParticipant] is the unified Android-side entry point
 * for result classification, flow binding, emit gating, duplicate suppression, and V2
 * canonical convergence participation.  It extends the truth ownership semantics
 * established by [AndroidLocalTruthOwnershipCoordinator] (PR-5) into a full
 * **flow-aware result convergence layer** that answers the following questions for every
 * outbound result:
 *
 *  - Which [FlowResultKind] does this result belong to?
 *  - What [FlowAwareResultConvergenceDecision] should govern how it is forwarded?
 *  - Should emission be suppressed because the result key has already been emitted?
 *  - Should emission be suppressed because this is a late partial after a final?
 *  - Should emission be held because a reconnect / rebind / resume is in progress?
 *  - Should the result be bound to a parent flow via `group_id` / `subtask_index`?
 *
 * ## Background and motivation
 *
 * Before PR-6, Android result emission was distributed across multiple modules
 * ([DelegatedTakeoverExecutor], [DelegatedRuntimeReceiver], [LocalCollaborationAgent],
 * [AutonomousExecutionPipeline], etc.) with no unified convergence participation layer.
 * This meant:
 *
 *  - partial and final results could flow out without a coherent priority model.
 *  - after a final result, late partial results were not suppressed.
 *  - parallel / collaboration sub-results were not explicitly bound to parent flows.
 *  - reconnect / rebind / resume scenarios had no unified result emit gate.
 *  - duplicate result emissions (replay, resend) had no idempotent suppression entry point.
 *  - V2 canonical convergence received results from multiple uncoordinated code paths.
 *
 * [AndroidFlowAwareResultConvergenceParticipant] closes these gaps by providing a
 * composable, testable API that every result-emitting code path can query before
 * forwarding, establishing a stable device-side result artifact that V2 canonical
 * convergence can reliably consume.
 *
 * ## Flow result classification model
 *
 * Five result kinds are defined ([FlowResultKind]):
 *
 * | Kind                    | Description                                                               |
 * |-------------------------|---------------------------------------------------------------------------|
 * | [FlowResultKind.PARTIAL]            | Intermediate / partial result; flow is still active.         |
 * | [FlowResultKind.FINAL]              | Canonical final result; flow has reached its terminal state. |
 * | [FlowResultKind.FAILURE_TERMINAL]   | Terminal failure result (error, timeout).                    |
 * | [FlowResultKind.CANCEL_TERMINAL]    | Terminal cancel/abort result.                                |
 * | [FlowResultKind.PARALLEL_SUB_RESULT]| Sub-result from a parallel/collaboration subtask.            |
 * | [FlowResultKind.REPLAYED_RESENT]    | Replayed or resent result after reconnect/rebind.            |
 *
 * ## Convergence decision model
 *
 * Six convergence decisions are layered in the [evaluateResultEmit] decision path:
 *
 * 1. **Duplicate suppression** — if the [ResultEmitContext.resultKey] has already been
 *    recorded via [markResultEmitted], the result is suppressed as
 *    [FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit].
 * 2. **Late partial suppression** — if the flow is already final
 *    ([isFlowFinal] returns `true`) and the result kind is [FlowResultKind.PARTIAL],
 *    the result is suppressed as
 *    [FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal].
 * 3. **Convergence alignment hold** — if the convergence gate for the flow is closed
 *    ([isConvergenceGated] returns `true`), the result is held as
 *    [FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment].
 * 4. **Parallel result binding** — if the result kind is
 *    [FlowResultKind.PARALLEL_SUB_RESULT] and all required binding fields are present,
 *    the result is classified as
 *    [FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow].
 * 5. **Partial emit** — if the result kind is [FlowResultKind.PARTIAL], the result is
 *    classified as [FlowAwareResultConvergenceDecision.EmitPartialForFlow].
 * 6. **Final emit** — all remaining result kinds (FINAL, FAILURE_TERMINAL,
 *    CANCEL_TERMINAL, REPLAYED_RESENT) are classified as
 *    [FlowAwareResultConvergenceDecision.EmitFinalForFlow].
 *
 * ## Integration points
 *
 * [AndroidFlowAwareResultConvergenceParticipant] establishes clear integration boundaries
 * with the following existing modules:
 *
 * | Integration point constant           | Module                                                              | Role                                                                    |
 * |--------------------------------------|---------------------------------------------------------------------|-------------------------------------------------------------------------|
 * | [INTEGRATION_RECEIVER]               | [com.ufo.galaxy.agent.DelegatedRuntimeReceiver]                     | Receipt acceptance is the anchor for `flowId`; convergence gating starts at receipt. |
 * | [INTEGRATION_UNIT]                   | [com.ufo.galaxy.agent.DelegatedRuntimeUnit]                         | Unit identity (`unitId` / `flowId`) binds all results to a stable convergence context. |
 * | [INTEGRATION_ACTIVATION_RECORD]      | [com.ufo.galaxy.runtime.DelegatedActivationRecord]                  | Activation record terminal status drives [markFlowFinal] calls.         |
 * | [INTEGRATION_PIPELINE]               | [com.ufo.galaxy.agent.AutonomousExecutionPipeline]                  | Pipeline result output must be classified via [evaluateResultEmit].      |
 * | [INTEGRATION_LOOP_CONTROLLER]        | [com.ufo.galaxy.loop.LoopController]                                | Loop partial results (step progress, intermediate goal states) classified as PARTIAL. |
 * | [INTEGRATION_TAKEOVER_EXECUTOR]      | [com.ufo.galaxy.agent.DelegatedTakeoverExecutor]                    | RESULT signals (completed / failed / cancelled) classified as FINAL or terminal. |
 * | [INTEGRATION_COLLABORATION_AGENT]    | [com.ufo.galaxy.agent.LocalCollaborationAgent]                      | Subtask results classified as PARALLEL_SUB_RESULT and bound to parent flow. |
 *
 * ## Convergence decision semantics
 *
 * The following convergence decision semantics are established by this class:
 *
 * | Decision constant                                     | Semantic                                              |
 * |-------------------------------------------------------|-------------------------------------------------------|
 * | [DECISION_EMIT_PARTIAL_FOR_FLOW]                      | `emit_partial_for_flow`                               |
 * | [DECISION_EMIT_FINAL_FOR_FLOW]                        | `emit_final_for_flow`                                 |
 * | [DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW]        | `bind_parallel_result_to_parent_flow`                 |
 * | [DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT]             | `suppress_duplicate_result_emit`                      |
 * | [DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL]          | `suppress_late_partial_after_final`                   |
 * | [DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT]      | `hold_result_for_convergence_alignment`               |
 *
 * ## Core API usage pattern
 *
 * ```kotlin
 * val participant = AndroidFlowAwareResultConvergenceParticipant()
 *
 * // Close the convergence gate during reconnect
 * participant.closeConvergenceGate(flowId)
 *
 * // Evaluate what to do with an outbound result
 * val decision = participant.evaluateResultEmit(ResultEmitContext(
 *     flowId = flowId,
 *     resultKind = FlowResultKind.PARTIAL,
 *     resultKey = "$flowId:partial:1"
 * ))
 *
 * when (decision) {
 *     is FlowAwareResultConvergenceDecision.EmitPartialForFlow -> { /* forward partial */ }
 *     is FlowAwareResultConvergenceDecision.EmitFinalForFlow -> {
 *         participant.markFlowFinal(decision.flowId, decision.resultKey)
 *         participant.markResultEmitted(decision.resultKey)
 *         /* forward final */
 *     }
 *     is FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow -> {
 *         /* bind sub-result to parent and forward */
 *     }
 *     is FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit -> { /* drop */ }
 *     is FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal -> { /* drop */ }
 *     is FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment -> { /* buffer */ }
 * }
 * ```
 *
 * ## Thread safety
 *
 * [markFlowFinal], [clearFlowFinal], [isFlowFinal], [finalizedFlowCount],
 * [clearAllFinalizedFlows], [markResultEmitted], [isResultEmitted],
 * [emittedResultCount], [clearAllEmittedResults], [closeConvergenceGate],
 * [openConvergenceGate], [isConvergenceGated], and [convergenceGatedFlowCount] all use
 * [synchronized] blocks for safe cross-thread access.  [evaluateResultEmit] and
 * [classifyResultKind] are pure functions with no shared mutable state, relying only on
 * the synchronized accessors above.
 *
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see FlowAwareResultConvergenceDecision
 * @see AndroidRecoveryParticipationOwner
 */
class AndroidFlowAwareResultConvergenceParticipant {

    // ── FlowResultKind enumeration ────────────────────────────────────────────

    /**
     * Enumeration of the six local flow result kinds Android recognises.
     *
     * Each kind governs which [FlowAwareResultConvergenceDecision] is returned by
     * [evaluateResultEmit] in the absence of suppression conditions.
     *
     * | Kind                    | Default convergence decision                                        |
     * |-------------------------|---------------------------------------------------------------------|
     * | [PARTIAL]               | [FlowAwareResultConvergenceDecision.EmitPartialForFlow]             |
     * | [FINAL]                 | [FlowAwareResultConvergenceDecision.EmitFinalForFlow]               |
     * | [FAILURE_TERMINAL]      | [FlowAwareResultConvergenceDecision.EmitFinalForFlow]               |
     * | [CANCEL_TERMINAL]       | [FlowAwareResultConvergenceDecision.EmitFinalForFlow]               |
     * | [PARALLEL_SUB_RESULT]   | [FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow] |
     * | [REPLAYED_RESENT]       | [FlowAwareResultConvergenceDecision.EmitFinalForFlow]               |
     */
    enum class FlowResultKind(val wireValue: String) {

        /**
         * Intermediate / partial result.  The flow is still active and has not yet
         * reached a terminal convergence.  V2 must treat this as a non-final input to
         * the canonical result for this flow.
         */
        PARTIAL("partial"),

        /**
         * Canonical final result.  The flow has successfully reached its terminal
         * convergence on Android.  V2 must treat this as the closing Android-side result
         * artifact for this flow.
         */
        FINAL("final"),

        /**
         * Terminal failure result.  The flow has terminated with an error or timeout.
         * V2 must close the canonical result for this flow as a failure.
         */
        FAILURE_TERMINAL("failure_terminal"),

        /**
         * Terminal cancel result.  The flow was cancelled or aborted before reaching a
         * successful completion.  V2 must close the canonical result for this flow as
         * cancelled.
         */
        CANCEL_TERMINAL("cancel_terminal"),

        /**
         * Sub-result from a parallel or collaboration subtask.  This result originates
         * from one execution arm of a V2-orchestrated parallel group and must be bound
         * to the parent flow via `group_id` and `subtask_index`.
         */
        PARALLEL_SUB_RESULT("parallel_sub_result"),

        /**
         * Replayed or resent result after reconnect, process recreation, or receiver
         * rebind.  The result key is the same as the original emission, so V2 must apply
         * idempotent absorption and must not double-count the result.
         */
        REPLAYED_RESENT("replayed_resent")
    }

    // ── ResultEmitContext input model ─────────────────────────────────────────

    /**
     * Input context for [evaluateResultEmit].
     *
     * @property flowId        The delegated flow ID whose result is being evaluated.
     * @property resultKind    The [FlowResultKind] classification for this result.
     * @property resultKey     Stable idempotency key for this specific result emission.
     *                         Should be globally unique per logical result event (e.g.
     *                         `"$flowId:final:$traceId"`).  Required for duplicate
     *                         suppression.
     * @property groupId       Parallel group identifier (from `GoalExecutionPayload.group_id`).
     *                         Required only for [FlowResultKind.PARALLEL_SUB_RESULT]; may be
     *                         `null` for other kinds.
     * @property subtaskIndex  Zero-based subtask index within the parallel group (from
     *                         `GoalExecutionPayload.subtask_index`).  Required only for
     *                         [FlowResultKind.PARALLEL_SUB_RESULT]; may be `null` for other
     *                         kinds.
     * @property parentFlowId  The parent flow ID that owns the parallel group.  Required
     *                         only for [FlowResultKind.PARALLEL_SUB_RESULT]; may be `null`
     *                         for other kinds.
     * @property reason        Optional human-readable description of why this result is
     *                         being emitted; used in [FlowAwareResultConvergenceDecision]
     *                         annotations.
     */
    data class ResultEmitContext(
        val flowId: String,
        val resultKind: FlowResultKind,
        val resultKey: String,
        val groupId: String? = null,
        val subtaskIndex: Int? = null,
        val parentFlowId: String? = null,
        val reason: String = ""
    )

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Registry of flow IDs that have reached a local final convergence state.
     *
     * Map key is [flowId]; value is the [resultKey] of the final emission that closed
     * the flow.  Added by [markFlowFinal]; removed by [clearFlowFinal] or
     * [clearAllFinalizedFlows].
     */
    private val _finalizedFlows: MutableMap<String, String> = mutableMapOf()

    /**
     * Registry of result keys that have already been emitted.
     *
     * This set is the idempotent suppression store.  A result key is added by
     * [markResultEmitted]; all keys are cleared by [clearAllEmittedResults].
     */
    private val _emittedResultKeys: MutableSet<String> = mutableSetOf()

    /**
     * Set of flow IDs for which the convergence emit gate is currently closed.
     *
     * A gated flow must not emit any result until [openConvergenceGate] is called.
     * Typically closed during reconnect / rebind / resume sequences to prevent
     * out-of-order result emission before V2 alignment is restored.
     */
    private val _convergenceGatedFlows: MutableSet<String> = mutableSetOf()

    // ── Core API — result kind classification ─────────────────────────────────

    /**
     * Classifies a result kind string into a [FlowResultKind].
     *
     * Maps canonical result kind strings to the appropriate [FlowResultKind] using the
     * standard kind-to-class mapping defined by this participant.
     *
     * Unknown kind strings default to [FlowResultKind.PARTIAL] as the most conservative
     * classification (non-terminal, non-binding).
     *
     * @param kind  The result kind string to classify.  Should be one of the canonical
     *              [FlowResultKind.wireValue] strings.
     * @return The [FlowResultKind] for the given kind string.
     */
    fun classifyResultKind(kind: String): FlowResultKind = when (kind) {
        KIND_PARTIAL          -> FlowResultKind.PARTIAL
        KIND_FINAL            -> FlowResultKind.FINAL
        KIND_FAILURE_TERMINAL -> FlowResultKind.FAILURE_TERMINAL
        KIND_CANCEL_TERMINAL  -> FlowResultKind.CANCEL_TERMINAL
        KIND_PARALLEL_SUB_RESULT -> FlowResultKind.PARALLEL_SUB_RESULT
        KIND_REPLAYED_RESENT  -> FlowResultKind.REPLAYED_RESENT
        else                  -> FlowResultKind.PARTIAL
    }

    // ── Core API — convergence decision ───────────────────────────────────────

    /**
     * Evaluates whether and how a result emission should proceed for the given [context].
     *
     * ## Decision logic
     *
     * 1. **Duplicate suppression** — if [isResultEmitted] is `true` for
     *    [context.resultKey][ResultEmitContext.resultKey] →
     *    [FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit].
     *
     * 2. **Late partial suppression** — if [isFlowFinal] is `true` for
     *    [context.flowId][ResultEmitContext.flowId] and
     *    [context.resultKind][ResultEmitContext.resultKind] is [FlowResultKind.PARTIAL] →
     *    [FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal].
     *
     * 3. **Convergence alignment hold** — if [isConvergenceGated] is `true` for
     *    [context.flowId][ResultEmitContext.flowId] →
     *    [FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment].
     *
     * 4. **Parallel result binding** — if [context.resultKind][ResultEmitContext.resultKind]
     *    is [FlowResultKind.PARALLEL_SUB_RESULT] and [context.groupId],
     *    [context.subtaskIndex], and [context.parentFlowId] are all non-null →
     *    [FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow].
     *
     * 5. **Partial emit** — if [context.resultKind][ResultEmitContext.resultKind] is
     *    [FlowResultKind.PARTIAL] →
     *    [FlowAwareResultConvergenceDecision.EmitPartialForFlow].
     *
     * 6. **Final emit** — all remaining result kinds (FINAL, FAILURE_TERMINAL,
     *    CANCEL_TERMINAL, REPLAYED_RESENT, PARALLEL_SUB_RESULT without complete binding
     *    fields) →
     *    [FlowAwareResultConvergenceDecision.EmitFinalForFlow].
     *
     * @param context The [ResultEmitContext] describing the result and its surrounding
     *                conditions.
     * @return The [FlowAwareResultConvergenceDecision] classifying the required convergence
     *         action.
     */
    fun evaluateResultEmit(context: ResultEmitContext): FlowAwareResultConvergenceDecision {

        // ── 1. Duplicate suppression ───────────────────────────────────────────
        if (isResultEmitted(context.resultKey)) {
            return FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit(
                flowId = context.flowId,
                resultKey = context.resultKey
            )
        }

        // ── 2. Late partial suppression ────────────────────────────────────────
        if (context.resultKind == FlowResultKind.PARTIAL && isFlowFinal(context.flowId)) {
            return FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal(
                flowId = context.flowId,
                resultKey = context.resultKey
            )
        }

        // ── 3. Convergence alignment hold ──────────────────────────────────────
        if (isConvergenceGated(context.flowId)) {
            return FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment(
                flowId = context.flowId,
                reason = REASON_CONVERGENCE_GATE_CLOSED_PENDING_ALIGNMENT
            )
        }

        // ── 4. Parallel result binding ─────────────────────────────────────────
        if (context.resultKind == FlowResultKind.PARALLEL_SUB_RESULT) {
            val groupId = context.groupId
            val subtaskIndex = context.subtaskIndex
            val parentFlowId = context.parentFlowId
            if (groupId != null && subtaskIndex != null && parentFlowId != null) {
                return FlowAwareResultConvergenceDecision.BindParallelResultToParentFlow(
                    flowId = context.flowId,
                    resultKey = context.resultKey,
                    groupId = groupId,
                    subtaskIndex = subtaskIndex,
                    parentFlowId = parentFlowId
                )
            }
        }

        // ── 5. Partial emit ────────────────────────────────────────────────────
        if (context.resultKind == FlowResultKind.PARTIAL) {
            return FlowAwareResultConvergenceDecision.EmitPartialForFlow(
                flowId = context.flowId,
                resultKey = context.resultKey
            )
        }

        // ── 6. Final emit — all terminal and replayed kinds ────────────────────
        return FlowAwareResultConvergenceDecision.EmitFinalForFlow(
            flowId = context.flowId,
            resultKey = context.resultKey,
            resultKind = context.resultKind
        )
    }

    // ── Finalized flow registry ───────────────────────────────────────────────

    /**
     * Marks the given [flowId] as having reached its local final convergence, recording
     * the [resultKey] of the emission that closed it.
     *
     * Once registered, [evaluateResultEmit] will return
     * [FlowAwareResultConvergenceDecision.SuppressLatePartialAfterFinal] for any
     * subsequent partial result evaluations for this flow.
     *
     * Callers should invoke this **after** emitting the final result and **before**
     * any subsequent evaluation, to ensure that the suppression guard is in place.
     *
     * @param flowId     The delegated flow ID to mark as final.
     * @param resultKey  The idempotency key of the final result emission that closed the flow.
     */
    fun markFlowFinal(flowId: String, resultKey: String) {
        synchronized(_finalizedFlows) {
            _finalizedFlows[flowId] = resultKey
        }
    }

    /**
     * Returns `true` when the given [flowId] has been registered as having a final
     * convergence result.
     *
     * @param flowId  The delegated flow ID to check.
     */
    fun isFlowFinal(flowId: String): Boolean =
        synchronized(_finalizedFlows) { _finalizedFlows.containsKey(flowId) }

    /**
     * Returns the result key of the final emission that closed [flowId], or `null` if
     * the flow has not yet reached final convergence.
     *
     * @param flowId  The delegated flow ID to query.
     */
    fun flowFinalResultKey(flowId: String): String? =
        synchronized(_finalizedFlows) { _finalizedFlows[flowId] }

    /**
     * Clears the final convergence registration for a single [flowId].
     *
     * Typically called when a flow's execution era is fully closed and a new flow era
     * begins (e.g. after replay / re-dispatch produces a new unit) to prevent stale
     * suppression.
     *
     * @param flowId  The delegated flow ID to clear.
     */
    fun clearFlowFinal(flowId: String) {
        synchronized(_finalizedFlows) { _finalizedFlows.remove(flowId) }
    }

    /**
     * Returns the count of currently registered finalized flow IDs.
     */
    val finalizedFlowCount: Int
        get() = synchronized(_finalizedFlows) { _finalizedFlows.size }

    /**
     * Clears the entire finalized flow registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).  After clearing, all flows will be treated as non-final.
     */
    fun clearAllFinalizedFlows() {
        synchronized(_finalizedFlows) { _finalizedFlows.clear() }
    }

    // ── Emitted result key registry ───────────────────────────────────────────

    /**
     * Records [resultKey] as having been emitted.
     *
     * After this call, [evaluateResultEmit] will return
     * [FlowAwareResultConvergenceDecision.SuppressDuplicateResultEmit] for any context
     * whose [ResultEmitContext.resultKey] matches [resultKey].  This provides the basic
     * idempotency suppression entry point for result replay / resend scenarios.
     *
     * @param resultKey  The stable idempotency key of the result that was emitted.
     */
    fun markResultEmitted(resultKey: String) {
        synchronized(_emittedResultKeys) { _emittedResultKeys.add(resultKey) }
    }

    /**
     * Returns `true` when [resultKey] has already been recorded as emitted.
     *
     * @param resultKey  The idempotency key to check.
     */
    fun isResultEmitted(resultKey: String): Boolean =
        synchronized(_emittedResultKeys) { _emittedResultKeys.contains(resultKey) }

    /**
     * Returns the count of currently recorded emitted result keys.
     */
    val emittedResultCount: Int
        get() = synchronized(_emittedResultKeys) { _emittedResultKeys.size }

    /**
     * Clears the entire emitted result key registry.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).  After clearing, all result keys will be treated as not-yet-emitted.
     */
    fun clearAllEmittedResults() {
        synchronized(_emittedResultKeys) { _emittedResultKeys.clear() }
    }

    // ── Convergence gate ──────────────────────────────────────────────────────

    /**
     * Closes the convergence emit gate for the given [flowId].
     *
     * While the gate is closed, [evaluateResultEmit] returns
     * [FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment] for all
     * evaluations for this flow (after duplicate and late-partial checks).  Call this at
     * the start of a reconnect / rebind / resume sequence to prevent result emission
     * before V2 alignment is established.
     *
     * @param flowId  The delegated flow ID for which to close the convergence gate.
     */
    fun closeConvergenceGate(flowId: String) {
        synchronized(_convergenceGatedFlows) { _convergenceGatedFlows.add(flowId) }
    }

    /**
     * Opens the convergence emit gate for the given [flowId].
     *
     * After the gate is opened, [evaluateResultEmit] will no longer return
     * [FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment] for the gate
     * check.  Call this once the reconnect / rebind / resume sequence has been resolved
     * and V2 canonical convergence alignment is complete.
     *
     * @param flowId  The delegated flow ID for which to open the convergence gate.
     */
    fun openConvergenceGate(flowId: String) {
        synchronized(_convergenceGatedFlows) { _convergenceGatedFlows.remove(flowId) }
    }

    /**
     * Returns `true` when the convergence emit gate for [flowId] is currently closed.
     *
     * @param flowId  The delegated flow ID to check.
     */
    fun isConvergenceGated(flowId: String): Boolean =
        synchronized(_convergenceGatedFlows) { _convergenceGatedFlows.contains(flowId) }

    /**
     * Returns the count of flow IDs for which the convergence emit gate is currently
     * closed.
     */
    val convergenceGatedFlowCount: Int
        get() = synchronized(_convergenceGatedFlows) { _convergenceGatedFlows.size }

    /**
     * Clears all convergence emit gates.
     *
     * Use only when the full execution era is being reset (e.g. session close / process
     * recreation).
     */
    fun clearAllConvergenceGates() {
        synchronized(_convergenceGatedFlows) { _convergenceGatedFlows.clear() }
    }

    // ── Companion — constants ─────────────────────────────────────────────────

    companion object {

        // ── Convergence decision semantic constants ───────────────────────────

        /**
         * Canonical wire value for the "emit partial for flow" decision.
         *
         * The flow is still active; Android may emit this partial result as a
         * non-terminal convergence artifact.  V2 must not treat it as the canonical final.
         */
        const val DECISION_EMIT_PARTIAL_FOR_FLOW = "emit_partial_for_flow"

        /**
         * Canonical wire value for the "emit final for flow" decision.
         *
         * Android has reached the canonical terminal convergence for this flow.  V2 must
         * treat this as the closing Android-side result.
         */
        const val DECISION_EMIT_FINAL_FOR_FLOW = "emit_final_for_flow"

        /**
         * Canonical wire value for the "bind parallel result to parent flow" decision.
         *
         * The result originates from a parallel/collaboration subtask and must be routed
         * to the parent flow aggregation bucket using `group_id` and `subtask_index`.
         */
        const val DECISION_BIND_PARALLEL_RESULT_TO_PARENT_FLOW =
            "bind_parallel_result_to_parent_flow"

        /**
         * Canonical wire value for the "suppress duplicate result emit" decision.
         *
         * This result has already been emitted (idempotency key match).  Duplicate
         * emission must be suppressed to prevent double-counting in V2.
         */
        const val DECISION_SUPPRESS_DUPLICATE_RESULT_EMIT = "suppress_duplicate_result_emit"

        /**
         * Canonical wire value for the "suppress late partial after final" decision.
         *
         * The final result for this flow has already been emitted.  Late partial results
         * must not be forwarded after the canonical final has been set.
         */
        const val DECISION_SUPPRESS_LATE_PARTIAL_AFTER_FINAL =
            "suppress_late_partial_after_final"

        /**
         * Canonical wire value for the "hold result for convergence alignment" decision.
         *
         * The convergence gate for this flow is closed pending V2 alignment.  The result
         * must be buffered or discarded until the gate opens.
         */
        const val DECISION_HOLD_RESULT_FOR_CONVERGENCE_ALIGNMENT =
            "hold_result_for_convergence_alignment"

        // ── Result kind constants ─────────────────────────────────────────────

        /** Partial result kind wire value; classifies as [FlowResultKind.PARTIAL]. */
        const val KIND_PARTIAL = "partial"

        /** Final result kind wire value; classifies as [FlowResultKind.FINAL]. */
        const val KIND_FINAL = "final"

        /** Failure terminal result kind wire value; classifies as [FlowResultKind.FAILURE_TERMINAL]. */
        const val KIND_FAILURE_TERMINAL = "failure_terminal"

        /** Cancel terminal result kind wire value; classifies as [FlowResultKind.CANCEL_TERMINAL]. */
        const val KIND_CANCEL_TERMINAL = "cancel_terminal"

        /** Parallel sub-result kind wire value; classifies as [FlowResultKind.PARALLEL_SUB_RESULT]. */
        const val KIND_PARALLEL_SUB_RESULT = "parallel_sub_result"

        /** Replayed/resent result kind wire value; classifies as [FlowResultKind.REPLAYED_RESENT]. */
        const val KIND_REPLAYED_RESENT = "replayed_resent"

        // ── Integration point constants ───────────────────────────────────────

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeReceiver].
         *
         * Receipt acceptance is the anchor for [ResultEmitContext.flowId] and is the
         * earliest point at which [closeConvergenceGate] may be called to protect the
         * convergence boundary during reconnect.
         */
        const val INTEGRATION_RECEIVER = "DelegatedRuntimeReceiver"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedRuntimeUnit].
         *
         * Unit identity (`unitId` / `flowId`) is the stable anchor for all result
         * classification and convergence gating in this participant.
         */
        const val INTEGRATION_UNIT = "DelegatedRuntimeUnit"

        /**
         * Integration point identifier for [com.ufo.galaxy.runtime.DelegatedActivationRecord].
         *
         * When [DelegatedActivationRecord.activationStatus] transitions to a terminal
         * status (COMPLETED, FAILED, REJECTED), callers should invoke [markFlowFinal]
         * with the corresponding [flowId] and result key to enable late-partial suppression.
         */
        const val INTEGRATION_ACTIVATION_RECORD = "DelegatedActivationRecord"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.AutonomousExecutionPipeline].
         *
         * Pipeline result output (partial and final) must be classified via
         * [evaluateResultEmit] before being forwarded.
         */
        const val INTEGRATION_PIPELINE = "AutonomousExecutionPipeline"

        /**
         * Integration point identifier for [com.ufo.galaxy.loop.LoopController].
         *
         * Loop partial results (step progress, intermediate goal states) should be
         * classified as [FlowResultKind.PARTIAL] and evaluated via [evaluateResultEmit].
         */
        const val INTEGRATION_LOOP_CONTROLLER = "LoopController"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.DelegatedTakeoverExecutor].
         *
         * RESULT signals (completed / failed / cancelled) should be classified as
         * [FlowResultKind.FINAL], [FlowResultKind.FAILURE_TERMINAL], or
         * [FlowResultKind.CANCEL_TERMINAL] and evaluated via [evaluateResultEmit].
         * Callers must invoke [markFlowFinal] and [markResultEmitted] after the decision
         * permits final emit.
         */
        const val INTEGRATION_TAKEOVER_EXECUTOR = "DelegatedTakeoverExecutor"

        /**
         * Integration point identifier for [com.ufo.galaxy.agent.LocalCollaborationAgent].
         *
         * Subtask results should be classified as [FlowResultKind.PARALLEL_SUB_RESULT]
         * and evaluated via [evaluateResultEmit].  The [ResultEmitContext.groupId],
         * [ResultEmitContext.subtaskIndex], and [ResultEmitContext.parentFlowId] must all
         * be populated for proper parent flow binding.
         */
        const val INTEGRATION_COLLABORATION_AGENT = "LocalCollaborationAgent"

        // ── Reason constants ──────────────────────────────────────────────────

        /**
         * Human-readable reason used in
         * [FlowAwareResultConvergenceDecision.HoldResultForConvergenceAlignment] when the
         * convergence gate is closed pending V2 canonical alignment.
         */
        const val REASON_CONVERGENCE_GATE_CLOSED_PENDING_ALIGNMENT =
            "convergence_gate_closed_pending_v2_alignment"

        // ── PR designation ────────────────────────────────────────────────────

        /**
         * The PR designation for this participant.
         */
        const val INTRODUCED_PR = 6

        /**
         * Human-readable description of this component's role in the result convergence model.
         */
        const val DESCRIPTION =
            "Android-side flow-aware result convergence participation owner: classifies " +
                "partial/final/parallel/replayed results, gates emit during reconnect/resume, " +
                "suppresses late partial after final, suppresses duplicate result emit, " +
                "and binds parallel sub-results to parent flow context for V2 canonical convergence."
    }
}
