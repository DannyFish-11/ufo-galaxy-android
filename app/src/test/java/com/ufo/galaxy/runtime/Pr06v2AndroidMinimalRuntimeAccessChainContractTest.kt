package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-06v2 (Android) — Android 最小真实运行接入链固定合约测试。
 *
 * 验证 [AndroidMinimalRuntimeAccessChainContract] 的核心层级分类、主链固定、
 * V2 叙事一致性映射、以及不变量声明。
 *
 * ## 验收标准
 *
 * 1. Android 最小真实运行接入链（7 阶段）全部被 [RuntimeAccessLayerClass.MAIN_CHAIN] 标注。
 * 2. AgentRuntimeBridge 被标注为 [RuntimeAccessLayerClass.COMPAT_FALLBACK]，不得冒充主链。
 * 3. FloatingWindowService 和 registrationError bridge 被标注为 [RuntimeAccessLayerClass.LEGACY_BRIDGE]。
 * 4. Diagnostics/projection 模块被标注为 [RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION]。
 * 5. 主链所有阶段的 ChainModuleRole 与 V2_CONSUMPTION_PATH_MAP 的双仓叙事一致。
 * 6. CHAIN_INVARIANTS 声明了 9 条形式化不变量。
 * 7. StabilizationBaseline 中已注册 android-minimal-runtime-access-chain-contract。
 */
