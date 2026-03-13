package com.ufo.galaxy.speech

import android.util.Log
import com.ufo.galaxy.network.MessageRouter

/**
 * Natural-language input collector.
 *
 * This class is responsible **only** for gathering user text/voice input and
 * forwarding it into the unified [MessageRouter]. It must not make any routing
 * decisions itself — all cross-device / local branching is handled by the router.
 *
 * Usage:
 * ```kotlin
 * val inputManager = NaturalLanguageInputManager(messageRouter)
 * inputManager.submit("打开微信")
 * ```
 *
 * @param router The [MessageRouter] that decides whether to send the input via
 *               WebSocket (cross-device) or handle it locally.
 */
class NaturalLanguageInputManager(private val router: MessageRouter) {

    companion object {
        private const val TAG = "NLInputManager"
    }

    /**
     * Submits [text] for routing.
     *
     * The text is trimmed; empty strings are silently ignored.
     *
     * @return `true` if the input was routed via WebSocket, `false` if the local
     *         fallback was used (or if the input was empty).
     */
    fun submit(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.d(TAG, "submit: empty input ignored")
            return false
        }
        Log.d(TAG, "submit: routing \"${trimmed.take(60)}\"")
        return router.route(trimmed)
    }

    /**
     * Submits the result of a voice-recognition session for routing.
     * Delegates to [submit] after basic sanity checks.
     *
     * @param voiceText The speech-recognition transcript.
     * @return `true` if the input was routed via WebSocket.
     */
    fun submitVoiceResult(voiceText: String): Boolean {
        Log.d(TAG, "submitVoiceResult: \"${voiceText.take(60)}\"")
        return submit(voiceText)
    }
}
