# Cross-Repository Homomorphic Mapping

**Scope:** `DannyFish-11/ufo-galaxy-android` ↔ `DannyFish-11/ufo-galaxy-realization-v2`

This document defines the official cross-repository homomorphic mapping for the most important
architectural concepts shared between the Android runtime-profile repository and the center-governed
main repository. It is intended to make later convergence work precise and low-risk by giving both
repositories a canonical mapping model rather than relying on implicit interpretation.

---

## 1. How to read this document

### 1.1 Mapping classification

Each mapping entry carries one of four classification tags:

| Tag | Meaning |
|-----|---------|
| **CANONICAL_MATCH** | Exact conceptual equivalence. Both sides express the same concept; the Android surface directly realizes the center-canonical term. Name or casing may differ slightly but semantics are identical. |
| **PARTIAL_MATCH** | Partial semantic overlap. One side has broader or narrower scope, or carries additional fields, or the mapping is conditional on context. Safe for incremental convergence but not a 1:1 replacement. |
| **TRANSITIONAL_ALIAS** | One side currently uses a local alias, bridge, or compatibility name. The semantic intent is aligned but the wire name or type surface has not yet been unified. The alias is intentionally retained as a transitional pathway and must not be treated as permanent divergence. |
| **UNRESOLVED_DIVERGENCE** | The concepts are related but the exact equivalence, scope, or authority boundary is not yet settled. Convergence requires explicit cross-repo coordination before safe renaming or replacement. |

### 1.2 Android-side reference conventions

- **Android runtime-profile repository:** `ufo-galaxy-android` (this repo)
- **Center / main repository:** `ufo-galaxy-realization-v2`
- Android surfaces are written as `ClassName.field` or `package/ClassName`.
- Center-side terms are written as canonical vocabulary strings or document references.
- Where a center-side Kotlin/code surface is inferred from protocol vocabulary alone, it is
  marked `(inferred)`.

### 1.3 Authority note

This document maps concepts only. It does not transfer authority: `RuntimeController` remains
the sole Android lifecycle authority. Center-side authority surfaces are unchanged. The mapping
does not imply that either side should unconditionally adopt the other's naming.

---

## 2. Participant / device / runtime / capability concepts

### 2.1 Participant concept

| Dimension | Android (`ufo-galaxy-android`) | Center (`ufo-galaxy-realization-v2`) | Classification |
|-----------|-------------------------------|--------------------------------------|----------------|
| Participant model type | `CanonicalParticipantModel` (`runtime/`) | `Participant` or `ParticipantNode` (inferred) | **PARTIAL_MATCH** — Android model is a typed additive projection/read-model; center owns participant-graph truth. |
| Participant identity key | `participantId` = `device_id:runtime_host_id` (via `RuntimeIdentityContracts.participantNodeId`) | `participant_node_id` | **CANONICAL_MATCH** — same composition formula; Android uses the `device_id:runtime_host_id` format explicitly. |
| Participant kind | `ParticipantKind.ANDROID_RUNTIME_HOST` | `android_runtime_host` (wire value) | **CANONICAL_MATCH** |
| Runtime tier | `ParticipantRuntimeTier` (`FULL_RUNTIME_HOST` / `PARTIAL_RUNTIME_NODE` / `COMMAND_ENDPOINT` / `OBSERVER`) | Shared tier vocabulary via `RuntimeTierContracts` | **CANONICAL_MATCH** — shared wire values defined in `RuntimeTierContracts`. |
| Autonomy level | `ParticipantAutonomyLevel.HIGH_AUTONOMY` | `high_autonomy` | **CANONICAL_MATCH** |
| Coordination role | `ParticipantCoordinationRole` (`COORDINATOR` / `PARTICIPANT`) | `coordination_role` (`coordinator`/`participant`) | **CANONICAL_MATCH** |
| Readiness state | `ParticipantReadinessState` (`READY` / `READY_WITH_FALLBACK` / `NOT_READY` / `UNKNOWN`) | Inferred readiness vocabulary | **PARTIAL_MATCH** — Android readiness state is derived from `DelegatedTargetReadinessProjection.selectionOutcome`; center may use a different readiness signal path. |
| Participation state | `RuntimeHostDescriptor.HostParticipationState` (`ACTIVE` / `INACTIVE` / `STANDBY` / `DRAINING`) | Participation state vocabulary (inferred) | **PARTIAL_MATCH** — Android `DRAINING` and `STANDBY` may not have direct center equivalents yet. |
| Participant mapper | `AndroidParticipantModelMapper.fromRuntimeHostDescriptor(...)` | Center participant assembly (inferred) | **UNRESOLVED_DIVERGENCE** — Android mapper is an additive bridge; center assembly authority and composition strategy are not yet cross-mapped. |
| Truth ownership | `ParticipantTruthBoundary` (explicitly `ownsRuntimeAttachmentTruth=false`, etc.) | Center participant-graph truth | **CANONICAL_MATCH** (intent) — Android explicitly marks its participant model as non-truth-owning. |