class Pr06v2AndroidMinimalRuntimeAccessChainContractTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. RuntimeAccessLayerClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `RuntimeAccessLayerClass has MAIN_CHAIN value`() {
        val values = AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.values()
            .map { it.name }
        assertTrue(values.contains("MAIN_CHAIN"))
    }

    @Test
    fun `RuntimeAccessLayerClass has FACADE_HELPER value`() {
        val values = AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.values()
            .map { it.name }
        assertTrue(values.contains("FACADE_HELPER"))
    }

    @Test
    fun `RuntimeAccessLayerClass has COMPAT_FALLBACK value`() {
        val values = AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.values()
            .map { it.name }
        assertTrue(values.contains("COMPAT_FALLBACK"))
    }

    @Test
    fun `RuntimeAccessLayerClass has LEGACY_BRIDGE value`() {
        val values = AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.values()
            .map { it.name }
        assertTrue(values.contains("LEGACY_BRIDGE"))
    }

    @Test
    fun `RuntimeAccessLayerClass has DIAGNOSTICS_PROJECTION value`() {
        val values = AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.values()
            .map { it.name }
        assertTrue(values.contains("DIAGNOSTICS_PROJECTION"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. ChainModuleRole enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ChainModuleRole has all 8 expected values`() {
        val values = AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.values()
            .map { it.name }
        assertTrue(values.contains("LIFECYCLE_TRIGGER"))
        assertTrue(values.contains("LIFECYCLE_MANAGER"))
        assertTrue(values.contains("TRANSPORT_LAYER"))
        assertTrue(values.contains("INBOUND_DISPATCHER"))
        assertTrue(values.contains("EXECUTION_RUNTIME"))
        assertTrue(values.contains("LOCAL_EXECUTOR"))
        assertTrue(values.contains("RESULT_UPLINK"))
        assertTrue(values.contains("NOT_APPLICABLE"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Wire values are stable and lowercase
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `RuntimeAccessLayerClass wire values are stable`() {
        assertEquals(
            "main_chain",
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.MAIN_CHAIN.wireValue
        )
        assertEquals(
            "facade_helper",
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.FACADE_HELPER.wireValue
        )
        assertEquals(
            "compat_fallback",
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.COMPAT_FALLBACK.wireValue
        )
        assertEquals(
            "legacy_bridge",
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.LEGACY_BRIDGE.wireValue
        )
        assertEquals(
            "diagnostics_projection",
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION.wireValue
        )
    }

    @Test
    fun `ChainModuleRole wire values are stable`() {
        assertEquals(
            "lifecycle_trigger",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER.wireValue
        )
        assertEquals(
            "lifecycle_manager",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_MANAGER.wireValue
        )
        assertEquals(
            "inbound_dispatcher",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.INBOUND_DISPATCHER.wireValue
        )
        assertEquals(
            "execution_runtime",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.EXECUTION_RUNTIME.wireValue
        )
        assertEquals(
            "result_uplink",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.RESULT_UPLINK.wireValue
        )
        assertEquals(
            "not_applicable",
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE.wireValue
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Minimal main chain — 7 ordered stages
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `minimal main chain contains exactly 7 stages`() {
        assertEquals(7, AndroidMinimalRuntimeAccessChainContract.minimalMainChainEntries.size)
    }

    @Test
    fun `lifecycle trigger stage is GalaxyConnectionService`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.MAIN_CHAIN,
            entry!!.layerClass
        )
        assertTrue(entry.ownerClass.contains("GalaxyConnectionService"))
    }

    @Test
    fun `lifecycle manager stage is RuntimeController`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_MANAGER
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("RuntimeController"))
    }

    @Test
    fun `transport layer stage is GalaxyWebSocketClient`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.TRANSPORT_LAYER
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("GalaxyWebSocketClient"))
    }

    @Test
    fun `inbound dispatcher stage is GalaxyConnectionService`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.INBOUND_DISPATCHER
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("GalaxyConnectionService"))
    }

    @Test
    fun `execution runtime stage is AutonomousExecutionPipeline`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.EXECUTION_RUNTIME
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("AutonomousExecutionPipeline"))
    }

    @Test
    fun `local executor stage is LocalLoopExecutor`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LOCAL_EXECUTOR
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("LocalLoopExecutor"))
    }

    @Test
    fun `result uplink stage is GalaxyConnectionService`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.RESULT_UPLINK
        )
        assertNotNull(entry)
        assertTrue(entry!!.ownerClass.contains("GalaxyConnectionService"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Non-main-chain layers — compat/legacy/diagnostics do NOT masquerade
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `AgentRuntimeBridge is classified as COMPAT_FALLBACK not MAIN_CHAIN`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "agent-runtime-bridge-compat-fallback"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.COMPAT_FALLBACK,
            entry!!.layerClass
        )
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE,
            entry.role
        )
    }

    @Test
    fun `FloatingWindowService is classified as LEGACY_BRIDGE not MAIN_CHAIN`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "floating-window-service-legacy-bridge"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.LEGACY_BRIDGE,
            entry!!.layerClass
        )
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE,
            entry.role
        )
    }

    @Test
    fun `RuntimeController registrationError legacy bridge is classified as LEGACY_BRIDGE`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "runtime-controller-registration-error-legacy"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.LEGACY_BRIDGE,
            entry!!.layerClass
        )
    }

    @Test
    fun `AndroidDelegatedRuntimeAudit is classified as DIAGNOSTICS_PROJECTION`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "android-delegated-runtime-audit-diagnostics"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION,
            entry!!.layerClass
        )
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE,
            entry.role
        )
    }

    @Test
    fun `AndroidOperationalStateSurfaceContract is classified as DIAGNOSTICS_PROJECTION`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "android-operational-state-surface-projection"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION,
            entry!!.layerClass
        )
    }

    @Test
    fun `LocalExecutionModeGate is classified as FACADE_HELPER not MAIN_CHAIN`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "local-execution-mode-gate-helper"
        )
        assertNotNull(entry)
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.FACADE_HELPER,
            entry!!.layerClass
        )
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE,
            entry.role
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. entriesForLayer helpers
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `entriesForLayer MAIN_CHAIN returns only MAIN_CHAIN entries`() {
        val mainChain = AndroidMinimalRuntimeAccessChainContract.entriesForLayer(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.MAIN_CHAIN
        )
        assertTrue(mainChain.isNotEmpty())
        mainChain.forEach { e ->
            assertEquals(
                AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.MAIN_CHAIN,
                e.layerClass
            )
        }
    }

    @Test
    fun `entriesForLayer COMPAT_FALLBACK returns only COMPAT_FALLBACK entries`() {
        val compat = AndroidMinimalRuntimeAccessChainContract.entriesForLayer(
            AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.COMPAT_FALLBACK
        )
        assertTrue(compat.isNotEmpty())
        compat.forEach { e ->
            assertEquals(
                AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.COMPAT_FALLBACK,
                e.layerClass
            )
        }
    }

    @Test
    fun `nonMainChainEntries contains no MAIN_CHAIN entries`() {
        AndroidMinimalRuntimeAccessChainContract.nonMainChainEntries.forEach { e ->
            assertTrue(
                "Expected non-main-chain but found MAIN_CHAIN for ${e.moduleId}",
                e.layerClass != AndroidMinimalRuntimeAccessChainContract.RuntimeAccessLayerClass.MAIN_CHAIN
            )
        }
    }

    @Test
    fun `main chain and non-main chain entries are disjoint`() {
        val mainIds = AndroidMinimalRuntimeAccessChainContract.minimalMainChainEntries.map { it.moduleId }.toSet()
        val nonMainIds = AndroidMinimalRuntimeAccessChainContract.nonMainChainEntries.map { it.moduleId }.toSet()
        assertTrue(mainIds.intersect(nonMainIds).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. V2 counterpart mapping — dual-repo narrative consistency
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `lifecycle trigger has V2 counterpart DesktopPresenceRuntime`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER
        )
        assertNotNull(entry)
        val v2 = entry!!.v2Counterpart
        assertNotNull(v2)
        assertTrue(v2!!.contains("DesktopPresenceRuntime"))
    }

    @Test
    fun `lifecycle manager has V2 counterpart OpenClawd`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_MANAGER
        )
        assertNotNull(entry)
        val v2 = entry!!.v2Counterpart
        assertNotNull(v2)
        assertTrue(v2!!.contains("OpenClawd"))
    }

    @Test
    fun `inbound dispatcher has V2 counterpart CommandRouter and DeviceRouter`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.INBOUND_DISPATCHER
        )
        assertNotNull(entry)
        val v2 = entry!!.v2Counterpart
        assertNotNull(v2)
        assertTrue(v2!!.contains("CommandRouter"))
        assertTrue(v2.contains("DeviceRouter"))
    }

    @Test
    fun `result uplink has V2 counterpart task_result_canonical_truth_chain`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.RESULT_UPLINK
        )
        assertNotNull(entry)
        val v2 = entry!!.v2Counterpart
        assertNotNull(v2)
        assertTrue(v2!!.contains("task_result_canonical_truth_chain"))
    }

    @Test
    fun `V2_CONSUMPTION_PATH_MAP covers lifecycle trigger manager inbound dispatcher and result uplink`() {
        val map = AndroidMinimalRuntimeAccessChainContract.V2_CONSUMPTION_PATH_MAP
        assertTrue(
            map.containsKey(AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER)
        )
        assertTrue(
            map.containsKey(AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_MANAGER)
        )
        assertTrue(
            map.containsKey(AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.INBOUND_DISPATCHER)
        )
        assertTrue(
            map.containsKey(AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.RESULT_UPLINK)
        )
    }

    @Test
    fun `V2_CONSUMPTION_PATH_MAP lifecycle trigger value contains DesktopPresenceRuntime`() {
        val value = AndroidMinimalRuntimeAccessChainContract.V2_CONSUMPTION_PATH_MAP[
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER
        ]
        assertNotNull(value)
        assertTrue(value!!.contains("DesktopPresenceRuntime"))
    }

    @Test
    fun `V2_CONSUMPTION_PATH_MAP lifecycle manager value contains OpenClawd`() {
        val value = AndroidMinimalRuntimeAccessChainContract.V2_CONSUMPTION_PATH_MAP[
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_MANAGER
        ]
        assertNotNull(value)
        assertTrue(value!!.contains("OpenClawd"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. CHAIN_INVARIANTS — 9 invariants declared
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CHAIN_INVARIANTS declares exactly 9 invariants`() {
        assertEquals(9, AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS.size)
    }

    @Test
    fun `all CHAIN_INVARIANTS are non-blank`() {
        AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS.forEachIndexed { i, inv ->
            assertTrue("INVARIANT at index $i is blank", inv.isNotBlank())
        }
    }

    @Test
    fun `CHAIN_INVARIANTS cover unique main entry uniqueness`() {
        val inv1 = AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS[0]
        assertTrue(inv1.contains("INVARIANT-1"))
        assertTrue(inv1.contains("GalaxyConnectionService"))
    }

    @Test
    fun `CHAIN_INVARIANTS cover compat legacy not pretending to be main chain`() {
        val inv7 = AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS[6]
        assertTrue(inv7.contains("INVARIANT-7"))
        assertTrue(inv7.contains("COMPAT_FALLBACK"))
        assertTrue(inv7.contains("LEGACY_BRIDGE"))
    }

    @Test
    fun `CHAIN_INVARIANTS cover diagnostics layer not participating in admission`() {
        val inv8 = AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS[7]
        assertTrue(inv8.contains("INVARIANT-8"))
        assertTrue(inv8.contains("DIAGNOSTICS_PROJECTION"))
    }

    @Test
    fun `CHAIN_INVARIANTS cover dual-repo narrative consistency`() {
        val inv9 = AndroidMinimalRuntimeAccessChainContract.CHAIN_INVARIANTS[8]
        assertTrue(inv9.contains("INVARIANT-9"))
        assertTrue(inv9.contains("DesktopPresenceRuntime"))
        assertTrue(inv9.contains("OpenClawd"))
        assertTrue(inv9.contains("CommandRouter"))
        assertTrue(inv9.contains("DeviceRouter"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. Wire map generation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap for MAIN_CHAIN lifecycle trigger contains expected keys`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.mainChainEntryForRole(
            AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.LIFECYCLE_TRIGGER
        )
        assertNotNull(entry)
        val wireMap = AndroidMinimalRuntimeAccessChainContract.toWireMap(entry!!)
        assertEquals(
            "main_chain",
            wireMap[AndroidMinimalRuntimeAccessChainContract.KEY_RUNTIME_ACCESS_LAYER_CLASS]
        )
        assertEquals(
            "lifecycle_trigger",
            wireMap[AndroidMinimalRuntimeAccessChainContract.KEY_CHAIN_MODULE_ROLE]
        )
        assertEquals(
            AndroidMinimalRuntimeAccessChainContract.SCHEMA_VERSION,
            wireMap[AndroidMinimalRuntimeAccessChainContract.KEY_SCHEMA_VERSION]
        )
    }

    @Test
    fun `toWireMap for COMPAT_FALLBACK AgentRuntimeBridge has correct wire values`() {
        val entry = AndroidMinimalRuntimeAccessChainContract.entryForId(
            "agent-runtime-bridge-compat-fallback"
        )
        assertNotNull(entry)
        val wireMap = AndroidMinimalRuntimeAccessChainContract.toWireMap(entry!!)
        assertEquals(
            "compat_fallback",
            wireMap[AndroidMinimalRuntimeAccessChainContract.KEY_RUNTIME_ACCESS_LAYER_CLASS]
        )
        assertEquals(
            "not_applicable",
            wireMap[AndroidMinimalRuntimeAccessChainContract.KEY_CHAIN_MODULE_ROLE]
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. Data integrity
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `all entries have non-blank moduleId`() {
        AndroidMinimalRuntimeAccessChainContract.entries.forEach { e ->
            assertTrue("moduleId blank for entry: $e", e.moduleId.isNotBlank())
        }
    }

    @Test
    fun `all entries have non-blank displayName`() {
        AndroidMinimalRuntimeAccessChainContract.entries.forEach { e ->
            assertTrue("displayName blank for ${e.moduleId}", e.displayName.isNotBlank())
        }
    }

    @Test
    fun `all entries have non-blank ownerClass`() {
        AndroidMinimalRuntimeAccessChainContract.entries.forEach { e ->
            assertTrue("ownerClass blank for ${e.moduleId}", e.ownerClass.isNotBlank())
        }
    }

    @Test
    fun `all entries have non-blank rationale`() {
        AndroidMinimalRuntimeAccessChainContract.entries.forEach { e ->
            assertTrue("rationale blank for ${e.moduleId}", e.rationale.isNotBlank())
        }
    }

    @Test
    fun `all moduleIds are unique`() {
        val ids = AndroidMinimalRuntimeAccessChainContract.entries.map { it.moduleId }
        assertEquals("Duplicate moduleIds found", ids.size, ids.toSet().size)
    }

    @Test
    fun `non-main-chain entries have NOT_APPLICABLE role`() {
        AndroidMinimalRuntimeAccessChainContract.nonMainChainEntries.forEach { e ->
            assertEquals(
                "Expected NOT_APPLICABLE role for non-main-chain module ${e.moduleId}",
                AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE,
                e.role
            )
        }
    }

    @Test
    fun `main-chain entries do not have NOT_APPLICABLE role`() {
        AndroidMinimalRuntimeAccessChainContract.minimalMainChainEntries.forEach { e ->
            assertTrue(
                "Expected non-NOT_APPLICABLE role for main-chain module ${e.moduleId}",
                e.role != AndroidMinimalRuntimeAccessChainContract.ChainModuleRole.NOT_APPLICABLE
            )
        }
    }

    @Test
    fun `entryForId returns null for unregistered module`() {
        assertNull(
            AndroidMinimalRuntimeAccessChainContract.entryForId("non-existent-module-id")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-minimal-runtime-access-chain-contract is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-minimal-runtime-access-chain-contract")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry.extensionGuidance
        )
    }

    @Test
    fun `android-minimal-runtime-access-chain-contract is introduced at PR 97`() {
        val entry = StabilizationBaseline.forId("android-minimal-runtime-access-chain-contract")
        assertNotNull(entry)
        assertEquals(97, entry!!.introducedPr)
    }

    @Test
    fun `android-minimal-runtime-access-chain-contract rationale mentions main chain layers`() {
        val entry = StabilizationBaseline.forId("android-minimal-runtime-access-chain-contract")
        assertNotNull(entry)
        val rationale = entry!!.rationale
        assertTrue(rationale.contains("RuntimeAccessLayerClass"))
        assertTrue(rationale.contains("MAIN_CHAIN"))
        assertTrue(rationale.contains("GalaxyConnectionService"))
    }
}
