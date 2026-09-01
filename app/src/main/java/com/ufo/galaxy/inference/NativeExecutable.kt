package com.ufo.galaxy.inference

import android.content.Context
import java.io.File

/**
 * 随 APK 发的可执行文件在磁盘上的位置。
 *
 * 为什么不能放 `filesDir`
 * -----------------------
 * 本仓此前把 `llama-server` 约定在 `filesDir/bin/llama-server`,由人手
 * `adb push` 供给。**那条路在本 App 上结构性走不通,和有没有把文件推进去无关。**
 *
 * Android 10(API 29)起,targetSdk ≥ 29 的应用不允许对自己可写数据目录里的文件
 * 调用 `execve()`。App 私有目录下的文件带 SELinux 标签 `app_data_file`,该标签
 * 不含执行权限 —— 这是 MAC(强制访问控制),不是 Unix 权限位,所以
 * `File.setExecutable(true)` 改不动它,`ProcessBuilder.start()` 会直接
 * `IOException: error=13, Permission denied`。本模块 targetSdk 是 35。
 *
 * 这个失效形态特别隐蔽:代码里"检查文件存在 → 标记可执行 → 校验摘要 → 启动"
 * 每一步都写得很认真,前三步也都会通过,只有最后 exec 的那一刻失败,并且被
 * `catch (e: Exception)` 归类成一个泛泛的 `Failed("launch failed: ...")` ——
 * 看上去像"这次没起来",而不是"这条路永远起不来"。
 *
 * 正确位置:`nativeLibraryDir`
 * ---------------------------
 * 系统认可的做法是把可执行文件当原生库发:放进
 * `app/src/main/jniLibs/<abi>/lib<name>.so`,安装时由 PackageManager 解压到
 * `applicationInfo.nativeLibraryDir`。该目录是只读的,SELinux 标签允许执行。
 *
 * 换到这条路顺带解决了三件原本各自要处理的事:
 *
 * | 原来 | 换到 jniLibs 之后 |
 * |---|---|
 * | 人手 `adb push` 到设备(每台设备一次) | 随 APK 一起装,零设备侧步骤 |
 * | `setExecutable(true)` | 安装时就是可执行,且改不动(只读) |
 * | 要靠 `MODEL_LLAMA_SERVER_SHA256` 钉死摘要 | APK v2/v3 签名已覆盖全部内容,装机时由系统校验 |
 *
 * 两个约束
 * --------
 * 1. **文件名必须是 `lib*.so`**。PackageManager 只解压符合该形态的条目;
 *    叫 `llama-server` 会被直接忽略。它是不是真的 ELF 共享库无所谓 ——
 *    可执行文件同样是 ELF,系统不检查这一点。
 * 2. **必须开 `useLegacyPackaging`**(等价于 `android:extractNativeLibs="true"`)。
 *    AGP 4.2 起默认不解压原生库:它们以对齐未压缩的形式留在 APK 内,
 *    `nativeLibraryDir` 指向 APK 里的一段而不是真实文件,exec 无从谈起。
 *    见 `app/build.gradle` 的 `packaging { jniLibs { useLegacyPackaging = true } }`。
 */
object NativeExecutable {

    /** `llama-server` 在 jniLibs 里的文件名。必须以 `lib` 开头、`.so` 结尾。 */
    const val LLAMA_SERVER_SO = "libllama-server.so"

    /**
     * 返回随 APK 发的可执行文件路径。
     *
     * 文件不存在时**照样返回路径**(而不是 null):调用方需要这个路径去组织
     * "未供给"的结构化上报,一个能说清"我在哪儿找过"的消息比 null 有用。
     */
    fun path(context: Context, soName: String): String =
        File(context.applicationInfo.nativeLibraryDir, soName).absolutePath

    /** [path] 的便捷形态:llama-server。 */
    fun llamaServerPath(context: Context): String = path(context, LLAMA_SERVER_SO)
}
