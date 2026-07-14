package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-9（Android）AndroidMinimalOperabilityContract 单元测试。
 *
 * 验证以下接受准则：
 *
 *  1. **OperabilityPathStep** — 恰好 10 个步骤；stepIndex 连续；wire value 稳定；
 *     orderedPath 有序；isAndroidLocal 分布正确；fromWireValue 正确。
 *  2. **PathBlockCondition** — 6 个阻断条件；wire value 稳定；blocksStep 正确；
 *     forStep 按步骤正确过滤；fromWireValue 正确。
 *  3. **CapabilityDegradationKind** — 5 个降级种类；wire value 稳定；
 *     isRecoverable 语义正确；fromWireValue 正确。
 *  4. **LocalModeReadinessGate.ReadinessDimension** — 4 个维度；hardGates 包含
 *     LOCAL_LLM_READY 和 ACCESSIBILITY_SERVICE_ENABLED。
 *  5. **LocalModeReadinessGate.assess()** — overallReady=true 当且仅当硬前提全满足；
 *     failingHardGates 正确枚举；toWireMap 包含必要字段。
 *  6. **DelegatedExecutionBlockKind** — 5 个阻断种类；CROSS_DEVICE_RUNTIME_INACTIVE
 *     覆盖所有三种任务类型；MODE_TRANSITIONING_HOLD 包含通配符。
 *  7. **FailureDiagnosticKind** — 9 个诊断种类；actionableHint 非空；
 *     forStep 按步骤正确过滤；wire value 全局唯一。
 *  8. **OPERABILITY_INVARIANTS** — 恰好 12 条；全部非空。
 *  9. **StabilizationBaseline** — 已注册 android-minimal-operability-contract 为 CANONICAL_STABLE。
 * 10. **SCHEMA_VERSION** — 为 "1"；INTRODUCED_PR — 为 9。
 * 11. **toContractMetaWireMap()** — 包含必要的 4 个元数据字段。
 * 12. **INV-OPR 不变量** — 逐条运行时验证。
 *
 * ## 测试矩阵
 *
 * ### OperabilityPathStep
 *  - 恰好 10 个步骤
 *  - stepIndex 从 1 到 10 不含空缺
 *  - 所有 wire value 全局唯一
 *  - orderedPath 按 stepIndex 升序排列
 *  - CLONE_REPO stepIndex=1 wire=clone_repo isAndroidLocal=true
 *  - BUILD_APK stepIndex=2 wire=build_apk isAndroidLocal=true
 *  - INSTALL_APK stepIndex=3 wire=install_apk isAndroidLocal=true
 *  - CONFIGURE_V2_SERVER stepIndex=4 wire=configure_v2_server isAndroidLocal=false
 *  - ESTABLISH_WS_CONNECTION stepIndex=5 wire=establish_ws_connection isAndroidLocal=false
 *  - REPORT_DEVICE_TRUTH stepIndex=6 wire=report_device_truth isAndroidLocal=false
 *  - RECEIVE_DELEGATED_TASK stepIndex=7 wire=receive_delegated_task isAndroidLocal=false
 *  - UPLINK_RESULT stepIndex=8 wire=uplink_result isAndroidLocal=false
 *  - ASSESS_LOCAL_MODE_READINESS stepIndex=9 wire=assess_local_mode_readiness isAndroidLocal=true
 *  - DIAGNOSE_FAILURE stepIndex=10 wire=diagnose_failure isAndroidLocal=true
 *  - fromWireValue 对每个值返回正确步骤
 *  - fromWireValue 对未知值返回 null
 *
 * ### PathBlockCondition
 *  - 恰好 6 个条件
 *  - MISSING_JDK_OR_SDK blocksStep=BUILD_APK isSelfHealable=false
 *  - GATEWAY_URL_IS_PLACEHOLDER blocksStep=CONFIGURE_V2_SERVER isSelfHealable=false
 *  - CROSS_DEVICE_DISABLED blocksStep=ESTABLISH_WS_CONNECTION isSelfHealable=false
 *  - WS_HOST_UNREACHABLE blocksStep=ESTABLISH_WS_CONNECTION isSelfHealable=false
 *  - MODE_NOT_CROSS_DEVICE_ACTIVE blocksStep=RECEIVE_DELEGATED_TASK isSelfHealable=true
 *  - GOAL_EXECUTION_DISABLED blocksStep=RECEIVE_DELEGATED_TASK isSelfHealable=false
 *  - forStep(BUILD_APK) 返回 [MISSING_JDK_OR_SDK]
 *  - forStep(ESTABLISH_WS_CONNECTION) 返回 [CROSS_DEVICE_DISABLED, WS_HOST_UNREACHABLE]
 *  - forStep(RECEIVE_DELEGATED_TASK) 返回 [MODE_NOT_CROSS_DEVICE_ACTIVE, GOAL_EXECUTION_DISABLED]
 *  - fromWireValue 对每个值返回正确条件
 *  - fromWireValue 对未知值返回 null
 *
 * ### CapabilityDegradationKind
 *  - 恰好 5 个种类
 *  - LOCAL_LLM_NOT_LOADED isRecoverable=true
 *  - ACCESSIBILITY_PERMISSION_NOT_GRANTED isRecoverable=false
 *  - OVERLAY_PERMISSION_NOT_GRANTED isRecoverable=false
 *  - EXTERNAL_INFERENCE_SERVER_UNAVAILABLE isRecoverable=true
 *  - CONNECTION_DEGRADED isRecoverable=true
 *  - 所有 wire value 全局唯一
 *
 * ### LocalModeReadinessGate
 *  - ReadinessDimension 恰好 4 个维度
 *  - hardGates 包含 LOCAL_LLM_READY 和 ACCESSIBILITY_SERVICE_ENABLED（恰好 2 个）
 *  - OVERLAY_PERMISSION_GRANTED 是软前提（isHardGate=false）
 *  - CROSS_DEVICE_DISABLED_CONFIRMED 是软前提（isHardGate=false）
 *  - assess(all ready + crossDevice=false) -> overall_ready=true, failingHardGates=[]
 *  - assess(llm not ready) -> overall_ready=false, failingHardGates 含 local_llm_ready
 *  - assess(accessibility not ready) -> overall_ready=false, failingHardGates 含 accessibility_service_enabled
 *  - assess(both hard gates fail) -> failingHardGates 含两个
 *  - assess(soft gate fails) -> overall_ready=true（软前提不影响 overall_ready）
 *  - toWireMap 包含 overall_ready/schema_version/failing_hard_gates
 *
 * ### DelegatedExecutionBlockKind
 *  - 恰好 5 个种类
 *  - CROSS_DEVICE_RUNTIME_INACTIVE blocksTaskTypes 包含 goal_execution/parallel_subtask/task_assign
 *  - MODE_TRANSITIONING_HOLD blocksTaskTypes 包含 "*"
 *  - GOAL_EXECUTION_FEATURE_DISABLED blocksTaskTypes 仅包含 goal_execution
 *  - PARALLEL_EXECUTION_FEATURE_DISABLED blocksTaskTypes 仅包含 parallel_subtask
 *
 * ### FailureDiagnosticKind
 *  - 恰好 9 个种类
 *  - 所有 actionableHint 非空且不是纯空白
 *  - 所有 wire value 全局唯一
 *  - forStep(BUILD_APK) 返回 BUILD_FAILED_MISSING_PREREQUISITES
 *  - forStep(ESTABLISH_WS_CONNECTION) 包含三个连接失败条目
 *  - forStep(ASSESS_LOCAL_MODE_READINESS) 包含两个本地模式阻断条目
 *
 * ### OPERABILITY_INVARIANTS
 *  - 恰好 12 条
 *  - 所有不变量以 "INV-OPR-" 开头
 *  - 所有不变量非空
 *
 * ### StabilizationBaseline
 *  - 包含 android-minimal-operability-contract
 *  - stability = CANONICAL_STABLE
 *  - extensionGuidance = EXTEND
 *  - introducedPr = 9
 */
