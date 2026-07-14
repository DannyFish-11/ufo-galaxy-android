package com.ufo.galaxy.surface

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * 背屏/副屏表面 —— 【预留位】。
 *
 * 背景:2025-2026 的旗舰机型开始带"背板/背屏"形态 —— 小米 17/18 Pro
 * 系列的妙享背屏(Dynamic Back Display)、阶跃星辰 aOS 手机的背板等。
 * 这类表面和"AI 伙伴常驻"的组件形态天然贴合(参考 Friends 的桌面
 * 小组件一体感):三态在场球 + 一句状态,放在背屏比主屏更"贴身"。
 *
 * 现状(诚实申报):
 * - AOSP 的 [DisplayManager] 能枚举物理副屏,但小米妙享背屏、aOS 背板
 *   的内容投放走的是【厂商私有 SDK/接口】,公开 API 尚不可用;
 * - 因此本表面当前只做能力探测与如实上报 unavailable,不假装支持。
 *
 * 接入路径(留给厂商 SDK 可用时):
 * 1. isAvailable():检测厂商 SDK / 副屏 Display(displayId != DEFAULT);
 * 2. refresh():读取 PresenceStateStore → 用厂商内容 API(或
 *    Presentation + 副屏 Display)渲染同一套三态在场视图;
 * 3. 视觉与主屏小组件同源(设计回合统一),仅布局按背屏尺寸适配。
 */
object BackDisplaySurface : SurfaceRegistry.PresenceSurface {
    private const val TAG = "BackDisplaySurface"
    override val name: String = "back_display(预留)"

    override fun isAvailable(context: Context): Boolean {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val secondary = dm?.displays?.any { it.displayId != Display.DEFAULT_DISPLAY } == true
            if (secondary) {
                // 有物理副屏,但内容投放需厂商接口 —— 如实报不可用并留日志线索。
                Log.i(TAG, "检测到副屏 Display,但背屏内容 API 未接入(厂商 SDK 预留位),暂不渲染")
            }
            false // 预留位:在厂商适配落地前恒为不可用
        } catch (e: Exception) {
            Log.d(TAG, "副屏探测失败: $e")
            false
        }
    }

    override fun refresh(context: Context) {
        // 预留:厂商 SDK 接入后,此处读 PresenceStateStore 并渲染背屏视图。
    }
}
