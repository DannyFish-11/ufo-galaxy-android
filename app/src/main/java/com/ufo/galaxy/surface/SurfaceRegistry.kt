package com.ufo.galaxy.surface

import android.content.Context
import android.util.Log

/**
 * 原生表面注册表 —— "一个状态源,N 个表面"的分发点。
 *
 * 表面(surface)= 把 [PresenceState] 呈现到某个系统原生位置的适配器:
 * 主屏小组件、快捷设置磁贴、(预留)背屏/副屏、(预留)锁屏……
 * 状态写入后由 [notifyAll] 逐个触发刷新;单个表面失败不影响其他表面。
 *
 * 背屏预留位见 [BackDisplaySurface]。
 */
object SurfaceRegistry {
    private const val TAG = "SurfaceRegistry"

    /** 表面适配器契约:实现方负责把最新状态推到自己的系统位置。 */
    interface PresenceSurface {
        val name: String

        /** 该表面在本机是否可用(如实探测,不假装)。 */
        fun isAvailable(context: Context): Boolean

        /** 触发该表面刷新(内部自行读取 [PresenceStateStore])。 */
        fun refresh(context: Context)
    }

    private val surfaces: List<PresenceSurface> = listOf(
        WidgetSurface,
        TileSurface,
        BackDisplaySurface,
    )

    fun notifyAll(context: Context) {
        for (s in surfaces) {
            try {
                if (s.isAvailable(context)) s.refresh(context)
            } catch (e: Exception) {
                Log.w(TAG, "表面 ${s.name} 刷新失败(不影响其他表面): $e")
            }
        }
    }
}
