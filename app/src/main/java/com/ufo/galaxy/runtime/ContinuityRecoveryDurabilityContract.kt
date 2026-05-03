package com.ufo.galaxy.runtime

/**
 * PR-5Android (Android) — Continuity recovery and offline durability hardening contract.
 *
 * [ContinuityRecoveryDurabilityContract] is the **canonical validation matrix** for the
 * Android-side continuity recovery and offline durability hardening work.  It documents,
 * in a machine-readable and test-verifiable form:
 *
 *  1. Which Android continuity/recovery behaviors are **now covered** (provable by tests).
 *  2. Which participant-side emissions are **bounded** after disruption (stale/duplicate
 *     signals are suppressed, not just structurally defined).
 *  3. Which Android-side durability items **remain deferred** to later PRs.
 *
 * ## Purpose
 *
 * The PR-4 (Android) and prior PRs established the structural scaffolding for Android-side
 * recovery — [AndroidRecoveryParticipationOwner], [AndroidContinuityIntegration],
 * [LocalRecoveryDecision], [EmittedSignalLedger], [DelegatedFlowContinuityRecord], and
 * [AndroidLifecycleRecoveryContract].  However, the "proof of closure" — evidence that
 * the behaviors close correctly under real disruption scenarios — was not yet collected
 * in one reviewable place.
 *
 * This contract object addresses that gap by providing:
 *  - A [CoveredBehavior] registry listing all recovery behaviors now provable by test.
 *  - A [BoundedEmission] registry listing all emission-suppression rules now enforced.
 *  - A [DeferredItem] registry listing remaining gaps with explicit rationale.
 *  - Helper query methods for audit and test assertion.
 *
 * ## V2 authority boundary
 *
 * Android is a **recovery participant**, not a recovery coordinator.  Every entry in this
 * contract reflects Android's _participant-side_ behavior only.  V2 remains the
 * authoritative source for:
 *  - Session/task resumption decisions.
 *  - Re-dispatch for interrupted or in-flight tasks.
 *  - Barrier and merge coordination state.
 *  - Canonical participant truth.
 *
 * @see AndroidRecoveryParticipationOwner
 * @see AndroidContinuityIntegration
 * @see EmittedSignalLedger
 * @see AndroidLifecycleRecoveryContract
 */
object ContinuityRecoveryDurabilityContract {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this contract. */
    const val INTRODUCED_PR = 66   // sequential PR number for PR-5Android in the combined series

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE =
        "Harden Android continuity recovery and offline durability behavior"

    // ── CoveredBehavior ───────────────────────────────────────────────────────

    /**
     * A single Android-side recovery behavior that is now covered by tests.
     *
     * @property behaviorId   Stable identifier used in test assertions and audit queries.
     * @property description  Human-readable description of the recovery behavior.
     * @property coveredBy    Classname(s) or method name(s) of the component that implements
     *                        this behavior.
     * @property testEvidence Simple description of the test(s) that prove this behavior.
     */
    data class CoveredBehavior(
        val behaviorId: String,
        val description: String,
        val coveredBy: String,
        val testEvidence: String
    )

