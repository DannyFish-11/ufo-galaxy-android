package com.ufo.galaxy.agent

import com.ufo.galaxy.capability.AndroidCapabilityVector
import com.ufo.galaxy.runtime.RuntimeHostDescriptor

/**
 * **Canonical Android-side delegated-runtime handoff contract** (PR-9,
 * post-#533 dual-repo runtime unification master plan — Canonical Cross-Repo
 * Delegated-Runtime Handoff Contract Foundations, Android side).
 *
 * [DelegatedHandoffContract] is the authoritative Android-side representation of
 * the *full contract* established when the main-repo host delegates a runtime work
 * unit to Android for execution.  It extends the domain-model work unit
 * ([DelegatedRuntimeUnit]) with the contract-level metadata that describes:
 *
 *  - **Why** the work is being delegated ([handoffReason]).
 *  - **Who** is delegating (originating host identity: [originatingHostId] and
 *    [originatingFormationRole]).
 *  - **What** capabilities the delegated task requires ([requiredCapabilityDimensions]).
 *  - **Where** execution should continue ([continuationToken], complementing the
 *    human-readable [DelegatedRuntimeUnit.checkpoint]).
 *
 * ## Design intent
 *
 * Before PR-9 Android had a domain-model work unit ([DelegatedRuntimeUnit]) that
 * captured *what to execute* and a session binding, but no explicit model for the
 * *contract* between the main-repo dispatcher and the Android receiver — the
 * assumptions about why the handoff occurred, what capabilities are expected, and
 * what continuation context exists were scattered across envelope fields and implicit
 * in calling code.
 *
 * [DelegatedHandoffContract] closes that gap.  It is the single authoritative
 * entity that both the dispatch path and the receipt/validation path can depend on
 * without re-parsing raw JSON or making implicit assumptions.
 *
 * ## Relationship to [DelegatedRuntimeUnit]
 *
 * [DelegatedRuntimeUnit] is the **work unit** model — what Android must execute.
 * [DelegatedHandoffContract] is the **contract** model — why and under what terms
 * the work was delegated.  A contract always wraps exactly one [DelegatedRuntimeUnit]
 * and adds the delegation-specific metadata.
 *
 * ## Lifecycle
 *
 * A contract is produced by [DelegatedHandoffContract.from] once
 * [DelegatedRuntimeReceiver] has accepted the inbound envelope (i.e. after the
 * session gate has passed).  It is then passed alongside the
 * [com.ufo.galaxy.runtime.DelegatedActivationRecord] to the dispatch path so that
 * contract metadata is available throughout the activation lifecycle.
 *
 * ## Obtaining an instance
 * Use [DelegatedHandoffContract.from]:
 * ```kotlin
 * val contract = DelegatedHandoffContract.from(
 *     unit     = receiverResult.unit,
 *     envelope = inboundEnvelope
 * )
 * ```
 *
 * @property unit                     The [DelegatedRuntimeUnit] produced from the accepted
 *                                    inbound envelope; carries the work identity and session binding.
 * @property handoffReason            Resolved reason the main-repo delegated this unit;
 *                                    defaults to [HandoffReason.DEFAULT] for legacy senders that
 *                                    do not supply [TakeoverRequestEnvelope.handoff_reason].
 * @property originatingHostId        [RuntimeHostDescriptor.hostId] of the dispatching
 *                                    device; `null` for legacy senders.
 * @property originatingFormationRole [RuntimeHostDescriptor.FormationRole] of the originating
 *                                    device; `null` for legacy senders.  Parsed from wire value
 *                                    via [RuntimeHostDescriptor.FormationRole.fromValue].
 * @property requiredCapabilityDimensions Set of [AndroidCapabilityVector.ExecutionDimension]
 *                                    values the delegated task expects the receiver to satisfy;
 *                                    empty for legacy senders.
 * @property continuationToken        Opaque, stable machine-readable continuation state token
 *                                    produced by the originating executor; `null` when not provided.
 *                                    More structured than [DelegatedRuntimeUnit.checkpoint]
 *                                    (which is human-readable progress description).
 * @property contractVersion          Contract schema version; [CURRENT_CONTRACT_VERSION] for
 *                                    contracts produced by this factory.
 */
