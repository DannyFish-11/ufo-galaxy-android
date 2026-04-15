# Android UGCP Constitution & Canonical Vocabulary Alignment

## 1) Scope and status

This document defines the Android-side **contract freeze** for UGCP vocabulary and control semantics.
It is intentionally grounded in current Android implementation.

- In scope: naming, semantics, mapping, lifecycle/phase alignment, runtime-profile responsibilities.
- Out of scope: claiming full protocol convergence is already complete.

## 2) Android runtime-profile role in UGCP

Android is a **runtime-profile participant** in the shared control-plane architecture.
Android AIP/WS is treated as the Android-side realization of the **UGCP Runtime WS Profile**.
Current responsibilities (implemented in Android):

- Device registration + capability/reporting + heartbeat
- Task submit / assign handling / result return
- Goal/parallel execution result return
- Takeover request/response handling
- Delegated execution lifecycle signaling (ACK/PROGRESS/RESULT)
- Mesh join / leave / result reporting
- Attached runtime session and readiness projections

Authoritative Android runtime surfaces:

- `AipMessage` + payload families: `app/src/main/java/com/ufo/galaxy/protocol/AipModels.kt`
- Runtime lifecycle/session truth: `app/src/main/java/com/ufo/galaxy/runtime/RuntimeController.kt`
- Attached host-session projection: `app/src/main/java/com/ufo/galaxy/runtime/AttachedRuntimeHostSessionSnapshot.kt`
- Readiness contract: `app/src/main/java/com/ufo/galaxy/service/ReadinessChecker.kt`
- Takeover/delegated contracts: `app/src/main/java/com/ufo/galaxy/agent/TakeoverEnvelope.kt`, `app/src/main/java/com/ufo/galaxy/runtime/DelegatedExecutionSignal.kt`

### 2.1 Runtime WS Profile declaration (Android side)

- Profile identity: `ugcp.runtime_ws_profile.android`
- Transport realization: `aip_ws` (AIP over WebSocket)
- Current status: `incremental_alignment`
- Stability posture: preserve current runtime behavior; align semantics and vocabulary incrementally.

## 3) Canonical identity/session vocabulary freeze (Android side)

### 3.1 Canonical UGCP term → Android mapping

| Canonical term | Android field(s) now | Android-side freeze |
|---|---|---|
| `task_id` | `TaskSubmitPayload.task_id`, `TaskAssignPayload.task_id`, `TaskResultPayload.task_id`, `Goal*`, `MeshResultPayload.task_id`, takeover/delegated payloads | Primary per-task identity across Android runtime/control flows. |
| `trace_id` | `AipMessage.trace_id`, takeover/delegated payload `trace_id` | End-to-end correlation identity; should remain stable across one distributed execution chain. |
| `control_session_id` | `AipMessage.session_id`, payload `session_id` (notably `TaskSubmitPayload.session_id`, takeover `session_id`) | Android currently uses `session_id` as control-session carrier. `control_session_id` is treated as canonical alias, not a separate Android wire field yet. |
| `runtime_session_id` | `AipMessage.runtime_session_id`; takeover/handoff envelopes `runtime_session_id`; host snapshot `runtimeSessionId` | Per app-process/runtime connection scope identifier used for runtime-session correlation. |
| `mesh_session_id` | `mesh_id` in `MeshJoinPayload` / `MeshLeavePayload` / `MeshResultPayload` | Android canonical mesh session identity is `mesh_id`; this is the Android wire realization of canonical `mesh_session_id`. |
| `source_node_id` | `device_id` (origin device identity); examples may show `source_node` envelope field | Android canonical source-node identity is the emitting `device_id`. |
| `target_node_id` | Target gateway/runtime endpoint implied by message direction; examples may show `target_node` | Android treats target node as routing context (usually Gateway/host runtime), not a mandatory typed field in `AipMessage`. |
| `execution_instance_id` | Delegated: `signal_id` (+ `emission_seq`) in `DelegatedExecutionSignal`; local/cross-device result path: `idempotency_key` | Android freeze: delegated path uses `signal_id` as execution-emission identity; general envelope dedupe uses `idempotency_key`. |

