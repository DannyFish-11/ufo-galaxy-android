# Android-Side Signal Closure Validation

This document is the Android-side counterpart to the V2 signal closure review.
It records which cross-repo signal chains are validated from the Android participant
side, which send/receipt/execution paths were covered, and what remains open for
later PRs.

---

## System Context

The combined `ufo-galaxy-android` + `ufo-galaxy-realization-v2` system is a
**central distributed intelligent system** where:

- **V2** is the canonical orchestration / truth convergence / audit / governance
  center.
- **Android** is a distributed execution participant with its own local runtime,
  lifecycle authority, readiness evaluator, and offline queue.

For the system to be end-to-end closure-capable, Android must emit and receive the
right signals from the real runtime paths, not only from standalone model code.

---

## Signal Chain Validation Matrix

### Chain 1 — ReconciliationSignal

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.RECONCILIATION_SIGNAL` wire value stable | ✅ Proven | `AipModels.kt` enum entry; test in `CrossRepoSignalClosureValidationTest` |
| `ReconciliationSignalPayload` serialises with all required fields | ✅ Proven | `Pr06ReconciliationSignalOutboundTransportTest`; `CrossRepoSignalClosureValidationTest` |
| Kind TASK_RESULT reachable from `ReconciliationSignal.taskResult()` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| Kind TASK_ACCEPTED reachable from delegated lifecycle path | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| Kind TASK_CANCELLED / TASK_FAILED | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| Kind PARTICIPANT_STATE with health + readiness payload fields | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| Kind RUNTIME_TRUTH_SNAPSHOT with non-null `runtimeTruth` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `GalaxyConnectionService` collects `RuntimeController.reconciliationSignals` and calls `sendReconciliationSignal()` | ✅ Wired | `GalaxyConnectionService.kt` `onStartCommand` coroutine (PR-06) |
| AipMessage envelope with `type = "reconciliation_signal"` round-trips through Gson | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| Full integration test (inject signal into real service, confirm wire send) | 🔲 Open | Requires Robolectric / instrumented test environment |

**Send path**: `RuntimeController.reconciliationSignals` (SharedFlow) →
`GalaxyConnectionService.onStartCommand` collector →
`GalaxyConnectionService.sendReconciliationSignal()` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 2 — HandoffEnvelopeV2 Round-Trip

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.HANDOFF_ENVELOPE_V2` wire value stable | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `MsgType.HANDOFF_ENVELOPE_V2_RESULT` wire value stable | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `HandoffEnvelopeV2` Gson-parses `handoff_id`, `task_id`, `goal` from representative JSON | ✅ Proven | `HandoffEnvelopeV2ConsumptionTest`; `CrossRepoSignalClosureValidationTest` |
| `HandoffEnvelopeV2ResultPayload.STATUS_ACK / STATUS_RESULT / STATUS_FAILURE` are distinct | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| ACK result envelope serialises with correct fields | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| RESULT envelope carries `result_summary` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| FAILURE envelope carries `error` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| All three status variants produce AipMessage envelopes with correct type string | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `GalaxyConnectionService.handleHandoffEnvelopeV2()` routes inbound envelope to execution | ✅ Wired | `GalaxyConnectionService.kt` `onHandoffEnvelopeV2` dispatch |
| Full integration test (fake WebSocket → handleHandoffEnvelopeV2 → RESULT sent) | 🔲 Open | Requires Robolectric / instrumented test environment |

