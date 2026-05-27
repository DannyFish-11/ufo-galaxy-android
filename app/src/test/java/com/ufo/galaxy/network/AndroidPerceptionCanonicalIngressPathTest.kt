package com.ufo.galaxy.network

import com.google.gson.JsonParser
import com.ufo.galaxy.protocol.DeviceGroundingPayload
import com.ufo.galaxy.protocol.DeviceLocalPerceptionPayload
import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload
import com.ufo.galaxy.protocol.DeviceVisionPayload
import com.ufo.galaxy.protocol.MsgType
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPerceptionCanonicalIngressPathTest {

    private class RecordingWebSocket : WebSocket {
        val textMessages = mutableListOf<String>()

        override fun request(): Request = Request.Builder().url("ws://localhost:9999").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            textMessages += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() = Unit
    }

    private fun buildClient(): GalaxyWebSocketClient =
        GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true,
            offlineQueue = OfflineTaskQueue(prefs = null)
        )

    @Test
    fun `perception emission with grounding keeps canonical main-chain ingress semantics`() {
        val client = buildClient()
        val socket = RecordingWebSocket()
        client.installWebSocketForTest(socket)
        client.simulateConnected()

        val sent = client.sendDevicePerceptionEmission(
            DevicePerceptionEmissionPayload(
                flow_id = "flow-main-chain",
                task_id = "task-main-chain",
                emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                participates_in_multimodal_main_chain = true,
                vision_payload = DeviceVisionPayload(prompt_text = "tap login"),
                grounding_payload = DeviceGroundingPayload(
                    intent = "tap login",
                    input_width = 1080,
                    input_height = 2400
                ),
                local_perception_payload = DeviceLocalPerceptionPayload(
                    capture_present = true,
                    planner_participated = true,
                    grounding_participated = true
                )
            )
        )

        assertTrue(sent)
        val envelope = JsonParser.parseString(socket.textMessages.single()).asJsonObject
        assertEquals(MsgType.DEVICE_PERCEPTION_EMISSION.value, envelope.get("type").asString)
        val payload = envelope.getAsJsonObject("payload")
        assertEquals(
            DevicePerceptionEmissionPayload.CANONICAL_INGRESS_PROTOCOL_ANDROID_PERCEPTION,
            payload.get("canonical_ingress_protocol").asString
        )
        assertEquals(
            DevicePerceptionEmissionPayload.CANONICAL_INGRESS_TARGET_V2_PERCEPTION_FRAME,
            payload.get("canonical_ingress_target").asString
        )
        assertEquals(
            DevicePerceptionEmissionPayload.DOWNSTREAM_CONSUMPTION_ROUTE_CONTEXT_PLANNING,
            payload.get("downstream_consumption_semantics").asString
        )
        assertTrue(payload.get("canonical_main_chain_eligible").asBoolean)
        assertEquals(
            DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
            payload.get("participation_semantics").asString
        )
    }

    @Test
    fun `perception emission without grounding is downgraded to vision probe semantics`() {
        val client = buildClient()
        val socket = RecordingWebSocket()
        client.installWebSocketForTest(socket)
        client.simulateConnected()

        val sent = client.sendDevicePerceptionEmission(
            DevicePerceptionEmissionPayload(
                flow_id = "flow-probe",
                task_id = "task-probe",
                emission_kind = DevicePerceptionEmissionPayload.EMISSION_KIND_MULTIMODAL_PARTICIPATION_SIGNAL,
                perception_stage = DevicePerceptionEmissionPayload.STAGE_GROUNDING,
                carrier_semantics = DevicePerceptionEmissionPayload.CARRIER_SEMANTICS_MULTIMODAL_MAIN_CHAIN,
                participation_semantics = DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
                participates_in_multimodal_main_chain = true,
                vision_payload = DeviceVisionPayload(prompt_text = "tap login"),
                local_perception_payload = DeviceLocalPerceptionPayload(capture_present = true)
            )
        )

        assertTrue(sent)
        val envelope = JsonParser.parseString(socket.textMessages.single()).asJsonObject
        val payload = envelope.getAsJsonObject("payload")
        assertEquals(
            DevicePerceptionEmissionPayload.DOWNSTREAM_CONSUMPTION_VISION_PROBE_ONLY,
            payload.get("downstream_consumption_semantics").asString
        )
        assertFalse(payload.get("canonical_main_chain_eligible").asBoolean)
        assertEquals(
            DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_ONE_SHOT_REQUEST,
            payload.get("participation_semantics").asString
        )
        assertEquals(
            DevicePerceptionEmissionPayload.EMISSION_KIND_ONE_SHOT_VISION_REQUEST,
            payload.get("emission_kind").asString
        )
    }
}
