package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ufo.galaxy.MainActivity
import java.util.*

/**
 * 语音识别服务（VoiceRecognitionService）
 *
 * 最小可用实现，满足 Manifest 注册要求。
 * 提供后台语音识别能力；识别结果通过本地广播
 * [ACTION_VOICE_RESULT] 发出，由 MainActivity 或
 * EnhancedFloatingService 监听处理。
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class VoiceRecognitionService : android.app.Service() {

    companion object {
        private const val TAG = "VoiceRecognitionService"
        private const val CHANNEL_ID = "voice_recognition_channel"
        private const val NOTIFICATION_ID = 1002

        /** 识别成功后发出的广播 Action */
        const val ACTION_VOICE_RESULT = "com.ufo.galaxy.ACTION_VOICE_RESULT"
        /** 识别结果文本附加在广播 Intent 的此 Extra 中 */
        const val EXTRA_VOICE_TEXT = "voice_text"

        /** 外部组件发送此 Action 以触发一次语音识别 */
        const val ACTION_START_RECOGNITION = "com.ufo.galaxy.START_VOICE_RECOGNITION"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "✅ VoiceRecognitionService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECOGNITION -> startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
        Log.i(TAG, "VoiceRecognitionService 已停止")
    }

    // ── 语音识别 ─────────────────────────────────────────────────────────────

    /** 开始一次语音识别会话。 */
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "⚠️ 语音识别不可用")
            return
        }
        stopListening()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(recognitionListener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }
        Log.i(TAG, "🎤 语音识别已启动")
    }

    private fun stopListening() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        override fun onPartialResults(partialResults: android.os.Bundle?) {}

        override fun onResults(results: android.os.Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()
            if (!text.isNullOrEmpty()) {
                Log.i(TAG, "✅ 识别结果: $text")
                // 通过广播将结果传递出去
                val resultIntent = Intent(ACTION_VOICE_RESULT).apply {
                    putExtra(EXTRA_VOICE_TEXT, text)
                    setPackage(packageName)
                }
                sendBroadcast(resultIntent)
            }
        }

        override fun onError(error: Int) {
            Log.e(TAG, "❌ 语音识别错误: $error")
        }
    }

    // ── 前台通知 ─────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "语音识别服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO³ Galaxy 语音识别")
            .setContentText("语音识别服务就绪")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
