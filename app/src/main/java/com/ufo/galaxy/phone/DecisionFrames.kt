package com.ufo.galaxy.phone

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 中心智能体问人一件事:`decision_request` 进来、人的回答以 `human_input` 回去,撤回是 `decision_withdraw`。
 *
 * 帧形状与手表一致(V2 `core/interaction/pending_decision_registry.request_human_decision` 发,
 * `galaxy_gateway/websocket_handler.py` 的 `human_input` 分支收):
 * - 进:`{"type":"decision_request","payload":{"decision_id","title","summary","options":[{"id","label"}],...}}`;
 * - 回:`{"type":"command","payload":{"id","command":"human_input","payload":{"decision_id","selected_option"|"voice_input",...}}}`;
 * - 撤:`{"type":"decision_withdraw","payload":{"decision_id","reason"}}`(别处答了、超时了,把通知收起来)。
 *
 * 同一条提问会并行发给所有声明能回答提问的设备,先答的算数;所以这里只管摆出来、把人点的那一个原样交回,
 * 不做默认选择 —— 人没点就什么都不回(中心那边超时即不批准)。
 *
 * 纯 Kotlin,不碰 Android。
 */
object DecisionFrames {

    data class Option(val id: String, val label: String)

    data class Decision(
        val decisionId: String,
        val title: String,
        val summary: String,
        val options: List<Option>,
    )

    /** 通知上最多能放几个按钮(Android 的上限);选项更多时点开通知去选。 */
    const val MAX_BUTTONS = 3

    /** 读一条 `decision_request`;不是或缺 decision_id 就是 null。 */
    fun parseRequest(frameJson: String): Decision? {
        val root = parse(frameJson) ?: return null
        if (root.str("type") != "decision_request") return null
        val p = root.obj("payload") ?: root
        val id = p.str("decision_id").ifBlank { return null }
        val options = (p.get("options") as? JsonArray).orEmptyList().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val oid = o.str("id").ifBlank { return@mapNotNull null }
            Option(oid, o.str("label").ifBlank { oid })
        }
        return Decision(id, p.str("title").ifBlank { "智能体想问你" }, p.str("summary"), options)
    }

    /** 读一条 `decision_withdraw`,返回要收起的 decision_id;不是就是 null。 */
    fun parseWithdraw(frameJson: String): String? {
        val root = parse(frameJson) ?: return null
        if (root.str("type") != "decision_withdraw") return null
        return (root.obj("payload") ?: root).str("decision_id").ifBlank { null }
    }

    /** 通知上直接放的按钮:选项不多于 [MAX_BUTTONS] 个时全放,否则一个不放(点开通知选,免得只露出一部分)。 */
    fun buttons(d: Decision): List<Option> = if (d.options.size <= MAX_BUTTONS) d.options else emptyList()

    /**
     * 人的回答。和手表 `AIPClient.sendHumanInput` 同形:中心按 `decision_id` 认领,先答的算数。
     * [selectedOption] 是人点的选项 id;没有选项的提问用 [text] 回一句话。
     */
    fun humanInputFrame(
        deviceId: String,
        decisionId: String,
        selectedOption: String?,
        text: String?,
        seq: Long,
        nowMs: Long,
    ): JsonObject = JsonObject().apply {
        addProperty("type", "command")
        addProperty("version", "3.0")
        addProperty("device_id", deviceId)
        addProperty("correlation_id", "hi_$seq")
        addProperty("timestamp", nowMs)
        add("payload", JsonObject().apply {
            addProperty("id", seq)
            addProperty("command", "human_input")
            add("payload", JsonObject().apply {
                addProperty("decision_id", decisionId)
                selectedOption?.takeIf { it.isNotBlank() }?.let { addProperty("selected_option", it) }
                text?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("voice_input", it) }
                addProperty("device", "android_phone")
                addProperty("timestamp", nowMs)
            })
        })
    }

    private fun parse(json: String): JsonObject? = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonArray?.orEmptyList() = this?.toList() ?: emptyList()
}
