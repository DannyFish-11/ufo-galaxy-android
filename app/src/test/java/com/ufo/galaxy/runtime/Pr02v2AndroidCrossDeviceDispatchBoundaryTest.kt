package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-02v2 (Android) — AndroidCrossDeviceDispatchBoundaryContract 合约单元测试。
 *
 * 验证以下验收准则：
 *
 *  1. **DispatchBoundaryClass** — 5 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isCanonicalPath / isFallbackPath / v2DispatchPath 语义正确。
 *  2. **DispatchPathConsumptionKind** — 3 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isActiveConsumer 语义正确。
 *  3. **derive() — NOT_CROSS_DEVICE 路径**：
 *     isCrossDeviceMode=false → DispatchBoundaryClass.NOT_CROSS_DEVICE + NONE。
 *  4. **derive() — LEGACY_BYPASS 路径**：
 *     isCrossDeviceMode=true + isLegacyBypassEntry=true → LEGACY_BYPASS + NONE。
 *  5. **derive() — COMPAT_FALLBACK 路径**：
 *     isCrossDeviceMode=true + isLegacyCompatRemapped=true → COMPAT_FALLBACK；
 *     isAgentBridgeCompatEntry=true → COMPAT_FALLBACK；
 *     isAgentBridgeCompatEntry=true + 活跃执行 → COMPAT_FALLBACK + INBOUND_EXECUTION。
 *  6. **derive() — CONTROLLED_CANONICAL_FALLBACK 路径**：
 *     isAgentBridgeFallback=true → CONTROLLED_CANONICAL_FALLBACK；
 *     活跃执行 → LOCAL_FALLBACK_EXECUTION；
 *     无活跃执行 → NONE。
 *  7. **derive() — CANONICAL_CROSS_DEVICE 路径**：
 *     跨设备模式 + 无特殊路径 → CANONICAL_CROSS_DEVICE；
 *     活跃执行 → INBOUND_EXECUTION；
 *     无活跃执行 → NONE。
 *  8. **DispatchBoundarySnapshot.toWireMap()** — 3 个 key 均存在；值类型正确。
 *  9. **DISPATCH_BOUNDARY_INVARIANTS** — 共 7 条；全部非空；无重复 ID。
 * 10. **StabilizationBaseline** — "android-cross-device-dispatch-boundary-contract" 已注册为 CANONICAL_STABLE。
 * 11. **SCHEMA_VERSION** — 值为 "1"。
 * 12. **KEY_ 常量** — 全部非空；互不相同。
 * 13. **V2_DISPATCH_PATH_MAP** — 包含全部 5 个 DispatchBoundaryClass 条目。
 * 14. **INV-DB-02**：NOT_CROSS_DEVICE 的 isCanonicalPath=false + isFallbackPath=false + v2DispatchPath=null。
 * 15. **INV-DB-05**：COMPAT_FALLBACK 和 LEGACY_BYPASS 的 isCanonicalPath=false。
 * 16. **优先级校验**：isLegacyBypassEntry 优先于 isLegacyCompatRemapped（即使两者同为 true）。
 * 17. **优先级校验**：isLegacyCompatRemapped 优先于 isAgentBridgeFallback。
 */
class Pr02v2AndroidCrossDeviceDispatchBoundaryTest {

    // ── 测试辅助 ────────────────────────────────────────────────────────────────

    private fun defaultInput(
        isCrossDeviceMode: Boolean = true,
        executionPathTag: AndroidRuntimeObservabilityAuditContract.ExecutionPathTag =
            AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH,
        isFallbackTierActive: Boolean = false,
        isAgentBridgeFallback: Boolean = false,
        isLegacyCompatRemapped: Boolean = false,
        isAgentBridgeCompatEntry: Boolean = false,
        isLegacyBypassEntry: Boolean = false,
        hasDelegatedOrTakeoverExecution: Boolean = false
    ) = AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryDerivationInput(
        isCrossDeviceMode = isCrossDeviceMode,
        executionPathTag = executionPathTag,
        isFallbackTierActive = isFallbackTierActive,
        isAgentBridgeFallback = isAgentBridgeFallback,
        isLegacyCompatRemapped = isLegacyCompatRemapped,
        isAgentBridgeCompatEntry = isAgentBridgeCompatEntry,
        isLegacyBypassEntry = isLegacyBypassEntry,
        hasDelegatedOrTakeoverExecution = hasDelegatedOrTakeoverExecution
    )

    private fun derive(input: AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryDerivationInput) =
        AndroidCrossDeviceDispatchBoundaryContract.derive(input)

