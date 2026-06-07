package com.ufo.galaxy.runtime

/**
 * Android mobile/device surface source contract.
 *
 * Defines a unified, explicit source typing for Android-side display fields so UI/service
 * surfaces can clearly state whether they reflect local Android truth, remote V2 truth,
 * V2 projection, or stale/cached/degraded state.
 */
object AndroidDeviceSurfaceSourceContract {

    enum class DisplaySourceType(
        val wireValue: String,
        val badgeZh: String
    ) {
        LOCAL_ANDROID_TRUTH("local_android_truth", "本地真相"),
        REMOTE_V2_TRUTH("remote_v2_truth", "V2真相"),
        V2_PROJECTION("v2_projection", "V2投影"),
        STALE_OR_CACHED("stale_or_cached", "缓存/退化");
    }

    data class FloatingSurfaceProjection(
        val sourceType: DisplaySourceType,
        val semanticState: String
    )

    fun deriveFloatingSurfaceProjection(
        crossDeviceEnabled: Boolean,
        wsConnected: Boolean,
        attachedToV2Session: Boolean,
        hasPendingOfflineSignals: Boolean,
        runtimeDegraded: Boolean,
        taskInFlight: Boolean
    ): FloatingSurfaceProjection = when {
        !crossDeviceEnabled -> FloatingSurfaceProjection(
            sourceType = DisplaySourceType.LOCAL_ANDROID_TRUTH,
            semanticState = "local_mode_active"
        )
        wsConnected && attachedToV2Session -> FloatingSurfaceProjection(
            sourceType = DisplaySourceType.REMOTE_V2_TRUTH,
            semanticState = "v2_attached_truth"
        )
        wsConnected -> FloatingSurfaceProjection(
            sourceType = DisplaySourceType.V2_PROJECTION,
            semanticState = "projection_live"
        )
        hasPendingOfflineSignals || runtimeDegraded || taskInFlight -> FloatingSurfaceProjection(
            sourceType = DisplaySourceType.STALE_OR_CACHED,
            semanticState = "transport_degraded_or_cached"
        )
        else -> FloatingSurfaceProjection(
            sourceType = DisplaySourceType.LOCAL_ANDROID_TRUTH,
            semanticState = "local_fallback_unattached"
        )
    }

    fun deriveSnapshotObservationBasis(
        crossDeviceEnabled: Boolean,
        wsConnected: Boolean,
        reconnectRecoveryState: String?,
        offlineQueueDepth: Int,
        reportedStateSemanticClass: AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass
    ): AndroidCanonicalRuntimeTruthContract.LocalObservationBasis {
        val inRecovery = reconnectRecoveryState == ReconnectRecoveryState.RECOVERING.wireValue ||
            reconnectRecoveryState == ReconnectRecoveryState.RECOVERED.wireValue

        return when {
            reportedStateSemanticClass ==
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL ->
                AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.DERIVED_PROJECTION
            crossDeviceEnabled && (!wsConnected || inRecovery || offlineQueueDepth > 0) ->
                AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.CACHED_STATE
            else -> AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME
        }
    }
}
