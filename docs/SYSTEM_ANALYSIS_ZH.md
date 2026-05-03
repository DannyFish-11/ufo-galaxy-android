# UFO Galaxy Android — 系统全面分析报告（中文）

**撰写日期：** 2026-05-03  
**文档基线：** v3.0.0 · **Android app versionName：** 2.0.1 · **协议：** AIP v3.0  
**代码规模：** 主源文件 256 个 · 测试文件 198 个 · Kotlin 总量 454 个（2026-05-03 以 `find app/src -name '*.kt'` 复核）

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

### 2.1 总体完成度：**约 78%（开发者可运行，非用户开箱即用）**

| 维度 | 状态 | 置信度 |
|------|------|--------|
| 代码构建链路 | ✅ CI 已补齐；本地沙箱复跑受外网阻塞 | `.github/workflows/android-ci.yml` 已包含构建、协议、运行时、E2E harness、全量单测、lint 门禁；当前沙箱仍受 `dl.google.com` DNS 阻塞 |
| 服务生命周期（Service/BootReceiver） | ✅ 完成 | 已验证 |
| WebSocket 连接 + 断线重连 | ✅ 完成 | 已验证 |
| 跨设备任务协议（AIP v3.0） | ✅ 完成 | 已验证 |
| 离线任务队列 + 恢复回放 | ✅ 完成 | 已验证 |
| 悬浮窗 UI（FloatingIsland） | ✅ 完成 | 已验证 |
| 语音输入 + 自然语言规范化 | ✅ 完成 | 已验证 |
| 单元测试覆盖（100+ 测试文件） | ✅ 完成 | 已验证 |
| 本地推理集成（MobileVLM + SeeClick） | ⚠️ 部分 | 客户端与 `LocalInferenceRuntimeManager` 就绪；真实推理服务/模型仍需外部部署 |
| 设备无障碍操作 | ⚠️ 部分 | 需手动授权 |
| 首次使用引导流程 | ❌ 缺失 | 未实现 |
| 双仓库联合启动文档 | ✅ 已补齐 | `DUAL_REPO_SETUP.md` 已创建 |
| 高级协议通道（RAG / CODE_EXEC / 混合执行等） | ⚠️ 部分收口 | `peer_announce` / `peer_exchange` / `mesh_topology` / `coord_sync` 已提升为结构化处理；RAG、CODE_EXEC、relay 等仍为兼容桩 |
| 生产级 TLS 证书绑定 | ⚠️ 需加强 | release/production 已强制关闭 self-signed 信任；尚无证书绑定 |

---

## 3. 排查发现的所有问题

### 问题 1：真实本地推理服务未随 APK 开箱运行（高影响 — 本地 AI 路径运行障碍）

**描述：** `MobileVlmPlanner` 和 `SeeClickGroundingEngine` 均通过 HTTP 访问本机 `http://127.0.0.1:8080` 上的推理服务器。仓库内已经有 `LocalInferenceRuntimeManager` 管理 planner/grounding 的启动、健康检查、降级、恢复与 safe-mode 状态，但 APK 本身仍**不内置真实模型服务二进制、不自动下载并启动可用推理进程**。

**当前状态：** HTTP 客户端代码完整，模型资产管理器（`ModelAssetManager`）、模型下载器（`ModelDownloader`）和 `LocalInferenceRuntimeManager` 就绪；缺口已经从“没有生命周期管理”收敛为“没有可开箱运行的真实推理服务/模型打包与设备端拉起方案”。

**影响：** 未外部配置推理服务器的设备上，`LocalLoopExecutor` 的模型就绪性检查会失败，本地 AI 执行路径不可用。

**缓解路径：** 使用 `crossDeviceEnabled=true`，由 V2 后端的 `Node_113_AndroidVLM` 提供远程推理。这是大多数设备的实际生产路径。

**状态：** 已知差距，未完全修复（属于产品打包、模型分发与设备资源策略层面）。

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

### 问题 6：部分高级协议通道仍为最小兼容桩（中低影响 — 功能不完整）

**描述：** AIP v3.0 的主链消息（`device_register` / `capability_report` / `task_assign` / `goal_execution` / `task_result` / `goal_result`）已经闭环；部分高级消息也已从兼容桩提升为结构化处理。但以下消息类型在 `GalaxyConnectionService` / `AipModels` 中仍属于过渡兼容层：

