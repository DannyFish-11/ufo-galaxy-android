package com.ufo.galaxy.shared.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 设备自述(`device_self/1`):设备在 `capability_report` 的 `self_description` 里描述自己。
 *
 * 设计见 V2 `docs/DEVICE_SELF_DESCRIPTION_DESIGN.md` 第 3 节;schema 在 V2 `contracts/device_self_v1.json`,
 * 中心读入在 V2 `core/device_self.py`。手机、手表都用这里拼,线上的键与 V2 逐字一致
 * (V2 `tests/test_devices_describe_themselves.py` 拿到安卓仓时逐字对照)。
 *
 * 中心怎么对待一台设备看它自己说的,不看它是什么类型:
 * - [actions] 给模型挑:说效果的一句话、参数格式,以及做了会怎样([Action.effect])、
 *   要不要人在设备上点一下才生效([Action.needsHuman]) —— 后两样中心的权限门也要读;
 * - 设备上有、此刻做不了的动作进 [unavailable] 附原因,不进 [actions],模型据此照实告诉人怎么打开;
 * - [Declares] 是代码在边界上必须知道的四样,只有四样。
 */
data class DeviceSelfDescription(
    val actions: List<Action>,
    val declares: Declares,
    val name: String = "",
    val kind: String = "",
    val about: String = "",
    val unavailable: List<Unavailable> = emptyList(),
) {

    data class Action(
        val name: String,
        val description: String,
        val effect: String,
        val params: JsonObject = NO_PARAMS,
        val returns: JsonObject? = null,
        val needsHuman: Boolean = false,
    ) {
        init {
            require(NAME.matches(name)) { "动作名不合规(小写字母开头,只用小写字母、数字、下划线):$name" }
            require(description.isNotBlank()) { "动作 $name 没有说明" }
            require(effect in Effect.ALL) { "动作 $name 的 effect 不认识:$effect(可用:${Effect.ALL})" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description.take(MAX_DESCRIPTION))
            put("effect", effect)
            put("params", params)
            returns?.let { put("returns", it) }
            if (needsHuman) put("needs_human", true)
        }
    }

    data class Unavailable(val name: String, val reason: String) {
        init {
            require(NAME.matches(name)) { "动作名不合规:$name" }
            require(reason.isNotBlank()) { "做不了的动作 $name 要说原因" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("reason", reason.take(MAX_REASON))
        }
    }

    /**
     * 设备级声明,只有四样,每一样都有一条边界在用(V2 设计文档 3.4):
     * - [answersDecisions]:代码发起的确认和智能体的提问只弹到这些设备上;
     * - [showsRequestProgress]:这台设备自己发起的请求,中心把进度推回来;
     * - [hostsAgent]:能装中心派来的 Agent 的版本号(如 `bridge/1`),不能装是空串;
     * - [tailnet]:怎么进自建内网([Tailnet])。
     */
    data class Declares(
        val answersDecisions: Boolean = false,
        val showsRequestProgress: Boolean = false,
        val hostsAgent: String = "",
        val tailnet: String = Tailnet.NONE,
    ) {
        init {
            require(tailnet in Tailnet.ALL) { "tailnet 不认识:$tailnet(可用:${Tailnet.ALL})" }
        }

        fun toJson(): JsonObject = buildJsonObject {
            put("answers_decisions", answersDecisions)
            put("shows_request_progress", showsRequestProgress)
            put("hosts_agent", hostsAgent)
            put("tailnet", tailnet)
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        if (name.isNotBlank()) put("name", name.trim().take(MAX_NAME))
        if (kind.isNotBlank()) put("kind", kind.trim().take(MAX_KIND))
        if (about.isNotBlank()) put("about", about.trim().take(MAX_ABOUT))
        put("actions", JsonArray(actions.map { it.toJson() }))
        if (unavailable.isNotEmpty()) put("unavailable", JsonArray(unavailable.map { it.toJson() }))
        put("declares", declares.toJson())
    }

    /** 做了会怎样。没声明的,中心按 [ACT] 处理。 */
    object Effect {
        /** 只看,不改变任何东西:心率、日程、定位、截屏、电量。 */
        const val READ = "read"

        /** 会打扰人:弹通知、震动、出声。 */
        const val NOTIFY = "notify"

        /** 在设备上改变状态:打开应用、点按、输入、写本地文件。 */
        const val ACT = "act"

        /** 对外发出,收不回来:短信、电话、邮件、发帖。 */
        const val SEND = "send"

        /** 不可逆或代价大:删除、执行代码、付款。 */
        const val DANGER = "danger"

        val ALL: Set<String> = setOf(READ, NOTIFY, ACT, SEND, DANGER)
    }

    /** 怎么进自建内网:App 自带(`embedded`)/ 靠系统里的 Tailscale(`system`)/ 不进(`none`)。 */
    object Tailnet {
        const val EMBEDDED = "embedded"
        const val SYSTEM = "system"
        const val NONE = "none"

        val ALL: Set<String> = setOf(EMBEDDED, SYSTEM, NONE)
    }

    /** 拼参数 JSON Schema 的几个小帮手。 */
    object Params {
        fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties.toMap()))
                if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }

        fun string(description: String, maxLength: Int? = null): JsonObject = buildJsonObject {
            put("type", "string")
            put("description", description)
            maxLength?.let { put("maxLength", it) }
        }

        fun integer(description: String): JsonObject = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

        fun boolean(description: String): JsonObject = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        fun oneOf(values: List<String>, description: String): JsonObject = buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(values.map { JsonPrimitive(it) }))
            put("description", description)
        }
    }

    companion object {
        const val SCHEMA = "device_self/1"

        /** `capability_report` 里放自述的那个键。 */
        const val KEY = "self_description"

        const val MAX_NAME = 80
        const val MAX_KIND = 40
        const val MAX_ABOUT = 300
        const val MAX_DESCRIPTION = 300
        const val MAX_REASON = 200

        /** 没有参数的动作。 */
        val NO_PARAMS: JsonObject = Params.obj()

        private val NAME = Regex("^[a-z][a-z0-9_]{0,47}$")
    }
}
