package com.ufo.galaxy.runtime

/**
 * PR-7B — Android truth publication semantics for hardened V2 governance consumption.
 *
 * Aligns Android-side truth publication semantics with V2's hardened governance expectations
 * so that missing or unverifiable Android state is never represented as implicitly healthy.
 *
 * ## Problem addressed
 *
 * Prior PRs established rich semantic classification for reported state
 * ([AndroidCanonicalRuntimeTruthContract]) and execution lifecycle phases
 * ([AndroidExecutionLifecycleContract]).  Despite this, a critical gap remained:
 *
 *  1. **Optimistic-default risk**: When Android emits a snapshot with null or absent fields,
 *     V2 has no contract-level signal to distinguish "this field is not applicable here" from
 *     "Android genuinely does not know the value of this field".  Without an explicit evidence
 *     quality label, V2's dispatch scorer may treat absent evidence as implicitly healthy, which
 *     can lead to over-eager dispatch to devices that are not in a confirmed-ready state.
 *
 *  2. **Missing/unknown state not explicit**: Android startup paths (Starting, Recovering,
 *     FailedStartup) all emit snapshots, but nothing in the wire payload explicitly marks these
 *     as "evidence is not yet available" vs "evidence confirms capability".
 *
 *  3. **Partial evidence conflated with full evidence**: When only the planner subsystem is
 *     ready but grounding is not, Android may still emit a snapshot that V2 interprets as
 *     a full capability declaration.
 *
 *  4. **Failed evidence not distinguished from missing evidence**: A device that failed its
 *     warmup (FailedStartup) and a device that has not yet started (Starting) both produce
 *     snapshots with no positive capability confirmation, but only one of them represents an
 *     explicit failure.  V2's recovery policy differs for these two cases.
 *
 * ## What this contract introduces
 *
 *  1. [EvidencePresenceKind] — the 6-class vocabulary for classifying the quality and
 *     completeness of the evidence backing an Android truth publication.
 *  2. [classifySnapshotEvidencePresence] — classifier for capability/state snapshots:
 *     derives [EvidencePresenceKind] from runtime manager state, warmup result, readiness
 *     flags, and offline queue depth.
 *  3. [classifyEventEvidencePresence] — classifier for execution events: derives
 *     [EvidencePresenceKind] from the execution phase being reported.
 *  4. [CLOSURE_INVARIANTS] — machine-verifiable invariants that must all be `true`; any
 *     `false` value indicates a regression in this contract.
 *
 * ## Wire alignment
 *
 * | New field              | Payload                     | Wire key                |
 * |------------------------|-----------------------------|-------------------------|
 * | evidence_presence_kind | DeviceStateSnapshotPayload  | "evidence_presence_kind" |
 * | evidence_presence_kind | DeviceExecutionEventPayload | "evidence_presence_kind" |
 *
 * ## V2 consumption model
 *
 * V2's capability ingestion and dispatch pipeline MUST:
 *  1. Read `evidence_presence_kind` from every incoming Android snapshot and event.
 *  2. Only apply standard dispatch scoring when `evidence_presence_kind == "positive_evidence"`.
 *  3. For `"unknown"` or `"partial"`: withhold or limit dispatch to confirmed subsystems only.
 *  4. For `"unavailable"`: block dispatch that depends on the reported capability surface.
 *  5. For `"delayed"`: apply staleness and timeout budget extensions.
 *  6. For `"failed_observation"`: apply recovery/retry policy; MUST NOT assume healthy state.
 *
 * ## Relationship to prior contracts
 *
 * | Contract                                      | Scope                                           |
 * |-----------------------------------------------|-------------------------------------------------|
 * | [AndroidCanonicalRuntimeTruthContract]        | Semantic class of each reported state surface   |
 * | [AndroidExecutionLifecycleContract]           | Execution lifecycle phase state machine         |
 * | [ExecutionUplinkDiscipline]                   | When to emit result/state uplinks               |
 * | [AndroidTruthPublicationSemanticsContract] (this) | Evidence quality of each truth publication  |
 *
 * @see AndroidCanonicalRuntimeTruthContract
 * @see AndroidExecutionLifecycleContract
 * @see com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
 * @see com.ufo.galaxy.protocol.DeviceExecutionEventPayload
 */
object AndroidTruthPublicationSemanticsContract {

    /** PR number that introduces this contract. */
    const val INTRODUCED_PR = "PR-7B"

    // ── Evidence presence kind ────────────────────────────────────────────────

