# UFO Galaxy Android — 系统全面分析报告（中文）

**撰写日期：** 2026-05-03  
**文档基线：** v3.0.0 · **Android app versionName：** 2.0.1 · **协议：** AIP v3.0  
**代码规模：** 主源文件 255 个 · 测试文件 198 个 · Kotlin 总量 453 个

---

## 1. 这是一个什么样的系统？

### 1.1 系统定义

UFO Galaxy Android 是一个 **Android 端 L4 自主 AI 代理客户端**。它能够通过自然语言指令在 Android 设备上自主执行任务，支持两种运行模式：

| 模式 | 触发条件 | 执行引擎 | 结果归宿 |
|------|----------|----------|----------|
| **本地模式**（默认） | `cross_device_enabled=false` | 设备本地推理（MobileVLM 1.7B + SeeClick） | UI（MainViewModel / FloatingService） |
| **跨设备模式**（可选） | WS 连接到 Galaxy Gateway | 与网关协作执行 | 经 WebSocket 返回网关 |

两种模式共用同一套本地执行引擎（`EdgeExecutor`），只有输入路由和生命周期层有所不同。

### 1.2 双仓库产品架构

本系统由两个 GitHub 仓库共同构成一个完整产品：

```
┌──────────────────────────────────────────────────────┐
│           Android 设备（本仓库：ufo-galaxy-android）  │
│                                                        │
│  UFOGalaxyApplication                                  │
│    ├─ GalaxyConnectionService ──WS──────────────────┐ │
│    ├─ EnhancedFloatingService（悬浮 UI）              │ │
│    ├─ RuntimeController（生命周期权威）               │ │
│    ├─ EdgeExecutor（本地执行管道）                    │ │
│    │    ├─ MobileVlmPlanner → HTTP → localhost:8080   │ │
│    │    ├─ SeeClickGroundingEngine → HTTP → localhost  │ │
│    │    └─ AccessibilityActionExecutor                 │ │
│    └─ OfflineTaskQueue（SharedPreferences 持久化）    │ │
└───────────────────────────────────────────────────────┘ │
                                                          │
              AIP v3.0 WebSocket                          │
              ws://<host>:8765/ws/device/{id}             │
                                                          │
┌─────────────────────────────────────────────────────────┘
│     V2 后端（配套仓库：ufo-galaxy-realization）
│    ├─ Galaxy Gateway（任务调度中心）
│    ├─ Node_113_AndroidVLM（远程 VLM 推理节点）
│    └─ REST API + WebSocket 网关
```

### 1.3 核心技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.21 |
| 平台 | Android API 26+ (Android 8.0+) |
| UI | Jetpack Compose + ViewBinding |
| 异步 | Kotlin Coroutines + Flow |
| 网络 | OkHttp + WebSocket |
| 协议 | AIP v3.0（自研 AI 交互协议） |
| 本地推理 | MobileVLM 1.7B（llama.cpp HTTP）+ SeeClick（NCNN/MNN HTTP） |
| 序列化 | Gson |
| 测试 | JVM 单元测试（JUnit4 + 自研 Fake/Mock 框架） |

---

## 2. 完成度评估

### 2.1 总体完成度：**约 75%（开发者可运行，非用户开箱即用）**

| 维度 | 状态 | 置信度 |
|------|------|--------|
| 代码构建链路 | ⚠️ 代码结构完整；当前审查环境未复跑 | 受 `dl.google.com` 网络限制阻塞 |
| 服务生命周期（Service/BootReceiver） | ✅ 完成 | 已验证 |
| WebSocket 连接 + 断线重连 | ✅ 完成 | 已验证 |
| 跨设备任务协议（AIP v3.0） | ✅ 完成 | 已验证 |
| 离线任务队列 + 恢复回放 | ✅ 完成 | 已验证 |
| 悬浮窗 UI（FloatingIsland） | ✅ 完成 | 已验证 |
| 语音输入 + 自然语言规范化 | ✅ 完成 | 已验证 |
| 单元测试覆盖（100+ 测试文件） | ✅ 完成 | 已验证 |
| 本地推理集成（MobileVLM + SeeClick） | ⚠️ 部分 | 客户端就绪；服务端需外部部署 |
| 设备无障碍操作 | ⚠️ 部分 | 需手动授权 |
| 首次使用引导流程 | ❌ 缺失 | 未实现 |
| 双仓库联合启动文档 | ✅ 已补齐 | `DUAL_REPO_SETUP.md` 已创建 |
| 高级协议通道（RAG / CODE_EXEC / 混合执行） | ⚠️ 最小兼容桩 | 仅日志记录，逻辑未实现 |
| 生产级 TLS 证书绑定 | ⚠️ 风险 | 信任所有证书的 debug 选项存在 |

