# Android Readiness Evidence Surface

**PR-6Android / PR-67** — Make Android readiness evidence reviewable and release-gate
friendly.

This document is the Android-side readiness evidence guide for reviewers, release gates,
and governance PRs that need to assess whether Android is a trustworthy participant in a
V2 canonical release.

---

## Overview

The Android repository has accumulated a significant body of runtime machinery across
PRs 1–PR-5Android.  This document and the companion
[`AndroidReadinessEvidenceSurface`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidReadinessEvidenceSurface.kt)
object organise that evidence into a coherent, auditable, and release-gate-friendly
surface.

### What a reviewer can determine from this document

1. What Android-side signals/tests/artifacts count as readiness evidence.
2. Which evidence is **strong/canonical** vs **advisory/observational** vs **deprecated-compat**.
3. Where the main readiness dimensions are covered in the Android repository.
4. How later governance/release gating work can consume this evidence.
5. What remains deferred to later PRs.

---

## Evidence Confidence Levels

| Level | Wire value | Meaning |
|-------|------------|---------|
| **CANONICAL** | `canonical` | Evidence comes from the canonical runtime path; strong for release gating. |
| **ADVISORY** | `advisory` | Observational or indirect evidence; informative but not sufficient alone for gating. |
| **DEPRECATED_COMPAT** | `deprecated_compat` | Evidence from a legacy/compat path; **must NOT** count as strong readiness evidence. |

---

## Readiness Dimensions and Evidence

### 1. Runtime Lifecycle Correctness (`runtime_lifecycle`)

Evidence that the Android delegated runtime lifecycle is stable and that the device can
produce structured readiness and acceptance verdicts that V2 release/graduation gates can
consume.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `readiness_evaluator_five_dimension_verdict` | **CANONICAL** | `DelegatedRuntimeReadinessEvaluator` | `Pr9DelegatedRuntimeReadinessTest` | `DeviceReadinessArtifact` → `ReconciliationSignal` → V2 PR-9 release gate |
| `acceptance_evaluator_six_dimension_graduation_verdict` | **CANONICAL** | `DelegatedRuntimeAcceptanceEvaluator` | `Pr10DelegatedRuntimeAcceptanceTest` | `DeviceAcceptanceArtifact` → `ReconciliationSignal` → V2 PR-10 graduation gate |
| `post_graduation_governance_evaluator_verdict` | **CANONICAL** | `DelegatedRuntimePostGraduationGovernanceEvaluator` | `Pr11DelegatedRuntimePostGraduationGovernanceTest` | `DeviceGovernanceArtifact` → `ReconciliationSignal` → V2 PR-11 governance gate |
| `strategy_evaluator_dispatch_verdict` | ADVISORY | `DelegatedRuntimeStrategyEvaluator` | `Pr12DelegatedRuntimeStrategyTest` | `DeviceStrategyArtifact` → `ReconciliationSignal` → V2 strategy gate |
| `runtime_lifecycle_transition_event_emission` | **CANONICAL** | `RuntimeController`, `AndroidLifecycleRecoveryContract` | `Pr37AndroidRuntimeLifecycleHardeningTest` | `RuntimeController.reconciliationSignals` → `GalaxyConnectionService` → V2 |

**Summary**: The readiness/acceptance/governance evaluator chain provides structured,
typed artifacts at every decision point.  `DeviceReadyForRelease` → `DeviceAcceptedForGraduation`
→ ongoing governance is the canonical lifecycle path.

---

### 2. Takeover Execution Behavior (`takeover_execution`)

Evidence that Android takeover and fallback execution paths are correctly bounded by
canonical flow controls.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `takeover_fallback_event_canonical_bounding` | **CANONICAL** | `AndroidCompatLegacyBlockingParticipant`, `TakeoverFallbackEvent` | `Pr8AndroidCompatLegacyBlockingTest` | `CompatLegacyBlockingDecision.semanticTag` in `ReconciliationSignal` |
| `takeover_executor_metadata_unification` | **CANONICAL** | `TakeoverEnvelope`, `AutonomousExecutionPipeline` | `Pr03TakeoverMetadataUnificationTest` | `continuity_token` echoed in `DelegatedExecutionSignal` RESULT payloads |
| `takeover_recovery_path_compat_gate` | ADVISORY | `AndroidCompatLegacyBlockingParticipant` | `Pr8AndroidCompatLegacyBlockingTest` | Deferred — `takeover_session_authority_bounding` |

**Summary**: Takeover metadata round-trips are validated.  Takeover paths on non-canonical
influence classes are blocked or quarantined.  Dedicated session-authority bounding for
takeover recovery is deferred (see §Deferred Items below).

---

