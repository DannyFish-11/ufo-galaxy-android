# Android ↔ V2 Protocol, State, and Truth Surfaces

## 1. Purpose

This document records the current factual interaction surfaces between the Android runtime and V2.
It is not a protocol rewrite proposal. Its purpose is to give all contributors a shared engineering
baseline for the actual protocol, state, and truth surfaces that exist today, so that later
incremental evolution work can attach to real behavior rather than to an imagined future shape.

This document is intentionally concrete. It does not attempt to prescribe a future architecture.

---

## 2. Android Runtime Role

Android must not be understood as a dumb client or a passive display target. In the current system,
Android participates as a full runtime contributor on the following dimensions:

| Role | Meaning |
|---|---|
| Runtime participant | Android registers, maintains a live connection, and participates in the dispatch cycle |
| Capability producer | Android declares its execution capabilities to V2 through `capability_report` |
| Readiness source | Android publishes authoritative local readiness state that V2 cannot independently observe |
| Action executor | Android receives and executes goal/task dispatches and owns execution lifecycle on the device side |
| Local perception contributor | Android emits screenshots, sensor readings, and other device-local perception signals to V2 |
| Execution truth publisher | Android publishes authoritative ground truth for device-side execution events and outcomes |

V2 does not have independent access to the device-local state that Android observes. Android's
publications on these dimensions are the primary authoritative source for V2's understanding of
phone-side runtime, capability, and execution state.

---

## 3. Protocol Surfaces by Responsibility

The Android↔V2 protocol currently covers the following responsibility areas:

### 3.1 Registration and Connection

Android initiates and maintains the WebSocket connection to V2. The registration surface establishes:

- device identity and runtime identity for the session
- the basis for V2 to associate subsequent publications with a specific Android runtime participant
- reconnect and re-registration behavior when the connection is lost

### 3.2 Capability Publication

Android publishes its declared capability surface to V2. This surface tells V2:

- which execution modes Android currently supports
- which tasks or goal classes Android is eligible to receive
- what scheduling and gate metadata applies to the current capability declaration
- what contract schema version this capability publication conforms to

### 3.3 Readiness Publication

Android publishes its runtime readiness state independently of capability declaration. This surface:

- reflects live device-local readiness evidence (model warmup, subsystem availability, etc.)
- allows V2 to distinguish "capability declared" from "capability currently usable"
- is updated on readiness changes, not only on reconnection

### 3.4 State Snapshot Publication

Android publishes point-in-time device state snapshots that capture the full operational picture of
the device-side runtime at a given moment. These snapshots include lifecycle state, queue depth,
session continuity, carrier state, mode state, and other runtime fields.

### 3.5 Execution Lifecycle Publication

Android publishes execution lifecycle events covering the full arc of a dispatched action, from
acceptance through activation, active execution, terminal outcome, and result closure. This surface
gives V2 the Android-side execution ground truth without requiring V2 to reconstruct lifecycle state
from raw field combinations.

### 3.6 Perception Publication

Android emits device-local perception signals to V2. These include screenshots, accessibility tree
observations, sensor data, and other locally-derived observations of the device environment. This
surface feeds V2's multimodal, grounding, and state-inference paths.

### 3.7 Task and Goal Reception

V2 dispatches task assignments and goal execution requests to Android. Android receives these
dispatches through the established connection and routes them to its local executor. This is the
primary V2→Android direction in the protocol.

### 3.8 Result and Truth Uplink

Android closes execution cycles by uplinking results and terminal truth to V2. This surface carries
terminal outcome declarations, result payload, and evidence classification so V2 can correctly
interpret whether execution succeeded, failed, was rejected, or requires further recovery handling.

---

## 4. Key Message Surfaces and Directions

| Message | Direction | Purpose |
|---|---|---|
| `device_register` | Android → V2 | Establish device identity and register the Android participant with the V2 runtime |
| `capability_report` | Android → V2 | Declare current device capability surface, execution eligibility, and contract schema version |
| `device_state_snapshot` | Android → V2 | Publish point-in-time lifecycle, readiness, queue, session, and operational state |
| `device_execution_event` | Android → V2 | Publish execution lifecycle phase transitions and terminal outcome truth |
| `device_perception_emission` | Android → V2 | Emit device-local perception signals (screenshots, accessibility observations, sensor data) |
| `task_assign` | V2 → Android | Dispatch a task assignment to the Android executor |
| `goal_execution` | V2 → Android | Dispatch a goal for local execution on the Android device |
| execution/result uplink | Android → V2 | Close an execution cycle with terminal result truth and evidence classification |

