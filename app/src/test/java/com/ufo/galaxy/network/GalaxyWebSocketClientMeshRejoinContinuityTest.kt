package com.ufo.galaxy.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GalaxyWebSocketClientMeshRejoinContinuityTest {

    @Test
    fun `buildMeshRejoinEnvelopeForTest returns null when no mesh participation context exists`() {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true
        )

        assertNull(client.buildMeshRejoinEnvelopeForTest())
    }

    @Test
    fun `sendMeshJoin captures mesh participation context for reconnect rejoin envelope`() {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true
        )

        val sent = client.sendMeshJoin(
            meshId = "mesh-alpha",
            role = "participant",
            capabilities = listOf("parallel_subtask", "delegated_takeover")
        )
        assertFalse("Precondition: no live socket means sendMeshJoin returns false", sent)

        val rejoinJson = client.buildMeshRejoinEnvelopeForTest()
        assertNotNull("Mesh participation context should still be available for reconnect rejoin", rejoinJson)

        val root = JsonParser.parseString(rejoinJson).asJsonObject
        val payload = root.getAsJsonObject("payload")

        assertEquals("mesh_join", root.get("type").asString)
        assertEquals("mesh-alpha", payload.get("mesh_id").asString)
        assertEquals("participant", payload.get("role").asString)
        assertEquals(2, payload.getAsJsonArray("capabilities").size())
    }

    @Test
    fun `sendMeshLeave clears mesh participation context so no stale rejoin is emitted`() {
        val client = GalaxyWebSocketClient(
            serverUrl = "ws://localhost:9999",
            crossDeviceEnabled = true
        )

        client.sendMeshJoin(meshId = "mesh-beta", role = "participant", capabilities = listOf("parallel_subtask"))
        assertNotNull(client.buildMeshRejoinEnvelopeForTest())

        client.sendMeshLeave(meshId = "mesh-beta", reason = "disconnect")

        assertNull("After mesh leave, reconnect should not attempt a stale mesh rejoin", client.buildMeshRejoinEnvelopeForTest())
    }
}
