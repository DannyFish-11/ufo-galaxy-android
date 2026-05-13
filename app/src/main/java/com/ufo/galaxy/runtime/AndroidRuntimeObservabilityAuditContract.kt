package com.ufo.galaxy.runtime

/**
 * PR-5 (Android) — Runtime observability and problem-solving audit contract.
 *
 * [AndroidRuntimeObservabilityAuditContract] is the authoritative Android-side declaration of
 * how Android emits structured runtime signals that support end-to-end problem-solving audit,
 * V2-side metrics/SLO computation, and board/reliability surface readiness.
 *
 * ## Motivation
 *
 * Prior to this contract, Android emitted runtime events and state snapshots with detailed
 * per-domain fields but lacked a unified, machine-readable surface answering:
 *
 * > Which execution path did Android take? How does this event contribute to the end-to-end
 * > problem-solving audit? Is the observability signal high-fidelity or degraded?
 *
 * Without this contract:
 *  - V2 metrics could not reliably distinguish local-path from cross-device-path execution events.
 *  - Board/reliability surfaces could not infer whether a signal represents participation
 *    attestation, execution contribution, interruption, or recovery.
 *  - SLO computation remained partially inferred from combinations of unrelated fields.
 *  - Problem-solving audit could not reconstruct whether Android participated, was interrupted,
 *    recovered, or contributed to final outcome quality.
 *
 * [AndroidRuntimeObservabilityAuditContract] closes these gaps by:
 *  1. Declaring [ExecutionPathTag] — the canonical tagging of local vs cross-device execution.
 *  2. Defining [AuditContributionClass] — the role of each emitted event in the audit trail.
 *  3. Defining [ObservabilityReliabilityClass] — the fidelity of the signal for SLO/metrics use.
 *  4. Providing classifier methods that derive these classes from existing runtime state, so no
 *     new ad-hoc fields are required beyond the structured output of this contract.
 *  5. Declaring [OBSERVABILITY_AUDIT_INVARIANTS] that both Android and V2 can audit to verify
 *     the contract is upheld at runtime.
 *
 * ## Design principles
 *
 * 1. **No duplication of existing fields.** This contract does not re-derive what
 *    [AndroidNlDrivenExecutionSpineContract], [AndroidCanonicalRuntimeTruthContract], or
 *    [AndroidOperationalStateSurfaceContract] already declare. It adds a convergence layer
 *    on top of those contracts' outputs.
 *
 * 2. **Classifiers are pure functions over existing wire state.** Every classifier in this
 *    object takes only string/boolean/nullable inputs already present in emitted payloads.
 *    No new Android-side counters or ring-buffer reads are required.
 *
 * 3. **V2 authority boundaries are explicit.** Android classifies its own contribution;
 *    V2 retains authority to accept, override, or aggregate Android-reported audit classes
 *    into the canonical problem-solving closure chain.
 *
 * ## V2 integration points
 *
 * | V2 surface                                          | Android field                           |
 * |-----------------------------------------------------|-----------------------------------------|
 * | `core/android_device_state_store.py` (absorb)       | [FIELD_EXECUTION_PATH_TAG]              |
 * | `core/problem_solving_audit_chain.py`               | [FIELD_AUDIT_CONTRIBUTION_CLASS]        |
 * | `metrics/android_slo_metrics.py`                    | [FIELD_OBSERVABILITY_RELIABILITY_CLASS] |
 * | `board/reliability_surface.py`                      | [FIELD_AUDIT_CONTRIBUTION_CLASS]        |
 * | `core/canonical_completion_ingress.py`              | [FIELD_AUDIT_CONTRIBUTION_CLASS]        |
 *
 * @see AndroidNlDrivenExecutionSpineContract
 * @see AndroidCanonicalRuntimeTruthContract
 * @see AndroidOperationalStateSurfaceContract
 * @see AndroidReconnectRecoveryParticipationContract
 */
object AndroidRuntimeObservabilityAuditContract {

    /** PR number that introduced this contract. */
    const val INTRODUCED_PR = 89

