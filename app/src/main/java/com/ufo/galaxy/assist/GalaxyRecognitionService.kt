package com.ufo.galaxy.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音识别服务占位 —— interaction_service.xml 的 recognitionService 属性
 * 必须指向一个有效的 [RecognitionService] 组件,故提供此薄实现。
 *
 * 诚实申报:本路径不自带独立 ASR;Galaxy 的语音识别走 App 内既有识别器
 * (MainViewModel 语音输入)。此处收到识别请求即如实回 ERROR_CLIENT,
 * 不假装能识别。真正的助理入口价值在"唤起并展开灵动岛"([GalaxyVoiceInteractionSession])。
 */
class GalaxyRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            Log.d(TAG, "recognition error 回调失败: $e")
        }
    }

    override fun onStopListening(listener: Callback) { /* no-op */ }

    override fun onCancel(listener: Callback) { /* no-op */ }

    companion object {
        private const val TAG = "GalaxyRecognition"
    }
}
