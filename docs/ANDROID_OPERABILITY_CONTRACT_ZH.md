# Android 侧最小可用路径与可操作性合约

**仓库**：`DannyFish-11/ufo-galaxy-android`  
**合约文件**：`app/src/main/java/com/ufo/galaxy/runtime/AndroidMinimalOperabilityContract.kt`  
**合约 Schema 版本**：`1`  
**引入 PR**：PR-9  
**日期**：2026-05-14

---

## 一、目的

本文档是 `AndroidMinimalOperabilityContract` 的配套中文说明文档。

该合约将 Android 侧的 APK 构建、安装、V2 连接、跨设备参与、委托执行、本地模式就绪、结果上行与失败诊断，从"作者记忆工作流"提升为**明确、可机器验证、可诊断**的形式化可操作性合约。

### 核心目标

通过本合约，以下操作变得更清晰、更可推理，不再依赖作者记忆：

- APK 构建前提检测与失败提示
- V2 服务端配置与 WebSocket 连接建立
- 设备真相快照（device truth snapshot）上报
- 接收委托任务（delegated task）的前提与阻断条件
- 任务结果上行（result uplink）的离线容错机制
- 本地模式（local mode）就绪前提的明确机器可读判断
- 关键失败点的结构化诊断与可操作性建议

---

## 二、十步最小可用路径

| 步骤 | 名称                     | 说明                                             | 是否本地 |
|------|--------------------------|--------------------------------------------------|----------|
|  1   | 克隆仓库                 | 克隆 `DannyFish-11/ufo-galaxy-android`           | ✅ 本地  |
|  2   | 构建 APK                 | `./build_apk.sh` 或 `./gradlew assembleDebug`    | ✅ 本地  |
|  3   | 安装 APK                 | `adb install` 或手动安装到目标设备               | ✅ 本地  |
|  4   | 配置 V2 服务端           | 在 App 设置中设置 gateway URL，启用跨设备模式     | ❌ 需 V2 |
|  5   | 建立 WS 连接             | `GalaxyWebSocketClient` 自动连接并握手           | ❌ 需 V2 |
|  6   | 上报设备真相             | 连接成功后自动发送 `device_state_snapshot`       | ❌ 需 V2 |
|  7   | 接收委托任务             | 通过 `GalaxyConnectionService` 分发器接收任务    | ❌ 需 V2 |
|  8   | 上行结果                 | 发送 `goal_execution_result`；离线时缓冲至队列   | ❌ 需 V2 |
|  9   | 评估本地模式就绪         | 调用 `LocalModeReadinessGate.assess()`           | ✅ 本地  |
| 10   | 诊断失败点               | 使用 `FailureDiagnosticKind` 分类并上报诊断      | ✅ 本地  |

---

## 三、路径阻断条件（PathBlockCondition）

以下条件会**硬性阻断**对应步骤，必须先解除才能继续。

> 与"降级条件"（CapabilityDegradationKind）的关键区别：
> - **阻断条件**：步骤无法执行。
> - **降级条件**：步骤可降级执行，某些能力受限但主路径仍可继续。

| 条件名称                        | 阻断步骤           | 可自愈 | 说明 |
|---------------------------------|--------------------|--------|------|
| `missing_jdk_or_sdk`            | 构建 APK（步骤 2） | ❌     | JDK 17+ 或 ANDROID_HOME 未配置；`build_apk.sh` 会检测并给出提示 |
| `gateway_url_is_placeholder`    | 配置 V2（步骤 4）  | ❌     | `config.properties` 默认 URL（`ws://100.x.x.x:8765`）为占位符，必须替换 |
| `cross_device_disabled`         | 建立连接（步骤 5） | ❌     | `crossDeviceEnabled=false`；`RuntimeController` 不会建立 WS 连接 |
| `ws_host_unreachable`           | 建立连接（步骤 5） | ❌     | V2 宿主不可达（网络隔离/V2 服务未启动）；需人工修复 |
| `mode_not_cross_device_active`  | 接收任务（步骤 7） | ✅     | 执行模式未达到 `CROSS_DEVICE_ACTIVE`；V2 等待模式稳定后自动恢复 |
| `goal_execution_disabled`       | 接收任务（步骤 7） | ❌     | `goalExecutionEnabled=false`；`AutonomousExecutionPipeline` 拒绝 `goal_execution` 消息 |

---

## 四、能力降级种类（CapabilityDegradationKind）

以下条件仅**降级**设备能力，不阻断主可用路径。V2 应根据受影响能力类别决定是否派发需要该能力的任务。