**Receipt path**: `GalaxyWebSocketClient.onHandoffEnvelopeV2()` →
`GalaxyConnectionService.handleHandoffEnvelopeV2()` → execution pipeline →
`GalaxyConnectionService.sendHandoffEnvelopeV2Result()` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 3 — Delegated Execution Full Loop

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.DELEGATED_EXECUTION_SIGNAL` wire value stable | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DelegatedExecutionSignal.Kind` ACK / PROGRESS / RESULT all reachable | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DelegatedExecutionSignal.ResultKind` COMPLETED / FAILED / TIMEOUT / CANCELLED all present | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DelegatedRuntimeReceiver` session gate rejects null-session inputs with `NO_ATTACHED_SESSION` | ✅ Proven | `DelegatedRuntimeReceiverTest`; `CrossRepoSignalClosureValidationTest` |
| `DelegatedTakeoverExecutor` emits ACK then PROGRESS then RESULT (COMPLETED) on success | ✅ Proven | `DelegatedTakeoverExecutorTest`; `CrossRepoSignalClosureValidationTest` |
| `DelegatedTakeoverExecutor` emits RESULT (FAILED) on pipeline exception | ✅ Proven | `DelegatedTakeoverExecutorTest`; `CrossRepoSignalClosureValidationTest` |
| ACK signal wraps to AipMessage envelope with correct type string | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `EmittedSignalLedger` records ACK / PROGRESS / RESULT for replay | ✅ Wired | `DelegatedTakeoverExecutor.execute()` ledger creation |
| `DelegatedExecutionSignalSink` → `GalaxyConnectionService.sendDelegatedExecutionSignal()` | ✅ Wired | `GalaxyConnectionService.kt` `delegatedSignalSink` initialiser |
| Full integration test covering TIMEOUT and CANCELLED result kinds end-to-end | 🔲 Open | Requires task-cancellation injection in Robolectric / instrumented test |

**Loop path**: `GalaxyConnectionService.handleTakeoverRequest()` →
`TakeoverEligibilityAssessor.assess()` →
`DelegatedRuntimeReceiver.receive()` →
`DelegatedTakeoverExecutor.execute()` →
`DelegatedExecutionSignalSink` callbacks →
`GalaxyConnectionService.sendDelegatedExecutionSignal()` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 4 — Evaluator / Runtime Artifact Visibility toward V2

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.DEVICE_READINESS_REPORT` wire value stable | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DeviceReadinessReportPayload` serialises with all required fields | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DelegatedRuntimeReadinessEvaluator.buildSnapshot()` returns non-blank `snapshotId` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| All-UNKNOWN evaluator produces `ARTIFACT_DEVICE_READINESS_UNKNOWN_DUE_TO_MISSING_SIGNAL` | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| All-READY evaluator produces `ARTIFACT_DEVICE_READY_FOR_RELEASE` | ✅ Proven | `CrossRepoSignalClosureValidationTest`; `Pr9DelegatedRuntimeReadinessTest` |
| `DeviceReadinessReportPayload.dimension_states` map contains all five dimension wire values | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `DeviceReadinessReportPayload.missing_dimensions` lists all five when evaluator has no signals | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| AipMessage envelope wrapping payload round-trips through Gson | ✅ Proven | `CrossRepoSignalClosureValidationTest` |
| `GalaxyConnectionService` instantiates `DelegatedRuntimeReadinessEvaluator` | ✅ Wired | `GalaxyConnectionService.kt` `delegatedRuntimeReadinessEvaluator` field |
| `GalaxyConnectionService.sendDeviceReadinessReport()` builds snapshot and sends uplink | ✅ Wired | `GalaxyConnectionService.kt` `sendDeviceReadinessReport()` method |
| Initial readiness report emitted after service start | ✅ Wired | `GalaxyConnectionService.onStartCommand` launch block |
| Dimension-state population from real runtime events (owners calling `markDimensionReady` / `markDimensionGap`) | 🔲 Open | `AndroidRecoveryParticipationOwner` et al. need to feed the evaluator |
| Follow-up reports after dimension-state changes | 🔲 Open | Requires reactive dimension-change hook in evaluator / owners |

**Emission path**: `GalaxyConnectionService.onStartCommand` →
`sendDeviceReadinessReport()` →
`DelegatedRuntimeReadinessEvaluator.buildSnapshot()` →
`DeviceReadinessReportPayload` →
`AipMessage(DEVICE_READINESS_REPORT, ...)` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 5 — Governance Evaluator Artifact Visibility toward V2 (PR-4 Android)

**Emission semantics**: **Canonical participant evidence** — V2 post-graduation governance /
enforcement paths must treat this artifact as the authoritative Android-side compliance signal.

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.DEVICE_GOVERNANCE_REPORT` wire value is `"device_governance_report"` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DeviceGovernanceReportPayload` serialises with `artifact_tag`, `snapshot_id`, `device_id`, `dimension_states` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DelegatedRuntimePostGraduationGovernanceEvaluator.buildSnapshot()` returns non-blank `snapshotId` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-UNKNOWN evaluator produces `device_governance_unknown_due_to_missing_signal` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-COMPLIANT evaluator produces `device_governance_compliant` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| REGRESSION snapshot produces a violation artifact tag | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| AipMessage envelope with `DEVICE_GOVERNANCE_REPORT` type round-trips through Gson | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `GalaxyConnectionService` instantiates `DelegatedRuntimePostGraduationGovernanceEvaluator` | ✅ Wired | `GalaxyConnectionService.kt` `delegatedRuntimeGovernanceEvaluator` field |
| `GalaxyConnectionService.sendDeviceGovernanceReport()` builds snapshot and sends uplink | ✅ Wired | `GalaxyConnectionService.kt` `sendDeviceGovernanceReport()` method |
| Initial governance report emitted after service start | ✅ Wired | `GalaxyConnectionService.onStartCommand` launch block |
| `EvaluatorArtifactEmissionSemantics.REGISTRY` classifies `DEVICE_GOVERNANCE_REPORT` as `CANONICAL_PARTICIPANT_EVIDENCE` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| Governance dimension-state population from real post-graduation lifecycle callbacks | 🔲 Deferred | Follow-up PR should connect observation signals from `AndroidLocalTruthOwnershipCoordinator`, `AndroidFlowAwareResultConvergenceParticipant`, etc. |
| Follow-up governance reports after dimension-state changes | 🔲 Deferred | Reactive dimension-change hook in evaluator / owners needed |

