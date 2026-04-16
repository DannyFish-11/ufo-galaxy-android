# Protocol Consistency Rules

**PR-4 — Canonical Protocol Consistency Rules**

**Scope:** `DannyFish-11/ufo-galaxy-android`

This document defines the canonical consistency rules for the most load-bearing shared
protocol surfaces across both repositories.  It establishes which values on each surface
are canonical, which are transitional aliases (explicitly tolerated for compatibility), and
what constitutes a drift candidate requiring cross-repo coordination before acceptance.

The companion Kotlin implementation is:
`app/src/main/java/com/ufo/galaxy/protocol/UgcpProtocolConsistencyRules.kt`

---

## 1. Purpose and problem statement

The existing governance framework includes shared schema vocabulary, truth-event modeling,
transfer/control profiles, delegated execution lifecycle semantics, and Android-side
alignment logic.  However, cross-repository protocol alignment still depends too heavily on:

- Incremental convergence without explicit rules
- Transitional compatibility families without formal boundaries
- Alias normalization without a registered enumeration of tolerated aliases
- Documentation discipline rather than reviewable consistency rules

This creates risk of:

- **Enum drift** — new or renamed status or state values introduced without cross-repo coordination.
- **Terminal-state mismatches** — one side treating a value as terminal while the other does not.
- **Session identifier drift** — a new session carrier introduced without mapping to a canonical session family.
- **Descriptor field inconsistencies** — capability or runtime-profile descriptor fields diverging between Android and center vocabularies.
- **Alias-based semantic erosion** — transitional aliases gradually becoming de-facto canonical, obscuring the convergence path.
- **Silent divergence** — changes in one repository triggering no signal that the corresponding surface in the other repository needs updating.

The document and its companion Kotlin object stop uncontrolled drift on the ten most
important shared surfaces, and make convergence reviewable.

---

## 2. How to read this document

### 2.1 Surface classification

Each surface carries one of two class labels:

| Label | Meaning |
|-------|---------|
| **CANONICAL** | The surface is authoritative and stable.  Values on this surface are the agreed cross-repository representation.  Changes require explicit cross-repo coordination. |
| **TRANSITIONAL_COMPATIBILITY** | The surface is retained for compatibility.  Values or naming are not yet unified with the center canonical vocabulary.  The surface has an explicit convergence path toward CANONICAL. |

### 2.2 Value classification

Within a surface, each value carries one of three status labels:

| Status | Meaning |
|--------|---------|
| **CANONICAL** | The value is an authoritative cross-repo agreed value on this surface.  It must not be renamed, removed, or replaced without explicit cross-repo coordination. |
| **TRANSITIONAL_ALIAS** | The value is currently tolerated for compatibility.  It has a stated canonical target and a stated reason for the transitional tolerance.  It must not be added as a new canonical value; convergence toward the canonical target is the explicit path. |
| **DRIFT_CANDIDATE** | The value is not in the canonical set and not a known alias.  It should be treated as a potential cross-repo inconsistency requiring review. |

---

## 3. Consistency rules by surface

### 3.1 Terminal / lifecycle status vocabulary
**Surface class:** CANONICAL

The cross-repository agreed terminal status strings used in result payloads, lifecycle status
fields, and normalization maps.

| Value | Status | Notes |
|-------|--------|-------|
| `success` | CANONICAL | Primary success terminal state |
| `error` | CANONICAL | Primary error/failure terminal state |
| `cancelled` | CANONICAL | Execution was cancelled before completion |
| `timeout` | CANONICAL | Execution exceeded time limit |
| `rejected` | CANONICAL | Execution was rejected before it began |
| `partial` | CANONICAL | Partial success (coordination surface) |
| `disabled` | CANONICAL | Feature or capability was disabled |
| `completed` | TRANSITIONAL_ALIAS → `success` | Legacy result status; must normalize via `lifecycleStatusNormalizations` |
| `failed` | TRANSITIONAL_ALIAS → `error` | Legacy failure status; must normalize via `lifecycleStatusNormalizations` |
| `failure` | TRANSITIONAL_ALIAS → `error` | Legacy failure status variant; must normalize via `lifecycleStatusNormalizations` |
| `no_op` | TRANSITIONAL_ALIAS → `disabled` | Legacy no-op status; must normalize via `lifecycleStatusNormalizations` |

