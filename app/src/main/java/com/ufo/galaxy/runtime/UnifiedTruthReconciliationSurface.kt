package com.ufo.galaxy.runtime

/**
 * PR-64 — Unified Android truth/reconciliation convergence surface.
 *
 * This object is the **canonical reviewer reference** for tracing how Android's unified
 * truth/reconciliation convergence model works across delegated execution, handoff,
 * takeover, and task terminal events.  It answers the four PR-64 acceptance criteria
 * in one place:
 *
 *  1. **Which event sources are authoritative truth updates and which are notification
 *     signals only?** — See [AUTHORITATIVE_TRUTH_SOURCES] and [NOTIFICATION_SIGNALS].
 *
 *  2. **How does the unified convergence model prevent multiple modules from
 *     independently advancing the same task terminal state?** — See [CONVERGENCE_MODEL].
 *
 *  3. **How does Android local truth remain stable and consistent when multiple events
 *     arrive in different orderings?** — See [MULTI_EVENT_ORDERING].
 *
 *  4. **What data models carry the unified truth and how are they structured?** —
 *     See [DATA_MODEL_SURFACES].
 *
 * ## Problem addressed
 *
 * Before PR-64, delegated execution result, handoff response, takeover accept/reject, and
 * session terminal events each advanced task terminal state through independent code paths:
 *
 *  - No single convergence point existed — state could be cleared multiple times.
 *  - Handoff outcomes had no structured local truth update.
 *  - Authoritative mutations were not distinguished from advisory notification signals.
 *  - Multi-event orderings could produce inconsistent terminal states.
 *
 * PR-64 introduces three new surfaces:
 *  - [RuntimeTruthPatch] — typed, atomic, event-sourced truth update.
 *  - [TruthReconciliationReducer] — pure, stateless reducer implementing four invariants
 *    (epoch gating, authoritative-only mutation, terminal idempotency, participant safety).
 *  - [UnifiedTruthReconciliationSurface] (this object) — reviewer reference / acceptance
 *    criteria declaration.
 *
 * @see RuntimeTruthPatch
 * @see TruthReconciliationReducer
 * @see AndroidParticipantRuntimeTruth
 * @see ReconciliationSignal
 * @see ParticipantLiveExecutionSurface
 * @see ParticipantProgressFeedbackSurface
 */
object UnifiedTruthReconciliationSurface {

    // ── PR-64 acceptance criteria maps ────────────────────────────────────────

