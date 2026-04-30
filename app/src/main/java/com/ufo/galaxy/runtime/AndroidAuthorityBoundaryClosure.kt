package com.ufo.galaxy.runtime

/**
 * PR-76 (Android A4) — Android participant-truth and contribution authority boundary closure.
 *
 * [AndroidAuthorityBoundaryClosure] is the structural closure that explicitly declares
 * Android's authority is limited to three contribution domains, and that all higher-order
 * system authority belongs to the center runtime.
 *
 * ## Background
 *
 * After A1 (close non-canonical result emission paths), A2 (promote continuity gates), and
 * A3 (canonicalize delegated and takeover signals), Android still retained residual authority
 * in areas that should belong to the center:
 *
 *  - Terminal signals were emitted as authoritative assertions rather than as execution
 *    evidence contributions.
 *  - Dispatch readiness was declared as a device-local "eligible/ineligible" verdict
 *    without explicitly reducing to a contribution input for the center.
 *  - No single structural declaration named the five center-owned authority domains.
 *
 * This closure addresses all three gaps by:
 *
 *  1. Demoting [AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM] to
 *     [LocalTruthEmitDecision.EmitAsExecutionEvidence] (see [AndroidLocalTruthOwnershipCoordinator]).
 *  2. Declaring [RuntimeDispatchReadinessCoordinator]'s output as a contribution input to
 *     the center's dispatch-slot authority, not an Android-owned readiness verdict.
 *  3. Publishing the explicit [ContributionAuthority] / [CenterAuthorityDomain] model below.
 *
 * ## Android's three contribution authorities
 *
 * Android is authorized to contribute in exactly three domains:
 *
 * | [ContributionAuthority]             | What Android contributes                                     |
 * |-------------------------------------|--------------------------------------------------------------|
 * | [ContributionAuthority.PARTICIPANT_TRUTH]     | Stable device identity, session, health, posture, and readiness state as participant truth inputs. |
 * | [ContributionAuthority.EXECUTION_CONTRIBUTION] | Execution signals (ACK, PROGRESS, RESULT) and execution outcomes as evidence contributions to the center-side execution chain. |
 * | [ContributionAuthority.CONTINUITY_CONTRIBUTION] | Continuity token, recovery context, and resumability data contributed to center-side continuity gates. |
 *
 * ## Center-owned authority domains (Android must not own these)
 *
 * Five domains belong exclusively to the center:
 *
 * | [CenterAuthorityDomain]                        | What the center owns                                         |
 * |------------------------------------------------|--------------------------------------------------------------|
 * | [CenterAuthorityDomain.FINAL_COMPLETION_TRUTH]  | Final determination that a system-level execution era has closed. Android contributes terminal signals as evidence; the center makes the closure decision. |
 * | [CenterAuthorityDomain.DISPATCH_READINESS_AUTHORITY] | Final dispatch-slot selection and readiness verdict. Android contributes eligibility inputs via [RuntimeDispatchReadinessCoordinator]; the center owns the dispatch-slot decision. |
 * | [CenterAuthorityDomain.ORCHESTRATION_TRUTH]     | Cross-device orchestration flow, parallel fan-out, and execution-mode selection. Android executes assigned work; it does not own orchestration meaning. |
 * | [CenterAuthorityDomain.DELEGATED_LIFECYCLE_TRUTH] | Final interpretation of delegated and takeover lifecycle transitions. Android contributes ACK/TERMINAL signals; the center owns the lifecycle closure decision. |
 * | [CenterAuthorityDomain.SYSTEM_RECONCILIATION_TRUTH] | Canonical reconciliation of participant and execution truth across devices and sessions. Android contributes reconciliation signals; the center applies them. |
 *
 * ## Authority mandate
 *
 * - Android MUST NOT emit signals classified as final system-completion claims.
 * - Android MUST NOT treat its dispatch readiness evaluation as a final dispatch verdict.
 * - Android MUST NOT maintain an independent orchestration model that competes with the center spine.
 * - Android MUST NOT own the final lifecycle interpretation for delegated or takeover flows.
 * - Any remaining local coordination logic MUST be clearly subordinate to center-side truth.
 *
 * @see AndroidLocalTruthOwnershipCoordinator
 * @see RuntimeDispatchReadinessCoordinator
 * @see ParticipantExecutionSignalContract
 */
