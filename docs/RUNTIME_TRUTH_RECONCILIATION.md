# Android Runtime Truth and V2 Reconciliation

> **PR-51**
> Introduced in: `ufo-galaxy-android`
> Audience: V2 orchestration engineers, Android platform engineers, distributed runtime reviewers

---

## Overview

This document provides the canonical reference for:

1. **What Android reports as local runtime truth** — the specific state fields Android owns and publishes
2. **How Android participant/session/runtime state maps to V2 expectations** — field-by-field alignment
3. **Cancel/status/failure/result protocol semantics** — structured signals and reconciliation protocol
4. **Android local truth vs V2 canonical truth** — explicit responsibility boundaries

---

## Responsibility Boundaries

### Android owns locally

Android is the **participant-side** runtime owner. It exclusively owns the following truths:

| Truth Domain | Android-owned fields | Surface |
|---|---|---|
| **Participant identity** | `participantId`, `deviceId`, `hostId`, `deviceRole`, `formationRole` | `RuntimeHostDescriptor`, `CanonicalParticipantModel` |
| **Participation state** | `participationState` (ACTIVE / STANDBY / DRAINING / INACTIVE) | `RuntimeHostDescriptor.HostParticipationState` |
| **Session attachment** | `sessionId`, `sessionState`, `delegatedExecutionCount`, `isReuseValid` | `AttachedRuntimeSession`, `AttachedRuntimeHostSessionSnapshot` |
| **Runtime posture** | `source_runtime_posture` (JOIN_RUNTIME / CONTROL_ONLY) | `SourceRuntimePosture` |
| **Health state** | `healthState` (HEALTHY / DEGRADED / RECOVERING / FAILED / UNKNOWN) | `ParticipantHealthState` |
| **Readiness** | `readinessState` (READY / READY_WITH_FALLBACK / NOT_READY / UNKNOWN) | `ParticipantReadinessState` |
| **Active task status** | `activeTaskId`, `activeTaskStatus` (RUNNING / PENDING / CANCELLING / FAILING) | `AndroidParticipantRuntimeTruth.ActiveTaskStatus` |
| **Task outcomes** | cancel, status, failure, result signals | `AndroidSessionContribution`, `ReconciliationSignal` |
| **Runtime lifecycle transitions** | connect, reconnect, disconnect, degraded, health changed | `V2MultiDeviceLifecycleEvent` |

### V2 owns canonically

V2 is the **center-side orchestration authority**. It owns:

- Global session truth (which participants are in a session, which tasks are assigned to whom)
- Task assignment and dispatch decisions
- Barrier / merge / completion tracking across multiple participants
- Canonical reconciliation of Android-reported truth against its own participant registry
- Cross-participant coordination (formation, role rebalancing, fallback/recovery decisions)

### Reconciliation flow

```
Android                                    V2
  │                                         │
  │── AndroidParticipantRuntimeTruth ──────►│ (full participant truth snapshot)
  │                                         │── reconcile against participant registry
  │                                         │
  │── ReconciliationSignal.TASK_ACCEPTED ──►│ (task in-progress)
  │                                         │── mark task active for this participant
  │                                         │
  │── ReconciliationSignal.TASK_CANCELLED ─►│ (task stopped by cancel request)
  │                                         │── close task as cancelled; update canonical
  │                                         │
  │── ReconciliationSignal.TASK_FAILED ────►│ (task failed)
  │                                         │── close task as failed; trigger fallback if needed
  │                                         │
  │── ReconciliationSignal.TASK_RESULT ────►│ (task completed successfully)
  │                                         │── close task as success; update session truth
  │                                         │
  │── V2MultiDeviceLifecycleEvent ─────────►│ (participant health/connectivity events)
  │                                         │── update device health; trigger mesh session lifecycle
```

---

## Structured Android Runtime State Report

### `AndroidParticipantRuntimeTruth`

The canonical consolidated snapshot for V2 reconciliation. Published via
`ReconciliationSignal.RUNTIME_TRUTH_SNAPSHOT` and available as a point-in-time
map via `toMap()`.

