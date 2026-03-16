# UFO Galaxy Android — 变更日志

本文件记录 Android 客户端所有版本的重要变更，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

---

## [3.0.0] — 2026-03-16

> PR-C5：文档/示例 & 自动化测试收尾

### 新增
- **文档**：`README.md` 版本升至 v3.0.0，新增从 v2 升级指南和全部 6 种核心消息类型示例载荷。
- **文档**：`docs/CHANGELOG.md`（本文件）——记录 C1–C5 全系列变更。
- **文档**：`docs/AIP_V3_EXAMPLES.md`——完整 AIP v3 示例载荷文档，含 Microsoft 兼容映射示例。
- **测试**：`ExamplePayloadsTest`——针对 6 种核心消息类型（`device_register`、`heartbeat`、`capability_report`、`task_assign`、`command_result`、`task_submit`）的可运行示例测试，兼作活文档。
- **测试**：`MicrosoftMappingExampleTest`——`EnhancedAIPClient` Microsoft 映射开关（开/关）示例测试。

### 变更
- 文档统一升版至 v3.0.0，全面反映 PR-C1 至 PR-C4 所有行为变更。

---

## [2.9.0] — 2026-03-15

> PR-C4：TaskSubmitPayload & MsgType 对齐（AIP v3）

### 新增
- `TaskSubmitPayload` 新增 `task_id` 字段（与 `AipMessage.correlation_id` 对应）。
- `TaskSubmitPayload.validate()` / `validationError()` — 发送前强制字段校验。
- `AipMessage.protocol` 字段（默认 `"AIP/1.0"`）。
- `MsgType.LEGACY_TYPE_MAP` 与 `MsgType.toV3Type()` — v2→v3 类型字符串规范化工具。
- 测试：`TaskSubmitV3Test`（v3 shape、信封、类型常量、负向测试）。

### 变更
- `InputRouter.sendViaWebSocket` 传入 `task_id`，发送前调用 `validate()`。
- `MessageRouter.sendViaWebSocket` 同步传入 `task_id`。

---

## [2.8.0] — 2026-03-14

> PR-C3：EnhancedAIPClient 兼容封装（v3 信封 → Microsoft 映射）

### 新增
- `EnhancedAIPClient.microsoftMappingEnabled` 开关（默认 `true`）。
- `EnhancedAIPClient.applyMicrosoftMapping()` — 在 v3 信封基础上追加 `ms_message_type`、`ms_agent_id`、`ms_session_id` 三个补充字段，v3 字段本身始终不变。
- `EnhancedAIPClient.microsoftTypeMapping` — v3 类型名 → Microsoft wire-type 字符串映射表。
- `EnhancedAIPClient.sendWire()` — 统一发送出口，按开关决定是否应用映射。
- 测试：`EnhancedAIPClientMappingTest`（映射开/关、遗留类型规范化、所有 5 种 v3 类型映射）。

---

## [2.7.0] — 2026-03-13

> PR-C2：AIPClient / EnhancedAIPClient 发包改为 v3-only

### 变更
- `EnhancedAIPClient.sendMessage()` 在构建信封前通过 `AIPMessageBuilder.toV3Type()` 规范化消息类型。
- 所有出站消息（注册、心跳、能力上报、命令结果）均经由 `AIPMessageBuilder.build()` 构建，强制携带 `version="3.0"`、`protocol="AIP/1.0"`。
- `sendCapabilityReport()` 在注册成功后自动调用。

---

## [2.6.0] — 2026-03-12

> PR-C1：AIPMessageBuilder 强制 v3

### 新增
- `AIPMessageBuilder` 对象：
  - `PROTOCOL_AIP1` = `"AIP/1.0"`、`PROTOCOL_V3` = `"3.0"` 常量。
  - `MessageType` 内嵌对象：`DEVICE_REGISTER`、`HEARTBEAT`、`CAPABILITY_REPORT`、`TASK_ASSIGN`、`COMMAND_RESULT` 常量。
  - `LEGACY_TYPE_MAP` / `toV3Type()` — 遗留类型规范化。
  - `build()` — 强制生成含 v3 字段的出站信封。
  - `parse()` / `normalise()` — 三格式（AIP/1.0、Microsoft、v3）入站规范化。
  - `detectProtocol()` — 检测入站消息所用协议格式。
- 测试：`AIPMessageBuilderTest`（build、parse/normalise、detectProtocol、LEGACY_TYPE_MAP 全覆盖）。

---

## [2.5.x 及更早版本]

早于 PR-C1 的历史变更请参阅 `master-snapshot/` 目录下的归档文件及对应 Git 提交记录。
