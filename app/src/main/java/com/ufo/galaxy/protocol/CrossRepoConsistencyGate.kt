package com.ufo.galaxy.protocol

import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import com.ufo.galaxy.runtime.ReconciliationSignal
import com.ufo.galaxy.runtime.RuntimeObservabilityMetadata
import com.ufo.galaxy.runtime.StagedMeshParticipationResult
import com.ufo.galaxy.runtime.CanonicalSessionFamily

/**
 * PR-12 — Cross-Repository Consistency Gates.
 *
 * Provides automated, CI-friendly consistency gates for the most important shared
 * semantic surfaces between the Android repository and the center repository.
 *
 * ## Purpose
 *
 * Prior to this class, consistency between Android and center depended on:
 *  - Documentation review
 *  - Implicit reviewer memory of what the canonical values are
 *  - Manual mapping maintenance
 *
 * This class turns the most critical surfaces into **executable gates** that verify at
 * test/CI time that:
 *
 *  1. Every wireValue declared in an Android enum is covered by the corresponding
 *     [ConsistencyRule] (canonical or transitional alias).
 *  2. Every canonical value declared in a [ConsistencyRule] is realized by an actual
 *     Android enum entry (no stale phantom values in the rule).
 *  3. Transitional aliases are explicitly registered — not merely tolerated implicitly.
 *  4. A newly introduced wireValue that hasn't been added to the rule is detected as a
 *     [GateViolationType.UNDECLARED_ENUM_VALUE] violation, forcing the author to either
 *     add it as a canonical value or explicitly register it as a transitional alias.
 *
 * ## Usage
 *
 * Run all gates at once for a CI summary:
 * ```kotlin
 * val report = CrossRepoConsistencyGate.runAllGates()
 * check(report.overallOutcome == GateOutcome.PASS) { report.failureSummary() }
 * ```
 *
 * Or run a specific surface gate:
 * ```kotlin
 * val result = CrossRepoConsistencyGate.checkReconnectRecoveryStates()
 * check(result.outcome == GateOutcome.PASS) { result.summary }
 * ```
 *
 * ## Relationship to [UgcpProtocolConsistencyRules]
 *
 * [UgcpProtocolConsistencyRules] is the **rule registry** — it declares what values are
 * canonical and what aliases are tolerated.
 * [CrossRepoConsistencyGate] is the **enforcement layer** — it checks that the live enum
 * wireValues in the Android source actually match what the rule registry declares.
 */

// ── Gate outcome types ────────────────────────────────────────────────────────

/**
 * Pass/fail outcome of a single consistency gate.
 */
enum class GateOutcome {
    /** All checks on this surface passed. */
    PASS,
    /** One or more violations were detected on this surface. */
    FAIL
}

/**
 * Classification of a gate violation.
 */
enum class GateViolationType {
    /**
     * A wireValue exists in the Android enum but is not listed as a canonical value
     * or a transitional alias in the [ConsistencyRule].  This means a new enum value
     * was introduced without updating the rule — the primary vector for silent drift.
     */
    UNDECLARED_ENUM_VALUE,

    /**
     * The [ConsistencyRule] declares a canonical value that is not realized by any
     * current Android enum entry.  This may indicate a stale rule (the enum was
     * updated but the rule was not pruned) or a missing enum entry.
     */
    STALE_CANONICAL_VALUE,

    /**
     * The [ConsistencyRule] declares a transitional alias whose [TransitionalAlias.canonicalTarget]
     * is not in the surface's canonical value set.  This indicates the rule itself is
     * internally inconsistent.
     */
    ALIAS_TARGET_NOT_CANONICAL
}

/**
 * A single violation found by a consistency gate.
 *
 * @property surface         The protocol surface where the violation was found.
 * @property violationType   The category of the violation.
 * @property detail          Human-readable description of the specific violation.
 */
data class GateViolation(
    val surface: ProtocolSurface,
    val violationType: GateViolationType,
    val detail: String
)

