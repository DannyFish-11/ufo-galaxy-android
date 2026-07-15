package com.ufo.galaxy.runtime

import com.ufo.galaxy.shared.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-14Android — CI-blocking tests for [AndroidV2ContractVersionGate].
 *
 * ### Gate-level schema version stability
 *  - GATE_SCHEMA_VERSION is stable at "1"
 *  - V2_GATE_VERSION_KEY is stable at "android_v2_contract_gate_schema_version"
 *
 * ### AIP envelope version anchors
 *  - CANONICAL_AIP_PROTOCOL_VERSION is "AIP/1.0"
 *  - CANONICAL_AIP_SPEC_VERSION is "3.0"
 *
 * ### MsgType count anchor
 *  - EXPECTED_MSG_TYPE_COUNT equals MsgType.entries.size (drift detection)
 *
 * ### Per-boundary schema version constants
 *  - EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION matches AndroidCompletionClosureUplinkContract
 *  - EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION matches AndroidResultUplinkBoundaryContract
 *  - EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION matches AndroidDistributedTruthOwnershipUplinkContract
 *  - EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION matches AndroidParticipationSemanticNormalizationContract
 *
 * ### Wire key stability
 *  - KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS consistent with source contract
 *  - KEY_RESULT_COMPLETION_SIGNAL_CLASS consistent with source contract
 *  - KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS consistent with source contract
 *  - KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION consistent with source contract
 *  - KEY_RESULT_SIGNAL_CLASS consistent with AndroidResultUplinkBoundaryContract
 *  - KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION consistent with source contract
 *  - KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION consistent with source contract
 *
 * ### Boundary category coverage
 *  - GateBoundaryCategory has exactly four values
 *  - CRITICAL_CONTRACT_BOUNDARIES covers all four GateBoundaryCategory values
 *  - CRITICAL_CONTRACT_BOUNDARIES has no duplicate categories
 *
 * ### V2 alignment map
 *  - V2_EXPECTED_VERSIONS is non-empty and contains every critical wire version key
 *
 * ### Gate validation
 *  - validate() returns GateValidationStatus.PASSED (all contracts internally consistent)
 *  - validate() violations list is empty
 *
 * ### Gate invariants
 *  - GATE_INVARIANTS count matches EXPECTED_GATE_INVARIANT_COUNT
 *  - All invariant strings are non-blank
 *
 * ### StabilizationBaseline registration
 *  - "android-v2-contract-version-gate" is registered as CANONICAL_STABLE with EXTEND guidance
 */
class Pr14AndroidV2ContractVersionGateTest {

    private fun Set<MsgType>.toWireValueSet(): Set<String> =
        mapTo(mutableSetOf()) { it.value }

    // ── 1. Gate schema version stability ─────────────────────────────────────

    @Test
    fun `GATE_SCHEMA_VERSION is stable at 1`() {
        assertEquals(
            "GATE_SCHEMA_VERSION drift detected — bump it, update V2_EXPECTED_VERSIONS, and align V2",
            "1",
            AndroidV2ContractVersionGate.GATE_SCHEMA_VERSION
        )
    }

    @Test
    fun `V2_GATE_VERSION_KEY is stable`() {
        assertEquals(
            "android_v2_contract_gate_schema_version",
            AndroidV2ContractVersionGate.V2_GATE_VERSION_KEY
        )
    }

    // ── 2. AIP envelope version anchors ──────────────────────────────────────

    @Test
    fun `CANONICAL_AIP_PROTOCOL_VERSION matches AipMessage default`() {
        assertEquals(
            "AIP/1.0",
            AndroidV2ContractVersionGate.CANONICAL_AIP_PROTOCOL_VERSION
        )
    }

    @Test
    fun `CANONICAL_AIP_SPEC_VERSION matches AipMessage default`() {
        assertEquals(
            "3.0",
            AndroidV2ContractVersionGate.CANONICAL_AIP_SPEC_VERSION
        )
    }

    // ── 3. MsgType count anchor ───────────────────────────────────────────────

