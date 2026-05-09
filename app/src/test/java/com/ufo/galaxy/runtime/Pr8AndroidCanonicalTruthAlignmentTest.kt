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
 * PR-08 (Android side) — Canonical Runtime Truth Unification regression tests.
 *
 * Validates all deliverables of the Android-side canonical truth alignment work:
 *  1. [AndroidCanonicalTruthAlignmentContract] — four-category state surface classification,
 *     seven-scenario reporting classification, uplink rules, and canonical truth invariants.
 *  2. [DeviceStateSnapshotPayload] — new `reporting_scenario` and `state_surface_class` fields.
 *  3. [DeviceExecutionEventPayload] — new `reporting_scenario` and `state_surface_class` fields.
 *  4. [StabilizationBaseline] — new PR-08 surfaces registered and extendable.
 *
 * ## Test matrix
 *
 * ### StateSurfaceClass — four categories
 *  - All four categories have distinct wire values
 *  - ACTIVE_RUNTIME has the highest precedence
 *  - LOCALLY_OBSERVED has the lowest precedence
 *  - TERMINAL_REPORTED has higher precedence than LOCALLY_DERIVED
 *  - fromWireValue() returns each category for its wire value
 *  - fromWireValue(null) returns null
 *  - fromWireValue(unknown) returns null
 *  - ALL_WIRE_VALUES has 4 entries
 *
 * ### ReportingScenario — seven scenarios
 *  - All seven scenarios have distinct wire values
 *  - WIRE_KEY constant is "reporting_scenario"
 *  - ALL_WIRE_VALUES has 7 entries
 *  - fromWireValue() returns each scenario for its wire value
 *  - DEGRADED.requiresDegradedFields is true
 *  - NORMAL.requiresDegradedFields is false
 *  - No scenario has isTerminal=true in this contract
 *  - DEGRADED_CONTEXT_SCENARIOS contains only DEGRADED
 *  - NON_TERMINAL_SCENARIOS contains all seven scenarios
 *
 * ### STATE_SURFACE_REGISTRY — surface classification
 *  - Registry is non-empty
 *  - execution-mode-state is LOCALLY_DERIVED
 *  - participant-identity-freshness is LOCALLY_DERIVED
 *  - carrier-foreground-visible is LOCALLY_OBSERVED
 *  - accessibility-ready is LOCALLY_OBSERVED
 *  - execution-lifecycle-phase is ACTIVE_RUNTIME
 *  - terminal-execution-phase is TERMINAL_REPORTED
 *  - All entries have non-blank surfaceKey and wireKey
 *
 * ### REPORTING_SCENARIO_UPLINK_RULES — uplink discipline
 *  - All seven scenarios have a rule
 *  - DEGRADED rule requires state uplink and degraded-context fields
 *  - FALLBACK rule requires state uplink and fallback_tier
 *  - CONSTRAINED rule requires state uplink and mesh_constrained_reasons
 *  - DELAYED rule requires state uplink
 *  - RECOVERED rule requires state uplink and reconnect_recovery_state
 *  - NORMAL rule does not require state or result uplink
 *  - No rule requires result uplink (governed by ExecutionUplinkDiscipline)
 *
 * ### CANONICAL_TRUTH_INVARIANTS — all true
 *  - All invariants pass (validateInvariants() returns empty list)
 *
 * ### DeviceStateSnapshotPayload — new fields present
 *  - reporting_scenario field exists and defaults to null
 *  - state_surface_class field exists and defaults to null
 *  - reporting_scenario can be set to each ReportingScenario wire value
 *  - state_surface_class can be set to each StateSurfaceClass wire value
 *
 * ### DeviceExecutionEventPayload — new fields present
 *  - reporting_scenario field exists and defaults to null
 *  - state_surface_class field exists and defaults to null
 *  - reporting_scenario can be set to each ReportingScenario wire value
 *
 * ### State/reporting transitions — canonical scenarios
 *  - Normal execution: reporting_scenario=normal, state_surface_class=locally_derived
 *  - Degraded execution: reporting_scenario=degraded, state_surface_class=active_runtime
 *  - Fallback execution: reporting_scenario=fallback, state_surface_class=active_runtime
 *  - Constrained: reporting_scenario=constrained, state_surface_class=active_runtime
 *  - Delayed: reporting_scenario=delayed, state_surface_class=active_runtime
 *  - Partial: reporting_scenario=partial, state_surface_class=active_runtime
 *  - Recovered: reporting_scenario=recovered, state_surface_class=locally_derived
 *
 * ### Reported-vs-local distinction
 *  - LOCALLY_DERIVED execution_mode_state is preferred over LOCALLY_OBSERVED carrier_foreground_visible
 *  - ACTIVE_RUNTIME phase beats LOCALLY_DERIVED execution_mode_state for dispatch eligibility
 *  - TERMINAL_REPORTED phase requires result uplink before clearing dispatch block
 *
 * ### Degraded/fallback/recovery reporting
 *  - DEGRADED scenario requires local_intelligence_status and degraded_reasons
 *  - FALLBACK scenario requires current_fallback_tier
 *  - RECOVERED scenario requires reconnect_recovery_state and participant_identity_freshness
 *
 * ### StabilizationBaseline — new PR-08 surfaces registered
 *  - android-canonical-truth-alignment-contract is registered and CANONICAL_STABLE
 *  - state-surface-class is registered and CANONICAL_STABLE
 *  - reporting-scenario is registered and CANONICAL_STABLE
 *  - device-state-snapshot-reporting-scenario-field is registered and CANONICAL_STABLE
 *  - device-execution-event-reporting-scenario-field is registered and CANONICAL_STABLE
 *  - All new surfaces are in extendableSurfaceIds
 *
 * ### Center-side canonical truth compatibility
 *  - INTRODUCED_PR is "PR-08"
 *  - CENTER_SIDE_CANONICAL_TRUTH_MODEL is populated
 *  - STATE_SURFACE_REGISTRY covers all four StateSurfaceClass categories
 */
