package com.ufo.galaxy.runtime

import com.ufo.galaxy.shared.protocol.MsgType

/**
 * PR-120 — Android Non-Closure Signal Boundary Contract.
 *
 * Establishes a unified, machine-actionable classification of all Android-emitted payload
 * classes that are observability-only, diagnostics-only, advisory-only, or readiness-only —
 * and therefore MUST NOT be used by V2 as canonical task-closure or final-truth inputs.
 *
 * ## Problem addressed
 *
 * The Android runtime emits several classes of information that are legitimate and valuable
 * but are not closure-authoritative:
 *
 *  - [NonClosureSignalClass.DIAGNOSTICS_ONLY]: Pure diagnostics signals reporting local
 *    failure causes and runtime state snapshots.  V2 MUST store these for observability
 *    but MUST NOT derive canonical task closure from them.
 *  - [NonClosureSignalClass.ADVISORY_RECOVERY_ONLY]: Android-local continuity recovery
 *    evidence.  Signals how Android perceives its own recovery state, but V2 owns canonical
 *    continuity adjudication.
 *  - [NonClosureSignalClass.READINESS_ONLY]: Android-local readiness assessments and
 *    evaluator artifacts indicating runtime readiness for release gating — not task closure.
 *  - [NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY]: Post-graduation governance, acceptance,
 *    and strategy evaluator artifacts that inform V2 governance paths but cannot directly
 *    close tasks.
 *  - [NonClosureSignalClass.CONTINUITY_DIAGNOSTIC_ONLY]: Continuity diagnostics events
 *    exposed at the service layer for local observability — diagnostics-visible only, not
 *    canonical truth.
 *
 * ## Non-closure invariant
 *
 * ALL values of [NonClosureSignalClass] share the defining invariant:
 *
 *   `isClosureEligible = false` — V2 MUST NOT use any payload classified under this
 *   contract to directly complete task closure or establish final truth.
 *
 * Legitimate closure is owned exclusively by the V2 canonical truth chain consuming
 * [MsgType.GOAL_EXECUTION_RESULT] payloads classified as
 * [AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT].
 *
 * ## Wire integration
 *
 * Each non-closure payload carries [KEY_NON_CLOSURE_SIGNAL_CLASS] and
 * [KEY_NON_CLOSURE_SCHEMA_VERSION] wire fields.  V2 MUST read [KEY_NON_CLOSURE_SIGNAL_CLASS]
 * before applying any closure or result logic:
 *  - If the field is present and its value is any [NonClosureSignalClass.wireValue],
 *    V2 MUST route the payload to the corresponding non-closure consumption path and
 *    MUST NOT derive canonical task closure from it.
 *  - If the field is absent (null — legacy sender), V2 MUST fall back to the existing
 *    [AndroidResultUplinkBoundaryContract] and [AndroidDiagnosticsFailureExplanationUplinkContract]
 *    classification fields for backward compatibility.
 *
 * ## Relation to existing contracts
 *
 * | Existing contract | Relationship to this contract |
 * |---|---|
 * | [AndroidResultUplinkBoundaryContract] | DIAGNOSTICS_ONLY maps to DIAGNOSTICS_INFORMATIONAL; READINESS_ONLY and EVALUATOR_ADVISORY_ONLY map to CLOSURE_NOT_APPLICABLE |
 * | [AndroidDiagnosticsFailureExplanationUplinkContract] | This contract provides a higher-level non-closure class spanning all diagnostic boundary classes |
 * | [AndroidContinuityRecoveryStateModel] | ADVISORY_RECOVERY_ONLY covers advisory recovery phases (e.g. RECOVERED_INFLIGHT) |
 * | [AndroidCrossRepoRecoveryStateRoutingContract] | Routing decisions with isAdvisoryOnly=true align with ADVISORY_RECOVERY_ONLY |
 * | [AndroidGovernanceExecutionPolicyIngressContract] | DIAGNOSTICS_AUDIT_SUMMARY ingress aligns with DIAGNOSTICS_ONLY and EVALUATOR_ADVISORY_ONLY |
 *
 * @see AndroidResultUplinkBoundaryContract
 * @see AndroidDiagnosticsFailureExplanationUplinkContract
 * @see AndroidContinuityRecoveryStateModel
 * @see AndroidCrossRepoRecoveryStateRoutingContract
 */
object AndroidNonClosureSignalBoundaryContract {

    /** The Android PR number that introduced this contract. */
    const val INTRODUCED_PR = 120

