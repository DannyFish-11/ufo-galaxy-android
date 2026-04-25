package com.ufo.galaxy.runtime

/**
 * PR-4 (Android) — Canonical classification of Android evaluator artifact emission
 * semantics for V2 canonical readiness and governance integration.
 *
 * [EvaluatorArtifactEmissionSemantics] is the authoritative Android-side reference for how
 * each evaluator artifact category should be classified and consumed by V2.  It provides:
 *
 *  - A stable [EmissionClass] taxonomy distinguishing canonical participant evidence from
 *    advisory / observation-only signals and compat-oriented signals.
 *  - A [EvaluatorEntry] record for each Android evaluator that emits artifacts toward V2.
 *  - The [REGISTRY] list as a machine-readable inventory of Android-originated artifact
 *    emission points that can be used for V2-side routing, audit, and policy decisions.
 *
 * ## Background and motivation
 *
 * Android PR-4 tightened artifact emission from real runtime paths.  To help V2 correctly
 * classify incoming Android-originated artifacts, this file establishes which artifacts
 * represent **canonical participant evidence** (binding gate inputs) versus
 * **advisory / observation-only signals** (informational, V2 retains authority) versus
 * **compat / legacy-oriented signals** (must not be treated as canonical by default).
 *
 * ## Artifact emission chain (all paths established in Android PR-4)
 *
 * ```
 * DelegatedRuntimeReadinessEvaluator.buildSnapshot()
 *   → GalaxyConnectionService.sendDeviceReadinessReport()
 *   → MsgType.DEVICE_READINESS_REPORT  [CANONICAL_PARTICIPANT_EVIDENCE]
 *
 * DelegatedRuntimePostGraduationGovernanceEvaluator.buildSnapshot()
 *   → GalaxyConnectionService.sendDeviceGovernanceReport()
 *   → MsgType.DEVICE_GOVERNANCE_REPORT  [CANONICAL_PARTICIPANT_EVIDENCE]
 *
 * DelegatedRuntimeAcceptanceEvaluator.buildSnapshot()
 *   → GalaxyConnectionService.sendDeviceAcceptanceReport()
 *   → MsgType.DEVICE_ACCEPTANCE_REPORT  [CANONICAL_PARTICIPANT_EVIDENCE]
 *
 * DelegatedRuntimeStrategyEvaluator.buildSnapshot()
 *   → GalaxyConnectionService.sendDeviceStrategyReport()
 *   → MsgType.DEVICE_STRATEGY_REPORT    [ADVISORY_OBSERVATION_ONLY]
 *
 * RuntimeController.reconciliationSignals (SharedFlow)
 *   → GalaxyConnectionService.sendReconciliationSignal()
 *   → MsgType.RECONCILIATION_SIGNAL     [CANONICAL_PARTICIPANT_EVIDENCE]
 * ```
 *
 * @see DelegatedRuntimeReadinessEvaluator
 * @see DelegatedRuntimePostGraduationGovernanceEvaluator
 * @see DelegatedRuntimeAcceptanceEvaluator
 * @see DelegatedRuntimeStrategyEvaluator
 * @see com.ufo.galaxy.service.GalaxyConnectionService
 */
object EvaluatorArtifactEmissionSemantics {

    /**
     * Emission class taxonomy for Android-originated evaluator artifacts.
     *
     * V2 MUST use [EmissionClass] when classifying incoming Android artifact payloads:
     *  - [CANONICAL_PARTICIPANT_EVIDENCE] artifacts are binding gate inputs; V2 must respect
     *    them when making readiness / governance / acceptance decisions.
     *  - [ADVISORY_OBSERVATION_ONLY] artifacts are informational signals; V2 retains final
     *    authority and must not gate on them without an explicit policy decision.
     *  - [COMPAT_LEGACY_ORIENTED] artifacts carry legacy semantics; V2 must not treat them
     *    as canonical by default.
     *
     * @property wireLabel  Stable label used in structured log entries and validation docs.
     */
    enum class EmissionClass(val wireLabel: String) {
        /**
         * Canonical participant evidence.
         *
         * The artifact is produced by an authoritative Android-side evaluator from real
         * runtime paths and represents a binding gate input for V2 canonical readiness /
         * governance / acceptance flows.  V2 must respect this artifact when forming gate
         * decisions.
         *
         * Examples: readiness report, governance report, acceptance report, reconciliation
         * signal RUNTIME_TRUTH_SNAPSHOT.
         */
        CANONICAL_PARTICIPANT_EVIDENCE("canonical_participant_evidence"),

        /**
         * Advisory / observation-only signal.
         *
         * The artifact provides informational program-level observation from Android, but
         * V2 retains full authority over the corresponding decision layer.  V2 should treat
         * this as a risk signal or posture observation, not a binding gate input.
         *
         * Examples: strategy report (V2 retains strategy authority).
         */
        ADVISORY_OBSERVATION_ONLY("advisory_observation_only"),

        /**
         * Compat / legacy-oriented signal.
         *
         * The artifact originates from a compat or legacy path.  V2 must not treat it as
         * canonical participant evidence by default.  Future retirement is tracked via
         * [CompatibilitySurfaceRetirementRegistry].
         */
        COMPAT_LEGACY_ORIENTED("compat_legacy_oriented")
    }

