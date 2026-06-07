package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 (Android companion) — Capability honesty guard under multi-device orchestration.
 *
 * Acceptance and regression test suite for [CapabilityHonestyGuard]:
 *
 *  1. [CapabilityHonestyGuard.BASE_CAPABILITIES] — constant set and count.
 *
 *  2. [CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE] — constant value.
 *
 *  3. [CapabilityHonestyGuard.CapabilityReport] — construction and field access.
 *
 *  4. [CapabilityHonestyGuard.HonestyViolation] — construction and field access.
 *
 *  5. [CapabilityHonestyGuard.computeAllowedCapabilities] — correct capability sets
 *     for every (OrchestrationState × LocalIntelligenceCapabilityStatus) combination.
 *
 *  6. [CapabilityHonestyGuard.isHonest] — honest reports return empty list;
 *     dishonest reports return violations with correct metadata.
 *
 *  7. [CapabilityHonestyGuard.inferenceCapabilityAllowed] — allowed only for
 *     CONNECTED + ACTIVE combination.
 *
 *  8. [CapabilityHonestyGuard.anyCapabilityAllowed] — allowed for CONNECTED,
 *     DEGRADED, CAPABILITY_LIMITED; not for RECOVERING, RECONNECTING, DISCONNECTED.
 *
 * ## Test matrix
 *
 * ### BASE_CAPABILITIES — constants
 *  - BASE_CAPABILITY_COUNT is 4
 *  - BASE_CAPABILITIES has exactly BASE_CAPABILITY_COUNT entries
 *  - BASE_CAPABILITIES contains autonomous_goal_execution
 *  - BASE_CAPABILITIES contains local_task_planning
 *  - BASE_CAPABILITIES contains local_ui_reasoning
 *  - BASE_CAPABILITIES contains cross_device_coordination
 *  - CAPABILITY_LOCAL_MODEL_INFERENCE is "local_model_inference"
 *
 * ### computeAllowedCapabilities — CONNECTED state
 *  - CONNECTED + ACTIVE → base + inference
 *  - CONNECTED + DEGRADED → base only
 *  - CONNECTED + DISABLED → base only
 *  - CONNECTED + RECOVERING → base only
 *  - CONNECTED + UNAVAILABLE → base only
 *
 * ### computeAllowedCapabilities — non-CONNECTED states
 *  - DEGRADED → base only (no inference)
 *  - CAPABILITY_LIMITED → base only (no inference)
 *  - RECOVERING → empty set
 *  - RECONNECTING → empty set
 *  - DISCONNECTED → empty set
 *
 * ### isHonest — honest reports
 *  - CONNECTED + ACTIVE + base + inference → no violations
 *  - CONNECTED + ACTIVE + base only → no violations (subset is honest)
 *  - CONNECTED + DEGRADED + base only → no violations
 *  - DEGRADED + base only → no violations
 *  - RECOVERING + empty → no violations
 *  - DISCONNECTED + empty → no violations
 *
 * ### isHonest — dishonest reports
 *  - DISCONNECTED + base caps → violation per disallowed cap
 *  - RECONNECTING + inference → violation for inference
 *  - RECOVERING + any cap → violations for each cap
 *  - DEGRADED + inference → violation for inference
 *  - CAPABILITY_LIMITED + inference → violation for inference
 *  - CONNECTED + DEGRADED + inference → violation for inference
 *
 * ### isHonest — violation metadata
 *  - violatingCapability matches the disallowed capability
 *  - orchestrationState in violation matches the report state
 *  - inferenceStatus in violation matches the report status
 *  - reason is non-blank
 *
 * ### inferenceCapabilityAllowed — per-state
 *  - CONNECTED + ACTIVE → true
 *  - CONNECTED + DEGRADED → false
 *  - DEGRADED + ACTIVE → false (state blocks inference)
 *  - RECOVERING + ACTIVE → false
 *  - RECONNECTING + ACTIVE → false
 *  - DISCONNECTED + ACTIVE → false
 *
 * ### anyCapabilityAllowed — per-state
 *  - CONNECTED → true
 *  - DEGRADED → true
 *  - CAPABILITY_LIMITED → true
 *  - RECOVERING → false
 *  - RECONNECTING → false
 *  - DISCONNECTED → false
 *
 * ### Invariant constants
 *  - INTRODUCED_PR is 7
 *  - INTRODUCED_PR_TITLE is non-blank
 */
class Pr7CapabilityHonestyGuardTest {

    private val G = CapabilityHonestyGuard
    private val S = MultiDeviceParticipantOrchestrationState.OrchestrationState

    // ── BASE_CAPABILITIES — constants ─────────────────────────────────────────

    @Test
    fun `BASE_CAPABILITY_COUNT is 4`() {
        assertEquals(4, G.BASE_CAPABILITY_COUNT)
    }

    @Test
    fun `BASE_CAPABILITIES has exactly BASE_CAPABILITY_COUNT entries`() {
        assertEquals(G.BASE_CAPABILITY_COUNT, G.BASE_CAPABILITIES.size)
    }

