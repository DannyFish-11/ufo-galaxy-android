# Android Distributed Gate Mapping

**PR-7Android (reopened)** — Reopen Android PR-7: Align Android readiness evidence with
canonical distributed release-gate skeleton.

This document explains how Android-side readiness evidence maps into the six V2 canonical
distributed gate dimensions established by V2 PR-7.  It is the reviewer guide for the
[`AndroidDistributedGateMapping`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidDistributedGateMapping.kt)
object and its accompanying test suite
[`Pr7bAndroidDistributedGateMappingTest`](../app/src/test/java/com/ufo/galaxy/runtime/Pr7bAndroidDistributedGateMappingTest.kt).

---

## Context and Purpose

**Android PR-6** ([`AndroidReadinessEvidenceSurface`](ANDROID_READINESS_EVIDENCE.md))
organised Android-side readiness evidence into six internal dimensions with confidence
levels (CANONICAL / ADVISORY / DEPRECATED_COMPAT) and V2 consumption paths.

**This PR (Android PR-7 reopened)** adds the minimum safe alignment layer so that Android
evidence can be explicitly mapped to the V2 canonical distributed gate dimensions.  It does
not implement release policy, CI blocking, or independent Android gate authority.  **V2
remains the canonical orchestration authority.**

### What a reviewer can determine from this document

1. How every Android evidence category maps into the V2 canonical distributed gate categories.
2. Which Android evidence is **strong** (suitable for V2 gate consumption) vs **advisory**
   (corroborating context only) vs **deprecated-compat** (must NOT count toward gate
   satisfaction) vs **intentionally-local** (correct but not directly V2-consumed).
3. That every V2 gate dimension has at least one strong Android evidence entry backed by
   a concrete test suite.
4. How later release-policy or CI work can consume Android evidence consistently from V2
   by querying the machine-readable mapping object.
5. How this fresh PR-7 supersedes the previous Android PR-7 effort, including where the
   earlier work (canonical execution events, durable participant identity) fits in the
   mapping.

---

## Evidence Alignment Model

| Alignment | Wire value | Meaning | V2 gate implication |
|-----------|------------|---------|---------------------|
| **STRONG_PARTICIPANT_RUNTIME** | `strong_participant_runtime` | From the canonical Android runtime path, backed by concrete tests | **Primary gate input** — V2 may consume as direct participant evidence |
| **ADVISORY_OBSERVATION** | `advisory_observation` | Observational or indirect | **Corroborating context only** — not gate-sufficient alone |
| **DEPRECATED_COMPAT** | `deprecated_compat` | From a legacy/compat path | **Must NOT count toward gate satisfaction** — listed for traceability |
| **INTENTIONALLY_LOCAL** | `intentionally_local` | Correct but local/deferred by design | **Not directly V2-consumed** — may become gate-relevant in a later PR |

---

## V2 Canonical Distributed Gate Dimensions

### 1. Lifecycle / Runtime Correctness (`lifecycle_runtime_correctness`)

Evidence that the Android delegated runtime lifecycle is stable and that the readiness,
acceptance, and governance evaluator chains produce correct verdicts.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `runtime_lifecycle_evaluator_chain_to_gate_lifecycle` | `readiness_evaluator_five_dimension_verdict`, `acceptance_evaluator_six_dimension_graduation_verdict`, `post_graduation_governance_evaluator_verdict`, `runtime_lifecycle_transition_event_emission` | **STRONG** | `Pr9DelegatedRuntimeReadinessTest`, `Pr10DelegatedRuntimeAcceptanceTest`, `Pr11DelegatedRuntimePostGraduationGovernanceTest`, `Pr37AndroidRuntimeLifecycleHardeningTest` |
| `canonical_execution_event_emission_to_gate_lifecycle` | `runtime_lifecycle_transition_event_emission` | **STRONG** | `Pr7AndroidCanonicalExecutionEventsTest` |
| `strategy_evaluator_to_gate_lifecycle_advisory` | `strategy_evaluator_dispatch_verdict` | ADVISORY | `Pr12DelegatedRuntimeStrategyTest` |

**V2 consumption**: `DeviceReadinessArtifact.semanticTag` and `DeviceAcceptanceArtifact.semanticTag`
are emitted via `ReconciliationSignal` `Kind.PARTICIPANT_STATE` → V2 parses `semanticTag` to
determine lifecycle correctness gate satisfaction.

