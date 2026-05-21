package com.ufo.galaxy.runtime

/**
 * PR-25Android — Android formal boundary summary output contract.
 *
 * Consolidates the canonical, long-term-referenceable formal boundary summary
 * for the Android bounded relative subject runtime.  Serves as the single stable
 * output that downstream PRs, test assertions, and V2-side narrative can cite
 * without chasing multiple intermediate contracts.
 *
 * ## What this contract does
 *
 * 1. **System role declaration** — locks Android's current formal role in the
 *    dual-repo system with explicit "is" and "is not" declarations.
 * 2. **Bounded subject runtime summary** — consolidates the six non-overlapping
 *    boundary summary classes already established across prior Android contracts.
 * 3. **restore / replay / uplink / outward / local-continuity boundary** — maps
 *    each boundary to the canonical module anchors that implement it.
 * 4. **Anti-drift invariants** — machine-assertable invariants that prevent
 *    future narrative / contract naming / authority drift.
 * 5. **V2 alignment map** — aligns every boundary class to the corresponding
 *    V2-side canonical narrative path.
 *
 * ## What Android is (formal role)
 *
 * | Role class | Meaning |
 * |---|---|
 * | `bounded_relative_subject_runtime` | Executes locally; returns results upward |
 * | `local_runtime_host` | Owns Android runtime lifecycle |
 * | `local_continuity_holder` | Holds continuity/restore/replay state locally |
 * | `local_execution_policy_participant` | Gates local execution under governance policy |
 * | `local_ai_consumer_host` | Hosts on-device ML inference pipeline |
 * | `distributed_participant` | Participates in V2-governed distributed runtime |
 *
 * ## What Android is NOT
 *
 * | Non-role | Reason |
 * |---|---|
 * | parallel canonical center | V2 holds canonical authority/truth convergence/closure |
 * | fully sovereign distributed authority node | Android authority is bounded to local scope |
 * | final closure adjudicator | V2 canonical center owns closure finalization |
 * | canonical truth finalizer | Android produces participant truth; V2 finalizes |
 * | dispatch arbitrator | V2 canonical center arbitrates final dispatch |
 * | platform sovereign | Android operates within V2-governed quasi-platform state |
 *
 * ## Six boundary summary classes (cross-reference)
 *
 * | [BoundarySummaryClass] | Canonical anchor |
 * |---|---|
 * | `ANDROID_BOUNDED_SUBJECT_RUNTIME` | [AndroidBoundedSubjectRuntimeContract.FormalRole] |
 * | `LOCAL_VISIBLE_VS_CANONICAL_VISIBLE` | [AndroidBoundedSubjectRuntimeContract.LocalVisibleClass] |
 * | `CONTINUITY_RESTORE_REPLAY` | [AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.CONTINUITY_STATE_UPLINK] |
 * | `UPLINK_INGRESS_CONTRACT` | [AndroidGovernanceExecutionPolicyIngressContract], uplink contracts |
 * | `OUTWARD_UI_CONSUMPTION` | [AndroidFinalSurfaceConvergenceContract] |
 * | `NON_SOVEREIGN_ROLE` | [AndroidBoundedSubjectPlatformBoundaryContract.QUASI_PLATFORM_STATE_DEFINITION] |
 *
 * @see AndroidBoundedSubjectRuntimeContract
 * @see AndroidBoundedSubjectPlatformBoundaryContract
 * @see AndroidFinalSurfaceConvergenceContract
 */
object AndroidFormalBoundarySummaryOutputContract {

    const val INTRODUCED_PR = 115
    const val SCHEMA_VERSION = "1"

    // ── System role declaration ───────────────────────────────────────────────

    /**
     * Formal classification of what Android currently is in the dual-repo system.
     *
     * Each entry has an explicit boolean confirming the role is active, so callers
     * can assert completeness without parsing strings.
     */
    data class SystemRoleDeclaration(
        val roleClass: String,
        val wireValue: String,
        val isActiveInCurrentSystem: Boolean,
        val canonicalAnchor: String
    )

