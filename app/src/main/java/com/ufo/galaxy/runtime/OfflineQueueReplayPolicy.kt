package com.ufo.galaxy.runtime

/**
 * PR-71 (Android) — Formal policy/evidence model for offline queue replay ordering and
 * authority semantics.
 *
 * [OfflineQueueReplayPolicy] formalises the semantics of Android offline queue replay so
 * that downstream systems (V2 governance, dual-repo recovery audits) can programmatically
 * consume the exact status of each replay sub-dimension rather than relying on implicit
 * code comments or assuming "the queue exists" equals "ordering/authority is closed".
 *
 * ## Background
 *
 * [com.ufo.galaxy.network.OfflineTaskQueue] provides a reliable FIFO queue that survives
 * WebSocket disconnects.  However, a reliable queue does not imply a formally-closed replay
 * ordering / authority contract:
 *
 *  - **Task replay existence** can be verified by inspecting the queue implementation.
 *  - **Replay ordering guarantee** depends on whether V2 guarantees processing order for
 *    replayed messages — a contract that is not yet formally defined from Android's
 *    perspective.  This dimension is explicitly **DEFERRED**.
 *  - **Replay authority** (who decides whether to replay and in what order) rests entirely
 *    with V2; Android is **NON_AUTHORITATIVE** here.
 *  - **Duplicate avoidance** is partially covered by session bounding but not fully
 *    persistent across process recreation (**PARTIALLY_SUPPORTED**).
 *  - **Eventual recovery** is bounded by queue size, TTL policy, and V2 authority — there
 *    is no unconditional guarantee (**ACCEPTED_LIMITATION**).
 *
 * Without a formal policy surface, consumers risk conflating "the queue exists" with "the
 * ordering / authority contract is closed," leading to dishonest cross-repo recovery
 * verdicts in the dual-repo system.
 *
 * ## Programmatic usage
 *
 * ```kotlin
 * val report = OfflineQueueReplayPolicy.buildReport()
 * val wireMap = report.toWireMap()
 *
 * // Check whether ordering is deferred (it is — do not assume ordering is closed):
 * val hasDeferred = report.hasDeferredItems()  // → true
 *
 * // Inspect per-dimension statuses:
 * val orderingStatus = report.statusFor(
 *     OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE)
 * // → DEFERRED
 *
 * val authorityStatus = report.statusFor(
 *     OfflineQueueReplayPolicy.ReplaySemanticDimension.REPLAY_AUTHORITY)
 * // → NON_AUTHORITATIVE
 *
 * // Wire map keys for V2 consumption:
 * wireMap["replay_ordering_guarantee_status"]  // → "deferred"
 * wireMap["replay_authority_status"]           // → "non_authoritative"
 * wireMap["duplicate_avoidance_status"]        // → "partially_supported"
 * wireMap["has_full_support"]                  // → false
 * wireMap["has_deferred_items"]                // → true
 * ```
 *
 * ## Design constraints
 *
 *  - Each [ReplaySemanticDimension] maps to exactly one [ReplayPolicyStatus].
 *  - Dimensions without sufficient evidence MUST NOT be upgraded to [ReplayPolicyStatus.SUPPORTED].
 *  - [ReplayPolicyStatus.DEFERRED] and [ReplayPolicyStatus.NON_AUTHORITATIVE] are distinct:
 *    DEFERRED means the contract has not been defined; NON_AUTHORITATIVE means the contract
 *    is defined but Android is not the decision-making authority.
 *  - [OfflineQueueReplayPolicyReport.toWireMap] is the programmatic output surface; it must
 *    be consumed by V2 and report infrastructure rather than only being readable in comments.
 *
 * @see com.ufo.galaxy.network.OfflineTaskQueue
 * @see AndroidReadinessEvidenceSurface
 * @see ContinuityRecoveryDurabilityContract
 * @see AndroidRecoveryParticipationOwner
 */
object OfflineQueueReplayPolicy {

    // ── PR identifier ─────────────────────────────────────────────────────────

    /** The Android PR number that introduced this policy surface. */
    const val INTRODUCED_PR = 71

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE =
        "Formalise offline queue replay ordering / authority semantics as machine-consumable policy"

