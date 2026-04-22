# Android Participant Lifecycle Hardening, Recovery, and Hybrid Runtime Continuity

> **PR-5Android**
> Introduced in: `ufo-galaxy-android`
> Audience: V2 orchestration engineers, Android platform engineers, distributed runtime reviewers

---

## Overview

This document provides the canonical reviewable reference for **PR-5Android**, which hardens
Android participant lifecycle semantics, recovery behavior, and hybrid runtime continuity.

It answers the four acceptance criteria questions a reviewer needs to determine:

1. **How Android participant runtime behaves across lifecycle interruptions and recovery**
2. **What local runtime/session/task truth is preserved, resumed, reset, or reattached**
3. **How Android hybrid participant behavior remains coherent across reconnect/restart/lifecycle transitions**
4. **How Android lifecycle recovery stays bounded under V2 canonical orchestration authority**

---

## Relationship to Prior PRs

| PR | Surface | Focus |
|---|---|---|
| PR-53 | `AppLifecycleParticipantBoundary`, `HybridParticipantCapabilityBoundary`, `ParticipantRecoveryReadinessSnapshot` | Structure Android participant runtime truth and execution semantics |
| PR-60 | `AndroidAppLifecycleTransition`, `HybridParticipantCapability`, `AndroidLifecycleRecoveryContract` | Lifecycle hardening and recovery boundary documentation |
| **PR-5Android** | `ParticipantPostureLifecycleBoundary`, `HybridRuntimeContinuityContract`, `ParticipantAttachmentTransitionSemantics` | **Harden posture-aware lifecycle, hybrid continuity, and attachment transition semantics** |

PR-5Android builds on PR-53/PR-60 but does NOT duplicate their content.  Where PR-53/PR-60
declared the *structure* of lifecycle and recovery semantics, PR-5Android clarifies
*posture-specific* and *hybrid-execution-specific* behavior and *attachment transition* coverage.

---

## 1. Posture-Aware Lifecycle Behavior

**Surface: `ParticipantPostureLifecycleBoundary`** (PR-5Android)

### AC1: How Android participant runtime behaves across lifecycle interruptions

`AppLifecycleParticipantBoundary` (PR-53) declared lifecycle → participant-state effects
independently of posture.  `ParticipantPostureLifecycleBoundary` extends this with
**posture-specific impact** when Android is in `JOIN_RUNTIME` posture.

### Posture Impact Classification

| Lifecycle Event | Posture Impact | JOIN_RUNTIME Recovery Expectation | V2 Loss Policy |
|---|---|---|---|
| `FOREGROUND` | `POSTURE_NEUTRAL` | `RESUME_ELIGIBLE` | `WAIT_FOR_RECONNECT` |
| `BACKGROUND` | `POSTURE_NEUTRAL` | `RESUME_ELIGIBLE` | `WAIT_FOR_RECONNECT` |
| `CONFIGURATION_CHANGE` | `POSTURE_NEUTRAL` | `RESUME_ELIGIBLE` | `WAIT_FOR_RECONNECT` |
| `BACKGROUND_KILL` | **`POSTURE_AMPLIFIED`** | `NEW_ERA_REQUIRED` | `REDIRECT_SUBTASKS` |
| `MEMORY_PRESSURE` | **`POSTURE_AMPLIFIED`** | `TIMEOUT_DEPENDENT` | `REBALANCE_FORMATION` |
| `PROCESS_RECREATION` | **`POSTURE_AMPLIFIED`** | `NEW_ERA_REQUIRED` | `ABORT_AND_RETRY` |

### Why POSTURE_AMPLIFIED Events Require Stronger V2 Action

**BACKGROUND_KILL in JOIN_RUNTIME posture**:
An active distributed execution participant has vanished without completing its subtasks.
V2 MUST redirect any in-progress subtasks assigned to this Android participant immediately —
it must NOT wait for reconnect.  When Android reconnects, it starts a NEW participation era.

**MEMORY_PRESSURE in JOIN_RUNTIME posture**:
Android MUST downgrade health to `ParticipantHealthState.DEGRADED` and emit
`ReconciliationSignal.PARTICIPANT_STATE` immediately on critical memory pressure events.
V2 SHOULD start rebalancing the formation to reduce the load on this participant.

**PROCESS_RECREATION in JOIN_RUNTIME posture**:
All distributed execution state held by the Android participant is lost.  The task may need
to be aborted and retried with V2 applying its own task-level fallback policy.

### Android's Role in Posture-Amplified Events

Android MUST NOT autonomously re-assign itself to a JOIN_RUNTIME posture after reconnecting
following a posture-amplified event.  Posture re-authorization belongs to V2.

### Query Helpers

