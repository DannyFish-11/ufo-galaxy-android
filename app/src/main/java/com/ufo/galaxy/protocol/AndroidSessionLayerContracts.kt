package com.ufo.galaxy.protocol

/**
 * Explicit Android-side split of session layers for PR-4 convergence.
 *
 * This model is additive and compatibility-safe: it does not change runtime behavior or wire
 * contracts. It documents which Android carriers belong to:
 * - ConversationSession
 * - RuntimeAttachmentSession
 * - DelegationTransferSession
 */
enum class AndroidSessionLayerKind {
    CONVERSATION_SESSION,
    RUNTIME_ATTACHMENT_SESSION,
    DELEGATION_TRANSFER_SESSION
}

data class AndroidSessionLayerContract(
    val layerKind: AndroidSessionLayerKind,
    val canonicalType: String,
    val canonicalTerm: String,
    val androidCarriers: Set<String>,
    val boundary: String
)

object AndroidSessionLayerContracts {
    val contracts: List<AndroidSessionLayerContract> = listOf(
        AndroidSessionLayerContract(
            layerKind = AndroidSessionLayerKind.CONVERSATION_SESSION,
            canonicalType = "ConversationSession",
            canonicalTerm = "conversation_session_id",
            androidCarriers = setOf(
                "LocalLoopTrace.sessionId",
                "SessionHistorySummary.sessionId",
                "TaskSubmitPayload.session_id"
            ),
            boundary = "conversation/history timeline context, not runtime attachment continuity"
        ),
        AndroidSessionLayerContract(
            layerKind = AndroidSessionLayerKind.RUNTIME_ATTACHMENT_SESSION,
            canonicalType = "RuntimeAttachmentSession",
            canonicalTerm = "attached_runtime_session_id",
            androidCarriers = setOf(
                "AttachedRuntimeSession.sessionId",
                "AttachedRuntimeHostSessionSnapshot.sessionId",
                "delegated_execution_signal.attached_session_id"
            ),
            boundary = "runtime attachment continuity for Android host participation"
        ),
        AndroidSessionLayerContract(
            layerKind = AndroidSessionLayerKind.DELEGATION_TRANSFER_SESSION,
            canonicalType = "DelegationTransferSession",
            canonicalTerm = "transfer_session_context",
            androidCarriers = setOf(
                "takeover_request.session_id",
                "takeover_response.accepted",
                "DelegatedExecutionSignal(taskId, traceId, attachedSessionId)"
            ),
            boundary = "delegation/takeover transfer lifecycle continuity, not generic session identity"
        )
    )

    private val carrierToLayer: Map<String, AndroidSessionLayerKind> =
        contracts
            .flatMap { contract -> contract.androidCarriers.map { it to contract.layerKind } }
            .toMap()

    fun layerForCarrier(carrier: String): AndroidSessionLayerKind? =
        carrierToLayer[carrier.trim()]
}
