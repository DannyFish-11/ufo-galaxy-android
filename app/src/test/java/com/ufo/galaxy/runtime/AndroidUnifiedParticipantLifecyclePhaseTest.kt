package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-91 (Android) — AndroidUnifiedParticipantLifecyclePhase 合约验证测试。
 *
 * 覆盖：
 *  - 所有 10 个阶段的推导正确性
 *  - 优先级规则（高优先级阶段不被低优先级阶段覆盖）
 *  - 降级事件检测（正值域 → 负值域）
 *  - TAKEOVER_ELIGIBLE 精确语义验证
 *  - PARTICIPATING 与 TAKEOVER_ELIGIBLE 的边界（executionBusy 的影响）
 *  - 不变量集合完整性
 *  - wire 值稳定性（Phase.ALL_WIRE_VALUES 涵盖全部 10 个阶段）
 *  - 语义查询方法正确性（isDispatchAllowed / isTakeoverAcceptable / isActivelyParticipating）
 */
class AndroidUnifiedParticipantLifecyclePhaseTest {

    // ── 辅助：构造完整"就绪健康"输入 ─────────────────────────────────────────

    private fun healthyReadyInput(
        crossDeviceEnabled: Boolean = true,
        wsConnected: Boolean = true,
        hasDurableParticipantId: Boolean = true,
        capabilityVisible: Boolean = true,
        sessionAttached: Boolean = true,
        readinessSatisfied: Boolean = true,
        executionBusy: Boolean = false,
        takeoverActive: Boolean = false,
        interactionSurfaceReady: Boolean = true,
        governanceBlocked: Boolean = false,
        formalLifecycleState: FormalParticipantLifecycleState = FormalParticipantLifecycleState.READY,
        reconnectRecoveryStateWire: String = ReconnectRecoveryState.IDLE.wireValue
    ) = AndroidUnifiedParticipantLifecyclePhase.DerivationInput(
        formalLifecycleState = formalLifecycleState,
        reconnectRecoveryStateWire = reconnectRecoveryStateWire,
        crossDeviceEnabled = crossDeviceEnabled,
        wsConnected = wsConnected,
        hasDurableParticipantId = hasDurableParticipantId,
        capabilityVisible = capabilityVisible,
        sessionAttached = sessionAttached,
        readinessSatisfied = readinessSatisfied,
        executionBusy = executionBusy,
        takeoverActive = takeoverActive,
        interactionSurfaceReady = interactionSurfaceReady,
        governanceBlocked = governanceBlocked
    )

    // ── 推导正确性：不可用（优先级 1）────────────────────────────────────────

