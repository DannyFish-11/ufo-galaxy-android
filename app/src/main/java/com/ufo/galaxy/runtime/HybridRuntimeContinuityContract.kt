package com.ufo.galaxy.runtime

/**
 * PR-5Android — Hybrid runtime continuity contract for the Android participant.
 *
 * Declares the explicit continuity semantics for hybrid execution state across Android
 * lifecycle disruptions (reconnect, process kill, background kill, memory pressure).
 *
 * ## Problem addressed
 *
 * [HybridParticipantCapabilityBoundary] (PR-53) declares the maturity classification of each
 * hybrid capability (FULLY_WIRED vs CONTRACT_FIRST) and the no-silent-degrade rule.
 * However, it does not address what happens to the Android participant's hybrid execution
 * context when a lifecycle disruption occurs mid-hybrid-execution:
 *
 * - If Android is executing a staged-mesh subtask and the WS drops, does the subtask result
 *   survive the reconnect?
 * - If Android sends a CONTRACT_FIRST degrade response for HYBRID_EXECUTE and then the process
 *   is killed, does V2 need to resend the HYBRID_EXECUTE command?
 * - After a process kill, should Android re-announce its capability maturity classification, or
 *   does V2 infer it from the new DeviceConnected event?
 *
 * [HybridRuntimeContinuityContract] answers these questions by:
 * 1. Declaring [HybridContinuityTier] — how durable each hybrid capability's execution
 *    context is across lifecycle disruptions.
 * 2. Declaring [PostDisruptionHybridBehavior] — what Android MUST do after reconnecting
 *    following a lifecycle disruption that interrupted hybrid execution.
 * 3. Providing explicit [HybridCapabilityContinuityEntry] records for each capability.
 *
 * ## Constraints
 *
 * - Android MUST NOT attempt to re-transmit or resume hybrid execution results independently.
 *   All hybrid execution recovery decisions belong to V2.
 * - Android MUST re-announce CONTRACT_FIRST capabilities after a process kill so V2 can
 *   update its routing policy for the new participation era.
 * - Android MUST NOT autonomously transition from CONTRACT_FIRST to FULLY_WIRED maturity
 *   after a lifecycle recovery — maturity is a static declaration, not a recovery outcome.
 *
 * @see HybridParticipantCapabilityBoundary
 * @see AppLifecycleParticipantBoundary
 * @see ParticipantPostureLifecycleBoundary
 * @see ContinuityRecoveryContext
 */
object HybridRuntimeContinuityContract {

    // ── Hybrid continuity tier ────────────────────────────────────────────────

    /**
     * Durability of a hybrid capability's execution context across Android lifecycle disruptions.
     *
     * Distinct from [ParticipantRecoveryReadinessSnapshot.DurabilityTier], which describes
     * runtime identity fields.  [HybridContinuityTier] is specific to hybrid execution state.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class HybridContinuityTier(val wireValue: String) {

        /**
         * The hybrid capability's execution context is fully stateless on the Android side.
         * Android does not retain any state between invocations.  After any lifecycle disruption,
         * V2 may re-invoke the capability independently without any state reconciliation.
         *
         * Typical for: CONTRACT_FIRST capabilities that send a degrade response and then
         * have no pending state.
         */
        STATELESS("stateless"),

        /**
         * The hybrid capability produces a single in-memory result that is valid for the
         * lifetime of the current WS connection.  The result survives WS reconnects if the
         * process stays alive, but is lost on process kill.
         *
         * After a WS reconnect: Android MAY re-report the result if V2 re-queries.
         * After a process kill: V2 must re-invoke the capability in the new era.
         */
        SESSION_SCOPED("session_scoped"),

