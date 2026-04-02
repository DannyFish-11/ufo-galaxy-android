## Summary

- Problem:
- Root cause:
- Fix scope:

## Affected modules

- [ ] Input routing (`InputRouter`)
- [ ] Cross-device lifecycle (`RuntimeController`)
- [ ] Gateway transport / reconnect / offline queue (`GalaxyWebSocketClient`, `GatewayClient`)
- [ ] Runtime configuration (`AppSettings`, `LocalLoopConfig`)
- [ ] Task dispatch / cancel / timeout (`GalaxyConnectionService`, `LocalGoalExecutor`, `TaskCancelRegistry`, `AipModels`)
- [ ] Local closed-loop execution (`LocalLoopExecutor`, `LoopController`, `local/*`)
- [ ] Observability / diagnostics (`GalaxyLogger`, `MetricsRecorder`, traces, history, debug UI)
- [ ] Other:

## Validation

- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew :app:test --tests "com.ufo.galaxy.*" --info`
- [ ] Relevant manual regression checklist items were exercised

### Manual / targeted verification

- Checklist rows or docs used:
- Trigger / preconditions:
- Expected behavior:
- Actual behavior after fix:

### Verification blockers

- None

## Risks

- User-visible risk:
- Operational / cross-device risk:
- Logging / metrics / protocol compatibility risk:

## Rollback plan

- Files or components to revert:
- Behavior expected after rollback:

## Notes for reviewers

- Canonical owner(s) touched:
- Out-of-scope items intentionally not changed:
