package com.ufo.galaxy.network

import com.ufo.galaxy.phone.PhoneActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机报能力时带上每个动作的一句话说明(整体方案阶段 1:模型把设备当整体看,自己决定让谁做什么)。
 */
class PhoneActionDescriptionsTest {

    private val directActions = PhoneActionCatalog.ACTIONS.map { it.name }

    @Test
    fun `every direct action the phone can report has a description`() {
        val d = PhoneActionDescriptions.forActions(directActions)
        assertEquals(directActions.toSet(), d.keys)
        assertTrue(d.values.all { it.isNotBlank() })
    }

    @Test
    fun `only reported actions get a description and unknown ones are not made up`() {
        val d = PhoneActionDescriptions.forActions(listOf("location", "made_up_action"))
        assertEquals(setOf("location"), d.keys)
        assertFalse(d.containsKey("made_up_action"))
    }

    @Test
    fun `actions that reach other people say a person confirms them on the phone`() {
        val d = PhoneActionDescriptions.DESCRIPTIONS
        listOf("sms", "phone_call", "calendar_add").forEach { assertTrue(it, d.getValue(it).contains("确认")) }
    }

    @Test
    fun `descriptions say which parameters to pass`() {
        val d = PhoneActionDescriptions.DESCRIPTIONS
        assertTrue(d.getValue("sms").contains("to") && d.getValue("sms").contains("text"))
        assertTrue(d.getValue("contacts").contains("query"))
        assertTrue(d.getValue("app_control").contains("app"))
    }
}
