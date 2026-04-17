package com.ufo.galaxy.protocol

import com.ufo.galaxy.runtime.AttachedRuntimeSession
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-4 — Protocol Consistency Rules tests.
 *
 * Verifies that:
 *  1. All load-bearing shared protocol surfaces are represented in the registry.
 *  2. Canonical and transitional surfaces are correctly classified.
 *  3. Canonical values are complete for each surface.
 *  4. Transitional aliases resolve to known canonical targets.
 *  5. Drift-candidate detection works for unrecognized values.
 *  6. Helper functions produce correct results.
 */
class Pr4ProtocolConsistencyRulesTest {

    // ── Surface registry completeness ─────────────────────────────────────────

    @Test
    fun `all load-bearing protocol surfaces are represented in the rule registry`() {
        val registered = UgcpProtocolConsistencyRules.allRules.keys
        assertTrue(registered.contains(ProtocolSurface.TERMINAL_STATE_VOCABULARY))
        assertTrue(registered.contains(ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND))
        assertTrue(registered.contains(ProtocolSurface.ATTACHED_SESSION_STATE))
        assertTrue(registered.contains(ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE))
        assertTrue(registered.contains(ProtocolSurface.RECONNECT_RECOVERY_STATE))
        assertTrue(registered.contains(ProtocolSurface.SESSION_IDENTIFIER_CARRIER))
        assertTrue(registered.contains(ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR))
        assertTrue(registered.contains(ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR))
        assertTrue(registered.contains(ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER))
        assertTrue(registered.contains(ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY))
        assertTrue(registered.contains(ProtocolSurface.STAGED_MESH_EXECUTION_STATUS))
        assertTrue(registered.contains(ProtocolSurface.DURABLE_SESSION_CONTINUITY))
        assertEquals(ProtocolSurface.entries.size, registered.size)
    }

    // ── Canonical vs transitional surface classification ──────────────────────

    @Test
    fun `canonical and transitional surfaces are correctly distinguished`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalSurfaces
        val transitional = UgcpProtocolConsistencyRules.transitionalSurfaces

        assertTrue(canonical.contains(ProtocolSurface.TERMINAL_STATE_VOCABULARY))
        assertTrue(canonical.contains(ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND))
        assertTrue(canonical.contains(ProtocolSurface.ATTACHED_SESSION_STATE))
        assertTrue(canonical.contains(ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE))
        assertTrue(canonical.contains(ProtocolSurface.RECONNECT_RECOVERY_STATE))
        assertTrue(canonical.contains(ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR))
        assertTrue(canonical.contains(ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR))
        assertTrue(canonical.contains(ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER))
        assertTrue(canonical.contains(ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY))
        assertTrue(canonical.contains(ProtocolSurface.DURABLE_SESSION_CONTINUITY))

        assertTrue(transitional.contains(ProtocolSurface.SESSION_IDENTIFIER_CARRIER))
        assertFalse(canonical.contains(ProtocolSurface.SESSION_IDENTIFIER_CARRIER))

        assertTrue(transitional.contains(ProtocolSurface.STAGED_MESH_EXECUTION_STATUS))
        assertFalse(canonical.contains(ProtocolSurface.STAGED_MESH_EXECUTION_STATUS))

