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
