package com.ufo.galaxy.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.ufo.galaxy.ui.MainActivity

/**
 * 助理会话 —— 用户唤起助理(长按电源/侧滑)时的响应。
 *
 * 不弹系统助理浮层,而是直接打开 Galaxy 主界面并【自动进入语音对话】(听→发→答→念),
 * 复用 MainActivity/MainViewModel 里既有的语音流(SpeechInputManager + 设备 TTS)。
 *
 * 之前是 startForegroundService 展开悬浮岛,但岛内 startVoiceInput() 只是占位 stub
 * (提示"请打开主界面"),唤起助理后并不能真正对话。故改为路由到真正能对话的主界面语音回路。
 */
class GalaxyVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val ctx = context
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_ASSIST_VOICE
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "助理唤起打开语音对话失败: $e")
        }
        // 立即隐藏系统会话:交互交给主界面语音回路,避免叠一层空助理浮层。
        hide()
    }

    companion object {
        private const val TAG = "GalaxyAssistSession"
    }
}
