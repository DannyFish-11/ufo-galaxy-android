package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.DeviceExecutionEventPayload
import com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-08 (Android) — Canonical Runtime Truth Unification test suite.
 *
 * Regression and acceptance tests for all PR-08 additions:
 *  1. [AndroidCanonicalRuntimeTruthContract] — five-category reported state classification,
 *     eight degraded condition kinds, classification functions, and contract invariants.
 *  2. [DeviceStateSnapshotPayload] — new `reported_state_category` and
 *     `degraded_condition_kinds` fields.
 *  3. [DeviceExecutionEventPayload] — new `reported_state_category` and
 *     `degraded_condition_kinds` fields.
 *  4. [StabilizationBaseline] — `android-canonical-runtime-truth` entry registered.
 *
 * ## Coverage areas
 *
 * ### ReportedStateCategory — wire values and stability
 *  - All 5 categories have distinct wire values
 *  - Each wire value is non-empty and stable
 *  - fromWireValue() parses every wire value correctly
 *  - fromWireValue(null) returns null
 *  - fromWireValue(unknown) returns null
 *  - ALL_WIRE_VALUES contains all 5 values
 *
 * ### ReportedStateCategory — category semantics
 *  - CAPABILITY_STATE wire value is "capability_state"
 *  - OBSERVATION_STATE wire value is "observation_state"
 *  - ACTIVE_EXECUTION_STATE wire value is "active_execution_state"
 *  - DERIVED_LOCAL_STATE wire value is "derived_local_state"
 *  - TERMINAL_REPORTING_STATE wire value is "terminal_reporting_state"
 *
 * ### DegradedConditionKind — wire values and stability
 *  - All 8 kinds have distinct wire values
 *  - Each wire value is non-empty and stable
 *  - fromWireValue() parses every wire value correctly
 *  - fromWireValue(null) returns null
 *  - fromWireValue(unknown) returns null
 *  - ALL_WIRE_VALUES contains all 8 values
 *
 * ### DegradedConditionKind — kind semantics
 *  - DEGRADED wire value is "degraded"
 *  - FALLBACK wire value is "fallback"
 *  - CONSTRAINED wire value is "constrained"
 *  - PARTIAL wire value is "partial"
 *  - DELAYED wire value is "delayed"
 *  - RECOVERING wire value is "recovering"
 *  - RECOVERED wire value is "recovered"
 *  - UNKNOWN wire value is "unknown"
 *
 * ### classifyStateCategory — snapshot classification
 *  - executionBusy=true → ACTIVE_EXECUTION_STATE (highest priority)
 *  - executionBusy=true overrides recovery state
 *  - reconnect_recovery_state="recovering" → OBSERVATION_STATE
 *  - reconnect_recovery_state="recovered" → OBSERVATION_STATE
 *  - currentFallbackTier non-null → DERIVED_LOCAL_STATE
 *  - plannerFallbackTier non-null → DERIVED_LOCAL_STATE
 *  - groundingFallbackTier non-null → DERIVED_LOCAL_STATE
 *  - executionModeState="cross_device_active" → DERIVED_LOCAL_STATE
 *  - meshConstrainedReasons non-empty → DERIVED_LOCAL_STATE
 *  - all null/empty/idle → CAPABILITY_STATE
 *  - executionModeState="inactive" → CAPABILITY_STATE (not DERIVED)
 *  - executionModeState="local_only" → CAPABILITY_STATE (not DERIVED)
 *
 * ### classifyEventCategory — execution event classification
 *  - "execution_started" → ACTIVE_EXECUTION_STATE
 *  - "execution_progress" → ACTIVE_EXECUTION_STATE
 *  - "takeover_milestone" → ACTIVE_EXECUTION_STATE
 *  - "completed" → TERMINAL_REPORTING_STATE
 *  - "failed" → TERMINAL_REPORTING_STATE
 *  - "cancelled" → TERMINAL_REPORTING_STATE
 *  - "stagnation_detected" → TERMINAL_REPORTING_STATE
 *  - "fallback_transition" → DERIVED_LOCAL_STATE
 *  - unknown phase → OBSERVATION_STATE
 *
 * ### classifyDegradedConditions — degraded condition derivation
 *  - healthy state → empty list
 *  - localIntelligenceStatus="degraded" → includes DEGRADED
 *  - degradedReasons non-empty → includes DEGRADED
 *  - currentFallbackTier non-null → includes FALLBACK
 *  - plannerFallbackTier non-null → includes FALLBACK
 *  - groundingFallbackTier non-null → includes FALLBACK
 *  - meshConstrainedReasons non-empty → includes CONSTRAINED
 *  - executionBusy=true + degraded runtime → includes PARTIAL
 *  - offlineQueueDepth ≥ 3 → includes DELAYED
 *  - offlineQueueDepth < 3 → does not include DELAYED
 *  - reconnectRecoveryState="recovering" → includes RECOVERING
 *  - reconnectRecoveryState="recovered" → includes RECOVERED
 *  - multiple simultaneous conditions → all present
 *
 * ### CANONICAL_TRUTH_INVARIANTS — all invariants hold
 *  - state_category_wire_values_distinct is true
 *  - degraded_condition_wire_values_distinct is true
 *  - state_category_count_is_five is true
 *  - degraded_condition_count_is_eight is true
 *  - reported_state_category_key_matches_field_name is true
 *  - degraded_condition_kinds_key_matches_field_name is true
 *  - capability_and_active_are_distinct is true
 *  - terminal_and_active_are_distinct is true
 *  - execution_busy_maps_to_active_execution_state is true
 *  - recovering_maps_to_observation_state is true
 *  - fallback_tier_maps_to_derived_local_state is true
 *  - idle_healthy_maps_to_capability_state is true
 *  - event_completed_maps_to_terminal_state is true
 *  - event_execution_started_maps_to_active_state is true
 *  - event_fallback_transition_maps_to_derived_state is true
 *  - healthy_state_produces_empty_degraded_conditions is true
 *  - recovering_state_produces_recovering_condition is true
 *
 * ### DeviceStateSnapshotPayload — new fields present
 *  - reported_state_category field exists and defaults to null
 *  - degraded_condition_kinds field exists and defaults to empty list
 *  - reported_state_category can be set to any ReportedStateCategory wire value
 *  - degraded_condition_kinds can carry multiple condition kind wire values
 *
 * ### DeviceExecutionEventPayload — new fields present
 *  - reported_state_category field exists and defaults to null
 *  - degraded_condition_kinds field exists and defaults to empty list
 *  - reported_state_category can be set to any ReportedStateCategory wire value
 *  - degraded_condition_kinds can carry multiple condition kind wire values
 *
 * ### Wire key constants — field names match constant values
 *  - KEY_REPORTED_STATE_CATEGORY == "reported_state_category"
 *  - KEY_DEGRADED_CONDITION_KINDS == "degraded_condition_kinds"
 *
 * ### StabilizationBaseline — new surface registered
 *  - "android-canonical-runtime-truth" entry exists in the baseline
 *  - it has CANONICAL_STABLE stability
 *  - it has EXTEND guidance
 */
