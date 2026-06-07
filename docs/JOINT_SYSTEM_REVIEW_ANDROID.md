# 双仓联合系统审查 — Android 仓正式落点

> **本文档性质**：双仓联合深度系统审查在 Android 仓侧的正式落点文档。  
> **关联仓库**：`DannyFish-11/ufo-galaxy-android`（本仓）与 `DannyFish-11/ufo-galaxy-realization-v2`（V2 仓）。  
> **约束**：本文档以真实代码为依据，不夸大能力，不虚饰完成度。所有结论均可在对应代码位置直接核验。  
> **受众**：Android 平台工程师、V2 运行时工程师、后续 PR 评审人、发布门控决策者。

---

## 1. Android 在双仓系统中的真实角色

### 1.1 本质定位

Android 仓**不是** orchestration authority，**不是**中心控制平面，**不具备**任务调度决策权。

Android 仓的真实定位是：

> **持久执行参与者（Durable Participant Runtime）与本地能力承载端（Persistent Execution Endpoint）**

具体而言：

| 职责维度 | Android 承担的内容 | V2 承担的内容 |
|----------|-------------------|--------------|
| 任务调度决策 | **不承担**（被动接收） | 是中心控制平面，拥有完整调度权 |
| 任务执行 | **承担**（本地 Accessibility + EdgeExecutor + LocalGoalExecutor） | 下发任务，不在 Android 上执行 |
| 连接/重连管理 | **承担**（GalaxyWebSocketClient 自主重连，OfflineTaskQueue 离线缓存） | 被动感知 Android 在线/离线状态 |
| 设备注册与能力上报 | **承担**（device_register + capability_report） | 维护设备注册表，依赖 Android 上报 |
| Session continuity | **承担**（AttachedRuntimeSession / DurableSessionContinuityRecord） | V2 消费 Android 的 continuity 信号 |
| 本地 AI（规划 + grounding） | **部分承担**（接口 + 实现存在，默认未激活，需本地推理服务器） | 不直接管理 |
| Accessibility/UI 执行 | **承担**（AccessibilityService + AccessibilityActionExecutor） | 不直接管理 |

### 1.2 边界说明

- Android **永远是** 指令的接收方，而非发起方（从 V2 视角看）
- Android 可以发起用户侧本地 task（cross_device_enabled=false），但这条路径**不经过 V2**
- Android 的任务结果（task_result / goal_result）**必须回传 V2**，Android 本身不保留任务历史权威性
- Android 比 V2 更 durable（OfflineTaskQueue 持久化到 SharedPreferences），但这是**执行端的弹性设计**，不是 authority 的表达

---

## 2. Android 真实主链与层次划分

### 2.1 主链（已真实运转、有代码和测试覆盖）

下表为 Android 仓**已形成真实闭环**的主链能力，每一项均有对应产品代码和单元测试：

| 能力 | 关键代码位置 | 说明 |
|------|-------------|------|
| **Persistent Service Runtime** | `GalaxyConnectionService`（`service/`） | Android Service，进程生命周期持久化，承载 WS 消息循环 |
| **WebSocket 连接与自动重连** | `GalaxyWebSocketClient`（`network/`） | 心跳、指数退避重连、连接状态管理 |
| **device_register / capability_report 握手** | `GalaxyWebSocketClient.handleConnected()` | 连接成功后自动上报注册信息和能力向量 |
| **task_assign 分发** | `GalaxyConnectionService.handleTaskAssign()` | 从 V2 接收任务并派发到 EdgeExecutor |
| **goal_execution 分发** | `GalaxyConnectionService.handleGoalExecution()` | 从 V2 接收目标执行请求 |
| **task_result / goal_result 回传** | `GalaxyWebSocketClient.sendJson()` + `GalaxyConnectionService` | 执行结果通过 WS 回传 V2 |
| **离线队列与重连 replay** | `OfflineTaskQueue`（`network/`） | task_result/goal_result 在断连期间持久化到 SharedPreferences，重连后 FIFO replay |
| **RuntimeController 生命周期状态机** | `RuntimeController`（`runtime/`） | 唯一的跨设备生命周期决策权威；状态：Inactive/Starting/Active/LocalOnly/Failed |
| **Accessibility 执行链** | `AccessibilityActionExecutor` + `AccessibilityScreenshotProvider`（`service/`） | 真实 UI 自动化执行，截图驱动 |
| **AndroidCapabilityVector 上报** | `AndroidCapabilityVector`（`capability/`） | 能力向量结构化计算与 wire 序列化 |
| **V2MultiDeviceLifecycleEvent 信号流** | `RuntimeController.v2LifecycleEvents` + `V2MultiDeviceLifecycleEvent`（`runtime/`） | DeviceConnected/Reconnected/Disconnected/Degraded 生命周期事件流，供 V2 消费 |

