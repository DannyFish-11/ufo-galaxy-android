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

    /**
     * 直接动作的说明来自 [com.ufo.galaxy.phone.PhoneActionCatalog](带参数,手机真做得了的才报);
     * 另外两项是模型就绪后才报的能力。
     */
    val DESCRIPTIONS: Map<String, String> =
        com.ufo.galaxy.phone.PhoneActionCatalog.ACTIONS.associate { it.name to it.description } + mapOf(
            "goal_execution" to "把一个完整目标交给手机自己规划完成(本地视觉模型 + 无障碍执行);整段目标用 devices__delegate 交",
            "local_model_inference" to "用手机本地模型推理",
        )

    /** 只给真正上报了的动作配说明。 */
    fun forActions(actions: List<String>): Map<String, String> =
        actions.mapNotNull { a -> DESCRIPTIONS[a]?.let { a to it } }.toMap()
}
