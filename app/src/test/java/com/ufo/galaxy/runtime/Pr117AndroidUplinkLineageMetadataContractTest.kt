package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr117AndroidUplinkLineageMetadataContractTest {

    @Test
    fun `derive is stable for same lineage inputs`() {
        val first = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = "task-117",
            emissionIdentity = "result:task-117:success",
            durableSessionId = "durable-117",
            sessionContinuityEpoch = 3,
            recoveryBasis = "resumed-cleanly:none"
        )
        val second = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = "task-117",
            emissionIdentity = "result:task-117:success",
            durableSessionId = "durable-117",
            sessionContinuityEpoch = 3,
            recoveryBasis = "resumed-cleanly:none"
        )

        assertEquals(first.dedupeKey, second.dedupeKey)
        assertEquals(first.executionIdentity, second.executionIdentity)
        assertEquals(first.emissionIdentity, second.emissionIdentity)
    }

    @Test
    fun `derive changes dedupe key when session epoch changes`() {
        val epoch0 = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = "task-117",
            emissionIdentity = "result:task-117:success",
            durableSessionId = "durable-117",
            sessionContinuityEpoch = 0,
            recoveryBasis = "recovering:reconnect"
        )
        val epoch1 = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = "task-117",
            emissionIdentity = "result:task-117:success",
            durableSessionId = "durable-117",
            sessionContinuityEpoch = 1,
            recoveryBasis = "recovering:reconnect"
        )

        assertNotEquals(epoch0.dedupeKey, epoch1.dedupeKey)
    }

    @Test
    fun `derive marks closure-grade lineage when execution emission and session lineage are present`() {
        val lineage = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = "task-closure-117",
            emissionIdentity = "result:task-closure-117:success",
            durableSessionId = "durable-117",
            sessionContinuityEpoch = 9,
            recoveryBasis = "resumed-cleanly:none"
        )

        assertTrue(lineage.isClosureLineageComplete)
        assertEquals(
            AndroidUplinkLineageMetadataContract.LineageStrengthClass.CLOSURE_GRADE,
            lineage.lineageStrengthClass
        )
    }

    @Test
    fun `derive marks degraded lineage and synthesizes identities when lineage-critical fields are missing`() {
        val lineage = AndroidUplinkLineageMetadataContract.derive(
            executionIdentity = null,
            emissionIdentity = null,
            durableSessionId = null,
            sessionContinuityEpoch = null,
            recoveryBasis = "recovering:none"
        )

        assertFalse(lineage.isClosureLineageComplete)
        assertEquals(
            AndroidUplinkLineageMetadataContract.LineageStrengthClass
                .DEGRADED_MISSING_EXECUTION_IDENTITY,
            lineage.lineageStrengthClass
        )
        assertTrue(lineage.executionIdentity.startsWith("synthetic_execution_"))
        assertTrue(lineage.emissionIdentity.startsWith("synthetic_emission_"))
    }
}