    val SYSTEM_ROLE_DECLARATIONS: List<SystemRoleDeclaration> = listOf(
        SystemRoleDeclaration(
            roleClass = "bounded_relative_subject_runtime",
            wireValue = "bounded_relative_subject_runtime",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.BOUNDED_RELATIVE_SUBJECT_RUNTIME"
        ),
        SystemRoleDeclaration(
            roleClass = "local_runtime_host",
            wireValue = "local_runtime_host",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.LOCAL_RUNTIME_HOST"
        ),
        SystemRoleDeclaration(
            roleClass = "local_continuity_holder",
            wireValue = "local_continuity_holder",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.LOCAL_CONTINUITY_HOLDER"
        ),
        SystemRoleDeclaration(
            roleClass = "local_execution_policy_participant",
            wireValue = "local_execution_policy_participant",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.LOCAL_EXECUTION_POLICY_PARTICIPANT"
        ),
        SystemRoleDeclaration(
            roleClass = "local_ai_consumer_host",
            wireValue = "local_ai_consumer_host",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.LOCAL_AI_CONSUMER_HOST"
        ),
        SystemRoleDeclaration(
            roleClass = "distributed_participant",
            wireValue = "distributed_participant",
            isActiveInCurrentSystem = true,
            canonicalAnchor = "AndroidBoundedSubjectRuntimeContract.FormalRole.DISTRIBUTED_PARTICIPANT"
        )
    )

    // ── "Is" vs "is not" boundary ─────────────────────────────────────────────

    /**
     * An explicit "is" vs "is not" pair that locks a boundary claim at the formal
     * system role level.  The [isStatement] must remain true for the system to be
     * coherent; the [isNotStatement] must remain false to prevent authority drift.
     */
    data class IsVsIsNotBoundary(
        val boundaryId: String,
        val isStatement: String,
        val isNotStatement: String,
        val rationale: String
    )

    val IS_VS_IS_NOT_BOUNDARIES: List<IsVsIsNotBoundary> = listOf(
        IsVsIsNotBoundary(
            boundaryId = "android-role-bounded-subject-runtime",
            isStatement = "Android IS the bounded relative subject runtime: owns local lifecycle, " +
                "continuity, execution, and AI consumption under V2 canonical governance.",
            isNotStatement = "Android is NOT a parallel canonical center or final canonical authority node.",
            rationale = "Prevents Android from being described as an independent canonical peer to V2."
        ),
        IsVsIsNotBoundary(
            boundaryId = "android-role-local-runtime-host",
            isStatement = "Android IS the local runtime host: owns RuntimeController lifecycle, " +
                "GalaxyConnectionService WS lifecycle, and local state continuity.",
            isNotStatement = "Android is NOT a fully sovereign distributed authority: its authority " +
                "scope is bounded to local runtime decisions.",
            rationale = "Distinguishes bounded local authority from distributed sovereignty."
        ),
        IsVsIsNotBoundary(
            boundaryId = "android-truth-contributor",
            isStatement = "Android IS a participant truth contributor: sends goal_execution_result, " +
                "device_execution_event, device_state_snapshot, and diagnostics uplinks to V2.",
            isNotStatement = "Android is NOT the canonical truth finalizer: V2 canonical center " +
                "receives, validates, and finalizes all participant truth.",
            rationale = "Prevents uplink payloads from being treated as already-finalized canonical truth."
        ),
        IsVsIsNotBoundary(
            boundaryId = "android-continuity-holder",
            isStatement = "Android IS the local continuity/restore/replay holder: manages " +
                "offline queue, reconnect recovery, and continuity state locally.",
            isNotStatement = "Android is NOT the canonical continuity authority: continuity legality " +
                "and final continuity adjudication are V2 canonical center responsibilities.",
            rationale = "Prevents local continuity management from being conflated with canonical continuity authority."
        ),
        IsVsIsNotBoundary(
            boundaryId = "android-uplink-ingress-participant",
            isStatement = "Android IS an uplink/ingress participant: goal_execution_result, " +
                "operator_action_result, and diagnostics payloads are participant contributions " +
                "into V2 canonical governance/truth/closure chains.",
            isNotStatement = "Android is NOT an uplink canonical adjudicator: ingress into V2 " +
                "does not confer canonical authority on Android.",
            rationale = "Prevents ingress semantics from elevating Android to authority status."
        ),
        IsVsIsNotBoundary(
            boundaryId = "android-outward-consumption",
            isStatement = "Android IS the canonical output renderer for UI/product/operator/diagnostics " +
                "surfaces: presents bounded and canonical outputs to local consumers.",
            isNotStatement = "Android outward layers are NOT canonical authority re-assemblers: " +
                "UI, operator, product, and diagnostics surfaces must not reassemble authority, " +
                "truth, dispatch, or closure.",
            rationale = "Prevents outward surfaces from being used as authority re-entry points."
        )
    )