All Android→V2 messages in this list represent Android as authoritative publisher. V2 should
consume them as ground truth for the respective surface rather than treating them as advisory hints.

---

## 5. State Surfaces

The following categories describe the state surfaces Android currently produces. For each category,
producer, timing, structure strength, and suitability for future StateSurfacePack mapping are noted.

### 5.1 Readiness State

**Producer:** Android runtime (device-local subsystem checks, model warmup result, warmup timing).

**Timing:** Published at connection/registration, on readiness changes, and as part of each
`device_state_snapshot`. May be emitted standalone when a readiness transition occurs.

**Structure strength:** Strong. Readiness fields carry explicit warmup results and evidence
classification. A missing or null readiness field means the backing evidence is unavailable, not
that readiness is implicitly healthy.

**StateSurfacePack suitability:** High. Readiness state is a self-contained, well-bounded surface
that maps cleanly onto a readiness state slot in a future StateSurfacePack. No inference needed;
Android publishes directly.

### 5.2 Capability Surface

**Producer:** Android capability export path (`capability_report`, `AndroidCapabilityExportContract`,
`LocalExecutionModeGate`).

**Timing:** Published at registration, on capability changes, and when execution mode or gate
conditions change. Carries a `contract_schema_version` on every emission.

**Structure strength:** Strong. The capability surface has explicit schema versioning, required
top-level and metadata fields, and drift-detection tests. Fields are derived from the live gate,
not from ad hoc call-site guesses.

**StateSurfacePack suitability:** High. The capability surface is a primary candidate for
RuntimeCapabilityProfile mapping. It is schema-stable, versioned, and structured for downstream
consumption.

### 5.3 Snapshot State

**Producer:** `GalaxyConnectionService.sendDeviceStateSnapshot()`, which assembles the snapshot
from live Android-owned state sources.

**Timing:** Published at connection, on significant state transitions, and on periodic or triggered
refresh cycles. Carries `reported_state_semantic_class`, `degraded_condition_class`,
`local_observation_basis`, and `evidence_presence_kind` for interpretability.

**Structure strength:** Medium-strong. The snapshot is rich and covers many operational axes, but
it is a point-in-time composite. Some fields may be null when backing evidence is unavailable, and
V2 must respect null semantics rather than substituting defaults.

**StateSurfacePack suitability:** Medium-high. Snapshot state is a strong candidate for a snapshot
state slot in StateSurfacePack, but the evidence classification fields must be preserved in the
mapping so downstream consumers understand the quality of the snapshot at the time it was taken.

### 5.4 Perception-Derived State

**Producer:** Android perception emission path (`device_perception_emission`). Sources include
screenshots, accessibility tree reads, sensor observations, and local VLM/grounding emissions.

**Timing:** Emitted on perception-triggering events (task phases, explicit capture requests,
periodic observation). Not a continuous stream in the current implementation.

**Structure strength:** Weaker than execution or readiness state. Perception emissions are
observation-based and may be stale, partial, or inferentially derived. They carry their own
evidence classification but are inherently lower-certainty than live execution lifecycle truth.

**StateSurfacePack suitability:** Medium. Perception-derived state can feed a perception slot in
StateSurfacePack, but consumers must treat it as observational rather than authoritative. It
supplements snapshot and execution state rather than replacing either.

### 5.5 Execution Lifecycle State

**Producer:** `AndroidExecutionLifecycleContract`, `ExecutionUplinkDiscipline`,
`GalaxyConnectionService` execution-event emitters.

**Timing:** Published on each lifecycle phase transition. Carries phase, previous phase, transition
validity, uplink requirements, terminal outcome kind, and result semantic class.

**Structure strength:** Strong. Execution lifecycle state has explicit phase vocabulary, transition
rules, uplink discipline, and terminal vs non-terminal classification. It is the highest-certainty
real-time state surface Android produces.

**StateSurfacePack suitability:** High. Execution lifecycle state is the primary candidate for the
execution truth slot in StateSurfacePack. Its structure is well-defined and its evidence
classification is authoritative.

### 5.6 Local Model / Grounding / Overlay / Accessibility Readiness Surfaces

**Producer:** Device-local subsystem availability checks (model download state, accessibility
service state, overlay permission state, grounding module availability).

