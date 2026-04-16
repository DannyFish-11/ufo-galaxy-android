# Canonical Task Dispatch and Delegated Execution Chain

**PR-12 (Android) — Cross-Repository Canonical Dispatch Chain Definition**

**Scope:** `DannyFish-11/ufo-galaxy-android` ↔ `DannyFish-11/ufo-galaxy-realization-v2`

This document defines the canonical end-to-end task dispatch and delegated execution chain
across both repositories. It makes the dispatch system explicitly inspectable and governable
without changing any core behavior.

---

## 1. Purpose

The system already contains a meaningful execution chain spanning source dispatch
orchestration, local execution, remote handoff, fallback-local behavior, staged mesh
coordination, Android inbound dispatch, delegated signal ingress, execution tracking,
signal reconciliation, and result handling.

However, these paths were previously understood more through accumulated implementation
knowledge than through a single canonical definition. This document establishes that
canonical definition so that:

- The primary dispatch path is unambiguously identified.
- Fallback, staged, and compatibility paths are clearly distinguished from the canonical path.
- Android-side inbound dispatch is placed explicitly inside the same execution model.
- Result, signal, and reconciliation responsibilities are distribution-traced to their owning layers.
- Local and remote execution are understood as one governed system rather than independent modes.
- Future work can reason about task dispatch as a single governed system rather than scattered handlers.

---

## 2. Canonical dispatch chain overview

The complete canonical execution chain spans six distinct phases:

```
Phase 1: Source dispatch orchestration        (main-repo governed)
Phase 2: Android inbound dispatch / routing   (Android, GalaxyConnectionService)
Phase 3: Execution pipeline                   (Android, EdgeExecutor / delegated path)
Phase 4: Execution tracking and signal emission
Phase 5: Result and signal return             (uplink)
Phase 6: Result handling and reconciliation   (MainViewModel / UI / memory backflow)
```

The chain may operate in one of four path modes:

| Path mode | Label | Description |
|-----------|-------|-------------|
| **Canonical** | `canonical` | Primary dispatch: user input → cross-device uplink → Gateway → inbound task_assign → local execution → result uplink |
| **Local** | `local` | User input → on-device closed-loop execution → local result (no Gateway involvement) |
| **Fallback** | `fallback` | Cross-device attempted but remote handoff failed → falls back to local execution (explicit, logged) |
| **Staged mesh** | `staged_mesh` | V2 coordinator assigns a mesh subtask → StagedMeshExecutionTarget → goal-execution pipeline → StagedMeshParticipationResult |

A fifth path — **delegated takeover** — is the Android-side of the canonical path when
the center dispatches a task to this device specifically as a delegated execution target.

---

## 3. Primary dispatch path (canonical)

The **canonical primary dispatch path** is:

```
User text / voice input
       │
       ▼
NaturalLanguageInputManager (speech/)
  — collects text; no routing decisions
       │
       ▼
InputRouter.route(text)
  — SOLE routing decision point
  — crossDeviceEnabled = true AND WS connected
       │  YES
       ▼
GatewayClient.sendJson(TaskSubmitPayload)
  — AIP v3 task_submit envelope
  — source_node = localDeviceId, target_node = "Galaxy"
       │  WS uplink
       ▼
Galaxy Gateway (center / main-repo governed)
  — assigns task to capable device(s)
  — sends task_assign / goal_execution back to this device
         or to another device in the network
       │  WS downlink to assigned device
       ▼
GalaxyWebSocketClient.onMessage()
  — notifies registered Listener (GalaxyConnectionService)
       │
       ▼
GalaxyConnectionService.handleTaskAssign()
  — parses AipMessage type
  — calls runtimeController.onRemoteTaskStarted()
    (cancels any running local LoopController session)
       │
       ▼
Execution phase (see §4)
       │
       ▼
Result uplink + runtimeController.onRemoteTaskFinished()
```

**Rollout gate:**
`RolloutControlSnapshot.crossDeviceAllowed` must be `true` for the canonical path to be
active. When it is `false`, `InputRouter` routes to local execution instead.

---

## 4. Execution phase — path routing

Once `GalaxyConnectionService` receives an inbound assignment, it routes based on message
type and execution context:

### 4.1 task_assign path

