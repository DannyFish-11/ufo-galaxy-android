package com.ufo.galaxy.phone

import com.ufo.galaxy.shared.protocol.DeviceState

/**
 * 这台手机此刻的状态(V2 `core/device_state.py` 读),跟着每一拍心跳走:30 秒一拍,重连后第一拍就带上。
 *
 * 只报手机自己确知的:电量、充没充电、屏幕亮没亮、在不在通话;开着勿扰是人的明确表态,说「明确别打扰」。
 * 戴没戴着手机不知道,不报;没开勿扰时能不能打扰手机说不准,也不报 —— 中心看到没有这个键就当不知道,不替人猜。
 */
object PhoneState {

    /** 从系统读到的;读不到的是 null。 */
    data class Readings(
        val battery: Float? = null,
        val charging: Boolean? = null,
        val screenOn: Boolean? = null,
        val inCall: Boolean? = null,
        val dnd: Boolean? = null,
    )

    /** 纯 Kotlin,不碰 Android(读系统在 [AndroidPhoneState])。 */
    fun build(r: Readings): DeviceState = DeviceState(
        battery = r.battery?.coerceIn(0f, 1f),
        charging = r.charging,
        screenOn = r.screenOn,
        inCall = r.inCall,
        interruptible = if (r.dnd == true) DeviceState.Interruptible.BLOCKED else null,
        note = if (r.dnd == true) "手机开着勿扰" else null,
    )
}