> **判断依据**：上述每项能力均有对应的产品代码路径可追踪，且均有 JVM 单元测试覆盖（见 `app/src/test/java/com/ufo/galaxy/`）。

### 2.2 准主链（代码存在、结构完整、但验证深度有限）

| 能力 | 关键代码位置 | 当前状态 |
|------|-------------|---------|
| **AttachedRuntimeSession / DurableSessionContinuityRecord** | `runtime/AttachedRuntimeSession.kt`, `runtime/DurableSessionContinuityRecord.kt` | 结构完整，continuity 信号有单元测试，但未经过双仓端到端验证 |
| **ContinuityRecoveryContext** | `runtime/ContinuityRecoveryContext.kt` | reconnect/handoff 语义定义完整，未有真实跨设备 replay 验证 |
| **LocalInferenceRuntimeManager** | `runtime/LocalInferenceRuntimeManager.kt` | 生命周期管理完整（Stopped/Starting/Running/Degraded/Failed/SafeMode），但需要真实推理服务才能到达 Running 状态 |
| **OfflineTaskQueue 会话标记与 discardForDifferentSession** | `network/OfflineTaskQueue.kt` | 代码完整，有单元测试，但 sessionTag-authority bounding 尚未端到端验证 |
| **InputRouter 双路由决策** | `input/InputRouter.kt`（文档层已说明） | local vs cross-device routing 逻辑存在，但仅有结构层测试 |

### 2.3 扩展/实验能力（有代码存在，但非主链，不可混入主链叙事）

| 能力 | 关键代码位置 | 实际状态 |
|------|-------------|---------|
| **WebRTC 信令** | `webrtc/WebRTCSignalingClient.kt` | 结构性存在，ICE/TURN 配置解析，**不参与主链 task dispatch，无端到端验证** |
| **TailscaleAdapter** | `network/TailscaleAdapter.kt` | 网络层兼容适配器，结构性存在 |
| **Parallel Subtask routing** | `agent/LocalCollaborationAgent.kt` | 依赖 `parallelExecutionEnabled` 标志，默认关闭 |
| **StagedMesh / FormationParticipation** | `runtime/StagedMeshExecutionTarget.kt`, `runtime/FormationParticipationRebalancer.kt` | 声明性结构，未形成可运行主链 |

> ⚠️ **重要约束**：上述扩展能力**不能**在能力上报或文档中被描述为"已成熟主链能力"。`AndroidCapabilityVector` 的 `CROSS_DEVICE_COORDINATION` 维度目前是实际能反映状态的门控，但 WebRTC/mesh 状态尚未有对应 wire 标记。

### 2.4 兼容层/桥接层（显式保留，须与主链隔离）

| 能力 | 关键代码位置 | 性质 |
|------|-------------|------|
| **AndroidCompatLegacyBlockingParticipant** | `runtime/AndroidCompatLegacyBlockingParticipant.kt` | 显式 compat 路径，有 `CompatLegacyInfluenceClass` 分类 |
| **LongTailCompatibilityRegistry / CompatibilitySurfaceRetirementRegistry** | `runtime/LongTailCompatibilityRegistry.kt`, `runtime/CompatibilitySurfaceRetirementRegistry.kt` | compat 退场管理，已有 `CompatibilityRetirementFence` 封堵机制 |
| **AgentRuntimeBridge** | `agent/AgentRuntimeBridge.kt` | 桥接到 OpenClawd/Agent Runtime，幂等缓存（200条），compat 路径 |
| **registrationError string bridge** | 旧 `CrossDeviceSetupError` 桥接路径 | 仍保留，须在后续 PR 中逐步 deprecate |

