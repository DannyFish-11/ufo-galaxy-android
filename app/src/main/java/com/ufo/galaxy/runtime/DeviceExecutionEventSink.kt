package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload

/**
 * **Testable sink for device execution events** (PR-2 Android companion).
 *
 * [DeviceExecutionEventSink] is a single-method functional interface that decouples
 * [com.ufo.galaxy.service.GalaxyConnectionService] from the concrete outbound-transmission
 * mechanism for [DeviceExecutionEventPayload] messages. This allows execution-event
 * emission to be fully verified in JVM unit tests without an Android framework or a live
 * WebSocket connection.
 *
 * ## Production wiring
 *
 * In [com.ufo.galaxy.service.GalaxyConnectionService] the sink logs the event and
 * transmits it as a [com.ufo.galaxy.protocol.MsgType.DEVICE_EXECUTION_EVENT] AIP v3
 * uplink message via [com.ufo.galaxy.network.GalaxyWebSocketClient.sendDeviceExecutionEvent]:
 *
 * ```kotlin
 * private val deviceExecutionEventSink = DeviceExecutionEventSink { payload ->
 *     GalaxyLogger.log(GalaxyLogger.TAG_DEVICE_EXECUTION_EVENT, payload.toLogMap())
 *     webSocketClient.sendDeviceExecutionEvent(payload)
 * }
 * ```
 *
 * ## Test wiring
 *
 * In unit tests a capturing lambda records emitted payloads for assertion:
 *
 * ```kotlin
 * val emitted = mutableListOf<DeviceExecutionEventPayload>()
 * val sink = DeviceExecutionEventSink { payload -> emitted += payload }
 * ```
 *
 * @see DeviceExecutionEventPayload
 * @see com.ufo.galaxy.protocol.MsgType.DEVICE_EXECUTION_EVENT
 */
fun interface DeviceExecutionEventSink {

    /**
     * Receives a [payload] emitted by the local execution pipeline.
     *
     * Implementations must not throw: any error handling (e.g. logging a failed send)
     * must be done internally so the caller's execution lifecycle progression is not
     * interrupted.
     *
     * @param payload The [DeviceExecutionEventPayload] to transmit or process.
     */
    fun onEvent(payload: DeviceExecutionEventPayload)
}