    // ── Six boundary summary classes ──────────────────────────────────────────

    /**
     * The six non-overlapping boundary summary classes that define Android's bounded
     * subject runtime.  Wire values are stable across schema versions.
     */
    enum class BoundarySummaryClass(val wireValue: String) {
        ANDROID_BOUNDED_SUBJECT_RUNTIME("android_bounded_subject_runtime_boundary"),
        LOCAL_VISIBLE_VS_CANONICAL_VISIBLE("local_visible_vs_canonical_visible_boundary"),
        CONTINUITY_RESTORE_REPLAY("continuity_restore_replay_boundary"),
        UPLINK_INGRESS_CONTRACT("uplink_ingress_contract_boundary"),
        OUTWARD_UI_CONSUMPTION("outward_ui_consumption_boundary"),
        NON_SOVEREIGN_ROLE("non_sovereign_role_boundary")
    }

    /**
     * A single boundary summary output entry, linking a [BoundarySummaryClass] to
     * a canonical summary string and the module anchors that implement it.
     *
     * The three boolean fields are always `false` — they exist as runtime-assertable
     * guards that ensure no boundary class silently claims authority it should not hold.
     */
    data class BoundarySummaryOutputEntry(
        val boundaryClass: BoundarySummaryClass,
        val summary: String,
        val moduleAnchors: Set<String>,
        val v2AlignmentPath: String,
        val allowsCanonicalTruthConvergence: Boolean = false,
        val allowsFinalClosureAdjudication: Boolean = false,
        val allowsPlatformSovereignty: Boolean = false
    )

    val BOUNDARY_SUMMARY_OUTPUT_ENTRIES: List<BoundarySummaryOutputEntry> = listOf(

        // ── 1. Android bounded subject runtime ───────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.ANDROID_BOUNDED_SUBJECT_RUNTIME,
            summary = "Android is the bounded relative subject runtime: local runtime host, " +
                "local continuity holder, local execution policy participant, local AI consumer host, " +
                "and distributed participant under V2 canonical center governance. " +
                "Android owns bounded local execution and lifecycle; V2 holds canonical authority.",
            moduleAnchors = setOf(
                "AndroidBoundedSubjectRuntimeContract",
                "AndroidBoundedSubjectPlatformBoundaryContract",
                "RuntimeController",
                "GalaxyConnectionService",
                "AutonomousExecutionPipeline"
            ),
            v2AlignmentPath = "v2:canonical_runtime_authority_chain/governance/truth_convergence"
        ),