### 2.5 结构存在但尚未成熟/默认未激活的能力（不可写成"已完成"）

#### 本地 AI（MobileVLM 规划器 + SeeClick grounding）— 最重要的未激活能力

**真实状态**：

- **接口层**：`LocalPlannerService` / `LocalGroundingService`（`inference/`）— 已完整定义，包含 warmup/grounding/plan 全生命周期
- **实现层**：`MobileVlmPlanner`（`planner/`）和 `SeeClickGroundingEngine`（`grounding/`）— 真实 HTTP 客户端实现，调用本地推理服务器（llama.cpp 默认 `127.0.0.1:8080`）
- **安全默认**：`NoOpPlannerService` / `NoOpGroundingService`（`inference/`）— 返回结构化错误，不进行推理；供测试或需显式禁用推理的场景注入使用
- **生命周期管理**：`LocalInferenceRuntimeManager`（`runtime/`）— 完整的 Stopped/Starting/Running/Degraded/Failed/SafeMode 状态机
- **能力门控**：`AndroidCapabilityVector.LOCAL_INFERENCE` 维度仅在 `localModelEnabled=true` AND `modelReady=true` 时为 true
- **应用初始化**：`UFOGalaxyApplication` 实际注入 `MobileVlmPlanner` 和 `SeeClickGroundingEngine`（即真实 HTTP 实现，而非 NoOp）；在没有本地推理服务器运行时，两者的 `warmupWithResult()` 调用均会失败，`LocalInferenceRuntimeManager` 随之进入 `Failed` 或 `Degraded` 状态，应用可在降级状态下继续运行（无本地 AI，仅依赖网关下发执行）

**激活前提**（均未默认满足）：
1. 本地 llama.cpp / MLC-LLM 推理服务器在设备上运行（`127.0.0.1:8080`）
2. MobileVLM 1.7B GGUF 模型文件已下载到设备（由 `ModelAssetManager` 管理）
3. `AppSettings.localModelEnabled = true`
4. `ReadinessChecker.modelReady = true`

**结论**：本地 AI 是**有真实代码的扩展能力，但默认未激活**。capability_report 中的 `LOCAL_INFERENCE` 在标准安装下为 `false`。

---

## 3. Android ↔ V2 协议契约

### 3.1 注册与能力上报契约

| 协议消息 | 方向 | Android 代码位置 | 契约状态 |
|---------|------|-----------------|---------|
| `device_register` | Android → V2 | `GalaxyWebSocketClient.sendDeviceRegister()` | **已真实实现**，WS 连接后自动发送，包含 device_id、metadata |
| `capability_report` | Android → V2 | `GalaxyWebSocketClient.sendCapabilityReport()` | **已真实实现**，register 后发送，包含 `AndroidCapabilityVector` 序列化的 scheduling metadata |
| `task_assign` | V2 → Android | `GalaxyConnectionService.handleTaskAssign()` | **已真实实现**，完整 dispatch 到 EdgeExecutor |
| `goal_execution` | V2 → Android | `GalaxyConnectionService.handleGoalExecution()` | **已真实实现**，dispatch 到 LocalGoalExecutor |
| `task_cancel` | V2 → Android | `GalaxyConnectionService.handleTaskCancel()` | **已真实实现** |
| `task_result` | Android → V2 | `GalaxyWebSocketClient.sendJson()` via GalaxyConnectionService | **已真实实现**，含离线 queue replay |
| `goal_result` | Android → V2 | 同上 | **已真实实现**，含离线 queue replay |
| `peer_announce` | V2/peer → Android | `GalaxyConnectionService.handlePeerAnnounce()` | 代码存在，解析 PeerAnnouncePayload |

> **注意**：上述消息类型的 wire 格式以 `GalaxyWebSocketClient` 和 `AipModels` 中的实现为权威，任何 V2 侧契约变更须对应更新 Android 侧。

### 3.2 Capability Report Wire 格式

`capability_report` 的 metadata 包含两类键：