    /** Wire schema version for [OfflineQueueReplayPolicyReport]. */
    const val SCHEMA_VERSION = "1.0"

    // ── ReplaySemanticDimension ───────────────────────────────────────────────

    /**
     * The five sub-dimensions of offline queue replay semantics that each require an
     * explicit, independently-evaluated status.
     *
     * These dimensions are deliberately distinct: having evidence for one does NOT imply
     * evidence for another.  Consumers must inspect all five to form a correct picture of
     * the replay contract.
     *
     * @property wireValue Stable string identifier for wire transmission and audit logs.
     */
    enum class ReplaySemanticDimension(val wireValue: String) {

        /**
         * Whether tasks can be replayed at all — the basic replay capability dimension.
         *
         * Covers: does an offline queue exist, does it survive disconnects, and are
         * queued tasks eventually delivered on reconnect?
         *
         * This is distinct from ordering or authority: a queue can exist and deliver
         * tasks without providing ordering guarantees or authority contracts.
         */
        TASK_REPLAY_EXISTENCE("task_replay_existence"),

        /**
         * Whether the order of replayed messages is formally guaranteed end-to-end.
         *
         * Android drains [com.ufo.galaxy.network.OfflineTaskQueue] in FIFO order,
         * preserving insertion sequence within a single drain operation.  However,
         * end-to-end ordering — including how V2 processes messages after receiving
         * them over the WebSocket — is not yet formally contracted.  This dimension
         * captures that open boundary.
         *
         * **Current status: DEFERRED.**  See [OfflineQueueReplayPolicy.allEntries].
         */
        REPLAY_ORDERING_GUARANTEE("replay_ordering_guarantee"),

        /**
         * Who has authority over replay decisions.
         *
         * Android presents queued messages to V2 on reconnect but does not self-authorise
         * replay decisions.  V2 is the canonical authority for deciding which messages to
         * process, in what order, and whether to discard stale replays.
         *
         * **Current status: NON_AUTHORITATIVE.**  Android is a participant, not the arbiter.
         */
        REPLAY_AUTHORITY("replay_authority"),

        /**
         * Whether duplicate replay is prevented end-to-end.
         *
         * Session-bounded discard ([com.ufo.galaxy.network.OfflineTaskQueue.discardForDifferentSession])
         * and in-process signal deduplication ([EmittedSignalLedger]) provide partial coverage.
         * However, in-memory-only deduplication does not survive process recreation.
         *
         * **Current status: PARTIALLY_SUPPORTED.**
         */
        DUPLICATE_AVOIDANCE("duplicate_avoidance"),

        /**
         * Whether eventual recovery (all queued work reaching V2) is unconditionally guaranteed.
         *
         * Queue size limits (max 50 messages), 24-hour TTL eviction, and V2 authority over
         * replay processing mean that unconditional delivery cannot be guaranteed.  This is
         * an accepted limitation of the current bounded-queue design.
         *
         * **Current status: ACCEPTED_LIMITATION.**
         */
        EVENTUAL_RECOVERY("eventual_recovery");

        companion object {

            /** Returns the dimension matching [wireValue], or `null` if not found. */
            fun fromWireValue(wireValue: String?): ReplaySemanticDimension? =
                values().firstOrNull { it.wireValue == wireValue }

            /** All wire values for exhaustive validation. */
            val ALL_WIRE_VALUES: Set<String> = values().map { it.wireValue }.toSet()
        }
    }

    // ── ReplayPolicyStatus ────────────────────────────────────────────────────

    /**
     * The formal status of a [ReplaySemanticDimension].
     *
     * These statuses are deliberately granular.  A consumer MUST NOT assume that a dimension
     * without a [SUPPORTED] status is unimportant; rather, the non-SUPPORTED statuses carry
     * specific semantic meaning that affects dual-repo recovery truth verdicts.
     *
     * @property wireValue Stable string identifier for wire transmission.
     */
    enum class ReplayPolicyStatus(val wireValue: String) {

