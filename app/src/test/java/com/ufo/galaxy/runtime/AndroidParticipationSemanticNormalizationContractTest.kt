package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-3Android — AndroidParticipationSemanticNormalizationContract 合约单元测试。
 *
 * 验证以下验收准则：
 *
 *  1. **ParticipationModeClass** — 8 个分类值；wireValue 稳定；fromWireValue 正确；
 *     isActivelyExecuting / isDistributedParticipant / acceptsNewDispatch 语义正确。
 *  2. **LocalExecutionActivityKind** — 4 个枚举值；wireValue 稳定；fromWireValue 正确；
 *     isLocalExecution / isDistributedPathActive 语义正确。
 *  3. **derive()** — 推导逻辑覆盖全部 8 种 ParticipationModeClass 输出路径：
 *     - crossDeviceEnabled=false → UNAVAILABLE
 *     - takeoverStateWire="active" → TAKEOVER_EXECUTING
 *     - runtimeConstrained=true → CONSTRAINED
 *     - governanceBlocked=true → DEGRADED
 *     - localCapabilityState=degraded → DEGRADED
 *     - distributedParticipant=true → DISTRIBUTED_EXECUTING
 *     - delegatedExecutionActive=true → DISTRIBUTED_EXECUTING
 *     - localModeActive=true, executionBusy=true → LOCAL_ONLY_EXECUTING
 *     - localModeActive=true, executionBusy=false → LOCAL_ONLY_IDLE
 *     - dispatchEligible=true, crossDeviceEnabled=true → CROSS_DEVICE_READY
 *     - runtimeDeferred=true → CONSTRAINED
 *  4. **LocalExecutionActivityKind derive** — 推导逻辑正确：
 *     - takeover_active + executionBusy → TAKEOVER_PARTICIPANT
 *     - distributedParticipant + executionBusy → DELEGATED_PARTICIPANT
 *     - executionBusy only → LOCAL_ASSISTIVE
 *     - no execution → NONE
 *  5. **localExecutionActive** — 与 localExecutionActivityKind.isLocalExecution 一致（INV-PSN-03）。
 *  6. **modeSignalAmbiguityResolved** — 当 localModeActive 且 crossDeviceEnabled 同时为 true 时为 false。
 *  7. **NORMALIZATION_INVARIANTS** — 共 10 条；全部非空；无重复。
 *  8. **NormalizationSnapshot.toWireMap()** — 所有 5 个键均存在；值类型正确。
 *  9. **StabilizationBaseline** — "android-participation-semantic-normalization" 已注册为 CANONICAL_STABLE。
 * 10. **SCHEMA_VERSION** — 值为 "1"。
 * 11. **KEY_ 常量** — 全部非空；互不相同。
 * 12. **INV-PSN-01**：participationModeClass 永不为 UNAVAILABLE 除非 crossDeviceEnabled=false 或无路径匹配。
 * 13. **INV-PSN-06**：CROSS_DEVICE_READY 时 localExecutionActive=false。
 * 14. **INV-PSN-07**：acceptsNewDispatch=true 当且仅当 CROSS_DEVICE_READY。
 * 15. **INV-PSN-08**：isDistributedParticipant=true 当且仅当 DISTRIBUTED_EXECUTING 或 TAKEOVER_EXECUTING。
 */
class AndroidParticipationSemanticNormalizationContractTest {

    // ── 测试辅助：构建默认最小有效输入 ─────────────────────────────────────────

    private fun baselineInput(
        localModeActive: Boolean = false,
        executionBusy: Boolean = false,
        distributedParticipant: Boolean = false,
        delegatedExecutionActive: Boolean = false,
        takeoverStateWire: String? = "inactive",
        runtimeConstrained: Boolean = false,
        runtimeDeferred: Boolean = false,
        governanceBlocked: Boolean = false,
        crossDeviceEnabled: Boolean = true,
        dispatchEligible: Boolean = true,
        localCapabilityStateWire: String? = "full"
    ) = AndroidParticipationSemanticNormalizationContract.NormalizationDerivationInput(
        localModeActive = localModeActive,
        executionBusy = executionBusy,
        distributedParticipant = distributedParticipant,
        delegatedExecutionActive = delegatedExecutionActive,
        takeoverStateWire = takeoverStateWire,
        runtimeConstrained = runtimeConstrained,
        runtimeDeferred = runtimeDeferred,
        governanceBlocked = governanceBlocked,
        crossDeviceEnabled = crossDeviceEnabled,
        dispatchEligible = dispatchEligible,
        localCapabilityStateWire = localCapabilityStateWire
    )