| Field | Wire key | Type | Description |
|---|---|---|---|
| `participantId` | `participant_id` | String | Stable `deviceId:hostId` participant identity |
| `deviceId` | `device_id` | String | Hardware device identifier |
| `hostId` | `host_id` | String | Per-process runtime host instance UUID |
| `deviceRole` | `device_role` | String | Logical device role ("phone", "tablet") |
| `participationState` | `participation_state` | String | ACTIVE / STANDBY / DRAINING / INACTIVE |
| `coordinationRole` | `coordination_role` | String | "coordinator" or "participant" |
| `sourceRuntimePosture` | `source_runtime_posture` | String | "join_runtime" or "control_only" |
| `sessionId` | `session_id` | String? | Current attached session UUID; absent if not attached |
| `sessionState` | `session_state` | String? | "attached" / "detaching" / "detached"; absent if no session |
| `delegatedExecutionCount` | `delegated_execution_count` | Int | Tasks accepted under current session |
| `healthState` | `health_state` | String | "healthy" / "degraded" / "recovering" / "failed" / "unknown" |
| `readinessState` | `readiness_state` | String | "ready" / "ready_with_fallback" / "not_ready" / "unknown" |
| `activeTaskId` | `active_task_id` | String? | In-flight task ID; absent if idle |
| `activeTaskStatus` | `active_task_status` | String? | "running" / "pending" / "cancelling" / "failing"; absent if idle |
| `reportedAtMs` | `reported_at_ms` | Long | Epoch-ms snapshot creation timestamp |
| `reconciliationEpoch` | `reconciliation_epoch` | Int | Monotonically increasing epoch for staleness detection |
| `isFullyReconcilable` | `is_fully_reconcilable` | Boolean | Pre-computed reconcilability flag |

#### `isFullyReconcilable` semantics

A snapshot is **fully reconcilable** when:
- `participantId` is non-blank
- `participationState` is not INACTIVE
- `healthState` is not UNKNOWN
- `readinessState` is not UNKNOWN

V2 should accept non-reconcilable snapshots as **partial truth** and wait for a subsequent
fully-reconcilable snapshot or a `V2MultiDeviceLifecycleEvent` to complete the picture.

---

## Cancel / Status / Failure / Result Protocol

### `ReconciliationSignal`

Structured wrapper for all Android→V2 signals requiring canonical reconciliation.

#### Signal taxonomy

| `Kind` | Wire value | Phase | V2 action |
|---|---|---|---|
| `TASK_ACCEPTED` | `task_accepted` | Pre-execution | Mark task as in-progress under this participant |
| `TASK_STATUS_UPDATE` | `task_status_update` | In-progress | Update progress tracking; do not close task |
| `TASK_RESULT` | `task_result` | Terminal | Close task as success; update session truth |
| `TASK_CANCELLED` | `task_cancelled` | Terminal | Close task as cancelled; release execution capacity |
| `TASK_FAILED` | `task_failed` | Terminal | Close task as failed; trigger fallback if policy requires |
| `PARTICIPANT_STATE` | `participant_state` | Any time | Update canonical participant view immediately |
| `RUNTIME_TRUTH_SNAPSHOT` | `runtime_truth_snapshot` | Any time | Full reconciliation pass against `AndroidParticipantRuntimeTruth` |

#### `TASK_CANCELLED` semantics

`TASK_CANCELLED` is emitted **as soon as Android determines a task will be cancelled**,
before the full termination sequence completes. V2 must treat this signal as authoritative:
the task is being stopped. A subsequent `AndroidSessionContribution.Kind.CANCELLATION` closes
the terminal session-truth record.

**V2 must not re-dispatch a cancelled task to Android until it receives confirmation that
the cancel is complete** (i.e. the terminal `AndroidSessionContribution`).

#### `TASK_FAILED` semantics

`TASK_FAILED` is emitted as soon as Android encounters a failure condition, before execution
fully terminates. V2 should begin fallback/retry evaluation on receipt of this signal rather
than waiting for the terminal contribution.

The signal's `payload["error_detail"]` carries a human-readable error description when available.

#### `TASK_RESULT` vs `AndroidSessionContribution.Kind.FINAL_COMPLETION`

`ReconciliationSignal.TASK_RESULT` is the **pre-terminal** signal indicating Android has
completed execution successfully. `AndroidSessionContribution.Kind.FINAL_COMPLETION` is the
**terminal** session-truth contribution that closes the V2 session-truth record.

V2 may process `TASK_RESULT` for early pipeline advancement while awaiting the
`FINAL_COMPLETION` contribution for canonical session-truth closure.

---

## V2 Participant State Alignment

### Participant state → V2 hook mapping

| Android state | V2 expectation |
|---|---|
| `participationState = ACTIVE` + `healthState = HEALTHY` | Participant eligible for task dispatch |
| `participationState = STANDBY` | Participant temporarily not accepting tasks; no new dispatch |
| `participationState = DRAINING` | In-flight tasks may complete; no new dispatch |
| `participationState = INACTIVE` | Participant absent from formation; treat as unavailable |
| `healthState = DEGRADED` | Dispatch at reduced priority; prefer other participants |
| `healthState = RECOVERING` | Do not dispatch new tasks; await HEALTHY |
| `healthState = FAILED` | Mark participant unavailable; trigger formation rebalance |
| `sessionState = ATTACHED` + `participationState = ACTIVE` | Valid delegated dispatch target |
| `sessionState = DETACHED` | Session terminated; do not dispatch until new session announced |
| `sourceRuntimePosture = JOIN_RUNTIME` | Android is a runtime executor for the current task |
| `sourceRuntimePosture = CONTROL_ONLY` | Android is initiator/controller only; no task allocation |