| 消息类型 | 当前状态 |
|----------|----------|
| `relay` / `forward` / `reply` | 日志记录或 ACK，无完整路由/回复状态机 |
| `hybrid_execute` | 解析载荷 + 记录日志 + 发送降级回复，完整混合执行器未实现 |
| `rag_query` | 仅日志记录 + 返回空结果，完整 RAG 管道未实现 |
| `code_execute` | 仅日志记录 + 返回错误结果，沙箱未实现 |
| `session_migrate` | 仅日志记录 + 发送降级回复，完整迁移逻辑未实现 |
| `wake_event` | 日志记录 + ACK，无 suspend/resume 状态机 |
| `broadcast` | 仅日志记录，无广播扇出 |
| `lock` / `unlock` | 仅日志记录 + ACK，无锁管理器 |

**已提升出兼容桩的通道：** `peer_announce`、`peer_exchange`、`mesh_topology`、`coord_sync` 已有专用 payload / ACK / 状态记录路径，不能再按“全部高级通道均未实现”描述。

**评估：** 剩余兼容桩均为**有意设计的最小兼容层**，在代码中明确标注为 `@status minimal-compat` 或登记在兼容退场注册表中。核心任务执行路径（`task_assign` / `goal_execution` / `task_result` / `goal_result`）均已实现。

---

### 问题 7：生产级 TLS 证书验证缺失（中等安全风险）

**描述：** `GalaxyWebSocketClient.buildOkHttpClient(allowSelfSigned = true)` 会安装信任所有证书的 `X509TrustManager`（空 `checkServerTrusted` + `hostnameVerifier { _, _ -> true }`），彻底禁用 TLS 验证。

**当前状态：** 此选项由 `allowSelfSigned` 参数控制，默认为 `false`，**仅在开发/内网（Tailscale）环境下按需开启**。`AppSettings.effectiveAllowSelfSigned()` 与 `GalaxyWebSocketClient.buildOkHttpClient(..., isDebugBuild)` 已通过 `BuildConfig.DEBUG` 将 release/production 的有效值强制为 `false`。

**建议：** 下一步应对已知服务器证书实施证书绑定（Certificate Pinning），并增加发布门禁测试证明 production 构建不会启用 trust-all TLS。

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

### 问题 10：CI 门禁已有最小闭环，但真实设备 E2E 仍未自动化（中影响 — 发布置信度不足）

**描述：** 仓库已存在 `.github/workflows/android-ci.yml`，覆盖 debug 构建、协议契约测试、运行时回归、E2E harness 单测、全量 JVM 单测与 lint，已经解决“完全没有 CI”的基础设施缺口。

**剩余缺口：** 该 CI 在无实体 Android 设备时只能生成诚实的 `e2e_blocked_no_device` 状态，不能声称完成真实设备端到端验证。真实设备上的“安装 APK → 授权 → 连接 V2 → 下发任务 → 回传结果”仍需要人工或专门设备农场验证。

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

- 本地推理真实服务/模型分发未开箱内置（`LocalInferenceRuntimeManager` 已存在，但仍需外部推理服务）
- 首次使用引导流程未实现
- 部分高级协议通道为空桩
- 真实设备 E2E 尚未进入自动化 CI

---

## 5. 系统完整架构组件清单

### 5.1 Android 主源码（256 个 Kotlin 文件）

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
| `runtime/` | `RuntimeController`, `AttachedRuntimeSession`, `LocalInferenceRuntimeManager`, `RolloutControlSnapshot`, `ReconnectRecoveryState`, `StagedMeshExecutionTarget`, `ExecutionRouteTag`, 等 80+ 个文件 | ✅ 主链完整；本地推理运行需外部服务 |
| `service/` | `GalaxyConnectionService`, `EnhancedFloatingService`, `BootReceiver`, `HardwareKeyReceiver`, `HardwareKeyListener`, `ReadinessChecker` | ✅ 完整 |
| `session/` | `AndroidSessionContribution` | ✅ 完整 |
| `speech/` | `NaturalLanguageInputManager`, `SpeechInputManager` | ✅ 完整 |
| `ui/` | `MainActivity`, `NetworkSettingsScreen`, `ChatScreen`, `DiagnosticsScreen` | ✅ 完整 |
| `webrtc/` | `WebRTCSignalingClient`, `IceCandidateManager`, `TurnConfig` | ✅ 完整（预留扩展） |

---

## 6. 本轮收口内容汇总

| 修正内容 | 文件 |
|----------|------|
| 明确系统真实定位、双仓边界、主链/扩展能力分层 | `docs/SYSTEM_ANALYSIS_ZH.md`（本文件） |
| 按真实代码更新完成度、源码/测试规模、CI 门禁与本地验证阻塞说明 | `docs/SYSTEM_ANALYSIS_ZH.md` |
| 将本地推理差距从“完全无生命周期管理”修正为“管理器已存在，真实服务/模型仍未开箱内置” | `docs/SYSTEM_ANALYSIS_ZH.md` |
| 将高级协议通道状态拆分为“已提升结构化处理”和“仍为 minimal-compat”两类 | `docs/SYSTEM_ANALYSIS_ZH.md` |

