package com.ufo.galaxy.runtime

/**
 * PR-119 — Android Cross-Repo Recovery State Routing Contract.
 *
 * Bridges Android continuity recovery semantics to explicit, machine-actionable V2 routing
 * decisions so each [AndroidContinuityRecoveryStateModel.RecoveryPhase] is mapped to a
 * concrete [V2RoutingCategory] with structured routing metadata rather than remaining a
 * loosely coupled string path.
 *
 * ## Problem addressed
 *
 * [AndroidContinuityRecoveryStateModel] already provides the recovery phase classification
 * and a human-readable [AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP].
 * However, V2 still needs to:
 *  - Distinguish advisory evidence from phases that require canonical V2 action.
 *  - Determine whether canonical closure is blocked for a given phase.
 *  - Verify that recovery evidence never bypasses V2 adjudication.
 *
 * This contract formalises those distinctions as a machine-actionable [RoutingDecision]
 * and exposes them as stable wire keys in uplink payloads so V2 can consume structured
 * routing intent alongside the raw recovery phase value.
 *
 * ## Routing decision vocabulary
 *
 * | [V2RoutingCategory]                        | Recovery phase(s)             | Requires V2 action | Advisory only | Closure blocked |
 * |--------------------------------------------|-------------------------------|-------------------|---------------|-----------------|
 * | [V2RoutingCategory.NO_RECOVERY_ACTION_REQUIRED]             | RESUMED_CLEANLY               | false             | true          | false           |
 * | [V2RoutingCategory.PENDING_RECONNECT_VERDICT]               | RECOVERING                    | true              | false         | true            |
 * | [V2RoutingCategory.ADVISORY_INFLIGHT_EVIDENCE]              | RECOVERED_INFLIGHT            | false             | true          | true            |
 * | [V2RoutingCategory.TASK_CLOSURE_OR_RECONCILIATION_REQUIRED] | LOST_INFLIGHT                 | true              | false         | false           |
 * | [V2RoutingCategory.CANONICAL_RECONCILIATION_PASS]           | REQUIRES_RECONCILIATION       | true              | false         | true            |
 * | [V2RoutingCategory.STALE_ARTIFACT_REJECTION]                | STALE_RECOVERY_ARTIFACT       | true              | false         | true            |
 * | [V2RoutingCategory.TERMINAL_RECONNECT_FAILURE]              | RECOVERY_FAILED               | true              | false         | true            |
 *
 * ## Wire integration
 *
 * [KEY_V2_ROUTING_CATEGORY] and companion keys are embedded alongside
 * [AndroidContinuityRecoveryStateModel.KEY_CONTINUITY_RECOVERY_STATE] in:
 *  - [ReconciliationSignal] payload for [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT]
 *    (via [ReconciliationSignal.runtimeTruthSnapshot])
 *
 * ## Boundary constraint
 *
 * Android MUST NOT self-promote any recovery phase to canonical closure.  V2 owns all
 * canonical closure decisions.  [RoutingDecision.canonicalClosureBlocked] records the
 * routing contract's assertion about closure eligibility for each phase; V2 MUST NOT
 * bypass this flag without an explicit reconciliation decision.
 *
 * @see AndroidContinuityRecoveryStateModel
 * @see ReconciliationSignal
 */
object AndroidCrossRepoRecoveryStateRoutingContract {

    /** Android PR that introduced this routing contract. */
    const val INTRODUCED_PR = 119

    /** Wire schema version for this contract's routing fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the [V2RoutingCategory.wireValue] of the current recovery phase.
     *
     * V2 MUST read this key to route recovery evidence to the correct handling path.
     */
    const val KEY_V2_ROUTING_CATEGORY = "recovery_state_v2_routing_category"

    /**
     * Wire key indicating whether this recovery phase requires explicit V2 canonical action.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, V2 must trigger the appropriate canonical
     * handling path (reconciliation pass, stale rejection, closure review, etc.).
     */
    const val KEY_ROUTING_REQUIRES_V2_ACTION = "recovery_state_routing_requires_v2_action"

    /**
     * Wire key indicating whether this recovery phase carries only advisory evidence.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, the phase represents an Android-local
     * observation that V2 MAY use as evidence but MUST NOT treat as canonical truth by itself.
     */
    const val KEY_ROUTING_IS_ADVISORY_ONLY = "recovery_state_routing_is_advisory_only"

    /**
     * Wire key indicating whether canonical closure is blocked for this recovery phase.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, V2 MUST NOT close the task canonically
     * based solely on this recovery evidence — independent V2 adjudication is required first.
     */
    const val KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED = "recovery_state_routing_canonical_closure_blocked"

    /**
     * Wire key for this contract's [SCHEMA_VERSION].
     */
    const val KEY_ROUTING_SCHEMA_VERSION = "recovery_state_routing_schema_version"

    // ── V2RoutingCategory ──────────────────────────────────────────────────────

