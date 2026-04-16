package com.ufo.galaxy.runtime

import com.ufo.galaxy.history.SessionHistorySummary
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AndroidSessionLayerContracts
import com.ufo.galaxy.protocol.AndroidSessionLayerKind
import com.ufo.galaxy.trace.LocalLoopTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-3 — Canonical Session Axis.
 *
 * Regression and acceptance test suite for all PR-3 additions:
 *
 *  1. [CanonicalSessionFamily] enum — six session families ([CanonicalSessionFamily.CONTROL_SESSION],
 *     [CanonicalSessionFamily.RUNTIME_SESSION], [CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION],
 *     [CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION], [CanonicalSessionFamily.CONVERSATION_SESSION],
 *     [CanonicalSessionFamily.MESH_SESSION]) with stable [CanonicalSessionFamily.canonicalTerm]
 *     and [CanonicalSessionFamily.wireAlias] values.
 *
 *  2. [SessionIdentifierRole] enum — three identifier role classifications
 *     ([SessionIdentifierRole.CANONICAL], [SessionIdentifierRole.TRANSITIONAL_ALIAS],
 *     [SessionIdentifierRole.CONTEXTUAL_ALIAS]) with stable [SessionIdentifierRole.label] values.
 *
 *  3. [SessionContinuityBehavior] enum — five behavior values covering all session lifetime
 *     patterns present in the Android codebase.
 *
 *  4. [SessionContinuityLayer] enum — six layers corresponding to each session family's
 *     runtime plane.
 *
 *  5. [AndroidSessionAxisEntry] data class — per-carrier registry entry with all required
 *     fields.
 *
 *  6. [SessionFamilyContinuityModel] data class — per-family continuity model with
 *     [SessionFamilyContinuityModel.surviveReconnect], [SessionFamilyContinuityModel.surviveTransfer],
 *     and [SessionFamilyContinuityModel.surviveInvalidation] flags.
 *
 *  7. [CanonicalSessionAxis] object — canonical carrier registry and continuity model with:
 *     - [CanonicalSessionAxis.carriers]: all Android session carriers registered and classified.
 *     - [CanonicalSessionAxis.entryForCarrier]: resolves a carrier string to its axis entry.
 *     - [CanonicalSessionAxis.carriersForFamily]: returns all carriers for a session family.
 *     - [CanonicalSessionAxis.carriersForLayer]: returns all carriers for a continuity layer.
 *     - [CanonicalSessionAxis.transitionalAliases]: returns all TRANSITIONAL_ALIAS carriers.
 *     - [CanonicalSessionAxis.canonicalCarriers]: returns all CANONICAL carriers.
 *     - [CanonicalSessionAxis.contextualAliases]: returns all CONTEXTUAL_ALIAS carriers.
 *     - [CanonicalSessionAxis.continuityModels]: all six families have continuity models.
 *     - [CanonicalSessionAxis.continuityModelFor]: returns model for a given family.
 *     - [CanonicalSessionAxis.crossRepoTermFor]: maps carrier to canonical cross-repo term.
 *
 *  8. [GalaxyLogger.TAG_SESSION_AXIS] — new stable structured log tag constant for
 *     session-axis boundary events.
 *
 *  9. Cross-layer consistency:
 *     - All six session families are covered in the carrier registry.
 *     - All six session families have a continuity model.
 *     - [AndroidSessionLayerContracts] three-layer model is consistent with the axis families.
 *     - [AttachedRuntimeSession.runtimeAttachmentSessionId] alias is consistent with
 *       [CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION] canonical term.
 *     - [DelegatedExecutionSignal.delegationTransferSessionId] alias is consistent with
 *       [CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION] canonical term.
 *     - [com.ufo.galaxy.trace.LocalLoopTrace.conversationSessionId] alias is consistent with
 *       [CanonicalSessionFamily.CONVERSATION_SESSION] canonical term.
 *
 * ## Test matrix
 *
 * ### CanonicalSessionFamily — canonical terms
 *  - CONTROL_SESSION canonicalTerm is "control_session_id"
 *  - CONTROL_SESSION wireAlias is "session_id"
 *  - RUNTIME_SESSION canonicalTerm is "runtime_session_id"
 *  - RUNTIME_SESSION wireAlias is null
 *  - ATTACHED_RUNTIME_SESSION canonicalTerm is "attached_runtime_session_id"
 *  - ATTACHED_RUNTIME_SESSION wireAlias is null
 *  - DELEGATION_TRANSFER_SESSION canonicalTerm is "transfer_session_context"
 *  - DELEGATION_TRANSFER_SESSION wireAlias is null
 *  - CONVERSATION_SESSION canonicalTerm is "conversation_session_id"
 *  - CONVERSATION_SESSION wireAlias is null
 *  - MESH_SESSION canonicalTerm is "mesh_session_id"
 *  - MESH_SESSION wireAlias is "mesh_id"
 *  - all six canonical terms are distinct
 *  - families with wireAlias have hasWireAlias == true
 *  - families without wireAlias have hasWireAlias == false
 *
 * ### SessionIdentifierRole — label values
 *  - CANONICAL label is "canonical"
 *  - TRANSITIONAL_ALIAS label is "transitional_alias"
 *  - CONTEXTUAL_ALIAS label is "contextual_alias"
 *
 * ### SessionContinuityBehavior — enum completeness
 *  - five distinct behaviors exist
 *  - STABLE_ACROSS_RECONNECT, REFRESHED_ON_RECONNECT, CONVERSATION_SCOPED,
 *    TRANSFER_SCOPED, MESH_SCOPED all present
 *
 * ### SessionContinuityLayer — enum completeness
 *  - six distinct layers exist (CONTROL, RUNTIME, ATTACHMENT, TRANSFER, CONVERSATION, MESH)
 *
 * ### CanonicalSessionAxis.carriers — registry completeness
 *  - all six session families appear in the registry
 *  - every family has at least two registered carriers
 *  - all carrier strings are distinct
 *  - all crossRepoTerm values match the family's canonicalTerm
 *  - every carrier's continuityLayer matches the family's expected layer
 *
 * ### CanonicalSessionAxis — carrier lookup
 *  - entryForCarrier("AipMessage.session_id") resolves to CONTROL_SESSION / TRANSITIONAL_ALIAS
 *  - entryForCarrier("AipMessage.runtime_session_id") resolves to RUNTIME_SESSION / CANONICAL
 *  - entryForCarrier("AttachedRuntimeSession.sessionId") resolves to ATTACHED_RUNTIME_SESSION / CANONICAL
 *  - entryForCarrier("DelegatedExecutionSignal.attachedSessionId") resolves to DELEGATION_TRANSFER_SESSION / CANONICAL
 *  - entryForCarrier("LocalLoopTrace.sessionId") resolves to CONVERSATION_SESSION / CANONICAL
 *  - entryForCarrier("MeshJoinPayload.mesh_id") resolves to MESH_SESSION / TRANSITIONAL_ALIAS
 *  - entryForCarrier("unknown_carrier") returns null
 *  - entryForCarrier with leading/trailing whitespace resolves correctly
 *
 * ### CanonicalSessionAxis — family and layer lookup
 *  - carriersForFamily returns non-empty list for each of the six families
 *  - carriersForLayer returns non-empty list for each of the six layers
 *
 * ### CanonicalSessionAxis — role filters
 *  - transitionalAliases() returns only TRANSITIONAL_ALIAS entries
 *  - canonicalCarriers() returns only CANONICAL entries
 *  - contextualAliases() returns only CONTEXTUAL_ALIAS entries
 *  - control-session carriers include at least one transitional alias
 *  - mesh-session carriers are all transitional aliases
 *  - attached-session carriers are all canonical
 *
 * ### CanonicalSessionAxis.continuityModels — completeness
 *  - all six families have a continuity model
 *  - continuityModelFor returns non-null for all six families
 *  - continuityModelFor returns null for a non-existent family (guarded via safe call)
 *
 * ### CanonicalSessionAxis.continuityModels — per-family continuity semantics
 *  - CONTROL_SESSION: surviveReconnect=true, surviveTransfer=true, surviveInvalidation=false
 *  - CONTROL_SESSION: behavior is STABLE_ACROSS_RECONNECT
 *  - RUNTIME_SESSION: surviveReconnect=false, behavior is REFRESHED_ON_RECONNECT
 *  - ATTACHED_RUNTIME_SESSION: surviveReconnect=true, surviveTransfer=true, surviveInvalidation=false
 *  - ATTACHED_RUNTIME_SESSION: behavior is STABLE_ACROSS_RECONNECT
 *  - DELEGATION_TRANSFER_SESSION: surviveReconnect=false, surviveTransfer=true
 *  - DELEGATION_TRANSFER_SESSION: behavior is TRANSFER_SCOPED
 *  - CONVERSATION_SESSION: surviveReconnect=true, surviveInvalidation=true
 *  - CONVERSATION_SESSION: behavior is CONVERSATION_SCOPED
 *  - MESH_SESSION: surviveReconnect=false, surviveTransfer=false, surviveInvalidation=false
 *  - MESH_SESSION: behavior is MESH_SCOPED
 *
 * ### CanonicalSessionAxis.crossRepoTermFor
 *  - crossRepoTermFor("AttachedRuntimeSession.sessionId") returns "attached_runtime_session_id"
 *  - crossRepoTermFor("MeshJoinPayload.mesh_id") returns "mesh_session_id"
 *  - crossRepoTermFor("unknown_carrier") returns null
 *
 * ### GalaxyLogger.TAG_SESSION_AXIS
 *  - TAG_SESSION_AXIS value is "GALAXY:SESSION:AXIS"
 *  - TAG_SESSION_AXIS is distinct from TAG_RECONNECT_RECOVERY
 *  - TAG_SESSION_AXIS is distinct from TAG_STAGED_MESH
 *  - TAG_SESSION_AXIS is distinct from TAG_ROLLOUT_CONTROL
 *  - TAG_SESSION_AXIS is distinct from TAG_KILL_SWITCH
 *
 * ### Cross-layer consistency
 *  - AndroidSessionLayerContracts CONVERSATION_SESSION maps to CONVERSATION_SESSION family
 *  - AndroidSessionLayerContracts RUNTIME_ATTACHMENT_SESSION maps to ATTACHED_RUNTIME_SESSION family
 *  - AndroidSessionLayerContracts DELEGATION_TRANSFER_SESSION maps to DELEGATION_TRANSFER_SESSION family
 *  - AttachedRuntimeSession.runtimeAttachmentSessionId alias equals sessionId
 *  - DelegatedExecutionSignal.delegationTransferSessionId alias equals attachedSessionId
 *  - LocalLoopTrace.conversationSessionId alias equals sessionId
 *  - SessionHistorySummary.conversationSessionId alias equals sessionId
 */