    /**
     * **AC-1a: Which events are authoritative truth updates.**
     *
     * Key: event/patch kind wire value.
     * Value: description of why this event is authoritative and what truth it mutates.
     *
     * An **authoritative** patch ([RuntimeTruthPatch.isAuthoritative] = `true`) is one
     * that [TruthReconciliationReducer] must apply to [AndroidParticipantRuntimeTruth].
     * It is the single convergence path through which task terminal state, participant
     * role/state, and ownership outcomes are mutated.
     */
    val AUTHORITATIVE_TRUTH_SOURCES: Map<String, String> = mapOf(

        RuntimeTruthPatch.Kind.DELEGATED_TASK_RESULT.wireValue to
            "RuntimeController.publishTaskResult() — Android completed a delegated task. " +
            "Authoritative: TruthReconciliationReducer clears activeTaskId / activeTaskStatus " +
            "and records TaskTerminalOutcome.COMPLETED. " +
            "V2 receives TASK_RESULT ReconciliationSignal as the upstream protocol event.",

        RuntimeTruthPatch.Kind.DELEGATED_TASK_CANCELLED.wireValue to
            "RuntimeController.publishTaskCancelled() / notifyTakeoverFailed(CANCELLED) — " +
            "Android cancelled a running task. " +
            "Authoritative: TruthReconciliationReducer clears activeTaskId / activeTaskStatus " +
            "and records TaskTerminalOutcome.CANCELLED. " +
            "V2 receives TASK_CANCELLED ReconciliationSignal as the upstream protocol event.",

        RuntimeTruthPatch.Kind.DELEGATED_TASK_FAILED.wireValue to
            "RuntimeController.notifyTakeoverFailed(non-CANCELLED) — Android failed a task. " +
            "Also: RuntimeController.closeAttachedSession(DISCONNECT or INVALIDATION) when " +
            "activeTaskId is non-null (session interruption forces task failure). " +
            "Authoritative: TruthReconciliationReducer clears activeTaskId / activeTaskStatus " +
            "and records TaskTerminalOutcome.FAILED. " +
            "V2 receives TASK_FAILED ReconciliationSignal as the upstream protocol event.",

        RuntimeTruthPatch.Kind.HANDOFF_ACCEPTED.wireValue to
            "GalaxyConnectionService.handoff() success path — the handoff request was " +
            "accepted by the remote runtime. " +
            "Authoritative ownership-transition: TruthReconciliationReducer advances the " +
            "reconciliationEpoch, recording that execution ownership was transferred. " +
            "V2 is informed via the handoff wire response, not via ReconciliationSignal.",

        RuntimeTruthPatch.Kind.HANDOFF_REJECTED.wireValue to
            "GalaxyConnectionService.handoff() rejection path — the remote runtime refused " +
            "the handoff request. " +
            "Authoritative ownership-transition: Android retains execution ownership; " +
            "TruthReconciliationReducer advances the epoch without clearing task state. " +
            "V2 is informed via the handoff wire response.",

        RuntimeTruthPatch.Kind.TAKEOVER_ACCEPTED.wireValue to
            "GalaxyConnectionService.handleTakeoverRequest() acceptance path — Android " +
            "accepted delegated work from V2. " +
            "Authoritative ownership-transition: TruthReconciliationReducer records " +
            "TakeoverOutcome.ACCEPTED and sets activeTaskId=taskId / activeTaskStatus=RUNNING " +
            "when the participant was previously idle. " +
            "V2 receives TakeoverResponseEnvelope(accepted=true) and TASK_ACCEPTED ReconciliationSignal.",

        RuntimeTruthPatch.Kind.TAKEOVER_REJECTED.wireValue to
            "GalaxyConnectionService.handleTakeoverRequest() rejection path — Android " +
            "refused delegated work from V2 (eligibility failure, no active session, etc.). " +
            "Authoritative ownership-transition: TruthReconciliationReducer records " +
            "TakeoverOutcome.REJECTED and advances epoch without acquiring task ownership. " +
            "V2 receives TakeoverResponseEnvelope(accepted=false) with the rejection reason.",

        RuntimeTruthPatch.Kind.SESSION_TERMINAL.wireValue to
            "RuntimeController.closeAttachedSession() — the attached runtime session reached " +
            "a terminal state (DISCONNECT, INVALIDATION, or DISABLE). " +
            "Authoritative: TruthReconciliationReducer clears activeTaskId / activeTaskStatus " +
            "(treating any in-flight task as FAILED) and advances the reconciliation epoch. " +
            "V2 receives TASK_FAILED (if task was active) and DeviceDisconnected lifecycle event."
    )

