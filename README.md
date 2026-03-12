# UFO Galaxy Android 客户端

**版本: v2.0.1**

L4 级自主性智能系统 Android 客户端，实现书法卷轴式 UI 和系统级 AI 交互。

## 仓库关系

本仓库是 [ufo-galaxy-realization](https://github.com/DannyFish-11/ufo-galaxy-realization) 主仓库的 Android 客户端子模块。

- **主仓库**: https://github.com/DannyFish-11/ufo-galaxy-realization
- **版本同步**: v2.0.1
- **同步时间**: 2025-02-15

## 功能特性

### 核心功能
- **书法卷轴式 UI** - 一展一收的写意风格交互
- **灵动岛悬浮窗** - 系统级常驻 AI 入口
- **实时通信** - WebSocket 连接 Galaxy 服务器
- **语音输入** - 支持语音识别输入
- **开机自启** - 系统启动时自动连接

### UI 组件
- `ScrollPaperContainer` - 书法卷轴容器
- `ChatScreen` - 聊天界面
- `DynamicIslandIndicator` - 灵动岛指示器

### 服务
- `GalaxyConnectionService` - 后台连接服务
- `FloatingWindowService` - 悬浮窗服务
- `BootReceiver` - 开机启动接收器

## 环境要求

- **Android Studio**: Arctic Fox (2020.3.1) 或更高版本
- **JDK**: 17 或更高版本
- **Android SDK**: API 26 (Android 8.0) 或更高版本
- **Kotlin**: 1.9.21
- **Gradle**: 8.4

## 快速开始

### 1. 克隆仓库
```bash
git clone https://github.com/DannyFish-11/ufo-galaxy-realization.git
cd ufo-galaxy-realization/android_client
```

### 2. 配置服务器地址
编辑 `app/build.gradle`，修改 `GALAXY_SERVER_URL`:
```gradle
buildConfigField "String", "GALAXY_SERVER_URL", '"ws://YOUR_SERVER_IP:8765"'
```

### 3. 使用 Android Studio 打开
1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择 `android_client` 目录
4. 等待 Gradle 同步完成

### 4. 构建 APK
```bash
# 使用脚本
chmod +x build_apk.sh
./build_apk.sh

# 或使用 Gradle
./gradlew assembleDebug
```

### 5. 安装到设备
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
android_client/
├── app/
│   ├── src/main/
│   │   ├── java/com/ufo/galaxy/
│   │   │   ├── UFOGalaxyApplication.kt    # 应用入口
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt        # 主 Activity
│   │   │   │   ├── viewmodel/
│   │   │   │   │   └── MainViewModel.kt   # 主 ViewModel
│   │   │   │   ├── components/
│   │   │   │   │   ├── ScrollPaperContainer.kt  # 书法卷轴
│   │   │   │   │   └── ChatScreen.kt      # 聊天界面
│   │   │   │   └── theme/
│   │   │   │       ├── Theme.kt           # 主题配置
│   │   │   │       └── Type.kt            # 字体配置
│   │   │   ├── service/
│   │   │   │   ├── GalaxyConnectionService.kt  # 连接服务
│   │   │   │   ├── FloatingWindowService.kt    # 悬浮窗服务
│   │   │   │   └── BootReceiver.kt        # 开机启动
│   │   │   ├── network/
│   │   │   │   └── GalaxyWebSocketClient.kt    # WebSocket 客户端
│   │   │   └── data/
│   │   │       └── Models.kt              # 数据模型
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml            # 字符串资源
│   │   │   │   ├── colors.xml             # 颜色资源
│   │   │   │   └── themes.xml             # 主题资源
│   │   │   └── drawable/
│   │   │       ├── ic_notification.xml    # 通知图标
│   │   │       └── dynamic_island_bg.xml  # 灵动岛背景
│   │   └── AndroidManifest.xml            # 清单文件
│   ├── build.gradle                       # 模块构建配置
│   └── proguard-rules.pro                 # ProGuard 规则
├── build.gradle                           # 项目构建配置
├── settings.gradle                        # 项目设置
├── gradle.properties                      # Gradle 属性
├── build_apk.sh                           # APK 打包脚本
└── README.md                              # 本文档
```

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 网络通信 |
| RECORD_AUDIO | 语音输入 |
| CAMERA | 拍照功能 |
| SYSTEM_ALERT_WINDOW | 悬浮窗显示 |
| FOREGROUND_SERVICE | 后台服务 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| VIBRATE | 触觉反馈 |
| POST_NOTIFICATIONS | 通知显示 |

## 配置说明

### 服务器配置
在 `app/build.gradle` 中配置:
```gradle
defaultConfig {
    buildConfigField "String", "GALAXY_SERVER_URL", '"ws://192.168.1.100:8765"'
}

buildTypes {
    release {
        buildConfigField "String", "GALAXY_SERVER_URL", '"wss://galaxy.ufo.ai:8765"'
    }
}
```

### 签名配置
Release 构建需要签名密钥:
```gradle
signingConfigs {
    release {
        storeFile file("keystore.jks")
        storePassword "your_password"
        keyAlias "ufo_galaxy"
        keyPassword "your_password"
    }
}
```

## 开发指南

### 添加新功能
1. 在 `data/Models.kt` 中定义数据模型
2. 在 `network/` 中实现网络逻辑
3. 在 `ui/components/` 中创建 UI 组件
4. 在 `ui/viewmodel/` 中管理状态

### 自定义主题
编辑 `ui/theme/Theme.kt` 修改配色方案:
```kotlin
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2C2C2C),           // 墨色
    primaryContainer = Color(0xFFF5F0E6),   // 宣纸色
    secondary = Color(0xFF8B4513),          // 朱砂色
    // ...
)
```

## 常见问题

### Q: 悬浮窗不显示？
A: 请在系统设置中授予 UFO Galaxy 悬浮窗权限。

### Q: 无法连接服务器？
A: 检查服务器地址配置和网络连接，确保服务器正在运行。

### Q: 语音输入不工作？
A: 请授予麦克风权限，并确保设备支持语音识别。

## 许可证

MIT License

---

## 🔗 关联仓库

本 Android 客户端与以下服务端仓库配合使用：

- **服务端**: [ufo-galaxy-realization-v2](https://github.com/DannyFish-11/ufo-galaxy-realization-v2)
- **当前版本**: v2.0.3
- **协议版本**: AIP v2.0

### 版本兼容性

| Android 版本 | 服务端版本 | 协议版本 | 状态 |
|-------------|-----------|---------|------|
| v2.0.1 | v2.0.3 | AIP v2.0 | ✅ 兼容 |
| v2.0.1 | v2.0.4+ | AIP v3.0 | ✅ 兼容 |


---

## 📱 最新版本

| 组件 | 版本 | 状态 |
|------|------|------|
| 服务端 | v2.0.4 | ✅ 已发布 |
| Android 客户端 | v2.0.1 | ✅ 已发布 |
| 协议版本 | AIP v3.0 | ✅ 兼容 |

### 快速开始

1. 克隆服务端仓库
```bash
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2
./deploy.sh
```

2. 构建 Android 客户端
```bash
./gradlew assembleDebug
```

3. 安装到设备
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```


