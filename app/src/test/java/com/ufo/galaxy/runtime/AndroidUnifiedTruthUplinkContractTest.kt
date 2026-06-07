package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Android 统一真相上行合约（AndroidUnifiedTruthUplinkContract）单元测试。
 *
 * 验证以下接受准则：
 *
 *  1. **TruthCategory** — 七个分类定义；wire value 稳定；fromWireValue 正确。
 *  2. **ConstraintSemantics** — 六个枚举值；isConstraint/isDeferred 语义正确；derive() 优先级正确。
 *  3. **GovernanceState / TakeoverState** — 治理态与接管态枚举稳定，deriveGovernanceTruth() 逻辑正确。
 *  4. **LocalCapabilityState** — 五个枚举值；isLocalModeCapable 正确；derive() 逻辑正确。
 *  5. **UPLINK_INVARIANTS** — 共 18 条；全部非空。
 *  6. **build()** — 核心工厂方法；所有不变量在运行时被执行。
 *  7. **UnifiedTruthSnapshot.toWireMap()** — 所有非 null 字段出现在 map 中；null 字段被省略。
 *  8. **StabilizationBaseline** — "android-unified-truth-uplink-contract" 已注册为 CANONICAL_STABLE。
 *  9. **SCHEMA_VERSION** — 为 "1"。
 * 10. **KEY_ 常量** — 所有 KEY_ 常量非空。
 * 11. **participation_tier 保底** — null 时默认 pre_attach（INV-UTU-01）。
 *
 * ## 测试矩阵
 *
 * ### TruthCategory
 *  - PARTICIPATION wireValue 为 participation
 *  - MODE wireValue 为 mode
 *  - GOVERNANCE wireValue 为 governance
 *  - EXECUTION wireValue 为 execution
 *  - CLOSURE_UPSTREAM wireValue 为 closure_upstream
 *  - CONTINUITY wireValue 为 continuity
 *  - LOCAL_CAPABILITY wireValue 为 local_capability
 *  - fromWireValue 对每个值返回正确分类
 *  - fromWireValue 对未知值返回 null
 *  - ALL_WIRE_VALUES 恰好包含七条
 *
 * ### ConstraintSemantics
 *  - NONE wireValue 为 none；isConstraint=false；isDeferred=false
 *  - RUNTIME_CONSTRAINED wireValue 为 runtime_constrained；isConstraint=true
 *  - RUNTIME_DEFERRED wireValue 为 runtime_deferred；isDeferred=true
 *  - LOCAL_MODE_GATE_DEFERRED wireValue 为 local_mode_gate_deferred；isDeferred=true
 *  - EXECUTION_PRESSURE wireValue 为 execution_pressure；isConstraint=true
 *  - HOLD wireValue 为 hold；isConstraint=true
 *  - ALL_WIRE_VALUES 恰好包含六条
 *  - derive(isHold=true) 返回 HOLD（最高优先级）
 *  - derive(isConstrained=true) 返回 RUNTIME_CONSTRAINED
 *  - derive(isLocalModeGateHold=true) 返回 LOCAL_MODE_GATE_DEFERRED
 *  - derive(isExecutionBusy=true) 返回 EXECUTION_PRESSURE
 *  - derive(isDeferred=true) 返回 RUNTIME_DEFERRED
 *  - derive() 默认返回 NONE
 *
 * ### LocalCapabilityState
 *  - FULL wireValue 为 full；isLocalModeCapable=true
 *  - PARTIAL wireValue 为 partial；isLocalModeCapable=true
 *  - DEGRADED wireValue 为 degraded；isLocalModeCapable=false
 *  - UNAVAILABLE wireValue 为 unavailable；isLocalModeCapable=false
 *  - UNKNOWN wireValue 为 unknown；isLocalModeCapable=false
 *  - ALL_WIRE_VALUES 恰好包含五条
 *  - derive(llmReady=true, accessibilityReady=true) 返回 FULL
 *  - derive(llmReady=true, accessibilityReady=false) 返回 PARTIAL
 *  - derive(llmReady=true, accessibilityReady=null) 返回 PARTIAL
 *  - derive(llmReady=true, isDegraded=true) 返回 DEGRADED
 *  - derive(llmReady=false, inferenceAvail=false) 返回 UNAVAILABLE
 *  - derive(inferenceAvail=true, accessibilityReady=true) 返回 FULL
 *
 * ### build() — 不变量验证
 *  - participation_tier null 时默认 pre_attach（INV-UTU-01）
 *  - execution_mode_state null 时默认 inactive（INV-UTU-02）
 *  - constraint_semantics 永不为 null（INV-UTU-03）
 *  - local_capability_state 永不为 null（INV-UTU-04）
 *  - schema_version 始终为 SCHEMA_VERSION（INV-UTU-05）
 *  - dispatch_eligible 在 dispatch_eligible tier 时为 true（INV-UTU-06）
 *  - dispatch_eligible 在 distributed_participant tier 时为 true（INV-UTU-06）
 *  - dispatch_eligible 在 pre_attach tier 时为 false（INV-UTU-06）
 *  - distributed_participant 仅在 distributed_participant tier 时为 true（INV-UTU-07）
 *  - session_attached 在 pre_attach tier 时为 false（INV-UTU-08）
 *  - session_attached 在 fully_attached tier 时为 true（INV-UTU-08）
 *  - local_mode_active 仅在 local_only 模式时为 true（INV-UTU-09）
 *  - runtime_constrained 在 RUNTIME_CONSTRAINED 时为 true（INV-UTU-10）
 *  - runtime_deferred 在 RUNTIME_DEFERRED 时为 true（INV-UTU-11）
 *  - local_mode_capable 在 FULL/PARTIAL 能力状态时为 true（INV-UTU-12）
 *
 * ### UnifiedTruthSnapshot.toWireMap()
 *  - 包含所有必需的非 null 字段键
 *  - null 字段被省略（不出现在 map 中）
 *  - schema_version 键为 KEY_SCHEMA_VERSION
 *  - participation_tier 键为 KEY_PARTICIPATION_TIER
 */
