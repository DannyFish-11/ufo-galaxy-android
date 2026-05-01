package com.ufo.galaxy.protocol

/**
 * PR-4 — Canonical Protocol Consistency Rules.
 *
 * Defines explicit, reviewable consistency rules for the most load-bearing
 * cross-repository shared protocol surfaces.  The goal is to stop uncontrolled
 * drift on the most important shared surfaces and make convergence reviewable,
 * without immediately removing any compatibility pathway.
 *
 * ## Problem
 *
 * Without explicit rules, the following drift risks accumulate silently:
 *  - **Enum drift** — new or renamed terminal states introduced without cross-repo
 *    coordination.
 *  - **Terminal-state mismatches** — one side treats a status as terminal while the
 *    other does not.
 *  - **Session identifier drift** — a new session carrier is introduced without being
 *    formally mapped to a canonical session family.
 *  - **Descriptor field inconsistencies** — capability or runtime-profile descriptor
 *    fields diverge between Android and center vocabularies.
 *  - **Alias-based semantic erosion** — transitional aliases gradually become the
 *    de-facto canonical form, obscuring the intended convergence path.
 *  - **Silent divergence** — changes in one repository do not trigger any signal that
 *    the corresponding surface in the other repository requires updating.
 *
 * ## Design
 *
 * [UgcpProtocolConsistencyRules] is an additive, non-breaking registry.  It does not
 * alter any wire contract, runtime behavior, or existing identifier value.  It formalizes
 * the consistency baseline so that:
 *
 *  1. Each critical shared surface has an explicit [ProtocolSurfaceClass] label
 *     (canonical or transitional).
 *  2. Canonical values are enumerated in [ConsistencyRule.canonicalValues].
 *  3. Transitional aliases are listed in [ConsistencyRule.transitionalAliases] with their
 *     canonical target and the reason for the alias.
 *  4. A [ConsistencyCheckResult] can be produced for any surface value via
 *     [checkValue] so that tooling or CI can report surface-level drift.
 *
 * ## Scope
 *
 * The thirteen load-bearing shared surfaces covered are:
 *
 * | Surface | [ProtocolSurface] |
 * |---------|------------------|
 * | Terminal / lifecycle status vocabulary | [ProtocolSurface.TERMINAL_STATE_VOCABULARY] |
 * | Delegated execution result kinds | [ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND] |
 * | Reconciliation signal kind vocabulary | [ProtocolSurface.RECONCILIATION_SIGNAL_KIND] |
 * | Attached session state | [ProtocolSurface.ATTACHED_SESSION_STATE] |
 * | Detach cause | [ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE] |
 * | Reconnect recovery state | [ProtocolSurface.RECONNECT_RECOVERY_STATE] |
 * | Session identifier carriers | [ProtocolSurface.SESSION_IDENTIFIER_CARRIER] |
 * | Runtime profile descriptor names | [ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR] |
 * | Capability / readiness descriptor fields | [ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR] |
 * | Truth-event payload identifiers | [ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER] |
 * | Transfer lifecycle vocabulary | [ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY] |
 * | Staged-mesh execution status | [ProtocolSurface.STAGED_MESH_EXECUTION_STATUS] |
 * | Durable session continuity | [ProtocolSurface.DURABLE_SESSION_CONTINUITY] |
 */

// ── Classification types ──────────────────────────────────────────────────────

/**
 * Classification of a shared protocol surface.
 *
 * - [CANONICAL] — The surface is authoritative and stable.  Values on this surface are the
 *   agreed cross-repository representation.  Changes require explicit cross-repo coordination.
 * - [TRANSITIONAL_COMPATIBILITY] — The surface is retained for compatibility.  The values
 *   or naming are not yet unified with the center canonical vocabulary.  The surface has an
 *   explicit convergence path toward [CANONICAL] that is tracked in [ConsistencyRule.notes].
 */
enum class ProtocolSurfaceClass {
    CANONICAL,
    TRANSITIONAL_COMPATIBILITY
}

/**
 * Identifies one of the load-bearing shared protocol surfaces.
 */