    /**
     * Structured V2-side routing category for each Android recovery phase.
     *
     * Each value names the explicit V2 handling path that MUST be invoked when
     * the corresponding [AndroidContinuityRecoveryStateModel.RecoveryPhase] is received.
     *
     * @property wireValue Stable lowercase string for wire transmission.
     */
    enum class V2RoutingCategory(val wireValue: String) {

        /**
         * No recovery artifacts or reconnect state found; V2 may treat the participant
         * session as clean without any recovery-specific action.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY].
         */
        NO_RECOVERY_ACTION_REQUIRED("no_recovery_action_required"),

        /**
         * Android is mid-reconnect; canonical session stability is pending.
         *
         * V2 MUST withhold canonical closure decisions for this participant until a
         * non-RECOVERING phase is reported.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING].
         */
        PENDING_RECONNECT_VERDICT("pending_reconnect_verdict"),

        /**
         * Android locally recovered a prior in-flight task.
         *
         * This is advisory evidence only.  V2 MUST independently verify execution state
         * via its canonical reconciliation chain before acknowledging any closure.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT].
         */
        ADVISORY_INFLIGHT_EVIDENCE("advisory_inflight_evidence"),

        /**
         * Android dropped the prior in-flight task; V2 must handle potential task closure
         * or trigger a reconciliation pass for the referenced task ID.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT].
         */
        TASK_CLOSURE_OR_RECONCILIATION_REQUIRED("task_closure_or_reconciliation_required"),

        /**
         * Android found a durable recovery artifact but has no live execution; V2 must
         * perform a canonical reconciliation pass to resolve the task's true outcome.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION].
         */
        CANONICAL_RECONCILIATION_PASS("canonical_reconciliation_pass"),

        /**
         * Android found a durable artifact that belongs to an expired session epoch.
         *
         * V2 MUST reject this artifact as stale continuity evidence and MUST NOT use it
         * as evidence for the current session's canonical truth.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT].
         */
        STALE_ARTIFACT_REJECTION("stale_artifact_rejection"),

        /**
         * All reconnect attempts were exhausted; V2 should treat the participant connection
         * as terminally disconnected until a new RECOVERING phase is reported.
         *
         * Corresponds to [AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED].
         */
        TERMINAL_RECONNECT_FAILURE("terminal_reconnect_failure");

        companion object {
            fun fromWireValue(value: String?): V2RoutingCategory? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── RoutingDecision ────────────────────────────────────────────────────────

    /**
     * Structured routing decision for a single [AndroidContinuityRecoveryStateModel.RecoveryPhase].
     *
     * @property phase                    The Android recovery phase this decision applies to.
     * @property v2RoutingCategory        The V2 handling category for this phase.
     * @property requiresV2Action         True if V2 must trigger an explicit canonical handling
     *                                    action (reconciliation pass, rejection, closure review).
     * @property isAdvisoryOnly           True if the evidence is Android-local advisory only;
     *                                    V2 MUST NOT treat it as canonical truth by itself.
     * @property canonicalClosureBlocked  True if canonical task closure is blocked pending
     *                                    independent V2 adjudication.
     * @property v2HandlingPath           Stable string path echoing
     *                                    [AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP]
     *                                    for audit and telemetry.
     */
    data class RoutingDecision(
        val phase: AndroidContinuityRecoveryStateModel.RecoveryPhase,
        val v2RoutingCategory: V2RoutingCategory,
        val requiresV2Action: Boolean,
        val isAdvisoryOnly: Boolean,
        val canonicalClosureBlocked: Boolean,
        val v2HandlingPath: String
    )

    // ── routeRecoveryPhase ─────────────────────────────────────────────────────

    /**
     * Returns the explicit [RoutingDecision] for the given [phase].
     *
     * This is the canonical single-call entry point for converting a
     * [AndroidContinuityRecoveryStateModel.RecoveryPhase] into V2-actionable routing metadata.
     * Callers MUST use this method rather than constructing routing decisions ad hoc.
     */
    fun routeRecoveryPhase(
        phase: AndroidContinuityRecoveryStateModel.RecoveryPhase
    ): RoutingDecision = when (phase) {

        AndroidContinuityRecoveryStateModel.RecoveryPhase.RESUMED_CLEANLY ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.NO_RECOVERY_ACTION_REQUIRED,
                requiresV2Action = false,
                isAdvisoryOnly = true,
                canonicalClosureBlocked = false,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERING ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.PENDING_RECONNECT_VERDICT,
                requiresV2Action = true,
                isAdvisoryOnly = false,
                canonicalClosureBlocked = true,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERED_INFLIGHT ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.ADVISORY_INFLIGHT_EVIDENCE,
                requiresV2Action = false,
                isAdvisoryOnly = true,
                canonicalClosureBlocked = true,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.LOST_INFLIGHT ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.TASK_CLOSURE_OR_RECONCILIATION_REQUIRED,
                requiresV2Action = true,
                isAdvisoryOnly = false,
                canonicalClosureBlocked = false,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.REQUIRES_RECONCILIATION ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.CANONICAL_RECONCILIATION_PASS,
                requiresV2Action = true,
                isAdvisoryOnly = false,
                canonicalClosureBlocked = true,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.STALE_RECOVERY_ARTIFACT ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.STALE_ARTIFACT_REJECTION,
                requiresV2Action = true,
                isAdvisoryOnly = false,
                canonicalClosureBlocked = true,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )

        AndroidContinuityRecoveryStateModel.RecoveryPhase.RECOVERY_FAILED ->
            RoutingDecision(
                phase = phase,
                v2RoutingCategory = V2RoutingCategory.TERMINAL_RECONNECT_FAILURE,
                requiresV2Action = true,
                isAdvisoryOnly = false,
                canonicalClosureBlocked = true,
                v2HandlingPath = AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP
                    .getValue(phase.wireValue)
            )
    }