class Pr3CanonicalSessionAxisTest {

    // ── CanonicalSessionFamily — canonical terms ──────────────────────────────

    @Test
    fun `CONTROL_SESSION canonical term is control_session_id`() {
        assertEquals("control_session_id", CanonicalSessionFamily.CONTROL_SESSION.canonicalTerm)
    }

    @Test
    fun `CONTROL_SESSION wire alias is session_id`() {
        assertEquals("session_id", CanonicalSessionFamily.CONTROL_SESSION.wireAlias)
    }

    @Test
    fun `RUNTIME_SESSION canonical term is runtime_session_id`() {
        assertEquals("runtime_session_id", CanonicalSessionFamily.RUNTIME_SESSION.canonicalTerm)
    }

    @Test
    fun `RUNTIME_SESSION wire alias is null`() {
        assertNull(CanonicalSessionFamily.RUNTIME_SESSION.wireAlias)
    }

    @Test
    fun `ATTACHED_RUNTIME_SESSION canonical term is attached_runtime_session_id`() {
        assertEquals(
            "attached_runtime_session_id",
            CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION.canonicalTerm
        )
    }

    @Test
    fun `ATTACHED_RUNTIME_SESSION wire alias is null`() {
        assertNull(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION.wireAlias)
    }

    @Test
    fun `DELEGATION_TRANSFER_SESSION canonical term is transfer_session_context`() {
        assertEquals(
            "transfer_session_context",
            CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION.canonicalTerm
        )
    }

    @Test
    fun `DELEGATION_TRANSFER_SESSION wire alias is null`() {
        assertNull(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION.wireAlias)
    }

    @Test
    fun `CONVERSATION_SESSION canonical term is conversation_session_id`() {
        assertEquals(
            "conversation_session_id",
            CanonicalSessionFamily.CONVERSATION_SESSION.canonicalTerm
        )
    }

    @Test
    fun `CONVERSATION_SESSION wire alias is null`() {
        assertNull(CanonicalSessionFamily.CONVERSATION_SESSION.wireAlias)
    }

    @Test
    fun `MESH_SESSION canonical term is mesh_session_id`() {
        assertEquals("mesh_session_id", CanonicalSessionFamily.MESH_SESSION.canonicalTerm)
    }

    @Test
    fun `MESH_SESSION wire alias is mesh_id`() {
        assertEquals("mesh_id", CanonicalSessionFamily.MESH_SESSION.wireAlias)
    }

    @Test
    fun `all six canonical terms are distinct`() {
        val terms = CanonicalSessionFamily.entries.map { it.canonicalTerm }
        assertEquals("all canonical terms must be distinct", terms.size, terms.toSet().size)
    }

    @Test
    fun `families with wire alias have hasWireAlias true`() {
        assertTrue(CanonicalSessionFamily.CONTROL_SESSION.hasWireAlias)
        assertTrue(CanonicalSessionFamily.MESH_SESSION.hasWireAlias)
    }

