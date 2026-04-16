# UFO Galaxy Android — Stabilization Baseline

**PR-11 · Architectural Stabilization Baseline**

This document defines the explicit stabilization baseline for UFO Galaxy Android following the
governance, dispatch, truth/projection separation, and compatibility-retirement work established
through the PR-1–PR-10 series (and the broader PR-11–PR-35 work).

It is the single authoritative reference that says:

- **which surfaces are now canonical enough to be considered stable**, and therefore safe to build on;
- **which surfaces remain transitional**, and must not receive new logic;
- **which categories of future work should prefer canonical-only paths**; and
- **where extension should happen versus where convergence should continue**.

The machine-readable declaration of this baseline lives in
[`app/src/main/java/com/ufo/galaxy/runtime/StabilizationBaseline.kt`](../app/src/main/java/com/ufo/galaxy/runtime/StabilizationBaseline.kt).

---

## Surface stability classifications

| Classification | Meaning |
|---|---|
| **CANONICAL_STABLE** | Safe to build on. Extension is permitted and encouraged. |
| **CANONICAL_FROZEN** | Canonical but intentionally frozen; extend by wrapping or observing, not modifying. |
| **TRANSITIONAL** | Retained for backward compatibility only. Must not be extended. Converge consumers away. |
| **RETIREMENT_GATED** | Awaiting cross-repo coordination before retirement. No new Android-side dependencies. |

---

## Extension vs convergence guidance

| Guidance | Meaning |
|---|---|
| **EXTEND** | The canonical home for this category of future work. New features should extend through this surface. |
| **CONVERGE** | Future work must migrate consumers to the canonical replacement. |
| **WRAP_ONLY** | Extend by adding wrapper/observer only. Do not modify the frozen surface. |
| **NO_NEW_WORK** | This surface must not receive new logic. Retire when the gate condition is satisfied. |

---

## Canonical stable surfaces (safe to build on)

### Lifecycle truth

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `RuntimeController.state` | `runtime` | EXTEND | PR-1 |
| `RuntimeController.hostSessionSnapshot` | `runtime` | EXTEND | PR-22 |
| `RuntimeController.targetReadinessProjection` | `runtime` | EXTEND | PR-24 |
| `RuntimeController.reconnectRecoveryState` | `runtime` | EXTEND | PR-33 |

These are the **sole authoritative truth surfaces** for Android runtime lifecycle state.
No other surface should be consulted for these values.

### Dispatch governance

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `CanonicalDispatchChain` | `runtime` | EXTEND | PR-12 |
| `RolloutControlSnapshot` | `runtime` | EXTEND | PR-31 |
| `ExecutionRouteTag` | `runtime` | EXTEND | PR-29 |

`CanonicalDispatchChain` is the single machine-readable model of every execution path.
`RolloutControlSnapshot` governs all path activations through rollout flags and kill-switch.
`ExecutionRouteTag` is the canonical machine-readable tag for result-path identification.

### Signal emission

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `DelegatedExecutionSignal` | `runtime` | EXTEND | PR-15 |
| `EmittedSignalLedger` | `runtime` | EXTEND | PR-18 |

The delegated execution signal contract (ACK → PROGRESS → RESULT, `emissionSeq` 1/2/3,
`signalId` for idempotency) is stable and canonical. `EmittedSignalLedger` provides
replay-safe signal identity storage.

### Session identity

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `CanonicalSessionAxis` | `runtime` | EXTEND | PR-3 |
| `AttachedRuntimeHostSessionSnapshot` | `runtime` | EXTEND | PR-19 |
| `RuntimeIdentityContracts` | `runtime` | **WRAP_ONLY** (frozen) | PR-19 |

`CanonicalSessionAxis` defines the session families for all execution paths.
`AttachedRuntimeHostSessionSnapshot` provides the canonical typed 9-field attached-session projection.
`RuntimeIdentityContracts` contains frozen identity-linkage format constants; do not modify, only wrap.

