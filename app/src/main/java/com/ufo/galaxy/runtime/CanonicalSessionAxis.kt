package com.ufo.galaxy.runtime

/**
 * PR-3 — Canonical Session Axis.
 *
 * Establishes the authoritative Android-side cross-repository session axis for
 * cross-layer and cross-repository session reasoning.
 *
 * ## Purpose
 *
 * The Android codebase contains multiple overlapping session-related concepts:
 * control session, runtime session, attached runtime session, transfer/delegation
 * session, conversation session, and mesh session.  These concepts are meaningful
 * but their cross-layer and cross-repository relationships were not previously
 * explicit.  This object formalizes them into a single, canonical model so that:
 *
 * - Reconnect and recovery flows can reason about session identity precisely.
 * - Runtime continuity can be tracked across the attach/detach/reconnect lifecycle.
 * - Transfer and delegation semantics can be unambiguously scoped.
 * - Projection and runtime-truth assembly can identify the correct session carrier.
 * - Cross-repository consumers can map Android session structures to canonical terms.
 *
 * ## Design intent
 *
 * This is an **additive, compatibility-safe** model.  It does not change any runtime
 * behavior, wire contracts, or existing identifier values.  It documents and formalizes
 * the session families and identifier semantics that were previously implicit in
 * `AndroidSessionLayerContracts`, `RuntimeIdentityContracts`, and scattered
 * carrier-specific comments.
 *
 * ## Session families
 *
 * Seven canonical session families are recognized on the Android side.  Each family has:
 * - A [CanonicalSessionFamily] enum value with the canonical cross-repo term and wire alias.
 * - A [SessionIdentifierRole] classification for each carrier.
 * - A [SessionContinuityBehavior] that specifies how the session identity behaves
 *   across reconnect, recovery, transfer, and projection events.
 *
 * | Family                        | Canonical term                 | Wire alias (Android) |
 * |-------------------------------|--------------------------------|----------------------|
 * | [CanonicalSessionFamily.CONTROL_SESSION]             | `control_session_id`           | `session_id`         |
 * | [CanonicalSessionFamily.RUNTIME_SESSION]             | `runtime_session_id`           | (none)               |
 * | [CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION]    | `attached_runtime_session_id`  | (none)               |
 * | [CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION] | `transfer_session_context`     | (none)               |
 * | [CanonicalSessionFamily.CONVERSATION_SESSION]        | `conversation_session_id`      | `session_id`         |
 * | [CanonicalSessionFamily.MESH_SESSION]                | `mesh_session_id`              | `mesh_id`            |
 *
 * ## Identifier roles
 *
 * Each Android carrier is classified with one of three roles:
 *
 * - [SessionIdentifierRole.CANONICAL] — The Android field directly realizes the
 *   canonical cross-repo term.  No naming convergence work is needed.
 * - [SessionIdentifierRole.TRANSITIONAL_ALIAS] — The Android field uses a local alias
 *   or wire-level name that differs from the canonical term.  Semantic equivalence is
 *   frozen; naming convergence is deferred to a follow-up phase.
 * - [SessionIdentifierRole.CONTEXTUAL_ALIAS] — The Android field serves multiple
 *   session families depending on context (e.g. `session_id` used in transfer envelopes
 *   that also carry control-session scope).  Semantic disambiguation requires inspecting
 *   the surrounding message type.
 *
 * ## Session continuity model
 *
 * The [continuityModels] list specifies the intended continuity behavior for each
 * session family across key runtime events.  See [SessionFamilyContinuityModel] and
 * [SessionContinuityBehavior] for details.
 *
 * ## Relationship to other session models
 *
 * - [AndroidSessionLayerContracts] — defines the three-layer Android session split
 *   (ConversationSession / RuntimeAttachmentSession / DelegationTransferSession).
 *   [CanonicalSessionAxis] extends this model to all seven families and adds
 *   identifier-role and continuity-model dimensions.
 * - [RuntimeIdentityContracts] — defines participant/node identity composition.
 *   [CanonicalSessionAxis] references [RuntimeIdentityContracts] for cross-layer
 *   identity linkage but does not replace it.
 * - [AttachedRuntimeSession] / [AttachedRuntimeHostSessionSnapshot] — own the
 *   attached-session runtime truth.  [CanonicalSessionAxis] documents how their
 *   carriers map to the canonical axis without taking over their authority.
 */
