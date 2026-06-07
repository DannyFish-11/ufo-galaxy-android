package com.ufo.galaxy.runtime

/**
 * PR-121 — Android Canonical Transport Path Boundary Contract.
 *
 * Establishes a formal, machine-actionable classification of Android WebSocket transport
 * send paths as canonical production paths vs. compatibility/legacy/fallback paths,
 * ensuring that non-canonical paths cannot be silently treated as production-equivalent.
 *
 * ## Problem addressed
 *
 * The Android WebSocket transport layer already contains:
 *  - Canonical production path: [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson]
 *    with full AIP v3 semantics, cross-device gate enforcement, and offline queuing.
 *  - Compatibility/legacy paths: deprecated [com.ufo.galaxy.network.GalaxyWebSocketClient.send]
 *    and [com.ufo.galaxy.network.GalaxyWebSocketClient.sendAIPMessage], retained for backward
 *    compatibility during migration.
 *
 * However, there was no formal contract declaring:
 *  - Which transport path classes exist and what their production-equivalence semantics are.
 *  - That compatibility paths MUST NOT be treated as canonical production paths.
 *  - The V2 ingress alignment for each transport path class.
 *  - The invariants that prevent compatibility paths from drifting into production use.
 *
 * ## Transport path classes
 *
 * | [TransportPathClass]       | Meaning                                                           | Production equivalent? |
 * |----------------------------|-------------------------------------------------------------------|------------------------|
 * | [CANONICAL_PRODUCTION]     | sendJson() — primary AIP v3 path with gate enforcement            | ✅ Yes                 |
 * | [COMPAT_LEGACY]            | send()/sendAIPMessage() — @Deprecated, migration only             | ❌ No                  |
 * | [DIAGNOSTICS_ONLY_PATH]    | Diagnostics/observability-only; no closure semantics              | ❌ No                  |
 * | [NOT_APPLICABLE]           | Offline queued, blocked, or local-only mode                       | ❌ No                  |
 *
 * ## Relation to existing contracts
 *
 * | Existing contract | Relationship to this contract |
 * |---|---|
 * | [AndroidMinimalRuntimeAccessChainContract] | MAIN_CHAIN transport stage (GalaxyWebSocketClient) corresponds to [CANONICAL_PRODUCTION]; COMPAT_FALLBACK/LEGACY_BRIDGE layer classes correspond to [COMPAT_LEGACY] |
 * | [AndroidCrossDeviceDispatchBoundaryContract] | CANONICAL_CROSS_DEVICE dispatch aligns with [CANONICAL_PRODUCTION] transport; COMPAT_FALLBACK/LEGACY_BYPASS align with [COMPAT_LEGACY] |
 * | [AndroidResultUplinkBoundaryContract] | AUTHORITY_RESULT uplinks MUST only travel via [CANONICAL_PRODUCTION] transport |
 * | [AndroidNonClosureSignalBoundaryContract] | NON_CLOSURE_MSG_TYPES payloads semantically correspond to [DIAGNOSTICS_ONLY_PATH] even when transported via sendJson() |
 *
 * @see AndroidMinimalRuntimeAccessChainContract
 * @see AndroidCrossDeviceDispatchBoundaryContract
 * @see AndroidResultUplinkBoundaryContract
 * @see AndroidNonClosureSignalBoundaryContract
 */
object AndroidCanonicalTransportPathBoundaryContract {

    /** The Android PR number that introduced this contract. */
    const val INTRODUCED_PR = 121

    /** Wire schema version for this contract's classification. */
    const val SCHEMA_VERSION = "1"

    // ── Transport path class enum ──────────────────────────────────────────────