1. **必需 metadata 键**（`CapabilityReport.REQUIRED_METADATA_KEYS`）：8 个 Boolean/String 就绪信号（accessibility_ready, overlay_ready 等）
2. **Scheduling basis 键**（`AndroidCapabilityVector.toSchedulingMetadata()`）：4 个调度决策键：
   - `scheduling_local_eligible`（Boolean）
   - `scheduling_cross_device_eligible`（Boolean）
   - `scheduling_parallel_subtask_eligible`（Boolean）
   - `scheduling_execution_dimensions`（String，逗号分隔的维度列表）

### 3.3 Continuity 信号语义

Android 的 continuity 信号体系是本仓最复杂的契约部分，以下为真实语义：

#### 核心标识符

| 标识符 | 类型 | 稳定性 | 语义 |
|--------|------|--------|------|
| `deviceId` | String | 最稳定（进程生命周期） | 设备标识，来自 `AppSettings.deviceId`，进程间稳定 |
| `runtimeSessionId` | String (UUID) | 每次连接变化 | 每个 WS 连接分配一个新 UUID；断连重连后变化 |
| `durableSessionId` | String? | 跨重连稳定，ERA 内不变 | 一个激活周期（ERA）内稳定，跨 `DeviceReconnected` 不变；仅在 `stop()` / `invalidateSession()` 后重置 |
| `sessionContinuityEpoch` | Int | 单调递增 | 同一 ERA 内每次重连加 1；ERA 内首次连接为 0 |
| `runtimeAttachmentSessionId` | String (UUID) | 每次 `AttachedRuntimeSession` 创建变化 | 每次 `openAttachedSession` 生成新 UUID |

#### 重连契约

```
DeviceDisconnected(isResumable=true)
  ↓ WS 自动重连
DeviceReconnected  ← durableSessionId 不变, sessionContinuityEpoch +1
  或
DeviceDisconnected(isResumable=false)  ← 恢复失败，ERA 终止
```

- `durableSessionId` **跨重连稳定**，是 V2 侧跨断点关联 session 的最可靠锚点
- `runtimeSessionId` **每次连接变化**，不可用于跨重连关联
- `isResumable=true` 时 V2 应调用 `suspend()`，等待 `RESTORE_ACTIVATE` hint
- `isResumable=false` 时 V2 应调用 `terminate()`

#### 离线队列与 session 权威边界

`OfflineTaskQueue` 支持 `sessionTag`（= `durableSessionId`）：
- 重连时调用 `discardForDifferentSession(currentDurableSessionId)` 可清除不属于当前 ERA 的消息
- `sessionTag=null` 的消息（旧路径兼容）**不会被清除**

**当前状态**：session-tag authority bounding 代码完整，但尚未经过双仓端到端 replay 验证。

### 3.4 已形成真实闭环的行为

| 行为 | 闭环状态 |
|------|---------|
| WS 连接 → device_register → capability_report | ✅ 已真实闭环，代码可追踪 |
| task_assign → EdgeExecutor → task_result 回传 | ✅ 已真实闭环 |
| goal_execution → LocalGoalExecutor → goal_result 回传 | ✅ 已真实闭环 |
| 断连 → OfflineTaskQueue 持久化 → 重连 → replay | ✅ 代码闭环（SharedPreferences 持久化），**未经双仓端到端自动验证** |
| DeviceConnected/Reconnected/Disconnected 事件流 | ✅ Android 侧代码完整，**V2 侧消费未经双仓集成测试验证** |
| durableSessionId 跨重连稳定 | ✅ 有单元测试（Pr66ContinuityRecoveryDurabilityTest），**未经双仓验证** |

### 3.5 仅接口/结构准备（未真实验证的闭环）

| 行为 | 当前状态 |
|------|---------|
| 本地 AI 自主执行链（无网关参与） | 接口+实现存在，需本地推理服务器，未有自动化验证 |
| V2 消费 v2LifecycleEvents 双仓集成 | 协议文档完整（`docs/V2_INTEGRATION.md`），但无双仓集成测试 |
| continuity token 跨设备 handoff | 协议定义存在（`ContinuityRecoveryContext.CONTINUATION_TOKEN_WIRE_FIELD`），未端到端验证 |
| WebRTC 数据通道 | 结构性代码，不参与 task dispatch 主链 |