object CanonicalSessionAxis {

    // ── Session family carrier registry ──────────────────────────────────────

    /**
     * Full cross-layer carrier registry for the canonical session axis.
     *
     * Maps each Android carrier string to its [CanonicalSessionFamily],
     * [SessionIdentifierRole], [SessionContinuityLayer], and cross-repo term.
     * This registry is the authoritative source for cross-layer and cross-repository
     * session carrier disambiguation.
     */
    val carriers: List<AndroidSessionAxisEntry> = listOf(

        // ── Control session ───────────────────────────────────────────────────
        // Android uses `session_id` as the control-session wire carrier.
        // `control_session_id` is the canonical cross-repo term; naming
        // convergence is deferred (TRANSITIONAL_ALIAS).

        AndroidSessionAxisEntry(
            carrier = "AipMessage.session_id",
            sessionFamily = CanonicalSessionFamily.CONTROL_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.CONTROL,
            crossRepoTerm = "control_session_id",
            note = "Android wire name is `session_id`; canonical term is `control_session_id`. " +
                "Semantic equivalence is frozen; naming convergence is follow-up work."
        ),
        AndroidSessionAxisEntry(
            carrier = "TaskSubmitPayload.session_id",
            sessionFamily = CanonicalSessionFamily.CONTROL_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.CONTROL,
            crossRepoTerm = "control_session_id",
            note = "Control-session scope on task-submit envelope; wire name is `session_id`."
        ),
        AndroidSessionAxisEntry(
            carrier = "InputRouter.conversationSessionId",
            sessionFamily = CanonicalSessionFamily.CONTROL_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.CONTROL,
            crossRepoTerm = "control_session_id",
            note = "InputRouter generates a new conversationSessionId per dispatch and carries " +
                "it as `session_id` on the task-submit envelope."
        ),

        // ── Runtime session ───────────────────────────────────────────────────
        // `runtime_session_id` is the canonical cross-repo term and the Android
        // wire field name — a direct CANONICAL_MATCH.

        AndroidSessionAxisEntry(
            carrier = "AipMessage.runtime_session_id",
            sessionFamily = CanonicalSessionFamily.RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.RUNTIME,
            crossRepoTerm = "runtime_session_id",
            note = "Canonical match; per-connection UUID regenerated on each WS connect."
        ),
        AndroidSessionAxisEntry(
            carrier = "AttachedRuntimeHostSessionSnapshot.runtimeSessionId",
            sessionFamily = CanonicalSessionFamily.RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.RUNTIME,
            crossRepoTerm = "runtime_session_id",
            note = "Projected into the host-session snapshot; refreshed on reconnect."
        ),
        AndroidSessionAxisEntry(
            carrier = "RuntimeController._currentRuntimeSessionId",
            sessionFamily = CanonicalSessionFamily.RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.RUNTIME,
            crossRepoTerm = "runtime_session_id",
            note = "Internal RuntimeController field; set in openAttachedSession(), " +
                "cleared in closeAttachedSession()."
        ),

        // ── Attached runtime session ──────────────────────────────────────────
        // `attached_runtime_session_id` / `AttachedRuntimeSession.sessionId` is
        // a direct CANONICAL_MATCH across all carriers.

        AndroidSessionAxisEntry(
            carrier = "AttachedRuntimeSession.sessionId",
            sessionFamily = CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.ATTACHMENT,
            crossRepoTerm = "attached_runtime_session_id",
            note = "Stable UUID per attach event; constant across state transitions."
        ),
        AndroidSessionAxisEntry(
            carrier = "AttachedRuntimeHostSessionSnapshot.sessionId",
            sessionFamily = CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.ATTACHMENT,
            crossRepoTerm = "attached_runtime_session_id",
            note = "Projected from AttachedRuntimeSession.sessionId; always-present snapshot field."
        ),
        AndroidSessionAxisEntry(
            carrier = "delegated_execution_signal.attached_session_id",
            sessionFamily = CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.ATTACHMENT,
            crossRepoTerm = "attached_runtime_session_id",
            note = "DelegatedExecutionSignal.attachedSessionId carries the attached-session " +
                "scope for ACK/PROGRESS/RESULT signals."
        ),

        // ── Delegation / transfer session ─────────────────────────────────────
        // The transfer session is the lifecycle context that spans one inbound
        // delegated-execution unit (ACK→PROGRESS→RESULT).  The `session_id` field
        // on takeover envelopes is a CONTEXTUAL_ALIAS: it carries control-session
        // scope in the takeover context but the transfer lifecycle is scoped to
        // the attached-session + DelegatedExecutionSignal chain.

        AndroidSessionAxisEntry(
            carrier = "takeover_request.session_id",
            sessionFamily = CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION,
            identifierRole = SessionIdentifierRole.CONTEXTUAL_ALIAS,
            continuityLayer = SessionContinuityLayer.TRANSFER,
            crossRepoTerm = "transfer_session_context",
            note = "Carries control-session scope in the takeover envelope context. " +
                "The transfer lifecycle is scoped to the attached session."
        ),
        AndroidSessionAxisEntry(
            carrier = "DelegatedExecutionSignal.attachedSessionId",
            sessionFamily = CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.TRANSFER,
            crossRepoTerm = "transfer_session_context",
            note = "Primary carrier for the transfer-session context in ACK/PROGRESS/RESULT signals."
        ),
        AndroidSessionAxisEntry(
            carrier = "DelegatedExecutionTracker.attachedSessionId",
            sessionFamily = CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.TRANSFER,
            crossRepoTerm = "transfer_session_context",
            note = "Transfer-session scope in the local execution-tracking state."
        ),

        // ── Conversation session ──────────────────────────────────────────────
        // Android conversation/history timeline identity; independent of cross-device
        // attachment.  Direct CANONICAL_MATCH.

        AndroidSessionAxisEntry(
            carrier = "LocalLoopTrace.sessionId",
            sessionFamily = CanonicalSessionFamily.CONVERSATION_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.CONVERSATION,
            crossRepoTerm = "conversation_session_id",
            note = "Per-local-loop session identity; scoped to one conversation/history timeline."
        ),
        AndroidSessionAxisEntry(
            carrier = "SessionHistorySummary.sessionId",
            sessionFamily = CanonicalSessionFamily.CONVERSATION_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.CONVERSATION,
            crossRepoTerm = "conversation_session_id",
            note = "Derived from LocalLoopTrace.sessionId; persistent conversation history identity."
        ),

        // ── Mesh session ──────────────────────────────────────────────────────
        // Android wire name is `mesh_id`; canonical cross-repo term is `mesh_session_id`.
        // TRANSITIONAL_ALIAS — semantic equivalence frozen; naming convergence is follow-up.

        AndroidSessionAxisEntry(
            carrier = "MeshJoinPayload.mesh_id",
            sessionFamily = CanonicalSessionFamily.MESH_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.MESH,
            crossRepoTerm = "mesh_session_id",
            note = "Android wire name is `mesh_id`; canonical term is `mesh_session_id`."
        ),
        AndroidSessionAxisEntry(
            carrier = "MeshLeavePayload.mesh_id",
            sessionFamily = CanonicalSessionFamily.MESH_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.MESH,
            crossRepoTerm = "mesh_session_id",
            note = "Android wire name is `mesh_id`; canonical term is `mesh_session_id`."
        ),
        AndroidSessionAxisEntry(
            carrier = "MeshResultPayload.mesh_id",
            sessionFamily = CanonicalSessionFamily.MESH_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.MESH,
            crossRepoTerm = "mesh_session_id",
            note = "Android wire name is `mesh_id`; canonical term is `mesh_session_id`."
        ),
        AndroidSessionAxisEntry(
            carrier = "StagedMeshParticipationResult.meshId",
            sessionFamily = CanonicalSessionFamily.MESH_SESSION,
            identifierRole = SessionIdentifierRole.TRANSITIONAL_ALIAS,
            continuityLayer = SessionContinuityLayer.MESH,
            crossRepoTerm = "mesh_session_id",
            note = "Typed Android field; carries mesh_id as a mesh-session scope identifier."
        ),

        // ── Durable runtime session (PR-1) ────────────────────────────────────
        // DurableSessionContinuityRecord.durableSessionId is the durable-era anchor
        // that survives multiple attached-session lifetimes and WS reconnect cycles.

        AndroidSessionAxisEntry(
            carrier = "DurableSessionContinuityRecord.durableSessionId",
            sessionFamily = CanonicalSessionFamily.DURABLE_RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.DURABLE,
            crossRepoTerm = "durable_session_id",
            note = "Activation-era durable identity managed by RuntimeController. " +
                "Constant across reconnects; reset only by stop() or invalidateSession()."
        ),
        AndroidSessionAxisEntry(
            carrier = "AttachedRuntimeHostSessionSnapshot.durableSessionId",
            sessionFamily = CanonicalSessionFamily.DURABLE_RUNTIME_SESSION,
            identifierRole = SessionIdentifierRole.CANONICAL,
            continuityLayer = SessionContinuityLayer.DURABLE,
            crossRepoTerm = "durable_session_id",
            note = "Projection of DurableSessionContinuityRecord.durableSessionId into the " +
                "host-facing snapshot; absent when no durable era is active."
        )
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    private val carrierIndex: Map<String, AndroidSessionAxisEntry> =
        carriers.associateBy { it.carrier }

    private val familyIndex: Map<CanonicalSessionFamily, List<AndroidSessionAxisEntry>> =
        carriers.groupBy { it.sessionFamily }

    private val layerIndex: Map<SessionContinuityLayer, List<AndroidSessionAxisEntry>> =
        carriers.groupBy { it.continuityLayer }

    /**
     * Returns the [AndroidSessionAxisEntry] for [carrier], or `null` if [carrier]
     * is not registered in the canonical session axis.
     */
    fun entryForCarrier(carrier: String): AndroidSessionAxisEntry? =
        carrierIndex[carrier.trim()]

    /**
     * Returns all [AndroidSessionAxisEntry] instances for [family].
     * Returns an empty list if no carriers are registered for [family].
     */
    fun carriersForFamily(family: CanonicalSessionFamily): List<AndroidSessionAxisEntry> =
        familyIndex[family] ?: emptyList()

    /**
     * Returns all [AndroidSessionAxisEntry] instances for [layer].
     * Returns an empty list if no carriers are registered for [layer].
     */
    fun carriersForLayer(layer: SessionContinuityLayer): List<AndroidSessionAxisEntry> =
        layerIndex[layer] ?: emptyList()

    /**
     * Returns all carriers classified as [SessionIdentifierRole.TRANSITIONAL_ALIAS].
     * These carriers use Android-local alias names that differ from the canonical
     * cross-repo term; naming convergence is deferred follow-up work.
     */
    fun transitionalAliases(): List<AndroidSessionAxisEntry> =
        carriers.filter { it.identifierRole == SessionIdentifierRole.TRANSITIONAL_ALIAS }

    /**
     * Returns all carriers classified as [SessionIdentifierRole.CANONICAL].
     * These carriers directly realize the canonical cross-repo term.
     */
    fun canonicalCarriers(): List<AndroidSessionAxisEntry> =
        carriers.filter { it.identifierRole == SessionIdentifierRole.CANONICAL }

    /**
     * Returns all carriers classified as [SessionIdentifierRole.CONTEXTUAL_ALIAS].
     * These carriers serve multiple session families depending on context;
     * disambiguation requires inspecting the surrounding message type.
     */
    fun contextualAliases(): List<AndroidSessionAxisEntry> =
        carriers.filter { it.identifierRole == SessionIdentifierRole.CONTEXTUAL_ALIAS }

    // ── Session continuity model ──────────────────────────────────────────────

    /**
     * Session continuity model: specifies how each session family's identity
     * behaves across key runtime events (reconnect, recovery, transfer, projection).
     *
     * This model is the authoritative reference for cross-layer session-continuity
     * reasoning on the Android side.  It complements the runtime truth in
     * [RuntimeController], [AttachedRuntimeSession], and [ReconnectRecoveryState].
     */
    val continuityModels: List<SessionFamilyContinuityModel> = listOf(

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.CONTROL_SESSION,
            continuityBehavior = SessionContinuityBehavior.STABLE_ACROSS_RECONNECT,
            surviveReconnect = true,
            surviveTransfer = true,
            surviveInvalidation = false,
            continuityNote = "Control session is maintained by the center; Android echoes " +
                "it from inbound envelopes. Short WS reconnects do not change control-session " +
                "scope. Invalidation (e.g. auth expiry) terminates the session."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.RUNTIME_SESSION,
            continuityBehavior = SessionContinuityBehavior.REFRESHED_ON_RECONNECT,
            surviveReconnect = false,
            surviveTransfer = false,
            surviveInvalidation = false,
            continuityNote = "Runtime session ID is regenerated per WS connection cycle by " +
                "RuntimeController._currentRuntimeSessionId. Each new attach event produces " +
                "a fresh runtime_session_id; the host can use this to detect reconnect events."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION,
            continuityBehavior = SessionContinuityBehavior.STABLE_ACROSS_RECONNECT,
            surviveReconnect = true,
            surviveTransfer = true,
            surviveInvalidation = false,
            continuityNote = "Attached session survives short WS disconnects: the permanent " +
                "WS listener in RuntimeController reopens the session on reconnect. " +
                "Terminated only by: EXPLICIT_DETACH, DISABLE, DISCONNECT (non-recovering), " +
                "or INVALIDATION. New runtime_session_id on each reconnect while sessionId " +
                "remains stable."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION,
            continuityBehavior = SessionContinuityBehavior.TRANSFER_SCOPED,
            surviveReconnect = false,
            surviveTransfer = true,
            surviveInvalidation = false,
            continuityNote = "Transfer session context spans exactly one inbound delegated " +
                "execution lifecycle: ACK→(PROGRESS*)→RESULT. Scoped to the " +
                "DelegatedExecutionSignal chain. A WS disconnect during transfer signals " +
                "DISCONNECT fallback; the transfer session does not recover across reconnects."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.CONVERSATION_SESSION,
            continuityBehavior = SessionContinuityBehavior.CONVERSATION_SCOPED,
            surviveReconnect = true,
            surviveTransfer = false,
            surviveInvalidation = true,
            continuityNote = "Conversation session is scoped to local loop execution " +
                "(LocalLoopTrace / SessionHistorySummary). Independent of cross-device " +
                "attachment state; survives reconnect because it does not depend on the WS " +
                "connection. Does not span cross-device transfer boundaries."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.MESH_SESSION,
            continuityBehavior = SessionContinuityBehavior.MESH_SCOPED,
            surviveReconnect = false,
            surviveTransfer = false,
            surviveInvalidation = false,
            continuityNote = "Mesh session is scoped to one MeshJoin→MeshLeave/MeshResult " +
                "coordination cycle. A WS disconnect terminates the mesh session. " +
                "A new mesh_id is required for each new mesh coordination cycle."
        ),

        SessionFamilyContinuityModel(
            family = CanonicalSessionFamily.DURABLE_RUNTIME_SESSION,
            continuityBehavior = SessionContinuityBehavior.DURABLE_ACROSS_ACTIVATION,
            surviveReconnect = true,
            surviveTransfer = true,
            surviveInvalidation = false,
            continuityNote = "Durable session (PR-1): DurableSessionContinuityRecord.durableSessionId " +
                "persists across WS reconnects and across multiple AttachedRuntimeSession lifetimes " +
                "within a single activation era. The sessionContinuityEpoch counter increments on " +
                "each transparent reconnect (RECONNECT_RECOVERY) so the center can distinguish " +
                "'same era, reconnected' from 'new era.' Terminated only by explicit stop() or " +
                "invalidateSession(); does not survive session invalidation."
        )
    )

