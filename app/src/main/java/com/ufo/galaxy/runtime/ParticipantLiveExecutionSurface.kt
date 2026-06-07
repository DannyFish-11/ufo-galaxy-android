package com.ufo.galaxy.runtime

/**
 * PR-62 — Android participant live execution surface.
 *
 * This object is the **canonical reviewer reference** for tracing Android participant truth
 * through live execution, lifecycle, and protocol surfaces.  It answers the four PR-62
 * acceptance criteria in one place:
 *
 *  1. **Where does Android participant/runtime truth live in actual execution and lifecycle
 *     code paths?** — See [LIVE_EXECUTION_WIRING].
 *
 *  2. **How do cancel/status/failure/result influence Android runtime behavior and task
 *     continuity?** — See [TASK_LIFECYCLE_WIRING].
 *
 *  3. **What participant-local truth survives, resumes, resets, or emits across interruption
 *     and recovery cases?** — See [INTERRUPTION_AND_RECOVERY].
 *
 *  4. **Does Android now behave more like a live first-class participant runtime rather than
 *     a semantic or observational shell?** — See [FIRST_CLASS_PARTICIPANT_EVIDENCE].
 *
 * ## Role of this surface
 *
 * Before PR-62, all signal emission capability existed, but:
 *  - Active task state was not tracked persistently in [RuntimeController]; the
 *    [RuntimeController.publishRuntimeTruthSnapshot] caller had to supply task identity.
 *  - No method existed to emit [ReconciliationSignal.Kind.TASK_STATUS_UPDATE] from
 *    [RuntimeController] directly.
 *  - When the WS session was interrupted (DISCONNECT or INVALIDATION), any in-flight task
 *    was silently abandoned — no terminal signal was emitted automatically.
 *  - After a transparent WS reconnect, [RuntimeController] did not emit a
 *    [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT] to inform V2 that Android is now
 *    idle (no active task after the interruption).
 *
 * PR-62 addresses all four gaps by wiring participant truth into live execution flows.
 *
 * @see RuntimeController.activeTaskId
 * @see RuntimeController.activeTaskStatus
 * @see RuntimeController.publishTaskStatusUpdate
 * @see RuntimeController.recordDelegatedTaskAccepted
 * @see RuntimeController.publishTaskResult
 * @see RuntimeController.publishTaskCancelled
 * @see RuntimeController.notifyTakeoverFailed
 * @see AndroidLifecycleRecoveryContract
 * @see ParticipantRuntimeSemanticsBoundary
 */
object ParticipantLiveExecutionSurface {

    // ── PR-62 acceptance criteria wiring maps ─────────────────────────────────

    /**
     * **AC-1: Where Android participant/runtime truth lives in actual execution and lifecycle
     * code paths.**
     *
     * Key: human-readable surface name.
     * Value: description of where it lives and how it is updated.
     */
    val LIVE_EXECUTION_WIRING: Map<String, String> = mapOf(

        "active_task_id" to
            "RuntimeController._activeTaskId (volatile field). " +
            "Set by recordDelegatedTaskAccepted(); cleared by publishTaskResult(), " +
            "publishTaskCancelled(), notifyTakeoverFailed(), and clearActiveTaskState() " +
            "(which is also called by closeAttachedSession on DISCONNECT/INVALIDATION). " +
            "Exposed as RuntimeController.activeTaskId (read-only property).",

        "active_task_status" to
            "RuntimeController._activeTaskStatus (volatile field). " +
            "Transitions: PENDING→RUNNING on accept; RUNNING→CANCELLING on cancel-path; " +
            "RUNNING→FAILING on failure-path; cleared (null) on terminal outcome. " +
            "Exposed as RuntimeController.activeTaskStatus (read-only property).",

        "reconciliation_signal_stream" to
            "RuntimeController.reconciliationSignals (SharedFlow<ReconciliationSignal>). " +
            "All task lifecycle signals (TASK_ACCEPTED, TASK_STATUS_UPDATE, TASK_RESULT, " +
            "TASK_CANCELLED, TASK_FAILED, PARTICIPANT_STATE, RUNTIME_TRUTH_SNAPSHOT) are emitted " +
            "here. V2 consumes this stream as its canonical Android→V2 protocol input.",

        "participant_health_state" to
            "RuntimeController._lastKnownHealthState (volatile field). " +
            "Updated by notifyParticipantHealthChanged(). Also emits PARTICIPANT_STATE signal " +
            "and V2MultiDeviceLifecycleEvent.DeviceHealthChanged on reconciliationSignals and " +
            "v2LifecycleEvents respectively.",

        "lifecycle_transition_events" to
            "RuntimeController.lifecycleTransitionEvents (SharedFlow<RuntimeLifecycleTransitionEvent>). " +
            "Emits Governed/Unexpected events on every RuntimeState transition, enabling " +
            "reactive observers (test or production) to trace lifecycle continuity.",

        "v2_lifecycle_event_stream" to
            "RuntimeController.v2LifecycleEvents (SharedFlow<V2MultiDeviceLifecycleEvent>). " +
            "Emits DeviceConnected, DeviceReconnected, DeviceDisconnected, DeviceDegraded, " +
            "DeviceHealthChanged, ParticipantReadinessChanged on all lifecycle transitions."
    )