    private fun derive(
        localModeActive: Boolean = false,
        executionBusy: Boolean = false,
        distributedParticipant: Boolean = false,
        delegatedExecutionActive: Boolean = false,
        takeoverStateWire: String? = "inactive",
        runtimeConstrained: Boolean = false,
        runtimeDeferred: Boolean = false,
        governanceBlocked: Boolean = false,
        crossDeviceEnabled: Boolean = true,
        dispatchEligible: Boolean = true,
        localCapabilityStateWire: String? = "full"
    ) = AndroidParticipationSemanticNormalizationContract.derive(
        baselineInput(
            localModeActive = localModeActive,
            executionBusy = executionBusy,
            distributedParticipant = distributedParticipant,
            delegatedExecutionActive = delegatedExecutionActive,
            takeoverStateWire = takeoverStateWire,
            runtimeConstrained = runtimeConstrained,
            runtimeDeferred = runtimeDeferred,
            governanceBlocked = governanceBlocked,
            crossDeviceEnabled = crossDeviceEnabled,
            dispatchEligible = dispatchEligible,
            localCapabilityStateWire = localCapabilityStateWire
        )
    )

    // ── ParticipationModeClass wire values ──────────────────────────────────

    @Test
    fun `ParticipationModeClass LOCAL_ONLY_IDLE wireValue 为 local_only_idle`() {
        assertEquals("local_only_idle",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_IDLE.wireValue)
    }

    @Test
    fun `ParticipationModeClass LOCAL_ONLY_EXECUTING wireValue 为 local_only_executing`() {
        assertEquals("local_only_executing",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_EXECUTING.wireValue)
    }

    @Test
    fun `ParticipationModeClass CROSS_DEVICE_READY wireValue 为 cross_device_ready`() {
        assertEquals("cross_device_ready",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CROSS_DEVICE_READY.wireValue)
    }

    @Test
    fun `ParticipationModeClass DISTRIBUTED_EXECUTING wireValue 为 distributed_executing`() {
        assertEquals("distributed_executing",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DISTRIBUTED_EXECUTING.wireValue)
    }

    @Test
    fun `ParticipationModeClass TAKEOVER_EXECUTING wireValue 为 takeover_executing`() {
        assertEquals("takeover_executing",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.TAKEOVER_EXECUTING.wireValue)
    }

    @Test
    fun `ParticipationModeClass DEGRADED wireValue 为 degraded`() {
        assertEquals("degraded",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DEGRADED.wireValue)
    }

    @Test
    fun `ParticipationModeClass CONSTRAINED wireValue 为 constrained`() {
        assertEquals("constrained",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CONSTRAINED.wireValue)
    }

    @Test
    fun `ParticipationModeClass UNAVAILABLE wireValue 为 unavailable`() {
        assertEquals("unavailable",
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.UNAVAILABLE.wireValue)
    }