### 2.2 Device concept

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Device model type | `CanonicalDeviceModel` (`runtime/`) | Device model (inferred) | **PARTIAL_MATCH** — Android model is an additive projection; center owns device-graph truth. |
| Device identity key | `deviceId` / `AipMessage.device_id` | `device_id` | **CANONICAL_MATCH** |
| Device category | `DeviceCategory` (`ANDROID_FULL_RUNTIME_HOST` / `AERIAL_DRONE` / `FABRICATION_3D_PRINTER` / `EMBEDDED_CONTROLLER` / `SMART_HOME_ENDPOINT` / `SPECIALIZED_ENDPOINT`) | Device category taxonomy (inferred) | **PARTIAL_MATCH** — Android provides a shared taxonomy; center may define a different or broader category surface. |
| Runtime-host strength | `DeviceRuntimeHostStrength` (mirrors `RuntimeTierContracts` values) | Runtime-tier vocabulary | **CANONICAL_MATCH** — shared tier wire values. |
| Linked participant identity | `CanonicalDeviceModel.linkedParticipantId` = `device_id:runtime_host_id` | `participant_node_id` linkage (inferred) | **CANONICAL_MATCH** (intent) — same identity composition. |
| Truth boundary | `DeviceTruthBoundary` (explicit non-ownership flags) | Center device-plane truth | **CANONICAL_MATCH** (intent) — Android marks itself as non-truth-owning for attachment, selection, and reconnect. |
| Device mapper | `AndroidDeviceModelMapper.fromRuntimeHostDescriptor(...)` | Center device assembly (inferred) | **UNRESOLVED_DIVERGENCE** — composition strategies not yet cross-mapped. |

### 2.3 Runtime / runtime-host concept

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Runtime-host descriptor | `RuntimeHostDescriptor(hostId, formationRole, participationState)` | `runtime_host_participant` | **CANONICAL_MATCH** — Android maps to canonical `runtime_host_participant` term. |
| Runtime-host identity | `RuntimeHostDescriptor.hostId` | Runtime-host identifier (inferred) | **CANONICAL_MATCH** |
| Formation role | `RuntimeHostDescriptor.FormationRole` (`PRIMARY` / `SECONDARY` / `SATELLITE`) | Formation role vocabulary (inferred) | **PARTIAL_MATCH** — Android provides three tiers; center vocabulary may be broader. |
| Runtime lifecycle state | `RuntimeController.RuntimeState` (`Idle` / `Starting` / `Active` / `Failed` / `LocalOnly`) | Runtime state vocabulary (inferred) | **UNRESOLVED_DIVERGENCE** — `LocalOnly` is an Android-specific fallback state; center-side equivalent is not known. |
| Runtime-host lifecycle authority | `RuntimeController` (sole authority) | Center orchestrator (authority surface) | **CANONICAL_MATCH** (intent) — Android authority is Android-local; center authority is center-local; both sides respect the other's authority plane. |
| Reconnect recovery state | `ReconnectRecoveryState` (`IDLE` / `RECOVERING` / `RECOVERED` / `FAILED`) | Reconnect recovery vocabulary (inferred) | **PARTIAL_MATCH** — Android reconnect recovery is an explicit truth surface; center equivalent may be a derived or implicit state. |

### 2.4 Capability concept

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Capability report surface | `capability_report` message + `ReadinessChecker` + `AndroidCapabilityVector` | `runtime_capability_report` | **CANONICAL_MATCH** — Android maps to canonical term. |
| Capability provider model | `CanonicalCapabilityProviderModel` + `AndroidCapabilityProviderModelMapper` | `capability_provider_ref` | **CANONICAL_MATCH** — Android model maps to canonical `capability_provider_ref` term. |
| Capability provider identity | `CanonicalCapabilityProviderModel.providerId` = `capability_provider:<device_id:runtime_host_id>` | `capability_provider_ref` identity (inferred) | **CANONICAL_MATCH** — formula is `capability_provider:<participant_node_id>` via `RuntimeIdentityContracts.capabilityProviderRef`. |
| Capability dimensions | `AndroidCapabilityVector.ExecutionDimension` (`ACCESSIBILITY_EXECUTION` / `PARALLEL_SUBTASK` / `CROSS_DEVICE_COORDINATION`) | Capability dimension vocabulary (inferred) | **PARTIAL_MATCH** — Android capability dimensions are Android-specific; center may use a different or broader dimension surface. |
| Readiness signals | `model_ready` / `accessibility_ready` / `overlay_ready` / `degraded_mode` | Readiness vocabulary consumed by selection/scheduling logic | **CANONICAL_MATCH** — these signals are protocol-facing and consumed by center scheduling. |

---

## 3. Device-domain vs node-domain structures