### Formation role alignment

| Android `FormationRole` | V2 interpretation |
|---|---|
| `PRIMARY` | Lead execution surface; may receive primary task assignments |
| `SECONDARY` | Auxiliary surface; receives overflow or parallel subtasks from PRIMARY |
| `SATELLITE` | Lightweight participant; receives task fragments but not full tasks |

---

## Protocol Safety Rules

1. **Android never re-orders cancel and result signals.** A `TASK_CANCELLED` signal for a given
   `taskId` means the task is being stopped. No `TASK_RESULT` will follow for that `taskId`.

2. **Signal `signalId` fields are stable and unique.** V2 may use `signalId` for deduplication
   when a signal is retried over a lossy transport.

3. **`reconciliationEpoch` is monotonically increasing.** V2 should discard a snapshot with a
   lower epoch than the most recently received snapshot for the same `participantId`.

4. **Android does not modify V2 canonical state directly.** All state changes on V2's side
   are V2's decision based on Android's signals. Android owns its local truth; V2 owns the
   canonical orchestration truth.

5. **`RUNTIME_TRUTH_SNAPSHOT` signals are authoritative over V2's cached state.** When V2
   receives a `RUNTIME_TRUTH_SNAPSHOT`, it must resolve any conflicts in favour of the snapshot.

---

## Related Surfaces

| Surface | Purpose | PR |
|---|---|---|
| `AndroidParticipantRuntimeTruth` | Consolidated participant truth snapshot | PR-51 |
| `ReconciliationSignal` | Structured Android→V2 signal wrapper | PR-51 |
| `ActiveTaskStatus` | In-flight task status enum | PR-51 |
| `AndroidSessionContribution` | Terminal task result/cancellation envelope | PR-4 |
| `StagedMeshParticipationResult` | Staged-mesh target execution result | PR-32 |
| `CanonicalParticipantModel` | Participant read-model projection | PR-6 |
| `AttachedRuntimeHostSessionSnapshot` | Session truth snapshot | PR-19 |
| `V2MultiDeviceLifecycleEvent` | V2-consumable device lifecycle events | PR-43 / PR-44 |
| `RuntimeTruthPrecedenceRules` | Three-tier truth precedence model (internal) | PR-39 |
| `AndroidContractFinalizer` | Contract responsibility boundaries | PR-41 |
| `RuntimeInvariantEnforcer` | Internal invariant enforcement | PR-42 |

---

## What is Fully Wired vs Contract-First

### Fully wired and evidence-backed

- ✅ Android participant identity (`participantId = deviceId:hostId`) — stable across PRs
- ✅ Session attachment lifecycle (ATTACHED → DETACHING → DETACHED) — tested in `RuntimeControllerAttachedSessionTest`
- ✅ Cancel signal propagation via `AndroidSessionContribution.Kind.CANCELLATION` — tested in `Pr32StagedMeshTargetExecutionTest`
- ✅ Failure signal via `AndroidSessionContribution.Kind.FAILURE` — tested across multiple PR test suites
- ✅ Staged-mesh execution target and result (PR-32) — full test coverage in `Pr32StagedMeshTargetExecutionTest`
- ✅ V2 lifecycle event stream (`RuntimeController.v2LifecycleEvents`) — tested in `Pr43V2MultiDeviceLifecycleIntegrationTest`
- ✅ Mesh session lifecycle hints (PR-44) — tested in `Pr44MeshSessionLifecycleMappingTest`
- ✅ Consolidated participant truth snapshot (`AndroidParticipantRuntimeTruth`) — tested in `Pr51AndroidParticipantRuntimeTruthTest`
- ✅ Structured reconciliation signal (`ReconciliationSignal`) — tested in `Pr51AndroidParticipantRuntimeTruthTest`

### Contract-first / partially wired

- ⚠️ `RELAY` / `FORWARD` / `REPLY` message types — minimal-compat stubs (logged, no routing logic)
- ⚠️ `HYBRID_EXECUTE` — payload parsed; degrade reply sent; full hybrid executor not implemented
- ⚠️ `RAG_QUERY` — logged; empty result returned; full RAG pipeline not implemented
- ⚠️ `CODE_EXECUTE` — logged; error result returned; sandbox not implemented

### What Android does NOT own

- ❌ Global session truth (which participants are assigned to which tasks)
- ❌ Barrier / merge / completion tracking (V2-side concern)
- ❌ Formation rebalance decisions (V2 decides; Android reports health/readiness)
- ❌ Task retry/fallback policy (V2 policy; Android only signals failure)