**Emission path**: `GalaxyConnectionService.onStartCommand` →
`sendDeviceGovernanceReport()` →
`DelegatedRuntimePostGraduationGovernanceEvaluator.buildSnapshot()` →
`DeviceGovernanceReportPayload` →
`AipMessage(DEVICE_GOVERNANCE_REPORT, ...)` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 6 — Acceptance Evaluator Artifact Visibility toward V2 (PR-4 Android)

**Emission semantics**: **Canonical participant evidence** — V2 graduation gate paths must
treat this artifact as the authoritative Android-side acceptance verdict.

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.DEVICE_ACCEPTANCE_REPORT` wire value is `"device_acceptance_report"` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DeviceAcceptanceReportPayload` serialises with `artifact_tag`, `snapshot_id`, `device_id`, `dimension_states` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DelegatedRuntimeAcceptanceEvaluator.buildSnapshot()` returns non-blank `snapshotId` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-UNKNOWN evaluator produces `device_acceptance_unknown_due_to_incomplete_signal` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-EVIDENCED evaluator produces `device_accepted_for_graduation` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| GAP snapshot produces a rejected artifact tag | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| AipMessage envelope with `DEVICE_ACCEPTANCE_REPORT` type round-trips through Gson | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `GalaxyConnectionService` instantiates `DelegatedRuntimeAcceptanceEvaluator` | ✅ Wired | `GalaxyConnectionService.kt` `delegatedRuntimeAcceptanceEvaluator` field |
| `GalaxyConnectionService.sendDeviceAcceptanceReport()` builds snapshot and sends uplink | ✅ Wired | `GalaxyConnectionService.kt` `sendDeviceAcceptanceReport()` method |
| Initial acceptance report emitted after service start | ✅ Wired | `GalaxyConnectionService.onStartCommand` launch block |
| `EvaluatorArtifactEmissionSemantics.REGISTRY` classifies `DEVICE_ACCEPTANCE_REPORT` as `CANONICAL_PARTICIPANT_EVIDENCE` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| Evidence dimension population from readiness prerequisite wiring and per-dimension callbacks | 🔲 Deferred | Follow-up PR should wire evidence evidence signals from `DelegatedRuntimeReadinessEvaluator`, truth/result/event/compat/continuity owners |
| Follow-up acceptance reports after dimension-state changes | 🔲 Deferred | Reactive evidence-change hook needed |

**Emission path**: `GalaxyConnectionService.onStartCommand` →
`sendDeviceAcceptanceReport()` →
`DelegatedRuntimeAcceptanceEvaluator.buildSnapshot()` →
`DeviceAcceptanceReportPayload` →
`AipMessage(DEVICE_ACCEPTANCE_REPORT, ...)` →
`GalaxyWebSocketClient.sendJson()`.

---

### Chain 7 — Strategy Evaluator Artifact Visibility toward V2 (PR-4 Android)

**Emission semantics**: **Advisory / observation-only** — V2 retains full authority over
program strategy and evolution control decisions.  V2 should treat this artifact as
informational input and must not gate on it without an explicit policy decision.

| Item | Status | Evidence |
|------|--------|----------|
| `MsgType.DEVICE_STRATEGY_REPORT` wire value is `"device_strategy_report"` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DeviceStrategyReportPayload` serialises with `artifact_tag`, `snapshot_id`, `device_id`, `dimension_states` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `DelegatedRuntimeStrategyEvaluator.buildSnapshot()` returns non-blank `snapshotId` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-UNKNOWN evaluator produces `device_strategy_unknown_due_to_missing_program_signal` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| All-ON_TRACK evaluator produces `device_strategy_on_track` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| AT_RISK snapshot produces a risk artifact tag | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| AipMessage envelope with `DEVICE_STRATEGY_REPORT` type round-trips through Gson | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| `GalaxyConnectionService` instantiates `DelegatedRuntimeStrategyEvaluator` | ✅ Wired | `GalaxyConnectionService.kt` `delegatedRuntimeStrategyEvaluator` field |
| `GalaxyConnectionService.sendDeviceStrategyReport()` builds snapshot and sends uplink | ✅ Wired | `GalaxyConnectionService.kt` `sendDeviceStrategyReport()` method |
| Initial strategy report emitted after service start | ✅ Wired | `GalaxyConnectionService.onStartCommand` launch block |
| `EvaluatorArtifactEmissionSemantics.REGISTRY` classifies `DEVICE_STRATEGY_REPORT` as `ADVISORY_OBSERVATION_ONLY` | ✅ Proven | `Pr66EvaluatorArtifactEmissionTest` |
| Strategy dimension population from real program-level signals | 🔲 Deferred | Current emission always carries all-UNKNOWN posture (advisory — no strategy risk detected). Real program signal population deferred to a follow-up PR. |
| Follow-up strategy reports after dimension-state changes | 🔲 Deferred | Reactive posture-change hook needed |

