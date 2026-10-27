package com.ufo.galaxy.phone

import com.ufo.galaxy.shared.protocol.DeviceSelfDescription
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Declares
import com.ufo.galaxy.shared.protocol.DeviceSelfDescription.Tailnet
import kotlinx.serialization.json.JsonObject

/**
 * 这台手机的设备自述(`device_self/1`,V2 `core/device_self.py`):能力上报里的 `self_description`。
 *
 * 动作来自 [PhoneActionCatalog]:做得了的进 `actions`,手机上有、权限没给的进 `unavailable` 附原因 ——
 * 中心智能体据此照实告诉人缺什么、去哪打开,而不是当成这台手机没有这项能力。
 *
 * 纯 Kotlin,不碰 Android。
 */
object PhoneSelfDescription {

    /**
     * @param available  每个前提此刻具备没有(Android 那一侧判断,见 [PhoneEffects.available])
     * @param deviceName 人叫这台手机的名字(没有就空串,中心用设备 id)
     * @param model      机型(Build.MODEL),只写进形态描述
     * @param hostsAgent 能装中心派来的桥接 Agent 的版本号;不装是空串
     */
    fun build(available: (PhoneActionCatalog.Need) -> Boolean, deviceName: String, model: String, hostsAgent: String): JsonObject {
        val ok = PhoneActionCatalog.reportable(available).toSet()
        return DeviceSelfDescription(
            name = deviceName,
            kind = KIND,
            about = about(model),
            actions = PhoneActionCatalog.ACTIONS.filter { it.name in ok }.map { a ->
                DeviceSelfDescription.Action(a.name, a.description, a.effect, a.params, needsHuman = a.needsHuman)
            },
            unavailable = PhoneActionCatalog.unavailable(available).map { (name, reason) ->
                DeviceSelfDescription.Unavailable(name, reason)
            },
            declares = Declares(
                // 提问弹成通知(PhoneDecisions):通知打得开才能回答,打不开就不声明 —— 免得中心白等
                answersDecisions = available(PhoneActionCatalog.Need.NOTIFICATIONS),
                // 手机会显示自己发起的那件请求做到哪了(runtime/RequestProgress.kt)
                showsRequestProgress = true,
                hostsAgent = hostsAgent,
                // 进自建内网靠系统里的 Tailscale App,要了才给钥匙(配对时不声明)
                tailnet = Tailnet.NONE,
            ),
        ).toJson()
    }

    const val KIND = "android_phone"

    fun about(model: String): String {
        val which = model.trim().takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()
        return "一台安卓手机$which,人一般随身带着。屏幕比手表大,适合看长一点的内容;" +
            "整段要在手机上点来点去的事交给它自己的 Agent 做(devices__delegate)。"
    }
}