**Timing:** Published as part of `capability_report` metadata, `device_state_snapshot` fields, and
readiness state. Updated on subsystem state changes.

**Structure strength:** Medium. These surfaces are reported as discrete boolean or enum fields
rather than as first-class state objects. They are structurally present but not independently
versioned or sealed.

**StateSurfacePack suitability:** Medium. These surfaces are suitable for inclusion in a
StateSurfacePack as subsidiary readiness slots, but they currently lack the same schema discipline
as capability or execution lifecycle state. Future work should introduce explicit contracts for each
subsystem before elevating them to primary StateSurfacePack slots.

---

## 6. Truth Surfaces

### 6.1 Execution Truth Candidates

The following surfaces are strong truth candidates for execution outcomes because they are produced
by the Android executor at the moment of the relevant lifecycle event and carry explicit evidence
classification:

- **`device_execution_event` with terminal lifecycle phase** (`COMPLETED`, `FAILED`, `REJECTED`,
  `TIMED_OUT`): These are Android's direct observation of execution outcome at the device. They
  carry `terminal_outcome_kind`, `result_uplink_semantic_class`, and `evidence_presence_kind` to
  make the quality of the truth claim explicit.
- **Result uplink payload**: The concrete result data uplinking at execution closure. This is the
  definitive V2-facing record of what Android executed and what it observed.

### 6.2 Observation, Telemetry, and Heuristics

The following surfaces provide supporting signal but are not equivalent to execution truth:

- **`device_perception_emission`**: Observations of the device environment (screenshots,
  accessibility reads). These reflect what Android's sensors observed, not what the executor
  deterministically confirmed.
- **`device_state_snapshot`**: Operational telemetry reflecting runtime state at a point in time.
  Useful for V2's health picture, but not an execution outcome claim.
- **Carrier/session continuity fields**: Heuristic signals about connectivity and session health.
  These help V2 interpret the reliability of other publications but are not independent truth claims.

### 6.3 Attempted vs Completed vs Verified-Success

Android makes explicit distinctions between these execution outcome classes:

| Class | Meaning |
|---|---|
| Attempted | Android accepted and activated the work (`ACTIVATING`, `ACTIVE`) but has not yet reached a terminal phase. V2 must not treat this as success. |
| Completed | Android reached a terminal phase and published a terminal lifecycle event. This is Android's truth claim about the execution outcome, not V2's derived interpretation. |
| Verified-success | A completed execution where `terminal_outcome_kind` is success-class and evidence classification is `positive_evidence`. This is the strongest truth claim Android currently makes. |

Future phases should not collapse these distinctions. An `INTERRUPTED` or `TIMED_OUT` outcome is
not a generic failure; a `RETRYING` phase is not a success; a transitional event such as
`takeover_milestone` is not a terminal outcome.

### 6.4 Avoiding Queued, Offline, and Uncertain State as Live Truth

Android explicitly flags state that should not be treated as live execution truth:

- **`offline_queue_depth > 0`**: Indicates queued-but-not-executed work. V2 must apply staleness
  handling, not standard fresh-state handling.
- **`evidence_presence_kind = "delayed"`**: State backed by potentially stale evidence.
- **`evidence_presence_kind = "unknown"`**: Android cannot yet determine the state. V2 must not
  treat absence of proof as a healthy default.
- **`pending_first_download = true`**: Capability explicitly unavailable; not an unknown gap.

The governing rule is: **offline-queued, uncertain, or partial state must not be promoted to live
execution truth by either Android or V2.**

---

## 7. Current Strengths

The current Android↔V2 protocol surface has several genuine strengths:

- Android already publishes capability, readiness, execution lifecycle, state snapshot, and
  perception signals. The major surface categories exist and are actively consumed.
- Evidence classification (`evidence_presence_kind`) is present on the main publication paths,
  allowing V2 to interpret truth quality rather than guessing from raw fields.
- The capability surface is schema-versioned and has explicit contract enforcement.
- Execution lifecycle has explicit phase vocabulary, transition rules, and uplink discipline,
  preventing ad hoc terminal-outcome guessing.
- The operational surface axes (`operational_surface_states`, `operational_surface_authority`) give
  V2 a compact projection of Android's participant status.
- Regression tests exist for the main protocol contracts, providing a safety fence for future
  changes to capability, execution lifecycle, and truth semantics.

---

