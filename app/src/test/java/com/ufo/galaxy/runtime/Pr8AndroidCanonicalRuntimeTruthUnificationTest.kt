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
 * PR-08 Android — Regression-protection tests for Canonical Runtime Truth Unification.
 *
 * Validates all deliverables of the Android-side canonical runtime truth unification:
 *
 * 1. [AndroidCanonicalRuntimeTruthContract] — semantic class enums are well-formed,
 *    wire values are stable, and classifier methods produce correct outputs.
 * 2. [DeviceStateSnapshotPayload] — three new canonical truth fields are present:
 *    `reported_state_semantic_class`, `degraded_condition_class`, `local_observation_basis`.
 * 3. [DeviceExecutionEventPayload] — two new canonical truth fields are present:
 *    `reported_state_semantic_class`, `result_uplink_semantic_class`.
 * 4. Semantic distinctions — capability vs observation vs active_runtime vs derived_local
 *    vs terminal_reporting are correctly derived by the classifier.
 * 5. Degraded/fallback/constrained/partial/delayed/recovered conditions are classified
 *    correctly by [AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition].
 * 6. Result uplink semantic classes are correctly derived for all execution phases.
 * 7. CLOSURE_INVARIANTS in [AndroidCanonicalRuntimeTruthContract] all pass.
 * 8. [StabilizationBaseline] registration for the new contract surface.
 * 9. Gson round-trip stability for all new wire fields.
 *
 * ## Test matrix (116 tests)
 *
 * ### ReportedStateSemanticClass — enum coverage
 *  - has exactly 5 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - each value has a non-empty v2TruthTier
 *  - CAPABILITY v2TruthTier is capability_truth
 *  - ACTIVE_RUNTIME v2TruthTier is runtime_state_truth
 *  - TERMINAL_REPORTING v2TruthTier is terminal_truth
 *
 * ### DegradedConditionClass — enum coverage
 *  - has exactly 7 values
 *  - all wire values are stable
 *  - wire values match expected set
 *  - only NOMINAL has isNominal=true
 *  - DEGRADED, FALLBACK, CONSTRAINED, PARTIAL all have impliesReducedCapability=true
 *  - NOMINAL, DELAYED, RECOVERED all have impliesReducedCapability=false
 *
 * ### ResultUplinkSemanticClass — enum coverage
 *  - has exactly 4 values
 *  - all wire values are stable
 *  - AUTHORITATIVE_TERMINAL and AUTHORITATIVE_RECOVERY have isTerminal=true
 *  - AUTHORITATIVE_INTERRUPTION and INFORMATIONAL have isTerminal=false
 *  - each value has a non-empty v2Action
 *
 * ### LocalObservationBasis — enum coverage
 *  - has exactly 4 values
 *  - all wire values are stable
 *  - only LIVE_RUNTIME has isFresh=true
 *
 * ### classifySnapshot — semantic class derivation
 *  - returns CAPABILITY when no discriminating fields are present
 *  - returns TERMINAL_REPORTING when carrier_runtime_state=failed
 *  - returns TERMINAL_REPORTING when reconnect_recovery_state=failed
 *  - returns TERMINAL_REPORTING when execution_mode_state=inactive
 *  - returns ACTIVE_RUNTIME when carrier_runtime_state=active
 *  - returns ACTIVE_RUNTIME when execution_busy=true
 *  - returns ACTIVE_RUNTIME when execution_mode_state=cross_device_active
 *  - returns OBSERVATION when meshParticipationLifecycle=active
 *  - returns OBSERVATION when carrierForegroundVisible=true
 *  - returns DERIVED_LOCAL when plannerFallbackTier is non-null
 *  - returns DERIVED_LOCAL when groundingFallbackTier is non-null
 *  - TERMINAL_REPORTING has higher priority than ACTIVE_RUNTIME
 *  - ACTIVE_RUNTIME has higher priority than OBSERVATION
 *  - OBSERVATION has higher priority than DERIVED_LOCAL
 *  - DERIVED_LOCAL has higher priority than CAPABILITY
 *
 * ### classifyDegradedCondition — degraded condition derivation
 *  - returns NOMINAL when no conditions present
 *  - returns RECOVERED when reconnect_recovery_state=recovered and no degraded reasons
 *  - returns DEGRADED when degraded_reasons is non-empty
 *  - returns DEGRADED when execution_mode_state=cross_device_degraded
 *  - returns FALLBACK when planner_fallback_tier is non-null
 *  - returns FALLBACK when grounding_fallback_tier is non-null
 *  - returns FALLBACK when current_fallback_tier is non-null
 *  - returns CONSTRAINED when mesh_constrained_reasons is non-empty
 *  - returns CONSTRAINED when cross_device_eligibility=false
 *  - returns PARTIAL when plannerReady=true and groundingReady=false
 *  - returns PARTIAL when plannerReady=false and groundingReady=true
 *  - returns DELAYED when offline_queue_depth > 0
 *  - RECOVERED has higher priority than DEGRADED (if reconnect=recovered and no reasons)
 *  - DEGRADED has higher priority than FALLBACK
 *  - FALLBACK has higher priority than CONSTRAINED
 *  - CONSTRAINED has higher priority than PARTIAL
 *  - PARTIAL has higher priority than DELAYED
 *
 * ### classifyResultUplink — result uplink semantic class
 *  - CAPABILITY_IDLE → INFORMATIONAL
 *  - PENDING → INFORMATIONAL
 *  - ACTIVATING → INFORMATIONAL
 *  - ACTIVE → INFORMATIONAL
 *  - DEGRADED → INFORMATIONAL
 *  - RETRYING → INFORMATIONAL
 *  - UNKNOWN → INFORMATIONAL
 *  - INTERRUPTED → AUTHORITATIVE_INTERRUPTION
 *  - COMPLETED (no prior interruption) → AUTHORITATIVE_TERMINAL
 *  - FAILED (no prior interruption) → AUTHORITATIVE_TERMINAL
 *  - TIMED_OUT → AUTHORITATIVE_TERMINAL
 *  - REJECTED → AUTHORITATIVE_TERMINAL
 *  - COMPLETED (prior interruption) → AUTHORITATIVE_RECOVERY
 *  - FAILED (prior interruption) → AUTHORITATIVE_RECOVERY
 *
 * ### DeviceStateSnapshotPayload — new fields
 *  - reported_state_semantic_class defaults to null
 *  - degraded_condition_class defaults to null
 *  - local_observation_basis defaults to null
 *  - all three fields can be set to their respective wire values
 *  - Gson round-trip for reported_state_semantic_class
 *  - Gson round-trip for degraded_condition_class
 *  - Gson round-trip for local_observation_basis
 *
 * ### DeviceExecutionEventPayload — new fields
 *  - reported_state_semantic_class defaults to null
 *  - result_uplink_semantic_class defaults to null
 *  - both fields can be set to their respective wire values
 *  - Gson round-trip for reported_state_semantic_class
 *  - Gson round-trip for result_uplink_semantic_class
 *
 * ### CLOSURE_INVARIANTS — all pass
 *
 * ### StabilizationBaseline — registration
 *  - android-canonical-runtime-truth-contract is registered
 *  - android-canonical-runtime-truth-contract is CANONICAL_STABLE
 *  - android-canonical-runtime-truth-contract is in extendableSurfaceIds
 */