### 3.1 Domain distinction

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Device domain definition | Hardware/runtime-host identity and capability/readiness declaration context (`CanonicalDeviceModel`, `RuntimeHostDescriptor`) | Device-plane identity and capability publication | **CANONICAL_MATCH** — both repositories treat device domain as hardware/identity-declaration context. |
| Node domain definition | Dispatch/delegation/execution-target context in the governed cross-device graph (`participant_node_id`, `source_node_id`, `target_node_id`) | Node-plane dispatch/delegation routing context | **CANONICAL_MATCH** — both treat node domain as execution-target/dispatch context. |
| Device-to-node bridge | `CanonicalDeviceModel.linkedParticipantId` + `RuntimeIdentityContracts.participantNodeId(deviceId, runtimeHostId)` | `participant_node_id` assembly from device/host identity | **CANONICAL_MATCH** — same composition formula bridges the two domains. |
| Source node identity | `device_id` (Android emitting node identity in `AipMessage`) | `source_node_id` | **TRANSITIONAL_ALIAS** — Android uses `device_id` as its source-node identity carrier; `source_node_id` is the canonical center term. Wire field is `device_id`. |
| Target node identity | `target_device_id` / `target_node` (contextual) | `target_node_id` | **TRANSITIONAL_ALIAS** — Android does not require a mandatory typed `target_node_id` in `AipMessage`; target is implied by message direction and routing context. |

---

## 4. Session families and related identifiers

### 4.1 Session taxonomy

| Session family | Android term | Android carrier(s) | Center canonical term | Classification |
|----------------|-------------|-------------------|----------------------|----------------|
| Control session | `session_id` (current wire name) | `AipMessage.session_id`, `TaskSubmitPayload.session_id`, takeover `session_id` | `control_session_id` | **TRANSITIONAL_ALIAS** — Android uses `session_id` as the control-session carrier; `control_session_id` is the canonical name. Semantic equivalence is frozen; naming convergence is follow-up work. |
| Runtime session | `runtime_session_id` | `AipMessage.runtime_session_id`, takeover/handoff envelopes, `AttachedRuntimeHostSessionSnapshot.runtimeSessionId` | `runtime_session_id` | **CANONICAL_MATCH** |
| Attached runtime host session | `AttachedRuntimeSession.sessionId` | `AttachedRuntimeSession`, `AttachedRuntimeHostSessionSnapshot`, `delegated_execution_signal.attached_session_id` | `attached_runtime_session_id` | **CANONICAL_MATCH** — Android term maps exactly to canonical term. |
| Delegation / transfer session | `takeover_request.session_id` + `takeover_response` + `delegated_execution_signal` lifecycle | `transfer_session_context` | **CANONICAL_MATCH** (scope) — transfer session context is the umbrella; Android covers the same lifecycle. |
| Mesh session | `mesh_id` | `MeshJoinPayload.mesh_id`, `MeshResultPayload.mesh_id` | `mesh_session_id` | **TRANSITIONAL_ALIAS** — Android wire name is `mesh_id`; canonical term is `mesh_session_id`. Semantic equivalence is frozen; wire name convergence is follow-up work. |
| Conversation / history session | `LocalLoopTrace.sessionId`, `SessionHistorySummary.sessionId` | local trace/history surfaces | `conversation_session_id` | **CANONICAL_MATCH** |

### 4.2 Session identifier roles

| Identifier | Android surface | Role | Classification |
|------------|----------------|------|----------------|
| `task_id` | `TaskSubmitPayload.task_id`, `TaskAssignPayload.task_id`, `TaskResultPayload.task_id`, takeover/delegated payloads | Primary per-task identity; stable across a distributed execution chain | **CANONICAL_MATCH** |
| `trace_id` | `AipMessage.trace_id`, delegated payload `trace_id` | End-to-end correlation identity; stable across one distributed execution chain | **CANONICAL_MATCH** |
| `signal_id` | `DelegatedExecutionSignal.signalId` | Delegated execution emission idempotency key; used for host-side dedupe/replay | **CANONICAL_MATCH** — maps to `execution_instance_id` for the delegated execution path. |
| `emission_seq` | `DelegatedExecutionSignal.emissionSeq` (ACK=1 / PROGRESS=2 / RESULT=3) | Ordered emission position for delegated lifecycle; companion to `signal_id` for replay safety | **PARTIAL_MATCH** — Android-specific sequencing convention; center equivalent ordering strategy is not yet cross-mapped. |
| `idempotency_key` | `AipMessage`-level general envelope dedupe | General envelope-level idempotency key | **TRANSITIONAL_ALIAS** — Android uses `idempotency_key` for general envelope dedupe; the canonical `execution_instance_id` covers a narrower delegated-path scope. |

### 4.3 Session lifecycle state terms

| State / cause | Android type | Android wire value | Center canonical term | Classification |
|---------------|-------------|-------------------|----------------------|----------------|
| Attached | `AttachedRuntimeSession.State.ATTACHED` | `attached` | `attached` | **CANONICAL_MATCH** |
| Detaching | `AttachedRuntimeSession.State.DETACHING` | `detaching` | `detaching` | **CANONICAL_MATCH** |
| Detached | `AttachedRuntimeSession.State.DETACHED` | `detached` | `detached` | **CANONICAL_MATCH** |
| Explicit detach cause | `AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH` | `explicit_detach` | `explicit_detach` | **CANONICAL_MATCH** |
| Disconnect detach cause | `AttachedRuntimeSession.DetachCause.DISCONNECT` | `disconnect` | `disconnect` | **CANONICAL_MATCH** |
| Disable detach cause | `AttachedRuntimeSession.DetachCause.DISABLE` | `disable` | `disable` | **CANONICAL_MATCH** |
| Invalidation detach cause | `AttachedRuntimeSession.DetachCause.INVALIDATION` | `invalidation` | `invalidation` | **CANONICAL_MATCH** |
| Reconnect recovery: idle | `ReconnectRecoveryState.IDLE` | `idle` | Reconnect lifecycle vocabulary (inferred) | **PARTIAL_MATCH** — Android has an explicit typed truth surface; center equivalent is not yet formally mapped. |
| Reconnect recovery: recovering | `ReconnectRecoveryState.RECOVERING` | `recovering` | Reconnect lifecycle vocabulary (inferred) | **PARTIAL_MATCH** |
| Reconnect recovery: recovered | `ReconnectRecoveryState.RECOVERED` | `recovered` | Reconnect lifecycle vocabulary (inferred) | **PARTIAL_MATCH** |
| Reconnect recovery: failed | `ReconnectRecoveryState.FAILED` | `failed` | Reconnect lifecycle vocabulary (inferred) | **PARTIAL_MATCH** |

