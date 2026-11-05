package com.ufo.galaxy.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.shared.protocol.AipMessage
import com.ufo.galaxy.shared.protocol.call.AgentTaskLine
import com.ufo.galaxy.shared.protocol.call.CallEvent
import com.ufo.galaxy.shared.protocol.call.CallSignaling
import com.ufo.galaxy.shared.protocol.call.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 手机上的"听筒" —— 把 [CallSignaling] 的决定翻译成 WebRTC 调用。和手表(galaxy-wearos
 * `VoiceCallController`)同一套:信令走同一条 AIP WebSocket,网关把声音接到实时语音模型,
 * 通话里说一声智能体就在后台去做(V2 `core/voice_call_agent.py`)。
 *
 * 分工
 * ----
 * **什么时候能发什么**全在 [CallSignaling] 里(纯 Kotlin,有 JVM 单测)。这里只做
 * 三件不可能脱离设备验证的事:建 PeerConnection、开麦克风、切音频路由。
 *
 * 回声消除只能在采集端做
 * --------------------
 * 外放时,喇叭里放的 AI 声音会被自己的麦克风收回去,AI 于是"听见自己说话"并被
 * 打断——这不是调参能绕过去的:AEC 需要把喇叭的参考信号与麦克风信号在**毫秒级**对齐,
 * 而网络抖动让服务端根本拿不到这个对齐关系,原理上做不了。
 *
 * 所以走系统这条路:``VOICE_COMMUNICATION`` 采集源 + ``MODE_IN_COMMUNICATION`` 音频
 * 模式,拿到的是**打电话用的同一条 DSP 链路**(AEC / 降噪 / 增益)。这也是为什么不
 * 自己录 PCM 再塞进 WebSocket:那条路上没有这条链路。
 */
