package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.PeerAnnouncePayload

/**
 * PR-14 Android — Explicit mesh direct-runtime viability and fallback contract.
 *
 * Captures Android-side truth for whether a mesh execution can reasonably treat peer-direct
 * communication as viable, and how that decision evolves when an actual direct send is attempted.
 *
 * The contract is intentionally conservative:
 *  - Android only treats direct peer runtime as viable when a live mesh id exists.
 *  - The transport/control channel must be connected.
 *  - Topology must include this device and at least one remote peer.
 *  - At least one remote peer must have both presence evidence and the required capability.
 *
 * When any of those conditions fail, Android explicitly reports a gateway fallback route instead
 * of silently assuming direct peer viability from scattered optimistic hints.
 */
object AndroidMeshDirectRuntimeContract {

    const val SCHEMA_VERSION = "android-mesh-direct-runtime/v1"
    const val REQUIRED_CAPABILITY_PARALLEL_SUBTASK = "parallel_subtask"

    const val KEY_SCHEMA_VERSION = "mesh_direct_schema_version"
    const val KEY_STATE = "mesh_direct_state"
    const val KEY_ROUTE = "mesh_direct_route"
    const val KEY_CHANNEL_READY = "mesh_direct_channel_ready"
    const val KEY_PEER_COUNT = "mesh_direct_peer_count"
    const val KEY_READY_PEER_COUNT = "mesh_direct_ready_peer_count"
    const val KEY_REASON_CODES = "mesh_direct_reason_codes"
    const val KEY_LAST_ATTEMPT_STAGE = "mesh_direct_last_attempt_stage"
    const val KEY_LAST_ATTEMPT_SUCCEEDED = "mesh_direct_last_attempt_succeeded"

    const val REASON_NO_MESH_ID = "mesh_direct_no_mesh_id"
    const val REASON_TRANSPORT_DISCONNECTED = "mesh_direct_transport_disconnected"
    const val REASON_TOPOLOGY_UNAVAILABLE = "mesh_direct_topology_unavailable"
    const val REASON_LOCAL_NODE_MISSING = "mesh_direct_local_node_missing"
    const val REASON_NO_REMOTE_PEERS = "mesh_direct_no_remote_peers"
    const val REASON_PEER_PRESENCE_UNCONFIRMED = "mesh_direct_peer_presence_unconfirmed"
    const val REASON_PEER_CAPABILITY_UNCONFIRMED = "mesh_direct_peer_capability_unconfirmed"
    const val REASON_SEND_FAILED_JOIN = "mesh_direct_send_failed_join"
    const val REASON_SEND_FAILED_RESULT = "mesh_direct_send_failed_result"
    const val REASON_SEND_FAILED_LEAVE = "mesh_direct_send_failed_leave"

    enum class DirectPathState(val wireValue: String) {
        UNAVAILABLE("unavailable"),
        READY("ready"),
        ACTIVE("active"),
        FALLBACK("fallback");
    }

    enum class DirectPathRoute(val wireValue: String) {
        DIRECT_PEER("direct_peer"),
        GATEWAY_FALLBACK("gateway_fallback");
    }

    enum class AttemptStage(val wireValue: String) {
        JOIN("join"),
        RESULT("result"),
        LEAVE("leave");

        val failureReason: String
            get() = when (this) {
                JOIN -> REASON_SEND_FAILED_JOIN
                RESULT -> REASON_SEND_FAILED_RESULT
                LEAVE -> REASON_SEND_FAILED_LEAVE
            }
    }

    data class DerivationInput(
        val meshId: String?,
        val localDeviceId: String,
        val wsConnected: Boolean,
        val topologyMeshId: String?,
        val topologyNodes: List<String>,
        val peerAnnouncements: Map<String, PeerAnnouncePayload>,
        val peerCapabilities: Map<String, List<String>>,
        val requiredCapability: String = REQUIRED_CAPABILITY_PARALLEL_SUBTASK
    )

