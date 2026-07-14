package com.ufo.galaxy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ufo.galaxy.R
import com.ufo.galaxy.service.EnhancedFloatingService
import com.ufo.galaxy.surface.PresenceState
import com.ufo.galaxy.surface.PresenceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 主屏小组件 —— "AI 住在桌面上"的原生表面(对标 Friends 的桌面组件一体感)。
 *
 * 用 RemoteViews(非 Glance):零新依赖、全 API 稳、无 compose 编译器对齐
 * 风险。渲染三态在场 + 一句状态;整体点按 = 唤醒并展开灵动岛。
 * 数据来自唯一状态源 [PresenceStateStore],与灵动岛/磁贴/背屏口径一致。
 *
 * 视觉为占位样式(设计回合再统一到星云配色/分形图标语言,任务⑤)。
 */
class PresenceWidget : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        scope.launch {
            val state = PresenceStateStore.read(context)
            for (id in ids) render(context, mgr, id, state)
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int, s: PresenceState) {
        val rv = RemoteViews(context.packageName, R.layout.widget_presence)

        val (dotRes, label) = statusVisual(s)
        rv.setImageViewResource(R.id.widget_dot, dotRes)
        rv.setTextViewText(R.id.widget_title, "Galaxy")
        rv.setTextViewText(R.id.widget_status, label)

        // 整体点按 → 唤醒并展开灵动岛(startForegroundService + 启动即展开动作;
        // 比广播稳:服务未运行也能拉起,无接收器注册竞态)。
        val wake = Intent(context, EnhancedFloatingService::class.java).apply {
            action = EnhancedFloatingService.ACTION_WAKE_EXPAND
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getForegroundService(context, id, wake, flags)
        rv.setOnClickPendingIntent(R.id.widget_root, pi)

        mgr.updateAppWidget(id, rv)
    }

    /** 状态 → (指示点 drawable, 一句话文案)。诚实:未连接/陈旧如实显示。 */
    private fun statusVisual(s: PresenceState): Pair<Int, String> {
        if (!s.connected) {
            return R.drawable.widget_dot_offline to "未连接主脑"
        }
        if (s.isStale) {
            return R.drawable.widget_dot_offline to "状态未更新"
        }
        val text = s.statusText.ifBlank {
            when (s.phase) {
                PresenceState.PHASE_MANIFEST -> "执行中…"
                PresenceState.PHASE_LIMINAL -> "在想…"
                else -> "在听"
            }
        }
        val dot = when (s.phase) {
            PresenceState.PHASE_MANIFEST -> R.drawable.widget_dot_manifest
            PresenceState.PHASE_LIMINAL -> R.drawable.widget_dot_liminal
            else -> R.drawable.widget_dot_silent
        }
        return dot to text
    }

    companion object {
        /** 刷新全部已放置的本 widget 实例(由 [PresenceWidgetBridge] 调用)。 */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, PresenceWidget::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isEmpty()) return
            val intent = Intent(context, PresenceWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