```
GalaxyConnectionService.handleTaskAssign(taskId, payloadJson, traceId)
       │
       ├─ crossDeviceEnabled = true
       │  AND require_local_agent = false
       │  AND exec_mode ∈ {remote, both}?
       │
       │  YES → AgentRuntimeBridge.handoff(HandoffRequest)        [remote handoff path]
       │           — idempotency cache check
       │           — bridge_handoff AIP v3 message sent via GatewayClient
       │           — timeout 30s, 3 retries, backoff 1/2/4 s
       │           — on success: runtimeController.onRemoteTaskFinished() immediately
       │           — on all retries exhausted: FALLBACK (see §5)
       │
       └─ NO → EdgeExecutor.handleTaskAssign(payload)             [local execution path]
```

### 4.2 goal_execution / parallel_subtask path

```
LocalGoalExecutor.executeGoal(payload)
  — wraps EdgeExecutor internally
  — returns GoalResultPayload
  — result sent via sendGoalResult(GOAL_EXECUTION_RESULT)
```

### 4.3 task_cancel path

```
TaskCancelRegistry.cancel(task_id)
  — looks up coroutine Job by task_id
  — calls job.cancel(CancellationException)
  — deregistered in finally block of executing coroutine
  — GalaxyConnectionService sends CancelResultPayload
```

### 4.4 takeover_request path (delegated execution)

```
GalaxyConnectionService.handleTakeoverRequest()
       │
       ▼
DelegatedRuntimeReceiver.receive(envelope, session)
  — gate: session must be ATTACHED and non-null
  — gate: RolloutControlSnapshot.delegatedExecutionAllowed = true
  — on rejection: returns Rejected (null session / DETACHING / DETACHED)
       │
       ▼  Accepted
AttachedRuntimeSession.withExecutionAccepted()
  — increments delegatedExecutionCount (PR-14)
  — RuntimeController.recordDelegatedExecutionAccepted()
       │
       ▼
DelegatedTakeoverExecutor.execute(unit, initialRecord, pipeline, signalSink)
  — creates DelegatedExecutionTracker (PENDING)
  — emits ACK signal (emissionSeq=1)
  — advances tracker: PENDING → ACTIVATING → ACTIVE
  — emits PROGRESS signal (emissionSeq=2)
  — invokes GoalExecutionPipeline (SourceRuntimePosture.JOIN_RUNTIME)
  — emits RESULT signal (emissionSeq=3) with ResultKind:
      COMPLETED / FAILED / TIMEOUT / CANCELLED
```

---

## 5. Fallback-local path

The fallback path is **always explicit** — it is logged, counted, and tagged:

```
AgentRuntimeBridge.handoff() — all retries exhausted
       │
       ▼  HandoffResult.status = STATUS_FALLBACK
GalaxyConnectionService
  — logs at WARN: "task_assign bridge handoff failed — falling back to local execution"
  — GalaxyLogger: event="bridge_fallback"
  — emitRuntimeDiagnostics(nodeName="bridge_handoff")
       │
       ▼
EdgeExecutor.handleTaskAssign(payload)   [same local execution path as §4.1 NO-branch]
       │
       ▼
TaskResultPayload sent via GalaxyWebSocketClient
ExecutionRouteTag.FALLBACK recorded on result
```

**Fallback triggers:**

| Trigger | Behavior |
|---------|----------|
| `crossDeviceEnabled = false` | `InputRouter` routes locally; bridge never invoked |
| `exec_mode = "local"` | Bridge skipped regardless of cross-device flag |
| WS not connected | Bridge send fails; falls back after retries |
| All retries exhausted (3 attempts, backoff 1/2/4 s) | Explicit fallback to local |
| `RolloutControlSnapshot.fallbackToLocalAllowed = false` | Fallback suppressed (task fails) |

---

## 6. Staged mesh coordination path

```
V2 coordinator (main-repo) assigns staged-mesh subtask to Android
       │  WS downlink: goal_execution (parallel_subtask context)
       ▼
GalaxyConnectionService → LocalGoalExecutor
       │
       ▼
StagedMeshExecutionTarget.acceptSubtask(meshId, subtaskId, payload, rolloutSnapshot)
  — gate: RolloutControlSnapshot.crossDeviceAllowed = true
  — on gate closed: returns ExecutionStatus.BLOCKED immediately
  — on gate open: delegates to SubtaskExecutor (→ goal-execution pipeline)
       │
       ▼
GoalResultPayload (from SubtaskExecutor)
       │
       ▼
StagedMeshParticipationResult
  — identity chain: meshId / subtaskId / taskId / deviceId
  — ExecutionStatus: SUCCESS / FAILURE / CANCELLED / BLOCKED
  — toSessionContribution() → AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK
       │
       ▼
Result returned to V2 coordinator via GOAL_EXECUTION_RESULT
```

