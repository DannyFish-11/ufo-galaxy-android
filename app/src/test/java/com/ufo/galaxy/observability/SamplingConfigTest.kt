package com.ufo.galaxy.observability

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SamplingConfig].
 *
 * Covers:
 *  - [SamplingConfig.debug]: all tags always sampled.
 *  - [SamplingConfig.production]: errors and tasks always sampled; others subject to rate.
 *  - Rate bounds validation: constructor rejects rates outside [0, 1].
 *  - [SamplingConfig.shouldSample]: deterministic at extremes (0.0 → never, 1.0 → always).
 *  - Tag-to-category mapping: each new Round-7 tag resolves to the correct category.
 *  - [GalaxyLogger.logSampled]: respects [samplingConfig] and bypasses it for TAG_ERROR.
 *  - [GalaxyLogger.logError]: always records; includes trace_id and cause in fields.
 */
class SamplingConfigTest {

    @Before
    fun setUp() {
        GalaxyLogger.clear()
        // Reset to debug config for isolation between tests
        GalaxyLogger.samplingConfig = SamplingConfig.debug()
    }

    @After
    fun tearDown() {
        GalaxyLogger.clear()
        GalaxyLogger.samplingConfig = SamplingConfig.debug()
    }

    // ── debug config ──────────────────────────────────────────────────────────

    @Test
    fun `debug config samples all known tags`() {
        val cfg = SamplingConfig.debug()
        listOf(
            GalaxyLogger.TAG_CONNECT,
            GalaxyLogger.TAG_DISCONNECT,
            GalaxyLogger.TAG_RECONNECT,
            GalaxyLogger.TAG_TASK_RECV,
            GalaxyLogger.TAG_TASK_EXEC,
            GalaxyLogger.TAG_TASK_RETURN,
            GalaxyLogger.TAG_TASK_TIMEOUT,
            GalaxyLogger.TAG_TASK_CANCEL,
            GalaxyLogger.TAG_SIGNAL_START,
            GalaxyLogger.TAG_SIGNAL_STOP,
            GalaxyLogger.TAG_DISPATCHER_SELECT,
            GalaxyLogger.TAG_BRIDGE_HANDOFF,
            GalaxyLogger.TAG_WEBRTC_TURN,
            GalaxyLogger.TAG_ERROR,
            GalaxyLogger.TAG_READINESS,
            GalaxyLogger.TAG_DEGRADED
        ).forEach { tag ->
            assertTrue("debug config must sample tag=$tag", cfg.shouldSample(tag))
        }
    }

    // ── production config ─────────────────────────────────────────────────────

    @Test
    fun `production config always samples TAG_ERROR`() {
        val cfg = SamplingConfig.production()
        // At rate=1.0 shouldSample must always return true
        repeat(20) {
            assertTrue("production must always sample TAG_ERROR", cfg.shouldSample(GalaxyLogger.TAG_ERROR))
        }
    }