    private val continuityModelIndex: Map<CanonicalSessionFamily, SessionFamilyContinuityModel> =
        continuityModels.associateBy { it.family }

    /**
     * Returns the [SessionFamilyContinuityModel] for [family], or `null` if the
     * family has no registered continuity model.
     *
     * In the current implementation every family has a model; `null` is a
     * defensive return for unknown future families.
     */
    fun continuityModelFor(family: CanonicalSessionFamily): SessionFamilyContinuityModel? =
        continuityModelIndex[family]

    // ── Cross-repo alias summary ──────────────────────────────────────────────

    /**
     * Returns the canonical cross-repo term for [androidCarrier], or `null` if the
     * carrier is not registered in the axis.
     *
     * Use this helper in projection/reconciliation logic that needs to map an
     * Android carrier to the corresponding center-canonical term.
     */
    fun crossRepoTermFor(androidCarrier: String): String? =
        entryForCarrier(androidCarrier)?.crossRepoTerm

    // ── PR-37: Session/dispatch alignment helpers ─────────────────────────────

    /**
     * PR-37 — Resolves which session families are "live" (can participate in dispatch)
     * given the current runtime/session state.
     *
     * A session family is "live" when:
     *  - Its [SessionContinuityLayer] aligns with the current runtime mode.
     *  - The runtime state is compatible with that family being active.
     *
     * Rules:
     *  - [SessionContinuityLayer.RUNTIME] and [SessionContinuityLayer.ATTACHMENT] families
     *    require [RuntimeController.RuntimeState.Active] to be live.
     *  - [SessionContinuityLayer.TRANSFER] families additionally require the session to be
     *    ATTACHED (i.e. a delegated task can only be in-flight when ATTACHED).
     *  - [SessionContinuityLayer.CONTROL], [SessionContinuityLayer.CONVERSATION], and
     *    [SessionContinuityLayer.MESH] families are governed by the center and may be
     *    live independent of the Android runtime state.
     *  - [SessionContinuityLayer.DURABLE] families are approximated as live when the runtime
     *    is currently [RuntimeController.RuntimeState.Active].  **Limitation**: the durable
     *    era actually persists across short WS disconnects (until explicit stop or invalidation),
     *    so this approximation may return `false` during a transient disconnect even though the
     *    durable era is still valid.  Callers that need accurate durable-era liveness must pass
     *    the actual [RuntimeController.durableSessionContinuityRecord] to a separate check;
     *    this stateless helper uses `isActive` as a conservative proxy.
     *
     * @param runtimeState      Current [RuntimeController.RuntimeState].
     * @param sessionIsAttached `true` when [RuntimeController.attachedSession] is in
     *                          [AttachedRuntimeSession.State.ATTACHED].
     * @return                  A [Map] from [CanonicalSessionFamily] to `true` (live) or
     *                          `false` (not live) for each registered family.
     */
    fun resolveDispatchAlignmentForState(
        runtimeState: RuntimeController.RuntimeState,
        sessionIsAttached: Boolean
    ): Map<CanonicalSessionFamily, Boolean> {
        val isActive = runtimeState is RuntimeController.RuntimeState.Active
        return CanonicalSessionFamily.entries.associateWith { family ->
            val model = continuityModelFor(family) ?: return@associateWith false
            when (model.continuityBehavior) {
                SessionContinuityBehavior.REFRESHED_ON_RECONNECT  -> isActive
                SessionContinuityBehavior.STABLE_ACROSS_RECONNECT -> isActive
                SessionContinuityBehavior.TRANSFER_SCOPED         -> isActive && sessionIsAttached
                SessionContinuityBehavior.MESH_SCOPED             -> isActive
                SessionContinuityBehavior.CONVERSATION_SCOPED     -> true
                SessionContinuityBehavior.DURABLE_ACROSS_ACTIVATION ->
                    // Conservative approximation: treat durable era as live only when Active.
                    // See method KDoc for the known limitation with transient disconnects.
                    isActive
            }
        }
    }
}