    /**
     * All Android-side recovery behaviors now covered by concrete tests.
     *
     * A reviewer can use this list to determine with evidence how Android restores or
     * bounds in-flight participant/runtime work after restart/reconnect.
     */
    val coveredBehaviors: List<CoveredBehavior> = listOf(

        CoveredBehavior(
            behaviorId = "process_recreation_with_prior_context",
            description = "After Android process recreation, if a persisted " +
                "DelegatedFlowContinuityRecord is available, AndroidRecoveryParticipationOwner " +
                "returns RehydrateThenContinue — Android presents the rehydrated context to V2 " +
                "and MUST NOT self-authorize continuation.",
            coveredBy = "AndroidRecoveryParticipationOwner.evaluateRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "process recreation with context yields RehydrateThenContinue"
        ),

        CoveredBehavior(
            behaviorId = "process_recreation_without_prior_context",
            description = "After Android process recreation, if no persisted flow record is " +
                "available, AndroidRecoveryParticipationOwner returns WaitForV2ReplayDecision — " +
                "Android defers entirely to V2 for re-dispatch.",
            coveredBy = "AndroidRecoveryParticipationOwner.evaluateRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "process recreation without context yields WaitForV2ReplayDecision"
        ),

        CoveredBehavior(
            behaviorId = "transport_reconnect_defers_to_v2",
            description = "After a WS transport reconnect (same durable session era), " +
                "AndroidRecoveryParticipationOwner returns WaitForV2ReplayDecision — Android " +
                "waits for V2 to decide whether to replay, resume, or start fresh.",
            coveredBy = "AndroidRecoveryParticipationOwner.evaluateRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "transport reconnect yields WaitForV2ReplayDecision with durableSessionId"
        ),

        CoveredBehavior(
            behaviorId = "receiver_pipeline_rebind_resumes_locally",
            description = "After a receiver / pipeline rebind (session intact, component " +
                "re-binding only), AndroidRecoveryParticipationOwner returns " +
                "ResumeLocalExecution — Android may resume locally without waiting for V2.",
            coveredBy = "AndroidRecoveryParticipationOwner.evaluateRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "receiver pipeline rebind yields ResumeLocalExecution"
        ),

        CoveredBehavior(
            behaviorId = "duplicate_recovery_suppression",
            description = "When a recovery attempt key is already in-progress, " +
                "AndroidRecoveryParticipationOwner returns SuppressDuplicateLocalRecovery — " +
                "the duplicate attempt is dropped without entering the execution pipeline.",
            coveredBy = "AndroidRecoveryParticipationOwner.suppressDuplicateLocalRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "duplicate recovery attempt is suppressed"
        ),

        CoveredBehavior(
            behaviorId = "emit_ledger_terminal_bounding_ack",
            description = "After a terminal RESULT signal is recorded in EmittedSignalLedger, " +
                "replayBounded() returns null for Kind.ACK — preventing stale pre-terminal " +
                "ACK replay toward V2 after execution has already completed.",
            coveredBy = "EmittedSignalLedger.replayBounded",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "replayBounded suppresses ACK after terminal RESULT"
        ),

        CoveredBehavior(
            behaviorId = "emit_ledger_terminal_bounding_progress",
            description = "After a terminal RESULT signal is recorded in EmittedSignalLedger, " +
                "replayBounded() returns null for Kind.PROGRESS — preventing stale mid-execution " +
                "PROGRESS replay toward V2 after execution has already completed.",
            coveredBy = "EmittedSignalLedger.replayBounded",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "replayBounded suppresses PROGRESS after terminal RESULT"
        ),

        CoveredBehavior(
            behaviorId = "emit_ledger_result_replay_idempotent",
            description = "After a terminal RESULT signal is recorded in EmittedSignalLedger, " +
                "replayBounded() still returns the RESULT replay — replaying the terminal result " +
                "is safe and idempotent (same signalId preserved).",
            coveredBy = "EmittedSignalLedger.replayBounded",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "replayBounded returns RESULT after terminal state"
        ),

        CoveredBehavior(
            behaviorId = "offline_queue_stale_session_discard",
            description = "OfflineTaskQueue.discardForDifferentSession() removes messages " +
                "tagged with a session that no longer matches the current authority tag — " +
                "preventing late offline queue flush after session/authority has changed.",
            coveredBy = "OfflineTaskQueue.discardForDifferentSession",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "discardForDifferentSession purges stale-tagged messages"
        ),

        CoveredBehavior(
            behaviorId = "offline_queue_null_tag_preserved",
            description = "OfflineTaskQueue.discardForDifferentSession() does NOT discard " +
                "messages with a null sessionTag — backward-compatible callers that do not " +
                "use session tagging are unaffected.",
            coveredBy = "OfflineTaskQueue.discardForDifferentSession",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "discardForDifferentSession preserves null-tagged messages"
        ),

        CoveredBehavior(
            behaviorId = "offline_queue_matching_tag_preserved",
            description = "OfflineTaskQueue.discardForDifferentSession() does NOT discard " +
                "messages whose sessionTag matches the current authority tag — only stale " +
                "sessions are purged.",
            coveredBy = "OfflineTaskQueue.discardForDifferentSession",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "discardForDifferentSession preserves current-session messages"
        ),

        CoveredBehavior(
            behaviorId = "duplicate_signal_emit_suppression",
            description = "AndroidContinuityIntegration.suppressDuplicateLocalEmit() returns " +
                "true for a signalId that has already been emitted in the current era — " +
                "preventing duplicate DelegatedExecutionSignal emission after reconnect.",
            coveredBy = "AndroidContinuityIntegration.suppressDuplicateLocalEmit",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "suppressDuplicateLocalEmit blocks already-seen signalId"
        ),

        CoveredBehavior(
            behaviorId = "fresh_attach_no_recovery_context",
            description = "When there is no prior continuity context (fresh attach), " +
                "AndroidRecoveryParticipationOwner returns NoRecoveryContext — no recovery " +
                "overhead, Android starts a new execution era.",
            coveredBy = "AndroidRecoveryParticipationOwner.evaluateRecovery",
            testEvidence = "Pr66ContinuityRecoveryDurabilityTest: " +
                "fresh attach returns NoRecoveryContext"
        )
    )