---

## 5. Delegated execution signal and result structures

### 5.1 Signal structure

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Signal type | `DelegatedExecutionSignal` (`runtime/`) | `delegated_execution_signal` (wire family) | **CANONICAL_MATCH** |
| Signal kinds | `DelegatedExecutionSignal.Kind` (`ACK` / `PROGRESS` / `RESULT`) | `signal_kind`: `ack` / `progress` / `result` | **CANONICAL_MATCH** |
| Result kinds (terminal) | `DelegatedExecutionSignal.ResultKind` (`COMPLETED` / `FAILED` / `TIMEOUT` / `CANCELLED` / `REJECTED`) | `result_kind`: `completed` / `failed` / `timeout` / `cancelled` / `rejected` | **CANONICAL_MATCH** |
| Signal identity | `signalId` (UUID idempotency key) | `signal_id` | **CANONICAL_MATCH** |
| Emission sequence | `emissionSeq` (Int: ACK=1, PROGRESS=2, RESULT=3) | `emission_seq` | **CANONICAL_MATCH** |
| Attached session binding | `delegated_execution_signal.attached_session_id` | Attached-session binding in delegated signal | **CANONICAL_MATCH** |
| Handoff contract version | `DelegatedHandoffContract.CURRENT_CONTRACT_VERSION` / `KEY_HANDOFF_CONTRACT_VERSION` | Handoff contract versioning (inferred) | **PARTIAL_MATCH** — Android has an explicit contract version field; center-side versioning model is not yet cross-mapped. |
| Replay safety | `DelegatedExecutionSignal.replayAt()` preserves `signalId` + `emissionSeq`; `EmittedSignalLedger` stores last emitted signal per kind | Replay / dedupe semantics (inferred) | **PARTIAL_MATCH** — Android has an explicit replay-safe identity model; center replay/dedupe strategy is not yet formally mapped. |

### 5.2 Takeover / transfer handshake

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Takeover request | `TakeoverEnvelope` / `takeover_request` | `takeover_request` | **CANONICAL_MATCH** |
| Takeover response | `takeover_response` | `takeover_response` | **CANONICAL_MATCH** |
| Transfer accept | `takeover_response.accepted=true` | `transfer_accept` | **CANONICAL_MATCH** (semantic mapping) |
| Transfer reject | `takeover_response.accepted=false` OR `result_kind=rejected` | `transfer_reject` | **CANONICAL_MATCH** (semantic mapping) |
| Transfer cancel | `delegated_execution_signal.result_kind=cancelled` | `transfer_cancel` | **CANONICAL_MATCH** (semantic mapping) |
| Transfer expire | `delegated_execution_signal.result_kind=timeout` | `transfer_expire` | **CANONICAL_MATCH** (semantic mapping) |
| Transfer adopt | `delegated_handoff_contract.continuation_token` present | `transfer_adopt` | **CANONICAL_MATCH** (semantic mapping) |
| Transfer resume | `delegated_handoff_contract.handoff_reason=continuation` | `transfer_resume` | **CANONICAL_MATCH** (semantic mapping) |
| Takeover fallback event | `TakeoverFallbackEvent` (causes: `FAILED` / `TIMEOUT` / `CANCELLED` / `DISCONNECT`) | Transfer fallback event (inferred) | **PARTIAL_MATCH** — Android `TakeoverFallbackEvent` is an observational event emission, not an authoritative truth surface; center fallback representation is not yet formally mapped. |

### 5.3 Result / terminal state vocabulary

| Status term | Android usage | Center canonical term | Classification |
|-------------|--------------|----------------------|----------------|
| `success` | `task_result`, `goal_result`, mesh aggregate result | `success` | **CANONICAL_MATCH** |
| `error` | `task_result`, `goal_result` (canonical) | `error` | **CANONICAL_MATCH** |
| `failed` → `error` | Legacy Android variant (normalized) | `error` (canonical) | **TRANSITIONAL_ALIAS** — `failed` normalizes to `error` via `lifecycleStatusNormalizations`. |
| `completed` → `success` | Legacy Android variant (normalized) | `success` (canonical) | **TRANSITIONAL_ALIAS** — `completed` normalizes to `success` via `lifecycleStatusNormalizations`. |
| `cancelled` | Delegated / task cancel terminal | `cancelled` | **CANONICAL_MATCH** |
| `timeout` | Delegated timeout terminal | `timeout` | **CANONICAL_MATCH** |
| `rejected` | Delegated rejection terminal | `rejected` | **CANONICAL_MATCH** |
| `partial` | Mesh aggregate partial result | `partial` | **CANONICAL_MATCH** |
| `disabled` / `no_op` → `disabled` | Runtime-disabled state; `no_op` normalizes | `disabled` | **TRANSITIONAL_ALIAS** — `no_op` normalizes to `disabled`; canonical term is `disabled`. |

