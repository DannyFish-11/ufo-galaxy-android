package com.ufo.galaxy.runtime

/**
 * PR-68 (Android) — Sealed class representing the Android device-side delegated runtime
 * audit evidence quality for V2 cross-repo evidence ingestion and acceptance auditing.
 *
 * [AndroidDelegatedRuntimeAuditEvidence] is the typed output of
 * [AndroidDelegatedRuntimeAudit.evaluateEvidence].  Every V2 system component that needs
 * to absorb Android participant runtime state must inspect the evidence quality before
 * making acceptance or governance decisions.
 *
 * ## Evidence quality states
 *
 * Each concrete subtype corresponds to a named evidence quality outcome:
 *
 * | Subtype                              | Semantic tag                       | Description                                                                       |
 * |--------------------------------------|------------------------------------|-----------------------------------------------------------------------------------|
 * | [AuditEvidenceReady]                 | `audit_evidence_ready`             | All six dimensions are AUDITED and fresh; evidence is fully consumable by V2.     |
 * | [AuditEvidenceDegraded]              | `audit_evidence_degraded`          | Some dimensions are DEGRADED; participant is available but not fully healthy.     |
 * | [AuditEvidenceUnavailable]           | `audit_evidence_unavailable`       | Participant is not reachable or not registered; evidence cannot be produced.      |
 * | [AuditEvidenceStale]                 | `audit_evidence_stale`             | Evidence is too old to be consumed; a refresh is required before V2 can use it.  |
 * | [AuditEvidenceMalformedOrIncomplete] | `audit_evidence_malformed_incomplete` | Evidence is incomplete or malformed; V2 must not consume it.                  |
 * | [AuditEvidenceUnverified]            | `audit_evidence_unverified`        | One or more dimensions have no signal; audit cannot be concluded.                 |
 *
 * ## Consumption contract
 *
 * Only [AuditEvidenceReady] signals that the Android participant's runtime state can be
 * reliably consumed by V2 for acceptance and governance decisions.  All other states must
 * be treated as blocking conditions:
 *
 *  - [AuditEvidenceDegraded]: V2 may acknowledge the participant with limited capability
 *    but must not treat it as fully operational.
 *  - [AuditEvidenceUnavailable]: V2 must treat the participant as absent/offline.
 *  - [AuditEvidenceStale]: V2 must trigger a refresh before consuming Android state.
 *  - [AuditEvidenceMalformedOrIncomplete]: V2 must reject the evidence payload.
 *  - [AuditEvidenceUnverified]: V2 must wait for evidence signals before concluding.
 *
 * @see AndroidDelegatedRuntimeAudit
 * @see AndroidDelegatedRuntimeAuditDimension
 * @see AndroidDelegatedRuntimeAuditSnapshot
 */
sealed class AndroidDelegatedRuntimeAuditEvidence {

    /**
     * Stable wire tag identifying the evidence quality for this evidence instance.
     *
     * Matches one of the [AndroidDelegatedRuntimeAudit] `EVIDENCE_*` constants.
     */
    abstract val evidenceTag: String

    // ── AuditEvidenceReady ────────────────────────────────────────────────────

    /**
     * All six audit dimensions are AUDITED and evidence is fresh.
     *
     * The Android participant's runtime state is fully observable and can be reliably
     * consumed by V2 for acceptance, governance, and readiness decisions.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_READY].
     *
     * @property deviceId    The device identifier for which audit was evaluated.
     * @property snapshotId  Stable identifier for the audit snapshot that produced this evidence.
     */
    data class AuditEvidenceReady(
        val deviceId: String,
        val snapshotId: String
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String = AndroidDelegatedRuntimeAudit.EVIDENCE_READY
    }

    // ── AuditEvidenceDegraded ─────────────────────────────────────────────────

    /**
     * One or more audit dimensions are DEGRADED.
     *
     * The participant is reachable and registered but is operating in a degraded state.
     * V2 may acknowledge the participant as present but must not treat it as fully
     * operational or route critical work to it.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_DEGRADED].
     *
     * @property deviceId         The device identifier for which audit was evaluated.
     * @property snapshotId       Stable identifier for the audit snapshot.
     * @property degradedReason   Human-readable explanation of the degraded condition.
     * @property dimension        The [AndroidDelegatedRuntimeAuditDimension] that produced
     *                            the first DEGRADED state triggering this evidence.
     */
    data class AuditEvidenceDegraded(
        val deviceId: String,
        val snapshotId: String,
        val degradedReason: String,
        val dimension: AndroidDelegatedRuntimeAuditDimension
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String = AndroidDelegatedRuntimeAudit.EVIDENCE_DEGRADED
    }