---

## 4. 当前完成度诚实评估

### 4.1 已真实完成（可用于发布决策的证据）

| 能力 | 证据 |
|------|------|
| 持久 Service + WS 主链 | 产品代码 + JVM 单元测试（RuntimeControllerAttachedSessionTest 等） |
| 设备注册与能力上报协议 | GalaxyWebSocketClient 实现 + CapabilityReport 测试 |
| task_assign/task_result 主链 | GalaxyConnectionService 实现 |
| 离线队列持久化（SharedPreferences） | OfflineTaskQueue + 单元测试 |
| RuntimeController 状态机 | 完整实现 + 多个 PR 测试覆盖 |
| Continuity 信号定义（durableSessionId 等） | AttachedRuntimeSession + DurableSessionContinuityRecord + Pr66 测试 |
| V2 事件流协议定义 | V2MultiDeviceLifecycleEvent + V2_INTEGRATION.md |
| 能力向量结构化 | AndroidCapabilityVector + 测试 |
| Accessibility 执行链 | AccessibilityActionExecutor + AccessibilityScreenshotProvider |

### 4.2 部分完成/近主链（代码完整但验证不足）

| 能力 | 缺口 |
|------|------|
| 离线 replay + session-tag authority bounding | 缺双仓端到端自动化验证 |
| V2 事件流 V2 侧消费 | 缺双仓集成测试（V2 侧实际消费 `v2LifecycleEvents` 未验证） |
| continuity handoff / takeover | 协议定义完整，缺端到端场景验证 |
| InputRouter 双路由 | 逻辑存在，缺完整路由回归测试 |

### 4.3 尚未默认激活或未真实验证的能力

| 能力 | 激活前提 | 默认状态 |
|------|---------|---------|
| 本地 AI（MobileVLM + SeeClick） | 需本地推理服务器 + 模型文件 | ❌ 未激活（NoOp fallback 实际不会默认触发，但 MobileVlmPlanner warmup 在无服务器时失败） |
| 并行子任务路由 | `parallelExecutionEnabled=true` | ❌ 默认关闭 |
| WebRTC 信令 | 需 WebRTC peer setup | ❌ 结构性，不参与主链 |
| Mesh staging | 需完整 mesh 参与逻辑 | ❌ 声明性结构 |

### 4.4 Android CI 现状（最大缺口）

> ⚠️ **Android 仓当前完全没有 CI 工作流（.github/workflows/ 目录为空，仅有 PR 模板）**

这意味着：
- 每次 PR 的构建正确性无自动保障
- JVM 单元测试无自动运行
- lint 无自动检查
- 协议契约回归无法被 PR 自动拦截

这是当前最高优先级的基础设施缺口，已通过本 PR 同步补充最小 CI（见 `.github/workflows/android-ci.yml`）。

---

## 5. 高优先级后续 PR 入口

以下为基于本次审查识别的高优先级后续 PR，按紧迫程度排序：

### PR-Next-1：Android CI 基础（已在本 PR 完成最小版本）

**目标**：让每次 PR 有自动的构建 + JVM 单元测试 + lint 保障。  
**已包含**：见本 PR 的 `.github/workflows/android-ci.yml`（build + test + lint）。  
**后续扩展**：emulator smoke test（需 Android emulator 支持）。

### PR-Next-2：双仓协议回归测试框架

**目标**：建立 V2↔Android 协议契约的自动化验证，不再依赖人工 review。  
**需解决的核心问题**：
- device_register / capability_report 格式回归（字段变更即报错）
- task_assign → task_result roundtrip 闭环验证
- offline queue replay 场景自动验证
- reconnect + durableSessionId continuity 场景验证

**前提**：V2 仓需提供协议级模拟 client 或 mock gateway 端点。

### PR-Next-3：Emulator Smoke Test

**目标**：用 Android emulator 验证最小运行路径：Runtime 启动 → register 握手 → 一次 task_assign → task_result。  
**当前缺口**：无 emulator 环境，无 smoke test harness。  
**注意**：这是"本地 AI 未激活"下的纯协议层 smoke，不涉及推理。

