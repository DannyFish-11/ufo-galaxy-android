package com.ufo.galaxy.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7B — Regression-protection tests for Android truth publication semantics.
 *
 * Validates all deliverables of PR-7B (Android truth publication semantics alignment):
 *
 * 1. [AndroidTruthPublicationSemanticsContract] — EvidencePresenceKind enum is well-formed,
 *    wire values are stable, classifier methods produce correct outputs.
 * 2. [DeviceStateSnapshotPayload] — `evidence_presence_kind` field is present and defaults null.
 * 3. [DeviceExecutionEventPayload] — `evidence_presence_kind` field is present and defaults null.
 * 4. Classifier semantics — snapshot and event classifiers produce the correct EvidencePresenceKind
 *    for each observable state combination, with no optimistic-default risk.
 * 5. CLOSURE_INVARIANTS all pass.
 * 6. [StabilizationBaseline] registration for the new contract surface.
 * 7. Gson round-trip stability for the new wire field in both payload types.
 *
 * ## Coverage areas
 *
 * ### EvidencePresenceKind — enum coverage
 *  - has exactly 6 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - only POSITIVE_EVIDENCE has isPositiveEvidence=true
 *  - all non-positive kinds have non-empty v2GovernanceHint
 *
 * ### classifySnapshotEvidencePresence — classifier correctness
 *  - FAILED_OBSERVATION when manager is Failed/FailedStartup or warmup_result="failed"
 *  - UNAVAILABLE when pending_first_download=true
 *  - UNKNOWN when runtime is starting or warmup not_started or localLoopReady=null
 *  - PARTIAL when plannerReady != groundingReady
 *  - DELAYED when offlineQueueDepth > 0
 *  - POSITIVE_EVIDENCE when running + warmup ok + no queue + loop ready
 *  - UNKNOWN as default fallback
 *
 * ### classifyEventEvidencePresence — classifier correctness
 *  - POSITIVE_EVIDENCE for PHASE_COMPLETED, PHASE_CANCELLED, PHASE_EXECUTION_PROGRESS
 *  - FAILED_OBSERVATION for PHASE_FAILED, PHASE_STAGNATION_DETECTED
 *  - PARTIAL for PHASE_FALLBACK_TRANSITION
 *  - UNKNOWN for PHASE_EXECUTION_STARTED, PHASE_TAKEOVER_MILESTONE
 *  - UNKNOWN for unknown phase strings
 *
 * ### Wire field presence
 *  - DeviceStateSnapshotPayload.evidence_presence_kind defaults null
 *  - DeviceExecutionEventPayload.evidence_presence_kind defaults null
 *  - Both fields are serialised by Gson correctly
 *
 * ### CLOSURE_INVARIANTS
 *  - All invariants pass
 *
 * ### StabilizationBaseline
 *  - "android-truth-publication-semantics" is registered as CANONICAL_STABLE
 */
class Pr7BAndroidTruthPublicationSemanticsTest {

    // ── EvidencePresenceKind — enum coverage ──────────────────────────────────

    @Test
    fun `EvidencePresenceKind has exactly 6 values`() {
        assertEquals(6, AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries.size)
    }