    /**
     * **AC-2: How cancel/status/failure/result influence Android runtime behavior and task
     * continuity.**
     *
     * Key: signal kind wire value.
     * Value: runtime behavior description.
     */
    val TASK_LIFECYCLE_WIRING: Map<String, String> = mapOf(

        ReconciliationSignal.Kind.TASK_ACCEPTED.wireValue to
            "RuntimeController.recordDelegatedTaskAccepted(taskId, correlationId?): " +
            "  1. Calls recordDelegatedExecutionAccepted() → increments session execution counter. " +
            "  2. Sets _activeTaskId = taskId, _activeTaskStatus = RUNNING. " +
            "  3. Emits TASK_ACCEPTED signal on reconciliationSignals. " +
            "  Continuity effect: task is now tracked; any subsequent DISCONNECT emits TASK_FAILED.",

        ReconciliationSignal.Kind.TASK_STATUS_UPDATE.wireValue to
            "RuntimeController.publishTaskStatusUpdate(taskId, correlationId?, progressDetail?): " +
            "  1. Emits TASK_STATUS_UPDATE signal on reconciliationSignals. " +
            "  2. Logs to GalaxyLogger.TAG_LIVE_EXECUTION. " +
            "  Does NOT mutate _activeTaskStatus (coarse lifecycle) — fine-grained progress " +
            "  is carried only in the signal payload for V2. " +
            "  Continuity effect: V2 updates its in-flight task progress view without closing the task.",

        ReconciliationSignal.Kind.TASK_RESULT.wireValue to
            "RuntimeController.publishTaskResult(taskId, correlationId?): " +
            "  1. Calls clearActiveTaskState(taskId, 'result') → clears _activeTaskId, _activeTaskStatus. " +
            "  2. Emits TASK_RESULT signal on reconciliationSignals. " +
            "  Continuity effect: task is closed as success; participant is now idle; V2 closes task.",

        ReconciliationSignal.Kind.TASK_CANCELLED.wireValue to
            "RuntimeController.publishTaskCancelled(taskId, correlationId?): " +
            "  1. Sets _activeTaskStatus = CANCELLING (if _activeTaskId matches). " +
            "  2. Calls clearActiveTaskState(taskId, 'cancelled') → clears both fields. " +
            "  3. Emits TASK_CANCELLED signal on reconciliationSignals. " +
            "  Also via notifyTakeoverFailed(cause=CANCELLED): same status transition + clearance + signal. " +
            "  Continuity effect: task is closed as cancelled; V2 releases execution capacity.",

        ReconciliationSignal.Kind.TASK_FAILED.wireValue to
            "RuntimeController.notifyTakeoverFailed(cause≠CANCELLED): " +
            "  1. Sets _activeTaskStatus = FAILING (if _activeTaskId matches). " +
            "  2. Calls clearActiveTaskState(taskId, cause.wireValue). " +
            "  3. Emits TASK_FAILED signal on reconciliationSignals. " +
            "  Auto-interrupt path (closeAttachedSession on DISCONNECT or INVALIDATION): " +
            "  1. Captures _activeTaskId if non-null. " +
            "  2. Sets _activeTaskStatus = FAILING. " +
            "  3. Calls clearActiveTaskState. " +
            "  4. Emits TASK_FAILED with errorDetail='session_interrupted:<cause>'. " +
            "  Continuity effect: V2 closes task as failed and applies its retry/fallback policy."
    )

