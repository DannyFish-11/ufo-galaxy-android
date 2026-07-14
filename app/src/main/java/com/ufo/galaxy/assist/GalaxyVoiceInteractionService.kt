package com.ufo.galaxy.assist

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * 默认助理服务 —— 让 Galaxy 有资格成为系统默认助理(ROLE_ASSISTANT)。
 *
 * 声明本服务后,用户在系统"默认助理 App"里选中 Galaxy,即可用长按电源/
 * 侧滑手势唤起 Galaxy 而非 Gemini —— 这是真正的系统级身份入口。
 *
 * 具体唤起 UI 由 [GalaxyVoiceInteractionSession] 负责(展开灵动岛)。
 */
class GalaxyVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Galaxy 默认助理服务就绪")
    }

    companion object {
        private const val TAG = "GalaxyAssistService"
    }
}
