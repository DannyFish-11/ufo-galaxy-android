package com.ufo.galaxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机报能力时带上每个动作的一句话说明(整体方案阶段 1:模型把设备当整体看,自己决定让谁做什么)。
 */
class PhoneActionDescriptionsTest {

    private val baseActions = listOf(
        "location", "camera", "sensor_data", "automation",
        "notification", "sms", "phone_call", "contacts",
        "calendar", "voice_input", "screen_capture", "app_control",
    )

    @Test
    fun `every base action the phone always reports has a description`() {
        val d = PhoneActionDescriptions.forActions(baseActions)
        assertEquals(baseActions.toSet(), d.keys)
        assertTrue(d.values.all { it.isNotBlank() })
    }

    @Test
    fun `only reported actions get a description and unknown ones are not made up`() {
        val d = PhoneActionDescriptions.forActions(listOf("camera", "made_up_action"))
        assertEquals(setOf("camera"), d.keys)
        assertFalse(d.containsKey("made_up_action"))
    }

    @Test
    fun `actions that reach other people say they need confirmation`() {
        val d = PhoneActionDescriptions.DESCRIPTIONS
        assertTrue(d.getValue("sms").contains("确认"))
        assertTrue(d.getValue("phone_call").contains("确认"))
    }
}
