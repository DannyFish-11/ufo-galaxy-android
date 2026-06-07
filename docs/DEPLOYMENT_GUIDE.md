# UFO Galaxy Android — 部署指南

> **版本: v2.1.0** | 网络与诊断增强包已集成

---

## 目录

1. [快速开始](#快速开始)
2. [App 内配置网关（推荐）](#app-内配置网关推荐)
3. [Tailscale 配置](#tailscale-配置)
4. [编译时预配置（可选）](#编译时预配置可选)
5. [TLS / 自签名证书](#tls--自签名证书)
6. [网络诊断](#网络诊断)
7. [指标与可观测性](#指标与可观测性)
8. [故障排查](#故障排查)

---

## 快速开始

1. 构建并安装 APK：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. 打开 App → 顶栏 **⚙** 图标 → **网络与诊断**
3. 填入网关 IP 和端口 → **保存并重连**
4. 开启顶栏跨设备开关

---

## App 内配置网关（推荐）

从 v2.1 起，你可以直接在 App 内配置网关，**无需修改代码或重新打包 APK**。

### 打开设置界面

主界面 → 顶栏右侧 **⚙ 齿轮图标** → 进入 **网络与诊断** 界面。

### 字段说明

| 字段 | Key (SharedPrefs) | 说明 | 默认 |
|------|-------------------|------|------|
| 主机/IP | `gateway_host` | 网关 IP 或域名 | `""` (使用下方 URL) |
| 端口 | `gateway_port` | TCP 端口 | `8765` |
| 使用 TLS | `use_tls` | 启用 `wss://` / `https://` | `false` |
| 允许自签名 | `allow_self_signed` | 信任自签名 TLS 证书 | `false` |
| 设备 ID | `device_id` | 握手时上报的设备标识符 | `""` (系统自动生成) |
| REST 基础 URL | `rest_base_url` | REST 请求基础地址 | 由 host+port 自动推导 |
| 指标上报端点 | `metrics_endpoint` | POST 遥测数据的端点 | `""` (不上报) |

### URL 构建优先级

```
1. gateway_host + gateway_port + use_tls  (SharedPreferences, 最高优先级)
        ↓
2. galaxy_gateway_url  (SharedPreferences 或 assets/config.properties)
        ↓
3. BuildConfig.GALAXY_SERVER_URL  (编译时默认，最低优先级)
```

### 操作按钮

| 按钮 | 说明 |
|------|------|
| **保存** | 写入 SharedPreferences，不触发重连 |
| **保存并重连** | 写入后断开当前 WS，用新配置重新连接 |
| **自动探测** | 扫描 Tailscale 网段 (`100.64.0.0/10`) 发现网关 |
| **一键填入 Tailscale** | 检测本机 Tailscale IP 并写入主机字段 |
| **运行诊断** | 执行 DNS + HTTP + WS + AIP Ping 四项检查 |

---

## Tailscale 配置

UFO Galaxy 原生支持 Tailscale VPN 实现跨设备通信，**无需在同一局域网**。

### 步骤

1. 在服务端机器和 Android 设备上均安装并登录 **[Tailscale](https://tailscale.com/)**
2. 确认服务端的 Tailscale IP（通常为 `100.x.x.x`）
3. 打开 App ⚙ 设置界面：
   - 点击 **一键填入 Tailscale** 自动检测本机 Tailscale IP
   - 或 点击 **自动探测** 扫描 `100.64.x.x` 网段
4. 确认填入的 IP 正确后点击 **保存并重连**

> **提示**：如果自动探测失败，请手动填入服务端的 Tailscale IP。

### 验证连接

点击 **运行诊断** 查看详细报告：
- ✓ DNS 解析 — 能解析主机名
- ✓ HTTP /health — 服务端 REST 接口健康
- ✓ WS 握手 — WebSocket 连接成功
- ✓ AIP Ping — AIP 协议层可达

---

## 编译时预配置（可选）

如需提前烧录默认地址（例如配置专用 APK），可使用以下两种方式之一：

### 方式 A — `assets/config.properties`

在 `app/src/main/assets/config.properties` 中填写（不需要重编译，适合 CD 流水线替换）：
```properties
# UFO Galaxy 网关配置
galaxy_gateway_url=ws://100.64.0.1:8765
rest_base_url=http://100.64.0.1:8765
```

### 方式 B — `build.gradle`（编译时写死）

```gradle
defaultConfig {
    buildConfigField "String", "GALAXY_SERVER_URL", '"ws://100.64.0.1:8765"'
}
```

> **注意**：App 内 ⚙ 设置的值优先于以上两种方式。用户可在 App 内随时修改，无需重打包。

### build_apk.sh 使用说明

```bash
# 构建 debug APK
./build_apk.sh

# 构建 release APK（需签名配置）
./gradlew assembleRelease
```

> 从 v2.1 起，`build_configured_apk.sh` 脚本仅作为参考。推荐改用 **App 内 ⚙ 配置** 替代在脚本中编辑 `AIPClient` 地址。

---

## TLS / 自签名证书

### 公网 / 受信 CA 场景

1. 在 **网络与诊断** 界面开启 **使用 TLS**
2. 确保 `allow_self_signed = false`（默认）
3. 将主机填为域名或合法证书对应的 IP

### 内网自签名证书（仅调试）

1. 开启 **使用 TLS**
2. 开启 **允许自签名证书**
3. 在弹出的 ⚠ 提示下确认（仅限 Tailscale 或私有内网使用）

> **安全警告**：`allow_self_signed = true` 禁用了 TLS 证书验证。**切勿在公共网络上使用此选项。**

---

## 网络诊断

在 **网络与诊断** 界面点击 **运行诊断** 可执行以下检查：

| 检查项 | 内容 |
|--------|------|
| DNS 解析 | 解析 gateway_host，返回 IP 和耗时 |
| HTTP /health | GET `rest_base/health`，检查 HTTP 状态码和耗时 |
| WS 握手 | 尝试连接 WebSocket，记录握手成功/失败和耗时 |
| AIP Ping | HEAD `rest_base`，验证 AIP 层连通性 |

诊断结果可在界面直接查看，也可通过 **ⓘ 诊断面板** 的 "Copy" 按钮复制到剪贴板。

### 从跨设备错误对话框运行诊断

当跨设备注册失败时，对话框提供 **"查看诊断/设置"** 按钮，直接跳转到网络设置界面运行诊断，无需手动导航。

---

## 指标与可观测性

### 本地指标

`MetricsRecorder` 每 5 分钟在本地 `GalaxyLogger` 记录一次：

```
[METRICS] ws_reconnects=3 reg_failures=1 task_ok=12 task_fail=0 uptime_ms=1800000
```

日志文件路径：`/data/data/com.ufo.galaxy/files/galaxy_observability.log`

通过 **ⓘ 诊断面板** → **Share Logs** 可导出日志文件。

### 远程上报（可选）

在 **指标上报端点** 字段填入 HTTP 端点，`MetricsRecorder` 将每 5 分钟 POST 一次 JSON 报告：
```json
{
  "ws_reconnects": 3,
  "registration_failures": 1,
  "task_successes": 12,
  "task_failures": 0,
  "uptime_ms": 1800000,
  "ts": 1737000000000
}
```

---

## 故障排查

### 跨设备无法连接

1. 打开 ⚙ 设置 → **运行诊断**，查看哪一项检查失败
2. DNS 失败 → 检查 `gateway_host` 是否正确，网络是否可达
3. HTTP /health 失败 → 服务端是否运行？端口是否开放？
4. WS 握手失败 → 检查防火墙；如使用 TLS，检查证书配置
5. AIP Ping 失败 → 服务端 REST API 是否正常

### 首次开启跨设备开关

- 若网关 **未配置**（`gateway_host` 为空且 URL 包含占位符 `100.x.x.x`），App 会自动打开 **网络与诊断** 设置界面，引导用户配置或自动探测。
- 配置完成后再次开启跨设备开关即可连接。

### 弱网 / 断网场景

- WS 自动重连（指数退避 1→2→4→8→16→30s，最多 10 次）
- 断网期间的 `task_result` / `goal_result` 会离线缓存，重连后自动补发

### 日志查看

```bash
adb logcat | grep -E "GALAXY:|MetricsRecorder|TailscaleAdapter|NetworkDiagnostics"
```
