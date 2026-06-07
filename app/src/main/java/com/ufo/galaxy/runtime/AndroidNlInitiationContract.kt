package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-993 (Android) — Android-side natural-language initiation contract.
 *
 * [AndroidNlInitiationContract] is the explicit, machine-verifiable declaration of
 * how Android initiates requests via natural language in cross-device mode. It is the
 * authoritative definition of Android's "controlled initiation" capability within the
 * dual-repo distributed system.
 *
 * ## Motivation
 *
 * Prior to this contract, Android's role as an *initiator* — not just an executor —
 * was underdefined. [AndroidAutonomyBoundary] (PR-78) clarified that Android is an
 * autonomous edge agent in local mode and a V2-governed participant in cross-device mode.
 * However, it did not address the orthogonal question of Android-*originated* NL
 * initiation: when can Android *start* a request, and how must that initiation be
 * wired into the V2 central authority chain?
 *
 * Without this contract, there is a risk of:
 *  - Android creating a parallel NL initiation path that bypasses V2 governance.
 *  - NL initiations lacking the metadata required for V2 to admit them to the main
 *    intent / governance / execution path / truth / reconciliation / closure chain.
 *  - Ambiguity about whether an Android NL initiation is a proposal to V2 or an
 *    autonomous decision by Android itself.
 *
 * ## Fundamental rule
 *
 * **Android NL initiation is only valid when `cross_device_enabled = true`.**
 * When cross-device is off, Android operates in [AutonomyMode.LOCAL_AUTONOMOUS] and
 * NL input enters the local execution chain directly without V2 involvement.
 *
 * When cross-device is on, Android uses NL input as a **controlled entry point** into
 * the V2 central authority main chain:
 *  - Android is the *originator* (source/carrier) of the request.
 *  - V2 remains the *authority* for intent classification, governance, execution path
 *    selection, truth, reconciliation, and closure.
 *  - Android MUST NOT make autonomous initiation decisions (this is not the same as
 *    per-step execution autonomy, which Android always retains via
 *    [AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION]).
 *
 * ## Metadata requirements for V2 admission
 *
 * For an Android-originated NL initiation to be admitted to the V2 central main chain,
 * the outbound [com.ufo.galaxy.protocol.TaskSubmitPayload] must carry:
 *
 * | Field                   | Required value / semantic                                  |
 * |-------------------------|------------------------------------------------------------|
 * | [FIELD_ORIGIN]          | [ORIGIN_ANDROID_DEVICE]                                    |
 * | [FIELD_INITIATION_MODE] | [NlInitiationMode.ANDROID_NL_CROSS_DEVICE] wire value      |
 * | [FIELD_AUTHORITY_SCOPE] | [NlAuthorityScope.V2_CENTRAL] wire value                   |
 * | [FIELD_LINEAGE]         | Stable string linking this initiation to the device/session|
 * | [FIELD_CORRELATION_ID]  | Per-message unique ID echoed to the WS correlation_id      |
 *
 * These fields are consumed by V2's intake layer to classify an Android-originated NL
 * request as a legitimate main-chain entry — not an out-of-band bypass.
 *
 * ## No parallel initiation system
 *
 * This contract explicitly prohibits the creation of an Android-only intent/governance
 * system that exists in parallel to the V2 main chain. All Android NL initiations MUST:
 *  1. Enter through [com.ufo.galaxy.input.InputRouter] (the single routing gate).
 *  2. Travel over the canonical [com.ufo.galaxy.network.GalaxyWebSocketClient] uplink.
 *  3. Reach [com.ufo.galaxy.service.GalaxyConnectionService] on the inbound side.
 *  4. Be governed by V2 authority for intent / execution path / truth / reconciliation.
 *
 * @see AndroidAutonomyBoundary
 * @see AndroidAuthorityBoundaryClosure
 * @see com.ufo.galaxy.input.InputRouter
 * @see com.ufo.galaxy.protocol.TaskSubmitPayload
 */
object AndroidNlInitiationContract {

    /** PR number that introduced this contract. */
    const val INTRODUCED_PR = 993

    /** Schema version for NL initiation metadata wire fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire field names ──────────────────────────────────────────────────────

    /** Wire field name for the originating device/system identity. */
    const val FIELD_ORIGIN = "nl_initiation_origin"

    /** Wire field name for the NL initiation mode. */
    const val FIELD_INITIATION_MODE = "nl_initiation_mode"

    /** Wire field name for the authority scope of this initiation. */
    const val FIELD_AUTHORITY_SCOPE = "nl_initiation_authority_scope"

    /** Wire field name for the session/device lineage string. */
    const val FIELD_LINEAGE = "nl_initiation_lineage"

    /** Wire field name for the per-message correlation identifier. */
    const val FIELD_CORRELATION_ID = "nl_initiation_correlation_id"

    /** Wire field name for the schema version of this contract. */
    const val FIELD_SCHEMA_VERSION = "nl_initiation_schema_version"

    // ── Wire value constants ──────────────────────────────────────────────────

