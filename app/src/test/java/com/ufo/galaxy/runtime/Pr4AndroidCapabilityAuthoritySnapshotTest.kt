package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-4 (Android) — Capability authority snapshot contract tests.
 *
 * Validates [CapabilityAuthoritySnapshot] as the authoritative, versioned capability
 * reporting contract that Android provides to V2 for dispatch scoring.
 *
 * ## Test domains
 *
 * ### 1. Schema version stability
 *  - SCHEMA_VERSION is a non-empty stable string.
 *  - Every snapshot carries the same SCHEMA_VERSION.
 *
 * ### 2. Per-subsystem readiness (planner_ready / grounding_ready)
 *  - Running + both HEALTHY → planner_ready=true, grounding_ready=true.
 *  - Running + planner UNHEALTHY → planner_ready=false, grounding_ready=true.
 *  - Running + grounding UNHEALTHY → planner_ready=true, grounding_ready=false.
 *  - Degraded → correctly reflects snapshot health.
 *  - PartialReady → planner_ready=true, grounding_ready=false.
 *  - Stopped/Failed/SafeMode/FailedStartup → both false.
 *  - Starting → both false.
 *  - Recovering → both false.
 *
 * ### 3. Local intelligence status wire value
 *  - Running → "active".
 *  - Degraded → "degraded".
 *  - PartialReady → "degraded".
 *  - Starting / Stopped / Failed / FailedStartup / SafeMode → "disabled".
 *  - Recovering → "recovering".
 *  - Unavailable → "unavailable".
 *
 * ### 4. Inference ready derivation
 *  - inferenceReady = (plannerReady || groundingReady) && checksumValid.
 *  - checksumValid=false suppresses inferenceReady even when components are healthy.
 *  - inferenceReady=false when neither component is ready.
 *
 * ### 5. Checksum gate
 *  - checksumValid=false → inferenceReady=false regardless of component health.
 *  - checksumValid=false with Running state → authority label degrades.
 *
 * ### 6. Authority label derivation
 *  - Running + both healthy + checksum valid → "authoritative".
 *  - Running + both healthy + checksum invalid → "degraded_authority".
 *  - Running + one component unhealthy → "degraded_authority".
 *  - Degraded → "degraded_authority".
 *  - Stopped/Failed/SafeMode/FailedStartup → "no_authority".
 *  - Starting / Recovering / PartialReady → "pending".
 *
 * ### 7. Capability list consistency
 *  - inferenceReady=true → "local_model_inference" is in capabilityList.
 *  - inferenceReady=false → "local_model_inference" is NOT in capabilityList.
 *  - Base capabilities always present when inference-ready or not.
 *  - Base capability count is 4 (matching CapabilityHonestyGuard.BASE_CAPABILITY_COUNT).
 *
 * ### 8. Wire encoding (toMap)
 *  - All required keys present in toMap() output.
 *  - Values match snapshot properties.
 *
 * ### 9. Authority label × inference readiness consistency
 *  - "authoritative" → inferenceReady=true.
 *  - "no_authority" → inferenceReady=false.
 *  - "pending" → inferenceReady=false.
 *  - "degraded_authority" → no constraint on inferenceReady (can be true or false).
 *
 * ### 10. All ManagerState variants covered
 *  - from() handles every ManagerState without throwing.
 */