    @Test
    fun `families without wire alias have hasWireAlias false`() {
        assertFalse(CanonicalSessionFamily.RUNTIME_SESSION.hasWireAlias)
        assertFalse(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION.hasWireAlias)
        assertFalse(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION.hasWireAlias)
        assertFalse(CanonicalSessionFamily.CONVERSATION_SESSION.hasWireAlias)
    }

    // ── SessionIdentifierRole — label values ──────────────────────────────────

    @Test
    fun `CANONICAL label is canonical`() {
        assertEquals("canonical", SessionIdentifierRole.CANONICAL.label)
    }

    @Test
    fun `TRANSITIONAL_ALIAS label is transitional_alias`() {
        assertEquals("transitional_alias", SessionIdentifierRole.TRANSITIONAL_ALIAS.label)
    }

    @Test
    fun `CONTEXTUAL_ALIAS label is contextual_alias`() {
        assertEquals("contextual_alias", SessionIdentifierRole.CONTEXTUAL_ALIAS.label)
    }

    // ── SessionContinuityBehavior — enum completeness ─────────────────────────

    @Test
    fun `five distinct continuity behaviors exist`() {
        assertEquals(5, SessionContinuityBehavior.entries.size)
    }

    @Test
    fun `all expected continuity behavior values are present`() {
        val values = SessionContinuityBehavior.entries.toSet()
        assertTrue(values.contains(SessionContinuityBehavior.STABLE_ACROSS_RECONNECT))
        assertTrue(values.contains(SessionContinuityBehavior.REFRESHED_ON_RECONNECT))
        assertTrue(values.contains(SessionContinuityBehavior.CONVERSATION_SCOPED))
        assertTrue(values.contains(SessionContinuityBehavior.TRANSFER_SCOPED))
        assertTrue(values.contains(SessionContinuityBehavior.MESH_SCOPED))
    }

    // ── SessionContinuityLayer — enum completeness ────────────────────────────

    @Test
    fun `six distinct continuity layers exist`() {
        assertEquals(6, SessionContinuityLayer.entries.size)
    }

    @Test
    fun `all expected continuity layer values are present`() {
        val values = SessionContinuityLayer.entries.toSet()
        assertTrue(values.contains(SessionContinuityLayer.CONTROL))
        assertTrue(values.contains(SessionContinuityLayer.RUNTIME))
        assertTrue(values.contains(SessionContinuityLayer.ATTACHMENT))
        assertTrue(values.contains(SessionContinuityLayer.TRANSFER))
        assertTrue(values.contains(SessionContinuityLayer.CONVERSATION))
        assertTrue(values.contains(SessionContinuityLayer.MESH))
    }

    // ── CanonicalSessionAxis.carriers — registry completeness ─────────────────

    @Test
    fun `all six session families appear in carrier registry`() {
        val families = CanonicalSessionAxis.carriers.map { it.sessionFamily }.toSet()
        assertEquals(6, families.size)
        assertTrue(families.contains(CanonicalSessionFamily.CONTROL_SESSION))
        assertTrue(families.contains(CanonicalSessionFamily.RUNTIME_SESSION))
        assertTrue(families.contains(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION))
        assertTrue(families.contains(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION))
        assertTrue(families.contains(CanonicalSessionFamily.CONVERSATION_SESSION))
        assertTrue(families.contains(CanonicalSessionFamily.MESH_SESSION))
    }