enum class ProtocolSurface(
    val description: String,
    val surfaceClass: ProtocolSurfaceClass
) {
    TERMINAL_STATE_VOCABULARY(
        description = "Lifecycle/terminal status vocabulary used in result payloads and normalization",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    DELEGATED_EXECUTION_RESULT_KIND(
        description = "Terminal outcome discriminator for DelegatedExecutionSignal.ResultKind",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),

    /**
     * PR Block 2 — Reconciliation signal kind vocabulary.
     *
     * Covers the wire discriminator values carried in [com.ufo.galaxy.runtime.ReconciliationSignal.Kind.wireValue]
     * for all Android→V2 reconciliation/governance signal kinds.  These values are consumed
     * by the V2 gateway handler to route incoming Android reconciliation messages to the
     * correct participant-truth reconciliation action.
     *
     * Registering this surface as canonical ensures that any future addition or rename of a
     * [com.ufo.galaxy.runtime.ReconciliationSignal.Kind] entry is caught by the
     * [CrossRepoConsistencyGate] at CI time before it can silently break V2's reconciliation loop.
     */
    RECONCILIATION_SIGNAL_KIND(
        description = "Android→V2 reconciliation/governance signal kind discriminator values (ReconciliationSignal.Kind)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    ATTACHED_SESSION_STATE(
        description = "Attached runtime session lifecycle state (AttachedRuntimeSession.State)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    ATTACHED_SESSION_DETACH_CAUSE(
        description = "Cause of attached session detachment (AttachedRuntimeSession.DetachCause)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    RECONNECT_RECOVERY_STATE(
        description = "WS reconnect recovery lifecycle state (ReconnectRecoveryState)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    SESSION_IDENTIFIER_CARRIER(
        description = "Android session identifier carrier fields and their canonical session family",
        surfaceClass = ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY
    ),
    RUNTIME_PROFILE_DESCRIPTOR(
        description = "Profile identity strings for runtime, transfer, coordination, and truth-event profiles",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    CAPABILITY_READINESS_DESCRIPTOR(
        description = "Capability and readiness descriptor field names reported in capability_report",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    TRUTH_EVENT_PAYLOAD_IDENTIFIER(
        description = "Identifier field names carried in truth-event payload structures",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    TRANSFER_LIFECYCLE_VOCABULARY(
        description = "Canonical control-transfer lifecycle event vocabulary",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),
    STAGED_MESH_EXECUTION_STATUS(
        description = "Terminal execution status values for staged-mesh subtask results (StagedMeshParticipationResult.ExecutionStatus)",
        surfaceClass = ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY
    ),

    /**
     * PR-1 — Durable session continuity contract.
     *
     * Covers the wire key values and activation-source vocabulary used by
     * [com.ufo.galaxy.runtime.DurableSessionContinuityRecord] and the corresponding
     * [com.ufo.galaxy.runtime.AttachedRuntimeHostSessionSnapshot] durable projection fields.
     */
    DURABLE_SESSION_CONTINUITY(
        description = "Durable session continuity wire key vocabulary and activation-source values (PR-1)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    ),

    /**
     * PR-4B — V2 cross-system observability trace field names (PR-G).
     *
     * Covers the wire field name constants used in V2 observability payloads and log entries
     * to carry cross-system dispatch tracing identifiers.  Gated so that renaming a constant
     * in [com.ufo.galaxy.runtime.RuntimeObservabilityMetadata] is caught at CI time.
     */
    OBSERVABILITY_TRACE_FIELD_NAMES(
        description = "V2 cross-system observability trace field name constants used in log entries and payloads (PR-G/PR-4B)",
        surfaceClass = ProtocolSurfaceClass.CANONICAL
    )
}

/**
 * A transitional alias entry: the Android-side alias value, the canonical target value
 * it maps to, and the reason it has not yet been renamed.
 */
data class TransitionalAlias(
    val aliasValue: String,
    val canonicalTarget: String,
    val reason: String
)

/**
 * An explicit consistency rule for one [ProtocolSurface].
 *
 * @property surface The shared protocol surface this rule governs.
 * @property canonicalValues The exhaustive set of canonical values on this surface.
 *   A value not in this set and not listed in [transitionalAliases] is a drift candidate.
 * @property transitionalAliases Aliases that are currently tolerated.  Each alias has a
 *   canonical target and a stated reason for the transitional tolerance.
 * @property notes Human-readable notes on convergence intent, authority, or open questions.
 */
data class ConsistencyRule(
    val surface: ProtocolSurface,
    val canonicalValues: Set<String>,
    val transitionalAliases: List<TransitionalAlias> = emptyList(),
    val notes: String = ""
)

/**
 * Classification of a value checked against a [ConsistencyRule].
 */
enum class ConsistencyCheckStatus {
    /** The value is a canonical value on this surface. */
    CANONICAL,
    /** The value is a known transitional alias; it maps to a canonical target. */
    TRANSITIONAL_ALIAS,
    /** The value is not canonical and not a known alias — potential drift. */
    DRIFT_CANDIDATE
}

/**
 * Result of checking a single value against a [ConsistencyRule].
 *
 * @property surface The surface the check was performed on.
 * @property rawValue The value that was checked.
 * @property status Classification of the value.
 * @property canonicalTarget For [ConsistencyCheckStatus.TRANSITIONAL_ALIAS]: the canonical
 *   target this alias maps to.  `null` for [CANONICAL] and [DRIFT_CANDIDATE].
 * @property aliasReason For [ConsistencyCheckStatus.TRANSITIONAL_ALIAS]: the stated reason
 *   for tolerance.  `null` for [CANONICAL] and [DRIFT_CANDIDATE].
 */
data class ConsistencyCheckResult(
    val surface: ProtocolSurface,
    val rawValue: String,
    val status: ConsistencyCheckStatus,
    val canonicalTarget: String? = null,
    val aliasReason: String? = null
)

// ── Registry ──────────────────────────────────────────────────────────────────

/**
 * Registry of canonical consistency rules for the most load-bearing shared protocol surfaces.
 *
 * Callers should use [checkValue] to evaluate individual values and [allRules] to enumerate
 * the full set of rules for documentation or CI-based consistency checks.
 */
object UgcpProtocolConsistencyRules {

    /**
     * Canonical lifecycle/terminal status vocabulary.
     *
     * These are the cross-repository agreed terminal status strings used in result payloads,
     * lifecycle status fields, and normalization maps.  [UgcpSharedSchemaAlignment.lifecycleStatusNormalizations]
     * maps legacy aliases to these canonical values.
     *
     * Canonical values: `success`, `error`, `cancelled`, `timeout`, `rejected`, `partial`, `disabled`.
     * Transitional aliases: `completed` → `success`, `failed` / `failure` → `error`, `no_op` → `disabled`.
     */
    val terminalStateVocabularyRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.TERMINAL_STATE_VOCABULARY,
        canonicalValues = setOf(
            "success",
            "error",
            "cancelled",
            "timeout",
            "rejected",
            "partial",
            "disabled"
        ),
        transitionalAliases = listOf(
            TransitionalAlias(
                aliasValue = "completed",
                canonicalTarget = "success",
                reason = "Legacy result status used by early protocol versions; normalized by lifecycleStatusNormalizations"
            ),
            TransitionalAlias(
                aliasValue = "failed",
                canonicalTarget = "error",
                reason = "Legacy failure status; normalized by lifecycleStatusNormalizations"
            ),
            TransitionalAlias(
                aliasValue = "failure",
                canonicalTarget = "error",
                reason = "Legacy failure status variant; normalized by lifecycleStatusNormalizations"
            ),
            TransitionalAlias(
                aliasValue = "no_op",
                canonicalTarget = "disabled",
                reason = "Legacy no-op status; normalized to disabled by lifecycleStatusNormalizations"
            )
        ),
        notes = "Normalization is enforced via UgcpSharedSchemaAlignment.normalizeLifecycleStatus(). " +
            "Transitional aliases must normalize before entering canonical routing. " +
            "New terminal status values must be added here with cross-repo coordination."
    )

    /**
     * Delegated execution result kind vocabulary.
     *
     * These are the terminal outcome discriminators carried in DelegatedExecutionSignal
     * RESULT signals.  They are canonical Android-side values and are consumed by the
     * center host for session-truth reconciliation.
     *
     * Canonical values: `completed`, `failed`, `timeout`, `cancelled`, `rejected`.
     *
     * Note: Unlike the general lifecycle status vocabulary, `completed` is canonical here
     * (not an alias) because DelegatedExecutionSignal.ResultKind uses it as the primary
     * success discriminator.  The center host must not assume `completed` = `success` here
     * without going through the delegated result surface specifically.
     */
    val delegatedExecutionResultKindRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND,
        canonicalValues = setOf(
            "completed",
            "failed",
            "timeout",
            "cancelled",
            "rejected"
        ),
        transitionalAliases = emptyList(),
        notes = "Carried by DelegatedExecutionSignal.ResultKind.wireValue. " +
            "These values are canonical for the delegated execution surface specifically. " +
            "Do not normalize 'completed' to 'success' on this surface — the distinction matters " +
            "for host-side session-truth reconciliation."
    )

    /**
     * PR Block 2 — Reconciliation signal kind vocabulary.
     *
     * These are the canonical wire discriminator values carried in
     * [com.ufo.galaxy.runtime.ReconciliationSignal.Kind.wireValue].  They are consumed
     * by the V2 gateway handler to route each incoming Android→V2 reconciliation/governance
     * message to the correct participant-truth reconciliation action.
     *
     * Canonical values:
     *  - `task_accepted`          — Android accepted a delegated task; V2 marks task as active.
     *  - `task_status_update`     — Intermediate execution status; V2 updates in-flight view.
     *  - `task_result`            — Task completed successfully; V2 closes task as success.
     *  - `task_cancelled`         — Task was cancelled; V2 closes task as cancelled.
     *  - `task_failed`            — Task failed; V2 closes task as failed.
     *  - `participant_state`      — Participant health/readiness changed; V2 updates canonical view.
     *  - `runtime_truth_snapshot` — Full reconciliation snapshot; V2 performs full truth pass.
     *
     * All seven values must remain stable across the integrated Android↔V2 protocol surface.
     * Adding, removing, or renaming any value requires explicit cross-repo coordination with
     * the V2 gateway handler registration table.
     */
    val reconciliationSignalKindRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.RECONCILIATION_SIGNAL_KIND,
        canonicalValues = setOf(
            "task_accepted",
            "task_status_update",
            "task_result",
            "task_cancelled",
            "task_failed",
            "participant_state",
            "runtime_truth_snapshot"
        ),
        transitionalAliases = emptyList(),
        notes = "Carried by ReconciliationSignal.Kind.wireValue (PR Block 2). " +
            "These values are the canonical Android→V2 reconciliation/governance signal kind " +
            "discriminators.  V2 gateway handler registration MUST cover every value here. " +
            "Do not add, remove, or rename values without updating both the Android " +
            "ReconciliationSignal.Kind enum and the V2 gateway handler table."
    )

    /**
     * Attached runtime session state vocabulary.
     *
     * These are the lifecycle state values carried in AttachedRuntimeSession.State.wireValue
     * and in the KEY_STATE metadata key in attached-session payloads.
     *
     * Canonical values: `attached`, `detaching`, `detached`.
     */
    val attachedSessionStateRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.ATTACHED_SESSION_STATE,
        canonicalValues = setOf(
            "attached",
            "detaching",
            "detached"
        ),
        transitionalAliases = emptyList(),
        notes = "Carried by AttachedRuntimeSession.State.wireValue and KEY_STATE metadata key. " +
            "These values are stable cross-repo wire values. " +
            "Do not add new state values without updating AttachedRuntimeSession.State and " +
            "coordinating with center-side session-truth consumers."
    )

    /**
     * Attached runtime session detach cause vocabulary.
     *
     * These are the detachment cause values carried in AttachedRuntimeSession.DetachCause.wireValue
     * and in the KEY_DETACH_CAUSE metadata key in attached-session payloads.
     *
     * Canonical values: `explicit_detach`, `disconnect`, `disable`, `invalidation`.
     */
    val attachedSessionDetachCauseRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE,
        canonicalValues = setOf(
            "explicit_detach",
            "disconnect",
            "disable",
            "invalidation"
        ),
        transitionalAliases = emptyList(),
        notes = "Carried by AttachedRuntimeSession.DetachCause.wireValue and KEY_DETACH_CAUSE metadata key. " +
            "Detach cause is present only when state is 'detaching' or 'detached'. " +
            "New causes require explicit cross-repo coordination."
    )

    /**
     * Reconnect recovery state vocabulary.
     *
     * These are the reconnect recovery lifecycle state values carried in
     * ReconnectRecoveryState.wireValue and in structured log entries under
     * TAG_RECONNECT_RECOVERY.
     *
     * Canonical values: `idle`, `recovering`, `recovered`, `failed`.
     */
    val reconnectRecoveryStateRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.RECONNECT_RECOVERY_STATE,
        canonicalValues = setOf(
            "idle",
            "recovering",
            "recovered",
            "failed"
        ),
        transitionalAliases = emptyList(),
        notes = "Carried by ReconnectRecoveryState.wireValue. " +
            "Observable via RuntimeController.reconnectRecoveryState StateFlow. " +
            "These states are authoritative Android-side truth for reconnect lifecycle. " +
            "Do not change wireValue constants without updating the TAG_RECONNECT_RECOVERY log contract."
    )

    /**
     * Session identifier carrier classification.
     *
     * This rule covers which Android session identifier carrier fields are canonical matches
     * to cross-repo session families and which are transitional aliases.
     *
     * Canonical values (Android wire names that directly match or canonical cross-repo terms):
     *  - `runtime_session_id`   — direct canonical match to the cross-repo canonical term
     *  - `attached_session_id`  — direct canonical match to `attached_runtime_session_id`
     *  - `signal_id`            — direct canonical match to `execution_instance_id`
     *  - `control_session_id`   — canonical cross-repo term; Android currently uses `session_id` as wire alias
     *  - `mesh_session_id`      — canonical cross-repo term; Android currently uses `mesh_id` as wire alias
     *  - `durable_session_id`   — canonical cross-repo term; PR-1 durable runtime session era identity
     *
     * Transitional aliases (Android wire names that differ from the canonical cross-repo term):
     *  - `session_id`  → `control_session_id`  (TRANSITIONAL_ALIAS)
     *  - `mesh_id`     → `mesh_session_id`      (TRANSITIONAL_ALIAS)
     */
    val sessionIdentifierCarrierRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.SESSION_IDENTIFIER_CARRIER,
        canonicalValues = setOf(
            "runtime_session_id",
            "attached_session_id",
            "signal_id",
            "control_session_id",
            "mesh_session_id",
            "durable_session_id"
        ),
        transitionalAliases = listOf(
            TransitionalAlias(
                aliasValue = "session_id",
                canonicalTarget = "control_session_id",
                reason = "Android wire carrier for control_session_id; naming convergence deferred per CanonicalSessionAxis § 3.1"
            ),
            TransitionalAlias(
                aliasValue = "mesh_id",
                canonicalTarget = "mesh_session_id",
                reason = "Android wire carrier for mesh_session_id; naming convergence deferred per CanonicalSessionAxis § 3.1"
            )
        ),
        notes = "New session identifier carriers must be added here and mapped to a CanonicalSessionFamily entry " +
            "in CanonicalSessionAxis before being introduced in wire payloads. " +
            "Carrier introduction without this mapping is the primary mechanism of session identifier drift."
    )

    /**
     * Runtime profile descriptor names.
     *
     * These are the profile identity strings used to identify Android runtime profiles.
     * They are declared in UgcpSharedSchemaAlignment and must remain stable wire values.
     *
     * Canonical values:
     *  - `ugcp.runtime_ws_profile.android`
     *  - `ugcp.control_transfer_profile.android`
     *  - `ugcp.coordination_profile.android`
     *  - `ugcp.truth_event_model.android`
     *  - `ugcp.conformance_surface.android`
     */
    val runtimeProfileDescriptorRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR,
        canonicalValues = setOf(
            "ugcp.runtime_ws_profile.android",
            "ugcp.control_transfer_profile.android",
            "ugcp.coordination_profile.android",
            "ugcp.truth_event_model.android",
            "ugcp.conformance_surface.android"
        ),
        transitionalAliases = emptyList(),
        notes = "Profile identity strings are the cross-repo identifiers for Android runtime participation surfaces. " +
            "Do not rename or add new profile strings without cross-repo coordination. " +
            "Declared in UgcpSharedSchemaAlignment profile name constants."
    )

    /**
     * Capability and readiness descriptor fields.
     *
     * These are the field names reported in capability_report payloads and consumed by the
     * center's scheduling/selection logic.  They are protocol-facing and must not drift
     * independently of the center's consumption model.
     *
     * Canonical values:
     *  - `source_runtime_posture` — participation posture field in AipMessage envelopes
     *  - `model_ready` — indicates model inference capability is available
     *  - `accessibility_ready` — indicates accessibility execution capability is available
     *  - `overlay_ready` — indicates overlay rendering capability is available
     *  - `degraded_mode` — indicates the device is operating in a degraded capability state
     */
    val capabilityReadinessDescriptorRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR,
        canonicalValues = setOf(
            "source_runtime_posture",
            "model_ready",
            "accessibility_ready",
            "overlay_ready",
            "degraded_mode"
        ),
        transitionalAliases = emptyList(),
        notes = "These field names are consumed by center-side scheduling and selection logic. " +
            "Do not rename or add new readiness fields without coordinating with center capability consumers. " +
            "source_runtime_posture canonical values: 'control_only' | 'join_runtime' (SourceRuntimePosture)."
    )

    /**
     * Truth-event payload identifier fields.
     *
     * These are the identifier field names that must be present in truth-event payload
     * structures to enable cross-repo correlation, idempotency, and session-truth reconciliation.
     *
     * Canonical values:
     *  - `task_id` — primary per-task identity across Android runtime/control flows
     *  - `trace_id` — end-to-end correlation identity; stable across one distributed chain
     *  - `runtime_session_id` — per-connection runtime scope identifier
     *  - `signal_id` — idempotency key for delegated execution signals
     *  - `emission_seq` — ordering discriminator for delegated execution signals
     *  - `attached_session_id` — attached runtime session scope in delegated signals
     */
    val truthEventPayloadIdentifierRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER,
        canonicalValues = setOf(
            "task_id",
            "trace_id",
            "runtime_session_id",
            "signal_id",
            "emission_seq",
            "attached_session_id"
        ),
        transitionalAliases = emptyList(),
        notes = "These identifier fields must be present in truth-event payloads for cross-repo reconciliation. " +
            "Presence rules: task_id + trace_id + runtime_session_id are required on all result payloads. " +
            "signal_id + emission_seq + attached_session_id are required on delegated execution signals. " +
            "Do not remove or rename these fields without explicit cross-repo coordination."
    )

    /**
     * Transfer lifecycle vocabulary.
     *
     * These are the canonical control-transfer lifecycle event terms.  They define the
     * cross-repo agreed vocabulary for transfer acceptance, rejection, cancellation,
     * expiry, adoption, and resumption events.
     *
     * Canonical values: `transfer_accept`, `transfer_reject`, `transfer_cancel`,
     * `transfer_expire`, `transfer_adopt`, `transfer_resume`.
     */
    val transferLifecycleVocabularyRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY,
        canonicalValues = setOf(
            "transfer_accept",
            "transfer_reject",
            "transfer_cancel",
            "transfer_expire",
            "transfer_adopt",
            "transfer_resume"
        ),
        transitionalAliases = emptyList(),
        notes = "Android events map to these canonical transfer lifecycle terms via " +
            "UgcpSharedSchemaAlignment.transferEventAlignments. " +
            "The transfer vocabulary is canonical (not transitional) and applies to both " +
            "the takeover/response path and the delegated execution lifecycle signal path."
    )

    /**
     * Staged-mesh subtask execution status vocabulary.
     *
     * These are the terminal execution status values carried in
     * [com.ufo.galaxy.runtime.StagedMeshParticipationResult.ExecutionStatus.wireValue]
     * and in staged-mesh result payloads consumed by the V2 coordinator.
     *
     * Canonical values (using the cross-repo terminal state vocabulary):
     *  - `success`   — subtask completed successfully
     *  - `error`     — canonical error/failure terminal state
     *  - `cancelled` — subtask was cancelled before or during execution
     *  - `blocked`   — execution blocked by a rollout-control gate
     *
     * Transitional alias (Android-side wire value differs from canonical term):
     *  - `failure` → `error` — [StagedMeshParticipationResult.ExecutionStatus.FAILURE] uses
     *    `failure` as its wire value; the canonical terminal state vocabulary term is `error`.
     *    This alias is explicitly tolerated here and tracked for future naming convergence.
     */
    val stagedMeshExecutionStatusRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.STAGED_MESH_EXECUTION_STATUS,
        canonicalValues = setOf(
            "success",
            "error",
            "cancelled",
            "blocked"
        ),
        transitionalAliases = listOf(
            TransitionalAlias(
                aliasValue = "failure",
                canonicalTarget = "error",
                reason = "StagedMeshParticipationResult.ExecutionStatus.FAILURE uses 'failure' as its wire value; " +
                    "the canonical cross-repo terminal state vocabulary term is 'error'. " +
                    "Naming convergence deferred to a follow-up phase per TERMINAL_STATE_VOCABULARY rule."
            )
        ),
        notes = "Carried by StagedMeshParticipationResult.ExecutionStatus.wireValue. " +
            "The 'blocked' value is staged-mesh-specific (rollout-control gate) and does not appear " +
            "in the general TERMINAL_STATE_VOCABULARY. " +
            "The 'failure' wire value is a transitional alias — do not add new execution status values " +
            "without updating this rule and coordinating with V2 coordinator consumers."
    )

    /**
     * PR-1 — Durable session continuity vocabulary.
     *
     * Covers the activation-source values used by [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.activationSource]
     * (i.e., the SessionOpenSource.wireValue values that can start a new durable era) and the
     * canonical wire key strings carried in
     * [com.ufo.galaxy.runtime.DurableSessionContinuityRecord.toMetadataMap] and
     * [com.ufo.galaxy.runtime.AttachedRuntimeHostSessionSnapshot.toMap].
     *
     * Canonical activation-source values:
     *  - `user_activation`   — user-initiated enable/start path
     *  - `background_restore`— process/service restore path
     *  - `test_only`         — synthetic test-only activation
     *
     * Canonical wire key values:
     *  - `durable_session_id`
     *  - `session_continuity_epoch`
     *  - `durable_session_activation_epoch_ms`
     *  - `durable_session_activation_source`
     *  - `snapshot_durable_session_id`
     *  - `snapshot_session_continuity_epoch`
     */
    val durableSessionContinuityRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.DURABLE_SESSION_CONTINUITY,
        canonicalValues = setOf(
            // Activation source values (DurableSessionContinuityRecord.activationSource)
            "user_activation",
            "background_restore",
            "test_only",
            // Wire key names (DurableSessionContinuityRecord companion + snapshot keys)
            "durable_session_id",
            "session_continuity_epoch",
            "durable_session_activation_epoch_ms",
            "durable_session_activation_source",
            "snapshot_durable_session_id",
            "snapshot_session_continuity_epoch"
        ),
        transitionalAliases = emptyList(),
        notes = "PR-1 durable session continuity. " +
            "DurableSessionContinuityRecord.durableSessionId is the top-level stable Android session " +
            "identity; it persists across WS reconnects within a single activation era and resets " +
            "only on stop() or invalidateSession(). " +
            "The sessionContinuityEpoch counter increments on each RECONNECT_RECOVERY open. " +
            "These values are canonical; do not add new activation-source values without updating " +
            "SessionOpenSource and coordinating with the center-side durable session registry."
    )

    /**
     * PR-4B — V2 cross-system observability trace field name vocabulary (PR-G).
     *
     * Covers the wire field name constants declared in
     * [com.ufo.galaxy.runtime.RuntimeObservabilityMetadata] that appear in structured log
     * entries and in observability payload fields across the V2 dispatch chain.
     *
     * Canonical wire field names:
     *  - `dispatch_trace_id`       — cross-system dispatch trace correlation identifier
     *  - `lifecycle_event_id`      — V2 lifecycle event that triggered this dispatch
     *  - `session_correlation_id`  — session-level correlation identifier spanning multiple dispatches
     *
     * These values are gated so that a constant rename in [com.ufo.galaxy.runtime.RuntimeObservabilityMetadata]
     * that diverges from the canonical cross-repo vocabulary is caught at CI time.
     */
    val observabilityTraceFieldNamesRule: ConsistencyRule = ConsistencyRule(
        surface = ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES,
        canonicalValues = setOf(
            "dispatch_trace_id",
            "lifecycle_event_id",
            "session_correlation_id"
        ),
        transitionalAliases = emptyList(),
        notes = "Wire field names introduced by V2 production-grade runtime observability (PR-G). " +
            "These values must match RuntimeObservabilityMetadata.FIELD_DISPATCH_TRACE_ID, " +
            "FIELD_LIFECYCLE_EVENT_ID, and FIELD_SESSION_CORRELATION_ID exactly. " +
            "Do not rename without cross-repo coordination — these identifiers appear in V2 " +
            "structured logs, payloads, and cross-system trace pipelines."
    )

    /**
     * All consistency rules, indexed by [ProtocolSurface].
     */
    val allRules: Map<ProtocolSurface, ConsistencyRule> = mapOf(
        ProtocolSurface.TERMINAL_STATE_VOCABULARY to terminalStateVocabularyRule,
        ProtocolSurface.DELEGATED_EXECUTION_RESULT_KIND to delegatedExecutionResultKindRule,
        ProtocolSurface.RECONCILIATION_SIGNAL_KIND to reconciliationSignalKindRule,
        ProtocolSurface.ATTACHED_SESSION_STATE to attachedSessionStateRule,
        ProtocolSurface.ATTACHED_SESSION_DETACH_CAUSE to attachedSessionDetachCauseRule,
        ProtocolSurface.RECONNECT_RECOVERY_STATE to reconnectRecoveryStateRule,
        ProtocolSurface.SESSION_IDENTIFIER_CARRIER to sessionIdentifierCarrierRule,
        ProtocolSurface.RUNTIME_PROFILE_DESCRIPTOR to runtimeProfileDescriptorRule,
        ProtocolSurface.CAPABILITY_READINESS_DESCRIPTOR to capabilityReadinessDescriptorRule,
        ProtocolSurface.TRUTH_EVENT_PAYLOAD_IDENTIFIER to truthEventPayloadIdentifierRule,
        ProtocolSurface.TRANSFER_LIFECYCLE_VOCABULARY to transferLifecycleVocabularyRule,
        ProtocolSurface.STAGED_MESH_EXECUTION_STATUS to stagedMeshExecutionStatusRule,
        ProtocolSurface.DURABLE_SESSION_CONTINUITY to durableSessionContinuityRule,
        ProtocolSurface.OBSERVABILITY_TRACE_FIELD_NAMES to observabilityTraceFieldNamesRule
    )

    /**
     * The set of surfaces classified as [ProtocolSurfaceClass.CANONICAL].
     */
    val canonicalSurfaces: Set<ProtocolSurface> =
        ProtocolSurface.entries.filter {
            it.surfaceClass == ProtocolSurfaceClass.CANONICAL
        }.toSet()

    /**
     * The set of surfaces classified as [ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY].
     */
    val transitionalSurfaces: Set<ProtocolSurface> =
        ProtocolSurface.entries.filter {
            it.surfaceClass == ProtocolSurfaceClass.TRANSITIONAL_COMPATIBILITY
        }.toSet()

    /**
     * Checks [value] against the consistency rule for [surface] and returns a
     * [ConsistencyCheckResult] classifying the value.
     *
     * @param surface The protocol surface to check against.
     * @param value The raw value to evaluate (e.g. a wire-level string).
     * @return [ConsistencyCheckResult] with status [ConsistencyCheckStatus.CANONICAL],
     *   [ConsistencyCheckStatus.TRANSITIONAL_ALIAS], or [ConsistencyCheckStatus.DRIFT_CANDIDATE].
     * @throws IllegalArgumentException if no rule is registered for [surface].
     */
    fun checkValue(surface: ProtocolSurface, value: String): ConsistencyCheckResult {
        val rule = requireNotNull(allRules[surface]) {
            "No consistency rule registered for surface $surface"
        }
        if (value in rule.canonicalValues) {
            return ConsistencyCheckResult(
                surface = surface,
                rawValue = value,
                status = ConsistencyCheckStatus.CANONICAL
            )
        }
        val alias = rule.transitionalAliases.firstOrNull { it.aliasValue == value }
        if (alias != null) {
            return ConsistencyCheckResult(
                surface = surface,
                rawValue = value,
                status = ConsistencyCheckStatus.TRANSITIONAL_ALIAS,
                canonicalTarget = alias.canonicalTarget,
                aliasReason = alias.reason
            )
        }
        return ConsistencyCheckResult(
            surface = surface,
            rawValue = value,
            status = ConsistencyCheckStatus.DRIFT_CANDIDATE
        )
    }

    /**
     * Returns all transitional aliases across all registered rules.
     *
     * Useful for generating documentation or producing a reviewable list of all
     * alias-based compatibility allowances that are currently in effect.
     */
    fun allTransitionalAliases(): List<Pair<ProtocolSurface, TransitionalAlias>> =
        allRules.flatMap { (surface, rule) ->
            rule.transitionalAliases.map { surface to it }
        }

    /**
     * Returns all canonical values across all registered rules for [surface].
     *
     * Useful for CI-based checks that enumerate allowed values and flag unknown ones.
     */
    fun canonicalValuesFor(surface: ProtocolSurface): Set<String> =
        allRules[surface]?.canonicalValues ?: emptySet()

    /**
     * Returns all transitional alias values for [surface].
     */
    fun transitionalAliasValuesFor(surface: ProtocolSurface): Set<String> =
        allRules[surface]?.transitionalAliases?.map { it.aliasValue }?.toSet() ?: emptySet()

    /**
     * Returns the complete set of tolerated values (canonical + transitional aliases)
     * for [surface].  A value outside this set is a drift candidate.
     */
    fun toleratedValuesFor(surface: ProtocolSurface): Set<String> =
        canonicalValuesFor(surface) + transitionalAliasValuesFor(surface)

    /**
     * Returns `true` if [value] is tolerated on [surface] (canonical or transitional alias),
     * or `false` if it is a drift candidate.
     */
    fun isTolerated(surface: ProtocolSurface, value: String): Boolean =
        value in toleratedValuesFor(surface)
}