### 3.2 Session taxonomy freeze

Android session terms are frozen as:

- **ConversationSession**: local conversation/history timeline identity (`LocalLoopTrace.sessionId`, `SessionHistorySummary.sessionId`, task-submit `session_id`).
- **RuntimeAttachmentSession**: Android runtime host attachment continuity identity (`AttachedRuntimeSession.sessionId`, `AttachedRuntimeHostSessionSnapshot.sessionId`).
- **DelegationTransferSession**: takeover/delegation lifecycle continuity context (`takeover_request.session_id`, `takeover_response`, `delegated_execution_signal`).
- **Control session**: `session_id` scope used for request grouping / orchestration context.
- **Runtime session**: `runtime_session_id` carried on envelopes and takeover/handoff contracts.
- **Attached runtime host session**: `AttachedRuntimeSession.sessionId` + projected `runtimeSessionId` in `AttachedRuntimeHostSessionSnapshot`.
- **Adopted delegated session context**: `attached_session_id` in `delegated_execution_signal` payload binds delegated execution to the active attached runtime host session.
- **Mesh session**: `mesh_id`.
- **Reconnect recovery state**: `idle` / `recovering` / `recovered` / `failed` (`ReconnectRecoveryState`).
- **Attached session continuity state**: `attached` / `detaching` / `detached` with detach cause
  `explicit_detach` / `disconnect` / `disable` / `invalidation` (`AttachedRuntimeSession`).

No claim is made that all repos already expose all canonical names as first-class wire keys.
This document freezes Android-side semantics and aliases for convergence.

### 3.3 Canonical concept boundary glossary (Android runtime side)

Android now keeps explicit concept-boundary alignment in
`UgcpSharedSchemaAlignment.canonicalConceptVocabulary` so `participant/node`, `device`, and
`runtime host` semantics stay distinct while runtime behavior remains unchanged.

| Concept | Canonical term | Android mapping | Boundary |
|---|---|---|---|
| participant / node | `participant_node_id` | `device_id + runtime_host_id` | Cross-device participant identity (not only hardware identity). |
| device | `device_id` | `AipMessage.device_id`, `AndroidSessionContribution.deviceId` | Hardware/device identity carrier. |
| runtime host / runtime | `runtime_host_participant` | `RuntimeHostDescriptor(hostId, formationRole, participationState)` | Runtime-host lifecycle participation authority. |
| capability reporting | `runtime_capability_report` | `capability_report`, `ReadinessChecker`, `AndroidCapabilityVector` | Runtime capability/readiness declaration surface (provider publication only; not runtime/device/participant identity authority). |
| capability provider | `capability_provider_ref` | `CanonicalCapabilityProviderModel`, `AndroidCapabilityProviderModelMapper` | Capability-plane provider contract; does not own runtime identity, attachment/reconnect truth, or participant truth. |
| conversation/history session | `conversation_session_id` | `LocalLoopTrace.sessionId`, `SessionHistorySummary.sessionId` | Conversation/history timeline identity. |
| runtime attachment session | `attached_runtime_session_id` | `AttachedRuntimeSession.sessionId`, `delegated_execution_signal.attached_session_id` | Runtime attachment continuity identity. |
| delegation/transfer session | `transfer_session_context` | `takeover_request.session_id`, `takeover_response`, delegated signal lifecycle | Transfer/delegation lifecycle context. |
| posture | `source_runtime_posture` | `AipMessage.source_runtime_posture` | Participation posture for selection/scheduling. |
| coordination role | `coordination_role` | `mesh_join.role` (`participant`/`coordinator`) | Coordination-plane role semantics. |

## 4) Runtime/control/state vocabulary freeze (Android)

### 4.1 Runtime and session lifecycle terms

- Runtime state: `Idle`, `Starting`, `Active`, `Failed`, `LocalOnly` (`RuntimeController.RuntimeState`)
- Attached session state: `attached`, `detaching`, `detached`
- Detach causes: `explicit_detach`, `disconnect`, `disable`, `invalidation`

### 4.2 Source posture and readiness semantics