    /** Wire schema version for [KEY_NON_CLOSURE_SIGNAL_CLASS] and [KEY_NON_CLOSURE_SCHEMA_VERSION]. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the non-closure signal class field.
     *
     * Value: one of the [NonClosureSignalClass.wireValue] strings.
     * Present on all payloads classified as non-closure by this contract.
     * V2 MUST read this field before applying any closure or result logic.
     */
    const val KEY_NON_CLOSURE_SIGNAL_CLASS = "non_closure_signal_class"

    /**
     * Wire key for the schema version of the non-closure classification fields.
     *
     * Value: [SCHEMA_VERSION] ("1").
     */
    const val KEY_NON_CLOSURE_SCHEMA_VERSION = "non_closure_schema_version"

    // ── Non-closure signal class enum ─────────────────────────────────────────

    /**
     * Classification of Android-emitted payload types that are observability-only
     * and MUST NOT be treated as canonical task-closure or final-truth inputs.
     *
     * All values have [isClosureEligible] = `false` and [isCanonicalResultCapable] = `false`.
     * These properties are the defining invariants of the non-closure boundary.
     *
     * V2 MUST route payloads carrying any of these classifications to their
     * corresponding non-closure consumption paths (see [V2_NON_CLOSURE_ALIGNMENT_MAP]).
     */
    enum class NonClosureSignalClass(
        val wireValue: String,
        val description: String
    ) {
        /**
         * Pure diagnostics signal — local runtime state snapshot or failure diagnostic.
         *
         * Covers:
         *  - [com.ufo.galaxy.protocol.DiagnosticsPayload] ([MsgType.DIAGNOSTICS_PAYLOAD])
         *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] ([MsgType.DEVICE_STATE_SNAPSHOT])
         *
         * V2 MUST store these payloads in `core/android_device_state_store.py` for
         * observability only and MUST NOT derive canonical task closure from them.
         */
        DIAGNOSTICS_ONLY(
            wireValue = "diagnostics_only",
            description = "Pure diagnostics signal: local runtime state or failure diagnostic. " +
                "V2 MUST store for observability; MUST NOT use for task closure."
        ),

        /**
         * Advisory Android-local recovery evidence — how Android perceives its own
         * continuity recovery state, not canonical truth.
         *
         * Covers:
         *  - Recovery phase [AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT]
         *    carried in reconciliation signal payloads.
         *  - Any advisory continuity evidence embedded in state snapshots or truth snapshots.
         *
         * V2 MUST adjudicate canonical continuity independently via its canonical
         * reconciliation chain and MUST NOT treat this advisory evidence as a closure input.
         */
        ADVISORY_RECOVERY_ONLY(
            wireValue = "advisory_recovery_only",
            description = "Advisory Android-local recovery evidence. " +
                "V2 must adjudicate continuity independently; MUST NOT close tasks directly."
        ),

        /**
         * Android-local readiness assessment for release gating — not task closure.
         *
         * Covers:
         *  - [com.ufo.galaxy.protocol.DeviceReadinessReportPayload] ([MsgType.DEVICE_READINESS_REPORT])
         *
         * V2 MUST use this only for release-gate readiness assessment and MUST NOT
         * derive canonical task closure from it.
         */
        READINESS_ONLY(
            wireValue = "readiness_only",
            description = "Android-local readiness assessment for release gating. " +
                "V2 MUST NOT use for canonical task closure."
        ),

        /**
         * Post-graduation evaluator advisory artifact — governance, acceptance, or
         * strategy posture conclusion that informs V2 governance paths but cannot
         * directly close tasks.
         *
         * Covers:
         *  - [com.ufo.galaxy.protocol.DeviceGovernanceReportPayload] ([MsgType.DEVICE_GOVERNANCE_REPORT])
         *  - [com.ufo.galaxy.protocol.DeviceAcceptanceReportPayload] ([MsgType.DEVICE_ACCEPTANCE_REPORT])
         *  - [com.ufo.galaxy.protocol.DeviceStrategyReportPayload] ([MsgType.DEVICE_STRATEGY_REPORT])
         *
         * V2 MUST use these only for governance and graduation gate evaluation and
         * MUST NOT derive canonical task closure from them.
         */
        EVALUATOR_ADVISORY_ONLY(
            wireValue = "evaluator_advisory_only",
            description = "Post-graduation evaluator artifact advisory. " +
                "V2 MUST NOT use for canonical task closure; feeds governance/graduation gates only."
        ),

        /**
         * Continuity diagnostics event — service-layer visibility of continuity runtime
         * decisions, not canonical continuity truth.
         *
         * Covers:
         *  - [AndroidContinuityDiagnosticsContract.ContinuityDiagnosticsEvent] subtypes
         *    emitted on [RuntimeController.continuityDiagnosticsEvents].
         *
         * V2 MUST treat these as diagnostics-visible only and MUST NOT derive canonical
         * continuity adjudication or task closure from them.
         */
        CONTINUITY_DIAGNOSTIC_ONLY(
            wireValue = "continuity_diagnostic_only",
            description = "Service-layer continuity diagnostics event. " +
                "V2 MUST NOT derive canonical continuity adjudication or task closure from this signal."
        );

        /**
         * Whether this signal class is eligible for canonical task closure.
         *
         * Always `false` for all [NonClosureSignalClass] values — this is the
         * defining invariant of the non-closure boundary.
         *
         * V2 MUST check this property (or the equivalent [KEY_NON_CLOSURE_SIGNAL_CLASS] wire
         * field) before applying any closure logic to a payload.
         */
        val isClosureEligible: Boolean = false

        /**
         * Whether this signal class is capable of carrying a canonical task result.
         *
         * Always `false` for all [NonClosureSignalClass] values — non-closure signals
         * do not carry canonical task results.
         */
        val isCanonicalResultCapable: Boolean = false

        companion object {
            /**
             * Returns the [NonClosureSignalClass] for the given [wireValue], or
             * [DIAGNOSTICS_ONLY] as a safe defensive default for unknown values.
             *
             * V2 MUST treat unknown values as [DIAGNOSTICS_ONLY] (the most restrictive
             * default) rather than upgrading them to a closure-eligible path.
             */
            fun fromWireValue(wire: String): NonClosureSignalClass =
                values().firstOrNull { it.wireValue == wire } ?: DIAGNOSTICS_ONLY
        }
    }

