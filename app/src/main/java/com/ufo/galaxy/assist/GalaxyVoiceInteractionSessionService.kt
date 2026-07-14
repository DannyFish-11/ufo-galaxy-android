package com.ufo.galaxy.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * 助理会话服务 —— 系统在唤起助理时向此处要一个会话([VoiceInteractionSession])。
 */
class GalaxyVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        GalaxyVoiceInteractionSession(this)
}