**Emission path**: `GalaxyConnectionService.onStartCommand` →
`sendDeviceStrategyReport()` →
`DelegatedRuntimeStrategyEvaluator.buildSnapshot()` →
`DeviceStrategyReportPayload` →
`AipMessage(DEVICE_STRATEGY_REPORT, ...)` →
`GalaxyWebSocketClient.sendJson()`.

---

## PR-4 Android Evaluator Artifact Semantic Classification

This table provides the canonical V2-side classification reference for all Android evaluator
artifacts established by Android PR-4. See `EvaluatorArtifactEmissionSemantics.kt` for the
machine-readable source of truth.

| Wire type | Evaluator | Emission class | V2 action |
|-----------|-----------|---------------|-----------|
| `device_readiness_report` | `DelegatedRuntimeReadinessEvaluator` | **Canonical participant evidence** | V2 readiness gate may proceed only if all dimensions are READY |
| `device_governance_report` | `DelegatedRuntimePostGraduationGovernanceEvaluator` | **Canonical participant evidence** | V2 must escalate / block on REGRESSION artifacts |
| `device_acceptance_report` | `DelegatedRuntimeAcceptanceEvaluator` | **Canonical participant evidence** | V2 graduation gate proceeds only on `device_accepted_for_graduation` |
| `device_strategy_report` | `DelegatedRuntimeStrategyEvaluator` | **Advisory / observation-only** | V2 retains strategy authority; risk signals inform, not gate |
| `reconciliation_signal` | `RuntimeController` (SharedFlow) | **Canonical participant evidence** | V2 must update participant truth per signal kind |

