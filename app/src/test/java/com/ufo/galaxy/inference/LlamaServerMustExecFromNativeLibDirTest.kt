package com.ufo.galaxy.inference

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地推理的可执行文件必须从 `nativeLibraryDir` 起,不能从 `filesDir` 起。
 *
 * 这条守的是一个**结构性**缺陷,不是一次配置疏忽。
 *
 * 事故形态
 * --------
 * 改前 [LlamaServerController] 的 `binaryPath` 是 `filesDir/bin/llama-server`,
 * 供给方式写在 `jniLibs/README.md` 里:`adb push` 到该路径。整条链路在读码时看不出
 * 问题 —— 存在性检查、`setExecutable(true)`、SHA-256 校验、结构化 `NotProvisioned`
 * 上报,每一步都写得很认真。
 *
 * 但 Android 10(API 29)起,targetSdk ≥ 29 的应用不允许对自己可写数据目录里的文件
 * 调 `execve()`:那些文件带 SELinux 标签 `app_data_file`,不含执行权限。这是 MAC,
 * 不是 Unix 权限位,`setExecutable(true)` 改不动。本模块 targetSdk 是 35。
 *
 * 于是前三步全部通过,只有最后 `ProcessBuilder.start()` 抛
 * `IOException: error=13, Permission denied`,被 `catch (e: Exception)` 归成一个泛泛的
 * `Failed("launch failed: ...")`。日志上看像"这次没起来",实际是"这条路永远起不来"。
 *
 * 为什么必须是源码级检查
 * ----------------------
 * `nativeLibraryDir` 只有在真机/仪器测试里才有值,JVM 单测拿不到 `Context`;
 * 而本仓一个仪器测试都没有。真机上跑一次就能发现的事,这里跑不了。
 *
 * 能在 JVM 里守住的,是**接线本身**:构造 [LlamaServerController] 的那一处必须走
 * [NativeExecutable],而不是自己拼一个 `filesDir` 路径。这不是"测实现细节" ——
 * 在没有真机的前提下,接线就是唯一还能被检查的东西。
 */
class LlamaServerMustExecFromNativeLibDirTest {

    private fun mainSourceRoot(): File {
        // 单测工作目录在不同调用方式下是模块目录或仓库根，两种都试。
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java，试过：${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过——找不到就当通过的守卫等于没有守卫。"
            )
    }

    private fun source(relative: String): String {
        val f = File(mainSourceRoot(), relative)
        assertTrue("源文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    @Test
    fun `控制器的 binaryPath 由 NativeExecutable 给出`() {
        val src = source("com/ufo/galaxy/UFOGalaxyApplication.kt")
        assertTrue(
            "LlamaServerController 的 binaryPath 没走 NativeExecutable —— " +
                "只要它落在可写私有目录下，execve 必然被 SELinux 拒绝",
            src.contains("NativeExecutable.llamaServerPath(this)"),
        )
    }

    @Test
    fun `没有任何地方再把可执行文件指向 filesDir`() {
        // 正面断言只能证明"新路接上了"，证明不了"旧路拆干净了"。
        // 两条路同时存在时，先跑到的那条决定行为。
        val offenders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                // 只认代码形态：带引号的字符串字面量，或直接从 filesDir 拼路径。
                // KDoc 里用反引号提到旧路径是在解释事故，不该被判成复发。
                Regex("\"[^\"]*bin/llama-server").containsMatchIn(t) ||
                    Regex("""File\(\s*filesDir\s*,""").containsMatchIn(t)
            }
            .map { it.path }
            .toList()
        assertTrue("仍有源文件把可执行文件指向 filesDir：$offenders", offenders.isEmpty())
    }

    @Test
    fun `so 文件名符合 PackageManager 的解压条件`() {
        // PackageManager 只解压 lib*.so 形态的条目。叫别的名字会被静默忽略，
        // 表现为"文件明明打进 APK 了，装机后 nativeLibraryDir 里却没有"。
        val name = NativeExecutable.LLAMA_SERVER_SO
        assertTrue("$name 必须以 lib 开头", name.startsWith("lib"))
        assertTrue("$name 必须以 .so 结尾", name.endsWith(".so"))
    }

    @Test
    fun `构建打开了 useLegacyPackaging`() {
        // 关着的话原生库不落地成真实文件，nativeLibraryDir 指向 APK 内的偏移，
        // exec 无从谈起。这个开关和上面那条改动是一对，缺一不可。
        val gradle = listOf(File("build.gradle"), File("app/build.gradle"), File("../app/build.gradle"))
            .firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 app/build.gradle")
        val text = gradle.readText()
        assertTrue(
            "app/build.gradle 没有 useLegacyPackaging = true —— " +
                "原生库不会被解压到磁盘，libllama-server.so 无法被 exec",
            Regex("""useLegacyPackaging\s*=\s*true""").containsMatchIn(text),
        )
        assertFalse(
            "useLegacyPackaging 被显式设成了 false",
            Regex("""useLegacyPackaging\s*=\s*false""").containsMatchIn(text),
        )
    }
}
