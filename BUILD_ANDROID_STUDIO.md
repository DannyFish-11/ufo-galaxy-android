# 在 Android Studio 里构建 UFO Galaxy(手机 + 手表)

本文手把手教你在 **Android Studio** 里把两端 App 构建出来:
- **手机**:`ufo-galaxy-android`(本仓)
- **手表**:`galaxy-wearos`(WearOS)

> 桌面/V2 大脑是另一套(Python),不在本文范围;本文只讲两个 Android 端的构建。

---

## 0. 一个关键前提:两仓必须摆成【兄弟目录】

手表工程 **依赖手机工程里的共享模块**(`shared-transport` / `shared-protocol`)。
`galaxy-wearos/settings.gradle.kts` 用相对路径 `../ufo-galaxy-android/...` 找它们。所以两仓
**必须放在同一个父目录下、名字保持原样**:

```
<你的工作区>/
├── ufo-galaxy-android/     ← 手机(含 shared-transport / shared-protocol)
└── galaxy-wearos/          ← 手表(引用上面两个共享模块)
```

克隆:
```bash
mkdir galaxy && cd galaxy
git clone <ufo-galaxy-android 的仓库地址> ufo-galaxy-android
git clone <galaxy-wearos 的仓库地址>      galaxy-wearos
```

> 目录名改了、或没放成兄弟目录 → 手表工程 sync 会报 `Project with path ':shared-transport'
> could not be found`。这是手表构建最常见的坑。

---

## 1. 装工具(一次性)

| 需要 | 版本 | 说明 |
|---|---|---|
| **JDK** | **17** | 两端都要 JDK 17(不是 11、不是 21)。AS 里 `Settings → Build Tools → Gradle → Gradle JDK` 选 17。 |
| **Android Studio** | 较新稳定版即可 | Ladybug/Koala 或更新。 |
| **Android SDK** | **compileSdk 35** | SDK Manager 里装 **Android 15 (API 35)** 的 SDK Platform。 |
| **NDK + CMake** | 见 §4 | **只有要端侧原生推理时才需要**;纯远程模式不用装。 |

两端各自的构建工具版本(AS 会按 wrapper 自动下载对应 Gradle,无需手动装):
- 手机:Gradle **8.4**、AGP **8.2.0**、Kotlin **1.9.21**、compileSdk **35**、minSdk **26**
- 手表:Gradle **8.8**、compileSdk **35**、minSdk **30**

---

## 2. 构建手机 App(ufo-galaxy-android)

1. AS → `File → Open` → 选 **`ufo-galaxy-android`** 目录 → 等 Gradle sync。
2. sync 绿了以后,出 Debug 包:
   - 菜单:`Build → Build Bundle(s)/APK(s) → Build APK(s)`,或
   - 终端:`./gradlew :app:assembleDebug`
3. 产物:`app/build/outputs/apk/debug/app-debug.apk`。用数据线连真机(开发者选项+USB 调试)→
   AS 顶部选设备 → ▶ Run 直接装。

命令行等价(CI 也是这么编的):
```bash
cd ufo-galaxy-android
./gradlew :app:compileDebugKotlin   # 只编译(快)
./gradlew :app:assembleDebug        # 出 APK
```

---

## 3. 构建手表 App(galaxy-wearos)

前提:§0 的兄弟目录已就位(否则找不到共享模块)。

1. AS → `File → Open` → 选 **`galaxy-wearos`** 目录 → 等 sync(它会把兄弟仓的
   `shared-transport` / `shared-protocol` 一并纳入)。
2. 出 Debug 包:
   ```bash
   cd galaxy-wearos
   ./gradlew :app:assembleDebug
   ```
   产物:`galaxy-wearos/app/build/outputs/apk/debug/app-debug.apk`。
3. 装到手表:真机用 `adb`(手表连 WiFi 调试或走手机代理),或先在 Wear OS 模拟器上跑。

---

## 4. 端侧原生推理(手机本地跑模型)—— 可选、进阶

