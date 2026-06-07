# Android Operational State Surface

This document describes the Android-side unified operational/readiness surface exported through
`DeviceStateSnapshotPayload.operational_surface_*`.

## Purpose

Android already emitted many useful signals, but they were spread across readiness, acceptance,
governance, reconnect, lifecycle, and capability contracts. The new operational surface provides
a compact cross-repo projection so higher-level aggregation can consume Android as a first-class
participant instead of inferring state from scattered fields.

## Surface axes

The exported `operational_surface_states` map contains these axes:

- `registration_discoverability`
- `capability_visibility`
- `operational_readiness`
- `active_usable_path`
- `degraded_mode`
- `recovery_repair`
- `cross_device_participation`
- `session_continuity`
- `task_initiation_eligibility`
- `result_closure`
- `minimum_access_admission`

## Authority semantics

`operational_surface_authority` makes Android/V2 authority boundaries explicit:

- Android is locally authoritative for identity presence, capability visibility, readiness,
  usable-path, degradation, and recovery-participation reporting.
- Android reports cross-device participation and continuity as local signals, while V2 remains the
  global coordinator for cross-repo interpretation.
- Android reports task-initiation prerequisites and local completion evidence, but V2 still owns
  admission and final closure.
- `minimum_access_admission` remains explicitly `v2_authoritative`.

## Known limitations

`operational_surface_limitations` is emitted alongside the surface so higher-level aggregation can
see why Android still does not claim full symmetry on its own. The list always preserves these
truths:

- V2 retains final admission authority.
- V2 retains cross-repo aggregation authority.
- V2 retains final closure authority.

Additional limitations appear when Android lacks durable identity, capability visibility, complete
readiness evidence, or an attached cross-device session.
