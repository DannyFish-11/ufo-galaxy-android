package com.ufo.galaxy.runtime

/**
 * 中心发来的相位帧里，手机只认一种：**这台手机发起的请求，智能体处理到哪了**（在想 / 在做 / 做完）。
 *
 * 电脑的三态是电脑这具身体的表达，不推给设备，手机也不显示 —— 设备直接对智能体。V2 只在回这台手机
 * 自己那件请求的进度时发相位帧，并在 `payload.sync_type` 标上 [SYNC_TYPE]
 * （V2 `core/cross_device_sync.REQUEST_PROGRESS`）。没带这个标记的相位帧（旧版 V2 广播的电脑三态）一律不显示。
 */
object RequestProgress {

    /** V2 `core/cross_device_sync.REQUEST_PROGRESS`。 */
    const val SYNC_TYPE = "request_progress"

    /**
     * 这一帧是不是这台手机自己那件请求的进度；是就给出相位，不是（电脑的三态、别的类别、认不出的相位）给 null。
     */
    fun phaseOf(eventCategory: String, eventAction: String, syncType: String?): PhaseStateMachine.Phase? {
        if (eventCategory != "phase" || syncType != SYNC_TYPE) return null
        return PhaseStateMachine.Phase.fromWire(eventAction)
    }

    /** 主屏状态栏上的一句话；智能体手上没有这台手机的事（静默）时不显示。 */
    fun label(phase: PhaseStateMachine.Phase): String? = when (phase) {
        PhaseStateMachine.Phase.SILENT -> null
        PhaseStateMachine.Phase.LIMINAL -> "智能体在想你这件事"
        PhaseStateMachine.Phase.MANIFEST -> "智能体在做你这件事"
    }
}