---

## Open Gaps for Later PRs

The following items are confirmed **not covered** by this PR and require follow-up:

| Gap | Priority | Notes |
|-----|----------|-------|
| Dimension-state population from real runtime events (readiness) | 🔴 High | Owners (`AndroidRecoveryParticipationOwner`, `AndroidLocalTruthOwnershipCoordinator`, etc.) must call `markDimensionReady` / `markDimensionGap` on the readiness evaluator. Without this, the readiness report is always UNKNOWN at service start. |
| Follow-up readiness/governance/acceptance/strategy reports after dimension-state changes | 🔴 High | Reactive hook needed so V2 receives an updated artifact whenever dimension state changes during the session. |
| Governance dimension-state population from real post-graduation lifecycle callbacks | 🔴 High | Follow-up PR should connect observation signals from `AndroidLocalTruthOwnershipCoordinator`, `AndroidFlowAwareResultConvergenceParticipant`, and other owners into `DelegatedRuntimePostGraduationGovernanceEvaluator`. |
| Acceptance dimension-state population from real evidence chain | 🔴 High | Follow-up PR should wire evidence signals from readiness-prerequisite, truth/result/event/compat/continuity owners into `DelegatedRuntimeAcceptanceEvaluator`. |
| Strategy dimension-state population from real program-level signals | 🟠 Medium | Current emission always carries all-UNKNOWN (advisory — no risk detected). Real program signals are advisory only; deferred to later PR. |
| Takeover executor full implementation (TakeoverEligibilityAssessor → full takeover flow) | 🔴 High | `AipModels.kt` comment still notes full takeover executor deferred. The delegated execution loop is proven but the full takeover path is not. |
| Integration test: `GalaxyConnectionService` HANDOFF_ENVELOPE_V2 → HANDOFF_ENVELOPE_V2_RESULT | 🟠 Medium | Needs Robolectric or instrumented test to drive the real service with a fake WebSocket. |
| Integration test: `RuntimeController.reconciliationSignals` → wire send | 🟠 Medium | Same Robolectric constraint. The coroutine collector is wired but not proven by a service-level test. |
| Legacy path default-off | 🟠 Medium | Compat gate / legacy path kill switch not yet default-off. `GalaxyConnectionService` still serves legacy routing. |
| Readiness / governance verdict into CI / release pipeline | 🟡 Low | V2-side governance gate not yet connected to CI blocking. Android side produces artifacts; V2 side consumption is not yet CI-gating. |

---

## Coverage Summary

| Signal Chain | Model Present | Wire Type Present | Send Path Wired | Tested (pure-JVM) | Integration Tested |
|---|---|---|---|---|---|
| ReconciliationSignal | ✅ | ✅ | ✅ | ✅ | 🔲 |
| HandoffEnvelopeV2 round-trip | ✅ | ✅ | ✅ | ✅ | 🔲 |
| Delegated execution signal loop | ✅ | ✅ | ✅ | ✅ | 🔲 |
| Device readiness artifact → V2 | ✅ | ✅ | ✅ | ✅ | 🔲 |
| Device governance artifact → V2 | ✅ | ✅ | ✅ | ✅ | 🔲 |
| Device acceptance artifact → V2 | ✅ | ✅ | ✅ | ✅ | 🔲 |
| Device strategy artifact → V2 | ✅ | ✅ | ✅ | ✅ | 🔲 |

All seven chains are model-complete, transport-capable, and proven by pure-JVM tests.
Integration tests remain open (see gaps above).

---

*Generated by the Android-side signal closure PR as part of the cross-repo
end-to-end validation work for the central distributed intelligent system.*
