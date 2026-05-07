package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DevicePerceptionEmissionPayload

/**
 * Testable sink for unified Android perception emissions.
 *
 * Decouples the local perception producer from the concrete outbound transport so unit tests
 * can assert emission semantics without a live WebSocket or Android framework runtime.
 */
fun interface DevicePerceptionEmissionSink {
    fun onEmission(payload: DevicePerceptionEmissionPayload)
}
