# Android Distributed Gate Alignment

**PR-7 (Android)** — Reopen Android PR-7: Align Android readiness evidence with canonical
distributed release-gate skeleton.

This document is the Android-side distributed gate alignment guide for reviewers, V2 gate
operators, and release-policy authors who need to understand how Android participant
evidence maps into the V2 canonical distributed release-gate skeleton.

---

## Overview

Android PR-6 ([`AndroidReadinessEvidenceSurface`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidReadinessEvidenceSurface.kt))
organised Android readiness evidence into six local dimensions with confidence levels
(CANONICAL / ADVISORY / DEPRECATED_COMPAT).

Android PR-7 adds the next layer: an explicit **mapping from Android evidence dimensions to
V2 canonical distributed gate categories**, together with an [EvidenceAuthority] classification
that tells V2 gate logic how strongly to weight each Android evidence entry within the
distributed gate model.

The companion machine-readable object is
[`AndroidDistributedGateAlignment`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidDistributedGateAlignment.kt).
The accompanying test suite is
[`Pr7AndroidDistributedGateAlignmentTest`](../app/src/test/java/com/ufo/galaxy/runtime/Pr7AndroidDistributedGateAlignmentTest.kt).

### What a reviewer can determine from this document

1. How each Android evidence dimension maps to a V2 canonical gate category.
2. Which Android evidence is **strong participant-runtime** vs **advisory** vs
   **deprecated-compat** vs **intentionally deferred**.
3. That each mapping is backed by a real runtime component and a concrete test.
4. How V2 gate logic or release-policy CI can consume Android evidence consistently
   without needing to re-interpret Android-internal confidence levels.
5. How this PR-7 effort supersedes or replaces the previous Android PR-7 attempt.

---

## V2 Authority Boundary

Android is a **participant**, not a gate operator.  This document and
`AndroidDistributedGateAlignment` provide the participant-side mapping; V2 remains
authoritative for:

- Final release gate decisions and dimension satisfaction thresholds.
- Canonical truth convergence and participant state adjudication.
- Session/task resumption and re-dispatch.
- Graduation and governance policy.

Android evidence is an **input** to the V2 gate; Android does not implement release policy.

---

## Dimension → Canonical Gate Category Mapping

Each Android readiness dimension maps one-to-one to a V2 canonical distributed gate
category:

| Android Dimension | Wire value | V2 Canonical Gate Category | Category wire value |
|---|---|---|---|
| `RUNTIME_LIFECYCLE` | `runtime_lifecycle` | `LIFECYCLE_RUNTIME_CORRECTNESS` | `lifecycle_runtime_correctness` |
| `TAKEOVER_EXECUTION` | `takeover_execution` | `TAKEOVER_EXECUTION_OUTCOMES` | `takeover_execution_outcomes` |
| `ARTIFACT_EMISSION_RECONCILIATION` | `artifact_emission_reconciliation` | `RECONCILIATION_ARTIFACT_EMISSION` | `reconciliation_artifact_emission` |
| `CONTINUITY_RECOVERY_SAFETY` | `continuity_recovery_safety` | `CONTINUITY_RECOVERY_SAFETY` | `continuity_recovery_safety` |
| `COMPATIBILITY_SUPPRESSION` | `compatibility_suppression` | `COMPATIBILITY_LEGACY_SUPPRESSION` | `compatibility_legacy_suppression` |
| `SIGNAL_REPLAY_DUPLICATE_SAFETY` | `signal_replay_duplicate_safety` | `SIGNAL_REPLAY_DUPLICATE_SAFETY` | `signal_replay_duplicate_safety` |

---

## Evidence Authority Levels

Each gate mapping entry carries an `EvidenceAuthority` that governs how V2 should weight
Android evidence in the distributed gate model:

| Authority | Wire value | Gate implication |
|-----------|------------|-----------------|
| **STRONG_PARTICIPANT_RUNTIME** | `strong_participant_runtime` | Grounded in real Android runtime; V2 gate should count as primary input. |
| **ADVISORY_OBSERVATION_ONLY** | `advisory_observation_only` | Observational; V2 gate may use as corroborating signal, not sole gate input. |
| **DEPRECATED_COMPATIBILITY** | `deprecated_compatibility` | From a legacy path; **must NOT** count toward canonical gate satisfaction. |
| **INTENTIONALLY_LOCAL_DEFERRED** | `intentionally_local_deferred` | Deferred by design; not yet available for gate consumption. |

**Summary counts at PR-7:**