object AndroidAuthorityBoundaryClosure {

    /** PR number that introduced this authority boundary closure. */
    const val INTRODUCED_PR = 76

    // ── Contribution authority ─────────────────────────────────────────────────

    /**
     * The three authority domains in which Android is authorized to contribute.
     *
     * Android's role is exclusively that of a contributor in these domains.  It does not
     * own the final interpretation of what it contributes — that authority belongs to the
     * center.
     *
     * @property wireValue     Stable lowercase wire tag for this contribution authority.
     * @property displayName   Short human-readable name for diagnostics and audit trails.
     * @property description   One-sentence description of what Android contributes.
     */
    enum class ContributionAuthority(
        val wireValue: String,
        val displayName: String,
        val description: String
    ) {

        /**
         * Android contributes stable participant identity, session state, health, posture,
         * and readiness data as participant truth inputs to the center.
         *
         * This includes:
         *  - [AndroidParticipantRuntimeTruth] snapshots
         *  - [AttachedRuntimeSession] state
         *  - [ParticipantHealthState] reports
         *  - [SourceRuntimePosture] transitions
         *  - Readiness evidence from [AndroidReadinessEvidenceSurface]
         */
        PARTICIPANT_TRUTH(
            wireValue = "participant_truth",
            displayName = "Participant Truth",
            description = "Android contributes participant identity, session, health, posture, " +
                "and readiness state as participant truth inputs to the center."
        ),

        /**
         * Android contributes execution signals (ACK, PROGRESS, RESULT) and execution
         * outcomes as evidence contributions to the center-side canonical execution chain.
         *
         * This includes:
         *  - [DelegatedExecutionSignal] (ACK / PROGRESS / RESULT)
         *  - [ReconciliationSignal] (TASK_ACCEPTED / TASK_STATUS_UPDATE / TASK_RESULT / etc.)
         *  - [AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM] signals,
         *    emitted as [LocalTruthEmitDecision.EmitAsExecutionEvidence]
         *  - [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL] signals
         *
         * Android does NOT own final completion truth — the center interprets TERMINAL
         * signals to decide whether the system-level execution era is closed.
         */
        EXECUTION_CONTRIBUTION(
            wireValue = "execution_contribution",
            displayName = "Execution Contribution",
            description = "Android contributes execution signals (ACK, PROGRESS, RESULT) and " +
                "outcomes as evidence for the center-side canonical execution chain."
        ),

        /**
         * Android contributes continuity tokens, recovery contexts, and resumability data
         * as continuity contribution inputs to the center-side continuity gates.
         *
         * This includes:
         *  - `continuity_token` values echoed in result payloads
         *  - `is_resumable` flags and `recovery_context` fields
         *  - [DurableSessionContinuityRecord] and [HybridRuntimeContinuityContract] data
         *  - [ContinuityRecoveryContext] inputs
         *
         * Android contributes this data but the center decides whether a continuity gate
         * is valid and whether resumption is permitted.
         */
        CONTINUITY_CONTRIBUTION(
            wireValue = "continuity_contribution",
            displayName = "Continuity Contribution",
            description = "Android contributes continuity tokens, recovery contexts, and " +
                "resumability data as inputs to the center-side continuity gates."
        );

        companion object {
            /** Returns the [ContributionAuthority] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): ContributionAuthority? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Center authority domain ────────────────────────────────────────────────

    /**
     * The five authority domains that belong to the center, not to Android.
     *
     * Android must not claim ownership of interpretation or final truth in any of these
     * domains.  Signals, data, and evidence that Android contributes in these areas must
     * terminate in contribution semantics before reaching the center.
     *
     * @property wireValue     Stable lowercase wire tag for this center authority domain.
     * @property displayName   Short human-readable name for diagnostics and audit trails.
     * @property description   One-sentence description of what the center owns.
     * @property androidContributionKind  The [ContributionAuthority] that Android uses when
     *                         contributing data in this domain.
     */
    enum class CenterAuthorityDomain(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val androidContributionKind: ContributionAuthority
    ) {

        /**
         * The center is the sole authority for determining that a system-level execution era
         * has closed.
         *
         * Android contributes [ContributionAuthority.EXECUTION_CONTRIBUTION] terminal signals
         * (e.g. [ParticipantExecutionSignalContract.ExecSignalClass.TERMINAL]) as execution
         * evidence.  The center interprets these signals and decides whether the system-wide
         * task closure is final.
         *
         * Android-side constraint: [AndroidLocalTruthOwnershipCoordinator.TruthClass.LOCAL_TERMINAL_CLAIM]
         * signals MUST be emitted as [LocalTruthEmitDecision.EmitAsExecutionEvidence], not as
         * [LocalTruthEmitDecision.EmitAsAuthoritativeLocalTruth].
         */
        FINAL_COMPLETION_TRUTH(
            wireValue = "final_completion_truth",
            displayName = "Final Completion Truth",
            description = "Center is the sole authority for final system-level task closure; " +
                "Android contributes terminal signals as execution evidence only.",
            androidContributionKind = ContributionAuthority.EXECUTION_CONTRIBUTION
        ),

        /**
         * The center is the sole authority for dispatch-slot selection and final dispatch
         * readiness verdicts.
         *
         * [RuntimeDispatchReadinessCoordinator] produces a [RuntimeDispatchReadinessCoordinator.DispatchReadiness]
         * result that expresses Android's local eligibility inputs (runtime state, session
         * state, rollout flags).  This result is a **contribution input** to the center's
         * dispatch-slot authority — the center decides which device(s) receive tasks and
         * when dispatch is actually permitted.
         *
         * Android-side constraint: [RuntimeDispatchReadinessCoordinator.DispatchReadiness.isEligible]
         * MUST be treated as a local condition report, not as a final dispatch decision.
         */
        DISPATCH_READINESS_AUTHORITY(
            wireValue = "dispatch_readiness_authority",
            displayName = "Dispatch Readiness Authority",
            description = "Center is the sole authority for dispatch-slot selection; Android " +
                "contributes local eligibility inputs via RuntimeDispatchReadinessCoordinator.",
            androidContributionKind = ContributionAuthority.PARTICIPANT_TRUTH
        ),

        /**
         * The center is the sole authority for cross-device orchestration, parallel fan-out,
         * and execution-mode selection.
         *
         * Android executes work assigned to it by the center orchestration spine.  It does
         * not maintain an independent orchestration model or choose how work is distributed
         * across devices.
         *
         * Android-side constraint: [CanonicalDispatchChain] describes dispatch paths from an
         * Android-inspection perspective, but the center orchestration spine governs which
         * paths are activated and in what order.
         */
        ORCHESTRATION_TRUTH(
            wireValue = "orchestration_truth",
            displayName = "Orchestration Truth",
            description = "Center is the sole authority for cross-device orchestration and " +
                "execution-mode selection; Android executes assigned work only.",
            androidContributionKind = ContributionAuthority.EXECUTION_CONTRIBUTION
        ),

        /**
         * The center is the sole authority for final lifecycle interpretation of delegated
         * and takeover flows.
         *
         * Android contributes ACK, PROGRESS, and TERMINAL signals via
         * [ParticipantExecutionSignalContract] for each delegated or takeover flow.  The
         * center interprets these signals to decide whether the delegated lifecycle has
         * reached its final state.
         *
         * Android-side constraint: [AndroidDelegatedFlowBridge.executionTruthOwnershipBoundary]
         * names the Android component currently holding execution truth, but final lifecycle
         * interpretation (open / close / transfer) belongs to the center.
         */
        DELEGATED_LIFECYCLE_TRUTH(
            wireValue = "delegated_lifecycle_truth",
            displayName = "Delegated Lifecycle Truth",
            description = "Center is the sole authority for delegated and takeover lifecycle " +
                "closure; Android contributes ACK/PROGRESS/TERMINAL signals only.",
            androidContributionKind = ContributionAuthority.EXECUTION_CONTRIBUTION
        ),

        /**
         * The center is the sole authority for canonical reconciliation of participant and
         * execution truth across devices and sessions.
         *
         * Android contributes [ReconciliationSignal] instances (PARTICIPANT_STATE,
         * RUNTIME_TRUTH_SNAPSHOT, etc.) as reconcile-only inputs
         * ([ParticipantExecutionSignalContract.ExecSignalClass.RECONCILE_ONLY]).  The center
         * applies these inputs to its canonical participant truth without Android controlling
         * how or when reconciliation commits.
         *
         * Android-side constraint: [TruthReconciliationReducer] governs Android-local truth
         * convergence, but the cross-device canonical reconciliation authority is the center.
         */
        SYSTEM_RECONCILIATION_TRUTH(
            wireValue = "system_reconciliation_truth",
            displayName = "System Reconciliation Truth",
            description = "Center is the sole authority for canonical cross-device truth " +
                "reconciliation; Android contributes reconcile-only signals as inputs.",
            androidContributionKind = ContributionAuthority.EXECUTION_CONTRIBUTION
        );

        companion object {
            /** Returns the [CenterAuthorityDomain] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): CenterAuthorityDomain? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Dispatch readiness contribution role ──────────────────────────────────

    /**
     * Stable wire tag declaring the contribution role of [RuntimeDispatchReadinessCoordinator].
     *
     * [RuntimeDispatchReadinessCoordinator.resolve] produces a
     * [RuntimeDispatchReadinessCoordinator.DispatchReadiness] that reports Android's local
     * eligibility conditions (runtime state, session attachment, rollout flag).  This result
     * is a **contribution input** to the center's dispatch-slot authority — it is NOT an
     * Android-owned final dispatch readiness verdict.
     *
     * The center uses Android's contribution to decide whether to include this device in
     * a dispatch-slot selection, but the final decision (which device(s), which tasks, in
     * what order) belongs to the center.
     */
    const val DISPATCH_READINESS_CONTRIBUTION_ROLE =
        "dispatch_eligibility_contribution_input_for_center_dispatch_slot_authority"

    // ── Contract wire map ─────────────────────────────────────────────────────

    /**
     * Produces a stable machine-consumable map of the key authority boundary closure assertions.
     *
     * Suitable for structured telemetry, audit trails, and cross-repo alignment checks.
     */
    fun buildClosureWireMap(): Map<String, Any> = mapOf(
        "introduced_pr" to INTRODUCED_PR,
        "schema_version" to "1.0",
        "contribution_authorities" to ContributionAuthority.ALL_WIRE_VALUES.toList().sorted(),
        "center_authority_domains" to CenterAuthorityDomain.ALL_WIRE_VALUES.toList().sorted(),
        "dispatch_readiness_contribution_role" to DISPATCH_READINESS_CONTRIBUTION_ROLE,
        "local_terminal_claim_emits_as_execution_evidence" to true,
        "android_does_not_own_final_completion_truth" to true,
        "android_does_not_own_dispatch_readiness_authority" to true,
        "android_does_not_own_orchestration_truth" to true,
        "android_does_not_own_delegated_lifecycle_truth" to true,
        "android_does_not_own_system_reconciliation_truth" to true
    )

    // ── Authority mandate assertions ──────────────────────────────────────────

    /**
     * Set of wire-value assertions that must hold for the authority boundary closure to be
     * correctly enforced.  These are the invariants required by the A4 acceptance criteria.
     *
     * Each key names a mandatory property; the `true` value asserts it holds.
     * A cross-repo audit can verify all values are `true` to confirm closure.
     */
    val AUTHORITY_MANDATE: Map<String, Any> = mapOf(
        "android_may_contribute_participant_truth" to true,
        "android_may_contribute_execution_signals" to true,
        "android_may_contribute_continuity_data" to true,
        "android_must_not_define_final_system_completion_truth" to true,
        "android_must_not_act_as_final_dispatch_readiness_authority" to true,
        "android_must_not_own_orchestration_model" to true,
        "android_delegated_takeover_reduces_to_signal_contribution" to true,
        "any_local_coordination_is_subordinate_to_center_truth" to true
    )
}
