package com.ufo.galaxy.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Voice recognition service stub.
 *
 * This Service is declared in AndroidManifest.xml. The actual voice-recognition
 * pipeline is handled by [com.ufo.galaxy.speech.SpeechInputManager] (used in
 * [com.ufo.galaxy.ui.viewmodel.MainViewModel]). This class exists to satisfy the
 * Manifest registration requirement and will not crash the application when the
 * system queries it.
 *
 * Future implementations may migrate the [SpeechInputManager] logic here to enable
 * background voice activation without the main Activity being in the foreground.
 */
class VoiceRecognitionService : Service() {

    companion object {
        private const val TAG = "VoiceRecognitionService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceRecognitionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VoiceRecognitionService started")
        // No-op stub; voice input is handled by SpeechInputManager in MainViewModel.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VoiceRecognitionService destroyed")
    }
}
