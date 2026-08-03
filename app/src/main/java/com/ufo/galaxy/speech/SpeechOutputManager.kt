package com.ufo.galaxy.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音输出状态(与 [SpeechState] 对称)。
 */
sealed class TtsState {
    object Idle : TtsState()
    object Initializing : TtsState()
    /** 已就绪但当前未在说话。 */
    object Ready : TtsState()
    data class Speaking(val text: String) : TtsState()
    object Done : TtsState()
    data class Error(val message: String) : TtsState()
}

/**
 * 语音输出管理器 —— 封装 Android [TextToSpeech],让助手在手机上【出声】。
 *
 * 这是全模态输出缺的另一半:此前 app 只有 [SpeechInputManager](听),没有说。本类与其对称
 * (StateFlow + 回调 + 生命周期 initialize/stop/release),把助手的文本回复念出来。
 *
 * 诚实原则(降级不僵):TTS 引擎缺失/语言不支持/初始化失败时,进入 [TtsState.Error] 并回调,
 * **绝不抛异常崩溃**;调用方可据此静默退化为"只显示文字"。
 */
class SpeechOutputManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechOutputManager"
        private const val UTTERANCE_PREFIX = "galaxy_tts_"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private val utteranceCounter = AtomicInteger(0)

    /** 期望的朗读语言(默认简体中文);init 完成后应用。 */
    private var preferredLocale: Locale = Locale.SIMPLIFIED_CHINESE

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    // 回调(与 SpeechInputManager 风格一致)
    var onStart: ((String) -> Unit)? = null
    var onDone: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** init 完成前调用 speak,先缓存,就绪后自动补说。 */
    private var pendingText: String? = null

    /**
     * 初始化 TTS 引擎。异步:真正就绪在 [TextToSpeech.OnInitListener] 回调里。
     *
     * @return true 表示已发起初始化(不代表已就绪);false 表示当前已在初始化/已就绪。
     */
    fun initialize(): Boolean {
        if (isReady || _state.value is TtsState.Initializing) return false
        _state.value = TtsState.Initializing
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                onTtsInit(status)
            }.apply {
                setOnUtteranceProgressListener(progressListener)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "TTS 初始化抛错(降级为无语音)", e)
            fail("TTS 初始化失败: ${e.message}")
            return false
        }
    }

    private fun onTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 引擎不可用 (status=$status) —— 降级为只显示文字")
            fail("设备无可用 TTS 引擎")
            return
        }
        // 设定语言;缺数据/不支持则退回英语,再不行则报错(不崩)。
        val res = trySetLanguage(preferredLocale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "首选语言 $preferredLocale 不可用 (res=$res),回退英语")
            val fallback = trySetLanguage(Locale.ENGLISH)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                fail("设备缺少可用 TTS 语音数据")
                return
            }
        }
        isReady = true
        _state.value = TtsState.Ready
        Log.i(TAG, "TTS 就绪")
        // 补说 init 前排队的文本
        pendingText?.let {
            pendingText = null
            speak(it)
        }
    }

    private fun trySetLanguage(locale: Locale): Int = try {
        tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
    } catch (e: Exception) {
        Log.e(TAG, "setLanguage($locale) 抛错", e)
        TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 朗读一段文本。空文本忽略。未就绪时缓存,就绪后自动补说。
     *
     * @param text  要念的文本
     * @param flush true=打断当前朗读立即说(QUEUE_FLUSH);false=排到队尾(QUEUE_ADD)
     */
    fun speak(text: String, flush: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (!isReady) {
            // 尚未就绪:发起初始化并缓存文本(只保留最新一条,避免堆积)
            pendingText = trimmed
            if (tts == null && _state.value !is TtsState.Initializing) initialize()
            return
        }

        val utteranceId = UTTERANCE_PREFIX + utteranceCounter.incrementAndGet()
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        try {
            val r = tts?.speak(trimmed, queueMode, null, utteranceId)
            if (r == TextToSpeech.ERROR) {
                fail("TTS speak 返回错误")
            } else {
                _state.value = TtsState.Speaking(trimmed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "speak 抛错", e)
            fail("朗读失败: ${e.message}")
        }
    }

    /** 停止当前朗读(不释放引擎)。 */
    fun stop() {
        try {
            tts?.stop()
            if (isReady) _state.value = TtsState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "stop 抛错", e)
        }
    }

    /** 释放 TTS 引擎资源。 */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "release 抛错", e)
        } finally {
            tts = null
            isReady = false
            pendingText = null
            _state.value = TtsState.Idle
            Log.i(TAG, "TTS 已释放")
        }
    }

    /** 运行时切换朗读语言(就绪后即时生效;未就绪则记录,init 时应用)。 */
    fun setLanguage(locale: Locale) {
        preferredLocale = locale
        if (isReady) trySetLanguage(locale)
    }

    fun isSpeaking(): Boolean = try {
        tts?.isSpeaking == true
    } catch (e: Exception) {
        false
    }

    fun isReady(): Boolean = isReady

    private fun fail(message: String) {
        _state.value = TtsState.Error(message)
        onError?.invoke(message)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            onStart?.invoke(utteranceId ?: "")
        }

        override fun onDone(utteranceId: String?) {
            if (isReady) _state.value = TtsState.Done
            onDone?.invoke(utteranceId ?: "")
            if (isReady) _state.value = TtsState.Ready
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            fail("朗读中断")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            fail("朗读错误 (code=$errorCode)")
        }
    }
}