class Pr4AndroidCapabilityAuthoritySnapshotTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun healthySnapshot() = RuntimeHealthSnapshot(
        plannerHealth  = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
        groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
    )

    private fun plannerUnhealthySnapshot() = RuntimeHealthSnapshot(
        plannerHealth  = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
        groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
    )

    private fun groundingUnhealthySnapshot() = RuntimeHealthSnapshot(
        plannerHealth  = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
        groundingHealth = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY
    )

    private fun bothUnhealthySnapshot() = RuntimeHealthSnapshot(
        plannerHealth  = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
        groundingHealth = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY
    )

    private fun build(
        state: LocalInferenceRuntimeManager.ManagerState,
        checksumValid: Boolean = true
    ) = CapabilityAuthoritySnapshot.from(state, checksumValid)

    // ── 1. Schema version stability ───────────────────────────────────────────

    @Test
    fun `SCHEMA_VERSION is a non-empty string`() {
        assertTrue(
            "SCHEMA_VERSION must be non-empty",
            CapabilityAuthoritySnapshot.SCHEMA_VERSION.isNotBlank()
        )
    }

    @Test
    fun `every snapshot carries the schema version constant`() {
        val snapshot = build(LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()))
        assertEquals(
            "snapshot.schemaVersion must equal SCHEMA_VERSION",
            CapabilityAuthoritySnapshot.SCHEMA_VERSION,
            snapshot.schemaVersion
        )
    }

    @Test
    fun `schema version is the same across different manager states`() {
        val states = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            LocalInferenceRuntimeManager.ManagerState.Failed("crash")
        )
        val versions = states.map { build(it).schemaVersion }.distinct()
        assertEquals("All states must produce the same schema version", 1, versions.size)
        assertEquals(CapabilityAuthoritySnapshot.SCHEMA_VERSION, versions.first())
    }

    // ── 2. Per-subsystem readiness ────────────────────────────────────────────

    @Test
    fun `Running with both HEALTHY gives plannerReady=true and groundingReady=true`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()))
        assertTrue("plannerReady must be true when planner HEALTHY", snap.plannerReady)
        assertTrue("groundingReady must be true when grounding HEALTHY", snap.groundingReady)
    }

    @Test
    fun `Running with planner UNHEALTHY gives plannerReady=false groundingReady=true`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Running(plannerUnhealthySnapshot()))
        assertFalse("plannerReady must be false when planner UNHEALTHY", snap.plannerReady)
        assertTrue("groundingReady must be true when grounding HEALTHY", snap.groundingReady)
    }

    @Test
    fun `Running with grounding UNHEALTHY gives plannerReady=true groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Running(groundingUnhealthySnapshot()))
        assertTrue("plannerReady must be true when planner HEALTHY", snap.plannerReady)
        assertFalse("groundingReady must be false when grounding UNHEALTHY", snap.groundingReady)
    }

    @Test
    fun `Degraded state reflects snapshot health for planner and grounding`() {
        val degradedPlannerDown = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "planner down")
        )
        assertFalse("plannerReady must be false in Degraded with planner UNHEALTHY",
            degradedPlannerDown.plannerReady)
        assertTrue("groundingReady must be true in Degraded with grounding HEALTHY",
            degradedPlannerDown.groundingReady)

        val degradedGroundingDown = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(groundingUnhealthySnapshot(), "grounding down")
        )
        assertTrue("plannerReady must be true in Degraded with planner HEALTHY",
            degradedGroundingDown.plannerReady)
        assertFalse("groundingReady must be false in Degraded with grounding UNHEALTHY",
            degradedGroundingDown.groundingReady)
    }

    @Test
    fun `PartialReady gives plannerReady=true groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding")))
        assertTrue("plannerReady must be true in PartialReady (planner loaded)", snap.plannerReady)
        assertFalse("groundingReady must be false in PartialReady (grounding still starting)",
            snap.groundingReady)
    }

    @Test
    fun `Stopped state gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Stopped)
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    @Test
    fun `Failed state gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Failed("crash"))
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    @Test
    fun `SafeMode gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.SafeMode)
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    @Test
    fun `FailedStartup gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.FailedStartup(
            "both failed",
            com.ufo.galaxy.runtime.RuntimeStartResult.StartStage.HEALTH_CHECK
        ))
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    @Test
    fun `Starting gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Starting)
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    @Test
    fun `Recovering gives plannerReady=false groundingReady=false`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Recovering)
        assertFalse(snap.plannerReady)
        assertFalse(snap.groundingReady)
    }

    // ── 3. Local intelligence status wire value ───────────────────────────────

    @Test
    fun `Running gives local_intelligence_status = active`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()))
        assertEquals("active", snap.localIntelligenceStatus)
    }

    @Test
    fun `Degraded gives local_intelligence_status = degraded`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "partial")
        )
        assertEquals("degraded", snap.localIntelligenceStatus)
    }

    @Test
    fun `PartialReady gives local_intelligence_status = degraded`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding")))
        assertEquals("degraded", snap.localIntelligenceStatus)
    }

    @Test
    fun `Stopped gives local_intelligence_status = disabled`() {
        assertEquals("disabled",
            build(LocalInferenceRuntimeManager.ManagerState.Stopped).localIntelligenceStatus)
    }

    @Test
    fun `Failed gives local_intelligence_status = disabled`() {
        assertEquals("disabled",
            build(LocalInferenceRuntimeManager.ManagerState.Failed("crash")).localIntelligenceStatus)
    }

    @Test
    fun `SafeMode gives local_intelligence_status = disabled`() {
        assertEquals("disabled",
            build(LocalInferenceRuntimeManager.ManagerState.SafeMode).localIntelligenceStatus)
    }

    @Test
    fun `FailedStartup gives local_intelligence_status = disabled`() {
        assertEquals("disabled",
            build(LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "both failed",
                com.ufo.galaxy.runtime.RuntimeStartResult.StartStage.HEALTH_CHECK
            )).localIntelligenceStatus)
    }

    @Test
    fun `Recovering gives local_intelligence_status = recovering`() {
        assertEquals("recovering",
            build(LocalInferenceRuntimeManager.ManagerState.Recovering).localIntelligenceStatus)
    }

    @Test
    fun `Unavailable gives local_intelligence_status = unavailable`() {
        assertEquals("unavailable",
            build(LocalInferenceRuntimeManager.ManagerState.Unavailable("crash")).localIntelligenceStatus)
    }

    // ── 4. Inference ready derivation ─────────────────────────────────────────

    @Test
    fun `inferenceReady is true when both components healthy and checksum valid`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertTrue("inferenceReady must be true: both healthy + checksum valid", snap.inferenceReady)
    }

    @Test
    fun `inferenceReady is false when checksum is invalid even with healthy components`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = false
        )
        assertFalse("inferenceReady must be false when checksum is invalid", snap.inferenceReady)
    }

    @Test
    fun `inferenceReady is false when neither component is ready`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            checksumValid = true
        )
        assertFalse("inferenceReady must be false when no component is ready", snap.inferenceReady)
    }

    @Test
    fun `inferenceReady is true for degraded with one healthy component and valid checksum`() {
        // Grounding healthy, planner unhealthy — still usable for some inference
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "planner down"),
            checksumValid = true
        )
        assertTrue("inferenceReady must be true when at least one component is ready", snap.inferenceReady)
    }

    @Test
    fun `inferenceReady is false when both Degraded components are unhealthy`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(bothUnhealthySnapshot(), "both down"),
            checksumValid = true
        )
        assertFalse("inferenceReady must be false when all components are unhealthy", snap.inferenceReady)
    }

    // ── 5. Checksum gate ──────────────────────────────────────────────────────

    @Test
    fun `checksumValid=false propagates correctly to snapshot`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = false
        )
        assertFalse("checksumValid must be false in snapshot when false was passed", snap.checksumValid)
    }

    @Test
    fun `checksumValid=true propagates correctly to snapshot`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertTrue("checksumValid must be true in snapshot when true was passed", snap.checksumValid)
    }

    @Test
    fun `Running with healthy components but invalid checksum has degraded_authority label`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = false
        )
        assertEquals(
            "Running + healthy + invalid checksum must be degraded_authority",
            CapabilityAuthoritySnapshot.AUTHORITY_LABEL_DEGRADED,
            snap.authorityLabel
        )
    }

    // ── 6. Authority label derivation ─────────────────────────────────────────

    @Test
    fun `Running both healthy and checksum valid gives authoritative label`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_AUTHORITATIVE, snap.authorityLabel)
    }

    @Test
    fun `Running one component unhealthy gives degraded_authority`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(plannerUnhealthySnapshot()),
            checksumValid = true
        )
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_DEGRADED, snap.authorityLabel)
    }

    @Test
    fun `Degraded state gives degraded_authority`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "partial"),
            checksumValid = true
        )
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_DEGRADED, snap.authorityLabel)
    }

    @Test
    fun `Stopped gives no_authority`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE,
            build(LocalInferenceRuntimeManager.ManagerState.Stopped).authorityLabel)
    }

    @Test
    fun `Failed gives no_authority`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE,
            build(LocalInferenceRuntimeManager.ManagerState.Failed("crash")).authorityLabel)
    }

    @Test
    fun `SafeMode gives no_authority`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE,
            build(LocalInferenceRuntimeManager.ManagerState.SafeMode).authorityLabel)
    }

    @Test
    fun `FailedStartup gives no_authority`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE,
            build(LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "both failed",
                com.ufo.galaxy.runtime.RuntimeStartResult.StartStage.HEALTH_CHECK
            )).authorityLabel)
    }

    @Test
    fun `Starting gives pending label`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING,
            build(LocalInferenceRuntimeManager.ManagerState.Starting).authorityLabel)
    }

    @Test
    fun `Recovering gives pending label`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING,
            build(LocalInferenceRuntimeManager.ManagerState.Recovering).authorityLabel)
    }

    @Test
    fun `PartialReady gives pending label`() {
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING,
            build(LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding"))).authorityLabel)
    }

    // ── 7. Capability list consistency ────────────────────────────────────────

    @Test
    fun `local_model_inference present in capabilityList when inferenceReady=true`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertTrue("inferenceReady must be true for this test", snap.inferenceReady)
        assertTrue(
            "capabilityList must contain local_model_inference when inferenceReady",
            snap.capabilityList.contains("local_model_inference")
        )
    }

    @Test
    fun `local_model_inference absent from capabilityList when inferenceReady=false`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            checksumValid = true
        )
        assertFalse("inferenceReady must be false for this test", snap.inferenceReady)
        assertFalse(
            "capabilityList must NOT contain local_model_inference when not inferenceReady",
            snap.capabilityList.contains("local_model_inference")
        )
    }

    @Test
    fun `base capabilities always present regardless of inference readiness`() {
        val baseExpected = listOf(
            "autonomous_goal_execution",
            "local_task_planning",
            "local_ui_reasoning",
            "cross_device_coordination"
        )
        // Check for a fully healthy Running state
        val runningSnap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        for (cap in baseExpected) {
            assertTrue("Running snapshot must contain base capability: $cap",
                runningSnap.capabilityList.contains(cap))
        }
        // Check for Stopped state
        val stoppedSnap = build(LocalInferenceRuntimeManager.ManagerState.Stopped)
        for (cap in baseExpected) {
            assertTrue("Stopped snapshot must contain base capability: $cap",
                stoppedSnap.capabilityList.contains(cap))
        }
    }

    @Test
    fun `base capability count without inference is 4 matching CapabilityHonestyGuard`() {
        val snap = build(LocalInferenceRuntimeManager.ManagerState.Stopped)
        assertEquals(
            "Base capability count must match CapabilityHonestyGuard.BASE_CAPABILITY_COUNT",
            CapabilityHonestyGuard.BASE_CAPABILITY_COUNT,
            snap.capabilityList.size
        )
    }

    @Test
    fun `capability list with inference has exactly 5 entries (4 base + inference)`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertTrue(snap.inferenceReady)
        assertEquals(
            "Inference-ready capability list must have exactly 5 entries",
            CapabilityHonestyGuard.BASE_CAPABILITY_COUNT + 1,
            snap.capabilityList.size
        )
    }

    // ── 8. Wire encoding (toMap) ──────────────────────────────────────────────

    @Test
    fun `toMap contains all required keys`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        val map = snap.toMap()
        val requiredKeys = listOf(
            "schema_version",
            "local_intelligence_status",
            "planner_ready",
            "grounding_ready",
            "inference_ready",
            "checksum_valid",
            "authority_label",
            "capability_list"
        )
        for (key in requiredKeys) {
            assertTrue("toMap() must contain key: $key", map.containsKey(key))
        }
    }

    @Test
    fun `toMap values match snapshot properties`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        val map = snap.toMap()
        assertEquals(snap.schemaVersion,            map["schema_version"])
        assertEquals(snap.localIntelligenceStatus,  map["local_intelligence_status"])
        assertEquals(snap.plannerReady,             map["planner_ready"])
        assertEquals(snap.groundingReady,           map["grounding_ready"])
        assertEquals(snap.inferenceReady,           map["inference_ready"])
        assertEquals(snap.checksumValid,            map["checksum_valid"])
        assertEquals(snap.authorityLabel,           map["authority_label"])
        assertEquals(snap.capabilityList,           map["capability_list"])
    }

    // ── 9. Authority label × inference readiness consistency ─────────────────

    @Test
    fun `authoritative label always implies inferenceReady=true`() {
        val snap = build(
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()),
            checksumValid = true
        )
        assertEquals(CapabilityAuthoritySnapshot.AUTHORITY_LABEL_AUTHORITATIVE, snap.authorityLabel)
        assertTrue(
            "authoritative label must imply inferenceReady=true",
            snap.inferenceReady
        )
    }

    @Test
    fun `no_authority label implies inferenceReady=false`() {
        val states = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Failed("crash"),
            LocalInferenceRuntimeManager.ManagerState.SafeMode
        )
        for (state in states) {
            val snap = build(state, checksumValid = true)
            assertEquals("State $state should give no_authority",
                CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE, snap.authorityLabel)
            assertFalse("no_authority must imply inferenceReady=false for state $state",
                snap.inferenceReady)
        }
    }

    @Test
    fun `pending label implies inferenceReady=false`() {
        val pendingStates = listOf(
            LocalInferenceRuntimeManager.ManagerState.Starting,
            LocalInferenceRuntimeManager.ManagerState.Recovering,
            LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding"))
        )
        for (state in pendingStates) {
            val snap = build(state, checksumValid = true)
            assertEquals("State $state should give pending",
                CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING, snap.authorityLabel)
            assertFalse("pending label must imply inferenceReady=false for state $state",
                snap.inferenceReady)
        }
    }

    // ── 10. All ManagerState variants covered without throwing ────────────────

    @Test
    fun `from() handles every ManagerState variant without throwing`() {
        val allStates: List<LocalInferenceRuntimeManager.ManagerState> = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Starting,
            LocalInferenceRuntimeManager.ManagerState.SafeMode,
            LocalInferenceRuntimeManager.ManagerState.Recovering,
            LocalInferenceRuntimeManager.ManagerState.Failed("crash"),
            LocalInferenceRuntimeManager.ManagerState.FailedStartup(
                "both failed",
                com.ufo.galaxy.runtime.RuntimeStartResult.StartStage.HEALTH_CHECK
            ),
            LocalInferenceRuntimeManager.ManagerState.Unavailable("gone"),
            LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding")),
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "partial"),
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot())
        )
        for (state in allStates) {
            val snap = build(state, checksumValid = true)
            // All snapshots must carry a valid schema version and non-blank authority label
            assertEquals("Schema version must match for state $state",
                CapabilityAuthoritySnapshot.SCHEMA_VERSION, snap.schemaVersion)
            assertTrue("Authority label must be non-blank for state $state",
                snap.authorityLabel.isNotBlank())
            assertTrue("localIntelligenceStatus must be non-blank for state $state",
                snap.localIntelligenceStatus.isNotBlank())
        }
    }

    // ── 11. Authority label constants are stable ──────────────────────────────

    @Test
    fun `authority label constants have expected wire values`() {
        assertEquals("authoritative",   CapabilityAuthoritySnapshot.AUTHORITY_LABEL_AUTHORITATIVE)
        assertEquals("degraded_authority", CapabilityAuthoritySnapshot.AUTHORITY_LABEL_DEGRADED)
        assertEquals("no_authority",    CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE)
        assertEquals("pending",         CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING)
    }

    // ── 12. V2 dispatch scoring invariants ────────────────────────────────────

    /**
     * Validates the invariants V2 relies on for authoritative dispatch scoring:
     *
     * 1. If [CapabilityAuthoritySnapshot.inferenceReady] is true, then
     *    "local_model_inference" MUST be in [CapabilityAuthoritySnapshot.capabilityList].
     * 2. If [CapabilityAuthoritySnapshot.inferenceReady] is false, then
     *    "local_model_inference" MUST NOT be in [CapabilityAuthoritySnapshot.capabilityList].
     * 3. [CapabilityAuthoritySnapshot.authorityLabel] must be one of the four defined constants.
     */
    @Test
    fun `V2 dispatch scoring invariants hold across all manager states`() {
        val validLabels = setOf(
            CapabilityAuthoritySnapshot.AUTHORITY_LABEL_AUTHORITATIVE,
            CapabilityAuthoritySnapshot.AUTHORITY_LABEL_DEGRADED,
            CapabilityAuthoritySnapshot.AUTHORITY_LABEL_NONE,
            CapabilityAuthoritySnapshot.AUTHORITY_LABEL_PENDING
        )

        val allStates: List<Pair<LocalInferenceRuntimeManager.ManagerState, Boolean>> = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped to true,
            LocalInferenceRuntimeManager.ManagerState.Stopped to false,
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()) to true,
            LocalInferenceRuntimeManager.ManagerState.Running(healthySnapshot()) to false,
            LocalInferenceRuntimeManager.ManagerState.Running(plannerUnhealthySnapshot()) to true,
            LocalInferenceRuntimeManager.ManagerState.Degraded(plannerUnhealthySnapshot(), "x") to true,
            LocalInferenceRuntimeManager.ManagerState.Failed("x") to true,
            LocalInferenceRuntimeManager.ManagerState.Starting to true,
            LocalInferenceRuntimeManager.ManagerState.Recovering to true,
            LocalInferenceRuntimeManager.ManagerState.PartialReady(listOf("planner"), listOf("grounding")) to true
        )

        for ((state, checksum) in allStates) {
            val snap = build(state, checksumValid = checksum)
            val inferenceCap = "local_model_inference"

            // Invariant 1: inferenceReady=true → capability in list
            if (snap.inferenceReady) {
                assertTrue(
                    "V2 invariant: inferenceReady=true must include $inferenceCap for state=$state checksum=$checksum",
                    snap.capabilityList.contains(inferenceCap)
                )
            }
            // Invariant 2: inferenceReady=false → capability NOT in list
            if (!snap.inferenceReady) {
                assertFalse(
                    "V2 invariant: inferenceReady=false must exclude $inferenceCap for state=$state checksum=$checksum",
                    snap.capabilityList.contains(inferenceCap)
                )
            }
            // Invariant 3: authority label is always one of the defined constants
            assertTrue(
                "V2 invariant: authorityLabel must be a known value for state=$state, got=${snap.authorityLabel}",
                snap.authorityLabel in validLabels
            )
        }
    }
}
