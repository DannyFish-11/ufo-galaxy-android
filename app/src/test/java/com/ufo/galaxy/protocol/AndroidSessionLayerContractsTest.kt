package com.ufo.galaxy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSessionLayerContractsTest {

    @Test
    fun `contracts define explicit three-layer session split`() {
        val byLayer = AndroidSessionLayerContracts.contracts.associateBy { it.layerKind }
        assertEquals("ConversationSession", byLayer[AndroidSessionLayerKind.CONVERSATION_SESSION]?.canonicalType)
        assertEquals(
            "RuntimeAttachmentSession",
            byLayer[AndroidSessionLayerKind.RUNTIME_ATTACHMENT_SESSION]?.canonicalType
        )
        assertEquals(
            "DelegationTransferSession",
            byLayer[AndroidSessionLayerKind.DELEGATION_TRANSFER_SESSION]?.canonicalType
        )
    }

    @Test
    fun `carrier lookup resolves conversation runtime and transfer boundaries`() {
        assertEquals(
            AndroidSessionLayerKind.CONVERSATION_SESSION,
            AndroidSessionLayerContracts.layerForCarrier("TaskSubmitPayload.session_id")
        )
        assertEquals(
            AndroidSessionLayerKind.RUNTIME_ATTACHMENT_SESSION,
            AndroidSessionLayerContracts.layerForCarrier("AttachedRuntimeSession.sessionId")
        )
        assertEquals(
            AndroidSessionLayerKind.DELEGATION_TRANSFER_SESSION,
            AndroidSessionLayerContracts.layerForCarrier("takeover_request.session_id")
        )
        assertTrue(AndroidSessionLayerContracts.layerForCarrier("unknown_carrier") == null)
    }
}