**Normalization boundary:** `UgcpSharedSchemaAlignment.normalizeLifecycleStatus()` — transitional aliases MUST normalize before entering canonical routing.

**Drift rule:** Any status value not in the table above is a drift candidate and requires
explicit cross-repo coordination before use.

---

### 3.2 Delegated execution result kind
**Surface class:** CANONICAL

The terminal outcome discriminators carried in `DelegatedExecutionSignal` RESULT signals.

| Value | Status | Notes |
|-------|--------|-------|
| `completed` | CANONICAL | Delegated unit completed successfully |
| `failed` | CANONICAL | Delegated unit failed during execution |
| `timeout` | CANONICAL | Execution timed out |
| `cancelled` | CANONICAL | Execution was cancelled |
| `rejected` | CANONICAL | Unit rejected before execution began |

**Important surface distinction:** On this surface, `completed` is **canonical** (not a
transitional alias).  Do not normalize `completed` to `success` on the delegated result
surface — the distinction matters for host-side session-truth reconciliation.  This is a
deliberate difference from the general lifecycle status vocabulary (§ 3.1).

**Authority:** Carried by `DelegatedExecutionSignal.ResultKind.wireValue`.

---

### 3.3 Attached session state
**Surface class:** CANONICAL

The lifecycle state values for an Android attached runtime session.

| Value | Status | Notes |
|-------|--------|-------|
| `attached` | CANONICAL | Actively participating as a persistent runtime surface |
| `detaching` | CANONICAL | Graceful shutdown in progress; no new tasks |
| `detached` | CANONICAL | No longer participating; session terminated |

**Authority:** `AttachedRuntimeSession.State.wireValue` and `KEY_STATE` metadata key.

---

### 3.4 Attached session detach cause
**Surface class:** CANONICAL

The cause values for an attached runtime session detachment.

| Value | Status | Notes |
|-------|--------|-------|
| `explicit_detach` | CANONICAL | Operator or user explicitly detached |
| `disconnect` | CANONICAL | Underlying WebSocket connection lost |
| `disable` | CANONICAL | Cross-device participation administratively disabled |
| `invalidation` | CANONICAL | Session invalidated (auth expired, identity change, etc.) |

**Authority:** `AttachedRuntimeSession.DetachCause.wireValue` and `KEY_DETACH_CAUSE` metadata key.

---

### 3.5 Reconnect recovery state
**Surface class:** CANONICAL

The reconnect recovery lifecycle state values for the Android runtime.

| Value | Status | Notes |
|-------|--------|-------|
| `idle` | CANONICAL | No recovery in progress; runtime connected or cleanly stopped |
| `recovering` | CANONICAL | WS disconnected; automatic reconnect in progress |
| `recovered` | CANONICAL | Reconnect succeeded; attached session resumed |
| `failed` | CANONICAL | All reconnect attempts exhausted; user action required |

**Authority:** `ReconnectRecoveryState.wireValue`, observable via
`RuntimeController.reconnectRecoveryState` StateFlow and logged under `TAG_RECONNECT_RECOVERY`.

---

### 3.6 Session identifier carrier
**Surface class:** TRANSITIONAL_COMPATIBILITY

Android session identifier carrier fields and their canonical session family mapping.

| Android carrier | Status | Canonical session family term | Notes |
|-----------------|--------|------------------------------|-------|
| `runtime_session_id` | CANONICAL | `runtime_session_id` | Direct match; no naming convergence needed |
| `attached_session_id` | CANONICAL | `attached_runtime_session_id` | Direct match |
| `signal_id` | CANONICAL | `execution_instance_id` | Direct match |
| `control_session_id` | CANONICAL | `control_session_id` | Canonical cross-repo term; Android currently uses `session_id` wire alias |
| `mesh_session_id` | CANONICAL | `mesh_session_id` | Canonical cross-repo term; Android currently uses `mesh_id` wire alias |
| `session_id` | TRANSITIONAL_ALIAS → `control_session_id` | `control_session_id` | Android wire carrier; naming convergence deferred per `CanonicalSessionAxis § 3.1` |
| `mesh_id` | TRANSITIONAL_ALIAS → `mesh_session_id` | `mesh_session_id` | Android wire carrier; naming convergence deferred per `CanonicalSessionAxis § 3.1` |

