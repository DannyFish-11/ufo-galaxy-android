# Native Libraries (JNI)

> **本目录当前没有任何消费方 —— 往这里放 `.so` 不会让本地推理跑起来。**
>
> 本地端侧推理由一个**独立的 `llama-server` 进程**承担(llama.cpp 官方服务端,
> 带 `--mmproj` 多模态),不是进程内 JNI。要开本地闭环请看下面
> 「本地推理的正确供给路径」一节,而不是这里。

## 为什么这里是空的

历史上本仓有两条进程内 JNI 推理路径,都已删除:

| 已删路径 | 删除原因 |
|---|---|
| `planner/LlamaCppPlannerService.kt`(`libllama.so`) | 结构上不可能看见屏幕:`nativeLoadModel(path, threads)` 没有 mmproj 入参、`nativeCompletion(handle, prompt: String, …)` 只吃文本,于是截图被当作 `<image>BASE64</image>` **字面文本**拼进 prompt —— 几万个 token 顶爆上下文却一个像素都传不进去。而且它的选择条件只看 `System.loadLibrary("llama")` 成没成:把官方 `libllama.so`(只导出 C API,没有本仓假定的 JNI 胶水符号)放进本目录就会命中它,`nativeLoadModel` 抛 `UnsatisfiedLinkError` → 规划器永远加载不上,**本来能工作的 HTTP 路径连试都不会试**。 |
| `grounding/NcnnGroundingService.kt`(`libncnn.so`) | SeeClick 本体是 Qwen-VL 9.6B,官方仓从不存在 NCNN 端口,`seeclick.ncnn.param/bin` 从未成功供给 —— 该定位后端在生产上从未真正工作过。NCNN 栈已整体退役。 |

**放官方 `libllama.so` 进来不等于有了 JNI 绑定。** 官方库导出的是 C API,
不是 `Java_com_ufo_galaxy_…` 形式的 JNI 符号;要走进程内推理必须自己写并交叉编译
一层 JNI 胶水,还得把 mmproj、图像 patch、image-token 插入全部重做一遍 ——
而 llama.cpp 自己的 `mtmd` 已经把这些做对了。所以本仓的选择是:**不自建 JNI 视觉胶水。**

## 这两个 `.so` 探测为什么还留着

`runtime/NativeInferenceLoader.kt` 仍会尝试 load 它们,只为如实上报
`llama_cpp_available` / `ncnn_available` 两个协议字段 ——
**那只是"APK 里带没带 .so"这个事实本身,不是能力**。
判断本地推理能不能用,唯一判据是
`runtime/LocalIntelligenceCapabilityStatus.isLocalInferenceUsable`
(由 `LocalInferenceRuntimeManager` 的实际生命周期状态推导)。

> 另:**不要**通过 Gradle / JitPack 引入这两个库。`com.github.ggerganov:llama.cpp`
> 与 `com.github.nihui:ncnn-android-vulkan` 都是 C++/CMake 工程,JitPack 无法把它们
> 构建成 Android AAR(前者返回 not-found、后者返回 401),写成 `implementation`
> 会让全新克隆的 gradle 构建卡死在依赖解析阶段。

## 本地推理的正确供给路径

需要两样东西,都放在 app 私有目录下,**都不经过本目录**:

### 1. `llama-server` 可执行文件 → `files/bin/llama-server`

arm64 静态构建,从 llama.cpp 官方 release 取,或按 `docs/android.md` 用 NDK 交叉编译:

```bash
git clone https://github.com/ggml-org/llama.cpp.git && cd llama.cpp
cmake -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build build-android --target llama-server -j"$(nproc)"

adb push build-android/bin/llama-server /data/local/tmp/
adb shell run-as com.ufo.galaxy mkdir -p files/bin
adb shell run-as com.ufo.galaxy cp /data/local/tmp/llama-server files/bin/llama-server
```

**校验和必须钉死**:把该文件的 SHA-256 写进 `gradle.properties` 的
`MODEL_LLAMA_SERVER_SHA256`(见 `inference/LlamaServerController.kt`)。未钉死时
控制器拒绝执行来路不明的可执行文件,并结构化上报 `NotProvisioned`。

```bash
sha256sum build-android/bin/llama-server
```

由 `inference/LlamaServerController.kt` 幂等拉起,命令形如:

```
files/bin/llama-server -m <MAI-UI-2B.Q4_K_M.gguf> --mmproj <MAI-UI-2B.mmproj-f16.gguf> \
  --host 127.0.0.1 --port 8080 -c 4096 --no-webui
```

### 2. 模型权重 → 由 `ModelAssetManager` 下载与校验

`MAI-UI-2B.Q4_K_M.gguf` 与 `MAI-UI-2B.mmproj-f16.gguf`,SHA-256 由
`gradle.properties` 的 `MODEL_VLM_SHA256` / `MODEL_VLM_MMPROJ_SHA256` 钉死。
**mmproj 缺失时控制器坚决不起纯文本服务** —— 那正是旧 MobileVLM「从未看见过屏幕」
的静默故障形态。

## 缺任何一样时的行为

`LlamaServerController.ensureRunning()` 返回结构化结果
(`NotProvisioned` / `ModelsMissing` / `Failed`),不抛异常、不静默假装成功;
本地推理关闭,推理经 V2 网关由中心承担。
