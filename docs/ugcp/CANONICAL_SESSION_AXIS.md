# Canonical Session Axis

**PR-3 — Cross-Repository Canonical Session Axis Definition**

**Scope:** `DannyFish-11/ufo-galaxy-android`

This document establishes the canonical session axis for the Android runtime-profile repository.
It defines the role and boundaries of each session family, which identifiers are canonical, which
are transitional aliases or contextual aliases, and how Android-side session structures map to
main-repository session structures.

---

## 1. Purpose

The Android codebase contains multiple overlapping session-related concepts:
- Control session
- Runtime session
- Attached runtime session
- Delegation / transfer session
- Conversation / history session
- Mesh session
- **Durable runtime session** (PR-1)

These concepts are meaningful but their cross-layer and cross-repository relationships were not
previously explicit. This document formalizes the **canonical session axis** — a single,
authoritative model of session families and identifier roles — so that:

- Reconnect and recovery flows can reason about session identity precisely.
- Runtime continuity can be tracked across the attach/detach/reconnect lifecycle.
- Transfer and delegation semantics can be unambiguously scoped.
- Projection and runtime-truth assembly can identify the correct session carrier.
- Cross-repository consumers can map Android session structures to canonical terms.
- **Durable session continuity** (PR-1) lets the center-side system correlate multiple successive
  `attached_runtime_session_id` values (from the same activation era) as a single durable participant.

The canonical session axis is **additive and compatibility-safe**: it does not change any runtime
behavior, wire contracts, or existing identifier values.

---

## 2. Session families

### 2.1 Family definitions

| Session family | Canonical term | Android wire alias | Continuity behavior |
|----------------|---------------|--------------------|---------------------|
| **Control session** | `control_session_id` | `session_id` | Stable across reconnect (center-maintained) |
| **Runtime session** | `runtime_session_id` | (none) | Refreshed on reconnect |
| **Attached runtime session** | `attached_runtime_session_id` | (none) | Stable across reconnect (Android-maintained) |
| **Delegation/transfer session** | `transfer_session_context` | (none) | Transfer-scoped (ACK→PROGRESS→RESULT) |
| **Conversation session** | `conversation_session_id` | (none) | Conversation-scoped (local loop timeline) |
| **Mesh session** | `mesh_session_id` | `mesh_id` | Mesh-scoped (MeshJoin→MeshLeave/Result) |
| **Durable runtime session** | `durable_session_id` | (none) | Durable across activation era (PR-1) |

### 2.2 Family boundaries

**Control session** (`control_session_id` / `session_id`)
- Owned by the center/orchestrator; Android echoes it from inbound envelopes.
- Carried on: `AipMessage.session_id`, `TaskSubmitPayload.session_id`, takeover `session_id`.
- Transitional: Android uses `session_id` as the wire carrier; `control_session_id` is the canonical
  term. Naming convergence is follow-up work.
- Survives: WS reconnect. Does not survive: invalidation.

**Runtime session** (`runtime_session_id`)
- Owned by `RuntimeController`; regenerated for each new WS connection cycle.
- Carried on: `AipMessage.runtime_session_id`, takeover/handoff envelopes, `AttachedRuntimeHostSessionSnapshot.runtimeSessionId`.
- Canonical match: Android wire name `runtime_session_id` is identical to the canonical term.
- Does not survive: WS reconnect (new runtime_session_id on each attach). Does not survive: transfer, invalidation.

**Attached runtime session** (`attached_runtime_session_id`)
- Owned by `RuntimeController`; created once per cross-device activation, persists across
  multiple delegated task executions and short WS disconnects.
- Carried on: `AttachedRuntimeSession.sessionId`, `AttachedRuntimeHostSessionSnapshot.sessionId`,
  `delegated_execution_signal.attached_session_id`.
- Canonical match: all carriers directly realize the canonical term.
- Survives: WS reconnect (session reopened, new `runtime_session_id` but same `sessionId`).
  Does not survive: EXPLICIT_DETACH, DISABLE, INVALIDATION, or a non-recovering DISCONNECT.