class AndroidUnifiedTruthUplinkContractTest {

    // ════════════════════════════════════════════════════════════════════════
    // TruthCategory
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `PARTICIPATION wireValue is participation`() {
        assertEquals(
            "participation",
            AndroidUnifiedTruthUplinkContract.TruthCategory.PARTICIPATION.wireValue
        )
    }

    @Test
    fun `MODE wireValue is mode`() {
        assertEquals(
            "mode",
            AndroidUnifiedTruthUplinkContract.TruthCategory.MODE.wireValue
        )
    }

    @Test
    fun `GOVERNANCE wireValue is governance`() {
        assertEquals(
            "governance",
            AndroidUnifiedTruthUplinkContract.TruthCategory.GOVERNANCE.wireValue
        )
    }

    @Test
    fun `EXECUTION wireValue is execution`() {
        assertEquals(
            "execution",
            AndroidUnifiedTruthUplinkContract.TruthCategory.EXECUTION.wireValue
        )
    }

    @Test
    fun `CLOSURE_UPSTREAM wireValue is closure_upstream`() {
        assertEquals(
            "closure_upstream",
            AndroidUnifiedTruthUplinkContract.TruthCategory.CLOSURE_UPSTREAM.wireValue
        )
    }

    @Test
    fun `CONTINUITY wireValue is continuity`() {
        assertEquals(
            "continuity",
            AndroidUnifiedTruthUplinkContract.TruthCategory.CONTINUITY.wireValue
        )
    }

    @Test
    fun `LOCAL_CAPABILITY wireValue is local_capability`() {
        assertEquals(
            "local_capability",
            AndroidUnifiedTruthUplinkContract.TruthCategory.LOCAL_CAPABILITY.wireValue
        )
    }

    @Test
    fun `TruthCategory fromWireValue returns correct value for each category`() {
        val C = AndroidUnifiedTruthUplinkContract.TruthCategory
        assertEquals(C.PARTICIPATION, C.fromWireValue("participation"))
        assertEquals(C.MODE, C.fromWireValue("mode"))
        assertEquals(C.GOVERNANCE, C.fromWireValue("governance"))
        assertEquals(C.EXECUTION, C.fromWireValue("execution"))
        assertEquals(C.CLOSURE_UPSTREAM, C.fromWireValue("closure_upstream"))
        assertEquals(C.CONTINUITY, C.fromWireValue("continuity"))
        assertEquals(C.LOCAL_CAPABILITY, C.fromWireValue("local_capability"))
    }

    @Test
    fun `TruthCategory fromWireValue returns null for unknown value`() {
        assertNull(AndroidUnifiedTruthUplinkContract.TruthCategory.fromWireValue("unknown_category"))
    }