| 降级种类                              | 影响能力                     | 可恢复 | 说明 |
|---------------------------------------|------------------------------|--------|------|
| `local_llm_not_loaded`                | 本地模型推理                 | ✅     | 依赖本地 LLM 的任务降级失败；纯 UI 自动化任务仍可执行 |
| `accessibility_permission_not_granted`| 无障碍操作执行               | ❌     | `AccessibilityService` 未授权；本地推理路径不受影响 |
| `overlay_permission_not_granted`      | 悬浮窗显示                   | ❌     | `SYSTEM_ALERT_WINDOW` 未授权；不影响后台执行路径 |
| `external_inference_server_unavailable` | 本地 VLM 规划              | ✅     | `MobileVlmPlanner` HTTP 端点不可用；已配置远程 V2 VLM 节点时不受影响 |
| `connection_degraded`                 | 完整跨设备能力               | ✅     | `LocalExecutionModeGate` 处于 `CROSS_DEVICE_DEGRADED`；部分任务类型仍可接受 |

---

## 五、本地模式就绪门（LocalModeReadinessGate）

本地模式允许 Android 在不连接 V2 的情况下独立执行推理与 UI 自动化任务。其前提条件更为苛刻。

### 5.1 就绪前提维度

| 维度 Wire Key                     | 类型     | 说明 |
|-----------------------------------|----------|------|
| `local_llm_ready`                 | 硬前提 🔴 | 本地 LLM 模型已加载并预热 |
| `accessibility_service_enabled`   | 硬前提 🔴 | `AccessibilityService` 已授权 |
| `overlay_permission_granted`      | 软前提 🟡 | `SYSTEM_ALERT_WINDOW` 已授权（不影响 `overall_ready`）|
| `cross_device_disabled_confirmed` | 软前提 🟡 | `crossDeviceEnabled=false` 确认本地隔离（不影响 `overall_ready`）|

- **硬前提**（`isHardGate=true`）：不满足则 `overall_ready=false`，进入 `failingHardGates` 列表。
- **软前提**（`isHardGate=false`）：不满足仅降级部分能力，不影响 `overall_ready`。

### 5.2 编程调用

```kotlin
val snapshot = AndroidMinimalOperabilityContract.LocalModeReadinessGate.assess(
    localLlmReady = localInferenceRuntimeManager.isModelReady(),
    accessibilityServiceEnabled = settings.accessibilityEnabled,
    overlayPermissionGranted = settings.overlayPermissionGranted,
    crossDeviceEnabled = settings.crossDeviceEnabled
)

if (snapshot.overallReady) {
    // 本地模式就绪，可独立执行任务
} else {
    // snapshot.failingHardGates 列出阻断项，可用于用户引导
    Log.w(TAG, "本地模式未就绪，缺少硬前提：${snapshot.failingHardGates}")
}
```

### 5.3 Wire Map 示例

`snapshot.toWireMap()` 输出示例（LLM 未加载时）：

```json
{
  "schema_version": "1",
  "overall_ready": false,
  "failing_hard_gates": ["local_llm_ready"],
  "local_llm_ready": false,
  "accessibility_service_enabled": true,
  "overlay_permission_granted": null,
  "cross_device_disabled_confirmed": true
}
```

---

## 六、委托执行阻断分类（DelegatedExecutionBlockKind）

以下条件会导致 `AutonomousExecutionPipeline` 返回 `STATUS_DISABLED`，V2 应在观察到对应信号后停止向该设备派发新任务。

| 阻断种类                           | 受影响任务类型                           | 说明 |
|------------------------------------|------------------------------------------|------|
| `cross_device_runtime_inactive`    | `goal_execution` / `parallel_subtask` / `task_assign` | `crossDeviceEnabled=false`；全部委托任务类型均被拒绝 |
| `goal_execution_feature_disabled`  | `goal_execution`                         | `goalExecutionEnabled=false` |
| `parallel_execution_feature_disabled` | `parallel_subtask`                    | `parallelExecutionEnabled=false` |
| `policy_routing_rejected`          | `goal_execution` / `parallel_subtask`   | `policy_routing_outcome == "rejected"` |
| `mode_transitioning_hold`          | `*`（全部）                              | 模式处于 `TRANSITIONING`；V2 等待模式稳定后恢复 |

> **阻断 vs 降级的关键区别**：  
> - 出现 `DelegatedExecutionBlockKind` 时 → 返回 `STATUS_DISABLED`，任务未进入执行。  
> - 出现 `CapabilityDegradationKind` 时 → 任务可能进入执行，但特定能力受限。

---

## 七、失败诊断分类（FailureDiagnosticKind）

每类失败提供结构化的 wire value、受影响步骤和可操作建议（actionableHint）。