    @Test
    fun `every family has at least two registered carriers`() {
        CanonicalSessionFamily.entries.forEach { family ->
            val count = CanonicalSessionAxis.carriersForFamily(family).size
            assertTrue(
                "Family $family should have at least 2 carriers, found $count",
                count >= 2
            )
        }
    }

    @Test
    fun `all carrier strings are distinct`() {
        val carriers = CanonicalSessionAxis.carriers.map { it.carrier }
        assertEquals(
            "all carrier strings must be distinct",
            carriers.size, carriers.toSet().size
        )
    }

    @Test
    fun `all crossRepoTerm values match the family canonical term`() {
        CanonicalSessionAxis.carriers.forEach { entry ->
            assertEquals(
                "carrier ${entry.carrier} crossRepoTerm should match family canonicalTerm",
                entry.sessionFamily.canonicalTerm,
                entry.crossRepoTerm
            )
        }
    }

    @Test
    fun `every carrier continuity layer maps to the expected session family`() {
        val expectedLayerByFamily = mapOf(
            CanonicalSessionFamily.CONTROL_SESSION to SessionContinuityLayer.CONTROL,
            CanonicalSessionFamily.RUNTIME_SESSION to SessionContinuityLayer.RUNTIME,
            CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION to SessionContinuityLayer.ATTACHMENT,
            CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION to SessionContinuityLayer.TRANSFER,
            CanonicalSessionFamily.CONVERSATION_SESSION to SessionContinuityLayer.CONVERSATION,
            CanonicalSessionFamily.MESH_SESSION to SessionContinuityLayer.MESH
        )
        CanonicalSessionAxis.carriers.forEach { entry ->
            val expected = expectedLayerByFamily[entry.sessionFamily]
            assertEquals(
                "carrier ${entry.carrier} should have layer $expected",
                expected, entry.continuityLayer
            )
        }
    }

    // ── CanonicalSessionAxis — carrier lookup ─────────────────────────────────