    @Test
    fun `TruthCategory ALL_WIRE_VALUES contains exactly seven entries`() {
        assertEquals(7, AndroidUnifiedTruthUplinkContract.TruthCategory.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all TruthCategory wire values are unique`() {
        val values = AndroidUnifiedTruthUplinkContract.TruthCategory.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // ConstraintSemantics
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `NONE wireValue is none and not constraint nor deferred`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.NONE
        assertEquals("none", cs.wireValue)
        assertFalse(cs.isConstraint)
        assertFalse(cs.isDeferred)
    }

    @Test
    fun `RUNTIME_CONSTRAINED wireValue is runtime_constrained and isConstraint`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.RUNTIME_CONSTRAINED
        assertEquals("runtime_constrained", cs.wireValue)
        assertTrue(cs.isConstraint)
        assertFalse(cs.isDeferred)
    }

    @Test
    fun `RUNTIME_DEFERRED wireValue is runtime_deferred and isDeferred`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.RUNTIME_DEFERRED
        assertEquals("runtime_deferred", cs.wireValue)
        assertFalse(cs.isConstraint)
        assertTrue(cs.isDeferred)
    }

    @Test
    fun `LOCAL_MODE_GATE_DEFERRED wireValue is local_mode_gate_deferred and isDeferred`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.LOCAL_MODE_GATE_DEFERRED
        assertEquals("local_mode_gate_deferred", cs.wireValue)
        assertFalse(cs.isConstraint)
        assertTrue(cs.isDeferred)
    }

    @Test
    fun `EXECUTION_PRESSURE wireValue is execution_pressure and isConstraint`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.EXECUTION_PRESSURE
        assertEquals("execution_pressure", cs.wireValue)
        assertTrue(cs.isConstraint)
        assertFalse(cs.isDeferred)
    }

    @Test
    fun `HOLD wireValue is hold and isConstraint`() {
        val cs = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.HOLD
        assertEquals("hold", cs.wireValue)
        assertTrue(cs.isConstraint)
        assertFalse(cs.isDeferred)
    }

    @Test
    fun `ConstraintSemantics ALL_WIRE_VALUES contains exactly six entries`() {
        assertEquals(6, AndroidUnifiedTruthUplinkContract.ConstraintSemantics.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ConstraintSemantics fromWireValue returns correct value for each semantic`() {
        val CS = AndroidUnifiedTruthUplinkContract.ConstraintSemantics
        assertEquals(CS.NONE, CS.fromWireValue("none"))
        assertEquals(CS.RUNTIME_CONSTRAINED, CS.fromWireValue("runtime_constrained"))
        assertEquals(CS.RUNTIME_DEFERRED, CS.fromWireValue("runtime_deferred"))
        assertEquals(CS.LOCAL_MODE_GATE_DEFERRED, CS.fromWireValue("local_mode_gate_deferred"))
        assertEquals(CS.EXECUTION_PRESSURE, CS.fromWireValue("execution_pressure"))
        assertEquals(CS.HOLD, CS.fromWireValue("hold"))
    }

    @Test
    fun `ConstraintSemantics fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidUnifiedTruthUplinkContract.ConstraintSemantics.fromWireValue("unknown_semantic")
        )
    }

    @Test
    fun `derive isHold=true returns HOLD highest priority`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isConstrained = true,
            isDeferred = true,
            isLocalModeGateHold = true,
            isExecutionBusy = true,
            isHold = true
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.ConstraintSemantics.HOLD, result)
    }

    @Test
    fun `derive isConstrained=true without hold returns RUNTIME_CONSTRAINED`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isConstrained = true,
            isHold = false
        )
        assertEquals(
            AndroidUnifiedTruthUplinkContract.ConstraintSemantics.RUNTIME_CONSTRAINED,
            result
        )
    }

    @Test
    fun `derive isLocalModeGateHold=true without constrained returns LOCAL_MODE_GATE_DEFERRED`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isLocalModeGateHold = true,
            isConstrained = false,
            isHold = false
        )
        assertEquals(
            AndroidUnifiedTruthUplinkContract.ConstraintSemantics.LOCAL_MODE_GATE_DEFERRED,
            result
        )
    }

    @Test
    fun `derive isExecutionBusy=true without hold or constrained returns EXECUTION_PRESSURE`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isExecutionBusy = true,
            isConstrained = false,
            isHold = false,
            isLocalModeGateHold = false
        )
        assertEquals(
            AndroidUnifiedTruthUplinkContract.ConstraintSemantics.EXECUTION_PRESSURE,
            result
        )
    }

    @Test
    fun `derive isDeferred=true without higher priority conditions returns RUNTIME_DEFERRED`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive(
            isDeferred = true,
            isConstrained = false,
            isHold = false,
            isLocalModeGateHold = false,
            isExecutionBusy = false
        )
        assertEquals(
            AndroidUnifiedTruthUplinkContract.ConstraintSemantics.RUNTIME_DEFERRED,
            result
        )
    }

    @Test
    fun `derive no flags returns NONE`() {
        val result = AndroidUnifiedTruthUplinkContract.ConstraintSemantics.derive()
        assertEquals(AndroidUnifiedTruthUplinkContract.ConstraintSemantics.NONE, result)
    }

    // ════════════════════════════════════════════════════════════════════════
    // GovernanceState / TakeoverState
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `GovernanceState wire values are stable`() {
        val state = AndroidUnifiedTruthUplinkContract.GovernanceState
        assertEquals("local_autonomous", state.LOCAL_AUTONOMOUS.wireValue)
        assertEquals("v2_governed", state.V2_GOVERNED.wireValue)
        assertEquals("delegated_execution", state.DELEGATED_EXECUTION.wireValue)
        assertEquals("governance_blocked", state.GOVERNANCE_BLOCKED.wireValue)
        assertEquals(4, state.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `TakeoverState wire values are stable`() {
        val state = AndroidUnifiedTruthUplinkContract.TakeoverState
        assertEquals("inactive", state.INACTIVE.wireValue)
        assertEquals("pending", state.PENDING.wireValue)
        assertEquals("active", state.ACTIVE.wireValue)
        assertEquals(3, state.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `deriveGovernanceTruth returns local autonomous when not governed by V2`() {
        val truth = AndroidUnifiedTruthUplinkContract.deriveGovernanceTruth(
            crossDeviceEnabled = false,
            sessionAttached = false,
            activeTaskId = null,
            activeTakeoverId = null,
            operatorSuspendedOrIsolated = false
        )
        assertEquals("local_autonomous", truth.governance_state)
        assertFalse(truth.governance_blocked)
        assertFalse(truth.delegated_execution_active)
        assertEquals("inactive", truth.takeover_state)
    }

    @Test
    fun `deriveGovernanceTruth returns v2 governed when session attached without active task`() {
        val truth = AndroidUnifiedTruthUplinkContract.deriveGovernanceTruth(
            crossDeviceEnabled = true,
            sessionAttached = true,
            activeTaskId = null,
            activeTakeoverId = null,
            operatorSuspendedOrIsolated = false
        )
        assertEquals("v2_governed", truth.governance_state)
        assertFalse(truth.governance_blocked)
        assertFalse(truth.delegated_execution_active)
        assertEquals("inactive", truth.takeover_state)
    }

    @Test
    fun `deriveGovernanceTruth returns delegated execution state with active takeover during execution`() {
        val truth = AndroidUnifiedTruthUplinkContract.deriveGovernanceTruth(
            crossDeviceEnabled = true,
            sessionAttached = true,
            activeTaskId = "task-1",
            activeTakeoverId = "takeover-1",
            operatorSuspendedOrIsolated = false
        )
        assertEquals("delegated_execution", truth.governance_state)
        assertFalse(truth.governance_blocked)
        assertTrue(truth.delegated_execution_active)
        assertEquals("active", truth.takeover_state)
    }

    @Test
    fun `deriveGovernanceTruth returns blocked when operator isolation is active`() {
        val truth = AndroidUnifiedTruthUplinkContract.deriveGovernanceTruth(
            crossDeviceEnabled = true,
            sessionAttached = true,
            activeTaskId = null,
            activeTakeoverId = "takeover-1",
            operatorSuspendedOrIsolated = true
        )
        assertEquals("governance_blocked", truth.governance_state)
        assertTrue(truth.governance_blocked)
        assertFalse(truth.delegated_execution_active)
        assertEquals("pending", truth.takeover_state)
    }

    // ════════════════════════════════════════════════════════════════════════
    // LocalCapabilityState
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `FULL wireValue is full and isLocalModeCapable`() {
        val lcs = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.FULL
        assertEquals("full", lcs.wireValue)
        assertTrue(lcs.isLocalModeCapable)
    }

    @Test
    fun `PARTIAL wireValue is partial and isLocalModeCapable`() {
        val lcs = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.PARTIAL
        assertEquals("partial", lcs.wireValue)
        assertTrue(lcs.isLocalModeCapable)
    }

    @Test
    fun `DEGRADED wireValue is degraded and not isLocalModeCapable`() {
        val lcs = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.DEGRADED
        assertEquals("degraded", lcs.wireValue)
        assertFalse(lcs.isLocalModeCapable)
    }

    @Test
    fun `UNAVAILABLE wireValue is unavailable and not isLocalModeCapable`() {
        val lcs = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.UNAVAILABLE
        assertEquals("unavailable", lcs.wireValue)
        assertFalse(lcs.isLocalModeCapable)
    }

    @Test
    fun `UNKNOWN wireValue is unknown and not isLocalModeCapable`() {
        val lcs = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.UNKNOWN
        assertEquals("unknown", lcs.wireValue)
        assertFalse(lcs.isLocalModeCapable)
    }

    @Test
    fun `LocalCapabilityState ALL_WIRE_VALUES contains exactly five entries`() {
        assertEquals(5, AndroidUnifiedTruthUplinkContract.LocalCapabilityState.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `LocalCapabilityState fromWireValue returns correct value for each state`() {
        val LCS = AndroidUnifiedTruthUplinkContract.LocalCapabilityState
        assertEquals(LCS.FULL, LCS.fromWireValue("full"))
        assertEquals(LCS.PARTIAL, LCS.fromWireValue("partial"))
        assertEquals(LCS.DEGRADED, LCS.fromWireValue("degraded"))
        assertEquals(LCS.UNAVAILABLE, LCS.fromWireValue("unavailable"))
        assertEquals(LCS.UNKNOWN, LCS.fromWireValue("unknown"))
    }

    @Test
    fun `LocalCapabilityState fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidUnifiedTruthUplinkContract.LocalCapabilityState.fromWireValue("nonexistent")
        )
    }

