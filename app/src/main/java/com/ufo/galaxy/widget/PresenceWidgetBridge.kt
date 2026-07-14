package com.ufo.galaxy.widget

import android.content.Context

/**
 * WidgetSurface → PresenceWidget 的解耦桥。
 * surface 层不直接依赖 widget 实现类,统一经此入口请求刷新。
 */
object PresenceWidgetBridge {
    fun requestRefresh(context: Context) {
        PresenceWidget.refreshAll(context.applicationContext)
    }
}