class Pr08AndroidCanonicalRuntimeTruthTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val C = AndroidCanonicalRuntimeTruthContract

    private fun minimalSnapshot(
        reportedStateCategory: String? = null,
        degradedConditionKinds: List<String> = emptyList()
    ) = DeviceStateSnapshotPayload(
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
        reported_state_category = reportedStateCategory,
        degraded_condition_kinds = degradedConditionKinds
    )

    private fun minimalEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        reportedStateCategory: String? = null,
        degradedConditionKinds: List<String> = emptyList()
    ) = DeviceExecutionEventPayload(
        flow_id = "flow-1",
        task_id = "task-1",
        phase = phase,
        reported_state_category = reportedStateCategory,
        degraded_condition_kinds = degradedConditionKinds
    )

    // ── ReportedStateCategory — wire values ───────────────────────────────────

    @Test
    fun `all ReportedStateCategory entries have distinct wire values`() {
        val wireValues = C.ReportedStateCategory.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `all ReportedStateCategory wire values are non-empty`() {
        for (cat in C.ReportedStateCategory.entries) {
            assertTrue("wire value for $cat is empty", cat.wireValue.isNotEmpty())
        }
    }

    @Test
    fun `all ReportedStateCategory entries have non-empty descriptions`() {
        for (cat in C.ReportedStateCategory.entries) {
            assertTrue("description for $cat is empty", cat.description.isNotEmpty())
        }
    }

    @Test
    fun `ReportedStateCategory fromWireValue parses every wire value correctly`() {
        for (cat in C.ReportedStateCategory.entries) {
            assertEquals(cat, C.ReportedStateCategory.fromWireValue(cat.wireValue))
        }
    }

    @Test
    fun `ReportedStateCategory fromWireValue returns null for null`() {
        assertNull(C.ReportedStateCategory.fromWireValue(null))
    }

    @Test
    fun `ReportedStateCategory fromWireValue returns null for unknown value`() {
        assertNull(C.ReportedStateCategory.fromWireValue("not_a_category"))
    }

    @Test
    fun `ReportedStateCategory ALL_WIRE_VALUES contains all entries`() {
        val expected = C.ReportedStateCategory.entries.map { it.wireValue }.toSet()
        assertEquals(expected, C.ReportedStateCategory.ALL_WIRE_VALUES)
    }

    @Test
    fun `CAPABILITY_STATE wire value is capability_state`() {
        assertEquals("capability_state", C.ReportedStateCategory.CAPABILITY_STATE.wireValue)
    }

    @Test
    fun `OBSERVATION_STATE wire value is observation_state`() {
        assertEquals("observation_state", C.ReportedStateCategory.OBSERVATION_STATE.wireValue)
    }

    @Test
    fun `ACTIVE_EXECUTION_STATE wire value is active_execution_state`() {
        assertEquals("active_execution_state", C.ReportedStateCategory.ACTIVE_EXECUTION_STATE.wireValue)
    }

    @Test
    fun `DERIVED_LOCAL_STATE wire value is derived_local_state`() {
        assertEquals("derived_local_state", C.ReportedStateCategory.DERIVED_LOCAL_STATE.wireValue)
    }

    @Test
    fun `TERMINAL_REPORTING_STATE wire value is terminal_reporting_state`() {
        assertEquals("terminal_reporting_state", C.ReportedStateCategory.TERMINAL_REPORTING_STATE.wireValue)
    }

    // ── DegradedConditionKind — wire values ───────────────────────────────────

    @Test
    fun `all DegradedConditionKind entries have distinct wire values`() {
        val wireValues = C.DegradedConditionKind.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `all DegradedConditionKind wire values are non-empty`() {
        for (kind in C.DegradedConditionKind.entries) {
            assertTrue("wire value for $kind is empty", kind.wireValue.isNotEmpty())
        }
    }

    @Test
    fun `all DegradedConditionKind entries have non-empty descriptions`() {
        for (kind in C.DegradedConditionKind.entries) {
            assertTrue("description for $kind is empty", kind.description.isNotEmpty())
        }
    }

    @Test
    fun `DegradedConditionKind fromWireValue parses every wire value correctly`() {
        for (kind in C.DegradedConditionKind.entries) {
            assertEquals(kind, C.DegradedConditionKind.fromWireValue(kind.wireValue))
        }
    }

    @Test
    fun `DegradedConditionKind fromWireValue returns null for null`() {
        assertNull(C.DegradedConditionKind.fromWireValue(null))
    }

    @Test
    fun `DegradedConditionKind fromWireValue returns null for unknown value`() {
        assertNull(C.DegradedConditionKind.fromWireValue("not_a_kind"))
    }

    @Test
    fun `DegradedConditionKind ALL_WIRE_VALUES contains all entries`() {
        val expected = C.DegradedConditionKind.entries.map { it.wireValue }.toSet()
        assertEquals(expected, C.DegradedConditionKind.ALL_WIRE_VALUES)
    }

    @Test
    fun `DEGRADED wire value is degraded`() {
        assertEquals("degraded", C.DegradedConditionKind.DEGRADED.wireValue)
    }

    @Test
    fun `FALLBACK wire value is fallback`() {
        assertEquals("fallback", C.DegradedConditionKind.FALLBACK.wireValue)
    }

    @Test
    fun `CONSTRAINED wire value is constrained`() {
        assertEquals("constrained", C.DegradedConditionKind.CONSTRAINED.wireValue)
    }

    @Test
    fun `PARTIAL wire value is partial`() {
        assertEquals("partial", C.DegradedConditionKind.PARTIAL.wireValue)
    }

    @Test
    fun `DELAYED wire value is delayed`() {
        assertEquals("delayed", C.DegradedConditionKind.DELAYED.wireValue)
    }

    @Test
    fun `RECOVERING wire value is recovering`() {
        assertEquals("recovering", C.DegradedConditionKind.RECOVERING.wireValue)
    }

    @Test
    fun `RECOVERED wire value is recovered`() {
        assertEquals("recovered", C.DegradedConditionKind.RECOVERED.wireValue)
    }

    @Test
    fun `UNKNOWN wire value is unknown`() {
        assertEquals("unknown", C.DegradedConditionKind.UNKNOWN.wireValue)
    }

    @Test
    fun `DegradedConditionKind has exactly eight entries`() {
        assertEquals(8, C.DegradedConditionKind.entries.size)
    }

    @Test
    fun `ReportedStateCategory has exactly five entries`() {
        assertEquals(5, C.ReportedStateCategory.entries.size)
    }

    // ── classifyStateCategory ─────────────────────────────────────────────────

    @Test
    fun `executionBusy true maps to ACTIVE_EXECUTION_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = true,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.ACTIVE_EXECUTION_STATE, result)
    }

    @Test
    fun `executionBusy true overrides recovering state`() {
        val result = C.classifyStateCategory(
            executionBusy = true,
            reconnectRecoveryState = "recovering",
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.ACTIVE_EXECUTION_STATE, result)
    }

    @Test
    fun `reconnectRecoveryState recovering maps to OBSERVATION_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = "recovering",
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.OBSERVATION_STATE, result)
    }

    @Test
    fun `reconnectRecoveryState recovered maps to OBSERVATION_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = "recovered",
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.OBSERVATION_STATE, result)
    }

    @Test
    fun `currentFallbackTier non-null maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = "idle",
            currentFallbackTier = "tier_2",
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `plannerFallbackTier non-null maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = "planner_tier_1",
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `groundingFallbackTier non-null maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = "grounding_tier_1",
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `executionModeState cross_device_active maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = "cross_device_active",
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `executionModeState cross_device_degraded maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = "cross_device_degraded",
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `meshConstrainedReasons non-empty maps to DERIVED_LOCAL_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = listOf("battery_low")
        )
        assertEquals(C.ReportedStateCategory.DERIVED_LOCAL_STATE, result)
    }

    @Test
    fun `idle healthy state maps to CAPABILITY_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.CAPABILITY_STATE, result)
    }

    @Test
    fun `executionModeState inactive maps to CAPABILITY_STATE not DERIVED`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = "inactive",
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.CAPABILITY_STATE, result)
    }

    @Test
    fun `executionModeState local_only maps to CAPABILITY_STATE not DERIVED`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = null,
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = "local_only",
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.CAPABILITY_STATE, result)
    }

    @Test
    fun `reconnectRecoveryState idle maps to CAPABILITY_STATE`() {
        val result = C.classifyStateCategory(
            executionBusy = false,
            reconnectRecoveryState = "idle",
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            executionModeState = null,
            meshConstrainedReasons = emptyList()
        )
        assertEquals(C.ReportedStateCategory.CAPABILITY_STATE, result)
    }

    // ── classifyEventCategory ─────────────────────────────────────────────────

    @Test
    fun `execution_started event maps to ACTIVE_EXECUTION_STATE`() {
        assertEquals(
            C.ReportedStateCategory.ACTIVE_EXECUTION_STATE,
            C.classifyEventCategory("execution_started")
        )
    }

    @Test
    fun `execution_progress event maps to ACTIVE_EXECUTION_STATE`() {
        assertEquals(
            C.ReportedStateCategory.ACTIVE_EXECUTION_STATE,
            C.classifyEventCategory("execution_progress")
        )
    }

    @Test
    fun `takeover_milestone event maps to ACTIVE_EXECUTION_STATE`() {
        assertEquals(
            C.ReportedStateCategory.ACTIVE_EXECUTION_STATE,
            C.classifyEventCategory("takeover_milestone")
        )
    }

    @Test
    fun `completed event maps to TERMINAL_REPORTING_STATE`() {
        assertEquals(
            C.ReportedStateCategory.TERMINAL_REPORTING_STATE,
            C.classifyEventCategory("completed")
        )
    }

    @Test
    fun `failed event maps to TERMINAL_REPORTING_STATE`() {
        assertEquals(
            C.ReportedStateCategory.TERMINAL_REPORTING_STATE,
            C.classifyEventCategory("failed")
        )
    }

    @Test
    fun `cancelled event maps to TERMINAL_REPORTING_STATE`() {
        assertEquals(
            C.ReportedStateCategory.TERMINAL_REPORTING_STATE,
            C.classifyEventCategory("cancelled")
        )
    }

    @Test
    fun `stagnation_detected event maps to TERMINAL_REPORTING_STATE`() {
        assertEquals(
            C.ReportedStateCategory.TERMINAL_REPORTING_STATE,
            C.classifyEventCategory("stagnation_detected")
        )
    }

    @Test
    fun `fallback_transition event maps to DERIVED_LOCAL_STATE`() {
        assertEquals(
            C.ReportedStateCategory.DERIVED_LOCAL_STATE,
            C.classifyEventCategory("fallback_transition")
        )
    }

    @Test
    fun `unknown phase maps to OBSERVATION_STATE`() {
        assertEquals(
            C.ReportedStateCategory.OBSERVATION_STATE,
            C.classifyEventCategory("some_unknown_phase")
        )
    }

    @Test
    fun `empty phase maps to OBSERVATION_STATE`() {
        assertEquals(
            C.ReportedStateCategory.OBSERVATION_STATE,
            C.classifyEventCategory("")
        )
    }

    // ── classifyDegradedConditions ────────────────────────────────────────────

    @Test
    fun `healthy state produces empty degraded conditions list`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = "idle"
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `localIntelligenceStatus degraded produces DEGRADED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "degraded",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.DEGRADED.wireValue))
    }

    @Test
    fun `non-empty degradedReasons produces DEGRADED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = listOf("PLANNER_UNAVAILABLE"),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.DEGRADED.wireValue))
    }

    @Test
    fun `currentFallbackTier non-null produces FALLBACK condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = "tier_2",
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.FALLBACK.wireValue))
    }

    @Test
    fun `plannerFallbackTier non-null produces FALLBACK condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = "planner_fallback",
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.FALLBACK.wireValue))
    }

    @Test
    fun `groundingFallbackTier non-null produces FALLBACK condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = "grounding_fallback",
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.FALLBACK.wireValue))
    }

    @Test
    fun `meshConstrainedReasons non-empty produces CONSTRAINED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = listOf("battery_low", "network_limited"),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.CONSTRAINED.wireValue))
    }

    @Test
    fun `executionBusy true plus degraded runtime produces PARTIAL condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "degraded",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = true,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.PARTIAL.wireValue))
    }

    @Test
    fun `executionBusy true plus fallback produces PARTIAL condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = "fallback_tier",
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = true,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.PARTIAL.wireValue))
    }

    @Test
    fun `executionBusy false never produces PARTIAL condition even with degraded runtime`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "degraded",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = null
        )
        assertFalse(result.contains(C.DegradedConditionKind.PARTIAL.wireValue))
    }

    @Test
    fun `offlineQueueDepth 3 produces DELAYED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 3,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.DELAYED.wireValue))
    }

    @Test
    fun `offlineQueueDepth 5 produces DELAYED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 5,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.DELAYED.wireValue))
    }

    @Test
    fun `offlineQueueDepth 2 does not produce DELAYED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 2,
            reconnectRecoveryState = null
        )
        assertFalse(result.contains(C.DegradedConditionKind.DELAYED.wireValue))
    }

    @Test
    fun `reconnectRecoveryState recovering produces RECOVERING condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = "recovering"
        )
        assertTrue(result.contains(C.DegradedConditionKind.RECOVERING.wireValue))
    }

    @Test
    fun `reconnectRecoveryState recovered produces RECOVERED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = "recovered"
        )
        assertTrue(result.contains(C.DegradedConditionKind.RECOVERED.wireValue))
    }

    @Test
    fun `recovering does not also produce RECOVERED condition`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "active",
            degradedReasons = emptyList(),
            currentFallbackTier = null,
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = emptyList(),
            executionBusy = false,
            offlineQueueDepth = 0,
            reconnectRecoveryState = "recovering"
        )
        assertFalse(result.contains(C.DegradedConditionKind.RECOVERED.wireValue))
    }

    @Test
    fun `multiple simultaneous conditions are all present`() {
        val result = C.classifyDegradedConditions(
            localIntelligenceStatus = "degraded",
            degradedReasons = listOf("PLANNER_SLOW"),
            currentFallbackTier = "tier_1",
            plannerFallbackTier = null,
            groundingFallbackTier = null,
            meshConstrainedReasons = listOf("battery_low"),
            executionBusy = true,
            offlineQueueDepth = 4,
            reconnectRecoveryState = null
        )
        assertTrue(result.contains(C.DegradedConditionKind.DEGRADED.wireValue))
        assertTrue(result.contains(C.DegradedConditionKind.FALLBACK.wireValue))
        assertTrue(result.contains(C.DegradedConditionKind.CONSTRAINED.wireValue))
        assertTrue(result.contains(C.DegradedConditionKind.PARTIAL.wireValue))
        assertTrue(result.contains(C.DegradedConditionKind.DELAYED.wireValue))
    }

    // ── CANONICAL_TRUTH_INVARIANTS ────────────────────────────────────────────

    @Test
    fun `all CANONICAL_TRUTH_INVARIANTS are true`() {
        val violations = C.CANONICAL_TRUTH_INVARIANTS.filter { !it.value }
        assertTrue(
            "Violated invariants: ${violations.keys.joinToString()}",
            violations.isEmpty()
        )
    }

    @Test
    fun `state_category_wire_values_distinct invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["state_category_wire_values_distinct"] == true)
    }

    @Test
    fun `degraded_condition_wire_values_distinct invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["degraded_condition_wire_values_distinct"] == true)
    }

    @Test
    fun `state_category_count_is_five invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["state_category_count_is_five"] == true)
    }

    @Test
    fun `degraded_condition_count_is_eight invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["degraded_condition_count_is_eight"] == true)
    }

    @Test
    fun `reported_state_category_key_matches_field_name invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["reported_state_category_key_matches_field_name"] == true)
        assertEquals("reported_state_category", C.KEY_REPORTED_STATE_CATEGORY)
    }

    @Test
    fun `degraded_condition_kinds_key_matches_field_name invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["degraded_condition_kinds_key_matches_field_name"] == true)
        assertEquals("degraded_condition_kinds", C.KEY_DEGRADED_CONDITION_KINDS)
    }

    @Test
    fun `capability_and_active_are_distinct invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["capability_and_active_are_distinct"] == true)
    }

    @Test
    fun `terminal_and_active_are_distinct invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["terminal_and_active_are_distinct"] == true)
    }

    @Test
    fun `execution_busy_maps_to_active_execution_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["execution_busy_maps_to_active_execution_state"] == true)
    }

    @Test
    fun `recovering_maps_to_observation_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["recovering_maps_to_observation_state"] == true)
    }

    @Test
    fun `fallback_tier_maps_to_derived_local_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["fallback_tier_maps_to_derived_local_state"] == true)
    }

    @Test
    fun `idle_healthy_maps_to_capability_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["idle_healthy_maps_to_capability_state"] == true)
    }

    @Test
    fun `event_completed_maps_to_terminal_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["event_completed_maps_to_terminal_state"] == true)
    }

    @Test
    fun `event_execution_started_maps_to_active_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["event_execution_started_maps_to_active_state"] == true)
    }

    @Test
    fun `event_fallback_transition_maps_to_derived_state invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["event_fallback_transition_maps_to_derived_state"] == true)
    }

    @Test
    fun `healthy_state_produces_empty_degraded_conditions invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["healthy_state_produces_empty_degraded_conditions"] == true)
    }

    @Test
    fun `recovering_state_produces_recovering_condition invariant holds`() {
        assertTrue(C.CANONICAL_TRUTH_INVARIANTS["recovering_state_produces_recovering_condition"] == true)
    }

    // ── DeviceStateSnapshotPayload — new fields present ───────────────────────

    @Test
    fun `snapshot reported_state_category field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.reported_state_category)
    }

    @Test
    fun `snapshot degraded_condition_kinds field exists and defaults to empty list`() {
        val snapshot = minimalSnapshot()
        assertTrue(snapshot.degraded_condition_kinds.isEmpty())
    }

    @Test
    fun `snapshot reported_state_category can hold any ReportedStateCategory wire value`() {
        for (cat in C.ReportedStateCategory.entries) {
            val snapshot = minimalSnapshot(reportedStateCategory = cat.wireValue)
            assertEquals(cat.wireValue, snapshot.reported_state_category)
        }
    }

    @Test
    fun `snapshot degraded_condition_kinds can carry multiple condition wire values`() {
        val kinds = listOf(
            C.DegradedConditionKind.DEGRADED.wireValue,
            C.DegradedConditionKind.FALLBACK.wireValue,
            C.DegradedConditionKind.CONSTRAINED.wireValue
        )
        val snapshot = minimalSnapshot(degradedConditionKinds = kinds)
        assertEquals(kinds, snapshot.degraded_condition_kinds)
    }

    @Test
    fun `snapshot degraded_condition_kinds can hold all DegradedConditionKind wire values`() {
        val allKinds = C.DegradedConditionKind.entries.map { it.wireValue }
        val snapshot = minimalSnapshot(degradedConditionKinds = allKinds)
        assertEquals(allKinds, snapshot.degraded_condition_kinds)
    }

    // ── DeviceExecutionEventPayload — new fields present ──────────────────────

    @Test
    fun `event reported_state_category field exists and defaults to null`() {
        val event = minimalEvent()
        assertNull(event.reported_state_category)
    }

    @Test
    fun `event degraded_condition_kinds field exists and defaults to empty list`() {
        val event = minimalEvent()
        assertTrue(event.degraded_condition_kinds.isEmpty())
    }

    @Test
    fun `event reported_state_category can hold any ReportedStateCategory wire value`() {
        for (cat in C.ReportedStateCategory.entries) {
            val event = minimalEvent(reportedStateCategory = cat.wireValue)
            assertEquals(cat.wireValue, event.reported_state_category)
        }
    }

    @Test
    fun `event degraded_condition_kinds can carry multiple condition wire values`() {
        val kinds = listOf(
            C.DegradedConditionKind.RECOVERING.wireValue,
            C.DegradedConditionKind.DELAYED.wireValue
        )
        val event = minimalEvent(degradedConditionKinds = kinds)
        assertEquals(kinds, event.degraded_condition_kinds)
    }

    // ── Wire key constants ────────────────────────────────────────────────────

    @Test
    fun `KEY_REPORTED_STATE_CATEGORY constant matches field name`() {
        assertEquals("reported_state_category", C.KEY_REPORTED_STATE_CATEGORY)
    }

    @Test
    fun `KEY_DEGRADED_CONDITION_KINDS constant matches field name`() {
        assertEquals("degraded_condition_kinds", C.KEY_DEGRADED_CONDITION_KINDS)
    }

    // ── State/reporting transition semantics ──────────────────────────────────

    @Test
    fun `CAPABILITY_STATE and ACTIVE_EXECUTION_STATE are mutually exclusive`() {
        assertFalse(
            C.ReportedStateCategory.CAPABILITY_STATE == C.ReportedStateCategory.ACTIVE_EXECUTION_STATE
        )
    }

    @Test
    fun `TERMINAL_REPORTING_STATE and ACTIVE_EXECUTION_STATE are mutually exclusive`() {
        assertFalse(
            C.ReportedStateCategory.TERMINAL_REPORTING_STATE == C.ReportedStateCategory.ACTIVE_EXECUTION_STATE
        )
    }

    @Test
    fun `CAPABILITY_STATE and TERMINAL_REPORTING_STATE are mutually exclusive`() {
        assertFalse(
            C.ReportedStateCategory.CAPABILITY_STATE == C.ReportedStateCategory.TERMINAL_REPORTING_STATE
        )
    }

    @Test
    fun `OBSERVATION_STATE and ACTIVE_EXECUTION_STATE are mutually exclusive`() {
        assertFalse(
            C.ReportedStateCategory.OBSERVATION_STATE == C.ReportedStateCategory.ACTIVE_EXECUTION_STATE
        )
    }

    @Test
    fun `all terminal event phases map to TERMINAL_REPORTING_STATE`() {
        val terminalPhases = listOf("completed", "failed", "cancelled", "stagnation_detected")
        for (phase in terminalPhases) {
            val result = C.classifyEventCategory(phase)
            assertEquals(
                "Phase '$phase' should map to TERMINAL_REPORTING_STATE",
                C.ReportedStateCategory.TERMINAL_REPORTING_STATE,
                result
            )
        }
    }

    @Test
    fun `all active event phases map to ACTIVE_EXECUTION_STATE`() {
        val activePhases = listOf("execution_started", "execution_progress", "takeover_milestone")
        for (phase in activePhases) {
            val result = C.classifyEventCategory(phase)
            assertEquals(
                "Phase '$phase' should map to ACTIVE_EXECUTION_STATE",
                C.ReportedStateCategory.ACTIVE_EXECUTION_STATE,
                result
            )
        }
    }

    @Test
    fun `classifyEventCategory is stable across all DeviceExecutionEventPayload phase constants`() {
        val phaseConstants = listOf(
            DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
            DeviceExecutionEventPayload.PHASE_EXECUTION_PROGRESS,
            DeviceExecutionEventPayload.PHASE_COMPLETED,
            DeviceExecutionEventPayload.PHASE_FAILED,
            DeviceExecutionEventPayload.PHASE_STAGNATION_DETECTED,
            DeviceExecutionEventPayload.PHASE_CANCELLED,
            DeviceExecutionEventPayload.PHASE_FALLBACK_TRANSITION,
            DeviceExecutionEventPayload.PHASE_TAKEOVER_MILESTONE
        )
        // All phase constants must produce a non-null ReportedStateCategory
        for (phase in phaseConstants) {
            val result = C.classifyEventCategory(phase)
            assertNotNull("classifyEventCategory($phase) returned unexpected result", result)
        }
    }

    // ── StabilizationBaseline — new surface registered ────────────────────────

    @Test
    fun `android-canonical-runtime-truth entry exists in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-canonical-runtime-truth")
        assertNotNull("android-canonical-runtime-truth not found in StabilizationBaseline", entry)
    }

    @Test
    fun `android-canonical-runtime-truth has CANONICAL_STABLE stability`() {
        val entry = StabilizationBaseline.forId("android-canonical-runtime-truth")
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry?.stability)
    }

    @Test
    fun `android-canonical-runtime-truth has EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-canonical-runtime-truth")
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry?.extensionGuidance)
    }

    @Test
    fun `android-canonical-runtime-truth packagePath references AndroidCanonicalRuntimeTruthContract`() {
        val entry = StabilizationBaseline.forId("android-canonical-runtime-truth")
        assertTrue(
            entry?.packagePath?.contains("AndroidCanonicalRuntimeTruthContract") == true
        )
    }

    // ── Contract metadata ─────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is PR-08`() {
        assertEquals("PR-08", C.INTRODUCED_PR)
    }

    @Test
    fun `TEST_CLASS references this test class name`() {
        assertEquals("Pr08AndroidCanonicalRuntimeTruthTest", C.TEST_CLASS)
    }
}