        /**
         * The hybrid capability's execution context is ephemeral per-invocation.
         * Any invocation in progress at the time of a lifecycle disruption is considered
         * lost; V2 must re-invoke if needed.
         *
         * Typical for: capabilities that execute a full pipeline per request (e.g. staged-mesh
         * subtask execution) where partial results are not reusable.
         */
        INVOCATION_SCOPED("invocation_scoped")
    }

    // ── Post-disruption hybrid behavior ───────────────────────────────────────

    /**
     * What Android MUST do after reconnecting following a lifecycle disruption that
     * interrupted hybrid execution.
     *
     * @property wireValue Stable lowercase string used in diagnostics payloads.
     */
    enum class PostDisruptionHybridBehavior(val wireValue: String) {

        /**
         * Android must re-announce its capability maturity tag for this capability after
         * reconnect, via [ReconciliationSignal.RUNTIME_TRUTH_SNAPSHOT] or equivalent.
         *
         * This is required so V2 can update its routing table for the new participation era.
         */
        REANNOUNCE_CAPABILITY("reannounce_capability"),

        /**
         * Android has no re-announcement obligation.  V2 infers the capability maturity
         * from the AndroidParticipantRuntimeTruth snapshot published on reconnect.
         *
         * Typical for stateless CONTRACT_FIRST capabilities where the static maturity
         * classification is sufficient.
         */
        NO_REANNOUNCEMENT_NEEDED("no_reannouncement_needed"),

        /**
         * Android must wait for V2 to re-invoke the capability before resuming.
         * Android MUST NOT autonomously retry or resume the prior invocation.
         *
         * Typical for invocation-scoped capabilities where the prior invocation was
         * interrupted and V2 decides whether to re-dispatch.
         */
        AWAIT_V2_REINVOCATION("await_v2_reinvocation")
    }

    // ── Hybrid capability continuity entry ───────────────────────────────────

    /**
     * Continuity semantics for a single hybrid capability across lifecycle disruptions.
     *
     * @param capability                The [HybridParticipantCapabilityBoundary.HybridCapability] described by this entry.
     * @param continuityTier            How durable this capability's execution context is ([HybridContinuityTier]).
     * @param survivesWsReconnect       `true` when the capability's execution context survives a WS reconnect
     *                                  within the same activation era (process alive).
     * @param survivesProcessKill       `true` when the capability's execution context survives a process kill.
     *                                  Always `false` for current Android hybrid capabilities (no on-disk hybrid state).
     * @param postDisruptionBehavior    What Android MUST do after reconnecting when this capability's context
     *                                  was disrupted ([PostDisruptionHybridBehavior]).
     * @param v2ExpectedAction          What V2 SHOULD do when it detects Android's hybrid context was disrupted.
     * @param rationale                 Explanation of the continuity semantics.
     */
    data class HybridCapabilityContinuityEntry(
        val capability: HybridParticipantCapabilityBoundary.HybridCapability,
        val continuityTier: HybridContinuityTier,
        val survivesWsReconnect: Boolean,
        val survivesProcessKill: Boolean,
        val postDisruptionBehavior: PostDisruptionHybridBehavior,
        val v2ExpectedAction: String,
        val rationale: String
    )

    // ── Hybrid continuity registry ────────────────────────────────────────────

    /**
     * Complete hybrid runtime continuity registry for all Android hybrid capabilities.
     *
     * All six [HybridParticipantCapabilityBoundary.HybridCapability] values are covered exactly once.
     */
    val entries: List<HybridCapabilityContinuityEntry> = listOf(

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION,
            continuityTier = HybridContinuityTier.INVOCATION_SCOPED,
            survivesWsReconnect = false,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.AWAIT_V2_REINVOCATION,
            v2ExpectedAction = "V2 must check whether the in-flight staged-mesh subtask received a " +
                "StagedMeshParticipationResult before the disruption. If not, V2 must re-dispatch " +
                "the subtask to another eligible participant or re-send to Android in the new era.",
            rationale = "Staged-mesh subtask execution is per-invocation: the result is produced once " +
                "per task dispatch and reported via AndroidSessionContribution. If the WS drops mid-execution " +
                "or the process is killed, the result is lost. Android MUST NOT re-transmit a prior result " +
                "after reconnect — V2 must re-invoke if needed. Android awaits V2 re-dispatch."
        ),

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE,
            continuityTier = HybridContinuityTier.STATELESS,
            survivesWsReconnect = true,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY,
            v2ExpectedAction = "After a WS reconnect, V2 may re-send a HYBRID_EXECUTE command if needed. " +
                "Android will respond with the same CONTRACT_FIRST degrade reply. " +
                "After process kill, V2 should re-read the capability maturity from the " +
                "AndroidParticipantRuntimeTruth snapshot published on DeviceConnected.",
            rationale = "HYBRID_EXECUTE is stateless on the Android side: Android accepts the payload, " +
                "returns a CONTRACT_FIRST degrade reply, and retains no execution state. " +
                "The degrade reply does not need to be retransmitted after WS reconnect — " +
                "V2 will re-issue if it needs the response. After process kill, Android must " +
                "re-announce its CONTRACT_FIRST maturity via the DeviceConnected truth snapshot."
        ),

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY,
            continuityTier = HybridContinuityTier.STATELESS,
            survivesWsReconnect = true,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY,
            v2ExpectedAction = "After any lifecycle disruption, V2 should read Android's RAG_QUERY " +
                "capability maturity from the published AndroidParticipantRuntimeTruth snapshot. " +
                "Android will always respond to new RAG_QUERY invocations with a CONTRACT_FIRST reply.",
            rationale = "RAG_QUERY is stateless: Android logs the request and returns CONTRACT_FIRST. " +
                "No execution state is retained between invocations. " +
                "Capability maturity is static and must be re-announced after process kill."
        ),

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE,
            continuityTier = HybridContinuityTier.STATELESS,
            survivesWsReconnect = true,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY,
            v2ExpectedAction = "After any lifecycle disruption, V2 should read Android's CODE_EXECUTE " +
                "capability maturity from the published AndroidParticipantRuntimeTruth snapshot. " +
                "Android will always respond to new CODE_EXECUTE invocations with a CONTRACT_FIRST reply.",
            rationale = "CODE_EXECUTE is stateless: Android logs the request and returns CONTRACT_FIRST. " +
                "No execution state is retained between invocations. " +
                "Capability maturity is static and must be re-announced after process kill."
        ),

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION,
            continuityTier = HybridContinuityTier.SESSION_SCOPED,
            survivesWsReconnect = true,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY,
            v2ExpectedAction = "After a WS reconnect, V2 will receive a fresh " +
                "V2MultiDeviceLifecycleEvent.DeviceReconnected and a new ReconciliationSignal.PARTICIPANT_STATE " +
                "with the current ParticipantHealthState. V2 must re-evaluate barrier state based on the " +
                "fresh health/readiness signals. After process kill (DeviceConnected), V2 must re-evaluate " +
                "barrier state from scratch for the new participation era.",
            rationale = "Barrier participation is session-scoped: health/readiness signals are re-reported " +
                "on each reconnect (WS reconnect increments epoch; process kill starts new era). " +
                "V2 re-evaluates barrier thresholds from the re-reported signals. " +
                "Android does not hold cross-participant barrier state — that belongs to V2."
        ),

        HybridCapabilityContinuityEntry(
            capability = HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE,
            continuityTier = HybridContinuityTier.SESSION_SCOPED,
            survivesWsReconnect = true,
            survivesProcessKill = false,
            postDisruptionBehavior = PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY,
            v2ExpectedAction = "After a WS reconnect, V2 will receive fresh health/readiness signals via " +
                "ReconciliationSignal.PARTICIPANT_STATE. V2 may trigger a formation rebalance based on " +
                "these signals. After process kill, V2 receives DeviceConnected and a fresh truth snapshot; " +
                "it may re-assign the Android participant's formation role for the new era.",
            rationale = "Formation rebalance participation is session-scoped: role assignments and health " +
                "signals are re-reported on reconnect. " +
                "FormationRebalanceEvent history is not preserved across reconnects or process kills. " +
                "V2 drives all formation reassignment decisions; Android only re-reports its health/readiness."
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [HybridCapabilityContinuityEntry] for the given [capability].
     * All six [HybridParticipantCapabilityBoundary.HybridCapability] values are guaranteed to have an entry.
     */
    fun continuityFor(capability: HybridParticipantCapabilityBoundary.HybridCapability): HybridCapabilityContinuityEntry =
        entries.first { it.capability == capability }

    /**
     * Returns all capabilities whose execution context survives a WS reconnect within the
     * same activation era (process alive).
     */
    val survivesWsReconnectCapabilities: List<HybridParticipantCapabilityBoundary.HybridCapability> =
        entries.filter { it.survivesWsReconnect }.map { it.capability }

    /**
     * Returns all capabilities whose execution context does NOT survive a WS reconnect.
     * These capabilities must be re-invoked by V2 after reconnect if needed.
     */
    val lostOnWsReconnectCapabilities: List<HybridParticipantCapabilityBoundary.HybridCapability> =
        entries.filter { !it.survivesWsReconnect }.map { it.capability }

    /**
     * Returns all capabilities that require re-announcement after a process kill.
     * After process kill, Android must re-emit capability maturity tags so V2 can update its
     * routing policy for the new participation era.
     */
    val requiresReannounceAfterProcessKill: List<HybridParticipantCapabilityBoundary.HybridCapability> =
        entries.filter {
            it.postDisruptionBehavior == PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY
        }.map { it.capability }

    // ── Wire-key constants ────────────────────────────────────────────────────

    /** Wire key for the hybrid continuity tier in diagnostics payloads. */
    const val KEY_HYBRID_CONTINUITY_TIER = "hybrid_continuity_tier"

    /** Wire key for the post-disruption hybrid behavior in diagnostics payloads. */
    const val KEY_POST_DISRUPTION_BEHAVIOR = "post_disruption_hybrid_behavior"

    /** Wire key for the WS-reconnect survival flag in diagnostics payloads. */
    const val KEY_SURVIVES_WS_RECONNECT = "hybrid_survives_ws_reconnect"

    /**
     * Expected number of hybrid capability continuity entries.
     * Must equal the number of [HybridParticipantCapabilityBoundary.HybridCapability] values.
     */
    const val ENTRY_COUNT: Int = 6

    /**
     * PR number that introduced this hybrid runtime continuity contract.
     */
    const val INTRODUCED_PR: String = "PR-5Android"
}
