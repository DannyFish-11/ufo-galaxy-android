package com.ufo.galaxy.shared.protocol.call

/**
 * 通话里交给智能体的事做到哪了 —— 通话界面上 AI 文字下面那一行。
 *
 * 通话里说一声「帮我……」,智能体在后台去做、话照说;网关用 `voice_event` 的 `agent_task`
 * 推「在做:… / 做完了:… / 没做成:… / 停下了:…」(V2 `core/voice_call_agent.py`)。
 * 做完的结果 AI 也会在电话里说;这一行是让人不用竖着耳朵也看得到。
 */
object AgentTaskLine {
    const val EVENT = "agent_task"

    /** 一行放得下的字数(手表表盘小;手机上同一行也不该占太多地方)。 */
    const val MAX_CHARS = 60

    /** 这条 AI 事件是不是在说交出去的事;是就给出要显示的那一行,不是给 null。 */
    fun of(name: String, text: String): String? {
        if (name != EVENT) return null
        val line = text.trim()
        if (line.isEmpty()) return null
        return if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS - 1) + "…"
    }
}