    // ── BoundedEmission ───────────────────────────────────────────────────────

    /**
     * A participant-side emission rule that is now enforced after disruption.
     *
     * @property emissionId   Stable identifier for this bounded-emission rule.
     * @property description  Human-readable description of the suppression or bounding rule.
     * @property enforcedBy   Component that enforces this rule.
     * @property suppressionCondition  When the emission is suppressed / bounded.
     */
    data class BoundedEmission(
        val emissionId: String,
        val description: String,
        val enforcedBy: String,
        val suppressionCondition: String
    )

    /**
     * All participant-side emission rules now bounded after disruption.
     *
     * A reviewer can use this list to determine how stale/duplicate/offline-delayed
     * emissions are suppressed or controlled after restart, reconnect, or offline period.
     */
    val boundedEmissions: List<BoundedEmission> = listOf(

        BoundedEmission(
            emissionId = "ack_after_terminal_result",
            description = "ACK signal replay is suppressed after a terminal RESULT has been " +
                "recorded — prevents V2 from seeing an execution lifecycle appearing to " +
                "restart after it already completed.",
            enforcedBy = "EmittedSignalLedger.replayBounded",
            suppressionCondition = "hasTerminalResult == true and kind == Kind.ACK"
        ),

        BoundedEmission(
            emissionId = "progress_after_terminal_result",
            description = "PROGRESS signal replay is suppressed after a terminal RESULT has " +
                "been recorded — prevents V2 from receiving mid-execution progress signals " +
                "for an already-completed execution.",
            enforcedBy = "EmittedSignalLedger.replayBounded",
            suppressionCondition = "hasTerminalResult == true and kind == Kind.PROGRESS"
        ),

        BoundedEmission(
            emissionId = "offline_queue_stale_authority",
            description = "Offline-queued task_result/goal_result messages tagged with an " +
                "old session/authority tag are discarded before drain — prevents sending " +
                "results for a session that has since been replaced by a new activation era.",
            enforcedBy = "OfflineTaskQueue.discardForDifferentSession",
            suppressionCondition = "sessionTag != null and sessionTag != currentTag before drainAll"
        ),

        BoundedEmission(
            emissionId = "duplicate_signal_cross_execution",
            description = "DelegatedExecutionSignal with a signalId already seen in the " +
                "current era is suppressed by the cross-execution idempotency guard — " +
                "prevents duplicate signal delivery on reconnect or rebind.",
            enforcedBy = "AndroidContinuityIntegration.suppressDuplicateLocalEmit",
            suppressionCondition = "signalId already present in _emittedSignalIds seen-set"
        ),

        BoundedEmission(
            emissionId = "duplicate_recovery_attempt",
            description = "A recovery attempt for a key already in-progress is suppressed — " +
                "prevents concurrent recovery sequences for the same flow or session from " +
                "both entering the execution pipeline.",
            enforcedBy = "AndroidRecoveryParticipationOwner.suppressDuplicateLocalRecovery",
            suppressionCondition = "recoveryAttemptKey already present in _inProgressAttemptKeys"
        ),

        BoundedEmission(
            emissionId = "stale_identity_reception",
            description = "Inbound delegated unit carrying a session ID that does not match " +
                "the current active session is rejected — prevents a stale or replayed " +
                "envelope from being accepted after session change.",
            enforcedBy = "AndroidContinuityIntegration.validateRuntimeIdentity / RejectStaleIdentity",
            suppressionCondition = "inbound sessionId != current activeSession.sessionId"
        ),

        BoundedEmission(
            emissionId = "reconciliation_signal_session_epoch_bounding",
            description = "Outbound ReconciliationSignal emissions carry durable_session_id and " +
                "session_continuity_epoch from the active durable session record, allowing V2 " +
                "to reject late signals from a prior reconnect epoch.",
            enforcedBy = "RuntimeController current durable record → ReconciliationSignal → " +
                "ReconciliationSignalPayload",
            suppressionCondition = "V2 ingestion compares durable_session_id and " +
                "session_continuity_epoch against the current participant session era"
        )
    )

