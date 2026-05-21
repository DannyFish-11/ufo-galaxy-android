package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