- `source_runtime_posture`: canonical values `control_only` / `join_runtime` (`SourceRuntimePosture`)
- Readiness minimal contract (`ReadinessChecker`):
  - `model_ready`
  - `accessibility_ready`
  - `overlay_ready`
  - Derived: `degraded_mode`

### 4.3 Capability semantics

- Registration/capability family is emitted via WS handshake and capability report.
- Capability metadata remains the Android runtime-profile declaration to control-plane consumers.

### 4.4 Transfer / delegated / mesh terminology

- Takeover: `takeover_request` / `takeover_response`
- Delegated execution signaling: `delegated_execution_signal`
  - `signal_kind`: `ack` / `progress` / `result`
  - `result_kind`: `completed` / `failed` / `timeout` / `cancelled` / `rejected`
- Mesh participation: `mesh_join` / `mesh_leave` / `mesh_result`
  - `mesh_join.role`: `participant` / `coordinator`
  - `mesh_leave.reason`: `disconnect` / `task_complete` / `error`

### 4.5 Terminal/result/failure terms

Android emits/interprets terminal outcomes across families, including:

- Task/goal result statuses: `success`, `error`, `cancelled` (and related execution-family statuses)
- Delegated terminal kinds: `completed`, `failed`, `timeout`, `cancelled`, `rejected`
- Mesh aggregate status: `success`, `partial`, `error`
- Takeover rejection reasons and setup/recovery error categories (runtime setup path)

### 4.6 UGCP Coordination Profile alignment (Android)

Android now explicitly declares participation in the **UGCP Coordination Profile**:

- Profile identity: `ugcp.coordination_profile.android`
- Current status: `incremental_alignment`
- Behavior stance: preserve existing runtime behavior while tightening coordination vocabulary/mapping.

Canonical coordination mapping anchors used by Android:

| Android runtime-facing event | Canonical coordination semantic |
|---|---|
| `mesh_join` | `coordination_participant_joined` |
| `mesh_leave` | `coordination_participant_left` |
| `mesh_result` | `coordination_execution_result_reported` |
| `mesh_result.status in {success,partial,error}` | `coordination_execution_terminal` |

Coordination posture/readiness interaction notes (incremental, non-disruptive):

- `source_runtime_posture=join_runtime` means Android is posture-eligible for coordinated execution participation.
- `source_runtime_posture=control_only` means Android remains a control-plane participant without joining execution participation.
- `model_ready` / `accessibility_ready` / `overlay_ready` / `degraded_mode` remain runtime capability-readiness signals consumed by selection/coordination logic.

### 4.7 UGCP Control Transfer Profile alignment (Android)

Android now explicitly declares participation in the **UGCP Control Transfer Profile**:

- Profile identity: `ugcp.control_transfer_profile.android`
- Current status: `incremental_alignment`
- Behavior stance: preserve existing Android runtime behavior while tightening transfer vocabulary/mapping.

Canonical transfer lifecycle vocabulary used for Android mapping:

- `transfer_accept`
- `transfer_reject`
- `transfer_cancel`
- `transfer_expire`
- `transfer_adopt`
- `transfer_resume`

Android transfer event → canonical transfer semantic mapping:

| Android runtime-facing event | Canonical transfer semantic |
|---|---|
| `takeover_response.accepted=true` | `transfer_accept` |
| `takeover_response.accepted=false` | `transfer_reject` |
| `delegated_execution_signal.result_kind=cancelled` | `transfer_cancel` |
| `delegated_execution_signal.result_kind=timeout` | `transfer_expire` |
| `delegated_execution_signal.result_kind=rejected` | `transfer_reject` |
| `delegated_handoff_contract.continuation_token` present | `transfer_adopt` |
| `delegated_handoff_contract.handoff_reason=continuation` | `transfer_resume` |

Notes:

- Android currently carries explicit cancellation/timeout terminal outcomes through delegated execution result signaling.
- Expiry semantics are currently aligned through delegated `timeout` terminal signaling.
- Adoption/resume semantics are currently aligned through continuation-token and continuation-reason handoff contract fields.

### 4.8 UGCP Truth/Event Model alignment (Android, incremental)

Android now explicitly declares participation in the **UGCP Truth/Event Model**:

- Model identity: `ugcp.truth_event_model.android`
- Current status: `incremental_alignment`
- Behavior stance: preserve existing runtime behavior while making Android truth/event roles explicit and reviewable.

Authoritative state-bearing transitions and reports (Android side):

| Android signal surface | Canonical truth/event semantic | Semantics class |
|---|---|---|
| `RuntimeController.state` transition | `runtime_state_truth_updated` | authoritative state transition |
| `RuntimeController.hostSessionSnapshot` update | `attached_runtime_session_truth_updated` | authoritative state transition |
| `RuntimeController.targetReadinessProjection` update | `delegated_target_selection_truth_updated` | authoritative state transition |
| `RuntimeController.reconnectRecoveryState` transition | `runtime_reconnect_recovery_truth_updated` | authoritative state transition |
| `task_result` / `command_result` / `goal_result` / `goal_execution_result` | `execution_terminal_truth_reported` | authoritative result report |
| `mesh_result.status in {success,partial,error}` | `coordination_execution_terminal_reported` | authoritative result report |

Observational/notification emissions (not replacement truth surfaces):

| Android signal surface | Canonical truth/event semantic | Semantics class |
|---|---|---|
| `RuntimeController.takeoverFailure` | `transfer_fallback_notified` | observational event emission |
| `delegated_execution_signal.signal_kind in {ack,progress,result}` | `delegated_execution_lifecycle_notified` | observational event emission |
| `mesh_join` / `mesh_leave` | `coordination_participant_joined` / `coordination_participant_left` | informational participation emission |

Truth/event boundary notes:

- `hostSessionSnapshot` and `targetReadinessProjection` are Android’s canonical host-facing truth projections for attached-session and delegated-selection semantics.
- `takeoverFailure` and delegated ACK/PROGRESS/RESULT streams are lifecycle notifications for reconciliation and operator visibility; they do not replace authoritative session/readiness projections.
- Terminal result payloads (`task_result` / `goal_result` families, mesh terminal status) remain the Android-side authoritative execution outcome reports sent to center-side truth surfaces.

## 5) Android message family → canonical phase graph mapping

Canonical phases used here: **ingress → planning → assignment → execution → transfer → completion → recovery**.

| Android flow family | ingress | planning | assignment | execution | transfer | completion | recovery |
|---|---|---|---|---|---|---|---|
| User `task_submit` uplink (`InputRouter`) | `task_submit` accepted locally | Gateway/control-plane concern | Gateway/control-plane concern | Android executes only if later assigned | Uplink to gateway | N/A at submit moment | Send failure handling / reconnect gates |
| Inbound `task_assign` / `goal_execution` / `parallel_subtask` | `GalaxyConnectionService` parses inbound | Goal/task decomposition is upstream concern | Assignment arrives on Android | `EdgeExecutor` / `LocalGoalExecutor` | Optional bridge/takeover/delegated transfer path | `task_result` / `goal_result` / `command_result` / `goal_execution_result` | Retry/fallback/offline-queue paths |
| Takeover + delegated | `takeover_request` ingress | Upstream dispatch planning | Android accepts/rejects takeover | Delegated unit execution | `takeover_response` + `delegated_execution_signal` stream | Terminal delegated `result_kind` emitted | Session gate rejection, timeout/cancel/disconnect recovery |
| Mesh participation | `mesh_join`/context ingress | Upstream mesh planning | Subtask assignment upstream | Android executes local subtask contribution | `mesh_result` exchange | Mesh completion reported (`success`/`partial`/`error`) | `mesh_leave` + reconnect/disconnect handling |
| Runtime lifecycle | WS/session events ingress | N/A | N/A | Runtime active/local-only toggles | State propagation to host snapshot/projections | Stable host-session projection emission | reconnect recovery states + detach causes |

## 6) Cross-repo control semantic alignment notes

1. **Identity continuity**
   - `task_id` and `trace_id` are Android’s primary cross-hop identity pair.
   - `runtime_session_id` and attached host-session snapshot provide runtime-scope continuity.

