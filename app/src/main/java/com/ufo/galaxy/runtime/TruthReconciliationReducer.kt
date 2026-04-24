package com.ufo.galaxy.runtime

/**
 * PR-64 — Truth Reconciliation Reducer.
 *
 * The **unified Android-side convergence entry point** for all authoritative runtime
 * truth updates.  [TruthReconciliationReducer] is a pure, stateless function object
 * that accepts a current [AndroidParticipantRuntimeTruth] snapshot and a
 * [RuntimeTruthPatch] and returns a new snapshot that reflects the patch.
 *
 * ## Problem addressed
 *
 * Before PR-64, every event handler updated Android's local participant/runtime truth
 * independently:
 *
 *  - [com.ufo.galaxy.service.GalaxyConnectionService.handleTakeoverRequest] — updated
 *    takeover outcome in isolation.
 *  - [RuntimeController.publishTaskResult] / [publishTaskCancelled] /
 *    [notifyTakeoverFailed] — each cleared active-task state through separate code paths.
 *  - Handoff accept/reject responses had no structured local truth update.
 *  - [RuntimeController.closeAttachedSession] — interrupted active tasks, but produced
 *    no single convergence patch record.
 *
 * Multiple modules independently advanced the same task terminal state, making truth
 * non-auditable and ordering-sensitive.
 *
 * [TruthReconciliationReducer] closes this gap by providing a **single typed reducer**
 * that every authoritative event can funnel through before any snapshot update is applied.
 *
 * ## Reducer contract
 *
 * The reducer implements four invariants:
 *
 * 1. **Epoch gating** — a patch whose [RuntimeTruthPatch.reconciliationEpoch] is lower
 *    than the current snapshot's [AndroidParticipantRuntimeTruth.reconciliationEpoch] is
 *    discarded; the original snapshot is returned unchanged.  This guarantees that late-arriving
 *    or replayed patches never overwrite newer truth.
 *
 * 2. **Authoritative-only mutation** — patches with [RuntimeTruthPatch.isAuthoritative]
 *    `= false` (notification signals) are accepted and epoch-advanced, but do not mutate
 *    task terminal state or ownership fields.  The snapshot's epoch is bumped to reflect
 *    the received signal, enabling observers to detect the event without materialising state
 *    side-effects.
 *
 * 3. **Terminal idempotency** — if the current snapshot has no [AndroidParticipantRuntimeTruth.activeTaskId]
 *    (task is already idle) and an authoritative task-terminal patch arrives, the reducer
 *    treats it as a no-op with respect to active task fields (already cleared) but still
 *    bumps the reconciliation epoch.
 *
 * 4. **Participant mismatch safety** — patches whose [RuntimeTruthPatch.participantId]
 *    does not match the snapshot's [AndroidParticipantRuntimeTruth.participantId] are
 *    rejected; the original snapshot is returned unchanged.
 *
 * ## Usage
 *
 * ```kotlin
 * var truth: AndroidParticipantRuntimeTruth = initialSnapshot()
 *
 * // On delegated task result:
 * val patch = RuntimeTruthPatch(
 *     patchId = UUID.randomUUID().toString(),
 *     participantId = truth.participantId,
 *     taskId = "task-123",
 *     kind = RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
 *     isAuthoritative = true,
 *     taskTerminalOutcome = RuntimeTruthPatch.TaskTerminalOutcome.COMPLETED,
 *     reconciliationEpoch = nextEpoch()
 * )
 * truth = TruthReconciliationReducer.reduce(truth, patch)
 * ```
 *
 * @see RuntimeTruthPatch
 * @see AndroidParticipantRuntimeTruth
 * @see ReconciliationSignal
 * @see ParticipantLiveExecutionSurface
 */
object TruthReconciliationReducer {

    // ── Reduction result ──────────────────────────────────────────────────────

    /**
     * Outcome of a [reduce] call.
     *
     * @property snapshot    The (possibly updated) [AndroidParticipantRuntimeTruth].
     * @property applied     `true` when the patch was applied to produce a new snapshot;
     *                       `false` when the patch was discarded and [snapshot] equals the
     *                       input (epoch-gated, participant-mismatched, or non-authoritative).
     * @property discardReason  Human-readable reason the patch was discarded; null when
     *                       [applied] is `true`.
     */
    data class ReduceResult(
        val snapshot: AndroidParticipantRuntimeTruth,
        val applied: Boolean,
        val discardReason: String? = null
    )

    // ── Primary entry point ───────────────────────────────────────────────────