### Projection models

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `CanonicalParticipantModel` | `runtime` | EXTEND | PR-6 |
| `CanonicalDeviceModel` | `runtime` | EXTEND | PR-6 |
| `CanonicalCapabilityProviderModel` | `runtime` | EXTEND | PR-6 |

These are canonical **read-model contracts** (not lifecycle truth owners). Extension through
these models is safe; they must not be treated as session/reconnect/readiness authorities.

### Failure classification

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `CrossDeviceSetupError` | `runtime` | EXTEND | PR-27 |
| `TakeoverFallbackEvent` | `runtime` | EXTEND | PR-23 |

`CrossDeviceSetupError` covers all setup failure categories (CONFIGURATION / NETWORK /
CAPABILITY_NOT_SATISFIED). `TakeoverFallbackEvent` covers all fallback causes
(FAILED / TIMEOUT / CANCELLED / DISCONNECT).

### Compatibility governance inventory

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `CompatibilitySurfaceRetirementRegistry` | `runtime` | EXTEND | PR-10 |
| `LongTailCompatibilityRegistry` | `runtime` | EXTEND | PR-35 |

These registries are **canonical governance surfaces** for tracking and classifying
compatibility surfaces. New compatibility surfaces must be registered here. Long-tail
minimal-compat entries must be promoted individually rather than extended in-place.

### Protocol alignment

| Surface | Package | Guidance | Introduced |
|---|---|---|---|
| `UgcpSharedSchemaAlignment` | `protocol` | EXTEND | PR-8 |
| `UgcpProtocolConsistencyRules` | `protocol` | EXTEND | PR-8 |

These are the canonical registry and consistency-rule surfaces for Android ↔ center shared
semantic mapping. All new shared vocabulary must be registered here.

---

## Transitional surfaces (converge away, do not extend)

| Surface | Canonical replacement | Rationale |
|---|---|---|
| `RuntimeController.registrationError` (legacy `SharedFlow<String>`) | `RuntimeController.setupError` (`SharedFlow<CrossDeviceSetupError>`) | Legacy untyped error bridge; consumers must migrate to the typed `CrossDeviceSetupError` surface. |
| `RuntimeController.currentSessionSnapshot()` (`Map<String,Any>?`) | `RuntimeController.hostSessionSnapshot` (`AttachedRuntimeHostSessionSnapshot?`) | Legacy untyped map projection; consumers must migrate to the canonical typed snapshot. |
| `UgcpSharedSchemaAlignment` legacy alias normalization maps | `UgcpSharedSchemaAlignment` canonical vocabulary sets | Legacy wire-value alias surfaces retained for backward compatibility with older message producers. |

Transitional surfaces must not receive new logic. Future PRs that touch these surfaces should
**migrate consumers** to the canonical replacements, not extend the transitional path.

---

## Retirement-gated surfaces (no new work, retire when gate clears)

| Surface | Retirement gate | Notes |
|---|---|---|
| `LongTailCompatibilityRegistry` RELAY / FORWARD / REPLY (TRANSITIONAL) | Center-Android coordination on relay/forward/reply lifecycle semantics | Minimal-compat placeholder; must not be extended. Promote individually when semantics are agreed. |
| `UgcpProtocolConsistencyRules` entries classified as `TRANSITIONAL_COMPATIBILITY` | Cross-repo protocol consistency rule tightening pass | Pending cross-repo tightening; no new Android-side dependencies. |

---

## Categories of future work that must use canonical-only paths

The following work categories must be routed through canonical surfaces only.
Attempting any of these through transitional or compatibility surfaces constitutes
a governance violation that will reintroduce compatibility drift.