class Pr9AndroidMinimalOperabilityContractTest {

    private val C = AndroidMinimalOperabilityContract

    // ════════════════════════════════════════════════════════════════════════
    // OperabilityPathStep
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `OperabilityPathStep has exactly 10 steps`() {
        assertEquals(10, AndroidMinimalOperabilityContract.OperabilityPathStep.entries.size)
    }

    @Test
    fun `OperabilityPathStep stepIndex is continuous from 1 to 10`() {
        val indices = AndroidMinimalOperabilityContract.OperabilityPathStep.entries.map { it.stepIndex }.sorted()
        assertEquals((1..10).toList(), indices)
    }

    @Test
    fun `all OperabilityPathStep wire values are unique`() {
        val values = AndroidMinimalOperabilityContract.OperabilityPathStep.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `orderedPath is sorted by stepIndex ascending`() {
        val ordered = AndroidMinimalOperabilityContract.OperabilityPathStep.orderedPath
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i].stepIndex < ordered[i + 1].stepIndex)
        }
    }

    @Test
    fun `CLONE_REPO stepIndex=1 wireValue=clone_repo isAndroidLocal=true`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.CLONE_REPO
        assertEquals(1, step.stepIndex)
        assertEquals("clone_repo", step.wireValue)
        assertTrue(step.isAndroidLocal)
    }

    @Test
    fun `BUILD_APK stepIndex=2 wireValue=build_apk isAndroidLocal=true`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.BUILD_APK
        assertEquals(2, step.stepIndex)
        assertEquals("build_apk", step.wireValue)
        assertTrue(step.isAndroidLocal)
    }

    @Test
    fun `INSTALL_APK stepIndex=3 wireValue=install_apk isAndroidLocal=true`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.INSTALL_APK
        assertEquals(3, step.stepIndex)
        assertEquals("install_apk", step.wireValue)
        assertTrue(step.isAndroidLocal)
    }

    @Test
    fun `CONFIGURE_V2_SERVER stepIndex=4 wireValue=configure_v2_server isAndroidLocal=false`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.CONFIGURE_V2_SERVER
        assertEquals(4, step.stepIndex)
        assertEquals("configure_v2_server", step.wireValue)
        assertFalse(step.isAndroidLocal)
    }

    @Test
    fun `ESTABLISH_WS_CONNECTION stepIndex=5 wireValue=establish_ws_connection isAndroidLocal=false`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION
        assertEquals(5, step.stepIndex)
        assertEquals("establish_ws_connection", step.wireValue)
        assertFalse(step.isAndroidLocal)
    }

    @Test
    fun `REPORT_DEVICE_TRUTH stepIndex=6 wireValue=report_device_truth isAndroidLocal=false`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.REPORT_DEVICE_TRUTH
        assertEquals(6, step.stepIndex)
        assertEquals("report_device_truth", step.wireValue)
        assertFalse(step.isAndroidLocal)
    }

    @Test
    fun `RECEIVE_DELEGATED_TASK stepIndex=7 wireValue=receive_delegated_task isAndroidLocal=false`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK
        assertEquals(7, step.stepIndex)
        assertEquals("receive_delegated_task", step.wireValue)
        assertFalse(step.isAndroidLocal)
    }

    @Test
    fun `UPLINK_RESULT stepIndex=8 wireValue=uplink_result isAndroidLocal=false`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.UPLINK_RESULT
        assertEquals(8, step.stepIndex)
        assertEquals("uplink_result", step.wireValue)
        assertFalse(step.isAndroidLocal)
    }

    @Test
    fun `ASSESS_LOCAL_MODE_READINESS stepIndex=9 wireValue=assess_local_mode_readiness isAndroidLocal=true`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS
        assertEquals(9, step.stepIndex)
        assertEquals("assess_local_mode_readiness", step.wireValue)
        assertTrue(step.isAndroidLocal)
    }

    @Test
    fun `DIAGNOSE_FAILURE stepIndex=10 wireValue=diagnose_failure isAndroidLocal=true`() {
        val step = AndroidMinimalOperabilityContract.OperabilityPathStep.DIAGNOSE_FAILURE
        assertEquals(10, step.stepIndex)
        assertEquals("diagnose_failure", step.wireValue)
        assertTrue(step.isAndroidLocal)
    }

    @Test
    fun `OperabilityPathStep fromWireValue returns correct step for each wire value`() {
        val S = AndroidMinimalOperabilityContract.OperabilityPathStep
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.CLONE_REPO, S.fromWireValue("clone_repo"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.BUILD_APK, S.fromWireValue("build_apk"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.INSTALL_APK, S.fromWireValue("install_apk"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.CONFIGURE_V2_SERVER, S.fromWireValue("configure_v2_server"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION, S.fromWireValue("establish_ws_connection"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.REPORT_DEVICE_TRUTH, S.fromWireValue("report_device_truth"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK, S.fromWireValue("receive_delegated_task"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.UPLINK_RESULT, S.fromWireValue("uplink_result"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS, S.fromWireValue("assess_local_mode_readiness"))
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.DIAGNOSE_FAILURE, S.fromWireValue("diagnose_failure"))
    }

    @Test
    fun `OperabilityPathStep fromWireValue returns null for unknown value`() {
        assertNull(AndroidMinimalOperabilityContract.OperabilityPathStep.fromWireValue("unknown_step"))
        assertNull(AndroidMinimalOperabilityContract.OperabilityPathStep.fromWireValue(null))
    }

    @Test
    fun `ALL_WIRE_VALUES contains exactly 10 entries`() {
        assertEquals(10, AndroidMinimalOperabilityContract.OperabilityPathStep.ALL_WIRE_VALUES.size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // PathBlockCondition
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `PathBlockCondition has exactly 6 entries`() {
        assertEquals(6, AndroidMinimalOperabilityContract.PathBlockCondition.entries.size)
    }

    @Test
    fun `MISSING_JDK_OR_SDK blocksStep=BUILD_APK isSelfHealable=false`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.MISSING_JDK_OR_SDK
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.BUILD_APK, cond.blocksStep)
        assertFalse(cond.isSelfHealable)
        assertEquals("missing_jdk_or_sdk", cond.wireValue)
    }

    @Test
    fun `GATEWAY_URL_IS_PLACEHOLDER blocksStep=CONFIGURE_V2_SERVER isSelfHealable=false`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.GATEWAY_URL_IS_PLACEHOLDER
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.CONFIGURE_V2_SERVER, cond.blocksStep)
        assertFalse(cond.isSelfHealable)
        assertEquals("gateway_url_is_placeholder", cond.wireValue)
    }

    @Test
    fun `CROSS_DEVICE_DISABLED blocksStep=ESTABLISH_WS_CONNECTION isSelfHealable=false`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.CROSS_DEVICE_DISABLED
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION, cond.blocksStep)
        assertFalse(cond.isSelfHealable)
        assertEquals("cross_device_disabled", cond.wireValue)
    }

    @Test
    fun `WS_HOST_UNREACHABLE blocksStep=ESTABLISH_WS_CONNECTION isSelfHealable=false`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.WS_HOST_UNREACHABLE
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION, cond.blocksStep)
        assertFalse(cond.isSelfHealable)
        assertEquals("ws_host_unreachable", cond.wireValue)
    }

    @Test
    fun `MODE_NOT_CROSS_DEVICE_ACTIVE blocksStep=RECEIVE_DELEGATED_TASK isSelfHealable=true`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.MODE_NOT_CROSS_DEVICE_ACTIVE
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK, cond.blocksStep)
        assertTrue(cond.isSelfHealable)
        assertEquals("mode_not_cross_device_active", cond.wireValue)
    }

    @Test
    fun `GOAL_EXECUTION_DISABLED blocksStep=RECEIVE_DELEGATED_TASK isSelfHealable=false`() {
        val cond = AndroidMinimalOperabilityContract.PathBlockCondition.GOAL_EXECUTION_DISABLED
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK, cond.blocksStep)
        assertFalse(cond.isSelfHealable)
        assertEquals("goal_execution_disabled", cond.wireValue)
    }

    @Test
    fun `forStep BUILD_APK returns MISSING_JDK_OR_SDK`() {
        val conditions = AndroidMinimalOperabilityContract.PathBlockCondition.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.BUILD_APK)
        assertEquals(listOf(AndroidMinimalOperabilityContract.PathBlockCondition.MISSING_JDK_OR_SDK), conditions)
    }

    @Test
    fun `forStep ESTABLISH_WS_CONNECTION returns CROSS_DEVICE_DISABLED and WS_HOST_UNREACHABLE`() {
        val conditions = AndroidMinimalOperabilityContract.PathBlockCondition.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION)
        assertTrue(conditions.contains(AndroidMinimalOperabilityContract.PathBlockCondition.CROSS_DEVICE_DISABLED))
        assertTrue(conditions.contains(AndroidMinimalOperabilityContract.PathBlockCondition.WS_HOST_UNREACHABLE))
        assertEquals(2, conditions.size)
    }

    @Test
    fun `forStep RECEIVE_DELEGATED_TASK returns MODE_NOT_CROSS_DEVICE_ACTIVE and GOAL_EXECUTION_DISABLED`() {
        val conditions = AndroidMinimalOperabilityContract.PathBlockCondition.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK)
        assertTrue(conditions.contains(AndroidMinimalOperabilityContract.PathBlockCondition.MODE_NOT_CROSS_DEVICE_ACTIVE))
        assertTrue(conditions.contains(AndroidMinimalOperabilityContract.PathBlockCondition.GOAL_EXECUTION_DISABLED))
        assertEquals(2, conditions.size)
    }

    @Test
    fun `forStep CONFIGURE_V2_SERVER returns GATEWAY_URL_IS_PLACEHOLDER`() {
        val conditions = AndroidMinimalOperabilityContract.PathBlockCondition.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.CONFIGURE_V2_SERVER)
        assertEquals(listOf(AndroidMinimalOperabilityContract.PathBlockCondition.GATEWAY_URL_IS_PLACEHOLDER), conditions)
    }

    @Test
    fun `PathBlockCondition fromWireValue returns correct condition for each wire value`() {
        val BC = AndroidMinimalOperabilityContract.PathBlockCondition
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.MISSING_JDK_OR_SDK, BC.fromWireValue("missing_jdk_or_sdk"))
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.GATEWAY_URL_IS_PLACEHOLDER, BC.fromWireValue("gateway_url_is_placeholder"))
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.CROSS_DEVICE_DISABLED, BC.fromWireValue("cross_device_disabled"))
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.WS_HOST_UNREACHABLE, BC.fromWireValue("ws_host_unreachable"))
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.MODE_NOT_CROSS_DEVICE_ACTIVE, BC.fromWireValue("mode_not_cross_device_active"))
        assertEquals(AndroidMinimalOperabilityContract.PathBlockCondition.GOAL_EXECUTION_DISABLED, BC.fromWireValue("goal_execution_disabled"))
    }

    @Test
    fun `PathBlockCondition fromWireValue returns null for unknown value`() {
        assertNull(AndroidMinimalOperabilityContract.PathBlockCondition.fromWireValue("unknown_block"))
        assertNull(AndroidMinimalOperabilityContract.PathBlockCondition.fromWireValue(null))
    }

    @Test
    fun `all PathBlockCondition wire values are unique`() {
        val values = AndroidMinimalOperabilityContract.PathBlockCondition.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // CapabilityDegradationKind
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `CapabilityDegradationKind has exactly 5 entries`() {
        assertEquals(5, AndroidMinimalOperabilityContract.CapabilityDegradationKind.entries.size)
    }

    @Test
    fun `LOCAL_LLM_NOT_LOADED wireValue and isRecoverable`() {
        val dk = AndroidMinimalOperabilityContract.CapabilityDegradationKind.LOCAL_LLM_NOT_LOADED
        assertEquals("local_llm_not_loaded", dk.wireValue)
        assertTrue(dk.isRecoverable)
        assertEquals("local_model_inference", dk.affectedCapability)
    }

    @Test
    fun `ACCESSIBILITY_PERMISSION_NOT_GRANTED wireValue and isRecoverable`() {
        val dk = AndroidMinimalOperabilityContract.CapabilityDegradationKind.ACCESSIBILITY_PERMISSION_NOT_GRANTED
        assertEquals("accessibility_permission_not_granted", dk.wireValue)
        assertFalse(dk.isRecoverable)
        assertEquals("accessibility_action_execution", dk.affectedCapability)
    }

    @Test
    fun `OVERLAY_PERMISSION_NOT_GRANTED wireValue and isRecoverable`() {
        val dk = AndroidMinimalOperabilityContract.CapabilityDegradationKind.OVERLAY_PERMISSION_NOT_GRANTED
        assertEquals("overlay_permission_not_granted", dk.wireValue)
        assertFalse(dk.isRecoverable)
        assertEquals("floating_overlay_display", dk.affectedCapability)
    }

    @Test
    fun `EXTERNAL_INFERENCE_SERVER_UNAVAILABLE wireValue and isRecoverable`() {
        val dk = AndroidMinimalOperabilityContract.CapabilityDegradationKind.EXTERNAL_INFERENCE_SERVER_UNAVAILABLE
        assertEquals("external_inference_server_unavailable", dk.wireValue)
        assertTrue(dk.isRecoverable)
        assertEquals("local_vlm_planning", dk.affectedCapability)
    }

    @Test
    fun `CONNECTION_DEGRADED wireValue and isRecoverable`() {
        val dk = AndroidMinimalOperabilityContract.CapabilityDegradationKind.CONNECTION_DEGRADED
        assertEquals("connection_degraded", dk.wireValue)
        assertTrue(dk.isRecoverable)
        assertEquals("full_cross_device_capability", dk.affectedCapability)
    }

    @Test
    fun `CapabilityDegradationKind fromWireValue returns correct kind`() {
        val D = AndroidMinimalOperabilityContract.CapabilityDegradationKind
        assertEquals(AndroidMinimalOperabilityContract.CapabilityDegradationKind.LOCAL_LLM_NOT_LOADED, D.fromWireValue("local_llm_not_loaded"))
        assertEquals(AndroidMinimalOperabilityContract.CapabilityDegradationKind.ACCESSIBILITY_PERMISSION_NOT_GRANTED, D.fromWireValue("accessibility_permission_not_granted"))
        assertEquals(AndroidMinimalOperabilityContract.CapabilityDegradationKind.OVERLAY_PERMISSION_NOT_GRANTED, D.fromWireValue("overlay_permission_not_granted"))
        assertEquals(AndroidMinimalOperabilityContract.CapabilityDegradationKind.EXTERNAL_INFERENCE_SERVER_UNAVAILABLE, D.fromWireValue("external_inference_server_unavailable"))
        assertEquals(AndroidMinimalOperabilityContract.CapabilityDegradationKind.CONNECTION_DEGRADED, D.fromWireValue("connection_degraded"))
    }

    @Test
    fun `CapabilityDegradationKind fromWireValue returns null for unknown value`() {
        assertNull(AndroidMinimalOperabilityContract.CapabilityDegradationKind.fromWireValue("unknown_degradation"))
        assertNull(AndroidMinimalOperabilityContract.CapabilityDegradationKind.fromWireValue(null))
    }

    @Test
    fun `all CapabilityDegradationKind wire values are unique`() {
        val values = AndroidMinimalOperabilityContract.CapabilityDegradationKind.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // LocalModeReadinessGate — ReadinessDimension
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `ReadinessDimension has exactly 4 entries`() {
        assertEquals(4, AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.entries.size)
    }

    @Test
    fun `hardGates contains LOCAL_LLM_READY and ACCESSIBILITY_SERVICE_ENABLED`() {
        val hardGates = AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.hardGates
        val wireKeys = hardGates.map { it.wireKey }
        assertTrue(wireKeys.contains("local_llm_ready"))
        assertTrue(wireKeys.contains("accessibility_service_enabled"))
        assertEquals(2, hardGates.size)
    }

    @Test
    fun `OVERLAY_PERMISSION_GRANTED is soft gate`() {
        val dim = AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.OVERLAY_PERMISSION_GRANTED
        assertFalse(dim.isHardGate)
        assertEquals("overlay_permission_granted", dim.wireKey)
    }

    @Test
    fun `CROSS_DEVICE_DISABLED_CONFIRMED is soft gate`() {
        val dim = AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.CROSS_DEVICE_DISABLED_CONFIRMED
        assertFalse(dim.isHardGate)
        assertEquals("cross_device_disabled_confirmed", dim.wireKey)
    }

    @Test
    fun `ALL_WIRE_KEYS contains exactly 4 entries`() {
        val keys = AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.ALL_WIRE_KEYS
        assertEquals(4, keys.size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // LocalModeReadinessGate.assess()
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `assess all prerequisites satisfied returns overall_ready=true with empty failingHardGates`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = true,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        assertTrue(snapshot.overallReady)
        assertTrue(snapshot.failingHardGates.isEmpty())
    }

    @Test
    fun `assess localLlmReady=false returns overall_ready=false with local_llm_ready in failingHardGates`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = false,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        assertFalse(snapshot.overallReady)
        assertTrue(snapshot.failingHardGates.contains("local_llm_ready"))
    }

    @Test
    fun `assess accessibilityServiceEnabled=false returns overall_ready=false with accessibility_service_enabled in failingHardGates`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = true,
            accessibilityServiceEnabled = false,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        assertFalse(snapshot.overallReady)
        assertTrue(snapshot.failingHardGates.contains("accessibility_service_enabled"))
    }

    @Test
    fun `assess both hard gates fail returns two entries in failingHardGates`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = false,
            accessibilityServiceEnabled = false,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        assertFalse(snapshot.overallReady)
        assertEquals(2, snapshot.failingHardGates.size)
        assertTrue(snapshot.failingHardGates.contains("local_llm_ready"))
        assertTrue(snapshot.failingHardGates.contains("accessibility_service_enabled"))
    }

    @Test
    fun `assess soft gate overlay not granted does not block overall readiness when hard gates pass`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = true,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = false,
            crossDeviceEnabled = false
        )
        assertTrue("软前提失败不应阻断 overall_ready", snapshot.overallReady)
        assertTrue("软前提不出现在 failingHardGates 中", snapshot.failingHardGates.isEmpty())
    }

    @Test
    fun `assess localLlmReady=null treats as not satisfied`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = null,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        assertFalse(snapshot.overallReady)
        assertTrue(snapshot.failingHardGates.contains("local_llm_ready"))
    }

    @Test
    fun `assess snapshot schemaVersion matches SCHEMA_VERSION`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = true,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = null,
            crossDeviceEnabled = false
        )
        assertEquals(C.SCHEMA_VERSION, snapshot.schemaVersion)
    }

    @Test
    fun `toWireMap includes overall_ready schema_version and failing_hard_gates`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = false,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = null,
            crossDeviceEnabled = null
        )
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("overall_ready"))
        assertTrue(map.containsKey("schema_version"))
        assertTrue(map.containsKey("failing_hard_gates"))
        assertEquals(snapshot.overallReady, map["overall_ready"])
    }

    @Test
    fun `toWireMap includes all dimension keys`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate
        val snapshot = gate.assess(
            localLlmReady = true,
            accessibilityServiceEnabled = true,
            overlayPermissionGranted = true,
            crossDeviceEnabled = false
        )
        val map = snapshot.toWireMap()
        assertTrue(map.containsKey("local_llm_ready"))
        assertTrue(map.containsKey("accessibility_service_enabled"))
        assertTrue(map.containsKey("overlay_permission_granted"))
        assertTrue(map.containsKey("cross_device_disabled_confirmed"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // DelegatedExecutionBlockKind
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `DelegatedExecutionBlockKind has exactly 5 entries`() {
        assertEquals(5, AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.entries.size)
    }

    @Test
    fun `CROSS_DEVICE_RUNTIME_INACTIVE blocksTaskTypes covers all three task types`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.CROSS_DEVICE_RUNTIME_INACTIVE.blocksTaskTypes
        assertTrue(kinds.contains("goal_execution"))
        assertTrue(kinds.contains("parallel_subtask"))
        assertTrue(kinds.contains("task_assign"))
    }

    @Test
    fun `MODE_TRANSITIONING_HOLD blocksTaskTypes contains wildcard`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.MODE_TRANSITIONING_HOLD.blocksTaskTypes
        assertTrue(kinds.contains("*"))
    }

    @Test
    fun `GOAL_EXECUTION_FEATURE_DISABLED blocksTaskTypes contains only goal_execution`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.GOAL_EXECUTION_FEATURE_DISABLED.blocksTaskTypes
        assertEquals(listOf("goal_execution"), kinds)
    }

    @Test
    fun `PARALLEL_EXECUTION_FEATURE_DISABLED blocksTaskTypes contains only parallel_subtask`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.PARALLEL_EXECUTION_FEATURE_DISABLED.blocksTaskTypes
        assertEquals(listOf("parallel_subtask"), kinds)
    }

    @Test
    fun `POLICY_ROUTING_REJECTED wireValue is correct`() {
        val kind = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.POLICY_ROUTING_REJECTED
        assertEquals("policy_routing_rejected", kind.wireValue)
        assertTrue(kind.blocksTaskTypes.contains("goal_execution"))
        assertTrue(kind.blocksTaskTypes.contains("parallel_subtask"))
    }

    @Test
    fun `DelegatedExecutionBlockKind fromWireValue returns correct kind`() {
        val DK = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind
        assertEquals(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.CROSS_DEVICE_RUNTIME_INACTIVE, DK.fromWireValue("cross_device_runtime_inactive"))
        assertEquals(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.GOAL_EXECUTION_FEATURE_DISABLED, DK.fromWireValue("goal_execution_feature_disabled"))
        assertEquals(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.PARALLEL_EXECUTION_FEATURE_DISABLED, DK.fromWireValue("parallel_execution_feature_disabled"))
        assertEquals(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.POLICY_ROUTING_REJECTED, DK.fromWireValue("policy_routing_rejected"))
        assertEquals(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.MODE_TRANSITIONING_HOLD, DK.fromWireValue("mode_transitioning_hold"))
    }

    @Test
    fun `DelegatedExecutionBlockKind fromWireValue returns null for unknown`() {
        assertNull(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.fromWireValue("unknown_block_kind"))
        assertNull(AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.fromWireValue(null))
    }

    @Test
    fun `all DelegatedExecutionBlockKind wire values are unique`() {
        val values = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // FailureDiagnosticKind
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `FailureDiagnosticKind has exactly 9 entries`() {
        assertEquals(9, AndroidMinimalOperabilityContract.FailureDiagnosticKind.entries.size)
    }

    @Test
    fun `all FailureDiagnosticKind actionableHints are non-empty`() {
        AndroidMinimalOperabilityContract.FailureDiagnosticKind.entries.forEach { kind ->
            assertTrue(
                "actionableHint for ${kind.wireValue} must not be blank",
                kind.actionableHint.isNotBlank()
            )
        }
    }

    @Test
    fun `all FailureDiagnosticKind wire values are unique`() {
        val values = AndroidMinimalOperabilityContract.FailureDiagnosticKind.ALL_WIRE_VALUES
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `forStep BUILD_APK returns BUILD_FAILED_MISSING_PREREQUISITES`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.BUILD_APK)
        assertEquals(1, diagnostics.size)
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.BUILD_FAILED_MISSING_PREREQUISITES, diagnostics.first())
    }

    @Test
    fun `forStep ESTABLISH_WS_CONNECTION returns three connection failure entries`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.ESTABLISH_WS_CONNECTION)
        val wires = diagnostics.map { it.wireValue }
        assertTrue(wires.contains("connect_failed_invalid_gateway_url"))
        assertTrue(wires.contains("connect_failed_cross_device_disabled"))
        assertTrue(wires.contains("connect_failed_host_unreachable"))
        assertEquals(3, diagnostics.size)
    }

    @Test
    fun `forStep ASSESS_LOCAL_MODE_READINESS returns two local mode block entries`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS)
        val wires = diagnostics.map { it.wireValue }
        assertTrue(wires.contains("local_mode_blocked_llm_not_loaded"))
        assertTrue(wires.contains("local_mode_blocked_accessibility_not_granted"))
        assertEquals(2, diagnostics.size)
    }

    @Test
    fun `forStep RECEIVE_DELEGATED_TASK returns delegated task rejected entry`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.RECEIVE_DELEGATED_TASK)
        assertEquals(1, diagnostics.size)
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.DELEGATED_TASK_REJECTED_PIPELINE_DISABLED, diagnostics.first())
    }

    @Test
    fun `forStep UPLINK_RESULT returns result uplink queued entry`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.UPLINK_RESULT)
        assertEquals(1, diagnostics.size)
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.RESULT_UPLINK_QUEUED_PENDING_RECONNECT, diagnostics.first())
    }

    @Test
    fun `forStep REPORT_DEVICE_TRUTH returns device truth uplink failed entry`() {
        val diagnostics = AndroidMinimalOperabilityContract.FailureDiagnosticKind.forStep(AndroidMinimalOperabilityContract.OperabilityPathStep.REPORT_DEVICE_TRUTH)
        assertEquals(1, diagnostics.size)
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.DEVICE_TRUTH_UPLINK_FAILED, diagnostics.first())
    }

    @Test
    fun `FailureDiagnosticKind fromWireValue returns correct kind`() {
        val FK = AndroidMinimalOperabilityContract.FailureDiagnosticKind
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.BUILD_FAILED_MISSING_PREREQUISITES, FK.fromWireValue("build_failed_missing_prerequisites"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.CONNECT_FAILED_INVALID_GATEWAY_URL, FK.fromWireValue("connect_failed_invalid_gateway_url"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.CONNECT_FAILED_CROSS_DEVICE_DISABLED, FK.fromWireValue("connect_failed_cross_device_disabled"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.CONNECT_FAILED_HOST_UNREACHABLE, FK.fromWireValue("connect_failed_host_unreachable"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.DEVICE_TRUTH_UPLINK_FAILED, FK.fromWireValue("device_truth_uplink_failed"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.DELEGATED_TASK_REJECTED_PIPELINE_DISABLED, FK.fromWireValue("delegated_task_rejected_pipeline_disabled"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.RESULT_UPLINK_QUEUED_PENDING_RECONNECT, FK.fromWireValue("result_uplink_queued_pending_reconnect"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.LOCAL_MODE_BLOCKED_LLM_NOT_LOADED, FK.fromWireValue("local_mode_blocked_llm_not_loaded"))
        assertEquals(AndroidMinimalOperabilityContract.FailureDiagnosticKind.LOCAL_MODE_BLOCKED_ACCESSIBILITY_NOT_GRANTED, FK.fromWireValue("local_mode_blocked_accessibility_not_granted"))
    }

    @Test
    fun `FailureDiagnosticKind fromWireValue returns null for unknown value`() {
        assertNull(AndroidMinimalOperabilityContract.FailureDiagnosticKind.fromWireValue("unknown_diagnostic"))
        assertNull(AndroidMinimalOperabilityContract.FailureDiagnosticKind.fromWireValue(null))
    }

    // ════════════════════════════════════════════════════════════════════════
    // Wire value global uniqueness across enums
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `FailureDiagnosticKind wire values do not overlap with PathBlockCondition wire values`() {
        val failureWires = AndroidMinimalOperabilityContract.FailureDiagnosticKind.ALL_WIRE_VALUES
        val blockWires = AndroidMinimalOperabilityContract.PathBlockCondition.ALL_WIRE_VALUES
        val overlap = failureWires.intersect(blockWires)
        assertTrue("Wire value 重叠：$overlap", overlap.isEmpty())
    }

    @Test
    fun `FailureDiagnosticKind wire values do not overlap with CapabilityDegradationKind wire values`() {
        val failureWires = AndroidMinimalOperabilityContract.FailureDiagnosticKind.ALL_WIRE_VALUES
        val degradeWires = AndroidMinimalOperabilityContract.CapabilityDegradationKind.ALL_WIRE_VALUES
        val overlap = failureWires.intersect(degradeWires)
        assertTrue("Wire value 重叠：$overlap", overlap.isEmpty())
    }

    // ════════════════════════════════════════════════════════════════════════
    // OPERABILITY_INVARIANTS
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `OPERABILITY_INVARIANTS has exactly 12 entries`() {
        assertEquals(12, C.OPERABILITY_INVARIANTS.size)
    }

    @Test
    fun `all OPERABILITY_INVARIANTS are non-empty`() {
        C.OPERABILITY_INVARIANTS.forEach { invariant ->
            assertTrue("不变量不允许为空字符串", invariant.isNotBlank())
        }
    }

    @Test
    fun `all OPERABILITY_INVARIANTS start with INV-OPR-`() {
        C.OPERABILITY_INVARIANTS.forEach { invariant ->
            assertTrue("不变量 '$invariant' 应以 INV-OPR- 开头", invariant.startsWith("INV-OPR-"))
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Schema meta
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", C.SCHEMA_VERSION)
    }

    @Test
    fun `INTRODUCED_PR is 9`() {
        assertEquals(9, C.INTRODUCED_PR)
    }

    // ════════════════════════════════════════════════════════════════════════
    // toContractMetaWireMap
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `toContractMetaWireMap contains all four required fields`() {
        val map = C.toContractMetaWireMap()
        assertTrue(map.containsKey("operability_contract_schema_version"))
        assertTrue(map.containsKey("operability_path_step_count"))
        assertTrue(map.containsKey("operability_invariant_count"))
        assertTrue(map.containsKey("introduced_pr"))
    }

    @Test
    fun `toContractMetaWireMap values are consistent with contract`() {
        val map = C.toContractMetaWireMap()
        assertEquals(C.SCHEMA_VERSION, map["operability_contract_schema_version"])
        assertEquals(AndroidMinimalOperabilityContract.OperabilityPathStep.entries.size, map["operability_path_step_count"])
        assertEquals(C.OPERABILITY_INVARIANTS.size, map["operability_invariant_count"])
        assertEquals(C.INTRODUCED_PR, map["introduced_pr"])
    }

    // ════════════════════════════════════════════════════════════════════════
    // StabilizationBaseline registration
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `StabilizationBaseline contains android-minimal-operability-contract`() {
        assertTrue(
            "StabilizationBaseline 应已注册 android-minimal-operability-contract",
            StabilizationBaseline.isRegistered("android-minimal-operability-contract")
        )
    }

    @Test
    fun `android-minimal-operability-contract has CANONICAL_STABLE stability`() {
        val entry = StabilizationBaseline.forId("android-minimal-operability-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `android-minimal-operability-contract has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-minimal-operability-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry!!.extensionGuidance)
    }

    @Test
    fun `android-minimal-operability-contract introducedPr is 9`() {
        val entry = StabilizationBaseline.forId("android-minimal-operability-contract")
        assertNotNull(entry)
        assertEquals(9, entry!!.introducedPr)
    }

    // ════════════════════════════════════════════════════════════════════════
    // INV-OPR 不变量运行时验证
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-OPR-01 OperabilityPathStep has exactly 10 steps with indices 1 to 10`() {
        val entries = AndroidMinimalOperabilityContract.OperabilityPathStep.entries
        assertEquals("INV-OPR-01 违反：步骤数量应为 10", 10, entries.size)
        val indices = entries.map { it.stepIndex }.sorted()
        assertEquals("INV-OPR-01 违反：stepIndex 应从 1 到 10", (1..10).toList(), indices)
    }

    @Test
    fun `INV-OPR-02 orderedPath is strictly ascending without gaps`() {
        val ordered = AndroidMinimalOperabilityContract.OperabilityPathStep.orderedPath
        for (i in ordered.indices) {
            assertEquals("INV-OPR-02 违反：第 ${i + 1} 个步骤的 stepIndex 应为 ${i + 1}", i + 1, ordered[i].stepIndex)
        }
    }

    @Test
    fun `INV-OPR-03 each PathBlockCondition has exactly one blocksStep`() {
        // 本测试验证每个 PathBlockCondition 有明确的 blocksStep，且不为 null
        AndroidMinimalOperabilityContract.PathBlockCondition.entries.forEach { cond ->
            assertNotNull("INV-OPR-03 违反：${cond.wireValue} 的 blocksStep 不应为 null", cond.blocksStep)
        }
    }

    @Test
    fun `INV-OPR-04 CapabilityDegradationKind wire values do not appear in PathBlockCondition`() {
        val degradeWires = AndroidMinimalOperabilityContract.CapabilityDegradationKind.ALL_WIRE_VALUES
        val blockWires = AndroidMinimalOperabilityContract.PathBlockCondition.ALL_WIRE_VALUES
        val overlap = degradeWires.intersect(blockWires)
        assertTrue("INV-OPR-04 违反：wire value 重叠 = $overlap", overlap.isEmpty())
    }

    @Test
    fun `INV-OPR-05 assess overallReady=true iff failingHardGates is empty`() {
        val gate = AndroidMinimalOperabilityContract.LocalModeReadinessGate

        val ready = gate.assess(true, true, true, false)
        assertTrue("INV-OPR-05 违反：全满足时 overallReady 应为 true", ready.overallReady)
        assertTrue("INV-OPR-05 违反：全满足时 failingHardGates 应为空", ready.failingHardGates.isEmpty())

        val notReady = gate.assess(false, true, true, false)
        assertFalse("INV-OPR-05 违反：有失败硬前提时 overallReady 应为 false", notReady.overallReady)
        assertTrue("INV-OPR-05 违反：有失败硬前提时 failingHardGates 不应为空", notReady.failingHardGates.isNotEmpty())
    }

    @Test
    fun `INV-OPR-06 hardGates contains at least LOCAL_LLM_READY and ACCESSIBILITY_SERVICE_ENABLED`() {
        val hardGates = AndroidMinimalOperabilityContract.LocalModeReadinessGate.ReadinessDimension.hardGates
        val wireKeys = hardGates.map { it.wireKey }
        assertTrue("INV-OPR-06 违反：hardGates 应包含 local_llm_ready", wireKeys.contains("local_llm_ready"))
        assertTrue("INV-OPR-06 违反：hardGates 应包含 accessibility_service_enabled", wireKeys.contains("accessibility_service_enabled"))
        assertTrue("INV-OPR-06 违反：hardGates 数量应 >= 2", hardGates.size >= 2)
    }

    @Test
    fun `INV-OPR-07 CROSS_DEVICE_RUNTIME_INACTIVE blocksTaskTypes has all three types`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.CROSS_DEVICE_RUNTIME_INACTIVE.blocksTaskTypes
        assertTrue("INV-OPR-07 违反：应包含 goal_execution", kinds.contains("goal_execution"))
        assertTrue("INV-OPR-07 违反：应包含 parallel_subtask", kinds.contains("parallel_subtask"))
        assertTrue("INV-OPR-07 违反：应包含 task_assign", kinds.contains("task_assign"))
    }

    @Test
    fun `INV-OPR-08 MODE_TRANSITIONING_HOLD blocksTaskTypes contains wildcard`() {
        val kinds = AndroidMinimalOperabilityContract.DelegatedExecutionBlockKind.MODE_TRANSITIONING_HOLD.blocksTaskTypes
        assertTrue("INV-OPR-08 违反：应包含通配符 *", kinds.contains("*"))
    }

    @Test
    fun `INV-OPR-09 all FailureDiagnosticKind actionableHints are non-empty`() {
        AndroidMinimalOperabilityContract.FailureDiagnosticKind.entries.forEach { kind ->
            assertTrue("INV-OPR-09 违反：${kind.wireValue} 的 actionableHint 不应为空白", kind.actionableHint.isNotBlank())
        }
    }

    @Test
    fun `INV-OPR-10 FailureDiagnosticKind wire values globally unique across all enums`() {
        val allWires = AndroidMinimalOperabilityContract.FailureDiagnosticKind.ALL_WIRE_VALUES +
            AndroidMinimalOperabilityContract.PathBlockCondition.ALL_WIRE_VALUES +
            AndroidMinimalOperabilityContract.CapabilityDegradationKind.ALL_WIRE_VALUES
        val set = allWires.toSet()
        assertEquals(
            "INV-OPR-10 违反：三个枚举合并后存在重复 wire value，合并数=${allWires.size}，去重数=${set.size}",
            allWires.size, set.size
        )
    }

    @Test
    fun `INV-OPR-11 SCHEMA_VERSION is 1`() {
        assertEquals("INV-OPR-11 违反：SCHEMA_VERSION 应为 \"1\"", "1", C.SCHEMA_VERSION)
    }

    @Test
    fun `INV-OPR-12 StabilizationBaseline contains android-minimal-operability-contract as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-minimal-operability-contract")
        assertNotNull("INV-OPR-12 违反：StabilizationBaseline 应已注册 android-minimal-operability-contract", entry)
        assertEquals(
            "INV-OPR-12 违反：stability 应为 CANONICAL_STABLE",
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }
}