| Authority | Count |
|-----------|-------|
| STRONG_PARTICIPANT_RUNTIME | 23 |
| ADVISORY_OBSERVATION_ONLY | 2 |
| DEPRECATED_COMPATIBILITY | 1 |
| INTENTIONALLY_LOCAL_DEFERRED | 0 |
| **Total** | **26** |

---

## Full Gate Mapping Matrix

### 1. `lifecycle_runtime_correctness` — Lifecycle / Runtime Correctness Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `readiness_evaluator_five_dimension_verdict` | **STRONG** | `DelegatedRuntimeReadinessEvaluator` | `Pr9DelegatedRuntimeReadinessTest` | `DeviceReadinessArtifact.semanticTag` → V2 PR-9 release gate via `ReconciliationSignal` PARTICIPANT_STATE |
| `acceptance_evaluator_six_dimension_graduation_verdict` | **STRONG** | `DelegatedRuntimeAcceptanceEvaluator` | `Pr10DelegatedRuntimeAcceptanceTest` | `DeviceAcceptanceArtifact.semanticTag` → V2 PR-10 graduation gate |
| `post_graduation_governance_evaluator_verdict` | **STRONG** | `DelegatedRuntimePostGraduationGovernanceEvaluator` | `Pr11DelegatedRuntimePostGraduationGovernanceTest` | `DeviceGovernanceArtifact` → V2 PR-11 governance gate |
| `strategy_evaluator_dispatch_verdict` | ADVISORY | `DelegatedRuntimeStrategyEvaluator` | `Pr12DelegatedRuntimeStrategyTest` | Advisory context for lifecycle gate; does not independently satisfy the dimension |
| `runtime_lifecycle_transition_event_emission` | **STRONG** | `RuntimeController`, `AndroidLifecycleRecoveryContract` | `Pr37AndroidRuntimeLifecycleHardeningTest` | Fine-grained lifecycle boundary evidence observable via `ReconciliationSignal` |

### 2. `takeover_execution_outcomes` — Takeover Execution Outcomes Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `takeover_fallback_event_canonical_bounding` | **STRONG** | `AndroidCompatLegacyBlockingParticipant`, `TakeoverFallbackEvent` | `Pr8AndroidCompatLegacyBlockingTest` | `CompatLegacyBlockingDecision.semanticTag` → V2 compat influence auditor |
| `takeover_executor_metadata_unification` | **STRONG** | `TakeoverEnvelope`, `AutonomousExecutionPipeline` | `Pr03TakeoverMetadataUnificationTest` | TakeoverEnvelope wire fields round-trip confirms metadata fidelity to V2 |
| `takeover_recovery_path_compat_gate` | ADVISORY | `AndroidCompatLegacyBlockingParticipant` | `Pr8AndroidCompatLegacyBlockingTest` | General compat gate; dedicated session-authority bounding deferred |

### 3. `reconciliation_artifact_emission` — Reconciliation / Artifact Emission Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `device_readiness_artifact_wire_emission` | **STRONG** | `DelegatedRuntimeReadinessEvaluator`, `DeviceReadinessArtifact` | `Pr4AndroidEvaluatorArtifactEmissionTest` | Stable `semanticTag` wire values emitted via `ReconciliationSignal` PARTICIPANT_STATE |
| `device_acceptance_artifact_wire_emission` | **STRONG** | `DelegatedRuntimeAcceptanceEvaluator`, `DeviceAcceptanceArtifact` | `Pr4AndroidEvaluatorArtifactEmissionTest` | `DeviceAcceptanceArtifact` subtypes emitted via `ReconciliationSignal` PARTICIPANT_STATE |
| `reconciliation_signal_participant_state_emission` | **STRONG** | `RuntimeController`, `ReconciliationSignal` | `Pr52ReconciliationSignalEmissionTest` | Primary wire emission path for all Android readiness artifacts to reach V2 |
| `reconciliation_signal_runtime_truth_snapshot_emission` | **STRONG** | `RuntimeController`, `AndroidParticipantRuntimeTruth` | `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest` | Canonical truth snapshot via `ReconciliationSignal` RUNTIME_TRUTH_SNAPSHOT |
| `unified_truth_reconciliation_surface_emission` | **STRONG** | `UnifiedTruthReconciliationSurface`, `TruthReconciliationReducer` | `Pr64UnifiedTruthReconciliationTest` | Aggregated truth patch + reduction output confirmed by V2 via RUNTIME_TRUTH_SNAPSHOT |