### 5.4 Execution route tracking

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Execution route tag | `ExecutionRouteTag` (`LOCAL` / `CROSS_DEVICE` / `DELEGATED` / `FALLBACK`) | Execution route / dispatch category (inferred) | **UNRESOLVED_DIVERGENCE** — Android tracks execution route for observability; center dispatch routing model is not yet formally mapped to this surface. |
| Staged mesh execution | `StagedMeshExecutionTarget` + `StagedMeshParticipationResult` | Staged mesh / multi-device subtask participation (inferred) | **PARTIAL_MATCH** — Android provides typed participation result with identity chain (`meshId` / `subtaskId` / `taskId` / `deviceId`); center multi-device dispatch model is not yet cross-mapped at this level. |

---

## 6. Protocol alignment models and shared schema vocabulary

### 6.1 Message family canonical mapping

| Android message family | UGCP schema family | Center semantic role | Classification |
|------------------------|-------------------|---------------------|----------------|
| `device_register` | `IDENTITY` | Runtime node identity/presence registration | **CANONICAL_MATCH** |
| `capability_report` | `RUNTIME` | Runtime profile + readiness/capability declaration | **CANONICAL_MATCH** |
| `heartbeat` / `heartbeat_ack` | `RUNTIME` | Liveness continuity for control/runtime session | **CANONICAL_MATCH** |
| `task_submit` | `CONTROL` | Control-plane ingress request from source runtime | **CANONICAL_MATCH** |
| `task_assign` | `CONTROL` | Assignment decision delivery from control-plane | **CANONICAL_MATCH** |
| `task_result` | `TRUTH` | Execution terminal outcome projection to control plane | **CANONICAL_MATCH** |
| `command_result` | `TRUTH` | Command execution terminal outcome | **CANONICAL_MATCH** |
| `goal_result` | `TRUTH` | Goal execution terminal outcome | **CANONICAL_MATCH** |
| `goal_execution_result` | `TRUTH` | Goal execution terminal outcome (alternate form) | **CANONICAL_MATCH** |
| `takeover_request` | `CONTROL` | Control-transfer handshake initiation | **CANONICAL_MATCH** |
| `takeover_response` | `CONTROL` | Control-transfer handshake response | **CANONICAL_MATCH** |
| `delegated_execution_signal` | `RUNTIME` | Delegated lifecycle stream for host-side tracker reconciliation | **CANONICAL_MATCH** |
| `mesh_join` | `COORDINATION` | Coordination participant joined | **CANONICAL_MATCH** |
| `mesh_leave` | `COORDINATION` | Coordination participant left | **CANONICAL_MATCH** |
| `mesh_result` | `COORDINATION` | Coordination execution result reported | **CANONICAL_MATCH** |

### 6.2 Protocol profile declarations

| Profile | Android declaration | Status | Classification |
|---------|-------------------|--------|----------------|
| Runtime WS Profile | `ugcp.runtime_ws_profile.android` (transport: `aip_ws`) | `incremental_alignment` | **CANONICAL_MATCH** — Android explicitly declares participation in the center's UGCP Runtime WS Profile. |
| Control Transfer Profile | `ugcp.control_transfer_profile.android` | `incremental_alignment` | **CANONICAL_MATCH** |
| Coordination Profile | `ugcp.coordination_profile.android` | `incremental_alignment` | **CANONICAL_MATCH** |
| Truth/Event Model | `ugcp.truth_event_model.android` | `incremental_alignment` | **CANONICAL_MATCH** |
| Conformance Surface | `ugcp.conformance_surface.android` | `incremental_alignment` | **CANONICAL_MATCH** |

### 6.3 Compatibility / legacy alias surface

| Android alias / normalization | Canonical target | Classification |
|-------------------------------|-----------------|----------------|
| `MsgType.LEGACY_TYPE_MAP` | Canonical message type routing via `MsgType` | **TRANSITIONAL_ALIAS** — legacy message type aliases are centralized and tolerated via `compatibilityAliasNormalizations`; retirement is phased. |
| `lifecycleStatusNormalizations` (`failed→error`, `completed→success`, `no_op→disabled`) | Canonical lifecycle status vocabulary | **TRANSITIONAL_ALIAS** — normalization map centralizes legacy status terms; phased retirement planned. |
| `transitionalCompatibilityMessageFamilies` | Canonical `runtimeWsProfileMessageFamilies` | **TRANSITIONAL_ALIAS** — advanced message types not yet promoted to canonical runtime profile. |

### 6.4 Schema alignment registry

