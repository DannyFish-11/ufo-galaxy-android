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


---

## 📱 最新版本

| 组件 | 版本 | 状态 |
|------|------|------|
| 服务端 | v2.0.4 | ✅ 已发布 |
| Android 客户端 | v2.0.1 | ✅ 已发布 |
| 协议版本 | AIP v2.0 | ✅ 兼容 |

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