**Note:** Android does **not** manage mesh session lifecycle (join / leave). That
responsibility belongs to the V2 coordinator. Android only executes assigned subtasks.

---

## 7. Local execution path

```
InputRouter.route(text)
  — crossDeviceEnabled = false  (or no WS connection on error path)
       │
       ▼
LocalLoopExecutor.execute(LocalLoopOptions)
  — creates sessionId, delegates to LoopController
       │
       ▼
LoopController.execute(instruction)
  — per-step loop (up to maxSteps):
      1. Screenshot capture (JPEG)
      2. MobileVLM 1.7B planner (LocalPlannerService)
      3. Stagnation / plan-repeat guard (StagnationDetector)
      4. SeeClick grounding (LocalGroundingService) — on-device; never Gateway
      5. AccessibilityService action dispatch (AccessibilityActionExecutor)
      6. Post-action screenshot + observer (PostActionObserver)
  — terminates on: success / budget exhausted / stagnation / timeout
       │
       ▼
LocalLoopResult
  — delivered to MainViewModel via InputRouter.onLocalResult callback
  — ExecutionRouteTag.LOCAL recorded
  — trace persisted to LocalLoopTraceStore
  — session summary persisted to SessionHistoryStore
```

---

## 8. Execution tracking

Every execution path is tracked and tagged for observability:

### 8.1 ExecutionRouteTag (Android-side, PR-29)

| Tag | Meaning |
|-----|---------|
| `LOCAL` | Closed-loop `LoopController` execution |
| `CROSS_DEVICE` | Task submitted to Gateway; result arrived as server message |
| `DELEGATED` | Accepted inbound delegated takeover; ran to COMPLETED |
| `FALLBACK` | Delegated takeover failed; `TakeoverFallbackEvent` emitted |

`MainUiState.lastExecutionRoute` and `executionRouteCounts` expose route history for
the diagnostics panel.

### 8.2 DelegatedExecutionTracker (delegated path only)

The tracker provides an immutable-chain representation of delegated execution state:

```
PENDING → ACTIVATING → ACTIVE → COMPLETED
                              → FAILED
                              → TIMEOUT
                              → CANCELLED
```

The tracker is created at receipt time (before the first signal) and transitions
forward only. It does not modify `AttachedRuntimeSession` state.

### 8.3 EmittedSignalLedger (PR-18)

Every `DelegatedExecutionSignal` emitted via `signalSink` is recorded in a
per-execution `EmittedSignalLedger`. Callers that need to replay a signal (e.g. after
a send failure) must use `EmittedSignalLedger.replaySignal()` to preserve the original
`signalId` and `emissionSeq`.

---

## 9. Signal emission and reconciliation (delegated path)

### 9.1 Signal sequence

Every inbound delegated execution emits exactly three signals in canonical order:

```
ACK    (emissionSeq=1) — "Android acknowledged the unit"
PROGRESS (emissionSeq=2) — "Android is actively executing"
RESULT   (emissionSeq=3) — "Android has a final outcome"
```

This sequence is invariant across all terminal outcomes (COMPLETED / FAILED / TIMEOUT /
CANCELLED). The main-repo host always receives exactly these three signals for each
accepted delegated unit.

### 9.2 Signal identity (PR-15)

Each signal carries stable identity fields for host-side reconciliation:

| Field | Purpose |
|-------|---------|
| `signalId` | UUID idempotency key — host discards duplicate deliveries with the same `signalId` |
| `emissionSeq` | Monotonic position (1/2/3) — host detects out-of-order delivery |
| `attachedSessionId` | Binds all signals to one attached runtime session |
| `taskId` | Binds all signals to one task |
| `traceId` | End-to-end correlation across the full chain |
| `unitId` | Binds all signals to one delegated execution unit (takeover_id) |
| `handoffContractVersion` | Declares the delegated handoff contract version in use |

All identity fields are captured at receipt time and are stable across the entire
ACK→PROGRESS→RESULT lifecycle.

### 9.3 Cross-repo reconciliation

The main-repo host reconciles delegated execution signals using:

- `signalId` for duplicate-delivery deduplication.
- `emissionSeq` for sequence completeness verification.
- `attachedSessionId` to correlate signals with the attached runtime session state.
- `taskId` + `traceId` to correlate with the upstream dispatch record.

Android never claims authority over main-repo reconciliation state. The signal stream
is Android's authoritative uplink contribution; reconciliation truth lives in the
main-repo tracker.

---