### 4. `continuity_recovery_safety` — Continuity / Recovery Safety Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `recovery_participation_owner_restart_reconnect_bounding` | **STRONG** | `AndroidRecoveryParticipationOwner` | `Pr66ContinuityRecoveryDurabilityTest` | `WaitForV2ReplayDecision.durableSessionId` forwarded; V2 gate confirms Android defers to V2 |
| `continuity_recovery_durability_contract_coverage` | **STRONG** | `ContinuityRecoveryDurabilityContract` | `Pr66ContinuityRecoveryDurabilityTest` | 13 covered behaviors + 6 bounded emission rules queryable by V2 governance tooling |
| `hybrid_lifecycle_recovery_contract_coverage` | **STRONG** | `AndroidLifecycleRecoveryContract`, `HybridRuntimeContinuityContract` | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | Hybrid recovery contract enforcement confirmed; V2 re-dispatch policy can trust Android recovery decisions |
| `durable_session_continuity_record_rehydration` | **STRONG** | `DelegatedFlowContinuityRecord`, `AndroidRecoveryParticipationOwner` | `Pr4AndroidRecoveryParticipationOwnerTest` | `RehydrateThenContinue` presents rehydrated context to V2; V2 confirms or overrides |

### 5. `compatibility_legacy_suppression` — Compatibility / Legacy Suppression Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `compat_legacy_blocking_participant_canonical_path_confirmation` | **STRONG** | `AndroidCompatLegacyBlockingParticipant` | `Pr8AndroidCompatLegacyBlockingTest` | `CompatLegacyBlockingDecision.semanticTag` → V2 compat influence auditor confirms canonical path |
| `compatibility_surface_retirement_registry` | **STRONG** | `CompatibilitySurfaceRetirementRegistry` | `Pr10CompatibilitySurfaceRetirementTest` | Retirement registry queryable by V2 governance tooling for suppression confirmation |
| `authoritative_path_alignment_audit` | **STRONG** | `AndroidAuthoritativePathAlignmentAudit` | `Pr65AndroidAuthoritativePathAlignmentTest` | Audit entries → `ReconciliationSignal` compat-state payload → V2 PR-3 single-path convergence gate |
| `long_tail_compat_registry_legacy_signals` | ~~DEPRECATED~~ | `LongTailCompatibilityRegistry` | `Pr35LongTailCompatHandlingTest` | **NOT counted** toward gate; V2 audit may reference for suppression confirmation only |
| `compatibility_retirement_fence_blocking` | **STRONG** | `CompatibilityRetirementFence` | `Pr36CompatRetirementHardeningTest` | Fence block outcomes observable via `ReconciliationSignal` compat-state fields |

### 6. `signal_replay_duplicate_safety` — Signal Replay / Duplicate Safety Gate

| Evidence ID | Authority | Produced By | Test Evidence | Gate Mapping Note |
|---|---|---|---|---|
| `emitted_signal_ledger_terminal_bounding` | **STRONG** | `EmittedSignalLedger` | `Pr66ContinuityRecoveryDurabilityTest` | ACK/PROGRESS suppressed after terminal RESULT; V2 gate treats RESULT as terminal |
| `continuity_integration_duplicate_signal_suppression` | **STRONG** | `AndroidContinuityIntegration` | `Pr66ContinuityRecoveryDurabilityTest` | Each `signalId` delivered to V2 at most once per execution era |
| `offline_queue_stale_session_discard` | **STRONG** | `OfflineTaskQueue` | `Pr66ContinuityRecoveryDurabilityTest` | Stale-era messages discarded before queue drain; V2 receives current-era results only |
| `delegated_execution_signal_idempotency_guard` | **STRONG** | `DelegatedExecutionSignal`, `EmittedSignalLedger` | `DelegatedExecutionSignalIdempotencyTest` | `signalId` is end-to-end deduplication key; Android-side guard + V2-side dedup close the gap |

---

## How V2 Gate Logic Can Consume Android Evidence

### Primary query paths

```kotlin
// Get all STRONG_PARTICIPANT_RUNTIME mappings for a specific gate category
AndroidDistributedGateAlignment.mappingsForCategory(
    AndroidDistributedGateAlignment.CanonicalGateCategory.LIFECYCLE_RUNTIME_CORRECTNESS
).filter {
    it.evidenceAuthority ==
        AndroidDistributedGateAlignment.EvidenceAuthority.STRONG_PARTICIPANT_RUNTIME
}

// Get the canonical gate category for an Android dimension
AndroidDistributedGateAlignment.categoryFor(
    AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
)
// → CanonicalGateCategory.CONTINUITY_RECOVERY_SAFETY

// Get all non-deprecated mappings for gate consumption
AndroidDistributedGateAlignment.gateMappings.filter {
    it.evidenceAuthority !=
        AndroidDistributedGateAlignment.EvidenceAuthority.DEPRECATED_COMPATIBILITY
}

// Look up the mapping note for a specific evidence entry
AndroidDistributedGateAlignment.mappingFor("device_readiness_artifact_wire_emission")
    ?.gateMappingNote
```