class PhoneCallController(
    private val context: Context,
    private val deviceId: String,
    private val gateway: GalaxyWebSocketClient,
) {

    private val signaling = CallSignaling(deviceId)

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var audioModeChanged = false

    private val _ui = MutableStateFlow(CallUiState())
    val ui: StateFlow<CallUiState> = _ui.asStateFlow()

    /**
     * 界面上的一通电话。[transcript] 是 AI 回推的文字,不是本地识别的;[agentTask] 是通话里交给
     * 智能体的事做到哪了(见 [AgentTaskLine])。
     */
    data class CallUiState(
        val state: CallState = CallState.IDLE,
        val transcript: String = "",
        val agentTask: String = "",
        val endedReason: String = "",
        val muted: Boolean = false,
    )

    // ── 拨号 ────────────────────────────────────────────────────────────

    /**
     * 拨号。网关必须已连上 —— 信令走的是同一条 AIP WebSocket。
     *
     * 返回 false 表示**没有拨出去**,界面必须据此提示,而不是显示"呼叫中"然后干等。
     */
    @Synchronized
    fun dial(): Boolean {
        if (signaling.state != CallState.IDLE) return false
        if (!gateway.isConnected()) {
            end("网关未连接,先连上再拨")
            return false
        }
        return try {
            ensureFactory()
            val connection = createPeerConnection() ?: run {
                end("建立 WebRTC 连接失败")
                return false
            }
            pc = connection
            connection.addTrack(createMicTrack())
            enterCommunicationMode()
            connection.createOffer(OfferObserver(connection), MediaConstraints())
            _ui.value = _ui.value.copy(state = CallState.DIALING, endedReason = "", transcript = "", agentTask = "")
            true
        } catch (t: Throwable) {
            // 建 PeerConnection 会加载原生库,失败在这里抛。不接住的话 App 直接崩,
            // 而用户看到的只是"点了通话就闪退",完全查不出是缺 so 还是权限没给。
            Log.e(TAG, "拨号失败", t)
            end("拨号失败: ${t.message ?: t::class.java.simpleName}")
            false
        }
    }

    /** 挂断。幂等,任何状态下调用都安全 —— 界面、服务、断线三条路都会调它。 */
    @Synchronized
    fun hangup(reason: String = "user_hangup") {
        signaling.hangup(reason)?.let { send(it) }
        end(reason)
    }

    /** 静音本地麦克风。AI 会以为你没说话,而不是听见静音——两者对它是一回事。 */
    @Synchronized
    fun setMuted(muted: Boolean) {
        micTrack?.setEnabled(!muted)
        _ui.value = _ui.value.copy(muted = muted)
    }

    // ── 网关消息 ────────────────────────────────────────────────────────

    /** 一帧网关原文(见 [CallFrames]);不是通话信令就什么都不做。 */
    fun onGatewayFrame(text: String) {
        CallFrames.parse(text)?.let { onGatewayMessage(it) }
    }

    /** 把一条网关消息喂进来。不是通话消息会被状态机忽略,调用方不必先过滤。 */
    @Synchronized
    fun onGatewayMessage(msg: AipMessage) {
        when (val ev = signaling.onGatewayMessage(msg)) {
            is CallEvent.Accepted -> onAccepted(ev)
            is CallEvent.RemoteIce ->
                pc?.addIceCandidate(IceCandidate(ev.sdpMid, ev.sdpMLineIndex, ev.sdp))
            is CallEvent.AiEvent -> onAiEvent(ev)
            is CallEvent.Ended -> end(ev.reason)
            CallEvent.Ignored -> Unit
        }
    }

    private fun onAccepted(ev: CallEvent.Accepted) {
        val connection = pc ?: return
        connection.setRemoteDescription(
            LoggingSdpObserver("setRemoteDescription") {
                // answer 装不进去 = 这通电话不可能通。必须收尾,否则界面停在"呼叫中"
                // 而麦克风还开着。
                hangup("answer 无法装入: $it")
            },
            SessionDescription(SessionDescription.Type.ANSWER, ev.answerSdp),
        )
        // 排队的候选现在才有地方去。见 CallSignaling.pendingIce 的说明。
        signaling.drainQueuedIce().forEach { send(it) }
        _ui.value = _ui.value.copy(state = CallState.IN_CALL)
    }

    private fun onAiEvent(ev: CallEvent.AiEvent) {
        AgentTaskLine.of(ev.name, ev.text)?.let { line ->
            _ui.value = _ui.value.copy(agentTask = line)
            return
        }
        when (ev.name) {
            "partial_transcript", "final_transcript", "assistant_text_delta" ->
                if (ev.text.isNotEmpty()) _ui.value = _ui.value.copy(transcript = ev.text)
            "user_speech_started" ->
                // 服务端 VAD 判定用户开口 → 让 AI 立刻闭嘴。手机这边不自己做 VAD:
                // 再跑一份既费电又和服务端的判断打架。
                signaling.interrupt("user_speech")?.let { send(it) }
            "error" -> hangup("AI 侧出错: ${ev.text}")
        }
    }

    // ── WebRTC ──────────────────────────────────────────────────────────

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            // 这一行是整条链路里最要紧的一行:VOICE_COMMUNICATION 才走打电话那条
            // DSP 链路。用 MIC 的话喇叭声会被自己收回去,AI 听见自己说话就被打断。
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        audioModule = adm
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection? {
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            // 进隧道、Wi-Fi 切蜂窝都会换网卡。持续收集候选才能不挂断地切过去;
            // 一次性收集(GATHER_ONCE)在这类场景下表现为"走出门通话就断"。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        return factory?.createPeerConnection(cfg, PeerObserver())
    }

    /**
     * ICE 服务器。**默认空**。
     *
     * 网关与手机在同一张网时不需要 STUN;走蜂窝数据时需要,而那时该配哪个
     * STUN/TURN 是部署决定,不是代码决定。这里不塞一个公共 STUN 地址进来:那等于把
     * 用户的网络拓扑悄悄发给第三方,而且它随时可能不可用却没人知道。
     */
    private fun iceServers(): List<PeerConnection.IceServer> = emptyList()

    private fun createMicTrack(): AudioTrack {
        val f = requireNotNull(factory) { "factory 必须先建好" }
        val source = f.createAudioSource(MediaConstraints())
        micSource = source
        return f.createAudioTrack("phone_mic", source).also { micTrack = it }
    }

    private inner class OfferObserver(private val connection: PeerConnection) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            connection.setLocalDescription(
                LoggingSdpObserver("setLocalDescription") { hangup("本地 SDP 设置失败: $it") },
                sdp,
            )
            // offer 一定要在 setLocalDescription **之后**发吗?不一定,但必须在这里发:
            // 候选是在 setLocalDescription 之后才开始产的,而 CallSignaling 会把它们
            // 排队到接通为止,所以这里的顺序不影响正确性,只影响首包时间。
            signaling.dial(sdp.description)?.let { send(it) }
        }

        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            Log.w(TAG, "createOffer 失败: $error")
            hangup("生成通话请求失败: $error")
        }

        override fun onSetFailure(error: String) = Unit
    }

    private inner class LoggingSdpObserver(
        private val what: String,
        private val onFailure: (String) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) {
            Log.w(TAG, "$what 失败: $error")
            onFailure(error)
        }
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling.localIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)?.let { send(it) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED -> hangup("链路中断")
                PeerConnection.PeerConnectionState.CLOSED -> end("通话已结束")
                else -> Unit
            }
        }

        // 远端音轨不需要手工接线:它由 AudioDeviceModule 直接播出去。
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    // ── 音频路由 ────────────────────────────────────────────────────────

    private fun enterCommunicationMode() {
        if (audioModeChanged) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        audioModeChanged = true
    }

    private fun leaveCommunicationMode() {
        if (!audioModeChanged) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // 不还原的话,整台手机的音频都会停在通话模式:媒体音量键改的是通话音量,
        // 通知声也从听筒出。而且它熬得过本进程。
        am?.mode = previousAudioMode
        audioModeChanged = false
    }

    // ── 收尾 ────────────────────────────────────────────────────────────

    /** 释放全部资源并把界面切到已结束。幂等、永不抛出。 */
    @Synchronized
    fun end(reason: String) {
        runCatching { pc?.close() }.onFailure { Log.w(TAG, "关闭 PeerConnection 失败", it) }
        runCatching { micTrack?.dispose() }
        runCatching { micSource?.dispose() }
        pc = null
        micTrack = null
        micSource = null
        leaveCommunicationMode()
        _ui.value = _ui.value.copy(state = CallState.ENDED, endedReason = reason, muted = false)
    }

    /**
     * 彻底释放。**退出通话界面时调**,不是每次挂断都调 —— PeerConnectionFactory 与
     * AudioDeviceModule 建一次要加载原生库,反复建拆会让每次拨号都多等一两秒。
     */
    @Synchronized
    fun dispose() {
        end("已释放")
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        factory = null
        audioModule = null
    }

    private fun send(msg: AipMessage) {
        val ok = gateway.sendJson(AipMessage.toJson(msg))
        if (!ok) Log.w(TAG, "通话信令发送失败: ${msg.type}")
    }

    companion object {
        private const val TAG = "Galaxy.PhoneCall"
    }
}