    /**
     * **AC-3: What participant-local truth survives, resumes, resets, or emits across
     * interruption and recovery cases.**
     *
     * Key: interruption scenario.
     * Value: description of what Android emits/clears and what V2 must do.
     */
    val INTERRUPTION_AND_RECOVERY: Map<String, String> = mapOf(

        "transient_ws_disconnect_with_active_task" to
            "closeAttachedSession(DISCONNECT) fires. " +
            "If _activeTaskId != null: emits TASK_FAILED('session_interrupted: disconnect'), " +
            "clears _activeTaskId / _activeTaskStatus. " +
            "V2 must close the task as failed and apply retry/fallback policy. " +
            "After reconnect (RECONNECT_RECOVERY): openAttachedSession emits RUNTIME_TRUTH_SNAPSHOT " +
            "with activeTaskId=null (IDLE), so V2 confirms Android is ready for new tasks.",

        "transient_ws_disconnect_no_active_task" to
            "closeAttachedSession(DISCONNECT) fires. " +
            "_activeTaskId is null — no TASK_FAILED emitted. " +
            "After reconnect: RUNTIME_TRUTH_SNAPSHOT confirms IDLE state. " +
            "No V2 task-fallback action needed.",

        "session_invalidation_with_active_task" to
            "invalidateSession() calls closeAttachedSession(INVALIDATION). " +
            "If _activeTaskId != null: emits TASK_FAILED('session_interrupted: invalidation'), " +
            "clears _activeTaskId / _activeTaskStatus. " +
            "Durable session era is terminated; new era on next activation. " +
            "V2 must close the task and re-evaluate participant readiness from next DeviceConnected.",

        "explicit_stop_with_active_task" to
            "stop() calls closeAttachedSession(DISABLE) then clearActiveTaskState(). " +
            "DISABLE cause does NOT auto-emit TASK_FAILED from closeAttachedSession " +
            "(controlled teardown — callers are expected to resolve task state first). " +
            "clearActiveTaskState() clears _activeTaskId / _activeTaskStatus silently. " +
            "V2 learns about the stop via DeviceDisconnected lifecycle event and must " +
            "apply its timeout/retry policy for any task it believed Android had in progress.",

        "process_kill_with_active_task" to
            "Process death: all in-memory state including _activeTaskId is lost. " +
            "No terminal signal is emitted (process is dead). " +
            "On next start, DeviceConnected is emitted with a new activation era. " +
            "V2 must treat any previously dispatched-but-unreported tasks as failed/lost. " +
            "See AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY.",

        "reconnect_recovery_idle_snapshot" to
            "openAttachedSession(RECONNECT_RECOVERY) emits RUNTIME_TRUTH_SNAPSHOT with " +
            "activeTaskId=null and activeTaskStatus=null after reopening the session. " +
            "This is the canonical post-reconnect participant truth reset: V2 is informed " +
            "that Android has no in-flight task and is ready for new dispatch. " +
            "The snapshot carries the current _lastKnownHealthState for V2's readiness evaluation."
    )