    @Test
    fun `ParticipationModeClass ALL_WIRE_VALUES 恰好包含 8 条`() {
        assertEquals(8,
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ParticipationModeClass fromWireValue 对每个值返回正确分类`() {
        AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.entries.forEach { cls ->
            assertEquals(cls,
                AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.fromWireValue(cls.wireValue))
        }
    }

    @Test
    fun `ParticipationModeClass fromWireValue 对未知值返回 null`() {
        assertNull(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.fromWireValue("unknown_value"))
    }

    // ── ParticipationModeClass 语义属性 ─────────────────────────────────────

    @Test
    fun `LOCAL_ONLY_IDLE isActivelyExecuting=false, isDistributedParticipant=false, acceptsNewDispatch=false`() {
        val cls = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_IDLE
        assertFalse(cls.isActivelyExecuting)
        assertFalse(cls.isDistributedParticipant)
        assertFalse(cls.acceptsNewDispatch)
    }

    @Test
    fun `LOCAL_ONLY_EXECUTING isActivelyExecuting=true, isDistributedParticipant=false, acceptsNewDispatch=false`() {
        val cls = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_EXECUTING
        assertTrue(cls.isActivelyExecuting)
        assertFalse(cls.isDistributedParticipant)
        assertFalse(cls.acceptsNewDispatch)
    }

    @Test
    fun `CROSS_DEVICE_READY isActivelyExecuting=false, isDistributedParticipant=false, acceptsNewDispatch=true`() {
        val cls = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CROSS_DEVICE_READY
        assertFalse(cls.isActivelyExecuting)
        assertFalse(cls.isDistributedParticipant)
        assertTrue(cls.acceptsNewDispatch)
    }

    @Test
    fun `DISTRIBUTED_EXECUTING isActivelyExecuting=true, isDistributedParticipant=true, acceptsNewDispatch=false`() {
        val cls = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DISTRIBUTED_EXECUTING
        assertTrue(cls.isActivelyExecuting)
        assertTrue(cls.isDistributedParticipant)
        assertFalse(cls.acceptsNewDispatch)
    }

    @Test
    fun `TAKEOVER_EXECUTING isActivelyExecuting=true, isDistributedParticipant=true, acceptsNewDispatch=false`() {
        val cls = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.TAKEOVER_EXECUTING
        assertTrue(cls.isActivelyExecuting)
        assertTrue(cls.isDistributedParticipant)
        assertFalse(cls.acceptsNewDispatch)
    }

    @Test
    fun `INV-PSN-07 acceptsNewDispatch=true 当且仅当 CROSS_DEVICE_READY`() {
        val acceptsList = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.entries
            .filter { it.acceptsNewDispatch }
        assertEquals(1, acceptsList.size)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CROSS_DEVICE_READY,
            acceptsList.single()
        )
    }

    @Test
    fun `INV-PSN-08 isDistributedParticipant=true 当且仅当 DISTRIBUTED_EXECUTING 或 TAKEOVER_EXECUTING`() {
        val distributed = AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.entries
            .filter { it.isDistributedParticipant }
        assertEquals(2, distributed.size)
        assertTrue(distributed.contains(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DISTRIBUTED_EXECUTING))
        assertTrue(distributed.contains(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.TAKEOVER_EXECUTING))
    }

    // ── LocalExecutionActivityKind wire values ───────────────────────────────

    @Test
    fun `LocalExecutionActivityKind NONE wireValue 为 none`() {
        assertEquals("none",
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.NONE.wireValue)
    }

    @Test
    fun `LocalExecutionActivityKind LOCAL_ASSISTIVE wireValue 为 local_assistive`() {
        assertEquals("local_assistive",
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.LOCAL_ASSISTIVE.wireValue)
    }

    @Test
    fun `LocalExecutionActivityKind DELEGATED_PARTICIPANT wireValue 为 delegated_participant`() {
        assertEquals("delegated_participant",
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.DELEGATED_PARTICIPANT.wireValue)
    }

    @Test
    fun `LocalExecutionActivityKind TAKEOVER_PARTICIPANT wireValue 为 takeover_participant`() {
        assertEquals("takeover_participant",
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.TAKEOVER_PARTICIPANT.wireValue)
    }

    @Test
    fun `LocalExecutionActivityKind ALL_WIRE_VALUES 恰好包含 4 条`() {
        assertEquals(4,
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `LocalExecutionActivityKind fromWireValue 对每个值返回正确分类`() {
        AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.entries.forEach { kind ->
            assertEquals(kind,
                AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.fromWireValue(kind.wireValue))
        }
    }

    @Test
    fun `LocalExecutionActivityKind fromWireValue 对未知值返回 null`() {
        assertNull(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.fromWireValue("unknown"))
    }

    @Test
    fun `NONE isLocalExecution=false, isDistributedPathActive=false`() {
        val kind = AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.NONE
        assertFalse(kind.isLocalExecution)
        assertFalse(kind.isDistributedPathActive)
    }

    @Test
    fun `LOCAL_ASSISTIVE isLocalExecution=true, isDistributedPathActive=false`() {
        val kind = AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.LOCAL_ASSISTIVE
        assertTrue(kind.isLocalExecution)
        assertFalse(kind.isDistributedPathActive)
    }

    @Test
    fun `DELEGATED_PARTICIPANT isLocalExecution=true, isDistributedPathActive=true`() {
        val kind = AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.DELEGATED_PARTICIPANT
        assertTrue(kind.isLocalExecution)
        assertTrue(kind.isDistributedPathActive)
    }

    @Test
    fun `TAKEOVER_PARTICIPANT isLocalExecution=true, isDistributedPathActive=true`() {
        val kind = AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.TAKEOVER_PARTICIPANT
        assertTrue(kind.isLocalExecution)
        assertTrue(kind.isDistributedPathActive)
    }

    // ── derive() ParticipationModeClass 路径 ────────────────────────────────

    @Test
    fun `derive crossDeviceEnabled=false 且 localModeActive=false 返回 UNAVAILABLE`() {
        val snapshot = derive(crossDeviceEnabled = false, localModeActive = false)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.UNAVAILABLE,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive takeoverStateWire=active 返回 TAKEOVER_EXECUTING（高优先级）`() {
        val snapshot = derive(
            takeoverStateWire = "active",
            executionBusy = true,
            crossDeviceEnabled = true
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.TAKEOVER_EXECUTING,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive runtimeConstrained=true 返回 CONSTRAINED`() {
        val snapshot = derive(runtimeConstrained = true)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CONSTRAINED,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive governanceBlocked=true 返回 DEGRADED`() {
        val snapshot = derive(governanceBlocked = true)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DEGRADED,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive localCapabilityState=degraded 返回 DEGRADED`() {
        val snapshot = derive(localCapabilityStateWire = "degraded")
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DEGRADED,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive localCapabilityState=unavailable 返回 DEGRADED`() {
        val snapshot = derive(localCapabilityStateWire = "unavailable")
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DEGRADED,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive distributedParticipant=true 返回 DISTRIBUTED_EXECUTING`() {
        val snapshot = derive(
            distributedParticipant = true,
            executionBusy = true,
            crossDeviceEnabled = true
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DISTRIBUTED_EXECUTING,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive delegatedExecutionActive=true 返回 DISTRIBUTED_EXECUTING`() {
        val snapshot = derive(
            delegatedExecutionActive = true,
            executionBusy = true,
            crossDeviceEnabled = true
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.DISTRIBUTED_EXECUTING,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive localModeActive=true executionBusy=true 返回 LOCAL_ONLY_EXECUTING`() {
        val snapshot = derive(
            localModeActive = true,
            executionBusy = true,
            crossDeviceEnabled = false
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_EXECUTING,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive localModeActive=true executionBusy=false 返回 LOCAL_ONLY_IDLE`() {
        val snapshot = derive(
            localModeActive = true,
            executionBusy = false,
            crossDeviceEnabled = false
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.LOCAL_ONLY_IDLE,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive dispatchEligible=true crossDeviceEnabled=true 无执行 返回 CROSS_DEVICE_READY`() {
        val snapshot = derive(
            dispatchEligible = true,
            crossDeviceEnabled = true,
            executionBusy = false,
            distributedParticipant = false,
            delegatedExecutionActive = false,
            localModeActive = false
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CROSS_DEVICE_READY,
            snapshot.participationModeClass
        )
    }

    @Test
    fun `derive runtimeDeferred=true 返回 CONSTRAINED`() {
        val snapshot = derive(
            runtimeDeferred = true,
            dispatchEligible = false,
            localModeActive = false,
            executionBusy = false
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CONSTRAINED,
            snapshot.participationModeClass
        )
    }

    // ── derive() LocalExecutionActivityKind 路径 ────────────────────────────

    @Test
    fun `derive takeoverStateWire=active executionBusy=true 返回 TAKEOVER_PARTICIPANT`() {
        val snapshot = derive(
            takeoverStateWire = "active",
            executionBusy = true,
            crossDeviceEnabled = true
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.TAKEOVER_PARTICIPANT,
            snapshot.localExecutionActivityKind
        )
    }

    @Test
    fun `derive distributedParticipant=true executionBusy=true 返回 DELEGATED_PARTICIPANT`() {
        val snapshot = derive(
            distributedParticipant = true,
            executionBusy = true,
            crossDeviceEnabled = true
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.DELEGATED_PARTICIPANT,
            snapshot.localExecutionActivityKind
        )
    }

    @Test
    fun `derive executionBusy=true 本地模式 返回 LOCAL_ASSISTIVE`() {
        val snapshot = derive(
            executionBusy = true,
            localModeActive = true,
            crossDeviceEnabled = false,
            distributedParticipant = false,
            delegatedExecutionActive = false,
            takeoverStateWire = "inactive"
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.LOCAL_ASSISTIVE,
            snapshot.localExecutionActivityKind
        )
    }

    @Test
    fun `derive executionBusy=false 返回 NONE`() {
        val snapshot = derive(executionBusy = false)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.NONE,
            snapshot.localExecutionActivityKind
        )
    }

    // ── INV-PSN-03：localExecutionActive 与 localExecutionActivityKind 一致 ──

    @Test
    fun `INV-PSN-03 localExecutionActive=false 当 localExecutionActivityKind=NONE`() {
        val snapshot = derive(executionBusy = false)
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.NONE,
            snapshot.localExecutionActivityKind
        )
        assertFalse(snapshot.localExecutionActive)
    }

    @Test
    fun `INV-PSN-03 localExecutionActive=true 当 localExecutionActivityKind!=NONE`() {
        val snapshot = derive(
            executionBusy = true,
            localModeActive = true,
            crossDeviceEnabled = false
        )
        assertNotEquals(
            AndroidParticipationSemanticNormalizationContract.LocalExecutionActivityKind.NONE,
            snapshot.localExecutionActivityKind
        )
        assertTrue(snapshot.localExecutionActive)
    }

    // ── INV-PSN-06：CROSS_DEVICE_READY 时 localExecutionActive=false ─────────

    @Test
    fun `INV-PSN-06 CROSS_DEVICE_READY 时 localExecutionActive=false`() {
        val snapshot = derive(
            dispatchEligible = true,
            crossDeviceEnabled = true,
            executionBusy = false,
            distributedParticipant = false,
            delegatedExecutionActive = false,
            localModeActive = false
        )
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.CROSS_DEVICE_READY,
            snapshot.participationModeClass
        )
        assertFalse(snapshot.localExecutionActive)
    }

    // ── modeSignalAmbiguityResolved ──────────────────────────────────────────

    @Test
    fun `modeSignalAmbiguityResolved=false 当 localModeActive=true 且 crossDeviceEnabled=true`() {
        val snapshot = derive(
            localModeActive = true,
            crossDeviceEnabled = true
        )
        assertFalse(snapshot.modeSignalAmbiguityResolved)
    }

    @Test
    fun `modeSignalAmbiguityResolved=false 当 distributedParticipant=true 且 executionBusy=false`() {
        val snapshot = derive(
            distributedParticipant = true,
            executionBusy = false,
            crossDeviceEnabled = true
        )
        assertFalse(snapshot.modeSignalAmbiguityResolved)
    }

    @Test
    fun `modeSignalAmbiguityResolved=true 当信号一致（crossDevice_ready 路径）`() {
        val snapshot = derive(
            localModeActive = false,
            crossDeviceEnabled = true,
            distributedParticipant = false,
            executionBusy = false,
            dispatchEligible = true
        )
        assertTrue(snapshot.modeSignalAmbiguityResolved)
    }

    // ── NormalizationSnapshot.toWireMap() ────────────────────────────────────

    @Test
    fun `toWireMap 包含所有 5 个键`() {
        val snapshot = derive(
            dispatchEligible = true,
            crossDeviceEnabled = true,
            executionBusy = false
        )
        val wireMap = snapshot.toWireMap()
        assertTrue(wireMap.containsKey(
            AndroidParticipationSemanticNormalizationContract.KEY_PARTICIPATION_MODE_CLASS))
        assertTrue(wireMap.containsKey(
            AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVITY_KIND))
        assertTrue(wireMap.containsKey(
            AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVE))
        assertTrue(wireMap.containsKey(
            AndroidParticipationSemanticNormalizationContract.KEY_MODE_SIGNAL_AMBIGUITY_RESOLVED))
        assertTrue(wireMap.containsKey(
            AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION))
    }

    @Test
    fun `toWireMap participation_mode_class 值为稳定 wire 字符串`() {
        val snapshot = derive(
            dispatchEligible = true,
            crossDeviceEnabled = true,
            executionBusy = false
        )
        val wireMap = snapshot.toWireMap()
        val modeClass = wireMap[AndroidParticipationSemanticNormalizationContract.KEY_PARTICIPATION_MODE_CLASS]
        assertNotNull(modeClass)
        assertTrue(
            AndroidParticipationSemanticNormalizationContract.ParticipationModeClass.ALL_WIRE_VALUES.contains(
                modeClass as String
            )
        )
    }

    @Test
    fun `toWireMap schema_version 值为 SCHEMA_VERSION`() {
        val snapshot = derive()
        val wireMap = snapshot.toWireMap()
        assertEquals(
            AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION,
            wireMap[AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION]
        )
    }

    // ── NORMALIZATION_INVARIANTS ─────────────────────────────────────────────

    @Test
    fun `NORMALIZATION_INVARIANTS 共 10 条`() {
        assertEquals(10, AndroidParticipationSemanticNormalizationContract.NORMALIZATION_INVARIANTS.size)
    }

    @Test
    fun `NORMALIZATION_INVARIANTS 全部非空`() {
        AndroidParticipationSemanticNormalizationContract.NORMALIZATION_INVARIANTS.forEachIndexed { i, inv ->
            assertTrue("Invariant[$i] should not be blank", inv.isNotBlank())
        }
    }

    @Test
    fun `NORMALIZATION_INVARIANTS 无重复`() {
        val invs = AndroidParticipationSemanticNormalizationContract.NORMALIZATION_INVARIANTS
        assertEquals(invs.size, invs.toSet().size)
    }

    @Test
    fun `NORMALIZATION_INVARIANTS 包含 INV-PSN-01`() {
        assertTrue(
            AndroidParticipationSemanticNormalizationContract.NORMALIZATION_INVARIANTS
                .any { it.startsWith("INV-PSN-01") }
        )
    }

    @Test
    fun `NORMALIZATION_INVARIANTS 包含 INV-PSN-10`() {
        assertTrue(
            AndroidParticipationSemanticNormalizationContract.NORMALIZATION_INVARIANTS
                .any { it.startsWith("INV-PSN-10") }
        )
    }

    // ── StabilizationBaseline 注册 ───────────────────────────────────────────

    @Test
    fun `StabilizationBaseline 注册了 android-participation-semantic-normalization 条目`() {
        val entry = StabilizationBaseline.forId("android-participation-semantic-normalization")
        assertNotNull("StabilizationBaseline should have android-participation-semantic-normalization entry", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    // ── SCHEMA_VERSION ───────────────────────────────────────────────────────

    @Test
    fun `SCHEMA_VERSION 为 1`() {
        assertEquals("1", AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION)
    }

    // ── KEY_ 常量 ─────────────────────────────────────────────────────────────

    @Test
    fun `所有 KEY_ 常量非空`() {
        assertTrue(AndroidParticipationSemanticNormalizationContract.KEY_PARTICIPATION_MODE_CLASS.isNotBlank())
        assertTrue(AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVITY_KIND.isNotBlank())
        assertTrue(AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVE.isNotBlank())
        assertTrue(AndroidParticipationSemanticNormalizationContract.KEY_MODE_SIGNAL_AMBIGUITY_RESOLVED.isNotBlank())
        assertTrue(AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION.isNotBlank())
    }

    @Test
    fun `所有 KEY_ 常量互不相同`() {
        val keys = listOf(
            AndroidParticipationSemanticNormalizationContract.KEY_PARTICIPATION_MODE_CLASS,
            AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVITY_KIND,
            AndroidParticipationSemanticNormalizationContract.KEY_LOCAL_EXECUTION_ACTIVE,
            AndroidParticipationSemanticNormalizationContract.KEY_MODE_SIGNAL_AMBIGUITY_RESOLVED,
            AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    // ── 快照 schemaVersion 字段 ──────────────────────────────────────────────

    @Test
    fun `NormalizationSnapshot schemaVersion 始终为 SCHEMA_VERSION`() {
        val snapshot = derive()
        assertEquals(AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION, snapshot.schemaVersion)
    }
}