---

## 3. 排查发现的所有问题

### 问题 1：本地推理服务器未集成（高影响 — 核心功能运行障碍）

**描述：** `MobileVlmPlanner` 和 `SeeClickGroundingEngine` 均通过 HTTP 访问本机 `http://127.0.0.1:8080` 上的推理服务器，但 APK 本身**不包含、不启动、也不管理**这个推理服务器进程。

**当前状态：** HTTP 客户端代码完整，模型资产管理器（`ModelAssetManager`）和模型下载器（`ModelDownloader`）就绪，但没有服务器进程生命周期管理。

**影响：** 未外部配置推理服务器的设备上，`LocalLoopExecutor` 的模型就绪性检查会失败，本地 AI 执行路径不可用。

**缓解路径：** 使用 `crossDeviceEnabled=true`，由 V2 后端的 `Node_113_AndroidVLM` 提供远程推理。这是大多数设备的实际生产路径。

**状态：** 已知差距，未修复（属于架构决策层面）。

---

### 问题 2：网关 URL 为占位符（中影响 — 首次使用摩擦）

**描述：** `assets/config.properties` 默认值为 `ws://100.x.x.x:8765`，用户必须知道实际 V2 实例 IP 并手动在应用设置中填写。

**当前状态：** `SharedPrefsAppSettings` 支持运行时覆盖；`initRemoteGatewayConfig()` 尝试从 `/api/v1/config` 自动获取，但这需要 URL 已正确配置，形成"鸡和蛋"问题。

**缓解措施（已存在）：** 应用内网络设置界面可手动输入；`config.properties` 注释清楚说明了占位符。

---

### 问题 3：无权限申请引导流程（中影响 — 用户摩擦）

**描述：** 应用完全运行需要三个权限，全部需要用户手动操作：
1. 无障碍服务（系统设置中开启）
2. `SYSTEM_ALERT_WINDOW`（悬浮窗权限，系统设置中开启）
3. `POST_NOTIFICATIONS`（通知权限）

**当前状态：** `ReadinessChecker` 能检测并记录权限状态，`LocalLoopReadinessProvider` 在权限缺失时阻止执行，但**没有**引导用户完成授权的流程。

---

### 问题 4：PR8 文档错误描述 HardwareKeyReceiver（已修正）

**描述：** `docs/PR8_PRODUCT_READINESS_REVIEW.md` 的 Gap 4 写道："`HardwareKeyReceiver` 类文件在源码中未找到"，但**该类实际存在于 `service/BootReceiver.kt` 文件中**，位于 `com.ufo.galaxy.service.HardwareKeyReceiver`，且 `AndroidManifest.xml` 中的 `.service.HardwareKeyReceiver` 声明与之完全对应。

**修正状态：** 已修正本报告，并已同步更新 `docs/PR8_PRODUCT_READINESS_REVIEW.md`。

---

### 问题 5：缺少双仓库联合启动文档（低影响 — 运维体验）

**描述：** V2 仓库有 `docs/CLONE_TO_USE_REALITY.md`，本仓库有 Android 文档，但**没有一份文档**能同时引导运维人员从"克隆两个仓库"到"端到端跑起一个任务"。

**修正状态：** 已新建 `docs/DUAL_REPO_SETUP.md`（本次 PR 创建）。

---

### 问题 6：高级协议通道为最小兼容桩（中低影响 — 功能不完整）

**描述：** 以下 AIP v3.0 消息类型在 `GalaxyConnectionService` 中已注册，但业务逻辑未实现：

| 消息类型 | 当前状态 |
|----------|----------|
| `relay` / `forward` / `reply` | 仅日志记录 + ACK，无路由逻辑 |
| `hybrid_execute` | 解析载荷 + 记录日志 + 发送降级回复，完整混合执行器未实现 |
| `rag_query` | 仅日志记录 + 返回空结果，完整 RAG 管道未实现 |
| `code_execute` | 仅日志记录 + 返回错误结果，沙箱未实现 |
| `session_migrate` | 仅日志记录 + 发送降级回复，完整迁移逻辑未实现 |
| `broadcast` | 仅日志记录，无广播扇出 |
| `lock` / `unlock` | 仅日志记录 + ACK，无锁管理器 |