**Drift rule:** New session identifier carriers MUST be added to `CanonicalSessionAxis` and
mapped to a `CanonicalSessionFamily` entry before being introduced in wire payloads.  Carrier
introduction without this mapping is the primary mechanism of session identifier drift.

**Convergence path:** `session_id` → `control_session_id` and `mesh_id` → `mesh_session_id`
are both deferred naming convergences tracked in `CanonicalSessionAxis`.

---

### 3.7 Runtime profile descriptor names
**Surface class:** CANONICAL

The profile identity strings used to identify Android runtime profiles.

| Value | Status | Notes |
|-------|--------|-------|
| `ugcp.runtime_ws_profile.android` | CANONICAL | Android runtime WS profile identity |
| `ugcp.control_transfer_profile.android` | CANONICAL | Android control transfer profile identity |
| `ugcp.coordination_profile.android` | CANONICAL | Android coordination profile identity |
| `ugcp.truth_event_model.android` | CANONICAL | Android truth-event model identity |
| `ugcp.conformance_surface.android` | CANONICAL | Android conformance surface identity |

**Authority:** Declared in `UgcpSharedSchemaAlignment` profile name constants.

---

### 3.8 Capability and readiness descriptor fields
**Surface class:** CANONICAL

Field names reported in `capability_report` payloads and consumed by center scheduling/selection.

| Field | Status | Notes |
|-------|--------|-------|
| `source_runtime_posture` | CANONICAL | Participation posture field; values: `control_only` / `join_runtime` |
| `model_ready` | CANONICAL | Model inference capability available |
| `accessibility_ready` | CANONICAL | Accessibility execution capability available |
| `overlay_ready` | CANONICAL | Overlay rendering capability available |
| `degraded_mode` | CANONICAL | Device operating in degraded capability state |

**Authority:** Field names are protocol-facing and consumed by center-side scheduling.
Do not rename or add fields without coordinating with center capability consumers.

---

### 3.9 Truth-event payload identifier fields
**Surface class:** CANONICAL

Identifier fields that must be present in truth-event payload structures for cross-repo
correlation, idempotency, and session-truth reconciliation.

| Field | Status | Scope |
|-------|--------|-------|
| `task_id` | CANONICAL | Required on all result payloads |
| `trace_id` | CANONICAL | Required on all result payloads |
| `runtime_session_id` | CANONICAL | Required on all result payloads |
| `signal_id` | CANONICAL | Required on delegated execution signals |
| `emission_seq` | CANONICAL | Required on delegated execution signals |
| `attached_session_id` | CANONICAL | Required on delegated execution signals |

---

### 3.10 Transfer lifecycle vocabulary
**Surface class:** CANONICAL

The canonical control-transfer lifecycle event vocabulary.

| Value | Status | Notes |
|-------|--------|-------|
| `transfer_accept` | CANONICAL | Takeover accepted by target |
| `transfer_reject` | CANONICAL | Takeover rejected by target |
| `transfer_cancel` | CANONICAL | Delegated unit cancelled by runtime |
| `transfer_expire` | CANONICAL | Delegated unit timed out |
| `transfer_adopt` | CANONICAL | Continuation handoff token present |
| `transfer_resume` | CANONICAL | Handoff continuation resumes prior work |

**Authority:** Android events map to these terms via
`UgcpSharedSchemaAlignment.transferEventAlignments`.  The transfer vocabulary is canonical
(not transitional) and applies to both the takeover/response path and the delegated
execution lifecycle signal path.

---

## 4. Explicit transitional compatibility allowances

The following transitional aliases are currently in effect.  They must not be treated as
permanent divergence.  Each has a stated reason and an explicit canonical convergence target.