2. **Control-session aliasing**
   - Android uses `session_id` where canonical docs may refer to `control_session_id`.
   - Convergence expectation: semantic equivalence is frozen now; naming convergence can be additive.

3. **Posture/readiness truth**
   - `source_runtime_posture` and readiness metadata are explicit Android runtime-profile signals,
     not informal diagnostics.

4. **Transfer semantics are first-class (not compatibility-only)**
   - Takeover, delegated signaling, and mesh families are treated as protocol capabilities,
     not disposable temporary adapters.

5. **No over-claiming convergence**
   - Android alignment is documented and frozen; full cross-repo schema/name unification remains
     follow-up work in later Runtime WS Profile / control-transfer profile phases.

## 7) Android message families → control-plane semantic map

| Android message family | Control-plane semantic role |
|---|---|
| `device_register` | Runtime node identity/presence registration into control plane. |
| `capability_report` | Runtime profile + readiness/capability declaration consumed by scheduling/selection logic. |
| `heartbeat` / `heartbeat_ack` | Liveness continuity for control-session/runtime-session presence. |
| `task_submit` | Control-plane ingress request from source runtime/client. |
| `task_assign` | Assignment decision delivery from control-plane to runtime profile. |
| `task_result` / `command_result` / `goal_result` / `goal_execution_result` | Execution and terminal outcome projection back to control plane. |
| `takeover_request` / `takeover_response` | Explicit control-transfer handshake between source/target runtimes. |
| `delegated_execution_signal` | Fine-grained delegated lifecycle stream for host-side tracker reconciliation. |
| `mesh_join` / `mesh_leave` / `mesh_result` | Mesh participation lifecycle and aggregate contribution/result semantics (`coordination_participant_joined` / `coordination_participant_left` / `coordination_execution_result_reported`). |

## 8) Shared schema family preparation (incremental, non-disruptive)

To keep convergence work incremental, Android now keeps a lightweight alignment registry in code:

- `app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt`

This registry does **not** rewrite wire fields or runtime behavior. It documents and freezes:

- canonical identity lineage (`task_id`, `trace_id`, `control_session_id`, `runtime_session_id`, `mesh_session_id`, `source_node_id`, `target_node_id`, `execution_instance_id`)
- message-family mapping to canonical schema families (`identity`, `control`, `runtime`, `coordination`, `truth`)
- transfer/delegated/takeover vocabulary alignment
- control-transfer lifecycle vocabulary (`transfer_accept`, `transfer_reject`, `transfer_cancel`, `transfer_expire`, `transfer_adopt`, `transfer_resume`)
- Android transfer event ↔ canonical transfer semantic mapping notes
- coordination profile declaration (`ugcp.coordination_profile.android`) and mesh lifecycle/outcome mapping notes
- readiness/capability semantics and mesh participation semantics
- terminal/result/failure vocabulary alignment used by Android runtime-facing paths

This provides a stable Android-side bridge for later Runtime WS Profile and Control Transfer Profile convergence without destabilizing existing AIP/runtime implementations.

## 9) Runtime WS Profile foundation outcome (Android PR-4 scope)

Android-side PR-4 work intentionally focuses on explicit protocol-role promotion and bounded model
alignment, not broad behavioral rewrites.

- AIP/WS is explicitly documented as Android’s UGCP Runtime WS Profile realization.
- Core runtime message families are mapped to canonical control/runtime/coordination/truth semantics.
- Session continuity semantics now explicitly include runtime session, attached session, and reconnect
  recovery taxonomy.
- Readiness/capability/posture and transfer/mesh semantics are documented as first-class protocol
  roles.
- Full cross-repo convergence is **not** over-claimed; additive convergence remains follow-up work.

## 10) Replay/recovery/resume and durable-state expectations (Android scope)

Android aligns with shared truth/event expectations in an incremental runtime-profile way:

- **Replay/recovery state signaling:** `ReconnectRecoveryState` (`idle|recovering|recovered|failed`) is a first-class runtime truth surface for reconnect lifecycle continuity.
- **Session continuity projection:** `AttachedRuntimeHostSessionSnapshot` remains the authoritative Android projection for attached runtime session continuity and invalidation semantics.
- **Delegated signal replay identity:** delegated ACK/PROGRESS/RESULT supports replay-safe identity (`signal_id`, `emission_seq`) for host-side dedupe/reconciliation without claiming full Android-local durable event-sourcing.
- **Offline result durability (bounded):** Android buffers selected outbound result payloads for reconnect replay (`OfflineTaskQueue`) as a bounded runtime durability aid, not as a full authoritative center-side persistence layer.

This keeps Android behavior stable while making replay/recovery/resume and truth/event responsibilities explicit for later cross-repo hardening.

## 11) PR-8 conformance and compatibility-retirement groundwork (Android)

Android now keeps explicit **conformance-tier boundaries** in
`app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt`:

- `canonicalRuntimeMessageFamilies`: runtime-profile canonical message family surface.
- `transitionalCompatibilityMessageFamilies`: bounded compatibility/minimal-compat surface.
- `protocolTierFor(MsgType)`: stable canonical vs transitional classification helper.

Compatibility and normalization boundaries are now explicitly frozen as additive scaffolding:

- `compatibilityAliasNormalizations` (`MsgType.LEGACY_TYPE_MAP`) for legacy type aliases.
- `normalizeMessageType(rawType)` for alias normalization before canonical routing.
- `lifecycleStatusNormalizations` + `normalizeLifecycleStatus(rawStatus)` for lifecycle/result term normalization.
  - Includes bounded canonicalization for transitional status variants such as
    `completed→success`, `failed|failure→error`, and `no_op→disabled`.

This is intentionally **incremental**:

- Android does not claim strict-mode validation or immediate compatibility removal.
- Canonical runtime/control-transfer/coordination/truth semantics are made easier to identify and review.
- Transitional pathways are isolated so later compatibility retirement can be done in bounded, safer follow-up work.

## 12) PR-10 Android enforcement scaffolding and deprecation execution path

PR-10 extends Android’s additive UGCP groundwork with explicit enforcement/deprecation
classification helpers in `UgcpSharedSchemaAlignment` (still non-breaking):

- `classifyMessageTypeHandling(rawType)` now makes canonical handling decisions reviewable as:
  - `CANONICAL_ACCEPT`
  - `NORMALIZE_AND_ACCEPT`
  - `TOLERATE_TRANSITIONAL`
  - `FUTURE_REJECT_CANDIDATE`
- `classifyLifecycleStatusHandling(rawStatus)` mirrors the same bounded handling semantics for
  lifecycle/result status values.
- `deprecationExecutionPhases` now documents progressive retirement intent:
  `warn_and_observe → normalize_and_report → migration_gate_candidate → reject_after_explicit_rollout`.
- `enforcementHookSurfaces` now marks where stricter review/tightening can later be applied across:
  runtime ingress, transfer lifecycle normalization, coordination result normalization, and
  truth/event boundary review.

This is intentionally incremental:

- Android runtime behavior is preserved (no global strict-mode hard break).
- Non-canonical/legacy/transitional pathways are more explicitly classified and reviewable.
- Deprecation-ready boundaries are clearer for future controlled tightening.

## 13) PR-11 Android migration-readiness and retirement sequencing groundwork

PR-11 adds bounded migration-readiness and retirement-sequencing surfaces in
`UgcpSharedSchemaAlignment` to support staged rollout planning without changing runtime behavior:

- `migrationReadinessSurfaces` classifies key Android runtime protocol boundaries as:
  - `READY_FOR_STAGED_TIGHTENING` (canonical surfaces with explicit review boundaries)
  - `REQUIRES_PHASED_TOLERANCE` (surfaces that still need compatibility-aware sequencing)
- `retirementSequencingForMessageType(rawType)` maps current handling classification to
  phased retirement guidance:
  `phase_1_warn_and_observe → phase_2_normalize_and_report → phase_3_migration_gate_candidate → phase_4_reject_after_explicit_rollout`.

This remains intentionally incremental:

- Android does not claim immediate strict rollout or abrupt legacy retirement.
- Lifecycle/transfer/coordination/truth-event-adjacent pathways are more explicitly reviewable
  for phased tightening.
- Transitional handling remains operationally tolerated until explicitly sequenced retirement.