**评估：** 这些均为**有意设计的最小兼容桩**，在代码中明确标注为 `@status minimal-compat`。核心任务执行路径（`task_assign` / `goal_execution` / `task_result` / `goal_result`）均已完整实现。

---

### 问题 7：生产级 TLS 证书验证缺失（中等安全风险）

**描述：** `GalaxyWebSocketClient.buildOkHttpClient(allowSelfSigned = true)` 会安装信任所有证书的 `X509TrustManager`（空 `checkServerTrusted` + `hostnameVerifier { _, _ -> true }`），彻底禁用 TLS 验证。

**当前状态：** 此选项由 `allowSelfSigned` 参数控制，默认为 `false`，**仅在开发/内网（Tailscale）环境下按需开启**。生产构建（`buildTypes.release`）不应开启此选项。

**建议：** 生产部署时应将 `allowSelfSigned=false` 作为强制要求，或对已知服务器证书实施证书绑定（Certificate Pinning）。

---

### 问题 8：缺少 V2 端对高级信号的路由处理确认（不确定）

**描述：** Android 端正确生成并序列化了以下报告类型的载荷：
- `device_readiness_report`
- `device_governance_report`
- `device_acceptance_report`
- `device_strategy_report`

但 V2 的 `android_bridge.py` 是否路由并处理了这些消息类型（除 `task_result` / `goal_result` / `device_register` 之外），**无法从本仓库代码中确认**。

---

### 问题 9：文档版本号与实际 APK `versionName` 容易混淆（低影响 — 认知偏差）

**描述：** `README.md`、`docs/architecture.md`、本报告等文档之前统一写为 `v3.0.0`，但 `app/build.gradle` 中当前 Android APK 的实际 `versionName` 为 `2.0.1`。

**影响：** 对首次接手仓库的人来说，容易误以为 APK 版本已经提升到 `3.0.0`，从而对发布状态、兼容性和变更范围产生错误判断。

**修正状态：** 本次已统一为“**文档基线 v3.0.0 / Android app versionName 2.0.1 / 协议 AIP v3.0**”的表述，避免把文档里程碑与 APK 版本混为一谈。

---

## 4. 代码质量与架构评估

### 4.1 优势

- **架构分层清晰：** 输入路由（`InputRouter`）→ 本地执行（`EdgeExecutor` / `LocalLoopExecutor`）→ 结果回传，三层完全解耦。
- **状态管理规范：** `RuntimeController` 作为跨设备生命周期的唯一权威，所有状态变更必须经由此类，消除了竞态条件。
- **测试覆盖完整：** 198 个 JVM 单元测试，覆盖全部主要子系统：运行时状态机、委托执行信号、会话快照、回调闭合路径等。
- **可观测性完善：** `GalaxyLogger` + `MetricsRecorder` + `TraceContext` 三层结合，支持结构化日志、采样、遥测导出。
- **协议合规：** AIP v3.0 消息类型双向对齐，`UgcpSharedSchemaAlignment` 保证字段契约，`CrossRepoConsistencyGate` 执行跨仓库一致性检查。
- **离线韧性：** `OfflineTaskQueue` 提供 SharedPreferences 持久化、FIFO 顺序、24 小时 TTL、会话边界清理。

### 4.2 不足

- 本地推理服务器生命周期管理缺失（已知缺口）
- 首次使用引导流程未实现
- 部分高级协议通道为空桩

---

## 5. 系统完整架构组件清单

### 5.1 Android 主源码（255 个文件）

