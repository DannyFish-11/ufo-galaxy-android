package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-4Android — AndroidBoundaryReliabilityContract 合约单元测试。
 *
 * 验证以下验收准则：
 *
 *  1. **AsyncScopeClass** — 4 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isBounded / hasExplicitTimeout 语义正确。
 *  2. **SourceFieldCoverageClass** — 3 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isReliable 语义正确。
 *  3. **AuthorityBoundaryCheckMode** — 4 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isExplicitlyChecked 语义正确。
 *  4. **classifyAsyncScope()** — 推导逻辑覆盖全部 4 种输出路径：
 *     - hasTimeoutGuard=true → TIMEOUT_GUARDED（优先级最高）
 *     - isLifecycleBound=true, hasTimeoutGuard=false → LIFECYCLE_BOUND
 *     - isServiceScoped=true, 其余 false → SERVICE_SCOPED
 *     - 全部 false → DETACHED_FIRE_AND_FORGET
 *  5. **classifySourceFieldCoverage()** — 推导逻辑覆盖全部 3 种输出路径：
 *     - deviceId=null → ABSENT
 *     - 执行事件路径（taskId 非 null）：task_id + source_component 均存在 → COMPLETE
 *     - 执行事件路径：source_component 缺失 → PARTIAL
 *     - 快照路径（taskId=null）：runtimeSessionId 存在 → COMPLETE
 *     - 快照路径：runtimeSessionId 缺失 → PARTIAL
 *  6. **classifyAuthorityBoundaryCheckMode()** — 推导逻辑正确：
 *     - hasExplicitContractGate=true → EXPLICIT_CONTRACT_GATE（优先级最高）
 *     - hasGovernanceContext=true, hasExplicitContractGate=false → GOVERNANCE_VALIDATED
 *     - 全部 false → ASSUMED_IMPLICIT
 *  7. **derive()** — 输入输出关联正确：各字段来自对应分类函数的推导结果。
 *  8. **BoundaryReliabilitySnapshot.toWireMap()** — 所有 4 个 key 均存在；值类型正确。
 *  9. **BOUNDARY_RELIABILITY_INVARIANTS** — 共 8 条；全部非空；无重复 ID。
 * 10. **StabilizationBaseline** — "android-boundary-reliability-contract" 已注册为 CANONICAL_STABLE。
 * 11. **SCHEMA_VERSION** — 值为 "1"。
 * 12. **KEY_ 常量** — 全部非空；互不相同。
 * 13. **REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS** — 包含 device_id、source_component、task_id、event_id。
 * 14. **REQUIRED_SNAPSHOT_SOURCE_FIELDS** — 包含 device_id、runtime_session_id。
 * 15. **INV-BR-01**：DETACHED_FIRE_AND_FORGET 的 isBounded=false；其余均 isBounded=true。
 * 16. **INV-BR-02**：ABSENT 的 isReliable=false；其余均 isReliable=true。
 * 17. **INV-BR-03**：EXPLICIT_CONTRACT_GATE 和 GOVERNANCE_VALIDATED 的 isExplicitlyChecked=true。
 */
class Pr4AndroidBoundaryReliabilityContractTest {

    // ── 测试辅助 ────────────────────────────────────────────────────────────────

    private fun deriveAsyncScope(
        isServiceScoped: Boolean = false,
        hasTimeoutGuard: Boolean = false,
        isLifecycleBound: Boolean = false
    ) = AndroidBoundaryReliabilityContract.classifyAsyncScope(
        isServiceScoped = isServiceScoped,
        hasTimeoutGuard = hasTimeoutGuard,
        isLifecycleBound = isLifecycleBound
    )

    private fun deriveCoverage(
        deviceId: String? = "device-123",
        sourceComponent: String? = "GalaxyConnectionService",
        taskId: String? = "task-456",
        runtimeSessionId: String? = "session-789"
    ) = AndroidBoundaryReliabilityContract.classifySourceFieldCoverage(
        deviceId = deviceId,
        sourceComponent = sourceComponent,
        taskId = taskId,
        runtimeSessionId = runtimeSessionId
    )

    private fun deriveAuthority(
        hasGovernanceContext: Boolean = false,
        hasExplicitContractGate: Boolean = false
    ) = AndroidBoundaryReliabilityContract.classifyAuthorityBoundaryCheckMode(
        hasGovernanceContext = hasGovernanceContext,
        hasExplicitContractGate = hasExplicitContractGate
    )