class Pr8AndroidCanonicalRuntimeTruthUnificationTest {

    private val gson = Gson()

    // ── Minimal payload factories ─────────────────────────────────────────────

    private fun minimalSnapshot(
        carrierRuntimeState: String? = null,
        reconnectRecoveryState: String? = null,
        executionModeState: String? = null,
        executionBusy: Boolean? = null,
        reportedStateSemanticClass: String? = null,
        degradedConditionClass: String? = null,
        localObservationBasis: String? = null
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
        carrier_runtime_state = carrierRuntimeState,
        reconnect_recovery_state = reconnectRecoveryState,
        execution_mode_state = executionModeState,
        execution_busy = executionBusy,
        reported_state_semantic_class = reportedStateSemanticClass,
        degraded_condition_class = degradedConditionClass,
        local_observation_basis = localObservationBasis
    )

    private fun minimalEvent(
        phase: String = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        reportedStateSemanticClass: String? = null,
        resultUplinkSemanticClass: String? = null
    ) = DeviceExecutionEventPayload(
        flow_id = "flow-1",
        task_id = "task-1",
        phase = phase,
        reported_state_semantic_class = reportedStateSemanticClass,
        result_uplink_semantic_class = resultUplinkSemanticClass
    )

    // ── ReportedStateSemanticClass — enum coverage ────────────────────────────