```kotlin
// Events that require V2 to apply stronger JOIN_RUNTIME-specific policy
ParticipantPostureLifecycleBoundary.postureAmplifiedEvents
// → [BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION]

// Events where JOIN_RUNTIME posture has no additional consequence
ParticipantPostureLifecycleBoundary.postureNeutralEvents
// → [FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE]

// Events where V2 must start a new participation era (no resume possible)
ParticipantPostureLifecycleBoundary.newEraRequiredEvents
// → [BACKGROUND_KILL, PROCESS_RECREATION]
```

---

## 2. Hybrid Runtime Continuity

**Surface: `HybridRuntimeContinuityContract`** (PR-5Android)

### AC3: How Android hybrid participant behavior remains coherent across reconnect/restart/lifecycle

`HybridParticipantCapabilityBoundary` (PR-53) declared capability maturity.
`HybridRuntimeContinuityContract` extends this by declaring what happens to each
capability's execution context **across lifecycle disruptions**.

### Hybrid Capability Continuity Tiers

| Continuity Tier | Description | Capabilities |
|---|---|---|
| `STATELESS` | No execution context held; same response to every invocation | `HYBRID_EXECUTE`, `RAG_QUERY`, `CODE_EXECUTE` |
| `SESSION_SCOPED` | Context valid for WS connection lifetime; re-reported on reconnect | `BARRIER_PARTICIPATION`, `FORMATION_REBALANCE` |
| `INVOCATION_SCOPED` | Context valid per invocation only; lost on disconnect or kill | `STAGED_MESH_EXECUTION` |

### Continuity Across Disruptions

| Capability | Tier | Survives WS Reconnect | Survives Process Kill | Post-Disruption Behavior |
|---|---|---|---|---|
| `STAGED_MESH_EXECUTION` | `INVOCATION_SCOPED` | ❌ | ❌ | `AWAIT_V2_REINVOCATION` |
| `HYBRID_EXECUTE` | `STATELESS` | ✅ | ❌ | `REANNOUNCE_CAPABILITY` |
| `RAG_QUERY` | `STATELESS` | ✅ | ❌ | `REANNOUNCE_CAPABILITY` |
| `CODE_EXECUTE` | `STATELESS` | ✅ | ❌ | `REANNOUNCE_CAPABILITY` |
| `BARRIER_PARTICIPATION` | `SESSION_SCOPED` | ✅ | ❌ | `REANNOUNCE_CAPABILITY` |
| `FORMATION_REBALANCE` | `SESSION_SCOPED` | ✅ | ❌ | `REANNOUNCE_CAPABILITY` |

**Key invariant**: No hybrid capability survives a process kill.  All capabilities require
re-announcement in the new participation era via `AndroidParticipantRuntimeTruth`.

### V2 Expected Actions After Disruption

- **After WS reconnect**: `STAGED_MESH_EXECUTION` context is lost — V2 must re-dispatch the
  subtask if needed.  Stateless and session-scoped capabilities re-report via reconnect signals.
- **After process kill**: All capabilities require re-announcement.  V2 reads capability maturity
  from the fresh `DeviceConnected` + `publishRuntimeTruthSnapshot` path.

### Android Constraints

- Android MUST NOT re-transmit a prior `StagedMeshParticipationResult` after reconnect.
- Android MUST NOT autonomously transition a capability from `CONTRACT_FIRST` to `FULLY_WIRED`
  maturity — maturity is a static declaration, not a recovery outcome.
- Android MUST re-announce all capability maturity tags after a process kill so V2 can update
  its routing table for the new participation era.

---

## 3. Participant Attachment Transition Semantics

**Surface: `ParticipantAttachmentTransitionSemantics`** (PR-5Android)

### AC2: What local runtime/session/task truth is preserved, resumed, reset, or reattached

### Attachment State Lifecycle

```
UNATTACHED ──(initial_attach)──→ ATTACHED
ATTACHED   ──(clean_detach)────→ UNATTACHED
ATTACHED   ──(disrupted_detach)→ REATTACHING
REATTACHING──(reconnect_recovery_attach)──→ ATTACHED
REATTACHING──(reconnect_failure_detach)───→ UNATTACHED
UNATTACHED ──(new_era_attach)──→ ATTACHED
```

### Attachment Transition Table

| Transition ID | From → To | V2 Event | Durable Session Effect | Recovery Semantics |
|---|---|---|---|---|
| `initial_attach` | UNATTACHED → ATTACHED | `DeviceConnected` | SESSION_PRESERVED | `FRESH_ATTACH` |
| `clean_detach` | ATTACHED → UNATTACHED | `DeviceDisconnected` | SESSION_RESET | `CLEAN_DETACH` |
| `disrupted_detach` | ATTACHED → REATTACHING | *(none — reconnect in progress)* | SESSION_PRESERVED | `DISRUPTED_DETACH` |
| `reconnect_recovery_attach` | REATTACHING → ATTACHED | `DeviceReconnected` | EPOCH_ADVANCED | `RECONNECT_RECOVERY` |
| `reconnect_failure_detach` | REATTACHING → UNATTACHED | `DeviceDisconnected` | SESSION_RESET | `DISRUPTED_DETACH` |
| `new_era_attach` | UNATTACHED → ATTACHED | `DeviceConnected` | SESSION_PRESERVED | `NEW_ERA_ATTACH` |

