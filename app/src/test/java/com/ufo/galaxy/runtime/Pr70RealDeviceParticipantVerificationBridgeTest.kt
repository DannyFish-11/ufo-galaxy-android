package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR-70 (Android) — Automated verification tests for the real-device participant
 * verification bridge.
 *
 * This test suite covers all verification outcomes and scenario classifications mandated
 * by the PR-70 problem statement:
 *
 *  1. [RealDeviceVerificationKind] — six-kind enum, wire values, helpers.
 *  2. [RealDeviceVerificationScenario] — five-scenario enum, wire values, required set.
 *  3. [ScenarioOutcomeStatus] — four-status enum, wire values.
 *  4. [RealDeviceParticipantVerificationArtifact] — seven-subtype sealed class, artifact tags.
 *  5. [RealDeviceParticipantVerificationBridge] — artifact derivation rules:
 *     - no device → VerificationBlockedNoDevice
 *     - no outcomes → VerificationAbsent
 *     - stale → StaleVerification
 *     - simulated + all required pass → SimulatedVerifiedOnly
 *     - any required missing → PartiallyVerified
 *     - emulator + all required pass → EmulatorVerifiedOnly
 *     - real_device + all required pass → RealDeviceFullyVerified
 *  6. [RealDeviceParticipantVerificationReport] — data class, toWireMap, helpers.
 *
 * ## Test matrix
 *
 * ### RealDeviceVerificationKind — wire values
 *  - REAL_DEVICE wireValue is "real_device"
 *  - EMULATOR wireValue is "emulator"
 *  - SIMULATED wireValue is "simulated"
 *  - STALE wireValue is "stale"
 *  - INCOMPLETE wireValue is "incomplete"
 *  - NO_DEVICE wireValue is "no_device"
 *  - All six wire values are distinct
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - fromWireValue returns correct kind for each wire value
 *  - fromWireValue returns null for unknown value
 *  - fromWireValue returns null for null input
 *
 * ### RealDeviceVerificationKind — helpers
 *  - isRealDeviceAuthoritative returns true only for REAL_DEVICE
 *  - isRealDeviceAuthoritative returns false for EMULATOR
 *  - isUsableEvidence returns true for REAL_DEVICE and EMULATOR
 *  - isUsableEvidence returns false for SIMULATED, STALE, INCOMPLETE, NO_DEVICE
 *
 * ### RealDeviceVerificationScenario — wire values
 *  - REGISTRATION wireValue is "registration"
 *  - COMMAND_RECEPTION wireValue is "command_reception"
 *  - DELEGATED_EXECUTION_AVAILABILITY wireValue is "delegated_execution_availability"
 *  - DISCONNECT_RECONNECT wireValue is "disconnect_reconnect"
 *  - DEGRADED_OUTCOME_RECORDING wireValue is "degraded_outcome_recording"
 *  - All five wire values are distinct
 *  - ALL_WIRE_VALUES has exactly five entries
 *  - fromWireValue returns correct scenario for each wire value
 *  - fromWireValue returns null for unknown value
 *
 * ### RealDeviceVerificationScenario — required scenarios
 *  - REGISTRATION isRequired is true
 *  - COMMAND_RECEPTION isRequired is true
 *  - DELEGATED_EXECUTION_AVAILABILITY isRequired is true
 *  - DISCONNECT_RECONNECT isRequired is false
 *  - DEGRADED_OUTCOME_RECORDING isRequired is false
 *  - REQUIRED_SCENARIOS has exactly three entries
 *  - REQUIRED_SCENARIOS contains all three required scenarios
 *
 * ### ScenarioOutcomeStatus — wire values
 *  - PASSED wireValue is "passed"
 *  - FAILED wireValue is "failed"
 *  - SKIPPED wireValue is "skipped"
 *  - TIMED_OUT wireValue is "timed_out"
 *  - All four wire values are distinct
 *  - fromWireValue returns correct status for each wire value
 *  - fromWireValue returns null for unknown value
 *
 * ### RealDeviceParticipantVerificationBridge — constants
 *  - ARTIFACT_REAL_DEVICE_FULLY_VERIFIED is "real_device_fully_verified"
 *  - ARTIFACT_EMULATOR_VERIFIED_ONLY is "emulator_verified_only"
 *  - ARTIFACT_SIMULATED_VERIFIED_ONLY is "simulated_verified_only"
 *  - ARTIFACT_PARTIALLY_VERIFIED is "partially_verified"
 *  - ARTIFACT_STALE_VERIFICATION is "stale_verification"
 *  - ARTIFACT_VERIFICATION_ABSENT is "verification_absent"
 *  - ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE is "verification_blocked_no_device"
 *  - All seven artifact tag constants are distinct
 *  - ALL_ARTIFACT_TAGS has exactly seven entries
 *  - INTRODUCED_PR is 70
 *  - DESCRIPTION is non-blank
 *
 * ### evaluateArtifact — VerificationBlockedNoDevice
 *  - Returns VerificationBlockedNoDevice when verificationKind is NO_DEVICE
 *  - VerificationBlockedNoDevice.artifactTag is ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE
 *  - VerificationBlockedNoDevice.blockedReason is non-blank
 *  - NO_DEVICE takes priority over no outcomes
 *
 * ### evaluateArtifact — VerificationAbsent
 *  - Returns VerificationAbsent when no scenarios recorded and kind is not NO_DEVICE
 *  - VerificationAbsent.artifactTag is ARTIFACT_VERIFICATION_ABSENT
 *  - VerificationAbsent.deviceId matches bridge deviceId
 *  - VerificationAbsent.reason is non-blank
 *
 * ### evaluateArtifact — StaleVerification
 *  - Returns StaleVerification when verificationKind is STALE
 *  - StaleVerification.artifactTag is ARTIFACT_STALE_VERIFICATION
 *  - StaleVerification.deviceId matches bridge deviceId
 *  - StaleVerification.staleReason is non-blank
 *  - Stale takes priority over partial outcomes
 *
 * ### evaluateArtifact — SimulatedVerifiedOnly
 *  - Returns SimulatedVerifiedOnly when SIMULATED kind and all required scenarios pass
 *  - SimulatedVerifiedOnly.artifactTag is ARTIFACT_SIMULATED_VERIFIED_ONLY
 *  - SimulatedVerifiedOnly.passedScenarios includes all three required
 *  - SIMULATED with missing required scenarios returns PartiallyVerified
 *
 * ### evaluateArtifact — PartiallyVerified
 *  - Returns PartiallyVerified when a required scenario is missing for REAL_DEVICE kind
 *  - Returns PartiallyVerified when a required scenario is missing for EMULATOR kind
 *  - PartiallyVerified.artifactTag is ARTIFACT_PARTIALLY_VERIFIED
 *  - PartiallyVerified.missingRequiredScenarios contains the missing scenario
 *  - PartiallyVerified.passedScenarios contains the passed scenarios
 *  - PartiallyVerified.partialReason is non-blank
 *  - PartiallyVerified.deviceId matches bridge deviceId
 *  - All scenarios FAILED produces PartiallyVerified (all required missing)
 *  - Scenario SKIPPED is not counted as passed (still PartiallyVerified)
 *  - Scenario TIMED_OUT is not counted as passed (still PartiallyVerified)
 *
 * ### evaluateArtifact — EmulatorVerifiedOnly
 *  - Returns EmulatorVerifiedOnly when EMULATOR kind and all required scenarios pass
 *  - EmulatorVerifiedOnly.artifactTag is ARTIFACT_EMULATOR_VERIFIED_ONLY
 *  - EmulatorVerifiedOnly.deviceId matches bridge deviceId
 *  - EmulatorVerifiedOnly.participantId matches bridge participantId
 *  - EmulatorVerifiedOnly.passedScenarios includes all three required
 *
 * ### evaluateArtifact — RealDeviceFullyVerified
 *  - Returns RealDeviceFullyVerified when REAL_DEVICE kind and all required scenarios pass
 *  - RealDeviceFullyVerified.artifactTag is ARTIFACT_REAL_DEVICE_FULLY_VERIFIED
 *  - RealDeviceFullyVerified.deviceId matches bridge deviceId
 *  - RealDeviceFullyVerified.participantId matches bridge participantId
 *  - RealDeviceFullyVerified.passedScenarios includes all three required
 *  - Optional scenarios passed alongside required are included in passedScenarios
 *
 * ### recordScenarioOutcome / getScenarioStatus
 *  - getScenarioStatus returns null before recording
 *  - getScenarioStatus returns PASSED after recording PASSED
 *  - getScenarioStatus returns FAILED after recording FAILED
 *  - Recording same scenario twice replaces previous outcome
 *  - clearAllOutcomes resets all scenario outcomes to empty
 *  - clearAllOutcomes resets lifecycleTruthState to null
 *
 * ### buildReport
 *  - buildReport reportId is non-blank
 *  - buildReport deviceId matches bridge deviceId
 *  - buildReport participantId matches bridge participantId
 *  - buildReport verificationKind matches bridge verificationKind
 *  - buildReport overallVerificationArtifact matches evaluateArtifact output type
 *  - buildReport lifecycleTruthState reflects bridge field
 *  - buildReport reportedAtMs matches provided nowMs
 *
 * ### RealDeviceParticipantVerificationReport — helpers
 *  - isCrossRepoConsumable is true for REAL_DEVICE with deviceId and non-absent artifact
 *  - isCrossRepoConsumable is true for EMULATOR with deviceId and non-absent artifact
 *  - isCrossRepoConsumable is false for VerificationAbsent artifact
 *  - isCrossRepoConsumable is false for blank deviceId
 *  - isCrossRepoConsumable is false for SIMULATED kind
 *  - isRealDeviceVerified is true only for REAL_DEVICE + RealDeviceFullyVerified
 *  - isRealDeviceVerified is false for EMULATOR + EmulatorVerifiedOnly
 *
 * ### RealDeviceParticipantVerificationReport — toWireMap
 *  - toWireMap contains schema_version "1.0"
 *  - toWireMap contains report_id matching reportId
 *  - toWireMap contains device_id matching deviceId
 *  - toWireMap contains participant_id matching participantId
 *  - toWireMap contains verification_kind as wire value
 *  - toWireMap contains artifact_tag matching overallVerificationArtifact.artifactTag
 *  - toWireMap contains lifecycle_truth_state as wire value when set
 *  - toWireMap contains lifecycle_truth_state null when not set
 *  - toWireMap contains reported_at_ms
 *  - toWireMap contains is_cross_repo_consumable boolean
 *  - toWireMap contains is_real_device_verified boolean
 *  - toWireMap contains scenario_outcomes map with wire-value keys and values
 *  - toWireMap contains scenario_reasons map only for outcomes with reasons
 */