        /**
         * This dimension is fully supported with canonical evidence and a formal contract.
         *
         * Consumers may treat a SUPPORTED dimension as a closed, gating evidence item.
         * **Do not assign SUPPORTED to a dimension without concrete test evidence and a
         * formal contract; partial coverage must use [PARTIALLY_SUPPORTED] instead.**
         */
        SUPPORTED("supported"),

        /**
         * This dimension is partially covered but has known gaps.
         *
         * There is real evidence and real runtime behavior, but the coverage is incomplete.
         * Consumers must NOT treat PARTIALLY_SUPPORTED as equivalent to SUPPORTED for
         * release-gate or recovery-closure purposes.
         *
         * Distinct from [NON_AUTHORITATIVE]: Android has some coverage here but not full
         * coverage; the distinction is about coverage depth, not authority.
         */
        PARTIALLY_SUPPORTED("partially_supported"),

        /**
         * The contract for this dimension has not yet been formally defined.
         *
         * Something may work at runtime, but the formal guarantee — the written policy, the
         * test coverage, the V2-side contract — is explicitly deferred to a later PR.
         * Consumers MUST treat DEFERRED as an open boundary, not a closed one.
         *
         * Distinct from [NON_AUTHORITATIVE]: DEFERRED means the contract does not exist yet;
         * NON_AUTHORITATIVE means the contract exists but Android is not the authority.
         */
        DEFERRED("deferred"),

        /**
         * Android recognises this dimension but is not the authority over it.
         *
         * Android participates (e.g., sends queued messages to V2) but does not make
         * authoritative decisions (e.g., V2 decides replay order and validity).  Consumers
         * must look to V2 for the authoritative status of this dimension.
         *
         * Distinct from [PARTIALLY_SUPPORTED]: the gap here is about who owns the decision,
         * not about coverage depth.  Distinct from [DEFERRED]: the framework for authority
         * is understood; Android simply is not the authority.
         */
        NON_AUTHORITATIVE("non_authoritative"),

        /**
         * This dimension has known, accepted limitations by design.
         *
         * The limitation is understood, documented, and accepted as part of the current
         * system design.  It is not a bug or an omission; it is a deliberate trade-off.
         * Consumers should document the limitation in dual-repo recovery verdicts rather than
         * treating it as an unaddressed gap.
         */
        ACCEPTED_LIMITATION("accepted_limitation"),

        /**
         * This dimension does not apply to the current component or context.
         *
         * Reserved for cases where a dimension is part of the taxonomy but genuinely
         * irrelevant for a specific component.
         */
        NOT_APPLICABLE("not_applicable");

        /**
         * Returns `true` if this status represents an open boundary — i.e., the dimension
         * is not fully closed and consumers must not treat it as complete evidence.
         *
         * SUPPORTED and ACCEPTED_LIMITATION return `false` (they represent closed or
         * acknowledged states); DEFERRED, NON_AUTHORITATIVE, and PARTIALLY_SUPPORTED
         * return `true` (they represent incompleteness or non-ownership).
         */
        fun isOpenBoundary(): Boolean =
            this == DEFERRED || this == NON_AUTHORITATIVE || this == PARTIALLY_SUPPORTED

        companion object {

            /** Returns the status matching [wireValue], or `null` if not found. */
            fun fromWireValue(wireValue: String?): ReplayPolicyStatus? =
                values().firstOrNull { it.wireValue == wireValue }

            /** All wire values for exhaustive validation. */
            val ALL_WIRE_VALUES: Set<String> = values().map { it.wireValue }.toSet()
        }
    }

    // ── ReplayPolicyEntry ─────────────────────────────────────────────────────

    /**
     * A single policy entry describing the current state of one [ReplaySemanticDimension].
     *
     * @property dimension          The replay semantic dimension this entry describes.
     * @property status             The formal policy status for this dimension.
     * @property rationale          Explanation of why this status was assigned.
     * @property evidenceReference  Android-side code or tests supporting this assignment.
     *                              Non-blank for SUPPORTED and PARTIALLY_SUPPORTED entries.
     *                              Must explain what is missing for DEFERRED entries.
     * @property limitations        Known limitations or caveats.  Non-blank for DEFERRED,
     *                              NON_AUTHORITATIVE, PARTIALLY_SUPPORTED, and
     *                              ACCEPTED_LIMITATION entries.
     * @property v2ConsumptionPath  How V2 or dual-repo governance should consume this entry.
     */
    data class ReplayPolicyEntry(
        val dimension: ReplaySemanticDimension,
        val status: ReplayPolicyStatus,
        val rationale: String,
        val evidenceReference: String,
        val limitations: String,
        val v2ConsumptionPath: String
    )