    @Test
    fun `EXPECTED_MSG_TYPE_COUNT equals actual MsgType entry count`() {
        val actual = MsgType.entries.size
        assertEquals(
            "MsgType count drift: actual=$actual, expected=${AndroidV2ContractVersionGate.EXPECTED_MSG_TYPE_COUNT}. " +
                "Bump GATE_SCHEMA_VERSION, update EXPECTED_MSG_TYPE_COUNT, and align V2 MsgType registry.",
            AndroidV2ContractVersionGate.EXPECTED_MSG_TYPE_COUNT,
            actual
        )
    }

    // ── 4. Per-boundary schema version constants ──────────────────────────────

    @Test
    fun `EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidCompletionClosureUplinkContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 canonical_completion_ingress.py",
            AndroidV2ContractVersionGate.EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION,
            AndroidCompletionClosureUplinkContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidResultUplinkBoundaryContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 task_result_canonical_truth_chain.py",
            AndroidV2ContractVersionGate.EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION,
            AndroidResultUplinkBoundaryContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 handoff_governance_chain.py",
            AndroidV2ContractVersionGate.EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
            AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 android_device_state_store.py",
            AndroidV2ContractVersionGate.EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION,
            AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_NON_CLOSURE_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 non-closure ingress guards",
            AndroidV2ContractVersionGate.EXPECTED_NON_CLOSURE_SCHEMA_VERSION,
            AndroidNonClosureSignalBoundaryContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_RECOVERY_ROUTING_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 recovery routing ingress",
            AndroidV2ContractVersionGate.EXPECTED_RECOVERY_ROUTING_SCHEMA_VERSION,
            AndroidCrossRepoRecoveryStateRoutingContract.SCHEMA_VERSION
        )
    }

    @Test
    fun `EXPECTED_CROSS_REPO_DEDUPE_SCHEMA_VERSION matches contract`() {
        assertEquals(
            "AndroidCrossRepoDedupeContract.SCHEMA_VERSION drift — " +
                "bump GATE_SCHEMA_VERSION and align V2 replay/dedupe ingress",
            AndroidV2ContractVersionGate.EXPECTED_CROSS_REPO_DEDUPE_SCHEMA_VERSION,
            AndroidCrossRepoDedupeContract.SCHEMA_VERSION
        )
    }

    // ── 5. Wire key stability ─────────────────────────────────────────────────

    @Test
    fun `KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS matches source contract`() {
        assertEquals(
            AndroidCompletionClosureUplinkContract.KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS,
            AndroidV2ContractVersionGate.KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS
        )
    }

    @Test
    fun `KEY_RESULT_COMPLETION_SIGNAL_CLASS matches source contract`() {
        assertEquals(
            AndroidCompletionClosureUplinkContract.KEY_RESULT_COMPLETION_SIGNAL_CLASS,
            AndroidV2ContractVersionGate.KEY_RESULT_COMPLETION_SIGNAL_CLASS
        )
    }

    @Test
    fun `KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS matches source contract`() {
        assertEquals(
            AndroidCompletionClosureUplinkContract.KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS,
            AndroidV2ContractVersionGate.KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS
        )
    }

    @Test
    fun `KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION matches source contract`() {
        assertEquals(
            AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION
        )
    }

    @Test
    fun `KEY_RESULT_SIGNAL_CLASS matches AndroidResultUplinkBoundaryContract`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS,
            AndroidV2ContractVersionGate.KEY_RESULT_SIGNAL_CLASS
        )
    }

    @Test
    fun `KEY_ACCEPTANCE_CANDIDATE_CLASS matches AndroidResultUplinkBoundaryContract`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.KEY_ACCEPTANCE_CANDIDATE_CLASS,
            AndroidV2ContractVersionGate.KEY_ACCEPTANCE_CANDIDATE_CLASS
        )
    }

    @Test
    fun `KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION matches source contract`() {
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.KEY_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION
        )
    }

    @Test
    fun `KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION matches source contract`() {
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION
        )
    }

    @Test
    fun `KEY_NON_CLOSURE_SCHEMA_VERSION matches source contract`() {
        assertEquals(
            AndroidNonClosureSignalBoundaryContract.KEY_NON_CLOSURE_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.KEY_NON_CLOSURE_SCHEMA_VERSION
        )
    }

    @Test
    fun `KEY_RECOVERY_ROUTING_SCHEMA_VERSION matches source contract`() {
        assertEquals(
            AndroidCrossRepoRecoveryStateRoutingContract.KEY_ROUTING_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.KEY_RECOVERY_ROUTING_SCHEMA_VERSION
        )
    }

    // ── 6. Boundary category coverage ────────────────────────────────────────

    @Test
    fun `GateBoundaryCategory has exactly four values`() {
        assertEquals(4, AndroidV2ContractVersionGate.GateBoundaryCategory.entries.size)
    }

    @Test
    fun `CRITICAL_CONTRACT_BOUNDARIES covers all four GateBoundaryCategory values`() {
        val covered = AndroidV2ContractVersionGate.CRITICAL_CONTRACT_BOUNDARIES
            .map { it.category }
            .toSet()
        for (category in AndroidV2ContractVersionGate.GateBoundaryCategory.entries) {
            assertTrue(
                "CRITICAL_CONTRACT_BOUNDARIES missing entry for ${category.name}",
                covered.contains(category)
            )
        }
    }

    @Test
    fun `CRITICAL_CONTRACT_BOUNDARIES has no duplicate categories`() {
        val categories = AndroidV2ContractVersionGate.CRITICAL_CONTRACT_BOUNDARIES.map { it.category }
        assertEquals(
            "Duplicate categories in CRITICAL_CONTRACT_BOUNDARIES",
            categories.size,
            categories.toSet().size
        )
    }

    @Test
    fun `each CRITICAL_CONTRACT_BOUNDARIES entry has non-blank contractName and v2ConsumptionPath`() {
        for (entry in AndroidV2ContractVersionGate.CRITICAL_CONTRACT_BOUNDARIES) {
            assertTrue(
                "contractName is blank for ${entry.category}",
                entry.contractName.isNotBlank()
            )
            assertTrue(
                "v2ConsumptionPath is blank for ${entry.category}",
                entry.v2ConsumptionPath.isNotBlank()
            )
        }
    }

    // ── 7. V2 alignment map ───────────────────────────────────────────────────

    @Test
    fun `V2_EXPECTED_VERSIONS is non-empty`() {
        assertFalse(AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS.isEmpty())
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains completion closure schema version key`() {
        val key = AndroidV2ContractVersionGate.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains result uplink boundary schema version key`() {
        val key = AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains distributed truth ownership schema version key`() {
        val key = AndroidV2ContractVersionGate.KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains gate schema version key`() {
        val key = AndroidV2ContractVersionGate.V2_GATE_VERSION_KEY
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing gate version key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.GATE_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains non-closure schema version key`() {
        val key = AndroidV2ContractVersionGate.KEY_NON_CLOSURE_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing non-closure schema version key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_NON_CLOSURE_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains recovery routing schema version key`() {
        val key = AndroidV2ContractVersionGate.KEY_RECOVERY_ROUTING_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing recovery routing schema version key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_RECOVERY_ROUTING_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `V2_EXPECTED_VERSIONS contains cross-repo dedupe schema version key`() {
        val key = AndroidV2ContractVersionGate.KEY_CROSS_REPO_DEDUPE_SCHEMA_VERSION
        assertNotNull(
            "V2_EXPECTED_VERSIONS missing dedupe schema version key $key",
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
        assertEquals(
            AndroidV2ContractVersionGate.EXPECTED_CROSS_REPO_DEDUPE_SCHEMA_VERSION,
            AndroidV2ContractVersionGate.V2_EXPECTED_VERSIONS[key]
        )
    }

    @Test
    fun `REQUIRED_NON_CLOSURE_MSG_TYPES matches contract`() {
        assertEquals(
            AndroidV2ContractVersionGate.REQUIRED_NON_CLOSURE_MSG_TYPES,
            AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES
        )
    }

    @Test
    fun `REQUIRED_CANONICAL_REPLAY_MSG_TYPES matches contract`() {
        assertEquals(
            AndroidV2ContractVersionGate.REQUIRED_CANONICAL_REPLAY_MSG_TYPES.toWireValueSet(),
            AndroidCrossRepoDedupeContract.CANONICAL_REPLAY_TYPES
        )
    }

    @Test
    fun `REQUIRED_REPLAY_EPOCH_MSG_TYPES matches contract`() {
        assertEquals(
            AndroidV2ContractVersionGate.REQUIRED_REPLAY_EPOCH_MSG_TYPES.toWireValueSet(),
            AndroidCrossRepoDedupeContract.REPLAY_EPOCH_REQUIRED_TYPES
        )
    }

    // ── 8. Gate validation (primary CI gate) ─────────────────────────────────

    @Test
    fun `validate returns PASSED when all contracts are internally consistent`() {
        val result = AndroidV2ContractVersionGate.validate()
        assertEquals(
            "Contract version gate FAILED. Violations:\n" +
                result.violations.joinToString("\n") { v ->
                    "  [${v.boundary}] ${v.description}: expected=${v.expected}, actual=${v.actual}"
                },
            AndroidV2ContractVersionGate.GateValidationStatus.PASSED,
            result.status
        )
    }

    @Test
    fun `validate violations list is empty when gate passes`() {
        val result = AndroidV2ContractVersionGate.validate()
        assertTrue(
            "Unexpected gate violations: ${result.violations}",
            result.violations.isEmpty()
        )
    }

    // ── 9. Gate invariants ────────────────────────────────────────────────────

    @Test
    fun `GATE_INVARIANTS count matches EXPECTED_GATE_INVARIANT_COUNT`() {
        assertEquals(
            "GATE_INVARIANTS count drift — bump GATE_SCHEMA_VERSION if invariants changed",
            AndroidV2ContractVersionGate.EXPECTED_GATE_INVARIANT_COUNT,
            AndroidV2ContractVersionGate.GATE_INVARIANTS.size
        )
    }

    @Test
    fun `all GATE_INVARIANTS strings are non-blank`() {
        for ((i, inv) in AndroidV2ContractVersionGate.GATE_INVARIANTS.withIndex()) {
            assertTrue("GATE_INVARIANTS[$i] is blank", inv.isNotBlank())
        }
    }

    // ── 10. StabilizationBaseline registration ────────────────────────────────

    @Test
    fun `android-v2-contract-version-gate is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-v2-contract-version-gate")
        assertNotNull(
            "android-v2-contract-version-gate not registered in StabilizationBaseline",
            entry
        )
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry.extensionGuidance
        )
    }

    @Test
    fun `StabilizationBaseline CANONICAL_STABLE count is 150`() {
        val count = StabilizationBaseline.byStability(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE
        ).size
        // 计数漂移收口:后续注册了新表面,CANONICAL_STABLE 实际已 150(锚点停在 138)。
        // 按测试自身提示"update this assertion"对齐到实际值(实证:distribution 求和=156 总数)。
        assertEquals(
            "CANONICAL_STABLE count drift — register new surface or update this assertion",
            150,
            count
        )
    }

    @Test
    fun `StabilizationBaseline total entry count is 156`() {
        assertEquals(
            "Total entry count drift — register new surface or update this assertion",
            156,
            StabilizationBaseline.entries.size
        )
    }

    @Test
    fun `StabilizationBaseline EXTEND guidance count is 150`() {
        val count = StabilizationBaseline.byGuidance(
            StabilizationBaseline.ExtensionGuidance.EXTEND
        ).size
        assertEquals(
            "EXTEND guidance count drift — update this assertion",
            150,
            count
        )
    }
}