class Pr70RealDeviceParticipantVerificationBridgeTest {

    private lateinit var bridge: RealDeviceParticipantVerificationBridge

    @Before
    fun setUp() {
        bridge = RealDeviceParticipantVerificationBridge(
            deviceId = "device-70-test",
            participantId = "participant-70-test",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceVerificationKind — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `REAL_DEVICE wireValue is real_device`() {
        assertEquals("real_device", RealDeviceVerificationKind.REAL_DEVICE.wireValue)
    }

    @Test
    fun `EMULATOR wireValue is emulator`() {
        assertEquals("emulator", RealDeviceVerificationKind.EMULATOR.wireValue)
    }

    @Test
    fun `SIMULATED wireValue is simulated`() {
        assertEquals("simulated", RealDeviceVerificationKind.SIMULATED.wireValue)
    }

    @Test
    fun `STALE wireValue is stale`() {
        assertEquals("stale", RealDeviceVerificationKind.STALE.wireValue)
    }

    @Test
    fun `INCOMPLETE wireValue is incomplete`() {
        assertEquals("incomplete", RealDeviceVerificationKind.INCOMPLETE.wireValue)
    }

    @Test
    fun `NO_DEVICE wireValue is no_device`() {
        assertEquals("no_device", RealDeviceVerificationKind.NO_DEVICE.wireValue)
    }

    @Test
    fun `All six verification kind wire values are distinct`() {
        val values = RealDeviceVerificationKind.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly six entries`() {
        assertEquals(6, RealDeviceVerificationKind.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue returns correct kind for each wire value`() {
        RealDeviceVerificationKind.entries.forEach { kind ->
            assertEquals(kind, RealDeviceVerificationKind.fromWireValue(kind.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(RealDeviceVerificationKind.fromWireValue("unknown_kind"))
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertNull(RealDeviceVerificationKind.fromWireValue(null))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceVerificationKind — helpers
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isRealDeviceAuthoritative returns true only for REAL_DEVICE`() {
        assertTrue(RealDeviceVerificationKind.isRealDeviceAuthoritative(RealDeviceVerificationKind.REAL_DEVICE))
        RealDeviceVerificationKind.entries
            .filter { it != RealDeviceVerificationKind.REAL_DEVICE }
            .forEach { kind ->
                assertFalse("Expected false for $kind", RealDeviceVerificationKind.isRealDeviceAuthoritative(kind))
            }
    }

    @Test
    fun `isRealDeviceAuthoritative returns false for EMULATOR`() {
        assertFalse(RealDeviceVerificationKind.isRealDeviceAuthoritative(RealDeviceVerificationKind.EMULATOR))
    }

    @Test
    fun `isUsableEvidence returns true for REAL_DEVICE and EMULATOR`() {
        assertTrue(RealDeviceVerificationKind.isUsableEvidence(RealDeviceVerificationKind.REAL_DEVICE))
        assertTrue(RealDeviceVerificationKind.isUsableEvidence(RealDeviceVerificationKind.EMULATOR))
    }

    @Test
    fun `isUsableEvidence returns false for SIMULATED STALE INCOMPLETE NO_DEVICE`() {
        listOf(
            RealDeviceVerificationKind.SIMULATED,
            RealDeviceVerificationKind.STALE,
            RealDeviceVerificationKind.INCOMPLETE,
            RealDeviceVerificationKind.NO_DEVICE
        ).forEach { kind ->
            assertFalse("Expected false for $kind", RealDeviceVerificationKind.isUsableEvidence(kind))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceVerificationScenario — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `REGISTRATION wireValue is registration`() {
        assertEquals("registration", RealDeviceVerificationScenario.REGISTRATION.wireValue)
    }

    @Test
    fun `COMMAND_RECEPTION wireValue is command_reception`() {
        assertEquals("command_reception", RealDeviceVerificationScenario.COMMAND_RECEPTION.wireValue)
    }

    @Test
    fun `DELEGATED_EXECUTION_AVAILABILITY wireValue is delegated_execution_availability`() {
        assertEquals(
            "delegated_execution_availability",
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY.wireValue
        )
    }

    @Test
    fun `DISCONNECT_RECONNECT wireValue is disconnect_reconnect`() {
        assertEquals("disconnect_reconnect", RealDeviceVerificationScenario.DISCONNECT_RECONNECT.wireValue)
    }

    @Test
    fun `DEGRADED_OUTCOME_RECORDING wireValue is degraded_outcome_recording`() {
        assertEquals(
            "degraded_outcome_recording",
            RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING.wireValue
        )
    }

    @Test
    fun `All five scenario wire values are distinct`() {
        val values = RealDeviceVerificationScenario.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ALL_WIRE_VALUES has exactly five entries`() {
        assertEquals(5, RealDeviceVerificationScenario.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue returns correct scenario for each wire value`() {
        RealDeviceVerificationScenario.entries.forEach { scenario ->
            assertEquals(scenario, RealDeviceVerificationScenario.fromWireValue(scenario.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown scenario value`() {
        assertNull(RealDeviceVerificationScenario.fromWireValue("unknown_scenario"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceVerificationScenario — required scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `REGISTRATION isRequired is true`() {
        assertTrue(RealDeviceVerificationScenario.REGISTRATION.isRequired)
    }

    @Test
    fun `COMMAND_RECEPTION isRequired is true`() {
        assertTrue(RealDeviceVerificationScenario.COMMAND_RECEPTION.isRequired)
    }

    @Test
    fun `DELEGATED_EXECUTION_AVAILABILITY isRequired is true`() {
        assertTrue(RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY.isRequired)
    }

    @Test
    fun `DISCONNECT_RECONNECT isRequired is false`() {
        assertFalse(RealDeviceVerificationScenario.DISCONNECT_RECONNECT.isRequired)
    }

    @Test
    fun `DEGRADED_OUTCOME_RECORDING isRequired is false`() {
        assertFalse(RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING.isRequired)
    }

    @Test
    fun `REQUIRED_SCENARIOS has exactly three entries`() {
        assertEquals(3, RealDeviceVerificationScenario.REQUIRED_SCENARIOS.size)
    }

    @Test
    fun `REQUIRED_SCENARIOS contains all three required scenarios`() {
        assertTrue(RealDeviceVerificationScenario.REGISTRATION in RealDeviceVerificationScenario.REQUIRED_SCENARIOS)
        assertTrue(RealDeviceVerificationScenario.COMMAND_RECEPTION in RealDeviceVerificationScenario.REQUIRED_SCENARIOS)
        assertTrue(RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY in RealDeviceVerificationScenario.REQUIRED_SCENARIOS)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ScenarioOutcomeStatus — wire values
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `PASSED wireValue is passed`() {
        assertEquals("passed", ScenarioOutcomeStatus.PASSED.wireValue)
    }

    @Test
    fun `FAILED wireValue is failed`() {
        assertEquals("failed", ScenarioOutcomeStatus.FAILED.wireValue)
    }

    @Test
    fun `SKIPPED wireValue is skipped`() {
        assertEquals("skipped", ScenarioOutcomeStatus.SKIPPED.wireValue)
    }

    @Test
    fun `TIMED_OUT wireValue is timed_out`() {
        assertEquals("timed_out", ScenarioOutcomeStatus.TIMED_OUT.wireValue)
    }

    @Test
    fun `All four outcome status wire values are distinct`() {
        val values = ScenarioOutcomeStatus.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `ScenarioOutcomeStatus fromWireValue roundtrip for each value`() {
        ScenarioOutcomeStatus.entries.forEach { status ->
            assertEquals(status, ScenarioOutcomeStatus.fromWireValue(status.wireValue))
        }
    }

    @Test
    fun `ScenarioOutcomeStatus fromWireValue returns null for unknown value`() {
        assertNull(ScenarioOutcomeStatus.fromWireValue("unknown_status"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceParticipantVerificationBridge — constants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ARTIFACT_REAL_DEVICE_FULLY_VERIFIED is real_device_fully_verified`() {
        assertEquals(
            "real_device_fully_verified",
            RealDeviceParticipantVerificationBridge.ARTIFACT_REAL_DEVICE_FULLY_VERIFIED
        )
    }

    @Test
    fun `ARTIFACT_EMULATOR_VERIFIED_ONLY is emulator_verified_only`() {
        assertEquals(
            "emulator_verified_only",
            RealDeviceParticipantVerificationBridge.ARTIFACT_EMULATOR_VERIFIED_ONLY
        )
    }

    @Test
    fun `ARTIFACT_SIMULATED_VERIFIED_ONLY is simulated_verified_only`() {
        assertEquals(
            "simulated_verified_only",
            RealDeviceParticipantVerificationBridge.ARTIFACT_SIMULATED_VERIFIED_ONLY
        )
    }

    @Test
    fun `ARTIFACT_PARTIALLY_VERIFIED is partially_verified`() {
        assertEquals(
            "partially_verified",
            RealDeviceParticipantVerificationBridge.ARTIFACT_PARTIALLY_VERIFIED
        )
    }

    @Test
    fun `ARTIFACT_STALE_VERIFICATION is stale_verification`() {
        assertEquals(
            "stale_verification",
            RealDeviceParticipantVerificationBridge.ARTIFACT_STALE_VERIFICATION
        )
    }

    @Test
    fun `ARTIFACT_VERIFICATION_ABSENT is verification_absent`() {
        assertEquals(
            "verification_absent",
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_ABSENT
        )
    }

    @Test
    fun `ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE is verification_blocked_no_device`() {
        assertEquals(
            "verification_blocked_no_device",
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE
        )
    }

    @Test
    fun `All seven artifact tag constants are distinct`() {
        assertEquals(
            7,
            RealDeviceParticipantVerificationBridge.ALL_ARTIFACT_TAGS.size
        )
    }

    @Test
    fun `ALL_ARTIFACT_TAGS has exactly seven entries`() {
        assertEquals(7, RealDeviceParticipantVerificationBridge.ALL_ARTIFACT_TAGS.size)
    }

    @Test
    fun `INTRODUCED_PR is 70`() {
        assertEquals(70, RealDeviceParticipantVerificationBridge.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(RealDeviceParticipantVerificationBridge.DESCRIPTION.isNotBlank())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — VerificationBlockedNoDevice
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns VerificationBlockedNoDevice when verificationKind is NO_DEVICE`() {
        val noDeviceBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceBridge.evaluateArtifact()
        assertTrue(
            "Expected VerificationBlockedNoDevice but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice
        )
    }

    @Test
    fun `VerificationBlockedNoDevice artifactTag is ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE`() {
        val noDeviceBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_BLOCKED_NO_DEVICE,
            artifact.artifactTag
        )
    }

    @Test
    fun `VerificationBlockedNoDevice blockedReason is non-blank`() {
        val noDeviceBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        val artifact = noDeviceBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice
        assertTrue(artifact.blockedReason.isNotBlank())
    }

    @Test
    fun `NO_DEVICE takes priority over no outcomes recorded`() {
        val noDeviceBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "dev-nd",
            verificationKind = RealDeviceVerificationKind.NO_DEVICE
        )
        // No outcomes recorded — but NO_DEVICE must take priority
        val artifact = noDeviceBridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.VerificationBlockedNoDevice)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — VerificationAbsent
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns VerificationAbsent when no scenarios recorded and kind is not NO_DEVICE`() {
        val artifact = bridge.evaluateArtifact()
        assertTrue(
            "Expected VerificationAbsent but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.VerificationAbsent
        )
    }

    @Test
    fun `VerificationAbsent artifactTag is ARTIFACT_VERIFICATION_ABSENT`() {
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.VerificationAbsent
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_VERIFICATION_ABSENT,
            artifact.artifactTag
        )
    }

    @Test
    fun `VerificationAbsent deviceId matches bridge deviceId`() {
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.VerificationAbsent
        assertEquals("device-70-test", artifact.deviceId)
    }

    @Test
    fun `VerificationAbsent reason is non-blank`() {
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.VerificationAbsent
        assertTrue(artifact.reason.isNotBlank())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — StaleVerification
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns StaleVerification when verificationKind is STALE`() {
        val staleBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "device-stale",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        staleBridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = staleBridge.evaluateArtifact()
        assertTrue(
            "Expected StaleVerification but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.StaleVerification
        )
    }

    @Test
    fun `StaleVerification artifactTag is ARTIFACT_STALE_VERIFICATION`() {
        val staleBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "device-stale",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        staleBridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = staleBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.StaleVerification
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_STALE_VERIFICATION,
            artifact.artifactTag
        )
    }

    @Test
    fun `StaleVerification deviceId matches bridge deviceId`() {
        val staleBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "stale-device-id",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        staleBridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = staleBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.StaleVerification
        assertEquals("stale-device-id", artifact.deviceId)
    }

    @Test
    fun `StaleVerification staleReason is non-blank`() {
        val staleBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.STALE
        )
        staleBridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = staleBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.StaleVerification
        assertTrue(artifact.staleReason.isNotBlank())
    }

    @Test
    fun `Stale takes priority over partial outcomes`() {
        val staleBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "dev-stale-partial",
            verificationKind = RealDeviceVerificationKind.STALE
        )
        // Only one scenario recorded (incomplete), but STALE must win
        staleBridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = staleBridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.StaleVerification)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — SimulatedVerifiedOnly
    // ═══════════════════════════════════════════════════════════════════════════

    private fun recordAllRequired(b: RealDeviceParticipantVerificationBridge) {
        b.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        b.recordScenarioOutcome(RealDeviceVerificationScenario.COMMAND_RECEPTION, ScenarioOutcomeStatus.PASSED)
        b.recordScenarioOutcome(
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY,
            ScenarioOutcomeStatus.PASSED
        )
    }

    @Test
    fun `Returns SimulatedVerifiedOnly when SIMULATED kind and all required scenarios pass`() {
        val simBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "sim-device",
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simBridge)
        val artifact = simBridge.evaluateArtifact()
        assertTrue(
            "Expected SimulatedVerifiedOnly but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly
        )
    }

    @Test
    fun `SimulatedVerifiedOnly artifactTag is ARTIFACT_SIMULATED_VERIFIED_ONLY`() {
        val simBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simBridge)
        val artifact = simBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_SIMULATED_VERIFIED_ONLY,
            artifact.artifactTag
        )
    }

    @Test
    fun `SimulatedVerifiedOnly passedScenarios includes all three required`() {
        val simBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simBridge)
        val artifact = simBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.SimulatedVerifiedOnly
        assertTrue(RealDeviceVerificationScenario.REGISTRATION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.COMMAND_RECEPTION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY in artifact.passedScenarios)
    }

    @Test
    fun `SIMULATED with missing required scenarios returns PartiallyVerified`() {
        val simBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "sim-partial",
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        simBridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        // COMMAND_RECEPTION and DELEGATED_EXECUTION_AVAILABILITY not recorded
        val artifact = simBridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.PartiallyVerified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — PartiallyVerified
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns PartiallyVerified when a required scenario is missing for REAL_DEVICE kind`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.COMMAND_RECEPTION, ScenarioOutcomeStatus.PASSED)
        // DELEGATED_EXECUTION_AVAILABILITY not recorded
        val artifact = bridge.evaluateArtifact()
        assertTrue(
            "Expected PartiallyVerified but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.PartiallyVerified
        )
    }

    @Test
    fun `Returns PartiallyVerified when a required scenario is missing for EMULATOR kind`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-partial",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        emuBridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        // Only one scenario
        val artifact = emuBridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.PartiallyVerified)
    }

    @Test
    fun `PartiallyVerified artifactTag is ARTIFACT_PARTIALLY_VERIFIED`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_PARTIALLY_VERIFIED,
            artifact.artifactTag
        )
    }

    @Test
    fun `PartiallyVerified missingRequiredScenarios contains missing scenario`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.COMMAND_RECEPTION, ScenarioOutcomeStatus.PASSED)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertTrue(
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY
                in artifact.missingRequiredScenarios
        )
    }

    @Test
    fun `PartiallyVerified passedScenarios contains passed scenarios`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertTrue(RealDeviceVerificationScenario.REGISTRATION in artifact.passedScenarios)
    }

    @Test
    fun `PartiallyVerified partialReason is non-blank`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertTrue(artifact.partialReason.isNotBlank())
    }

    @Test
    fun `PartiallyVerified deviceId matches bridge deviceId`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertEquals("device-70-test", artifact.deviceId)
    }

    @Test
    fun `All scenarios FAILED produces PartiallyVerified with all required missing`() {
        RealDeviceVerificationScenario.entries.forEach { scenario ->
            bridge.recordScenarioOutcome(scenario, ScenarioOutcomeStatus.FAILED)
        }
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.PartiallyVerified
        assertEquals(
            RealDeviceVerificationScenario.REQUIRED_SCENARIOS,
            artifact.missingRequiredScenarios
        )
    }

    @Test
    fun `Scenario SKIPPED is not counted as passed`() {
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.SKIPPED
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.COMMAND_RECEPTION,
            ScenarioOutcomeStatus.PASSED
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY,
            ScenarioOutcomeStatus.PASSED
        )
        // REGISTRATION is SKIPPED, not PASSED → still partially verified
        val artifact = bridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.PartiallyVerified)
    }

    @Test
    fun `Scenario TIMED_OUT is not counted as passed`() {
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.TIMED_OUT
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.COMMAND_RECEPTION,
            ScenarioOutcomeStatus.PASSED
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = bridge.evaluateArtifact()
        assertTrue(artifact is RealDeviceParticipantVerificationArtifact.PartiallyVerified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — EmulatorVerifiedOnly
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns EmulatorVerifiedOnly when EMULATOR kind and all required scenarios pass`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emulator-device",
            participantId = "emu-participant",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val artifact = emuBridge.evaluateArtifact()
        assertTrue(
            "Expected EmulatorVerifiedOnly but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly
        )
    }

    @Test
    fun `EmulatorVerifiedOnly artifactTag is ARTIFACT_EMULATOR_VERIFIED_ONLY`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-dev",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val artifact = emuBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_EMULATOR_VERIFIED_ONLY,
            artifact.artifactTag
        )
    }

    @Test
    fun `EmulatorVerifiedOnly deviceId matches bridge deviceId`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-device-id",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val artifact = emuBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly
        assertEquals("emu-device-id", artifact.deviceId)
    }

    @Test
    fun `EmulatorVerifiedOnly participantId matches bridge participantId`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-dev",
            participantId = "emu-pid",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val artifact = emuBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly
        assertEquals("emu-pid", artifact.participantId)
    }

    @Test
    fun `EmulatorVerifiedOnly passedScenarios includes all three required`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val artifact = emuBridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.EmulatorVerifiedOnly
        assertTrue(RealDeviceVerificationScenario.REGISTRATION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.COMMAND_RECEPTION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY in artifact.passedScenarios)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateArtifact — RealDeviceFullyVerified
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Returns RealDeviceFullyVerified when REAL_DEVICE kind and all required scenarios pass`() {
        recordAllRequired(bridge)
        val artifact = bridge.evaluateArtifact()
        assertTrue(
            "Expected RealDeviceFullyVerified but got $artifact",
            artifact is RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        )
    }

    @Test
    fun `RealDeviceFullyVerified artifactTag is ARTIFACT_REAL_DEVICE_FULLY_VERIFIED`() {
        recordAllRequired(bridge)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        assertEquals(
            RealDeviceParticipantVerificationBridge.ARTIFACT_REAL_DEVICE_FULLY_VERIFIED,
            artifact.artifactTag
        )
    }

    @Test
    fun `RealDeviceFullyVerified deviceId matches bridge deviceId`() {
        recordAllRequired(bridge)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        assertEquals("device-70-test", artifact.deviceId)
    }

    @Test
    fun `RealDeviceFullyVerified participantId matches bridge participantId`() {
        recordAllRequired(bridge)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        assertEquals("participant-70-test", artifact.participantId)
    }

    @Test
    fun `RealDeviceFullyVerified passedScenarios includes all three required`() {
        recordAllRequired(bridge)
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        assertTrue(RealDeviceVerificationScenario.REGISTRATION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.COMMAND_RECEPTION in artifact.passedScenarios)
        assertTrue(RealDeviceVerificationScenario.DELEGATED_EXECUTION_AVAILABILITY in artifact.passedScenarios)
    }

    @Test
    fun `Optional scenarios passed alongside required are included in passedScenarios`() {
        recordAllRequired(bridge)
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.DISCONNECT_RECONNECT,
            ScenarioOutcomeStatus.PASSED
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.DEGRADED_OUTCOME_RECORDING,
            ScenarioOutcomeStatus.PASSED
        )
        val artifact = bridge.evaluateArtifact() as
            RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified
        assertEquals(5, artifact.passedScenarios.size)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recordScenarioOutcome / getScenarioStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getScenarioStatus returns null before recording`() {
        assertNull(bridge.getScenarioStatus(RealDeviceVerificationScenario.REGISTRATION))
    }

    @Test
    fun `getScenarioStatus returns PASSED after recording PASSED`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            bridge.getScenarioStatus(RealDeviceVerificationScenario.REGISTRATION)
        )
    }

    @Test
    fun `getScenarioStatus returns FAILED after recording FAILED`() {
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.COMMAND_RECEPTION,
            ScenarioOutcomeStatus.FAILED,
            "Gateway rejected command"
        )
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            bridge.getScenarioStatus(RealDeviceVerificationScenario.COMMAND_RECEPTION)
        )
    }