    /**
     * Classification of the evidence quality backing an Android truth publication.
     *
     * Every [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] and
     * [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] carries exactly one
     * [EvidencePresenceKind] in the `evidence_presence_kind` wire field.
     *
     * V2's canonical truth reducer MUST use this class to determine the dispatch and
     * governance policy to apply for each incoming Android truth publication:
     *
     * | Kind                 | V2 governance action                                          |
     * |----------------------|---------------------------------------------------------------|
     * | POSITIVE_EVIDENCE    | Apply standard dispatch and capability scoring                |
     * | UNKNOWN              | Withhold dispatch; evidence not yet available                 |
     * | UNAVAILABLE          | Block dispatch requiring this surface; capability absent      |
     * | DELAYED              | Apply staleness penalty; extend timeout budgets               |
     * | PARTIAL              | Limit dispatch to confirmed subsystems only                   |
     * | FAILED_OBSERVATION   | Apply recovery policy; MUST NOT assume healthy state          |
     *
     * @property wireValue           Stable wire string emitted in the `evidence_presence_kind` field.
     * @property displayName         Short human-readable label.
     * @property description         One-sentence semantic description.
     * @property isPositiveEvidence  True only for [POSITIVE_EVIDENCE]; V2 applies standard dispatch.
     * @property v2GovernanceHint    The canonical V2 governance action for this evidence kind.
     */
    enum class EvidencePresenceKind(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isPositiveEvidence: Boolean,
        val v2GovernanceHint: String
    ) {

        /**
         * Android has directly observed and verified this state.  All key fields in the
         * publication are backed by fresh, live Android runtime state at emission time.
         *
         * V2 MUST apply standard dispatch and capability scoring.
         *
         * Preconditions for capability snapshots: managerState is Running, warmup_result="ok",
         * key readiness signals are non-null, offline_queue_depth=0.
         *
         * Preconditions for execution events: phase is COMPLETED, CANCELLED, or an in-progress
         * phase with live runtime backing (EXECUTION_STARTED, EXECUTION_PROGRESS).
         */
        POSITIVE_EVIDENCE(
            wireValue = "positive_evidence",
            displayName = "Positive Evidence",
            description = "Android has directly observed and verified this state; V2 applies standard dispatch policy.",
            isPositiveEvidence = true,
            v2GovernanceHint = "apply_standard_dispatch_policy"
        ),

        /**
         * Android cannot determine the reported state.  Absence of evidence MUST NOT be
         * interpreted by V2 as implicit confirmation of healthy state.
         *
         * V2 MUST withhold dispatch pending evidence arrival or an explicit state transition.
         *
         * Preconditions for capability snapshots: managerState is Starting, Recovering, or
         * key readiness signals are null; warmup_result="not_started" or null.
         *
         * Preconditions for execution events: phase is EXECUTION_STARTED before any
         * runtime confirmation, or INTERRUPTED (outcome uncertain after interruption).
         */
        UNKNOWN(
            wireValue = "unknown",
            displayName = "Unknown",
            description = "Android cannot determine the reported state; V2 must not interpret as implicitly healthy.",
            isPositiveEvidence = false,
            v2GovernanceHint = "withhold_dispatch_pending_evidence"
        ),

        /**
         * Android has determined that the reported state or capability is explicitly
         * inaccessible.  This is not the same as unknown: Android **knows** the capability
         * is absent, e.g. model not yet downloaded.
         *
         * V2 MUST block dispatch that depends on this capability surface until a subsequent
         * [POSITIVE_EVIDENCE] publication signals availability.
         *
         * Preconditions for capability snapshots: pending_first_download=true; no model
         * has been successfully downloaded.
         *
         * Preconditions for execution events: phase is FAILED with an explicit unavailability
         * reason (no model, inference service unreachable).
         */
        UNAVAILABLE(
            wireValue = "unavailable",
            displayName = "Unavailable",
            description = "Android has determined the state or capability is explicitly inaccessible; V2 must block dependent dispatch.",
            isPositiveEvidence = false,
            v2GovernanceHint = "block_dependent_dispatch"
        ),

        /**
         * Evidence exists but may be stale.  Android is operating from a cached or
         * offline-queued state rather than live runtime confirmation.
         *
         * V2 MUST apply staleness penalties and extend timeout budgets rather than treating
         * this publication as fresh live state.
         *
         * Preconditions for capability snapshots: offline_queue_depth > 0; execution mode
         * is transitioning or tasks are queued for replay.
         *
         * Preconditions for execution events: event was queued offline and is being replayed.
         */
        DELAYED(
            wireValue = "delayed",
            displayName = "Delayed",
            description = "Evidence exists but may be stale (offline queue or cached state); V2 must apply staleness penalty.",
            isPositiveEvidence = false,
            v2GovernanceHint = "apply_staleness_penalty_extend_timeout"
        ),

        /**
         * Only partial evidence is available.  Some subsystems can be confirmed as ready
         * but others cannot; V2 MUST NOT assume full capability from partial evidence.
         *
         * V2 MUST limit dispatch to confirmed subsystems only and must not schedule tasks
         * that require the unconfirmed subsystem.
         *
         * Preconditions for capability snapshots: planner_ready != grounding_ready (one
         * subsystem is up while the other is down or unknown).
         *
         * Preconditions for execution events: phase is FALLBACK_TRANSITION (primary path
         * unavailable; running on fallback).
         */
        PARTIAL(
            wireValue = "partial",
            displayName = "Partial",
            description = "Only partial evidence is available; V2 must not assume full capability from partial confirmation.",
            isPositiveEvidence = false,
            v2GovernanceHint = "limit_dispatch_to_confirmed_subsystems"
        ),

        /**
         * Android's attempt to observe or verify the reported state failed.
         *
         * This is distinct from [UNKNOWN]: [UNKNOWN] means Android has not yet had a chance
         * to observe the state; [FAILED_OBSERVATION] means Android attempted to observe it
         * and the attempt failed (e.g. warmup failed, model verification failed).
         *
         * V2 MUST apply recovery and retry policy and MUST NOT assume healthy state.
         *
         * Preconditions for capability snapshots: managerState is Failed or FailedStartup;
         * warmup_result="failed".
         *
         * Preconditions for execution events: phase is FAILED or STAGNATION_DETECTED with
         * a runtime error or stagnation cause.
         */
        FAILED_OBSERVATION(
            wireValue = "failed_observation",
            displayName = "Failed Observation",
            description = "Evidence collection attempt failed; V2 must apply recovery policy and must not assume healthy state.",
            isPositiveEvidence = false,
            v2GovernanceHint = "apply_recovery_policy_do_not_assume_healthy"
        );

        companion object {
            /** All stable wire values; used for wire-stability contract tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Classifier: snapshot evidence presence ────────────────────────────────

    /**
     * Derives the [EvidencePresenceKind] for a capability/state snapshot given the key
     * runtime state signals from [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload].
     *
     * **Classification priority** (first match wins):
     * 1. FAILED_OBSERVATION — if [managerStateIsFailed]=true or [warmupResult]="failed".
     * 2. UNAVAILABLE — if [pendingFirstDownload]=true (no model installed).
     * 3. UNKNOWN — if [managerStateIsStarting]=true or [warmupResult]="not_started" or
     *    [localLoopReady]=null (key signals not yet available).
     * 4. PARTIAL — if [plannerReady] != [groundingReady] (subsystem mismatch).
     * 5. DELAYED — if [offlineQueueDepth] > 0 (tasks queued for offline replay).
     * 6. POSITIVE_EVIDENCE — [managerStateIsRunning]=true and [warmupResult]="ok".
     * 7. UNKNOWN — default (recovering, transitional, or other unclassified state).
     *
     * @param warmupResult           [DeviceStateSnapshotPayload.warmup_result]
     * @param pendingFirstDownload   [DeviceStateSnapshotPayload.pending_first_download]
     * @param localLoopReady         [DeviceStateSnapshotPayload.local_loop_ready]
     * @param plannerReady           [DeviceStateSnapshotPayload.planner_ready]
     * @param groundingReady         [DeviceStateSnapshotPayload.grounding_ready]
     * @param offlineQueueDepth      [DeviceStateSnapshotPayload.offline_queue_depth]
     * @param managerStateIsRunning  True when the local inference runtime is in Running state.
     * @param managerStateIsFailed   True when the local inference runtime is in Failed or FailedStartup state.
     * @param managerStateIsStarting True when the local inference runtime is in Starting or Recovering state.
     * @return The [EvidencePresenceKind] for this snapshot publication.
     */
    fun classifySnapshotEvidencePresence(
        warmupResult: String?,
        pendingFirstDownload: Boolean?,
        localLoopReady: Boolean?,
        plannerReady: Boolean?,
        groundingReady: Boolean?,
        offlineQueueDepth: Int?,
        managerStateIsRunning: Boolean,
        managerStateIsFailed: Boolean,
        managerStateIsStarting: Boolean
    ): EvidencePresenceKind {
        // 1. Failed observation: runtime failed or warmup explicitly failed
        if (managerStateIsFailed || warmupResult == "failed") {
            return EvidencePresenceKind.FAILED_OBSERVATION
        }

        // 2. Unavailable: no model installed (pending first download)
        if (pendingFirstDownload == true) {
            return EvidencePresenceKind.UNAVAILABLE
        }

        // 3. Unknown: runtime is still starting/recovering or key signals not yet available
        if (managerStateIsStarting || warmupResult == "not_started" || localLoopReady == null) {
            return EvidencePresenceKind.UNKNOWN
        }

        // 4. Partial: one subsystem is ready while the other is not (planner/grounding mismatch)
        if (plannerReady != null && groundingReady != null && plannerReady != groundingReady) {
            return EvidencePresenceKind.PARTIAL
        }

        // 5. Delayed: offline queue has pending items (evidence present but potentially stale)
        if (offlineQueueDepth != null && offlineQueueDepth > 0) {
            return EvidencePresenceKind.DELAYED
        }

        // 6. Positive evidence: runtime is confirmed running with successful warmup
        if (managerStateIsRunning && warmupResult == "ok") {
            return EvidencePresenceKind.POSITIVE_EVIDENCE
        }

        // 7. Default: unknown (recovering, transitional, or unclassified state)
        return EvidencePresenceKind.UNKNOWN
    }

    // ── Classifier: execution event evidence presence ─────────────────────────

    /**
     * Derives the [EvidencePresenceKind] for an execution event given the
     * [phase][com.ufo.galaxy.protocol.DeviceExecutionEventPayload.phase] being reported.
     *
     * **Classification logic:**
     * - FAILED_OBSERVATION: [PHASE_FAILED] or [PHASE_STAGNATION_DETECTED] —
     *   Android directly observed a runtime or stagnation failure.
     * - PARTIAL: [PHASE_FALLBACK_TRANSITION] —
     *   primary path is unavailable; running on partial fallback path.
     * - POSITIVE_EVIDENCE: [PHASE_COMPLETED] or [PHASE_CANCELLED] or
     *   [PHASE_EXECUTION_PROGRESS] — Android has direct live observation.
     * - UNKNOWN: [PHASE_EXECUTION_STARTED] — initial event before runtime confirmation.
     * - UNKNOWN: [PHASE_TAKEOVER_MILESTONE] — transitional handoff state.
     * - UNKNOWN: default — unclassified phase.
     *
     * @param phase The [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] phase value.
     * @return The [EvidencePresenceKind] for this execution event publication.
     */
    fun classifyEventEvidencePresence(phase: String): EvidencePresenceKind = when (phase) {
        // Terminal failures: Android directly observed a failure
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_FAILED,
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED ->
            EvidencePresenceKind.FAILED_OBSERVATION

        // Fallback transition: primary path unavailable; partial evidence only
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION ->
            EvidencePresenceKind.PARTIAL

        // Positive terminal and progress events: Android has direct live observation
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_COMPLETED,
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_CANCELLED,
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS ->
            EvidencePresenceKind.POSITIVE_EVIDENCE

        // Initial start event: execution begun but no runtime confirmation yet
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED ->
            EvidencePresenceKind.UNKNOWN

        // Transitional takeover handoff: partial/uncertain state during handoff
        com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE ->
            EvidencePresenceKind.UNKNOWN

        // Default: unknown for any unclassified phase
        else -> EvidencePresenceKind.UNKNOWN
    }

    // ── Closure invariants ────────────────────────────────────────────────────

    /**
     * Machine-verifiable closure invariants for this contract.
     * All values must be `true`; any `false` indicates a regression.
     */
    val CLOSURE_INVARIANTS: Map<String, Boolean> = mapOf(

        // EvidencePresenceKind covers exactly 6 distinct evidence kinds
        "evidence_presence_kind_has_6_values" to
            (EvidencePresenceKind.ALL_WIRE_VALUES.size == 6),

        // Only POSITIVE_EVIDENCE has isPositiveEvidence=true
        "only_positive_evidence_kind_is_positive" to
            (EvidencePresenceKind.entries.count { it.isPositiveEvidence } == 1 &&
                EvidencePresenceKind.POSITIVE_EVIDENCE.isPositiveEvidence),

        // All non-positive kinds explicitly signal that V2 must not assume healthy state
        "all_non_positive_kinds_have_non_empty_governance_hint" to
            EvidencePresenceKind.entries
                .filter { !it.isPositiveEvidence }
                .all { it.v2GovernanceHint.isNotEmpty() },

        // classifySnapshotEvidencePresence: failed manager state → FAILED_OBSERVATION
        "classify_snapshot_failed_manager_yields_failed_observation" to
            (classifySnapshotEvidencePresence(
                warmupResult = "failed",
                pendingFirstDownload = false,
                localLoopReady = false,
                plannerReady = null,
                groundingReady = null,
                offlineQueueDepth = 0,
                managerStateIsRunning = false,
                managerStateIsFailed = true,
                managerStateIsStarting = false
            ) == EvidencePresenceKind.FAILED_OBSERVATION),

        // classifySnapshotEvidencePresence: pending_first_download=true → UNAVAILABLE
        "classify_snapshot_pending_download_yields_unavailable" to
            (classifySnapshotEvidencePresence(
                warmupResult = "unavailable",
                pendingFirstDownload = true,
                localLoopReady = null,
                plannerReady = null,
                groundingReady = null,
                offlineQueueDepth = 0,
                managerStateIsRunning = false,
                managerStateIsFailed = false,
                managerStateIsStarting = false
            ) == EvidencePresenceKind.UNAVAILABLE),

        // classifySnapshotEvidencePresence: running + warmup ok + no queue → POSITIVE_EVIDENCE
        "classify_snapshot_running_ready_yields_positive_evidence" to
            (classifySnapshotEvidencePresence(
                warmupResult = "ok",
                pendingFirstDownload = false,
                localLoopReady = true,
                plannerReady = true,
                groundingReady = true,
                offlineQueueDepth = 0,
                managerStateIsRunning = true,
                managerStateIsFailed = false,
                managerStateIsStarting = false
            ) == EvidencePresenceKind.POSITIVE_EVIDENCE),

        // classifySnapshotEvidencePresence: planner/grounding mismatch → PARTIAL
        "classify_snapshot_subsystem_mismatch_yields_partial" to
            (classifySnapshotEvidencePresence(
                warmupResult = "ok",
                pendingFirstDownload = false,
                localLoopReady = null,
                plannerReady = true,
                groundingReady = false,
                offlineQueueDepth = 0,
                managerStateIsRunning = true,
                managerStateIsFailed = false,
                managerStateIsStarting = false
            ) == EvidencePresenceKind.PARTIAL),

        // classifySnapshotEvidencePresence: offline queue non-empty → DELAYED
        "classify_snapshot_offline_queue_yields_delayed" to
            (classifySnapshotEvidencePresence(
                warmupResult = "ok",
                pendingFirstDownload = false,
                localLoopReady = true,
                plannerReady = true,
                groundingReady = true,
                offlineQueueDepth = 3,
                managerStateIsRunning = true,
                managerStateIsFailed = false,
                managerStateIsStarting = false
            ) == EvidencePresenceKind.DELAYED),

        // classifySnapshotEvidencePresence: runtime starting → UNKNOWN
        "classify_snapshot_starting_yields_unknown" to
            (classifySnapshotEvidencePresence(
                warmupResult = "not_started",
                pendingFirstDownload = false,
                localLoopReady = null,
                plannerReady = null,
                groundingReady = null,
                offlineQueueDepth = 0,
                managerStateIsRunning = false,
                managerStateIsFailed = false,
                managerStateIsStarting = true
            ) == EvidencePresenceKind.UNKNOWN),

        // classifyEventEvidencePresence: PHASE_COMPLETED → POSITIVE_EVIDENCE
        "classify_event_completed_yields_positive_evidence" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_COMPLETED
            ) == EvidencePresenceKind.POSITIVE_EVIDENCE),

        // classifyEventEvidencePresence: PHASE_FAILED → FAILED_OBSERVATION
        "classify_event_failed_yields_failed_observation" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_FAILED
            ) == EvidencePresenceKind.FAILED_OBSERVATION),

        // classifyEventEvidencePresence: PHASE_STAGNATION_DETECTED → FAILED_OBSERVATION
        "classify_event_stagnation_yields_failed_observation" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED
            ) == EvidencePresenceKind.FAILED_OBSERVATION),

        // classifyEventEvidencePresence: PHASE_FALLBACK_TRANSITION → PARTIAL
        "classify_event_fallback_transition_yields_partial" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION
            ) == EvidencePresenceKind.PARTIAL),

        // classifyEventEvidencePresence: PHASE_EXECUTION_STARTED → UNKNOWN
        "classify_event_execution_started_yields_unknown" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
            ) == EvidencePresenceKind.UNKNOWN),

        // classifyEventEvidencePresence: PHASE_CANCELLED → POSITIVE_EVIDENCE
        "classify_event_cancelled_yields_positive_evidence" to
            (classifyEventEvidencePresence(
                com.ufo.galaxy.protocol.DeviceExecutionEventPayload.PHASE_CANCELLED
            ) == EvidencePresenceKind.POSITIVE_EVIDENCE)
    )

    /** The canonical test class for this contract. */
    const val TEST_CLASS = "Pr7BAndroidTruthPublicationSemanticsTest"
}
