package com.ufo.galaxy.contract

import com.ufo.galaxy.runtime.LocalIntelligenceCapabilityStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地推理能不能用」这件事，判据只有一个，而且**不是** `.so` 探测。
 *
 * 起因是一处真实的报反：`localLlmReady()` 的判据曾是
 * `modelReady && (isLlamaCppAvailable() || isNcnnAvailable())`。
 * 但规划与定位都走 llama.cpp **服务进程**的 HTTP 口，进程内一个原生库都不需要，
 * 于是这个字段在两个方向上同时报错 ——
 *
 *  · llama-server 在跑、权重齐、warmup 过（本地闭环真的能跑）→ 报 **false**，
 *    因为 APK 里没有 `libllama.so`；
 *  · APK 里恰好带了个 `libllama.so`、llama-server 根本没起 → 报 **true**。
 *
 * V2 侧拿 `local_llm_ready` / `active_runtime_type` 决定派发，报反会把任务派到
 * 跑不动的设备、或把跑得动的设备排除在外。这类缺陷不会让任何行为测试变红：
 * 两个函数各自都"正确"，错的是谁被当成了权威。
 *
 * 同一个根因还长出过第二个形态：规划器后端曾按
 * `NativeInferenceLoader.isLlamaCppAvailable()` 优先选一个 JNI 实现，
 * 那个实现结构上不可能看见屏幕（无 mmproj、`nativeCompletion` 只吃文本、
 * 截图被当作 `<image>BASE64</image>` 字面文本拼进 prompt），而且官方 `libllama.so`
 * 只导出 C API、没有本仓假定的 JNI 符号 —— 于是把官方库放进 jniLibs 会让
 * **本来能工作的 HTTP 路径连试都不会试**。该路径已删除。
 *
 * 所以这里同时钉住语义与接线：语义用真值表，接线直接读源码。
 */
class LocalInferenceCapabilityIsNotAnSoProbeTest {

    // ── 语义：唯一判据的真值表 ────────────────────────────────────────────

    @Test
    fun `only running and degraded count as usable local inference`() {
        val usable = LocalIntelligenceCapabilityStatus.entries.filter { it.isLocalInferenceUsable }
        assertEquals(
            "可用集合必须恰好是 ACTIVE 与 DEGRADED —— " +
                "RECOVERING/UNAVAILABLE 是暂时失能，把它们算作可用会把任务派进正在重启的运行时",
            setOf(
                LocalIntelligenceCapabilityStatus.ACTIVE,
                LocalIntelligenceCapabilityStatus.DEGRADED,
            ),
            usable.toSet(),
        )
    }

    @Test
    fun `active runtime type follows usability, and never claims a retired stack`() {
        LocalIntelligenceCapabilityStatus.entries.forEach { status ->
            val expected = if (status.isLocalInferenceUsable) {
                LocalIntelligenceCapabilityStatus.RUNTIME_TYPE_LLAMA_CPP
            } else {
                LocalIntelligenceCapabilityStatus.RUNTIME_TYPE_CENTER
            }
            assertEquals("$status 的 active_runtime_type 与可用性不一致", expected, status.activeRuntimeType)
        }
        val produced = LocalIntelligenceCapabilityStatus.entries.map { it.activeRuntimeType }.toSet()
        assertFalse(
            "NCNN 栈已整体退役（官方仓从不存在 NCNN 端口，文件从未成功供给）—— 报它等于报一个不存在的能力",
            produced.contains("NCNN") || produced.contains("HYBRID"),
        )
    }

    // ── 接线：谁被当成了权威 ──────────────────────────────────────────────

    @Test
    fun `localLlmReady asks the runtime lifecycle, not the so probe`() {
        val src = source("com/ufo/galaxy/service/GalaxyConnectionService.kt")
        val body = src.substringAfter("private fun localLlmReady()").substringBefore("\n    private fun ")
        assertTrue(
            "localLlmReady 没有问推理运行时的实际状态 —— 它是 V2 派发的判据，不能靠猜",
            body.contains("isLocalInferenceUsable"),
        )
        assertFalse(
            "localLlmReady 又回去问 .so 探测了 —— 那只是『APK 里带没带库』，与本地推理能否工作无关",
            body.contains("isLlamaCppAvailable") || body.contains("isNcnnAvailable"),
        )
    }

    @Test
    fun `active runtime type is not derived from the so probe in either snapshot builder`() {
        listOf(
            "com/ufo/galaxy/service/GalaxyConnectionService.kt",
            "com/ufo/galaxy/service/handler/StateHandler.kt",
        ).forEach { relative ->
            val src = source(relative)
            val decl = src.substringAfter("val activeRuntimeType").substringBefore("\n\n")
            assertTrue(
                "$relative 的 active_runtime_type 没有走唯一判据",
                decl.contains("LocalIntelligenceCapabilityStatus"),
            )
            assertFalse(
                "$relative 的 active_runtime_type 又回到 .so 标志推导了",
                decl.contains("llamaCppAvailable") || decl.contains("ncnnAvailable"),
            )
        }
    }

    @Test
    fun `no planner backend is selected by probing for a native library`() {
        val src = source("com/ufo/galaxy/UFOGalaxyApplication.kt")
        val block = src.substringAfter("private fun initInferenceServices()")
            .substringBefore("private fun initRuntimeController()")
        assertTrue("规划器后端不再是 llama.cpp 服务的 HTTP 客户端了", block.contains("VlmPlanner("))
        // 注释里**要**留着旧 API 的名字（那段解释了这条路为什么被删），所以先剥注释再查。
        assertFalse(
            "规划器后端又按 .so 是否存在来选了 —— 官方 libllama.so 没有本仓假定的 JNI 符号，" +
                "命中它只会让能工作的 HTTP 路径连试都不试",
            withoutComments(block).contains("isLlamaCppAvailable"),
        )
    }

    @Test
    fun `the blind jni planner stays deleted`() {
        val root = locateMainSourceRoot()
        assertFalse(
            "LlamaCppPlannerService 回来了 —— 它无 mmproj、nativeCompletion 只吃文本，" +
                "截图会被当作字面 base64 文本塞进 prompt，结构上不可能看见屏幕",
            File(root, "com/ufo/galaxy/planner/LlamaCppPlannerService.kt").exists(),
        )
        val offenders = File(root, "com/ufo/galaxy").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("<image>\$") }
            .map { it.name }
            .toList()
        assertTrue(
            "有源文件把 base64 截图当字面文本拼进 prompt（`<image>\$…</image>`）—— " +
                "llama.cpp 的多模态不是这样工作的，这只会顶爆上下文且一个像素都传不进去：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 剥掉 Kotlin 注释后的源码。
     *
     * 这些守卫查的是"这行代码还在不在"，而解释性注释里往往**必须**提到被删掉的旧
     * API 名字（否则后人无从知道那条路为什么被删）。不剥注释的话，一段正确的代码会
     * 因为旁边写了句解释就被判成违规 —— 守卫必须能分清代码和说明。
     *
     * 已知边界：字符串字面量里的 `//`（如 URL）之后的内容也会被剥掉。对这几条守卫
     * 无影响（它们找的都是标识符，不是 URL），但换用途前要留意。
     */
    private fun withoutComments(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    private fun source(relative: String): String {
        val f = File(locateMainSourceRoot(), relative)
        assertTrue("源文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun locateMainSourceRoot(): File {
        // 单测的工作目录在不同调用方式下不一样(模块目录 / 仓库根),两种都试。
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过:${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }
}
