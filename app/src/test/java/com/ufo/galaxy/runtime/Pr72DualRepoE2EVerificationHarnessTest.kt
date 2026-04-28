package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-72 (Android) — Automated verification tests for the dual-repo E2E verification harness.
 *
 * This test suite covers all verification outcomes and stage classifications mandated
 * by the PR-72 problem statement:
 *
 *  1. [DualRepoE2EVerificationStage] — seven-stage enum, wire values, required set,
 *     [toBridgeScenario] mapping.
 *  2. [DualRepoE2EVerificationArtifact] — seven-subtype sealed class, artifact tags.
 *  3. [DualRepoE2EVerificationHarness] — artifact derivation rules:
 *     - no device → E2EBlockedNoDevice
 *     - no stages → E2EAbsent
 *     - stale → E2EStale
 *     - simulated + all required pass → E2ESimulatedOnly
 *     - simulated + missing required → E2EPartiallyVerified
 *     - any required missing → E2EPartiallyVerified
 *     - emulator + all required pass → E2EEmulatorVerifiedOnly
 *     - real_device + all required pass → E2EFullyVerified
 *  4. [DualRepoE2EVerificationReport] — data class, toWireMap, isRealDeviceE2EVerified,
 *     isV2Consumable, schema version, embedded bridge report.
 *  5. [DualRepoE2EStageOutcome] — data class, outcomeStatus, reason.
 *  6. Bridge wiring — overlapping stages correctly forwarded; CAPABILITY_REPORT and
 *     TASK_RESULT_RETURN not forwarded.
 *  7. Honest no-device output — E2EBlockedNoDevice in CI / no-device scenarios.
 *
 * ## Test matrix
 *
 * ### DualRepoE2EVerificationStage — wire values
 *  - DEVICE_REGISTER wireValue is "device_register"
 *  - CAPABILITY_REPORT wireValue is "capability_report"
 *  - TASK_ASSIGNMENT_RECEPTION wireValue is "task_assignment_reception"
 *  - DELEGATED_EXECUTION_AVAILABLE wireValue is "delegated_execution_available"
 *  - TASK_RESULT_RETURN wireValue is "task_result_return"
 *  - RECONNECT_RECOVERY wireValue is "reconnect_recovery"
 *  - DEGRADED_OUTCOME_RECORDING wireValue is "degraded_outcome_recording"
 *  - All seven wire values are distinct
 *  - ALL_WIRE_VALUES has exactly seven entries
 *  - fromWireValue returns correct stage for each wire value
 *  - fromWireValue returns null for unknown value
 *
 * ### DualRepoE2EVerificationStage — required stages
 *  - DEVICE_REGISTER isRequired is true
 *  - CAPABILITY_REPORT isRequired is true
 *  - TASK_ASSIGNMENT_RECEPTION isRequired is true
 *  - DELEGATED_EXECUTION_AVAILABLE isRequired is true
 *  - TASK_RESULT_RETURN isRequired is true
 *  - RECONNECT_RECOVERY isRequired is false
 *  - DEGRADED_OUTCOME_RECORDING isRequired is false
 *  - REQUIRED_STAGES has exactly five entries
 *  - REQUIRED_STAGES contains all five required stages
 *
 * ### DualRepoE2EVerificationStage — toBridgeScenario
 *  - DEVICE_REGISTER maps to REGISTRATION
 *  - TASK_ASSIGNMENT_RECEPTION maps to COMMAND_RECEPTION
 *  - DELEGATED_EXECUTION_AVAILABLE maps to DELEGATED_EXECUTION_AVAILABILITY
 *  - RECONNECT_RECOVERY maps to DISCONNECT_RECONNECT
 *  - DEGRADED_OUTCOME_RECORDING maps to DEGRADED_OUTCOME_RECORDING
 *  - CAPABILITY_REPORT maps to null (no bridge equivalent)
 *  - TASK_RESULT_RETURN maps to null (no bridge equivalent)
 *
 * ### DualRepoE2EVerificationHarness — constants
 *  - ARTIFACT_E2E_FULLY_VERIFIED is "e2e_fully_verified"
 *  - ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY is "e2e_emulator_verified_only"
 *  - ARTIFACT_E2E_SIMULATED_ONLY is "e2e_simulated_only"
 *  - ARTIFACT_E2E_PARTIALLY_VERIFIED is "e2e_partially_verified"
 *  - ARTIFACT_E2E_STALE is "e2e_stale"
 *  - ARTIFACT_E2E_ABSENT is "e2e_absent"
 *  - ARTIFACT_E2E_BLOCKED_NO_DEVICE is "e2e_blocked_no_device"
 *  - All seven artifact tag constants are distinct
 *  - ALL_ARTIFACT_TAGS has exactly seven entries
 *  - INTRODUCED_PR is 72
 *  - DESCRIPTION is non-blank
 *
 * ### evaluateArtifact — E2EBlockedNoDevice
 * ### evaluateArtifact — E2EAbsent
 * ### evaluateArtifact — E2EStale
 * ### evaluateArtifact — E2ESimulatedOnly
 * ### evaluateArtifact — E2EPartiallyVerified
 * ### evaluateArtifact — E2EEmulatorVerifiedOnly
 * ### evaluateArtifact — E2EFullyVerified
 * ### buildReport — bridge report wiring
 * ### DualRepoE2EVerificationReport — toWireMap
 * ### DualRepoE2EVerificationReport — isRealDeviceE2EVerified / isV2Consumable
 * ### Honest no-device output
 */