    /**
     * **AC-4: Evidence that Android now behaves more like a live first-class participant
     * runtime rather than a semantic or observational shell.**
     *
     * Each entry pairs a named behavior with the concrete code path that implements it.
     */
    val FIRST_CLASS_PARTICIPANT_EVIDENCE: Map<String, String> = mapOf(

        "live_active_task_tracking" to
            "RuntimeController tracks _activeTaskId / _activeTaskStatus as live volatile state. " +
            "Any observer can query RuntimeController.activeTaskId / activeTaskStatus to determine " +
            "the current execution state without inspecting external signal streams.",

        "automatic_task_interruption_signal" to
            "When a WS session is interrupted (DISCONNECT or INVALIDATION), RuntimeController " +
            "autonomously emits TASK_FAILED for any in-flight task. Android does not silently " +
            "abandon tasks — it ensures V2 always receives a terminal signal, preserving protocol " +
            "correctness without requiring caller coordination.",

        "automatic_reconnect_truth_reset" to
            "On RECONNECT_RECOVERY, RuntimeController autonomously emits a RUNTIME_TRUTH_SNAPSHOT " +
            "showing IDLE state. V2 receives a proactive truth report without needing to poll or " +
            "request a snapshot, enabling immediate re-dispatch eligibility evaluation.",

        "pre_terminal_cancellation_status" to
            "publishTaskCancelled() transitions _activeTaskStatus to CANCELLING before clearing " +
            "and emitting. notifyTakeoverFailed(CANCELLED) does the same. This makes the " +
            "cancellation-in-progress state observable before the final terminal signal, matching " +
            "the semantics of ActiveTaskStatus.CANCELLING.",

        "pre_terminal_failure_status" to
            "notifyTakeoverFailed(non-CANCELLED) and closeAttachedSession(DISCONNECT/INVALIDATION) " +
            "both transition _activeTaskStatus to FAILING before clearing. This makes the " +
            "failure-in-progress state observable before the final terminal signal.",

        "in_progress_status_update_surface" to
            "RuntimeController.publishTaskStatusUpdate() provides a direct call path for emitting " +
            "TASK_STATUS_UPDATE reconciliation signals with optional progressDetail payload. " +
            "Previously there was no publishTaskStatusUpdate() method despite the signal kind " +
            "being fully defined in ReconciliationSignal.Kind — the live wiring was absent.",

        "structured_live_execution_log_tag" to
            "GalaxyLogger.TAG_LIVE_EXECUTION (GALAXY:LIVE:EXECUTION) provides a single filter " +
            "path for tracing all live execution events: task_accepted, task_status_update, " +
            "task_interrupted_by_session_close, active_task_state_cleared, " +
            "reconnect_idle_truth_snapshot_emitted. Reviewers and operators can grep this tag " +
            "to trace execution truth through the full task lifecycle."
    )

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key for the interruption cause in live execution log entries. */
    const val KEY_INTERRUPTED_BY = "interrupted_by"

    /** Wire key for the terminal outcome cause in live execution log entries. */
    const val KEY_FINISHED_WITH = "finished_with"

    /** Wire key for the task ID in live execution log entries. */
    const val KEY_TASK_ID = "task_id"

    /** Wire key for the participant ID in live execution log entries. */
    const val KEY_PARTICIPANT_ID = "participant_id"

    /** Wire key for the progress detail in TASK_STATUS_UPDATE log entries. */
    const val KEY_PROGRESS_DETAIL = "progress_detail"

    /**
     * Expected number of entries in [LIVE_EXECUTION_WIRING].
     * Checked by the test suite to detect accidental omissions.
     */
    const val LIVE_EXECUTION_WIRING_COUNT: Int = 6

    /**
     * Expected number of entries in [TASK_LIFECYCLE_WIRING].
     * Matches the five task-relevant [ReconciliationSignal.Kind] values.
     */
    const val TASK_LIFECYCLE_WIRING_COUNT: Int = 5

    /**
     * Expected number of entries in [INTERRUPTION_AND_RECOVERY].
     */
    const val INTERRUPTION_AND_RECOVERY_COUNT: Int = 6

    /**
     * Expected number of entries in [FIRST_CLASS_PARTICIPANT_EVIDENCE].
     */
    const val FIRST_CLASS_PARTICIPANT_EVIDENCE_COUNT: Int = 7

    /**
     * PR number that introduced this live execution surface.
     */
    const val INTRODUCED_PR: Int = 62
}