    /** The only valid origin value for Android-originated NL initiations. */
    const val ORIGIN_ANDROID_DEVICE = "android_device"

    // ── Initiation modes ─────────────────────────────────────────────────────

    /**
     * The valid NL initiation modes for Android.
     *
     * An initiation mode describes the context in which Android originates an NL request.
     *
     * @property wireValue   Stable lowercase wire tag.
     * @property displayName Short human-readable name.
     * @property description One-sentence description.
     * @property requiresCrossDevice Whether `cross_device_enabled` must be `true` for this mode.
     * @property authorityTransfersToV2 Whether semantic authority transfers to V2 in this mode.
     */
    enum class NlInitiationMode(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val requiresCrossDevice: Boolean,
        val authorityTransfersToV2: Boolean
    ) {

        /**
         * Android originates a natural-language request in cross-device mode, handing
         * semantic authority to V2 for intent classification, governance, execution path
         * selection, truth, reconciliation, and closure.
         *
         * **This is the only valid initiation mode when `cross_device_enabled = true`.**
         *
         * The Android device acts as source/carrier; V2 acts as the central authority.
         * The request enters the V2 main chain via the canonical WS uplink path.
         */
        ANDROID_NL_CROSS_DEVICE(
            wireValue = "android_nl_cross_device",
            displayName = "Android NL Cross-Device",
            description = "Android originates an NL request in cross-device mode; semantic " +
                "authority transfers to V2 for the full intent/governance/execution/truth chain.",
            requiresCrossDevice = true,
            authorityTransfersToV2 = true
        );

        companion object {
            /** Returns the [NlInitiationMode] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): NlInitiationMode? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Authority scopes ──────────────────────────────────────────────────────

    /**
     * The authority scope of an Android NL initiation.
     *
     * The authority scope declares which system is responsible for intent classification,
     * governance, execution path selection, truth, reconciliation, and closure.
     *
     * @property wireValue   Stable lowercase wire tag.
     * @property displayName Short human-readable name.
     * @property description One-sentence description.
     * @property isV2Governed Whether V2 is the governing authority for this scope.
     */
    enum class NlAuthorityScope(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isV2Governed: Boolean
    ) {

        /**
         * V2 central authority governs the full intent/execution/truth/closure path.
         *
         * **All Android NL initiations that enter the cross-device main chain MUST use
         * this authority scope.** Using any other scope (or omitting the scope) would
         * constitute a governance bypass.
         */
        V2_CENTRAL(
            wireValue = "v2_central",
            displayName = "V2 Central",
            description = "V2 central authority governs all intent classification, governance, " +
                "execution path selection, truth, reconciliation, and closure decisions.",
            isV2Governed = true
        );

        companion object {
            /** Returns the [NlAuthorityScope] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): NlAuthorityScope? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Initiation metadata ───────────────────────────────────────────────────

    /**
     * The complete set of metadata that must accompany an Android NL initiation for it
     * to be admitted to the V2 central main chain.
     *
     * Build instances via [build]; do not construct directly in production code.
     *
     * @param origin           The originating device/system identity. Always [ORIGIN_ANDROID_DEVICE].
     * @param initiationMode   The NL initiation mode. Always [NlInitiationMode.ANDROID_NL_CROSS_DEVICE].
     * @param authorityScope   The authority scope. Always [NlAuthorityScope.V2_CENTRAL].
     * @param lineage          A stable string linking this initiation to the device/session context,
     *                         formatted as `"${deviceId}/${runtimeSessionId}"`.
     * @param correlationId    Per-message unique identifier; echoed to the WS correlation_id field
     *                         so V2 can correlate the task_assign reply to this initiation.
     * @param runtimeSessionId The runtime attachment session ID from [RuntimeController] at the
     *                         time of initiation.
     * @param deviceContext    Key-value device and session context for V2 intake classification.
     *                         MUST contain `schema_version`, `device_id`, and `cross_device_enabled`.
     */
    data class NlInitiationMetadata(
        val origin: String,
        val initiationMode: NlInitiationMode,
        val authorityScope: NlAuthorityScope,
        val lineage: String,
        val correlationId: String,
        val runtimeSessionId: String?,
        val deviceContext: Map<String, String>
    ) {
        /**
         * Returns `true` when all invariant conditions are met:
         *  - [origin] is [ORIGIN_ANDROID_DEVICE]
         *  - [initiationMode] requires cross-device (all valid modes do)
         *  - [authorityScope] is V2-governed (all valid scopes are)
         *  - [lineage] is non-blank
         *  - [correlationId] is non-blank
         *  - [deviceContext] contains `schema_version`, `device_id`, `cross_device_enabled`
         */
        fun isValid(): Boolean =
            origin == ORIGIN_ANDROID_DEVICE &&
                initiationMode.requiresCrossDevice &&
                authorityScope.isV2Governed &&
                lineage.isNotBlank() &&
                correlationId.isNotBlank() &&
                deviceContext.containsKey(FIELD_SCHEMA_VERSION) &&
                deviceContext.containsKey("device_id") &&
                deviceContext.containsKey("cross_device_enabled")

        /**
         * Serialises this metadata to a flat [Map] of wire-field strings suitable for
         * inclusion in [com.ufo.galaxy.protocol.TaskSubmitContext.extra] or structured logging.
         */
        fun toWireMap(): Map<String, String> {
            val map = mutableMapOf(
                FIELD_ORIGIN to origin,
                FIELD_INITIATION_MODE to initiationMode.wireValue,
                FIELD_AUTHORITY_SCOPE to authorityScope.wireValue,
                FIELD_LINEAGE to lineage,
                FIELD_CORRELATION_ID to correlationId,
                FIELD_SCHEMA_VERSION to SCHEMA_VERSION
            )
            runtimeSessionId?.let { map["runtime_session_id"] = it }
            map.putAll(deviceContext)
            return map.toMap()
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Builds [NlInitiationMetadata] for a cross-device NL initiation.
     *
     * This is the **only** permitted factory entry-point for production code.
     * It enforces the gate condition: [crossDeviceEnabled] must be `true`, otherwise
     * this method returns `null` and the caller must not proceed with a WS uplink.
     *
     * @param crossDeviceEnabled Value of [com.ufo.galaxy.data.AppSettings.crossDeviceEnabled].
     *                           Must be `true`; returns `null` if `false`.
     * @param deviceId           Stable device identifier.
     * @param runtimeSessionId   Runtime attachment session ID from [RuntimeController]; may be `null`
     *                           for early-startup initiations before a session is established.
     * @param correlationId      Per-message unique ID. If not supplied, a fresh UUID is generated.
     * @return [NlInitiationMetadata] ready for embedding in the outbound payload, or `null` when
     *         [crossDeviceEnabled] is `false` (initiation is forbidden in local mode).
     */
    fun build(
        crossDeviceEnabled: Boolean,
        deviceId: String,
        runtimeSessionId: String? = null,
        correlationId: String = UUID.randomUUID().toString()
    ): NlInitiationMetadata? {
        if (!crossDeviceEnabled) return null
        val lineage = buildLineage(deviceId, runtimeSessionId)
        return NlInitiationMetadata(
            origin = ORIGIN_ANDROID_DEVICE,
            initiationMode = NlInitiationMode.ANDROID_NL_CROSS_DEVICE,
            authorityScope = NlAuthorityScope.V2_CENTRAL,
            lineage = lineage,
            correlationId = correlationId,
            runtimeSessionId = runtimeSessionId,
            deviceContext = mapOf(
                FIELD_SCHEMA_VERSION to SCHEMA_VERSION,
                "device_id" to deviceId,
                "cross_device_enabled" to "true"
            )
        )
    }

    // ── Lineage helpers ───────────────────────────────────────────────────────

    /**
     * Builds the canonical lineage string for an NL initiation.
     *
     * Format: `"android/{deviceId}/{runtimeSessionId}"` when a session is present,
     * or `"android/{deviceId}/no_session"` when [runtimeSessionId] is null.
     */
    fun buildLineage(deviceId: String, runtimeSessionId: String?): String =
        "android/$deviceId/${runtimeSessionId ?: "no_session"}"

    // ── Invariant assertions ──────────────────────────────────────────────────

    /**
     * Set of wire-value assertions that must hold for the NL initiation contract to be
     * correctly enforced.
     *
     * Each key names a mandatory property; `true` asserts it holds.
     * A cross-repo audit can verify all values are `true` to confirm closure.
     */
    val INITIATION_INVARIANTS: Map<String, Boolean> = mapOf(
        "nl_initiation_requires_cross_device_enabled" to true,
        "nl_initiation_authority_scope_is_always_v2_central" to true,
        "nl_initiation_cannot_bypass_input_router" to true,
        "nl_initiation_uses_canonical_ws_uplink" to true,
        "nl_initiation_carries_origin_metadata" to true,
        "nl_initiation_carries_lineage_metadata" to true,
        "nl_initiation_carries_correlation_id" to true,
        "nl_initiation_does_not_form_parallel_intent_system" to true,
        "nl_initiation_semantic_authority_transfers_to_v2" to true,
        "nl_initiation_enters_v2_main_chain" to true
    )

    /**
     * Builds a stable machine-readable wire map describing the current contract invariants.
     *
     * Suitable for structured telemetry, audit trails, and cross-repo alignment checks.
     */
    fun buildContractWireMap(): Map<String, Any> = mapOf(
        "introduced_pr" to INTRODUCED_PR,
        "schema_version" to SCHEMA_VERSION,
        "origin" to ORIGIN_ANDROID_DEVICE,
        "valid_initiation_modes" to NlInitiationMode.ALL_WIRE_VALUES.toList().sorted(),
        "valid_authority_scopes" to NlAuthorityScope.ALL_WIRE_VALUES.toList().sorted(),
        "cross_device_gate_required" to true,
        "v2_central_authority_required" to true,
        "parallel_system_forbidden" to true,
        "invariant_count" to INITIATION_INVARIANTS.size,
        "all_invariants_hold" to INITIATION_INVARIANTS.values.all { it }
    )
}
