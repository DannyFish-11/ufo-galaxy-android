package com.ufo.galaxy.runtime

/**
 * PR-53 — Hybrid/distributed participant capability boundary declaration for Android.
 *
 * Explicitly declares the implementation maturity of each hybrid and distributed execution
 * capability on the Android participant side — whether the capability is
 * [CapabilityMaturity.FULLY_WIRED] (end-to-end working) or
 * [CapabilityMaturity.CONTRACT_FIRST] (protocol accepted, partial implementation,
 * explicit non-silent response required).
 *
 * ## Problem addressed
 *
 * Prior to PR-53:
 * - Some hybrid capabilities (e.g. `HYBRID_EXECUTE`) sent a degrade reply without an explicit
 *   contract declaration that this was the intended behaviour, making it indistinguishable from
 *   an implementation error.
 * - V2 had no machine-readable way to determine which Android capabilities are operational vs
 *   intentionally limited.
 * - Silent degrade-only behaviour is ambiguous: V2 cannot distinguish "participant chose not to
 *   execute" from "capability not implemented."
 *
 * [HybridParticipantCapabilityBoundary] fills this gap by:
 * 1. Listing all hybrid/distributed execution capabilities as [HybridCapability] enum values.
 * 2. Classifying each as [CapabilityMaturity.FULLY_WIRED] or [CapabilityMaturity.CONTRACT_FIRST].
 * 3. Declaring the explicit response contract for each capability.
 * 4. Providing wire-tag constants ([RESULT_CAPABILITY_MATURITY_TAG]) so protocol responses can
 *    include the capability maturity context — **no silent empty-ok responses**.
 *
 * ## No-silent-degrade rule
 *
 * Any [CapabilityMaturity.CONTRACT_FIRST] capability MUST return [RESULT_CAPABILITY_MATURITY_TAG]
 * in its response payload with value [RESULT_MATURITY_CONTRACT_FIRST].  This distinguishes
 * intentional capability limitation from an execution failure and allows V2 to apply the correct
 * routing policy.
 *
 * ## Android as participant, not orchestrator
 *
 * This class does NOT change routing decisions — those belong to V2.  It only makes existing
 * Android-side capability behaviour explicit, testable, and observable.
 *
 * @see StagedMeshParticipationResult
 * @see ReconciliationSignal
 * @see RuntimeController
 * @see AppLifecycleParticipantBoundary
 */
object HybridParticipantCapabilityBoundary {

    // ── Hybrid capabilities ───────────────────────────────────────────────────

    /**
     * Android-side hybrid/distributed execution capabilities.
     *
     * @property wireValue Stable lowercase string used in protocol response tags and diagnostics.
     */
    enum class HybridCapability(val wireValue: String) {

        /**
         * Staged-mesh subtask execution (PR-32).
         *
         * Android receives a staged-mesh subtask from the V2 coordinator and executes it
         * using the local goal-execution pipeline.  The result is wrapped in a
         * [StagedMeshParticipationResult] and contributed via [AndroidSessionContribution].
         */
        STAGED_MESH_EXECUTION("staged_mesh_execution"),

        /**
         * Hybrid execution (`HYBRID_EXECUTE` message type).
         *
         * Android receives a `hybrid_execute` command from the gateway.  The payload is
         * accepted and parsed; a full hybrid executor is not yet implemented.  A
         * CONTRACT_FIRST response MUST be returned.
         */
        HYBRID_EXECUTE("hybrid_execute"),

        /**
         * RAG query execution (`RAG_QUERY` message type).
         *
         * Android receives a `rag_query` command from the gateway.  The payload is accepted
         * and logged; a full RAG pipeline is not implemented.  A CONTRACT_FIRST response
         * MUST be returned.
         */
        RAG_QUERY("rag_query"),

        /**
         * Code execution (`CODE_EXECUTE` message type).
         *
         * Android receives a `code_execute` command from the gateway.  The payload is accepted
         * and logged; sandbox execution is not implemented.  A CONTRACT_FIRST response MUST be
         * returned.
         */
        CODE_EXECUTE("code_execute"),

        /**
         * Barrier participation — acknowledging a distributed barrier/merge signal from V2.
         *
         * Android recognises barrier-crossing conditions through health/readiness state
         * transitions, reported via [ReconciliationSignal.PARTICIPANT_STATE] and
         * [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged].  The V2 coordinator
         * drives barrier tracking; Android provides the participant-side readiness signals.
         */
        BARRIER_PARTICIPATION("barrier_participation"),

        /**
         * Formation role rebalancing participation (PR-2).
         *
         * Android participates in coordinator-driven formation rebalancing by reporting
         * health/readiness state changes and accepting role reassignments through
         * [FormationParticipationRebalancer] and [FormationRebalanceEvent].
         */
        FORMATION_REBALANCE("formation_rebalance");

