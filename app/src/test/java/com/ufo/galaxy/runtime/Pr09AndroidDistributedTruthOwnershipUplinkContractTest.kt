package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-09Android — Android 分布式真值与 ownership 上行语义收束合约测试。
 *
 * 验证 [AndroidDistributedTruthOwnershipUplinkContract] 的核心枚举、推导规则、
 * wire map 格式、不变量声明以及 StabilizationBaseline 注册状态。
 *
 * ## 验收标准
 *
 * 1. 四个枚举（AuthoritySignalClass / OwnershipUplinkClass / SessionContinuityClass /
 *    DevicePostureSignalClass）各自包含全部预期值，wireValue 与 V2 消费规范一致。
 * 2. derive() 在 5 个优先级路径下产生正确分类结果。
 * 3. toWireMap() 包含全部 5 个预期字段，且字段值与枚举 wireValue 一致。
 * 4. DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS 声明了 8 条不变量，且内容覆盖核心语义保证。
 * 5. V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP 为全部 4 个 AuthoritySignalClass 值提供了映射。
 * 6. StabilizationBaseline 中已注册 android-distributed-truth-ownership-uplink。
 */
class Pr09AndroidDistributedTruthOwnershipUplinkContractTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. AuthoritySignalClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AuthoritySignalClass has AUTHORITY_RUNTIME value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values()
            .map { it.name }
        assertTrue(values.contains("AUTHORITY_RUNTIME"))
    }

    @Test
    fun `AuthoritySignalClass has OWNERSHIP_HANDOFF value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values()
            .map { it.name }
        assertTrue(values.contains("OWNERSHIP_HANDOFF"))
    }

    @Test
    fun `AuthoritySignalClass has SUMMARY_PROJECTION value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values()
            .map { it.name }
        assertTrue(values.contains("SUMMARY_PROJECTION"))
    }

    @Test
    fun `AuthoritySignalClass has DIAGNOSTICS_AUDIT value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values()
            .map { it.name }
        assertTrue(values.contains("DIAGNOSTICS_AUDIT"))
    }

    @Test
    fun `AuthoritySignalClass has exactly 4 values`() {
        assertEquals(4, AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values().size)
    }

    @Test
    fun `AuthoritySignalClass AUTHORITY_RUNTIME wireValue is correct`() {
        assertEquals(
            "authority_runtime",
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.AUTHORITY_RUNTIME.wireValue
        )
    }

    @Test
    fun `AuthoritySignalClass OWNERSHIP_HANDOFF wireValue is correct`() {
        assertEquals(
            "ownership_handoff",
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.OWNERSHIP_HANDOFF.wireValue
        )
    }

    @Test
    fun `AuthoritySignalClass SUMMARY_PROJECTION wireValue is correct`() {
        assertEquals(
            "summary_projection",
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.SUMMARY_PROJECTION.wireValue
        )
    }

    @Test
    fun `AuthoritySignalClass DIAGNOSTICS_AUDIT wireValue is correct`() {
        assertEquals(
            "diagnostics_audit",
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.DIAGNOSTICS_AUDIT.wireValue
        )
    }

    @Test
    fun `AuthoritySignalClass fromWireValue returns correct enum for authority_runtime`() {
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.AUTHORITY_RUNTIME,
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.fromWireValue("authority_runtime")
        )
    }

    @Test
    fun `AuthoritySignalClass fromWireValue returns DIAGNOSTICS_AUDIT for unknown value`() {
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.DIAGNOSTICS_AUDIT,
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.fromWireValue("unknown_value")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. OwnershipUplinkClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `OwnershipUplinkClass has AUTHORITY_HELD value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values()
            .map { it.name }
        assertTrue(values.contains("AUTHORITY_HELD"))
    }

    @Test
    fun `OwnershipUplinkClass has HANDOFF_INITIATOR value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values()
            .map { it.name }
        assertTrue(values.contains("HANDOFF_INITIATOR"))
    }

    @Test
    fun `OwnershipUplinkClass has HANDOFF_PARTICIPANT value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values()
            .map { it.name }
        assertTrue(values.contains("HANDOFF_PARTICIPANT"))
    }

    @Test
    fun `OwnershipUplinkClass has OWNERSHIP_RETURN value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values()
            .map { it.name }
        assertTrue(values.contains("OWNERSHIP_RETURN"))
    }

    @Test
    fun `OwnershipUplinkClass has NO_TRANSFER value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values()
            .map { it.name }
        assertTrue(values.contains("NO_TRANSFER"))
    }

    @Test
    fun `OwnershipUplinkClass has exactly 5 values`() {
        assertEquals(5, AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.values().size)
    }

    @Test
    fun `OwnershipUplinkClass AUTHORITY_HELD wireValue is correct`() {
        assertEquals(
            "authority_held",
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.AUTHORITY_HELD.wireValue
        )
    }

    @Test
    fun `OwnershipUplinkClass HANDOFF_INITIATOR wireValue is correct`() {
        assertEquals(
            "handoff_initiator",
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.HANDOFF_INITIATOR.wireValue
        )
    }

    @Test
    fun `OwnershipUplinkClass fromWireValue returns NO_TRANSFER for unknown value`() {
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.NO_TRANSFER,
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.fromWireValue("unknown_value")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. SessionContinuityClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SessionContinuityClass has SESSION_LIVE_AUTHORITATIVE value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.values()
            .map { it.name }
        assertTrue(values.contains("SESSION_LIVE_AUTHORITATIVE"))
    }

    @Test
    fun `SessionContinuityClass has SESSION_RECOVERY_PENDING value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.values()
            .map { it.name }
        assertTrue(values.contains("SESSION_RECOVERY_PENDING"))
    }

    @Test
    fun `SessionContinuityClass has SESSION_CONTINUATION value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.values()
            .map { it.name }
        assertTrue(values.contains("SESSION_CONTINUATION"))
    }

    @Test
    fun `SessionContinuityClass has NO_ACTIVE_SESSION value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.values()
            .map { it.name }
        assertTrue(values.contains("NO_ACTIVE_SESSION"))
    }

    @Test
    fun `SessionContinuityClass has exactly 4 values`() {
        assertEquals(4, AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.values().size)
    }

    @Test
    fun `SessionContinuityClass SESSION_LIVE_AUTHORITATIVE wireValue is correct`() {
        assertEquals(
            "session_live_authoritative",
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.SESSION_LIVE_AUTHORITATIVE.wireValue
        )
    }

    @Test
    fun `SessionContinuityClass SESSION_RECOVERY_PENDING wireValue is correct`() {
        assertEquals(
            "session_recovery_pending",
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.SESSION_RECOVERY_PENDING.wireValue
        )
    }

    @Test
    fun `SessionContinuityClass fromWireValue returns NO_ACTIVE_SESSION for null`() {
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.NO_ACTIVE_SESSION,
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.fromWireValue(null)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. DevicePostureSignalClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DevicePostureSignalClass has RUNTIME_NODE_ACTIVE value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values()
            .map { it.name }
        assertTrue(values.contains("RUNTIME_NODE_ACTIVE"))
    }

    @Test
    fun `DevicePostureSignalClass has RUNTIME_NODE_DEGRADED value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values()
            .map { it.name }
        assertTrue(values.contains("RUNTIME_NODE_DEGRADED"))
    }

    @Test
    fun `DevicePostureSignalClass has RUNTIME_NODE_RECOVERING value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values()
            .map { it.name }
        assertTrue(values.contains("RUNTIME_NODE_RECOVERING"))
    }

    @Test
    fun `DevicePostureSignalClass has POSTURE_SIGNAL_ONLY value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values()
            .map { it.name }
        assertTrue(values.contains("POSTURE_SIGNAL_ONLY"))
    }

    @Test
    fun `DevicePostureSignalClass has CONTROL_PLANE_ONLY value`() {
        val values = AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values()
            .map { it.name }
        assertTrue(values.contains("CONTROL_PLANE_ONLY"))
    }

    @Test
    fun `DevicePostureSignalClass has exactly 5 values`() {
        assertEquals(5, AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.values().size)
    }

    @Test
    fun `DevicePostureSignalClass RUNTIME_NODE_ACTIVE wireValue is correct`() {
        assertEquals(
            "runtime_node_active",
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.RUNTIME_NODE_ACTIVE.wireValue
        )
    }

    @Test
    fun `DevicePostureSignalClass CONTROL_PLANE_ONLY wireValue is correct`() {
        assertEquals(
            "control_plane_only",
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.CONTROL_PLANE_ONLY.wireValue
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. derive() — priority-1a: diagnostics signal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns DIAGNOSTICS_AUDIT when isDiagnosticsSignal is true`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isDiagnosticsSignal = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.DIAGNOSTICS_AUDIT,
            result.authoritySignalClass
        )
    }

    @Test
    fun `derive returns NO_TRANSFER ownership when isDiagnosticsSignal is true`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isDiagnosticsSignal = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.NO_TRANSFER,
            result.ownershipUplinkClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. derive() — priority-1b: operator-visible summary
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns SUMMARY_PROJECTION when isOperatorVisibleSummary is true`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isOperatorVisibleSummary = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.SUMMARY_PROJECTION,
            result.authoritySignalClass
        )
    }

    @Test
    fun `derive returns NO_TRANSFER ownership when isOperatorVisibleSummary is true`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isOperatorVisibleSummary = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.NO_TRANSFER,
            result.ownershipUplinkClass
        )
    }

    @Test
    fun `diagnostics signal takes priority over operator visible summary`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isDiagnosticsSignal = true, isOperatorVisibleSummary = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.DIAGNOSTICS_AUDIT,
            result.authoritySignalClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. derive() — priority-2a: ownership return
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns OWNERSHIP_HANDOFF with OWNERSHIP_RETURN when isOwnershipReturnPending`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isOwnershipReturnPending = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.OWNERSHIP_HANDOFF,
            result.authoritySignalClass
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.OWNERSHIP_RETURN,
            result.ownershipUplinkClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. derive() — priority-2b: takeover / handoff
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns OWNERSHIP_HANDOFF with HANDOFF_INITIATOR when isHandoffInitiator and takeoverActive`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(takeoverActive = true, isHandoffInitiator = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.OWNERSHIP_HANDOFF,
            result.authoritySignalClass
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.HANDOFF_INITIATOR,
            result.ownershipUplinkClass
        )
    }

    @Test
    fun `derive returns OWNERSHIP_HANDOFF with HANDOFF_PARTICIPANT when takeoverActive but not initiator`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(takeoverActive = true, isHandoffInitiator = false)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.OWNERSHIP_HANDOFF,
            result.authoritySignalClass
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.HANDOFF_PARTICIPANT,
            result.ownershipUplinkClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. derive() — priority-3: authority runtime signal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns AUTHORITY_RUNTIME with AUTHORITY_HELD when executionBusy and join_runtime and crossDeviceEnabled`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                executionBusy = true,
                crossDeviceEnabled = true,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.AUTHORITY_RUNTIME,
            result.authoritySignalClass
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.OwnershipUplinkClass.AUTHORITY_HELD,
            result.ownershipUplinkClass
        )
    }

    @Test
    fun `derive does not return AUTHORITY_RUNTIME when control_only posture`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                executionBusy = true,
                crossDeviceEnabled = true,
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertTrue(
            "control_only posture should not yield AUTHORITY_RUNTIME",
            result.authoritySignalClass != AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.AUTHORITY_RUNTIME
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. derive() — session continuity derivation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns NO_ACTIVE_SESSION when sessionId is null`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(sessionId = null)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.NO_ACTIVE_SESSION,
            result.sessionContinuityClass
        )
    }

    @Test
    fun `derive returns SESSION_RECOVERY_PENDING when isSessionRecoveryActive`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(sessionId = "sess-001", isSessionRecoveryActive = true)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.SESSION_RECOVERY_PENDING,
            result.sessionContinuityClass
        )
    }

    @Test
    fun `derive returns SESSION_LIVE_AUTHORITATIVE when sessionId present and executionBusy and not recovery`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                sessionId = "sess-001",
                executionBusy = true,
                isSessionRecoveryActive = false,
                crossDeviceEnabled = true,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.SESSION_LIVE_AUTHORITATIVE,
            result.sessionContinuityClass
        )
    }

    @Test
    fun `derive returns SESSION_CONTINUATION when sessionId present but not executionBusy`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                sessionId = "sess-001",
                executionBusy = false,
                isSessionRecoveryActive = false
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SessionContinuityClass.SESSION_CONTINUATION,
            result.sessionContinuityClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. derive() — device posture derivation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns CONTROL_PLANE_ONLY posture when control_only posture`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.CONTROL_PLANE_ONLY,
            result.devicePostureSignalClass
        )
    }

    @Test
    fun `derive returns RUNTIME_NODE_DEGRADED when isCapabilityDegraded`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                isCapabilityDegraded = true,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.RUNTIME_NODE_DEGRADED,
            result.devicePostureSignalClass
        )
    }

    @Test
    fun `derive returns RUNTIME_NODE_RECOVERING when isRecoveryActive`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                isRecoveryActive = true,
                isCapabilityDegraded = false,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.RUNTIME_NODE_RECOVERING,
            result.devicePostureSignalClass
        )
    }

    @Test
    fun `derive returns RUNTIME_NODE_ACTIVE when executionBusy and crossDeviceEnabled and join_runtime`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                executionBusy = true,
                crossDeviceEnabled = true,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                isCapabilityDegraded = false,
                isRecoveryActive = false
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.RUNTIME_NODE_ACTIVE,
            result.devicePostureSignalClass
        )
    }

    @Test
    fun `derive returns POSTURE_SIGNAL_ONLY when join_runtime but not executionBusy`() {
        val result = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(
                executionBusy = false,
                crossDeviceEnabled = true,
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                isCapabilityDegraded = false,
                isRecoveryActive = false
            )
        )
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.DevicePostureSignalClass.POSTURE_SIGNAL_ONLY,
            result.devicePostureSignalClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. toWireMap() — field count and key names
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap contains exactly 5 fields`() {
        val snapshot = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput())
        assertEquals(5, snapshot.toWireMap().size)
    }

    @Test
    fun `toWireMap contains authority_signal_class key`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertTrue(wireMap.containsKey(AndroidDistributedTruthOwnershipUplinkContract.KEY_AUTHORITY_SIGNAL_CLASS))
    }

    @Test
    fun `toWireMap contains ownership_uplink_class key`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertTrue(wireMap.containsKey(AndroidDistributedTruthOwnershipUplinkContract.KEY_OWNERSHIP_UPLINK_CLASS))
    }

    @Test
    fun `toWireMap contains session_continuity_class key`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertTrue(wireMap.containsKey(AndroidDistributedTruthOwnershipUplinkContract.KEY_SESSION_CONTINUITY_CLASS))
    }

    @Test
    fun `toWireMap contains device_posture_signal_class key`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertTrue(wireMap.containsKey(AndroidDistributedTruthOwnershipUplinkContract.KEY_DEVICE_POSTURE_SIGNAL_CLASS))
    }

    @Test
    fun `toWireMap contains schema version key`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertTrue(wireMap.containsKey(AndroidDistributedTruthOwnershipUplinkContract.KEY_SCHEMA_VERSION))
    }

    @Test
    fun `toWireMap authority_signal_class value matches enum wireValue`() {
        val snapshot = AndroidDistributedTruthOwnershipUplinkContract.derive(
            buildInput(isDiagnosticsSignal = true)
        )
        val wireMap = snapshot.toWireMap()
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.DIAGNOSTICS_AUDIT.wireValue,
            wireMap[AndroidDistributedTruthOwnershipUplinkContract.KEY_AUTHORITY_SIGNAL_CLASS]
        )
    }

    @Test
    fun `toWireMap schema version is SCHEMA_VERSION constant`() {
        val wireMap = AndroidDistributedTruthOwnershipUplinkContract.derive(buildInput()).toWireMap()
        assertEquals(
            AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION,
            wireMap[AndroidDistributedTruthOwnershipUplinkContract.KEY_SCHEMA_VERSION]
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS has exactly 8 items`() {
        assertEquals(8, AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS.size)
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS all items are non-blank`() {
        AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
            .forEachIndexed { i, inv ->
                assertTrue("Invariant $i is blank", inv.isNotBlank())
            }
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS first item mentions authority_signal_class`() {
        val first = AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS[0]
        assertTrue(
            "INV-1 should mention authority_signal_class",
            first.contains("authority_signal_class")
        )
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS contains invariant covering SUMMARY_PROJECTION`() {
        val invariants = AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
        assertTrue(
            "Invariants should cover summary_projection",
            invariants.any { it.contains("summary_projection") }
        )
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS contains invariant covering session recovery`() {
        val invariants = AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
        assertTrue(
            "Invariants should cover session_recovery_pending",
            invariants.any { it.contains("session_recovery_pending") }
        )
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS contains invariant covering control_plane_only`() {
        val invariants = AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
        assertTrue(
            "Invariants should cover control_plane_only",
            invariants.any { it.contains("control_plane_only") }
        )
    }

    @Test
    fun `DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS contains invariant asserting Android is runtime node`() {
        val invariants = AndroidDistributedTruthOwnershipUplinkContract.DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS
        assertTrue(
            "Invariants should assert Android is a runtime node",
            invariants.any { it.contains("runtime node") || it.contains("AUTHORITY_RUNTIME") }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP has exactly 4 entries`() {
        assertEquals(4, AndroidDistributedTruthOwnershipUplinkContract.V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP.size)
    }

    @Test
    fun `V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP covers all AuthoritySignalClass values`() {
        val mapKeys = AndroidDistributedTruthOwnershipUplinkContract.V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP.keys
        AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.values().forEach { cls ->
            assertTrue(
                "V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP must cover $cls",
                mapKeys.contains(cls)
            )
        }
    }

    @Test
    fun `V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP AUTHORITY_RUNTIME path mentions canonical_truth_chain`() {
        val path = AndroidDistributedTruthOwnershipUplinkContract.V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP[
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.AUTHORITY_RUNTIME
        ]
        assertNotNull(path)
        assertTrue(
            "AUTHORITY_RUNTIME path should reference canonical truth chain",
            path!!.contains("canonical_truth_chain") || path.contains("canonical")
        )
    }

    @Test
    fun `V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP SUMMARY_PROJECTION path is non-blank`() {
        val path = AndroidDistributedTruthOwnershipUplinkContract.V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP[
            AndroidDistributedTruthOwnershipUplinkContract.AuthoritySignalClass.SUMMARY_PROJECTION
        ]
        assertNotNull(path)
        assertTrue(path!!.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 15. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-distributed-truth-ownership-uplink is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("android-distributed-truth-ownership-uplink"))
    }

    @Test
    fun `android-distributed-truth-ownership-uplink is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-distributed-truth-ownership-uplink")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `android-distributed-truth-ownership-uplink has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-distributed-truth-ownership-uplink")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry!!.extensionGuidance)
    }

    @Test
    fun `android-distributed-truth-ownership-uplink introducedPr is 99`() {
        val entry = StabilizationBaseline.forId("android-distributed-truth-ownership-uplink")
        assertNotNull(entry)
        assertEquals(99, entry!!.introducedPr)
    }

    @Test
    fun `AndroidDistributedTruthOwnershipUplinkContract INTRODUCED_PR is 99`() {
        assertEquals(99, AndroidDistributedTruthOwnershipUplinkContract.INTRODUCED_PR)
    }

    @Test
    fun `AndroidDistributedTruthOwnershipUplinkContract SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildInput(
        executionBusy: Boolean = false,
        crossDeviceEnabled: Boolean = false,
        sourceRuntimePosture: String = SourceRuntimePosture.JOIN_RUNTIME,
        takeoverActive: Boolean = false,
        isHandoffInitiator: Boolean = false,
        isOwnershipReturnPending: Boolean = false,
        sessionId: String? = null,
        isSessionRecoveryActive: Boolean = false,
        isCapabilityDegraded: Boolean = false,
        isRecoveryActive: Boolean = false,
        isDiagnosticsSignal: Boolean = false,
        isOperatorVisibleSummary: Boolean = false
    ) = AndroidDistributedTruthOwnershipUplinkContract.TruthOwnershipUplinkDerivationInput(
        executionBusy = executionBusy,
        crossDeviceEnabled = crossDeviceEnabled,
        sourceRuntimePosture = sourceRuntimePosture,
        takeoverActive = takeoverActive,
        isHandoffInitiator = isHandoffInitiator,
        isOwnershipReturnPending = isOwnershipReturnPending,
        sessionId = sessionId,
        isSessionRecoveryActive = isSessionRecoveryActive,
        isCapabilityDegraded = isCapabilityDegraded,
        isRecoveryActive = isRecoveryActive,
        isDiagnosticsSignal = isDiagnosticsSignal,
        isOperatorVisibleSummary = isOperatorVisibleSummary
    )
}