    /**
     * **AC-1b: Which signals are notification-only (non-authoritative).**
     *
     * Key: signal or event name.
     * Value: description of why this signal is advisory and must not mutate canonical truth.
     *
     * A **notification signal** patch ([RuntimeTruthPatch.isAuthoritative] = `false`) is
     * accepted by [TruthReconciliationReducer] and advances the reconciliation epoch, but
     * does not mutate active task state or ownership fields.  V2 may observe these signals
     * for monitoring and display without treating them as canonical state changes.
     */
    val NOTIFICATION_SIGNALS: Map<String, String> = mapOf(

        RuntimeTruthPatch.Kind.PARTICIPANT_STATE_CHANGED.wireValue to
            "RuntimeController.notifyParticipantHealthChanged() — participant health or " +
            "readiness state changed. " +
            "Notification signal: TruthReconciliationReducer advances the reconciliationEpoch " +
            "but does not close any task or transfer ownership. " +
            "V2 receives PARTICIPANT_STATE ReconciliationSignal for its canonical participant view.",

        "task_status_update" to
            "RuntimeController.publishTaskStatusUpdate() — Android reports in-progress execution " +
            "status for a running task. " +
            "Notification signal: this is an intermediate advisory signal, not a task terminal " +
            "outcome. TruthReconciliationReducer does not mutate task state for these signals. " +
            "V2 receives TASK_STATUS_UPDATE ReconciliationSignal for progress tracking only.",

        "runtime_truth_snapshot_request" to
            "RuntimeController.publishRuntimeTruthSnapshot() — periodic or reconnect-triggered " +
            "full participant truth snapshot sent to V2. " +
            "Notification signal: the snapshot itself is advisory — it reflects the current " +
            "state; it does not advance task terminal state or ownership. " +
            "V2 uses the snapshot for full reconciliation pass, resolving conflicts in " +
            "favour of the Android-owned snapshot.",

        "subtask_checkpoint" to
            "ParticipantProgressCheckpoint / SubtaskProgressReport emitted via " +
            "TASK_STATUS_UPDATE signals (PR-63). " +
            "Notification signal: intermediate execution progress. V2 uses for display and " +
            "monitoring only. Android never expects V2 to make dispatch decisions based on " +
            "checkpoint-level progress state."
    )

    /**
     * **AC-2: How the unified convergence model prevents independent terminal state mutation.**
     *
     * Key: convergence mechanism name.
     * Value: description of how the mechanism closes a prior multi-module divergence gap.
     */
    val CONVERGENCE_MODEL: Map<String, String> = mapOf(

        "single_reducer_entry_point" to
            "TruthReconciliationReducer.reduce(current, patch) is the canonical single " +
            "convergence entry point for all authoritative truth updates. " +
            "Every event handler that advances task terminal state, participant role/state, " +
            "or ownership must produce a RuntimeTruthPatch and funnel it through the reducer. " +
            "No module should mutate AndroidParticipantRuntimeTruth directly outside the reducer.",

        "typed_patch_discriminator" to
            "RuntimeTruthPatch.Kind discriminates every event source with a stable wire value. " +
            "This makes the originating event observable in logs and tests without requiring " +
            "code path tracing. Any log consumer or test can assert which kind of patch " +
            "was applied by inspecting RuntimeTruthPatch.toMap()[KEY_KIND].",

        "authoritative_flag_gate" to
            "RuntimeTruthPatch.isAuthoritative gates which patches may mutate canonical truth. " +
            "Notification signals (isAuthoritative=false) advance the epoch but do not clear " +
            "activeTaskId or activeTaskStatus. This prevents advisory signals from accidentally " +
            "closing tasks — a gap that existed when publishTaskStatusUpdate() and " +
            "notifyParticipantHealthChanged() had no explicit authoritative/advisory distinction.",

        "epoch_ordering_guard" to
            "TruthReconciliationReducer discards patches whose reconciliationEpoch is lower " +
            "than the current snapshot epoch. This guarantees that late-arriving or replayed " +
            "patches (e.g. a TASK_FAILED arriving after a SESSION_TERMINAL for the same task) " +
            "do not overwrite newer terminal truth. The first authoritative terminal patch wins.",

        "terminal_idempotency" to
            "TruthReconciliationReducer.applyTaskTerminal() clears activeTaskId and " +
            "activeTaskStatus regardless of whether the patch taskId matches the current " +
            "snapshot (terminal idempotency). A second TASK_FAILED arriving for a task that " +
            "is already cleared is a safe no-op — the snapshot remains idle, the epoch advances. " +
            "This closes the gap where multiple code paths (e.g. notifyTakeoverFailed + " +
            "closeAttachedSession) could both attempt to clear the same task.",

        "participant_safety_guard" to
            "TruthReconciliationReducer rejects patches whose participantId does not match " +
            "the snapshot's participantId, returning the snapshot unchanged. This prevents " +
            "cross-participant pollution when multiple participant contexts are live."
    )