| Category | Canonical surface(s) |
|---|---|
| New execution path variants | `CanonicalDispatchChain` |
| New session identity fields | `CanonicalSessionAxis`, `AttachedRuntimeHostSessionSnapshot` |
| New rollout gate flags | `RolloutControlSnapshot` (center-coordinated) |
| New delegated signal kinds | `DelegatedExecutionSignal` |
| New failure classification categories | `CrossDeviceSetupError`, `TakeoverFallbackEvent` |
| New protocol consistency rules | `UgcpProtocolConsistencyRules` |
| New canonical projection fields | `CanonicalParticipantModel` / `CanonicalDeviceModel` / `CanonicalCapabilityProviderModel` |

---

## Where to extend versus where to converge

### Extend here (canonical extension points)

- `CanonicalDispatchChain` — add new `DispatchPathDescriptor` entries for new execution paths.
- `RolloutControlSnapshot` — add new rollout gate fields (requires center coordination).
- `ExecutionRouteTag` — add new result-path tags (requires center coordination).
- `DelegatedExecutionSignal` — add new signal metadata fields (backward-compatible only).
- `CrossDeviceSetupError` — add new failure `Category` values.
- `TakeoverFallbackEvent` — add new fallback `Cause` values.
- `CompatibilitySurfaceRetirementRegistry` — register new compatibility surfaces as they are identified.
- `LongTailCompatibilityRegistry` — promote TRANSITIONAL entries to PROMOTED or CANONICAL when
  semantics are agreed with center.
- `UgcpSharedSchemaAlignment` — add new canonical alignment entries.
- `UgcpProtocolConsistencyRules` — add new consistency rules.
- `GalaxyLogger TAG_*` constants — add new structured log tags for new governance checkpoints.

### Converge here (work to eliminate transitional debt)

- Migrate `RuntimeController.registrationError` consumers → `RuntimeController.setupError`.
- Migrate `RuntimeController.currentSessionSnapshot()` consumers → `RuntimeController.hostSessionSnapshot`.
- Converge `UgcpSharedSchemaAlignment` legacy alias maps → canonical vocabulary sets (requires coordination).
- Promote `LongTailCompatibilityRegistry` TRANSITIONAL entries → PROMOTED/CANONICAL as lifecycle
  semantics are agreed.
- Reclassify `UgcpProtocolConsistencyRules` `TRANSITIONAL_COMPATIBILITY` entries → canonical
  classification after cross-repo tightening.

---

## Relationship to prior governance documents

| Document | Relationship |
|---|---|
| [`docs/architecture.md`](architecture.md) | Canonical component index and authority-boundary reference. This baseline is additive to that document. |
| [`docs/CANONICAL_DISPATCH_CHAIN.md`](CANONICAL_DISPATCH_CHAIN.md) | Detailed dispatch chain documentation. `CanonicalDispatchChain` is a canonical stable surface. |
| [`docs/ugcp/ANDROID_UGCP_CONSTITUTION.md`](ugcp/ANDROID_UGCP_CONSTITUTION.md) | Android UGCP runtime-profile constitution. This baseline operationalizes the layer boundaries defined there. |
| [`docs/ugcp/CANONICAL_SESSION_AXIS.md`](ugcp/CANONICAL_SESSION_AXIS.md) | Session identity model. `CanonicalSessionAxis` is a canonical stable surface. |
| `CompatibilitySurfaceRetirementRegistry` | Governance inventory for high-risk compatibility surfaces; itself a canonical stable surface. |
| `LongTailCompatibilityRegistry` | Governance inventory for long-tail minimal-compat message types; itself a canonical stable surface. |

---

## Summary

After the PR-1–PR-10+ governance series:

- **20 surfaces are canonical** (19 CANONICAL_STABLE + 1 CANONICAL_FROZEN): safe to build on.
- **3 surfaces are transitional**: must not be extended; converge consumers to canonical replacements.
- **2 surfaces are retirement-gated**: no new work; retire when gate conditions are met.
- **7 future-work categories must use canonical-only paths**.
- **11 extension points** define where new features should be added.
- **5 convergence targets** define the remaining transitional debt.

The system is now structurally ready to be extended without reintroducing governance ambiguity
or compatibility drift, provided future work routes through the canonical extension points above.