---

## 7. 本次复核的验证说明

- 本次复核以**真实仓库代码审阅**为主，已核对主源码、测试、Manifest、CI 工作流与关键文档。
- 当前沙箱环境在执行 `./gradlew :app:testDebugUnitTest` 时，被外部依赖下载拦截（`dl.google.com` DNS 不可达），因此**无法在本环境重新完成一次完整 Gradle 构建/测试复跑**。本轮已再次复跑该命令，失败点仍为 `com.android.tools.build:gradle:8.2.0` 从 `https://dl.google.com/dl/android/maven2/...` 下载失败。
- 因此，文中“已验证”优先表示“已通过源码/测试/文档交叉核对确认”；对需要联网拉取依赖的构建结论，统一按“当前环境未复跑”处理，更符合真实情况。

---

## 8. 建议优先修复的遗留问题

按优先级排序：

1. **[P1] 本地推理开箱运行闭环** — 在现有 `LocalInferenceRuntimeManager` 基础上补齐真实模型分发、推理服务二进制/进程拉起、资源限制与失败恢复策略，使本地 AI 执行路径不再依赖人工外部启动。
2. **[P2] 首次使用引导流程** — 增加一个设置向导 Activity，依次完成：网关 URL 配置 → 无障碍服务授权 → 悬浮窗授权 → 通知授权 → 连通性验证。
3. **[P3] 真实设备 E2E 自动化** — 在 CI 或专用设备池中验证：安装 APK → 授权 → 连接 V2 → 下发任务 → Android 执行 → 回传结果；无设备时继续输出诚实的 blocked artifact。
4. **[P4] 生产级 TLS 强化** — 当前代码已通过 `effectiveAllowSelfSigned()` 保证 release/production 构建强制关闭 self-signed 信任；下一步应对已知服务端证书实施证书绑定（Certificate Pinning）并增加发布门禁。
5. **[P5] V2 信号处理确认** — 核查 V2 的 `android_bridge.py` 是否处理了 `device_readiness_report` / `device_governance_report` 等高级报告类型。
6. **[P6] 剩余高级协议通道实现** — 逐步实现 `hybrid_execute` / `rag_query` / `code_execute` / `relay` / `broadcast` / `lock` 等消息类型的完整业务逻辑，或正式标记为非产品主链并制定退场策略。

---

## 9. 距离 100% 完成度还差什么？

这里的“100%”不等于“代码文件都存在”，而是指**普通用户或运维人员可以稳定、可验证、可回归地把系统跑成产品闭环**。按当前仓库真实状态，剩余差距如下：

| 差距 | 为什么还没到 100% | 收口标准 |
|------|------------------|----------|
| 本地 AI 开箱运行 | planner / grounding 客户端和运行时管理器存在，但真实模型服务、模型文件、设备资源策略仍需外部准备 | APK 或官方安装流程能自动准备模型与推理服务；无服务器时有明确降级与恢复 UI |
| 首次使用引导 | 权限检测和失败分类已有，但没有一步步带用户完成授权和网关配置的向导 | 首次打开即可完成网关、无障碍、悬浮窗、通知、连通性检查 |
| 真实设备 E2E 自动化 | CI 有单测和 harness，但无实体设备时只能诚实标记 blocked | 至少一条设备池或手工认证流程能产出可追溯的 real-device E2E artifact |
| V2 双仓消费确认 | Android 侧能发出多类信号，但 V2 侧对部分高级报告的消费无法仅从本仓确认 | 双仓契约测试覆盖 register、capability、task、result、reconnect、governance/report 信号 |
| 剩余高级协议业务逻辑 | RAG、CODE_EXEC、relay、broadcast、lock 等仍是 minimal-compat | 要么实现完整业务闭环，要么从产品能力声明中降级/移除并保留兼容 ACK |
| 生产安全加固 | release 已禁用 self-signed 信任，但尚无证书绑定与发布门禁证明 | 证书绑定、发布配置检查、安全回归测试全部落地 |

**一句话结论：** Android 仓的主链已经接近可发布参与者运行时；距离 100% 差的不是“再补几个普通类”，而是本地 AI 开箱化、首次使用引导、真实设备 E2E、双仓契约自动化和生产安全门禁这些产品化闭环。
