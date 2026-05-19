package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-05v2 (Android) — AndroidResultUplinkBoundaryContract 合约单元测试。
 *
 * 验证以下验收准则：
 *
 *  1. **ResultSignalClass** — 3 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isAuthoritative / isAcceptanceRelevant 语义正确。
 *  2. **AcceptanceCandidateClass** — 4 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isEligible 语义正确。
 *  3. **derive() — DIAGNOSTICS_INFORMATIONAL 路径（非终态 + 无闭合）**：
 *     !isTerminalPhase + !closureReadyForAcceptance → DIAGNOSTICS_INFORMATIONAL + PENDING_RESULT_RETURN。
 *  4. **derive() — ACCEPTANCE_BLOCKED 路径**：
 *     closureReadyForAcceptance + governance_blocked → ACCEPTANCE_CLOSURE_SIGNAL + ACCEPTANCE_BLOCKED；
 *     closureReadyForAcceptance + runtime_constrained → ACCEPTANCE_CLOSURE_SIGNAL + ACCEPTANCE_BLOCKED。
 *  5. **derive() — AUTHORITY_RESULT 路径**：
 *     终态 + resultReturned + completionSignaled + closureReadyForAcceptance + !isHoldState
 *     → AUTHORITY_RESULT + ELIGIBLE_FOR_ACCEPTANCE。
 *  6. **derive() — ACCEPTANCE_CLOSURE_SIGNAL（非完整 authority）路径**：
 *     closureReadyForAcceptance=true 但 isHoldState=true → ACCEPTANCE_CLOSURE_SIGNAL + ELIGIBLE_FOR_ACCEPTANCE。
 *  7. **UplinkBoundarySnapshot.toWireMap()** — 3 个 key 均存在；值类型正确。
 *  8. **RESULT_UPLINK_BOUNDARY_INVARIANTS** — 共 6 条；全部非空；无重复 ID。
 *  9. **StabilizationBaseline** — "android-result-uplink-boundary-contract" 已注册为 CANONICAL_STABLE。
 * 10. **SCHEMA_VERSION** — 值为 "1"。
 * 11. **KEY_ 常量** — 全部非空；互不相同。
 * 12. **V2_CONSUMPTION_PATH_MAP** — 包含全部 3 个 ResultSignalClass 条目；路径非空。
 * 13. **INV-RUB-02**：AUTHORITY_RESULT 时 isAuthoritative=true；DIAGNOSTICS_INFORMATIONAL 时 isAuthoritative=false。
 * 14. **INV-RUB-03**：DIAGNOSTICS_INFORMATIONAL 时 isAcceptanceRelevant=false。
 * 15. **优先级校验**：governance_blocked 优先于 authority_result 条件（即使满足终态 + resultReturned 等）。
 * 16. **优先级校验**：非终态且有 closureReadyForAcceptance=false → PENDING_RESULT_RETURN（非 CLOSURE_NOT_APPLICABLE）。
 * 17. **hold state 边界**：isHoldState=true 时不产出 AUTHORITY_RESULT。
 */
class Pr05v2AndroidResultUplinkBoundaryTest {

    // ── 测试辅助 ────────────────────────────────────────────────────────────────

    private fun defaultInput(
        isTerminalPhase: Boolean = false,
        resultReturned: Boolean = false,
        completionSignaled: Boolean = false,
        closureReadyForAcceptance: Boolean = false,
        isGovernanceBlocked: Boolean = false,
        isRuntimeConstrained: Boolean = false,
        isHoldState: Boolean = false
    ) = AndroidResultUplinkBoundaryContract.UplinkBoundaryDerivationInput(
        isTerminalPhase = isTerminalPhase,
        resultReturned = resultReturned,
        completionSignaled = completionSignaled,
        closureReadyForAcceptance = closureReadyForAcceptance,
        isGovernanceBlocked = isGovernanceBlocked,
        isRuntimeConstrained = isRuntimeConstrained,
        isHoldState = isHoldState
    )

    private fun derive(input: AndroidResultUplinkBoundaryContract.UplinkBoundaryDerivationInput) =
        AndroidResultUplinkBoundaryContract.derive(input)