/**
 * Outcome of running the consistency gate for one [ProtocolSurface].
 *
 * @property surface     The protocol surface this result covers.
 * @property outcome     [GateOutcome.PASS] or [GateOutcome.FAIL].
 * @property violations  The violations found, if any.
 * @property summary     A human-readable one-line summary of the gate result.
 */
data class GateSurfaceResult(
    val surface: ProtocolSurface,
    val outcome: GateOutcome,
    val violations: List<GateViolation>,
    val summary: String
)

/**
 * Aggregated report produced by [CrossRepoConsistencyGate.runAllGates].
 *
 * @property results         Per-surface gate results, one entry per registered [ProtocolSurface].
 * @property overallOutcome  [GateOutcome.PASS] if all surfaces passed; [GateOutcome.FAIL] otherwise.
 * @property passingCount    Number of surfaces with [GateOutcome.PASS].
 * @property failingCount    Number of surfaces with [GateOutcome.FAIL].
 */
data class GateReport(
    val results: List<GateSurfaceResult>,
    val overallOutcome: GateOutcome,
    val passingCount: Int,
    val failingCount: Int
) {
    /**
     * Returns a multi-line human-readable failure summary that lists all violations
     * across all failing surfaces.  Returns an empty string when [overallOutcome] is [GateOutcome.PASS].
     */
    fun failureSummary(): String {
        if (overallOutcome == GateOutcome.PASS) return ""
        val sb = StringBuilder("CrossRepoConsistencyGate FAILED ($failingCount surface(s)):\n")
        for (result in results.filter { it.outcome == GateOutcome.FAIL }) {
            sb.append("  [${result.surface.name}] ${result.summary}\n")
            for (v in result.violations) {
                sb.append("    • ${v.violationType}: ${v.detail}\n")
            }
        }
        return sb.toString()
    }
}

// ── Gate engine ───────────────────────────────────────────────────────────────

/**
 * Cross-repository consistency gate engine.
 *
 * Each `check*` method verifies that the live Android enum wireValues for one protocol
 * surface are fully covered by the corresponding [ConsistencyRule].
 *
 * Call [runAllGates] to produce a [GateReport] covering all registered surfaces.
 */