| Android surface | Role | Classification |
|----------------|------|----------------|
| `UgcpSharedSchemaAlignment` (`protocol/`) | Canonical Android-side alignment registry: identity, message-family, transfer, coordination, truth/event, conformance tier, enforcement, migration-readiness, pathway audit | **CANONICAL_MATCH** — this object is the Android-side canonical alignment surface; its structure is intentionally aligned to center-governed protocol governance vocabulary. |
| `UgcpSharedSchemaAlignment.identityAlignments` | Android → canonical identity term mapping | **CANONICAL_MATCH** |
| `UgcpSharedSchemaAlignment.canonicalConceptVocabulary` | Android → canonical concept boundary glossary | **CANONICAL_MATCH** |
| `UgcpSharedSchemaAlignment.runtimeToCanonicalPathwayInventory` | Audit inventory of all Android runtime-to-canonical pathway mappings with strictness/rollout gates | **CANONICAL_MATCH** |

---

## 7. Registry, facade, cache, adapter, and authority surfaces

### 7.1 Registry

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Schema alignment registry | `UgcpSharedSchemaAlignment` — message-family, concept, identity, and pathway alignment maps | Center protocol/schema registry (inferred) | **PARTIAL_MATCH** — Android registry is an explicit read-only alignment surface; center registry is the governance authority. Both surfaces are complementary, not competing. |
| Session layer contracts | `AndroidSessionLayerContracts.contracts` / `UgcpSharedSchemaAlignment.sessionLayerContracts` | Session layer contract surface (inferred) | **PARTIAL_MATCH** — Android exposes session layer contracts as an alignment aid; center owns session layer governance. |
| Capability registry | `AndroidCapabilityVector` + `ReadinessChecker` declare capability/readiness surface | Capability/scheduling registry (inferred) | **PARTIAL_MATCH** — Android publishes capabilities via `capability_report`; center registry consumes and governs scheduling. |

### 7.2 Facade

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Runtime lifecycle facade | `RuntimeController` — sole lifecycle authority for WS connect/disconnect, `crossDeviceEnabled`, attached session, host participation state | Center orchestrator (inferred) | **CANONICAL_MATCH** (intent) — `RuntimeController` is Android's single lifecycle facade; center has its own authority surface; both are intentionally bounded. |
| Uplink facade | `GalaxyWebSocketClient` — sole outbound cross-device uplink | Center inbound WS endpoint | **CANONICAL_MATCH** (intent) — all outbound Android messages flow through a single facade. |
| Input routing facade | `InputRouter` — sole user-input dispatch gate | Center ingress / task routing (inferred) | **PARTIAL_MATCH** — `InputRouter` owns the Android routing decision; center owns assignment and dispatch governance. |

### 7.3 Cache

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Offline result queue | `OfflineTaskQueue` — bounded replay buffer (max 50, 24 h TTL) for result payloads | Center result durability layer (inferred) | **PARTIAL_MATCH** — Android provides bounded operational cache for reconnect replay; center durable result storage is a separate authority. |
| Agent runtime bridge cache | `AgentRuntimeBridge` — idempotent with 200-entry in-memory cache | Center dispatch cache (inferred) | **PARTIAL_MATCH** — Android bridge cache is local dedup; center dispatch dedup is a separate surface. |
| Emitted signal ledger | `EmittedSignalLedger` — last-emitted signal per kind for replay-safe identity | Replay / event ledger (inferred) | **PARTIAL_MATCH** — Android ledger is a local signal-replay aid; center event ledger semantics are not yet formally mapped. |

### 7.4 Adapter

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Participant model adapter | `AndroidParticipantModelMapper.fromRuntimeHostDescriptor(...)` | Center participant assembly | **TRANSITIONAL_ALIAS** — Android mapper is an additive bridge converting `RuntimeHostDescriptor` → `CanonicalParticipantModel`; it is explicitly non-authoritative and not a permanent separate model. |
| Device model adapter | `AndroidDeviceModelMapper.fromRuntimeHostDescriptor(...)` | Center device assembly | **TRANSITIONAL_ALIAS** — same as above; additive projection bridge, not an authority surface. |
| Capability provider adapter | `AndroidCapabilityProviderModelMapper` | Center capability provider assembly | **TRANSITIONAL_ALIAS** — additive bridge for capability-plane convergence. |
| Legacy message type adapter | `MsgType.toV3Type()` + `classifyMessageTypeHandling(rawType)` | Center ingress normalization (inferred) | **TRANSITIONAL_ALIAS** — Android normalizes legacy message types before canonical routing; this bridge is retained for backward compatibility and is slated for phased retirement. |
| Legacy session map adapter | `RuntimeController.currentSessionSnapshot()` — returns `Map<String,Any>?` | Center host-session typed contract (inferred) | **TRANSITIONAL_ALIAS** — legacy map accessor retained while `hostSessionSnapshot` (typed projection) is preferred; retirement is follow-up work. |

### 7.5 Authority surfaces