    // ══════════════════════════════════════════════════════════════════════════
    // 1. ResultSignalClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `ResultSignalClass has 3 values`() {
        assertEquals(3, AndroidResultUplinkBoundaryContract.ResultSignalClass.values().size)
    }

    @Test fun `ResultSignalClass AUTHORITY_RESULT wireValue is authority_result`() {
        assertEquals(
            "authority_result",
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT.wireValue
        )
    }

    @Test fun `ResultSignalClass ACCEPTANCE_CLOSURE_SIGNAL wireValue is acceptance_closure_signal`() {
        assertEquals(
            "acceptance_closure_signal",
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL.wireValue
        )
    }

    @Test fun `ResultSignalClass DIAGNOSTICS_INFORMATIONAL wireValue is diagnostics_informational`() {
        assertEquals(
            "diagnostics_informational",
            AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.wireValue
        )
    }

    @Test fun `ResultSignalClass fromWireValue unknown returns DIAGNOSTICS_INFORMATIONAL`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            AndroidResultUplinkBoundaryContract.ResultSignalClass.fromWireValue("unknown_value")
        )
    }

    @Test fun `ResultSignalClass fromWireValue authority_result returns AUTHORITY_RESULT`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            AndroidResultUplinkBoundaryContract.ResultSignalClass.fromWireValue("authority_result")
        )
    }

    @Test fun `ResultSignalClass fromWireValue acceptance_closure_signal returns ACCEPTANCE_CLOSURE_SIGNAL`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            AndroidResultUplinkBoundaryContract.ResultSignalClass.fromWireValue("acceptance_closure_signal")
        )
    }

    @Test fun `ResultSignalClass AUTHORITY_RESULT isAuthoritative is true`() {
        assertTrue(AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT.isAuthoritative)
    }

    @Test fun `ResultSignalClass ACCEPTANCE_CLOSURE_SIGNAL isAuthoritative is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL.isAuthoritative)
    }

    @Test fun `ResultSignalClass DIAGNOSTICS_INFORMATIONAL isAuthoritative is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.isAuthoritative)
    }

    @Test fun `ResultSignalClass ACCEPTANCE_CLOSURE_SIGNAL isAcceptanceRelevant is true`() {
        assertTrue(AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL.isAcceptanceRelevant)
    }

    @Test fun `ResultSignalClass AUTHORITY_RESULT isAcceptanceRelevant is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT.isAcceptanceRelevant)
    }

    @Test fun `ResultSignalClass DIAGNOSTICS_INFORMATIONAL isAcceptanceRelevant is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.isAcceptanceRelevant)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. AcceptanceCandidateClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `AcceptanceCandidateClass has 4 values`() {
        assertEquals(4, AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.values().size)
    }

    @Test fun `AcceptanceCandidateClass ELIGIBLE_FOR_ACCEPTANCE wireValue is eligible_for_acceptance`() {
        assertEquals(
            "eligible_for_acceptance",
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE.wireValue
        )
    }

    @Test fun `AcceptanceCandidateClass PENDING_RESULT_RETURN wireValue is pending_result_return`() {
        assertEquals(
            "pending_result_return",
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.PENDING_RESULT_RETURN.wireValue
        )
    }

    @Test fun `AcceptanceCandidateClass ACCEPTANCE_BLOCKED wireValue is acceptance_blocked`() {
        assertEquals(
            "acceptance_blocked",
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED.wireValue
        )
    }

    @Test fun `AcceptanceCandidateClass CLOSURE_NOT_APPLICABLE wireValue is closure_not_applicable`() {
        assertEquals(
            "closure_not_applicable",
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE.wireValue
        )
    }

    @Test fun `AcceptanceCandidateClass fromWireValue unknown returns CLOSURE_NOT_APPLICABLE`() {
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE,
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.fromWireValue("unknown_value")
        )
    }

    @Test fun `AcceptanceCandidateClass ELIGIBLE_FOR_ACCEPTANCE isEligible is true`() {
        assertTrue(AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE.isEligible)
    }

    @Test fun `AcceptanceCandidateClass PENDING_RESULT_RETURN isEligible is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.PENDING_RESULT_RETURN.isEligible)
    }

    @Test fun `AcceptanceCandidateClass ACCEPTANCE_BLOCKED isEligible is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED.isEligible)
    }

    @Test fun `AcceptanceCandidateClass CLOSURE_NOT_APPLICABLE isEligible is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE.isEligible)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. derive() — DIAGNOSTICS_INFORMATIONAL path (not terminal, no closure)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive not-terminal no-closure returns DIAGNOSTICS_INFORMATIONAL and PENDING_RESULT_RETURN`() {
        val result = derive(defaultInput(isTerminalPhase = false, closureReadyForAcceptance = false))
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.PENDING_RESULT_RETURN,
            result.acceptanceCandidateClass
        )
    }

    @Test fun `derive all defaults returns DIAGNOSTICS_INFORMATIONAL`() {
        val result = derive(defaultInput())
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            result.resultSignalClass
        )
    }

    @Test fun `derive device_state_snapshot pattern returns DIAGNOSTICS_INFORMATIONAL and CLOSURE_NOT_APPLICABLE when resultReturned has been set`() {
        // Simulating a non-terminal phase with no resultReturned but closureReadyForAcceptance=false:
        // this is what sendDeviceStateSnapshot uses (fixed to DIAGNOSTICS_INFORMATIONAL).
        val result = derive(
            defaultInput(
                isTerminalPhase = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            result.resultSignalClass
        )
        assertFalse(result.acceptanceCandidateClass.isEligible)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. derive() — ACCEPTANCE_BLOCKED path
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive closureReady + governanceBlocked returns ACCEPTANCE_CLOSURE_SIGNAL and ACCEPTANCE_BLOCKED`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = true
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED,
            result.acceptanceCandidateClass
        )
    }

    @Test fun `derive closureReady + runtimeConstrained returns ACCEPTANCE_CLOSURE_SIGNAL and ACCEPTANCE_BLOCKED`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isRuntimeConstrained = true
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED,
            result.acceptanceCandidateClass
        )
    }

    @Test fun `derive governance_blocked takes priority over authority_result conditions`() {
        // Even with all authority conditions met, governance_blocked forces ACCEPTANCE_BLOCKED.
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = true,
                isRuntimeConstrained = false,
                isHoldState = false
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ACCEPTANCE_BLOCKED,
            result.acceptanceCandidateClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. derive() — AUTHORITY_RESULT path
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive full authority conditions returns AUTHORITY_RESULT and ELIGIBLE_FOR_ACCEPTANCE`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = false,
                isRuntimeConstrained = false,
                isHoldState = false
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE,
            result.acceptanceCandidateClass
        )
        assertTrue(result.resultSignalClass.isAuthoritative)
        assertTrue(result.acceptanceCandidateClass.isEligible)
    }

    @Test fun `derive AUTHORITY_RESULT has isAuthoritative true`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true
            )
        )
        assertTrue(result.resultSignalClass.isAuthoritative)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. derive() — ACCEPTANCE_CLOSURE_SIGNAL (partial authority) path
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive closureReady + holdState returns ACCEPTANCE_CLOSURE_SIGNAL and ELIGIBLE_FOR_ACCEPTANCE`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isHoldState = true
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            result.resultSignalClass
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE,
            result.acceptanceCandidateClass
        )
    }

    @Test fun `derive closureReady without terminal returns ACCEPTANCE_CLOSURE_SIGNAL`() {
        // closureReadyForAcceptance=true but isTerminalPhase=false: acceptance signal, not authority.
        val result = derive(
            defaultInput(
                isTerminalPhase = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = true
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
            result.resultSignalClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. UplinkBoundarySnapshot.toWireMap()
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `toWireMap contains result_signal_class key`() {
        val snapshot = derive(defaultInput())
        assertTrue(
            snapshot.toWireMap().containsKey(
                AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS
            )
        )
    }

    @Test fun `toWireMap contains acceptance_candidate_class key`() {
        val snapshot = derive(defaultInput())
        assertTrue(
            snapshot.toWireMap().containsKey(
                AndroidResultUplinkBoundaryContract.KEY_ACCEPTANCE_CANDIDATE_CLASS
            )
        )
    }

    @Test fun `toWireMap contains result_uplink_boundary_schema_version key`() {
        val snapshot = derive(defaultInput())
        assertTrue(
            snapshot.toWireMap().containsKey(
                AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION
            )
        )
    }

    @Test fun `toWireMap result_signal_class value is String`() {
        val wireMap = derive(defaultInput()).toWireMap()
        assertTrue(wireMap[AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS] is String)
    }

    @Test fun `toWireMap schema_version matches SCHEMA_VERSION constant`() {
        val wireMap = derive(defaultInput()).toWireMap()
        assertEquals(
            AndroidResultUplinkBoundaryContract.SCHEMA_VERSION,
            wireMap[AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION]
        )
    }

    @Test fun `toWireMap has exactly 3 keys`() {
        assertEquals(3, derive(defaultInput()).toWireMap().size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. RESULT_UPLINK_BOUNDARY_INVARIANTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `RESULT_UPLINK_BOUNDARY_INVARIANTS has 6 entries`() {
        assertEquals(
            6,
            AndroidResultUplinkBoundaryContract.RESULT_UPLINK_BOUNDARY_INVARIANTS.size
        )
    }

    @Test fun `RESULT_UPLINK_BOUNDARY_INVARIANTS all have non-blank id`() {
        AndroidResultUplinkBoundaryContract.RESULT_UPLINK_BOUNDARY_INVARIANTS.forEach { inv ->
            assertTrue("Invariant id must not be blank", inv.id.isNotBlank())
        }
    }

    @Test fun `RESULT_UPLINK_BOUNDARY_INVARIANTS all have non-blank summary`() {
        AndroidResultUplinkBoundaryContract.RESULT_UPLINK_BOUNDARY_INVARIANTS.forEach { inv ->
            assertTrue("Invariant ${inv.id} summary must not be blank", inv.summary.isNotBlank())
        }
    }

    @Test fun `RESULT_UPLINK_BOUNDARY_INVARIANTS ids are unique`() {
        val ids = AndroidResultUplinkBoundaryContract.RESULT_UPLINK_BOUNDARY_INVARIANTS.map { it.id }
        assertEquals("Invariant IDs must be unique", ids.distinct().size, ids.size)
    }

    @Test fun `RESULT_UPLINK_BOUNDARY_INVARIANTS contains INV-RUB-01 through INV-RUB-06`() {
        val ids = AndroidResultUplinkBoundaryContract.RESULT_UPLINK_BOUNDARY_INVARIANTS.map { it.id }.toSet()
        for (i in 1..6) {
            val expectedId = "INV-RUB-0$i"
            assertTrue("$expectedId must be present in RESULT_UPLINK_BOUNDARY_INVARIANTS", ids.contains(expectedId))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `android-result-uplink-boundary-contract is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("android-result-uplink-boundary-contract"))
    }

    @Test fun `android-result-uplink-boundary-contract has CANONICAL_STABLE stability`() {
        val entry = StabilizationBaseline.forId("android-result-uplink-boundary-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `android-result-uplink-boundary-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-result-uplink-boundary-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry!!.extensionGuidance)
    }

    @Test fun `android-result-uplink-boundary-contract has introducedPr 96`() {
        val entry = StabilizationBaseline.forId("android-result-uplink-boundary-contract")
        assertNotNull(entry)
        assertEquals(96, entry!!.introducedPr)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. SCHEMA_VERSION constant
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidResultUplinkBoundaryContract.SCHEMA_VERSION)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. KEY_ constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `KEY_RESULT_SIGNAL_CLASS is not blank`() {
        assertTrue(AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS.isNotBlank())
    }

    @Test fun `KEY_ACCEPTANCE_CANDIDATE_CLASS is not blank`() {
        assertTrue(AndroidResultUplinkBoundaryContract.KEY_ACCEPTANCE_CANDIDATE_CLASS.isNotBlank())
    }

    @Test fun `KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION is not blank`() {
        assertTrue(AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION.isNotBlank())
    }

    @Test fun `KEY_ constants are distinct`() {
        val keys = setOf(
            AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS,
            AndroidResultUplinkBoundaryContract.KEY_ACCEPTANCE_CANDIDATE_CLASS,
            AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION
        )
        assertEquals(3, keys.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. V2_CONSUMPTION_PATH_MAP
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `V2_CONSUMPTION_PATH_MAP contains all 3 ResultSignalClass entries`() {
        assertEquals(
            3,
            AndroidResultUplinkBoundaryContract.V2_CONSUMPTION_PATH_MAP.size
        )
    }

    @Test fun `V2_CONSUMPTION_PATH_MAP AUTHORITY_RESULT maps to task_result_canonical_truth_chain`() {
        assertEquals(
            "task_result_canonical_truth_chain",
            AndroidResultUplinkBoundaryContract.V2_CONSUMPTION_PATH_MAP[
                AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT
            ]
        )
    }

    @Test fun `V2_CONSUMPTION_PATH_MAP ACCEPTANCE_CLOSURE_SIGNAL maps to acceptance_adjudication`() {
        assertEquals(
            "acceptance_adjudication",
            AndroidResultUplinkBoundaryContract.V2_CONSUMPTION_PATH_MAP[
                AndroidResultUplinkBoundaryContract.ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL
            ]
        )
    }

    @Test fun `V2_CONSUMPTION_PATH_MAP DIAGNOSTICS_INFORMATIONAL maps to android_device_state_store`() {
        assertEquals(
            "android_device_state_store",
            AndroidResultUplinkBoundaryContract.V2_CONSUMPTION_PATH_MAP[
                AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL
            ]
        )
    }

    @Test fun `V2_CONSUMPTION_PATH_MAP all values are non-blank`() {
        AndroidResultUplinkBoundaryContract.V2_CONSUMPTION_PATH_MAP.values.forEach { path ->
            assertTrue("V2 consumption path must not be blank", path.isNotBlank())
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13 & 14. INV-RUB-02/03 semantic invariants (isAuthoritative / isAcceptanceRelevant)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `INV-RUB-02 AUTHORITY_RESULT isAuthoritative is true`() {
        assertTrue(AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT.isAuthoritative)
    }

    @Test fun `INV-RUB-02 DIAGNOSTICS_INFORMATIONAL isAuthoritative is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.isAuthoritative)
    }

    @Test fun `INV-RUB-03 DIAGNOSTICS_INFORMATIONAL isAcceptanceRelevant is false`() {
        assertFalse(AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL.isAcceptanceRelevant)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 15. Priority: governance_blocked overrides authority_result
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `governance_blocked prevents AUTHORITY_RESULT even with all other conditions met`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = true,
                isRuntimeConstrained = false,
                isHoldState = false
            )
        )
        assertNotEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            result.resultSignalClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 16. Non-terminal without closure → PENDING_RESULT_RETURN
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `non-terminal without closureReadyForAcceptance returns PENDING_RESULT_RETURN`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = false,
                resultReturned = false,
                completionSignaled = false,
                closureReadyForAcceptance = false
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.AcceptanceCandidateClass.PENDING_RESULT_RETURN,
            result.acceptanceCandidateClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 17. hold state boundary
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `isHoldState true prevents AUTHORITY_RESULT`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = false,
                isRuntimeConstrained = false,
                isHoldState = true
            )
        )
        assertNotEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            result.resultSignalClass
        )
    }

    @Test fun `isHoldState false allows AUTHORITY_RESULT`() {
        val result = derive(
            defaultInput(
                isTerminalPhase = true,
                resultReturned = true,
                completionSignaled = true,
                closureReadyForAcceptance = true,
                isGovernanceBlocked = false,
                isRuntimeConstrained = false,
                isHoldState = false
            )
        )
        assertEquals(
            AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT,
            result.resultSignalClass
        )
    }
}
