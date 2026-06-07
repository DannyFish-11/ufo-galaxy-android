package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.observability.TraceContext
import com.ufo.galaxy.protocol.CrossRepoConsistencyGate
import com.ufo.galaxy.protocol.GateOutcome
import com.ufo.galaxy.protocol.GoalExecutionPayload
import com.ufo.galaxy.protocol.ProtocolSurface
import com.ufo.galaxy.protocol.UgcpProtocolConsistencyRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-4B — Trace round-trip, payload serialization verification, and gate wire-behavior coverage.
 *
 * Validates three inter-related requirements that turn the existing observability infrastructure
 * into a provable end-to-end evidence chain:
 *
 *  1. **dispatch_trace_id round-trip**: [TraceContext] can record an inbound dispatch trace ID
 *     and verify that the echoed value in the outbound result matches exactly.
 *
 *  2. **Real payload round-trip**: [GalaxyLogger.LogEntry] serialises field names from
 *     [RuntimeObservabilityMetadata] constants into the JSON wire format correctly, so the
 *     key that appears on the wire is exactly the canonical field name.  Also verifies the
 *     [GoalExecutionPayload] → [com.ufo.galaxy.protocol.GoalResultPayload] dispatch_trace_id
 *     echo path using [TraceContext.verifyDispatchTraceIdEcho].
 *
 *  3. **Gate wire-behavior coverage**: [CrossRepoConsistencyGate.checkObservabilityTraceFieldNames]
 *     verifies that the runtime values of [RuntimeObservabilityMetadata] field name constants
 *     match the cross-repo vocabulary — catching constant renames before they silently drift.
 *
 * ## Sections
 *
 * ### TraceContext — setDispatchTraceId / currentDispatchTraceId
 *  - setDispatchTraceId records non-blank id
 *  - setDispatchTraceId clears on null
 *  - setDispatchTraceId clears on blank string
 *  - currentDispatchTraceId returns null before any set
 *
 * ### TraceContext — verifyDispatchTraceIdEcho
 *  - verifyDispatchTraceIdEcho returns true when echoed id matches recorded id
 *  - verifyDispatchTraceIdEcho returns false when echoed id does not match
 *  - verifyDispatchTraceIdEcho returns false when no dispatch trace id recorded
 *  - verifyDispatchTraceIdEcho returns false for null echoed id
 *  - verifyDispatchTraceIdEcho returns false when recorded id is null and echoed is non-null
 *
 * ### TraceContext — reset clears dispatch trace id
 *  - reset clears any recorded dispatch trace id
 *
 * ### GalaxyLogger.LogEntry — payload field name round-trip
 *  - LogEntry fields map preserves dispatch_trace_id key from RuntimeObservabilityMetadata
 *  - LogEntry fields map preserves lifecycle_event_id key from RuntimeObservabilityMetadata
 *  - LogEntry fields map preserves session_correlation_id key from RuntimeObservabilityMetadata
 *  - LogEntry toJsonLine contains dispatch_trace_id field name and value
 *  - LogEntry toJsonLine contains lifecycle_event_id field name and value
 *  - LogEntry toJsonLine with dispatch_trace_id in fields uses canonical key from constant
 *
 * ### GoalExecutionPayload → GoalResultPayload — dispatch_trace_id round-trip via TraceContext
 *  - dispatch_trace_id round-trip: set in TraceContext, echoed in result, verified via verifyDispatchTraceIdEcho
 *  - TraceContext round-trip fails correctly when result echoes different id
 *  - TraceContext round-trip: null dispatch_trace_id in payload leaves currentDispatchTraceId null
 *
 * ### CrossRepoConsistencyGate — observability trace field names gate
 *  - observability trace field names gate passes
 *  - observability trace field names surface is in full gate report
 *  - full gate report includes observability trace field names and passes
 *  - dispatch_trace_id canonical value matches RuntimeObservabilityMetadata constant
 *  - lifecycle_event_id canonical value matches RuntimeObservabilityMetadata constant
 *  - session_correlation_id canonical value matches RuntimeObservabilityMetadata constant
 *  - gate detects drift when field name constant value diverges from canonical rule
 */
class Pr4bTraceRoundTripVerificationTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeScreenshot : EdgeExecutor.ScreenshotProvider {
        override fun captureJpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        override fun screenWidth() = 1080
        override fun screenHeight() = 2340
    }

    private class OkPlanner : LocalPlannerService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun plan(goal: String, constraints: List<String>, screenshotBase64: String?) =
            LocalPlannerService.PlanResult(
                steps = listOf(LocalPlannerService.PlanStep("tap", "tap the button"))
            )
        override fun replan(
            goal: String, constraints: List<String>,
            failedStep: LocalPlannerService.PlanStep,
            error: String, screenshotBase64: String?
        ) = LocalPlannerService.PlanResult(steps = emptyList(), error = "replan not supported")
    }

    private class OkGrounder : LocalGroundingService {
        override fun loadModel() = true
        override fun unloadModel() {}
        override fun isModelLoaded() = true
        override fun ground(intent: String, screenshotBase64: String, width: Int, height: Int) =
            LocalGroundingService.GroundingResult(x = 540, y = 960, confidence = 0.9f, element_description = "button")
    }

    private class OkAccessibility : AccessibilityExecutor {
        override fun execute(action: AccessibilityExecutor.AccessibilityAction) = true
    }

    private fun buildPipeline(
        crossDeviceEnabled: Boolean = true,
        deviceId: String = "test-device"
    ): AutonomousExecutionPipeline {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = true,
            parallelExecutionEnabled = true,
            crossDeviceEnabled = crossDeviceEnabled,
            deviceRole = "phone"
        )
        val edge = EdgeExecutor(
            screenshotProvider = FakeScreenshot(),
            plannerService = OkPlanner(),
            groundingService = OkGrounder(),
            accessibilityExecutor = OkAccessibility()
        )
        val goalExec = LocalGoalExecutor(edge, deviceId)
        val collab = LocalCollaborationAgent(goalExec)
        return AutonomousExecutionPipeline(
            settings = settings,
            goalExecutor = goalExec,
            collaborationAgent = collab,
            deviceId = deviceId,
            deviceRole = "phone"
        )
    }

    @Before
    fun setUp() {
        TraceContext.reset()
        GalaxyLogger.clear()
    }

    @After
    fun tearDown() {
        TraceContext.reset()
        GalaxyLogger.clear()
    }

    // ── TraceContext — setDispatchTraceId / currentDispatchTraceId ────────────

    @Test
    fun `setDispatchTraceId records non-blank id`() {
        TraceContext.setDispatchTraceId("dt-test-001")
        assertEquals(
            "setDispatchTraceId must record the provided id",
            "dt-test-001",
            TraceContext.currentDispatchTraceId()
        )
    }

    @Test
    fun `setDispatchTraceId clears on null`() {
        TraceContext.setDispatchTraceId("dt-initial")
        TraceContext.setDispatchTraceId(null)
        assertNull(
            "setDispatchTraceId(null) must clear the recorded id",
            TraceContext.currentDispatchTraceId()
        )
    }

    @Test
    fun `setDispatchTraceId clears on blank string`() {
        TraceContext.setDispatchTraceId("dt-initial")
        TraceContext.setDispatchTraceId("   ")
        assertNull(
            "setDispatchTraceId with blank string must clear the recorded id",
            TraceContext.currentDispatchTraceId()
        )
    }

    @Test
    fun `currentDispatchTraceId returns null before any set`() {
        // reset() was called in setUp(); no setDispatchTraceId called yet
        assertNull(
            "currentDispatchTraceId must return null when no dispatch trace id has been set",
            TraceContext.currentDispatchTraceId()
        )
    }

    // ── TraceContext — verifyDispatchTraceIdEcho ──────────────────────────────

    @Test
    fun `verifyDispatchTraceIdEcho returns true when echoed id matches recorded id`() {
        val id = "dt-echo-match-001"
        TraceContext.setDispatchTraceId(id)
        assertTrue(
            "verifyDispatchTraceIdEcho must return true when echoed id matches the recorded dispatch trace id",
            TraceContext.verifyDispatchTraceIdEcho(id)
        )
    }

    @Test
    fun `verifyDispatchTraceIdEcho returns false when echoed id does not match`() {
        TraceContext.setDispatchTraceId("dt-recorded")
        assertFalse(
            "verifyDispatchTraceIdEcho must return false when echoed id differs from recorded id",
            TraceContext.verifyDispatchTraceIdEcho("dt-different")
        )
    }

    @Test
    fun `verifyDispatchTraceIdEcho returns false when no dispatch trace id recorded`() {
        // No setDispatchTraceId called; currentDispatchTraceId is null
        assertFalse(
            "verifyDispatchTraceIdEcho must return false when no dispatch trace id has been recorded",
            TraceContext.verifyDispatchTraceIdEcho("dt-echo-without-record")
        )
    }

    @Test
    fun `verifyDispatchTraceIdEcho returns false for null echoed id`() {
        TraceContext.setDispatchTraceId("dt-recorded")
        assertFalse(
            "verifyDispatchTraceIdEcho must return false when the echoed id is null",
            TraceContext.verifyDispatchTraceIdEcho(null)
        )
    }

    @Test
    fun `verifyDispatchTraceIdEcho returns false when recorded id is null and echoed is non-null`() {
        // No dispatch trace id recorded — currentDispatchTraceId is null
        assertFalse(
            "verifyDispatchTraceIdEcho must return false when no id was recorded even if echoed is non-null",
            TraceContext.verifyDispatchTraceIdEcho("dt-some-value")
        )
    }

    // ── TraceContext — reset clears dispatch trace id ─────────────────────────

    @Test
    fun `reset clears any recorded dispatch trace id`() {
        TraceContext.setDispatchTraceId("dt-before-reset")
        assertNotNull("Pre-condition: dispatch trace id must be set before reset", TraceContext.currentDispatchTraceId())
        TraceContext.reset()
        assertNull(
            "reset() must clear any recorded dispatch trace id",
            TraceContext.currentDispatchTraceId()
        )
    }

    // ── GalaxyLogger.LogEntry — payload field name round-trip ─────────────────

    @Test
    fun `LogEntry fields map preserves dispatch_trace_id key from RuntimeObservabilityMetadata`() {
        val traceId = "dt-logentry-roundtrip"
        val entry = GalaxyLogger.LogEntry(
            ts = 1000L,
            tag = GalaxyLogger.TAG_DISPATCH_DECISION,
            fields = mapOf(RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID to traceId)
        )
        assertEquals(
            "LogEntry must preserve the dispatch_trace_id value when keyed by RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID",
            traceId,
            entry.fields[RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID]
        )
        assertTrue(
            "LogEntry fields must contain the exact canonical key '${RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID}'",
            entry.fields.containsKey(RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID)
        )
    }

    @Test
    fun `LogEntry fields map preserves lifecycle_event_id key from RuntimeObservabilityMetadata`() {
        val lifecycleId = "le-logentry-roundtrip"
        val entry = GalaxyLogger.LogEntry(
            ts = 2000L,
            tag = GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            fields = mapOf(RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID to lifecycleId)
        )
        assertEquals(
            "LogEntry must preserve the lifecycle_event_id value when keyed by RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID",
            lifecycleId,
            entry.fields[RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID]
        )
    }

    @Test
    fun `LogEntry fields map preserves session_correlation_id key from RuntimeObservabilityMetadata`() {
        val sessionCorrId = "sc-logentry-roundtrip"
        val entry = GalaxyLogger.LogEntry(
            ts = 3000L,
            tag = GalaxyLogger.TAG_RECOVERY_OBSERVE,
            fields = mapOf(RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID to sessionCorrId)
        )
        assertEquals(
            "LogEntry must preserve the session_correlation_id value when keyed by RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID",
            sessionCorrId,
            entry.fields[RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID]
        )
    }

    @Test
    fun `LogEntry toJsonLine contains dispatch_trace_id field name and value`() {
        val traceId = "dt-json-001"
        val entry = GalaxyLogger.LogEntry(
            ts = 1000L,
            tag = GalaxyLogger.TAG_DISPATCH_DECISION,
            fields = mapOf(RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID to traceId)
        )
        val json = entry.toJsonLine()
        assertTrue(
            "toJsonLine output must contain the canonical field name '${RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID}'",
            json.contains(RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID)
        )
        assertTrue(
            "toJsonLine output must contain the dispatch trace id value '$traceId'",
            json.contains(traceId)
        )
    }

    @Test
    fun `LogEntry toJsonLine contains lifecycle_event_id field name and value`() {
        val lifecycleId = "le-json-002"
        val entry = GalaxyLogger.LogEntry(
            ts = 2000L,
            tag = GalaxyLogger.TAG_LIFECYCLE_OBSERVE,
            fields = mapOf(RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID to lifecycleId)
        )
        val json = entry.toJsonLine()
        assertTrue(
            "toJsonLine output must contain the canonical field name '${RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID}'",
            json.contains(RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID)
        )
        assertTrue(
            "toJsonLine output must contain the lifecycle event id value '$lifecycleId'",
            json.contains(lifecycleId)
        )
    }

    @Test
    fun `LogEntry toJsonLine with dispatch_trace_id uses canonical key from constant`() {
        // This test verifies that the key "dispatch_trace_id" (not some other variant)
        // appears in the JSON output, confirming wire format consistency.
        val entry = GalaxyLogger.LogEntry(
            ts = 1000L,
            tag = GalaxyLogger.TAG_DISPATCH_DECISION,
            fields = mapOf(
                RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID to "dt-canonical-key-test"
            )
        )
        val json = entry.toJsonLine()
        // Verify the exact canonical string "dispatch_trace_id" appears in the JSON
        assertTrue(
            "JSON wire format must use exactly 'dispatch_trace_id' as the field key — " +
                "not 'dispatch_trace_identifier' or any other variant",
            json.contains("\"dispatch_trace_id\"")
        )
    }

    // ── GoalExecutionPayload → GoalResultPayload — dispatch_trace_id round-trip ──

    @Test
    fun `dispatch_trace_id round-trip via TraceContext verifyDispatchTraceIdEcho`() {
        val dispatchTraceId = "dt-full-roundtrip-001"
        val payload = GoalExecutionPayload(
            task_id = "t-rt-001",
            goal = "open settings",
            dispatch_trace_id = dispatchTraceId
        )

        // Simulate recording the inbound dispatch trace id (as the runtime would do on receive)
        TraceContext.setDispatchTraceId(payload.dispatch_trace_id)

        // Execute through the pipeline (disabled path echoes dispatch_trace_id)
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleGoalExecution(payload)

        // Verify the echo: result.dispatch_trace_id must match what was recorded
        assertTrue(
            "dispatch_trace_id must survive the full inbound → execution → result round-trip; " +
                "TraceContext.verifyDispatchTraceIdEcho must return true",
            TraceContext.verifyDispatchTraceIdEcho(result.dispatch_trace_id)
        )
    }

    @Test
    fun `TraceContext round-trip fails correctly when result echoes different id`() {
        TraceContext.setDispatchTraceId("dt-recorded-id")
        // Simulate a result that echoes a different (wrong) id
        assertFalse(
            "verifyDispatchTraceIdEcho must fail when the result echoes a different dispatch_trace_id",
            TraceContext.verifyDispatchTraceIdEcho("dt-wrong-id")
        )
    }

    @Test
    fun `TraceContext round-trip: null dispatch_trace_id in payload leaves currentDispatchTraceId null`() {
        val payload = GoalExecutionPayload(
            task_id = "t-rt-legacy",
            goal = "open settings"
            // dispatch_trace_id defaults to null (legacy payload)
        )
        TraceContext.setDispatchTraceId(payload.dispatch_trace_id) // sets null
        assertNull(
            "currentDispatchTraceId must be null when payload carries no dispatch_trace_id (legacy sender)",
            TraceContext.currentDispatchTraceId()
        )
        // Verify the pipeline result also has null dispatch_trace_id
        val pipeline = buildPipeline(crossDeviceEnabled = false)
        val result = pipeline.handleGoalExecution(payload)
        assertNull(
            "Legacy payload (no dispatch_trace_id) must produce a result with null dispatch_trace_id",
            result.dispatch_trace_id
        )
    }

    // ── CrossRepoConsistencyGate — observability trace field names gate ────────

    @Test
    fun `observability trace field names gate passes`() {
        val result = CrossRepoConsistencyGate.checkObservabilityTraceFieldNames()
        assertEquals(
            "Observability trace field names gate failed: ${result.violations.joinToString { it.detail }}",
            GateOutcome.PASS,
            result.outcome
        )
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `observability trace field names surface is in full gate report`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        val reportedSurfaces = report.results.map { it.surface }.toSet()
        assertTrue(
            "OBSERVABILITY_TRACE_FIELD_NAMES must appear in the full gate report",
            reportedSurfaces.contains(ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES)
        )
    }

    @Test
    fun `full gate report includes observability trace field names and passes`() {
        val report = CrossRepoConsistencyGate.runAllGates()
        assertEquals(
            "Full gate report must pass when observability trace field names gate is included:\n${report.failureSummary()}",
            GateOutcome.PASS,
            report.overallOutcome
        )
    }

    @Test
    fun `dispatch_trace_id canonical value matches RuntimeObservabilityMetadata constant`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES
        )
        assertTrue(
            "The canonical rule for OBSERVABILITY_TRACE_FIELD_NAMES must include " +
                "RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID = " +
                "'${RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID}'",
            canonical.contains(RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID)
        )
    }

    @Test
    fun `lifecycle_event_id canonical value matches RuntimeObservabilityMetadata constant`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES
        )
        assertTrue(
            "The canonical rule for OBSERVABILITY_TRACE_FIELD_NAMES must include " +
                "RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID = " +
                "'${RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID}'",
            canonical.contains(RuntimeObservabilityMetadata.FIELD_LIFECYCLE_EVENT_ID)
        )
    }

    @Test
    fun `session_correlation_id canonical value matches RuntimeObservabilityMetadata constant`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES
        )
        assertTrue(
            "The canonical rule for OBSERVABILITY_TRACE_FIELD_NAMES must include " +
                "RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID = " +
                "'${RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID}'",
            canonical.contains(RuntimeObservabilityMetadata.FIELD_SESSION_CORRELATION_ID)
        )
    }

    @Test
    fun `gate detects drift when field name constant value diverges from canonical rule`() {
        // Verify that using a non-canonical field name produces a DRIFT_CANDIDATE
        val surface = ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES
        val driftValue = "dispatch_trace_identifier" // renamed from canonical "dispatch_trace_id"
        val checkResult = UgcpProtocolConsistencyRules.checkValue(surface, driftValue)
        assertEquals(
            "A renamed field name constant '$driftValue' must be classified as DRIFT_CANDIDATE " +
                "by the OBSERVABILITY_TRACE_FIELD_NAMES gate — proving the gate catches renames",
            com.ufo.galaxy.protocol.ConsistencyCheckStatus.DRIFT_CANDIDATE,
            checkResult.status
        )
    }
}