### Design Notes

- **`disrupted_detach` emits NO V2 event** — Android transitions to REATTACHING (reconnect
  in progress) rather than emitting DeviceDisconnected immediately.  V2 sees the outcome
  only after the reconnect succeeds (DeviceReconnected) or fails (DeviceDisconnected).

- **`reconnect_recovery_attach` increments epoch** — `DurableSessionContinuityRecord.sessionContinuityEpoch`
  is advanced on successful reconnect.  V2 uses the epoch to correlate the returning participant
  to its prior session.

- **`new_era_attach` emits DeviceConnected (not DeviceReconnected)** — After process kill,
  the attachment identity is fresh.  V2 MUST treat this as a new registration, not a resume.

### AC4: Bounded under V2 canonical orchestration authority

Android never self-authorizes attachment state transitions.  The attachment lifecycle is
driven by `RuntimeController` in response to WS connection events, explicit user actions,
and lifecycle events.  All recovery decisions (session resumption, task retry) belong to V2.

---

## 4. Intentional Out-of-Scope Limitations

The following remain explicitly out of scope for PR-5Android:

| Limitation | Reason |
|---|---|
| Autonomous posture re-authorization after reconnect | V2 is the canonical posture authority; Android only re-reports state |
| Persisted hybrid execution state across process death | V2 is the canonical task state authority |
| Automatic JOIN_RUNTIME posture restoration after new_era_attach | Requires explicit V2 re-authorization |
| Barrier/merge coordination authority | Android is not a barrier authority; V2 owns this |
| Full `HYBRID_EXECUTE` executor | Deferred from PR-53; not changed in PR-5Android |

---

## 5. Test Evidence

All three surfaces have comprehensive unit test coverage in
`Pr5AndroidParticipantLifecycleHardeningTest`.

### Test coverage summary

| Surface | Test Coverage |
|---|---|
| `ParticipantPostureLifecycleBoundary` | Enum wire values, boundary registry (6/6 events), POSTURE_AMPLIFIED/NEUTRAL classifications, query helpers, wire-key constants |
| `HybridRuntimeContinuityContract` | Enum wire values, continuity registry (6/6 capabilities), continuity tiers per capability, process-kill invariant, query helpers, wire-key constants |
| `ParticipantAttachmentTransitionSemantics` | Enum wire values, transition registry (6 entries), V2 event per transition, durable session effect per transition, query helpers, wire-key constants |
| Cross-surface coherence | Android-as-participant constraint, new-era event ↔ attachment transition consistency, process-kill re-announcement completeness |

---

## 6. Related Surfaces

| Surface | Purpose | PR |
|---|---|---|
| `AppLifecycleParticipantBoundary` | Android app lifecycle → participant state mapping | PR-53 |
| `HybridParticipantCapabilityBoundary` | Hybrid capability maturity (FULLY_WIRED vs CONTRACT_FIRST) | PR-53 |
| `ParticipantRecoveryReadinessSnapshot` | Durability/V2-resync registry for local state fields | PR-53 |
| `AndroidAppLifecycleTransition` | App-level lifecycle event model | PR-60 |
| `AndroidLifecycleRecoveryContract` | Recovery boundary documentation constants | PR-60 |
| `ContinuityRecoveryContext` | Interruption reason vocabulary and token boundary | PR-F |
| `DurableSessionContinuityRecord` | Live durable session era record (in-memory) | PR-1 |
| `ReconnectRecoveryState` | Observable WS reconnect phase | PR-33 |
| `V2MultiDeviceLifecycleEvent` | V2-consumable device lifecycle events | PR-43/PR-44 |
| `ReconciliationSignal` | Android→V2 reconciliation signal stream | PR-52 |
| `AndroidParticipantRuntimeTruth` | Consolidated participant truth snapshot | PR-51 |
| **`ParticipantPostureLifecycleBoundary`** | **Posture-aware lifecycle impact and V2 loss policy** | **PR-5Android** |
| **`HybridRuntimeContinuityContract`** | **Hybrid runtime continuity across lifecycle disruptions** | **PR-5Android** |
| **`ParticipantAttachmentTransitionSemantics`** | **Attachment lifecycle transitions with V2 event expectations** | **PR-5Android** |
