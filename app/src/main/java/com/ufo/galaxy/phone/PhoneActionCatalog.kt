package com.ufo.galaxy.phone

import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Effect
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Params
import kotlinx.serialization.json.JsonObject

/**
 * 手机能替中心智能体**直接**做的动作:名字、给模型看的一句话(带参数)、要什么前提。
 *
 * 中心智能体经 `devices__invoke` 调这些动作,V2 发一个 `command` 帧,手机做完回 `command_result`
 * (和手表同一个约定,见 [PhoneCommandExecutor])。上报的只是**做得了**的:实现了、而且人在手机上给了
 * 对应权限([reportable])。没给权限的不报 —— 报了也只会让智能体去调、再失败。
 *
 * 要碰别人的事(发短信、打电话、加日程)不由手机自己做完:手机把要发的短信 / 要拨的号 / 新日程备好,
 * 弹一条通知,**人点一下**才真的发出去。所以这几项只要「能弹通知」。
 *
 * 不在这里的:
 * - 整段目标(「帮我在手机上订个外卖」)走 `devices__delegate`,交给手机上的桥接 Agent 慢慢做、做完回报;
 *   `command` 帧中心只等 30 秒,不适合。
 * - 拍照、听一段话:要人在手机前、要前台界面,这一版不报。
 *
 * 每个动作还说明做了会怎样([Action.effect])、要不要人在手机上点一下才生效([Action.needsHuman])、
 * 参数格式([Action.params]):进设备自述(V2 `core/device_self.py`),中心的权限门要读。
 *
 * 纯 Kotlin,不碰 Android。
 */
object PhoneActionCatalog {

    /** 动作要的前提。是否具备由 Android 那一侧判断([PhoneEffects.available])。 */
    enum class Need(val label: String) {
        NOTIFICATIONS("通知"),
        LOCATION("位置"),
        CONTACTS("通讯录"),
        CALENDAR("日历"),
        SCREEN("无障碍截屏(Android 11 以上、打开 Galaxy 的无障碍服务)"),
    }

    data class Action(
        val name: String,
        val description: String,
        val needs: Set<Need> = emptySet(),
        val effect: String = Effect.ACT,
        val params: JsonObject = Params.obj(),
        val needsHuman: Boolean = false,
    )

    private val TIME = "ISO 时间如 2026-10-25T18:00,或毫秒"

    val ACTIONS: List<Action> = listOf(
        Action(
            "notification", "在手机上弹一条通知。参数:title、text", setOf(Need.NOTIFICATIONS), Effect.NOTIFY,
            Params.obj("title" to Params.string("标题"), "text" to Params.string("正文"), required = listOf("text")),
        ),
        Action("location", "读手机当前位置:经纬度、精度(米)、定位时间。无参数", setOf(Need.LOCATION), Effect.READ),
        Action(
            "sensor_data",
            "读一次手机传感器。参数:sensor = accelerometer / gyroscope / magnetic_field / light / pressure / proximity / " +
                "ambient_temperature / relative_humidity",
            effect = Effect.READ,
            params = Params.obj("sensor" to Params.oneOf(SENSORS, "读哪个传感器"), required = listOf("sensor")),
        ),
        Action(
            "contacts", "按名字查手机通讯录,返回名字和号码。参数:query、limit(可选,默认 5)", setOf(Need.CONTACTS), Effect.READ,
            Params.obj("query" to Params.string("名字"), "limit" to Params.integer("最多几条,默认 5"), required = listOf("query")),
        ),
        Action(
            "calendar",
            "查手机日程。参数:from、to(ISO 时间如 2026-10-25T18:00,或毫秒;默认接下来 24 小时)、limit(可选,默认 10)",
            setOf(Need.CALENDAR),
            Effect.READ,
            Params.obj(
                "from" to Params.string("从什么时候($TIME)"),
                "to" to Params.string("到什么时候($TIME)"),
                "limit" to Params.integer("最多几条,默认 10"),
            ),
        ),
        Action(
            "calendar_add",
            "在手机上备好一条新日程,人在手机上点一下确认才存。参数:title、start、end(可选)、location(可选)",
            setOf(Need.NOTIFICATIONS),
            Effect.ACT,
            Params.obj(
                "title" to Params.string("标题"),
                "start" to Params.string("开始($TIME)"),
                "end" to Params.string("结束($TIME)"),
                "location" to Params.string("地点"),
                required = listOf("title", "start"),
            ),
            needsHuman = true,
        ),
        Action(
            "sms", "在手机上备好一条短信,人在手机上确认、点发送才发。参数:to(号码)、text", setOf(Need.NOTIFICATIONS), Effect.SEND,
            Params.obj("to" to Params.string("号码"), "text" to Params.string("短信内容"), required = listOf("to", "text")),
            needsHuman = true,
        ),
        Action(
            "phone_call", "在手机上备好拨号,人在手机上确认、点拨打才打。参数:number", setOf(Need.NOTIFICATIONS), Effect.SEND,
            Params.obj("number" to Params.string("号码"), required = listOf("number")),
            needsHuman = true,
        ),
        Action(
            "app_control", "打开手机上的一个应用。参数:app(应用名或包名)", setOf(Need.NOTIFICATIONS), Effect.ACT,
            Params.obj("app" to Params.string("应用名或包名"), required = listOf("app")),
        ),
        Action("screen_capture", "截一张手机当前屏幕,返回 JPEG(base64)。无参数", setOf(Need.SCREEN), Effect.READ),
    )

    val BY_NAME: Map<String, Action> = ACTIONS.associateBy { it.name }

    /** 现在做得了的动作:每个前提都具备。 */
    fun reportable(available: (Need) -> Boolean): List<String> =
        ACTIONS.filter { a -> a.needs.all(available) }.map { it.name }

    /** 手机上有、此刻做不了的动作 → 缺什么(给人的一句话,说清去哪打开)。 */
    fun unavailable(available: (Need) -> Boolean): Map<String, String> =
        ACTIONS.mapNotNull { a ->
            val missing = a.needs.filterNot(available)
            if (missing.isEmpty()) null
            else a.name to "手机上还没给 Galaxy 打开:${missing.joinToString("、") { it.label }}(在手机设置里打开)"
        }.toMap()
}

private val SENSORS = listOf(
    "accelerometer", "gyroscope", "magnetic_field", "light", "pressure", "proximity", "ambient_temperature",
    "relative_humidity",
)