    // ── DeferredItem ──────────────────────────────────────────────────────────

    /**
     * An Android-side continuity/durability item that is explicitly deferred to a later PR.
     *
     * @property itemId         Stable identifier for the deferred item.
     * @property description    What has been deferred and why.
     * @property deferralReason Rationale for deferring (e.g. "requires V2-side coordination").
     * @property deferredTo     Target phase or PR where this item is expected to land.
     */
    data class DeferredItem(
        val itemId: String,
        val description: String,
        val deferralReason: String,
        val deferredTo: String
    )

    /**
     * Android-side continuity/recovery items explicitly deferred to later PRs.
     *
     * A reviewer can use this list to understand what Android-side durability work remains,
     * why it is deferred, and what V2-side coordination is required before it can land.
     */
    val deferredItems: List<DeferredItem> = listOf(

        DeferredItem(
            itemId = "offline_queue_durable_session_tag_persistence",
            description = "OfflineTaskQueue.QueuedMessage.sessionTag is not persisted to " +
                "SharedPreferences in the current implementation — the tag is serialised in " +
                "the Gson JSON but the deserialization path does not yet validate that " +
                "restored messages carry a session tag that matches the current era on load.",
            deferralReason = "Requires a coordinated load-time eviction pass alongside the " +
                "existing MAX_AGE_MS eviction.  Low risk: process-restart scenarios also " +
                "clear the in-memory DurableSessionContinuityRecord, so callers should " +
                "call discardForDifferentSession() after the new session is established.",
            deferredTo = "Later release-hardening PR once V2-side session continuity token " +
                "propagation to Android is fully wired."
        ),

        DeferredItem(
            itemId = "emit_ledger_cross_process_persistence",
            description = "EmittedSignalLedger is in-memory only — seen-set and terminal " +
                "result state are lost on process recreation.  Replay bounding (replayBounded) " +
                "is therefore only effective within a single process lifetime.",
            deferralReason = "Cross-process signal idempotency requires persisting the ledger " +
                "to disk (e.g. alongside DelegatedFlowContinuityRecord), which requires " +
                "store design and V2-side duplicate-detection validation first.",
            deferredTo = "Post-release hardening PR after DelegatedFlowContinuityStore " +
                "schema stabilises and V2 confirms its own duplicate-detection policy."
        ),

        DeferredItem(
            itemId = "takeover_recovery_path_explicit_bounding",
            description = "TakeoverFallbackEvent-driven recovery paths do not yet have " +
                "explicit session-authority bounding equivalent to the main delegated " +
                "execution path.  Takeover fallback currently relies on the general " +
                "AndroidCompatLegacyBlockingParticipant gate rather than a dedicated " +
                "post-reconnect authority check.",
            deferralReason = "Takeover recovery requires V2-side takeover authority model " +
                "(V2 PR companion) to be stable before Android can bind its bounding logic " +
                "to explicit V2 takeover session identifiers.",
            deferredTo = "V2 PR-2 companion work once V2 takeover authority surface is " +
                "published and Android can reference a stable takeover session wire contract."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns the [CoveredBehavior] with [behaviorId], or `null` if not found. */
    fun coveredBehaviorFor(behaviorId: String): CoveredBehavior? =
        coveredBehaviors.firstOrNull { it.behaviorId == behaviorId }

    /** Returns the [BoundedEmission] with [emissionId], or `null` if not found. */
    fun boundedEmissionFor(emissionId: String): BoundedEmission? =
        boundedEmissions.firstOrNull { it.emissionId == emissionId }

    /** Returns the [DeferredItem] with [itemId], or `null` if not found. */
    fun deferredItemFor(itemId: String): DeferredItem? =
        deferredItems.firstOrNull { it.itemId == itemId }

    // ── Count constants for test assertions ───────────────────────────────────

    /** Expected number of covered behaviors at the time of this PR. */
    const val COVERED_BEHAVIOR_COUNT = 13

    /** Expected number of bounded emissions at the time of this PR. */
    const val BOUNDED_EMISSION_COUNT = 7

    /** Expected number of deferred items at the time of this PR. */
    const val DEFERRED_ITEM_COUNT = 3
}