    @Test
    fun `production config always samples task tags`() {
        val cfg = SamplingConfig.production()
        repeat(20) {
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_TASK_RECV))
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_TASK_EXEC))
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_TASK_RETURN))
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_TASK_TIMEOUT))
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_TASK_CANCEL))
        }
    }

    // ── Rate at 0.0 (never) ───────────────────────────────────────────────────

    @Test
    fun `shouldSample returns false for rate 0 dot 0`() {
        val cfg = SamplingConfig(connectionRate = 0.0)
        repeat(20) {
            assertFalse(
                "connectionRate=0.0 must never sample TAG_CONNECT",
                cfg.shouldSample(GalaxyLogger.TAG_CONNECT)
            )
        }
    }

    @Test
    fun `shouldSample returns false for signaling at rate 0 dot 0`() {
        val cfg = SamplingConfig(signalingRate = 0.0)
        repeat(20) {
            assertFalse(cfg.shouldSample(GalaxyLogger.TAG_SIGNAL_START))
            assertFalse(cfg.shouldSample(GalaxyLogger.TAG_SIGNAL_STOP))
        }
    }

    @Test
    fun `shouldSample returns false for TURN at rate 0 dot 0`() {
        val cfg = SamplingConfig(turnRate = 0.0)
        repeat(20) {
            assertFalse(cfg.shouldSample(GalaxyLogger.TAG_WEBRTC_TURN))
        }
    }

    @Test
    fun `shouldSample returns false for dispatcher at rate 0 dot 0`() {
        val cfg = SamplingConfig(dispatcherRate = 0.0)
        repeat(20) {
            assertFalse(cfg.shouldSample(GalaxyLogger.TAG_DISPATCHER_SELECT))
            assertFalse(cfg.shouldSample(GalaxyLogger.TAG_BRIDGE_HANDOFF))
        }
    }

    // ── Rate at 1.0 (always) ──────────────────────────────────────────────────

    @Test
    fun `shouldSample returns true for rate 1 dot 0`() {
        val cfg = SamplingConfig(connectionRate = 1.0)
        repeat(20) {
            assertTrue(cfg.shouldSample(GalaxyLogger.TAG_CONNECT))
        }
    }

    // ── Bounds validation ─────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `negative errorRate throws IllegalArgumentException`() {
        SamplingConfig(errorRate = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `errorRate above 1 throws IllegalArgumentException`() {
        SamplingConfig(errorRate = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative connectionRate throws`() {
        SamplingConfig(connectionRate = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `connectionRate above 1 throws`() {
        SamplingConfig(connectionRate = 2.0)
    }

    // ── GalaxyLogger.logSampled ───────────────────────────────────────────────

    @Test
    fun `logSampled records entry when samplingConfig is debug`() {
        GalaxyLogger.samplingConfig = SamplingConfig.debug()
        GalaxyLogger.logSampled(GalaxyLogger.TAG_SIGNAL_START, mapOf("url" to "ws://test"))
        val entries = GalaxyLogger.getEntries()
        assertEquals(1, entries.size)
        assertEquals(GalaxyLogger.TAG_SIGNAL_START, entries[0].tag)
    }

    @Test
    fun `logSampled drops entry when rate is 0 dot 0`() {
        GalaxyLogger.samplingConfig = SamplingConfig(connectionRate = 0.0)
        repeat(10) {
            GalaxyLogger.logSampled(GalaxyLogger.TAG_CONNECT)
        }
        assertTrue(
            "No CONNECT entries should be recorded at rate 0.0",
            GalaxyLogger.getEntries().none { it.tag == GalaxyLogger.TAG_CONNECT }
        )
    }

    @Test
    fun `logSampled always records TAG_ERROR regardless of samplingConfig`() {
        // Even with rate=0.0 for errors, logSampled must bypass the config for errors
        GalaxyLogger.samplingConfig = SamplingConfig(errorRate = 0.0)
        GalaxyLogger.logSampled(GalaxyLogger.TAG_ERROR, mapOf("trace_id" to "t1", "cause" to "test"))
        assertEquals(1, GalaxyLogger.getEntries().size)
    }

    // ── GalaxyLogger.logError ─────────────────────────────────────────────────

    @Test
    fun `logError always records an entry`() {
        GalaxyLogger.logError("trace-abc", "test_error")
        assertEquals(1, GalaxyLogger.getEntries().size)
    }

    @Test
    fun `logError sets tag to TAG_ERROR`() {
        GalaxyLogger.logError("t1", "some_cause")
        assertEquals(GalaxyLogger.TAG_ERROR, GalaxyLogger.getEntries()[0].tag)
    }

    @Test
    fun `logError includes trace_id in fields`() {
        GalaxyLogger.logError("trace-xyz-123", "test_cause")
        val fields = GalaxyLogger.getEntries()[0].fields
        assertEquals("trace-xyz-123", fields["trace_id"])
    }

    @Test
    fun `logError includes cause in fields`() {
        GalaxyLogger.logError("t1", "ws_connection_failed")
        val fields = GalaxyLogger.getEntries()[0].fields
        assertEquals("ws_connection_failed", fields["cause"])
    }

    @Test
    fun `logError merges extraFields`() {
        GalaxyLogger.logError(
            traceId = "t1",
            cause = "timeout",
            extraFields = mapOf("task_id" to "task-99", "session_id" to "s1")
        )
        val fields = GalaxyLogger.getEntries()[0].fields
        assertEquals("task-99", fields["task_id"])
        assertEquals("s1", fields["session_id"])
    }

    // ── Tag constant values ───────────────────────────────────────────────────

    @Test
    fun `TAG_SIGNAL_START equals GALAXY_SIGNAL_START`() {
        assertEquals("GALAXY:SIGNAL:START", GalaxyLogger.TAG_SIGNAL_START)
    }

    @Test
    fun `TAG_SIGNAL_STOP equals GALAXY_SIGNAL_STOP`() {
        assertEquals("GALAXY:SIGNAL:STOP", GalaxyLogger.TAG_SIGNAL_STOP)
    }

    @Test
    fun `TAG_DISPATCHER_SELECT equals GALAXY_DISPATCHER_SELECT`() {
        assertEquals("GALAXY:DISPATCHER:SELECT", GalaxyLogger.TAG_DISPATCHER_SELECT)
    }

    @Test
    fun `TAG_BRIDGE_HANDOFF equals GALAXY_BRIDGE_HANDOFF`() {
        assertEquals("GALAXY:BRIDGE:HANDOFF", GalaxyLogger.TAG_BRIDGE_HANDOFF)
    }

    @Test
    fun `TAG_WEBRTC_TURN equals GALAXY_WEBRTC_TURN`() {
        assertEquals("GALAXY:WEBRTC:TURN", GalaxyLogger.TAG_WEBRTC_TURN)
    }

    @Test
    fun `TAG_ERROR equals GALAXY_ERROR`() {
        assertEquals("GALAXY:ERROR", GalaxyLogger.TAG_ERROR)
    }
}
