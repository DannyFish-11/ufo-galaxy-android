package com.ufo.galaxy.input

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import com.ufo.galaxy.local.LocalLoopExecutor
import com.ufo.galaxy.local.LocalLoopOptions
import com.ufo.galaxy.local.LocalLoopResult

/**
 * Tests that [InputRouter.route] correctly canonicalises and propagates
 * `source_runtime_posture` for both the cross-device (WS uplink) and local
 * execution paths.
 *
 * These tests are part of PR package 1 (ANDROID side) of the post-533
 * dual-repo runtime unification plan: Posture Contract Canonicalization.
 *
 * Test matrix:
 *  - Default posture (`CONTROL_ONLY`) is used when the caller omits the parameter.
 *  - Explicit `JOIN_RUNTIME` posture is preserved in `TaskSubmitPayload` and
 *    `AipMessage` envelope when routing cross-device.
 *  - Explicit `CONTROL_ONLY` posture is present in the outbound envelope.
 *  - Unknown / blank posture values are normalised to `CONTROL_ONLY` before being
 *    included in the outbound JSON — never left as raw unknown strings.
 *  - For the local path, posture is forwarded in [LocalLoopOptions] received
 *    by the executor.
 *  - `source_runtime_posture` appears in BOTH the payload body AND the AipMessage
 *    envelope (dual-field contract matching main-repo PR #533).
 */
class InputRouterPostureTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeGatewayClient(
        var connected: Boolean = true,
        var sendResult: Boolean = true
    ) : GatewayClient {
        val sentMessages = mutableListOf<String>()
        override fun isConnected() = connected
        override fun sendJson(json: String): Boolean {
            if (connected && sendResult) {
                sentMessages.add(json)
                return true
            }
            return false
        }
    }

    /** Captures [LocalLoopOptions] passed to execute() for assertion. */
    private class CapturingLocalLoopExecutor : LocalLoopExecutor {
        val capturedOptions = mutableListOf<LocalLoopOptions>()
        override suspend fun execute(options: LocalLoopOptions): LocalLoopResult {
            capturedOptions.add(options)
            return LocalLoopResult(
                sessionId = "test-session",
                instruction = options.instruction,
                status = LocalLoopResult.STATUS_SUCCESS,
                stepCount = 0,
                stopReason = null,
                error = null
            )
        }
    }

    private fun buildCrossDeviceRouter(
        gateway: FakeGatewayClient = FakeGatewayClient(connected = true),
        localExecutor: LocalLoopExecutor = CapturingLocalLoopExecutor(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = true)
        return InputRouter(
            settings = settings,
            webSocketClient = gateway,
            localLoopExecutor = localExecutor,
            coroutineScope = scope
        )
    }

    private fun buildLocalRouter(
        localExecutor: LocalLoopExecutor = CapturingLocalLoopExecutor(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ): InputRouter {
        val settings = InMemoryAppSettings(crossDeviceEnabled = false)
        return InputRouter(
            settings = settings,
            webSocketClient = FakeGatewayClient(connected = false),
            localLoopExecutor = localExecutor,
            coroutineScope = scope
        )
    }

    // ── Cross-device path: default posture ────────────────────────────────────

    @Test
    fun `cross-device route with default posture includes control_only in TaskSubmitPayload`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open settings")

        val sent = JSONObject(gateway.sentMessages.first())
        val payloadJson = sent.getJSONObject("payload")
        assertTrue(
            "source_runtime_posture must be present in TaskSubmitPayload body",
            payloadJson.has("source_runtime_posture")
        )
        assertEquals(
            "Default posture must be control_only in payload body",
            SourceRuntimePosture.CONTROL_ONLY,
            payloadJson.getString("source_runtime_posture")
        )
    }

    @Test
    fun `cross-device route with default posture includes control_only in AipMessage envelope`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open settings")

        val envelope = JSONObject(gateway.sentMessages.first())
        assertTrue(
            "source_runtime_posture must be present in AipMessage envelope",
            envelope.has("source_runtime_posture")
        )
        assertEquals(
            "Default posture must be control_only in envelope",
            SourceRuntimePosture.CONTROL_ONLY,
            envelope.getString("source_runtime_posture")
        )
    }

    // ── Cross-device path: explicit JOIN_RUNTIME posture ──────────────────────

    @Test
    fun `cross-device route propagates join_runtime into TaskSubmitPayload`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("run parallel task", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val sent = JSONObject(gateway.sentMessages.first())
        val payloadJson = sent.getJSONObject("payload")
        assertEquals(
            "join_runtime posture must be preserved in TaskSubmitPayload body",
            SourceRuntimePosture.JOIN_RUNTIME,
            payloadJson.getString("source_runtime_posture")
        )
    }

    @Test
    fun `cross-device route propagates join_runtime into AipMessage envelope`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("run parallel task", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val envelope = JSONObject(gateway.sentMessages.first())
        assertEquals(
            "join_runtime posture must be preserved in AipMessage envelope",
            SourceRuntimePosture.JOIN_RUNTIME,
            envelope.getString("source_runtime_posture")
        )
    }

    @Test
    fun `cross-device route propagates control_only into both payload and envelope`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("navigate to home", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)

        val envelope = JSONObject(gateway.sentMessages.first())
        val payloadJson = envelope.getJSONObject("payload")
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, envelope.getString("source_runtime_posture"))
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, payloadJson.getString("source_runtime_posture"))
    }

    // ── Normalisation: unknown / blank posture ────────────────────────────────

    @Test
    fun `cross-device route normalises unknown posture to control_only in payload`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open camera", sourceRuntimePosture = "legacy_value")

        val sent = JSONObject(gateway.sentMessages.first())
        val payloadJson = sent.getJSONObject("payload")
        assertEquals(
            "Unknown posture must be normalised to control_only",
            SourceRuntimePosture.CONTROL_ONLY,
            payloadJson.getString("source_runtime_posture")
        )
    }

    @Test
    fun `cross-device route normalises unknown posture to control_only in envelope`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open camera", sourceRuntimePosture = "future_posture_value")

        val envelope = JSONObject(gateway.sentMessages.first())
        assertEquals(
            "Unknown posture must be normalised to control_only in envelope",
            SourceRuntimePosture.CONTROL_ONLY,
            envelope.getString("source_runtime_posture")
        )
    }

    @Test
    fun `cross-device route normalises blank posture to control_only`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open browser", sourceRuntimePosture = "")

        val envelope = JSONObject(gateway.sentMessages.first())
        assertEquals(
            "Blank posture must be normalised to control_only",
            SourceRuntimePosture.CONTROL_ONLY,
            envelope.getString("source_runtime_posture")
        )
    }

    // ── Dual-field contract: payload and envelope must agree ──────────────────

    @Test
    fun `payload posture and envelope posture always match for join_runtime`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("execute goal", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val envelope = JSONObject(gateway.sentMessages.first())
        val payloadPosture = envelope.getJSONObject("payload").getString("source_runtime_posture")
        val envelopePosture = envelope.getString("source_runtime_posture")
        assertEquals("Payload and envelope posture must match", envelopePosture, payloadPosture)
    }

    @Test
    fun `payload posture and envelope posture always match for control_only`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("navigate", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)

        val envelope = JSONObject(gateway.sentMessages.first())
        val payloadPosture = envelope.getJSONObject("payload").getString("source_runtime_posture")
        val envelopePosture = envelope.getString("source_runtime_posture")
        assertEquals("Payload and envelope posture must match", envelopePosture, payloadPosture)
    }

    @Test
    fun `cross-device route includes NL source carrier and V2 semantic authority metadata in payload context`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open maps", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val envelope = JSONObject(gateway.sentMessages.first())
        val extra = envelope
            .getJSONObject("payload")
            .getJSONObject("context")
            .getJSONObject("extra")
        assertEquals(
            AndroidNlSemanticContract.SOURCE_ANDROID_LOCAL_INPUT,
            extra.getString(AndroidNlSemanticContract.KEY_NL_SOURCE)
        )
        assertEquals(
            AndroidNlSemanticContract.CARRIER_ANDROID,
            extra.getString(AndroidNlSemanticContract.KEY_NL_CARRIER)
        )
        assertEquals(
            AndroidNlSemanticContract.PATH_ANDROID_HANDOFF_TO_V2,
            extra.getString(AndroidNlSemanticContract.KEY_NL_PATH)
        )
        assertEquals(
            AndroidNlSemanticContract.SEMANTIC_AUTHORITY_V2,
            extra.getString(AndroidNlSemanticContract.KEY_SEMANTIC_AUTHORITY)
        )
        assertEquals(
            AndroidNlSemanticContract.LOCAL_NL_LAYER_ROLE,
            extra.getString(AndroidNlSemanticContract.KEY_LOCAL_NL_LAYER_ROLE)
        )
    }

    @Test
    fun `cross-device route assembles canonical subject input with NL plus device context plus state snapshot`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open maps and start navigation", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val envelope = JSONObject(gateway.sentMessages.first())
        val payload = envelope.getJSONObject("payload")
        val extra = payload.getJSONObject("context").getJSONObject("extra")
        val canonicalSubjectInput = JSONObject(extra.getString(AndroidNlSemanticContract.KEY_CANONICAL_SUBJECT_INPUT))

        assertTrue(canonicalSubjectInput.has("naturalLanguage"))
        assertTrue(canonicalSubjectInput.has("deviceContext"))
        assertTrue(canonicalSubjectInput.has("stateSnapshot"))
        assertTrue(canonicalSubjectInput.has("mixedContext"))
        assertEquals(
            "open maps and start navigation",
            canonicalSubjectInput.getJSONObject("naturalLanguage").getString("text")
        )
        assertEquals(
            SourceRuntimePosture.JOIN_RUNTIME,
            canonicalSubjectInput.getJSONObject("deviceContext").getString("sourceRuntimePosture")
        )
        assertEquals(
            canonicalSubjectInput.getJSONObject("deviceContext").getString("localeTag"),
            payload.getJSONObject("context").getString("locale")
        )
    }

    @Test
    fun `cross-device route canonical subject input advertises mixed modalities in one unified structure`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("open mail", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)

        val envelope = JSONObject(gateway.sentMessages.first())
        val payload = envelope.getJSONObject("payload")
        val extra = payload.getJSONObject("context").getJSONObject("extra")
        val canonicalSubjectInput = JSONObject(extra.getString(AndroidNlSemanticContract.KEY_CANONICAL_SUBJECT_INPUT))
        val modalities = canonicalSubjectInput.getJSONObject("mixedContext").getJSONArray("modalities")

        val modalityValues = (0 until modalities.length()).map { modalities.getString(it) }.toSet()
        assertEquals(AndroidNlSemanticContract.CANONICAL_MODALITIES.toSet(), modalityValues)
        assertEquals(
            AndroidNlSemanticContract.CANONICAL_MODALITIES.joinToString(separator = ","),
            extra.getString(AndroidNlSemanticContract.KEY_CANONICAL_INPUT_MODALITIES)
        )
    }

    @Test
    fun `canonical subject input changes request packaging strategy and downstream request shaping by posture`() {
        val gateway = FakeGatewayClient()
        val router = buildCrossDeviceRouter(gateway)

        router.route("execute task", sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY)
        router.route("execute task", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val controlEnvelope = JSONObject(gateway.sentMessages[0])
        val controlPayload = controlEnvelope.getJSONObject("payload")
        val controlExtra = controlPayload.getJSONObject("context").getJSONObject("extra")
        val joinEnvelope = JSONObject(gateway.sentMessages[1])
        val joinPayload = joinEnvelope.getJSONObject("payload")
        val joinExtra = joinPayload.getJSONObject("context").getJSONObject("extra")

        assertEquals(
            AndroidNlSemanticContract.RequestPackagingStrategy.CONTROLLED_HANDOFF_BASELINE.wireValue,
            controlExtra.getString(AndroidNlSemanticContract.KEY_REQUEST_PACKAGING_STRATEGY)
        )
        assertEquals(
            AndroidNlSemanticContract.RequestPackagingStrategy.STATEFUL_PARTICIPANT_FUSION.wireValue,
            joinExtra.getString(AndroidNlSemanticContract.KEY_REQUEST_PACKAGING_STRATEGY)
        )
        assertTrue(controlPayload.getString("session_id").startsWith("android-control-"))
        assertTrue(joinPayload.getString("session_id").startsWith("android-participant-"))
        assertTrue(controlEnvelope.getString("idempotency_key").startsWith("controlled-handoff-"))
        assertTrue(joinEnvelope.getString("idempotency_key").startsWith("participant-fusion-"))
    }

    @Test
    fun `canonical subject input runtimeSessionAttached is false when runtime session id is null`() {
        val subjectInput = AndroidNlSemanticContract.buildCanonicalSubjectInput(
            text = "open maps",
            deviceId = "android-test",
            posture = SourceRuntimePosture.CONTROL_ONLY,
            crossDeviceEnabled = true,
            websocketConnected = true,
            runtimeSessionId = null
        )
        assertFalse(subjectInput.stateSnapshot.runtimeSessionAttached)
    }

    @Test
    fun `canonical subject input runtimeSessionAttached is true when runtime session id exists`() {
        val subjectInput = AndroidNlSemanticContract.buildCanonicalSubjectInput(
            text = "open maps",
            deviceId = "android-test",
            posture = SourceRuntimePosture.CONTROL_ONLY,
            crossDeviceEnabled = true,
            websocketConnected = true,
            runtimeSessionId = "runtime-session-1"
        )
        assertTrue(subjectInput.stateSnapshot.runtimeSessionAttached)
    }

    @Test
    fun `canonical subject input falls back to und locale when default locale has no language`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("", ""))
            val subjectInput = AndroidNlSemanticContract.buildCanonicalSubjectInput(
                text = "open maps",
                deviceId = "android-test",
                posture = SourceRuntimePosture.CONTROL_ONLY,
                crossDeviceEnabled = true,
                websocketConnected = true,
                runtimeSessionId = "runtime-session-1"
            )
            assertEquals("und", subjectInput.deviceContext.localeTag)
        } finally {
            Locale.setDefault(original)
        }
    }

    // ── Local path: posture forwarded in LocalLoopOptions ─────────────────────

    @Test
    fun `local route forwards join_runtime posture in LocalLoopOptions`() = runBlocking {
        val capturing = CapturingLocalLoopExecutor()
        val router = buildLocalRouter(localExecutor = capturing)

        router.route("tap the button", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        // Dispatchers.Unconfined executes coroutines eagerly; give it a moment to complete.
        delay(100)
        assertEquals(1, capturing.capturedOptions.size)
        assertEquals(
            "LocalLoopOptions must carry join_runtime posture",
            SourceRuntimePosture.JOIN_RUNTIME,
            capturing.capturedOptions.first().sourceRuntimePosture
        )
    }

    @Test
    fun `local route forwards control_only posture in LocalLoopOptions by default`() = runBlocking {
        val capturing = CapturingLocalLoopExecutor()
        val router = buildLocalRouter(localExecutor = capturing)

        router.route("open settings")

        delay(100)
        assertEquals(1, capturing.capturedOptions.size)
        assertEquals(
            "LocalLoopOptions must carry control_only posture by default",
            SourceRuntimePosture.CONTROL_ONLY,
            capturing.capturedOptions.first().sourceRuntimePosture
        )
    }

    @Test
    fun `local route normalises unknown posture to control_only in LocalLoopOptions`() = runBlocking {
        val capturing = CapturingLocalLoopExecutor()
        val router = buildLocalRouter(localExecutor = capturing)

        router.route("do something", sourceRuntimePosture = "unknown_value")

        delay(100)
        assertEquals(1, capturing.capturedOptions.size)
        assertEquals(
            "Unknown posture must be normalised to control_only for local executor",
            SourceRuntimePosture.CONTROL_ONLY,
            capturing.capturedOptions.first().sourceRuntimePosture
        )
    }

    @Test
    fun `local route logs Android local NL path with non-system semantic authority`() {
        GalaxyLogger.clear()
        val router = buildLocalRouter()

        router.route("open settings", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)

        val routeLocalEntry = GalaxyLogger.getEntries().last {
            it.tag == "InputRouter" && it.fields["event"] == "route_local"
        }
        assertEquals(
            AndroidNlSemanticContract.PATH_ANDROID_LOCAL_NL,
            routeLocalEntry.fields[AndroidNlSemanticContract.KEY_NL_PATH]
        )
        assertEquals(
            AndroidNlSemanticContract.SEMANTIC_AUTHORITY_ANDROID_LOCAL,
            routeLocalEntry.fields[AndroidNlSemanticContract.KEY_SEMANTIC_AUTHORITY]
        )
        assertEquals(
            AndroidNlSemanticContract.LOCAL_NL_LAYER_ROLE,
            routeLocalEntry.fields[AndroidNlSemanticContract.KEY_LOCAL_NL_LAYER_ROLE]
        )
    }

    // ── Posture does not affect routing decision ───────────────────────────────

    @Test
    fun `posture does not affect route mode — cross-device with join_runtime still returns CROSS_DEVICE`() {
        val router = buildCrossDeviceRouter()
        val result = router.route("execute task", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)
        assertEquals(
            "Posture must not influence routing mode decision",
            InputRouter.RouteMode.CROSS_DEVICE,
            result
        )
    }

    @Test
    fun `posture does not affect route mode — local with join_runtime still returns LOCAL`() {
        val router = buildLocalRouter()
        val result = router.route("execute locally", sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME)
        assertEquals(
            "Posture must not influence routing mode decision for local path",
            InputRouter.RouteMode.LOCAL,
            result
        )
    }
}
