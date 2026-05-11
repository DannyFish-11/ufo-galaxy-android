# 双仓联合认知审查（中心分布式智能体）— Android 落库版

> 审查对象：`DannyFish-11/ufo-galaxy-android` + `DannyFish-11/ufo-galaxy-realization-v2`  
> PR 落点：`DannyFish-11/ufo-galaxy-android`  
> 审查原则：仅基于真实代码路径、类、函数、协议流，不基于愿景描述推断。

---

## 1. 双仓联合审查范围与方法

### 1.1 审查范围
- Android 侧：`app/src/main/java/com/ufo/galaxy/**`、`.github/workflows/android-ci.yml`、`docs/V2_INTEGRATION.md`
- V2 侧（远程代码读取）：`core/**`、`galaxy_gateway/**`、`galaxy_gateway/android/handlers/**`

### 1.2 审查方法
- 目录/模块级：确认控制面、执行面、适配层、兼容层是否分离。
- 协议级：核对 `device_register` / `capability_report` / `task_assign` / `task_result` / `goal_execution_result` 的真实处理链。
- 状态级：核对 session identity（`runtime_session_id` / `runtime_attachment_session_id` / `durable_session_id`）与 continuity 恢复语义。
- 任务级：核对本地链路与跨设备链路的发起、分发、执行、回传、闭环。

### 1.3 证据边界
- 本次无法在同一运行环境直接做真实双端设备联调，仅可做代码级闭环判断；凡涉及“真实在线设备并发跑通”的结论，统一标注“证据不足（缺实机联调）”。

---

## 2. 双仓角色划分

### 2.1 `ufo-galaxy-android` 角色
- 执行侧/接入侧：`GalaxyConnectionService` 接收 `task_assign`/`goal_execution` 并调 `EdgeExecutor`、`LocalGoalExecutor`。
- 设备会话与上报侧：`GalaxyWebSocketClient.sendJson()`、`device_register`、`capability_report`、离线重放 `OfflineTaskQueue`。
- 设备运行时状态侧：`RuntimeController.v2LifecycleEvents` 与 `V2MultiDeviceLifecycleEvent` 输出设备生命周期事实。

### 2.2 `ufo-galaxy-realization-v2` 角色
- 更接近中心控制面/编排面：`core/command_router.py` 明确 `CommandRouter.route_envelope()` 为 canonical dispatch spine。
- 设备状态权威侧：`core/unified/device_manager.py`（`UnifiedDeviceManager`）声明设备状态写入 SSOT。
- 会话权威侧：`core/attached_runtime_session_registry.py` 声明 attached session 的单一真相源。
- 网关协议接入侧：`galaxy_gateway/android_bridge.py` 与 `galaxy_gateway/android/handlers/*` 处理 Android 协议消息。

### 2.3 双边对齐与单边存在
- 已对齐：AIP v3 主消息流（register/capability/task/result）、session continuity 字段、结果 canonical truth chain。
- 单边或半连接：多设备会话迁移与跨设备业务闭环验证仍偏 V2 侧；Android 侧本地推理能力有实现但默认未激活（`AppSettings.localModelEnabled=false`）。

---

## 3. 总体能力树 / 问题树（中心分布式智能体）

