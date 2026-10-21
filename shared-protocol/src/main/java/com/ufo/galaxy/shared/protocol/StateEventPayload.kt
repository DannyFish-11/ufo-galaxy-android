package com.ufo.galaxy.shared.protocol

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Unified state-event payload for cross-device phase/task/skill/device synchronization.
 *
 * This is the canonical payload carried by [AipMessage] when its [AipMessage.type] is
 * [MsgType.STATE_EVENT] or [MsgType.LIQUID_EVENT].  Both Android and Wear OS use this
 * exact class so that V2 state-sync payloads are parsed identically on both platforms.
 *
 * @param eventCategory  Event category: `"phase"` | `"task"` | `"skill"` | `"device"` | `"mesh"`.
 * @param eventAction    Event action: e.g. `"silent"`, `"liminal"`, `"manifest"` for phase category.
 * @param fromPhase      Previous phase (for phase transition events).
 * @param toPhase        New phase (for phase transition events).
 * @param source         Where the request came in (troubleshooting only).
 * @param syncType       For the phase category, `"request_progress"`: the progress of this device's own
 *                       request. The desktop's tri-state is never pushed to devices (devices talk to the
 *                       agent directly), so a phase frame without this marker is not shown.
 * @param payload        Extra event-specific data as key-value map.
 *
 * **Both annotation families are required.**  `@SerialName` covers
 * kotlinx.serialization; `@SerializedName` covers Gson.  This repo parses
 * incoming frames with **Gson** (`GalaxyWebSocketClient` / `MessageRouter`),
 * so a kotlinx-only annotation leaves Gson looking for a field literally named
 * `eventCategory` — which the wire never carries.  `AuthMessage` already
 * carries both for exactly this reason; this class was missing the Gson half,
 * which is why callers ended up hand-reading the JSON and getting the key
 * names wrong.  See the note in `GalaxyWebSocketClient.handleStateEvent`.
 */
@Serializable
data class StateEventPayload(
    @SerialName("event_category") @SerializedName("event_category") val eventCategory: String = "",
    @SerialName("event_action") @SerializedName("event_action") val eventAction: String = "",
    @SerialName("from_phase") @SerializedName("from_phase") val fromPhase: String = "",
    @SerialName("to_phase") @SerializedName("to_phase") val toPhase: String = "",
    val source: String = "",
    @SerialName("sync_type") @SerializedName("sync_type") val syncType: String = "",
    val payload: Map<String, String> = emptyMap()
)