### 3. Artifact Emission and Reconciliation (`artifact_emission_reconciliation`)

Evidence that Android-side evaluator artifacts and reconciliation signals are emitted
from real runtime paths in a V2-consumable form.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `device_readiness_artifact_wire_emission` | **CANONICAL** | `DelegatedRuntimeReadinessEvaluator`, `DeviceReadinessArtifact` | `Pr4AndroidEvaluatorArtifactEmissionTest` | `ReconciliationSignal` Kind.PARTICIPANT_STATE → V2 release gate |
| `device_acceptance_artifact_wire_emission` | **CANONICAL** | `DelegatedRuntimeAcceptanceEvaluator`, `DeviceAcceptanceArtifact` | `Pr4AndroidEvaluatorArtifactEmissionTest` | `ReconciliationSignal` Kind.PARTICIPANT_STATE → V2 graduation gate |
| `reconciliation_signal_participant_state_emission` | **CANONICAL** | `RuntimeController`, `ReconciliationSignal` | `Pr52ReconciliationSignalEmissionTest` | `GalaxyConnectionService` → V2 WebSocket → V2 participant state registry |
| `reconciliation_signal_runtime_truth_snapshot_emission` | **CANONICAL** | `RuntimeController`, `AndroidParticipantRuntimeTruth` | `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest` | `ReconciliationSignal` Kind.RUNTIME_TRUTH_SNAPSHOT → V2 truth reconciliation |
| `unified_truth_reconciliation_surface_emission` | **CANONICAL** | `UnifiedTruthReconciliationSurface`, `TruthReconciliationReducer` | `Pr64UnifiedTruthReconciliationTest` | Truth patch → `AndroidParticipantRuntimeTruth` → `ReconciliationSignal` → V2 |

**Send path**:
```
RuntimeController.reconciliationSignals (SharedFlow)
  → GalaxyConnectionService collector
  → GalaxyConnectionService.sendReconciliationSignal()
  → GalaxyWebSocketClient.sendJson()
  → V2 WebSocket ingestion
```

**Note on instrumented E2E**: The above path is validated by unit tests.  Full end-to-end
instrumented tests (real service + test-double V2 WebSocket) are deferred — see
`instrumented_e2e_readiness_evidence_test`.

---

### 4. Continuity and Recovery Safety (`continuity_recovery_safety`)

Evidence that Android correctly bounds in-flight work after restart, reconnect, or
offline period.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `recovery_participation_owner_restart_reconnect_bounding` | **CANONICAL** | `AndroidRecoveryParticipationOwner` | `Pr66ContinuityRecoveryDurabilityTest` | `WaitForV2ReplayDecision.durableSessionId` forwarded to V2 recovery handshake |
| `continuity_recovery_durability_contract_coverage` | **CANONICAL** | `ContinuityRecoveryDurabilityContract` | `Pr66ContinuityRecoveryDurabilityTest` (all 13 covered behaviors, 6 bounded emissions) | `.coveredBehaviors`, `.boundedEmissions` queryable by V2 governance tooling |
| `hybrid_lifecycle_recovery_contract_coverage` | **CANONICAL** | `AndroidLifecycleRecoveryContract`, `HybridRuntimeContinuityContract` | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | Recovery decision artifacts → `ReconciliationSignal` → V2 re-dispatch policy |
| `durable_session_continuity_record_rehydration` | **CANONICAL** | `DelegatedFlowContinuityRecord`, `AndroidRecoveryParticipationOwner` | `Pr4AndroidRecoveryParticipationOwnerTest` | `RehydrateThenContinue` presented to V2; V2 confirms or overrides |

**Recovery semantics**:

| Disruption scenario | Android recovery decision | V2 role |
|--------------------|--------------------------|---------|
| Process recreation + prior context | `RehydrateThenContinue` (Android presents context; MUST NOT self-authorize) | V2 confirms/overrides continuation |
| Process recreation + no context | `WaitForV2ReplayDecision` | V2 decides replay, resume, or fresh start |
| Transport reconnect | `WaitForV2ReplayDecision` (with `durableSessionId`) | V2 decides whether to replay |
| Receiver/pipeline rebind | `ResumeLocalExecution` | V2 not consulted; session is intact |
| Fresh attach | `NoRecoveryContext` | Not applicable |
| Duplicate recovery attempt | `SuppressDuplicateLocalRecovery` | Dropped before pipeline entry |

---

### 5. Compatibility and Legacy Suppression (`compatibility_suppression`)