    private fun deriveSnapshot(
        isServiceScoped: Boolean = true,
        hasTimeoutGuard: Boolean = false,
        isLifecycleBound: Boolean = false,
        deviceId: String? = "device-123",
        sourceComponent: String? = "GalaxyConnectionService",
        taskId: String? = "task-456",
        runtimeSessionId: String? = "session-789",
        hasGovernanceContext: Boolean = true,
        hasExplicitContractGate: Boolean = false
    ) = AndroidBoundaryReliabilityContract.derive(
        AndroidBoundaryReliabilityContract.BoundaryReliabilityDerivationInput(
            isServiceScoped = isServiceScoped,
            hasTimeoutGuard = hasTimeoutGuard,
            isLifecycleBound = isLifecycleBound,
            deviceId = deviceId,
            sourceComponent = sourceComponent,
            taskId = taskId,
            runtimeSessionId = runtimeSessionId,
            hasGovernanceContext = hasGovernanceContext,
            hasExplicitContractGate = hasExplicitContractGate
        )
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 1. AsyncScopeClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `AsyncScopeClass has 4 values`() {
        assertEquals(4, AndroidBoundaryReliabilityContract.AsyncScopeClass.entries.size)
    }

    @Test fun `AsyncScopeClass SERVICE_SCOPED wireValue is service_scoped`() {
        assertEquals("service_scoped", AndroidBoundaryReliabilityContract.AsyncScopeClass.SERVICE_SCOPED.wireValue)
    }

    @Test fun `AsyncScopeClass TIMEOUT_GUARDED wireValue is timeout_guarded`() {
        assertEquals("timeout_guarded", AndroidBoundaryReliabilityContract.AsyncScopeClass.TIMEOUT_GUARDED.wireValue)
    }

    @Test fun `AsyncScopeClass LIFECYCLE_BOUND wireValue is lifecycle_bound`() {
        assertEquals("lifecycle_bound", AndroidBoundaryReliabilityContract.AsyncScopeClass.LIFECYCLE_BOUND.wireValue)
    }

    @Test fun `AsyncScopeClass DETACHED_FIRE_AND_FORGET wireValue is detached_fire_and_forget`() {
        assertEquals("detached_fire_and_forget", AndroidBoundaryReliabilityContract.AsyncScopeClass.DETACHED_FIRE_AND_FORGET.wireValue)
    }

    @Test fun `AsyncScopeClass fromWireValue returns correct enum for all values`() {
        for (v in AndroidBoundaryReliabilityContract.AsyncScopeClass.entries) {
            assertEquals(v, AndroidBoundaryReliabilityContract.AsyncScopeClass.fromWireValue(v.wireValue))
        }
    }

    @Test fun `AsyncScopeClass fromWireValue returns null for unknown value`() {
        assertNull(AndroidBoundaryReliabilityContract.AsyncScopeClass.fromWireValue("unknown_scope"))
    }

    @Test fun `AsyncScopeClass DETACHED_FIRE_AND_FORGET isBounded is false`() {
        assertFalse(AndroidBoundaryReliabilityContract.AsyncScopeClass.DETACHED_FIRE_AND_FORGET.isBounded)
    }

    @Test fun `AsyncScopeClass SERVICE_SCOPED isBounded is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.AsyncScopeClass.SERVICE_SCOPED.isBounded)
    }

    @Test fun `AsyncScopeClass TIMEOUT_GUARDED hasExplicitTimeout is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.AsyncScopeClass.TIMEOUT_GUARDED.hasExplicitTimeout)
    }

    @Test fun `AsyncScopeClass SERVICE_SCOPED hasExplicitTimeout is false`() {
        assertFalse(AndroidBoundaryReliabilityContract.AsyncScopeClass.SERVICE_SCOPED.hasExplicitTimeout)
    }

    @Test fun `AsyncScopeClass ALL_WIRE_VALUES contains all expected values`() {
        val expected = setOf("service_scoped", "timeout_guarded", "lifecycle_bound", "detached_fire_and_forget")
        assertEquals(expected, AndroidBoundaryReliabilityContract.AsyncScopeClass.ALL_WIRE_VALUES)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. SourceFieldCoverageClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `SourceFieldCoverageClass has 3 values`() {
        assertEquals(3, AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.entries.size)
    }

    @Test fun `SourceFieldCoverageClass COMPLETE wireValue is complete`() {
        assertEquals("complete", AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.COMPLETE.wireValue)
    }

    @Test fun `SourceFieldCoverageClass PARTIAL wireValue is partial`() {
        assertEquals("partial", AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.PARTIAL.wireValue)
    }

    @Test fun `SourceFieldCoverageClass ABSENT wireValue is absent`() {
        assertEquals("absent", AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT.wireValue)
    }

    @Test fun `SourceFieldCoverageClass fromWireValue returns correct enum for all values`() {
        for (v in AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.entries) {
            assertEquals(v, AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.fromWireValue(v.wireValue))
        }
    }

    @Test fun `SourceFieldCoverageClass fromWireValue returns null for unknown value`() {
        assertNull(AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.fromWireValue("unknown_coverage"))
    }

    @Test fun `SourceFieldCoverageClass ABSENT isReliable is false`() {
        assertFalse(AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT.isReliable)
    }

    @Test fun `SourceFieldCoverageClass COMPLETE isReliable is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.COMPLETE.isReliable)
    }

    @Test fun `SourceFieldCoverageClass PARTIAL isReliable is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.PARTIAL.isReliable)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. AuthorityBoundaryCheckMode enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `AuthorityBoundaryCheckMode has 4 values`() {
        assertEquals(4, AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.entries.size)
    }

    @Test fun `AuthorityBoundaryCheckMode EXPLICIT_CONTRACT_GATE wireValue is explicit_contract_gate`() {
        assertEquals("explicit_contract_gate", AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE.wireValue)
    }

    @Test fun `AuthorityBoundaryCheckMode GOVERNANCE_VALIDATED wireValue is governance_validated`() {
        assertEquals("governance_validated", AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED.wireValue)
    }

    @Test fun `AuthorityBoundaryCheckMode AUDIT_TRAIL_ONLY wireValue is audit_trail_only`() {
        assertEquals("audit_trail_only", AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.AUDIT_TRAIL_ONLY.wireValue)
    }

    @Test fun `AuthorityBoundaryCheckMode ASSUMED_IMPLICIT wireValue is assumed_implicit`() {
        assertEquals("assumed_implicit", AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.ASSUMED_IMPLICIT.wireValue)
    }

    @Test fun `AuthorityBoundaryCheckMode fromWireValue returns correct enum for all values`() {
        for (v in AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.entries) {
            assertEquals(v, AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.fromWireValue(v.wireValue))
        }
    }

    @Test fun `AuthorityBoundaryCheckMode EXPLICIT_CONTRACT_GATE isExplicitlyChecked is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE.isExplicitlyChecked)
    }

    @Test fun `AuthorityBoundaryCheckMode GOVERNANCE_VALIDATED isExplicitlyChecked is true`() {
        assertTrue(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED.isExplicitlyChecked)
    }

    @Test fun `AuthorityBoundaryCheckMode AUDIT_TRAIL_ONLY isExplicitlyChecked is false`() {
        assertFalse(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.AUDIT_TRAIL_ONLY.isExplicitlyChecked)
    }

    @Test fun `AuthorityBoundaryCheckMode ASSUMED_IMPLICIT isExplicitlyChecked is false`() {
        assertFalse(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.ASSUMED_IMPLICIT.isExplicitlyChecked)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. classifyAsyncScope() derivation coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classifyAsyncScope hasTimeoutGuard=true returns TIMEOUT_GUARDED`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.TIMEOUT_GUARDED,
            deriveAsyncScope(hasTimeoutGuard = true)
        )
    }

    @Test fun `classifyAsyncScope hasTimeoutGuard=true overrides isServiceScoped=true`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.TIMEOUT_GUARDED,
            deriveAsyncScope(isServiceScoped = true, hasTimeoutGuard = true)
        )
    }

    @Test fun `classifyAsyncScope isLifecycleBound=true returns LIFECYCLE_BOUND when no timeout`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.LIFECYCLE_BOUND,
            deriveAsyncScope(isLifecycleBound = true, hasTimeoutGuard = false)
        )
    }

    @Test fun `classifyAsyncScope isServiceScoped=true returns SERVICE_SCOPED when no timeout and not lifecycle`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.SERVICE_SCOPED,
            deriveAsyncScope(isServiceScoped = true, hasTimeoutGuard = false, isLifecycleBound = false)
        )
    }

    @Test fun `classifyAsyncScope all false returns DETACHED_FIRE_AND_FORGET`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.DETACHED_FIRE_AND_FORGET,
            deriveAsyncScope(isServiceScoped = false, hasTimeoutGuard = false, isLifecycleBound = false)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. classifySourceFieldCoverage() derivation coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classifySourceFieldCoverage deviceId=null returns ABSENT`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT,
            deriveCoverage(deviceId = null)
        )
    }

    @Test fun `classifySourceFieldCoverage deviceId=blank returns ABSENT`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT,
            deriveCoverage(deviceId = "")
        )
    }

    @Test fun `classifySourceFieldCoverage execution event path with all fields returns COMPLETE`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.COMPLETE,
            deriveCoverage(
                deviceId = "device-123",
                sourceComponent = "GalaxyConnectionService",
                taskId = "task-456",
                runtimeSessionId = "session-789"
            )
        )
    }

    @Test fun `classifySourceFieldCoverage execution event path source_component missing returns PARTIAL`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.PARTIAL,
            deriveCoverage(
                deviceId = "device-123",
                sourceComponent = null,
                taskId = "task-456",
                runtimeSessionId = "session-789"
            )
        )
    }

    @Test fun `classifySourceFieldCoverage execution event path source_component blank returns PARTIAL`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.PARTIAL,
            deriveCoverage(
                deviceId = "device-123",
                sourceComponent = "",
                taskId = "task-456",
                runtimeSessionId = "session-789"
            )
        )
    }

    @Test fun `classifySourceFieldCoverage snapshot path with runtimeSessionId returns COMPLETE`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.COMPLETE,
            deriveCoverage(
                deviceId = "device-123",
                sourceComponent = null,
                taskId = null,
                runtimeSessionId = "session-789"
            )
        )
    }

    @Test fun `classifySourceFieldCoverage snapshot path without runtimeSessionId returns PARTIAL`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.PARTIAL,
            deriveCoverage(
                deviceId = "device-123",
                sourceComponent = null,
                taskId = null,
                runtimeSessionId = null
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. classifyAuthorityBoundaryCheckMode() derivation coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `classifyAuthorityBoundaryCheckMode hasExplicitContractGate=true returns EXPLICIT_CONTRACT_GATE`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE,
            deriveAuthority(hasGovernanceContext = true, hasExplicitContractGate = true)
        )
    }

    @Test fun `classifyAuthorityBoundaryCheckMode hasGovernanceContext=true returns GOVERNANCE_VALIDATED`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED,
            deriveAuthority(hasGovernanceContext = true, hasExplicitContractGate = false)
        )
    }

    @Test fun `classifyAuthorityBoundaryCheckMode all false returns ASSUMED_IMPLICIT`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.ASSUMED_IMPLICIT,
            deriveAuthority(hasGovernanceContext = false, hasExplicitContractGate = false)
        )
    }

    @Test fun `classifyAuthorityBoundaryCheckMode hasExplicitContractGate overrides governance context`() {
        assertEquals(
            AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE,
            deriveAuthority(hasGovernanceContext = false, hasExplicitContractGate = true)
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. derive() integration coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `derive returns SERVICE_SCOPED for standard execution event emission`() {
        val snapshot = deriveSnapshot(isServiceScoped = true, hasTimeoutGuard = false)
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.SERVICE_SCOPED,
            snapshot.asyncScopeClass
        )
    }

    @Test fun `derive returns TIMEOUT_GUARDED when hasTimeoutGuard=true`() {
        val snapshot = deriveSnapshot(hasTimeoutGuard = true)
        assertEquals(
            AndroidBoundaryReliabilityContract.AsyncScopeClass.TIMEOUT_GUARDED,
            snapshot.asyncScopeClass
        )
    }

    @Test fun `derive returns COMPLETE for fully populated execution event`() {
        val snapshot = deriveSnapshot(
            deviceId = "dev-1",
            sourceComponent = "GalaxyConnectionService",
            taskId = "task-1"
        )
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.COMPLETE,
            snapshot.sourceFieldCoverageClass
        )
    }

    @Test fun `derive returns GOVERNANCE_VALIDATED when governance context present`() {
        val snapshot = deriveSnapshot(hasGovernanceContext = true, hasExplicitContractGate = false)
        assertEquals(
            AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED,
            snapshot.authorityBoundaryCheckMode
        )
    }

    @Test fun `derive returns ABSENT when deviceId is null`() {
        val snapshot = deriveSnapshot(deviceId = null)
        assertEquals(
            AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT,
            snapshot.sourceFieldCoverageClass
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. BoundaryReliabilitySnapshot.toWireMap() coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `toWireMap contains async_scope_class key`() {
        val map = deriveSnapshot().toWireMap()
        assertTrue(map.containsKey(AndroidBoundaryReliabilityContract.KEY_ASYNC_SCOPE_CLASS))
    }

    @Test fun `toWireMap contains source_field_coverage_class key`() {
        val map = deriveSnapshot().toWireMap()
        assertTrue(map.containsKey(AndroidBoundaryReliabilityContract.KEY_SOURCE_FIELD_COVERAGE_CLASS))
    }

    @Test fun `toWireMap contains authority_boundary_check_mode key`() {
        val map = deriveSnapshot().toWireMap()
        assertTrue(map.containsKey(AndroidBoundaryReliabilityContract.KEY_AUTHORITY_BOUNDARY_CHECK_MODE))
    }

    @Test fun `toWireMap contains boundary_reliability_schema_version key`() {
        val map = deriveSnapshot().toWireMap()
        assertTrue(map.containsKey(AndroidBoundaryReliabilityContract.KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION))
    }

    @Test fun `toWireMap has 4 entries`() {
        assertEquals(4, deriveSnapshot().toWireMap().size)
    }

    @Test fun `toWireMap async_scope_class value is a String`() {
        val value = deriveSnapshot().toWireMap()[AndroidBoundaryReliabilityContract.KEY_ASYNC_SCOPE_CLASS]
        assertTrue(value is String)
    }

    @Test fun `toWireMap source_field_coverage_class value is a String`() {
        val value = deriveSnapshot().toWireMap()[AndroidBoundaryReliabilityContract.KEY_SOURCE_FIELD_COVERAGE_CLASS]
        assertTrue(value is String)
    }

    @Test fun `toWireMap boundary_reliability_schema_version equals SCHEMA_VERSION`() {
        val value = deriveSnapshot().toWireMap()[AndroidBoundaryReliabilityContract.KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION]
        assertEquals(AndroidBoundaryReliabilityContract.SCHEMA_VERSION, value)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. BOUNDARY_RELIABILITY_INVARIANTS coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS has 8 entries`() {
        assertEquals(8, AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS.size)
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS all ids are non-blank`() {
        for (inv in AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS) {
            assertTrue("id blank: ${inv.id}", inv.id.isNotBlank())
        }
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS all descriptions are non-blank`() {
        for (inv in AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS) {
            assertTrue("description blank for ${inv.id}", inv.description.isNotBlank())
        }
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS ids are unique`() {
        val ids = AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS ids follow INV-BR-NN format`() {
        for (inv in AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS) {
            assertTrue("id does not match INV-BR-NN: ${inv.id}", inv.id.matches(Regex("INV-BR-\\d{2}")))
        }
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS contains INV-BR-01`() {
        val ids = AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS.map { it.id }
        assertTrue(ids.contains("INV-BR-01"))
    }

    @Test fun `BOUNDARY_RELIABILITY_INVARIANTS contains INV-BR-08`() {
        val ids = AndroidBoundaryReliabilityContract.BOUNDARY_RELIABILITY_INVARIANTS.map { it.id }
        assertTrue(ids.contains("INV-BR-08"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `android-boundary-reliability-contract is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("android-boundary-reliability-contract"))
    }

    @Test fun `android-boundary-reliability-contract is CANONICAL_STABLE`() {
        val e = StabilizationBaseline.forId("android-boundary-reliability-contract")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
    }

    @Test fun `android-boundary-reliability-contract has EXTEND guidance`() {
        val e = StabilizationBaseline.forId("android-boundary-reliability-contract")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e!!.extensionGuidance)
    }

    @Test fun `android-boundary-reliability-contract introducedPr is 94`() {
        val e = StabilizationBaseline.forId("android-boundary-reliability-contract")
        assertEquals(94, e!!.introducedPr)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. SCHEMA_VERSION
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `SCHEMA_VERSION is 1`() {
        assertEquals("1", AndroidBoundaryReliabilityContract.SCHEMA_VERSION)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. KEY_ constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `KEY_ASYNC_SCOPE_CLASS is non-blank`() {
        assertTrue(AndroidBoundaryReliabilityContract.KEY_ASYNC_SCOPE_CLASS.isNotBlank())
    }

    @Test fun `KEY_SOURCE_FIELD_COVERAGE_CLASS is non-blank`() {
        assertTrue(AndroidBoundaryReliabilityContract.KEY_SOURCE_FIELD_COVERAGE_CLASS.isNotBlank())
    }

    @Test fun `KEY_AUTHORITY_BOUNDARY_CHECK_MODE is non-blank`() {
        assertTrue(AndroidBoundaryReliabilityContract.KEY_AUTHORITY_BOUNDARY_CHECK_MODE.isNotBlank())
    }

    @Test fun `KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION is non-blank`() {
        assertTrue(AndroidBoundaryReliabilityContract.KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION.isNotBlank())
    }

    @Test fun `all KEY_ constants are distinct`() {
        val keys = setOf(
            AndroidBoundaryReliabilityContract.KEY_ASYNC_SCOPE_CLASS,
            AndroidBoundaryReliabilityContract.KEY_SOURCE_FIELD_COVERAGE_CLASS,
            AndroidBoundaryReliabilityContract.KEY_AUTHORITY_BOUNDARY_CHECK_MODE,
            AndroidBoundaryReliabilityContract.KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION
        )
        assertEquals(4, keys.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS contains device_id`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS.contains("device_id"))
    }

    @Test fun `REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS contains source_component`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS.contains("source_component"))
    }

    @Test fun `REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS contains task_id`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS.contains("task_id"))
    }

    @Test fun `REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS contains event_id`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS.contains("event_id"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. REQUIRED_SNAPSHOT_SOURCE_FIELDS
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `REQUIRED_SNAPSHOT_SOURCE_FIELDS contains device_id`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_SNAPSHOT_SOURCE_FIELDS.contains("device_id"))
    }

    @Test fun `REQUIRED_SNAPSHOT_SOURCE_FIELDS contains runtime_session_id`() {
        assertTrue(AndroidBoundaryReliabilityContract.REQUIRED_SNAPSHOT_SOURCE_FIELDS.contains("runtime_session_id"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 15. INV-BR-01: isBounded semantics
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `INV-BR-01 only DETACHED_FIRE_AND_FORGET has isBounded=false`() {
        val unbounded = AndroidBoundaryReliabilityContract.AsyncScopeClass.entries.filter { !it.isBounded }
        assertEquals(
            listOf(AndroidBoundaryReliabilityContract.AsyncScopeClass.DETACHED_FIRE_AND_FORGET),
            unbounded
        )
    }

    @Test fun `INV-BR-01 SERVICE_SCOPED LIFECYCLE_BOUND TIMEOUT_GUARDED all have isBounded=true`() {
        val bounded = AndroidBoundaryReliabilityContract.AsyncScopeClass.entries.filter { it.isBounded }
        assertEquals(3, bounded.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 16. INV-BR-02: isReliable semantics
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `INV-BR-02 only ABSENT has isReliable=false`() {
        val unreliable = AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.entries.filter { !it.isReliable }
        assertEquals(
            listOf(AndroidBoundaryReliabilityContract.SourceFieldCoverageClass.ABSENT),
            unreliable
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 17. INV-BR-03: isExplicitlyChecked semantics
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `INV-BR-03 EXPLICIT_CONTRACT_GATE and GOVERNANCE_VALIDATED have isExplicitlyChecked=true`() {
        val explicit = AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.entries
            .filter { it.isExplicitlyChecked }
        assertEquals(2, explicit.size)
        assertTrue(explicit.contains(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE))
        assertTrue(explicit.contains(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED))
    }

    @Test fun `INV-BR-03 AUDIT_TRAIL_ONLY and ASSUMED_IMPLICIT have isExplicitlyChecked=false`() {
        val implicit = AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.entries
            .filter { !it.isExplicitlyChecked }
        assertEquals(2, implicit.size)
        assertTrue(implicit.contains(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.AUDIT_TRAIL_ONLY))
        assertTrue(implicit.contains(AndroidBoundaryReliabilityContract.AuthorityBoundaryCheckMode.ASSUMED_IMPLICIT))
    }
}