**代码侧已经就绪**:`NativeInferenceLoader` 用 `System.loadLibrary("llama"/"ncnn")` +
`external fun` 动态链接,`.so` 缺失时**捕获 `UnsatisfiedLinkError` 优雅降级**(本地推理关闭、
自动退回远程 V2/Ollama,不崩)。`app/build.gradle` 里 `ndk.abiFilters` 与 `jniLibs` 已配好,
**不需要改 gradle**。你要做的只有两件事:放 **.so** + 放 **模型**。

### 4.1 放 native 库(.so)
把预编译好的 `.so` 放进(按 ABI 分目录):
```
app/src/main/jniLibs/
├── arm64-v8a/    libllama.so   libncnn.so     ← 绝大多数真机(64 位 ARM)
├── armeabi-v7a/  libllama.so   libncnn.so     ← 老 32 位设备(可选)
└── x86_64/       libllama.so   libncnn.so     ← 模拟器(可选)
```
- **libllama.so**:按 [llama.cpp `docs/android.md`] 用 **NDK + CMake** 从源码交叉编译
  (`-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a`)。
- **libncnn.so**:从 ncnn Releases 的 `ncnn-YYYYMMDD-android-vulkan.zip` 解压取对应 ABI 的即可。

放好后 `loadLibrary` 会自动加载,无需改任何 Kotlin/Gradle。详见
`app/src/main/jniLibs/README.md`。

> 为什么不走 Gradle 依赖:llama.cpp / ncnn 是 C++/CMake 工程,JitPack 无法打成 AAR
> (`com.github.ggerganov:llama.cpp` 永远解析不到),硬加会让 clone 后的构建卡死在依赖解析。
> 所以走 `jniLibs` 直接放 `.so` 是**唯一稳的**接入方式。

### 4.2 放模型
把 GGUF/模型文件放进 App 私有目录或 `assets`(几百 MB,别提交进 git);`ModelAssetManager` /
`ModelManifest` 负责定位加载。缺模型时同样走远程回退。

### 4.3 验证
装 APK 后看日志:`NativeInferenceLoader` 会打印加载成功/失败;失败即静默退回远程,不影响可用性。

---

## 5. 连上 V2(登录/连接)

- App 内设置里填 **V2 网关地址**(如 `ws://<你的机器局域网IP>:9000` 或 Tailscale IP);
  真机别用 `localhost`(那是设备自己)。
- 手表:进 **设置 → 设备登录**(设备流扫码授权),或用预设地址 + token。
- 手机/手表要能连到跑 V2 的机器(同局域网 / Tailscale)。

---

## 6. 常见坑速查

| 现象 | 原因 / 解法 |
|---|---|
| 手表 sync 报 `':shared-transport' could not be found` | 两仓没摆成兄弟目录 / 目录名改了 → 见 §0。 |
| `Inconsistent JVM-target: Java(1.8) vs Kotlin(17)` | Gradle JDK 没选 17 → §1 设成 17。 |
| `Installed Build Tools ... compileSdk 35` 警告 | 可忽略(功能不受影响);想消除就在 `gradle.properties` 加 `android.suppressUnsupportedCompileSdk=35`。 |
| `UnsatisfiedLinkError: libllama.so` | 没放 `.so`(§4.1)→ App 会自动退回远程,不是崩溃;要端侧推理才需补。 |
| `mergeDebugJavaResource` 报 `META-INF/... 多份` | 依赖里重复元数据 → 已在 gradle 配 `packaging.resources.excludes` 处理(手表侧已修)。 |
| 真机连不上 V2 | 用了 `localhost` / 不同网段 → 填局域网或 Tailscale IP。 |

---

## 7. 一句话总结

- **纯远程模式**(不做端侧推理):装 JDK17 + SDK35 → 两仓摆兄弟目录 → 各自 `:app:assembleDebug`
  → 出两个 APK,连上 V2 就能用。
- **端侧推理**:另加 NDK/CMake 编 `libllama.so` 放进 `jniLibs` + 放模型,其余不变。