// ── Data model ────────────────────────────────────────────────────────────────

/**
 * Seven canonical session families recognized on the Android side.
 *
 * @property canonicalTerm  Cross-repository canonical vocabulary term.
 * @property wireAlias      Android wire-level alias, or `null` when the Android
 *                          field already uses the canonical term.
 */
enum class CanonicalSessionFamily(
    val canonicalTerm: String,
    val wireAlias: String?
) {
    /**
     * Control-session family: request grouping / orchestration scope.
     *
     * Android wire alias: `session_id` → canonical: `control_session_id`.
     */
    CONTROL_SESSION(
        canonicalTerm = "control_session_id",
        wireAlias = "session_id"
    ),

    /**
     * Runtime-session family: per-WS-connection runtime-scope identifier.
     *
     * No wire alias: Android field name `runtime_session_id` is the canonical term.
     */
    RUNTIME_SESSION(
        canonicalTerm = "runtime_session_id",
        wireAlias = null
    ),

    /**
     * Attached-runtime-session family: stable Android runtime host attachment identity.
     *
     * No wire alias: Android field name (`attached_session_id`) directly realizes
     * the canonical term `attached_runtime_session_id`.
     */
    ATTACHED_RUNTIME_SESSION(
        canonicalTerm = "attached_runtime_session_id",
        wireAlias = null
    ),

    /**
     * Delegation/transfer-session family: lifecycle context for one delegated execution unit.
     *
     * No wire alias at the signal level: `DelegatedExecutionSignal.attachedSessionId`
     * directly realizes `transfer_session_context`.
     */
    DELEGATION_TRANSFER_SESSION(
        canonicalTerm = "transfer_session_context",
        wireAlias = null
    ),

    /**
     * Conversation-session family: local conversation/history timeline identity.
     *
     * No wire alias: Android field name `sessionId` in `LocalLoopTrace` /
     * `SessionHistorySummary` is the canonical conversation identity.
     */
    CONVERSATION_SESSION(
        canonicalTerm = "conversation_session_id",
        wireAlias = null
    ),

    /**
     * Mesh-session family: staged-mesh coordination identity.
     *
     * Android wire alias: `mesh_id` → canonical: `mesh_session_id`.
     */
    MESH_SESSION(
        canonicalTerm = "mesh_session_id",
        wireAlias = "mesh_id"
    ),

    /**
     * Durable runtime session family: activation-era durable identity (PR-1).
     *
     * Spans multiple [ATTACHED_RUNTIME_SESSION] lifetimes and [RUNTIME_SESSION]
     * connection cycles within a single activation era.  Resets only on explicit
     * [RuntimeController.stop] or [RuntimeController.invalidateSession].
     *
     * No wire alias: Android field name `durable_session_id` is the canonical term.
     */
    DURABLE_RUNTIME_SESSION(
        canonicalTerm = "durable_session_id",
        wireAlias = null
    );

    /**
     * `true` when this family uses a transitional Android-local wire alias
     * (i.e. [wireAlias] is non-`null`).
     */
    val hasWireAlias: Boolean get() = wireAlias != null
}