    /**
     * **AC-3: How Android local truth remains stable when multiple events arrive in any order.**
     *
     * Key: ordering scenario name.
     * Value: description of how the reducer produces stable truth in that scenario.
     */
    val MULTI_EVENT_ORDERING: Map<String, String> = mapOf(

        "delegated_result_then_session_terminal" to
            "DELEGATED_TASK_RESULT (epoch N) arrives before SESSION_TERMINAL (epoch N+1). " +
            "Reducer applies RESULT first: activeTaskId cleared, epoch=N. " +
            "SESSION_TERMINAL arrives: activeTaskId already null (idempotent); epoch=N+1. " +
            "Final truth: idle (no active task), epoch=N+1. Correct stable outcome.",

        "session_terminal_then_delegated_result" to
            "SESSION_TERMINAL (epoch N) arrives before DELEGATED_TASK_RESULT (epoch N-1). " +
            "Reducer applies SESSION_TERMINAL: activeTaskId cleared, epoch=N. " +
            "DELEGATED_TASK_RESULT arrives with epoch N-1 < N: epoch-gated, discarded. " +
            "Final truth: idle (no active task), epoch=N. Correct stable outcome — the " +
            "later-arriving stale result does not reopen the task.",

        "takeover_accepted_then_task_result" to
            "TAKEOVER_ACCEPTED (epoch N) sets activeTaskId=task-A, epoch=N. " +
            "DELEGATED_TASK_RESULT for task-A (epoch N+1) arrives: terminal patch applied, " +
            "activeTaskId cleared, epoch=N+1. " +
            "Final truth: idle, epoch=N+1. Correct ownership accept→task complete lifecycle.",

        "handoff_rejected_then_task_cancelled" to
            "HANDOFF_REJECTED (epoch N) advances epoch without clearing task. " +
            "DELEGATED_TASK_CANCELLED for task-B (epoch N+1) arrives: terminal patch clears " +
            "activeTaskId, epoch=N+1. " +
            "Final truth: idle, epoch=N+1. Correct — handoff rejection did not close the task; " +
            "explicit cancellation did.",

        "duplicate_task_failed_events" to
            "DELEGATED_TASK_FAILED (epoch N) arrives: terminal patch, activeTaskId cleared, epoch=N. " +
            "Duplicate DELEGATED_TASK_FAILED (epoch N) arrives again (replayed signal). " +
            "Second patch: epoch N == current epoch N → not stale; isAuthoritative=true; " +
            "applyTaskTerminal: activeTaskId already null (no-op), epoch stays N. " +
            "Final truth: idle, epoch=N. Correct — duplicate terminal is safe and idempotent.",

        "notification_then_authoritative_terminal" to
            "TASK_STATUS_UPDATE notification patch (isAuthoritative=false, epoch N) arrives: " +
            "epoch advanced to N, task state unchanged. " +
            "DELEGATED_TASK_RESULT authoritative patch (epoch N+1) arrives: terminal applied, " +
            "activeTaskId cleared, epoch=N+1. " +
            "Final truth: idle, epoch=N+1. Correct — advisory signal did not preclude the " +
            "subsequent authoritative terminal."
    )