Evidence that compat/legacy influence paths are classified, blocked, or quarantined and
do not corrupt canonical runtime state.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `compat_legacy_blocking_participant_canonical_path_confirmation` | **CANONICAL** | `AndroidCompatLegacyBlockingParticipant` | `Pr8AndroidCompatLegacyBlockingTest` | `CompatLegacyBlockingDecision.semanticTag` in `ReconciliationSignal` |
| `compatibility_surface_retirement_registry` | **CANONICAL** | `CompatibilitySurfaceRetirementRegistry` | `Pr10CompatibilitySurfaceRetirementTest` | Registry queryable by V2 governance tooling; retired surface IDs cross-referenced |
| `authoritative_path_alignment_audit` | **CANONICAL** | `AndroidAuthoritativePathAlignmentAudit` | `Pr65AndroidAuthoritativePathAlignmentTest` | Audit entries exported via `ReconciliationSignal` compat-state payload → V2 PR-3 gate |
| `long_tail_compat_registry_legacy_signals` | ~~DEPRECATED_COMPAT~~ | `LongTailCompatibilityRegistry` | `Pr35LongTailCompatHandlingTest` | **NOT forwarded as canonical evidence**; V2 audit may reference for suppression confirmation |
| `compatibility_retirement_fence_blocking` | **CANONICAL** | `CompatibilityRetirementFence` | `Pr36CompatRetirementHardeningTest` | Fence block outcomes observable via `ReconciliationSignal` compat-state fields |

⚠️ **Important**: `long_tail_compat_registry_legacy_signals` is marked **DEPRECATED_COMPAT**
and **must not** count toward release-gate readiness.  It is listed for traceability only.

**Influence classification model** (`AndroidCompatLegacyBlockingParticipant`):

| Influence class | Meaning | Release-gate implication |
|-----------------|---------|--------------------------|
| `CANONICAL_RUNTIME_PATH_CONFIRMED` | Path confirmed canonical | ✅ Evidence contributes to readiness |
| `ALLOW_FOR_OBSERVATION_ONLY` | Legacy path; observation only | ℹ️ Advisory only |
| `BLOCK_DUE_TO_LEGACY_RUNTIME_PATH` | Non-canonical path; block | ❌ Blocks if unresolved |
| `BLOCK_DUE_TO_COMPAT_CONTRACT_INFLUENCE` | Legacy contract influence; suppress emit | ❌ Blocks if unresolved |
| `QUARANTINE_DUE_TO_AMBIGUOUS_LEGACY_STATE` | Ambiguous state; quarantine | ❌ Blocks if unresolved |

---

### 6. Signal Replay and Duplicate Suppression Safety (`signal_replay_duplicate_safety`)

Evidence that duplicate or stale signals are suppressed and the emission ledger correctly
bounds replay.

| Evidence ID | Confidence | Produced By | Test Evidence | V2 Consumption |
|-------------|------------|-------------|---------------|----------------|
| `emitted_signal_ledger_terminal_bounding` | **CANONICAL** | `EmittedSignalLedger` | `Pr66ContinuityRecoveryDurabilityTest` | Bounded signals silently dropped; V2 never receives stale ACK/PROGRESS after terminal RESULT |
| `continuity_integration_duplicate_signal_suppression` | **CANONICAL** | `AndroidContinuityIntegration` | `Pr66ContinuityRecoveryDurabilityTest` | Suppressed duplicates never enter WebSocket send path; V2 receives each signalId at most once |
| `offline_queue_stale_session_discard` | **CANONICAL** | `OfflineTaskQueue` | `Pr66ContinuityRecoveryDurabilityTest` | Discarded messages never reach V2; drain delivers only current-era results |
| `delegated_execution_signal_idempotency_guard` | **CANONICAL** | `DelegatedExecutionSignal`, `EmittedSignalLedger` | `DelegatedExecutionSignalIdempotencyTest` | V2 deduplicates by `signalId`; Android-side `signalId` is the primary deduplication key |

---

## Deferred Items

The following Android-side readiness evidence items are explicitly deferred to later PRs.
Reviewers should not be surprised by these gaps — they are documented with rationale.

| Item ID | Dimension | Summary | Deferred To |
|---------|-----------|---------|-------------|
| `takeover_session_authority_bounding` | `takeover_execution` | Dedicated session-authority bounding for takeover recovery (currently relies on general compat gate) | V2 PR-2 companion, once V2 takeover authority surface is stable |
| `emit_ledger_cross_process_persistence` | `signal_replay_duplicate_safety` | `EmittedSignalLedger` is in-memory only; replay bounding limited to single process lifetime | Post-release hardening after `DelegatedFlowContinuityStore` schema stabilises |
| `reconciliation_signal_epoch_bounding_after_reconnect` | `artifact_emission_reconciliation` | Late reconciliation signals from a prior epoch are not yet epoch-gated | V2 PR-5, once epoch-stamped reconciliation signals are in the wire contract |
| `instrumented_e2e_readiness_evidence_test` | `artifact_emission_reconciliation` | Full end-to-end instrumented test (real service + test-double V2 WebSocket) | Later integration testing PR once V2 test-double infrastructure is available |
| `final_release_policy_in_android` | `runtime_lifecycle` | Android does not implement final release policy — by design; V2 is the canonical orchestration authority | V2-side governance PRs (V2 PR-4 through V2 PR-6) |

