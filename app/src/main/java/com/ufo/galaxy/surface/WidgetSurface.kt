package com.ufo.galaxy.surface

import android.content.Context

/**
 * 主屏小组件表面 —— 由 [com.ufo.galaxy.widget.PresenceWidget] 承载。
 * 此适配器只负责"通知 widget 该刷新了",具体渲染在 widget 类里。
 */
object WidgetSurface : SurfaceRegistry.PresenceSurface {
    override val name: String = "home_widget"

    override fun isAvailable(context: Context): Boolean = true

    override fun refresh(context: Context) {
        // 委托给 widget 的刷新入口(存在即调,避免编译期硬依赖顺序)。
        com.ufo.galaxy.widget.PresenceWidgetBridge.requestRefresh(context)
    }
}
