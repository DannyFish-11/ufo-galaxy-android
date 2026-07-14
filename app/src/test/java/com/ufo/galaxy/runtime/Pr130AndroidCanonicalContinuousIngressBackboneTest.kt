package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.protocol.DeviceGroundingPayload
import com.ufo.galaxy.protocol.DeviceLocalPerceptionPayload
import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload
import com.ufo.galaxy.protocol.DeviceVisionPayload
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.webrtc.SignalingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr130AndroidCanonicalContinuousIngressBackboneTest {

    private class FakePlanner : LocalPlannerService {
        var lastConstraints: List<String> = emptyList()

        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?): LocalPlannerService.PlanResult {
            lastConstraints = constraints
            return LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap login"))
            )
        }

        override fun replan(
            goal: String,
            constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String,
            screenshotBase64: String?
        ): LocalPlannerService.PlanResult {
            lastConstraints = constraints
            return LocalPlannerService.PlanResult(emptyList())
        }
    }

    private class FakeGrounding : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 10, y = 20, confidence = 0.9f, element_description = "")
    }

    private class FakeAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg(): ByteArray = byteArrayOf(1, 2, 3)
        override fun screenWidth(): Int = 1080
        override fun screenHeight(): Int = 2400
    }

    @Test
    fun `unified canonical continuous ingress is constructed from android stream and runtime manager input`() {
        val manager = AndroidCanonicalContinuousIngressSessionManager()
        manager.recordAndroidDeviceContinuousStream(
            DevicePerceptionEmissionPayload(
                flow_id = "flow-1",
                task_id = "task-1",
                emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                participates_in_multimodal_main_chain = true,
                vision_payload = DeviceVisionPayload(prompt_text = "tap login"),
                grounding_payload = DeviceGroundingPayload(intent = "tap login", input_width = 1080, input_height = 2400),
                local_perception_payload = DeviceLocalPerceptionPayload(capture_present = true)
            )
        )
        manager.recordRuntimeStreamingSessionInput(
            AndroidCanonicalContinuousIngressBackbone.RuntimeStreamingSessionInput(
                runtimeState = "active",
                crossDeviceEligible = true,
                hasAttachedSession = true,
                durableSessionId = "durable-1",
                traceId = "trace-1"
            )
        )

        val snapshot = manager.currentSnapshot()
        assertTrue(snapshot.hasUnifiedContinuousFamilies)
        assertTrue(snapshot.families.contains(AndroidCanonicalContinuousIngressBackbone.IngressFamily.ANDROID_DEVICE_CONTINUOUS_STREAM))
        assertTrue(snapshot.families.contains(AndroidCanonicalContinuousIngressBackbone.IngressFamily.RUNTIME_STREAMING_SESSION_MANAGER_INPUT))
        assertEquals(
            AndroidCanonicalContinuousIngressBackbone.MainlineBehavior.CONTINUOUS_CONTEXT_PREPARATION,
            snapshot.mainlineBehavior()
        )
    }

    @Test
    fun `stream-backed canonical ingress is consumed by planning mainline`() {
        val planner = FakePlanner()
        val manager = AndroidCanonicalContinuousIngressSessionManager().apply {
            recordAndroidDeviceContinuousStream(
                DevicePerceptionEmissionPayload(
                    flow_id = "flow-2",
                    task_id = "task-2",
                    emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                    perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                    carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                    participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                    participates_in_multimodal_main_chain = true,
                    vision_payload = DeviceVisionPayload(prompt_text = "tap"),
                    grounding_payload = DeviceGroundingPayload(intent = "tap", input_width = 1080, input_height = 2400),
                    local_perception_payload = DeviceLocalPerceptionPayload(capture_present = true)
                )
            )
            recordRuntimeStreamingSessionInput(
                AndroidCanonicalContinuousIngressBackbone.RuntimeStreamingSessionInput(
                    runtimeState = "active",
                    crossDeviceEligible = true,
                    hasAttachedSession = true,
                    durableSessionId = "durable-2",
                    traceId = "trace-2"
                )
            )
        }
        val edgeExecutor = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = planner,
            groundingService = FakeGrounding(),
            accessibilityExecutor = FakeAccessibility(),
            continuousIngressProvider = { manager.currentSnapshot() }
        )

        edgeExecutor.handleTaskAssign(
            TaskAssignPayload(
                task_id = "task-2",
                goal = "open login",
                constraints = listOf("must_be_safe"),
                max_steps = 1,
                require_local_agent = true
            )
        )

        assertTrue(
            planner.lastConstraints.contains(
                AndroidCanonicalContinuousIngressBackbone.CONSTRAINT_CONTINUOUS_INGRESS_CONTEXT_READY
            )
        )
    }

    @Test
    fun `planning behavior differs between no continuous ingress and unified continuous ingress`() {
        val plannerWithout = FakePlanner()
        val plannerWith = FakePlanner()

        val withoutStreamExecutor = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = plannerWithout,
            groundingService = FakeGrounding(),
            accessibilityExecutor = FakeAccessibility(),
            continuousIngressProvider = { AndroidCanonicalContinuousIngressBackbone.assemble(emptyList()) }
        )

        val manager = AndroidCanonicalContinuousIngressSessionManager().apply {
            recordAndroidDeviceContinuousStream(
                DevicePerceptionEmissionPayload(
                    flow_id = "flow-3",
                    task_id = "task-3",
                    emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                    perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                    carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                    participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                    participates_in_multimodal_main_chain = true,
                    vision_payload = DeviceVisionPayload(prompt_text = "tap"),
                    grounding_payload = DeviceGroundingPayload(intent = "tap", input_width = 1080, input_height = 2400),
                    local_perception_payload = DeviceLocalPerceptionPayload(capture_present = true)
                )
            )
            recordRuntimeStreamingSessionInput(
                AndroidCanonicalContinuousIngressBackbone.RuntimeStreamingSessionInput(
                    runtimeState = "active",
                    crossDeviceEligible = true,
                    hasAttachedSession = true,
                    durableSessionId = "durable-3",
                    traceId = "trace-3"
                )
            )
        }
        val withStreamExecutor = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = plannerWith,
            groundingService = FakeGrounding(),
            accessibilityExecutor = FakeAccessibility(),
            continuousIngressProvider = { manager.currentSnapshot() }
        )

        val task = TaskAssignPayload(
            task_id = "task-3",
            goal = "open app",
            constraints = listOf("must_be_safe"),
            max_steps = 1,
            require_local_agent = true
        )
        withoutStreamExecutor.handleTaskAssign(task)
        withStreamExecutor.handleTaskAssign(task)

        assertFalse(
            plannerWithout.lastConstraints.contains(
                AndroidCanonicalContinuousIngressBackbone.CONSTRAINT_CONTINUOUS_INGRESS_CONTEXT_READY
            )
        )
        assertTrue(
            plannerWith.lastConstraints.contains(
                AndroidCanonicalContinuousIngressBackbone.CONSTRAINT_CONTINUOUS_INGRESS_CONTEXT_READY
            )
        )
    }

    @Test
    fun `webrtc transport ingress is promoted into canonical cognition preparation when fused`() {
        val webrtcSignal = AndroidCanonicalContinuousIngressBackbone.fromWebRtcSignaling(
            SignalingMessage(
                type = SignalingMessage.TYPE_ICE_CANDIDATES,
                candidates = listOf(
                    SignalingMessage.IceCandidate(
                        candidate = "candidate:3 1 UDP 33562623 5.6.7.8 3478 typ relay",
                        sdpMid = "0",
                        sdpMLineIndex = 0
                    )
                ),
                deviceId = "dev-1",
                traceId = "trace-webrtc"
            )
        )
        assertTrue(webrtcSignal != null)

        val snapshot = AndroidCanonicalContinuousIngressBackbone.assemble(
            listOfNotNull(
                webrtcSignal,
                AndroidCanonicalContinuousIngressBackbone.fromDevicePerception(
                    DevicePerceptionEmissionPayload(
                        flow_id = "flow-4",
                        task_id = "task-4",
                        emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                        perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                        carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                        participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                        participates_in_multimodal_main_chain = true,
                        vision_payload = DeviceVisionPayload(prompt_text = "tap"),
                        grounding_payload = DeviceGroundingPayload(intent = "tap", input_width = 1080, input_height = 2400),
                        local_perception_payload = DeviceLocalPerceptionPayload(capture_present = true)
                    )
                ),
                AndroidCanonicalContinuousIngressBackbone.fromRuntimeStreamingSessionInput(
                    AndroidCanonicalContinuousIngressBackbone.RuntimeStreamingSessionInput(
                        runtimeState = "active",
                        crossDeviceEligible = true,
                        hasAttachedSession = true,
                        durableSessionId = "durable-4",
                        traceId = "trace-4"
                    )
                )
            )
        )

        assertTrue(snapshot.families.contains(AndroidCanonicalContinuousIngressBackbone.IngressFamily.WEBRTC_STREAM_INGRESS))
        assertEquals(
            AndroidCanonicalContinuousIngressBackbone.MainlineBehavior.STREAM_FUSED_CONTINUOUS_PREPARATION,
            snapshot.mainlineBehavior()
        )
    }
}
