package com.ufo.galaxy.network

/**
 * 手机报给中心智能体的每个动作「能做什么」—— 一句话,说效果不说实现。
 *
 * 中心智能体把每台设备当成一个整体来看,由模型自己决定让谁做什么(V2
 * `core/device_onboarding/device_card.py`,整体方案阶段 1)。`capability_report` 里的
 * `action_descriptions` 原样进 V2 的能力权威面,模型在设备整体卡里读到的就是这几句。
 * 没有说明的动作照样上报,只是卡上那一行留空 —— 不编。
 *
 * 纯 Kotlin,不碰 Android。
 */
object PhoneActionDescriptions {

    val DESCRIPTIONS: Map<String, String> = mapOf(
        "location" to "读手机当前位置",
        "camera" to "用手机摄像头拍照",
        "sensor_data" to "读手机传感器(加速度、光线等)",
        "automation" to "在手机上跑一段自动化操作",
        "notification" to "在手机上弹一条通知",
        "sms" to "发一条短信(要人确认)",
        "phone_call" to "拨打电话(要人确认)",
        "contacts" to "查手机通讯录",
        "calendar" to "查或加手机日程",
        "voice_input" to "用手机麦克风听一段话",
        "screen_capture" to "截一张手机当前屏幕",
        "app_control" to "打开、切换或操作手机上的应用",
        "goal_execution" to "把一个完整目标交给手机自己规划完成(本地视觉模型 + 无障碍执行)",
        "local_model_inference" to "用手机本地模型推理",
    )

    /** 只给真正上报了的动作配说明。 */
    fun forActions(actions: List<String>): Map<String, String> =
        actions.mapNotNull { a -> DESCRIPTIONS[a]?.let { a to it } }.toMap()
}