class Pr72DualRepoE2EVerificationHarnessTest {

    private lateinit var harness: DualRepoE2EVerificationHarness

    @Before
    fun setUp() {
        harness = DualRepoE2EVerificationHarness(
            deviceId = "device-72-test",
            participantId = "participant-72-test",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Records all five required stages as PASSED on [h]. */
    private fun recordAllRequired(h: DualRepoE2EVerificationHarness) {
        DualRepoE2EVerificationStage.REQUIRED_STAGES.forEach { stage ->
            h.recordStageOutcome(stage, ScenarioOutcomeStatus.PASSED)
        }
    }

    /** Records all seven stages as PASSED on [h]. */
    private fun recordAll(h: DualRepoE2EVerificationHarness) {
        DualRepoE2EVerificationStage.entries.forEach { stage ->
            h.recordStageOutcome(stage, ScenarioOutcomeStatus.PASSED)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationStage — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_REGISTER wireValue is device_register`() {
        assertEquals("device_register", DualRepoE2EVerificationStage.DEVICE_REGISTER.wireValue)
    }

    @Test
    fun `CAPABILITY_REPORT wireValue is capability_report`() {
        assertEquals("capability_report", DualRepoE2EVerificationStage.CAPABILITY_REPORT.wireValue)
    }

    @Test
    fun `TASK_ASSIGNMENT_RECEPTION wireValue is task_assignment_reception`() {
        assertEquals(
            "task_assignment_reception",
            DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION.wireValue
        )
    }

    @Test
    fun `DELEGATED_EXECUTION_AVAILABLE wireValue is delegated_execution_available`() {
        assertEquals(
            "delegated_execution_available",
            DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE.wireValue
        )
    }

    @Test
    fun `TASK_RESULT_RETURN wireValue is task_result_return`() {
        assertEquals(
            "task_result_return",
            DualRepoE2EVerificationStage.TASK_RESULT_RETURN.wireValue
        )
    }

    @Test
    fun `RECONNECT_RECOVERY wireValue is reconnect_recovery`() {
        assertEquals(
            "reconnect_recovery",
            DualRepoE2EVerificationStage.RECONNECT_RECOVERY.wireValue
        )
    }

    @Test
    fun `DEGRADED_OUTCOME_RECORDING wireValue is degraded_outcome_recording`() {
        assertEquals(
            "degraded_outcome_recording",
            DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING.wireValue
        )
    }

    @Test
    fun `All seven stage wire values are distinct`() {
        val values = DualRepoE2EVerificationStage.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly seven entries`() {
        assertEquals(7, DualRepoE2EVerificationStage.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue returns correct stage for each wire value`() {
        DualRepoE2EVerificationStage.entries.forEach { stage ->
            assertEquals(stage, DualRepoE2EVerificationStage.fromWireValue(stage.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(DualRepoE2EVerificationStage.fromWireValue("unknown_stage"))
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertNull(DualRepoE2EVerificationStage.fromWireValue(null))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationStage — required stages
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_REGISTER isRequired is true`() {
        assertTrue(DualRepoE2EVerificationStage.DEVICE_REGISTER.isRequired)
    }

    @Test
    fun `CAPABILITY_REPORT isRequired is true`() {
        assertTrue(DualRepoE2EVerificationStage.CAPABILITY_REPORT.isRequired)
    }

    @Test
    fun `TASK_ASSIGNMENT_RECEPTION isRequired is true`() {
        assertTrue(DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION.isRequired)
    }

    @Test
    fun `DELEGATED_EXECUTION_AVAILABLE isRequired is true`() {
        assertTrue(DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE.isRequired)
    }

    @Test
    fun `TASK_RESULT_RETURN isRequired is true`() {
        assertTrue(DualRepoE2EVerificationStage.TASK_RESULT_RETURN.isRequired)
    }

    @Test
    fun `RECONNECT_RECOVERY isRequired is false`() {
        assertFalse(DualRepoE2EVerificationStage.RECONNECT_RECOVERY.isRequired)
    }

    @Test
    fun `DEGRADED_OUTCOME_RECORDING isRequired is false`() {
        assertFalse(DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING.isRequired)
    }

    @Test
    fun `REQUIRED_STAGES has exactly five entries`() {
        assertEquals(5, DualRepoE2EVerificationStage.REQUIRED_STAGES.size)
    }

    @Test
    fun `REQUIRED_STAGES contains DEVICE_REGISTER`() {
        assertTrue(DualRepoE2EVerificationStage.DEVICE_REGISTER in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES contains CAPABILITY_REPORT`() {
        assertTrue(DualRepoE2EVerificationStage.CAPABILITY_REPORT in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES contains TASK_ASSIGNMENT_RECEPTION`() {
        assertTrue(DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES contains DELEGATED_EXECUTION_AVAILABLE`() {
        assertTrue(DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES contains TASK_RESULT_RETURN`() {
        assertTrue(DualRepoE2EVerificationStage.TASK_RESULT_RETURN in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES does not contain RECONNECT_RECOVERY`() {
        assertFalse(DualRepoE2EVerificationStage.RECONNECT_RECOVERY in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    @Test
    fun `REQUIRED_STAGES does not contain DEGRADED_OUTCOME_RECORDING`() {
        assertFalse(DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING in DualRepoE2EVerificationStage.REQUIRED_STAGES)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationStage — toBridgeScenario
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DEVICE_REGISTER toBridgeScenario maps to REGISTRATION`() {
        assertEquals(
            RealDeviceVerificationScenario.REGISTRATION,
            DualRepoE2EVerificationStage.DEVICE_REGISTER.toBridgeScenario()
        )
    }

    @Test
    fun `TASK_ASSIGNMENT_RECEPTION toBridgeScenario maps to COMMAND_RECEPTION`() {
        assertEquals(
            RealDeviceVerificationScenario.COMMAND_RECEPTION,
            DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION.toBridgeScenario()
        )
    }

    @Test
    fun `DELEGATED_EXECUTION_AVAILABLE toBridgeScenario maps to DELEGATED_EXECUTION_AVAILABILITY`() {
        assertEquals(
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY,
            DualRepoE2EVerificationStage.DELEGATED_EXECUTION_AVAILABLE.toBridgeScenario()
        )
    }

    @Test
    fun `RECONNECT_RECOVERY toBridgeScenario maps to DISCONNECT_RECONNECT`() {
        assertEquals(
            RealDeviceVerificationScenario.DISCONNECT_RECONNECT,
            DualRepoE2EVerificationStage.RECONNECT_RECOVERY.toBridgeScenario()
        )
    }

    @Test
    fun `DEGRADED_OUTCOME_RECORDING toBridgeScenario maps to DEGRADED_OUTCOME_RECORDING`() {
        assertEquals(
            RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING,
            DualRepoE2EVerificationStage.DEGRADED_OUTCOME_RECORDING.toBridgeScenario()
        )
    }

    @Test
    fun `CAPABILITY_REPORT toBridgeScenario returns null (no bridge equivalent)`() {
        assertNull(DualRepoE2EVerificationStage.CAPABILITY_REPORT.toBridgeScenario())
    }

    @Test
    fun `TASK_RESULT_RETURN toBridgeScenario returns null (no bridge equivalent)`() {
        assertNull(DualRepoE2EVerificationStage.TASK_RESULT_RETURN.toBridgeScenario())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationHarness — constants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_E2E_FULLY_VERIFIED is e2e_fully_verified`() {
        assertEquals("e2e_fully_verified", DualRepoE2EVerificationHarness.ARTIFACT_E2E_FULLY_VERIFIED)
    }

    @Test
    fun `ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY is e2e_emulator_verified_only`() {
        assertEquals(
            "e2e_emulator_verified_only",
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY
        )
    }

    @Test
    fun `ARTIFACT_E2E_SIMULATED_ONLY is e2e_simulated_only`() {
        assertEquals("e2e_simulated_only", DualRepoE2EVerificationHarness.ARTIFACT_E2E_SIMULATED_ONLY)
    }

    @Test
    fun `ARTIFACT_E2E_PARTIALLY_VERIFIED is e2e_partially_verified`() {
        assertEquals(
            "e2e_partially_verified",
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_PARTIALLY_VERIFIED
        )
    }

    @Test
    fun `ARTIFACT_E2E_STALE is e2e_stale`() {
        assertEquals("e2e_stale", DualRepoE2EVerificationHarness.ARTIFACT_E2E_STALE)
    }

    @Test
    fun `ARTIFACT_E2E_ABSENT is e2e_absent`() {
        assertEquals("e2e_absent", DualRepoE2EVerificationHarness.ARTIFACT_E2E_ABSENT)
    }

    @Test
    fun `ARTIFACT_E2E_BLOCKED_NO_DEVICE is e2e_blocked_no_device`() {
        assertEquals(
            "e2e_blocked_no_device",
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_BLOCKED_NO_DEVICE
        )
    }

    @Test
    fun `All seven artifact tag constants are distinct`() {
        assertEquals(7, DualRepoE2EVerificationHarness.ALL_ARTIFACT_TAGS.size)
    }

    @Test
    fun `ALL_ARTIFACT_TAGS has exactly seven entries`() {
        assertEquals(7, DualRepoE2EVerificationHarness.ALL_ARTIFACT_TAGS.size)
    }

    @Test
    fun `Harness INTRODUCED_PR is 72`() {
        assertEquals(72, DualRepoE2EVerificationHarness.INTRODUCED_PR)
    }

    @Test
    fun `Harness DESCRIPTION is non-blank`() {
        assertTrue(DualRepoE2EVerificationHarness.DESCRIPTION.isNotBlank())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EBlockedNoDevice
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EBlockedNoDevice when verificationKind is NO_DEVICE`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceHarness.evaluateArtifact()
        assertTrue(
            "Expected E2EBlockedNoDevice but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EBlockedNoDevice
        )
    }

    @Test
    fun `E2EBlockedNoDevice artifactTag is ARTIFACT_E2E_BLOCKED_NO_DEVICE`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EBlockedNoDevice
        assertEquals(
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_BLOCKED_NO_DEVICE,
            artifact.artifactTag
        )
    }

    @Test
    fun `E2EBlockedNoDevice blockedReason is non-blank`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EBlockedNoDevice
        assertTrue(artifact.blockedReason.isNotBlank())
    }

    @Test
    fun `NO_DEVICE takes priority over no stages recorded`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        // No stages recorded — but NO_DEVICE must still produce E2EBlockedNoDevice, not E2EAbsent
        val artifact = noDeviceHarness.evaluateArtifact()
        assertTrue(artifact is DualRepoE2EVerificationArtifact.E2EBlockedNoDevice)
    }

    @Test
    fun `NO_DEVICE takes priority even when all required stages are recorded`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        recordAllRequired(noDeviceHarness)
        val artifact = noDeviceHarness.evaluateArtifact()
        assertTrue(
            "NO_DEVICE must produce E2EBlockedNoDevice even when stages are recorded; got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EBlockedNoDevice
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EAbsent
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EAbsent when no stage outcomes recorded (REAL_DEVICE kind)`() {
        val artifact = harness.evaluateArtifact()
        assertTrue(
            "Expected E2EAbsent but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EAbsent
        )
    }

    @Test
    fun `E2EAbsent artifactTag is ARTIFACT_E2E_ABSENT`() {
        val artifact = harness.evaluateArtifact() as DualRepoE2EVerificationArtifact.E2EAbsent
        assertEquals(DualRepoE2EVerificationHarness.ARTIFACT_E2E_ABSENT, artifact.artifactTag)
    }

    @Test
    fun `E2EAbsent reason is non-blank`() {
        val artifact = harness.evaluateArtifact() as DualRepoE2EVerificationArtifact.E2EAbsent
        assertTrue(artifact.reason.isNotBlank())
    }

    @Test
    fun `E2EAbsent after clearAllOutcomes`() {
        recordAllRequired(harness)
        harness.clearAllOutcomes()
        val artifact = harness.evaluateArtifact()
        assertTrue(artifact is DualRepoE2EVerificationArtifact.E2EAbsent)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EStale
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EStale when verificationKind is STALE and outcomes recorded`() {
        val staleHarness = DualRepoE2EVerificationHarness(
            deviceId = "stale-dev",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        recordAllRequired(staleHarness)
        val artifact = staleHarness.evaluateArtifact()
        assertTrue(
            "Expected E2EStale but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EStale
        )
    }

    @Test
    fun `E2EStale artifactTag is ARTIFACT_E2E_STALE`() {
        val staleHarness = DualRepoE2EVerificationHarness(
            deviceId = "stale-dev",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        recordAllRequired(staleHarness)
        val artifact = staleHarness.evaluateArtifact() as DualRepoE2EVerificationArtifact.E2EStale
        assertEquals(DualRepoE2EVerificationHarness.ARTIFACT_E2E_STALE, artifact.artifactTag)
    }

    @Test
    fun `E2EStale staleReason is non-blank`() {
        val staleHarness = DualRepoE2EVerificationHarness(
            deviceId = "stale-dev",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        recordAllRequired(staleHarness)
        val artifact = staleHarness.evaluateArtifact() as DualRepoE2EVerificationArtifact.E2EStale
        assertTrue(artifact.staleReason.isNotBlank())
    }

    @Test
    fun `E2EStale deviceId matches harness deviceId`() {
        val staleHarness = DualRepoE2EVerificationHarness(
            deviceId = "stale-dev-72",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        recordAllRequired(staleHarness)
        val artifact = staleHarness.evaluateArtifact() as DualRepoE2EVerificationArtifact.E2EStale
        assertEquals("stale-dev-72", artifact.deviceId)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2ESimulatedOnly
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2ESimulatedOnly when verificationKind is SIMULATED and all required pass`() {
        val simHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simHarness)
        val artifact = simHarness.evaluateArtifact()
        assertTrue(
            "Expected E2ESimulatedOnly but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2ESimulatedOnly
        )
    }

    @Test
    fun `E2ESimulatedOnly artifactTag is ARTIFACT_E2E_SIMULATED_ONLY`() {
        val simHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simHarness)
        val artifact = simHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2ESimulatedOnly
        assertEquals(DualRepoE2EVerificationHarness.ARTIFACT_E2E_SIMULATED_ONLY, artifact.artifactTag)
    }

    @Test
    fun `E2ESimulatedOnly passedStages includes all five required stages`() {
        val simHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simHarness)
        val artifact = simHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2ESimulatedOnly
        DualRepoE2EVerificationStage.REQUIRED_STAGES.forEach { stage ->
            assertTrue("Expected $stage in passedStages", stage in artifact.passedStages)
        }
    }

    @Test
    fun `Returns E2EPartiallyVerified when SIMULATED kind but required stage missing`() {
        val simHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        // Record only DEVICE_REGISTER — missing CAPABILITY_REPORT and others
        simHarness.recordStageOutcome(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = simHarness.evaluateArtifact()
        assertTrue(
            "Expected E2EPartiallyVerified but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EPartiallyVerified
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EPartiallyVerified when CAPABILITY_REPORT is missing`() {
        // Record all required except CAPABILITY_REPORT
        DualRepoE2EVerificationStage.REQUIRED_STAGES
            .filter { it != DualRepoE2EVerificationStage.CAPABILITY_REPORT }
            .forEach { harness.recordStageOutcome(it, ScenarioOutcomeStatus.PASSED) }
        val artifact = harness.evaluateArtifact()
        assertTrue(
            "Expected E2EPartiallyVerified but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        )
    }

    @Test
    fun `Returns E2EPartiallyVerified when TASK_RESULT_RETURN is missing`() {
        // Record all required except TASK_RESULT_RETURN
        DualRepoE2EVerificationStage.REQUIRED_STAGES
            .filter { it != DualRepoE2EVerificationStage.TASK_RESULT_RETURN }
            .forEach { harness.recordStageOutcome(it, ScenarioOutcomeStatus.PASSED) }
        val artifact = harness.evaluateArtifact()
        assertTrue(
            "Expected E2EPartiallyVerified but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        )
    }

    @Test
    fun `E2EPartiallyVerified missingRequiredStages includes CAPABILITY_REPORT when absent`() {
        DualRepoE2EVerificationStage.REQUIRED_STAGES
            .filter { it != DualRepoE2EVerificationStage.CAPABILITY_REPORT }
            .forEach { harness.recordStageOutcome(it, ScenarioOutcomeStatus.PASSED) }
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        assertTrue(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT in artifact.missingRequiredStages
        )
    }

    @Test
    fun `E2EPartiallyVerified missingRequiredStages includes TASK_RESULT_RETURN when absent`() {
        DualRepoE2EVerificationStage.REQUIRED_STAGES
            .filter { it != DualRepoE2EVerificationStage.TASK_RESULT_RETURN }
            .forEach { harness.recordStageOutcome(it, ScenarioOutcomeStatus.PASSED) }
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        assertTrue(
            DualRepoE2EVerificationStage.TASK_RESULT_RETURN in artifact.missingRequiredStages
        )
    }

    @Test
    fun `E2EPartiallyVerified artifactTag is ARTIFACT_E2E_PARTIALLY_VERIFIED`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        assertEquals(
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_PARTIALLY_VERIFIED,
            artifact.artifactTag
        )
    }

    @Test
    fun `E2EPartiallyVerified partialReason is non-blank`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EPartiallyVerified
        assertTrue(artifact.partialReason.isNotBlank())
    }

    @Test
    fun `FAILED required stage produces E2EPartiallyVerified`() {
        recordAllRequired(harness)
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.FAILED,
            reason = "V2 rejected capability report"
        )
        val artifact = harness.evaluateArtifact()
        assertTrue(artifact is DualRepoE2EVerificationArtifact.E2EPartiallyVerified)
    }

    @Test
    fun `TIMED_OUT required stage produces E2EPartiallyVerified`() {
        recordAllRequired(harness)
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.TASK_RESULT_RETURN,
            ScenarioOutcomeStatus.TIMED_OUT,
            reason = "No task result received within window"
        )
        val artifact = harness.evaluateArtifact()
        assertTrue(artifact is DualRepoE2EVerificationArtifact.E2EPartiallyVerified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EEmulatorVerifiedOnly
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EEmulatorVerifiedOnly when verificationKind is EMULATOR and all required pass`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            deviceId = "emu-dev-72",
            participantId = "emu-pid-72",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val artifact = emuHarness.evaluateArtifact()
        assertTrue(
            "Expected E2EEmulatorVerifiedOnly but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly
        )
    }

    @Test
    fun `E2EEmulatorVerifiedOnly artifactTag is ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val artifact = emuHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly
        assertEquals(
            DualRepoE2EVerificationHarness.ARTIFACT_E2E_EMULATOR_VERIFIED_ONLY,
            artifact.artifactTag
        )
    }

    @Test
    fun `E2EEmulatorVerifiedOnly deviceId matches harness deviceId`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            deviceId = "emu-dev-72",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val artifact = emuHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly
        assertEquals("emu-dev-72", artifact.deviceId)
    }

    @Test
    fun `E2EEmulatorVerifiedOnly participantId matches harness participantId`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            deviceId = "emu-dev",
            participantId = "emu-pid-72",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val artifact = emuHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly
        assertEquals("emu-pid-72", artifact.participantId)
    }

    @Test
    fun `E2EEmulatorVerifiedOnly passedStages includes all five required stages`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val artifact = emuHarness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EEmulatorVerifiedOnly
        DualRepoE2EVerificationStage.REQUIRED_STAGES.forEach { stage ->
            assertTrue("Expected $stage in passedStages", stage in artifact.passedStages)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — E2EFullyVerified
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns E2EFullyVerified when REAL_DEVICE kind and all required stages pass`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact()
        assertTrue(
            "Expected E2EFullyVerified but got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EFullyVerified
        )
    }

    @Test
    fun `E2EFullyVerified artifactTag is ARTIFACT_E2E_FULLY_VERIFIED`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertEquals(DualRepoE2EVerificationHarness.ARTIFACT_E2E_FULLY_VERIFIED, artifact.artifactTag)
    }

    @Test
    fun `E2EFullyVerified deviceId matches harness deviceId`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertEquals("device-72-test", artifact.deviceId)
    }

    @Test
    fun `E2EFullyVerified participantId matches harness participantId`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertEquals("participant-72-test", artifact.participantId)
    }

    @Test
    fun `E2EFullyVerified passedStages includes all five required stages`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        DualRepoE2EVerificationStage.REQUIRED_STAGES.forEach { stage ->
            assertTrue("Expected $stage in passedStages", stage in artifact.passedStages)
        }
    }

    @Test
    fun `E2EFullyVerified passedStages includes CAPABILITY_REPORT`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertTrue(DualRepoE2EVerificationStage.CAPABILITY_REPORT in artifact.passedStages)
    }

    @Test
    fun `E2EFullyVerified passedStages includes TASK_RESULT_RETURN`() {
        recordAllRequired(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertTrue(DualRepoE2EVerificationStage.TASK_RESULT_RETURN in artifact.passedStages)
    }

    @Test
    fun `E2EFullyVerified with all seven stages including optional ones`() {
        recordAll(harness)
        val artifact = harness.evaluateArtifact() as
            DualRepoE2EVerificationArtifact.E2EFullyVerified
        assertEquals(7, artifact.passedStages.size)
    }

    @Test
    fun `Optional stages passing does not prevent E2EFullyVerified when required all pass`() {
        recordAllRequired(harness)
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.RECONNECT_RECOVERY,
            ScenarioOutcomeStatus.SKIPPED,
            reason = "Recovery capability not exercised in this run"
        )
        val artifact = harness.evaluateArtifact()
        assertTrue(
            "Optional SKIPPED stage must not block E2EFullyVerified; got $artifact",
            artifact is DualRepoE2EVerificationArtifact.E2EFullyVerified
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bridge report wiring
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildBridgeReport forwards DEVICE_REGISTER to bridge REGISTRATION scenario`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            ScenarioOutcomeStatus.PASSED
        )
        val bridge = harness.buildBridgeReport()
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            bridge.scenarioOutcomes[RealDeviceVerificationScenario.REGISTRATION]?.outcomeStatus
        )
    }

    @Test
    fun `buildBridgeReport forwards TASK_ASSIGNMENT_RECEPTION to bridge COMMAND_RECEPTION`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.TASK_ASSIGNMENT_RECEPTION,
            ScenarioOutcomeStatus.PASSED
        )
        val bridge = harness.buildBridgeReport()
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            bridge.scenarioOutcomes[RealDeviceVerificationScenario.COMMAND_RECEPTION]?.outcomeStatus
        )
    }

    @Test
    fun `buildBridgeReport does NOT forward CAPABILITY_REPORT to bridge`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.PASSED
        )
        val bridge = harness.buildBridgeReport()
        // CAPABILITY_REPORT has no bridge scenario; none of the 5 bridge scenarios should be set
        // (because only CAPABILITY_REPORT was recorded, and it has no bridge equivalent)
        assertTrue(bridge.scenarioOutcomes.isEmpty())
    }

    @Test
    fun `buildBridgeReport does NOT forward TASK_RESULT_RETURN to bridge`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.TASK_RESULT_RETURN,
            ScenarioOutcomeStatus.PASSED
        )
        val bridge = harness.buildBridgeReport()
        assertTrue(bridge.scenarioOutcomes.isEmpty())
    }

    @Test
    fun `buildBridgeReport produces RealDeviceFullyVerified when bridge required scenarios pass`() {
        // Record all 5 required harness stages (3 of which map to bridge required scenarios)
        recordAllRequired(harness)
        val bridge = harness.buildBridgeReport()
        // Bridge should be fully verified because all 3 bridge required scenarios were forwarded
        assertTrue(
            "Expected RealDeviceFullyVerified bridge artifact; got ${bridge.overallVerificationArtifact}",
            bridge.overallVerificationArtifact is
                RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        )
    }

    @Test
    fun `buildBridgeReport preserves lifecycleTruthState`() {
        harness.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        recordAllRequired(harness)
        val bridge = harness.buildBridgeReport()
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, bridge.lifecycleTruthState)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationReport — isRealDeviceE2EVerified / isV2Consumable
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isRealDeviceE2EVerified is true when REAL_DEVICE kind and E2EFullyVerified`() {
        recordAllRequired(harness)
        val report = harness.buildReport()
        assertTrue(report.isRealDeviceE2EVerified)
    }

    @Test
    fun `isRealDeviceE2EVerified is false when kind is EMULATOR`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            deviceId = "emu-dev",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val report = emuHarness.buildReport()
        assertFalse(report.isRealDeviceE2EVerified)
    }

    @Test
    fun `isRealDeviceE2EVerified is false when kind is NO_DEVICE`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val report = noDeviceHarness.buildReport()
        assertFalse(report.isRealDeviceE2EVerified)
    }

    @Test
    fun `isV2Consumable is true when REAL_DEVICE kind and E2EFullyVerified`() {
        recordAllRequired(harness)
        val report = harness.buildReport()
        assertTrue(report.isV2Consumable)
    }

    @Test
    fun `isV2Consumable is false when kind is NO_DEVICE`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val report = noDeviceHarness.buildReport()
        assertFalse(report.isV2Consumable)
    }

    @Test
    fun `isV2Consumable is false when deviceId is blank`() {
        val blankDeviceHarness = DualRepoE2EVerificationHarness(
            deviceId = "",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        recordAllRequired(blankDeviceHarness)
        val report = blankDeviceHarness.buildReport()
        assertFalse(report.isV2Consumable)
    }

    @Test
    fun `isV2Consumable is true when EMULATOR kind and all required pass`() {
        val emuHarness = DualRepoE2EVerificationHarness(
            deviceId = "emu-dev-72",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuHarness)
        val report = emuHarness.buildReport()
        assertTrue(report.isV2Consumable)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationReport — toWireMap
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap contains schema_version matching SCHEMA_VERSION`() {
        val report = harness.buildReport()
        assertEquals(DualRepoE2EVerificationReport.SCHEMA_VERSION, report.toWireMap()["schema_version"])
    }

    @Test
    fun `toWireMap contains device_id matching deviceId`() {
        val report = harness.buildReport()
        assertEquals("device-72-test", report.toWireMap()["device_id"])
    }

    @Test
    fun `toWireMap contains participant_id matching participantId`() {
        val report = harness.buildReport()
        assertEquals("participant-72-test", report.toWireMap()["participant_id"])
    }

    @Test
    fun `toWireMap contains verification_kind as wire value`() {
        val report = harness.buildReport()
        assertEquals("real_device", report.toWireMap()["verification_kind"])
    }

    @Test
    fun `toWireMap contains artifact_tag matching overallArtifact artifactTag`() {
        val report = harness.buildReport()
        assertEquals(report.overallArtifact.artifactTag, report.toWireMap()["artifact_tag"])
    }

    @Test
    fun `toWireMap contains lifecycle_truth_state as wire value when set`() {
        harness.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        val report = harness.buildReport()
        assertEquals("active", report.toWireMap()["lifecycle_truth_state"])
    }

    @Test
    fun `toWireMap contains lifecycle_truth_state null when not set`() {
        val report = harness.buildReport()
        assertNull(report.toWireMap()["lifecycle_truth_state"])
    }

    @Test
    fun `toWireMap contains reported_at_ms`() {
        val report = harness.buildReport()
        assertNotNull(report.toWireMap()["reported_at_ms"])
    }

    @Test
    fun `toWireMap contains is_real_device_e2e_verified boolean`() {
        recordAllRequired(harness)
        val report = harness.buildReport()
        assertEquals(true, report.toWireMap()["is_real_device_e2e_verified"])
    }

    @Test
    fun `toWireMap contains is_real_device_e2e_verified false when no device`() {
        val noDeviceHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val report = noDeviceHarness.buildReport()
        assertEquals(false, report.toWireMap()["is_real_device_e2e_verified"])
    }

    @Test
    fun `toWireMap contains is_v2_consumable boolean`() {
        val report = harness.buildReport()
        assertNotNull(report.toWireMap()["is_v2_consumable"])
    }

    @Test
    fun `toWireMap stage_outcomes contains passed stages`() {
        recordAllRequired(harness)
        val report = harness.buildReport()
        @Suppress("UNCHECKED_CAST")
        val stageOutcomes = report.toWireMap()["stage_outcomes"] as Map<String, String>
        assertEquals("passed", stageOutcomes["capability_report"])
        assertEquals("passed", stageOutcomes["task_result_return"])
    }

    @Test
    fun `toWireMap stage_reasons contains reasons for non-passed stages`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.FAILED,
            reason = "V2 rejected capability report"
        )
        val report = harness.buildReport()
        @Suppress("UNCHECKED_CAST")
        val stageReasons = report.toWireMap()["stage_reasons"] as Map<String, String>
        assertEquals("V2 rejected capability report", stageReasons["capability_report"])
    }

    @Test
    fun `toWireMap contains bridge_artifact_tag`() {
        val report = harness.buildReport()
        assertNotNull(report.toWireMap()["bridge_artifact_tag"])
    }

    @Test
    fun `toWireMap contains bridge_is_real_device_verified`() {
        val report = harness.buildReport()
        assertNotNull(report.toWireMap()["bridge_is_real_device_verified"])
    }

    @Test
    fun `toWireMap bridge_is_real_device_verified is true when all required stages pass on real device`() {
        recordAllRequired(harness)
        val report = harness.buildReport()
        assertEquals(true, report.toWireMap()["bridge_is_real_device_verified"])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EVerificationReport — schema version
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SCHEMA_VERSION is 1_0`() {
        assertEquals("1.0", DualRepoE2EVerificationReport.SCHEMA_VERSION)
    }

    @Test
    fun `Report DESCRIPTION is non-blank`() {
        assertTrue(DualRepoE2EVerificationReport.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `Report INTRODUCED_PR is 72`() {
        assertEquals(72, DualRepoE2EVerificationReport.INTRODUCED_PR)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Honest no-device output — CI / absent device scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Default harness with NO_DEVICE produces E2EBlockedNoDevice report`() {
        val defaultHarness = DualRepoE2EVerificationHarness()
        val report = defaultHarness.buildReport()
        assertTrue(
            "Default NO_DEVICE harness must produce E2EBlockedNoDevice; got ${report.overallArtifact}",
            report.overallArtifact is DualRepoE2EVerificationArtifact.E2EBlockedNoDevice
        )
    }

    @Test
    fun `CI run without real device produces e2e_blocked_no_device artifact tag in wire map`() {
        val ciHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val report = ciHarness.buildReport()
        assertEquals("e2e_blocked_no_device", report.toWireMap()["artifact_tag"])
    }

    @Test
    fun `CI run without real device is not V2 consumable`() {
        val ciHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        assertFalse(ciHarness.buildReport().isV2Consumable)
    }

    @Test
    fun `CI run without real device is not real device E2E verified`() {
        val ciHarness = DualRepoE2EVerificationHarness(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        assertFalse(ciHarness.buildReport().isRealDeviceE2EVerified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DualRepoE2EStageOutcome
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DualRepoE2EStageOutcome holds stage outcomeStatus and reason`() {
        val outcome = DualRepoE2EStageOutcome(
            stage = DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            outcomeStatus = ScenarioOutcomeStatus.FAILED,
            reason = "Timeout"
        )
        assertEquals(DualRepoE2EVerificationStage.CAPABILITY_REPORT, outcome.stage)
        assertEquals(ScenarioOutcomeStatus.FAILED, outcome.outcomeStatus)
        assertEquals("Timeout", outcome.reason)
    }

    @Test
    fun `DualRepoE2EStageOutcome reason is null by default`() {
        val outcome = DualRepoE2EStageOutcome(
            stage = DualRepoE2EVerificationStage.DEVICE_REGISTER,
            outcomeStatus = ScenarioOutcomeStatus.PASSED
        )
        assertNull(outcome.reason)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStageStatus / getStageOutcomes / clearAllOutcomes
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getStageStatus returns PASSED after recording PASSED`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.PASSED
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            harness.getStageStatus(DualRepoE2EVerificationStage.CAPABILITY_REPORT)
        )
    }

    @Test
    fun `getStageStatus returns null before recording`() {
        assertNull(harness.getStageStatus(DualRepoE2EVerificationStage.TASK_RESULT_RETURN))
    }

    @Test
    fun `getStageOutcomes returns immutable snapshot`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.DEVICE_REGISTER,
            ScenarioOutcomeStatus.PASSED
        )
        val snapshot = harness.getStageOutcomes()
        assertEquals(1, snapshot.size)
    }

    @Test
    fun `clearAllOutcomes clears stage outcomes and lifecycleTruthState`() {
        recordAllRequired(harness)
        harness.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        harness.clearAllOutcomes()
        assertTrue(harness.getStageOutcomes().isEmpty())
        assertNull(harness.lifecycleTruthState)
    }

    @Test
    fun `recordStageOutcome replaces previous outcome for same stage`() {
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.FAILED,
            reason = "Initial failure"
        )
        harness.recordStageOutcome(
            DualRepoE2EVerificationStage.CAPABILITY_REPORT,
            ScenarioOutcomeStatus.PASSED
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            harness.getStageStatus(DualRepoE2EVerificationStage.CAPABILITY_REPORT)
        )
    }
}
