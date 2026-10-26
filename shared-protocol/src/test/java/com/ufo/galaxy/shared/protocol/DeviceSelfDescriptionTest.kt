package com.ufo.galaxy.shared.protocol

import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Action
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Declares
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Effect
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Params
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Unavailable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备自述的线格式,按 V2 `contracts/device_self_v1.json` 钉死(V2 `core/device_self.py` 读它)。
 * 键名、取值范围两边必须逐字一致 —— 读错一个键,中心就当这台设备没说。
 */
class DeviceSelfDescriptionTest {

    private fun sample() = DeviceSelfDescription(
        name = "Pixel 9",
        kind = "android_phone",
        about = "一台安卓手机",
        actions = listOf(
            Action(
                "sms", "在手机上备好一条短信,人点发送才发", Effect.SEND,
                Params.obj("to" to Params.string("号码"), "text" to Params.string("内容"), required = listOf("to", "text")),
                needsHuman = true,
            ),
            Action("location", "读当前位置", Effect.READ),
        ),
        unavailable = listOf(Unavailable("contacts", "没给通讯录权限")),
        declares = Declares(showsRequestProgress = true, hostsAgent = "bridge/1"),
    )

    @Test
    fun `the top level carries exactly the keys the center reads`() {
        val json = sample().toJson()
        assertEquals(setOf("schema", "name", "kind", "about", "actions", "unavailable", "declares"), json.keys)
        assertEquals("device_self/1", json["schema"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an action says what it does, what doing it means and whether a person must tap`() {
        val sms = sample().toJson()["actions"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("name", "description", "effect", "params", "needs_human"), sms.keys)
        assertEquals("send", sms["effect"]!!.jsonPrimitive.content)
        assertTrue(sms["needs_human"]!!.jsonPrimitive.boolean)
        val params = sms["params"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("to", "text"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 不要人点的动作不带这个键(省得每个动作都多一行 false)
        val location = sample().toJson()["actions"]!!.jsonArray[1].jsonObject
        assertFalse("needs_human" in location.keys)
        assertEquals(setOf("type", "properties"), location["params"]!!.jsonObject.keys)
    }

    @Test
    fun `there are exactly four device-level declarations`() {
        val declares = sample().toJson()["declares"]!!.jsonObject
        assertEquals(setOf("answers_decisions", "shows_request_progress", "hosts_agent", "tailnet"), declares.keys)
        assertEquals("none", declares["tailnet"]!!.jsonPrimitive.content)
        assertEquals("bridge/1", declares["hosts_agent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `what cannot be done right now travels with its reason`() {
        val u = sample().toJson()["unavailable"]!!.jsonArray.single().jsonObject
        assertEquals("contacts", u["name"]!!.jsonPrimitive.content)
        assertEquals("没给通讯录权限", u["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty optional parts are left out rather than sent blank`() {
        val bare = DeviceSelfDescription(actions = emptyList(), declares = Declares()).toJson()
        assertEquals(setOf("schema", "actions", "declares"), bare.keys)
    }

    @Test
    fun `the effects and tailnet modes are the five and three the center knows`() {
        assertEquals(setOf("read", "notify", "act", "send", "danger"), Effect.ALL)
        assertEquals(setOf("embedded", "system", "none"), DeviceSelfDescription.Tailnet.ALL)
    }

    @Test
    fun `an action the center would not understand is refused when it is built, not on the wire`() {
        assertThrows { Action("Send SMS", "x", Effect.SEND) }
        assertThrows { Action("sms", " ", Effect.SEND) }
        assertThrows { Action("sms", "x", "write") }
        assertThrows { Unavailable("sms", "") }
        assertThrows { Declares(tailnet = "vpn") }
    }

    @Test
    fun `long texts are cut to what the schema allows`() {
        val long = "字".repeat(400)
        val json: JsonObject = DeviceSelfDescription(
            about = long,
            actions = listOf(Action("x", long, Effect.READ)),
            unavailable = listOf(Unavailable("y", long)),
            declares = Declares(),
        ).toJson()
        assertEquals(300, json["about"]!!.jsonPrimitive.content.length)
        assertEquals(300, json["actions"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content.length)
        assertEquals(200, json["unavailable"]!!.jsonArray[0].jsonObject["reason"]!!.jsonPrimitive.content.length)
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = runCatching(block).isFailure
        assertTrue("应该被拒", threw)
    }
}
