# Android Lifecycle, Recovery, and Hybrid-Participant Hardening

> **PR-53**
> Introduced in: `ufo-galaxy-android`
> Audience: V2 orchestration engineers, Android platform engineers, distributed runtime reviewers

---

## Overview

This document provides the canonical reference for Android lifecycle integration, recovery
semantics, and hybrid-participant capability boundaries, answering the five acceptance criteria
questions for this PR:

1. **How Android behaves across lifecycle changes and reconnect scenarios**
2. **What Android can recover locally vs what must be re-synchronized from V2**
3. **Whether Android participant behavior supports the hybrid/distributed runtime model**
4. **Whether runtime-critical local state has the necessary durability/readiness semantics**
5. **What lifecycle/recovery limitations remain intentionally out of scope**

---

## 1. Android App Lifecycle → Participant State Mapping

**Surface: `AppLifecycleParticipantBoundary`** (PR-53)

The complete canonical mapping from Android app lifecycle events to participant-side runtime
state effects, session continuity behavior, and recovery ownership.

### Lifecycle Event Map

| Android Lifecycle Event | Participant State Effect | Session Continuity | Android Recovers Locally? | V2 Resync Required? |
|---|---|---|---|---|
| `FOREGROUND` | `NO_CHANGE` | `DURABLE_SESSION_PRESERVED` | ✅ Yes | ❌ No |
| `BACKGROUND` | `NO_CHANGE` | `DURABLE_SESSION_PRESERVED` | ✅ Yes | ❌ No |
| `CONFIGURATION_CHANGE` | `NO_CHANGE` | `DURABLE_SESSION_PRESERVED` | ✅ Yes | ❌ No |
| `BACKGROUND_KILL` | `SESSION_TERMINATED` | `DURABLE_SESSION_ENDED` | ❌ No | ✅ Yes |
| `MEMORY_PRESSURE` | `EXECUTION_PAUSED` | `RECONNECT_DEPENDENT` | ❌ No | ✅ Yes |
| `PROCESS_RECREATION` | `SESSION_TERMINATED` | `DURABLE_SESSION_ENDED` | ❌ No | ✅ Yes |

### Design notes

- **Configuration changes** (Activity rotation, locale change) do NOT affect RuntimeController
  because it is scoped to a Service/Application context, not to an Activity. Session and runtime
  state are fully preserved — no V2 action required.

- **Background without kill**: WS connection and AttachedRuntimeSession remain active.
  In-flight tasks may complete normally. Android does not notify V2 of background-only
  transitions unless a WS disconnect follows.

- **Background kill / Process recreation**: All in-memory state is lost. Android starts a new
  activation era (new `durableSessionId`). V2 must reconcile the new session via the
  `DeviceConnected` lifecycle event and `publishRuntimeTruthSnapshot`.

- **Memory pressure**: Android downgrades readiness to `ParticipantHealthState.DEGRADED` before
  critical memory pressure to allow V2 to redirect dispatch proactively. If the process survives,
  session continuity depends on WS reconnect success.

### Query helpers

```kotlin
// Events Android can recover from without V2 resync
AppLifecycleParticipantBoundary.locallyRecoverableEvents
// → [FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE]

// Events requiring V2 re-synchronization
AppLifecycleParticipantBoundary.v2ResyncRequiredEvents
// → [BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION]

// Events that end the durable session era
AppLifecycleParticipantBoundary.sessionTerminatingEvents
// → [BACKGROUND_KILL, PROCESS_RECREATION]
```

---

## 2. Recovery Semantics: Local vs V2 Resync

**Surface: `ParticipantRecoveryReadinessSnapshot`** (PR-53)

Explicit durability/V2-resync registry for every runtime-critical local state field.

### Durability Tiers

| Tier | Description |
|---|---|
| `SETTINGS_PERSISTED` | Persisted in AppSettings (SharedPreferences); survives process kill |
| `DURABLE_IN_MEMORY` | In-memory; survives WS reconnects within era; lost on process kill |
| `EPHEMERAL` | Per-WS-connection; reset on any disconnect |
| `V2_CANONICAL` | Not held by Android; V2 is the canonical source |

### Recovery Field Registry