    /**
     * Applies [patch] to [current] and returns the resulting [ReduceResult].
     *
     * This is the **canonical single convergence entry point** for all Android-side
     * runtime truth updates.  All event handlers that advance task terminal state,
     * participant role/state, or ownership should funnel through this method.
     *
     * The four invariants described in the class KDoc are enforced here:
     * epoch gating, authoritative-only mutation, terminal idempotency, and participant
     * mismatch safety.
     *
     * @param current  The current [AndroidParticipantRuntimeTruth] snapshot.
     * @param patch    The [RuntimeTruthPatch] to apply.
     * @return         A [ReduceResult] containing the (possibly unchanged) snapshot
     *                 and a flag indicating whether the patch was applied.
     */
    fun reduce(
        current: AndroidParticipantRuntimeTruth,
        patch: RuntimeTruthPatch
    ): ReduceResult {

        // Invariant 4: Participant mismatch safety.
        if (patch.participantId != current.participantId) {
            return ReduceResult(
                snapshot = current,
                applied = false,
                discardReason = "participant_mismatch: patch.participantId=${patch.participantId} " +
                    "current.participantId=${current.participantId}"
            )
        }

        // Invariant 1: Epoch gating — stale patches are silently discarded.
        if (patch.reconciliationEpoch < current.reconciliationEpoch) {
            return ReduceResult(
                snapshot = current,
                applied = false,
                discardReason = "stale_epoch: patch.epoch=${patch.reconciliationEpoch} " +
                    "current.epoch=${current.reconciliationEpoch}"
            )
        }

        // Invariant 2: Non-authoritative patches bump the epoch but do not mutate task or
        // ownership state.  They are advisory notification signals only.
        if (!patch.isAuthoritative) {
            return ReduceResult(
                snapshot = current.copy(reconciliationEpoch = patch.reconciliationEpoch),
                applied = true
            )
        }

        // Authoritative patch: dispatch to the appropriate reduction path.
        val updated = when (patch.kind) {
            RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED,
            RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED ->
                applyTaskTerminal(current, patch)

            RuntimeTruthPatch.Kind.SESSION_TERMINAL ->
                applySessionTerminal(current, patch)

            RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED,
            RuntimeTruthPatch.Kind.HANDOFF_REJECTED,
            RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED,
            RuntimeTruthPatch.Kind.TAKEOVER_REJECTED ->
                applyOwnershipTransition(current, patch)

            RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED ->
                // Advisory state change — epoch advanced but no task/ownership mutation.
                current.copy(reconciliationEpoch = patch.reconciliationEpoch)
        }

        return ReduceResult(
            snapshot = updated,
            applied = true
        )
    }

    // ── Private reduction helpers ─────────────────────────────────────────────

    /**
     * Applies a task-terminal patch ([DELEGATED_TASK_RESULT], [DELEGATED_TASK_CANCELLED],
     * [DELEGATED_TASK_FAILED]).
     *
     * Clears [AndroidParticipantRuntimeTruth.activeTaskId] and
     * [AndroidParticipantRuntimeTruth.activeTaskStatus] regardless of whether the patch's
     * [RuntimeTruthPatch.taskId] matches the current [activeTaskId] (terminal idempotency).
     *
     * The terminal outcome is not stored on [AndroidParticipantRuntimeTruth] directly;
     * it is carried in the patch and logged by the caller.  The snapshot is advanced to
     * the idle state so that subsequent eligibility checks see Android as ready.
     */
    private fun applyTaskTerminal(
        current: AndroidParticipantRuntimeTruth,
        patch: RuntimeTruthPatch
    ): AndroidParticipantRuntimeTruth = current.copy(
        activeTaskId = null,
        activeTaskStatus = null,
        reconciliationEpoch = patch.reconciliationEpoch
    )

    /**
     * Applies a session-terminal patch ([SESSION_TERMINAL]).
     *
     * If an active task exists it is treated as [TaskTerminalOutcome.FAILED] (the session
     * interruption forced task failure).  The snapshot is reset to the idle task state
     * and epoch is advanced.
     */
    private fun applySessionTerminal(
        current: AndroidParticipantRuntimeTruth,
        patch: RuntimeTruthPatch
    ): AndroidParticipantRuntimeTruth = current.copy(
        activeTaskId = null,
        activeTaskStatus = null,
        reconciliationEpoch = patch.reconciliationEpoch
    )

    /**
     * Applies an ownership-transition patch (handoff or takeover accept/reject).
     *
     * Ownership transitions do not clear active task state directly (the task may continue
     * under the new owner).  The epoch is advanced.  For takeover-accepted patches, the
     * [RuntimeTruthPatch.taskId] is echoed into [AndroidParticipantRuntimeTruth.activeTaskId]
     * with [ActiveTaskStatus.RUNNING] when the current snapshot has no active task, signaling
     * that Android has accepted a new delegated task.
     */
    private fun applyOwnershipTransition(
        current: AndroidParticipantRuntimeTruth,
        patch: RuntimeTruthPatch
    ): AndroidParticipantRuntimeTruth {
        return when {
            patch.kind == RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED && current.activeTaskId == null ->
                current.copy(
                    activeTaskId = patch.taskId,
                    activeTaskStatus = patch.taskId?.let { ActiveTaskStatus.RUNNING },
                    reconciliationEpoch = patch.reconciliationEpoch
                )
            else ->
                current.copy(reconciliationEpoch = patch.reconciliationEpoch)
        }
    }

    // ── Batch reduction ───────────────────────────────────────────────────────

    /**
     * Applies a sequence of [patches] to [initial] in order, returning the final
     * [AndroidParticipantRuntimeTruth].
     *
     * Each patch is applied through [reduce].  Patches that are epoch-gated or discarded
     * by any invariant are silently skipped, ensuring that multi-event arrival sequences
     * produce a stable, consistent final truth regardless of ordering.
     *
     * @param initial  Starting [AndroidParticipantRuntimeTruth] snapshot.
     * @param patches  Sequence of [RuntimeTruthPatch] events to fold over [initial].
     * @return         The final [AndroidParticipantRuntimeTruth] after all applicable patches
     *                 have been applied.
     */
    fun reduceFold(
        initial: AndroidParticipantRuntimeTruth,
        patches: List<RuntimeTruthPatch>
    ): AndroidParticipantRuntimeTruth =
        patches.fold(initial) { acc, patch -> reduce(acc, patch).snapshot }
}
