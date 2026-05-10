# Android Truth Publication Semantics

Android-owned documentation for the truth surfaces consumed by
`DannyFish-11/ufo-galaxy-realization-v2`.

This document is the reviewer-facing summary of what Android actually publishes as:

1. **canonical capability truth**
2. **canonical lifecycle/runtime truth**
3. **execution transition truth**

It exists to prevent future Android changes from reintroducing optimistic, inferred, or
ad hoc behavior when evidence is missing or stale.

---

## Scope

Android publishes truth to V2 on three primary surfaces:

| Surface | Purpose | Primary implementation |
|---|---|---|
| `capability_report` | Canonical capability declaration and dispatch-gate metadata | `GalaxyWebSocketClient.normalizedCapabilityMetadata()`, `AndroidCapabilityExportContract` |
| `device_state_snapshot` | Point-in-time lifecycle/runtime truth | `GalaxyConnectionService.sendDeviceStateSnapshot()` |
| `device_execution_event` | Execution-phase and transition truth | `GalaxyConnectionService` execution-event emitters, `AndroidExecutionLifecycleContract`, `ExecutionUplinkDiscipline` |

Android does **not** own V2's final canonical orchestration state. Android owns the participant-side
truth it can directly observe and publishes that truth with explicit evidence semantics so V2 does
not have to guess.

---

## 1. Canonical capability truth

### What Android emits

Android's canonical capability truth is the `capability_report` payload plus its required
`metadata` map.

The authoritative contract is `AndroidCapabilityExportContract`:

- top-level payload shape is fixed and versioned
- metadata includes required readiness, scheduling, and canonical gate fields
- `contract_schema_version` is emitted on every report
- execution-mode and eligibility fields are derived from `LocalExecutionModeGate`, not from
  ad hoc call-site guesses

`GalaxyWebSocketClient.normalizedCapabilityMetadata()` is the concrete publication path. It:

- normalizes Android-owned metadata to canonical wire values
- emits `contract_schema_version`
- derives `mode_state`, `mode_readiness_state`, `cross_device_eligibility`,
  `goal_execution_eligibility`, `parallel_execution_eligibility`, and
  `execution_mode_state` from `LocalExecutionModeGate`
- keeps degraded/cross-device semantics coherent with the runtime gate

### What Android does **not** claim

Android capability publication is intentionally conservative:

- Missing required metadata is a contract failure, not implicit readiness.
- Missing canonical gate metadata is a completeness gap, not proof that the gate is healthy.
- Android does not claim cross-device eligibility unless `LocalExecutionModeGate` says the
  participant can accept cross-device work.
- Android does not claim full capability from partial subsystem readiness.

### Regression intent

Future changes must not:

- add/remove/rename required capability fields without updating the contract and schema version
- derive capability truth from convenience heuristics outside `LocalExecutionModeGate`
- publish a capability surface that is complete-looking but semantically inconsistent

---

## 2. Canonical lifecycle/runtime truth

### What Android emits

Android's canonical point-in-time lifecycle/runtime truth is `device_state_snapshot`.

`GalaxyConnectionService.sendDeviceStateSnapshot()` builds the snapshot from live Android-owned
state sources, including runtime manager state, readiness, fallback state, queue depth, session
continuity, carrier state, mode state, capability authority state, and mesh/runtime context.

The snapshot also carries:

- `reported_state_semantic_class`
- `degraded_condition_class`
- `local_observation_basis`
- `evidence_presence_kind`

These fields tell V2 how to interpret the snapshot instead of forcing V2 to infer truth quality
from raw field combinations.

### What Android does **not** claim when data is missing

Android-side rule: **null means the backing Android evidence is unavailable, not silently healthy**.

Examples already encoded in payload comments and emitters:

- session/continuity identifiers stay `null` when no real session is active
- carrier/interaction hints stay `null` when the backing state is unavailable
- defensive-default fields remain `null` rather than receiving placeholder values
- startup and recovery snapshots are still emitted, but `evidence_presence_kind` communicates that
  they are not positive proof of healthy capability

This is the main anti-optimism invariant: Android may still publish a snapshot during startup,
recovery, or failure, but it must label that publication honestly.

---

## 3. Evidence semantics: positive, unknown, missing, stale, partial

`AndroidTruthPublicationSemanticsContract` defines the concrete evidence vocabulary consumed by V2.

The six evidence kinds are:

1. `positive_evidence`
2. `unknown`
3. `unavailable`
4. `delayed`
5. `partial`
6. `failed_observation`

Current implementation note:

- `device_state_snapshot` can currently classify to all six evidence kinds
- `device_execution_event` currently classifies to `positive_evidence`, `unknown`, `partial`,
  or `failed_observation`
- Android does **not** currently synthesize `unavailable` or `delayed` on the execution-event
  path

### Positive evidence

`positive_evidence` means Android has direct, current backing evidence for the publication.

- Snapshot classifier: running runtime + successful warmup (`warmup_result="ok"`) + no higher-priority
  failure/unavailable/unknown/partial/delayed condition
- Event classifier: `execution_progress`, `completed`, or `cancelled`

Only `positive_evidence` allows V2 to apply standard dispatch/capability scoring.

### Unknown state

`unknown` means Android cannot yet determine the state and V2 must not treat absence of proof as a
healthy/default state.

Examples:

- runtime starting or recovering
- `warmup_result="not_started"`
- key signals not yet available
- transitional execution events such as `execution_started` or `takeover_milestone`

### Missing data

Missing data is represented by **field absence / `null`**, not by an optimistic substitute.

Android therefore does **not** claim:

- that a missing readiness field means "ready"
- that a missing identity/session field means "same as before"
- that a missing capability field means "not applicable but still healthy"