    // ── toWireMap ──────────────────────────────────────────────────────────────

    /**
     * Produces the minimal wire map for embedding this contract's routing fields alongside
     * [AndroidContinuityRecoveryStateModel.toWireMap] in any uplink payload.
     *
     * All values are strings so the map is suitable for embedding in any JSON payload.
     */
    fun toWireMap(decision: RoutingDecision): Map<String, String> = mapOf(
        KEY_V2_ROUTING_CATEGORY to decision.v2RoutingCategory.wireValue,
        KEY_ROUTING_REQUIRES_V2_ACTION to decision.requiresV2Action.toString(),
        KEY_ROUTING_IS_ADVISORY_ONLY to decision.isAdvisoryOnly.toString(),
        KEY_ROUTING_CANONICAL_CLOSURE_BLOCKED to decision.canonicalClosureBlocked.toString(),
        KEY_ROUTING_SCHEMA_VERSION to SCHEMA_VERSION
    )

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Formal invariants that MUST hold for all routing decisions produced by this contract.
     */
    val ROUTING_CONTRACT_INVARIANTS: List<String> = listOf(
        "INV-ROUTING-01: RECOVERED_INFLIGHT MUST be routed as ADVISORY_INFLIGHT_EVIDENCE; " +
            "isAdvisoryOnly MUST be true and canonicalClosureBlocked MUST be true; " +
            "V2 MUST NOT treat recovered-inflight evidence as canonical closure",
        "INV-ROUTING-02: REQUIRES_RECONCILIATION MUST trigger CANONICAL_RECONCILIATION_PASS; " +
            "requiresV2Action MUST be true; V2 must perform a canonical reconciliation pass " +
            "before making any closure decision for the referenced task",
        "INV-ROUTING-03: STALE_RECOVERY_ARTIFACT MUST trigger STALE_ARTIFACT_REJECTION; " +
            "requiresV2Action MUST be true and canonicalClosureBlocked MUST be true; " +
            "V2 MUST reject stale artifacts and MUST NOT use them as current continuity evidence",
        "INV-ROUTING-04: every routing decision with canonicalClosureBlocked=true MUST ensure " +
            "V2 does not directly close the task without independent adjudication; " +
            "the blocked flag is advisory to V2 but MUST be respected for correctness",
        "INV-ROUTING-05: routing metadata MUST be emitted in RUNTIME_TRUTH_SNAPSHOT payload " +
            "alongside continuity_recovery_state so V2 can consume structured routing intent " +
            "without re-inferring routing category from the raw phase value",
        "INV-ROUTING-06: LOST_INFLIGHT MUST require V2 action (requiresV2Action=true); " +
            "Android dropping an in-flight task MUST trigger V2 closure or reconciliation review",
        "INV-ROUTING-07: RECOVERING phase MUST block canonical closure (canonicalClosureBlocked=true) " +
            "pending reconnect verdict; V2 MUST wait for a non-RECOVERING phase before " +
            "making canonical closure decisions for the affected participant",
        "INV-ROUTING-08: routing contract MUST remain semantically aligned with " +
            "AndroidContinuityRecoveryStateModel.V2_CONSUMPTION_PATH_MAP; " +
            "v2HandlingPath in each RoutingDecision MUST equal the corresponding path map entry"
    )

    // ── V2 routing intent map ──────────────────────────────────────────────────

    /**
     * Precomputed map from [AndroidContinuityRecoveryStateModel.RecoveryPhase.wireValue]
     * to [RoutingDecision] for all known phases.
     *
     * V2 MAY use this map to look up routing intent by wire value when the phase is
     * received as a string from an Android uplink payload.
     */
    val V2_ROUTING_INTENT_MAP: Map<String, RoutingDecision> =
        AndroidContinuityRecoveryStateModel.RecoveryPhase.entries.associate { phase ->
            phase.wireValue to routeRecoveryPhase(phase)
        }
}
