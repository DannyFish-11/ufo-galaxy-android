package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceSurfaceSourceContractTest {

    @Test
    fun `deriveFloatingSurfaceProjection marks local mode as local truth`() {
        val projection = AndroidDeviceSurfaceSourceContract.deriveFloatingSurfaceProjection(
            crossDeviceEnabled = false,
            wsConnected = false,
            attachedToV2Session = false,
            hasPendingOfflineSignals = false,
            runtimeDegraded = false,
            taskInFlight = false
        )

        assertEquals(
            AndroidDeviceSurfaceSourceContract.DisplaySourceType.LOCAL_ANDROID_TRUTH,
            projection.sourceType
        )
        assertEquals("local_mode_active", projection.semanticState)
    }

    @Test
    fun `deriveFloatingSurfaceProjection marks attached cross-device as remote v2 truth`() {
        val projection = AndroidDeviceSurfaceSourceContract.deriveFloatingSurfaceProjection(
            crossDeviceEnabled = true,
            wsConnected = true,
            attachedToV2Session = true,
            hasPendingOfflineSignals = false,
            runtimeDegraded = false,
            taskInFlight = false
        )

        assertEquals(
            AndroidDeviceSurfaceSourceContract.DisplaySourceType.REMOTE_V2_TRUTH,
            projection.sourceType
        )
        assertEquals("v2_attached_truth", projection.semanticState)
    }

    @Test
    fun `deriveFloatingSurfaceProjection marks disconnected degraded path as stale_or_cached`() {
        val projection = AndroidDeviceSurfaceSourceContract.deriveFloatingSurfaceProjection(
            crossDeviceEnabled = true,
            wsConnected = false,
            attachedToV2Session = false,
            hasPendingOfflineSignals = true,
            runtimeDegraded = true,
            taskInFlight = true
        )

        assertEquals(
            AndroidDeviceSurfaceSourceContract.DisplaySourceType.STALE_OR_CACHED,
            projection.sourceType
        )
        assertEquals("transport_degraded_or_cached", projection.semanticState)
    }

    @Test
    fun `deriveSnapshotObservationBasis returns derived projection for derived local snapshots`() {
        val basis = AndroidDeviceSurfaceSourceContract.deriveSnapshotObservationBasis(
            crossDeviceEnabled = true,
            wsConnected = true,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
            offlineQueueDepth = 0,
            reportedStateSemanticClass =
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL
        )

        assertEquals(AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.DERIVED_PROJECTION, basis)
    }

    @Test
    fun `deriveSnapshotObservationBasis returns cached state under reconnect recovery`() {
        val basis = AndroidDeviceSurfaceSourceContract.deriveSnapshotObservationBasis(
            crossDeviceEnabled = true,
            wsConnected = true,
            reconnectRecoveryState = ReconnectRecoveryState.RECOVERING.wireValue,
            offlineQueueDepth = 0,
            reportedStateSemanticClass =
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME
        )

        assertEquals(AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.CACHED_STATE, basis)
    }

    @Test
    fun `deriveSnapshotObservationBasis returns live runtime in nominal local truth path`() {
        val basis = AndroidDeviceSurfaceSourceContract.deriveSnapshotObservationBasis(
            crossDeviceEnabled = false,
            wsConnected = false,
            reconnectRecoveryState = ReconnectRecoveryState.IDLE.wireValue,
            offlineQueueDepth = 0,
            reportedStateSemanticClass =
                AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME
        )

        assertEquals(AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME, basis)
    }
}
