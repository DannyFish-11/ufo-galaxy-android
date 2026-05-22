package com.ufo.galaxy.network

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OfflineTaskQueue].
 *
 * All tests run on the JVM without any Android framework dependency
 * (SharedPreferences is passed as null ⇒ in-memory only mode).
 */
class OfflineQueueTest {

    private lateinit var queue: OfflineTaskQueue

    @Before
    fun setUp() {
        // in-memory queue (no SharedPreferences)
        queue = OfflineTaskQueue(prefs = null)
    }

    // ── Basic enqueue / drain ─────────────────────────────────────────────────

    @Test
    fun `empty queue has size 0`() {
        assertEquals(0, queue.size)
    }

    @Test
    fun `enqueue increases size`() {
        queue.enqueue("task_result", """{"type":"task_result"}""")
        assertEquals(1, queue.size)
    }

    @Test
    fun `exact duplicate enqueue is suppressed`() {
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "durable-1")
        queue.enqueue("task_result", """{"id":1}""", sessionTag = "durable-1")

        assertEquals(1, queue.size)
    }

    @Test
    fun `lineage dedupe key suppresses duplicate replay artifacts`() {
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","idempotency_key":"nonce-a"}""",
            sessionTag = "durable-1",
            sessionEpoch = 2,
            dedupeKey = "lineage-dedupe-1"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","idempotency_key":"nonce-b"}""",
            sessionTag = "durable-1",
            sessionEpoch = 2,
            dedupeKey = "lineage-dedupe-1"
        )

        assertEquals("Same lineage dedupe key must collapse duplicate artifacts", 1, queue.size)
    }

    @Test
    fun `drainAll returns messages in FIFO order`() {
        queue.enqueue("task_result", """{"id":1}""")
        queue.enqueue("goal_result",  """{"id":2}""")
        queue.enqueue("task_result", """{"id":3}""")

        val messages = queue.drainAll()

        assertEquals(3, messages.size)
        assertEquals("""{"id":1}""", messages[0].json)
        assertEquals("""{"id":2}""", messages[1].json)
        assertEquals("""{"id":3}""", messages[2].json)
        assertTrue(messages[0].queueSequence < messages[1].queueSequence)
        assertTrue(messages[1].queueSequence < messages[2].queueSequence)
    }

    @Test
    fun `drainAll clears the queue`() {
        queue.enqueue("task_result", "{}")
        queue.drainAll()
        assertEquals(0, queue.size)
    }

    @Test
    fun `clear empties the queue without returning messages`() {
        queue.enqueue("task_result", "{}")
        queue.enqueue("goal_result", "{}")
        queue.clear()
        assertEquals(0, queue.size)
    }

    // ── Drop policy ───────────────────────────────────────────────────────────

    @Test
    fun `queue never exceeds maxQueueSize`() {
        val small = OfflineTaskQueue(prefs = null, maxQueueSize = 3)
        repeat(5) { i -> small.enqueue("task_result", """{"id":$i}""") }
        assertEquals(3, small.size)
    }

    @Test
    fun `oldest message is dropped when queue is full`() {
        val small = OfflineTaskQueue(prefs = null, maxQueueSize = 2)
        small.enqueue("task_result", """{"id":0}""")   // oldest
        small.enqueue("task_result", """{"id":1}""")
        small.enqueue("task_result", """{"id":2}""")   // should push out id=0

        val messages = small.drainAll()
        assertEquals(2, messages.size)
        // id=0 was dropped; id=1 and id=2 remain in order
        assertEquals("""{"id":1}""", messages[0].json)
        assertEquals("""{"id":2}""", messages[1].json)
    }

    // ── QUEUEABLE_TYPES ───────────────────────────────────────────────────────

    @Test
    fun `QUEUEABLE_TYPES contains task_result and goal_result`() {
        assertTrue("task_result" in OfflineTaskQueue.QUEUEABLE_TYPES)
        assertTrue("goal_result" in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    @Test
    fun `QUEUEABLE_TYPES contains goal_execution_result`() {
        assertTrue(
            "goal_execution_result must be queueable: it is the canonical uplink type " +
                "for all main production result paths (task_assign / goal_execution / parallel_subtask)",
            "goal_execution_result" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `QUEUEABLE_TYPES contains delegated_execution_signal`() {
        assertTrue(
            "delegated_execution_signal must be queueable so delegated takeover ACK/PROGRESS/RESULT " +
                "signals can replay across reconnect",
            "delegated_execution_signal" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `QUEUEABLE_TYPES contains device_execution_event`() {
        assertTrue(
            "device_execution_event must be queueable so canonical execution lifecycle truth " +
                "can replay across reconnect without loss",
            "device_execution_event" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `QUEUEABLE_TYPES contains device_state_snapshot`() {
        assertTrue(
            "device_state_snapshot must be queueable so disconnect-time freshness snapshots " +
                "can replay across reconnect for stale-state degradation",
            "device_state_snapshot" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `QUEUEABLE_TYPES contains device_acceptance_report`() {
        assertTrue(
            "device_acceptance_report must be queueable so closure evidence survives transient disconnects",
            "device_acceptance_report" in OfflineTaskQueue.QUEUEABLE_TYPES
        )
    }

    @Test
    fun `heartbeat type is NOT in QUEUEABLE_TYPES`() {
        assertFalse("heartbeat" in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    @Test
    fun `capability_report type is NOT in QUEUEABLE_TYPES`() {
        assertFalse("capability_report" in OfflineTaskQueue.QUEUEABLE_TYPES)
    }

    // ── StateFlow sizeFlow ────────────────────────────────────────────────────

    @Test
    fun `sizeFlow value matches size after enqueue`() {
        queue.enqueue("task_result", "{}")
        assertEquals(1, queue.sizeFlow.value)
    }

    @Test
    fun `sizeFlow value is 0 after drainAll`() {
        queue.enqueue("task_result", "{}")
        queue.drainAll()
        assertEquals(0, queue.sizeFlow.value)
    }

    @Test
    fun `sizeFlow value is 0 after clear`() {
        queue.enqueue("task_result", "{}")
        queue.clear()
        assertEquals(0, queue.sizeFlow.value)
    }

    // ── Thread safety (smoke test) ────────────────────────────────────────────

    @Test
    fun `concurrent enqueue does not exceed maxQueueSize`() {
        val concurrent = OfflineTaskQueue(prefs = null, maxQueueSize = 20)
        val threads = (1..50).map { i ->
            Thread { concurrent.enqueue("task_result", """{"id":$i}""") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("Queue size should not exceed max", concurrent.size <= 20)
    }

    // ── MAX_QUEUE_SIZE constant ───────────────────────────────────────────────

    @Test
    fun `MAX_QUEUE_SIZE constant is 50`() {
        assertEquals(50, OfflineTaskQueue.MAX_QUEUE_SIZE)
    }

    // ── drainAll on empty queue ───────────────────────────────────────────────

    @Test
    fun `drainAll on empty queue returns empty list`() {
        assertTrue(queue.drainAll().isEmpty())
    }

    @Test
    fun `discardLineageBoundMessagesForDifferentEpoch removes stale lineage artifacts`() {
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"old"}}""",
            sessionTag = "durable-1",
            sessionEpoch = 1,
            dedupeKey = "lineage-old"
        )
        queue.enqueue(
            "goal_execution_result",
            """{"type":"goal_execution_result","payload":{"task_id":"current"}}""",
            sessionTag = "durable-1",
            sessionEpoch = 2,
            dedupeKey = "lineage-current"
        )

        val discarded = queue.discardLineageBoundMessagesForDifferentEpoch(currentEpoch = 2)

        assertEquals(1, discarded)
        val remaining = queue.drainAll()
        assertEquals(1, remaining.size)
        assertEquals("lineage-current", remaining.single().dedupeKey)
    }

    // ── Preserve type in QueuedMessage ────────────────────────────────────────

    @Test
    fun `QueuedMessage preserves type and json fields`() {
        val type = "goal_result"
        val json = """{"task_id":"t1","status":"success"}"""
        queue.enqueue(type, json)
        val msg = queue.drainAll().first()
        assertEquals(type, msg.type)
        assertEquals(json, msg.json)
    }

    @Test
    fun `QueuedMessage queuedAt is set to current time`() {
        val before = System.currentTimeMillis()
        queue.enqueue("task_result", "{}")
        val after = System.currentTimeMillis()
        val msg = queue.drainAll().first()
        assertTrue("queuedAt should be within test window", msg.queuedAt in before..after)
    }

    @Test
    fun `previewReplayGovernance marks mismatched session as stale_result`() {
        queue.enqueue("goal_result", """{"id":1}""", sessionTag = "durable-old")

        val decision = queue.previewReplayGovernance(currentTag = "durable-new").single()

        assertEquals(
            "stale_result",
            decision.disposition.wireValue
        )
        assertFalse(decision.shouldForward)
    }

    @Test
    fun `previewReplayGovernance marks tagged message without authority as attachment_only_recovery`() {
        queue.enqueue("goal_result", """{"id":2}""", sessionTag = "durable-2")

        val decision = queue.previewReplayGovernance(currentTag = null).single()

        assertEquals("attachment_only_recovery", decision.disposition.wireValue)
        assertFalse(decision.shouldForward)
    }

    @Test
    fun `previewReplayGovernance blocks null-tagged authority-sensitive replay`() {
        queue.enqueue("goal_execution_result", """{"id":3}""")

        val decision = queue.previewReplayGovernance(currentTag = "durable-3").single()

        assertEquals("attachment_only_recovery", decision.disposition.wireValue)
        assertFalse(decision.shouldForward)
        assertEquals("authority_sensitive_replay_requires_session_tag", decision.reason)
    }

    @Test
    fun `previewReplayGovernance still allows null-tagged non-authority replay`() {
        queue.enqueue("device_state_snapshot", """{"id":4}""")

        val decision = queue.previewReplayGovernance(currentTag = "durable-4").single()

        assertEquals("replay", decision.disposition.wireValue)
        assertTrue(decision.shouldForward)
    }

    @Test
    fun `previewReplayGovernance blocks authority-sensitive replay missing session epoch`() {
        queue.enqueue(
            "goal_execution_result",
            """{"id":5}""",
            sessionTag = "durable-5"
            // sessionEpoch intentionally missing
        )

        val decision = queue.previewReplayGovernance(currentTag = "durable-5").single()

        assertEquals("attachment_only_recovery", decision.disposition.wireValue)
        assertFalse(decision.shouldForward)
        assertEquals("replay_order_metadata_missing_session_epoch", decision.reason)
    }
}