    @Test
    fun `derive returns UNAVAILABLE when formalLifecycleState is UNAVAILABLE_FAILED`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE, phase)
    }

    @Test
    fun `UNAVAILABLE overrides all other signals including execution busy`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED,
                executionBusy = true,
                takeoverActive = true
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE, phase)
    }

    // ── 推导正确性：恢复中（优先级 2）────────────────────────────────────────

    @Test
    fun `derive returns RECOVERING when reconnectRecoveryState is RECOVERING`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                reconnectRecoveryStateWire = ReconnectRecoveryState.RECOVERING.wireValue
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING, phase)
    }

    @Test
    fun `derive returns RECOVERING when formalLifecycleState is RECOVERING`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                formalLifecycleState = FormalParticipantLifecycleState.RECOVERING
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING, phase)
    }

    @Test
    fun `RECOVERING overrides cross-device enabled when WS reconnecting`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                reconnectRecoveryStateWire = ReconnectRecoveryState.RECOVERING.wireValue,
                crossDeviceEnabled = true,
                executionBusy = true
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING, phase)
    }

    // ── 推导正确性：降级/治理阻断（优先级 3）──────────────────────────────────

    @Test
    fun `derive returns DEGRADED when governanceBlocked is true`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(governanceBlocked = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED, phase)
    }

    @Test
    fun `governance block overrides session attached and readiness`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                governanceBlocked = true,
                sessionAttached = true,
                readinessSatisfied = true
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED, phase)
    }

    // ── 推导正确性：未注册（优先级 4）────────────────────────────────────────

    @Test
    fun `derive returns UNREGISTERED when crossDeviceEnabled is false`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(crossDeviceEnabled = false)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED, phase)
    }

    // ── 推导正确性：已注册（优先级 5）────────────────────────────────────────

    @Test
    fun `derive returns REGISTERED when crossDeviceEnabled and WS disconnected and has durable id`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                wsConnected = false,
                hasDurableParticipantId = true
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED, phase)
    }

    @Test
    fun `derive returns UNREGISTERED when crossDeviceEnabled and WS disconnected but no durable id`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                wsConnected = false,
                hasDurableParticipantId = false
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED, phase)
    }

    // ── 推导正确性：降级运行时（优先级 6）────────────────────────────────────

    @Test
    fun `derive returns DEGRADED when formalLifecycleState is DEGRADED`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                formalLifecycleState = FormalParticipantLifecycleState.DEGRADED
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED, phase)
    }

    @Test
    fun `derive returns DEGRADED when formalLifecycleState is STARTING`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                formalLifecycleState = FormalParticipantLifecycleState.STARTING
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED, phase)
    }

    // ── 推导正确性：已连接（优先级 7）────────────────────────────────────────

    @Test
    fun `derive returns CONNECTED when WS connected but capability not visible`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(capabilityVisible = false)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED, phase)
    }

    // ── 推导正确性：已可见（优先级 8）────────────────────────────────────────

    @Test
    fun `derive returns VISIBLE when capability visible but session not attached`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(sessionAttached = false)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE, phase)
    }

    @Test
    fun `derive returns VISIBLE when capability visible but readiness not satisfied`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(readinessSatisfied = false)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE, phase)
    }

    // ── 推导正确性：参与中（优先级 9）────────────────────────────────────────

    @Test
    fun `derive returns PARTICIPATING when executionBusy is true`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(executionBusy = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING, phase)
    }

    @Test
    fun `derive returns PARTICIPATING when takeoverActive is true`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(takeoverActive = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING, phase)
    }

    @Test
    fun `derive returns PARTICIPATING when both executionBusy and takeoverActive are true`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(executionBusy = true, takeoverActive = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING, phase)
    }

    // ── 推导正确性：接管就绪（优先级 10）─────────────────────────────────────

    @Test
    fun `derive returns TAKEOVER_ELIGIBLE when ready and no execution and interaction surface ready`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                executionBusy = false,
                takeoverActive = false,
                interactionSurfaceReady = true
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE, phase)
    }

    @Test
    fun `derive returns READY not TAKEOVER_ELIGIBLE when interaction surface not ready`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(
                executionBusy = false,
                takeoverActive = false,
                interactionSurfaceReady = false
            )
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.READY, phase)
    }

    // ── 推导正确性：就绪（优先级 11）─────────────────────────────────────────

    @Test
    fun `derive returns READY when all conditions met but interaction surface not available`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(interactionSurfaceReady = false)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.READY, phase)
    }

    // ── TAKEOVER_ELIGIBLE 精确语义边界验证 ────────────────────────────────────

    @Test
    fun `executionBusy true prevents TAKEOVER_ELIGIBLE even with interaction surface ready`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(executionBusy = true, interactionSurfaceReady = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING, phase)
        assertFalse(
            "PARTICIPATING should not be takeover-acceptable",
            AndroidUnifiedParticipantLifecyclePhase.isTakeoverAcceptable(phase)
        )
    }

    @Test
    fun `governance block prevents TAKEOVER_ELIGIBLE even with interaction surface ready`() {
        val phase = AndroidUnifiedParticipantLifecyclePhase.derive(
            healthyReadyInput(governanceBlocked = true, interactionSurfaceReady = true)
        )
        assertEquals(AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED, phase)
        assertFalse(
            "DEGRADED should not be takeover-acceptable",
            AndroidUnifiedParticipantLifecyclePhase.isTakeoverAcceptable(phase)
        )
    }

    // ── 语义查询方法正确性 ────────────────────────────────────────────────────

    @Test
    fun `isActivelyParticipating returns true for READY TAKEOVER_ELIGIBLE and PARTICIPATING`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isActivelyParticipating(
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isActivelyParticipating(
            AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isActivelyParticipating(
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING))
    }

    @Test
    fun `isActivelyParticipating returns false for non-active phases`() {
        listOf(
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE
        ).forEach { phase ->
            assertFalse(
                "Expected isActivelyParticipating=false for $phase",
                AndroidUnifiedParticipantLifecyclePhase.isActivelyParticipating(phase)
            )
        }
    }

    @Test
    fun `isDispatchAllowed returns true only for READY and TAKEOVER_ELIGIBLE`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isDispatchAllowed(
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isDispatchAllowed(
            AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE))
    }

    @Test
    fun `isDispatchAllowed returns false for all non-dispatch phases`() {
        listOf(
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE
        ).forEach { phase ->
            assertFalse(
                "Expected isDispatchAllowed=false for $phase",
                AndroidUnifiedParticipantLifecyclePhase.isDispatchAllowed(phase)
            )
        }
    }

    @Test
    fun `isTakeoverAcceptable returns true only for TAKEOVER_ELIGIBLE`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isTakeoverAcceptable(
            AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE))
    }

    @Test
    fun `isTakeoverAcceptable returns false for all other phases including READY`() {
        listOf(
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY,
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE
        ).forEach { phase ->
            assertFalse(
                "Expected isTakeoverAcceptable=false for $phase",
                AndroidUnifiedParticipantLifecyclePhase.isTakeoverAcceptable(phase)
            )
        }
    }

    // ── 阶段转换检测 ──────────────────────────────────────────────────────────

    @Test
    fun `isForwardTransition returns true for increasing phaseRank in positive domain`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isForwardTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isForwardTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING))
    }

    @Test
    fun `isDegradationTransition returns true when going from positive to negative phaseRank`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isDegradationTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY,
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isDegradationTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING))
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.isDegradationTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE))
    }

    @Test
    fun `isDegradationTransition returns false when staying in negative domain`() {
        assertFalse(AndroidUnifiedParticipantLifecyclePhase.isDegradationTransition(
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING))
    }

    // ── wire 值稳定性 ─────────────────────────────────────────────────────────

    @Test
    fun `all 10 wire values are distinct and stable`() {
        val wireValues = AndroidUnifiedParticipantLifecyclePhase.Phase.ALL_WIRE_VALUES
        assertEquals(10, wireValues.size)
        assertTrue(wireValues.contains("unregistered"))
        assertTrue(wireValues.contains("registered"))
        assertTrue(wireValues.contains("connected"))
        assertTrue(wireValues.contains("visible"))
        assertTrue(wireValues.contains("ready"))
        assertTrue(wireValues.contains("takeover_eligible"))
        assertTrue(wireValues.contains("participating"))
        assertTrue(wireValues.contains("degraded"))
        assertTrue(wireValues.contains("recovering"))
        assertTrue(wireValues.contains("unavailable"))
    }

    @Test
    fun `fromWireValue resolves all stable wire values`() {
        AndroidUnifiedParticipantLifecyclePhase.Phase.ALL_WIRE_VALUES.forEach { wireValue ->
            assertNotNull(
                "Expected non-null phase for wire value: $wireValue",
                AndroidUnifiedParticipantLifecyclePhase.Phase.fromWireValue(wireValue)
            )
        }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertEquals(null, AndroidUnifiedParticipantLifecyclePhase.Phase.fromWireValue("unknown_xyz"))
        assertEquals(null, AndroidUnifiedParticipantLifecyclePhase.Phase.fromWireValue(null))
    }

    // ── schema 版本稳定性 ─────────────────────────────────────────────────────

    @Test
    fun `SCHEMA_VERSION is stable and non-blank`() {
        assertTrue(AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION.isNotBlank())
        assertEquals("1", AndroidUnifiedParticipantLifecyclePhase.SCHEMA_VERSION)
    }

    // ── 不变量完整性 ──────────────────────────────────────────────────────────

    @Test
    fun `PHASE_INVARIANTS contains at least 12 invariants`() {
        assertTrue(
            "PHASE_INVARIANTS should have at least 12 entries",
            AndroidUnifiedParticipantLifecyclePhase.PHASE_INVARIANTS.size >= 12
        )
    }

    // ── 阶段 phaseRank 语义 ──────────────────────────────────────────────────

    @Test
    fun `main path phases have non-negative phaseRank`() {
        listOf(
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.READY,
            AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE,
            AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING
        ).forEach { phase ->
            assertTrue(
                "Main path phase $phase should have non-negative phaseRank",
                phase.phaseRank >= 0
            )
        }
    }

    @Test
    fun `degraded recovery unavailable phases have negative phaseRank`() {
        listOf(
            AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING,
            AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE
        ).forEach { phase ->
            assertTrue(
                "Off-path phase $phase should have negative phaseRank",
                phase.phaseRank < 0
            )
        }
    }

    // ── 综合场景：完整生命周期迁移路径 ──────────────────────────────────────────

    @Test
    fun `full lifecycle progression from UNREGISTERED to PARTICIPATING produces correct phases`() {
        // 场景：Android 从未注册到最终参与中的完整路径
        val stages = listOf(
            // 1. 跨设备未启用
            healthyReadyInput(crossDeviceEnabled = false) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.UNREGISTERED,
            // 2. 跨设备已启用但 WS 未连接（有持久 ID）
            healthyReadyInput(crossDeviceEnabled = true, wsConnected = false, hasDurableParticipantId = true) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.REGISTERED,
            // 3. WS 已连接，能力未可见
            healthyReadyInput(capabilityVisible = false) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.CONNECTED,
            // 4. 能力已可见，会话未附加
            healthyReadyInput(sessionAttached = false) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.VISIBLE,
            // 5. 就绪，交互面未就绪
            healthyReadyInput(interactionSurfaceReady = false) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.READY,
            // 6. 就绪，交互面就绪 → TAKEOVER_ELIGIBLE
            healthyReadyInput() to
                AndroidUnifiedParticipantLifecyclePhase.Phase.TAKEOVER_ELIGIBLE,
            // 7. 执行忙碌 → PARTICIPATING
            healthyReadyInput(executionBusy = true) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.PARTICIPATING
        )

        stages.forEach { (input, expectedPhase) ->
            val actual = AndroidUnifiedParticipantLifecyclePhase.derive(input)
            assertEquals(
                "Expected $expectedPhase for input: $input",
                expectedPhase,
                actual
            )
        }
    }

    @Test
    fun `lifecycle degradation scenario triggers correct degraded phases`() {
        // 场景：从就绪状态退化到各种降级状态
        val degradationCases = listOf(
            // WS 重连中
            healthyReadyInput(reconnectRecoveryStateWire = ReconnectRecoveryState.RECOVERING.wireValue) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.RECOVERING,
            // 运行时故障
            healthyReadyInput(formalLifecycleState = FormalParticipantLifecycleState.UNAVAILABLE_FAILED) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.UNAVAILABLE,
            // 治理阻断
            healthyReadyInput(governanceBlocked = true) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED,
            // 运行时正在启动
            healthyReadyInput(formalLifecycleState = FormalParticipantLifecycleState.STARTING) to
                AndroidUnifiedParticipantLifecyclePhase.Phase.DEGRADED
        )

        degradationCases.forEach { (input, expectedPhase) ->
            val actual = AndroidUnifiedParticipantLifecyclePhase.derive(input)
            assertEquals(
                "Expected $expectedPhase for degradation scenario",
                expectedPhase,
                actual
            )
            assertTrue(
                "Degradation phase $expectedPhase should have negative phaseRank",
                actual.phaseRank < 0
            )
        }
    }

    // ── StabilizationBaseline 注册验证 ────────────────────────────────────────

    @Test
    fun `unified lifecycle phase is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-unified-participant-lifecycle-phase")
        assertNotNull("Expected StabilizationBaseline entry for unified lifecycle phase", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `unified lifecycle schema version is in StabilizationBaseline extendable surfaces`() {
        assertTrue(
            StabilizationBaseline.extendableSurfaceIds.contains(
                "android-unified-participant-lifecycle-phase"
            )
        )
    }
}