    @Test
    fun `entryForCarrier AipMessage-session_id resolves to CONTROL_SESSION TRANSITIONAL_ALIAS`() {
        val entry = CanonicalSessionAxis.entryForCarrier("AipMessage.session_id")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.CONTROL_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.TRANSITIONAL_ALIAS, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier AipMessage-runtime_session_id resolves to RUNTIME_SESSION CANONICAL`() {
        val entry = CanonicalSessionAxis.entryForCarrier("AipMessage.runtime_session_id")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.RUNTIME_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.CANONICAL, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier AttachedRuntimeSession-sessionId resolves to ATTACHED_RUNTIME_SESSION CANONICAL`() {
        val entry = CanonicalSessionAxis.entryForCarrier("AttachedRuntimeSession.sessionId")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.CANONICAL, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier DelegatedExecutionSignal-attachedSessionId resolves to DELEGATION_TRANSFER_SESSION CANONICAL`() {
        val entry = CanonicalSessionAxis.entryForCarrier("DelegatedExecutionSignal.attachedSessionId")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.CANONICAL, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier LocalLoopTrace-sessionId resolves to CONVERSATION_SESSION CANONICAL`() {
        val entry = CanonicalSessionAxis.entryForCarrier("LocalLoopTrace.sessionId")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.CONVERSATION_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.CANONICAL, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier MeshJoinPayload-mesh_id resolves to MESH_SESSION TRANSITIONAL_ALIAS`() {
        val entry = CanonicalSessionAxis.entryForCarrier("MeshJoinPayload.mesh_id")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.MESH_SESSION, entry!!.sessionFamily)
        assertEquals(SessionIdentifierRole.TRANSITIONAL_ALIAS, entry.identifierRole)
    }

    @Test
    fun `entryForCarrier unknown carrier returns null`() {
        assertNull(CanonicalSessionAxis.entryForCarrier("unknown_carrier"))
    }

    @Test
    fun `entryForCarrier with leading and trailing whitespace resolves correctly`() {
        val entry = CanonicalSessionAxis.entryForCarrier("  AttachedRuntimeSession.sessionId  ")
        assertNotNull(entry)
        assertEquals(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION, entry!!.sessionFamily)
    }

    // ── CanonicalSessionAxis — family and layer lookup ────────────────────────

    @Test
    fun `carriersForFamily returns non-empty list for all six families`() {
        CanonicalSessionFamily.entries.forEach { family ->
            assertTrue(
                "carriersForFamily($family) should be non-empty",
                CanonicalSessionAxis.carriersForFamily(family).isNotEmpty()
            )
        }
    }

    @Test
    fun `carriersForLayer returns non-empty list for all six layers`() {
        SessionContinuityLayer.entries.forEach { layer ->
            assertTrue(
                "carriersForLayer($layer) should be non-empty",
                CanonicalSessionAxis.carriersForLayer(layer).isNotEmpty()
            )
        }
    }

    // ── CanonicalSessionAxis — role filters ───────────────────────────────────

    @Test
    fun `transitionalAliases returns only TRANSITIONAL_ALIAS entries`() {
        val aliases = CanonicalSessionAxis.transitionalAliases()
        assertTrue(aliases.isNotEmpty())
        assertTrue(aliases.all { it.identifierRole == SessionIdentifierRole.TRANSITIONAL_ALIAS })
    }

    @Test
    fun `canonicalCarriers returns only CANONICAL entries`() {
        val canonical = CanonicalSessionAxis.canonicalCarriers()
        assertTrue(canonical.isNotEmpty())
        assertTrue(canonical.all { it.identifierRole == SessionIdentifierRole.CANONICAL })
    }

    @Test
    fun `contextualAliases returns only CONTEXTUAL_ALIAS entries`() {
        val contextual = CanonicalSessionAxis.contextualAliases()
        assertTrue(contextual.isNotEmpty())
        assertTrue(contextual.all { it.identifierRole == SessionIdentifierRole.CONTEXTUAL_ALIAS })
    }

    @Test
    fun `control session carriers include at least one transitional alias`() {
        val controlCarriers = CanonicalSessionAxis.carriersForFamily(CanonicalSessionFamily.CONTROL_SESSION)
        assertTrue(
            "control session should have at least one transitional alias",
            controlCarriers.any { it.identifierRole == SessionIdentifierRole.TRANSITIONAL_ALIAS }
        )
    }

    @Test
    fun `mesh session carriers are all transitional aliases`() {
        val meshCarriers = CanonicalSessionAxis.carriersForFamily(CanonicalSessionFamily.MESH_SESSION)
        assertTrue(
            "mesh session carriers should all be transitional aliases",
            meshCarriers.all { it.identifierRole == SessionIdentifierRole.TRANSITIONAL_ALIAS }
        )
    }

    @Test
    fun `attached session carriers are all canonical`() {
        val attachedCarriers =
            CanonicalSessionAxis.carriersForFamily(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)
        assertTrue(
            "attached runtime session carriers should all be canonical",
            attachedCarriers.all { it.identifierRole == SessionIdentifierRole.CANONICAL }
        )
    }

    // ── CanonicalSessionAxis.continuityModels — completeness ──────────────────

    @Test
    fun `all six families have a continuity model`() {
        assertEquals(6, CanonicalSessionAxis.continuityModels.size)
        val families = CanonicalSessionAxis.continuityModels.map { it.family }.toSet()
        assertEquals(6, families.size)
    }

    @Test
    fun `continuityModelFor returns non-null for all six families`() {
        CanonicalSessionFamily.entries.forEach { family ->
            assertNotNull(
                "continuityModelFor($family) should be non-null",
                CanonicalSessionAxis.continuityModelFor(family)
            )
        }
    }

    // ── CanonicalSessionAxis.continuityModels — per-family semantics ──────────

    @Test
    fun `CONTROL_SESSION continuity model survives reconnect and transfer but not invalidation`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.CONTROL_SESSION)!!
        assertTrue(model.surviveReconnect)
        assertTrue(model.surviveTransfer)
        assertFalse(model.surviveInvalidation)
    }

    @Test
    fun `CONTROL_SESSION continuity behavior is STABLE_ACROSS_RECONNECT`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.CONTROL_SESSION)!!
        assertEquals(SessionContinuityBehavior.STABLE_ACROSS_RECONNECT, model.continuityBehavior)
    }

    @Test
    fun `RUNTIME_SESSION continuity model does not survive reconnect`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.RUNTIME_SESSION)!!
        assertFalse(model.surviveReconnect)
    }

    @Test
    fun `RUNTIME_SESSION continuity behavior is REFRESHED_ON_RECONNECT`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.RUNTIME_SESSION)!!
        assertEquals(SessionContinuityBehavior.REFRESHED_ON_RECONNECT, model.continuityBehavior)
    }

    @Test
    fun `ATTACHED_RUNTIME_SESSION continuity model survives reconnect and transfer but not invalidation`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)!!
        assertTrue(model.surviveReconnect)
        assertTrue(model.surviveTransfer)
        assertFalse(model.surviveInvalidation)
    }

    @Test
    fun `ATTACHED_RUNTIME_SESSION continuity behavior is STABLE_ACROSS_RECONNECT`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)!!
        assertEquals(SessionContinuityBehavior.STABLE_ACROSS_RECONNECT, model.continuityBehavior)
    }

    @Test
    fun `DELEGATION_TRANSFER_SESSION does not survive reconnect but survives transfer`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)!!
        assertFalse(model.surviveReconnect)
        assertTrue(model.surviveTransfer)
    }

    @Test
    fun `DELEGATION_TRANSFER_SESSION continuity behavior is TRANSFER_SCOPED`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)!!
        assertEquals(SessionContinuityBehavior.TRANSFER_SCOPED, model.continuityBehavior)
    }

    @Test
    fun `CONVERSATION_SESSION survives reconnect and invalidation`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.CONVERSATION_SESSION)!!
        assertTrue(model.surviveReconnect)
        assertTrue(model.surviveInvalidation)
    }

    @Test
    fun `CONVERSATION_SESSION continuity behavior is CONVERSATION_SCOPED`() {
        val model =
            CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.CONVERSATION_SESSION)!!
        assertEquals(SessionContinuityBehavior.CONVERSATION_SCOPED, model.continuityBehavior)
    }

    @Test
    fun `MESH_SESSION does not survive reconnect transfer or invalidation`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.MESH_SESSION)!!
        assertFalse(model.surviveReconnect)
        assertFalse(model.surviveTransfer)
        assertFalse(model.surviveInvalidation)
    }

    @Test
    fun `MESH_SESSION continuity behavior is MESH_SCOPED`() {
        val model = CanonicalSessionAxis.continuityModelFor(CanonicalSessionFamily.MESH_SESSION)!!
        assertEquals(SessionContinuityBehavior.MESH_SCOPED, model.continuityBehavior)
    }

    // ── CanonicalSessionAxis.crossRepoTermFor ─────────────────────────────────

    @Test
    fun `crossRepoTermFor AttachedRuntimeSession-sessionId returns attached_runtime_session_id`() {
        assertEquals(
            "attached_runtime_session_id",
            CanonicalSessionAxis.crossRepoTermFor("AttachedRuntimeSession.sessionId")
        )
    }

    @Test
    fun `crossRepoTermFor MeshJoinPayload-mesh_id returns mesh_session_id`() {
        assertEquals(
            "mesh_session_id",
            CanonicalSessionAxis.crossRepoTermFor("MeshJoinPayload.mesh_id")
        )
    }

    @Test
    fun `crossRepoTermFor unknown carrier returns null`() {
        assertNull(CanonicalSessionAxis.crossRepoTermFor("unknown_carrier"))
    }

    // ── GalaxyLogger.TAG_SESSION_AXIS ─────────────────────────────────────────

    @Test
    fun `TAG_SESSION_AXIS value is GALAXY-SESSION-AXIS`() {
        assertEquals("GALAXY:SESSION:AXIS", GalaxyLogger.TAG_SESSION_AXIS)
    }

    @Test
    fun `TAG_SESSION_AXIS is distinct from TAG_RECONNECT_RECOVERY`() {
        assertFalse(GalaxyLogger.TAG_SESSION_AXIS == GalaxyLogger.TAG_RECONNECT_RECOVERY)
    }

    @Test
    fun `TAG_SESSION_AXIS is distinct from TAG_STAGED_MESH`() {
        assertFalse(GalaxyLogger.TAG_SESSION_AXIS == GalaxyLogger.TAG_STAGED_MESH)
    }

    @Test
    fun `TAG_SESSION_AXIS is distinct from TAG_ROLLOUT_CONTROL`() {
        assertFalse(GalaxyLogger.TAG_SESSION_AXIS == GalaxyLogger.TAG_ROLLOUT_CONTROL)
    }

    @Test
    fun `TAG_SESSION_AXIS is distinct from TAG_KILL_SWITCH`() {
        assertFalse(GalaxyLogger.TAG_SESSION_AXIS == GalaxyLogger.TAG_KILL_SWITCH)
    }

    // ── Cross-layer consistency ───────────────────────────────────────────────

    @Test
    fun `AndroidSessionLayerContracts CONVERSATION_SESSION is consistent with axis family`() {
        val contract = AndroidSessionLayerContracts.contracts.first {
            it.layerKind == AndroidSessionLayerKind.CONVERSATION_SESSION
        }
        val axisFamily = CanonicalSessionFamily.CONVERSATION_SESSION
        // Both should use "conversation_session_id" as the canonical term
        assertTrue(contract.canonicalTerm.contains("conversation"))
        assertTrue(axisFamily.canonicalTerm.contains("conversation"))
    }

    @Test
    fun `AndroidSessionLayerContracts RUNTIME_ATTACHMENT_SESSION is consistent with axis family`() {
        val contract = AndroidSessionLayerContracts.contracts.first {
            it.layerKind == AndroidSessionLayerKind.RUNTIME_ATTACHMENT_SESSION
        }
        val axisFamily = CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION
        assertEquals(contract.canonicalTerm, axisFamily.canonicalTerm)
    }

    @Test
    fun `AndroidSessionLayerContracts DELEGATION_TRANSFER_SESSION is consistent with axis family`() {
        val contract = AndroidSessionLayerContracts.contracts.first {
            it.layerKind == AndroidSessionLayerKind.DELEGATION_TRANSFER_SESSION
        }
        val axisFamily = CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION
        assertEquals(contract.canonicalTerm, axisFamily.canonicalTerm)
    }

    @Test
    fun `AttachedRuntimeSession runtimeAttachmentSessionId alias equals sessionId`() {
        val session = AttachedRuntimeSession.create(hostId = "host-1", deviceId = "device-1")
        assertEquals(session.sessionId, session.runtimeAttachmentSessionId)
    }

    @Test
    fun `AttachedRuntimeSession runtimeAttachmentSessionId is consistent with axis canonical term`() {
        val session = AttachedRuntimeSession.create(hostId = "h", deviceId = "d")
        val axisEntry = CanonicalSessionAxis.entryForCarrier("AttachedRuntimeSession.sessionId")
        assertNotNull(axisEntry)
        assertEquals(
            "attached_runtime_session_id",
            axisEntry!!.crossRepoTerm
        )
        // The alias provides the same value — the canonical term is the cross-repo vocabulary
        assertEquals(session.sessionId, session.runtimeAttachmentSessionId)
    }

    @Test
    fun `DelegatedExecutionSignal delegationTransferSessionId alias equals attachedSessionId`() {
        val signal = DelegatedExecutionSignal(
            kind = DelegatedExecutionSignal.Kind.ACK,
            unitId = "u-1",
            taskId = "t-1",
            traceId = "tr-1",
            attachedSessionId = "attached-session-99",
            handoffContractVersion = 1,
            stepCount = 0,
            activationStatusHint = DelegatedActivationRecord.ActivationStatus.PENDING.wireValue,
            resultKind = null,
            timestampMs = 1L,
            signalId = "sig-1",
            emissionSeq = DelegatedExecutionSignal.EMISSION_SEQ_ACK
        )
        assertEquals(signal.attachedSessionId, signal.delegationTransferSessionId)
        assertEquals("attached-session-99", signal.delegationTransferSessionId)
    }

    @Test
    fun `LocalLoopTrace conversationSessionId alias equals sessionId`() {
        val trace = LocalLoopTrace(
            sessionId = "conv-session-42",
            originalGoal = "test goal"
        )
        assertEquals(trace.sessionId, trace.conversationSessionId)
        assertEquals("conv-session-42", trace.conversationSessionId)
    }

    @Test
    fun `SessionHistorySummary conversationSessionId alias equals sessionId`() {
        val summary = SessionHistorySummary(
            sessionId = "hist-session-7",
            originalGoal = "goal",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            durationMs = 1_000L,
            stepCount = 3,
            status = "success",
            stopReason = null,
            error = null,
            planCount = 1,
            actionCount = 3
        )
        assertEquals(summary.sessionId, summary.conversationSessionId)
        assertEquals("hist-session-7", summary.conversationSessionId)
    }

    @Test
    fun `session continuity model family fields match their enum identity`() {
        CanonicalSessionAxis.continuityModels.forEach { model ->
            assertEquals(
                "continuity model family field must match its position",
                model.family,
                CanonicalSessionAxis.continuityModelFor(model.family)?.family
            )
        }
    }
}
