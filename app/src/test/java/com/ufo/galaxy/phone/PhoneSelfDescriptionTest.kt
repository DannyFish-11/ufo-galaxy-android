package com.ufo.galaxy.phone

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机的设备自述(V2 `core/device_self.py` 读):做得了的动作带 effect / 参数 / 要不要人点,
 * 权限没给的进 unavailable 附原因,四样声明照手机真实情况填。
 */
class PhoneSelfDescriptionTest {

    private val all: (PhoneActionCatalog.Need) -> Boolean = { true }
    private val noContacts: (PhoneActionCatalog.Need) -> Boolean = { it != PhoneActionCatalog.Need.CONTACTS }

    private fun actions(available: (PhoneActionCatalog.Need) -> Boolean) =
        PhoneSelfDescription.build(available, "Pixel 9", "Pixel 9", "bridge/1")["actions"]!!.jsonArray
            .associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }

    @Test
    fun `every catalog action says what doing it means`() {
        val byName = actions(all)
        assertEquals(PhoneActionCatalog.ACTIONS.map { it.name }.toSet(), byName.keys)
        val effects = byName.mapValues { it.value["effect"]!!.jsonPrimitive.content }
        assertEquals("notify", effects["notification"])
        assertEquals("read", effects["location"])
        assertEquals("read", effects["calendar"])
        assertEquals("read", effects["contacts"])
        assertEquals("read", effects["screen_capture"])
        assertEquals("send", effects["sms"])
        assertEquals("send", effects["phone_call"])
        assertEquals("act", effects["app_control"])
        assertEquals("act", effects["calendar_add"])
    }

    @Test
    fun `what only gets ready on the phone says a person must tap`() {
        val byName = actions(all)
        for (a in listOf("sms", "phone_call", "calendar_add")) {
            assertTrue(a, byName[a]!!["needs_human"]!!.jsonPrimitive.boolean)
        }
        assertFalse("needs_human" in byName["app_control"]!!.keys)
    }

    @Test
    fun `parameters are a schema the center can check before sending`() {
        val sms = actions(all)["sms"]!!["params"]!!.jsonObject
        assertEquals(listOf("to", "text"), sms["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val sensor = actions(all)["sensor_data"]!!["params"]!!.jsonObject["properties"]!!.jsonObject["sensor"]!!.jsonObject
        assertTrue(sensor["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("accelerometer"))
    }

    @Test
    fun `a permission not given moves the action to unavailable with where to turn it on`() {
        val desc = PhoneSelfDescription.build(noContacts, "Pixel 9", "Pixel 9", "bridge/1")
        val names = desc["actions"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertFalse("contacts" in names)
        val u = desc["unavailable"]!!.jsonArray.single().jsonObject
        assertEquals("contacts", u["name"]!!.jsonPrimitive.content)
        assertTrue(u["reason"]!!.jsonPrimitive.content, u["reason"]!!.jsonPrimitive.content.contains("通讯录"))
    }

    @Test
    fun `the four declarations are what this phone really does`() {
        val d = PhoneSelfDescription.build(all, "Pixel 9", "Pixel 9", "bridge/1")["declares"]!!.jsonObject
        // 提问弹成通知(PhoneDecisions):通知打得开就能回答
        assertTrue(d["answers_decisions"]!!.jsonPrimitive.boolean)
        val noNotifications: (PhoneActionCatalog.Need) -> Boolean = { it != PhoneActionCatalog.Need.NOTIFICATIONS }
        val blocked = PhoneSelfDescription.build(noNotifications, "Pixel 9", "Pixel 9", "bridge/1")["declares"]!!.jsonObject
        assertFalse("通知打不开就不声明,免得中心白等", blocked["answers_decisions"]!!.jsonPrimitive.boolean)
        assertTrue(d["shows_request_progress"]!!.jsonPrimitive.boolean)
        assertEquals("bridge/1", d["hosts_agent"]!!.jsonPrimitive.content)
        assertEquals("none", d["tailnet"]!!.jsonPrimitive.content)
    }

    @Test
    fun `it describes itself in words for the model and names its kind only as a description`() {
        val desc = PhoneSelfDescription.build(all, "Pixel 9", "Pixel 9", "")
        assertEquals("Pixel 9", desc["name"]!!.jsonPrimitive.content)
        assertEquals("android_phone", desc["kind"]!!.jsonPrimitive.content)
        assertTrue(desc["about"]!!.jsonPrimitive.content.contains("(Pixel 9)"))
        assertEquals("", desc["declares"]!!.jsonObject["hosts_agent"]!!.jsonPrimitive.content)
    }
}
