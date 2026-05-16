package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-B2 (Android) — 注册体位握手合约测试。
 *
 * 验证 [SourceRuntimePosture] 的注册体位推导逻辑，确保：
 *  1. `crossDeviceEnabled=true && goal_execution_enabled=true` → `join_runtime`
 *  2. `crossDeviceEnabled=false` → `control_only`（无论 goal_execution_enabled 值）
 *  3. `goal_execution_enabled=false` → `control_only`（无论 crossDeviceEnabled 值）
 *  4. 推导出的体位值符合 [SourceRuntimePosture.ALL_VALUES] 约束
 *  5. 推导结果与 [SourceRuntimePosture.isJoinRuntime] / [SourceRuntimePosture.isControlOnly] 谓词一致
 *
 * 这些测试直接对应 PR-B2 补充的握手体位推导规则：
 *  ```
 *  val registrationPosture = run {
 *      val goalExecutionEnabled = deviceMetadata["goal_execution_enabled"] as? Boolean ?: false
 *      if (crossDeviceEnabled && goalExecutionEnabled) SourceRuntimePosture.JOIN_RUNTIME
 *      else SourceRuntimePosture.CONTROL_ONLY
 *  }
 *  ```
 */
class HandshakeRegistrationPostureTest {

    // ── 工具函数：模拟握手层 registrationPosture 推导 ────────────────────────────

    /**
     * 模拟 GalaxyWebSocketClient.sendHandshake() 中的 registrationPosture 推导逻辑。
     *
     * 将实际推导规则提取为纯函数，使测试无需构造真实 WebSocket 客户端即可验证推导行为。
     */
    private fun deriveRegistrationPosture(
        crossDeviceEnabled: Boolean,
        goalExecutionEnabled: Boolean
    ): String = if (crossDeviceEnabled && goalExecutionEnabled) {
        SourceRuntimePosture.JOIN_RUNTIME
    } else {
        SourceRuntimePosture.CONTROL_ONLY
    }

    // ── join_runtime 推导路径 ─────────────────────────────────────────────────

    @Test
    fun `join_runtime when crossDevice enabled and goal execution enabled`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true
        )
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, posture)
    }

    @Test
    fun `join_runtime satisfies isJoinRuntime predicate`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true
        )
        assertTrue(SourceRuntimePosture.isJoinRuntime(posture))
    }

    @Test
    fun `join_runtime does not satisfy isControlOnly predicate`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = true,
            goalExecutionEnabled = true
        )
        assertFalse(SourceRuntimePosture.isControlOnly(posture))
    }

    // ── control_only：crossDeviceEnabled = false ──────────────────────────────

    @Test
    fun `control_only when crossDevice disabled regardless of goal execution`() {
        val postureWithGoal = deriveRegistrationPosture(
            crossDeviceEnabled = false,
            goalExecutionEnabled = true
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, postureWithGoal)
    }

    @Test
    fun `control_only when both crossDevice and goal execution disabled`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = false,
            goalExecutionEnabled = false
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, posture)
    }

    // ── control_only：goal_execution_enabled = false ──────────────────────────

    @Test
    fun `control_only when crossDevice enabled but goal execution disabled`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = true,
            goalExecutionEnabled = false
        )
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, posture)
    }

    @Test
    fun `control_only satisfies isControlOnly predicate`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = false,
            goalExecutionEnabled = false
        )
        assertTrue(SourceRuntimePosture.isControlOnly(posture))
    }

    @Test
    fun `control_only does not satisfy isJoinRuntime predicate`() {
        val posture = deriveRegistrationPosture(
            crossDeviceEnabled = false,
            goalExecutionEnabled = false
        )
        assertFalse(SourceRuntimePosture.isJoinRuntime(posture))
    }

    // ── ALL_VALUES 约束 ───────────────────────────────────────────────────────

    @Test
    fun `all derived postures are in SourceRuntimePosture ALL_VALUES`() {
        val combinations = listOf(
            true to true,
            true to false,
            false to true,
            false to false
        )
        for ((crossDevice, goalExecution) in combinations) {
            val posture = deriveRegistrationPosture(crossDevice, goalExecution)
            assertTrue(
                "Derived posture '$posture' for crossDevice=$crossDevice goalExecution=$goalExecution " +
                    "must be in SourceRuntimePosture.ALL_VALUES",
                posture in SourceRuntimePosture.ALL_VALUES
            )
        }
    }

    // ── 一致性：两条握手消息携带相同体位值 ────────────────────────────────────────

    @Test
    fun `device_register and capability_report derive the same posture for same inputs`() {
        // 模拟 sendHandshake() 中两条消息使用同一 registrationPosture 变量
        val crossDeviceEnabled = true
        val goalExecutionEnabled = true

        // 两条消息都使用 registrationPosture 变量，此处验证推导函数的幂等性
        val postureForRegister = deriveRegistrationPosture(crossDeviceEnabled, goalExecutionEnabled)
        val postureForCapabilityReport = deriveRegistrationPosture(crossDeviceEnabled, goalExecutionEnabled)

        assertEquals(
            "device_register 和 capability_report 的 source_runtime_posture 必须一致",
            postureForRegister,
            postureForCapabilityReport
        )
    }

    // ── StabilizationBaseline 注册验证 ────────────────────────────────────────

    @Test
    fun `StabilizationBaseline registers android-registration-posture-handshake`() {
        assertTrue(
            "StabilizationBaseline 应注册 android-registration-posture-handshake",
            StabilizationBaseline.isRegistered("android-registration-posture-handshake")
        )
    }

    @Test
    fun `android-registration-posture-handshake baseline entry has correct introduced pr`() {
        val entry = StabilizationBaseline.forId("android-registration-posture-handshake")
        assertNotNull(entry)
        assertEquals(92, entry!!.introducedPr)
    }

    @Test
    fun `android-registration-posture-handshake baseline entry is canonical stable`() {
        val entry = StabilizationBaseline.forId("android-registration-posture-handshake")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }
}