| Surface | Alias value | Canonical target | Reason |
|---------|-------------|-----------------|--------|
| TERMINAL_STATE_VOCABULARY | `completed` | `success` | Legacy result status; normalization enforced by `lifecycleStatusNormalizations` |
| TERMINAL_STATE_VOCABULARY | `failed` | `error` | Legacy failure status; normalization enforced by `lifecycleStatusNormalizations` |
| TERMINAL_STATE_VOCABULARY | `failure` | `error` | Legacy failure status variant; normalization enforced by `lifecycleStatusNormalizations` |
| TERMINAL_STATE_VOCABULARY | `no_op` | `disabled` | Legacy no-op status; normalization enforced by `lifecycleStatusNormalizations` |
| SESSION_IDENTIFIER_CARRIER | `session_id` | `control_session_id` | Android wire carrier; naming convergence deferred per `CanonicalSessionAxis § 3.1` |
| SESSION_IDENTIFIER_CARRIER | `mesh_id` | `mesh_session_id` | Android wire carrier; naming convergence deferred per `CanonicalSessionAxis § 3.1` |

Total transitional aliases in effect: **6** across 2 surfaces.

---

## 5. What constitutes a consistency violation

A **consistency violation** occurs when:

1. A value is used on a canonical surface that is not in the canonical values set and not a
   registered transitional alias.  This is a **DRIFT_CANDIDATE**.

2. A new session identifier carrier is introduced in a wire payload without being registered
   in `CanonicalSessionAxis` and `UgcpProtocolConsistencyRules.sessionIdentifierCarrierRule`.

3. A transitional alias is used as a canonical name in documentation, code, or tests without
   referencing its canonical target.

4. A capability or readiness field is added to `capability_report` without being added to
   `UgcpProtocolConsistencyRules.capabilityReadinessDescriptorRule`.

5. A new runtime profile descriptor name is introduced without being added to
   `UgcpProtocolConsistencyRules.runtimeProfileDescriptorRule`.

---

## 6. Convergence path for transitional surfaces

### SESSION_IDENTIFIER_CARRIER convergence

1. `session_id` → `control_session_id`: Requires center-side wire contract update so inbound
   `AipMessage` envelopes carry `control_session_id` as the primary field.  Android can then
   rename its wire usage.  Blocked on cross-repo coordination.

2. `mesh_id` → `mesh_session_id`: Requires MeshJoinPayload / MeshLeavePayload / MeshResultPayload
   to adopt `mesh_session_id` as the primary field name.  Blocked on cross-repo coordination.

### TERMINAL_STATE_VOCABULARY transitional aliases

Legacy alias normalization is already enforced at ingress by `UgcpSharedSchemaAlignment.normalizeLifecycleStatus()`.
Retirement of the aliases (phase 4: reject after explicit rollout) is blocked on confirmation
that all center-side result producers have migrated to canonical status values only.

---

## 7. Implementation reference

| Concept | Implementation |
|---------|---------------|
| Consistency rule registry | `UgcpProtocolConsistencyRules.allRules` |
| Canonical surface set | `UgcpProtocolConsistencyRules.canonicalSurfaces` |
| Transitional surface set | `UgcpProtocolConsistencyRules.transitionalSurfaces` |
| Check a single value | `UgcpProtocolConsistencyRules.checkValue(surface, value)` |
| All transitional aliases | `UgcpProtocolConsistencyRules.allTransitionalAliases()` |
| Is a value tolerated? | `UgcpProtocolConsistencyRules.isTolerated(surface, value)` |
| Lifecycle status normalization | `UgcpSharedSchemaAlignment.normalizeLifecycleStatus()` |
| Session carrier classification | `CanonicalSessionAxis.entryForCarrier(carrier)` |

---

## 8. Non-goals

- This document does **not** remove any existing compatibility pathway.
- This document does **not** change any wire contracts, field names, or runtime behavior.
- Naming convergence for transitional aliases is explicitly deferred to follow-up phases.
- Full cross-repository schema/name unification is deferred to later Runtime WS Profile and
  control-transfer profile phases.
- This document does **not** define enforcement mechanisms; `checkValue()` produces
  `REPORT_ONLY_DIVERGENCE`-style results suitable for later CI-based consistency checks.
