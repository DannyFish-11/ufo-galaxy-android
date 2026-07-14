package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.input.InputRouter
import com.ufo.galaxy.local.DefaultLocalLoopExecutor
import com.ufo.galaxy.local.FakeReadinessProvider
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.protocol.TaskSubmitPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR-993 (Android) — Unit tests for [AndroidNlInitiationContract].
 *
 * Validates all acceptance criteria for the Android-side NL initiation contract:
 *
 *  1. **Cross-device gate** — [AndroidNlInitiationContract.build] returns `null` when
 *     `cross_device_enabled=false`; returns non-null when `true`.
 *  2. **Initiation mode** — ANDROID_NL_CROSS_DEVICE is the only valid mode; wire value,
 *     requiresCrossDevice, and authorityTransfersToV2 are correct.
 *  3. **Authority scope** — V2_CENTRAL is the only valid scope; isV2Governed is true.
 *  4. **Metadata validity** — [AndroidNlInitiationContract.NlInitiationMetadata.isValid] returns
 *     `true` for correctly built metadata and `false` for tampered fields.
 *  5. **Wire map** — [AndroidNlInitiationContract.NlInitiationMetadata.toWireMap] produces all
 *     required fields; V2 can read origin, mode, authority scope, lineage, correlation_id,
 *     schema_version.
 *  6. **Lineage** — [AndroidNlInitiationContract.buildLineage] formats correctly.
 *  7. **No parallel system** — initiation must go through InputRouter (the canonical gate)
 *     and emit to gateway, not a separate path.
 *  8. **Main-chain payload fields** — when InputRouter routes cross-device, the outbound
 *     TaskSubmitPayload carries the NL initiation fields.
 *  9. **Invariants** — all INITIATION_INVARIANTS hold.
 * 10. **Contract wire map** — buildContractWireMap() contains required keys.
 * 11. **StabilizationBaseline** — "android-nl-initiation-contract" is registered as canonical.
 * 12. **Complex scenarios** — metadata consistency when takeover/recovery/reconnect/stale
 *     semantics are applied (authority scope always V2_CENTRAL; initiation mode always
 *     ANDROID_NL_CROSS_DEVICE; parallel bypass never formed).
 *
 * ## Test matrix
 *
 * ### Cross-device gate
 *  - build returns null when crossDeviceEnabled=false
 *  - build returns non-null when crossDeviceEnabled=true
 *  - gate condition is evaluated on every call
 *
 * ### NlInitiationMode
 *  - ANDROID_NL_CROSS_DEVICE wireValue is android_nl_cross_device
 *  - ANDROID_NL_CROSS_DEVICE requiresCrossDevice is true
 *  - ANDROID_NL_CROSS_DEVICE authorityTransfersToV2 is true
 *  - fromWireValue returns ANDROID_NL_CROSS_DEVICE for correct value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly one entry
 *
 * ### NlAuthorityScope
 *  - V2_CENTRAL wireValue is v2_central
 *  - V2_CENTRAL isV2Governed is true
 *  - fromWireValue returns V2_CENTRAL for correct value
 *  - fromWireValue returns null for unknown value
 *  - ALL_WIRE_VALUES contains exactly one entry
 *
 * ### NlInitiationMetadata.isValid()
 *  - correctly built metadata is valid
 *  - tampered origin fails validation
 *  - blank lineage fails validation
 *  - blank correlationId fails validation
 *  - missing schema_version in deviceContext fails validation
 *  - missing device_id in deviceContext fails validation
 *  - missing cross_device_enabled in deviceContext fails validation
 *
 * ### NlInitiationMetadata.toWireMap()
 *  - wire map contains FIELD_ORIGIN key with ORIGIN_ANDROID_DEVICE
 *  - wire map contains FIELD_INITIATION_MODE key
 *  - wire map contains FIELD_AUTHORITY_SCOPE key with v2_central
 *  - wire map contains FIELD_LINEAGE key
 *  - wire map contains FIELD_CORRELATION_ID key
 *  - wire map contains FIELD_SCHEMA_VERSION key
 *  - wire map contains device_id from deviceContext
 *  - wire map contains cross_device_enabled=true
 *
 * ### Lineage
 *  - buildLineage with sessionId formats as android/device/session
 *  - buildLineage without sessionId uses no_session
 *
 * ### Main-chain integration (InputRouter)
 *  - cross_device=false: no NL initiation metadata in outbound message
 *  - cross_device=true, WS connected: outbound message contains nl_initiation_origin
 *  - cross_device=true, WS connected: outbound message contains nl_initiation_mode
 *  - cross_device=true, WS connected: outbound message contains nl_initiation_authority_scope=v2_central
 *  - cross_device=true, WS connected: outbound message contains nl_initiation_lineage
 *  - initiation uses InputRouter not a parallel path (gateway receives exactly 1 message)
 *  - initiation route is CROSS_DEVICE (enters main chain)
 *
 * ### Invariants
 *  - all INITIATION_INVARIANTS hold (all values true)
 *  - nl_initiation_requires_cross_device_enabled is true
 *  - nl_initiation_authority_scope_is_always_v2_central is true
 *  - nl_initiation_cannot_bypass_input_router is true
 *  - nl_initiation_does_not_form_parallel_intent_system is true
 *  - nl_initiation_enters_v2_main_chain is true
 *
 * ### Contract wire map
 *  - buildContractWireMap contains introduced_pr=993
 *  - buildContractWireMap contains cross_device_gate_required=true
 *  - buildContractWireMap contains v2_central_authority_required=true
 *  - buildContractWireMap contains parallel_system_forbidden=true
 *  - buildContractWireMap all_invariants_hold=true
 *
 * ### StabilizationBaseline
 *  - android-nl-initiation-contract is registered
 *  - surface is CANONICAL_STABLE
 *
 * ### Complex scenarios
 *  - authority scope is always V2_CENTRAL (not affected by recovery semantics)
 *  - initiation mode is always ANDROID_NL_CROSS_DEVICE (not affected by stale/duplicate)
 *  - lineage is stable per device/session pair (reconnect does not change lineage format)
 *  - different correlation_ids for concurrent initiations (no duplicate correlation)
 */
class Pr993AndroidNlInitiationContractTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    // ── Fake GatewayClient ────────────────────────────────────────────────────

    private class FakeGatewayClient(
        var connected: Boolean = false,
        var sendResult: Boolean = true
    ) : GatewayClient {
        val sentMessages = mutableListOf<String>()

        override fun isConnected(): Boolean = connected
        override fun sendJson(json: String): Boolean {
            if (connected && sendResult) {
                sentMessages.add(json)
                return true
            }
            return false
        }
    }

    // ── Fake LocalLoop dependencies ───────────────────────────────────────────

    private class FakePlannerService : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "no replan")
    }

    private class FakeGroundingService : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 1170, confidence = 0.9f, element_description = "")
    }

    private class FakeAccessibilityExecutor : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshotProvider : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildLoopController(): LoopController {
        val modelsDir = tmpFolder.newFolder("models")
        return LoopController(
            localPlanner = LocalPlanner(FakePlannerService()),
            executorBridge = ExecutorBridge(
                groundingService = FakeGroundingService(),
                accessibilityExecutor = FakeAccessibilityExecutor(),
                imageScaler = NoOpImageScaler()
            ),
            screenshotProvider = FakeScreenshotProvider(),
            modelAssetManager = ModelAssetManager(modelsDir),
            modelDownloader = ModelDownloader(modelsDir)
        )
    }

    private fun buildRouter(
        crossDeviceEnabled: Boolean = false,
        gateway: FakeGatewayClient = FakeGatewayClient(connected = false),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = crossDeviceEnabled)
        val localLoopExecutor = DefaultLocalLoopExecutor(
            loopController = buildLoopController(),
            readinessProvider = FakeReadinessProvider.fullyReady()
        )
        return InputRouter(
            settings = settings,
            webSocketClient = gateway,
            localLoopExecutor = localLoopExecutor,
            coroutineScope = scope
        )
    }

    // ── INTRODUCED_PR ─────────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 993`() {
        assertEquals(993, AndroidNlInitiationContract.INTRODUCED_PR)
    }

    // ── Cross-device gate ─────────────────────────────────────────────────────

    @Test
    fun `build returns null when crossDeviceEnabled=false`() {
        val result = AndroidNlInitiationContract.build(
            crossDeviceEnabled = false,
            deviceId = "pixel8"
        )
        assertNull("NL initiation must be forbidden when crossDeviceEnabled=false", result)
    }

    @Test
    fun `build returns non-null when crossDeviceEnabled=true`() {
        val result = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )
        assertNotNull("NL initiation must be available when crossDeviceEnabled=true", result)
    }

    @Test
    fun `build gate condition is evaluated on every call`() {
        // First call with disabled — must be null.
        val disabled = AndroidNlInitiationContract.build(crossDeviceEnabled = false, deviceId = "dev")
        assertNull(disabled)

        // Second call with enabled — must be non-null.
        val enabled = AndroidNlInitiationContract.build(crossDeviceEnabled = true, deviceId = "dev")
        assertNotNull(enabled)
    }

    // ── NlInitiationMode ─────────────────────────────────────────────────────

    @Test
    fun `ANDROID_NL_CROSS_DEVICE wireValue is android_nl_cross_device`() {
        assertEquals(
            "android_nl_cross_device",
            AndroidNlInitiationContract.NlInitiationMode.ANDROID_NL_CROSS_DEVICE.wireValue
        )
    }

    @Test
    fun `ANDROID_NL_CROSS_DEVICE requiresCrossDevice is true`() {
        assertTrue(
            AndroidNlInitiationContract.NlInitiationMode.ANDROID_NL_CROSS_DEVICE.requiresCrossDevice
        )
    }

    @Test
    fun `ANDROID_NL_CROSS_DEVICE authorityTransfersToV2 is true`() {
        assertTrue(
            AndroidNlInitiationContract.NlInitiationMode.ANDROID_NL_CROSS_DEVICE.authorityTransfersToV2
        )
    }

    @Test
    fun `NlInitiationMode fromWireValue returns ANDROID_NL_CROSS_DEVICE for correct value`() {
        val result = AndroidNlInitiationContract.NlInitiationMode.fromWireValue("android_nl_cross_device")
        assertEquals(
            AndroidNlInitiationContract.NlInitiationMode.ANDROID_NL_CROSS_DEVICE,
            result
        )
    }

    @Test
    fun `NlInitiationMode fromWireValue returns null for unknown value`() {
        assertNull(AndroidNlInitiationContract.NlInitiationMode.fromWireValue("unknown_mode"))
    }

    @Test
    fun `NlInitiationMode ALL_WIRE_VALUES contains exactly one entry`() {
        assertEquals(1, AndroidNlInitiationContract.NlInitiationMode.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all NlInitiationMode entries have non-blank displayName and description`() {
        for (mode in AndroidNlInitiationContract.NlInitiationMode.entries) {
            assertTrue("displayName must not be blank for $mode", mode.displayName.isNotBlank())
            assertTrue("description must not be blank for $mode", mode.description.isNotBlank())
        }
    }

    // ── NlAuthorityScope ──────────────────────────────────────────────────────

    @Test
    fun `V2_CENTRAL wireValue is v2_central`() {
        assertEquals(
            "v2_central",
            AndroidNlInitiationContract.NlAuthorityScope.V2_CENTRAL.wireValue
        )
    }

    @Test
    fun `V2_CENTRAL isV2Governed is true`() {
        assertTrue(AndroidNlInitiationContract.NlAuthorityScope.V2_CENTRAL.isV2Governed)
    }

    @Test
    fun `NlAuthorityScope fromWireValue returns V2_CENTRAL for correct value`() {
        val result = AndroidNlInitiationContract.NlAuthorityScope.fromWireValue("v2_central")
        assertEquals(AndroidNlInitiationContract.NlAuthorityScope.V2_CENTRAL, result)
    }

    @Test
    fun `NlAuthorityScope fromWireValue returns null for unknown value`() {
        assertNull(AndroidNlInitiationContract.NlAuthorityScope.fromWireValue("android_local"))
    }

    @Test
    fun `NlAuthorityScope ALL_WIRE_VALUES contains exactly one entry`() {
        assertEquals(1, AndroidNlInitiationContract.NlAuthorityScope.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all NlAuthorityScope entries have non-blank displayName and description`() {
        for (scope in AndroidNlInitiationContract.NlAuthorityScope.entries) {
            assertTrue("displayName must not be blank for $scope", scope.displayName.isNotBlank())
            assertTrue("description must not be blank for $scope", scope.description.isNotBlank())
        }
    }

    // ── NlInitiationMetadata.isValid() ────────────────────────────────────────

    @Test
    fun `correctly built metadata is valid`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "sess-001"
        )
        assertNotNull(metadata)
        assertTrue("Correctly built metadata must pass isValid()", metadata!!.isValid())
    }

    @Test
    fun `tampered origin fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(origin = "some_other_origin")
        assertFalse("Tampered origin must fail isValid()", tampered.isValid())
    }

    @Test
    fun `blank lineage fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(lineage = "")
        assertFalse("Blank lineage must fail isValid()", tampered.isValid())
    }

    @Test
    fun `blank correlationId fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(correlationId = "")
        assertFalse("Blank correlationId must fail isValid()", tampered.isValid())
    }

    @Test
    fun `missing schema_version in deviceContext fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(
            deviceContext = metadata.deviceContext - AndroidNlInitiationContract.FIELD_SCHEMA_VERSION
        )
        assertFalse("Missing schema_version must fail isValid()", tampered.isValid())
    }

    @Test
    fun `missing device_id in deviceContext fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(
            deviceContext = metadata.deviceContext - "device_id"
        )
        assertFalse("Missing device_id must fail isValid()", tampered.isValid())
    }

    @Test
    fun `missing cross_device_enabled in deviceContext fails validation`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val tampered = metadata.copy(
            deviceContext = metadata.deviceContext - "cross_device_enabled"
        )
        assertFalse("Missing cross_device_enabled must fail isValid()", tampered.isValid())
    }

    // ── NlInitiationMetadata.toWireMap() ──────────────────────────────────────

    @Test
    fun `wire map contains FIELD_ORIGIN key with ORIGIN_ANDROID_DEVICE`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals(
            AndroidNlInitiationContract.ORIGIN_ANDROID_DEVICE,
            map[AndroidNlInitiationContract.FIELD_ORIGIN]
        )
    }

    @Test
    fun `wire map contains FIELD_INITIATION_MODE key with android_nl_cross_device`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals(
            "android_nl_cross_device",
            map[AndroidNlInitiationContract.FIELD_INITIATION_MODE]
        )
    }

    @Test
    fun `wire map contains FIELD_AUTHORITY_SCOPE key with v2_central`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals(
            "v2_central",
            map[AndroidNlInitiationContract.FIELD_AUTHORITY_SCOPE]
        )
    }

    @Test
    fun `wire map contains FIELD_LINEAGE key`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "sess-abc"
        )!!
        val map = metadata.toWireMap()
        assertTrue(
            "Wire map must contain lineage key",
            map.containsKey(AndroidNlInitiationContract.FIELD_LINEAGE)
        )
        assertTrue("Lineage must not be blank", map[AndroidNlInitiationContract.FIELD_LINEAGE]!!.isNotBlank())
    }

    @Test
    fun `wire map contains FIELD_CORRELATION_ID key`() {
        val correlationId = "test-corr-001"
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            correlationId = correlationId
        )!!
        val map = metadata.toWireMap()
        assertEquals(correlationId, map[AndroidNlInitiationContract.FIELD_CORRELATION_ID])
    }

    @Test
    fun `wire map contains FIELD_SCHEMA_VERSION key`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals(
            AndroidNlInitiationContract.SCHEMA_VERSION,
            map[AndroidNlInitiationContract.FIELD_SCHEMA_VERSION]
        )
    }

    @Test
    fun `wire map contains device_id from deviceContext`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals("pixel8", map["device_id"])
    }

    @Test
    fun `wire map contains cross_device_enabled=true`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val map = metadata.toWireMap()
        assertEquals("true", map["cross_device_enabled"])
    }

    @Test
    fun `wire map includes runtime_session_id when provided`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "sess-xyz"
        )!!
        val map = metadata.toWireMap()
        assertEquals("sess-xyz", map["runtime_session_id"])
    }

    @Test
    fun `wire map does not include runtime_session_id key when null`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = null
        )!!
        val map = metadata.toWireMap()
        assertFalse("Wire map must not contain runtime_session_id when null",
            map.containsKey("runtime_session_id"))
    }

    // ── Lineage ───────────────────────────────────────────────────────────────

    @Test
    fun `buildLineage with sessionId formats as android-device-session`() {
        val lineage = AndroidNlInitiationContract.buildLineage("pixel8", "sess-001")
        assertEquals("android/pixel8/sess-001", lineage)
    }

    @Test
    fun `buildLineage without sessionId uses no_session`() {
        val lineage = AndroidNlInitiationContract.buildLineage("pixel8", null)
        assertEquals("android/pixel8/no_session", lineage)
    }

    @Test
    fun `built metadata lineage matches buildLineage output`() {
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "sess-abc"
        )!!
        val expected = AndroidNlInitiationContract.buildLineage("pixel8", "sess-abc")
        assertEquals(expected, metadata.lineage)
    }

    // ── Main-chain integration (InputRouter) ──────────────────────────────────

    @Test
    fun `cross_device=false NL input does not produce CROSS_DEVICE route`() {
        val gateway = FakeGatewayClient(connected = true)
        val router = buildRouter(crossDeviceEnabled = false, gateway = gateway)

        val result = router.route("打开微信")

        assertNotEquals(
            "Initiation must not be available when crossDeviceEnabled=false",
            InputRouter.RouteMode.CROSS_DEVICE,
            result
        )
        assertTrue(
            "No messages must be sent to gateway when crossDeviceEnabled=false",
            gateway.sentMessages.isEmpty()
        )
    }

    @Test
    fun `cross_device=true WS connected outbound message contains nl_initiation_origin`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        val sent = gateway.sentMessages.first()
        assertTrue(
            "Outbound message must contain nl_initiation_origin",
            sent.contains(AndroidNlInitiationContract.FIELD_ORIGIN) ||
                sent.contains(AndroidNlInitiationContract.ORIGIN_ANDROID_DEVICE)
        )
    }

    @Test
    fun `cross_device=true WS connected outbound message contains nl_initiation_mode`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        val sent = gateway.sentMessages.first()
        assertTrue(
            "Outbound message must contain android_nl_cross_device initiation mode",
            sent.contains("android_nl_cross_device")
        )
    }

    @Test
    fun `cross_device=true WS connected outbound message contains authority_scope v2_central`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        val sent = gateway.sentMessages.first()
        assertTrue(
            "Outbound message must declare v2_central authority scope",
            sent.contains("v2_central")
        )
    }

    @Test
    fun `cross_device=true WS connected outbound message contains nl_initiation_lineage`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        val sent = gateway.sentMessages.first()
        assertTrue(
            "Outbound message must contain lineage field",
            sent.contains(AndroidNlInitiationContract.FIELD_LINEAGE) ||
                sent.contains("android/")
        )
    }

    @Test
    fun `initiation uses InputRouter not a parallel path — gateway receives exactly one message`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        assertEquals(
            "Exactly one message must be sent — initiation must not create a parallel send path",
            1,
            gateway.sentMessages.size
        )
    }

    @Test
    fun `initiation route is CROSS_DEVICE — enters main chain`() {
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        val result = router.route("打开微信")

        assertEquals(
            "Initiation must produce CROSS_DEVICE route (enters V2 main chain)",
            InputRouter.RouteMode.CROSS_DEVICE,
            result
        )
    }

    @Test
    fun `outbound TaskSubmitPayload nl_initiation fields are present in serialised JSON`() {
        val gson = Gson()
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("打开微信")

        val sentJson = gateway.sentMessages.first()
        // Verify that the NL initiation payload fields are serialised into the JSON
        assertTrue("JSON must contain nl_initiation_origin", sentJson.contains("nl_initiation_origin"))
        assertTrue("JSON must contain nl_initiation_mode", sentJson.contains("nl_initiation_mode"))
        assertTrue("JSON must contain nl_initiation_authority_scope", sentJson.contains("nl_initiation_authority_scope"))
        assertTrue("JSON must contain nl_initiation_lineage", sentJson.contains("nl_initiation_lineage"))
    }

    // ── INITIATION_INVARIANTS ─────────────────────────────────────────────────

    @Test
    fun `all INITIATION_INVARIANTS hold`() {
        val invariants = AndroidNlInitiationContract.INITIATION_INVARIANTS
        assertTrue("INITIATION_INVARIANTS must not be empty", invariants.isNotEmpty())
        for ((key, value) in invariants) {
            assertTrue("Invariant '$key' must be true", value)
        }
    }

    @Test
    fun `nl_initiation_requires_cross_device_enabled invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_requires_cross_device_enabled"] == true
        )
    }

    @Test
    fun `nl_initiation_authority_scope_is_always_v2_central invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_authority_scope_is_always_v2_central"] == true
        )
    }

    @Test
    fun `nl_initiation_cannot_bypass_input_router invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_cannot_bypass_input_router"] == true
        )
    }

    @Test
    fun `nl_initiation_does_not_form_parallel_intent_system invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_does_not_form_parallel_intent_system"] == true
        )
    }

    @Test
    fun `nl_initiation_enters_v2_main_chain invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_enters_v2_main_chain"] == true
        )
    }

    @Test
    fun `nl_initiation_semantic_authority_transfers_to_v2 invariant is true`() {
        assertTrue(
            AndroidNlInitiationContract.INITIATION_INVARIANTS["nl_initiation_semantic_authority_transfers_to_v2"] == true
        )
    }

    // ── buildContractWireMap() ────────────────────────────────────────────────

    @Test
    fun `buildContractWireMap contains introduced_pr 993`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(993, map["introduced_pr"])
    }

    @Test
    fun `buildContractWireMap contains cross_device_gate_required=true`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(true, map["cross_device_gate_required"])
    }

    @Test
    fun `buildContractWireMap contains v2_central_authority_required=true`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(true, map["v2_central_authority_required"])
    }

    @Test
    fun `buildContractWireMap contains parallel_system_forbidden=true`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(true, map["parallel_system_forbidden"])
    }

    @Test
    fun `buildContractWireMap all_invariants_hold=true`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(true, map["all_invariants_hold"])
    }

    @Test
    fun `buildContractWireMap contains schema_version`() {
        val map = AndroidNlInitiationContract.buildContractWireMap()
        assertEquals(AndroidNlInitiationContract.SCHEMA_VERSION, map["schema_version"])
    }

    // ── StabilizationBaseline ─────────────────────────────────────────────────

    @Test
    fun `android-nl-initiation-contract is registered in StabilizationBaseline`() {
        assertTrue(
            "android-nl-initiation-contract must be registered in StabilizationBaseline",
            StabilizationBaseline.isRegistered("android-nl-initiation-contract")
        )
    }

    @Test
    fun `android-nl-initiation-contract surface is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-nl-initiation-contract")
        assertNotNull("Entry must exist in StabilizationBaseline", entry)
        assertEquals(
            "Surface must be CANONICAL_STABLE",
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `android-nl-initiation-contract extension guidance is EXTEND`() {
        val entry = StabilizationBaseline.forId("android-nl-initiation-contract")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry!!.extensionGuidance
        )
    }

    // ── Complex scenarios ─────────────────────────────────────────────────────

    @Test
    fun `authority scope is always V2_CENTRAL regardless of runtime context`() {
        // Simulates a scenario where device context has recovery/stale semantics.
        // The authority scope must still be V2_CENTRAL for any valid Android NL initiation.
        val recoveryMetadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "recovered-sess-001"
        )
        assertNotNull(recoveryMetadata)
        assertEquals(
            "Authority scope must always be V2_CENTRAL even in recovery scenarios",
            AndroidNlInitiationContract.NlAuthorityScope.V2_CENTRAL,
            recoveryMetadata!!.authorityScope
        )
    }

    @Test
    fun `initiation mode is always ANDROID_NL_CROSS_DEVICE regardless of session state`() {
        // Simulates stale/duplicate session scenario — mode must remain stable.
        val staleMetadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "stale-sess-001"
        )
        assertNotNull(staleMetadata)
        assertEquals(
            "Initiation mode must always be ANDROID_NL_CROSS_DEVICE",
            AndroidNlInitiationContract.NlInitiationMode.ANDROID_NL_CROSS_DEVICE,
            staleMetadata!!.initiationMode
        )
    }

    @Test
    fun `lineage format is stable across reconnect scenarios`() {
        // Lineage is formatted as android/{deviceId}/{sessionId}; format must not change
        // between a normal session and a reconnected session.
        val normal = AndroidNlInitiationContract.buildLineage("pixel8", "sess-001")
        val reconnected = AndroidNlInitiationContract.buildLineage("pixel8", "sess-002")

        assertTrue("Normal lineage starts with android/pixel8/", normal.startsWith("android/pixel8/"))
        assertTrue("Reconnected lineage starts with android/pixel8/", reconnected.startsWith("android/pixel8/"))
        assertNotEquals("Different sessions must produce different lineage values", normal, reconnected)
    }

    @Test
    fun `different correlation_ids for concurrent initiations prevent duplicate correlation`() {
        val metadata1 = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!
        val metadata2 = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8"
        )!!

        assertNotEquals(
            "Concurrent initiations must have different correlation IDs",
            metadata1.correlationId,
            metadata2.correlationId
        )
    }

    @Test
    fun `takeover scenario - authority scope remains V2_CENTRAL and no parallel bypass`() {
        // During takeover (cross-device is active, takeover accepted), NL initiations
        // must still use the canonical WS uplink path and V2_CENTRAL authority.
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("接管模式下发起任务")

        // Exactly one message via the canonical WS uplink — no parallel bypass.
        assertEquals(
            "Takeover scenario: must not form parallel bypass — exactly 1 WS message",
            1,
            gateway.sentMessages.size
        )
        val sent = gateway.sentMessages.first()
        assertTrue("Takeover scenario: must declare v2_central authority scope", sent.contains("v2_central"))
        assertTrue("Takeover scenario: must use android_nl_cross_device mode", sent.contains("android_nl_cross_device"))
    }

    @Test
    fun `recovery scenario - NL initiation blocked when crossDeviceEnabled=false`() {
        // If a recovery scenario results in cross-device being disabled (e.g. forced offline),
        // Android NL initiation must not be available — the gate must hold.
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = false,
            deviceId = "pixel8",
            runtimeSessionId = "recovering-sess"
        )
        assertNull(
            "Recovery scenario with crossDeviceEnabled=false must block NL initiation",
            metadata
        )
    }

    @Test
    fun `stale session scenario - metadata still valid with stale runtimeSessionId`() {
        // Even if the runtimeSessionId is from a stale session, the metadata structure
        // must still be valid — stale/duplicate handling is V2's responsibility.
        val metadata = AndroidNlInitiationContract.build(
            crossDeviceEnabled = true,
            deviceId = "pixel8",
            runtimeSessionId = "stale-old-session-99"
        )!!
        assertTrue(
            "Stale session metadata must still be structurally valid for V2 to classify",
            metadata.isValid()
        )
        assertEquals("v2_central", metadata.authorityScope.wireValue)
    }

    @Test
    fun `duplicate initiation scenario - each attempt has unique correlationId`() {
        // Simulates user rapidly submitting the same NL input (duplicate scenario).
        // Each attempt must get a unique correlationId so V2 can detect and deduplicate.
        val ids = (1..5).map {
            AndroidNlInitiationContract.build(
                crossDeviceEnabled = true,
                deviceId = "pixel8"
            )!!.correlationId
        }
        assertEquals(
            "All concurrent/duplicate initiations must have unique correlationIds",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `NL initiation does not form parallel intent system — only one gateway message`() {
        // The key invariant: initiating via NL must go through the single canonical
        // InputRouter path; no component may create a second WS send for the same NL input.
        val gateway = FakeGatewayClient(connected = true, sendResult = true)
        val router = buildRouter(crossDeviceEnabled = true, gateway = gateway)

        router.route("自然语言发起测试任务")
        router.route("第二个自然语言发起")

        // Two route() calls → two messages. But each call produces exactly one message (not two).
        assertEquals(
            "Each route() call must produce exactly one WS message — no parallel intent dispatch",
            2,
            gateway.sentMessages.size
        )
    }
}