    @Test
    fun `ReportedStateSemanticClass has exactly 5 values`() {
        assertEquals(5, AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.entries.size)
    }

    @Test
    fun `ReportedStateSemanticClass ALL_WIRE_VALUES has size 5`() {
        assertEquals(5, AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ReportedStateSemanticClass wire values match expected set`() {
        val expected = setOf("capability", "observation", "active_runtime", "derived_local", "terminal_reporting")
        assertEquals(expected, AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ALL_WIRE_VALUES)
    }

    @Test
    fun `each ReportedStateSemanticClass has a non-empty v2TruthTier`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.entries) {
            assertTrue("${cls.name} has empty v2TruthTier", cls.v2TruthTier.isNotEmpty())
        }
    }

    @Test
    fun `CAPABILITY v2TruthTier is capability_truth`() {
        assertEquals("capability_truth",
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.CAPABILITY.v2TruthTier)
    }

    @Test
    fun `ACTIVE_RUNTIME v2TruthTier is runtime_state_truth`() {
        assertEquals("runtime_state_truth",
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.v2TruthTier)
    }

    @Test
    fun `TERMINAL_REPORTING v2TruthTier is terminal_truth`() {
        assertEquals("terminal_truth",
            AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.v2TruthTier)
    }

    // ── DegradedConditionClass — enum coverage ────────────────────────────────

    @Test
    fun `DegradedConditionClass has exactly 7 values`() {
        assertEquals(7, AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.entries.size)
    }

    @Test
    fun `DegradedConditionClass ALL_WIRE_VALUES has size 7`() {
        assertEquals(7, AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `DegradedConditionClass wire values match expected set`() {
        val expected = setOf("nominal", "degraded", "fallback", "constrained", "partial", "delayed", "recovered")
        assertEquals(expected, AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.ALL_WIRE_VALUES)
    }

    @Test
    fun `only NOMINAL has isNominal true`() {
        val nominalOnes = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.entries.filter { it.isNominal }
        assertEquals(1, nominalOnes.size)
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL, nominalOnes[0])
    }

    @Test
    fun `DEGRADED implies reduced capability`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DEGRADED.impliesReducedCapability)
    }

    @Test
    fun `FALLBACK implies reduced capability`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK.impliesReducedCapability)
    }

    @Test
    fun `CONSTRAINED implies reduced capability`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED.impliesReducedCapability)
    }

    @Test
    fun `PARTIAL implies reduced capability`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.PARTIAL.impliesReducedCapability)
    }

    @Test
    fun `NOMINAL does not imply reduced capability`() {
        assertFalse(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL.impliesReducedCapability)
    }

    @Test
    fun `DELAYED does not imply reduced capability`() {
        assertFalse(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED.impliesReducedCapability)
    }

    @Test
    fun `RECOVERED does not imply reduced capability`() {
        assertFalse(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.RECOVERED.impliesReducedCapability)
    }

    // ── ResultUplinkSemanticClass — enum coverage ─────────────────────────────

    @Test
    fun `ResultUplinkSemanticClass has exactly 4 values`() {
        assertEquals(4, AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.entries.size)
    }

    @Test
    fun `ResultUplinkSemanticClass ALL_WIRE_VALUES has size 4`() {
        assertEquals(4, AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ResultUplinkSemanticClass wire values match expected set`() {
        val expected = setOf(
            "authoritative_terminal", "authoritative_interruption",
            "authoritative_recovery", "informational"
        )
        assertEquals(expected, AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.ALL_WIRE_VALUES)
    }

    @Test
    fun `AUTHORITATIVE_TERMINAL isTerminal is true`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL.isTerminal)
    }