    data class MeshDirectRuntimeSnapshot(
        val schemaVersion: String = SCHEMA_VERSION,
        val meshId: String? = null,
        val state: DirectPathState = DirectPathState.UNAVAILABLE,
        val route: DirectPathRoute = DirectPathRoute.GATEWAY_FALLBACK,
        val channelReady: Boolean = false,
        val peerCount: Int = 0,
        val readyPeerCount: Int = 0,
        val reasonCodes: List<String> = emptyList(),
        val eligiblePeerIds: List<String> = emptyList(),
        val lastAttemptStage: String? = null,
        val lastAttemptSucceeded: Boolean? = null
    ) {
        val directPathViable: Boolean
            get() = route == DirectPathRoute.DIRECT_PEER &&
                (state == DirectPathState.READY || state == DirectPathState.ACTIVE)

        fun toWireMap(): Map<String, Any> = buildMap {
            put(KEY_SCHEMA_VERSION, schemaVersion)
            put(KEY_STATE, state.wireValue)
            put(KEY_ROUTE, route.wireValue)
            put(KEY_CHANNEL_READY, channelReady)
            put(KEY_PEER_COUNT, peerCount)
            put(KEY_READY_PEER_COUNT, readyPeerCount)
            put(KEY_REASON_CODES, reasonCodes)
            if (lastAttemptStage != null) put(KEY_LAST_ATTEMPT_STAGE, lastAttemptStage)
            if (lastAttemptSucceeded != null) put(KEY_LAST_ATTEMPT_SUCCEEDED, lastAttemptSucceeded)
        }
    }

    fun derive(input: DerivationInput): MeshDirectRuntimeSnapshot {
        val meshId = input.meshId?.takeIf { it.isNotBlank() }
        val topologyMatchesMesh = meshId != null &&
            input.topologyMeshId != null &&
            input.topologyMeshId == meshId
        // Topology snapshots are treated as transport truth, so blank node ids are discarded
        // before deduplication to avoid counting malformed entries as viable direct peers.
        val topologyNodes = if (topologyMatchesMesh) input.topologyNodes.filter { it.isNotBlank() }.distinct() else emptyList()
        val localIncluded = meshId != null && topologyNodes.contains(input.localDeviceId)
        val remotePeers = topologyNodes.filter { it != input.localDeviceId }
        val announcedPeers = remotePeers.filter { input.peerAnnouncements.containsKey(it) }
        val capablePeers = announcedPeers.filter { peerId ->
            input.peerCapabilities[peerId]?.contains(input.requiredCapability) == true
        }
        val channelReady = meshId != null &&
            input.wsConnected &&
            topologyMatchesMesh &&
            localIncluded

        val reasonCodes = buildList {
            if (meshId == null) add(REASON_NO_MESH_ID)
            if (!input.wsConnected) add(REASON_TRANSPORT_DISCONNECTED)
            if (meshId != null && !topologyMatchesMesh) add(REASON_TOPOLOGY_UNAVAILABLE)
            if (meshId != null && topologyMatchesMesh && !localIncluded) add(REASON_LOCAL_NODE_MISSING)
            if (meshId != null && topologyMatchesMesh && remotePeers.isEmpty()) add(REASON_NO_REMOTE_PEERS)
            if (meshId != null && topologyMatchesMesh && remotePeers.isNotEmpty() && announcedPeers.isEmpty()) {
                add(REASON_PEER_PRESENCE_UNCONFIRMED)
            }
            if (meshId != null && topologyMatchesMesh && announcedPeers.isNotEmpty() && capablePeers.isEmpty()) {
                add(REASON_PEER_CAPABILITY_UNCONFIRMED)
            }
        }

        val route = if (meshId != null && channelReady && capablePeers.isNotEmpty()) {
            DirectPathRoute.DIRECT_PEER
        } else {
            DirectPathRoute.GATEWAY_FALLBACK
        }
        val state = when {
            meshId == null -> DirectPathState.UNAVAILABLE
            route == DirectPathRoute.DIRECT_PEER -> DirectPathState.READY
            else -> DirectPathState.FALLBACK
        }

        return MeshDirectRuntimeSnapshot(
            meshId = meshId,
            state = state,
            route = route,
            channelReady = channelReady,
            peerCount = remotePeers.size,
            readyPeerCount = capablePeers.size,
            reasonCodes = reasonCodes,
            eligiblePeerIds = capablePeers
        )
    }

    fun onDirectSendAttempt(
        snapshot: MeshDirectRuntimeSnapshot,
        stage: AttemptStage,
        succeeded: Boolean
    ): MeshDirectRuntimeSnapshot {
        if (!succeeded) {
            return snapshot.copy(
                state = DirectPathState.FALLBACK,
                route = DirectPathRoute.GATEWAY_FALLBACK,
                reasonCodes = (snapshot.reasonCodes + stage.failureReason).distinct(),
                lastAttemptStage = stage.wireValue,
                lastAttemptSucceeded = false
            )
        }

        val nextState = when (stage) {
            AttemptStage.LEAVE ->
                if (snapshot.route == DirectPathRoute.DIRECT_PEER) DirectPathState.READY else snapshot.state
            AttemptStage.JOIN, AttemptStage.RESULT -> DirectPathState.ACTIVE
        }

        return snapshot.copy(
            state = nextState,
            lastAttemptStage = stage.wireValue,
            lastAttemptSucceeded = true
        )
    }
}