    // ── Policy entries ────────────────────────────────────────────────────────

    /**
     * The five policy entries covering all [ReplaySemanticDimension]s.
     *
     * Each entry carries the honest, current status of that dimension.  Consumers MUST
     * inspect all five entries; summary flags such as [OfflineQueueReplayPolicyReport.hasFullSupport]
     * must not be used as a shortcut to avoid reading DEFERRED or NON_AUTHORITATIVE entries.
     */
    val allEntries: List<ReplayPolicyEntry> = listOf(

        ReplayPolicyEntry(
            dimension = ReplaySemanticDimension.TASK_REPLAY_EXISTENCE,
            status = ReplayPolicyStatus.SUPPORTED,
            rationale = "OfflineTaskQueue provides a persistent, FIFO outgoing task-result queue " +
                "that survives WebSocket disconnects and process restarts (when SharedPreferences " +
                "is provided).  On reconnect, GalaxyConnectionService drains the queue and sends " +
                "all current-era messages.  Task replay existence is fully supported with " +
                "concrete unit-test coverage.",
            evidenceReference = "OfflineTaskQueue.enqueue / drainAll; " +
                "OfflineQueueTest: drainAll returns messages in FIFO order; " +
                "Pr66ContinuityRecoveryDurabilityTest: queue drain on reconnect",
            limitations = "Queue is capped at 50 messages (oldest dropped when full).  " +
                "Messages older than 24 hours are evicted on load.  These are known, accepted " +
                "bounds on replay existence, not failures of the mechanism itself.",
            v2ConsumptionPath = "V2 receives replayed task_result / goal_result messages via " +
                "WebSocket after Android reconnects.  V2 can identify replayed messages by " +
                "sessionTag and cross-reference with existing deduplication surfaces."
        ),

        ReplayPolicyEntry(
            dimension = ReplaySemanticDimension.REPLAY_ORDERING_GUARANTEE,
            status = ReplayPolicyStatus.DEFERRED,
            rationale = "Android drains OfflineTaskQueue in strict FIFO order within a single " +
                "drain operation, preserving insertion sequence.  However, the system-level " +
                "end-to-end ordering guarantee — covering how V2 receives, queues, and processes " +
                "replayed messages over the WebSocket — has not been formally defined or contracted.  " +
                "The FIFO property is local to Android's drain path; V2-side ordering semantics " +
                "for replayed messages are not yet specified.",
            evidenceReference = "OfflineTaskQueue.drainAll() returns messages in ArrayDeque " +
                "insertion order (FIFO).  OfflineQueueTest: drainAll returns messages in FIFO " +
                "order.  V2-side replay ordering contract: NOT YET DEFINED — this is the " +
                "deferred boundary.",
            limitations = "FIFO is preserved only within a single drain.  The drop-oldest policy " +
                "breaks sequence continuity when the queue overflows (oldest messages lost).  " +
                "V2 processing order for replayed messages is not formally guaranteed by any " +
                "current contract.  This dimension remains DEFERRED until V2 publishes its " +
                "replay ordering contract.",
            v2ConsumptionPath = "DEFERRED — V2 must not assume ordered replay until this " +
                "dimension is upgraded to SUPPORTED or PARTIALLY_SUPPORTED in a subsequent PR.  " +
                "OfflineQueueReplayPolicy.buildReport().toWireMap() exposes " +
                "replay_ordering_guarantee_status = 'deferred' so that V2 dual-repo recovery " +
                "verdicts can explicitly acknowledge this open boundary."
        ),

        ReplayPolicyEntry(
            dimension = ReplaySemanticDimension.REPLAY_AUTHORITY,
            status = ReplayPolicyStatus.NON_AUTHORITATIVE,
            rationale = "Android presents queued messages to V2 on reconnect but does not " +
                "autonomously decide whether to replay them, in what order, or whether to " +
                "discard stale replays.  V2 is the canonical authority for all replay decisions.  " +
                "AndroidRecoveryParticipationOwner explicitly models this: " +
                "LocalRecoveryDecision.WaitForV2ReplayDecision defers all replay authority to V2.  " +
                "Android cannot unilaterally determine that a replay is valid, safe, or ordered.",
            evidenceReference = "AndroidRecoveryParticipationOwner.WaitForV2ReplayDecision; " +
                "OfflineTaskQueue.discardForDifferentSession() (Android-side pre-filter before " +
                "presenting to V2, not an authoritative replay decision); " +
                "Pr66ContinuityRecoveryDurabilityTest: process recreation without context → " +
                "WaitForV2ReplayDecision",
            limitations = "Android's non-authoritative status means that even if Android sends " +
                "replayed messages in correct FIFO order, V2 may process them in any order, " +
                "reject them, or request re-dispatch.  Consumers must not interpret Android " +
                "FIFO drain as a sufficient replay ordering authority.",
            v2ConsumptionPath = "V2 is the authoritative consumer of replayed messages.  " +
                "OfflineQueueReplayPolicy wire value replay_authority_status = 'non_authoritative' " +
                "signals that Android does not claim ordering authority; V2 must apply its own " +
                "replay validation and ordering policy when processing Android offline queue drain."
        ),

        ReplayPolicyEntry(
            dimension = ReplaySemanticDimension.DUPLICATE_AVOIDANCE,
            status = ReplayPolicyStatus.PARTIALLY_SUPPORTED,
            rationale = "Android provides two layers of duplicate avoidance: " +
                "(1) OfflineTaskQueue.discardForDifferentSession() removes stale-session messages " +
                "before drain, preventing cross-session replay; " +
                "(2) EmittedSignalLedger.replayBounded() suppresses ACK/PROGRESS signals after a " +
                "terminal RESULT within the current process lifetime.  These cover the most " +
                "common duplicate vectors.  However, the in-memory deduplication state is lost " +
                "on process recreation, leaving cross-process duplicate scenarios uncovered.",
            evidenceReference = "OfflineTaskQueue.discardForDifferentSession(); " +
                "EmittedSignalLedger.replayBounded(); " +
                "AndroidContinuityIntegration.suppressDuplicateLocalEmit(); " +
                "Pr66ContinuityRecoveryDurabilityTest: discardForDifferentSession removes " +
                "stale-tagged messages; replayBounded suppresses ACK/PROGRESS after RESULT",
            limitations = "EmittedSignalLedger is in-memory only: the seen-set and terminal " +
                "result state are lost on process recreation.  Cross-process duplicate avoidance " +
                "is explicitly deferred (see AndroidReadinessEvidenceSurface.deferredItems: " +
                "emit_ledger_cross_process_persistence).  PARTIALLY_SUPPORTED, not SUPPORTED.",
            v2ConsumptionPath = "V2 must apply its own deduplication by signalId / task_id for " +
                "replayed messages.  Android-side partial coverage reduces cross-session duplicate " +
                "volume but does not eliminate all duplicates; V2 must not assume zero-duplicate " +
                "replay delivery from Android offline queue."
        ),

        ReplayPolicyEntry(
            dimension = ReplaySemanticDimension.EVENTUAL_RECOVERY,
            status = ReplayPolicyStatus.ACCEPTED_LIMITATION,
            rationale = "Android provides best-effort eventual delivery of queued task results: " +
                "on reconnect, all current-era, non-stale queued messages are sent to V2.  " +
                "This constitutes a reasonable eventual recovery mechanism for most practical " +
                "scenarios.  However, 'eventual' is bounded by queue drop policy and V2 " +
                "authority, so unconditional delivery cannot be guaranteed.",
            evidenceReference = "OfflineTaskQueue: max 50 messages, 24 h TTL, FIFO drain; " +
                "GalaxyConnectionService reconnect path → discardForDifferentSession → drainAll; " +
                "Pr66ContinuityRecoveryDurabilityTest: queue drain on reconnect",
            limitations = "Queue overflow causes oldest-message drop: at most 50 messages survive " +
                "a prolonged outage.  Messages older than 24 hours are evicted on load.  V2 may " +
                "reject, re-order, or discard replayed messages at its discretion.  These are " +
                "accepted design trade-offs, not unaddressed bugs.",
            v2ConsumptionPath = "V2 should expect best-effort delivery within queue bounds.  " +
                "Dual-repo recovery verdicts must acknowledge that Android eventual recovery is " +
                "ACCEPTED_LIMITATION, not SUPPORTED.  This prevents false-positive " +
                "'fully closed' recovery verdicts in cross-device recovery audits."
        )
    )