    @Test
    fun `derive llmReady=true accessibilityReady=true returns FULL`() {
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = true,
            localInferenceAvailable = true,
            accessibilityReady = true
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.FULL, result)
    }

    @Test
    fun `derive llmReady=true accessibilityReady=false returns PARTIAL`() {
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = true,
            localInferenceAvailable = true,
            accessibilityReady = false
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.PARTIAL, result)
    }

    @Test
    fun `derive llmReady=true accessibilityReady=null returns PARTIAL`() {
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = true,
            localInferenceAvailable = null,
            accessibilityReady = null
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.PARTIAL, result)
    }

    @Test
    fun `derive llmReady=true isDegraded=true returns DEGRADED`() {
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = true,
            localInferenceAvailable = true,
            accessibilityReady = true,
            isDegraded = true
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.DEGRADED, result)
    }

    @Test
    fun `derive llmReady=false inferenceAvail=false returns UNAVAILABLE`() {
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = false,
            localInferenceAvailable = false,
            accessibilityReady = true
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.UNAVAILABLE, result)
    }

    @Test
    fun `derive inferenceAvail=true accessibilityReady=true returns FULL via fallback`() {
        // When localLlmReady is null but inferenceAvailable is true
        val result = AndroidUnifiedTruthUplinkContract.LocalCapabilityState.derive(
            localLlmReady = null,
            localInferenceAvailable = true,
            accessibilityReady = true
        )
        assertEquals(AndroidUnifiedTruthUplinkContract.LocalCapabilityState.FULL, result)
    }

    // ════════════════════════════════════════════════════════════════════════
    // UPLINK_INVARIANTS
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `UPLINK_INVARIANTS contains exactly 18 invariants`() {
        assertEquals(18, AndroidUnifiedTruthUplinkContract.UPLINK_INVARIANTS.size)
    }

    @Test
    fun `all UPLINK_INVARIANTS are non-blank`() {
        AndroidUnifiedTruthUplinkContract.UPLINK_INVARIANTS.forEachIndexed { i, inv ->
            assertTrue("Invariant at index $i is blank", inv.isNotBlank())
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // build() — 不变量验证
    // ════════════════════════════════════════════════════════════════════════

    private fun minimalBuild(
        participationStateWire: String = "pre_attach",
        tierWire: String? = null,
        modeStateWire: String? = null,
        crossDevice: Boolean? = null
    ) = AndroidUnifiedTruthUplinkContract.build(
        authoritativeParticipationStateWire = participationStateWire,
        participationTierWire = tierWire,
        executionModeStateWire = modeStateWire,
        crossDeviceEligibility = crossDevice,
        deviceId = "test-device"
    )

    @Test
    fun `build participation_tier null defaults to pre_attach INV-UTU-01`() {
        val snapshot = minimalBuild(tierWire = null)
        assertEquals("pre_attach", snapshot.participation_tier)
    }

    @Test
    fun `build execution_mode_state null defaults to inactive INV-UTU-02`() {
        val snapshot = minimalBuild(modeStateWire = null)
        assertEquals("inactive", snapshot.execution_mode_state)
    }

    @Test
    fun `build constraint_semantics never null INV-UTU-03`() {
        val snapshot = minimalBuild()
        assertNotNull(snapshot.constraint_semantics)
    }

    @Test
    fun `build local_capability_state never null INV-UTU-04`() {
        val snapshot = minimalBuild()
        assertNotNull(snapshot.local_capability_state)
    }

    @Test
    fun `build schema_version always equals SCHEMA_VERSION INV-UTU-05`() {
        val snapshot = minimalBuild()
        assertEquals(AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION, snapshot.schema_version)
    }

    @Test
    fun `build dispatch_eligible true when tier is dispatch_eligible INV-UTU-06`() {
        val snapshot = minimalBuild(
            participationStateWire = "dispatch_eligible",
            tierWire = "dispatch_eligible"
        )
        assertTrue(snapshot.dispatch_eligible)
    }

    @Test
    fun `build dispatch_eligible true when tier is distributed_participant INV-UTU-06`() {
        val snapshot = minimalBuild(
            participationStateWire = "distributed_participant",
            tierWire = "distributed_participant"
        )
        assertTrue(snapshot.dispatch_eligible)
    }

    @Test
    fun `build dispatch_eligible false when tier is pre_attach INV-UTU-06`() {
        val snapshot = minimalBuild(tierWire = "pre_attach")
        assertFalse(snapshot.dispatch_eligible)
    }

    @Test
    fun `build distributed_participant true only for distributed_participant tier INV-UTU-07`() {
        val dispatchSnapshot = minimalBuild(
            participationStateWire = "dispatch_eligible",
            tierWire = "dispatch_eligible"
        )
        assertFalse(dispatchSnapshot.distributed_participant)

        val distSnapshot = minimalBuild(
            participationStateWire = "distributed_participant",
            tierWire = "distributed_participant"
        )
        assertTrue(distSnapshot.distributed_participant)
    }

    @Test
    fun `build session_attached false when tier is pre_attach INV-UTU-08`() {
        val snapshot = minimalBuild(tierWire = "pre_attach")
        assertFalse(snapshot.session_attached)
    }

    @Test
    fun `build session_attached true when tier is fully_attached INV-UTU-08`() {
        val snapshot = minimalBuild(
            participationStateWire = "fully_attached",
            tierWire = "fully_attached"
        )
        assertTrue(snapshot.session_attached)
    }

    @Test
    fun `build local_mode_active true only when mode is local_only INV-UTU-09`() {
        val localSnapshot = minimalBuild(modeStateWire = "local_only")
        assertTrue(localSnapshot.local_mode_active)

        val crossSnapshot = minimalBuild(modeStateWire = "cross_device_active")
        assertFalse(crossSnapshot.local_mode_active)
    }

    @Test
    fun `build runtime_constrained true when isConstrained INV-UTU-10`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "pre_attach",
            participationTierWire = null,
            executionModeStateWire = null,
            crossDeviceEligibility = null,
            isConstrained = true
        )
        assertTrue(snapshot.runtime_constrained)
    }

    @Test
    fun `build runtime_deferred true when isDeferred INV-UTU-11`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "pre_attach",
            participationTierWire = null,
            executionModeStateWire = null,
            crossDeviceEligibility = null,
            isDeferred = true
        )
        assertTrue(snapshot.runtime_deferred)
    }

    @Test
    fun `build local_mode_capable true when localLlmReady and accessibilityReady INV-UTU-12`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "pre_attach",
            participationTierWire = null,
            executionModeStateWire = null,
            crossDeviceEligibility = null,
            localLlmReady = true,
            accessibilityReady = true
        )
        assertTrue(snapshot.local_mode_capable)
        assertEquals("full", snapshot.local_capability_state)
    }

    @Test
    fun `build local_mode_capable false when inference unavailable INV-UTU-12`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "pre_attach",
            participationTierWire = null,
            executionModeStateWire = null,
            crossDeviceEligibility = null,
            localLlmReady = false,
            localInferenceAvailable = false,
            accessibilityReady = true
        )
        assertFalse(snapshot.local_mode_capable)
        assertEquals("unavailable", snapshot.local_capability_state)
    }

    @Test
    fun `build governance truth separates governance from mode state`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "fully_attached",
            participationTierWire = "fully_attached",
            executionModeStateWire = "cross_device_active",
            crossDeviceEligibility = true,
            crossDeviceEnabled = true,
            activeTaskId = "task-1",
            activeTakeoverId = "takeover-1"
        )
        assertEquals("delegated_execution", snapshot.governance_state)
        assertFalse(snapshot.governance_blocked)
        assertTrue(snapshot.delegated_execution_active)
        assertEquals("active", snapshot.takeover_state)
    }

    @Test
    fun `build governance blocked is only true for blocked governance state INV-UTU-17`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "fully_attached",
            participationTierWire = "fully_attached",
            executionModeStateWire = "cross_device_active",
            crossDeviceEligibility = true,
            crossDeviceEnabled = true,
            activeTakeoverId = "takeover-1",
            operatorSuspendedOrIsolated = true
        )
        assertEquals("governance_blocked", snapshot.governance_state)
        assertTrue(snapshot.governance_blocked)
        assertEquals("pending", snapshot.takeover_state)
    }

    // ════════════════════════════════════════════════════════════════════════
    // UnifiedTruthSnapshot.toWireMap()
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap contains all required non-null fields`() {
        val snapshot = minimalBuild(
            participationStateWire = "dispatch_eligible",
            tierWire = "dispatch_eligible",
            modeStateWire = "cross_device_active",
            crossDevice = true
        )
        val map = snapshot.toWireMap()

        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_SCHEMA_VERSION))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_PARTICIPATION_TIER))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_EXECUTION_MODE_STATE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_DISPATCH_ELIGIBLE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_DISTRIBUTED_PARTICIPANT))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_SESSION_ATTACHED))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_LOCAL_MODE_ACTIVE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_CONSTRAINED))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_DEFERRED))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_CONSTRAINT_SEMANTICS))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_GOVERNANCE_STATE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_GOVERNANCE_BLOCKED))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_DELEGATED_EXECUTION_ACTIVE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_TAKEOVER_STATE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_LOCAL_MODE_CAPABLE))
        assertTrue(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_LOCAL_CAPABILITY_STATE))
    }

    @Test
    fun `toWireMap omits null optional fields`() {
        // Build snapshot with all optional fields null
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "pre_attach",
            participationTierWire = null,
            executionModeStateWire = null,
            crossDeviceEligibility = null,
            taskId = null,
            runtimeSessionId = null,
            executionPhase = null,
            delegatedExecutionState = null,
            durableParticipantId = null,
            attachedSessionId = null,
            localInferenceAvailable = null,
            localLlmReady = null,
            accessibilityReady = null
        )
        val map = snapshot.toWireMap()

        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_TASK_ID))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_SESSION_ID))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_EXECUTION_PHASE))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_DURABLE_PARTICIPANT_ID))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_LOCAL_INFERENCE_AVAILABLE))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_LOCAL_LLM_READY))
        assertFalse(map.containsKey(AndroidUnifiedTruthUplinkContract.KEY_ACCESSIBILITY_READY))
    }

    @Test
    fun `toWireMap schema_version key equals KEY_SCHEMA_VERSION`() {
        val snapshot = minimalBuild()
        val map = snapshot.toWireMap()
        assertEquals(
            AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION,
            map[AndroidUnifiedTruthUplinkContract.KEY_SCHEMA_VERSION]
        )
    }

    @Test
    fun `toWireMap participation_tier key equals KEY_PARTICIPATION_TIER`() {
        val snapshot = minimalBuild(tierWire = "dispatch_eligible")
        val map = snapshot.toWireMap()
        assertEquals("dispatch_eligible", map[AndroidUnifiedTruthUplinkContract.KEY_PARTICIPATION_TIER])
    }

    @Test
    fun `toWireMap includes optional fields when non-null`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "dispatch_eligible",
            participationTierWire = "dispatch_eligible",
            executionModeStateWire = "cross_device_active",
            crossDeviceEligibility = true,
            taskId = "task-123",
            runtimeSessionId = "session-456",
            durableParticipantId = "durable-789"
        )
        val map = snapshot.toWireMap()

        assertEquals("task-123", map[AndroidUnifiedTruthUplinkContract.KEY_TASK_ID])
        assertEquals("session-456", map[AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_SESSION_ID])
        assertEquals("durable-789", map[AndroidUnifiedTruthUplinkContract.KEY_DURABLE_PARTICIPANT_ID])
    }

    // ════════════════════════════════════════════════════════════════════════
    // KEY_ 常量
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `all KEY_ constants are non-blank`() {
        val keys = listOf(
            AndroidUnifiedTruthUplinkContract.KEY_SCHEMA_VERSION,
            AndroidUnifiedTruthUplinkContract.KEY_AUTHORITATIVE_PARTICIPATION_STATE,
            AndroidUnifiedTruthUplinkContract.KEY_PARTICIPATION_TIER,
            AndroidUnifiedTruthUplinkContract.KEY_DISPATCH_ELIGIBLE,
            AndroidUnifiedTruthUplinkContract.KEY_DISTRIBUTED_PARTICIPANT,
            AndroidUnifiedTruthUplinkContract.KEY_SESSION_ATTACHED,
            AndroidUnifiedTruthUplinkContract.KEY_EXECUTION_MODE_STATE,
            AndroidUnifiedTruthUplinkContract.KEY_CROSS_DEVICE_ELIGIBILITY,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_MODE_ACTIVE,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_MODE_GATE_DEFERRED,
            AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_CONSTRAINED,
            AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_DEFERRED,
            AndroidUnifiedTruthUplinkContract.KEY_CONSTRAINT_SEMANTICS,
            AndroidUnifiedTruthUplinkContract.KEY_GOVERNANCE_STATE,
            AndroidUnifiedTruthUplinkContract.KEY_GOVERNANCE_BLOCKED,
            AndroidUnifiedTruthUplinkContract.KEY_DELEGATED_EXECUTION_ACTIVE,
            AndroidUnifiedTruthUplinkContract.KEY_TAKEOVER_STATE,
            AndroidUnifiedTruthUplinkContract.KEY_TASK_ID,
            AndroidUnifiedTruthUplinkContract.KEY_DEVICE_ID,
            AndroidUnifiedTruthUplinkContract.KEY_RUNTIME_SESSION_ID,
            AndroidUnifiedTruthUplinkContract.KEY_EXECUTION_PHASE,
            AndroidUnifiedTruthUplinkContract.KEY_DELEGATED_EXECUTION_STATE,
            AndroidUnifiedTruthUplinkContract.KEY_NORMALIZED_STATUS,
            AndroidUnifiedTruthUplinkContract.KEY_RESULT_SUMMARY,
            AndroidUnifiedTruthUplinkContract.KEY_PROBLEM_SOLVING_CLOSURE_CLASS,
            AndroidUnifiedTruthUplinkContract.KEY_EXECUTION_SPINE_PARTICIPATION_KIND,
            AndroidUnifiedTruthUplinkContract.KEY_DURABLE_PARTICIPANT_ID,
            AndroidUnifiedTruthUplinkContract.KEY_PARTICIPANT_IDENTITY_FRESHNESS,
            AndroidUnifiedTruthUplinkContract.KEY_ATTACHED_SESSION_ID,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_INFERENCE_AVAILABLE,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_LLM_READY,
            AndroidUnifiedTruthUplinkContract.KEY_ACCESSIBILITY_READY,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_MODE_CAPABLE,
            AndroidUnifiedTruthUplinkContract.KEY_LOCAL_CAPABILITY_STATE
        )
        keys.forEachIndexed { i, key ->
            assertTrue("KEY_ constant at index $i is blank: '$key'", key.isNotBlank())
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCHEMA_VERSION
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION)
    }

    // ════════════════════════════════════════════════════════════════════════
    // StabilizationBaseline 注册
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `StabilizationBaseline contains android-unified-truth-uplink-contract as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-unified-truth-uplink-contract")
        assertNotNull(
            "android-unified-truth-uplink-contract not registered in StabilizationBaseline",
            entry
        )
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `StabilizationBaseline android-unified-truth-uplink-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-unified-truth-uplink-contract")!!
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `StabilizationBaseline android-unified-truth-uplink-contract introducedPr is 90`() {
        val entry = StabilizationBaseline.forId("android-unified-truth-uplink-contract")!!
        assertEquals(90, entry.introducedPr)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 参与层级 → 布尔字段一致性验证
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `build fully_attached tier session_attached=true dispatch_eligible=false`() {
        val snapshot = minimalBuild(
            participationStateWire = "fully_attached",
            tierWire = "fully_attached"
        )
        assertTrue(snapshot.session_attached)
        assertFalse(snapshot.dispatch_eligible)
        assertFalse(snapshot.distributed_participant)
    }

    @Test
    fun `build pre_attach tier all boolean participation fields false`() {
        val snapshot = minimalBuild(
            participationStateWire = "pre_attach",
            tierWire = "pre_attach"
        )
        assertFalse(snapshot.session_attached)
        assertFalse(snapshot.dispatch_eligible)
        assertFalse(snapshot.distributed_participant)
    }

    @Test
    fun `build constraint_semantics none when no flags set`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "dispatch_eligible",
            participationTierWire = "dispatch_eligible",
            executionModeStateWire = "cross_device_active",
            crossDeviceEligibility = true
        )
        assertEquals("none", snapshot.constraint_semantics)
        assertFalse(snapshot.runtime_constrained)
        assertFalse(snapshot.runtime_deferred)
    }

    @Test
    fun `build local_mode_gate_deferred true when isHoldState without hold flag`() {
        val snapshot = AndroidUnifiedTruthUplinkContract.build(
            authoritativeParticipationStateWire = "fully_attached",
            participationTierWire = "fully_attached",
            executionModeStateWire = "transitioning",
            crossDeviceEligibility = false,
            isHoldState = true
        )
        assertTrue(snapshot.local_mode_gate_deferred)
        assertEquals("local_mode_gate_deferred", snapshot.constraint_semantics)
        assertTrue(snapshot.runtime_deferred)
    }
}
