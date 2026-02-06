package com.ufo.galaxy.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import com.ufo.galaxy.network.DeviceManager

/**
 * WebRTC 管理器
 * 
 * 功能：
 * 1. 管理 WebRTC 连接的生命周期
 * 2. 处理信令交换（Offer/Answer/ICE）
 * 3. 与 Galaxy Gateway 通信
 * 4. 协调 ScreenCaptureService
 * 
 * @author Manus AI
 * @version 2.1
 * @date 2026-02-05
 */
class WebRTCManager(private val context: Context) {
    
    private val TAG = "WebRTCManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // WebRTC 组件
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var screenCaptureService: ScreenCaptureService? = null
    
    // 设备管理器（用于发送信令消息）
    private var deviceManager: DeviceManager? = null
    
    companion object {
        @Volatile
        private var instance: WebRTCManager? = null
        
        fun getInstance(context: Context): WebRTCManager {
            return instance ?: synchronized(this) {
                instance ?: WebRTCManager(context.applicationContext).also { instance = it }
            }
        }
        
        // ICE 服务器配置
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }
    
    /**
     * 设置设备管理器
     */
    fun setDeviceManager(deviceManager: DeviceManager) {
        this.deviceManager = deviceManager
        Log.i(TAG, "DeviceManager set")
    }
    
    /**
     * 初始化 WebRTC
     */
    fun initialize() {
        Log.i(TAG, "Initializing WebRTC")
        
        // 初始化 PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
        
        Log.i(TAG, "WebRTC initialized")
    }
    
    /**
     * 开始屏幕共享
     */
    fun startScreenSharing(resultCode: Int, data: Intent) {
        Log.i(TAG, "Starting screen sharing")
        
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory not initialized")
            return
        }
        
        // 创建 VideoSource（屏幕采集）
        val videoCapturer = createScreenCapturer(resultCode, data)
        videoSource = peerConnectionFactory!!.createVideoSource(videoCapturer.isScreencast)
        videoCapturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
            context,
            videoSource!!.capturerObserver
        )
        videoCapturer.startCapture(1280, 720, 30)
        
        // 创建 VideoTrack
        videoTrack = peerConnectionFactory!!.createVideoTrack("screen_video", videoSource)
        
        // 创建 PeerConnection
        createPeerConnection()
        
        // 添加 VideoTrack 到 PeerConnection
        peerConnection?.addTrack(videoTrack)
        
        // 创建 Offer
        createOffer()
    }
    
    /**
     * 创建屏幕采集器
     */
    private fun createScreenCapturer(resultCode: Int, data: Intent): VideoCapturer {
        return ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Screen capture stopped")
            }
            
            override fun onCapturedContentResize(width: Int, height: Int) {
                Log.i(TAG, "Screen capture resized: ${width}x${height}")
            }
            
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                Log.i(TAG, "Screen capture visibility changed: $isVisible")
            }
        })
    }
    
    /**
     * 创建 PeerConnection
     */
    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    Log.i(TAG, "ICE Candidate: ${iceCandidate.sdp}")
                    sendIceCandidate(iceCandidate)
                }
                
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.i(TAG, "ICE Candidates removed")
                }
                
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    Log.i(TAG, "ICE Connection State: $newState")
                }
                
                override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                    Log.i(TAG, "Signaling State: $newState")
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
            }
        )
        
        Log.i(TAG, "PeerConnection created")
    }
    
    /**
     * 创建 Offer
     */
    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.i(TAG, "Offer created: ${sessionDescription.description}")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.i(TAG, "Local description set")
                        sendOffer(sessionDescription)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }
            
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create offer: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }
    
    /**
     * 发送 Offer 到 Gateway
     */
    private fun sendOffer(sessionDescription: SessionDescription) {
        val deviceId = deviceManager?.deviceId ?: "unknown"
        val message = JSONObject().apply {
            put("type", "webrtc_offer")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "offer")
                put("sdp", sessionDescription.description)
            })
        }
        
        deviceManager?.sendRawMessage(message.toString())
        Log.i(TAG, "Offer sent to Gateway")
    }
    
    /**
     * 发送 ICE Candidate 到 Gateway
     */
    private fun sendIceCandidate(iceCandidate: IceCandidate) {
        val deviceId = deviceManager?.deviceId ?: "unknown"
        val message = JSONObject().apply {
            put("type", "webrtc_ice_candidate")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "ice_candidate")
                put("candidate", iceCandidate.sdp)
                put("sdpMid", iceCandidate.sdpMid)
                put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
            })
        }
        
        deviceManager?.sendRawMessage(message.toString())
        Log.i(TAG, "ICE Candidate sent to Gateway")
    }
    
    /**
     * 停止屏幕共享
     */
    fun stopScreenSharing() {
        Log.i(TAG, "Stopping screen sharing")
        
        // 停止 VideoTrack
        videoTrack?.dispose()
        videoTrack = null
        
        // 停止 VideoSource
        videoSource?.dispose()
        videoSource = null
        
        // 关闭 PeerConnection
        peerConnection?.close()
        peerConnection = null
        
        Log.i(TAG, "Screen sharing stopped")
    }
    
    /**
     * 处理来自 Gateway 的信令消息
     */
    fun handleSignalingMessage(message: JSONObject) {
        val type = message.optString("type")
        Log.i(TAG, "Received signaling message: $type")
        
        when (type) {
            "offer" -> handleOffer(message)
            "answer" -> handleAnswer(message)
            "ice_candidate" -> handleIceCandidate(message)
            else -> Log.w(TAG, "Unknown signaling message type: $type")
        }
    }
    
    /**
     * 处理 Offer
     */
    private fun handleOffer(message: JSONObject) {
        val sdp = message.optString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set (Offer)")
                createAnswer()
            }
            
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
            
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }
    
    /**
     * 创建 Answer
     */
    private fun createAnswer() {
        val constraints = MediaConstraints()
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.i(TAG, "Answer created: ${sessionDescription.description}")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.i(TAG, "Local description set (Answer)")
                        sendAnswer(sessionDescription)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }
            
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create answer: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }
    
    /**
     * 发送 Answer 到 Gateway
     */
    private fun sendAnswer(sessionDescription: SessionDescription) {
        val deviceId = deviceManager?.deviceId ?: "unknown"
        val message = JSONObject().apply {
            put("type", "webrtc_answer")
            put("device_id", deviceId)
            put("data", JSONObject().apply {
                put("type", "answer")
                put("sdp", sessionDescription.description)
            })
        }
        
        deviceManager?.sendRawMessage(message.toString())
        Log.i(TAG, "Answer sent to Gateway")
    }
    
    /**
     * 处理 Answer
     */
    private fun handleAnswer(message: JSONObject) {
        val sdp = message.optString("sdp")
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote description set (Answer)")
            }
            
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
            
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }
    
    /**
     * 处理 ICE Candidate
     */
    private fun handleIceCandidate(message: JSONObject) {
        val candidate = message.optString("candidate")
        val sdpMid = message.optString("sdpMid")
        val sdpMLineIndex = message.optInt("sdpMLineIndex")
        
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        
        Log.i(TAG, "ICE Candidate added")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopScreenSharing()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        scope.cancel()
        Log.i(TAG, "WebRTCManager cleaned up")
    }
}