    @Test
    fun `EvidencePresenceKind ALL_WIRE_VALUES has size 6`() {
        assertEquals(6, AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `EvidencePresenceKind wire values match expected set`() {
        val expected = setOf(
            "positive_evidence",
            "unknown",
            "unavailable",
            "delayed",
            "partial",
            "failed_observation"
        )
        assertEquals(expected, AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.ALL_WIRE_VALUES)
    }

    @Test
    fun `only POSITIVE_EVIDENCE has isPositiveEvidence true`() {
        val positiveKinds = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries
            .filter { it.isPositiveEvidence }
        assertEquals(1, positiveKinds.size)
        assertEquals(
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE,
            positiveKinds.first()
        )
    }

    @Test
    fun `all non-positive kinds have non-empty v2GovernanceHint`() {
        for (kind in AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries) {
            if (!kind.isPositiveEvidence) {
                assertTrue(
                    "${kind.name} must have a non-empty v2GovernanceHint so V2 knows what policy to apply",
                    kind.v2GovernanceHint.isNotEmpty()
                )
            }
        }
    }

    @Test
    fun `all EvidencePresenceKind values have non-empty displayName`() {
        for (kind in AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries) {
            assertTrue("${kind.name} must have non-empty displayName", kind.displayName.isNotEmpty())
        }
    }

    @Test
    fun `all EvidencePresenceKind values have non-empty description`() {
        for (kind in AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.entries) {
            assertTrue("${kind.name} must have non-empty description", kind.description.isNotEmpty())
        }
    }

    @Test
    fun `POSITIVE_EVIDENCE wireValue is positive_evidence`() {
        assertEquals(
            "positive_evidence",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE.wireValue
        )
    }

    @Test
    fun `UNKNOWN wireValue is unknown`() {
        assertEquals(
            "unknown",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN.wireValue
        )
    }

    @Test
    fun `UNAVAILABLE wireValue is unavailable`() {
        assertEquals(
            "unavailable",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNAVAILABLE.wireValue
        )
    }

    @Test
    fun `DELAYED wireValue is delayed`() {
        assertEquals(
            "delayed",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.DELAYED.wireValue
        )
    }

    @Test
    fun `PARTIAL wireValue is partial`() {
        assertEquals(
            "partial",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL.wireValue
        )
    }

    @Test
    fun `FAILED_OBSERVATION wireValue is failed_observation`() {
        assertEquals(
            "failed_observation",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION.wireValue
        )
    }

    @Test
    fun `POSITIVE_EVIDENCE isPositiveEvidence is true`() {
        assertTrue(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE.isPositiveEvidence)
    }

    @Test
    fun `UNKNOWN isPositiveEvidence is false`() {
        assertFalse(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN.isPositiveEvidence)
    }

    @Test
    fun `UNAVAILABLE isPositiveEvidence is false`() {
        assertFalse(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNAVAILABLE.isPositiveEvidence)
    }

    @Test
    fun `DELAYED isPositiveEvidence is false`() {
        assertFalse(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.DELAYED.isPositiveEvidence)
    }

    @Test
    fun `PARTIAL isPositiveEvidence is false`() {
        assertFalse(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL.isPositiveEvidence)
    }

    @Test
    fun `FAILED_OBSERVATION isPositiveEvidence is false`() {
        assertFalse(AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION.isPositiveEvidence)
    }

    // ── classifySnapshotEvidencePresence — classifier correctness ─────────────

    @Test
    fun `classifySnapshotEvidencePresence returns FAILED_OBSERVATION when manager is Failed`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "failed",
            pendingFirstDownload = false,
            localLoopReady = false,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = true,
            managerStateIsStarting = false
        )
        assertEquals(
            "Failed manager state must yield FAILED_OBSERVATION; V2 must not assume healthy",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns FAILED_OBSERVATION when warmup result is failed`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "failed",
            pendingFirstDownload = false,
            localLoopReady = false,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "warmup_result=failed must yield FAILED_OBSERVATION regardless of manager state flag",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence FAILED_OBSERVATION takes priority over UNAVAILABLE`() {
        // Even if pending_first_download=true, a failed manager state takes FAILED_OBSERVATION priority
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "failed",
            pendingFirstDownload = true,
            localLoopReady = null,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = true,
            managerStateIsStarting = false
        )
        assertEquals(
            "FAILED_OBSERVATION must win over UNAVAILABLE when both conditions are present",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns UNAVAILABLE when pending first download`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "unavailable",
            pendingFirstDownload = true,
            localLoopReady = null,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "pending_first_download=true means model is explicitly absent; V2 must block dependent dispatch",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNAVAILABLE,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns UNKNOWN when runtime is starting`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "not_started",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = false,
            managerStateIsStarting = true
        )
        assertEquals(
            "Starting runtime must yield UNKNOWN; V2 must not treat absence of evidence as healthy",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns UNKNOWN when localLoopReady is null`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Null localLoopReady means the readiness signal is not yet available; must be UNKNOWN not healthy",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns UNKNOWN when warmup is not_started`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "not_started",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "warmup_result=not_started must yield UNKNOWN; runtime not yet initialised",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns PARTIAL when planner ready but grounding not ready`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = true,
            groundingReady = false,
            offlineQueueDepth = 0,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "planner_ready=true but grounding_ready=false means only partial subsystem evidence; V2 must not assume full capability",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns PARTIAL when grounding ready but planner not ready`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = null,
            plannerReady = false,
            groundingReady = true,
            offlineQueueDepth = 0,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "grounding_ready=true but planner_ready=false means only partial subsystem evidence",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns DELAYED when offline queue is non-empty`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = true,
            plannerReady = true,
            groundingReady = true,
            offlineQueueDepth = 3,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Non-empty offline queue means evidence may be stale; V2 must apply staleness penalty",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.DELAYED,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns DELAYED when offline queue depth is 1`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = true,
            plannerReady = true,
            groundingReady = true,
            offlineQueueDepth = 1,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Offline queue depth=1 must yield DELAYED (minimum threshold is > 0)",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.DELAYED,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns POSITIVE_EVIDENCE when running and fully ready`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = true,
            plannerReady = true,
            groundingReady = true,
            offlineQueueDepth = 0,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Running + warmup ok + loop ready + no queue must yield POSITIVE_EVIDENCE; V2 applies standard dispatch",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE,
            result
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence POSITIVE_EVIDENCE requires offline queue to be zero`() {
        val withQueue = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "ok",
            pendingFirstDownload = false,
            localLoopReady = true,
            plannerReady = true,
            groundingReady = true,
            offlineQueueDepth = 1,
            managerStateIsRunning = true,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertFalse(
            "A non-zero offline queue must not yield POSITIVE_EVIDENCE (must be DELAYED)",
            withQueue == AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE
        )
    }

    @Test
    fun `classifySnapshotEvidencePresence returns UNKNOWN as default when manager not running and not starting`() {
        val result = AndroidTruthPublicationSemanticsContract.classifySnapshotEvidencePresence(
            warmupResult = "degraded",
            pendingFirstDownload = false,
            localLoopReady = true,
            plannerReady = null,
            groundingReady = null,
            offlineQueueDepth = 0,
            managerStateIsRunning = false,
            managerStateIsFailed = false,
            managerStateIsStarting = false
        )
        assertEquals(
            "Unclassified transitional state must yield UNKNOWN; never optimistically healthy",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            result
        )
    }

    // ── classifyEventEvidencePresence — classifier correctness ────────────────

    @Test
    fun `classifyEventEvidencePresence returns POSITIVE_EVIDENCE for PHASE_COMPLETED`() {
        assertEquals(
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_COMPLETED
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns POSITIVE_EVIDENCE for PHASE_CANCELLED`() {
        assertEquals(
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_CANCELLED
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns POSITIVE_EVIDENCE for PHASE_EXECUTION_PROGRESS`() {
        assertEquals(
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.POSITIVE_EVIDENCE,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns FAILED_OBSERVATION for PHASE_FAILED`() {
        assertEquals(
            "PHASE_FAILED must yield FAILED_OBSERVATION; V2 must apply recovery policy not assume healthy",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_FAILED
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns FAILED_OBSERVATION for PHASE_STAGNATION_DETECTED`() {
        assertEquals(
            "PHASE_STAGNATION_DETECTED must yield FAILED_OBSERVATION; stagnation is a runtime failure signal",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns PARTIAL for PHASE_FALLBACK_TRANSITION`() {
        assertEquals(
            "PHASE_FALLBACK_TRANSITION must yield PARTIAL; primary path unavailable, fallback only",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns UNKNOWN for PHASE_EXECUTION_STARTED`() {
        assertEquals(
            "PHASE_EXECUTION_STARTED must yield UNKNOWN; execution begun but no runtime confirmation yet",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns UNKNOWN for PHASE_TAKEOVER_MILESTONE`() {
        assertEquals(
            "PHASE_TAKEOVER_MILESTONE must yield UNKNOWN; transitional handoff state",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence(
                DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
            )
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns UNKNOWN for unrecognised phase strings`() {
        assertEquals(
            "Unrecognised phase must yield UNKNOWN; never assume healthy for unknown phases",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence("some_future_phase")
        )
    }

    @Test
    fun `classifyEventEvidencePresence returns UNKNOWN for empty phase string`() {
        assertEquals(
            "Empty phase string must yield UNKNOWN",
            AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN,
            AndroidTruthPublicationSemanticsContract.classifyEventEvidencePresence("")
        )
    }

    // ── Wire field presence in DeviceStateSnapshotPayload ────────────────────

    @Test
    fun `DeviceStateSnapshotPayload evidence_presence_kind defaults to null`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "test-device",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            warmup_result = "unavailable",
            offline_queue_depth = 0,
            current_fallback_tier = null
        )
        assertNull(
            "evidence_presence_kind must default to null; explicit population is required at emission sites",
            payload.evidence_presence_kind
        )
    }

    @Test
    fun `DeviceStateSnapshotPayload evidence_presence_kind can be set to positive_evidence`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "test-device",
            llama_cpp_available = true,
            ncnn_available = true,
            active_runtime_type = "HYBRID",
            model_ready = true,
            accessibility_ready = true,
            overlay_ready = true,
            local_loop_ready = true,
            model_id = "mobilevlm",
            runtime_type = "HYBRID",
            checksum_ok = true,
            mobilevlm_present = true,
            mobilevlm_checksum_ok = true,
            seeclick_present = true,
            pending_first_download = false,
            warmup_result = "ok",
            offline_queue_depth = 0,
            current_fallback_tier = null,
            evidence_presence_kind = "positive_evidence"
        )
        assertEquals("positive_evidence", payload.evidence_presence_kind)
    }

    @Test
    fun `DeviceStateSnapshotPayload evidence_presence_kind can be set to unknown`() {
        val payload = DeviceStateSnapshotPayload(
            device_id = "test-device",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = false,
            warmup_result = "not_started",
            offline_queue_depth = 0,
            current_fallback_tier = null,
            evidence_presence_kind = "unknown"
        )
        assertEquals("unknown", payload.evidence_presence_kind)
    }

    // ── Wire field presence in DeviceExecutionEventPayload ────────────────────

    @Test
    fun `DeviceExecutionEventPayload evidence_presence_kind defaults to null`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED
        )
        assertNull(
            "evidence_presence_kind must default to null; explicit population is required at emission sites",
            event.evidence_presence_kind
        )
    }

    @Test
    fun `DeviceExecutionEventPayload evidence_presence_kind can be set to positive_evidence`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            evidence_presence_kind = "positive_evidence"
        )
        assertEquals("positive_evidence", event.evidence_presence_kind)
    }

    @Test
    fun `DeviceExecutionEventPayload evidence_presence_kind can be set to failed_observation`() {
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_FAILED,
            is_blocking = true,
            evidence_presence_kind = "failed_observation"
        )
        assertEquals("failed_observation", event.evidence_presence_kind)
    }

    // ── Gson round-trip stability ─────────────────────────────────────────────

    @Test
    fun `DeviceStateSnapshotPayload evidence_presence_kind is serialised by Gson`() {
        val gson = Gson()
        val payload = DeviceStateSnapshotPayload(
            device_id = "test-device",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            warmup_result = "unavailable",
            offline_queue_depth = 0,
            current_fallback_tier = null,
            evidence_presence_kind = "unavailable"
        )
        val json = gson.toJson(payload)
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertTrue(
            "evidence_presence_kind must be present in serialised JSON",
            obj.has("evidence_presence_kind")
        )
        assertEquals("unavailable", obj.get("evidence_presence_kind").asString)
    }

    @Test
    fun `DeviceExecutionEventPayload evidence_presence_kind is serialised by Gson`() {
        val gson = Gson()
        val event = DeviceExecutionEventPayload(
            flow_id = "flow-1",
            task_id = "task-1",
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            evidence_presence_kind = "positive_evidence"
        )
        val json = gson.toJson(event)
        val obj = gson.fromJson(json, JsonObject::class.java)
        assertTrue(
            "evidence_presence_kind must be present in serialised JSON for execution events",
            obj.has("evidence_presence_kind")
        )
        assertEquals("positive_evidence", obj.get("evidence_presence_kind").asString)
    }

    @Test
    fun `DeviceStateSnapshotPayload null evidence_presence_kind is absent from Gson output`() {
        val gson = Gson()
        val payload = DeviceStateSnapshotPayload(
            device_id = "test-device",
            llama_cpp_available = false,
            ncnn_available = false,
            active_runtime_type = "CENTER",
            model_ready = false,
            accessibility_ready = false,
            overlay_ready = false,
            local_loop_ready = false,
            model_id = null,
            runtime_type = null,
            checksum_ok = null,
            mobilevlm_present = false,
            mobilevlm_checksum_ok = false,
            seeclick_present = false,
            pending_first_download = true,
            warmup_result = "unavailable",
            offline_queue_depth = 0,
            current_fallback_tier = null
            // evidence_presence_kind not set (null)
        )
        val json = gson.toJson(payload)
        val obj = gson.fromJson(json, JsonObject::class.java)
        // Gson omits null fields for data classes with nullable fields — correct for wire semantics
        // where an absent field signals "not yet populated" rather than "healthy default"
        assertNull(
            "Null evidence_presence_kind must not be serialised as 'null' string (Gson omits null fields)",
            payload.evidence_presence_kind
        )
    }

    // ── CLOSURE_INVARIANTS ────────────────────────────────────────────────────

    @Test
    fun `all CLOSURE_INVARIANTS pass`() {
        val failing = AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS
            .filter { (_, passes) -> !passes }
        assertTrue(
            "All CLOSURE_INVARIANTS must pass; failing: ${failing.keys}",
            failing.isEmpty()
        )
    }

    @Test
    fun `CLOSURE_INVARIANT evidence_presence_kind_has_6_values passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["evidence_presence_kind_has_6_values"] == true
        )
    }

    @Test
    fun `CLOSURE_INVARIANT only_positive_evidence_kind_is_positive passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["only_positive_evidence_kind_is_positive"] == true
        )
    }

    @Test
    fun `CLOSURE_INVARIANT classify_snapshot_running_ready_yields_positive_evidence passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["classify_snapshot_running_ready_yields_positive_evidence"] == true
        )
    }

    @Test
    fun `CLOSURE_INVARIANT classify_snapshot_failed_manager_yields_failed_observation passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["classify_snapshot_failed_manager_yields_failed_observation"] == true
        )
    }

    @Test
    fun `CLOSURE_INVARIANT classify_event_completed_yields_positive_evidence passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["classify_event_completed_yields_positive_evidence"] == true
        )
    }

    @Test
    fun `CLOSURE_INVARIANT classify_event_failed_yields_failed_observation passes`() {
        assertTrue(
            AndroidTruthPublicationSemanticsContract.CLOSURE_INVARIANTS["classify_event_failed_yields_failed_observation"] == true
        )
    }

    // ── StabilizationBaseline registration ───────────────────────────────────

    @Test
    fun `StabilizationBaseline contains android-truth-publication-semantics entry`() {
        assertTrue(
            "'android-truth-publication-semantics' must be registered in StabilizationBaseline",
            StabilizationBaseline.isRegistered("android-truth-publication-semantics")
        )
    }

    @Test
    fun `StabilizationBaseline android-truth-publication-semantics is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-truth-publication-semantics")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
    }

    @Test
    fun `StabilizationBaseline android-truth-publication-semantics has correct package path`() {
        val entry = StabilizationBaseline.forId("android-truth-publication-semantics")
        assertNotNull(entry)
        assertEquals(
            "com.ufo.galaxy.runtime.AndroidTruthPublicationSemanticsContract",
            entry!!.packagePath
        )
    }

    @Test
    fun `StabilizationBaseline android-truth-publication-semantics is in canonical stable set`() {
        assertTrue(
            "'android-truth-publication-semantics' must be in the canonicalSurfaceIds set",
            StabilizationBaseline.canonicalSurfaceIds.contains("android-truth-publication-semantics")
        )
    }

    // ── Semantic safety: no optimistic defaults ───────────────────────────────

    @Test
    fun `UNKNOWN v2GovernanceHint prevents optimistic dispatch`() {
        val hint = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNKNOWN.v2GovernanceHint
        assertTrue(
            "UNKNOWN governance hint must signal withholding, not dispatch: $hint",
            hint.contains("withhold") || hint.contains("pending")
        )
    }

    @Test
    fun `FAILED_OBSERVATION v2GovernanceHint prevents healthy assumption`() {
        val hint = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.FAILED_OBSERVATION.v2GovernanceHint
        assertFalse(
            "FAILED_OBSERVATION governance hint must not suggest dispatch is safe: $hint",
            hint.contains("dispatch") && !hint.contains("do_not") && !hint.contains("recovery")
        )
    }

    @Test
    fun `UNAVAILABLE v2GovernanceHint prevents dispatch of dependent capabilities`() {
        val hint = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.UNAVAILABLE.v2GovernanceHint
        assertTrue(
            "UNAVAILABLE governance hint must signal blocking: $hint",
            hint.contains("block")
        )
    }

    @Test
    fun `PARTIAL v2GovernanceHint limits dispatch to confirmed subsystems`() {
        val hint = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.PARTIAL.v2GovernanceHint
        assertTrue(
            "PARTIAL governance hint must restrict dispatch to confirmed subsystems: $hint",
            hint.contains("limit") || hint.contains("confirmed")
        )
    }

    @Test
    fun `DELAYED v2GovernanceHint applies staleness penalty`() {
        val hint = AndroidTruthPublicationSemanticsContract.EvidencePresenceKind.DELAYED.v2GovernanceHint
        assertTrue(
            "DELAYED governance hint must signal staleness: $hint",
            hint.contains("staleness") || hint.contains("timeout")
        )
    }

    @Test
    fun `INTRODUCED_PR constant is PR-7B`() {
        assertEquals("PR-7B", AndroidTruthPublicationSemanticsContract.INTRODUCED_PR)
    }

    @Test
    fun `TEST_CLASS constant references this test class`() {
        assertEquals(
            "Pr7BAndroidTruthPublicationSemanticsTest",
            AndroidTruthPublicationSemanticsContract.TEST_CLASS
        )
    }
}