    /**
     * **AC-4: Data models that carry unified truth and how they are structured.**
     *
     * Key: data model class name.
     * Value: description of what the model carries and its role in the convergence system.
     */
    val DATA_MODEL_SURFACES: Map<String, String> = mapOf(

        "RuntimeTruthPatch" to
            "PR-64 typed, atomic, event-sourced truth update. " +
            "Fields: patchId (stable deduplication ID), participantId (routing), taskId (optional), " +
            "kind (9-value Kind enum with stable wireValues), isAuthoritative (gate flag), " +
            "taskTerminalOutcome (COMPLETED / CANCELLED / FAILED), " +
            "handoffOutcome (ACCEPTED / REJECTED), takeoverOutcome (ACCEPTED / REJECTED), " +
            "errorDetail (rejection reason), patchedAtMs, reconciliationEpoch. " +
            "toMap() produces a stable key→value Map under KEY_* constants for structured logging.",

        "TruthReconciliationReducer" to
            "PR-64 pure, stateless reducer function object. " +
            "Primary API: reduce(current, patch) → ReduceResult(snapshot, applied, discardReason). " +
            "Batch API: reduceFold(initial, patches) → AndroidParticipantRuntimeTruth. " +
            "Implements four invariants: epoch gating, authoritative-only mutation, terminal " +
            "idempotency, participant mismatch safety. " +
            "Dispatches to applyTaskTerminal, applySessionTerminal, applyOwnershipTransition " +
            "reduction helpers based on RuntimeTruthPatch.Kind.",

        "AndroidParticipantRuntimeTruth" to
            "PR-51 consolidated, structured snapshot of all Android-side participant runtime " +
            "truth fields. Carries: participantId, deviceId, hostId, deviceRole, " +
            "participationState, coordinationRole, sourceRuntimePosture, sessionId, sessionState, " +
            "delegatedExecutionCount, healthState, readinessState, activeTaskId, activeTaskStatus, " +
            "reportedAtMs, reconciliationEpoch. " +
            "TruthReconciliationReducer produces updated copies via copy() — original is never " +
            "mutated in place.",

        "TruthReconciliationReducer.ReduceResult" to
            "Outcome wrapper returned by TruthReconciliationReducer.reduce(). " +
            "Fields: snapshot (new or unchanged AndroidParticipantRuntimeTruth), " +
            "applied (true when patch was applied), discardReason (non-null when applied=false). " +
            "Enables callers to distinguish applied vs. discarded patches for logging and " +
            "diagnostics without needing to compare snapshot equality themselves."
    )

    // ── Wire-key constant aliases ─────────────────────────────────────────────

    /**
     * Wire key for [RuntimeTruthPatch.patchId] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_PATCH_ID].
     */
    const val KEY_PATCH_ID = RuntimeTruthPatch.KEY_PATCH_ID

    /**
     * Wire key for [RuntimeTruthPatch.participantId] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_PARTICIPANT_ID].
     */
    const val KEY_PARTICIPANT_ID = RuntimeTruthPatch.KEY_PARTICIPANT_ID

    /**
     * Wire key for [RuntimeTruthPatch.taskId] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_TASK_ID].
     */
    const val KEY_TASK_ID = RuntimeTruthPatch.KEY_TASK_ID

    /**
     * Wire key for [RuntimeTruthPatch.kind] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_KIND].
     */
    const val KEY_KIND = RuntimeTruthPatch.KEY_KIND

    /**
     * Wire key for [RuntimeTruthPatch.isAuthoritative] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_IS_AUTHORITATIVE].
     */
    const val KEY_IS_AUTHORITATIVE = RuntimeTruthPatch.KEY_IS_AUTHORITATIVE

    /**
     * Wire key for [RuntimeTruthPatch.taskTerminalOutcome] in structured log maps.
     * Forwarded from [RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME].
     */
    const val KEY_TASK_TERMINAL_OUTCOME = RuntimeTruthPatch.KEY_TASK_TERMINAL_OUTCOME

    // ── Count invariants ──────────────────────────────────────────────────────

    /**
     * Expected number of entries in [AUTHORITATIVE_TRUTH_SOURCES].
     */
    const val AUTHORITATIVE_TRUTH_SOURCES_COUNT: Int = 8

    /**
     * Expected number of entries in [NOTIFICATION_SIGNALS].
     */
    const val NOTIFICATION_SIGNALS_COUNT: Int = 4

    /**
     * Expected number of entries in [CONVERGENCE_MODEL].
     */
    const val CONVERGENCE_MODEL_COUNT: Int = 6

    /**
     * Expected number of entries in [MULTI_EVENT_ORDERING].
     */
    const val MULTI_EVENT_ORDERING_COUNT: Int = 6

    /**
     * Expected number of entries in [DATA_MODEL_SURFACES].
     */
    const val DATA_MODEL_SURFACES_COUNT: Int = 4

    /**
     * PR number that introduced this unified truth reconciliation surface.
     */
    const val INTRODUCED_PR: Int = 64
}