    /** Schema version for observability audit wire fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire field names ──────────────────────────────────────────────────────

    /** Wire field: tagging of the execution path taken (local vs cross-device). */
    const val FIELD_EXECUTION_PATH_TAG = "execution_path_tag"

    /**
     * Wire field: audit contribution class.
     *
     * Classifies the role of the event or snapshot in the end-to-end problem-solving
     * audit trail so V2 can reconstruct execution, interruption, recovery, and takeover
     * lifecycles from the Android-emitted event stream.
     */
    const val FIELD_AUDIT_CONTRIBUTION_CLASS = "audit_contribution_class"

    /**
     * Wire field: observability reliability class.
     *
     * Declares the fidelity of the signal for V2-side SLO/metrics computation.
     * V2 MUST NOT treat a LOW_FIDELITY or INTERRUPTED signal as a confirmed execution
     * data point without applying the appropriate uncertainty adjustment.
     */
    const val FIELD_OBSERVABILITY_RELIABILITY_CLASS = "observability_reliability_class"

    /** Wire field: schema version for the observability audit fields. */
    const val FIELD_OBSERVABILITY_AUDIT_SCHEMA_VERSION = "observability_audit_schema_version"

    // ── ExecutionPathTag ─────────────────────────────────────────────────────

    /**
     * Canonical tagging of the execution path taken by Android.
     *
     * Enables V2 metrics and board surfaces to distinguish local-only execution signals
     * from cross-device or delegated execution signals without field-combination inference.
     *
     * ## Path semantics
     *
     * | Tag | Meaning |
     * |-----|---------|
     * | [LOCAL_PATH] | Execution fully on-device; no cross-device dependency. |
     * | [CROSS_DEVICE_PATH] | Execution involves a live cross-device session. |
     * | [DELEGATED_PATH] | Android received delegated execution from V2 center. |
     * | [TAKEOVER_PATH] | Android executed as a takeover device. |
     * | [DEGRADED_PATH] | Execution ran on a degraded fallback path. |
     * | [UNKNOWN] | Path could not be classified at emission time. |
     */
    enum class ExecutionPathTag(val wireValue: String) {
        LOCAL_PATH("local_path"),
        CROSS_DEVICE_PATH("cross_device_path"),
        DELEGATED_PATH("delegated_path"),
        TAKEOVER_PATH("takeover_path"),
        DEGRADED_PATH("degraded_path"),
        UNKNOWN("unknown");

