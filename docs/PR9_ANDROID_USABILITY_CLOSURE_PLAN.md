# PR-9 Android — Complete-System Usability Closure Plan

## Android-Side Framework for Turning Code-Grounded Review Findings into Closure Criteria

**Primary repository:** `DannyFish-11/ufo-galaxy-android` (this repo)  
**Companion context:** `DannyFish-11/ufo-galaxy-realization-v2`  
**Builds on:** PR-8 Android — Full-System Product Readiness Review (`docs/PR8_PRODUCT_READINESS_REVIEW.md`)  
**Authority model:** V2 (`DesktopPresenceRuntime`) remains canonical system authority; Android is a participant/runtime contributor.  
**Date:** 2026-04-25

---

## Purpose of This Document

PR-8 Android produced a code-grounded, full-system product readiness review.  That review
established **what the system can and cannot do today** from the Android/client/runtime
perspective.

This document — PR-9 Android — converts those findings into a **concrete, reviewer-usable
usability closure plan**: a structured framework of closure dimensions, status categorizations,
decision points, and follow-up tracks that reviewers and future PRs can use to drive the
dual-repo system from *"deeply reviewed"* toward *"complete, runnable, smooth, usable product."*

**This plan does not claim the system is already fully usable.**  Instead it makes the remaining
closure work explicit and actionable.

---

## How to Read This Document

### Status symbols

| Symbol | Meaning |
|--------|---------|
| ✅ **VERIFIED** | Capability is confirmed by direct code reading, test evidence, or integration evidence |
| ⚠️ **PARTIAL** | Structural code is present and the path exists, but it is not yet operationally smooth, fully closed, or turn-key |
| ❌ **MISSING** | Capability is absent, clearly blocked, or the gap was explicitly confirmed by review |
| 🔲 **UNCLEAR** | Status cannot be determined from Android-side code alone; requires V2-side verification or runtime test |

### Closure category columns

Each dimension table uses:
- **Closure Item** — the specific capability or property being assessed
- **Status** — one of the four symbols above
- **Android Evidence** — the real module/file/test that grounds the status claim
- **Gap / Blocker** — what is preventing full closure (for ⚠️ / ❌ / 🔲 items)
- **Closure Track** — the follow-up PR track that can close this item

---

## Table of Contents

