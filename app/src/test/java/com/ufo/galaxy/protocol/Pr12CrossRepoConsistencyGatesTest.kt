package com.ufo.galaxy.protocol

import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.CanonicalSessionFamily
import com.ufo.galaxy.runtime.DelegatedExecutionSignal
import com.ufo.galaxy.runtime.ReconciliationSignal
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import com.ufo.galaxy.runtime.StagedMeshParticipationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-12 — Cross-Repository Consistency Gate tests.
 *
 * These tests function as CI-level consistency gates.  They verify that the live
 * Android enum wireValues and declared constant sets are fully covered by the
 * [UgcpProtocolConsistencyRules] registry.  A test failure here indicates that a
 * shared semantic surface has drifted without a corresponding rule update —
 * the primary mechanism this gate is designed to catch.
 *
 * ## Passing these tests means:
 *  - Every wireValue in every gated enum is either canonical or an explicitly declared
 *    transitional alias in the consistency rule.
 *  - Every canonical value in the rule is realized by at least one live enum entry
 *    (no phantom values in the registry).
 *  - Transitional aliases map to canonical targets (no broken alias chains).
 *  - The rule registry covers all registered [ProtocolSurface] entries.
 *
 * ## A test failure means:
 *  - A new wireValue was added to an enum without updating [UgcpProtocolConsistencyRules].
 *  - A canonical value in a rule no longer has a corresponding enum entry.
 *  - A transitional alias maps to a non-canonical target.
 *
 * In all failure cases, the developer must either:
 *  a) Add the new value as a canonical entry in the consistency rule, OR
 *  b) Register it as a [TransitionalAlias] with a stated reason in the rule.
 */
class Pr12CrossRepoConsistencyGatesTest {

    // ── Full gate report ──────────────────────────────────────────────────────