        companion object {
            /** All wire values for this enum.  Useful for stability assertions in tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /**
             * Returns the [HybridCapability] matching [wireValue], or `null` if not recognised.
             */
            fun fromValue(wireValue: String?): HybridCapability? =
                entries.firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── Capability maturity ───────────────────────────────────────────────────

    /**
     * Implementation maturity of a hybrid capability on the Android participant side.
     *
     * @property wireValue Stable lowercase string used in protocol response tags and diagnostics.
     */
    enum class CapabilityMaturity(val wireValue: String) {

        /**
         * Capability is fully wired end-to-end.
         *
         * Android accepts, executes, and returns a result through the standard protocol path.
         * V2 can rely on this capability for production dispatch.
         */
        FULLY_WIRED("fully_wired"),

        /**
         * Capability is contract-first: protocol accepted, implementation partial.
         *
         * Android accepts and parses the payload, logs the request, and returns an explicit
         * contract-first result with [RESULT_CAPABILITY_MATURITY_TAG] set to
         * [RESULT_MATURITY_CONTRACT_FIRST].
         *
         * **Silent degrade-only behaviour is NOT permitted.**  V2 must receive an explicit
         * maturity tag to distinguish intentional limitation from an execution failure.
         *
         * V2 should not schedule full workload to this capability; it may probe the contract
         * surface and apply fallback routing for production dispatch.
         */
        CONTRACT_FIRST("contract_first")
    }

    // ── Capability boundary entry ─────────────────────────────────────────────

    /**
     * Describes a single hybrid/distributed capability's maturity and response contract.
     *
     * @param capability       The [HybridCapability] described by this entry.
     * @param maturity         [CapabilityMaturity] classification.
     * @param responseContract Human-readable description of what Android returns for this capability.
     * @param rationale        Explanation of the current maturity level and what would promote it.
     */
    data class CapabilityBoundaryEntry(
        val capability: HybridCapability,
        val maturity: CapabilityMaturity,
        val responseContract: String,
        val rationale: String
    )

    // ── Capability boundary registry ──────────────────────────────────────────

    /**
     * Complete boundary registry for all Android hybrid/distributed capabilities.
     *
     * All six [HybridCapability] values are covered exactly once.
     */
    val entries: List<CapabilityBoundaryEntry> = listOf(
        CapabilityBoundaryEntry(
            capability = HybridCapability.STAGED_MESH_EXECUTION,
            maturity = CapabilityMaturity.FULLY_WIRED,
            responseContract = "StagedMeshParticipationResult via " +
                "AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK on success; " +
                "FAILURE / CANCELLED / BLOCKED variants handled by toSessionContribution().",
            rationale = "Staged-mesh execution is fully wired: StagedMeshExecutionTarget, " +
                "StagedMeshParticipationResult, and toSessionContribution() provide end-to-end " +
                "execution, result wrapping, and session-truth contribution. " +
                "Tested in Pr32StagedMeshTargetExecutionTest."
        ),
        CapabilityBoundaryEntry(
            capability = HybridCapability.HYBRID_EXECUTE,
            maturity = CapabilityMaturity.CONTRACT_FIRST,
            responseContract = "Explicit degrade reply with " +
                RESULT_CAPABILITY_MATURITY_TAG + "=" + RESULT_MATURITY_CONTRACT_FIRST +
                " and reason=" + REASON_HYBRID_EXECUTOR_NOT_IMPLEMENTED + ".",
            rationale = "HYBRID_EXECUTE payload is accepted and parsed; a full hybrid executor " +
                "is not implemented. A CONTRACT_FIRST response MUST be returned (not a silent " +
                "empty-ok). V2 should not schedule full hybrid workload against this participant " +
                "until maturity is promoted to FULLY_WIRED."
        ),
        CapabilityBoundaryEntry(
            capability = HybridCapability.RAG_QUERY,
            maturity = CapabilityMaturity.CONTRACT_FIRST,
            responseContract = "Empty result reply with " +
                RESULT_CAPABILITY_MATURITY_TAG + "=" + RESULT_MATURITY_CONTRACT_FIRST +
                " and reason=" + REASON_RAG_PIPELINE_NOT_IMPLEMENTED + ".",
            rationale = "RAG_QUERY payload is accepted and logged; the RAG pipeline is not " +
                "implemented. Reply MUST include the contract-first capability maturity tag. " +
                "V2 should not dispatch RAG queries to this participant in production."
        ),
        CapabilityBoundaryEntry(
            capability = HybridCapability.CODE_EXECUTE,
            maturity = CapabilityMaturity.CONTRACT_FIRST,
            responseContract = "Error reply with " +
                RESULT_CAPABILITY_MATURITY_TAG + "=" + RESULT_MATURITY_CONTRACT_FIRST +
                " and reason=" + REASON_SANDBOX_NOT_IMPLEMENTED + ".",
            rationale = "CODE_EXECUTE payload is accepted and logged; sandbox execution is not " +
                "implemented. Reply MUST include the contract-first capability maturity tag. " +
                "V2 should not dispatch code execution tasks to this participant in production."
        ),
        CapabilityBoundaryEntry(
            capability = HybridCapability.BARRIER_PARTICIPATION,
            maturity = CapabilityMaturity.FULLY_WIRED,
            responseContract = "V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged and " +
                "ReconciliationSignal.PARTICIPANT_STATE signals on health/readiness transitions " +
                "that cross barrier-relevant thresholds.",
            rationale = "Barrier participation is supported through health/readiness reporting: " +
                "Android emits ReconciliationSignal.PARTICIPANT_STATE on all health changes and " +
                "V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged on readiness transitions. " +
                "V2 drives barrier tracking decisions; Android provides the participant-side readiness signals."
        ),
        CapabilityBoundaryEntry(
            capability = HybridCapability.FORMATION_REBALANCE,
            maturity = CapabilityMaturity.FULLY_WIRED,
            responseContract = "FormationRebalanceEvent stream via RuntimeController.formationRebalanceEvents; " +
                "ParticipantHealthState and ParticipantReadinessState reporting via " +
                "ReconciliationSignal.PARTICIPANT_STATE.",
            rationale = "Formation rebalance participation is fully wired: " +
                "FormationParticipationRebalancer, FormationRebalanceEvent, and the health/readiness " +
                "reporting path provide the V2 coordinator with the signals needed to drive " +
                "rebalancing decisions. Tested via Pr2FormationRebalanceAndRecoveryHooksTest."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [CapabilityBoundaryEntry] for the given [capability].
     * All six [HybridCapability] values are guaranteed to have an entry.
     */
    fun boundaryFor(capability: HybridCapability): CapabilityBoundaryEntry =
        entries.first { it.capability == capability }

    /**
     * Returns `true` when [capability] is classified as [CapabilityMaturity.FULLY_WIRED].
     */
    fun isFullyWired(capability: HybridCapability): Boolean =
        boundaryFor(capability).maturity == CapabilityMaturity.FULLY_WIRED

    /**
     * Returns `true` when [capability] is classified as [CapabilityMaturity.CONTRACT_FIRST].
     */
    fun isContractFirst(capability: HybridCapability): Boolean =
        boundaryFor(capability).maturity == CapabilityMaturity.CONTRACT_FIRST

    /**
     * Returns all capabilities with [CapabilityMaturity.FULLY_WIRED] maturity.
     */
    val fullyWiredCapabilities: List<HybridCapability> =
        entries.filter { it.maturity == CapabilityMaturity.FULLY_WIRED }.map { it.capability }

    /**
     * Returns all capabilities with [CapabilityMaturity.CONTRACT_FIRST] maturity.
     */
    val contractFirstCapabilities: List<HybridCapability> =
        entries.filter { it.maturity == CapabilityMaturity.CONTRACT_FIRST }.map { it.capability }

    // ── Protocol response tag constants ───────────────────────────────────────

    /**
     * Wire tag key for including capability maturity in protocol response payloads.
     *
     * [CapabilityMaturity.CONTRACT_FIRST] capabilities MUST include this key in their
     * response payload to avoid silent degrade behaviour.  The value must be
     * [RESULT_MATURITY_CONTRACT_FIRST].
     */
    const val RESULT_CAPABILITY_MATURITY_TAG = "capability_maturity"

    /**
     * Wire value for [RESULT_CAPABILITY_MATURITY_TAG] when the capability is
     * [CapabilityMaturity.CONTRACT_FIRST].
     *
     * V2 should interpret this value as: "Protocol accepted; implementation partial;
     * do not dispatch full production workload to this capability yet."
     */
    const val RESULT_MATURITY_CONTRACT_FIRST = "contract_first"

    /**
     * Wire value for [RESULT_CAPABILITY_MATURITY_TAG] when the capability is
     * [CapabilityMaturity.FULLY_WIRED].
     */
    const val RESULT_MATURITY_FULLY_WIRED = "fully_wired"

    /**
     * Wire reason value for a [HybridCapability.HYBRID_EXECUTE] CONTRACT_FIRST response.
     *
     * Include in the response payload's `reason` or `error` field to allow V2 to
     * distinguish this intentional limitation from an execution failure.
     */
    const val REASON_HYBRID_EXECUTOR_NOT_IMPLEMENTED = "hybrid_executor_not_implemented"

    /**
     * Wire reason value for a [HybridCapability.RAG_QUERY] CONTRACT_FIRST response.
     */
    const val REASON_RAG_PIPELINE_NOT_IMPLEMENTED = "rag_pipeline_not_implemented"

    /**
     * Wire reason value for a [HybridCapability.CODE_EXECUTE] CONTRACT_FIRST response.
     */
    const val REASON_SANDBOX_NOT_IMPLEMENTED = "sandbox_not_implemented"

    /**
     * Expected number of capability boundary entries in this registry.
     * Must equal the number of [HybridCapability] values.
     */
    const val ENTRY_COUNT: Int = 6

    /**
     * PR number that introduced this capability boundary declaration.
     */
    const val INTRODUCED_PR: Int = 53
}