    @Test
    fun `Recording same scenario twice replaces previous outcome`() {
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.FAILED)
        bridge.recordScenarioOutcome(RealDeviceVerificationScenario.REGISTRATION, ScenarioOutcomeStatus.PASSED)
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            bridge.getScenarioStatus(RealDeviceVerificationScenario.REGISTRATION)
        )
    }

    @Test
    fun `clearAllOutcomes resets all scenario outcomes to empty`() {
        recordAllRequired(bridge)
        bridge.clearAllOutcomes()
        assertTrue(bridge.getScenarioOutcomes().isEmpty())
    }

    @Test
    fun `clearAllOutcomes resets lifecycleTruthState to null`() {
        bridge.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        bridge.clearAllOutcomes()
        assertNull(bridge.lifecycleTruthState)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildReport
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildReport reportId is non-blank`() {
        val report = bridge.buildReport()
        assertTrue(report.reportId.isNotBlank())
    }

    @Test
    fun `buildReport deviceId matches bridge deviceId`() {
        val report = bridge.buildReport()
        assertEquals("device-70-test", report.deviceId)
    }

    @Test
    fun `buildReport participantId matches bridge participantId`() {
        val report = bridge.buildReport()
        assertEquals("participant-70-test", report.participantId)
    }

    @Test
    fun `buildReport verificationKind matches bridge verificationKind`() {
        val report = bridge.buildReport()
        assertEquals(RealDeviceVerificationKind.REAL_DEVICE, report.verificationKind)
    }

    @Test
    fun `buildReport overallVerificationArtifact matches evaluateArtifact output type when absent`() {
        val report = bridge.buildReport()
        assertTrue(report.overallVerificationArtifact is RealDeviceParticipantVerificationArtifact.VerificationAbsent)
    }

    @Test
    fun `buildReport overallVerificationArtifact is RealDeviceFullyVerified when all required pass`() {
        recordAllRequired(bridge)
        val report = bridge.buildReport()
        assertTrue(report.overallVerificationArtifact is RealDeviceParticipantVerificationArtifact.RealDeviceFullyVerified)
    }

    @Test
    fun `buildReport lifecycleTruthState reflects bridge field`() {
        bridge.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        val report = bridge.buildReport()
        assertEquals(ParticipantLifecycleTruthState.ACTIVE, report.lifecycleTruthState)
    }

    @Test
    fun `buildReport lifecycleTruthState is null when not set`() {
        val report = bridge.buildReport()
        assertNull(report.lifecycleTruthState)
    }

    @Test
    fun `buildReport reportedAtMs matches provided nowMs`() {
        val nowMs = 1_700_000_000_000L
        val report = bridge.buildReport(nowMs = nowMs)
        assertEquals(nowMs, report.reportedAtMs)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceParticipantVerificationReport — helpers
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `isCrossRepoConsumable is true for REAL_DEVICE with deviceId and non-absent artifact`() {
        recordAllRequired(bridge)
        val report = bridge.buildReport()
        assertTrue(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable is true for EMULATOR with deviceId and non-absent artifact`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-dev",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val report = emuBridge.buildReport()
        assertTrue(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable is false for VerificationAbsent artifact`() {
        // No outcomes → VerificationAbsent
        val report = bridge.buildReport()
        assertFalse(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable is false for blank deviceId`() {
        val blankDeviceBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        recordAllRequired(blankDeviceBridge)
        val report = blankDeviceBridge.buildReport()
        assertFalse(report.isCrossRepoConsumable)
    }

    @Test
    fun `isCrossRepoConsumable is false for SIMULATED kind`() {
        val simBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "sim-dev",
            verificationKind = RealDeviceVerificationKind.SIMULATED
        )
        recordAllRequired(simBridge)
        val report = simBridge.buildReport()
        assertFalse(report.isCrossRepoConsumable)
    }

    @Test
    fun `isRealDeviceVerified is true only for REAL_DEVICE plus RealDeviceFullyVerified`() {
        recordAllRequired(bridge)
        val report = bridge.buildReport()
        assertTrue(report.isRealDeviceVerified)
    }

    @Test
    fun `isRealDeviceVerified is false for EMULATOR plus EmulatorVerifiedOnly`() {
        val emuBridge = RealDeviceParticipantVerificationBridge(
            deviceId = "emu-dev",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        recordAllRequired(emuBridge)
        val report = emuBridge.buildReport()
        assertFalse(report.isRealDeviceVerified)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceParticipantVerificationReport — toWireMap
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap contains schema_version 1_0`() {
        val report = bridge.buildReport()
        assertEquals("1.0", report.toWireMap()["schema_version"])
    }

    @Test
    fun `toWireMap contains report_id matching reportId`() {
        val report = bridge.buildReport()
        assertEquals(report.reportId, report.toWireMap()["report_id"])
    }

    @Test
    fun `toWireMap contains device_id matching deviceId`() {
        val report = bridge.buildReport()
        assertEquals("device-70-test", report.toWireMap()["device_id"])
    }

    @Test
    fun `toWireMap contains participant_id matching participantId`() {
        val report = bridge.buildReport()
        assertEquals("participant-70-test", report.toWireMap()["participant_id"])
    }

    @Test
    fun `toWireMap contains verification_kind as wire value`() {
        val report = bridge.buildReport()
        assertEquals("real_device", report.toWireMap()["verification_kind"])
    }

    @Test
    fun `toWireMap contains artifact_tag matching overallVerificationArtifact artifactTag`() {
        val report = bridge.buildReport()
        assertEquals(
            report.overallVerificationArtifact.artifactTag,
            report.toWireMap()["artifact_tag"]
        )
    }

    @Test
    fun `toWireMap contains lifecycle_truth_state as wire value when set`() {
        bridge.lifecycleTruthState = ParticipantLifecycleTruthState.ACTIVE
        val report = bridge.buildReport()
        assertEquals("active", report.toWireMap()["lifecycle_truth_state"])
    }

    @Test
    fun `toWireMap contains lifecycle_truth_state null when not set`() {
        val report = bridge.buildReport()
        assertNull(report.toWireMap()["lifecycle_truth_state"])
    }

    @Test
    fun `toWireMap contains reported_at_ms`() {
        val nowMs = 1_700_000_000_000L
        val report = bridge.buildReport(nowMs = nowMs)
        assertEquals(nowMs, report.toWireMap()["reported_at_ms"])
    }

    @Test
    fun `toWireMap contains is_cross_repo_consumable boolean`() {
        val report = bridge.buildReport()
        assertNotNull(report.toWireMap()["is_cross_repo_consumable"])
        assertTrue(report.toWireMap()["is_cross_repo_consumable"] is Boolean)
    }

    @Test
    fun `toWireMap contains is_real_device_verified boolean`() {
        val report = bridge.buildReport()
        assertNotNull(report.toWireMap()["is_real_device_verified"])
        assertTrue(report.toWireMap()["is_real_device_verified"] is Boolean)
    }

    @Test
    fun `toWireMap contains scenario_outcomes map with wire-value keys and values`() {
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        val report = bridge.buildReport()
        @Suppress("UNCHECKED_CAST")
        val scenarioOutcomes = report.toWireMap()["scenario_outcomes"] as Map<String, String>
        assertEquals("passed", scenarioOutcomes["registration"])
    }

    @Test
    fun `toWireMap contains scenario_reasons map only for outcomes with reasons`() {
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.REGISTRATION,
            ScenarioOutcomeStatus.PASSED
        )
        bridge.recordScenarioOutcome(
            RealDeviceVerificationScenario.COMMAND_RECEPTION,
            ScenarioOutcomeStatus.FAILED,
            "Gateway timeout"
        )
        val report = bridge.buildReport()
        @Suppress("UNCHECKED_CAST")
        val reasons = report.toWireMap()["scenario_reasons"] as Map<String, String>
        assertFalse("registration" in reasons)
        assertEquals("Gateway timeout", reasons["command_reception"])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RealDeviceParticipantVerificationReport — constants
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `RealDeviceParticipantVerificationReport SCHEMA_VERSION is 1_0`() {
        assertEquals("1.0", RealDeviceParticipantVerificationReport.SCHEMA_VERSION)
    }

    @Test
    fun `RealDeviceParticipantVerificationReport INTRODUCED_PR is 70`() {
        assertEquals(70, RealDeviceParticipantVerificationReport.INTRODUCED_PR)
    }

    @Test
    fun `RealDeviceParticipantVerificationReport DESCRIPTION is non-blank`() {
        assertTrue(RealDeviceParticipantVerificationReport.DESCRIPTION.isNotBlank())
    }
}
