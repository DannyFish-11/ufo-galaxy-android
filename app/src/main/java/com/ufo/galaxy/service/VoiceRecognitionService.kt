package com.ufo.galaxy.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Voice recognition service stub.
 *
 * This Service is declared in AndroidManifest.xml to satisfy the Manifest registration
 * requirement and to reserve the component name for future background voice-activation
 * work. It does **not** implement any voice recognition logic and will not crash the
 * application when the system queries it.
 *
 * **Canonical path**: all active voice recognition is handled by
 * [com.ufo.galaxy.speech.SpeechInputManager], which is instantiated inside
 * [com.ufo.galaxy.ui.viewmodel.MainViewModel] and feeds speech transcripts into
 * [com.ufo.galaxy.input.InputRouter] — the sole canonical input routing backbone.
 * Any new voice-related feature must be wired through [SpeechInputManager] →
 * [InputRouter], not through this stub.
 */
@Deprecated(
    message = "Non-executing Manifest stub. All voice recognition is handled by " +
        "SpeechInputManager (instantiated in MainViewModel) which feeds transcripts " +
        "into InputRouter — the canonical input routing backbone. " +
        "Do not add logic here; implement voice features in SpeechInputManager instead.",
    replaceWith = ReplaceWith(
        "SpeechInputManager(context)",
        "com.ufo.galaxy.speech.SpeechInputManager"
    ),
    level = DeprecationLevel.WARNING
)
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