/**
 * Identifier role classification for each Android session carrier.
 *
 * @property label Stable string label used in structured log entries.
 */
enum class SessionIdentifierRole(val label: String) {

    /**
     * The Android field directly realizes the canonical cross-repo term.
     * No naming convergence work is needed.
     */
    CANONICAL("canonical"),

    /**
     * The Android field uses a local alias or wire-level name that differs from
     * the canonical cross-repo term.  Semantic equivalence is frozen; naming
     * convergence is deferred to a follow-up phase.
     */
    TRANSITIONAL_ALIAS("transitional_alias"),

    /**
     * The Android field serves multiple session families depending on context.
     * Semantic disambiguation requires inspecting the surrounding message type.
     */
    CONTEXTUAL_ALIAS("contextual_alias")
}

/**
 * Continuity layer: which runtime plane a session carrier belongs to.
 */
enum class SessionContinuityLayer {
    /** Control/orchestration plane (center-governed session context). */
    CONTROL,
    /** Per-connection runtime scope (regenerated on each WS connect). */
    RUNTIME,
    /** Stable Android runtime attachment (survives short disconnects). */
    ATTACHMENT,
    /** Delegated-execution transfer lifecycle (ACK→PROGRESS→RESULT). */
    TRANSFER,
    /** Local conversation/history timeline (independent of cross-device). */
    CONVERSATION,
    /** Staged-mesh coordination scope (one MeshJoin→MeshLeave cycle). */
    MESH,

