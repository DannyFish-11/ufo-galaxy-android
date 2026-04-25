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

## Open Gaps for Later PRs

The following items are confirmed **not covered** by this PR and require follow-up:

| Gap | Priority | Notes |
|-----|----------|-------|
| Dimension-state population from real runtime events | 🔴 High | Owners (`AndroidRecoveryParticipationOwner`, `AndroidLocalTruthOwnershipCoordinator`, etc.) must call `markDimensionReady` / `markDimensionGap` on the evaluator. Without this, the readiness report is always UNKNOWN at service start. |
| Follow-up readiness reports after dimension-state changes | 🔴 High | Reactive hook needed so V2 receives an updated readiness artifact whenever dimension state changes during the session. |
| Takeover executor full implementation (TakeoverEligibilityAssessor → full takeover flow) | ✅ Closed | Full takeover executor implemented and validated. `AipModels.kt` status updated to canonical. `TakeoverExecutorClosureTest` proves acceptance/rejection/execution/signal/replay path. See `docs/takeover-executor-closure.md`. |
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

All four chains are model-complete, transport-capable, and proven by pure-JVM tests.
Integration tests remain open (see gaps above).

---

*Generated by the Android-side signal closure PR as part of the cross-repo
end-to-end validation work for the central distributed intelligent system.*