**Delegation/transfer session** (`transfer_session_context`)
- Scoped to one delegated execution lifecycle (ACK→PROGRESS→RESULT).
- Primary carrier: `DelegatedExecutionSignal.attachedSessionId`.
- The `takeover_request.session_id` field is a contextual alias (carries control-session scope
  in the takeover envelope context, but transfer lifecycle is scoped to the attached-session + signal chain).
- Does not survive: WS reconnect or transfer boundaries.

**Conversation session** (`conversation_session_id`)
- Local conversation/history timeline identity; independent of cross-device attachment state.
- Carried on: `LocalLoopTrace.sessionId`, `SessionHistorySummary.sessionId`.
- Canonical match: session identity is per local loop trace.
- Survives: WS reconnect and invalidation (no dependency on cross-device state). Does not survive: transfer boundaries.

**Mesh session** (`mesh_session_id` / `mesh_id`)
- Scoped to one staged-mesh coordination cycle (MeshJoin→MeshLeave/MeshResult).
- Carried on: `MeshJoinPayload.mesh_id`, `MeshLeavePayload.mesh_id`, `MeshResultPayload.mesh_id`, `StagedMeshParticipationResult.meshId`.
- Transitional: Android wire name is `mesh_id`; canonical term is `mesh_session_id`. Naming convergence is follow-up work.
- Does not survive: WS reconnect, transfer, or invalidation.

**Durable runtime session** (`durable_session_id`) — PR-1
- Owned by `RuntimeController`; created once per activation era, spans multiple attached-session lifetimes.
- Carried on: `DurableSessionContinuityRecord.durableSessionId`,
  `AttachedRuntimeHostSessionSnapshot.durableSessionId`.
- Canonical match: Android field name `durable_session_id` is the canonical term; no wire alias.
- The `sessionContinuityEpoch` counter increments monotonically with each transparent reconnect
  (epoch 0 = initial attach, epoch N = Nth reconnect), allowing the center to distinguish
  "same era, reconnected" from "new era."
- Survives: WS reconnects (epoch increments; `durable_session_id` stays constant).
  Does not survive: `stop()`, `invalidateSession()`.

---

## 3. Identifier role classification

Each Android carrier is classified with one of three identifier roles:

| Role | Label | Meaning |
|------|-------|---------|
| **Canonical** | `canonical` | The Android field directly realizes the canonical cross-repo term. No naming convergence work needed. |
| **Transitional alias** | `transitional_alias` | The Android field uses a local alias or wire-level name that differs from the canonical term. Semantic equivalence is frozen; naming convergence is deferred follow-up work. |
| **Contextual alias** | `contextual_alias` | The Android field serves multiple session families depending on context (e.g. `session_id` in takeover envelopes). Semantic disambiguation requires inspecting the surrounding message type. |

### 3.1 Transitional aliases (deferred naming convergence)

| Android carrier | Android wire name | Canonical term |
|-----------------|------------------|----------------|
| `AipMessage.session_id` | `session_id` | `control_session_id` |
| `TaskSubmitPayload.session_id` | `session_id` | `control_session_id` |
| `InputRouter.conversationSessionId` | `session_id` | `control_session_id` |
| `MeshJoinPayload.mesh_id` | `mesh_id` | `mesh_session_id` |
| `MeshLeavePayload.mesh_id` | `mesh_id` | `mesh_session_id` |
| `MeshResultPayload.mesh_id` | `mesh_id` | `mesh_session_id` |
| `StagedMeshParticipationResult.meshId` | `meshId` | `mesh_session_id` |

### 3.2 Contextual aliases

| Android carrier | Context | Session family |
|-----------------|---------|----------------|
| `takeover_request.session_id` | Takeover/transfer envelope | `DELEGATION_TRANSFER_SESSION` (transfer lifecycle scope) |

### 3.3 Canonical carriers

All carriers for `ATTACHED_RUNTIME_SESSION` and `RUNTIME_SESSION` are canonical matches.
`CONVERSATION_SESSION` carriers are also canonical.

---

## 4. Session continuity model

### 4.1 Per-family continuity table