## 8. Current Gaps and Future Evolution Points

### Gaps

- **No unified semantic state object mapping**: Android's state is published across multiple message
  types. There is not yet a single canonical StateSurfacePack-style object that aggregates all
  state categories for a given point in time.
- **Protocol is message-centric, not semantic-contract-centric**: The current wire protocol is a
  set of messages with well-defined fields. It does not yet expose a higher-level semantic contract
  layer that future runtime matching or capability fabric layers can consume directly.
- **Verify, handoff, and recovery are not first-class execution stages**: These lifecycle stages
  exist in practice but are not formalized as first-class phases in the current execution lifecycle
  contract.
- **Perception surface lacks independent schema discipline**: Perception emissions are structurally
  present but do not have the same contract versioning and field stability guarantees as the
  capability or execution lifecycle surfaces.
- **No ingress normalization layer between Android and V2**: Android publications are ingested
  through the V2 gateway, but there is not yet a normalization layer that maps Android messages to
  a V2-canonical semantic representation. Downstream V2 paths consume the raw wire fields directly.

### Future Evolution Points

- Introduce RuntimeCapabilityProfile as a higher-level semantic object built on top of the existing
  `capability_report` surface, without replacing the underlying wire message.
- Introduce StateSurfacePack as an aggregation of the current state, readiness, and perception
  surfaces, normalized to a single canonical object per point in time.
- Add ingress normalization at the V2 gateway to produce semantic representations from Android wire
  messages without requiring downstream consumers to re-implement the same field interpretation.
- Formalize verify, handoff, and recovery as first-class execution lifecycle phases, building on
  the existing phase vocabulary and uplink discipline.
- Extend perception surface contracts to match the schema discipline of the capability and execution
  lifecycle surfaces.

---

## 9. Meaning for Later Phases

### 9.1 RuntimeCapabilityProfile Reusing capability_report

The existing `capability_report` message and `AndroidCapabilityExportContract` provide the factual
basis for RuntimeCapabilityProfile construction in Phase 2. The required top-level fields,
metadata map, gate-derived eligibility fields, and schema version are all already present. Phase 2
work should build RuntimeCapabilityProfile as an overlay and semantic enrichment of this surface,
not as a replacement wire message.

### 9.2 StateSurfacePack Absorbing State and Perception Surfaces

The state, readiness, snapshot, execution lifecycle, and perception surfaces described in Section 5
are the raw material for StateSurfacePack mapping in Phase 2/3. Each category maps to a distinct
slot in StateSurfacePack. The evidence classification fields already present on these surfaces must
be preserved in the mapping so downstream consumers can apply correct truth quality handling.

### 9.3 Ingress Normalization Basis

The current Android→V2 gateway path is the attachment point for future ingress normalization. The
message types, field names, and evidence classification vocabulary described in this document
represent the stable surface that normalization must preserve compatibility with. Normalization
should produce semantic representations at ingress without altering the underlying wire messages.

### 9.4 Android Runtime Participant Formalization Basis

The Android role definition in Section 2 and the protocol surface coverage in Section 3 are the
factual basis for formalizing Android as a first-class runtime participant in Phase 4. The
formalization should recognize the participation dimensions that already exist (capability, state,
execution, truth) and add formal contracts for the dimensions that are currently implicit
(participant lifecycle, session semantics, handoff/recovery eligibility).

---

## 10. Incremental Attachment Approach

All evolution work against the surfaces described in this document must follow the incremental
attachment principle:

**Normalize and overlay; do not replace.**

Concretely:

- New semantic objects (RuntimeCapabilityProfile, StateSurfacePack, participant contracts) must be
  built as overlays on top of the existing wire surfaces. The underlying messages (`capability_report`,
  `device_state_snapshot`, `device_execution_event`, etc.) must continue to function without
  modification during the transition period.
- Ingress normalization must be additive. It must produce new semantic representations at the
  gateway without blocking or altering the existing raw-field consumption paths that current V2
  subsystems depend on.
- New execution lifecycle phases (verify, handoff, recovery) must be introduced as extensions of
  the existing phase vocabulary, not as replacements. Transition rules and uplink discipline must
  remain backward-compatible.
- No new semantic contract should be declared authoritative until it has been validated against
  real Android publication behavior on the surfaces described in this document.
- When in doubt: preserve existing behavior, add the new path in parallel, and let the overlay
  prove its value before any derived interpretation is substituted for the original.