**Note on prior PR-7 work**: `AndroidCanonicalExecutionEventOwner` (introduced in the prior
Android PR-7 effort) ensures canonical execution events are emitted with stable wire values
and correct suppression semantics.  This evidence feeds the lifecycle correctness gate via
`ReconciliationSignal`.

---

### 2. Takeover Execution Outcomes (`takeover_execution_outcomes`)

Evidence that Android takeover and fallback execution paths are correctly bounded and that
takeover metadata is faithfully propagated through the Android execution pipeline.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `takeover_compat_bounding_to_gate_takeover` | `takeover_fallback_event_canonical_bounding`, `takeover_executor_metadata_unification` | **STRONG** | `Pr8AndroidCompatLegacyBlockingTest`, `Pr03TakeoverMetadataUnificationTest` |
| `takeover_recovery_authority_to_gate_takeover_advisory` | `takeover_recovery_path_compat_gate` | ADVISORY | `Pr8AndroidCompatLegacyBlockingTest` |

**V2 consumption**: `CompatLegacyBlockingDecision.semanticTag` emitted in
`ReconciliationSignal` `PARTICIPANT_STATE` → V2 takeover outcome auditor.

**Deferred**: Dedicated takeover session-authority bounding will become **STRONG** once V2
PR-2 companion takeover authority surface is stable.  Until then the advisory entry covers
takeover recovery.

---

### 3. Participant Truth Reconciliation (`participant_truth_reconciliation`)

Evidence that Android truth ownership is stable and that reconciliation signals carrying
the runtime truth snapshot are emitted correctly to V2.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `truth_reconciliation_signals_to_gate_truth` | `reconciliation_signal_runtime_truth_snapshot_emission`, `unified_truth_reconciliation_surface_emission`, `reconciliation_signal_participant_state_emission` | **STRONG** | `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest`, `Pr64UnifiedTruthReconciliationTest` |
| `durable_identity_reattach_to_gate_truth` | `reconciliation_signal_runtime_truth_snapshot_emission` | **STRONG** | `Pr7DurableParticipantIdentityReattachTest` |

**V2 consumption**: `ReconciliationSignal` (`RUNTIME_TRUTH_SNAPSHOT` and `PARTICIPANT_STATE`
kinds) → `GalaxyConnectionService` → V2 WebSocket ingestion → V2 truth reconciliation layer
/ participant state registry.

**Note on prior PR-7 work**: Durable participant identity reattach semantics (prior Android
PR-7) ensure a reconnecting Android participant can present stable identity for truth
reconciliation.  This maps to the `PARTICIPANT_TRUTH_RECONCILIATION` gate dimension.

---

### 4. Evaluator Artifact Emission (`evaluator_artifact_emission`)

Evidence that structured evaluator artifacts (readiness, acceptance, governance, strategy)
are emitted from real runtime paths with stable wire values that V2 release, graduation, and
governance gates can parse.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `device_artifacts_to_gate_evaluator` | `device_readiness_artifact_wire_emission`, `device_acceptance_artifact_wire_emission` | **STRONG** | `Pr4AndroidEvaluatorArtifactEmissionTest` |
| `governance_artifact_to_gate_evaluator` | `post_graduation_governance_evaluator_verdict` | **STRONG** | `Pr11DelegatedRuntimePostGraduationGovernanceTest` |
| `strategy_artifact_to_gate_evaluator_advisory` | `strategy_evaluator_dispatch_verdict` | ADVISORY | `Pr12DelegatedRuntimeStrategyTest` |

**V2 consumption**:
- `DeviceReadinessArtifact.semanticTag` → V2 PR-9 release gate
- `DeviceAcceptanceArtifact.semanticTag` → V2 PR-10 graduation gate
- `DeviceGovernanceArtifact` → V2 PR-11 post-graduation governance gate

---

### 5. Continuity / Recovery Safety (`continuity_recovery_safety`)