| Field | Durability | Survives Process Kill | Survives WS Reconnect | V2 Resync After Kill | V2 Resync After Reconnect |
|---|---|---|---|---|---|
| `AppSettings.crossDeviceEnabled` | `SETTINGS_PERSISTED` | ✅ | ✅ | ❌ | ❌ |
| `DurableSessionContinuityRecord.durableSessionId` | `DURABLE_IN_MEMORY` | ❌ | ✅ | ✅ | ❌ |
| `ParticipantHealthState / ParticipantReadinessState` | `DURABLE_IN_MEMORY` | ❌ | ✅ | ✅ | ❌ |
| `RuntimeHostDescriptor.HostParticipationState` | `DURABLE_IN_MEMORY` | ❌ | ✅ | ✅ | ❌ |
| `AttachedRuntimeSession (sessionId, state)` | `EPHEMERAL` | ❌ | ❌ | ✅ | ✅ |
| `In-flight task state (activeTaskId, activeTaskStatus)` | `EPHEMERAL` | ❌ | ❌ | ✅ | ✅ |
| `Global session assignment` | `V2_CANONICAL` | ❌ | ❌ | ✅ | ✅ |
| `Barrier / merge / completion tracking` | `V2_CANONICAL` | ❌ | ❌ | ✅ | ✅ |

### Key recovery rules

1. **`crossDeviceEnabled` survives process kill** — `RuntimeController.connectIfEnabled()` reads
   this from AppSettings on startup to autonomously re-establish the WS without V2 direction.

2. **`durableSessionId` survives WS reconnects** — the `sessionContinuityEpoch` increments on
   each reconnect. V2 can correlate successive `AttachedRuntimeSession` values to the same era
   via `DurableSessionContinuityRecord.durableSessionId`.

3. **In-flight task state is ephemeral** — Android MUST NOT attempt to resume interrupted task
   execution after any lifecycle disruption. V2 must apply fallback policy for tasks dispatched
   to Android that did not receive a `TASK_RESULT` or `TASK_CANCELLED` signal.

4. **After process kill: only `crossDeviceEnabled` is recoverable locally** — everything else
   requires V2 resync via `DeviceConnected` lifecycle event and `publishRuntimeTruthSnapshot`.

### Query helpers

```kotlin
// Fields Android recovers locally after WS reconnect (no V2 resync needed)
ParticipantRecoveryReadinessSnapshot.locallyRecoverableAfterWsReconnect
// → [cross_device_enabled_flag, durable_session_id, participant_health_readiness,
//    session_participation_state]

// Fields requiring V2 resync after process kill
ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterProcessKillList
// → [durable_session_id, participant_health_readiness, session_participation_state,
//    attached_runtime_session, active_task_state, global_session_assignment,
//    barrier_merge_tracking]

// Fields V2 canonically owns (never held by Android)
ParticipantRecoveryReadinessSnapshot.v2CanonicalFields
// → [global_session_assignment, barrier_merge_tracking]
```

---

## 3. WS Reconnect Lifecycle (existing behavior, documented here for completeness)

The WS reconnect path is handled by `ReconnectRecoveryState` (PR-33) and
`DurableSessionContinuityRecord` (PR-1). The PR-53 surfaces add explicit documentation
of the recovery semantics but do not change the runtime behavior.

### Reconnect flow

```
WS disconnect (runtime was Active)
  │
  ▼
ReconnectRecoveryState.RECOVERING
  │  emits V2MultiDeviceLifecycleEvent.DeviceDegraded (ws_recovering)
  │  emits V2MultiDeviceLifecycleEvent.DeviceDisconnected (isResumable=true)
  │
  ├── [WS reconnect success]
  │     ▼
  │   openAttachedSession(SessionOpenSource.RECONNECT_RECOVERY)
  │     → new AttachedRuntimeSession (new sessionId)
  │     → durableSessionContinuityRecord.withEpochIncremented()
  │     emits V2MultiDeviceLifecycleEvent.DeviceReconnected
  │     ReconnectRecoveryState.RECOVERED
  │
  └── [reconnect attempts exhausted]
        ▼
      ReconnectRecoveryState.FAILED
        emits V2MultiDeviceLifecycleEvent.DeviceDegraded (ws_recovery_failed)
        ▼
      [watchdog timer ~35 s] → ReconnectRecoveryState.RECOVERING (perpetual re-entry)
        WS client continues scheduling watchdog reconnect attempts at cap delay
```

### What Android recovers locally across a WS reconnect

- ✅ `crossDeviceEnabled` flag — unchanged in AppSettings
- ✅ `durableSessionId` — preserved; epoch incremented
- ✅ Health/readiness state — re-reported via `DeviceReconnected` event
- ✅ Formation role — unchanged