| Dimension | Android authority surface | Center authority surface | Classification |
|-----------|--------------------------|-------------------------|----------------|
| Cross-device lifecycle | `RuntimeController` (WS connect/disconnect, `crossDeviceEnabled`, session open/close) | Center orchestrator | **CANONICAL_MATCH** (intent) — each side owns its own authority plane; both are intentionally bounded. |
| Attached session truth | `RuntimeController.hostSessionSnapshot: StateFlow<AttachedRuntimeHostSessionSnapshot?>` | Center attached-session truth surface | **CANONICAL_MATCH** (intent) — Android projects its authoritative truth upward; center holds system-level truth. |
| Delegated selection truth | `RuntimeController.targetReadinessProjection: StateFlow<DelegatedTargetReadinessProjection?>` | Center selection/scheduling truth | **CANONICAL_MATCH** (intent) — Android readiness projection is consumed by center scheduling logic. |
| Reconnect recovery truth | `RuntimeController.reconnectRecoveryState: StateFlow<ReconnectRecoveryState>` | Center reconnect recovery signal | **PARTIAL_MATCH** — Android truth surface is explicit and typed; center consumption model is not yet formally mapped. |
| Terminal result truth | `task_result` / `command_result` / `goal_result` / `goal_execution_result` payloads | Center execution result truth | **CANONICAL_MATCH** — Android terminal result payloads are the Android-side authoritative execution outcome reports sent to center truth surfaces. |
| Rollout control authority | `RolloutControlSnapshot` (`crossDeviceAllowed` / `delegatedExecutionAllowed` / `fallbackToLocalAllowed` / `goalExecutionAllowed`) | Center policy authority (inferred) | **PARTIAL_MATCH** — Android rollout controls gate local behavior; center policy authority governs system-level rollout; relationship between the two gating layers is not yet formally mapped. |

---

## 8. Runtime identity and capability-provider structures

### 8.1 Identity composition

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Identity contracts helper | `RuntimeIdentityContracts` (`runtime/`) | Identity composition (inferred) | **CANONICAL_MATCH** (intent) — `RuntimeIdentityContracts` centralizes the canonical identity composition formulas. |
| Participant / node identity formula | `device_id:runtime_host_id` via `RuntimeIdentityContracts.participantNodeId` | `participant_node_id` | **CANONICAL_MATCH** |
| Capability-provider reference formula | `capability_provider:<device_id:runtime_host_id>` via `RuntimeIdentityContracts.capabilityProviderRef` | `capability_provider_ref` identity | **CANONICAL_MATCH** |
| Runtime-host identity key | `RuntimeHostDescriptor.hostId` | Runtime-host identifier (inferred) | **CANONICAL_MATCH** |
| Per-connection runtime session UUID | `RuntimeController._currentRuntimeSessionId` (set in `openAttachedSession`, cleared in `closeAttachedSession`) | `runtime_session_id` per connection scope | **CANONICAL_MATCH** |

### 8.2 Capability-provider structures

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Capability provider model | `CanonicalCapabilityProviderModel` | `capability_provider_ref` contract | **CANONICAL_MATCH** (term) |
| Provider identity | `CanonicalCapabilityProviderModel.providerId` = `capability_provider:<participant_node_id>` | Canonical provider reference identity | **CANONICAL_MATCH** |
| Provider truth boundary | `CanonicalCapabilityProviderModel` does not own runtime identity, attachment truth, reconnect truth, or participant truth | Center capability-plane contract | **CANONICAL_MATCH** (boundary intent) |
| Execution dimensions | `AndroidCapabilityVector.ExecutionDimension` | Capability execution dimension vocabulary (inferred) | **PARTIAL_MATCH** — Android execution dimensions are Android-specific (`ACCESSIBILITY_EXECUTION`, `PARALLEL_SUBTASK`, `CROSS_DEVICE_COORDINATION`); center capability vocabulary may have a different structure. |

### 8.3 Truth/event identity

| Dimension | Android | Center | Classification |
|-----------|---------|--------|----------------|
| Authoritative state transitions | `RuntimeController.state`, `hostSessionSnapshot`, `targetReadinessProjection`, `reconnectRecoveryState` | Center truth event surfaces | **CANONICAL_MATCH** — Android explicitly classifies these as `AUTHORITATIVE_STATE_TRANSITION` in `UgcpTruthEventAlignment`. |
| Authoritative result reports | `task_result` / `command_result` / `goal_result` / `goal_execution_result` / `mesh_result.status in {success,partial,error}` | Center execution result truth | **CANONICAL_MATCH** — explicitly classified as `AUTHORITATIVE_RESULT_REPORT`. |
| Observational event emissions | `RuntimeController.takeoverFailure`, `delegated_execution_signal` stream, `mesh_join` / `mesh_leave` | Center event/notification layer | **CANONICAL_MATCH** (boundary intent) — explicitly classified as `OBSERVATIONAL_EVENT_EMISSION` on Android; these do not replace authoritative truth surfaces on either side. |

---

## 9. Unresolved divergences and follow-up work

The following entries are currently classified as `UNRESOLVED_DIVERGENCE` or `PARTIAL_MATCH` and
require explicit cross-repository coordination before safe convergence work can proceed.