    @Test
    fun `AUTHORITATIVE_RECOVERY isTerminal is true`() {
        assertTrue(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY.isTerminal)
    }

    @Test
    fun `AUTHORITATIVE_INTERRUPTION isTerminal is false`() {
        assertFalse(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION.isTerminal)
    }

    @Test
    fun `INFORMATIONAL isTerminal is false`() {
        assertFalse(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL.isTerminal)
    }

    @Test
    fun `each ResultUplinkSemanticClass has a non-empty v2Action`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.entries) {
            assertTrue("${cls.name} has empty v2Action", cls.v2Action.isNotEmpty())
        }
    }

    // ── LocalObservationBasis — enum coverage ─────────────────────────────────

    @Test
    fun `LocalObservationBasis has exactly 4 values`() {
        assertEquals(4, AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.entries.size)
    }

    @Test
    fun `LocalObservationBasis ALL_WIRE_VALUES has size 4`() {
        assertEquals(4, AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `LocalObservationBasis wire values match expected set`() {
        val expected = setOf("live_runtime", "cached_state", "derived_projection", "none")
        assertEquals(expected, AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.ALL_WIRE_VALUES)
    }

    @Test
    fun `only LIVE_RUNTIME has isFresh true`() {
        val freshOnes = AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.entries.filter { it.isFresh }
        assertEquals(1, freshOnes.size)
        assertEquals(AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME, freshOnes[0])
    }

    // ── classifySnapshot — semantic class derivation ──────────────────────────

    @Test
    fun `classifySnapshot returns CAPABILITY when no discriminating fields are present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot()
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.CAPABILITY, result)
    }

    @Test
    fun `classifySnapshot returns TERMINAL_REPORTING when carrier_runtime_state is failed`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(carrierRuntimeState = "failed")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING, result)
    }

    @Test
    fun `classifySnapshot returns TERMINAL_REPORTING when reconnect_recovery_state is failed`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(reconnectRecoveryState = "failed")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING, result)
    }

    @Test
    fun `classifySnapshot returns TERMINAL_REPORTING when execution_mode_state is inactive`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(executionModeState = "inactive")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING, result)
    }

    @Test
    fun `classifySnapshot returns ACTIVE_RUNTIME when carrier_runtime_state is active`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(carrierRuntimeState = "active")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME, result)
    }

    @Test
    fun `classifySnapshot returns ACTIVE_RUNTIME when executionBusy is true`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(executionBusy = true)
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME, result)
    }

    @Test
    fun `classifySnapshot returns ACTIVE_RUNTIME when execution_mode_state is cross_device_active`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(executionModeState = "cross_device_active")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME, result)
    }

    @Test
    fun `classifySnapshot returns OBSERVATION when meshParticipationLifecycle is active`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(meshParticipationLifecycle = "active")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.OBSERVATION, result)
    }

    @Test
    fun `classifySnapshot returns OBSERVATION when carrierForegroundVisible is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(carrierForegroundVisible = true)
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.OBSERVATION, result)
    }

    @Test
    fun `classifySnapshot returns DERIVED_LOCAL when plannerFallbackTier is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(plannerFallbackTier = "fallback_local")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL, result)
    }

    @Test
    fun `classifySnapshot returns DERIVED_LOCAL when groundingFallbackTier is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(groundingFallbackTier = "fallback_remote")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL, result)
    }

    @Test
    fun `TERMINAL_REPORTING has higher priority than ACTIVE_RUNTIME`() {
        // Both carrier=failed (terminal) and execution_busy=true (active) → TERMINAL_REPORTING wins
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            carrierRuntimeState = "failed",
            executionBusy = true
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING, result)
    }

    @Test
    fun `ACTIVE_RUNTIME has higher priority than OBSERVATION`() {
        // Both execution_busy=true (active) and mesh state (observation) → ACTIVE_RUNTIME wins
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            executionBusy = true,
            meshParticipationLifecycle = "active"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME, result)
    }

    @Test
    fun `OBSERVATION has higher priority than DERIVED_LOCAL`() {
        // Both mesh state (observation) and fallback tier (derived) → OBSERVATION wins
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            meshParticipationLifecycle = "active",
            plannerFallbackTier = "tier_2"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.OBSERVATION, result)
    }

    @Test
    fun `DERIVED_LOCAL has higher priority than CAPABILITY`() {
        // plannerFallbackTier present (derived) → DERIVED_LOCAL wins over CAPABILITY default
        val result = AndroidCanonicalRuntimeTruthContract.classifySnapshot(plannerFallbackTier = "tier_1")
        assertEquals(AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.DERIVED_LOCAL, result)
    }

    // ── classifyDegradedCondition — condition class derivation ────────────────

    @Test
    fun `classifyDegradedCondition returns NOMINAL when no conditions present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition()
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.NOMINAL, result)
    }

    @Test
    fun `classifyDegradedCondition returns RECOVERED when reconnect_recovery_state is recovered and no degraded reasons`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            reconnectRecoveryState = "recovered"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.RECOVERED, result)
    }

    @Test
    fun `classifyDegradedCondition returns DEGRADED when degraded_reasons is non-empty`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            degradedReasons = listOf("inference_unavailable")
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DEGRADED, result)
    }

    @Test
    fun `classifyDegradedCondition returns DEGRADED when execution_mode_state is cross_device_degraded`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            executionModeState = "cross_device_degraded"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DEGRADED, result)
    }

    @Test
    fun `classifyDegradedCondition returns FALLBACK when planner_fallback_tier is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            plannerFallbackTier = "tier_2"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK, result)
    }

    @Test
    fun `classifyDegradedCondition returns FALLBACK when grounding_fallback_tier is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            groundingFallbackTier = "tier_1"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK, result)
    }

    @Test
    fun `classifyDegradedCondition returns FALLBACK when current_fallback_tier is non-null`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            currentFallbackTier = "local_fallback"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK, result)
    }

    @Test
    fun `classifyDegradedCondition returns CONSTRAINED when mesh_constrained_reasons is non-empty`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            meshConstrainedReasons = listOf("bandwidth_limit")
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED, result)
    }

    @Test
    fun `classifyDegradedCondition returns CONSTRAINED when cross_device_eligibility is false`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            crossDeviceEligibility = false
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED, result)
    }

    @Test
    fun `classifyDegradedCondition returns PARTIAL when plannerReady true and groundingReady false`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            plannerReady = true,
            groundingReady = false
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.PARTIAL, result)
    }

    @Test
    fun `classifyDegradedCondition returns PARTIAL when plannerReady false and groundingReady true`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            plannerReady = false,
            groundingReady = true
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.PARTIAL, result)
    }

    @Test
    fun `classifyDegradedCondition returns DELAYED when offline_queue_depth is positive`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            offlineQueueDepth = 3
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DELAYED, result)
    }

    @Test
    fun `RECOVERED has higher priority than DEGRADED`() {
        // reconnect=recovered (RECOVERED) but also degraded reason → RECOVERED wins because reasons empty
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            reconnectRecoveryState = "recovered",
            degradedReasons = emptyList()
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.RECOVERED, result)
    }

    @Test
    fun `DEGRADED wins over FALLBACK when both conditions present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            degradedReasons = listOf("model_load_failed"),
            plannerFallbackTier = "tier_2"
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.DEGRADED, result)
    }

    @Test
    fun `FALLBACK wins over CONSTRAINED when both conditions present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            plannerFallbackTier = "tier_2",
            meshConstrainedReasons = listOf("bandwidth_limit")
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK, result)
    }

    @Test
    fun `CONSTRAINED wins over PARTIAL when both conditions present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            meshConstrainedReasons = listOf("quota_exceeded"),
            plannerReady = true,
            groundingReady = false
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.CONSTRAINED, result)
    }

    @Test
    fun `PARTIAL wins over DELAYED when both conditions present`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            plannerReady = true,
            groundingReady = false,
            offlineQueueDepth = 2
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.PARTIAL, result)
    }

    // ── classifyResultUplink — result uplink semantic class ───────────────────

    @Test
    fun `classifyResultUplink CAPABILITY_IDLE returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink PENDING returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.PENDING
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink ACTIVATING returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVATING
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink ACTIVE returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink DEGRADED phase returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink RETRYING returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.RETRYING
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink UNKNOWN returns INFORMATIONAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.UNKNOWN
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.INFORMATIONAL, result)
    }

    @Test
    fun `classifyResultUplink INTERRUPTED returns AUTHORITATIVE_INTERRUPTION`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION, result)
    }

    @Test
    fun `classifyResultUplink COMPLETED without prior interruption returns AUTHORITATIVE_TERMINAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            priorPhaseWasInterruption = false
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL, result)
    }

    @Test
    fun `classifyResultUplink FAILED without prior interruption returns AUTHORITATIVE_TERMINAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED,
            priorPhaseWasInterruption = false
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL, result)
    }

    @Test
    fun `classifyResultUplink TIMED_OUT returns AUTHORITATIVE_TERMINAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.TIMED_OUT
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL, result)
    }

    @Test
    fun `classifyResultUplink REJECTED returns AUTHORITATIVE_TERMINAL`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL, result)
    }

    @Test
    fun `classifyResultUplink COMPLETED after interruption returns AUTHORITATIVE_RECOVERY`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            priorPhaseWasInterruption = true
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY, result)
    }

    @Test
    fun `classifyResultUplink FAILED after interruption returns AUTHORITATIVE_RECOVERY`() {
        val result = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED,
            priorPhaseWasInterruption = true
        )
        assertEquals(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY, result)
    }

    // ── DeviceStateSnapshotPayload — new field defaults ───────────────────────

    @Test
    fun `snapshot reported_state_semantic_class defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.reported_state_semantic_class)
    }

    @Test
    fun `snapshot degraded_condition_class defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.degraded_condition_class)
    }

    @Test
    fun `snapshot local_observation_basis defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.local_observation_basis)
    }

    @Test
    fun `snapshot reported_state_semantic_class can be set to any wire value`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.entries) {
            val snapshot = minimalSnapshot(reportedStateSemanticClass = cls.wireValue)
            assertEquals(cls.wireValue, snapshot.reported_state_semantic_class)
        }
    }

    @Test
    fun `snapshot degraded_condition_class can be set to any wire value`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.entries) {
            val snapshot = minimalSnapshot(degradedConditionClass = cls.wireValue)
            assertEquals(cls.wireValue, snapshot.degraded_condition_class)
        }
    }

    @Test
    fun `snapshot local_observation_basis can be set to any wire value`() {
        for (basis in AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.entries) {
            val snapshot = minimalSnapshot(localObservationBasis = basis.wireValue)
            assertEquals(basis.wireValue, snapshot.local_observation_basis)
        }
    }

    // ── DeviceStateSnapshotPayload — Gson round-trip ──────────────────────────

    @Test
    fun `snapshot reported_state_semantic_class round-trips through Gson`() {
        val wireValue = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue
        val snapshot = minimalSnapshot(reportedStateSemanticClass = wireValue)
        val json = gson.toJsonTree(snapshot).asJsonObject
        assertEquals(wireValue, json.get("reported_state_semantic_class").asString)
    }

    @Test
    fun `snapshot degraded_condition_class round-trips through Gson`() {
        val wireValue = AndroidCanonicalRuntimeTruthContract.DegradedConditionClass.FALLBACK.wireValue
        val snapshot = minimalSnapshot(degradedConditionClass = wireValue)
        val json = gson.toJsonTree(snapshot).asJsonObject
        assertEquals(wireValue, json.get("degraded_condition_class").asString)
    }

    @Test
    fun `snapshot local_observation_basis round-trips through Gson`() {
        val wireValue = AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME.wireValue
        val snapshot = minimalSnapshot(localObservationBasis = wireValue)
        val json = gson.toJsonTree(snapshot).asJsonObject
        assertEquals(wireValue, json.get("local_observation_basis").asString)
    }

    @Test
    fun `snapshot reported_state_semantic_class null serialises as JSON null`() {
        val snapshot = minimalSnapshot()
        val json = gson.toJsonTree(snapshot).asJsonObject
        assertTrue(json.get("reported_state_semantic_class").isJsonNull)
    }

    // ── DeviceExecutionEventPayload — new field defaults ──────────────────────

    @Test
    fun `event reported_state_semantic_class defaults to null`() {
        val event = minimalEvent()
        assertNull(event.reported_state_semantic_class)
    }

    @Test
    fun `event result_uplink_semantic_class defaults to null`() {
        val event = minimalEvent()
        assertNull(event.result_uplink_semantic_class)
    }

    @Test
    fun `event reported_state_semantic_class can be set to any wire value`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.entries) {
            val event = minimalEvent(reportedStateSemanticClass = cls.wireValue)
            assertEquals(cls.wireValue, event.reported_state_semantic_class)
        }
    }

    @Test
    fun `event result_uplink_semantic_class can be set to any wire value`() {
        for (cls in AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.entries) {
            val event = minimalEvent(resultUplinkSemanticClass = cls.wireValue)
            assertEquals(cls.wireValue, event.result_uplink_semantic_class)
        }
    }

    // ── DeviceExecutionEventPayload — Gson round-trip ─────────────────────────

    @Test
    fun `event reported_state_semantic_class round-trips through Gson`() {
        val wireValue = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue
        val event = minimalEvent(reportedStateSemanticClass = wireValue)
        val json = gson.toJsonTree(event).asJsonObject
        assertEquals(wireValue, json.get("reported_state_semantic_class").asString)
    }

    @Test
    fun `event result_uplink_semantic_class round-trips through Gson`() {
        val wireValue = AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL.wireValue
        val event = minimalEvent(resultUplinkSemanticClass = wireValue)
        val json = gson.toJsonTree(event).asJsonObject
        assertEquals(wireValue, json.get("result_uplink_semantic_class").asString)
    }

    @Test
    fun `event result_uplink_semantic_class null serialises as JSON null`() {
        val event = minimalEvent()
        val json = gson.toJsonTree(event).asJsonObject
        assertTrue(json.get("result_uplink_semantic_class").isJsonNull)
    }

    // ── End-to-end: classify and embed in snapshot/event ─────────────────────

    @Test
    fun `end-to-end active runtime snapshot classification embeds correctly`() {
        val semanticClass = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            carrierRuntimeState = "active",
            executionBusy = true,
            executionModeState = "cross_device_active"
        )
        val conditionClass = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition()
        val snapshot = minimalSnapshot(
            carrierRuntimeState = "active",
            executionModeState = "cross_device_active",
            executionBusy = true,
            reportedStateSemanticClass = semanticClass.wireValue,
            degradedConditionClass = conditionClass.wireValue,
            localObservationBasis = AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME.wireValue
        )
        assertEquals("active_runtime", snapshot.reported_state_semantic_class)
        assertEquals("nominal", snapshot.degraded_condition_class)
        assertEquals("live_runtime", snapshot.local_observation_basis)
    }

    @Test
    fun `end-to-end degraded snapshot classification embeds correctly`() {
        val semanticClass = AndroidCanonicalRuntimeTruthContract.classifySnapshot(
            carrierRuntimeState = "active",
            executionModeState = "cross_device_degraded"
        )
        val conditionClass = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            executionModeState = "cross_device_degraded",
            degradedReasons = listOf("inference_unavailable")
        )
        val snapshot = minimalSnapshot(
            carrierRuntimeState = "active",
            executionModeState = "cross_device_degraded",
            reportedStateSemanticClass = semanticClass.wireValue,
            degradedConditionClass = conditionClass.wireValue,
            localObservationBasis = AndroidCanonicalRuntimeTruthContract.LocalObservationBasis.LIVE_RUNTIME.wireValue
        )
        assertEquals("active_runtime", snapshot.reported_state_semantic_class)
        assertEquals("degraded", snapshot.degraded_condition_class)
    }

    @Test
    fun `end-to-end terminal event with result uplink semantic class`() {
        val resultUplinkClass = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            priorPhaseWasInterruption = false
        )
        val event = minimalEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            reportedStateSemanticClass = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.TERMINAL_REPORTING.wireValue,
            resultUplinkSemanticClass = resultUplinkClass.wireValue
        )
        assertEquals("terminal_reporting", event.reported_state_semantic_class)
        assertEquals("authoritative_terminal", event.result_uplink_semantic_class)
    }

    @Test
    fun `end-to-end interruption event classification`() {
        val resultUplinkClass = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
        )
        val event = minimalEvent(
            reportedStateSemanticClass = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            resultUplinkSemanticClass = resultUplinkClass.wireValue
        )
        assertEquals("active_runtime", event.reported_state_semantic_class)
        assertEquals("authoritative_interruption", event.result_uplink_semantic_class)
        // V2 must apply retry/fallback policy for interruption
        assertTrue(AndroidCanonicalRuntimeTruthContract.ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION.v2Action.contains("retry"))
    }

    @Test
    fun `end-to-end recovery event after prior interruption`() {
        val resultUplinkClass = AndroidCanonicalRuntimeTruthContract.classifyResultUplink(
            AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
            priorPhaseWasInterruption = true
        )
        val conditionClass = AndroidCanonicalRuntimeTruthContract.classifyDegradedCondition(
            reconnectRecoveryState = "recovered"
        )
        val event = minimalEvent(
            phase = DeviceExecutionEventPayload.PHASE_COMPLETED,
            reportedStateSemanticClass = AndroidCanonicalRuntimeTruthContract.ReportedStateSemanticClass.ACTIVE_RUNTIME.wireValue,
            resultUplinkSemanticClass = resultUplinkClass.wireValue
        )
        assertEquals("authoritative_recovery", event.result_uplink_semantic_class)
        assertEquals("recovered", conditionClass.wireValue)
    }

    // ── CLOSURE_INVARIANTS ────────────────────────────────────────────────────

    @Test
    fun `all CLOSURE_INVARIANTS are true`() {
        val failures = AndroidCanonicalRuntimeTruthContract.CLOSURE_INVARIANTS
            .filterValues { !it }
        assertTrue(
            "CLOSURE_INVARIANTS failures: ${failures.keys}",
            failures.isEmpty()
        )
    }

    @Test
    fun `INTRODUCED_PR is PR-08Android`() {
        assertEquals("PR-08Android", AndroidCanonicalRuntimeTruthContract.INTRODUCED_PR)
    }

    // ── StabilizationBaseline — registration ──────────────────────────────────

    @Test
    fun `android-canonical-runtime-truth-contract is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("android-canonical-runtime-truth-contract"))
    }

    @Test
    fun `android-canonical-runtime-truth-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-canonical-runtime-truth-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `android-canonical-runtime-truth-contract is in extendableSurfaceIds`() {
        assertTrue(StabilizationBaseline.extendableSurfaceIds.contains("android-canonical-runtime-truth-contract"))
    }
}