| Family | Behavior | Survive reconnect? | Survive transfer? | Survive invalidation? |
|--------|----------|--------------------|-------------------|-----------------------|
| CONTROL_SESSION | STABLE_ACROSS_RECONNECT | ✓ | ✓ | ✗ |
| RUNTIME_SESSION | REFRESHED_ON_RECONNECT | ✗ | ✗ | ✗ |
| ATTACHED_RUNTIME_SESSION | STABLE_ACROSS_RECONNECT | ✓ | ✓ | ✗ |
| DELEGATION_TRANSFER_SESSION | TRANSFER_SCOPED | ✗ | ✓ | ✗ |
| CONVERSATION_SESSION | CONVERSATION_SCOPED | ✓ | ✗ | ✓ |
| MESH_SESSION | MESH_SCOPED | ✗ | ✗ | ✗ |
| DURABLE_RUNTIME_SESSION | DURABLE_ACROSS_ACTIVATION | ✓ | ✓ | ✗ |

### 4.2 Reconnect and recovery semantics

During a short WS disconnect / reconnect cycle:

1. `RUNTIME_SESSION`: `RuntimeController._currentRuntimeSessionId` is regenerated.  The host can
   use the new `runtime_session_id` to detect the reconnect event.
2. `ATTACHED_RUNTIME_SESSION`: `RuntimeController`'s permanent WS listener reopens the attached
   session (`AttachedRuntimeSession`) on reconnect.  The `sessionId` remains stable; only
   `runtimeSessionId` in the snapshot changes.
3. `DURABLE_RUNTIME_SESSION` (PR-1): `RuntimeController._durableSessionContinuityRecord` is
   preserved across reconnects; only `sessionContinuityEpoch` increments.  The host can use the
   stable `durable_session_id` to correlate multiple `attached_runtime_session_id` values from the
   same activation era, and the epoch to count reconnect events within that era.
4. `CONTROL_SESSION`: echoed from center-supplied inbound envelopes; not affected by WS reconnect.
5. `CONVERSATION_SESSION`: local; not affected by WS state.
6. `DELEGATION_TRANSFER_SESSION` / `MESH_SESSION`: not recoverable across WS disconnects.

The observable reconnect lifecycle is tracked by `ReconnectRecoveryState` (PR-33):
`IDLE → RECOVERING → RECOVERED | FAILED`.

### 4.3 Transfer and delegation semantics

A delegation/transfer session spans exactly one inbound delegated execution lifecycle:
- `ACK` signal → `PROGRESS*` signal(s) → `RESULT` signal.

The `DelegatedExecutionSignal.attachedSessionId` carries the attached-session scope through
this lifecycle.  The transfer session does not persist beyond the RESULT signal.

After a `TakeoverFallbackEvent` (FAILED / TIMEOUT / CANCELLED / DISCONNECT), the transfer
session is terminated.  The attached runtime session is not terminated by a single transfer
failure; it remains ATTACHED and can accept subsequent delegated tasks.

---

## 5. Android-to-main-repository session structure mapping

### 5.1 Session family cross-repo mapping

| Android type | Android field | Center canonical term | Cross-repo classification |
|-------------|--------------|----------------------|--------------------------|
| `AttachedRuntimeSession` | `sessionId` | `attached_runtime_session_id` | CANONICAL_MATCH |
| `AttachedRuntimeHostSessionSnapshot` | `sessionId` | `attached_runtime_session_id` | CANONICAL_MATCH |
| `AttachedRuntimeHostSessionSnapshot` | `runtimeSessionId` | `runtime_session_id` | CANONICAL_MATCH |
| `AttachedRuntimeHostSessionSnapshot` | `durableSessionId` | `durable_session_id` | CANONICAL_MATCH (PR-1) |
| `DelegatedExecutionSignal` | `attachedSessionId` | `transfer_session_context` / `attached_runtime_session_id` | CANONICAL_MATCH |
| `AipMessage` | `session_id` | `control_session_id` | TRANSITIONAL_ALIAS |
| `AipMessage` | `runtime_session_id` | `runtime_session_id` | CANONICAL_MATCH |
| `LocalLoopTrace` | `sessionId` | `conversation_session_id` | CANONICAL_MATCH |
| `MeshJoinPayload` | `mesh_id` | `mesh_session_id` | TRANSITIONAL_ALIAS |
| `DurableSessionContinuityRecord` | `durableSessionId` | `durable_session_id` | CANONICAL_MATCH (PR-1) |

