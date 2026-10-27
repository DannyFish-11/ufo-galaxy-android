package com.ufo.galaxy.phone

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机此刻的状态(V2 `core/device_state.py` 读):只报手机确知的,不知道的不填。
 */
class PhoneStateTest {

    @Test
    fun `what the phone reads goes on the wire as it is`() {
        val s = PhoneState.build(PhoneState.Readings(battery = 0.8f, charging = true, screenOn = false, inCall = true, dnd = false)).toJson()
        assertEquals(setOf("battery", "charging", "screen_on", "in_call"), s.keys)
        assertEquals(0.8f, s["battery"]!!.jsonPrimitive.float, 0.0001f)
        assertTrue(s["in_call"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `do not disturb is the person saying so, and the phone passes it on`() {
        val s = PhoneState.build(PhoneState.Readings(dnd = true)).toJson()
        assertEquals("blocked", s["interruptible"]!!.jsonPrimitive.content)
        assertTrue(s["note"]!!.jsonPrimitive.content.contains("勿扰"))
    }

    @Test
    fun `without do not disturb the phone does not guess whether the person can be interrupted`() {
        assertFalse("interruptible" in PhoneState.build(PhoneState.Readings(dnd = false, screenOn = true)).toJson())
        assertFalse("interruptible" in PhoneState.build(PhoneState.Readings()).toJson())
    }

    @Test
    fun `what could not be read is left out, and the phone never says whether it is worn`() {
        assertTrue(PhoneState.build(PhoneState.Readings()).toJson().isEmpty())
        assertFalse("worn" in PhoneState.build(PhoneState.Readings(battery = 1f, charging = true, screenOn = true, inCall = false, dnd = true)).toJson())
    }
}
