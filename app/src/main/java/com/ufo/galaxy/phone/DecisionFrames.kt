package com.ufo.galaxy.phone

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * 中心智能体问人一件事:`decision_request` 进来、人的回答以 `human_input` 回去,撤回是 `decision_withdraw`。
 *
 * 帧形状与手表一致(V2 `core/interaction/pending_decision_registry.request_human_decision` 发,
 * `galaxy_gateway/websocket_handler.py` 的 `human_input` 分支收):
 * - 进:`{"type":"decision_request","payload":{"decision_id","title","summary","options":[{"id","label"}],...}}`;
 * - 回:`{"type":"command","version":"3.0","device_id","correlation_id":"hi_<uuid>","timestamp",
 *   "payload":{"command":"human_input","payload":{"decision_id","selected_option"|"voice_input","device":"android","timestamp"}}}`
 *   (逐字照 V2 `docs/DEVICE_CLIENT_REQUIREMENTS.md` A1);
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
        /** 中心等多久(`timeout_ms`);到了中心判「没人应答 = 不执行」,这边把通知收起来、不再回。没给是 null。 */
        val timeoutMs: Long? = null,
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
        val timeout = p.get("timeout_ms")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        return Decision(id, p.str("title").ifBlank { "智能体想问你" }, p.str("summary"), options, timeout?.takeIf { it > 0 })
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
     * 人的回答(V2 `galaxy_gateway/websocket_handler.py` 的 `human_input` 分支读 `decision_id` /
     * `selected_option` / `voice_input`)。一定从收到这条提问的那条连接发 —— 中心只认被问到的设备的回答。
     *
     * [selectedOption] 是人点的按钮 id,批准只能这样给(中心只认 `selected_option == "approve"`,只有一句话一律不算授权);
     * 没有选项的提问用 [text] 回一句话(放 `voice_input`)。
     */
    fun humanInputFrame(
        deviceId: String,
        decisionId: String,
        selectedOption: String?,
        text: String?,
        nowMs: Long,
        correlationId: String = "hi_${UUID.randomUUID()}",
    ): JsonObject = JsonObject().apply {
        addProperty("type", "command")
        addProperty("version", "3.0")
        addProperty("device_id", deviceId)
        addProperty("correlation_id", correlationId)
        addProperty("timestamp", nowMs)
        add("payload", JsonObject().apply {
            addProperty("command", "human_input")
            add("payload", JsonObject().apply {
                addProperty("decision_id", decisionId)
                selectedOption?.takeIf { it.isNotBlank() }?.let { addProperty("selected_option", it) }
                text?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("voice_input", it) }
                addProperty("device", "android")
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
