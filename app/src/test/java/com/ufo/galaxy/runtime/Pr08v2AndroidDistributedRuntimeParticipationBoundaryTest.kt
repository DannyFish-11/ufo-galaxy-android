package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-08v2 (Android) — Android 分布式运行参与边界收束合约测试。
 *
 * 验证 [AndroidDistributedRuntimeParticipationBoundaryContract] 的核心枚举、推导规则、
 * wire map 格式、不变量声明以及 StabilizationBaseline 注册状态。
 *
 * ## 验收标准
 *
 * 1. 三个枚举（ParticipationBoundaryRole / OwnershipPostureClass / RemoteLocalModeClass）
 *    各自包含全部预期值，wireValue 与 V2 消费规范一致。
 * 2. derive() 在 5 个优先级路径下产生正确分类结果。
 * 3. toWireMap() 包含全部 4 个预期字段，且字段值与枚举 wireValue 一致。
 * 4. PARTICIPATION_BOUNDARY_INVARIANTS 声明了 7 条不变量，且内容覆盖核心语义保证。
 * 5. V2_OWNERSHIP_GOVERNANCE_PATH_MAP 为全部 5 个 ParticipationBoundaryRole 值提供了映射。
 * 6. StabilizationBaseline 中已注册 android-distributed-runtime-participation-boundary。
 */