### What V2 must resync after a WS reconnect

- ✅ `AttachedRuntimeSession` identity — new sessionId issued; V2 correlates via `durableSessionId`
- ✅ In-flight task state — any task in progress at disconnect is treated as lost; V2 applies fallback

---

## 4. Hybrid/Distributed Participant Capability Boundaries

**Surface: `HybridParticipantCapabilityBoundary`** (PR-53)

### Capability maturity registry

| Capability | Maturity | Response Contract |
|---|---|---|
| `STAGED_MESH_EXECUTION` | ✅ `FULLY_WIRED` | `StagedMeshParticipationResult` via `AndroidSessionContribution.Kind.STAGED_MESH_SUBTASK` |
| `BARRIER_PARTICIPATION` | ✅ `FULLY_WIRED` | `ReconciliationSignal.PARTICIPANT_STATE` + `V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged` |
| `FORMATION_REBALANCE` | ✅ `FULLY_WIRED` | `FormationRebalanceEvent` stream; health/readiness reporting |
| `HYBRID_EXECUTE` | ⚠️ `CONTRACT_FIRST` | Degrade reply with `capability_maturity=contract_first` + `reason=hybrid_executor_not_implemented` |
| `RAG_QUERY` | ⚠️ `CONTRACT_FIRST` | Empty result with `capability_maturity=contract_first` + `reason=rag_pipeline_not_implemented` |
| `CODE_EXECUTE` | ⚠️ `CONTRACT_FIRST` | Error reply with `capability_maturity=contract_first` + `reason=sandbox_not_implemented` |

### No-silent-degrade rule

All `CONTRACT_FIRST` capabilities MUST include `capability_maturity=contract_first` in their
response payload. This allows V2 to distinguish intentional limitation from an execution failure
and apply correct routing policy.

**Prohibited**: empty-ok responses, silent discards, or untagged error responses for
`CONTRACT_FIRST` capabilities.

### Wire tags for CONTRACT_FIRST responses

```
capability_maturity = "contract_first"       // always required for CONTRACT_FIRST capabilities
reason              = "<specific_reason>"    // capability-specific; see below

HYBRID_EXECUTE:  reason = "hybrid_executor_not_implemented"
RAG_QUERY:       reason = "rag_pipeline_not_implemented"
CODE_EXECUTE:    reason = "sandbox_not_implemented"
```

### Fully wired hybrid paths (evidence-backed)

- ✅ **Staged-mesh execution** — end-to-end wired: `StagedMeshExecutionTarget`,
  `StagedMeshParticipationResult`, `toSessionContribution()`. Tested in
  `Pr32StagedMeshTargetExecutionTest`.

- ✅ **Barrier participation** — Android reports `PARTICIPANT_STATE` signals on every health and
  readiness change. V2 uses these to track barrier crossing without Android-side barrier
  orchestration. Tested in `Pr52ReconciliationSignalEmissionTest`.

- ✅ **Formation rebalance** — `FormationParticipationRebalancer` and `FormationRebalanceEvent`
  provide V2 with the signals needed to drive rebalancing. Tested via
  `Pr2FormationRebalanceAndRecoveryHooksTest`.

### Contract-first limitations (intentional, out of scope for PR-53)

- ⚠️ `HYBRID_EXECUTE` — full hybrid executor not implemented. Explicit CONTRACT_FIRST response required.
- ⚠️ `RAG_QUERY` — RAG pipeline not implemented. Explicit CONTRACT_FIRST response required.
- ⚠️ `CODE_EXECUTE` — sandbox not implemented. Explicit CONTRACT_FIRST response required.

These limitations are **intentional and stable**. Promoting any of these to `FULLY_WIRED`
requires a separate PR with the full implementation and tests.

---

## 5. Intentional Out-of-Scope Limitations

The following limitations are **intentionally out of scope** for PR-53 and remain acknowledged:

### Limitations Android cannot self-resolve