    // ── Count constants for test assertions ───────────────────────────────────

    /** Total number of policy entries; one per [ReplaySemanticDimension]. */
    const val ENTRY_COUNT = 5

    /** Number of entries with [ReplayPolicyStatus.SUPPORTED] status. */
    const val SUPPORTED_COUNT = 1

    /** Number of entries with [ReplayPolicyStatus.PARTIALLY_SUPPORTED] status. */
    const val PARTIALLY_SUPPORTED_COUNT = 1

    /** Number of entries with [ReplayPolicyStatus.DEFERRED] status. */
    const val DEFERRED_COUNT = 1

    /** Number of entries with [ReplayPolicyStatus.NON_AUTHORITATIVE] status. */
    const val NON_AUTHORITATIVE_COUNT = 1

    /** Number of entries with [ReplayPolicyStatus.ACCEPTED_LIMITATION] status. */
    const val ACCEPTED_LIMITATION_COUNT = 1

    /** Number of entries with [ReplayPolicyStatus.NOT_APPLICABLE] status. */
    const val NOT_APPLICABLE_COUNT = 0

    // ── OfflineQueueReplayPolicyReport ────────────────────────────────────────

    /**
     * A machine-consumable report aggregating all five [ReplayPolicyEntry] items.
     *
     * Produced by [buildReport].  All fields are stable and suitable for wire transmission,
     * artifact storage, and V2 governance consumption.
     *
     * @property entries        All five policy entries; one per [ReplaySemanticDimension].
     * @property schemaVersion  Wire schema version for this report format.
     * @property generatedAt    Epoch-milliseconds timestamp of report generation.
     */
    data class OfflineQueueReplayPolicyReport(
        val entries: List<ReplayPolicyEntry>,
        val schemaVersion: String = SCHEMA_VERSION,
        val generatedAt: Long = System.currentTimeMillis()
    ) {
        companion object {
            // ── Wire key constants ────────────────────────────────────────────

            const val KEY_SCHEMA_VERSION = "schema_version"
            const val KEY_INTRODUCED_PR = "introduced_pr"
            const val KEY_GENERATED_AT_MS = "generated_at_ms"
            const val KEY_ENTRY_COUNT = "entry_count"
            const val KEY_HAS_FULL_SUPPORT = "has_full_support"
            const val KEY_HAS_DEFERRED_ITEMS = "has_deferred_items"
            const val KEY_HAS_NON_AUTHORITATIVE_ITEMS = "has_non_authoritative_items"
        }

        /**
         * Returns a stable key→value map suitable for wire transmission, artifact storage,
         * and V2 governance consumption.
         *
         * The map includes a top-level summary section and per-dimension status entries.
         * Per-dimension keys follow the pattern `{dimension.wireValue}_status`.
         *
         * Example output:
         * ```
         * "schema_version"                    → "1.0"
         * "introduced_pr"                     → 71
         * "entry_count"                       → 5
         * "has_full_support"                  → false
         * "has_deferred_items"                → true
         * "has_non_authoritative_items"       → true
         * "task_replay_existence_status"      → "supported"
         * "replay_ordering_guarantee_status"  → "deferred"
         * "replay_authority_status"           → "non_authoritative"
         * "duplicate_avoidance_status"        → "partially_supported"
         * "eventual_recovery_status"          → "accepted_limitation"
         * ```
         */
        fun toWireMap(): Map<String, Any> {
            val map = mutableMapOf<String, Any>(
                KEY_SCHEMA_VERSION to schemaVersion,
                KEY_INTRODUCED_PR to OfflineQueueReplayPolicy.INTRODUCED_PR,
                KEY_GENERATED_AT_MS to generatedAt,
                KEY_ENTRY_COUNT to entries.size,
                KEY_HAS_FULL_SUPPORT to hasFullSupport(),
                KEY_HAS_DEFERRED_ITEMS to hasDeferredItems(),
                KEY_HAS_NON_AUTHORITATIVE_ITEMS to hasNonAuthoritativeItems()
            )
            entries.forEach { entry ->
                map["${entry.dimension.wireValue}_status"] = entry.status.wireValue
            }
            return map.toMap()
        }

        /**
         * Returns `true` only if ALL dimensions have [ReplayPolicyStatus.SUPPORTED] status.
         *
         * Intentionally strict: any DEFERRED, NON_AUTHORITATIVE, PARTIALLY_SUPPORTED, or
         * ACCEPTED_LIMITATION entry prevents a full-support verdict.  Consumers must not
         * use this as a lazy shortcut to avoid reading individual dimension entries.
         */
        fun hasFullSupport(): Boolean =
            entries.all { it.status == ReplayPolicyStatus.SUPPORTED }

        /**
         * Returns `true` if any dimension has [ReplayPolicyStatus.DEFERRED] status.
         *
         * A `true` result means the cross-device recovery verdict for replay ordering
         * is NOT fully closed and must not be presented as such.
         */
        fun hasDeferredItems(): Boolean =
            entries.any { it.status == ReplayPolicyStatus.DEFERRED }

        /**
         * Returns `true` if any dimension has [ReplayPolicyStatus.NON_AUTHORITATIVE] status.
         *
         * A `true` result means Android is not the authority for at least one replay
         * dimension; V2 must be consulted for those dimensions.
         */
        fun hasNonAuthoritativeItems(): Boolean =
            entries.any { it.status == ReplayPolicyStatus.NON_AUTHORITATIVE }

        /**
         * Returns the [ReplayPolicyStatus] for a given [dimension], or `null` if no entry
         * is present for that dimension in this report's [entries] list.
         */
        fun statusFor(dimension: ReplaySemanticDimension): ReplayPolicyStatus? =
            entries.firstOrNull { it.dimension == dimension }?.status
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Builds and returns an [OfflineQueueReplayPolicyReport] from the canonical [allEntries].
     *
     * The report is the primary programmatic output of this policy surface.  V2 and dual-repo
     * governance audits should call this method and consume
     * [OfflineQueueReplayPolicyReport.toWireMap] rather than reading source comments.
     */
    fun buildReport(): OfflineQueueReplayPolicyReport =
        OfflineQueueReplayPolicyReport(entries = allEntries)

    // ── Query helpers ─────────────────────────────────────────────────────────

    /** Returns all entries with a given [status]. */
    fun entriesForStatus(status: ReplayPolicyStatus): List<ReplayPolicyEntry> =
        allEntries.filter { it.status == status }

    /** Returns the entry for a given [dimension], or `null` if not found. */
    fun entryFor(dimension: ReplaySemanticDimension): ReplayPolicyEntry? =
        allEntries.firstOrNull { it.dimension == dimension }

    // ── Description constant ──────────────────────────────────────────────────

    /**
     * Human-readable description of this policy surface, suitable for wire metadata
     * or audit log entries.
     */
    const val DESCRIPTION =
        "Formal policy/evidence model for Android offline queue replay ordering and authority " +
            "semantics: formalises task_replay_existence (SUPPORTED), replay_ordering_guarantee " +
            "(DEFERRED — V2-side ordering contract not yet defined), replay_authority " +
            "(NON_AUTHORITATIVE — V2 decides), duplicate_avoidance (PARTIALLY_SUPPORTED — " +
            "session-bounded only), and eventual_recovery (ACCEPTED_LIMITATION — bounded by " +
            "queue drop policy).  Machine-consumable via buildReport().toWireMap()."
}