| 能力项 | Android 真实实现 | V2 真实实现 | 当前判断 |
|---|---|---|---|
| 中心控制能力 | 无中心调度权（执行端） | `CommandRouter` + `DesktopPresenceRuntime` + `DeviceRouter` | 主要在 V2 落地 |
| 分布式节点/设备能力 | `GalaxyConnectionService`、`RuntimeController`、`OfflineTaskQueue` | `android_bridge`、`registration.py` | 双边落地 |
| 本地链路能力 | 本地执行链完整（接收→执行→回传） | `task_submit.py`/`goal_execution.py` 生成 `task_assign` | 双边落地 |
| 跨设备链路能力 | `parallel_subtask` 接收执行、生命周期事件输出 | `DeviceRouter`/`CrossDeviceCoordinator`/并行分发 | 半闭环 |
| 协议/消息/状态同步 | AIP 消息模型、lifecycle event、durable 字段 | handler + canonical truth ingress + reconcile | 双边较完整 |
| 任务编排/接管/回退 | 执行与回传、离线缓存重放 | 编排在 `CommandRouter`，治理在 truth chain | 半闭环 |
| 上下文汇聚与分发 | 设备侧上下文有限，执行态上报为主 | `DesktopPresenceRuntime` 持有 runtime session、分发 | 主要在 V2 |
| 真实闭环交付能力 | Android CI 已有；实机双仓联调证据有限 | 代码链完整，但实机联合验证证据不足 | “真实但不完整” |

---

## 4. 本地链路联合审查

### 4.1 起点与入口
- Android 本地入口：`MainActivity/MainViewModel`（设置）→ `GalaxyConnectionService`。
- V2 入口：`galaxy_gateway/android/handlers/task_submit.py::handle_task_submit`、`goal_execution.py::handle_goal_execution`。

### 4.2 发起-接收-流转（双仓）
1. Android 或上层请求进入 V2，V2 在 `handle_task_submit/handle_goal_execution` 中构造 `MessageBuilder.task_assign(...)`。
2. Android `GalaxyConnectionService.handleTaskAssign()/handleGoalExecution()` 接收并执行。
3. 执行结果通过 `GalaxyWebSocketClient.sendJson()` 回传；断链时进入 `OfflineTaskQueue`，重连后 `flushOfflineQueue()` 重放。
4. V2 `task_lifecycle.py::handle_task_result` / `goal_execution.py::handle_goal_execution_result` 走 canonical truth chain（truth_ingress → reconcile → authority_update → completion_linkage）。

### 4.3 是否形成真实闭环
- 协议与调用闭环：已形成（代码证据充足）。
- 状态闭环：基本形成（V2 canonical truth chain + Android lifecycle/event 输出）。
- 验证闭环：部分形成（有单测/CI，但缺“同场景实机双端”长期回归证据）。

### 4.4 本地链路结论
- **完成度：75%~80%**
- **当前真实情况**：主链可运行，异常路径（离线、重放、状态归并）有代码处理。
- **关键缺口**：端到端实机联调覆盖不足；本地推理默认未激活导致“本地智能体能力”与“本地执行能力”存在落差。
- **下一步**：补双仓实机回归用例（含断线重连、重放幂等、异常回滚）。

---

## 5. 跨设备链路联合审查

### 5.1 双仓对应模块
- Android：`GalaxyConnectionService.handleParallelSubtask`、`RuntimeController.v2LifecycleEvents`、`V2MultiDeviceLifecycleEvent`。
- V2：`core/command_router.py`、`galaxy_gateway/device_router.py`、`galaxy_gateway/cross_device_coordinator.py`、`galaxy_gateway/cross_device_switch.py`。

### 5.2 中心/设备职责落地度
- 中心节点（V2）：
  - 已落地：路由/分发权威（`CommandRouter`）、设备状态权威（`UnifiedDeviceManager`）、会话权威（`AttachedRuntimeSessionRegistry`）。
  - 仍有兼容并存：`CrossDeviceCoordinator`、`DeviceRegistry`、`SessionRoamingManager` 均自述 legacy/compat layer。
- 设备节点（Android）：
  - 已落地：接收执行、结果回传、session continuity 字段上送、重连恢复事件输出。

### 5.3 会话、状态同步、上下文迁移、任务接管
- 会话/状态同步：`registration.py::handle_device_register` 已明确 canonical reconnect path（`device_register` + attachment/durable continuity）。
- 任务接管与结果归并：V2 有 `task_result_canonical_truth_chain.py` 与 `goal_execution_result` 归并流程。
- 上下文迁移：`session_roaming.py` 明确标注 legacy compat，不是 canonical 主链，说明“迁移语义有实现但非统一主干”。

