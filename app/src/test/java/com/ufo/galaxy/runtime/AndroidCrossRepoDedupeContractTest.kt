package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCrossRepoDedupeContractTest {

    @Test
    fun `goal_execution_result prefers lineage dedupe identity over envelope idempotency`() {
        val assessment = AndroidCrossRepoDedupeContract.assessEnvelopeJson(
            """
                {
                  "type":"goal_execution_result",
                  "idempotency_key":"nonce-result",
                  "payload":{
                    "task_id":"task-1",
                    "durable_session_id":"durable-1",
                    "session_continuity_epoch":4,
                    "uplink_lineage_schema_version":"1",
                    "uplink_lineage_dedupe_key":"lineage-4"
                  }
                }
            """.trimIndent()
        )

        assertEquals(AndroidCrossRepoDedupeContract.ContractStatus.CANONICAL, assessment.status)
        assertEquals("lineage-4", assessment.stableKey)
        assertEquals(AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY, assessment.stableKeySource)
    }

    @Test
    fun `reconciliation signal requires stable dedupe parity to be canonical`() {
        val assessment = AndroidCrossRepoDedupeContract.assessEnvelopeJson(
            """
                {
                  "type":"reconciliation_signal",
                  "idempotency_key":"reconcile-1",
                  "payload":{
                    "durable_session_id":"durable-2",
                    "session_continuity_epoch":7,
                    "uplink_lineage_schema_version":"1",
                    "uplink_lineage_dedupe_key":"lineage-reconcile-1",
                    "payload":{
                      "reconciliation_stable_dedupe_key":"reconcile-1"
                    }
                  }
                }
            """.trimIndent()
        )

        assertEquals(AndroidCrossRepoDedupeContract.ContractStatus.CANONICAL, assessment.status)
        assertEquals("reconcile-1", assessment.stableKey)
        assertEquals(ReconciliationSignal.KEY_STABLE_DEDUPE_KEY, assessment.stableKeySource)
    }

    @Test
    fun `missing lineage fields degrade canonical replay identity`() {
        val assessment = AndroidCrossRepoDedupeContract.assessEnvelopeJson(
            """
                {
                  "type":"goal_execution_result",
                  "idempotency_key":"legacy-only",
                  "payload":{
                    "task_id":"task-legacy"
                  }
                }
            """.trimIndent()
        )

        assertEquals(AndroidCrossRepoDedupeContract.ContractStatus.COMPATIBILITY, assessment.status)
        assertEquals("legacy-only", assessment.stableKey)
        assertTrue(
            assessment.missingFields.contains("payload.${AndroidUplinkLineageMetadataContract.KEY_DEDUPE_KEY}")
        )
        assertTrue(
            assessment.missingFields.contains("payload.${DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH}")
        )
    }

    @Test
    fun `runtime truth snapshot is authority sensitive and replay epoch bound`() {
        assertTrue(
            MsgType.DEVICE_STATE_SNAPSHOT.value in AndroidCrossRepoDedupeContract.REPLAY_EPOCH_REQUIRED_TYPES
        )
        assertTrue(
            MsgType.DEVICE_STATE_SNAPSHOT.value in AndroidCrossRepoDedupeContract.AUTHORITY_SENSITIVE_REPLAY_TYPES
        )
    }
}