    // ── MsgType → NonClosureSignalClass classification ────────────────────────

    /**
     * Set of [MsgType] values whose payloads are classified as non-closure Android signals.
     *
     * Any payload whose [com.ufo.galaxy.protocol.AipMessage.type] is in this set
     * MUST carry [KEY_NON_CLOSURE_SIGNAL_CLASS] and be treated as non-closure by V2.
     * V2 MUST NOT route these types to any canonical task-closure path.
     */
    val NON_CLOSURE_MSG_TYPES: Set<MsgType> = setOf(
        MsgType.DIAGNOSTICS_PAYLOAD,
        MsgType.DEVICE_STATE_SNAPSHOT,
        MsgType.DEVICE_READINESS_REPORT,
        MsgType.DEVICE_GOVERNANCE_REPORT,
        MsgType.DEVICE_ACCEPTANCE_REPORT,
        MsgType.DEVICE_STRATEGY_REPORT
    )

    /**
     * Classifies a [MsgType] into its [NonClosureSignalClass].
     *
     * Returns `null` for [MsgType] values that are not non-closure signals (i.e., types
     * that may carry closure-bearing payloads such as [MsgType.GOAL_EXECUTION_RESULT]).
     * Callers should only invoke this for types in [NON_CLOSURE_MSG_TYPES].
     *
     * @param msgType The [MsgType] to classify.
     * @return The [NonClosureSignalClass] for the given type, or `null` if not a non-closure type.
     */
    fun classify(msgType: MsgType): NonClosureSignalClass? = when (msgType) {
        MsgType.DIAGNOSTICS_PAYLOAD -> NonClosureSignalClass.DIAGNOSTICS_ONLY
        MsgType.DEVICE_STATE_SNAPSHOT -> NonClosureSignalClass.DIAGNOSTICS_ONLY
        MsgType.DEVICE_READINESS_REPORT -> NonClosureSignalClass.READINESS_ONLY
        MsgType.DEVICE_GOVERNANCE_REPORT -> NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY
        MsgType.DEVICE_ACCEPTANCE_REPORT -> NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY
        MsgType.DEVICE_STRATEGY_REPORT -> NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY
        else -> null
    }

    /**
     * Returns `true` if the given [msgType] is classified as a non-closure signal.
     *
     * V2 MUST gate on this check before routing any payload to a closure-generating path.
     * If this returns `true`, the payload MUST NOT be used for canonical task closure.
     */
    fun isNonClosureType(msgType: MsgType): Boolean = msgType in NON_CLOSURE_MSG_TYPES