    /**
     * Durable activation-era scope (PR-1).
     *
     * Spans multiple [ATTACHMENT] sessions and [RUNTIME] connection cycles within
     * one activation era.  Resets only on [RuntimeController.stop] or
     * [RuntimeController.invalidateSession].
     */
    DURABLE
}

/**
 * Continuity behavior: how a session family's identity behaves across key runtime events.
 */
enum class SessionContinuityBehavior {

    /**
     * Session identity is stable across WS reconnects.  The same identifier is
     * preserved as long as no explicit detach, disable, or invalidation event occurs.
     *
     * Families: [CanonicalSessionFamily.CONTROL_SESSION],
     *           [CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION].
     */
    STABLE_ACROSS_RECONNECT,

    /**
     * Session identity is refreshed on each new WS connection cycle.  A new
     * identifier is generated for each connect/attach event.
     *
     * Families: [CanonicalSessionFamily.RUNTIME_SESSION].
     */
    REFRESHED_ON_RECONNECT,

    /**
     * Session identity is scoped to a single conversation/task/history timeline.
     * Independent of cross-device attachment state.
     *
     * Families: [CanonicalSessionFamily.CONVERSATION_SESSION].
     */
    CONVERSATION_SCOPED,

    /**
     * Session identity is scoped to one delegated-execution transfer lifecycle
     * (ACK→PROGRESS→RESULT).  Does not survive WS disconnects.
     *
     * Families: [CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION].
     */
    TRANSFER_SCOPED,