### 5.4 跨设备链路结论
- **完成度：50%~60%**
- **当前真实情况**：中心编排骨架已成型，设备执行协同可通，但兼容层与主链并存，语义仍在收敛。
- **关键缺口**：跨设备“统一主链唯一性”尚未完全消灭 legacy 旁路；缺稳定的双仓多设备实机闭环验证。
- **下一步**：继续压缩 compat path，建立跨设备稳定回归矩阵（多设备并发 + 会话迁移 + 失败恢复）。

---

## 6. “两层不完整真实情况”的联合认知判断

### 6.1 为什么是“真实但不完整”
- 真实：主协议、主分发、主结果归并、主会话字段均在真实代码中可追踪。
- 不完整：仍存在兼容层并行与实机验证不足，导致“语义成立度”未到可宣称 fully closed-loop。

### 6.2 两层定义（基于代码）
1. **架构/权威层不完整**  
   - V2 已定义 canonical authority，但 `DeviceRegistry`/`CrossDeviceCoordinator`/`SessionRoamingManager` 仍保留 legacy compat 职责。
2. **交付/验证层不完整**  
   - Android CI 已覆盖构建/单测/lint/e2e-harness 单测，但双仓实机跨设备链路的长期自动化证据不足。

### 6.3 两层叠加影响
- 系统已具备“中心 + 分布式节点”框架，但在跨设备复杂场景下仍可能出现“主链与兼容路径并行”的运维与验证成本。

---

## 7. 中心分布式智能体语义成立度判断

- **判断**：**方向成立，但关键语义仍在收敛**（不是“仅拼接”，也未达到“完整成立”）。
- 中心语义：V2 已具备控制中枢/状态中枢/任务编排中枢的主干代码（`CommandRouter`、`UnifiedDeviceManager`、`AttachedRuntimeSessionRegistry`）。
- 设备语义：Android 具备独立执行职责、状态事实输出、可被协调与恢复语义（`RuntimeController` + `V2MultiDeviceLifecycleEvent` + continuity 字段）。
- 阻塞点：兼容并行路径仍在、跨设备实机验证链仍缺长期化自动证明。

---

## 8. 双仓真实代码闭环交付矩阵

| 能力项 | Android 侧关键文件/模块 | V2 侧关键文件/模块 | 真实实现 | 调用闭环 | 状态闭环 | 覆盖本地链路 | 覆盖跨设备链路 | 当前判断 | 风险/缺口 |
|---|---|---|---|---|---|---|---|---|---|
| 设备注册与能力上报 | `GalaxyWebSocketClient.kt` | `registration.py`、`capability_report.py` | 是 | 是 | 是 | 是 | 是 | 已可用 | 需实机长稳回归 |
| 任务下发与执行 | `GalaxyConnectionService.kt` | `task_submit.py`、`goal_execution.py` | 是 | 是 | 部分 | 是 | 部分 | 主链成立 | 并发跨设备仍需强化 |
| 结果回传与归并 | `sendJson` + `OfflineTaskQueue.kt` | `task_lifecycle.py`、`task_result_canonical_truth_chain.py` | 是 | 是 | 是 | 是 | 是 | 关键闭环存在 | 需补跨设备异常重放实证 |
| 会话连续性 | `AttachedRuntimeSession.kt`、`RuntimeController.kt` | `attached_runtime_session_registry.py`、`registration.py` | 是 | 部分 | 部分 | 是 | 是 | 语义对齐中 | 端到端迁移场景证据不足 |
| 跨设备分发 | `handleParallelSubtask` | `command_router.py`、`device_router.py`、`cross_device_coordinator.py` | 是 | 部分 | 部分 | 否 | 是 | 半闭环 | legacy 路径并存 |
| 本地推理能力 | `MobileVlmPlanner`、`SeeClickGroundingEngine`、`LocalInferenceRuntimeManager` | `desktop_presence_runtime.py`（调度壳） | 部分 | 部分 | 部分 | 是 | 否 | 有实现未默认激活 | 依赖本地模型与运行环境 |
| 兼容层收敛 | `AgentRuntimeBridge.kt` 等 | `DeviceRegistry`、`SessionRoamingManager` | 是 | 否 | 否 | 间接 | 间接 | 仍在收敛 | 容易产生语义分叉 |

