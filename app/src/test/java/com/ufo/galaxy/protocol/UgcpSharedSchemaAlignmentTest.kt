package com.ufo.galaxy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UgcpSharedSchemaAlignmentTest {

    @Test
    fun `identity alignment includes canonical UGCP lineage`() {
        val identities = UgcpSharedSchemaAlignment.identityAlignments.map { it.canonicalIdentity }.toSet()
        assertTrue(identities.contains("TaskId"))
        assertTrue(identities.contains("TraceId"))
        assertTrue(identities.contains("ControlSessionId"))
        assertTrue(identities.contains("RuntimeSessionId"))
        assertTrue(identities.contains("MeshSessionId"))
        assertTrue(identities.contains("ExecutionInstanceId"))
    }

    @Test
    fun `message types map to canonical schema families`() {
        assertEquals(UgcpSchemaFamily.IDENTITY, UgcpSharedSchemaAlignment.familyFor(MsgType.DEVICE_REGISTER))
        assertEquals(UgcpSchemaFamily.CONTROL, UgcpSharedSchemaAlignment.familyFor(MsgType.TAKEOVER_REQUEST))
        assertEquals(UgcpSchemaFamily.RUNTIME, UgcpSharedSchemaAlignment.familyFor(MsgType.DELEGATED_EXECUTION_SIGNAL))
        assertEquals(UgcpSchemaFamily.COORDINATION, UgcpSharedSchemaAlignment.familyFor(MsgType.MESH_RESULT))
        assertEquals(UgcpSchemaFamily.TRUTH, UgcpSharedSchemaAlignment.familyFor(MsgType.GOAL_EXECUTION_RESULT))
    }

    @Test
    fun `alignment contains readiness transfer mesh and terminal terms`() {
        assertTrue(UgcpSharedSchemaAlignment.readinessCapabilityTerms.contains("model_ready"))
        assertTrue(UgcpSharedSchemaAlignment.transferTerms.contains("takeover_response"))
        assertTrue(UgcpSharedSchemaAlignment.meshTerms.contains("mesh_join"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("failed"))
        assertTrue(UgcpSharedSchemaAlignment.terminalVocabulary.contains("partial"))
    }
}