    /**
     * Represents a single Android evaluator artifact emission point.
     *
     * Each entry documents:
     *  - which evaluator produces the artifact
     *  - which [com.ufo.galaxy.protocol.MsgType] carries it to V2
     *  - which [EmissionClass] V2 should use to classify it
     *  - the real send path through the transport layer
     *  - the PR that established the emission path
     *
     * @property evaluatorClass   Simple class name of the Android evaluator.
     * @property msgTypeWireValue Wire value of the [com.ufo.galaxy.protocol.MsgType]
     *                            used to transmit the artifact to V2.
     * @property emissionClass    [EmissionClass] for V2-side classification.
     * @property sendPath         Human-readable description of the runtime send path.
     * @property establishedPr    PR number that established this emission path.
     * @property deferredWork     Optional description of intentionally deferred follow-up work.
     */
    data class EvaluatorEntry(
        val evaluatorClass: String,
        val msgTypeWireValue: String,
        val emissionClass: EmissionClass,
        val sendPath: String,
        val establishedPr: Int,
        val deferredWork: String? = null
    )

    /**
     * Registry of all Android evaluator artifact emission points wired to the transport
     * layer as of Android PR-4.
     *
     * V2 can use this registry to:
     *  1. Enumerate which Android artifact types to expect.
     *  2. Classify each incoming artifact by [EmissionClass] for gate policy.
     *  3. Detect if an expected artifact type is missing (possible evaluator regression).
     */
    val REGISTRY: List<EvaluatorEntry> = listOf(

        EvaluatorEntry(
            evaluatorClass = "DelegatedRuntimeReadinessEvaluator",
            msgTypeWireValue = "device_readiness_report",
            emissionClass = EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            sendPath = "DelegatedRuntimeReadinessEvaluator.buildSnapshot() → " +
                "GalaxyConnectionService.sendDeviceReadinessReport() → " +
                "MsgType.DEVICE_READINESS_REPORT",
            establishedPr = 66,
            deferredWork = "Dimension-state change triggers (beyond initial service-start " +
                "emission) are deferred; follow-up PR should wire per-dimension change callbacks."
        ),

        EvaluatorEntry(
            evaluatorClass = "DelegatedRuntimePostGraduationGovernanceEvaluator",
            msgTypeWireValue = "device_governance_report",
            emissionClass = EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            sendPath = "DelegatedRuntimePostGraduationGovernanceEvaluator.buildSnapshot() → " +
                "GalaxyConnectionService.sendDeviceGovernanceReport() → " +
                "MsgType.DEVICE_GOVERNANCE_REPORT",
            establishedPr = 66,
            deferredWork = "Dimension-state change triggers and post-graduation lifecycle " +
                "wiring are deferred; follow-up PR should connect governance observation " +
                "signals from real subsystem callbacks."
        ),

        EvaluatorEntry(
            evaluatorClass = "DelegatedRuntimeAcceptanceEvaluator",
            msgTypeWireValue = "device_acceptance_report",
            emissionClass = EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            sendPath = "DelegatedRuntimeAcceptanceEvaluator.buildSnapshot() → " +
                "GalaxyConnectionService.sendDeviceAcceptanceReport() → " +
                "MsgType.DEVICE_ACCEPTANCE_REPORT",
            establishedPr = 66,
            deferredWork = "Evidence dimension population from real lifecycle callbacks " +
                "(readiness prerequisite wiring, per-dimension evidence signals from " +
                "recovery / truth / result / event / compat owners) is deferred to a " +
                "follow-up PR that wires the full acceptance evidence chain."
        ),

        EvaluatorEntry(
            evaluatorClass = "DelegatedRuntimeStrategyEvaluator",
            msgTypeWireValue = "device_strategy_report",
            emissionClass = EmissionClass.ADVISORY_OBSERVATION_ONLY,
            sendPath = "DelegatedRuntimeStrategyEvaluator.buildSnapshot() → " +
                "GalaxyConnectionService.sendDeviceStrategyReport() → " +
                "MsgType.DEVICE_STRATEGY_REPORT",
            establishedPr = 66,
            deferredWork = "Strategy dimension population from real program-level signals " +
                "is deferred; current emission carries all-UNKNOWN posture which is a " +
                "valid advisory signal indicating no strategy risk has been detected yet."
        ),

        EvaluatorEntry(
            evaluatorClass = "RuntimeController (reconciliationSignals)",
            msgTypeWireValue = "reconciliation_signal",
            emissionClass = EmissionClass.CANONICAL_PARTICIPANT_EVIDENCE,
            sendPath = "RuntimeController.reconciliationSignals (SharedFlow) → " +
                "GalaxyConnectionService.sendReconciliationSignal() → " +
                "MsgType.RECONCILIATION_SIGNAL",
            establishedPr = 65,
            deferredWork = null
        )
    )

    /**
     * Returns the [EvaluatorEntry] for the given [msgTypeWireValue], or `null` when not found.
     *
     * V2 can use this to resolve the [EmissionClass] for an incoming Android artifact.
     */
    fun findByMsgType(msgTypeWireValue: String): EvaluatorEntry? =
        REGISTRY.firstOrNull { it.msgTypeWireValue == msgTypeWireValue }
}