---

## 🌐 分布式部署

UFO Galaxy V2 支持分布式部署，可以将任意设备设为主节点：

### 支持的部署模式

| 模式 | 说明 |
|------|------|
| 云服务器主节点 | 云服务器作为主节点，本地设备作为工作节点 |
| 本地电脑主节点 | 本地电脑作为主节点，云服务器作为备用 |
| 多主节点集群 | 多个主节点负载均衡 |

### 配置方法

```bash
# 主节点
export UFO_NODE_ID="master"
export UFO_NODE_ROLE="coordinator"
./start.sh

# 工作节点
export UFO_NODE_ID="worker-$(hostname)"
export UFO_NODE_ROLE="worker"
export MASTER_URL="ws://master-host:8765"
./start.sh --worker
```


---

## 📋 设备注册流程

### Android 设备注册

1. **安装 APK**
   - 下载并安装 UFO Galaxy 客户端

2. **配置服务器地址**
   - 打开应用 → 设置
   - 输入服务器地址：
     - 本地网络: `ws://192.168.x.x:8765`
     - Tailscale: `ws://100.x.x.x:8765`
     - 云服务器: `wss://your-domain.com:8765`

3. **自动注册**
   - 应用启动后自动发送注册消息
   - 设备 ID、类型、能力自动上报

### 其他设备注册

| 设备类型 | 注册方式 |
|----------|----------|
| Windows | `python register_device.py` |
| Linux | `python register_device.py` |
| macOS | `python register_device.py` |


---

## 🖥️ 可视化管理

### 服务端界面

| 界面 | 地址 |
|------|------|
| 控制面板 | http://服务器IP:8080 |
| **设备管理** | http://服务器IP:8080/devices |
| API 文档 | http://服务器IP:8080/docs |

### 设备管理功能

- 📱 可视化注册设备
- 📋 查看所有设备状态
- 🟢 实时在线/离线监控
- ⚡ 发送控制命令


---

## 🎮 交互系统

### 唤醒方式