object CrossRepoConsistencyGate {

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Generic gate check: verifies that every value in [liveWireValues] is tolerated
     * (canonical or transitional alias) by the rule for [surface], and that every
     * canonical value in the rule is realized by at least one live entry.
     *
     * @param surface        The protocol surface to check.
     * @param liveWireValues The actual wireValues declared in the Android enum/set.
     * @param liveDescription A short phrase describing the live source (for messages).
     */
    private fun runBidirectionalGate(
        surface: ProtocolSurface,
        liveWireValues: Set<String>,
        liveDescription: String
    ): GateSurfaceResult {
        val violations = mutableListOf<GateViolation>()

        // Forward check: every live wireValue must be tolerated by the rule.
        for (wireValue in liveWireValues) {
            val result = UgcpProtocolConsistencyRules.checkValue(surface, wireValue)
            if (result.status == ConsistencyCheckStatus.DRIFT_CANDIDATE) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.UNDECLARED_ENUM_VALUE,
                    detail = "'$wireValue' from $liveDescription is not declared as a canonical value " +
                        "or transitional alias on surface ${surface.name}. " +
                        "Add it to UgcpProtocolConsistencyRules or register it as a TransitionalAlias."
                )
            }
        }

        // Reverse check: every canonical value in the rule should appear in live values
        // (or be a known intentional extension reserved for future use).
        // We only flag stale canonical values that are absent from live wireValues AND
        // also absent from the transitional alias set (since a canonical value could be
        // an alias target that doesn't directly appear as a live wireValue).
        val canonicalValues = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val transitionalTargets = UgcpProtocolConsistencyRules
            .allRules[surface]?.transitionalAliases?.map { it.canonicalTarget }?.toSet()
            ?: emptySet()

        for (canonicalValue in canonicalValues) {
            if (canonicalValue !in liveWireValues && canonicalValue !in transitionalTargets) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.STALE_CANONICAL_VALUE,
                    detail = "Canonical value '$canonicalValue' on surface ${surface.name} " +
                        "is not realized by any entry in $liveDescription. " +
                        "Either the rule has a stale value or the enum is missing an entry."
                )
            }
        }

        // Internal consistency check: every alias target must itself be canonical.
        val rule = UgcpProtocolConsistencyRules.allRules[surface]
        rule?.transitionalAliases?.forEach { alias ->
            if (alias.canonicalTarget !in canonicalValues) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.ALIAS_TARGET_NOT_CANONICAL,
                    detail = "TransitionalAlias '${alias.aliasValue}' maps to '${alias.canonicalTarget}' " +
                        "but '${alias.canonicalTarget}' is not in the canonical value set for ${surface.name}."
                )
            }
        }

        val outcome = if (violations.isEmpty()) GateOutcome.PASS else GateOutcome.FAIL
        val summary = if (outcome == GateOutcome.PASS) {
            "${surface.name}: PASS (${liveWireValues.size} value(s) checked)"
        } else {
            "${surface.name}: FAIL (${violations.size} violation(s))"
        }
        return GateSurfaceResult(surface, outcome, violations, summary)
    }

    // ── Per-surface gates ─────────────────────────────────────────────────────

    /**
     * Gate: all [DelegatedExecutionSignal.ResultKind] wireValues must be covered by the
     * [ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND] rule.
     */
    fun checkDelegatedExecutionResultKinds(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND,
            liveWireValues = DelegatedExecutionSignal.ResultKind.entries.map { it.wireValue }.toSet(),
            liveDescription = "DelegatedExecutionSignal.ResultKind"
        )

    /**
     * Gate: all [ReconciliationSignal.Kind] wireValues must be covered by the
     * [ProtocolSurface.RECONCILIATION_SIGNAL_KIND] rule, and the rule must cover exactly
     * those wireValues (bidirectional check).
     *
     * A failure here indicates either:
     *  - A new [ReconciliationSignal.Kind] entry was added without updating the consistency rule, or
     *  - The rule declares a canonical kind that is no longer realized by any enum entry.
     *
     * Either case represents a cross-repo protocol surface drift that requires coordination
     * with the V2 gateway handler registration table.
     */
    fun checkReconciliationSignalKinds(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.RECONCILIATION_SIGNAL_KIND,
            liveWireValues = ReconciliationSignal.Kind.entries.map { it.wireValue }.toSet(),
            liveDescription = "ReconciliationSignal.Kind"
        )

    /**
     * Gate: all [AttachedRuntimeSession.State] wireValues must be covered by the
     * [ProtocolSurface.ATTACHED_SESSION_STATE] rule.
     */
    fun checkAttachedSessionStates(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.ATTACHED_SESSION_STATE,
            liveWireValues = AttachedRuntimeSession.State.entries.map { it.wireValue }.toSet(),
            liveDescription = "AttachedRuntimeSession.State"
        )

    /**
     * Gate: all [AttachedRuntimeSession.DetachCause] wireValues must be covered by the
     * [ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE] rule.
     */
    fun checkAttachedSessionDetachCauses(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE,
            liveWireValues = AttachedRuntimeSession.DetachCause.entries.map { it.wireValue }.toSet(),
            liveDescription = "AttachedRuntimeSession.DetachCause"
        )

    /**
     * Gate: all [ReconnectRecoveryState] wireValues must be covered by the
     * [ProtocolSurface.RECONNECT_RECOVERY_STATE] rule.
     */
    fun checkReconnectRecoveryStates(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.RECONNECT_RECOVERY_STATE,
            liveWireValues = ReconnectRecoveryState.entries.map { it.wireValue }.toSet(),
            liveDescription = "ReconnectRecoveryState"
        )

    /**
     * Gate: all [CanonicalSessionFamily] canonical terms and wire aliases must be covered
     * by the [ProtocolSurface.SESSION_IDENTIFIER_CARRIER] rule.
     *
     * This gate checks:
     *  - Every [CanonicalSessionFamily.canonicalTerm] is in the canonical value set.
     *  - Every non-null [CanonicalSessionFamily.wireAlias] is a known transitional alias.
     */
    fun checkSessionIdentifierCarriers(): GateSurfaceResult {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER
        val violations = mutableListOf<GateViolation>()

        // Check that every canonical term from CanonicalSessionFamily is in the rule.
        val ruleCanonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        for (family in CanonicalSessionFamily.entries) {
            if (family.canonicalTerm !in ruleCanonical) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.UNDECLARED_ENUM_VALUE,
                    detail = "CanonicalSessionFamily.${family.name}.canonicalTerm '${family.canonicalTerm}' " +
                        "is not in the canonical value set for SESSION_IDENTIFIER_CARRIER. " +
                        "Add it to UgcpProtocolConsistencyRules.sessionIdentifierCarrierRule."
                )
            }
        }

        // Check that every non-null wire alias from CanonicalSessionFamily is a tolerated alias.
        val ruleTolerated = UgcpProtocolConsistencyRules.toleratedValuesFor(surface)
        for (family in CanonicalSessionFamily.entries.filter { it.hasWireAlias }) {
            val alias = family.wireAlias!!
            if (alias !in ruleTolerated) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.UNDECLARED_ENUM_VALUE,
                    detail = "CanonicalSessionFamily.${family.name}.wireAlias '$alias' " +
                        "is not tolerated (canonical or transitional alias) by SESSION_IDENTIFIER_CARRIER. " +
                        "Register it as a TransitionalAlias in UgcpProtocolConsistencyRules."
                )
            }
        }

        // Internal consistency: all alias targets must be canonical.
        val rule = UgcpProtocolConsistencyRules.allRules[surface]
        rule?.transitionalAliases?.forEach { alias ->
            if (alias.canonicalTarget !in ruleCanonical) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.ALIAS_TARGET_NOT_CANONICAL,
                    detail = "TransitionalAlias '${alias.aliasValue}' maps to '${alias.canonicalTarget}' " +
                        "but '${alias.canonicalTarget}' is not canonical on SESSION_IDENTIFIER_CARRIER."
                )
            }
        }

        val outcome = if (violations.isEmpty()) GateOutcome.PASS else GateOutcome.FAIL
        val summary = if (outcome == GateOutcome.PASS) {
            "${surface.name}: PASS (${CanonicalSessionFamily.entries.size} families checked)"
        } else {
            "${surface.name}: FAIL (${violations.size} violation(s))"
        }
        return GateSurfaceResult(surface, outcome, violations, summary)
    }

    /**
     * Gate: all profile name constants declared in [UgcpSharedSchemaAlignment] must be
     * in the [ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR] canonical set, and the rule
     * must cover exactly those constants (no extras, no missing).
     */
    fun checkRuntimeProfileDescriptors(): GateSurfaceResult {
        val declared = setOf(
            UgcpSharedSchemaAlignment.runtimeWsProfileName,
            UgcpSharedSchemaAlignment.controlTransferProfileName,
            UgcpSharedSchemaAlignment.coordinationProfileName,
            UgcpSharedSchemaAlignment.truthEventModelName,
            UgcpSharedSchemaAlignment.conformanceSurfaceName
        )
        return runBidirectionalGate(
            surface = ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR,
            liveWireValues = declared,
            liveDescription = "UgcpSharedSchemaAlignment profile name constants"
        )
    }

    /**
     * Gate: the [ProtocolSurface.TERMINAL_STATE_VOCABULARY] canonical values must exactly
     * match the normalization targets in [UgcpSharedSchemaAlignment.lifecycleStatusNormalizations].
     */
    fun checkTerminalStateVocabulary(): GateSurfaceResult {
        val surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        val violations = mutableListOf<GateViolation>()
        val canonicalValues = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val normalizationTargets = UgcpSharedSchemaAlignment.lifecycleStatusNormalizations.values.toSet()

        // Every canonical terminal state value must appear as a normalization target.
        for (value in canonicalValues) {
            if (value !in normalizationTargets) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.STALE_CANONICAL_VALUE,
                    detail = "Canonical terminal state '$value' does not appear as a normalization " +
                        "target in UgcpSharedSchemaAlignment.lifecycleStatusNormalizations. " +
                        "Ensure it is reachable through the normalization pipeline."
                )
            }
        }

        // Every normalization target must be a canonical terminal state value.
        for (target in normalizationTargets) {
            if (target !in canonicalValues) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.UNDECLARED_ENUM_VALUE,
                    detail = "Normalization target '$target' in lifecycleStatusNormalizations is not " +
                        "a canonical value on TERMINAL_STATE_VOCABULARY. " +
                        "Add it to the canonical set or update the normalization target."
                )
            }
        }

        // Internal consistency: all transitional alias targets must be canonical.
        val rule = UgcpProtocolConsistencyRules.allRules[surface]
        rule?.transitionalAliases?.forEach { alias ->
            if (alias.canonicalTarget !in canonicalValues) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.ALIAS_TARGET_NOT_CANONICAL,
                    detail = "TransitionalAlias '${alias.aliasValue}' maps to '${alias.canonicalTarget}' " +
                        "but '${alias.canonicalTarget}' is not canonical on TERMINAL_STATE_VOCABULARY."
                )
            }
        }

        val outcome = if (violations.isEmpty()) GateOutcome.PASS else GateOutcome.FAIL
        val summary = if (outcome == GateOutcome.PASS) {
            "${surface.name}: PASS (${canonicalValues.size} value(s) checked against normalization pipeline)"
        } else {
            "${surface.name}: FAIL (${violations.size} violation(s))"
        }
        return GateSurfaceResult(surface, outcome, violations, summary)
    }

    /**
     * Gate: the [ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY] canonical values must
     * exactly match [UgcpSharedSchemaAlignment.transferLifecycleTerms].
     */
    fun checkTransferLifecycleVocabulary(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY,
            liveWireValues = UgcpSharedSchemaAlignment.transferLifecycleTerms,
            liveDescription = "UgcpSharedSchemaAlignment.transferLifecycleTerms"
        )

    /**
     * Gate: all [StagedMeshParticipationResult.ExecutionStatus] wireValues must be covered
     * (as canonical or transitional alias) by the [ProtocolSurface.STAGED_MESH_EXECUTION_STATUS] rule.
     */
    fun checkStagedMeshExecutionStatus(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.STAGED_MESH_EXECUTION_STATUS,
            liveWireValues = StagedMeshParticipationResult.ExecutionStatus.entries.map { it.wireValue }.toSet(),
            liveDescription = "StagedMeshParticipationResult.ExecutionStatus"
        )

    /**
     * Gate: all readiness capability term constants declared in [UgcpSharedSchemaAlignment] must
     * be covered by the [ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR] canonical set, and
     * the rule must cover exactly those constants (no extras, no missing).
     */
    fun checkCapabilityReadinessDescriptors(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR,
            liveWireValues = UgcpSharedSchemaAlignment.readinessCapabilityTerms,
            liveDescription = "UgcpSharedSchemaAlignment.readinessCapabilityTerms"
        )

    /**
     * Gate: verifies that [UgcpProtocolConsistencyRules.allRules] covers exactly the set
     * of [ProtocolSurface] entries — no surface is missing a rule, and no phantom rule
     * exists for a non-existent surface.
     */
    fun checkRuleRegistryCompleteness(): GateSurfaceResult {
        val violations = mutableListOf<GateViolation>()
        val registeredSurfaces = UgcpProtocolConsistencyRules.allRules.keys
        val allSurfaces = ProtocolSurface.entries.toSet()

        for (surface in allSurfaces) {
            if (surface !in registeredSurfaces) {
                violations += GateViolation(
                    surface = surface,
                    violationType = GateViolationType.UNDECLARED_ENUM_VALUE,
                    detail = "ProtocolSurface.${surface.name} has no registered ConsistencyRule. " +
                        "Add a rule to UgcpProtocolConsistencyRules.allRules."
                )
            }
        }

        // Use TERMINAL_STATE_VOCABULARY as the reporting surface for registry-level violations
        // since this check is not specific to any single surface.
        val reportingSurface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        val outcome = if (violations.isEmpty()) GateOutcome.PASS else GateOutcome.FAIL
        val summary = if (outcome == GateOutcome.PASS) {
            "RULE_REGISTRY_COMPLETENESS: PASS (${allSurfaces.size} surfaces all have rules)"
        } else {
            "RULE_REGISTRY_COMPLETENESS: FAIL (${violations.size} surface(s) missing rules)"
        }
        return GateSurfaceResult(reportingSurface, outcome, violations, summary)
    }

    /**
     * Gate: the [ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES] canonical values must exactly
     * match the runtime values of the [RuntimeObservabilityMetadata] field name constants.
     *
     * This gate verifies **wire behavior** rather than enum constant alignment: it compares the
     * actual string values of [RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID],
     * [RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID], and
     * [RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID] against the canonical set in
     * [UgcpProtocolConsistencyRules.observabilityTraceFieldNamesRule].
     *
     * A failure here indicates that a field name constant in [RuntimeObservabilityMetadata] was
     * renamed without updating the cross-repo vocabulary rule — the primary vector for silent
     * observability field drift.
     */
    fun checkObservabilityTraceFieldNames(): GateSurfaceResult =
        runBidirectionalGate(
            surface = ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES,
            liveWireValues = setOf(
                RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID,
                RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID,
                RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID
            ),
            liveDescription = "RuntimeObservabilityMetadata field name constants"
        )

    // ── Full gate run ─────────────────────────────────────────────────────────

    /**
     * Runs all per-surface consistency gates and returns a [GateReport].
     *
     * The report's [GateReport.overallOutcome] is [GateOutcome.PASS] only when every
     * surface gate passes.  Use [GateReport.failureSummary] for a human-readable list
     * of all violations suitable for CI log output.
     *
     * Note: [checkRuleRegistryCompleteness] is also included in the run.  Its result is
     * reported under [ProtocolSurface.TERMINAL_STATE_VOCABULARY] in the results list;
     * its summary prefix distinguishes it from the terminal-state surface gate.
     */
    fun runAllGates(): GateReport {
        val results = listOf(
            checkRuleRegistryCompleteness(),
            checkDelegatedExecutionResultKinds(),
            checkReconciliationSignalKinds(),
            checkAttachedSessionStates(),
            checkAttachedSessionDetachCauses(),
            checkReconnectRecoveryStates(),
            checkSessionIdentifierCarriers(),
            checkRuntimeProfileDescriptors(),
            checkCapabilityReadinessDescriptors(),
            checkTerminalStateVocabulary(),
            checkTransferLifecycleVocabulary(),
            checkStagedMeshExecutionStatus(),
            checkObservabilityTraceFieldNames()
        )
        val failingCount = results.count { it.outcome == GateOutcome.FAIL }
        val passingCount = results.count { it.outcome == GateOutcome.PASS }
        val overall = if (failingCount == 0) GateOutcome.PASS else GateOutcome.FAIL
        return GateReport(results, overall, passingCount, failingCount)
    }
}