| 包名 | 主要组件 | 状态 |
|------|----------|------|
| `agent/` | `EdgeExecutor`, `LocalGoalExecutor`, `DelegatedTakeoverExecutor`, `DelegatedRuntimeReceiver`, `TakeoverEligibilityAssessor` | ✅ 完整 |
| `config/` | `LocalLoopConfig`, `RemoteConfigFetcher` | ✅ 完整 |
| `data/` | `AppSettings`, `SharedPrefsAppSettings`, `InMemoryAppSettings`, `Models`, `ChatMessage` | ✅ 完整 |
| `grounding/` | `SeeClickGroundingEngine`, `NcnnGroundingService` | ✅ 客户端完整；服务端需外部 |
| `history/` | `SessionHistoryStore`, `DelegatedFlowContinuityStore` | ✅ 完整 |
| `inference/` | `LocalPlannerService`, `LocalGroundingService`, `DegradedPlannerService`, `DegradedGroundingService` | ✅ 完整 |
| `input/` | `InputRouter` | ✅ 完整 |
| `local/` | `LocalLoopExecutor`, `GroundingFallbackLadder`, `PlannerFallbackLadder`, `StagnationDetector` | ✅ 完整 |
| `loop/` | `LoopController`, `LocalPlanner`, `ExecutorBridge` | ✅ 完整 |
| `network/` | `GalaxyWebSocketClient`, `OfflineTaskQueue`, `NetworkDiagnostics`, `TailscaleAdapter` | ✅ 完整 |
| `observability/` | `GalaxyLogger`, `MetricsRecorder`, `TraceContext`, `SamplingConfig`, `TelemetryExporter` | ✅ 完整 |
| `planner/` | `MobileVlmPlanner`, `LlamaCppPlannerService` | ✅ 客户端完整；服务端需外部 |
| `protocol/` | `AipModels`, `AndroidSessionLayerContracts`, `UgcpProtocolConsistencyRules` | ✅ 完整 |
| `runtime/` | `RuntimeController`, `AttachedRuntimeSession`, `RolloutControlSnapshot`, `ReconnectRecoveryState`, `StagedMeshExecutionTarget`, `ExecutionRouteTag`, 等 80+ 个文件 | ✅ 完整 |
| `service/` | `GalaxyConnectionService`, `EnhancedFloatingService`, `BootReceiver`, `HardwareKeyReceiver`, `HardwareKeyListener`, `ReadinessChecker` | ✅ 完整 |
| `session/` | `AndroidSessionContribution` | ✅ 完整 |
| `speech/` | `NaturalLanguageInputManager`, `SpeechInputManager` | ✅ 完整 |
| `ui/` | `MainActivity`, `NetworkSettingsScreen`, `ChatScreen`, `DiagnosticsScreen` | ✅ 完整 |
| `webrtc/` | `WebRTCSignalingClient`, `IceCandidateManager`, `TurnConfig` | ✅ 完整（预留扩展） |

---

## 6. 本次 PR 修正内容汇总

| 修正内容 | 文件 |
|----------|------|
| 新建双仓库联合启动指南 | `docs/DUAL_REPO_SETUP.md` |
| 更新 PR8 文档：修正 Gap 4（HardwareKeyReceiver 实际存在） | `docs/PR8_PRODUCT_READINESS_REVIEW.md` |
| 统一文档版本表述，明确文档基线与 APK `versionName` 的区别 | `README.md`、`docs/architecture.md`、`docs/SYSTEM_ANALYSIS_ZH.md` |
| 新建本中文系统分析报告 | `docs/SYSTEM_ANALYSIS_ZH.md`（本文件） |

---

## 7. 本次复核的验证说明

- 本次复核以**真实仓库代码审阅**为主，已核对主源码、测试、Manifest、关键文档与 PR 305 的实际改动。
- 当前沙箱环境在执行 `./gradlew :app:testDebugUnitTest` 时，被外部依赖下载拦截（`dl.google.com` DNS 不可达），因此**无法在本环境重新完成一次完整 Gradle 构建/测试复跑**。
- 因此，文中“已验证”优先表示“已通过源码/测试/文档交叉核对确认”；对需要联网拉取依赖的构建结论，统一按“当前环境未复跑”处理，更符合真实情况。

---

## 8. 建议优先修复的遗留问题

按优先级排序：

1. **[P1] 推理服务器生命周期管理** — 在 `GalaxyConnectionService` 或专用 `InferenceServerService` 中增加 llama.cpp 子进程管理，使本地 AI 执行路径开箱可用。
2. **[P2] 首次使用引导流程** — 增加一个设置向导 Activity，依次完成：网关 URL 配置 → 无障碍服务授权 → 悬浮窗授权 → 连通性验证。
3. **[P3] 生产级 TLS 强化** — 对已知服务端证书实施证书绑定，或至少为生产构建强制 `allowSelfSigned=false`。
4. **[P4] V2 信号处理确认** — 核查 V2 的 `android_bridge.py` 是否处理了 `device_readiness_report` / `device_governance_report` 等高级报告类型。
5. **[P5] 高级协议通道实现** — 逐步实现 `hybrid_execute` / `rag_query` / `code_execute` 等消息类型的完整业务逻辑。