class Pr08v2AndroidDistributedRuntimeParticipationBoundaryTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. ParticipationBoundaryRole enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ParticipationBoundaryRole has DISTRIBUTED_RUNTIME_PARTICIPANT value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values()
            .map { it.name }
        assertTrue(values.contains("DISTRIBUTED_RUNTIME_PARTICIPANT"))
    }

    @Test
    fun `ParticipationBoundaryRole has OWNERSHIP_POSTURE_SIGNAL value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values()
            .map { it.name }
        assertTrue(values.contains("OWNERSHIP_POSTURE_SIGNAL"))
    }

    @Test
    fun `ParticipationBoundaryRole has REMOTE_LOCAL_MODE_FALLBACK value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values()
            .map { it.name }
        assertTrue(values.contains("REMOTE_LOCAL_MODE_FALLBACK"))
    }

    @Test
    fun `ParticipationBoundaryRole has HANDOFF_PARTICIPANT value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values()
            .map { it.name }
        assertTrue(values.contains("HANDOFF_PARTICIPANT"))
    }

    @Test
    fun `ParticipationBoundaryRole has DIAGNOSTICS_SUMMARY_ONLY value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values()
            .map { it.name }
        assertTrue(values.contains("DIAGNOSTICS_SUMMARY_ONLY"))
    }

    @Test
    fun `ParticipationBoundaryRole has exactly 5 values`() {
        assertEquals(5, AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values().size)
    }

    @Test
    fun `ParticipationBoundaryRole DISTRIBUTED_RUNTIME_PARTICIPANT wireValue is correct`() {
        assertEquals(
            "distributed_runtime_participant",
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT.wireValue
        )
    }

    @Test
    fun `ParticipationBoundaryRole DIAGNOSTICS_SUMMARY_ONLY wireValue is correct`() {
        assertEquals(
            "diagnostics_summary_only",
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DIAGNOSTICS_SUMMARY_ONLY.wireValue
        )
    }

    @Test
    fun `ParticipationBoundaryRole REMOTE_LOCAL_MODE_FALLBACK wireValue is correct`() {
        assertEquals(
            "remote_local_mode_fallback",
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.REMOTE_LOCAL_MODE_FALLBACK.wireValue
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. OwnershipPostureClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `OwnershipPostureClass has RUNTIME_HOST_EXECUTOR value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.values()
            .map { it.name }
        assertTrue(values.contains("RUNTIME_HOST_EXECUTOR"))
    }

    @Test
    fun `OwnershipPostureClass has CONTROL_INITIATOR value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.values()
            .map { it.name }
        assertTrue(values.contains("CONTROL_INITIATOR"))
    }

    @Test
    fun `OwnershipPostureClass has HANDOFF_PARTICIPANT value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.values()
            .map { it.name }
        assertTrue(values.contains("HANDOFF_PARTICIPANT"))
    }

    @Test
    fun `OwnershipPostureClass has POSTURE_SIGNAL_ONLY value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.values()
            .map { it.name }
        assertTrue(values.contains("POSTURE_SIGNAL_ONLY"))
    }

    @Test
    fun `OwnershipPostureClass has exactly 4 values`() {
        assertEquals(4, AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.values().size)
    }

    @Test
    fun `OwnershipPostureClass CONTROL_INITIATOR wireValue is correct`() {
        assertEquals(
            "control_initiator",
            AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.CONTROL_INITIATOR.wireValue
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. RemoteLocalModeClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `RemoteLocalModeClass has DISTRIBUTED_EXECUTING value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.values()
            .map { it.name }
        assertTrue(values.contains("DISTRIBUTED_EXECUTING"))
    }

    @Test
    fun `RemoteLocalModeClass has LOCAL_ONLY_DECLARED value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.values()
            .map { it.name }
        assertTrue(values.contains("LOCAL_ONLY_DECLARED"))
    }

    @Test
    fun `RemoteLocalModeClass has FALLBACK_LOCAL value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.values()
            .map { it.name }
        assertTrue(values.contains("FALLBACK_LOCAL"))
    }

    @Test
    fun `RemoteLocalModeClass has DEGRADED_FALLBACK value`() {
        val values = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.values()
            .map { it.name }
        assertTrue(values.contains("DEGRADED_FALLBACK"))
    }

    @Test
    fun `RemoteLocalModeClass has exactly 4 values`() {
        assertEquals(4, AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.values().size)
    }

    @Test
    fun `RemoteLocalModeClass LOCAL_ONLY_DECLARED wireValue is correct`() {
        assertEquals(
            "local_only_declared",
            AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.LOCAL_ONLY_DECLARED.wireValue
        )
    }

    @Test
    fun `RemoteLocalModeClass FALLBACK_LOCAL wireValue differs from LOCAL_ONLY_DECLARED`() {
        val fallback = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.FALLBACK_LOCAL.wireValue
        val declared = AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.LOCAL_ONLY_DECLARED.wireValue
        assertTrue(
            "FALLBACK_LOCAL and LOCAL_ONLY_DECLARED must have distinct wireValues per INV-6",
            fallback != declared
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. derive() — priority-1: diagnostics signal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns DIAGNOSTICS_SUMMARY_ONLY when isDiagnosticsSignal is true`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = true
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DIAGNOSTICS_SUMMARY_ONLY,
            result.participationBoundaryRole
        )
    }

    @Test
    fun `derive returns POSTURE_SIGNAL_ONLY ownershipPostureClass for diagnostics signal`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = true
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.POSTURE_SIGNAL_ONLY,
            result.ownershipPostureClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. derive() — priority-2: local mode / fallback
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns REMOTE_LOCAL_MODE_FALLBACK when executionModeState is LOCAL_ONLY`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.REMOTE_LOCAL_MODE_FALLBACK,
            result.participationBoundaryRole
        )
    }

    @Test
    fun `derive returns LOCAL_ONLY_DECLARED modeClass when LOCAL_ONLY without fallback`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = false,
                executionModeStateWire = LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue,
                crossDeviceEnabled = false,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.LOCAL_ONLY_DECLARED,
            result.remoteLocalModeClass
        )
    }

    @Test
    fun `derive returns FALLBACK_LOCAL modeClass when fallback tier active`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = true,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.FALLBACK_LOCAL,
            result.remoteLocalModeClass
        )
    }

    @Test
    fun `derive returns DEGRADED_FALLBACK modeClass when fallback AND capability degraded`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = true,
                isCapabilityDegraded = true,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.DEGRADED_FALLBACK,
            result.remoteLocalModeClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. derive() — priority-3: handoff participant
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns HANDOFF_PARTICIPANT role when takeoverActive is true`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = true,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.HANDOFF_PARTICIPANT,
            result.participationBoundaryRole
        )
    }

    @Test
    fun `derive returns HANDOFF_PARTICIPANT ownershipPostureClass for join_runtime takeover`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = true,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.HANDOFF_PARTICIPANT,
            result.ownershipPostureClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. derive() — priority-4: true distributed execution
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns DISTRIBUTED_RUNTIME_PARTICIPANT when crossDevice+executionBusy+joinRuntime`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT,
            result.participationBoundaryRole
        )
    }

    @Test
    fun `derive returns RUNTIME_HOST_EXECUTOR ownershipPostureClass for distributed execution`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.RUNTIME_HOST_EXECUTOR,
            result.ownershipPostureClass
        )
    }

    @Test
    fun `derive returns DISTRIBUTED_EXECUTING modeClass for true distributed execution`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.RemoteLocalModeClass.DISTRIBUTED_EXECUTING,
            result.remoteLocalModeClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. derive() — priority-5: default/posture-signal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `derive returns OWNERSHIP_POSTURE_SIGNAL as default role when idle and no takeover`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.OWNERSHIP_POSTURE_SIGNAL,
            result.participationBoundaryRole
        )
    }

    @Test
    fun `derive returns CONTROL_INITIATOR ownershipPostureClass for control_only posture`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = false,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.CONTROL_INITIATOR,
            result.ownershipPostureClass
        )
    }

    @Test
    fun `derive does NOT return DISTRIBUTED_RUNTIME_PARTICIPANT when executionBusy is false`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertTrue(
            "DISTRIBUTED_RUNTIME_PARTICIPANT requires executionBusy=true",
            result.participationBoundaryRole !=
                AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT
        )
    }

    @Test
    fun `derive does NOT return DISTRIBUTED_RUNTIME_PARTICIPANT when sourceRuntimePosture is control_only`() {
        val result = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertTrue(
            "DISTRIBUTED_RUNTIME_PARTICIPANT requires join_runtime posture",
            result.participationBoundaryRole !=
                AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. toWireMap() field coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap contains participation_boundary_role key`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertTrue(snapshot.toWireMap().containsKey("participation_boundary_role"))
    }

    @Test
    fun `toWireMap contains ownership_posture_class key`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = false,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertTrue(snapshot.toWireMap().containsKey("ownership_posture_class"))
    }

    @Test
    fun `toWireMap contains remote_local_mode_class key`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = false,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertTrue(snapshot.toWireMap().containsKey("remote_local_mode_class"))
    }

    @Test
    fun `toWireMap contains participation_boundary_schema_version key`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = false,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = true
            )
        )
        assertTrue(snapshot.toWireMap().containsKey("participation_boundary_schema_version"))
    }

    @Test
    fun `toWireMap has exactly 4 keys`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(4, snapshot.toWireMap().size)
    }

    @Test
    fun `toWireMap participation_boundary_role value matches enum wireValue`() {
        val snapshot = AndroidDistributedRuntimeParticipationBoundaryContract.derive(
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryDerivationInput(
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                executionBusy = true,
                executionModeStateWire = null,
                crossDeviceEnabled = true,
                isFallbackTierActive = false,
                isCapabilityDegraded = false,
                takeoverActive = false,
                isDiagnosticsSignal = false
            )
        )
        assertEquals(
            snapshot.participationBoundaryRole.wireValue,
            snapshot.toWireMap()["participation_boundary_role"]
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. PARTICIPATION_BOUNDARY_INVARIANTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS has 7 entries`() {
        assertEquals(
            7,
            AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS.size
        )
    }

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS all entries are non-blank`() {
        for (invariant in AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS) {
            assertTrue("Invariant must be non-blank", invariant.isNotBlank())
        }
    }

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS INV-2 references ownership posture as signal`() {
        val inv2 = AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS
            .first { it.startsWith("INV-2") }
        assertTrue(
            "INV-2 must declare ownership posture as signal, not authority truth",
            inv2.contains("SIGNAL") && inv2.contains("NOT runtime authority truth")
        )
    }

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS INV-5 references HANDOFF_PARTICIPANT not complete takeover`() {
        val inv5 = AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS
            .first { it.startsWith("INV-5") }
        assertTrue(
            "INV-5 must warn that handoff_participant does not mean complete takeover",
            inv5.contains("handoff_participant") && inv5.contains("MUST NOT")
        )
    }

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS INV-6 distinguishes local_only_declared from fallback_local`() {
        val inv6 = AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS
            .first { it.startsWith("INV-6") }
        assertTrue(
            "INV-6 must distinguish local_only_declared from fallback_local",
            inv6.contains("local_only_declared") && inv6.contains("fallback_local")
        )
    }

    @Test
    fun `PARTICIPATION_BOUNDARY_INVARIANTS INV-7 marks operator-visible surface as projection-only`() {
        val inv7 = AndroidDistributedRuntimeParticipationBoundaryContract.PARTICIPATION_BOUNDARY_INVARIANTS
            .first { it.startsWith("INV-7") }
        assertTrue(
            "INV-7 must declare operator-visible surface as projection-only",
            inv7.contains("projection-only") || inv7.contains("MUST NOT")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. V2_OWNERSHIP_GOVERNANCE_PATH_MAP
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `V2_OWNERSHIP_GOVERNANCE_PATH_MAP covers all 5 ParticipationBoundaryRole values`() {
        val allRoles = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.values().toSet()
        val mappedRoles = AndroidDistributedRuntimeParticipationBoundaryContract.V2_OWNERSHIP_GOVERNANCE_PATH_MAP.keys
        assertEquals(
            "V2_OWNERSHIP_GOVERNANCE_PATH_MAP must cover all ParticipationBoundaryRole values",
            allRoles, mappedRoles
        )
    }

    @Test
    fun `V2_OWNERSHIP_GOVERNANCE_PATH_MAP DISTRIBUTED_RUNTIME_PARTICIPANT path is non-blank`() {
        val path = AndroidDistributedRuntimeParticipationBoundaryContract.V2_OWNERSHIP_GOVERNANCE_PATH_MAP[
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT
        ]
        assertNotNull(path)
        assertTrue(path!!.isNotBlank())
    }

    @Test
    fun `V2_OWNERSHIP_GOVERNANCE_PATH_MAP DIAGNOSTICS_SUMMARY_ONLY path mentions MUST NOT`() {
        val path = AndroidDistributedRuntimeParticipationBoundaryContract.V2_OWNERSHIP_GOVERNANCE_PATH_MAP[
            AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DIAGNOSTICS_SUMMARY_ONLY
        ]
        assertNotNull(path)
        assertTrue(
            "DIAGNOSTICS_SUMMARY_ONLY V2 path must include MUST NOT constraint",
            path!!.contains("MUST NOT")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-distributed-runtime-participation-boundary is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("android-distributed-runtime-participation-boundary"))
    }

    @Test
    fun `android-distributed-runtime-participation-boundary is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-distributed-runtime-participation-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `android-distributed-runtime-participation-boundary has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-distributed-runtime-participation-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry!!.extensionGuidance)
    }

    @Test
    fun `android-distributed-runtime-participation-boundary introducedPr is 98`() {
        val entry = StabilizationBaseline.forId("android-distributed-runtime-participation-boundary")
        assertNotNull(entry)
        assertEquals(98, entry!!.introducedPr)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. Schema version constant
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SCHEMA_VERSION is non-blank`() {
        assertTrue(
            AndroidDistributedRuntimeParticipationBoundaryContract.SCHEMA_VERSION.isNotBlank()
        )
    }

    @Test
    fun `wire key constants are non-blank and distinct`() {
        val keys = listOf(
            AndroidDistributedRuntimeParticipationBoundaryContract.KEY_PARTICIPATION_BOUNDARY_ROLE,
            AndroidDistributedRuntimeParticipationBoundaryContract.KEY_OWNERSHIP_POSTURE_CLASS,
            AndroidDistributedRuntimeParticipationBoundaryContract.KEY_REMOTE_LOCAL_MODE_CLASS,
            AndroidDistributedRuntimeParticipationBoundaryContract.KEY_SCHEMA_VERSION
        )
        for (key in keys) {
            assertTrue("Wire key must be non-blank: $key", key.isNotBlank())
        }
        assertEquals("All wire key constants must be distinct", keys.size, keys.toSet().size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. Boundary semantics: HANDOFF_PARTICIPANT != complete takeover (INV-5)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `HANDOFF_PARTICIPANT role does not equal DISTRIBUTED_RUNTIME_PARTICIPANT`() {
        val handoff = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.HANDOFF_PARTICIPANT
        val participant = AndroidDistributedRuntimeParticipationBoundaryContract.ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT
        assertTrue(
            "HANDOFF_PARTICIPANT must be distinct from DISTRIBUTED_RUNTIME_PARTICIPANT per INV-5",
            handoff != participant
        )
    }

    @Test
    fun `ownership posture HANDOFF_PARTICIPANT does not equal RUNTIME_HOST_EXECUTOR (INV-2 and INV-5)`() {
        val handoffPosture = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.HANDOFF_PARTICIPANT
        val executorPosture = AndroidDistributedRuntimeParticipationBoundaryContract.OwnershipPostureClass.RUNTIME_HOST_EXECUTOR
        assertTrue(
            "HANDOFF_PARTICIPANT ownershipPostureClass must differ from RUNTIME_HOST_EXECUTOR",
            handoffPosture != executorPosture
        )
    }
}