| Topic | Android surface | Blocking question | Suggested follow-up |
|-------|----------------|-------------------|---------------------|
| Participant / device assembly strategy | `AndroidParticipantModelMapper`, `AndroidDeviceModelMapper` | How does the center assemble participant/device models? Is the formula identical? Are there additional fields? | Map center participant/device assembly in a follow-up cross-repo session. |
| Rollout control ↔ center policy gating | `RolloutControlSnapshot` | Does center governance emit policy that Android should reflect directly, or does Android maintain independent local kill-switch semantics? | Define the gating protocol explicitly across both repos. |
| Reconnect recovery state consumption | `ReconnectRecoveryState` | How does center consume Android reconnect recovery signals? Is there a center-side reconnect truth surface to align with? | Document center reconnect consumption path. |
| Execution route tracking integration | `ExecutionRouteTag` | Is there a center-side execution route / dispatch-category concept that maps to Android's LOCAL / CROSS_DEVICE / DELEGATED / FALLBACK? | Map center dispatch-category vocabulary if it exists. |
| Staged mesh subtask identity chain | `StagedMeshParticipationResult` (`meshId` / `subtaskId` / `taskId` / `deviceId`) | Does center track this identity chain explicitly? | Cross-map center multi-device subtask tracking model. |
| `LocalOnly` runtime state | `RuntimeController.RuntimeState.LocalOnly` | Is there a center-side concept for a runtime participant in local-only fallback mode? | Define whether center should be aware of Android `LocalOnly` mode. |
| `DRAINING` and `STANDBY` participation states | `RuntimeHostDescriptor.HostParticipationState.DRAINING / STANDBY` | Do these states have center-side equivalents in the participation-state vocabulary? | Map center participation state vocabulary explicitly. |
| Handoff contract versioning | `DelegatedHandoffContract.CURRENT_CONTRACT_VERSION` | Is there a center-side handoff contract version surface? | Align versioning contract across both repos. |

---

## 10. Summary: mapping coverage by area

| Area | Canonical match | Partial match | Transitional alias | Unresolved divergence |
|------|----------------|---------------|-------------------|-----------------------|
| Participant concept | ✓ (identity formula, kind, tier, roles, truth boundary) | ✓ (readiness derivation, participation state range) | — | ✓ (assembly strategy) |
| Device concept | ✓ (identity, tier vocabulary, truth boundary) | ✓ (category taxonomy) | — | ✓ (assembly strategy) |
| Runtime / runtime-host | ✓ (descriptor, identity, authority intent) | ✓ (reconnect truth consumption, formation role range) | — | ✓ (`LocalOnly` state, `DRAINING`/`STANDBY`) |
| Capability | ✓ (capability_report, provider identity, readiness signals) | ✓ (execution dimensions) | — | — |
| Device-domain / node-domain | ✓ (domain definitions, bridge formula) | — | ✓ (source/target node wire names) | — |
| Session families | ✓ (runtime_session_id, attached_session_id, conversation_session_id, transfer_session) | ✓ (reconnect recovery states) | ✓ (session_id→control_session_id, mesh_id→mesh_session_id) | — |
| Session identifiers | ✓ (task_id, trace_id, signal_id) | ✓ (emission_seq convention) | ✓ (idempotency_key vs execution_instance_id) | — |
| Delegated execution signals | ✓ (signal structure, kinds, result kinds, signal identity) | ✓ (replay safety model, handoff versioning) | — | — |
| Transfer handshake | ✓ (all 6 transfer lifecycle semantics) | ✓ (fallback event) | — | — |
| Terminal state vocabulary | ✓ (success, error, cancelled, timeout, rejected, partial) | — | ✓ (failed→error, completed→success, no_op→disabled) | — |
| Protocol profiles | ✓ (all 5 profile declarations) | — | — | — |
| Message families | ✓ (all 16 canonical families) | — | ✓ (legacy alias families) | — |
| Schema alignment registry | ✓ (`UgcpSharedSchemaAlignment` structure) | — | — | — |
| Registry / facade / authority | ✓ (lifecycle, uplink, truth, terminal result) | ✓ (capability registry, rollout gating) | — | ✓ (rollout control ↔ center policy) |
| Cache | — | ✓ (offline queue, bridge cache, signal ledger) | — | — |
| Adapter | — | — | ✓ (participant/device/capability provider adapters, legacy type/session adapters) | — |
| Runtime identity / composition | ✓ (all identity formulas) | — | — | — |
| Capability-provider structures | ✓ (provider identity, truth boundary) | ✓ (execution dimensions) | — | — |
| Truth/event identity | ✓ (all three semantic classes) | — | — | — |

---

## 11. Relationship to other alignment documents

| Document | Relationship |
|----------|-------------|
| [`docs/ugcp/ANDROID_UGCP_CONSTITUTION.md`](./ANDROID_UGCP_CONSTITUTION.md) | Android-side protocol role declaration, vocabulary freeze, phase mapping, and constitutional alignment. The constitution defines Android's position in the governed system; this mapping document provides the formal per-concept equivalence model. |
| [`app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt`](../../app/src/main/java/com/ufo/galaxy/protocol/UgcpSharedSchemaAlignment.kt) | Code-level alignment registry for message families, identity terms, concept vocabulary, pathway audit, and conformance tier. The mapping document describes the cross-repo picture; `UgcpSharedSchemaAlignment` is the Android-side runtime realization of the alignment. |
| [`docs/architecture.md`](../architecture.md) | Canonical Android component index and authority boundaries. The mapping document references components documented there. |