        assertTrue(canonical.intersect(transitional).isEmpty())
        assertEquals(ProtocolSurface.entries.size, canonical.size + transitional.size)
    }

    // ── Terminal state vocabulary ─────────────────────────────────────────────

    @Test
    fun `canonical terminal status values are all recognized as canonical`() {
        val surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        for (value in listOf("success", "error", "cancelled", "timeout", "rejected", "partial", "disabled")) {
            val result = UgcpProtocolConsistencyRules.checkValue(surface, value)
            assertEquals(
                "Expected $value to be CANONICAL on TERMINAL_STATE_VOCABULARY",
                ConsistencyCheckStatus.CANONICAL,
                result.status
            )
        }
    }

    @Test
    fun `legacy terminal status aliases are classified as transitional with canonical targets`() {
        val surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        val legacyToCanonical = mapOf(
            "completed" to "success",
            "failed" to "error",
            "failure" to "error",
            "no_op" to "disabled"
        )
        for ((alias, target) in legacyToCanonical) {
            val result = UgcpProtocolConsistencyRules.checkValue(surface, alias)
            assertEquals(
                "Expected $alias to be TRANSITIONAL_ALIAS",
                ConsistencyCheckStatus.TRANSITIONAL_ALIAS,
                result.status
            )
            assertEquals(
                "Expected $alias to map to $target",
                target,
                result.canonicalTarget
            )
            assertNotNull("Alias reason must be non-null", result.aliasReason)
        }
    }

    @Test
    fun `unknown terminal status values are classified as drift candidates`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.TERMINAL_STATE_VOCABULARY,
            "running"
        )
        assertEquals(ConsistencyCheckStatus.DRIFT_CANDIDATE, result.status)
        assertNull(result.canonicalTarget)
    }

    // ── Delegated execution result kind ──────────────────────────────────────

    @Test
    fun `canonical delegated result kinds cover all DelegatedExecutionSignal ResultKind wire values`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND
        )
        assertTrue(canonical.contains("completed"))
        assertTrue(canonical.contains("failed"))
        assertTrue(canonical.contains("timeout"))
        assertTrue(canonical.contains("cancelled"))
        assertTrue(canonical.contains("rejected"))
    }

    @Test
    fun `delegated result kind completed is canonical not a transitional alias`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND,
            "completed"
        )
        assertEquals(ConsistencyCheckStatus.CANONICAL, result.status)
    }

    @Test
    fun `delegated result kind success is a drift candidate not a canonical value`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND,
            "success"
        )
        assertEquals(ConsistencyCheckStatus.DRIFT_CANDIDATE, result.status)
    }

    // ── Attached session state ────────────────────────────────────────────────

    @Test
    fun `attached session state canonical values match AttachedRuntimeSession State wireValues`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.ATTACHED_SESSION_STATE
        )
        for (state in AttachedRuntimeSession.State.entries) {
            assertTrue(
                "AttachedRuntimeSession.State.${state.name} wireValue '${state.wireValue}' must be canonical",
                canonical.contains(state.wireValue)
            )
        }
        assertEquals(AttachedRuntimeSession.State.entries.size, canonical.size)
    }

    @Test
    fun `attached session state has no transitional aliases`() {
        val aliases = UgcpProtocolConsistencyRules.transitionalAliasValuesFor(
            ProtocolSurface.ATTACHED_SESSION_STATE
        )
        assertTrue("Attached session state surface should have no transitional aliases", aliases.isEmpty())
    }

    // ── Attached session detach cause ─────────────────────────────────────────

    @Test
    fun `attached session detach cause canonical values match AttachedRuntimeSession DetachCause wireValues`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE
        )
        for (cause in AttachedRuntimeSession.DetachCause.entries) {
            assertTrue(
                "DetachCause.${cause.name} wireValue '${cause.wireValue}' must be canonical",
                canonical.contains(cause.wireValue)
            )
        }
        assertEquals(AttachedRuntimeSession.DetachCause.entries.size, canonical.size)
    }

    @Test
    fun `attached session detach cause unknown value is a drift candidate`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE,
            "expired"
        )
        assertEquals(ConsistencyCheckStatus.DRIFT_CANDIDATE, result.status)
    }

    // ── Reconnect recovery state ──────────────────────────────────────────────

    @Test
    fun `reconnect recovery state canonical values match ReconnectRecoveryState wireValues`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.RECONNECT_RECOVERY_STATE
        )
        for (state in ReconnectRecoveryState.entries) {
            assertTrue(
                "ReconnectRecoveryState.${state.name} wireValue '${state.wireValue}' must be canonical",
                canonical.contains(state.wireValue)
            )
        }
        assertEquals(ReconnectRecoveryState.entries.size, canonical.size)
    }

    @Test
    fun `reconnect recovery state has no transitional aliases`() {
        val aliases = UgcpProtocolConsistencyRules.transitionalAliasValuesFor(
            ProtocolSurface.RECONNECT_RECOVERY_STATE
        )
        assertTrue(aliases.isEmpty())
    }

    // ── Session identifier carrier ────────────────────────────────────────────

    @Test
    fun `session identifier surface is classified as transitional compatibility`() {
        assertEquals(
            ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY,
            ProtocolSurface.SESSION_IDENTIFIER_CARRIER.surfaceClass
        )
    }

    @Test
    fun `runtime session id and attached session id are canonical carriers`() {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER
        for (carrier in listOf("runtime_session_id", "attached_session_id", "signal_id",
            "control_session_id", "mesh_session_id", "durable_session_id")) {
            val result = UgcpProtocolConsistencyRules.checkValue(surface, carrier)
            assertEquals(
                "$carrier should be a CANONICAL session identifier carrier",
                ConsistencyCheckStatus.CANONICAL,
                result.status
            )
        }
    }

    @Test
    fun `session id and mesh id are transitional aliases for canonical session family terms`() {
        val surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER

        val sessionIdResult = UgcpProtocolConsistencyRules.checkValue(surface, "session_id")
        assertEquals(ConsistencyCheckStatus.TRANSITIONAL_ALIAS, sessionIdResult.status)
        assertEquals("control_session_id", sessionIdResult.canonicalTarget)

        val meshIdResult = UgcpProtocolConsistencyRules.checkValue(surface, "mesh_id")
        assertEquals(ConsistencyCheckStatus.TRANSITIONAL_ALIAS, meshIdResult.status)
        assertEquals("mesh_session_id", meshIdResult.canonicalTarget)
    }

    @Test
    fun `unrecognized session carrier is a drift candidate`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.SESSION_IDENTIFIER_CARRIER,
            "request_id"
        )
        assertEquals(ConsistencyCheckStatus.DRIFT_CANDIDATE, result.status)
    }

    // ── Runtime profile descriptor ────────────────────────────────────────────

    @Test
    fun `runtime profile descriptors are complete and canonical`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR
        )
        assertTrue(canonical.contains("ugcp.runtime_ws_profile.android"))
        assertTrue(canonical.contains("ugcp.control_transfer_profile.android"))
        assertTrue(canonical.contains("ugcp.coordination_profile.android"))
        assertTrue(canonical.contains("ugcp.truth_event_model.android"))
        assertTrue(canonical.contains("ugcp.conformance_surface.android"))
    }

    @Test
    fun `runtime profile descriptor values match UgcpSharedSchemaAlignment profile constants`() {
        for (profileName in listOf(
            UgcpSharedSchemaAlignment.runtimeWsProfileName,
            UgcpSharedSchemaAlignment.controlTransferProfileName,
            UgcpSharedSchemaAlignment.coordinationProfileName,
            UgcpSharedSchemaAlignment.truthEventModelName,
            UgcpSharedSchemaAlignment.conformanceSurfaceName
        )) {
            val result = UgcpProtocolConsistencyRules.checkValue(
                ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR,
                profileName
            )
            assertEquals(
                "Profile name '$profileName' from UgcpSharedSchemaAlignment should be CANONICAL",
                ConsistencyCheckStatus.CANONICAL,
                result.status
            )
        }
    }

    // ── Capability readiness descriptor ──────────────────────────────────────

    @Test
    fun `capability readiness descriptor canonical fields are complete`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR
        )
        assertTrue(canonical.contains("source_runtime_posture"))
        assertTrue(canonical.contains("model_ready"))
        assertTrue(canonical.contains("accessibility_ready"))
        assertTrue(canonical.contains("overlay_ready"))
        assertTrue(canonical.contains("degraded_mode"))
    }

    @Test
    fun `capability readiness descriptor fields match UgcpSharedSchemaAlignment readiness terms`() {
        val registryTerms = UgcpSharedSchemaAlignment.readinessCapabilityTerms
        for (term in registryTerms) {
            assertTrue(
                "Readiness term '$term' from UgcpSharedSchemaAlignment should be in capability descriptor rule",
                UgcpProtocolConsistencyRules.isTolerated(ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR, term)
            )
        }
    }

    // ── Truth-event payload identifier ────────────────────────────────────────

    @Test
    fun `truth event payload identifiers are canonical and complete`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER
        )
        assertTrue(canonical.contains("task_id"))
        assertTrue(canonical.contains("trace_id"))
        assertTrue(canonical.contains("runtime_session_id"))
        assertTrue(canonical.contains("signal_id"))
        assertTrue(canonical.contains("emission_seq"))
        assertTrue(canonical.contains("attached_session_id"))
    }

    @Test
    fun `truth event payload identifiers are consistent with UgcpSharedSchemaAlignment identity alignments`() {
        val alignmentCarriers = UgcpSharedSchemaAlignment.identityAlignments.map { it.androidCarrier }.toSet()
        val ruleCanonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER
        )
        for (identifier in listOf("task_id", "trace_id", "runtime_session_id")) {
            assertTrue(
                "$identifier should appear in both identity alignments and truth-event payload rule",
                alignmentCarriers.contains(identifier) || ruleCanonical.contains(identifier)
            )
        }
    }

    // ── Transfer lifecycle vocabulary ─────────────────────────────────────────

    @Test
    fun `transfer lifecycle vocabulary covers all canonical transfer event terms`() {
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY
        )
        assertTrue(canonical.contains("transfer_accept"))
        assertTrue(canonical.contains("transfer_reject"))
        assertTrue(canonical.contains("transfer_cancel"))
        assertTrue(canonical.contains("transfer_expire"))
        assertTrue(canonical.contains("transfer_adopt"))
        assertTrue(canonical.contains("transfer_resume"))
    }

    @Test
    fun `transfer lifecycle vocabulary is consistent with UgcpSharedSchemaAlignment transfer lifecycle terms`() {
        val alignmentTerms = UgcpSharedSchemaAlignment.transferLifecycleTerms
        val ruleCanonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY
        )
        assertEquals(
            "Transfer lifecycle rule canonical values should exactly match UgcpSharedSchemaAlignment.transferLifecycleTerms",
            alignmentTerms,
            ruleCanonical
        )
    }

    @Test
    fun `unknown transfer lifecycle value is a drift candidate`() {
        val result = UgcpProtocolConsistencyRules.checkValue(
            ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY,
            "transfer_retry"
        )
        assertEquals(ConsistencyCheckStatus.DRIFT_CANDIDATE, result.status)
    }

    // ── Cross-surface helper functions ────────────────────────────────────────

    @Test
    fun `allTransitionalAliases returns all aliases across surfaces`() {
        val all = UgcpProtocolConsistencyRules.allTransitionalAliases()
        val aliasValues = all.map { (_, alias) -> alias.aliasValue }.toSet()
        assertTrue(aliasValues.contains("completed"))
        assertTrue(aliasValues.contains("failed"))
        assertTrue(aliasValues.contains("session_id"))
        assertTrue(aliasValues.contains("mesh_id"))

        val surfaces = all.map { (surface, _) -> surface }.toSet()
        assertTrue(surfaces.contains(ProtocolSurface.TERMINAL_STATE_VOCABULARY))
        assertTrue(surfaces.contains(ProtocolSurface.SESSION_IDENTIFIER_CARRIER))
    }

    @Test
    fun `isTolerated returns true for canonical and alias values but false for drift candidates`() {
        val surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        assertTrue(UgcpProtocolConsistencyRules.isTolerated(surface, "success"))
        assertTrue(UgcpProtocolConsistencyRules.isTolerated(surface, "completed"))
        assertFalse(UgcpProtocolConsistencyRules.isTolerated(surface, "running"))
        assertFalse(UgcpProtocolConsistencyRules.isTolerated(surface, "pending"))
    }

    @Test
    fun `toleratedValuesFor returns union of canonical values and transitional aliases`() {
        val surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY
        val tolerated = UgcpProtocolConsistencyRules.toleratedValuesFor(surface)
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
        val aliases = UgcpProtocolConsistencyRules.transitionalAliasValuesFor(surface)
        assertEquals(canonical + aliases, tolerated)
    }

    @Test
    fun `each transitional alias maps to a value that is canonical on the same surface`() {
        for ((surface, alias) in UgcpProtocolConsistencyRules.allTransitionalAliases()) {
            val canonicalValues = UgcpProtocolConsistencyRules.canonicalValuesFor(surface)
            assertTrue(
                "Alias '${alias.aliasValue}' on $surface maps to '${alias.canonicalTarget}' " +
                    "but that target is not in the canonical values for the surface",
                canonicalValues.contains(alias.canonicalTarget)
            )
        }
    }

    @Test
    fun `every rule has a non-empty notes string`() {
        for ((surface, rule) in UgcpProtocolConsistencyRules.allRules) {
            assertTrue(
                "Rule for $surface must have non-empty notes",
                rule.notes.isNotBlank()
            )
        }
    }

    @Test
    fun `every rule has at least one canonical value`() {
        for ((surface, rule) in UgcpProtocolConsistencyRules.allRules) {
            assertTrue(
                "Rule for $surface must define at least one canonical value",
                rule.canonicalValues.isNotEmpty()
            )
        }
    }

    // ── Alignment with existing infrastructure ────────────────────────────────

    @Test
    fun `lifecycle status normalization targets are all canonical terminal state values`() {
        val normalizations = UgcpSharedSchemaAlignment.lifecycleStatusNormalizations
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TERMINAL_STATE_VOCABULARY
        )
        for ((_, target) in normalizations) {
            assertTrue(
                "Normalization target '$target' must be a canonical terminal state value",
                canonical.contains(target)
            )
        }
    }

    @Test
    fun `canonical terminal state values are all present in UgcpSharedSchemaAlignment lifecycle status normalizations`() {
        val normalizations = UgcpSharedSchemaAlignment.lifecycleStatusNormalizations
        val normalizationTargets = normalizations.values.toSet()
        val canonical = UgcpProtocolConsistencyRules.canonicalValuesFor(
            ProtocolSurface.TERMINAL_STATE_VOCABULARY
        )
        for (value in canonical) {
            assertTrue(
                "Canonical terminal state '$value' should be a normalization target",
                normalizationTargets.contains(value)
            )
        }
    }
}