data class DelegatedHandoffContract(
    val unit: DelegatedRuntimeUnit,
    val handoffReason: HandoffReason,
    val originatingHostId: String?,
    val originatingFormationRole: RuntimeHostDescriptor.FormationRole?,
    val requiredCapabilityDimensions: Set<AndroidCapabilityVector.ExecutionDimension>,
    val continuationToken: String?,
    val contractVersion: Int = CURRENT_CONTRACT_VERSION
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Reason why the main-repo host delegated this runtime work unit to Android.
     *
     * Carried on the wire as [wireValue] in the
     * [TakeoverRequestEnvelope.handoff_reason] field.
     *
     * @property wireValue Stable lowercase string used in JSON payloads and logging.
     */
    enum class HandoffReason(val wireValue: String) {

        /**
         * The originating device lacks a capability that Android provides.
         *
         * Typical example: the main-repo PC has no accessibility execution surface,
         * so Android is delegated the task because it has
         * [AndroidCapabilityVector.ExecutionDimension.ACCESSIBILITY_EXECUTION].
         */
        CAPABILITY_DELEGATION("capability_delegation"),

        /**
         * The task is being continued on Android from a previous execution on another
         * device.  The originating device has partially progressed the task; Android is
         * expected to resume from the [DelegatedRuntimeUnit.checkpoint] or
         * [DelegatedHandoffContract.continuationToken].
         */
        CONTINUATION("continuation"),

        /**
         * The main-repo host is distributing load across the formation by delegating
         * this task to Android, even though it could execute it locally.
         */
        LOAD_BALANCING("load_balancing"),

        /**
         * An operator or orchestration policy explicitly initiated the takeover,
         * bypassing normal capability-matching or load-balancing logic.
         *
         * This is also the [DEFAULT] used when [TakeoverRequestEnvelope.handoff_reason]
         * is absent (legacy senders).
         */
        EXPLICIT_TAKEOVER("explicit_takeover");

        companion object {
            /**
             * Parses [value] to a [HandoffReason], returning [DEFAULT] for unknown or null inputs.
             *
             * @param value Wire string from [TakeoverRequestEnvelope.handoff_reason]; may be null.
             */
            fun fromValue(value: String?): HandoffReason =
                entries.firstOrNull { it.wireValue == value } ?: DEFAULT

            /** Default reason used when [TakeoverRequestEnvelope.handoff_reason] is absent. */
            val DEFAULT: HandoffReason = EXPLICIT_TAKEOVER
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns `true` when this contract declares that the task is a capability-gap
     * delegation ([HandoffReason.CAPABILITY_DELEGATION]).
     */
    val isCapabilityDelegation: Boolean
        get() = handoffReason == HandoffReason.CAPABILITY_DELEGATION

    /**
     * Returns `true` when this contract declares that the task is a continuation
     * of in-progress work from another device ([HandoffReason.CONTINUATION]).
     *
     * Callers can rely on [DelegatedRuntimeUnit.checkpoint] and [continuationToken]
     * being more likely to be non-null in this case.
     */
    val isContinuation: Boolean
        get() = handoffReason == HandoffReason.CONTINUATION

    /**
     * Returns `true` when this contract carries a [continuationToken] that the
     * Android executor should honour for state restoration.
     */
    val hasContinuationToken: Boolean
        get() = continuationToken != null

    /**
     * Returns `true` when the specified [dimension] is listed as required by this
     * handoff contract.
     *
     * @param dimension The [AndroidCapabilityVector.ExecutionDimension] to check.
     */
    fun isCapabilityDimensionRequired(dimension: AndroidCapabilityVector.ExecutionDimension): Boolean =
        dimension in requiredCapabilityDimensions

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical metadata map for wire transmission or diagnostic logging.
     *
     * Keys always present:
     *  - [KEY_UNIT_ID]              — delegated unit identifier (from [unit]).
     *  - [KEY_TASK_ID]              — task identifier (from [unit]).
     *  - [KEY_TRACE_ID]             — end-to-end trace identifier (from [unit]).
     *  - [KEY_ATTACHED_SESSION_ID]  — session this contract was received under.
     *  - [KEY_HANDOFF_REASON]       — [HandoffReason.wireValue].
     *  - [KEY_CONTRACT_VERSION]     — [contractVersion].
     *
     * Keys present when non-null / non-empty:
     *  - [KEY_ORIGINATING_HOST_ID]         — [originatingHostId].
     *  - [KEY_ORIGINATING_FORMATION_ROLE]  — [originatingFormationRole] wire value.
     *  - [KEY_REQUIRED_CAPABILITY_DIMENSIONS] — comma-separated dimension wire values.
     *  - [KEY_CONTINUATION_TOKEN]          — [continuationToken].
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads.
     */
    fun toMetadataMap(): Map<String, Any> = buildMap {
        put(KEY_UNIT_ID, unit.unitId)
        put(KEY_TASK_ID, unit.taskId)
        put(KEY_TRACE_ID, unit.traceId)
        put(KEY_ATTACHED_SESSION_ID, unit.attachedSessionId)
        put(KEY_HANDOFF_REASON, handoffReason.wireValue)
        put(KEY_CONTRACT_VERSION, contractVersion)
        originatingHostId?.let { put(KEY_ORIGINATING_HOST_ID, it) }
        originatingFormationRole?.let { put(KEY_ORIGINATING_FORMATION_ROLE, it.wireValue) }
        if (requiredCapabilityDimensions.isNotEmpty()) {
            put(
                KEY_REQUIRED_CAPABILITY_DIMENSIONS,
                requiredCapabilityDimensions.joinToString(",") { it.wireValue }
            )
        }
        continuationToken?.let { put(KEY_CONTINUATION_TOKEN, it) }
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /** Current schema version produced by the [from] factory. */
        const val CURRENT_CONTRACT_VERSION = 1

        // ── Metadata key constants ────────────────────────────────────────────

        /** Metadata key for the delegated-unit identifier. */
        const val KEY_UNIT_ID = "handoff_contract_unit_id"

        /** Metadata key for the task identifier. */
        const val KEY_TASK_ID = "handoff_contract_task_id"

        /** Metadata key for the end-to-end trace identifier. */
        const val KEY_TRACE_ID = "handoff_contract_trace_id"

        /** Metadata key for the attached session identifier. */
        const val KEY_ATTACHED_SESSION_ID = "handoff_contract_attached_session_id"

        /** Metadata key for the [HandoffReason.wireValue] string. */
        const val KEY_HANDOFF_REASON = "handoff_contract_handoff_reason"

        /** Metadata key for the contract schema version integer. */
        const val KEY_CONTRACT_VERSION = "handoff_contract_version"

        /**
         * Metadata key for the originating host identifier.
         * Absent from [toMetadataMap] when [originatingHostId] is null.
         */
        const val KEY_ORIGINATING_HOST_ID = "handoff_contract_originating_host_id"

        /**
         * Metadata key for the originating device's formation-role wire value.
         * Absent from [toMetadataMap] when [originatingFormationRole] is null.
         */
        const val KEY_ORIGINATING_FORMATION_ROLE = "handoff_contract_originating_formation_role"

        /**
         * Metadata key for the comma-separated required capability-dimension wire values.
         * Absent from [toMetadataMap] when [requiredCapabilityDimensions] is empty.
         */
        const val KEY_REQUIRED_CAPABILITY_DIMENSIONS = "handoff_contract_required_capability_dimensions"

        /**
         * Metadata key for the opaque continuation state token.
         * Absent from [toMetadataMap] when [continuationToken] is null.
         */
        const val KEY_CONTINUATION_TOKEN = "handoff_contract_continuation_token"

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Creates a [DelegatedHandoffContract] from an accepted [DelegatedRuntimeUnit]
         * and the originating [TakeoverRequestEnvelope].
         *
         * ## Field resolution
         *
         * | Contract field                    | Source                                             |
         * |-----------------------------------|----------------------------------------------------|
         * | [unit]                            | Supplied directly — already validated by receiver. |
         * | [handoffReason]                   | [HandoffReason.fromValue]([TakeoverRequestEnvelope.handoff_reason]) — defaults to [HandoffReason.DEFAULT] for legacy senders. |
         * | [originatingHostId]               | [TakeoverRequestEnvelope.originating_host_id] (null for legacy senders). |
         * | [originatingFormationRole]        | [RuntimeHostDescriptor.FormationRole.fromValue]([TakeoverRequestEnvelope.originating_formation_role]) — null when absent. |
         * | [requiredCapabilityDimensions]    | [TakeoverRequestEnvelope.required_capability_dimensions] parsed via [AndroidCapabilityVector.ExecutionDimension.fromValue]; unknown dimension wire values are silently dropped. |
         * | [continuationToken]               | [TakeoverRequestEnvelope.continuation_token] (null when absent). |
         *
         * @param unit     The [DelegatedRuntimeUnit] produced by [DelegatedRuntimeReceiver].
         * @param envelope The originating [TakeoverRequestEnvelope] from which contract
         *                 metadata is extracted.
         * @return A fully resolved [DelegatedHandoffContract].
         */
        fun from(
            unit: DelegatedRuntimeUnit,
            envelope: TakeoverRequestEnvelope
        ): DelegatedHandoffContract {
            val resolvedFormationRole: RuntimeHostDescriptor.FormationRole? =
                envelope.originating_formation_role?.let {
                    RuntimeHostDescriptor.FormationRole.fromValue(it)
                }
            val resolvedDimensions: Set<AndroidCapabilityVector.ExecutionDimension> =
                envelope.required_capability_dimensions
                    .mapNotNull { AndroidCapabilityVector.ExecutionDimension.fromValue(it) }
                    .toSet()

            return DelegatedHandoffContract(
                unit = unit,
                handoffReason = HandoffReason.fromValue(envelope.handoff_reason),
                originatingHostId = envelope.originating_host_id,
                originatingFormationRole = resolvedFormationRole,
                requiredCapabilityDimensions = resolvedDimensions,
                continuationToken = envelope.continuation_token,
                contractVersion = CURRENT_CONTRACT_VERSION
            )
        }
    }
}