| 诊断 Wire Value                             | 步骤   | 可操作建议 |
|---------------------------------------------|--------|------------|
| `build_failed_missing_prerequisites`        | 步骤 2 | 确认 `java -version >= 17` 且 `ANDROID_HOME` 已设置；运行 `build_apk.sh` 查看详细错误提示 |
| `connect_failed_invalid_gateway_url`        | 步骤 5 | 在 App ⚙ 设置中将 Gateway URL 更新为 `ws://<V2宿主IP>:8765`；确认 V2 服务正在运行 |
| `connect_failed_cross_device_disabled`      | 步骤 5 | 在 App ⚙ 设置中启用「跨设备模式」（`crossDeviceEnabled=true`） |
| `connect_failed_host_unreachable`           | 步骤 5 | 确认 V2 服务已运行（`python main.py --host <ip> --port 8765`）；检查网络（Tailscale/LAN） |
| `device_truth_uplink_failed`                | 步骤 6 | 等待 WS 重连成功（自动重连）；重连后快照将自动重发 |
| `delegated_task_rejected_pipeline_disabled` | 步骤 7 | 确认 `crossDeviceEnabled=true` 且 `goalExecutionEnabled=true`；检查 `policy_routing_outcome` |
| `result_uplink_queued_pending_reconnect`    | 步骤 8 | 等待 WS 自动重连；重连后 `OfflineTaskQueue` 自动排空 |
| `local_mode_blocked_llm_not_loaded`         | 步骤 9 | 检查模型文件路径；查看 `LocalInferenceRuntimeManager` 加载日志 |
| `local_mode_blocked_accessibility_not_granted` | 步骤 9 | 前往「设置 > 无障碍 > UFO Galaxy」启用 Accessibility Service |

---

## 八、与现有运行时诊断合约的关系

本合约（`AndroidMinimalOperabilityContract`）与 `AndroidLocalDiagnosticReasonContract` **互补**，共同实现完整的诊断覆盖：

| 合约                                  | 覆盖层次     | 典型使用场景 |
|---------------------------------------|--------------|--------------|
| `AndroidMinimalOperabilityContract`   | **操作路径层** | 配置错误、权限缺失、模式错误、本地模式前提未满足 |
| `AndroidLocalDiagnosticReasonContract` | **运行时层** | WS 断开、解析错误、执行失败、握手失败 |

---

## 九、V2 消费约定

V2 消费方 MUST：

1. 在 `PathBlockCondition.blocksStep` 命中时，不向 Android 派发该步骤相关的任务。
2. 将 `CapabilityDegradationKind` 视为"可接受但能力受限"的运行态，继续尝试不需要该能力的任务类型。
3. 从 `FailureDiagnosticKind.wireValue` 中匹配 Android 上报的失败类型，而非依赖字符串解析。
4. 在 `DelegatedExecutionBlockKind.MODE_TRANSITIONING_HOLD` 激活期间，不向 Android 派发任何任务。
5. 将 `LocalModeReadinessGate.LocalModeReadinessSnapshot.overall_ready` 视为 Android 本地执行能力的权威机器可读信号。

---

## 十、权威边界

| 关注点                    | 权威方    | 说明 |
|---------------------------|-----------|------|
| 本地构建与安装            | 开发者    | 不在 V2 权威范围内 |
| 本地模式就绪判断          | Android   | `LocalModeReadinessGate.assess()` |
| 委托执行阻断 vs 降级分类  | Android   | `DelegatedExecutionBlockKind` |
| 设备真相快照              | Android   | `AndroidUnifiedTruthUplinkContract` |
| 连接终态（accept/reject）  | V2        | Android 上报信号；V2 裁决 |
| 结果接受与闭合            | V2        | Android 提供证据；V2 决定门控 |

---

## 十一、StabilizationBaseline 注册

本合约已在 `StabilizationBaseline` 中注册为 `CANONICAL_STABLE`：

- **surfaceId**：`android-minimal-operability-contract`
- **stability**：`CANONICAL_STABLE`
- **extensionGuidance**：`EXTEND`
- **introducedPr**：`9`

---

## 十二、测试覆盖

`app/src/test/java/com/ufo/galaxy/runtime/Pr9AndroidMinimalOperabilityContractTest.kt`

覆盖以下接受准则：

- `OperabilityPathStep`：10 个步骤，stepIndex 连续，wire value 稳定，有序路径
- `PathBlockCondition`：6 个阻断条件，blocksStep 与 isSelfHealable 正确
- `CapabilityDegradationKind`：5 种降级条件，isRecoverable 语义正确
- `LocalModeReadinessGate.assess()`：all 8 个测试场景，包含硬/软前提、null 信号处理
- `DelegatedExecutionBlockKind`：5 种阻断分类，blocksTaskTypes 覆盖正确
- `FailureDiagnosticKind`：9 种诊断分类，actionableHint 非空
- `OPERABILITY_INVARIANTS`：12 条不变量逐条验证
- `StabilizationBaseline` 注册验证：CANONICAL_STABLE，introducedPr=9

---

## 十三、版本历史

| PR   | 日期       | 变更说明 |
|------|------------|----------|
| PR-9 | 2026-05-14 | 初始版本：创建 `AndroidMinimalOperabilityContract`，定义十步最小可用路径、路径阻断条件、能力降级种类、本地模式就绪门、委托执行阻断分类、失败诊断分类与 12 条可用性不变量 |

---

*本文档基于 `DannyFish-11/ufo-galaxy-android` 仓库真实代码编写。所有状态声明均以代码实现为准，详见 `AndroidMinimalOperabilityContract.kt` 与 `Pr9AndroidMinimalOperabilityContractTest.kt`。*