    /**
     * Classification of Android WebSocket transport send paths by their
     * production authority and canonical equivalence.
     *
     * Only [CANONICAL_PRODUCTION] may carry closure-bearing payloads.
     * All other classes are non-production or non-closure path variants.
     */
    enum class TransportPathClass(
        val wireValue: String,
        val isCanonicalProduction: Boolean,
        val description: String
    ) {
        /**
         * Canonical production transport path.
         *
         * Corresponds to [com.ufo.galaxy.network.GalaxyWebSocketClient.sendJson] with:
         *  - AIP v3 protocol semantics (typed [com.ufo.galaxy.protocol.AipMessage] envelopes)
         *  - Cross-device gate enforcement (blocked when cross_device=off)
         *  - Offline queuing for queueable message types
         *  - Full observability logging
         *
         * This is the ONLY transport path class eligible to carry closure-bearing payloads
         * ([AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT]).
         * V2 canonical ingress MUST only accept closure from this path class.
         */
        CANONICAL_PRODUCTION(
            wireValue = "canonical_production",
            isCanonicalProduction = true,
            description = "GalaxyWebSocketClient.sendJson() — canonical AIP v3 production path " +
                "with cross-device gate enforcement and offline queuing. " +
                "Only path eligible to carry AUTHORITY_RESULT closure-bearing payloads."
        ),

        /**
         * Compatibility/legacy transport path — retained for migration only.
         *
         * Corresponds to deprecated [com.ufo.galaxy.network.GalaxyWebSocketClient.send] and
         * [com.ufo.galaxy.network.GalaxyWebSocketClient.sendAIPMessage].
         * Both methods internally redirect to [CANONICAL_PRODUCTION] (sendJson) but are
         * @Deprecated and MUST NOT be called in new code.
         *
         * Android components MUST prefer [CANONICAL_PRODUCTION] for all new send paths.
         * V2 MUST NOT treat payloads arriving via this path as production-canonical.
         */
        COMPAT_LEGACY(
            wireValue = "compat_legacy",
            isCanonicalProduction = false,
            description = "GalaxyWebSocketClient.send()/sendAIPMessage() — @Deprecated compat paths " +
                "retained for migration; internally redirect to canonical sendJson(). " +
                "MUST NOT be called in new code. V2 MUST NOT treat as production-equivalent."
        ),

        /**
         * Diagnostics/observability-only transport semantic.
         *
         * Applies when the transport technically uses sendJson() but the payload type is
         * classified as non-closure by [AndroidNonClosureSignalBoundaryContract].
         * These payloads do not carry canonical closure semantics regardless of transport method.
         *
         * Note: The physical transport for diagnostics payloads is still sendJson(), but their
         * semantic transport path class is DIAGNOSTICS_ONLY_PATH because they are
         * [AndroidNonClosureSignalBoundaryContract.NON_CLOSURE_MSG_TYPES].
         */
        DIAGNOSTICS_ONLY_PATH(
            wireValue = "diagnostics_only_path",
            isCanonicalProduction = false,
            description = "Diagnostics/observability-only transport semantic. " +
                "No canonical closure authority; V2 MUST store these, not close tasks."
        ),

        /**
         * Not applicable — no active transport send path.
         *
         * Applies when:
         *  - The device is in local-only mode (cross-device disabled)
         *  - The payload is queued for offline delivery
         *  - The transport is blocked by gate policy
         */
        NOT_APPLICABLE(
            wireValue = "not_applicable",
            isCanonicalProduction = false,
            description = "No active transport path: local-only mode, offline-queued, or gate blocked."
        );

        companion object {
            /**
             * Returns the [TransportPathClass] for the given [wireValue], or
             * [NOT_APPLICABLE] as a safe defensive default for unknown values.
             */
            fun fromWireValue(wire: String): TransportPathClass =
                values().firstOrNull { it.wireValue == wire } ?: NOT_APPLICABLE
        }
    }

    // ── Classification helpers ─────────────────────────────────────────────────

    /**
     * Classifies a send operation based on whether it uses the canonical send path
     * (sendJson) or a deprecated compat path (send/sendAIPMessage).
     *
     * @param isCanonicalSendJson    `true` when the send path is GalaxyWebSocketClient.sendJson().
     * @param isCompatSendAIPMessage `true` when the send path is the deprecated sendAIPMessage().
     * @return The [TransportPathClass] for the given send operation.
     */
    fun classifySendPath(
        isCanonicalSendJson: Boolean,
        isCompatSendAIPMessage: Boolean = false
    ): TransportPathClass = when {
        isCompatSendAIPMessage -> TransportPathClass.COMPAT_LEGACY
        isCanonicalSendJson -> TransportPathClass.CANONICAL_PRODUCTION
        else -> TransportPathClass.NOT_APPLICABLE
    }