---

## How V2 Governance/Release Gates Can Consume This Evidence

### Structured artifacts (primary consumption path)

```
DelegatedRuntimeReadinessEvaluator.evaluateReadiness(...)
  → DeviceReadinessArtifact (semanticTag, dimension, gapReason, snapshotId)
  → RuntimeController.reconciliationSignals (SharedFlow)
  → GalaxyConnectionService → WebSocket → V2
  → V2 PR-9 release gate parses DeviceReadinessArtifact.semanticTag

DelegatedRuntimeAcceptanceEvaluator.evaluateAcceptance(...)
  → DeviceAcceptanceArtifact (semanticTag, dimension, gapReason, snapshotId)
  → ReconciliationSignal Kind.PARTICIPANT_STATE
  → V2 PR-10 graduation gate parses DeviceAcceptanceArtifact.semanticTag
```

### Contract objects (audit/tooling consumption path)

```kotlin
// Query all CANONICAL evidence for a dimension
AndroidReadinessEvidenceSurface.evidenceForDimension(
    AndroidReadinessEvidenceSurface.ReadinessDimension.CONTINUITY_RECOVERY_SAFETY
).filter { it.confidenceLevel == AndroidReadinessEvidenceSurface.ConfidenceLevel.CANONICAL }

// Query continuity contract coverage
ContinuityRecoveryDurabilityContract.coveredBehaviorFor("process_recreation_with_prior_context")
ContinuityRecoveryDurabilityContract.boundedEmissionFor("ack_after_terminal_result")

// Query deferred items
AndroidReadinessEvidenceSurface.deferredItemFor("takeover_session_authority_bounding")
```

### Test index by readiness dimension

| Dimension | Primary test class(es) |
|-----------|------------------------|
| Runtime lifecycle | `Pr9DelegatedRuntimeReadinessTest`, `Pr10DelegatedRuntimeAcceptanceTest`, `Pr11DelegatedRuntimePostGraduationGovernanceTest`, `Pr37AndroidRuntimeLifecycleHardeningTest` |
| Takeover execution | `Pr03TakeoverMetadataUnificationTest`, `Pr8AndroidCompatLegacyBlockingTest` |
| Artifact emission / reconciliation | `Pr4AndroidEvaluatorArtifactEmissionTest`, `Pr51AndroidParticipantRuntimeTruthTest`, `Pr52ReconciliationSignalEmissionTest`, `Pr64UnifiedTruthReconciliationTest` |
| Continuity / recovery | `Pr66ContinuityRecoveryDurabilityTest`, `Pr4AndroidRecoveryParticipationOwnerTest`, `Pr53AndroidLifecycleRecoveryHybridHardeningTest` |
| Compatibility suppression | `Pr8AndroidCompatLegacyBlockingTest`, `Pr10CompatibilitySurfaceRetirementTest`, `Pr65AndroidAuthoritativePathAlignmentTest`, `Pr36CompatRetirementHardeningTest` |
| Signal replay / duplicate safety | `Pr66ContinuityRecoveryDurabilityTest`, `DelegatedExecutionSignalIdempotencyTest`, `EmittedSignalLedgerTest` |

---

## V2 Authority Boundary

Android is a **participant**, not a release orchestrator.  This document and
`AndroidReadinessEvidenceSurface` provide the Android-side evidence; V2 remains
authoritative for:

- Final release gate decisions
- Canonical truth convergence and participant state adjudication
- Session/task resumption and re-dispatch
- Graduation and governance policy

V2-side release and governance gating work that consumes this evidence should be
implemented in the V2 repository (see V2 PR-4 through V2 PR-6), not in this Android
repository.

---

## Machine-Readable Evidence Object

The canonical machine-readable version of this document is
[`AndroidReadinessEvidenceSurface.kt`](../app/src/main/java/com/ufo/galaxy/runtime/AndroidReadinessEvidenceSurface.kt).

The accompanying test suite is
[`Pr67AndroidReadinessEvidenceSurfaceTest.kt`](../app/src/test/java/com/ufo/galaxy/runtime/Pr67AndroidReadinessEvidenceSurfaceTest.kt).

All acceptance criteria from this PR (AC1–AC5) are validated by that test suite.