    @Test
    fun `full gate report passes for all surfaces`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        assertEquals(
            "One or more consistency gates failed:\n${report.failureSummary()}",
            GateOutcome.PASS,
            report.overallOutcome
        )
        assertEquals(0, report.failingCount)
        assertTrue(report.passingCount > 0)
    }

    @Test
    fun `gate report has results for all critical surfaces`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        val reportedSurfaces = report.results.map { it.surface }.toSet()
        // All individually-gated surfaces must appear in the report.
        assertTrue(reportedSurfaces.contains(ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.RECONCILIATION_SIGNAL_KIND))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.ATTACHED_SESSION_STATE))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.RECONNECT_RECOVERY_STATE))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.SESSION_IDENTIFIER_CARRIER))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.TERMINAL_STATE_VOCABULARY))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY))
        assertTrue(reportedSurfaces.contains(ProtocolSurface.STAGED_MESH_EXECUTION_STATUS))
    }

    @Test
    fun `gate report failure summary is empty when all surfaces pass`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        if (report.overallOutcome == GateOutcome.PASS) {
            assertEquals("", report.failureSummary())
        }
    }

    // ── Rule registry completeness gate ──────────────────────────────────────

    @Test
    fun `rule registry completeness gate passes`() {
        val result = CrossRepoConsistencyGate.checkRuleRegistryCompleteness()
        assertEquals(
            "Rule registry completeness gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `every protocol surface has exactly one registered rule`() {
        val allSurfaces = ProtocolSurface.entries.toSet()
        val registeredSurfaces = UgcpProtocolConsistencyRules.allRules.keys
        assertEquals(
            "Number of ProtocolSurface entries must equal number of registered rules",
            allSurfaces.size,
            registeredSurfaces.size
        )
        for (surface in allSurfaces) {
            assertTrue(
                "ProtocolSurface.${surface.name} must have a registered ConsistencyRule",
                registeredSurfaces.contains(surface)
            )
        }
    }

    // ── Delegated execution result kind gate ──────────────────────────────────

    @Test
    fun `delegated execution result kind gate passes`() {
        val result = CrossRepoConsistencyGate.checkDelegatedExecutionResultKinds()
        assertEquals(
            "Delegated execution result kind gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all DelegatedExecutionSignal ResultKind wireValues are tolerated by the rule`() {
        val surface = ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND
        for (kind in DelegatedExecutionSignal.ResultKind.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, kind.wireValue)
            assertFalse(
                "DelegatedExecutionSignal.ResultKind.${kind.name} wireValue '${kind.wireValue}' " +
                    "is a DRIFT_CANDIDATE on $surface — update UgcpProtocolConsistencyRules",
                checkResult.status == ConsistencyCheckStatus.DRIFT_CANDIDATE
            )
        }
    }

    @Test
    fun `delegated execution result kind rule has no stale canonical values`() {
        val surface = ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val liveWireValues = DelegatedExecutionSignal.ResultKind.entries
            .map { it.wireValue }.toSet()
        for (value in canonical) {
            assertTrue(
                "Canonical value '$value' on $surface has no corresponding enum entry in " +
                    "DelegatedExecutionSignal.ResultKind — the rule may have a stale value",
                value in liveWireValues
            )
        }
    }

    // ── Reconciliation signal kind gate ───────────────────────────────────────

    @Test
    fun `reconciliation signal kind gate passes`() {
        val result = CrossRepoConsistencyGate.checkReconciliationSignalKinds()
        assertEquals(
            "Reconciliation signal kind gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `all ReconciliationSignal Kind wireValues are canonical on RECONCILIATION_SIGNAL_KIND`() {
        val surface = ProtocolSurface.RECONCILIATION_SIGNAL_KIND
        for (kind in ReconciliationSignal.Kind.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, kind.wireValue)
            assertEquals(
                "ReconciliationSignal.Kind.${kind.name} wireValue '${kind.wireValue}' " +
                    "must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    @Test
    fun `reconciliation signal kind rule has no stale canonical values`() {
        val surface = ProtocolSurface.RECONCILIATION_SIGNAL_KIND
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val liveWireValues = ReconciliationSignal.Kind.entries.map { it.wireValue }.toSet()
        for (value in canonical) {
            assertTrue(
                "Canonical value '$value' on $surface has no corresponding enum entry in " +
                    "ReconciliationSignal.Kind — the rule may have a stale value",
                value in liveWireValues
            )
        }
    }

    @Test
    fun `reconciliation signal kind rule canonical set exactly matches ReconciliationSignal Kind entries`() {
        val surface = ProtocolSurface.RECONCILIATION_SIGNAL_KIND
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val liveWireValues = ReconciliationSignal.Kind.entries.map { it.wireValue }.toSet()
        assertEquals(
            "RECONCILIATION_SIGNAL_KIND canonical values must exactly match " +
                "ReconciliationSignal.Kind wire values",
            liveWireValues,
            canonical
        )
    }

    @Test
    fun `reconciliation signal kind surface is classified as canonical`() {
        assertEquals(
            ProtocolSurfaceClass.CANONICAL,
            ProtocolSurface.RECONCILIATION_SIGNAL_KIND.surfaceClass
        )
    }

    @Test
    fun `reconciliation signal kind rule has no transitional aliases`() {
        val rule = UgcpProtocolConsistencyRules.allRules[ProtocolSurface.RECONCILIATION_SIGNAL_KIND]!!
        assertTrue(
            "RECONCILIATION_SIGNAL_KIND must have no transitional aliases — all Kind values are canonical",
            rule.transitionalAliases.isEmpty()
        )
    }

    @Test
    fun `reconciliation signal kind rule notes are non-blank`() {
        val rule = UgcpProtocolConsistencyRules.allRules[ProtocolSurface.RECONCILIATION_SIGNAL_KIND]!!
        assertTrue(
            "RECONCILIATION_SIGNAL_KIND rule notes must be non-blank",
            rule.notes.isNotBlank()
        )
    }

    @Test
    fun `unregistered reconciliation kind wireValue is detected as drift candidate`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.RECONCILIATION_SIGNAL_KIND,
            "unknown_future_kind"
        )
        assertEquals(
            "An unregistered reconciliation kind wireValue must be DRIFT_CANDIDATE",
            ConsistencyCheckStatus.DRIFT_CANDIDATE,
            result.status
        )
    }

    // ── Attached session state gate ───────────────────────────────────────────

    @Test
    fun `attached session state gate passes`() {
        val result = CrossRepoConsistencyGate.checkAttachedSessionStates()
        assertEquals(
            "Attached session state gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all AttachedRuntimeSession State wireValues are canonical`() {
        val surface = ProtocolSurface.ATTACHED_SESSION_STATE
        for (state in AttachedRuntimeSession.State.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, state.wireValue)
            assertEquals(
                "AttachedRuntimeSession.State.${state.name} wireValue '${state.wireValue}' " +
                    "must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    // ── Attached session detach cause gate ────────────────────────────────────

    @Test
    fun `attached session detach cause gate passes`() {
        val result = CrossRepoConsistencyGate.checkAttachedSessionDetachCauses()
        assertEquals(
            "Attached session detach cause gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all AttachedRuntimeSession DetachCause wireValues are canonical`() {
        val surface = ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE
        for (cause in AttachedRuntimeSession.DetachCause.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, cause.wireValue)
            assertEquals(
                "AttachedRuntimeSession.DetachCause.${cause.name} wireValue '${cause.wireValue}' " +
                    "must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    // ── Reconnect recovery state gate ─────────────────────────────────────────

    @Test
    fun `reconnect recovery state gate passes`() {
        val result = CrossRepoConsistencyGate.checkReconnectRecoveryStates()
        assertEquals(
            "Reconnect recovery state gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all ReconnectRecoveryState wireValues are canonical`() {
        val surface = ProtocolSurface.RECONNECT_RECOVERY_STATE
        for (state in ReconnectRecoveryState.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, state.wireValue)
            assertEquals(
                "ReconnectRecoveryState.${state.name} wireValue '${state.wireValue}' " +
                    "must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    // ── Session identifier carrier gate ──────────────────────────────────────

    @Test
    fun `session identifier carrier gate passes`() {
        val result = CrossRepoConsistencyGate.checkSessionIdentifierCarriers()
        assertEquals(
            "Session identifier carrier gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all CanonicalSessionFamily canonical terms are in the session identifier carrier rule`() {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        for (family in CanonicalSessionFamily.entries) {
            assertTrue(
                "CanonicalSessionFamily.${family.name}.canonicalTerm '${family.canonicalTerm}' " +
                    "must be in the canonical value set for SESSION_IDENTIFIER_CARRIER",
                canonical.contains(family.canonicalTerm)
            )
        }
    }

    @Test
    fun `all CanonicalSessionFamily wire aliases are tolerated on the session identifier carrier surface`() {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER
        val tolerated = UgcpProtocolConsistencyRules.toleratedValuesFor(surface)
        for (family in CanonicalSessionFamily.entries.filter { it.hasWireAlias }) {
            val alias = family.wireAlias!!
            assertTrue(
                "CanonicalSessionFamily.${family.name}.wireAlias '$alias' must be tolerated " +
                    "(canonical or transitional alias) on SESSION_IDENTIFIER_CARRIER",
                tolerated.contains(alias)
            )
        }
    }

    @Test
    fun `session identifier carrier transitional aliases map to canonical targets`() {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val rule = UgcpProtocolConsistencyRules.allRules[surface]!!
        for (alias in rule.transitionalAliases) {
            assertTrue(
                "TransitionalAlias '${alias.aliasValue}' maps to '${alias.canonicalTarget}' " +
                    "but that target is not canonical on SESSION_IDENTIFIER_CARRIER",
                canonical.contains(alias.canonicalTarget)
            )
        }
    }

    // ── Runtime profile descriptor gate ──────────────────────────────────────

    @Test
    fun `runtime profile descriptor gate passes`() {
        val result = CrossRepoConsistencyGate.checkRuntimeProfileDescriptors()
        assertEquals(
            "Runtime profile descriptor gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all UgcpSharedSchemaAlignment profile constants are canonical runtime profile descriptors`() {
        val surface = ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR
        val declared = listOf(
            UgcpSharedSchemaAlignment.runtimeWsProfileName,
            UgcpSharedSchemaAlignment.controlTransferProfileName,
            UgcpSharedSchemaAlignment.coordinationProfileName,
            UgcpSharedSchemaAlignment.truthEventModelName,
            UgcpSharedSchemaAlignment.conformanceSurfaceName
        )
        for (profile in declared) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, profile)
            assertEquals(
                "Profile '$profile' from UgcpSharedSchemaAlignment must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    // ── Terminal state vocabulary gate ────────────────────────────────────────

    @Test
    fun `terminal state vocabulary gate passes`() {
        val result = CrossRepoConsistencyGate.checkTerminalStateVocabulary()
        assertEquals(
            "Terminal state vocabulary gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `lifecycle status normalization targets are a subset of canonical terminal state values`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TERMINAL_STATE_VOCABULARY
        )
        for ((_, target) in UgcpSharedSchemaAlignment.lifecycleStatusNormalizations) {
            assertTrue(
                "Normalization target '$target' must be canonical on TERMINAL_STATE_VOCABULARY",
                canonical.contains(target)
            )
        }
    }

    @Test
    fun `all canonical terminal state values are reachable via the normalization pipeline`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TERMINAL_STATE_VOCABULARY
        )
        val normalizationTargets = UgcpSharedSchemaAlignment.lifecycleStatusNormalizations.values.toSet()
        for (value in canonical) {
            assertTrue(
                "Canonical terminal state '$value' must appear as a normalization target in " +
                    "UgcpSharedSchemaAlignment.lifecycleStatusNormalizations to be reachable",
                normalizationTargets.contains(value)
            )
        }
    }

    // ── Transfer lifecycle vocabulary gate ────────────────────────────────────

    @Test
    fun `transfer lifecycle vocabulary gate passes`() {
        val result = CrossRepoConsistencyGate.checkTransferLifecycleVocabulary()
        assertEquals(
            "Transfer lifecycle vocabulary gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `transfer lifecycle vocabulary rule exactly matches UgcpSharedSchemaAlignment terms`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY
        )
        val alignmentTerms = UgcpSharedSchemaAlignment.transferLifecycleTerms
        assertEquals(
            "TRANSFER_LIFECYCLE_VOCABULARY canonical values must exactly match " +
                "UgcpSharedSchemaAlignment.transferLifecycleTerms",
            alignmentTerms,
            canonical
        )
    }

    // ── Capability readiness descriptor gate ─────────────────────────────────

    @Test
    fun `capability readiness descriptor gate passes`() {
        val result = CrossRepoConsistencyGate.checkCapabilityReadinessDescriptors()
        assertEquals(
            "Capability readiness descriptor gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all UgcpSharedSchemaAlignment readiness capability terms are canonical on the descriptor surface`() {
        val surface = ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR
        for (term in UgcpSharedSchemaAlignment.readinessCapabilityTerms) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, term)
            assertEquals(
                "Readiness capability term '$term' must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                checkResult.status
            )
        }
    }

    @Test
    fun `capability readiness descriptor rule exactly matches UgcpSharedSchemaAlignment readiness terms`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR
        )
        val alignmentTerms = UgcpSharedSchemaAlignment.readinessCapabilityTerms
        assertEquals(
            "CAPABILITY_READINESS_DESCRIPTOR canonical values must exactly match " +
                "UgcpSharedSchemaAlignment.readinessCapabilityTerms",
            alignmentTerms,
            canonical
        )
    }

    // ── Staged mesh execution status gate ─────────────────────────────────────

    @Test
    fun `staged mesh execution status gate passes`() {
        val result = CrossRepoConsistencyGate.checkStagedMeshExecutionStatus()
        assertEquals(
            "Staged mesh execution status gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
    }

    @Test
    fun `all StagedMeshParticipationResult ExecutionStatus wireValues are tolerated by the rule`() {
        val surface = ProtocolSurface.STAGED_MESH_EXECUTION_STATUS
        for (status in StagedMeshParticipationResult.ExecutionStatus.entries) {
            val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, status.wireValue)
            assertFalse(
                "StagedMeshParticipationResult.ExecutionStatus.${status.name} wireValue " +
                    "'${status.wireValue}' is a DRIFT_CANDIDATE on $surface — " +
                    "update UgcpProtocolConsistencyRules.stagedMeshExecutionStatusRule",
                checkResult.status == ConsistencyCheckStatus.DRIFT_CANDIDATE
            )
        }
    }

    @Test
    fun `StagedMeshParticipationResult ExecutionStatus FAILURE wireValue is declared as a transitional alias`() {
        val surface = ProtocolSurface.STAGED_MESH_EXECUTION_STATUS
        val result = UgcpProtocolConsistencyRules.checkValue(surface, "failure")
        assertEquals(
            "StagedMeshParticipationResult.ExecutionStatus.FAILURE 'failure' must be " +
                "a TRANSITIONAL_ALIAS (not canonical) on $surface since canonical is 'error'",
            ConsistencyCheckStatus.TRANSITIONAL_ALIAS,
            result.status
        )
        assertEquals("error", result.canonicalTarget)
    }

    @Test
    fun `StagedMeshParticipationResult ExecutionStatus SUCCESS CANCELLED BLOCKED are canonical`() {
        val surface = ProtocolSurface.STAGED_MESH_EXECUTION_STATUS
        for (wireValue in listOf("success", "cancelled", "blocked")) {
            val result = UgcpProtocolConsistencyRules.checkValue(surface, wireValue)
            assertEquals(
                "'$wireValue' must be CANONICAL on $surface",
                ConsistencyCheckStatus.CANONICAL,
                result.status
            )
        }
    }

    @Test
    fun `staged mesh execution status surface is classified as transitional compatibility`() {
        assertEquals(
            ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY,
            ProtocolSurface.STAGED_MESH_EXECUTION_STATUS.surfaceClass
        )
    }

    @Test
    fun `staged mesh execution status transitional alias reason is non-blank`() {
        val rule = UgcpProtocolConsistencyRules.allRules[ProtocolSurface.STAGED_MESH_EXECUTION_STATUS]!!
        for (alias in rule.transitionalAliases) {
            assertTrue(
                "TransitionalAlias '${alias.aliasValue}' on STAGED_MESH_EXECUTION_STATUS must have a non-blank reason",
                alias.reason.isNotBlank()
            )
        }
    }

    // ── GateReport structure tests ────────────────────────────────────────────

    @Test
    fun `GateReport passing count plus failing count equals total result count`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        assertEquals(
            "passingCount + failingCount must equal results.size",
            report.results.size,
            report.passingCount + report.failingCount
        )
    }

    @Test
    fun `GateReport overall outcome is PASS iff failing count is zero`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        if (report.failingCount == 0) {
            assertEquals(GateOutcome.PASS, report.overallOutcome)
        } else {
            assertEquals(GateOutcome.FAIL, report.overallOutcome)
        }
    }

    // ── Drift detection self-test ─────────────────────────────────────────────

    @Test
    fun `gate correctly identifies an undeclared value as a drift candidate`() {
        // Verify that the gate mechanism itself correctly flags a hypothetical new wireValue
        // that hasn't been added to any rule.  This is a self-test of the gate engine.
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND,
            "pending"
        )
        assertEquals(
            "A hypothetical new wireValue 'pending' not in any rule must be DRIFT_CANDIDATE",
            ConsistencyCheckStatus.DRIFT_CANDIDATE,
            result.status
        )
    }

    @Test
    fun `gate correctly identifies an undeclared session carrier as a drift candidate`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.SESSION_IDENTIFIER_CARRIER,
            "request_session_id"
        )
        assertEquals(
            "A hypothetical new session carrier 'request_session_id' not in any rule must be DRIFT_CANDIDATE",
            ConsistencyCheckStatus.DRIFT_CANDIDATE,
            result.status
        )
    }

    @Test
    fun `GateSurfaceResult summary is non-blank for passing outcomes`() {
        val passingResult = CrossRepoConsistencyGate.checkReconnectRecoveryStates()
        assertEquals(GateOutcome.PASS, passingResult.outcome)
        assertTrue(passingResult.summary.isNotBlank())
    }

    // ── All transitional aliases have canonical targets ───────────────────────

    @Test
    fun `every transitional alias across all surfaces maps to a canonical target on the same surface`() {
        for ((surface, alias) in UgcpProtocolConsistencyRules.allTransitionalAliases()) {
            val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
            assertTrue(
                "TransitionalAlias '${alias.aliasValue}' on $surface maps to '${alias.canonicalTarget}' " +
                    "but that value is not in the canonical set for $surface",
                canonical.contains(alias.canonicalTarget)
            )
            assertTrue(
                "TransitionalAlias '${alias.aliasValue}' on $surface must have a non-blank reason",
                alias.reason.isNotBlank()
            )
        }
    }
}
