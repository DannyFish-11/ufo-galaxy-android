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
    fun `drainAll returns messages in FIFO order`() {
        queue.enqueue("task_result", """{"id":1}""")
        queue.enqueue("goal_result",  """{"id":2}""")
        queue.enqueue("task_result", """{"id":3}""")

        val messages = queue.drainAll()

        assertEquals(3, messages.size)
        assertEquals("""{"id":1}""", messages[0].json)
        assertEquals("""{"id":2}""", messages[1].json)
        assertEquals("""{"id":3}""", messages[2].json)
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
}