        // ── 2. local-visible vs canonical-visible ────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.LOCAL_VISIBLE_VS_CANONICAL_VISIBLE,
            summary = "local-visible / runtime-visible / product-visible / diagnostics-visible " +
                "surfaces remain local-facing and must not be treated as canonical truth. " +
                "participant-truth / execution-result / continuity-state uplinks are canonical-participant " +
                "uplink surfaces that feed V2 canonical truth ingress only.",
            moduleAnchors = setOf(
                "AndroidBoundedSubjectRuntimeContract.LocalVisibleClass",
                "AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass",
                "AndroidBoundedSubjectRuntimeContract.OBSERVABILITY_BOUNDARY_ENTRIES",
                "AndroidBoundedSubjectRuntimeContract.classifyLocalSurface",
                "AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink"
            ),
            v2AlignmentPath = "v2:operator_board_diagnostics/evidence_projection_chain"
        ),

        // ── 3. continuity / restore / replay ────────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.CONTINUITY_RESTORE_REPLAY,
            summary = "Android owns bounded continuity/restore/replay state locally: " +
                "offline queue buffering, reconnect recovery, and continuity epoch management " +
                "are Android responsibilities. Android must uplink continuity state to V2 " +
                "canonical convergence without claiming final continuity authority or legality.",
            moduleAnchors = setOf(
                "AndroidContinuityIntegration",
                "UnifiedReplayRecoveryContract",
                "AndroidLifecycleRecoveryContract",
                "AndroidReconnectRecoveryParticipationContract",
                "OfflineQueueReplayPolicy",
                "AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.CONTINUITY_STATE_UPLINK",
                "DurableSessionContinuityRecord"
            ),
            v2AlignmentPath = "v2:canonical_continuity_legality/continuity_convergence_chain"
        ),

        // ── 4. uplink / ingress contract ─────────────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.UPLINK_INGRESS_CONTRACT,
            summary = "Uplink and ingress are participant contracts into V2 canonical " +
                "governance/truth/closure chains — not Android-owned canonical adjudication. " +
                "goal_execution_result / device_execution_event / device_state_snapshot / " +
                "operator_action_result / diagnostics_payload uplinks are classified through " +
                "layered uplink contracts and consumed by V2 acceptance/closure/truth chains.",
            moduleAnchors = setOf(
                "AndroidGovernanceExecutionPolicyIngressContract",
                "AndroidResultUplinkBoundaryContract",
                "AndroidDistributedTruthOwnershipUplinkContract",
                "AndroidCompletionClosureUplinkContract",
                "AndroidToolActionAuthorizationUplinkContract",
                "AndroidDiagnosticsFailureExplanationUplinkContract",
                "GalaxyConnectionService.sendGoalResult",
                "GalaxyWebSocketClient"
            ),
            v2AlignmentPath = "v2:canonical_truth_convergence/result_ingress/acceptance/closure_chain"
        ),

        // ── 5. outward / UI consumption ──────────────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.OUTWARD_UI_CONSUMPTION,
            summary = "Outward / UI / operator / diagnostics / product surfaces consume bounded " +
                "or canonical outputs only. No outward consumer may re-assemble canonical authority, " +
                "truth finalization, dispatch arbitration, or closure. " +
                "MainUiState / MainViewModel / UnifiedResultPresentation are consumption surfaces, " +
                "not authority surfaces.",
            moduleAnchors = setOf(
                "AndroidFinalSurfaceConvergenceContract",
                "AndroidBoundedSubjectPlatformBoundaryContract.BoundaryClass.OUTWARD_CONSUMPTION_BOUNDARY",
                "MainUiState",
                "MainViewModel",
                "UnifiedResultPresentation",
                "AndroidToolActionAuthorizationUplinkContract"
            ),
            v2AlignmentPath = "v2:final_integration/outward_composition/operator_product_consumption_chain"
        ),

        // ── 6. non-sovereign role ─────────────────────────────────────────────

        BoundarySummaryOutputEntry(
            boundaryClass = BoundarySummaryClass.NON_SOVEREIGN_ROLE,
            summary = "Android is non-sovereign in dual-repo governance: it does not perform " +
                "canonical truth convergence, final closure adjudication, or exercise platform sovereignty. " +
                "Android's authority is bounded to local runtime scope. " +
                "V2 canonical center retains canonical authority / truth finalization / dispatch arbitration " +
                "/ closure adjudication / platform governance.",
            moduleAnchors = setOf(
                "AndroidBoundedSubjectRuntimeContract.classifyAuthority",
                "AndroidBoundedSubjectPlatformBoundaryContract.QUASI_PLATFORM_STATE_DEFINITION",
                "AndroidBoundedSubjectPlatformBoundaryContract.ANDROID_FORMAL_DEFINITION",
                "AndroidBoundedSubjectRuntimeContract.BOUNDED_SUBJECT_RUNTIME_INVARIANTS"
            ),
            v2AlignmentPath = "v2:canonical_authority_center/governance/truth_finalization/closure"
        )
    )

    // ── restore / replay boundary explicit summary ───────────────────────────

    /**
     * Explicit restore / replay boundary summary anchored to module-level contracts.
     *
     * This is a specialisation of [BoundarySummaryClass.CONTINUITY_RESTORE_REPLAY] that
     * breaks the boundary down into the individual restore and replay sub-boundaries for
     * downstream contracts that need finer-grained citation.
     */
    data class RestoreReplayBoundarySummary(
        val subBoundaryId: String,
        val description: String,
        val moduleAnchors: Set<String>,
        val androidOwns: Boolean,
        val v2CanonicalAuthority: Boolean
    )

    val RESTORE_REPLAY_BOUNDARY_SUMMARIES: List<RestoreReplayBoundarySummary> = listOf(
        RestoreReplayBoundarySummary(
            subBoundaryId = "offline-queue-replay",
            description = "Android buffers offline task/goal messages locally and replays them " +
                "on reconnect — bounded local replay, not canonical replay authority.",
            moduleAnchors = setOf("OfflineQueueReplayPolicy", "GalaxyWebSocketClient"),
            androidOwns = true,
            v2CanonicalAuthority = false
        ),
        RestoreReplayBoundarySummary(
            subBoundaryId = "continuity-restore",
            description = "Android restores local runtime state (session epoch, continuity record) " +
                "on reconnect/recovery — bounded local restore, not canonical restore authority.",
            moduleAnchors = setOf(
                "AndroidContinuityIntegration",
                "DurableSessionContinuityRecord",
                "AndroidReconnectRecoveryParticipationContract"
            ),
            androidOwns = true,
            v2CanonicalAuthority = false
        ),
        RestoreReplayBoundarySummary(
            subBoundaryId = "lifecycle-recovery",
            description = "Android initiates lifecycle recovery (reconnect, process-recreate, takeover " +
                "handoff) based on local state — bounded local decision, uplinked to V2 for " +
                "canonical continuity legality adjudication.",
            moduleAnchors = setOf(
                "AndroidLifecycleRecoveryContract",
                "UnifiedReplayRecoveryContract",
                "AndroidRecoveryParticipationOwner"
            ),
            androidOwns = true,
            v2CanonicalAuthority = false
        ),
        RestoreReplayBoundarySummary(
            subBoundaryId = "continuity-state-uplink",
            description = "Android uplinks continuity state (device_state_snapshot, reconciliation " +
                "signals) to V2 canonical continuity legality chain — uplink is participant contribution, " +
                "not canonical authority claim.",
            moduleAnchors = setOf(
                "GalaxyConnectionService.sendDeviceStateSnapshot",
                "ReconciliationSignal",
                "AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.CONTINUITY_STATE_UPLINK"
            ),
            androidOwns = false,
            v2CanonicalAuthority = true
        )
    )

    // ── Anti-drift invariants ─────────────────────────────────────────────────

    /**
     * Machine-assertable invariants that prevent future Android-side authority drift.
     *
     * A violation of any invariant indicates that a PR has introduced a governance
     * inconsistency with the established bounded subject runtime boundary.
     */
    val ANTI_DRIFT_INVARIANTS: List<String> = listOf(
        "INVARIANT-ADI-1: All six boundary summary classes must be present " +
            "in BOUNDARY_SUMMARY_OUTPUT_ENTRIES and non-overlapping.",
        "INVARIANT-ADI-2: No boundary summary output entry may set " +
            "allowsCanonicalTruthConvergence=true, allowsFinalClosureAdjudication=true, " +
            "or allowsPlatformSovereignty=true.",
        "INVARIANT-ADI-3: All SYSTEM_ROLE_DECLARATIONS must have " +
            "isActiveInCurrentSystem=true — Android formally holds all six roles.",
        "INVARIANT-ADI-4: All IS_VS_IS_NOT_BOUNDARIES must include a non-blank " +
            "isNotStatement that explicitly excludes canonical authority claims.",
        "INVARIANT-ADI-5: CONTINUITY_RESTORE_REPLAY boundary must anchor at least one " +
            "AndroidContinuityIntegration or UnifiedReplayRecoveryContract module.",
        "INVARIANT-ADI-6: UPLINK_INGRESS_CONTRACT boundary must anchor " +
            "AndroidGovernanceExecutionPolicyIngressContract and at least one of the " +
            "uplink contracts (Result/Ownership/CompletionClosure/ToolAction/Diagnostics).",
        "INVARIANT-ADI-7: OUTWARD_UI_CONSUMPTION boundary must anchor " +
            "AndroidFinalSurfaceConvergenceContract.",
        "INVARIANT-ADI-8: NON_SOVEREIGN_ROLE boundary must anchor " +
            "AndroidBoundedSubjectPlatformBoundaryContract.QUASI_PLATFORM_STATE_DEFINITION.",
        "INVARIANT-ADI-9: RESTORE_REPLAY_BOUNDARY_SUMMARIES must include both " +
            "android-owned sub-boundaries (androidOwns=true) and the V2-authority " +
            "continuity-state-uplink sub-boundary (v2CanonicalAuthority=true)."
    )

    // ── V2 boundary narrative alignment map ──────────────────────────────────

    /**
     * Aligns every Android boundary summary class to the V2-side canonical narrative
     * path that governs the corresponding authority scope.
     *
     * This map is the single stable cross-repo citation surface: downstream PRs in
     * Android and V2 can reference it to verify alignment without consulting multiple
     * intermediate contracts.
     */
    val V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP: Map<String, String> = mapOf(
        "android_bounded_subject_runtime" to
            "v2:canonical_runtime_authority_chain/governance/truth_convergence",
        "local_visible_vs_canonical_visible" to
            "v2:operator_board_diagnostics/evidence_projection_chain",
        "continuity_restore_replay" to
            "v2:canonical_continuity_legality/continuity_convergence_chain",
        "uplink_ingress_contract" to
            "v2:canonical_truth_convergence/result_ingress/acceptance/closure_chain",
        "outward_ui_consumption" to
            "v2:final_integration/outward_composition/operator_product_consumption_chain",
        "non_sovereign_role" to
            "v2:canonical_authority_center/governance/truth_finalization/closure"
    )

    // ── Validation helpers ────────────────────────────────────────────────────

    /**
     * Returns `true` iff all six boundary summary classes are present and no entry
     * claims canonical truth convergence, final closure adjudication, or platform sovereignty.
     */
    fun validateCompleteFormalBoundarySummary(): Boolean =
        BoundarySummaryClass.values().all { cls ->
            BOUNDARY_SUMMARY_OUTPUT_ENTRIES.any { it.boundaryClass == cls }
        } &&
            BOUNDARY_SUMMARY_OUTPUT_ENTRIES.none {
                it.allowsCanonicalTruthConvergence ||
                    it.allowsFinalClosureAdjudication ||
                    it.allowsPlatformSovereignty
            }

    /**
     * Returns `true` iff all declared system roles are marked as active in the
     * current system.
     */
    fun validateAllSystemRolesActive(): Boolean =
        SYSTEM_ROLE_DECLARATIONS.all { it.isActiveInCurrentSystem }

    /**
     * Returns `true` iff every "is not" statement is non-blank (guards against
     * accidentally blank non-sovereign declarations).
     */
    fun validateAllIsNotStatementsNonBlank(): Boolean =
        IS_VS_IS_NOT_BOUNDARIES.all { it.isNotStatement.isNotBlank() }

    /**
     * Returns `true` iff all nine anti-drift invariants are present and non-blank.
     */
    fun validateAntiDriftInvariantCoverage(): Boolean =
        ANTI_DRIFT_INVARIANTS.size == 9 &&
            ANTI_DRIFT_INVARIANTS.all { it.isNotBlank() }

    /**
     * Returns the [BoundarySummaryOutputEntry] for the given [BoundarySummaryClass],
     * or `null` if not found (which would indicate a coverage gap).
     */
    fun forBoundaryClass(cls: BoundarySummaryClass): BoundarySummaryOutputEntry? =
        BOUNDARY_SUMMARY_OUTPUT_ENTRIES.firstOrNull { it.boundaryClass == cls }

    /**
     * Returns `true` iff the V2 alignment map covers every boundary summary class.
     */
    fun validateV2AlignmentMapCoverage(): Boolean =
        BoundarySummaryClass.values().all { cls ->
            V2_BOUNDARY_NARRATIVE_ALIGNMENT_MAP.containsKey(cls.wireValue.removeSuffix("_boundary"))
        }

    // ── Top-level summary constants ───────────────────────────────────────────

    /**
     * Canonical one-sentence description of Android's formal role in the dual-repo system.
     * Suitable for wire serialization, documentation headers, and test assertions.
     */
    const val ANDROID_FORMAL_ROLE_SUMMARY: String =
        "Android bounded relative subject runtime: local runtime host, local continuity holder, " +
            "local execution policy participant, local AI consumer host, and distributed participant — " +
            "bounded local authority under V2 canonical center governance; non-sovereign."

    /**
     * Canonical one-sentence statement of what Android is NOT.
     * Complements [ANDROID_FORMAL_ROLE_SUMMARY] to prevent authority drift.
     */
    const val ANDROID_NON_SOVEREIGN_STATEMENT: String =
        "Android is not a parallel canonical center, fully sovereign distributed authority node, " +
            "canonical truth finalizer, final closure adjudicator, dispatch arbitrator, " +
            "or platform sovereign — these roles belong exclusively to the V2 canonical center."

    const val DESCRIPTION: String =
        "PR-25Android formal boundary summary output contract: " +
            "canonical long-term-referenceable summary of Android bounded subject runtime role, " +
            "six boundary summary classes, restore/replay/uplink/outward/continuity boundaries, " +
            "explicit is/is-not declarations, anti-drift invariants, and V2 alignment map."
}
