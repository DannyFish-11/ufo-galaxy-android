package com.ufo.galaxy.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.ufo.galaxy.service.EnhancedFloatingService

/**
 * 助理会话 —— 用户唤起助理(长按电源/侧滑)时的响应。
 *
 * 不弹系统助理浮层,直接展开 Galaxy 灵动岛:startForegroundService +
 * "启动即展开"动作(与小组件/磁贴同一唤醒路径,口径一致),随即 hide 会话。
 */
class GalaxyVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val ctx = context
            ctx.startForegroundService(
                Intent(ctx, EnhancedFloatingService::class.java).apply {
                    action = EnhancedFloatingService.ACTION_WAKE_EXPAND
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "助理唤起展开灵动岛失败: $e")
        }
        // 立即隐藏系统会话:交互交给灵动岛,避免叠一层空助理浮层。
        hide()
    }

    companion object {
        private const val TAG = "GalaxyAssistSession"
    }
}