Evidence that Android correctly bounds in-flight work across disruptions and that duplicate
or stale signals never reach V2.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `recovery_bounding_to_gate_continuity` | `recovery_participation_owner_restart_reconnect_bounding`, `continuity_recovery_durability_contract_coverage`, `hybrid_lifecycle_recovery_contract_coverage`, `durable_session_continuity_record_rehydration` | **STRONG** | `Pr66ContinuityRecoveryDurabilityTest`, `Pr4AndroidRecoveryParticipationOwnerTest`, `Pr53AndroidLifecycleRecoveryHybridHardeningTest` |
| `signal_dedup_replay_to_gate_continuity` | `emitted_signal_ledger_terminal_bounding`, `continuity_integration_duplicate_signal_suppression`, `offline_queue_stale_session_discard`, `delegated_execution_signal_idempotency_guard` | **STRONG** | `Pr66ContinuityRecoveryDurabilityTest`, `DelegatedExecutionSignalIdempotencyTest`, `EmittedSignalLedgerTest` |

**Recovery scenarios**:

| Scenario | Android decision | V2 role |
|----------|-----------------|---------|
| Process recreation + prior context | `RehydrateThenContinue` | V2 confirms/overrides |
| Process recreation + no context | `WaitForV2ReplayDecision` | V2 decides replay/resume/fresh |
| Transport reconnect | `WaitForV2ReplayDecision` (with `durableSessionId`) | V2 decides replay |
| Receiver/pipeline rebind | `ResumeLocalExecution` | V2 not consulted |
| Fresh attach | `NoRecoveryContext` | Not applicable |
| Duplicate recovery | `SuppressDuplicateLocalRecovery` | Dropped before pipeline entry |

**V2 consumption**: `LocalRecoveryDecision.WaitForV2ReplayDecision.durableSessionId` forwarded
in recovery handshake → V2 decides replay/resume/fresh.  `ContinuityRecoveryDurabilityContract`
`.coveredBehaviors` and `.boundedEmissions` queryable by V2 governance tooling.

---

### 6. Compatibility / Legacy Suppression (`compatibility_legacy_suppression`)

Evidence that compat/legacy influence paths are classified, blocked, or quarantined and do
not corrupt canonical runtime state or gate evidence.

| Mapping ID | Android Evidence | Alignment | Key Test Classes |
|------------|-----------------|-----------|-----------------|
| `compat_blocking_audit_to_gate_compat` | `compat_legacy_blocking_participant_canonical_path_confirmation`, `compatibility_surface_retirement_registry`, `authoritative_path_alignment_audit`, `compatibility_retirement_fence_blocking` | **STRONG** | `Pr8AndroidCompatLegacyBlockingTest`, `Pr10CompatibilitySurfaceRetirementTest`, `Pr65AndroidAuthoritativePathAlignmentTest`, `Pr36CompatRetirementHardeningTest` |
| `long_tail_compat_to_gate_compat_deprecated` | `long_tail_compat_registry_legacy_signals` | ~~DEPRECATED_COMPAT~~ | `Pr35LongTailCompatHandlingTest` |

⚠️ **Important**: `long_tail_compat_to_gate_compat_deprecated` is **DEPRECATED_COMPAT** and
**must not** count toward gate satisfaction.  It is listed only for traceability and suppression
confirmation.

**Influence classification model** (`AndroidCompatLegacyBlockingParticipant`):

| Influence class | Meaning | Gate implication |
|-----------------|---------|-----------------|
| `CANONICAL_RUNTIME_PATH_CONFIRMED` | Path confirmed canonical | ✅ Contributes to gate evidence |
| `ALLOW_FOR_OBSERVATION_ONLY` | Legacy path; observation only | ℹ️ Advisory only |
| `BLOCK_DUE_TO_LEGACY_RUNTIME_PATH` | Non-canonical path; block | ❌ Gate blocks if unresolved |
| `BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE` | Legacy contract influence; suppress | ❌ Gate blocks if unresolved |
| `QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE` | Ambiguous state; quarantine | ❌ Gate blocks if unresolved |

---

## Supersession of Previous Android PR-7 Work

The previous Android PR-7 effort introduced two important pieces of runtime machinery:

1. **`AndroidCanonicalExecutionEventOwner`** — canonical execution event tracking with
   stable wire values and correct suppression semantics (duplicate, post-terminal, noise,
   reconnect-hold).  Tested by `Pr7AndroidCanonicalExecutionEventsTest`.