---

## 9. 完成度结论（联合视角）

- **双仓整体完成度**：**60%~68%**
  - 依据：中心权威主干+设备执行主干已存在；但跨设备统一主链与实机验证仍不充分。
- **本地链路完成度**：**75%~80%**
  - 依据：register/capability/task/result 主链完整，离线重放与结果归并均有代码实现。
- **跨设备链路完成度**：**50%~60%**
  - 依据：编排/分发/回传均有实现，但 legacy/compat 并行与实机多设备回归不足。
- **中心分布式智能体语义成立度**：**58%~65%**
  - 依据：中心与节点角色已清晰，语义方向成立；“唯一主链 + 可验证交付”尚未完全闭合。

---

## 10. 下一步必须做的事（P0 / P1 / P2）

### P0（不做就不能称为真实闭环）
1. **双仓协议/状态/任务接管对齐**
   - 固化 `device_register` reconnect canonical path 与 `durable_session_id/session_continuity_epoch` 的端到端回归。
2. **跨设备链路**
   - 以多设备实机场景验证 `parallel_subtask → goal_execution_result → canonical truth chain` 全链路。
3. **真实验证与交付证明**
   - 建立“Android 真设备 + V2 网关”自动回归流水线，产出可追溯报告。

### P1（影响体系成立质量）
1. **Android 仓侧**
   - 强化 `OfflineTaskQueue` 与 continuity 的组合回归（断线、重连、会话切换、幂等）。
2. **realization-v2 仓侧**
   - 继续压缩 `DeviceRegistry` / `SessionRoamingManager` / `CrossDeviceCoordinator` 的非 canonical 调用面。
3. **本地链路**
   - 明确本地推理激活路径与失败降级的统一验收标准。

### P2（增强项）
1. **观测与诊断**
   - 补齐双仓统一 trace_id/session_id 观测面板，快速定位跨设备断点。
2. **治理与文档**
   - 对所有兼容路径标注 retirement 条件与移除时间窗。
3. **性能**
   - 补跨设备并发吞吐与失败恢复的压测基线。

---

## 附：关键代码证据索引（节选）

- Android：
  - `app/src/main/java/com/ufo/galaxy/network/GalaxyWebSocketClient.kt`
  - `app/src/main/java/com/ufo/galaxy/service/GalaxyConnectionService.kt`
  - `app/src/main/java/com/ufo/galaxy/network/OfflineTaskQueue.kt`
  - `app/src/main/java/com/ufo/galaxy/runtime/RuntimeController.kt`
  - `app/src/main/java/com/ufo/galaxy/runtime/V2MultiDeviceLifecycleEvent.kt`
  - `app/src/main/java/com/ufo/galaxy/data/AppSettings.kt`
  - `.github/workflows/android-ci.yml`
- V2：
  - `core/command_router.py`
  - `core/unified/device_manager.py`
  - `core/attached_runtime_session_registry.py`
  - `core/task_result_canonical_truth_chain.py`
  - `galaxy_gateway/android_bridge.py`
  - `galaxy_gateway/device_router.py`
  - `galaxy_gateway/cross_device_switch.py`
  - `galaxy_gateway/cross_device_coordinator.py`
  - `galaxy_gateway/android/handlers/registration.py`
  - `galaxy_gateway/android/handlers/task_submit.py`
  - `galaxy_gateway/android/handlers/goal_execution.py`
  - `galaxy_gateway/android/handlers/task_lifecycle.py`
  - `core/device_registry.py`
  - `galaxy_gateway/session_roaming.py`