| 方式 | 说明 |
|------|------|
| **边缘滑动** | 从屏幕右侧边缘向左滑动 |
| **点击灵动岛** | 点击顶部灵动岛展开 |
| **长按灵动岛** | 打开主应用 |

### 交互功能

| 功能 | 说明 |
|------|------|
| **语音输入** | 点击麦克风按钮说话 |
| **打字输入** | 在输入框输入命令 |
| **悬浮窗** | 随时随地唤起交互 |
| **灵动岛** | 极客风展开动画 |

### UI 风格

```
┌──────────────────────────────────────┐
│                                      │
│         ┌────────────────┐           │
│         │  UFO Galaxy ●  │  <- 灵动岛 │
│         └────────────────┘           │
│                                      │
│    ┌────────────────────────────┐   │
│    │                            │   │
│    │   用户: 今天天气怎么样      │   │
│    │                            │   │
│    │   系统: 今天晴天，温度...   │   │
│    │                            │   │
│    │   ┌──────────────────────┐ │   │
│    │   │ 说点什么...          │ │   │
│    │   └──────────────────────┘ │   │
│    │                            │   │
│    │   [🎤]          [发送]     │   │
│    │                            │   │
│    └────────────────────────────┘   │
│                                      │
└──────────────────────────────────────┘
```

### 与主仓库配合

1. 启动主仓库服务
   ```bash
   cd ufo-galaxy-realization-v2
   ./start.sh
   ```

2. 配置 Android 连接
   - 打开应用设置
   - 输入服务器地址
   - 连接成功后即可交互


---

## 🔄 V2 后端兼容性 (AIP v3.0)

### 单一 Android 客户端声明

本仓库 (`ufo-galaxy-android`) 是 **唯一的 Android 客户端实现**。所有 Android 能力上报、任务执行和诊断遥测均以本 APK 为准。

### 服务端端点要求

Android 客户端要求 V2 后端暴露以下 WebSocket 端点（AIP v3.0 协议）：

| 端点 | 用途 |
|------|------|
| `/ws/android` | Android 客户端统一接入端点 |
| `/ws/device/{device_id}` | 按设备 ID 点对点通信 |

### 端对端握手流程

Android 客户端与 V2 后端的完整交互序列：

```
Android 客户端                        V2 后端 (Galaxy)
      |                                     |
      |── WebSocket connect /ws/android ──▶ |
      |                                     |
      |── capability_report ───────────────▶|  ← AIP v3.0，包含 platform、
      |   {platform, device_id,             |    device_id、supported_actions、
      |    supported_actions, version}       |    version；供 Loop 3 推断能力差距
      |                                     |
      |◀─ registration_ack ─────────────── |
      |                                     |
      |── heartbeat (每 30 秒) ────────────▶|
      |◀─ heartbeat_ack ────────────────── |
      |                                     |
      |◀─ task_assign ─────────────────── |  ← 服务端下发任务
      |                                     |
      |── task_result ────────────────────▶|  ← 成功结果
      |   或                               |
      |── diagnostics_payload ────────────▶|  ← 失败时上报，供 Loop 1 & Loop 2
      |   {error_type, error_context,       |    分类重复失败、触发自修复或学习
      |    task_id, node_name}              |
      |                                     |
```

### 能力上报示例（capability_report）

```json
{
  "type": "capability_report",
  "version": "3.0",
  "platform": "android",
  "device_id": "<android_id>",
  "supported_actions": [
    "location", "camera", "sensor_data", "automation",
    "notification", "sms", "phone_call", "contacts",
    "calendar", "voice_input", "screen_capture", "app_control"
  ]
}
```

### 诊断载荷示例（diagnostics_payload）

任务失败时通过 `GalaxyWebSocketClient.sendDiagnostics()` 上报：

```json
{
  "type": "diagnostics_payload",
  "device_id": "<android_id>",
  "error_type": "permission_denied",
  "error_context": "Camera permission not granted on step 1",
  "task_id": "task-abc-123",
  "node_name": "android_agent_01"
}
```

服务端需处理的诊断字段：

| 字段 | 说明 |
|------|------|
| `error_type` | 错误分类（如 `network_timeout`、`permission_denied`、`execution_failed`） |
| `error_context` | 错误详情或堆栈摘要 |
| `task_id` | 失败任务的唯一标识 |
| `node_name` | 上报诊断的 Android 节点名称 |

---



本 Android 客户端配合 Galaxy 主系统使用：

### 主系统启动

```bash
# 克隆主系统
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2

# 启动系统
./galaxy.sh daemon

# 配置系统
open http://localhost:8080/config
```

### 三级优先模型