    // ══════════════════════════════════════════════════════════════════════════
    // 1. DispatchBoundaryClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `DispatchBoundaryClass has 5 values`() {
        assertEquals(5, AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.values().size)
    }

    @Test fun `DispatchBoundaryClass CANONICAL_CROSS_DEVICE wireValue is canonical_cross_device`() {
        assertEquals(
            "canonical_cross_device",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE.wireValue
        )
    }

    @Test fun `DispatchBoundaryClass CONTROLLED_CANONICAL_FALLBACK wireValue is controlled_canonical_fallback`() {
        assertEquals(
            "controlled_canonical_fallback",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK.wireValue
        )
    }

    @Test fun `DispatchBoundaryClass COMPAT_FALLBACK wireValue is compat_fallback`() {
        assertEquals(
            "compat_fallback",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK.wireValue
        )
    }

    @Test fun `DispatchBoundaryClass LEGACY_BYPASS wireValue is legacy_bypass`() {
        assertEquals(
            "legacy_bypass",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS.wireValue
        )
    }

    @Test fun `DispatchBoundaryClass NOT_CROSS_DEVICE wireValue is not_cross_device`() {
        assertEquals(
            "not_cross_device",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE.wireValue
        )
    }

    @Test fun `DispatchBoundaryClass fromWireValue returns correct enum for all values`() {
        for (v in AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.values()) {
            assertEquals(v, AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.fromWireValue(v.wireValue))
        }
    }

    @Test fun `DispatchBoundaryClass fromWireValue returns NOT_CROSS_DEVICE for unknown value`() {
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE,
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.fromWireValue("unknown_boundary")
        )
    }

    @Test fun `DispatchBoundaryClass CANONICAL_CROSS_DEVICE isCanonicalPath is true`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE.isCanonicalPath)
    }

    @Test fun `DispatchBoundaryClass non-canonical entries have isCanonicalPath false`() {
        listOf(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK,
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS,
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE
        ).forEach { assertFalse("${it.name} should not be canonical", it.isCanonicalPath) }
    }

    @Test fun `DispatchBoundaryClass CONTROLLED_CANONICAL_FALLBACK isFallbackPath is true`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK.isFallbackPath)
    }

    @Test fun `DispatchBoundaryClass COMPAT_FALLBACK isFallbackPath is true`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK.isFallbackPath)
    }

    @Test fun `DispatchBoundaryClass LEGACY_BYPASS isFallbackPath is false`() {
        assertFalse(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS.isFallbackPath)
    }

    @Test fun `DispatchBoundaryClass NOT_CROSS_DEVICE v2DispatchPath is null`() {
        assertNull(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE.v2DispatchPath)
    }

    @Test fun `DispatchBoundaryClass CANONICAL_CROSS_DEVICE v2DispatchPath is canonical_dispatch`() {
        assertEquals(
            "canonical_dispatch",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE.v2DispatchPath
        )
    }

    @Test fun `DispatchBoundaryClass CONTROLLED_CANONICAL_FALLBACK v2DispatchPath is canonical_fallback`() {
        assertEquals(
            "canonical_fallback",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK.v2DispatchPath
        )
    }

    @Test fun `DispatchBoundaryClass COMPAT_FALLBACK v2DispatchPath is compat_fallback`() {
        assertEquals(
            "compat_fallback",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK.v2DispatchPath
        )
    }

    @Test fun `DispatchBoundaryClass LEGACY_BYPASS v2DispatchPath is legacy_bypass`() {
        assertEquals(
            "legacy_bypass",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS.v2DispatchPath
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. DispatchPathConsumptionKind enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `DispatchPathConsumptionKind has 3 values`() {
        assertEquals(3, AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.values().size)
    }

    @Test fun `DispatchPathConsumptionKind INBOUND_EXECUTION wireValue is inbound_execution`() {
        assertEquals(
            "inbound_execution",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION.wireValue
        )
    }

    @Test fun `DispatchPathConsumptionKind LOCAL_FALLBACK_EXECUTION wireValue is local_fallback_execution`() {
        assertEquals(
            "local_fallback_execution",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.LOCAL_FALLBACK_EXECUTION.wireValue
        )
    }

    @Test fun `DispatchPathConsumptionKind NONE wireValue is none`() {
        assertEquals(
            "none",
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE.wireValue
        )
    }

    @Test fun `DispatchPathConsumptionKind fromWireValue returns correct enum for all values`() {
        for (v in AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.values()) {
            assertEquals(v, AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.fromWireValue(v.wireValue))
        }
    }

    @Test fun `DispatchPathConsumptionKind fromWireValue returns NONE for unknown value`() {
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.fromWireValue("unknown_kind")
        )
    }

    @Test fun `DispatchPathConsumptionKind INBOUND_EXECUTION isActiveConsumer is true`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION.isActiveConsumer)
    }

    @Test fun `DispatchPathConsumptionKind LOCAL_FALLBACK_EXECUTION isActiveConsumer is true`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.LOCAL_FALLBACK_EXECUTION.isActiveConsumer)
    }

    @Test fun `DispatchPathConsumptionKind NONE isActiveConsumer is false`() {
        assertFalse(AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE.isActiveConsumer)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. derive() — NOT_CROSS_DEVICE (优先级 1)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns NOT_CROSS_DEVICE when isCrossDeviceMode is false`() {
        val result = derive(defaultInput(isCrossDeviceMode = false))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive returns NONE consumption kind when not cross device`() {
        val result = derive(defaultInput(isCrossDeviceMode = false))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive NOT_CROSS_DEVICE even when isLegacyBypassEntry is true and isCrossDeviceMode is false`() {
        // NOT_CROSS_DEVICE has highest priority
        val result = derive(defaultInput(isCrossDeviceMode = false, isLegacyBypassEntry = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE,
            result.dispatchBoundaryClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. derive() — LEGACY_BYPASS (优先级 2)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns LEGACY_BYPASS when isLegacyBypassEntry is true`() {
        val result = derive(defaultInput(isLegacyBypassEntry = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive returns NONE consumption when LEGACY_BYPASS with no active execution`() {
        val result = derive(defaultInput(isLegacyBypassEntry = true, hasDelegatedOrTakeoverExecution = false))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive LEGACY_BYPASS has priority over COMPAT_FALLBACK when both are true`() {
        val result = derive(defaultInput(isLegacyBypassEntry = true, isLegacyCompatRemapped = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive LEGACY_BYPASS has priority over agent bridge fallback when both are true`() {
        val result = derive(defaultInput(isLegacyBypassEntry = true, isAgentBridgeFallback = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS,
            result.dispatchBoundaryClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. derive() — COMPAT_FALLBACK (优先级 3)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns COMPAT_FALLBACK when isLegacyCompatRemapped is true`() {
        val result = derive(defaultInput(isLegacyCompatRemapped = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive returns COMPAT_FALLBACK when isAgentBridgeCompatEntry is true`() {
        val result = derive(defaultInput(isAgentBridgeCompatEntry = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive COMPAT_FALLBACK with active delegated execution returns INBOUND_EXECUTION`() {
        val result = derive(defaultInput(
            isAgentBridgeCompatEntry = true,
            hasDelegatedOrTakeoverExecution = true
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive COMPAT_FALLBACK with no active execution returns NONE`() {
        // 测试修复:defaultInput 默认 executionPathTag=CROSS_DEVICE_PATH,而
        // isActiveExecution 把该 tag 本身视为活跃执行,"无活跃执行"必须连 tag
        // 一起换成 LOCAL_PATH,否则恒推导出 INBOUND_EXECUTION。
        val result = derive(defaultInput(
            isAgentBridgeCompatEntry = true,
            hasDelegatedOrTakeoverExecution = false,
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.LOCAL_PATH
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive COMPAT_FALLBACK has priority over agent bridge fallback when both are true`() {
        val result = derive(defaultInput(isLegacyCompatRemapped = true, isAgentBridgeFallback = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. derive() — CONTROLLED_CANONICAL_FALLBACK (优先级 4)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns CONTROLLED_CANONICAL_FALLBACK when isAgentBridgeFallback is true`() {
        val result = derive(defaultInput(isAgentBridgeFallback = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive CONTROLLED_CANONICAL_FALLBACK with active execution returns LOCAL_FALLBACK_EXECUTION`() {
        val result = derive(defaultInput(
            isAgentBridgeFallback = true,
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH,
            hasDelegatedOrTakeoverExecution = true
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.LOCAL_FALLBACK_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive CONTROLLED_CANONICAL_FALLBACK with no active execution returns NONE`() {
        // 测试修复:同上,"无活跃执行"需把默认的 CROSS_DEVICE_PATH tag 换成 LOCAL_PATH。
        val result = derive(defaultInput(
            isAgentBridgeFallback = true,
            hasDelegatedOrTakeoverExecution = false,
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.LOCAL_PATH
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive returns CONTROLLED_CANONICAL_FALLBACK when fallback tier active and degraded path`() {
        val result = derive(defaultInput(
            isFallbackTierActive = true,
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DEGRADED_PATH
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive does not return CONTROLLED_CANONICAL_FALLBACK when fallback active but path is not degraded`() {
        val result = derive(defaultInput(
            isFallbackTierActive = true,
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH
        ))
        // fallback tier active but cross_device path → should be CANONICAL_CROSS_DEVICE not fallback
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE,
            result.dispatchBoundaryClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. derive() — CANONICAL_CROSS_DEVICE (优先级 5 / 默认)
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns CANONICAL_CROSS_DEVICE when cross device and no special flags`() {
        val result = derive(defaultInput())
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `derive CANONICAL_CROSS_DEVICE with active delegated execution returns INBOUND_EXECUTION`() {
        val result = derive(defaultInput(hasDelegatedOrTakeoverExecution = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE,
            result.dispatchBoundaryClass
        )
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive CANONICAL_CROSS_DEVICE with cross_device_path tag and active execution returns INBOUND_EXECUTION`() {
        val result = derive(defaultInput(
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH,
            hasDelegatedOrTakeoverExecution = false
        ))
        // CROSS_DEVICE_PATH tag alone drives INBOUND_EXECUTION even without hasDelegatedOrTakeoverExecution
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive CANONICAL_CROSS_DEVICE with delegated path tag returns INBOUND_EXECUTION`() {
        val result = derive(defaultInput(
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DELEGATED_PATH
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive CANONICAL_CROSS_DEVICE with takeover path tag returns INBOUND_EXECUTION`() {
        val result = derive(defaultInput(
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.TAKEOVER_PATH
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.INBOUND_EXECUTION,
            result.dispatchPathConsumptionKind
        )
    }

    @Test fun `derive CANONICAL_CROSS_DEVICE with unknown path and no active execution returns NONE`() {
        val result = derive(defaultInput(
            executionPathTag = AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.UNKNOWN,
            hasDelegatedOrTakeoverExecution = false
        ))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.NONE,
            result.dispatchPathConsumptionKind
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. DispatchBoundarySnapshot.toWireMap()
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `toWireMap contains all 3 required keys`() {
        val snapshot = derive(defaultInput())
        val wireMap = snapshot.toWireMap()
        assertTrue(wireMap.containsKey(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_CLASS))
        assertTrue(wireMap.containsKey(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_PATH_CONSUMPTION_KIND))
        assertTrue(wireMap.containsKey(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION))
    }

    @Test fun `toWireMap dispatch_boundary_class has correct wire value for canonical`() {
        val snapshot = derive(defaultInput())
        val wireMap = snapshot.toWireMap()
        assertEquals("canonical_cross_device", wireMap[AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_CLASS])
    }

    @Test fun `toWireMap dispatch_boundary_schema_version matches SCHEMA_VERSION`() {
        val snapshot = derive(defaultInput())
        val wireMap = snapshot.toWireMap()
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION,
            wireMap[AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION]
        )
    }

    @Test fun `toWireMap values are all String type`() {
        val snapshot = derive(defaultInput())
        val wireMap = snapshot.toWireMap()
        wireMap.values.forEach { assertTrue("Value $it should be String", it is String) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. DISPATCH_BOUNDARY_INVARIANTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `DISPATCH_BOUNDARY_INVARIANTS has exactly 7 entries`() {
        assertEquals(7, AndroidCrossDeviceDispatchBoundaryContract.DISPATCH_BOUNDARY_INVARIANTS.size)
    }

    @Test fun `DISPATCH_BOUNDARY_INVARIANTS all have non-blank ids`() {
        AndroidCrossDeviceDispatchBoundaryContract.DISPATCH_BOUNDARY_INVARIANTS.forEach {
            assertTrue("INV id should be non-blank: ${it.id}", it.id.isNotBlank())
        }
    }

    @Test fun `DISPATCH_BOUNDARY_INVARIANTS all have non-blank summaries`() {
        AndroidCrossDeviceDispatchBoundaryContract.DISPATCH_BOUNDARY_INVARIANTS.forEach {
            assertTrue("INV summary should be non-blank: ${it.id}", it.summary.isNotBlank())
        }
    }

    @Test fun `DISPATCH_BOUNDARY_INVARIANTS ids are unique`() {
        val ids = AndroidCrossDeviceDispatchBoundaryContract.DISPATCH_BOUNDARY_INVARIANTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `DISPATCH_BOUNDARY_INVARIANTS all ids start with INV-DB-`() {
        AndroidCrossDeviceDispatchBoundaryContract.DISPATCH_BOUNDARY_INVARIANTS.forEach {
            assertTrue("INV id should start with INV-DB-: ${it.id}", it.id.startsWith("INV-DB-"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `StabilizationBaseline registers android-cross-device-dispatch-boundary-contract as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.entries.firstOrNull {
            it.surfaceId == "android-cross-device-dispatch-boundary-contract"
        }
        assertNotNull("android-cross-device-dispatch-boundary-contract should be in StabilizationBaseline", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `StabilizationBaseline android-cross-device-dispatch-boundary-contract has introducedPr 95`() {
        val entry = StabilizationBaseline.entries.firstOrNull {
            it.surfaceId == "android-cross-device-dispatch-boundary-contract"
        }
        assertNotNull(entry)
        assertEquals(95, entry!!.introducedPr)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. SCHEMA_VERSION
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidCrossDeviceDispatchBoundaryContract.SCHEMA_VERSION)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. KEY_ constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `KEY_DISPATCH_BOUNDARY_CLASS is non-blank`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_CLASS.isNotBlank())
    }

    @Test fun `KEY_DISPATCH_PATH_CONSUMPTION_KIND is non-blank`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_PATH_CONSUMPTION_KIND.isNotBlank())
    }

    @Test fun `KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION is non-blank`() {
        assertTrue(AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION.isNotBlank())
    }

    @Test fun `KEY_ constants are all distinct`() {
        val keys = setOf(
            AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_CLASS,
            AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_PATH_CONSUMPTION_KIND,
            AndroidCrossDeviceDispatchBoundaryContract.KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION
        )
        assertEquals(3, keys.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. V2_DISPATCH_PATH_MAP
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `V2_DISPATCH_PATH_MAP contains entries for all 5 DispatchBoundaryClass values`() {
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.values().size,
            AndroidCrossDeviceDispatchBoundaryContract.V2_DISPATCH_PATH_MAP.size
        )
    }

    @Test fun `V2_DISPATCH_PATH_MAP canonical_cross_device maps to canonical_dispatch`() {
        assertEquals(
            "canonical_dispatch",
            AndroidCrossDeviceDispatchBoundaryContract.V2_DISPATCH_PATH_MAP[
                AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.CANONICAL_CROSS_DEVICE
            ]
        )
    }

    @Test fun `V2_DISPATCH_PATH_MAP not_cross_device maps to null`() {
        assertNull(
            AndroidCrossDeviceDispatchBoundaryContract.V2_DISPATCH_PATH_MAP[
                AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE
            ]
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14–15. INV-DB-02 / INV-DB-05 semantic invariants
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `INV-DB-02 NOT_CROSS_DEVICE has isCanonicalPath=false and isFallbackPath=false`() {
        val v = AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.NOT_CROSS_DEVICE
        assertFalse(v.isCanonicalPath)
        assertFalse(v.isFallbackPath)
    }

    @Test fun `INV-DB-05 COMPAT_FALLBACK has isCanonicalPath=false`() {
        assertFalse(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK.isCanonicalPath)
    }

    @Test fun `INV-DB-05 LEGACY_BYPASS has isCanonicalPath=false`() {
        assertFalse(AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS.isCanonicalPath)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 16–17. 优先级验证
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `priority - isLegacyBypassEntry beats isLegacyCompatRemapped when both true`() {
        val result = derive(defaultInput(isLegacyBypassEntry = true, isLegacyCompatRemapped = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.LEGACY_BYPASS,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `priority - isLegacyCompatRemapped beats isAgentBridgeFallback when both true`() {
        val result = derive(defaultInput(isLegacyCompatRemapped = true, isAgentBridgeFallback = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    @Test fun `priority - isAgentBridgeCompatEntry beats isAgentBridgeFallback when both true`() {
        val result = derive(defaultInput(isAgentBridgeCompatEntry = true, isAgentBridgeFallback = true))
        assertEquals(
            AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.COMPAT_FALLBACK,
            result.dispatchBoundaryClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 18. Wire 值稳定性 — 所有 wire 值匹配预期字符串集合
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `DispatchBoundaryClass all wire values are stable`() {
        val expected = setOf(
            "canonical_cross_device",
            "controlled_canonical_fallback",
            "compat_fallback",
            "legacy_bypass",
            "not_cross_device"
        )
        val actual = AndroidCrossDeviceDispatchBoundaryContract.DispatchBoundaryClass.values()
            .map { it.wireValue }.toSet()
        assertEquals(expected, actual)
    }

    @Test fun `DispatchPathConsumptionKind all wire values are stable`() {
        val expected = setOf("inbound_execution", "local_fallback_execution", "none")
        val actual = AndroidCrossDeviceDispatchBoundaryContract.DispatchPathConsumptionKind.values()
            .map { it.wireValue }.toSet()
        assertEquals(expected, actual)
    }
}