| Limitation | Why Out of Scope | V2 Behavior |
|---|---|---|
| Task continuation after process kill | Android cannot recover ephemeral task state across process death | V2 applies fallback policy; re-dispatches to another participant |
| Global session assignment recovery | V2 is the canonical source; Android never holds this | V2 re-syncs on reconnect/new activation |
| Barrier/merge state after disconnect | V2 tracks cross-participant state; Android only reports health | V2 re-evaluates on participant re-registration |
| Hybrid executor (`HYBRID_EXECUTE`) | Requires full hybrid executor implementation | V2 receives CONTRACT_FIRST tag; routes elsewhere |
| RAG pipeline (`RAG_QUERY`) | Requires on-device RAG pipeline | V2 receives CONTRACT_FIRST tag; routes elsewhere |
| Code sandbox (`CODE_EXECUTE`) | Requires sandboxed code execution environment | V2 receives CONTRACT_FIRST tag; routes elsewhere |

### Why Android remains a participant, not an orchestrator

- Android does not re-order or override V2 session assignments after lifecycle disruptions.
- Android MUST NOT autonomously resume interrupted task execution or self-declare a session as
  continued without V2 re-authorization.
- All recovery coordination decisions belong to V2. Android's role is to report its current state
  (via `publishRuntimeTruthSnapshot` and `V2MultiDeviceLifecycleEvent`) so V2 can make the
  correct coordination decision.

---

## 6. Test Evidence

| Behavior | Test class | Test method group |
|---|---|---|
| All 6 lifecycle events have correct state effect | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `AppLifecycleParticipantBoundary — *_KILL/FOREGROUND/etc semantics` |
| Locally recoverable vs V2 resync required | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `locallyRecoverableEvents / v2ResyncRequiredEvents` |
| Session-terminating lifecycle events | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `sessionTerminatingEvents` |
| FULLY_WIRED vs CONTRACT_FIRST capability classification | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `fullyWiredCapabilities / contractFirstCapabilities` |
| No-silent-degrade: CONTRACT_FIRST responseContract includes tag | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `all CONTRACT_FIRST capabilities have responseContract referencing RESULT_CAPABILITY_MATURITY_TAG` |
| `crossDeviceEnabled` survives process kill | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `cross_device_enabled_flag survivesProcessKill is true` |
| `durableSessionId` survives WS reconnect | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `durable_session_id survivesWsReconnect is true` |
| In-flight task state is ephemeral | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `active_task_state durabilityTier is EPHEMERAL` |
| V2-canonical fields never held by Android | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `v2CanonicalFields contains global_session_assignment` |
| 3 PR-53 StabilizationBaseline entries registered | `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | `exactly three entries with introducedPr equal to 53` |
| Staged-mesh execution fully wired | `Pr32StagedMeshTargetExecutionTest` | (all) |
| ReconciliationSignal emission (barrier participation) | `Pr52ReconciliationSignalEmissionTest` | `notifyParticipantHealthChanged also emits PARTICIPANT_STATE` |
| Formation rebalance hooks | `Pr2FormationRebalanceAndRecoveryHooksTest` | (all) |
| WS reconnect DeviceReconnected event | `Pr43V2MultiDeviceLifecycleIntegrationTest` | `DeviceReconnected is emitted` |

---

## 7. Related Surfaces

| Surface | Purpose | PR |
|---|---|---|
| `AppLifecycleParticipantBoundary` | Android app lifecycle → participant state mapping | PR-53 |
| `HybridParticipantCapabilityBoundary` | Hybrid capability maturity (FULLY_WIRED vs CONTRACT_FIRST) | PR-53 |
| `ParticipantRecoveryReadinessSnapshot` | Durability/V2-resync registry for local state fields | PR-53 |
| `DurableSessionContinuityRecord` | Live durable session era record (in-memory) | PR-1 |
| `ReconnectRecoveryState` | Observable WS reconnect phase | PR-33 |
| `ContinuityRecoveryContext` | Interruption reason vocabulary and token boundary | PR-46 |
| `V2MultiDeviceLifecycleEvent` | V2-consumable device lifecycle events | PR-43 / PR-44 |
| `RuntimeController.reconnectRecoveryState` | Observable reconnect recovery state flow | PR-33 |
| `RuntimeController.v2LifecycleEvents` | Observable V2 lifecycle event stream | PR-43 |
| `RuntimeController.reconciliationSignals` | Observable Android→V2 reconciliation signal stream | PR-52 |
| `AndroidParticipantRuntimeTruth` | Consolidated participant truth snapshot | PR-51 |
| `StagedMeshParticipationResult` | Staged-mesh target execution result | PR-32 |
| `FormationParticipationRebalancer` | Formation rebalance participant-side logic | PR-2 |
| `StabilizationBaseline` | Canonical surface stability registry | PR-11 |