## 14) Runtime-to-canonical convergence audit visibility surfaces (Android)

Android now adds a focused runtime-to-canonical audit inventory in
`UgcpSharedSchemaAlignment.runtimeToCanonicalPathwayInventory` to make current behavior easier to
review without changing runtime logic.

Inventory/classification surfaces now include:

- `runtimeToCanonicalPathwayInventory`: pathway-by-pathway mapping audit entries with:
  runtime surface, canonical semantic, pathway class, normalization boundary, compatibility/fallback
  note, verification readiness, strictness stage, and rollout gate.
- `runtimeCanonicalPathways`: pathways already mapping cleanly into canonical shared semantics.
- `runtimeTransitionalPathways`: operationally necessary transitional pathways.
- `runtimeCompatibilityWorkaroundPathways`: compatibility/fallback/workaround pathways still shaping
  shared-facing behavior.
- `runtimeNormalizationBoundarySurfaces`: explicit local-to-shared normalization/mapping boundaries.
- `runtimeVerificationCandidatePathways`: pathways likely suitable for future staged contract
  verification/strictness layering.

Coverage is intentionally incremental and review-first:

- lifecycle/session/readiness/posture truth projections are explicitly marked as canonical pathways;
- transfer/coordination result mapping remains visible as transitional tolerance surfaces;
- legacy ingress/status normalization and reconnect/fallback observability are explicitly marked as
  compatibility/workaround pathways retained for runtime resilience.

## 15) PR-12 runtime-to-shared contract verification scaffolding (Android)

Android now adds report-only runtime contract verification surfaces in
`UgcpSharedSchemaAlignment.verifyRuntimeToSharedContractConsistency(...)`:

- pathway-level checks for canonical readiness, compatibility note presence, and transitional/workaround
  normalization-boundary mapping into known enforcement surfaces;
- transfer lifecycle coverage checks for `transfer_accept|transfer_reject|transfer_cancel|transfer_expire`;
- truth/event boundary checks that keep reconnect recovery authoritative while keeping fallback emission
  observational.

These checks are intentionally non-disruptive and report-oriented:

- findings are returned as `UgcpRuntimeContractVerificationResult` with
  `PASS` vs `REPORT_ONLY_DIVERGENCE`;
- `runtimeContractReportOnlyDivergenceCheckIds(...)` provides a reviewable divergence surface for staged
  strictness layering later;
- no runtime hard-fail or canonical-only enforcement behavior is introduced in this phase.

## 16) PR-14 Android staged strictness controls and rollout gating

Android now extends runtime-to-shared convergence scaffolding with explicit staged strictness and
rollout gating surfaces in `UgcpSharedSchemaAlignment.runtimeToCanonicalPathwayInventory`:

- `strictnessStage` makes each pathway reviewable as one of:
  - `NORMALIZE_FIRST`
  - `WARN_AND_DIAGNOSE`
  - `CANONICAL_PREFERRED`
  - `REJECT_READY_CANDIDATE` (still gated; not immediate hard rejection)
- `rolloutGate` marks rollout readiness:
  - `ANDROID_LOCAL_TIGHTENING_READY`
  - `ANDROID_EVIDENCE_REQUIRED`
  - `CENTER_ANDROID_COORDINATION_REQUIRED`
- grouped review surfaces now expose staged tightening boundaries directly:
  - `runtimeNormalizeFirstPathways`
  - `runtimeWarnAndDiagnosePathways`
  - `runtimeCanonicalPreferredPathways`
  - `runtimeRejectReadyCandidatePathways`
  - `runtimeEarlyAndroidTighteningPathways`
  - `runtimeEvidenceGatedPathways`
  - `runtimeCoordinationGatedPathways`

`verifyRuntimeToSharedContractConsistency(...)` remains report-only while now adding staged governance
checks for strictness-stage alignment and reject-ready rollout-gate safety.

This is intentionally rollout-safe:

- normalize/warn/canonical-preferred/reject-ready distinctions are explicit and reviewable;
- reject-ready remains evidence/coordination gated;
- no abrupt canonical-only runtime rejection is introduced in this phase.