### Wire consumption paths (primary path)

```
DeviceReadinessArtifact.semanticTag
  → ReconciliationSignal (Kind.PARTICIPANT_STATE)
  → GalaxyConnectionService → WebSocket → V2
  → V2 canonical gate parses semanticTag by category

DeviceAcceptanceArtifact.semanticTag
  → ReconciliationSignal (Kind.PARTICIPANT_STATE)
  → V2 graduation gate

AndroidParticipantRuntimeTruth snapshot
  → ReconciliationSignal (Kind.RUNTIME_TRUTH_SNAPSHOT)
  → V2 truth reconciliation layer

CompatLegacyBlockingDecision.semanticTag
  → ReconciliationSignal (Kind.PARTICIPANT_STATE) compat-state payload
  → V2 compat influence auditor
```

### Test index by canonical gate category

| Gate Category | Primary Android test class(es) |
|---|---|
| `lifecycle_runtime_correctness` | `Pr9DelegatedRuntimeReadinessTest`, `Pr10DelegatedRuntimeAcceptanceTest`, `Pr11DelegatedRuntimePostGraduationGovernanceTest`, `Pr37AndroidRuntimeLifecycleHardeningTest` |
| `takeover_execution_outcomes` | `Pr03TakeoverMetadataUnificationTest`, `Pr8AndroidCompatLegacyBlockingTest` |
| `reconciliation_artifact_emission` | `Pr4AndroidEvaluatorArtifactEmissionTest`, `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest`, `Pr64UnifiedTruthReconciliationTest` |
| `continuity_recovery_safety` | `Pr66ContinuityRecoveryDurabilityTest`, `Pr4AndroidRecoveryParticipationOwnerTest`, `Pr53AndroidLifecycleRecoveryHybridHardeningTest` |
| `compatibility_legacy_suppression` | `Pr8AndroidCompatLegacyBlockingTest`, `Pr10CompatibilitySurfaceRetirementTest`, `Pr65AndroidAuthoritativePathAlignmentTest`, `Pr36CompatRetirementHardeningTest` |
| `signal_replay_duplicate_safety` | `Pr66ContinuityRecoveryDurabilityTest`, `DelegatedExecutionSignalIdempotencyTest` |

---

## Relationship to Android PR-6 Evidence Surface

This document adds one layer on top of
[`ANDROID_READINESS_EVIDENCE.md`](ANDROID_READINESS_EVIDENCE.md):

| Concept | Defined in |
|---------|-----------|
| Android readiness dimensions | PR-6 / `AndroidReadinessEvidenceSurface` |
| Evidence confidence levels (CANONICAL / ADVISORY / DEPRECATED_COMPAT) | PR-6 / `AndroidReadinessEvidenceSurface.ConfidenceLevel` |
| V2 canonical gate categories | **PR-7 / `AndroidDistributedGateAlignment.CanonicalGateCategory`** |
| Evidence authority in the gate model (STRONG / ADVISORY / DEPRECATED / DEFERRED) | **PR-7 / `AndroidDistributedGateAlignment.EvidenceAuthority`** |
| Explicit dimension → category mapping | **PR-7 / `AndroidDistributedGateAlignment.dimensionToCategoryMap`** |
| Per-evidence gate mapping with V2 consumption notes | **PR-7 / `AndroidDistributedGateAlignment.gateMappings`** |

The PR-6 confidence level and PR-7 evidence authority are intentionally aligned:

| PR-6 ConfidenceLevel | PR-7 EvidenceAuthority |
|---|---|
| CANONICAL | STRONG_PARTICIPANT_RUNTIME (primary), or ADVISORY_OBSERVATION_ONLY (secondary) |
| ADVISORY | ADVISORY_OBSERVATION_ONLY |
| DEPRECATED_COMPAT | DEPRECATED_COMPATIBILITY |

---

## Machine-Readable Objects

- [`AndroidDistributedGateAlignment.kt`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidDistributedGateAlignment.kt) — canonical machine-readable gate alignment
- [`AndroidReadinessEvidenceSurface.kt`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidReadinessEvidenceSurface.kt) — PR-6 evidence surface (prerequisite)
- [`Pr7AndroidDistributedGateAlignmentTest.kt`](../app/src/test/java/com/ufo/galaxy/runtime/Pr7AndroidDistributedGateAlignmentTest.kt) — test suite validating all five acceptance criteria

All five acceptance criteria (AC1–AC5) are validated by `Pr7AndroidDistributedGateAlignmentTest`.