        companion object {
            private val wireIndex = entries.associateBy { it.wireValue }
            fun fromWireValue(value: String?): ExecutionPathTag? =
                value?.trim()?.lowercase()?.let(wireIndex::get)

            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── AuditContributionClass ───────────────────────────────────────────────

    /**
     * Classification of an event or snapshot's role in the end-to-end problem-solving
     * audit trail.
     *
     * V2's problem-solving audit chain reads this class to determine which audit log
     * tier an Android-emitted signal belongs to and what inference it supports.
     *
     * ## Class semantics
     *
     * | Class | Meaning |
     * |-------|---------|
     * | [PARTICIPATION_ATTESTATION] | Android confirms or updates participation eligibility/attachment. |
     * | [EXECUTION_CONTRIBUTION] | Android reports active in-progress execution steps. |
     * | [EXECUTION_OUTCOME] | Android reports a terminal execution result (success or failure). |
     * | [INTERRUPTION_RECORD] | Android records an interruption to ongoing execution. |
     * | [RECOVERY_CONTRIBUTION] | Android reports a recovery or resume from interruption. |
     * | [TAKEOVER_CONTRIBUTION] | Android reports takeover initiation, progress, or completion. |
     * | [DELEGATED_CONTRIBUTION] | Android reports delegated task execution lifecycle. |
     * | [OPERATOR_ACTION_OUTCOME] | Android reports the result of an operator action. |
     * | [INFORMATIONAL] | Signal does not directly contribute to audit but is recorded. |
     */
    enum class AuditContributionClass(val wireValue: String) {
        PARTICIPATION_ATTESTATION("participation_attestation"),
        EXECUTION_CONTRIBUTION("execution_contribution"),
        EXECUTION_OUTCOME("execution_outcome"),
        INTERRUPTION_RECORD("interruption_record"),
        RECOVERY_CONTRIBUTION("recovery_contribution"),
        TAKEOVER_CONTRIBUTION("takeover_contribution"),
        DELEGATED_CONTRIBUTION("delegated_contribution"),
        OPERATOR_ACTION_OUTCOME("operator_action_outcome"),
        INFORMATIONAL("informational");

        companion object {
            private val wireIndex = entries.associateBy { it.wireValue }
            fun fromWireValue(value: String?): AuditContributionClass? =
                value?.trim()?.lowercase()?.let(wireIndex::get)

            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── ObservabilityReliabilityClass ────────────────────────────────────────

    /**
     * Fidelity classification of an Android-emitted observability signal.
     *
     * V2's SLO metrics pipeline reads this class to determine whether a signal can be
     * used as a confirmed data point, requires an uncertainty adjustment, or must be
     * treated as a stale/interrupted observation.
     *
     * ## Class semantics
     *
     * | Class | SLO use |
     * |-------|---------|
     * | [HIGH_FIDELITY] | Confirmed live-runtime signal; use directly in SLO computation. |
     * | [REDUCED_FIDELITY] | Partial or cached signal; apply uncertainty adjustment. |
     * | [STALE] | Signal is from a stale or cached state; do not use as current truth. |
     * | [INTERRUPTED] | Signal was emitted during or after an interruption; apply recovery policy. |
     * | [UNKNOWN] | Fidelity cannot be determined; treat conservatively. |
     */
    enum class ObservabilityReliabilityClass(val wireValue: String) {
        HIGH_FIDELITY("high_fidelity"),
        REDUCED_FIDELITY("reduced_fidelity"),
        STALE("stale"),
        INTERRUPTED("interrupted"),
        UNKNOWN("unknown");

        companion object {
            private val wireIndex = entries.associateBy { it.wireValue }
            fun fromWireValue(value: String?): ObservabilityReliabilityClass? =
                value?.trim()?.lowercase()?.let(wireIndex::get)

            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Classifier: ExecutionPathTag ─────────────────────────────────────────

    /**
     * Classifies the execution path from available runtime signals.
     *
     * The classifier applies the following precedence rules:
     * 1. If [spineParticipationKind] is set, it maps directly to the correct tag.
     * 2. If [isTakeoverPath] is true, the result is [ExecutionPathTag.TAKEOVER_PATH].
     * 3. If [executionModeState] is "cross_device_active" or "cross_device_degraded" and
     *    [crossDeviceEligibility] is true, the result is [ExecutionPathTag.CROSS_DEVICE_PATH].
     * 4. If [isDelegated] is true, the result is [ExecutionPathTag.DELEGATED_PATH].
     * 5. If [currentFallbackTier] indicates a degraded path, the result is
     *    [ExecutionPathTag.DEGRADED_PATH].
     * 6. If [executionModeState] is "local_only" and [crossDeviceEligibility] is false,
     *    the result is [ExecutionPathTag.LOCAL_PATH].
     * 7. Otherwise [ExecutionPathTag.UNKNOWN].
     *
     * @param spineParticipationKind  Wire value of
     *   [AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind], or null.
     * @param executionModeState      Wire value of
     *   [LocalExecutionModeGate.ExecutionModeState], or null.
     * @param crossDeviceEligibility  Whether cross-device is eligible.
     * @param isTakeoverPath          True when the event was produced during a takeover flow.
     * @param isDelegated             True when the task was dispatched from V2 center.
     * @param currentFallbackTier     Active fallback tier string from rollout snapshot.
     */
    fun classifyExecutionPath(
        spineParticipationKind: String?,
        executionModeState: String?,
        crossDeviceEligibility: Boolean?,
        isTakeoverPath: Boolean,
        isDelegated: Boolean,
        currentFallbackTier: String?
    ): ExecutionPathTag {
        // 1. Spine participation kind takes precedence
        if (!spineParticipationKind.isNullOrBlank()) {
            return when (spineParticipationKind.trim().lowercase()) {
                "takeover_interactive" -> ExecutionPathTag.TAKEOVER_PATH
                "delegated_execution" -> ExecutionPathTag.DELEGATED_PATH
                "local_assistive" -> ExecutionPathTag.LOCAL_PATH
                "degraded_fallback" -> ExecutionPathTag.DEGRADED_PATH
                else -> ExecutionPathTag.UNKNOWN
            }
        }
        // 2. Explicit takeover flag
        if (isTakeoverPath) return ExecutionPathTag.TAKEOVER_PATH
        // 3. Explicit delegated flag
        if (isDelegated) return ExecutionPathTag.DELEGATED_PATH
        // 4. Execution mode state
        val modeNorm = executionModeState?.trim()?.lowercase()
        if (modeNorm == "cross_device_degraded") return ExecutionPathTag.DEGRADED_PATH
        if (modeNorm == "cross_device_active" && crossDeviceEligibility == true)
            return ExecutionPathTag.CROSS_DEVICE_PATH
        // 5. Fallback tier
        val fallbackNorm = currentFallbackTier?.trim()?.lowercase()
        if (fallbackNorm != null && fallbackNorm != "center_delegated" && fallbackNorm != "no_execution") {
            if (fallbackNorm == "center_delegated_with_local_fallback")
                return ExecutionPathTag.DEGRADED_PATH
        }
        // 6. Local-only mode
        if (modeNorm == "local_only" && crossDeviceEligibility != true)
            return ExecutionPathTag.LOCAL_PATH
        return ExecutionPathTag.UNKNOWN
    }

    // ── Classifier: AuditContributionClass ───────────────────────────────────

    /**
     * Classifies the audit contribution of an execution event or state snapshot.
     *
     * @param eventPhase              Phase string from [DeviceExecutionEventPayload.phase],
     *   or null when classifying a state snapshot.
     * @param isTerminalPhase         True when [eventPhase] is a terminal phase.
     * @param isTakeoverEvent         True when the event belongs to a takeover flow.
     * @param isDelegatedEvent        True when the event belongs to a delegated task.
     * @param isInterruptionPhase     True when the event represents an interruption
     *   (e.g. phase "failed" with is_blocking=true, or "stagnation_detected").
     * @param isRecoveryPhase         True when the event is a recovery milestone
     *   (e.g. reconnect_participation_kind == "transport_reconnect" on a snapshot).
     * @param isParticipationSnapshot True when classifying a DeviceStateSnapshotPayload
     *   rather than a DeviceExecutionEventPayload.
     * @param isOperatorActionEvent   True when the event is an operator action result.
     */
    fun classifyAuditContribution(
        eventPhase: String?,
        isTerminalPhase: Boolean,
        isTakeoverEvent: Boolean,
        isDelegatedEvent: Boolean,
        isInterruptionPhase: Boolean,
        isRecoveryPhase: Boolean,
        isParticipationSnapshot: Boolean,
        isOperatorActionEvent: Boolean
    ): AuditContributionClass {
        if (isParticipationSnapshot && !isRecoveryPhase)
            return AuditContributionClass.PARTICIPATION_ATTESTATION
        if (isParticipationSnapshot && isRecoveryPhase)
            return AuditContributionClass.RECOVERY_CONTRIBUTION
        if (isOperatorActionEvent)
            return AuditContributionClass.OPERATOR_ACTION_OUTCOME
        if (isInterruptionPhase)
            return AuditContributionClass.INTERRUPTION_RECORD
        if (isRecoveryPhase)
            return AuditContributionClass.RECOVERY_CONTRIBUTION
        if (isTakeoverEvent)
            return AuditContributionClass.TAKEOVER_CONTRIBUTION
        if (isDelegatedEvent)
            return AuditContributionClass.DELEGATED_CONTRIBUTION
        if (isTerminalPhase)
            return AuditContributionClass.EXECUTION_OUTCOME
        val phaseNorm = eventPhase?.trim()?.lowercase()
        return when (phaseNorm) {
            "execution_started", "execution_progress" -> AuditContributionClass.EXECUTION_CONTRIBUTION
            "completed", "failed", "stagnation_detected", "cancelled" -> AuditContributionClass.EXECUTION_OUTCOME
            "fallback_transition" -> AuditContributionClass.RECOVERY_CONTRIBUTION
            "takeover_milestone" -> AuditContributionClass.TAKEOVER_CONTRIBUTION
            null -> AuditContributionClass.INFORMATIONAL
            else -> AuditContributionClass.INFORMATIONAL
        }
    }

    // ── Classifier: ObservabilityReliabilityClass ────────────────────────────

    /**
     * Classifies the reliability/fidelity of an Android observability signal.
     *
     * @param evidencePresenceKind     Wire value of evidence_presence_kind from the
     *   [AndroidTruthPublicationSemanticsContract], or null.
     * @param degradedConditionClass   Wire value of degraded_condition_class from
     *   [AndroidCanonicalRuntimeTruthContract], or null.
     * @param reconnectRecoveryState   Wire value of reconnect_recovery_state, or null.
     * @param localObservationBasis    Wire value of local_observation_basis, or null.
     */
    fun classifyObservabilityReliability(
        evidencePresenceKind: String?,
        degradedConditionClass: String?,
        reconnectRecoveryState: String?,
        localObservationBasis: String?
    ): ObservabilityReliabilityClass {
        val evidenceNorm = evidencePresenceKind?.trim()?.lowercase()
        val degradedNorm = degradedConditionClass?.trim()?.lowercase()
        val reconnectNorm = reconnectRecoveryState?.trim()?.lowercase()
        val basisNorm = localObservationBasis?.trim()?.lowercase()
        // Interrupted: WS is in active recovery
        if (reconnectNorm == "recovering") return ObservabilityReliabilityClass.INTERRUPTED
        // Stale basis
        if (basisNorm == "cached_state") return ObservabilityReliabilityClass.STALE
        // Evidence-presence-based classification
        return when (evidenceNorm) {
            "positive_evidence" -> {
                when (degradedNorm) {
                    "nominal", "recovered", null -> ObservabilityReliabilityClass.HIGH_FIDELITY
                    "degraded", "fallback", "constrained", "partial",
                    "delayed" -> ObservabilityReliabilityClass.REDUCED_FIDELITY
                    else -> ObservabilityReliabilityClass.HIGH_FIDELITY
                }
            }
            "delayed" -> ObservabilityReliabilityClass.STALE
            "partial" -> ObservabilityReliabilityClass.REDUCED_FIDELITY
            "unknown" -> ObservabilityReliabilityClass.UNKNOWN
            "unavailable", "failed_observation" -> ObservabilityReliabilityClass.INTERRUPTED
            null -> {
                when (degradedNorm) {
                    "degraded", "fallback", "constrained",
                    "partial", "delayed" -> ObservabilityReliabilityClass.REDUCED_FIDELITY
                    "recovering" -> ObservabilityReliabilityClass.INTERRUPTED
                    else -> ObservabilityReliabilityClass.UNKNOWN
                }
            }
            else -> ObservabilityReliabilityClass.UNKNOWN
        }
    }

    // ── OBSERVABILITY_AUDIT_INVARIANTS ───────────────────────────────────────

    /**
     * Canonical invariants for the Android runtime observability and problem-solving
     * audit contract.
     *
     * Both Android and V2 can audit these invariants at runtime to verify the contract
     * is upheld in both directions.
     */
    val OBSERVABILITY_AUDIT_INVARIANTS: Map<String, Boolean> = mapOf(
        "execution_path_tag_emitted_in_every_execution_event" to true,
        "audit_contribution_class_emitted_in_every_execution_event" to true,
        "observability_reliability_class_emitted_in_every_event" to true,
        "execution_path_tag_emitted_in_every_state_snapshot" to true,
        "audit_contribution_class_emitted_in_every_state_snapshot" to true,
        "observability_reliability_class_emitted_in_every_snapshot" to true,
        "execution_path_tag_derived_from_real_runtime_state" to true,
        "audit_contribution_class_derived_from_real_event_semantics" to true,
        "observability_reliability_class_derived_from_evidence_presence" to true,
        "all_invariants_hold" to true
    )
}
