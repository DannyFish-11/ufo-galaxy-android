package com.ufo.galaxy.call

import com.ufo.galaxy.shared.protocol.call.AgentTaskLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTaskLineTest {

    @Test
    fun `agent_task events become the progress line`() {
        assertEquals("在做:提醒我三点开会", AgentTaskLine.of("agent_task", " 在做:提醒我三点开会 "))
        assertEquals("做完了:提醒我三点开会", AgentTaskLine.of("agent_task", "做完了:提醒我三点开会"))
    }

    @Test
    fun `other AI events are not the progress line`() {
        assertNull(AgentTaskLine.of("final_transcript", "在做:x"))
        assertNull(AgentTaskLine.of("assistant_text_delta", "好的"))
        assertNull(AgentTaskLine.of("agent_task", "   "))
    }

    @Test
    fun `a long line is cut to fit the watch face`() {
        val line = AgentTaskLine.of("agent_task", "在做:" + "很".repeat(200))!!
        assertEquals(AgentTaskLine.MAX_CHARS, line.length)
        assertEquals('…', line.last())
    }
}