### PR-Next-4：本地 AI 激活路径明确化

**目标**：明确本地 AI（MobileVLM + SeeClick）从"接口存在"到"可用于实际测试"的完整激活步骤。  
**需要**：
- 本地推理服务器集成文档（llama.cpp/MLC-LLM 启动方式）
- 模型下载流程文档化（ModelDownloader 使用）
- `localModelEnabled` 标志激活的完整步骤
- 激活后的端到端本地 AI 执行验证

### PR-Next-5：V2 侧 v2LifecycleEvents 消费双仓集成测试

**目标**：验证 V2 侧实际消费 `v2LifecycleEvents` 的正确性，包括 DeviceConnected/Reconnected/Disconnected 在 V2 侧的处理语义。  
**当前文档**：`docs/V2_INTEGRATION.md` 已有完整协议文档，但缺实际运行验证。

---

## 6. 附录：关键文件索引

### 主链关键文件

| 文件 | 包 | 职责 |
|------|----|------|
| `GalaxyWebSocketClient.kt` | `network` | WS 连接主干、register/capability_report、离线队列驱动 |
| `GalaxyConnectionService.kt` | `service` | Android Service，inbound 消息循环，task_assign/goal_execution 分发 |
| `RuntimeController.kt` | `runtime` | 跨设备生命周期唯一权威，状态机管理 |
| `AttachedRuntimeSession.kt` | `runtime` | 持久附加 session 模型（sessionId, durableSessionId, epoch） |
| `DurableSessionContinuityRecord.kt` | `runtime` | ERA 内稳定 continuity 记录 |
| `OfflineTaskQueue.kt` | `network` | 离线消息队列，SharedPreferences 持久化 |
| `AndroidCapabilityVector.kt` | `capability` | 能力向量结构化计算 |
| `V2MultiDeviceLifecycleEvent.kt` | `runtime` | V2 消费的生命周期事件密封类 |
| `EdgeExecutor.kt` | `agent` | 本地 AIP v3 任务执行编排器 |
| `AccessibilityActionExecutor.kt` | `service` | Accessibility UI 执行 |

### 本地 AI 关键文件

| 文件 | 包 | 职责 |
|------|----|------|
| `LocalPlannerService.kt` | `inference` | 规划器接口 + NoOp 实现 |
| `LocalGroundingService.kt` | `inference` | Grounding 接口 + NoOp 实现 |
| `MobileVlmPlanner.kt` | `planner` | MobileVLM 真实 HTTP 实现（需本地推理服务器） |
| `SeeClickGroundingEngine.kt` | `grounding` | SeeClick 真实 HTTP 实现（需本地推理服务器） |
| `LocalInferenceRuntimeManager.kt` | `runtime` | 本地推理生命周期管理 |

### 兼容层关键文件

| 文件 | 包 | 职责 |
|------|----|------|
| `AndroidCompatLegacyBlockingParticipant.kt` | `runtime` | 显式 compat 路径 |
| `CompatibilitySurfaceRetirementRegistry.kt` | `runtime` | compat surface 退场管理 |
| `AgentRuntimeBridge.kt` | `agent` | OpenClawd 桥接，幂等 cache |

### 相关文档

| 文件 | 说明 |
|------|------|
| `docs/V2_INTEGRATION.md` | V2 侧消费 Android 生命周期事件的完整协议文档 |
| `docs/ANDROID_READINESS_EVIDENCE.md` | Android 就绪证据面的分层描述 |
| `docs/RUNTIME_TRUTH_RECONCILIATION.md` | Runtime truth 对齐与协调机制 |
| `docs/architecture.md` | Android 仓组件架构全局视图 |

---

## 7. 本文档的有效期与维护约定

- 本文档反映截至本 PR 合并时的真实代码状态
- 任何改变上述判断的代码变更（能力激活、协议变更、CI 补充）应在对应 PR 中同步更新本文档相关章节
- 若某能力从"结构存在"变为"真实激活"，须在第 4 节中更新其状态，并提供测试证据链接

---

*本文档由双仓联合系统审查过程生成，作为 Android 仓后续 PR 开发与评审的基准参考。*