    /**
     * Returns `true` if the [transportPathClass] is the canonical production path.
     *
     * Closure-bearing payloads (AUTHORITY_RESULT) MUST only travel via the canonical
     * production path.  This helper provides a direct gate check.
     */
    fun isCanonicalProduction(transportPathClass: TransportPathClass): Boolean =
        transportPathClass.isCanonicalProduction

    // ── V2 ingress alignment map ───────────────────────────────────────────────

    /**
     * Maps each [TransportPathClass] to the V2 ingress alignment expectation.
     *
     * V2 MUST enforce that only [CANONICAL_PRODUCTION] transport carries closure-bearing
     * uplinks into the truth chain; [COMPAT_LEGACY] and [DIAGNOSTICS_ONLY_PATH] must be
     * treated as non-canonical ingress.
     */
    val V2_TRANSPORT_PATH_ALIGNMENT_MAP: Map<TransportPathClass, String> = mapOf(
        TransportPathClass.CANONICAL_PRODUCTION to
            "galaxy_gateway/routes/websocket.py — canonical ingress; eligible for truth chain",
        TransportPathClass.COMPAT_LEGACY to
            "galaxy_gateway/routes/websocket.py — compat redirect; non-canonical, migration only",
        TransportPathClass.DIAGNOSTICS_ONLY_PATH to
            "core/android_device_state_store.py — diagnostics store only; no closure path",
        TransportPathClass.NOT_APPLICABLE to
            "n/a — blocked, offline-queued, or local-only; no active V2 transport path"
    )

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Formal invariants for canonical transport path boundary enforcement.
     *
     * All invariants must hold at all times.  Future contributors modifying Android
     * transport logic must verify all invariants remain satisfied.
     */
    val TRANSPORT_PATH_INVARIANTS: List<String> = listOf(
        "INV-TRP-01: GalaxyWebSocketClient.sendJson() is the ONLY canonical production " +
            "transport path; all result uplinks and closure-bearing payloads MUST use this path.",
        "INV-TRP-02: GalaxyWebSocketClient.send() and sendAIPMessage() are @Deprecated compat " +
            "paths; they MUST redirect to sendJson() internally and MUST NOT be called in new code.",
        "INV-TRP-03: COMPAT_LEGACY transport paths MUST NOT be treated as production-equivalent " +
            "by V2; legacy redirect logging MUST be preserved for observability.",
        "INV-TRP-04: Closure-bearing payloads classified as AUTHORITY_RESULT " +
            "(AndroidResultUplinkBoundaryContract) MUST only travel via CANONICAL_PRODUCTION transport.",
        "INV-TRP-05: New transport send paths MUST be classified as CANONICAL_PRODUCTION to be " +
            "eligible for carrying closure-bearing payloads; any new path not meeting this " +
            "classification MUST be registered as COMPAT_LEGACY or DIAGNOSTICS_ONLY_PATH.",
        "INV-TRP-06: AndroidMinimalRuntimeAccessChainContract MAIN_CHAIN transport stage " +
            "(GalaxyWebSocketClient) corresponds exclusively to CANONICAL_PRODUCTION transport; " +
            "COMPAT_FALLBACK/LEGACY_BRIDGE layer classes MUST NOT be elevated to CANONICAL_PRODUCTION.",
        "INV-TRP-07: Android transport path selection MUST prefer CANONICAL_PRODUCTION in all " +
            "production scenarios; fallback to COMPAT_LEGACY MUST only occur under explicit " +
            "migration or backward-compatibility constraints, never silently in production.",
        "INV-TRP-08: The cross-device gate enforced by GalaxyWebSocketClient.sendJson() is part " +
            "of the CANONICAL_PRODUCTION path guarantee; disabling or bypassing this gate " +
            "demotes the transport path to a non-canonical category.",
        "INV-TRP-09: Debug/dev path selection (allowSelfSigned, dev server URL) MUST NOT " +
            "alter the transport path class of the send operation itself; path class is " +
            "determined solely by the send method chosen, not by TLS or URL configuration.",
        "INV-TRP-10: Reconnect and backoff behavior within CANONICAL_PRODUCTION transport " +
            "MUST NOT alter the path class during or after reconnect; a reconnected " +
            "GalaxyWebSocketClient.sendJson() remains CANONICAL_PRODUCTION."
    )
}