2. **Durable participant identity reattach** — semantics ensuring a reconnecting Android
   participant can present stable identity across disruptions.  Tested by
   `Pr7DurableParticipantIdentityReattachTest`.

This reopened PR-7 **preserves and builds on** that runtime work.  Both outputs are
included in the distributed gate mapping:
- Canonical execution events feed `LIFECYCLE_RUNTIME_CORRECTNESS` (mapping:
  `canonical_execution_event_emission_to_gate_lifecycle`).
- Durable participant identity feeds `PARTICIPANT_TRUTH_RECONCILIATION` (mapping:
  `durable_identity_reattach_to_gate_truth`).

The fresh PR-7 adds the explicit gate-alignment layer that connects all prior Android
evidence — including the earlier PR-7 work — to the V2 distributed gate skeleton, so
reviewers can trace every piece of Android evidence to a specific gate dimension and
alignment strength.

---

## Machine-Readable Mapping Object

The canonical machine-readable version of this document is
[`AndroidDistributedGateMapping.kt`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidDistributedGateMapping.kt).

The accompanying test suite is
[`Pr7bAndroidDistributedGateMappingTest.kt`](../app/src/test/java/com/ufo/galaxy/runtime/Pr7bAndroidDistributedGateMappingTest.kt).

### Querying the mapping programmatically

```kotlin
// All STRONG entries for the continuity/recovery gate dimension
AndroidDistributedGateMapping.mappingsForDimension(
    AndroidDistributedGateMapping.GateDimension.CONTINUITY_RECOVERY_SAFETY
).filter {
    it.evidenceAlignment == AndroidDistributedGateMapping.EvidenceAlignment.STRONG_PARTICIPANT_RUNTIME
}

// All V2 gate dimensions that have at least one STRONG Android evidence mapping
AndroidDistributedGateMapping.dimensionsCoveredByStrongEvidence()

// All mappings that reference a specific Android evidence entry
AndroidDistributedGateMapping.mappingsForAndroidEvidence(
    "readiness_evaluator_five_dimension_verdict"
)
```

### Test index by gate dimension

| Gate Dimension | Primary Android Test Classes |
|----------------|------------------------------|
| `lifecycle_runtime_correctness` | `Pr9DelegatedRuntimeReadinessTest`, `Pr10DelegatedRuntimeAcceptanceTest`, `Pr11DelegatedRuntimePostGraduationGovernanceTest`, `Pr37AndroidRuntimeLifecycleHardeningTest`, `Pr7AndroidCanonicalExecutionEventsTest` |
| `takeover_execution_outcomes` | `Pr8AndroidCompatLegacyBlockingTest`, `Pr03TakeoverMetadataUnificationTest` |
| `participant_truth_reconciliation` | `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest`, `Pr64UnifiedTruthReconciliationTest`, `Pr7DurableParticipantIdentityReattachTest` |
| `evaluator_artifact_emission` | `Pr4AndroidEvaluatorArtifactEmissionTest`, `Pr11DelegatedRuntimePostGraduationGovernanceTest` |
| `continuity_recovery_safety` | `Pr66ContinuityRecoveryDurabilityTest`, `Pr4AndroidRecoveryParticipationOwnerTest`, `Pr53AndroidLifecycleRecoveryHybridHardeningTest`, `DelegatedExecutionSignalIdempotencyTest`, `EmittedSignalLedgerTest` |
| `compatibility_legacy_suppression` | `Pr8AndroidCompatLegacyBlockingTest`, `Pr10CompatibilitySurfaceRetirementTest`, `Pr65AndroidAuthoritativePathAlignmentTest`, `Pr36CompatRetirementHardeningTest` |

All acceptance criteria for this PR are validated by `Pr7bAndroidDistributedGateMappingTest`.

---

## V2 Authority Boundary

Android is a **participant**, not a release orchestrator.  This document and
`AndroidDistributedGateMapping` provide the Android-side evidence alignment; V2 remains
authoritative for:

- Final release gate decisions.
- Canonical truth convergence and participant state adjudication.
- Session/task resumption and re-dispatch.
- Graduation and governance policy.

Android evidence mapped here is **input** to V2 gate logic, not an independent gate decision.
V2-side gate implementation should be in the V2 repository (V2 PR-7 and companions).
