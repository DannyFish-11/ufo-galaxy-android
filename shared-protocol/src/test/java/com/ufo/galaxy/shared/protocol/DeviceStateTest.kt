package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备状态的线格式,按 V2 `contracts/device_state_v1.json` 钉死(V2 `core/device_state.py` 读它)。
 * 不知道的不填:中心看到没有这个键,就当设备现在不知道,不替它推断。
 */
class DeviceStateTest {

    @Test
    fun `only what the device knows goes on the wire, with the keys the center reads`() {
        val s = DeviceState(battery = 0.624f, charging = false, screenOn = true, inCall = false).toJson()
        assertEquals(setOf("battery", "charging", "screen_on", "in_call"), s.keys)
        assertEquals(0.62f, s["battery"]!!.jsonPrimitive.float, 0.0001f)
        assertTrue(s["screen_on"]!!.jsonPrimitive.boolean)
        assertFalse(s["charging"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `every key the schema allows, and nothing else`() {
        val s = DeviceState(
            battery = 1f, charging = true, screenOn = false, worn = true, inCall = true,
            interruptible = DeviceState.Interruptible.BLOCKED, note = "开着勿扰",
        ).toJson()
        assertEquals(setOf("battery", "charging", "screen_on", "worn", "in_call", "interruptible", "note"), s.keys)
        assertEquals("blocked", s["interruptible"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty state is an empty object, a blank note is left out and a long one is cut`() {
        assertTrue(DeviceState().toJson().isEmpty())
        assertFalse("note" in DeviceState(note = "   ").toJson())
        assertEquals(DeviceState.MAX_NOTE, DeviceState(note = "很".repeat(500)).toJson()["note"]!!.jsonPrimitive.content.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an interruptible value the center does not know is refused here, not sent`() {
        DeviceState(interruptible = "low")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `battery is a fraction, not a percentage`() {
        DeviceState(battery = 62f)
    }

    @Test
    fun `the four interruptible values match the center`() {
        assertEquals(setOf("blocked", "busy", "neutral", "free"), DeviceState.Interruptible.ALL)
        assertEquals("state", DeviceState.KEY)
    }
}