## 10. Result handling and distribution

### 10.1 Result payload families

| Result type | Used for | Return channel |
|-------------|----------|----------------|
| `TaskResultPayload` | task_assign (local or fallback) | WS uplink: `task_result` |
| `GoalResultPayload` | goal_execution / parallel_subtask | WS uplink: `goal_execution_result` |
| `CancelResultPayload` | task_cancel | WS uplink: `cancel_result` |
| `DelegatedExecutionSignalPayload` | delegated takeover signals | WS uplink: `delegated_execution_signal` |
| `StagedMeshParticipationResult` | staged mesh subtask | WS uplink: `goal_execution_result` (via V2 coordinator) |

### 10.2 Offline result durability

When WS is disconnected at result-send time:

```
GalaxyWebSocketClient.sendJson(result) — WS disconnected
       │
       ▼
OfflineTaskQueue.enqueue(json)
  — max 50 messages (drop-oldest policy)
  — persisted to SharedPreferences
  — messages older than 24 h dropped on load

WS reconnected (onOpen)
       │
       ▼
OfflineTaskQueue.drainTo { json → GalaxyWebSocketClient.sendJson(json) }
  — FIFO order
  — queue size exposed via StateFlow<Int> queueSize
```

`OfflineTaskQueue` is a bounded operational cache aid, **not** a full authoritative
persistence layer.

### 10.3 Android-side result presentation

`UnifiedResultPresentation` (PR-26) normalizes all four result paths to a single
user-facing presentation surface:

| Factory | Used for |
|---------|---------|
| `fromLocalResult(LocalLoopResult)` | Local closed-loop path |
| `fromServerMessage(String)` | Cross-device path (server message arrival) |
| `fromFallbackEvent(TakeoverFallbackEvent)` | Fallback after delegated failure |

`MainViewModel` uses `UnifiedResultPresentation` for all four paths so that user-facing
chat messages and the status label in `EnhancedFloatingService` are produced through a
single canonical presentation contract.

### 10.4 TakeoverFallbackEvent (PR-23)

When a delegated execution fails, `RuntimeController.notifyTakeoverFailed()` emits a
`TakeoverFallbackEvent` with one of four causes:

| Cause | Trigger |
|-------|---------|
| `FAILED` | `ExecutionOutcome.Failed` returned |
| `TIMEOUT` | Execution timed out |
| `CANCELLED` | Execution cancelled externally |
| `DISCONNECT` | WS disconnect while `activeTakeoverId != null` |

`MainViewModel` and `EnhancedFloatingService` both observe `takeoverFailure` to clear
stale UI state. `ExecutionRouteTag.FALLBACK` is set on the result.

---

## 11. Rollout control governance

All execution paths are governed by `RolloutControlSnapshot` (PR-31):

| Flag | Path governed |
|------|--------------|
| `crossDeviceAllowed` | Canonical cross-device path and staged mesh |
| `delegatedExecutionAllowed` | Inbound delegated takeover acceptance |
| `fallbackToLocalAllowed` | Local fallback after delegated failure |
| `goalExecutionAllowed` | Autonomous goal execution |

`RuntimeController.rolloutControlSnapshot` is a `StateFlow` derived from `AppSettings`.
`RuntimeController.applyKillSwitch()` can atomically disable all remote paths
(sets `crossDeviceAllowed=false` and `goalExecutionAllowed=false`).

`TakeoverEligibilityAssessor` adds outcome `BLOCKED_DELEGATED_EXECUTION_DISABLED` when
`delegatedExecutionAllowed=false`.

---

## 12. Responsibilities by domain

### Android-authoritative responsibilities

| Responsibility | Android surface |
|----------------|----------------|
| Runtime lifecycle | `RuntimeController.state` |
| Attached session continuity | `AttachedRuntimeSession` + `AttachedRuntimeHostSessionSnapshot` |
| Delegated execution signal emission | `DelegatedTakeoverExecutor` + `DelegatedExecutionSignal` |
| Delegated signal ledger (replay-safe) | `EmittedSignalLedger` |
| Reconnect recovery state | `RuntimeController.reconnectRecoveryState` |
| Execution route tracking (Android local) | `ExecutionRouteTag` + `MainUiState.executionRouteCounts` |
| Offline result buffering | `OfflineTaskQueue` |
| Staged mesh subtask execution | `StagedMeshExecutionTarget` |
| On-device execution | `EdgeExecutor` / `LoopController` |
| Rollout gate enforcement | `RolloutControlSnapshot` |