Galaxy 支持三级优先 LLM 模型：

| 优先级 | 模型 | 权重 |
|--------|------|------|
| 1 | GPT-4o | 60% |
| 2 | DeepSeek | 30% |
| 3 | Groq | 10% |

### 7×24 运行

Galaxy 支持 7×24 小时无间断运行：

- 自动启动
- 崩溃自动重启
- 健康检查
- 心跳监控


---

## 🚀 完整部署流程

### 1. 主系统安装 (一次配置)

```bash
# 克隆主系统
git clone https://github.com/DannyFish-11/ufo-galaxy-realization-v2.git
cd ufo-galaxy-realization-v2

# 一键安装 (自动配置开机自启动)
./install.sh    # Linux/macOS
install.bat     # Windows

# 完成！主系统现在 7×24 运行，开机自启动
```

### 2. Android 客户端安装

```bash
# 克隆 Android 仓库
git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
cd ufo-galaxy-android

# 配置服务器地址 (编辑 app/build.gradle)
# buildConfigField "String", "GALAXY_SERVER_URL", '"ws://你的服务器IP:8765"'

# 构建 APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开始使用

- **主系统**: 按 F12 键唤醒交互界面
- **Android**: 从屏幕右侧边缘滑动唤醒灵动岛


---

## 🧠 记忆系统

Galaxy 主系统拥有完整的记忆功能：

### 访问记忆中心

```
http://服务器IP:8080/memory
```

### 记忆类型

- **对话历史**: 自动保存所有对话
- **长期记忆**: 记住重要的事实、偏好、事件
- **用户偏好**: 学习并记住你的设置

### 使用示例

```
用户: 记住我喜欢喝咖啡
Galaxy: 好的，我已经记住了。

用户: 我喜欢什么？
Galaxy: 根据我的记忆，你喜欢喝咖啡。
```


---

## 🔀 AI 智能路由

Galaxy 主系统拥有 AI 驱动的智能路由：

### 访问路由中心

```
http://服务器IP:8080/router
```

### 工作原理

```
用户输入 → AI 分析任务 → 选择最佳模型 → 执行
```

### 优化目标

- **速度优先**: 选择最快的模型
- **成本优先**: 选择最便宜的模型
- **质量优先**: 选择最好的模型
- **平衡模式**: 综合考虑



---

## 📋 Android 能力就绪检查 (PR7)

### 概述

应用启动时和自主执行前会自动执行三项能力就绪检查：

| 检查项 | 说明 | 降级影响 |
|--------|------|----------|
| **本地模型就绪** | MobileVLM 和 SeeClick 模型文件存在且完整 | 本地推理不可用，降级为纯网络模式 |
| **无障碍服务** | HardwareKeyListener 无障碍服务已启用 | 无法捕获截图和执行 UI 操作 |
| **悬浮窗权限** | 已授予 SYSTEM_ALERT_WINDOW 权限 | 灵动岛悬浮窗不显示 |

### 降级模式 UI

任何检查未通过时，主界面顶部会显示橙色状态横幅（非阻断式），列出具体未就绪项。
所有功能仍可使用，横幅仅作提示。

### 手动验证步骤

**1. 本地模型检查**
- 在不含模型文件的设备上启动 → 横幅显示"本地模型未就绪"
- 复制/下载模型文件后重启 → 横幅消失

**2. 无障碍服务检查**
- 在 设置 → 无障碍 中禁用 UFO Galaxy → 横幅显示"无障碍服务未启用"
- 重新启用后回到前台 (onResume) → 横幅消失

**3. 悬浮窗权限检查**
- 在 设置 → 悬浮窗 中撤销权限 → 横幅显示"悬浮窗权限未授予"
- FloatingWindowService / EnhancedFloatingService 不崩溃，发送通知引导授权
- 点击通知跳转到悬浮窗权限设置页面
- 授权后回到前台 → 横幅消失

**4. Capability Report 元数据**
- 开启跨设备模式，在 WebSocket 消息中检查 `capability_report`
- 必须包含 `model_ready`、`accessibility_ready`、`overlay_ready` 字段

**5. 前台服务保活**
- 后台运行 GalaxyConnectionService → 系统杀死进程 → 服务通过 `START_STICKY` 自动重启
- BootReceiver 在设备重启后自动拉起 GalaxyConnectionService

### 能力报告示例

```json
{
  "goal_execution_enabled": true,
  "local_model_enabled": true,
  "cross_device_enabled": true,
  "parallel_execution_enabled": false,
  "device_role": "phone",
  "model_ready": true,
  "accessibility_ready": true,
  "overlay_ready": true
}
```