class Pr8AndroidCanonicalTruthAlignmentTest {

    // ── Payload factories ─────────────────────────────────────────────────────

    private fun minimalSnapshot(
        reportingScenario: String? = null,
        stateSurfaceClass: String? = null
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
        reporting_scenario = reportingScenario,
        state_surface_class = stateSurfaceClass
    )

    private fun minimalEvent(
        reportingScenario: String? = null,
        stateSurfaceClass: String? = null
    ) = DeviceExecutionEventPayload(
        flow_id = "flow-1",
        task_id = "task-1",
        phase = DeviceExecutionEventPayload.PHASE_EXECUTION_STARTED,
        reporting_scenario = reportingScenario,
        state_surface_class = stateSurfaceClass
    )

    // ── StateSurfaceClass — four categories ───────────────────────────────────

    @Test
    fun `all four StateSurfaceClass categories have distinct wire values`() {
        val wireValues = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries
            .map { it.wireValue }
        assertEquals(4, wireValues.size)
        assertEquals(wireValues.size, wireValues.distinct().size)
    }

    @Test
    fun `ACTIVE_RUNTIME has the highest precedence`() {
        val maxPrecedence = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries
            .maxByOrNull { it.precedence }
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME,
            maxPrecedence
        )
    }

    @Test
    fun `LOCALLY_OBSERVED has the lowest precedence`() {
        val minPrecedence = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries
            .minByOrNull { it.precedence }
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_OBSERVED,
            minPrecedence
        )
    }

    @Test
    fun `TERMINAL_REPORTED has higher precedence than LOCALLY_DERIVED`() {
        val terminal = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.TERMINAL_REPORTED
        val derived = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED
        assertTrue(terminal.precedence > derived.precedence)
    }

    @Test
    fun `fromWireValue returns correct category for each wire value`() {
        for (category in AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries) {
            val parsed = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass
                .fromWireValue(category.wireValue)
            assertEquals(category, parsed)
        }
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertNull(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.fromWireValue(null)
        )
    }

    @Test
    fun `fromWireValue returns null for unknown wire value`() {
        assertNull(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.fromWireValue("unknown_category")
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains exactly 4 entries`() {
        assertEquals(
            4,
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `StateSurfaceClass wire values are non-empty and lowercase`() {
        for (category in AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries) {
            assertTrue(
                "Wire value for $category must be non-blank",
                category.wireValue.isNotBlank()
            )
            assertEquals(
                "Wire value for $category must be lowercase",
                category.wireValue,
                category.wireValue.lowercase()
            )
        }
    }

    // ── ReportingScenario — seven scenarios ───────────────────────────────────

    @Test
    fun `all seven ReportingScenario scenarios have distinct wire values`() {
        val wireValues = AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries
            .map { it.wireValue }
        assertEquals(7, wireValues.size)
        assertEquals(wireValues.size, wireValues.distinct().size)
    }

    @Test
    fun `WIRE_KEY constant is reporting_scenario`() {
        assertEquals(
            "reporting_scenario",
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.WIRE_KEY
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains exactly 7 entries`() {
        assertEquals(
            7,
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.ALL_WIRE_VALUES.size
        )
    }

    @Test
    fun `fromWireValue returns correct scenario for each wire value`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            val parsed = AndroidCanonicalTruthAlignmentContract.ReportingScenario
                .fromWireValue(scenario.wireValue)
            assertEquals(scenario, parsed)
        }
    }

    @Test
    fun `DEGRADED requiresDegradedFields is true`() {
        assertTrue(
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.requiresDegradedFields
        )
    }

    @Test
    fun `NORMAL requiresDegradedFields is false`() {
        assertFalse(
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.NORMAL.requiresDegradedFields
        )
    }

    @Test
    fun `no scenario has isTerminal=true in this contract`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            assertFalse(
                "Scenario $scenario.wireValue must not be terminal in this contract",
                scenario.isTerminal
            )
        }
    }

    @Test
    fun `DEGRADED_CONTEXT_SCENARIOS contains only DEGRADED`() {
        val degradedContextScenarios =
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED_CONTEXT_SCENARIOS
        assertEquals(1, degradedContextScenarios.size)
        assertTrue(
            degradedContextScenarios.contains(
                AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED
            )
        )
    }

    @Test
    fun `NON_TERMINAL_SCENARIOS contains all seven scenarios`() {
        val nonTerminal =
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.NON_TERMINAL_SCENARIOS
        assertEquals(7, nonTerminal.size)
    }

    @Test
    fun `ReportingScenario wire values are non-empty and lowercase`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            assertTrue(
                "Wire value for $scenario must be non-blank",
                scenario.wireValue.isNotBlank()
            )
            assertEquals(
                "Wire value for $scenario must be lowercase",
                scenario.wireValue,
                scenario.wireValue.lowercase()
            )
        }
    }

    @Test
    fun `each ReportingScenario has a non-blank v2PolicyAction`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            assertTrue(
                "v2PolicyAction for $scenario must be non-blank",
                scenario.v2PolicyAction.isNotBlank()
            )
        }
    }

    // ── STATE_SURFACE_REGISTRY ────────────────────────────────────────────────

    @Test
    fun `STATE_SURFACE_REGISTRY is non-empty`() {
        assertTrue(
            AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY.isNotEmpty()
        )
    }

    @Test
    fun `execution-mode-state is classified as LOCALLY_DERIVED`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["execution-mode-state"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `participant-identity-freshness is classified as LOCALLY_DERIVED`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["participant-identity-freshness"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `carrier-foreground-visible is classified as LOCALLY_OBSERVED`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["carrier-foreground-visible"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_OBSERVED,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `accessibility-ready is classified as LOCALLY_OBSERVED`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["accessibility-ready"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_OBSERVED,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `execution-lifecycle-phase is classified as ACTIVE_RUNTIME`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["execution-lifecycle-phase"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `terminal-execution-phase is classified as TERMINAL_REPORTED`() {
        val entry = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY["terminal-execution-phase"]
        assertNotNull(entry)
        assertEquals(
            AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.TERMINAL_REPORTED,
            entry!!.surfaceClass
        )
    }

    @Test
    fun `all STATE_SURFACE_REGISTRY entries have non-blank surfaceKey and wireKey`() {
        for ((key, entry) in AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY) {
            assertTrue("surfaceKey must be non-blank for registry key '$key'", entry.surfaceKey.isNotBlank())
            assertTrue("wireKey must be non-blank for registry key '$key'", entry.wireKey.isNotBlank())
        }
    }

    @Test
    fun `STATE_SURFACE_REGISTRY covers all four StateSurfaceClass categories`() {
        val representedCategories = AndroidCanonicalTruthAlignmentContract.STATE_SURFACE_REGISTRY
            .values
            .map { it.surfaceClass }
            .toSet()
        for (category in AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries) {
            assertTrue(
                "StateSurfaceClass.$category must be represented in STATE_SURFACE_REGISTRY",
                representedCategories.contains(category)
            )
        }
    }

    // ── REPORTING_SCENARIO_UPLINK_RULES ───────────────────────────────────────

    @Test
    fun `all seven scenarios have uplink rules`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[scenario.wireValue]
            assertNotNull("Uplink rule must exist for scenario ${scenario.wireValue}", rule)
        }
    }

    @Test
    fun `DEGRADED uplink rule requires state uplink`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
    }

    @Test
    fun `DEGRADED uplink rule requires degraded-context snapshot fields`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("local_intelligence_status"))
        assertTrue(rule.requiredSnapshotKeys.contains("degraded_reasons"))
    }

    @Test
    fun `FALLBACK uplink rule requires state uplink and fallback_tier`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.FALLBACK.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
        assertTrue(rule.requiredSnapshotKeys.contains("current_fallback_tier"))
    }

    @Test
    fun `CONSTRAINED uplink rule requires state uplink and mesh_constrained_reasons`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.CONSTRAINED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
        assertTrue(rule.requiredSnapshotKeys.contains("mesh_constrained_reasons"))
    }

    @Test
    fun `DELAYED uplink rule requires state uplink`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DELAYED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
    }

    @Test
    fun `RECOVERED uplink rule requires state uplink and reconnect_recovery_state`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.RECOVERED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
        assertTrue(rule.requiredSnapshotKeys.contains("reconnect_recovery_state"))
        assertTrue(rule.requiredSnapshotKeys.contains("participant_identity_freshness"))
    }

    @Test
    fun `NORMAL uplink rule does not require state or result uplink`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.NORMAL.wireValue
        ]
        assertNotNull(rule)
        assertFalse(rule!!.stateUplinkRequired)
        assertFalse(rule.resultUplinkRequired)
    }

    @Test
    fun `no uplink rule requires a result uplink`() {
        for ((key, rule) in AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES) {
            assertFalse(
                "Uplink rule for '$key' must not require result uplink (governed by ExecutionUplinkDiscipline)",
                rule.resultUplinkRequired
            )
        }
    }

    @Test
    fun `all uplink rules have non-blank v2PolicyAction`() {
        for ((key, rule) in AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES) {
            assertTrue(
                "v2PolicyAction must be non-blank for uplink rule '$key'",
                rule.v2PolicyAction.isNotBlank()
            )
        }
    }

    // ── CANONICAL_TRUTH_INVARIANTS ────────────────────────────────────────────

    @Test
    fun `all CANONICAL_TRUTH_INVARIANTS are true`() {
        val violations = AndroidCanonicalTruthAlignmentContract.validateInvariants()
        assertTrue(
            "CANONICAL_TRUTH_INVARIANT violations: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `validateInvariants returns empty list when all invariants pass`() {
        assertEquals(
            emptyList<String>(),
            AndroidCanonicalTruthAlignmentContract.validateInvariants()
        )
    }

    // ── DeviceStateSnapshotPayload — new fields ───────────────────────────────

    @Test
    fun `DeviceStateSnapshotPayload reporting_scenario field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.reporting_scenario)
    }

    @Test
    fun `DeviceStateSnapshotPayload state_surface_class field exists and defaults to null`() {
        val snapshot = minimalSnapshot()
        assertNull(snapshot.state_surface_class)
    }

    @Test
    fun `DeviceStateSnapshotPayload reporting_scenario can hold each ReportingScenario wire value`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            val snapshot = minimalSnapshot(reportingScenario = scenario.wireValue)
            assertEquals(scenario.wireValue, snapshot.reporting_scenario)
        }
    }

    @Test
    fun `DeviceStateSnapshotPayload state_surface_class can hold each StateSurfaceClass wire value`() {
        for (category in AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries) {
            val snapshot = minimalSnapshot(stateSurfaceClass = category.wireValue)
            assertEquals(category.wireValue, snapshot.state_surface_class)
        }
    }

    // ── DeviceExecutionEventPayload — new fields ──────────────────────────────

    @Test
    fun `DeviceExecutionEventPayload reporting_scenario field exists and defaults to null`() {
        val event = minimalEvent()
        assertNull(event.reporting_scenario)
    }

    @Test
    fun `DeviceExecutionEventPayload state_surface_class field exists and defaults to null`() {
        val event = minimalEvent()
        assertNull(event.state_surface_class)
    }

    @Test
    fun `DeviceExecutionEventPayload reporting_scenario can hold each ReportingScenario wire value`() {
        for (scenario in AndroidCanonicalTruthAlignmentContract.ReportingScenario.entries) {
            val event = minimalEvent(reportingScenario = scenario.wireValue)
            assertEquals(scenario.wireValue, event.reporting_scenario)
        }
    }

    @Test
    fun `DeviceExecutionEventPayload state_surface_class can hold each StateSurfaceClass wire value`() {
        for (category in AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.entries) {
            val event = minimalEvent(stateSurfaceClass = category.wireValue)
            assertEquals(category.wireValue, event.state_surface_class)
        }
    }

    // ── State/reporting transition scenarios ──────────────────────────────────

    @Test
    fun `normal execution: reporting_scenario=normal state_surface_class=locally_derived`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.NORMAL.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED.wireValue
        )
        assertEquals("normal", snapshot.reporting_scenario)
        assertEquals("locally_derived", snapshot.state_surface_class)
    }

    @Test
    fun `degraded execution: reporting_scenario=degraded state_surface_class=active_runtime`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME.wireValue
        )
        assertEquals("degraded", snapshot.reporting_scenario)
        assertEquals("active_runtime", snapshot.state_surface_class)

        // V2 must reduce capability estimate for this snapshot
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES["degraded"]
        assertNotNull(rule)
        assertTrue(rule!!.stateUplinkRequired)
    }

    @Test
    fun `fallback execution: reporting_scenario=fallback state_surface_class=active_runtime`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.FALLBACK.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME.wireValue
        )
        assertEquals("fallback", snapshot.reporting_scenario)
        assertEquals("active_runtime", snapshot.state_surface_class)
    }

    @Test
    fun `constrained participation: reporting_scenario=constrained state_surface_class=active_runtime`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.CONSTRAINED.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME.wireValue
        )
        assertEquals("constrained", snapshot.reporting_scenario)
        assertEquals("active_runtime", snapshot.state_surface_class)
    }

    @Test
    fun `delayed execution: reporting_scenario=delayed state_surface_class=active_runtime`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.DELAYED.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME.wireValue
        )
        assertEquals("delayed", snapshot.reporting_scenario)
        assertEquals("active_runtime", snapshot.state_surface_class)
    }

    @Test
    fun `partial result: reporting_scenario=partial state_surface_class=active_runtime`() {
        val event = minimalEvent(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.PARTIAL.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME.wireValue
        )
        assertEquals("partial", event.reporting_scenario)
        assertEquals("active_runtime", event.state_surface_class)
    }

    @Test
    fun `recovered state: reporting_scenario=recovered state_surface_class=locally_derived`() {
        val snapshot = minimalSnapshot(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.RECOVERED.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED.wireValue
        )
        assertEquals("recovered", snapshot.reporting_scenario)
        assertEquals("locally_derived", snapshot.state_surface_class)
    }

    // ── Reported-vs-local distinction ─────────────────────────────────────────

    @Test
    fun `LOCALLY_DERIVED has higher precedence than LOCALLY_OBSERVED`() {
        // execution_mode_state (LOCALLY_DERIVED) takes precedence over
        // carrier_foreground_visible (LOCALLY_OBSERVED) for dispatch decisions
        val derived = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED
        val observed = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_OBSERVED
        assertTrue(
            "LOCALLY_DERIVED precedence (${derived.precedence}) must be higher than LOCALLY_OBSERVED (${observed.precedence})",
            derived.precedence > observed.precedence
        )
    }

    @Test
    fun `ACTIVE_RUNTIME beats LOCALLY_DERIVED for dispatch eligibility`() {
        // phase=ACTIVE (ACTIVE_RUNTIME) takes precedence over execution_mode_state (LOCALLY_DERIVED)
        val activeRuntime = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME
        val derived = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.LOCALLY_DERIVED
        assertTrue(
            "ACTIVE_RUNTIME precedence (${activeRuntime.precedence}) must exceed LOCALLY_DERIVED (${derived.precedence})",
            activeRuntime.precedence > derived.precedence
        )
    }

    @Test
    fun `terminal phase event has state_surface_class terminal_reported`() {
        val event = minimalEvent(
            reportingScenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.NORMAL.wireValue,
            stateSurfaceClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.TERMINAL_REPORTED.wireValue
        )
        assertEquals("terminal_reported", event.state_surface_class)

        // TERMINAL_REPORTED is not the highest precedence — ACTIVE_RUNTIME is
        val terminalClass = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.TERMINAL_REPORTED
        val activeRuntime = AndroidCanonicalTruthAlignmentContract.StateSurfaceClass.ACTIVE_RUNTIME
        assertTrue(activeRuntime.precedence > terminalClass.precedence)
    }

    // ── Degraded/fallback/recovery reporting ──────────────────────────────────

    @Test
    fun `DEGRADED scenario requires local_intelligence_status in snapshot`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("local_intelligence_status"))
    }

    @Test
    fun `DEGRADED scenario requires degraded_reasons in snapshot`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("degraded_reasons"))
    }

    @Test
    fun `FALLBACK scenario requires current_fallback_tier in snapshot`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.FALLBACK.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("current_fallback_tier"))
    }

    @Test
    fun `RECOVERED scenario requires reconnect_recovery_state in snapshot`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.RECOVERED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("reconnect_recovery_state"))
    }

    @Test
    fun `RECOVERED scenario requires participant_identity_freshness in snapshot`() {
        val rule = AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES[
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.RECOVERED.wireValue
        ]
        assertNotNull(rule)
        assertTrue(rule!!.requiredSnapshotKeys.contains("participant_identity_freshness"))
    }

    @Test
    fun `degraded-context scenarios set requiresDegradedFields=true`() {
        val degradedContextScenarios =
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED_CONTEXT_SCENARIOS
        for (scenario in degradedContextScenarios) {
            assertTrue(
                "Scenario ${scenario.wireValue} must have requiresDegradedFields=true",
                scenario.requiresDegradedFields
            )
        }
    }

    // ── StabilizationBaseline — new surfaces ──────────────────────────────────

    @Test
    fun `android-canonical-truth-alignment-contract is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("android-canonical-truth-alignment-contract"))
    }

    @Test
    fun `android-canonical-truth-alignment-contract is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("android-canonical-truth-alignment-contract")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `state-surface-class is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("state-surface-class"))
    }

    @Test
    fun `state-surface-class is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("state-surface-class")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `reporting-scenario is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("reporting-scenario"))
    }

    @Test
    fun `reporting-scenario is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("reporting-scenario")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `device-state-snapshot-reporting-scenario-field is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("device-state-snapshot-reporting-scenario-field"))
    }

    @Test
    fun `device-state-snapshot-reporting-scenario-field is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("device-state-snapshot-reporting-scenario-field")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `device-execution-event-reporting-scenario-field is registered in StabilizationBaseline`() {
        assertTrue(StabilizationBaseline.isRegistered("device-execution-event-reporting-scenario-field"))
    }

    @Test
    fun `device-execution-event-reporting-scenario-field is CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("device-execution-event-reporting-scenario-field")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all new PR-08 surfaces are in extendableSurfaceIds`() {
        val newSurfaces = listOf(
            "android-canonical-truth-alignment-contract",
            "state-surface-class",
            "reporting-scenario",
            "device-state-snapshot-reporting-scenario-field",
            "device-execution-event-reporting-scenario-field"
        )
        for (surfaceId in newSurfaces) {
            assertTrue(
                "Surface '$surfaceId' must be in extendableSurfaceIds",
                StabilizationBaseline.extendableSurfaceIds.contains(surfaceId)
            )
        }
    }

    // ── Center-side canonical truth compatibility ──────────────────────────────

    @Test
    fun `INTRODUCED_PR is PR-08`() {
        assertEquals("PR-08", AndroidCanonicalTruthAlignmentContract.INTRODUCED_PR)
    }

    @Test
    fun `CENTER_SIDE_CANONICAL_TRUTH_MODEL is populated`() {
        assertTrue(
            AndroidCanonicalTruthAlignmentContract.CENTER_SIDE_CANONICAL_TRUTH_MODEL.isNotBlank()
        )
    }

    @Test
    fun `snapshot and event reporting_scenario fields carry matching values for same emission context`() {
        // When Android emits a snapshot and event in the same DEGRADED scenario,
        // both must carry the same reporting_scenario wire value
        val scenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.DEGRADED
        val snapshot = minimalSnapshot(reportingScenario = scenario.wireValue)
        val event = minimalEvent(reportingScenario = scenario.wireValue)
        assertEquals(snapshot.reporting_scenario, event.reporting_scenario)
    }

    @Test
    fun `ReportingScenario fromWireValue returns null for null`() {
        assertNull(
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.fromWireValue(null)
        )
    }

    @Test
    fun `ReportingScenario fromWireValue returns null for unknown wire value`() {
        assertNull(
            AndroidCanonicalTruthAlignmentContract.ReportingScenario.fromWireValue("unknown_scenario")
        )
    }

    @Test
    fun `uplink rules v2PolicyAction strings match corresponding ReportingScenario v2PolicyAction`() {
        for ((wireValue, rule) in AndroidCanonicalTruthAlignmentContract.REPORTING_SCENARIO_UPLINK_RULES) {
            val scenario = AndroidCanonicalTruthAlignmentContract.ReportingScenario.fromWireValue(wireValue)
            assertNotNull("Scenario must exist for rule wire value '$wireValue'", scenario)
            assertEquals(
                "Rule v2PolicyAction must match scenario v2PolicyAction for '$wireValue'",
                scenario!!.v2PolicyAction,
                rule.v2PolicyAction
            )
        }
    }
}