    // ── Wire map helpers ───────────────────────────────────────────────────────

    /**
     * Produces the minimal wire map for embedding the non-closure classification
     * in any uplink payload.
     *
     * @param signalClass The [NonClosureSignalClass] for this payload.
     * @return Map of wire keys to values for inclusion in the uplink payload.
     */
    fun toWireMap(signalClass: NonClosureSignalClass): Map<String, String> = mapOf(
        KEY_NON_CLOSURE_SIGNAL_CLASS to signalClass.wireValue,
        KEY_NON_CLOSURE_SCHEMA_VERSION to SCHEMA_VERSION
    )

    // ── V2 non-closure consumption alignment map ───────────────────────────────

    /**
     * Maps each [NonClosureSignalClass] to the V2 canonical consumption path that
     * handles it without performing task closure.
     *
     * V2 MUST route non-closure signals through these paths and MUST NOT route them
     * to `core/task_result_canonical_truth_chain.py` or any other closure-generating path.
     */
    val V2_NON_CLOSURE_ALIGNMENT_MAP: Map<NonClosureSignalClass, String> = mapOf(
        NonClosureSignalClass.DIAGNOSTICS_ONLY to
            "core/android_device_state_store.py — store for observability only; no task closure",
        NonClosureSignalClass.ADVISORY_RECOVERY_ONLY to
            "core/continuity_adjudication.py — advisory evidence; V2 adjudicates independently",
        NonClosureSignalClass.READINESS_ONLY to
            "core/device_readiness_gate.py — readiness assessment only; no task closure",
        NonClosureSignalClass.EVALUATOR_ADVISORY_ONLY to
            "core/governance_evaluator_ingress.py — governance/graduation gate only; no task closure",
        NonClosureSignalClass.CONTINUITY_DIAGNOSTIC_ONLY to
            "core/android_device_state_store.py — continuity diagnostics store only; no task closure"
    )

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Formal invariants for non-closure signal boundary enforcement.
     *
     * All invariants must hold at all times.  Future contributors introducing new
     * Android payload types must verify all relevant invariants are satisfied.
     */
    val NON_CLOSURE_INVARIANTS: List<String> = listOf(
        "INV-NCL-01: All NonClosureSignalClass values MUST have isClosureEligible = false; " +
            "no non-closure signal class may ever be used to directly close a task.",
        "INV-NCL-02: All NonClosureSignalClass values MUST have isCanonicalResultCapable = false; " +
            "non-closure signals MUST NOT carry canonical task results.",
        "INV-NCL-03: V2 MUST NOT route any payload whose type is in NON_CLOSURE_MSG_TYPES " +
            "to core/task_result_canonical_truth_chain.py or any closure-generating ingress path.",
        "INV-NCL-04: DIAGNOSTICS_ONLY payloads (DiagnosticsPayload, DeviceStateSnapshotPayload) " +
            "MUST be consumed by core/android_device_state_store.py for observability only; " +
            "MUST NOT trigger task closure or canonical truth chain.",
        "INV-NCL-05: ADVISORY_RECOVERY_ONLY signals MUST NOT be consumed by V2 as canonical " +
            "closure without independent V2 continuity adjudication via its canonical reconciliation chain.",
        "INV-NCL-06: READINESS_ONLY payloads (DeviceReadinessReportPayload) MUST feed only " +
            "release-gate readiness assessment on V2; MUST NOT produce task closure.",
        "INV-NCL-07: EVALUATOR_ADVISORY_ONLY payloads (governance/acceptance/strategy reports) " +
            "MUST feed only V2 governance/graduation gates; MUST NOT produce canonical task closure.",
        "INV-NCL-08: The non_closure_signal_class wire field, when present and non-null, " +
            "MUST be treated by V2 as a hard non-closure gate; absent field (null, legacy sender) " +
            "falls back to AndroidResultUplinkBoundaryContract/AndroidDiagnosticsFailureExplanationUplinkContract.",
        "INV-NCL-09: New Android payload types that are observability-only or advisory-only MUST be " +
            "registered in NON_CLOSURE_MSG_TYPES and classified by classify(); " +
            "MUST NOT silently omit non-closure classification.",
        "INV-NCL-10: Under fallback, degraded, or replay scenarios, non-closure payload classes " +
            "MUST retain their non-closure semantics; no scenario may silently upgrade a " +
            "DIAGNOSTICS_ONLY/READINESS_ONLY/ADVISORY_RECOVERY_ONLY signal into a closure input."
    )
}