When key evidence is missing, Android either leaves the field `null` or emits a publication with
`evidence_presence_kind="unknown"` instead of claiming a healthy state.

### Explicit unavailability

`unavailable` is stronger than unknown. It means Android knows the capability/resource is absent.

Snapshot example:

- `pending_first_download=true`

This is not "not yet observed"; it is a concrete declaration that the capability cannot currently
be used.

Current implementation note: this is a snapshot-side classification today, not an execution-event
classification.

### Stale state

`delayed` means evidence exists but may be stale.

Snapshot example:

- `offline_queue_depth > 0`

Event meaning:

- Android's current event classifier does not emit `delayed`

V2 should apply staleness handling, not standard fresh-state handling.

### Partial publication

`partial` means Android can confirm part of the truth surface but not all of it.

Examples:

- `planner_ready != grounding_ready`
- `fallback_transition` execution event

Android is explicitly saying "some confirmed capability exists" while refusing to claim that the
entire capability/runtime surface is healthy.

### Failed observation

`failed_observation` means Android attempted to observe/verify the state and the observation failed.

Examples:

- failed runtime manager state
- `warmup_result="failed"`
- `failed` or `stagnation_detected` execution events

This is not a lack of evidence; it is negative evidence.

---

## 4. Execution transitions and lifecycle truth

Android's canonical execution lifecycle comes from `AndroidExecutionLifecycleContract`.

### The important distinction

Android separates:

| Class | Meaning |
|---|---|
| Capability-only state | Device is idle/capable and may accept new work |
| Transitional state | Work is being accepted, activated, retried, interrupted, or timed out |
| Active execution state | Android currently owns an in-flight execution |
| Terminal state | Android observed the execution outcome and can publish terminal truth |

This separation exists specifically so V2 does not mistake "accepted", "activating", "degraded",
"interrupted", or "retrying" for either idle capability or terminal success/failure.

### What Android emits with execution events

`device_execution_event` carries:

- raw event `phase`
- `execution_lifecycle_phase`
- `previous_execution_lifecycle_phase`
- `lifecycle_transition_valid`
- `lifecycle_result_uplink_required`
- `lifecycle_state_uplink_required`
- `lifecycle_terminal_phase`
- `result_uplink_semantic_class`
- `terminal_outcome_kind`
- `evidence_presence_kind`

V2 should consume these canonical lifecycle fields instead of reconstructing lifecycle truth from
raw event strings alone.

### Uplink discipline

`ExecutionUplinkDiscipline` defines what Android must publish on each lifecycle entry:

- `PENDING`, `ACTIVATING`, `ACTIVE`, `DEGRADED`, `RETRYING` → state uplink required
- `INTERRUPTED`, `TIMED_OUT`, `COMPLETED`, `FAILED`, `REJECTED` → result uplink and state uplink required
- `CAPABILITY_IDLE` → no result uplink; state uplink optional

This blocks ad hoc behavior where Android might skip a required state/result publication or emit a
terminal-looking result for a non-terminal transition.

---

## 5. Partial publication is allowed; optimistic completion is not

Android is allowed to publish **partial or transitional truth**. That is intentional.

Android is **not** allowed to:

- silently upgrade unknown data to healthy data
- emit placeholder values just to make the payload look complete
- collapse interruption, retry, fallback, and failure into one generic success/failure guess
- treat cached/offline-queued state as fresh live confirmation

The repository's regression intent is therefore:

> publish what Android can directly justify, publish incompleteness honestly, and never make V2
> recover correctness by guessing.

---

## 6. Android-side tests protecting these invariants

The following tests are the regression fence future changes must preserve.

| Test | What it protects |
|---|---|
| `Pr3AndroidCapabilityExportContractTest` | `capability_report` schema/version stability, required top-level and metadata fields, and drift detection |
| `CapabilityReportTest` | metadata completeness/validity accounting, canonical gate consistency, and evidence-surface reporting for capability metadata |
| `Pr7BAndroidTruthPublicationSemanticsTest` | the six evidence kinds, classifier priority, no optimistic fallback for missing evidence, wire-field presence, and `CLOSURE_INVARIANTS` |
| `Pr85AndroidExecutionLifecycleHardeningTest` | lifecycle phase set, valid transitions, terminal vs non-terminal meaning, and uplink discipline |
| `Pr08AndroidCanonicalRuntimeTruthTest` | runtime-truth category/degraded-condition classification stays canonical |
| `Pr8AndroidCanonicalTruthAlignmentTest` | snapshot/event truth is routed through the intended reporting scenario and state-surface class |
| `Pr10AndroidCarrierConsolidationTest` | carrier lifecycle/runtime fields remain present on the canonical snapshot/event publication path |

### Invariants reviewers should treat as non-negotiable

1. **Only positive evidence is positive.**
2. **Unknown/missing data must not be treated as healthy.**
3. **Explicit unavailability must stay distinct from unknown.**
4. **Delayed/offline-queued state must stay distinct from fresh live state.**
5. **Partial subsystem confirmation must not be published as full capability.**
6. **Execution transitions must follow the lifecycle contract, not call-site convention.**
7. **Required result/state uplinks must remain tied to lifecycle phase entry.**
8. **Capability schema drift must be versioned and test-updated, not silently shipped.**

---

## 7. Change guidance for future Android work

If a future PR changes any of the following, it must update both implementation and documentation:

- `capability_report` required fields or metadata semantics
- `evidence_presence_kind` vocabulary or classifier priority
- execution lifecycle phase vocabulary or transition rules
- required result/state uplink behavior
- the meaning of snapshot/event semantic-class fields

If a change only adds a new field but leaves these semantics intact, the new field must still obey
the same rule: **no optimistic placeholder truth when Android lacks backing evidence**.