    // ── AuditEvidenceUnavailable ──────────────────────────────────────────────

    /**
     * The participant is unavailable: not registered, offline, or in a recovery phase.
     *
     * V2 must treat the Android participant as absent.  Evidence cannot be produced in
     * a meaningful form when the participant is unavailable.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_UNAVAILABLE].
     *
     * @property deviceId          The device identifier for which audit was evaluated.
     * @property snapshotId        Stable identifier for the audit snapshot.
     * @property unavailableReason Human-readable explanation of why the participant is unavailable.
     * @property dimension         The [AndroidDelegatedRuntimeAuditDimension] that produced
     *                             the first UNAVAILABLE state triggering this evidence.
     */
    data class AuditEvidenceUnavailable(
        val deviceId: String,
        val snapshotId: String,
        val unavailableReason: String,
        val dimension: AndroidDelegatedRuntimeAuditDimension
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String = AndroidDelegatedRuntimeAudit.EVIDENCE_UNAVAILABLE
    }

    // ── AuditEvidenceStale ────────────────────────────────────────────────────

    /**
     * The audit evidence is stale: its timestamp is older than the freshness threshold.
     *
     * V2 must trigger a refresh of Android audit evidence before consuming participant
     * state.  Stale evidence must not be treated as current operational truth.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_STALE].
     *
     * @property deviceId       The device identifier for which audit was evaluated.
     * @property snapshotId     Stable identifier for the audit snapshot.
     * @property staleReason    Human-readable explanation of why evidence is considered stale
     *                          (e.g. "evidence is 65000ms old; threshold is 60000ms").
     * @property dimension      Always [AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS].
     */
    data class AuditEvidenceStale(
        val deviceId: String,
        val snapshotId: String,
        val staleReason: String,
        val dimension: AndroidDelegatedRuntimeAuditDimension =
            AndroidDelegatedRuntimeAuditDimension.EVIDENCE_FRESHNESS
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String = AndroidDelegatedRuntimeAudit.EVIDENCE_STALE
    }

    // ── AuditEvidenceMalformedOrIncomplete ────────────────────────────────────

    /**
     * The audit evidence is malformed or incomplete.
     *
     * One or more dimensions have data in a malformed state or required fields are
     * missing.  V2 must reject this evidence payload and must not use it for acceptance
     * or governance decisions.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_MALFORMED_INCOMPLETE].
     *
     * @property deviceId          The device identifier for which audit was evaluated.
     * @property snapshotId        Stable identifier for the audit snapshot.
     * @property malformedReason   Human-readable explanation of what is malformed or missing.
     * @property dimension         The [AndroidDelegatedRuntimeAuditDimension] that produced
     *                             the first MALFORMED state triggering this evidence.
     */
    data class AuditEvidenceMalformedOrIncomplete(
        val deviceId: String,
        val snapshotId: String,
        val malformedReason: String,
        val dimension: AndroidDelegatedRuntimeAuditDimension
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String =
            AndroidDelegatedRuntimeAudit.EVIDENCE_MALFORMED_INCOMPLETE
    }

    // ── AuditEvidenceUnverified ───────────────────────────────────────────────

    /**
     * One or more audit dimensions have no signal; audit evidence cannot be concluded.
     *
     * V2 must wait for evidence signals to be received before forming any conclusions
     * about the Android participant's runtime state.  This is a blocking condition
     * equivalent to unavailable for V2 evidence consumption purposes.
     *
     * Android-side semantic: [AndroidDelegatedRuntimeAudit.EVIDENCE_UNVERIFIED].
     *
     * @property deviceId           The device identifier for which audit was evaluated.
     * @property snapshotId         Stable identifier for the audit snapshot.
     * @property missingDimensions  The set of [AndroidDelegatedRuntimeAuditDimension] values
     *                              for which no signal was available.
     */
    data class AuditEvidenceUnverified(
        val deviceId: String,
        val snapshotId: String,
        val missingDimensions: Set<AndroidDelegatedRuntimeAuditDimension>
    ) : AndroidDelegatedRuntimeAuditEvidence() {
        override val evidenceTag: String = AndroidDelegatedRuntimeAudit.EVIDENCE_UNVERIFIED
    }
}
