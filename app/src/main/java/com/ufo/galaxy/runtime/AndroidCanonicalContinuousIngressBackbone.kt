package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload
import com.ufo.galaxy.webrtc.SignalingMessage

/**
 * Canonical Android-side continuous ingress backbone.
 *
 * Unifies multiple continuous ingress families into one model that can be consumed by
 * cognition mainline preparation (e.g. planner/context preparation).
 */
object AndroidCanonicalContinuousIngressBackbone {

    const val CONSTRAINT_CONTINUOUS_INGRESS_CONTEXT_READY = "continuous_ingress_context_ready"
    const val CONSTRAINT_STREAM_FUSION_READY = "stream_fusion_ready"

    enum class IngressFamily(val wireValue: String) {
        ANDROID_DEVICE_CONTINUOUS_STREAM("android_device_continuous_stream"),
        RUNTIME_STREAMING_SESSION_MANAGER_INPUT("runtime_streaming_session_manager_input"),
        WEBRTC_STREAM_INGRESS("webrtc_stream_ingress")
    }

    enum class MainlineBehavior(val wireValue: String) {
        ONE_SHOT_PREPARATION("one_shot_preparation"),
        CONTINUOUS_CONTEXT_PREPARATION("continuous_context_preparation"),
        STREAM_FUSED_CONTINUOUS_PREPARATION("stream_fused_continuous_preparation")
    }

    data class ContinuousIngressSignal(
        val family: IngressFamily,
        val contributesToPlanning: Boolean,
        val traceId: String?,
        val sessionId: String?,
        val observedAtMs: Long = System.currentTimeMillis()
    )

    data class RuntimeStreamingSessionInput(
        val runtimeState: String?,
        val crossDeviceEligible: Boolean,
        val hasAttachedSession: Boolean,
        val durableSessionId: String?,
        val traceId: String?,
        val observedAtMs: Long = System.currentTimeMillis()
    )

    data class Snapshot(
        val signals: List<ContinuousIngressSignal>
    ) {
        val families: Set<IngressFamily> = signals.map { it.family }.toSet()

        val hasUnifiedContinuousFamilies: Boolean
            get() = families.size >= 2

        fun mainlineBehavior(): MainlineBehavior {
            val hasAndroid = signals.any {
                it.family == IngressFamily.ANDROID_DEVICE_CONTINUOUS_STREAM && it.contributesToPlanning
            }
            val hasRuntimeManager = signals.any {
                it.family == IngressFamily.RUNTIME_STREAMING_SESSION_MANAGER_INPUT && it.contributesToPlanning
            }
            val hasWebRtc = signals.any {
                it.family == IngressFamily.WEBRTC_STREAM_INGRESS && it.contributesToPlanning
            }

            return when {
                hasAndroid && hasRuntimeManager && hasWebRtc ->
                    MainlineBehavior.STREAM_FUSED_CONTINUOUS_PREPARATION
                hasAndroid && hasRuntimeManager ->
                    MainlineBehavior.CONTINUOUS_CONTEXT_PREPARATION
                else -> MainlineBehavior.ONE_SHOT_PREPARATION
            }
        }
    }

    fun assemble(signals: List<ContinuousIngressSignal>): Snapshot =
        Snapshot(signals.sortedByDescending { it.observedAtMs })

    fun fromDevicePerception(payload: DevicePerceptionEmissionPayload): ContinuousIngressSignal? {
        val hasPerceptionInput =
            payload.local_perception_payload?.capture_present == true ||
                !payload.vision_payload?.prompt_text.isNullOrBlank()
        if (!hasPerceptionInput) return null

        return ContinuousIngressSignal(
            family = IngressFamily.ANDROID_DEVICE_CONTINUOUS_STREAM,
            contributesToPlanning = payload.participates_in_multimodal_main_chain ||
                payload.participation_semantics == DevicePerceptionEmissionPayload.PARTICIPATION_SEMANTICS_MAIN_CHAIN_INPUT,
            traceId = payload.trace_id,
            sessionId = payload.attached_session_id ?: payload.runtime_session_id,
            observedAtMs = payload.timestamp_ms
        )
    }

    fun fromRuntimeStreamingSessionInput(input: RuntimeStreamingSessionInput): ContinuousIngressSignal =
        ContinuousIngressSignal(
            family = IngressFamily.RUNTIME_STREAMING_SESSION_MANAGER_INPUT,
            contributesToPlanning = input.crossDeviceEligible && input.hasAttachedSession,
            traceId = input.traceId,
            sessionId = input.durableSessionId,
            observedAtMs = input.observedAtMs
        )

    fun fromWebRtcSignaling(
        message: SignalingMessage,
        observedAtMs: Long = System.currentTimeMillis()
    ): ContinuousIngressSignal? {
        val planningRelevant = when (message.type) {
            SignalingMessage.TYPE_OFFER,
            SignalingMessage.TYPE_ANSWER -> true
            SignalingMessage.TYPE_ICE_CANDIDATE,
            SignalingMessage.TYPE_ICE_CANDIDATES -> message.allCandidates.isNotEmpty()
            else -> false
        }
        if (!planningRelevant) return null

        return ContinuousIngressSignal(
            family = IngressFamily.WEBRTC_STREAM_INGRESS,
            contributesToPlanning = true,
            traceId = message.traceId,
            sessionId = message.deviceId,
            observedAtMs = observedAtMs
        )
    }
}

class AndroidCanonicalContinuousIngressSessionManager(
    private val maxSignals: Int = DEFAULT_MAX_SIGNALS
) {
    companion object {
        /**
         * Keep only a small, recent ingress window so planning preparation can reflect
         * current stream posture without retaining long-lived historical noise.
         */
        const val DEFAULT_MAX_SIGNALS: Int = 32
    }

    private val lock = Any()
    private val signals = ArrayDeque<AndroidCanonicalContinuousIngressBackbone.ContinuousIngressSignal>()

    fun record(signal: AndroidCanonicalContinuousIngressBackbone.ContinuousIngressSignal) {
        synchronized(lock) {
            signals.addFirst(signal)
            while (signals.size > maxSignals) {
                signals.removeLast()
            }
        }
    }

    fun recordAndroidDeviceContinuousStream(payload: DevicePerceptionEmissionPayload) {
        AndroidCanonicalContinuousIngressBackbone.fromDevicePerception(payload)?.let(::record)
    }

    fun recordRuntimeStreamingSessionInput(
        input: AndroidCanonicalContinuousIngressBackbone.RuntimeStreamingSessionInput
    ) {
        record(AndroidCanonicalContinuousIngressBackbone.fromRuntimeStreamingSessionInput(input))
    }

    fun currentSnapshot(): AndroidCanonicalContinuousIngressBackbone.Snapshot =
        synchronized(lock) {
            AndroidCanonicalContinuousIngressBackbone.assemble(signals.toList())
        }

    internal fun clearForTest() {
        synchronized(lock) {
            signals.clear()
        }
    }
}