### Center-authoritative (main-repo) responsibilities

| Responsibility | Surface |
|----------------|---------|
| Task dispatch orchestration | source dispatch orchestrator |
| Gateway routing and assignment | Galaxy Gateway |
| Delegated signal reconciliation and tracker truth | main-repo delegated execution tracker |
| Participant graph and device graph truth | center-governed registries |
| Control session identity | center-governed (echoed by Android) |
| Mesh session lifecycle (join / leave) | V2 coordinator |
| Staged mesh subtask assignment | V2 coordinator |

---

## 13. Path classification summary

| Path | Classification | Rollout gate |
|------|---------------|--------------|
| User → Gateway → inbound task_assign → EdgeExecutor | **Canonical** | `crossDeviceAllowed` |
| User → LocalLoopExecutor → LoopController | **Local** | (none — always available) |
| AgentRuntimeBridge fallback → EdgeExecutor | **Fallback** | `fallbackToLocalAllowed` |
| V2 coordinator → StagedMeshExecutionTarget | **Staged mesh** | `crossDeviceAllowed` |
| takeover_request → DelegatedTakeoverExecutor | **Delegated** | `delegatedExecutionAllowed` |
| Legacy task_execute / task_status_query remapped to task_assign | **Compatibility** | (same as task_assign) |

---

## 14. GalaxyLogger observability tags

The following structured log tags trace execution across all paths:

| Tag | Path | Events |
|-----|------|--------|
| `TAG_EXEC_ROUTE` (`GALAXY:EXEC:ROUTE`) | All paths | Route selection |
| `TAG_FALLBACK_DECISION` (`GALAXY:FALLBACK:DECISION`) | Fallback path | Fallback trigger |
| `TAG_RECONNECT_OUTCOME` (`GALAXY:RECONNECT:OUTCOME`) | Runtime lifecycle | Reconnect result |
| `TAG_SETUP_RECOVERY` (`GALAXY:SETUP:RECOVERY`) | Setup / registration | Recovery action |
| `TAG_STAGED_MESH` (`GALAXY:STAGED:MESH`) | Staged mesh path | Accept / block / result |
| `TAG_ROLLOUT_CONTROL` (`GALAXY:ROLLOUT:CONTROL`) | All paths | Gate state |
| `TAG_KILL_SWITCH` (`GALAXY:KILL:SWITCH`) | All paths | Kill-switch activation |

---

## 15. Implementation reference

| Concept | Android implementation |
|---------|----------------------|
| Routing decision point | `InputRouter` (`input/`) |
| Cross-device uplink | `GatewayClient` (`network/`) |
| Inbound dispatch router | `GalaxyConnectionService` (`service/`) |
| Local execution pipeline | `LocalLoopExecutor` → `LoopController` (`local/`, `loop/`) |
| On-device task executor | `EdgeExecutor` (`agent/`) |
| Remote handoff bridge | `AgentRuntimeBridge` (`agent/`) |
| Delegated receipt | `DelegatedRuntimeReceiver` (`agent/`) |
| Delegated executor | `DelegatedTakeoverExecutor` (`agent/`) |
| Signal model | `DelegatedExecutionSignal` (`runtime/`) |
| Signal ledger | `EmittedSignalLedger` (`runtime/`) |
| Tracker model | `DelegatedExecutionTracker` (`runtime/`) |
| Staged mesh target | `StagedMeshExecutionTarget` (`runtime/`) |
| Staged mesh result | `StagedMeshParticipationResult` (`runtime/`) |
| Rollout control | `RolloutControlSnapshot` (`runtime/`) |
| Execution route tag | `ExecutionRouteTag` (`runtime/`) |
| Fallback event | `TakeoverFallbackEvent` (`runtime/`) |
| Offline buffering | `OfflineTaskQueue` (`network/`) |
| Result presentation | `UnifiedResultPresentation` (`ui/viewmodel/`) |
| Attached session | `AttachedRuntimeSession` + `AttachedRuntimeHostSessionSnapshot` (`runtime/`) |
| Reconnect recovery | `ReconnectRecoveryState` (`runtime/`) |
| Dispatch chain registry | `CanonicalDispatchChain` (`runtime/`) |

---

## 16. Non-goals

- This document does **not** change any runtime behavior, wire contracts, or existing field values.
- This document does **not** transfer authority from `RuntimeController` or the center orchestrator.
- Naming convergence for transitional aliases is intentionally deferred to follow-up phases.
- Full cross-repository schema unification is deferred to later protocol profile phases.