    @Test
    fun `BASE_CAPABILITIES contains autonomous_goal_execution`() {
        assertTrue(G.BASE_CAPABILITIES.contains("autonomous_goal_execution"))
    }

    @Test
    fun `BASE_CAPABILITIES contains local_task_planning`() {
        assertTrue(G.BASE_CAPABILITIES.contains("local_task_planning"))
    }

    @Test
    fun `BASE_CAPABILITIES contains local_ui_reasoning`() {
        assertTrue(G.BASE_CAPABILITIES.contains("local_ui_reasoning"))
    }

    @Test
    fun `BASE_CAPABILITIES contains cross_device_coordination`() {
        assertTrue(G.BASE_CAPABILITIES.contains("cross_device_coordination"))
    }

    @Test
    fun `CAPABILITY_LOCAL_MODEL_INFERENCE is local_model_inference`() {
        assertEquals("local_model_inference", G.CAPABILITY_LOCAL_MODEL_INFERENCE)
    }

    // ── computeAllowedCapabilities — CONNECTED state ──────────────────────────

    @Test
    fun `CONNECTED plus ACTIVE allows base plus inference`() {
        val allowed = G.computeAllowedCapabilities(S.CONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertTrue(allowed.containsAll(G.BASE_CAPABILITIES))
        assertEquals(G.BASE_CAPABILITY_COUNT + 1, allowed.size)
    }

    @Test
    fun `CONNECTED plus DEGRADED allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.CONNECTED, LocalIntelligenceCapabilityStatus.DEGRADED)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    @Test
    fun `CONNECTED plus DISABLED allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.CONNECTED, LocalIntelligenceCapabilityStatus.DISABLED)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    @Test
    fun `CONNECTED plus RECOVERING inference status allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.CONNECTED, LocalIntelligenceCapabilityStatus.RECOVERING)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    @Test
    fun `CONNECTED plus UNAVAILABLE allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.CONNECTED, LocalIntelligenceCapabilityStatus.UNAVAILABLE)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    // ── computeAllowedCapabilities — non-CONNECTED states ────────────────────

    @Test
    fun `DEGRADED allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.DEGRADED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    @Test
    fun `CAPABILITY_LIMITED allows base only`() {
        val allowed = G.computeAllowedCapabilities(S.CAPABILITY_LIMITED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertFalse(allowed.contains(G.CAPABILITY_LOCAL_MODEL_INFERENCE))
        assertEquals(G.BASE_CAPABILITIES, allowed)
    }

    @Test
    fun `RECOVERING allows empty set`() {
        val allowed = G.computeAllowedCapabilities(S.RECOVERING, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `RECONNECTING allows empty set`() {
        val allowed = G.computeAllowedCapabilities(S.RECONNECTING, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `DISCONNECTED allows empty set`() {
        val allowed = G.computeAllowedCapabilities(S.DISCONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE)
        assertTrue(allowed.isEmpty())
    }

    // ── isHonest — honest reports ─────────────────────────────────────────────

    @Test
    fun `CONNECTED ACTIVE with base plus inference is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = S.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue("CONNECTED+ACTIVE full capability should be honest", G.isHonest(report).isEmpty())
    }

    @Test
    fun `CONNECTED ACTIVE with base only is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = S.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue("Subset of allowed capabilities should be honest", G.isHonest(report).isEmpty())
    }

    @Test
    fun `CONNECTED DEGRADED with base only is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = S.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `DEGRADED with base only is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = S.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `RECOVERING with empty capabilities is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = emptySet(),
            orchestrationState = S.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    @Test
    fun `DISCONNECTED with empty capabilities is honest`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = emptySet(),
            orchestrationState = S.DISCONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED
        )
        assertTrue(G.isHonest(report).isEmpty())
    }

    // ── isHonest — dishonest reports ──────────────────────────────────────────

    @Test
    fun `DISCONNECTED with base caps produces violations`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES,
            orchestrationState = S.DISCONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DISABLED
        )
        val violations = G.isHonest(report)
        assertFalse("DISCONNECTED advertising base caps must have violations", violations.isEmpty())
        assertEquals(G.BASE_CAPABILITY_COUNT, violations.size)
    }

    @Test
    fun `RECONNECTING with inference produces violation for inference`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.RECONNECTING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertFalse("RECONNECTING advertising inference must have violations", violations.isEmpty())
        assertEquals(G.CAPABILITY_LOCAL_MODEL_INFERENCE, violations.first().violatingCapability)
    }

    @Test
    fun `RECOVERING with any capability produces violations`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf("autonomous_goal_execution"),
            orchestrationState = S.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertFalse("RECOVERING advertising any capability must have violations", violations.isEmpty())
    }

    @Test
    fun `DEGRADED with inference produces violation for inference`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = S.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertFalse("DEGRADED advertising inference must have violations", violations.isEmpty())
        assertTrue(violations.any { it.violatingCapability == G.CAPABILITY_LOCAL_MODEL_INFERENCE })
    }

    @Test
    fun `CAPABILITY_LIMITED with inference produces violation`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.CAPABILITY_LIMITED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        val violations = G.isHonest(report)
        assertFalse(violations.isEmpty())
        assertEquals(G.CAPABILITY_LOCAL_MODEL_INFERENCE, violations.first().violatingCapability)
    }

    @Test
    fun `CONNECTED DEGRADED with inference produces violation`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = G.BASE_CAPABILITIES + G.CAPABILITY_LOCAL_MODEL_INFERENCE,
            orchestrationState = S.CONNECTED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        val violations = G.isHonest(report)
        assertFalse(violations.isEmpty())
        assertTrue(violations.any { it.violatingCapability == G.CAPABILITY_LOCAL_MODEL_INFERENCE })
    }

    // ── isHonest — violation metadata ─────────────────────────────────────────

    @Test
    fun `violation violatingCapability matches the disallowed capability`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertEquals(G.CAPABILITY_LOCAL_MODEL_INFERENCE, violations.first().violatingCapability)
    }

    @Test
    fun `violation orchestrationState matches report state`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.RECONNECTING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertEquals(S.RECONNECTING, violations.first().orchestrationState)
    }

    @Test
    fun `violation inferenceStatus matches report status`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.DEGRADED,
            inferenceStatus = LocalIntelligenceCapabilityStatus.DEGRADED
        )
        val violations = G.isHonest(report)
        assertEquals(LocalIntelligenceCapabilityStatus.DEGRADED, violations.first().inferenceStatus)
    }

    @Test
    fun `violation reason is non-blank`() {
        val report = G.CapabilityReport(
            advertisedCapabilities = setOf(G.CAPABILITY_LOCAL_MODEL_INFERENCE),
            orchestrationState = S.RECOVERING,
            inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE
        )
        val violations = G.isHonest(report)
        assertTrue(violations.first().reason.isNotBlank())
    }

    // ── inferenceCapabilityAllowed ────────────────────────────────────────────

    @Test
    fun `inferenceCapabilityAllowed CONNECTED ACTIVE is true`() {
        assertTrue(G.inferenceCapabilityAllowed(S.CONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE))
    }

    @Test
    fun `inferenceCapabilityAllowed CONNECTED DEGRADED is false`() {
        assertFalse(G.inferenceCapabilityAllowed(S.CONNECTED, LocalIntelligenceCapabilityStatus.DEGRADED))
    }

    @Test
    fun `inferenceCapabilityAllowed DEGRADED ACTIVE is false`() {
        assertFalse(G.inferenceCapabilityAllowed(S.DEGRADED, LocalIntelligenceCapabilityStatus.ACTIVE))
    }

    @Test
    fun `inferenceCapabilityAllowed RECOVERING ACTIVE is false`() {
        assertFalse(G.inferenceCapabilityAllowed(S.RECOVERING, LocalIntelligenceCapabilityStatus.ACTIVE))
    }

    @Test
    fun `inferenceCapabilityAllowed RECONNECTING ACTIVE is false`() {
        assertFalse(G.inferenceCapabilityAllowed(S.RECONNECTING, LocalIntelligenceCapabilityStatus.ACTIVE))
    }

    @Test
    fun `inferenceCapabilityAllowed DISCONNECTED ACTIVE is false`() {
        assertFalse(G.inferenceCapabilityAllowed(S.DISCONNECTED, LocalIntelligenceCapabilityStatus.ACTIVE))
    }

    // ── anyCapabilityAllowed ──────────────────────────────────────────────────

    @Test
    fun `anyCapabilityAllowed CONNECTED is true`() {
        assertTrue(G.anyCapabilityAllowed(S.CONNECTED))
    }

    @Test
    fun `anyCapabilityAllowed DEGRADED is true`() {
        assertTrue(G.anyCapabilityAllowed(S.DEGRADED))
    }

    @Test
    fun `anyCapabilityAllowed CAPABILITY_LIMITED is true`() {
        assertTrue(G.anyCapabilityAllowed(S.CAPABILITY_LIMITED))
    }

    @Test
    fun `anyCapabilityAllowed RECOVERING is false`() {
        assertFalse(G.anyCapabilityAllowed(S.RECOVERING))
    }

    @Test
    fun `anyCapabilityAllowed RECONNECTING is false`() {
        assertFalse(G.anyCapabilityAllowed(S.RECONNECTING))
    }

    @Test
    fun `anyCapabilityAllowed DISCONNECTED is false`() {
        assertFalse(G.anyCapabilityAllowed(S.DISCONNECTED))
    }

    // ── Invariant constants ───────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 7`() {
        assertEquals(7, G.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR_TITLE is non-blank`() {
        assertTrue(G.INTRODUCED_PR_TITLE.isNotBlank())
    }
}