### 5.2 Session identity alias properties

The following alias properties are defined on Android types to make the canonical session
layer explicit without changing the underlying field values:

| Type | Alias property | Points to | Layer |
|------|---------------|-----------|-------|
| `AttachedRuntimeSession` | `runtimeAttachmentSessionId` | `sessionId` | ATTACHED_RUNTIME_SESSION |
| `DelegatedExecutionSignal` | `delegationTransferSessionId` | `attachedSessionId` | DELEGATION_TRANSFER_SESSION |
| `LocalLoopTrace` | `conversationSessionId` | `sessionId` | CONVERSATION_SESSION |
| `SessionHistorySummary` | `conversationSessionId` | `sessionId` | CONVERSATION_SESSION |

The `DurableSessionContinuityRecord` does not use an alias property — its `durableSessionId`
field directly realizes the `durable_session_id` canonical term.

---

## 6. Session truth authority boundaries

Session truth authority on the Android side follows the same authority model defined in
`ANDROID_UGCP_CONSTITUTION.md` § 2.3:

| Layer | Android authority surface | Authority classification |
|-------|--------------------------|--------------------------|
| Attached session truth | `RuntimeController.attachedSession` + `hostSessionSnapshot` | Android-authoritative (non-truth-owning toward center) |
| Runtime session truth | `RuntimeController._currentRuntimeSessionId` | Android-authoritative (refreshed per connection) |
| **Durable session truth** (PR-1) | `RuntimeController.durableSessionContinuityRecord` | Android-authoritative (stable within activation era) |
| Reconnect recovery truth | `RuntimeController.reconnectRecoveryState` | Android-authoritative |
| Delegated execution truth | `DelegatedExecutionTracker` + `DelegatedExecutionSignal` | Android-authoritative for signal emission |
| Control session truth | Center-governed; echoed by Android | Center-authoritative |
| Participant/session registration truth | Center-governed; Android contributes via `AttachedRuntimeHostSessionSnapshot` | Center-authoritative |

---

## 7. Observability

The `GalaxyLogger.TAG_SESSION_AXIS` tag (`"GALAXY:SESSION:AXIS"`) is reserved for structured
log entries at session-axis boundary events:

- `attach` — A new `AttachedRuntimeSession` was opened.
- `detach` — An `AttachedRuntimeSession` was detached.
- `reconnect` — An attached session was reopened after a WS reconnect.
- `transfer_open` — A delegation/transfer session lifecycle started (ACK emitted).
- `transfer_close` — A delegation/transfer session lifecycle ended (RESULT emitted).

Required fields: `event`, `session_family` (canonical term value).

---

## 8. Implementation reference

| Concept | Android implementation |
|---------|----------------------|
| Session family registry | `CanonicalSessionAxis.carriers` |
| Carrier lookup | `CanonicalSessionAxis.entryForCarrier(carrier)` |
| Session continuity models | `CanonicalSessionAxis.continuityModels` |
| Transitional alias carriers | `CanonicalSessionAxis.transitionalAliases()` |
| Canonical carriers | `CanonicalSessionAxis.canonicalCarriers()` |
| Cross-repo term lookup | `CanonicalSessionAxis.crossRepoTermFor(carrier)` |
| Three-layer session split | `AndroidSessionLayerContracts` |
| Attached session runtime truth | `AttachedRuntimeSession` + `AttachedRuntimeHostSessionSnapshot` |
| Reconnect recovery model | `ReconnectRecoveryState` |
| **Durable session continuity** (PR-1) | `DurableSessionContinuityRecord` + `RuntimeController.durableSessionContinuityRecord` |
| Transfer signal model | `DelegatedExecutionSignal` |
| Observability tag | `GalaxyLogger.TAG_SESSION_AXIS` |

---

## 9. Non-goals

- This document does **not** transfer authority from `RuntimeController` or the center orchestrator.
- This document does **not** change any wire contracts, field names, or runtime behavior.
- Naming convergence for transitional aliases (`session_id` → `control_session_id`,
  `mesh_id` → `mesh_session_id`) is intentionally deferred to follow-up phases.
- Full cross-repository schema/name unification is deferred to later Runtime WS Profile and
  control-transfer profile phases.
