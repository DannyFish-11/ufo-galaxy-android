# jniLibs — 随 APK 发的原生产物

本目录放两类东西，都由 PackageManager 在安装时解压到只读的
`applicationInfo.nativeLibraryDir`：

1. 真正的原生共享库（给 `System.loadLibrary` 用）；
2. **本 App 自己 exec 的可执行文件** —— 目前只有一个：`llama-server`。

> 本目录当前在仓内是空的：`libllama-server.so` 是二进制，不入库、也无法在 CI 里
> 构建。缺它时本地推理关闭、推理经 V2 网关由中心承担，App 其它功能不受影响。

---

## 为什么可执行文件必须放这里（而不是 `filesDir`）

这份文档此前写的是相反的结论 —— 「往这里放 `.so` 不会让本地推理跑起来」，并把
`llama-server` 的供给路径定为 `adb push` 到 `files/bin/llama-server`。

**那条路在本模块上结构性走不通，和有没有把文件推进去无关。**

Android 10（API 29）起，`targetSdk ≥ 29` 的应用不允许对自己可写数据目录里的文件
调用 `execve()`。App 私有目录下的文件带 SELinux 标签 `app_data_file`，该标签不含
执行权限。这是 MAC（强制访问控制），不是 Unix 权限位，所以：

- `chmod +x` / `File.setExecutable(true)` 改不动它；
- `ProcessBuilder.start()` 会抛 `IOException: error=13, Permission denied`。

本模块 `targetSdk` 是 35。

这个失效形态很隐蔽：`LlamaServerController` 里「检查存在 → 标记可执行 → 校验
SHA-256 → 启动」四步，前三步都会通过，只有最后 exec 的那一刻失败，并被
`catch (e: Exception)` 归类成一个泛泛的 `Failed("launch failed: …")`。日志上看像
「这次没起来」，实际是「这条路永远起不来」。

`nativeLibraryDir` 是系统认可的位置：只读，且 SELinux 标签允许执行。