    /**
     * Session identity is scoped to one mesh coordination cycle
     * (MeshJoin→MeshLeave/MeshResult).  Does not survive WS disconnects.
     *
     * Families: [CanonicalSessionFamily.MESH_SESSION].
     */
    MESH_SCOPED,

    /**
     * Session identity is durable across WS reconnects **and** across successive
     * [AttachedRuntimeSession] lifetimes within a single activation era (PR-1).
     *
     * Unlike [STABLE_ACROSS_RECONNECT], this identity also survives the close-and-reopen
     * of an attached session during a transparent reconnect: the same
     * [DurableSessionContinuityRecord.durableSessionId] is preserved while
     * [DurableSessionContinuityRecord.sessionContinuityEpoch] increments.
     *
     * Resets only on [RuntimeController.stop] or [RuntimeController.invalidateSession].
     *
     * Families: [CanonicalSessionFamily.DURABLE_RUNTIME_SESSION].
     */
    DURABLE_ACROSS_ACTIVATION
}

/**
 * A single entry in the [CanonicalSessionAxis.carriers] registry.
 *
 * @property carrier          Android carrier string (e.g. `"AipMessage.session_id"`).
 * @property sessionFamily    The [CanonicalSessionFamily] this carrier belongs to.
 * @property identifierRole   [SessionIdentifierRole] classification.
 * @property continuityLayer  [SessionContinuityLayer] this carrier belongs to.
 * @property crossRepoTerm    Canonical cross-repository vocabulary term.
 * @property note             Human-readable explanation of the carrier's role and any
 *                            aliasing or transitional notes.
 */
data class AndroidSessionAxisEntry(
    val carrier: String,
    val sessionFamily: CanonicalSessionFamily,
    val identifierRole: SessionIdentifierRole,
    val continuityLayer: SessionContinuityLayer,
    val crossRepoTerm: String,
    val note: String
)

/**
 * Continuity model for a single session family.
 *
 * @property family              The [CanonicalSessionFamily] this model applies to.
 * @property continuityBehavior  [SessionContinuityBehavior] for this family.
 * @property surviveReconnect    `true` when the session identity survives a WS reconnect.
 * @property surviveTransfer     `true` when the session identity survives a delegation
 *                               transfer event (i.e. the identity is stable across
 *                               the ACK→PROGRESS→RESULT lifecycle).
 * @property surviveInvalidation `true` when the session identity survives an invalidation
 *                               event (e.g. [AttachedRuntimeSession.DetachCause.INVALIDATION]).
 * @property continuityNote      Human-readable explanation of continuity semantics.
 */
data class SessionFamilyContinuityModel(
    val family: CanonicalSessionFamily,
    val continuityBehavior: SessionContinuityBehavior,
    val surviveReconnect: Boolean,
    val surviveTransfer: Boolean,
    val surviveInvalidation: Boolean,
    val continuityNote: String
)
