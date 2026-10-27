package com.ufo.galaxy.call

/**
 * 一通电话结束时，要不要、以及怎么对人说。
 *
 * 为什么单独一处
 * ==============
 * 通话结束的原因有两类，对人的意义完全相反：
 *
 *  * **正常结束**：自己挂断、对端挂断、资源释放 —— 什么都不用说，直接退回上一屏；
 *  * **失败**：网关没连上、没配语音后端、链路断了 —— 必须让人看见，否则表现就是
 *    「点了通话，屏幕闪一下又回来了」，而原因（没给麦克风权限 / 网关没连 / 后端没配 key）
 *    各有各的处置。
 *
 * 原先界面只看「原因是不是空串」来决定退不退：自己挂断时原因是 `user_hangup`，会被当成
 * 失败原样写在屏幕上；而真正的失败又因为服务马上 `stopSelf()`、控制器被置空，原因跟着消失。
 *
 * 纯 Kotlin，不碰 Android —— 单测直接跑。
 */
object CallEndNotice {

    /** 这些原因不是故障。 */
    private val NORMAL_ENDS = setOf("", "user_hangup", "通话已结束", "已释放")

    /** 返回要显示给人的一句话；正常结束返回 null。 */
    fun of(reason: String): String? = when {
        reason in NORMAL_ENDS -> null
        reason == "unauthenticated" -> "网关还不认这台手机，先在设置里重新配对"
        reason == "connection_closed" -> "与网关的连接断了"
        reason == "sdp_negotiation_failed" -> "通话协商失败，稍后再试"
        reason.startsWith("webrtc_") -> "通话链路中断"
        else -> reason
    }
}