1. [Dimension 1: Android Buildability / Startup Closure](#1-android-buildability--startup-closure)
2. [Dimension 2: Android Runtime / Participant Execution Closure](#2-android-runtime--participant-execution-closure)
3. [Dimension 3: Local Persistence / Offline Queue / Replay / Recovery Closure](#3-local-persistence--offline-queue--replay--recovery-closure)
4. [Dimension 4: Android ↔ V2 Integration Closure](#4-android--v2-integration-closure)
5. [Dimension 5: Artifact / Signal / Readiness Evidence Closure](#5-artifact--signal--readiness-evidence-closure)
6. [Dimension 6: Operator / Debugging / Reviewability Closure](#6-operator--debugging--reviewability-closure)
7. [Dimension 7: End-to-End Smooth Usability from Android Outward](#7-end-to-end-smooth-usability-from-android-outward)
8. [Closure Status Summary Matrix](#8-closure-status-summary-matrix)
9. [Follow-Up PR Tracks](#9-follow-up-pr-tracks)
10. [V2 Authority and Boundary Notes](#10-v2-authority-and-boundary-notes)
11. [How to Update This Plan](#11-how-to-update-this-plan)

---

## 1. Android Buildability / Startup Closure

**Goal:** The Android app builds cleanly, starts deterministically, and reaches a ready-to-use
state without requiring manual developer intervention beyond APK installation.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| Gradle project structure and build scripts compile without error | ✅ VERIFIED | `build.gradle`, `settings.gradle`, `app/build.gradle` | — | — |
| Application class initializes all core services in correct order | ✅ VERIFIED | `UFOGalaxyApplication.onCreate` (14-step init sequence) | — | — |
| `BootReceiver` starts services correctly on device reboot | ✅ VERIFIED | `BootReceiver` + `AndroidManifest.xml` | — | — |
| `GalaxyConnectionService` starts and maintains foreground service lifecycle | ✅ VERIFIED | `GalaxyConnectionService` + notification channel setup | — | — |
| `EnhancedFloatingService` overlay starts (requires permission) | ⚠️ PARTIAL | `EnhancedFloatingService` | `SYSTEM_ALERT_WINDOW` must be manually granted; no guided flow | Track A |
| `SYSTEM_ALERT_WINDOW` permission grant is guided on first run | ❌ MISSING | No first-run permission wizard found in source | No in-app setup activity exists | Track A |
| `HardwareKeyReceiver` declared in manifest is resolvable at runtime | ❌ MISSING | `AndroidManifest.xml` declares `HardwareKeyReceiver`; class not found in sources | `ClassNotFoundException` risk when `MEDIA_BUTTON` intent fires | Track B |
| App config (`config.properties`) contains a valid default gateway URL | ⚠️ PARTIAL | `config.properties` (default: `ws://100.x.x.x:8765`) | Placeholder IP must be replaced before any cross-device operation | Track C |
| Startup boot-time readiness checks surface actionable errors to user | ⚠️ PARTIAL | `UFOGalaxyApplication.runReadinessChecks()` | Checks run but failures are logged, not surfaced as user-facing guidance | Track A |

**Dimension closure verdict:** ⚠️ PARTIAL — Buildable and structurally sound; startup sequence
is complete and correct; but first-run experience requires manual operator steps with no in-app
guidance, and the `HardwareKeyReceiver` manifest risk remains unresolved.

---

## 2. Android Runtime / Participant Execution Closure

**Goal:** Once running, the Android app can receive, execute, and report on tasks, both from
local user input and from V2-dispatched task assignments.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| Local user input (text) routed through execution pipeline | ✅ VERIFIED | `NaturalLanguageInputManager` → `InputRouter` → `LocalLoopExecutor` → `EdgeExecutor` | — | — |
| Local user input (voice) routed through execution pipeline | ✅ VERIFIED | `SpeechInputManager` → `InputRouter` → same pipeline | — | — |
| V2-dispatched `task_assign` messages handled by `EdgeExecutor` | ✅ VERIFIED | `GalaxyConnectionService` inbound dispatcher → `EdgeExecutor` | — | — |
| `goal_execution` and `parallel_subtask` messages handled | ✅ VERIFIED | `AutonomousExecutionPipeline.handleGoalExecution/handleParallelSubtask` | — | — |
| `task_cancel` messages correctly cancel in-flight tasks | ✅ VERIFIED | `TaskCancelRegistry.cancel()` | — | — |
| Posture gate prevents execution in `CONTROL_ONLY` mode | ✅ VERIFIED | `InputRouter` posture check, `LocalLoopExecutor` posture gate | — | — |
| `EdgeExecutor` full pipeline: screenshot → plan → ground → action | ✅ VERIFIED | `EdgeExecutor.kt` (plan/ground/action phases) | — | — |
| `MobileVlmPlanner` generates plans via local HTTP inference | ⚠️ PARTIAL | `MobileVlmPlanner` (HTTP POST to `127.0.0.1:8080`) | Inference server must be started externally; not bundled or auto-started | Track D |
| `SeeClickGroundingEngine` resolves screen coordinates | ⚠️ PARTIAL | `SeeClickGroundingEngine` (HTTP POST to localhost) | Same external inference server dependency as planner | Track D |
| `AccessibilityActionExecutor` executes UI actions on device | ⚠️ PARTIAL | `AccessibilityActionExecutor` | Accessibility Service permission must be granted manually; no guided flow | Track A |
| `AgentRuntimeBridge` hands off eligible tasks to Agent Runtime | ✅ VERIFIED | `AgentRuntimeBridge.handoff()` invoked from `GalaxyConnectionService` dispatcher | — | — |
| Local-only mode (`crossDeviceEnabled=false`) functions end-to-end | ✅ VERIFIED | `RuntimeController.connectIfEnabled()` skips WS when disabled | — | — |
| Cross-device mode functions when gateway URL is correctly configured | ✅ VERIFIED | `GalaxyWebSocketClient` + `AppSettings.crossDeviceEnabled` | Requires manual config — not turn-key | Track C |
| `cross_device_enabled` defaults to `false`; upgrade path to `true` is guided | ⚠️ PARTIAL | `AppSettings` default | No in-app toggle guidance; must be set manually in Settings | Track A / Track C |

**Dimension closure verdict:** ⚠️ PARTIAL — All execution paths are structurally wired and
verified by code; the core pipeline runs. Closure is blocked by the external inference server
dependency (no bundling or auto-start), unguided permission flows, and manual config requirements.

---

## 3. Local Persistence / Offline Queue / Replay / Recovery Closure

**Goal:** The Android app correctly persists state across disconnects, replays queued work
on reconnect, and makes bounded recovery decisions that integrate with V2's canonical
recovery authority.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| `OfflineTaskQueue` persists task results to `SharedPreferences` | ✅ VERIFIED | `OfflineTaskQueue` (FIFO, 24 h TTL, `SharedPreferences`) | — | — |
| Queue is session-authority-bounded (discards stale-session entries) | ✅ VERIFIED | `OfflineTaskQueue.discardForDifferentSession()` | — | — |
| Queue drain sends buffered results to V2 on reconnect | ✅ VERIFIED | `GalaxyConnectionService` reconnect path → queue drain | — | — |
| `AndroidRecoveryParticipationOwner` makes bounded recovery decisions | ✅ VERIFIED | `AndroidRecoveryParticipationOwner` (6 decision types) | — | — |
| `DelegatedFlowContinuityStore` persists durable session context | ✅ VERIFIED | `DelegatedFlowContinuityStore` (persists continuity records) | — | — |
| Recovery decisions presented to V2 before self-continuation | ✅ VERIFIED | `WaitForV2ReplayDecision` path: Android presents; V2 confirms | — | — |
| `EmittedSignalLedger` prevents duplicate ACK/PROGRESS after terminal RESULT | ✅ VERIFIED | `EmittedSignalLedger`, `Pr66ContinuityRecoveryDurabilityTest` | — | — |
| `EmittedSignalLedger` survives process death (cross-process persistence) | ❌ MISSING | In-memory only; not persisted to storage | Replay bounding is limited to current process lifetime | Track E |
| Reconciliation signal epoch-bounding after reconnect | 🔲 UNCLEAR | Not found in Android-side code; deferred per `ANDROID_READINESS_EVIDENCE.md` | Stale signals from prior epoch may reach V2 after reconnect | Track E / V2-side |
| `HybridRuntimeContinuityContract` covers process-kill + restart scenario | ✅ VERIFIED | `HybridRuntimeContinuityContract`, `Pr53AndroidLifecycleRecoveryHybridHardeningTest` | — | — |
| `ContinuityRecoveryDurabilityContract` 13 covered behaviors validated | ✅ VERIFIED | `ContinuityRecoveryDurabilityContract`, `Pr66ContinuityRecoveryDurabilityTest` | — | — |
| Offline queue behavior has end-to-end instrumented test (real service) | ❌ MISSING | Unit test coverage exists; instrumented E2E test deferred | No real-service + test-double V2 WebSocket integration test | Track F |
| `SuppressDuplicateLocalRecovery` correctly drops re-entrant recovery attempts | ✅ VERIFIED | `AndroidRecoveryParticipationOwner` decision logic | — | — |

**Dimension closure verdict:** ✅ STRONG PARTIAL — The persistence and recovery logic layer is
comprehensively implemented and unit-tested across all major disruption scenarios. The remaining
gaps are hardening items (cross-process ledger persistence, epoch bounding, instrumented E2E
test) rather than structural absences.

---

## 4. Android ↔ V2 Integration Closure

**Goal:** Android and V2 communicate using a mutually understood protocol, messages are routed
correctly in both directions, and the integration contract is explicitly agreed and verifiable.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| AIP v3.0 WebSocket transport established and maintained | ✅ VERIFIED | `GalaxyWebSocketClient` (OkHttp, exponential backoff) | — | — |
| Android connects to V2 at `ws://<host>:8765/ws/device/{id}` | ✅ VERIFIED | `GalaxyWebSocketClient` URL construction | — | — |
| V2 `android_bridge.py` endpoint exists and handles AIP v3.0 | ✅ VERIFIED | V2 `galaxy_gateway/android_bridge.py` confirmed by PR-8 review | — | — |
| V2 dispatches `task_assign` → Android receives and executes | ✅ VERIFIED | Inbound message path in `GalaxyConnectionService` | — | — |
| Android returns `task_result` → V2 receives | ✅ VERIFIED | `GalaxyConnectionService.sendReconciliationSignal()` → WebSocket | — | — |
| `ReconciliationSignal` (PARTICIPANT_STATE, RUNTIME_TRUTH_SNAPSHOT) sent to V2 | ✅ VERIFIED | `RuntimeController.reconciliationSignals` → `GalaxyConnectionService` collector → WebSocket | — | — |
| V2 handles `device_readiness_report` from Android | 🔲 UNCLEAR | Android emits payload; V2-side routing not confirmed from Android code alone | V2 `android_bridge.py` routing not verified for all report types | Track G |
| V2 handles `device_governance_report` from Android | 🔲 UNCLEAR | Same: Android emits, V2 consumption unverified | Same V2-side routing uncertainty | Track G |
| V2 handles `device_acceptance_report` from Android | 🔲 UNCLEAR | Same: Android emits, V2 consumption unverified | Same V2-side routing uncertainty | Track G |
| `Node_113_AndroidVLM` in V2 provides remote inference for Android | ⚠️ PARTIAL | V2 node exists per PR-8 review; Android can target remote VLM | Integration path and request/response schema not verified end-to-end | Track G |
| V2 port references consistent across docs and config | ⚠️ PARTIAL | `config.properties` (8765); V2 README references 8765/8888/8299 | Port inconsistency creates confusion; no canonical port alignment doc | Track C |
| Production TLS (`wss://`) configured and cert-pinned | ⚠️ PARTIAL | `GalaxyWebSocketClient` has trust-all `X509TrustManager` for dev | Trust-all presents risk for production deployments | Track H |
| Joint dual-repo integration contract document exists | ❌ MISSING | `docs/V2_INTEGRATION.md` covers Android-side only | No joint "clone both → connect → run" canonical setup guide | Track C |
| Automated integration test with test-double V2 WebSocket | ❌ MISSING | Unit tests exist; no integration test harness against V2 test double | No cross-repo contract regression test | Track F |

**Dimension closure verdict:** ⚠️ PARTIAL — Core AIP v3.0 transport and message routing is
verified and functionally wired. The integration is developer-runnable with correct manual
config. Critical gaps are the unverified V2-side governance/report message routing, the
`Node_113_AndroidVLM` integration path, TLS hardening, and absence of a joint setup guide.

---

## 5. Artifact / Signal / Readiness Evidence Closure

**Goal:** Android emits the correct structured artifacts and reconciliation signals that V2
release gates and governance flows can consume to make real decisions about Android's readiness.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| `DelegatedRuntimeReadinessEvaluator` produces 5-dimension readiness verdict | ✅ VERIFIED | `DelegatedRuntimeReadinessEvaluator`, `Pr9DelegatedRuntimeReadinessTest` | — | — |
| `DeviceReadinessArtifact` emitted as `ReconciliationSignal` to V2 | ✅ VERIFIED | `Pr4AndroidEvaluatorArtifactEmissionTest` | — | — |
| `DelegatedRuntimeAcceptanceEvaluator` produces 6-dimension acceptance verdict | ✅ VERIFIED | `DelegatedRuntimeAcceptanceEvaluator`, `Pr10DelegatedRuntimeAcceptanceTest` | — | — |
| `DeviceAcceptanceArtifact` emitted as `ReconciliationSignal` to V2 | ✅ VERIFIED | `Pr4AndroidEvaluatorArtifactEmissionTest` | — | — |
| `DelegatedRuntimePostGraduationGovernanceEvaluator` produces governance verdict | ✅ VERIFIED | `DelegatedRuntimePostGraduationGovernanceEvaluator`, `Pr11DelegatedRuntimePostGraduationGovernanceTest` | — | — |
| `AndroidReadinessEvidenceSurface` provides auditable, typed evidence registry | ✅ VERIFIED | `AndroidReadinessEvidenceSurface.kt`, `Pr67AndroidReadinessEvidenceSurfaceTest` | — | — |
| `UnifiedTruthReconciliationSurface` emits truth patches to V2 | ✅ VERIFIED | `UnifiedTruthReconciliationSurface`, `Pr64UnifiedTruthReconciliationTest` | — | — |
| `DelegatedExecutionSignal` has idempotency guard (`signalId`) | ✅ VERIFIED | `DelegatedExecutionSignalIdempotencyTest` | — | — |
| `CompatibilitySurfaceRetirementRegistry` queryable by V2 governance tooling | ✅ VERIFIED | `CompatibilitySurfaceRetirementRegistry`, `Pr10CompatibilitySurfaceRetirementTest` | — | — |
| `AndroidAuthoritativePathAlignmentAudit` exports compat-state to V2 | ✅ VERIFIED | `AndroidAuthoritativePathAlignmentAudit`, `Pr65AndroidAuthoritativePathAlignmentTest` | — | — |
| V2 release gate actually parses `DeviceReadinessArtifact.semanticTag` | 🔲 UNCLEAR | Cannot confirm from Android code; V2-side gate implementation not visible | V2 PR-9 gate may not yet be implemented | Track G |
| V2 graduation gate actually parses `DeviceAcceptanceArtifact.semanticTag` | 🔲 UNCLEAR | Same: V2-side gate implementation not visible | V2 PR-10 gate may not yet be implemented | Track G |
| `DelegatedRuntimeStrategyEvaluator` produces dispatch verdict (Advisory) | ✅ VERIFIED | `DelegatedRuntimeStrategyEvaluator`, `Pr12DelegatedRuntimeStrategyTest` | Advisory only — see `ANDROID_READINESS_EVIDENCE.md` | — |
| `LongTailCompatibilityRegistry` not used as canonical readiness evidence | ✅ VERIFIED | `ANDROID_READINESS_EVIDENCE.md` explicitly marks as `DEPRECATED_COMPAT` | — | — |
| Epoch-stamped reconciliation signals (post-reconnect staleness guard) | ❌ MISSING | Deferred per `ANDROID_READINESS_EVIDENCE.md`; V2 PR-5 dependency | Stale signals from prior epoch not yet filtered by epoch stamp | Track E |
| End-to-end instrumented test for artifact emission round-trip | ❌ MISSING | Deferred per `ANDROID_READINESS_EVIDENCE.md` | Requires V2 test-double infrastructure | Track F |

**Dimension closure verdict:** ✅ STRONG — Android-side artifact emission and readiness evidence
infrastructure is comprehensively implemented and tested. The outstanding items are V2-side gate
implementations (Track G) and two hardening/instrumentation items (Tracks E, F) explicitly
deferred in prior PRs.

---

## 6. Operator / Debugging / Reviewability Closure

**Goal:** Operators and developers can launch, observe, diagnose, and debug the running system
with sufficient visibility to understand what is happening and why.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| `GalaxyLogger` provides structured, observable logging | ✅ VERIFIED | `GalaxyLogger` (structured logs, init step 1 in `UFOGalaxyApplication`) | — | — |
| Network diagnostics module present (Tailscale adapter, metrics) | ✅ VERIFIED | `UFOGalaxyApplication.initNetworkDiagnosticsModules()` | — | — |
| `LOCAL_DEBUGGING.md` covers per-component debug procedures | ✅ VERIFIED | `docs/LOCAL_DEBUGGING.md` | — | — |
| `OBSERVABILITY.md` covers runtime observability surfaces | ✅ VERIFIED | `docs/OBSERVABILITY.md` | — | — |
| `android-regression-checklist.md` provides structured pre-release checklist | ✅ VERIFIED | `docs/android-regression-checklist.md` | — | — |
| `e2e-verification.md` documents end-to-end verification steps | ✅ VERIFIED | `docs/e2e-verification.md` | — | — |
| `AndroidReadinessEvidenceSurface` is machine-queryable for tooling | ✅ VERIFIED | `AndroidReadinessEvidenceSurface.kt` (typed evidence registry with confidence levels) | — | — |
| First-run startup failures surfaced as user-visible action items | ❌ MISSING | `runReadinessChecks()` runs but failures go to logs only | No UI feedback or action-guided resolution for startup failures | Track A |
| Per-session debug / operator visibility into active session context | ⚠️ PARTIAL | `SessionHistoryStore` (persisted history); no interactive session dashboard | No real-time session state viewer accessible to operator | Track I |
| Dual-repo joint operator guide (start V2 + connect Android) | ❌ MISSING | `docs/DEPLOYMENT_GUIDE.md` + `docs/V2_INTEGRATION.md` cover pieces | No single "clone both → run system" canonical joint guide | Track C |
| AIP protocol version negotiation observable to operator | ⚠️ PARTIAL | `GalaxyWebSocketClient` handles protocol but version negotiation logging unclear | Not confirmed whether AIP version mismatches are surfaced clearly | Track I |
| `config.properties` placeholder values fail fast with clear error | ⚠️ PARTIAL | App tries to connect with placeholder URL; connection failure is logged | No clear "you need to configure the gateway URL" prompt on first boot | Track A / Track C |

**Dimension closure verdict:** ⚠️ PARTIAL — Developer/operator observability tooling is solid and
well-documented. The gap is in first-run operator experience: startup failures, configuration
errors, and permission requirements are not surfaced through user-visible guidance, making the
system harder to operate for anyone who hasn't read all the docs.

---

## 7. End-to-End Smooth Usability from Android Outward

**Goal:** A complete, uninterrupted task execution round-trip — from Android participant
receiving a goal, executing it, and returning a result to V2 — can be demonstrated as a
coherent, smooth experience that an informed user can walk through without unexpected failure.

| Closure Item | Status | Android Evidence | Gap / Blocker | Closure Track |
|---|---|---|---|---|
| **Scenario A: Local-only task execution** (user gives voice/text command → EdgeExecutor runs → local result) | ⚠️ PARTIAL | Pipeline is wired; inference server must be running externally | External inference dependency prevents turn-key demo | Track D |
| **Scenario B: Cross-device task receipt and execution** (V2 dispatches → Android executes → result returned) | ⚠️ PARTIAL | Protocol wired; requires correct gateway URL + `crossDeviceEnabled=true` | Manual config required; not turn-key | Track A / Track C |
| **Scenario C: Offline resilience** (disconnect → queue → reconnect → drain) | ✅ VERIFIED | `OfflineTaskQueue` + reconnect + drain path confirmed by code + tests | — | — |
| **Scenario D: Process restart recovery** (app killed → reboot → recovery decision → V2 consults) | ✅ VERIFIED | `AndroidRecoveryParticipationOwner` + `HybridRuntimeContinuityContract` + tests | — | — |
| **Scenario E: Permission-blocked startup recovery** (no Accessibility → fail gracefully) | ⚠️ PARTIAL | `runReadinessChecks()` detects state; no user-visible recovery path | User sees nothing actionable; session blocked silently | Track A |
| **Scenario F: Readiness evidence round-trip** (Android evaluates → emits artifact → V2 gate processes) | ⚠️ PARTIAL | Android side fully implemented; V2 gate consumption not confirmed | V2 PR-9 release gate implementation status unknown | Track G |
| **Scenario G: End-to-end joint system demo** (both repos running, Android connects, executes task, V2 reflects result) | ❌ MISSING | No joint setup guide; no automated E2E test | Requires joint guide + V2 test-double infrastructure | Track C / Track F |
| Duration of uninterrupted continuous use (hours-long sessions) | 🔲 UNCLEAR | Reconnect and recovery are code-tested; real-world soak not documented | No long-running soak test or stability evidence documented | Track F |
| First-time setup time from APK install to first successful task | ❌ MISSING | Currently requires: gateway config + permission grants + inference setup + `crossDeviceEnabled` toggle | Many manual steps; estimated setup time unknown; no guided path | Track A / Track D |

**Dimension closure verdict:** ❌ NOT CLOSED AS TURN-KEY — The system is
*developer-runnable* with correct manual preparation but is *not participant-turn-key*. Core
execution scenarios work once configured. The end-to-end experience remains blocked by the
absence of a guided first-run setup flow, unguided permission grants, the external inference
server requirement, and the absence of a joint dual-repo runnable demo path.

---

## 8. Closure Status Summary Matrix

### By dimension

| Dimension | Overall Status | Strongest Evidence | Key Remaining Gap |
|---|---|---|---|
| 1. Buildability / Startup | ⚠️ PARTIAL | Build compiles; 14-step init verified | `HardwareKeyReceiver` risk; no guided first-run |
| 2. Runtime / Execution | ⚠️ PARTIAL | Full pipeline wired and tested | External inference server; unguided permission flow |
| 3. Persistence / Recovery | ✅ STRONG PARTIAL | 13 recovery behaviors covered; `Pr66` test suite | Cross-process ledger persistence; epoch bounding |
| 4. Android ↔ V2 Integration | ⚠️ PARTIAL | AIP v3.0 transport verified end-to-end | V2 governance message routing; joint setup guide |
| 5. Artifact / Readiness Evidence | ✅ STRONG | Full evaluator chain + `AndroidReadinessEvidenceSurface` | V2 gate implementation; epoch-stamped signals |
| 6. Operator / Debug | ⚠️ PARTIAL | Structured logging; multiple operator docs | Startup failures not user-visible; no joint guide |
| 7. End-to-End Usability | ❌ NOT CLOSED | Scenarios C+D verified (offline/recovery) | Scenarios A, B, F, G require config/inference/guides |

### By status category

**✅ VERIFIED (confirmed by code/tests)**
- AIP v3.0 transport and WebSocket reconnect
- Offline task queue (persistence, session bounding, drain on reconnect)
- Full recovery decision taxonomy (6 decision types, 13 covered behaviors)
- Complete artifact emission chain (readiness, acceptance, governance evaluators)
- `AndroidReadinessEvidenceSurface` machine-readable evidence registry
- Signal idempotency and duplicate suppression (`EmittedSignalLedger`)
- `CompatibilitySurfaceRetirementRegistry` and authoritative path alignment audit
- Android regression checklist, observability surfaces, local debugging docs

**⚠️ PARTIAL (structurally present; not operationally smooth)**
- Local inference execution (server dependency)
- Accessibility action execution (permission dependency)
- Cross-device mode (manual config dependency)
- V2 governance/report message routing (unverified on V2 side)
- `Node_113_AndroidVLM` remote inference path (integration details unclear)
- Production TLS / cert management
- Operator visibility into session state and startup failures

**❌ MISSING (confirmed absent; blocks complete usability)**
- Guided first-run setup activity (gateway URL + permissions + prerequisites)
- `HardwareKeyReceiver` class (manifest declared but class not found in sources)
- Joint dual-repo setup guide ("clone both → run system")
- Cross-process `EmittedSignalLedger` persistence
- End-to-end instrumented test with V2 test-double WebSocket
- Epoch-stamped reconciliation signals for post-reconnect staleness filtering

**🔲 UNCLEAR (cannot determine from Android code alone)**
- V2 release gate parsing of `DeviceReadinessArtifact.semanticTag`
- V2 graduation gate parsing of `DeviceAcceptanceArtifact.semanticTag`
- V2 routing for `device_governance_report` / `device_acceptance_report` message types
- Long-running session stability (no soak test documented)

---

## 9. Follow-Up PR Tracks

The following tracks organize closure work into actionable future PRs. Each track addresses
one or more gaps identified in this plan.

### Track A — First-Run Setup and Permission Guidance

**Goal:** Add an in-app guided setup flow that walks the user through all prerequisites before
allowing task execution.

**Specific closure items addressed:**
- Guided `SYSTEM_ALERT_WINDOW` permission grant (Dim 1, Dim 7)
- Guided Accessibility Service grant (Dim 2, Dim 7)
- Gateway URL input or QR-code scan (Dim 6, Dim 7)
- `crossDeviceEnabled` toggle with explanation (Dim 2, Dim 7)
- Startup readiness check failures surfaced as user-visible action items (Dim 1, Dim 6)
- Post-setup connectivity verification before allowing first task (Dim 7)

**Suggested Android modules to modify/add:**
- New `SetupWizardActivity` or `FirstRunFragment`
- `UFOGalaxyApplication.runReadinessChecks()` — pipe failures to UI layer
- `AppSettings` — add `isFirstRunComplete` flag
- `SharedPrefsAppSettings` — persist setup completion state

---

### Track B — `HardwareKeyReceiver` Manifest Alignment

**Goal:** Resolve the `HardwareKeyReceiver` manifest declaration risk.

**Specific closure items addressed:**
- `HardwareKeyReceiver` class absence (Dim 1)

**Options:**
1. Add a minimal `HardwareKeyReceiver` implementation (preferred if MEDIA_BUTTON handling is
   intentional).
2. Remove the `HardwareKeyReceiver` entry from `AndroidManifest.xml` if the feature is
   not currently needed.

**Suggested Android modules to modify/add:**
- `AndroidManifest.xml` — remove declaration, or
- `app/src/main/java/com/ufo/galaxy/hardware/HardwareKeyReceiver.kt` — add implementation

---

### Track C — Dual-Repo Joint Setup Guide and Config Consistency

**Goal:** Create a single authoritative setup document and resolve config/port inconsistencies.

**Specific closure items addressed:**
- Missing joint "clone both → run system" guide (Dim 4, Dim 6, Dim 7)
- V2 port inconsistency across docs (Dim 4)
- `config.properties` placeholder not fail-fast with user guidance (Dim 1, Dim 6)

**Suggested artifacts to create/modify:**
- `docs/DUAL_REPO_SETUP.md` — new joint guide covering:
  1. Start V2: `python main.py --host <ip> --port 8765`
  2. Set Android gateway URL to `ws://<ip>:8765`
  3. Enable `crossDeviceEnabled` in Android Settings
  4. Grant Accessibility + `SYSTEM_ALERT_WINDOW` permissions
  5. Verify connection (V2 projection log + Android connectivity indicator)
  6. Run first end-to-end task
- `config.properties` — update default gateway URL comment to be explicit about placeholder
- `docs/V2_INTEGRATION.md` — cross-link to new joint guide

---

### Track D — Inference Server Lifecycle Management

**Goal:** Make local AI inference usable without requiring external server management by the
operator.

**Specific closure items addressed:**
- `MobileVlmPlanner` external server dependency (Dim 2, Dim 7)
- `SeeClickGroundingEngine` external server dependency (Dim 2, Dim 7)
- First-time setup requiring inference server (Dim 7)

**Options:**
- **Option D1 (Recommended for most devices):** Confirm and document `Node_113_AndroidVLM` in V2
  as the canonical inference path; make `crossDeviceEnabled=true` with a known V2 host the
  default for production builds; deprecate localhost inference as primary path.
- **Option D2 (High-end devices):** Add `InferenceServerService` to manage llama.cpp/MLC-LLM
  lifecycle as a subprocess or JNI-linked native library; wire to
  `LocalInferenceRuntimeManager`.

**Suggested Android modules to modify/add:**
- `LocalInferenceRuntimeManager` — wire up actual lifecycle management
- `GalaxyConnectionService` or new `InferenceServerService` (Option D2)
- `AppSettings` — add `inferenceMode: REMOTE | LOCAL` setting
- `SetupWizardActivity` (from Track A) — expose inference mode choice

---

### Track E — Recovery and Signal Hardening

**Goal:** Close the remaining hardening gaps in the persistence and signal-safety layer.

**Specific closure items addressed:**
- `EmittedSignalLedger` cross-process persistence (Dim 3, Dim 5)
- Reconciliation signal epoch-bounding after reconnect (Dim 3, Dim 5)

**Suggested Android modules to modify/add:**
- `EmittedSignalLedger` — add `SharedPreferences`-backed or `Room`-backed persistence layer
- `ReconciliationSignal` wire format — add epoch stamp field (pending V2 PR-5 epoch contract)
- `GalaxyWebSocketClient` reconnect path — filter stale-epoch signals before re-sending

---

### Track F — Integration and Instrumented Test Infrastructure

**Goal:** Create automated integration-level tests that validate the Android ↔ V2 contract
using a test-double V2 WebSocket, removing reliance on manual end-to-end testing.

**Specific closure items addressed:**
- Missing instrumented E2E test for artifact emission round-trip (Dim 5)
- Missing automated integration test for `OfflineTaskQueue` drain (Dim 3)
- Missing cross-repo contract regression test (Dim 4)

**Suggested Android modules to add:**
- `app/src/androidTest/java/com/ufo/galaxy/integration/` — new integration test directory
- `V2WebSocketTestDouble` — in-process OkHttp MockWebServer standing in for V2 gateway
- `ArtifactEmissionIntegrationTest` — validates `DeviceReadinessArtifact` round-trip
- `OfflineQueueDrainIntegrationTest` — validates queue drain on reconnect against test-double

---

### Track G — V2-Side Governance Message Routing Verification

**Goal:** Confirm (or fix) that V2's `android_bridge.py` correctly routes all governance,
readiness, acceptance, and strategy report messages that Android emits.

**Specific closure items addressed:**
- V2 routing for `device_readiness_report`, `device_governance_report`,
  `device_acceptance_report` (Dim 4, Dim 5)
- V2 release/graduation gate implementation status (Dim 5)
- `Node_113_AndroidVLM` integration path (Dim 4)

**Note:** Primary work for this track is in the V2 repository.  The Android-side deliverable
is a **verification checklist** (added to `docs/V2_INTEGRATION.md`) listing every message type
Android emits, so V2 reviewers can confirm each one is handled.

**Suggested Android artifacts:**
- `docs/V2_INTEGRATION.md` — add "Android → V2 message type inventory" table with all
  emitted types, their purpose, and what V2 must do with each

---

### Track H — Production TLS Hardening

**Goal:** Replace the trust-all `X509TrustManager` with a production-safe certificate trust
policy.

**Specific closure items addressed:**
- Trust-all cert manager risk for production deployments (Dim 4)

**Suggested Android modules to modify:**
- `GalaxyWebSocketClient` — replace `TrustAllCertsManager` with a configurable policy:
  - Dev/Tailscale mode: retain current trust-all as explicit opt-in (`tlsMode=TRUST_ALL_DEV`)
  - Default (staging/prod): system trust store or bundled certificate pinning
- `AppSettings` — add `tlsMode` setting
- `config.properties` — document `tlsMode` options

---

### Track I — Operator / Session Visibility Improvements

**Goal:** Add runtime visibility surfaces that let operators and developers inspect active
session state, connection status, and AIP protocol health during real operation.

**Specific closure items addressed:**
- No interactive session state viewer (Dim 6)
- AIP protocol version negotiation not clearly observable (Dim 6)

**Suggested Android modules to add/modify:**
- `EnhancedFloatingService` — add operator-mode debug panel (hidden behind dev toggle)
- `GalaxyConnectionService` — emit structured connection-state events to observable channel
- `SessionDashboardFragment` or debug overlay — show: connection state, session ID, queue
  depth, last signal sent, AIP protocol version

---

## 10. V2 Authority and Boundary Notes

Throughout this closure plan, the following Android-side authority boundaries are preserved:

| Concern | Authority | Notes |
|---|---|---|
| Canonical orchestration | V2 (`DesktopPresenceRuntime`) | Android never self-authorizes continuation without V2 confirmation |
| Final release gate decisions | V2 | Android provides evidence; V2 holds the gate |
| Session/task resumption and re-dispatch | V2 | Android presents `WaitForV2ReplayDecision`; V2 decides |
| Canonical truth convergence | V2 | Android submits `ReconciliationSignal`; V2 adjudicates |
| Android-domain local truth ownership | Android | `ParticipantRuntimeSemanticsBoundary.ANDROID_TRUTH_DOMAIN` |
| Android readiness evidence provision | Android (companion) | `AndroidReadinessEvidenceSurface`, `DelegatedRuntimeReadinessEvaluator` |
| Local task execution decisions | Android | `EdgeExecutor`, `AutonomousExecutionPipeline` |

Track G items (V2-side governance message routing, V2 release gate implementation) are
explicitly in V2's authority domain. This closure plan notes them as `🔲 UNCLEAR` from the
Android side and tracks them as V2-side work, not Android-side work.

---

## 11. How to Update This Plan

This document is designed to be updated incrementally as closure tracks are completed.

### When a MISSING or PARTIAL item is resolved

1. Change the **Status** symbol to ✅ VERIFIED (or ⚠️ PARTIAL if partially closed).
2. Update the **Android Evidence** cell to reference the new module, test, or document.
3. Clear the **Gap / Blocker** cell or note the remaining sub-gap.
4. Update the **Closure Status Summary Matrix** (Section 8) to move the item to the correct
   category.
5. Add a note in the relevant **Follow-Up PR Track** (Section 9) marking the item closed.

### When a new gap is discovered

1. Add a new row to the relevant dimension table with status ❌ MISSING or ⚠️ PARTIAL.
2. Add a new entry (or update an existing entry) in Section 9 under the relevant track.
3. Update the summary matrix in Section 8.

### Versioning

When this document is updated as part of a PR, add an entry to the bottom of this section:

| PR | Date | Summary of Updates |
|----|------|-------------------|
| PR-9 Android | 2026-04-25 | Initial closure plan created from PR-8 review findings |

---

*This plan is grounded in the real Android codebase (`DannyFish-11/ufo-galaxy-android`) and the
PR-8 Android code-grounded full-system review (`docs/PR8_PRODUCT_READINESS_REVIEW.md`). All
status claims that are marked ✅ VERIFIED are backed by direct code reading or test evidence
referenced in that document or in `docs/ANDROID_READINESS_EVIDENCE.md`. Claims marked
🔲 UNCLEAR require V2-side investigation or end-to-end runtime tests to resolve.*