参考：
- [Behavior changes: apps targeting API 29+](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Execution blocked for app data files when targeting API ≥ 29](https://github.com/JeromeDeBretagne/erlanglauncher/issues/2)

## 换过来顺带解决的三件事

| 改前 | 改后 |
|---|---|
| 每台设备人手 `adb push` 一次 | 随 APK 安装，设备侧零步骤 |
| 启动前 `setExecutable(true)` | 安装时即可执行，且只读改不动 |
| 靠 `MODEL_LLAMA_SERVER_SHA256` 钉死摘要才敢 exec | APK v2/v3 签名已覆盖全部内容，装机时由系统校验 |

第三条值得单说：原先的顾虑是「本 App 亲自 exec、跑在自己 UID 下的可执行文件不该
零校验就启动」。这个顾虑是对的，但 `adb push` + 手工钉摘要是个很脆的答案 ——
摘要留空（本仓当前就是空）时等于没校验。放进 APK 之后，完整性由平台的安装校验
承担，比自己再算一遍 SHA-256 更强，也不需要任何人记得去填一个 gradle 属性。

`LlamaServerController` 仍保留 `expectedSha256`，因为供给渠道未必只有这一条；
但在 jniLibs 供给下它不再是必需品。

## 两个硬约束

1. **文件名必须是 `lib*.so`。** PackageManager 只解压符合该形态的条目；叫
   `llama-server` 会被静默忽略 —— 表现为「文件明明打进 APK 了，装机后
   `nativeLibraryDir` 里却没有」。它是不是真的共享库无所谓：可执行文件同样是
   ELF，系统不检查这一点。
2. **必须开 `useLegacyPackaging`。** AGP 4.2 起默认为 `false`：原生库以对齐未压缩
   的形式留在 APK 内、不再解压，`nativeLibraryDir` 指向 APK 里的一段偏移而不是真实
   文件路径。`dlopen` 走特殊路径所以照常，但 `execve` 无从谈起。本仓已在
   `app/build.gradle` 的 `packaging { jniLibs { useLegacyPackaging = true } }` 打开。

以上两条各配了一条单测，见
`app/src/test/java/com/ufo/galaxy/inference/LlamaServerMustExecFromNativeLibDirTest.kt`。

---

## 供给 `llama-server`

arm64 静态构建，从 llama.cpp 官方 release 取，或用 NDK 交叉编译：

```bash
git clone https://github.com/ggml-org/llama.cpp.git && cd llama.cpp
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build build-android --target llama-server -j"$(nproc)"
```

放到本目录，**改名成 `lib` 前缀 `.so` 后缀**：

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp build-android/bin/llama-server \
   app/src/main/jniLibs/arm64-v8a/libllama-server.so
```

然后正常 `./gradlew assembleDebug`。`abiFilters` 里的其它 ABI 没放这个文件时，
在那些设备上 `ensureRunning()` 返回 `NotProvisioned`，本地推理关闭 —— 这是预期的
降级，不是故障。

`LlamaServerController` 幂等拉起，命令形如：

```
<nativeLibraryDir>/libllama-server.so -m <MAI-UI-2B.Q4_K_M.gguf> \
  --mmproj <MAI-UI-2B.mmproj-f16.gguf> \
  --host 127.0.0.1 --port 8080 -c 4096 --no-webui
```

## 模型权重

`MAI-UI-2B.Q4_K_M.gguf` 与 `MAI-UI-2B.mmproj-f16.gguf` 走 `ModelAssetManager`
下载与校验，SHA-256 由 `gradle.properties` 的 `galaxy.model.vlm.sha256` /
`galaxy.model.vlmMmproj.sha256` 钉死。这两个是**运行时下载的数据**，不随 APK 发，
所以摘要钉死对它们依然必要 —— 留空即 TOFU（首次下到什么就信什么）。

**mmproj 缺失时控制器坚决不起纯文本服务** —— 那正是旧 MobileVLM「从未看见过屏幕」
的静默故障形态。

## 已删除的两条进程内 JNI 路径

| 已删路径 | 删除原因 |
|---|---|
| `planner/LlamaCppPlannerService.kt`（`libllama.so`） | 结构上不可能看见屏幕：`nativeLoadModel(path, threads)` 没有 mmproj 入参、`nativeCompletion(handle, prompt: String, …)` 只吃文本，于是截图被当作 `<image>BASE64</image>` **字面文本**拼进 prompt —— 几万个 token 顶爆上下文却一个像素都传不进去。选择条件还只看 `System.loadLibrary("llama")` 成没成：把官方 `libllama.so`（只导出 C API，没有本仓假定的 JNI 胶水符号）放进来就会命中它，`nativeLoadModel` 抛 `UnsatisfiedLinkError` → 规划器永远加载不上，而**本来能工作的 HTTP 路径连试都不会试**。 |
| `grounding/NcnnGroundingService.kt`（`libncnn.so`） | SeeClick 本体是 Qwen-VL 9.6B，官方仓从不存在 NCNN 端口，`seeclick.ncnn.param/bin` 从未成功供给 —— 该定位后端在生产上从未真正工作过。 |

多模态由 llama.cpp 自己的 `mtmd` 承担（`LlamaServerController` 以 `--mmproj` 拉起），
不自建 JNI 视觉胶水。

`runtime/NativeInferenceLoader.kt` 仍会尝试 load 这两个库，只为如实上报
`llama_cpp_available` / `ncnn_available` 两个协议字段 —— **那只是「APK 里带没带 .so」
这个事实本身，不是能力**。判断本地推理能不能用，唯一判据是
`runtime/LocalIntelligenceCapabilityStatus.isLocalInferenceUsable`。

> 另：**不要**通过 Gradle / JitPack 引入 llama.cpp 或 ncnn。两者都是 C++/CMake 工程，
> JitPack 无法把它们构建成 AAR（前者 not-found、后者 401），写成 `implementation`
> 会让全新克隆的 gradle 构建卡死在依赖解析阶段。
