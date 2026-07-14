package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.observability.TraceContext
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload
import com.ufo.galaxy.shared.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Pr7AndroidPerceptionEmissionTest {

    private val gson = Gson()

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        override fun screenWidth(): Int = 1080
        override fun screenHeight(): Int = 2400
    }

    private class OkPlanner : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the login button"))
            )

        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(emptyList(), error = "replan not needed")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(
                x = 25,
                y = 50,
                confidence = 0.88f,
                element_description = "login button"
            )
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    @Before
    fun setUp() {
        TraceContext.reset()
    }

    @After
    fun tearDown() {
        TraceContext.reset()
    }

    @Test
    fun `DEVICE_PERCEPTION_EMISSION wire value is device_perception_emission`() {
        assertEquals("device_perception_emission", MsgType.DEVICE_PERCEPTION_EMISSION.value)
        assertNotEquals(MsgType.DEVICE_PERCEPTION_EMISSION, MsgType.DEVICE_EXECUTION_EVENT)
    }

    @Test
    fun `edge executor emits one-shot vision request then multimodal participation signal`() {
        TraceContext.acceptServerTraceId("trace-android-perception")
        TraceContext.setDispatchTraceId("dispatch-android-perception")
        val emitted = mutableListOf<DevicePerceptionEmissionPayload>()
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = OkPlanner(),
            groundingService = OkGrounder(),
            accessibilityExecutor = OkAccessibility(),
            perceptionEmissionSink = DevicePerceptionEmissionSink { emitted += it }
        )

        val result = edge.handleTaskAssign(
            TaskAssignPayload(
                task_id = "task-perception-1",
                goal = "open login",
                constraints = emptyList(),
                max_steps = 3,
                require_local_agent = true
            )
        )

        assertEquals(EdgeExecutor.STATUS_SUCCESS, result.status)
        assertEquals(2, emitted.size)

        val oneShot = emitted[0]
        assertEquals(
            DevicePerceptionEmissionPayload.EMISSION_KIND_ONE_SHOT_VISION_REQUEST,
            oneShot.emission_kind
        )
        assertEquals(DevicePerceptionEmissionPayload.STAGE_PLANNING, oneShot.perception_stage)
        assertEquals(
            DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_VISION_PROBE,
            oneShot.carrier_semantics
        )
        assertEquals(
            DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST,
            oneShot.participation_semantics
        )
        assertFalse(oneShot.participates_in_multimodal_main_chain)
        assertEquals("trace-android-perception", oneShot.trace_id)
        assertEquals("dispatch-android-perception", oneShot.dispatch_trace_id)
        assertNotNull(oneShot.screenshot)
        assertEquals("open login", oneShot.vision_payload?.prompt_text)
        assertEquals("planner_context", oneShot.vision_payload?.request_reason)
        assertEquals(1080, oneShot.local_perception_payload?.screen_width)
        assertEquals(2400, oneShot.local_perception_payload?.screen_height)
        assertTrue(oneShot.local_perception_payload?.planner_participated == true)
        assertTrue(oneShot.local_perception_payload?.grounding_participated == false)
        assertNull(oneShot.grounding_payload)

        val participation = emitted[1]
        assertEquals(
            DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
            participation.emission_kind
        )
        assertEquals(DevicePerceptionEmissionPayload.STAGE_GROUNDING, participation.perception_stage)
        assertEquals(
            DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
            participation.carrier_semantics
        )
        assertEquals(
            DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
            participation.participation_semantics
        )
        assertTrue(participation.participates_in_multimodal_main_chain)
        assertEquals(0, participation.step_index)
        assertEquals("tap the login button", participation.vision_payload?.prompt_text)
        assertEquals("grounding_resolution", participation.vision_payload?.request_reason)
        assertEquals("tap the login button", participation.grounding_payload?.intent)
        assertEquals(1080, participation.screenshot?.width)
        assertEquals(2400, participation.screenshot?.height)
        assertEquals(25, participation.grounding_payload?.result_x)
        assertEquals(50, participation.grounding_payload?.result_y)
        assertEquals(0.88f, participation.grounding_payload?.confidence)
        assertEquals("login button", participation.grounding_payload?.element_description)
        assertEquals("trace-android-perception", participation.trace_id)
        assertEquals("dispatch-android-perception", participation.dispatch_trace_id)
    }

    @Test
    fun `payload serializes unified nested perception contracts and trace fields`() {
        val payload = DevicePerceptionEmissionPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
            perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
            carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
            participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
            participates_in_multimodal_main_chain = true,
            trace_id = "trace-1",
            dispatch_trace_id = "dispatch-1",
            source_component = "EdgeExecutor"
        )

        val json = gson.toJsonTree(payload).asJsonObject

        assertEquals("multimodal_participation_signal", json.get("emission_kind").asString)
        assertEquals("grounding", json.get("perception_stage").asString)
        assertEquals("multimodal_main_chain", json.get("carrier_semantics").asString)
        assertEquals("main_chain_input", json.get("participation_semantics").asString)
        assertTrue(json.get("participates_in_multimodal_main_chain").asBoolean)
        assertEquals("trace-1", json.get("trace_id").asString)
        assertEquals("dispatch-1", json.get("dispatch_trace_id").asString)
        assertTrue(json.get("event_ts").asDouble > 0.0)
    }

    @Test
    fun `AipMessage envelope type is DEVICE_PERCEPTION_EMISSION`() {
        val payload = DevicePerceptionEmissionPayload(
            flow_id = "flow-2",
            task_id = "task-2",
            emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_ONE_SHOT_VISION_REQUEST,
            perception_stage = DevicePerceptionEmissionPayload.STAGE_PLANNING,
            carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_VISION_PROBE,
            participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST,
            participates_in_multimodal_main_chain = false,
            trace_id = "trace-2"
        )
        val envelope = AipMessage(
            type = MsgType.DEVICE_PERCEPTION_EMISSION,
            payload = gson.toJsonTree(payload),
            device_id = "device-1",
            correlation_id = payload.task_id,
            trace_id = payload.trace_id,
            runtime_session_id = "runtime-1",
            idempotency_key = payload.emission_id
        )

        val json = gson.toJsonTree(envelope).asJsonObject

        assertEquals(MsgType.DEVICE_PERCEPTION_EMISSION.value, json.get("type").asString)
        assertEquals("device-1", json.get("device_id").asString)
        assertEquals("task-2", json.get("correlation_id").asString)
        assertEquals("runtime-1", json.get("runtime_session_id").asString)
        assertEquals(payload.emission_id, json.get("idempotency_key").asString)
    }

    @Test
    fun `sink lambda accumulates emissions in order`() {
        val emitted = mutableListOf<String>()
        val sink = DevicePerceptionEmissionSink { emitted += it.emission_kind }

        sink.onEmission(
            DevicePerceptionEmissionPayload(
                flow_id = "f1",
                task_id = "t1",
                emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_ONE_SHOT_VISION_REQUEST,
                perception_stage = DevicePerceptionEmissionPayload.STAGE_PLANNING,
                carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_VISION_PROBE,
                participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST,
                participates_in_multimodal_main_chain = false
            )
        )
        sink.onEmission(
            DevicePerceptionEmissionPayload(
                flow_id = "f1",
                task_id = "t1",
                emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                participates_in_multimodal_main_chain = true
            )
        )

        assertEquals(
            listOf(
                DevicePerceptionEmissionPayload.EMISSION_KIND_ONE_SHOT_VISION_REQUEST,
                DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL
            ),
            emitted
        )
    }

    @Test
    fun `TAG_DEVICE_PERCEPTION_EMISSION has expected format`() {
        assertEquals("GALAXY:DEVICE:PERCEPTION", GalaxyLogger.TAG_DEVICE_PERCEPTION_EMISSION)
    }
}
