package com.ufo.galaxy.contract

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试不许触网 —— 特指 `ModelDownloader` 这条最贵的路。
 *
 * 为什么需要这道门
 * ----------------
 * `NoNetworkModelDownloader.kt` 里已经写清了教训（CI 实测）：默认的
 * `DefaultHttpFactory` 会向 HuggingFace 发真实请求，`connectTimeout = 30_000`。
 * "空模型目录 + 真下载器"的夹具一旦跑进 `LoopController.ensureModels`，
 * 每次调用就白等 30 秒。
 *
 * 但**光有 helper 不够**：写下那段教训之后，仍有 41 处测试用了不带
 * `httpFactory` 的 `ModelDownloader(dir)` 构造，其中 `LoopControllerTest` 与
 * `LocalLoopCorrectnessTest` 甚至已经 import 了 helper、却还留着真构造——
 * 转换转了一半。CI 的实测代价：`MainlineAbnormalPathMatrixTest` 与
 * `RuntimeControllerTest` 里 12 条用例各等 17~33 秒，合计约 4.6 分钟。
 *
 * 约定靠人记会漂，所以固化成门。
 *
 * 判据
 * ----
 * 测试源码里出现 `ModelDownloader(` 时，同一处必须带 `httpFactory`（自己注入
 * 假工厂），否则就该改用 `noNetworkModelDownloader(dir)`。
 *
 * 刻意的边界
 * ----------
 * * 只扫 `src/test`。生产代码本来就该用真工厂。
 * * `NoNetworkModelDownloader.kt` 自身豁免 —— 它就是那个假工厂的定义处。
 * * 找不到源码根目录时**判失败**而不是跳过。"找不到就当通过"的守卫在目录结构
 *   变化那天会静默失效，而那正是这份测试要防的病本身。
 */
class NoNetworkInUnitTestsContractTest {

    @Test
    fun `no unit test constructs a network-capable ModelDownloader`() {
        val root = locateTestSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in EXEMPT_FILES }
            .forEach { file ->
                scanned++
                file.readText().lineSequence().forEachIndexed { idx, line ->
                    if (CONSTRUCTOR.containsMatchIn(line) && !line.contains("httpFactory")) {
                        offenders += "${file.name}:${idx + 1}   $line".trim()
                    }
                }
            }

        assertTrue("没扫到任何测试源码,这道门等于没在查(root=$root)", scanned > 0)
        assertTrue(
            "以下测试构造了会真的联网的 ModelDownloader。\n" +
                "改用 noNetworkModelDownloader(dir),或显式传 httpFactory 注入假工厂。\n" +
                "理由见 NoNetworkModelDownloader.kt 的注释:真下载器的 connectTimeout 是 30 秒,\n" +
                "每一次 ensureModels 都白等这么久。\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the guard can actually see the pattern it forbids`() {
        // 自证:上面那条若因为正则写错而永远匹配不到,就会恒真通过。
        // 这里用一行已知的违规文本喂给同一个正则,确认它认得出来。
        val violating = "            modelDownloader = ModelDownloader(modelsDir)"
        assertTrue(
            "正则认不出违规写法,上面那条守卫是恒真的",
            CONSTRUCTOR.containsMatchIn(violating) && !violating.contains("httpFactory")
        )

        // 反向:合规写法不该被误判
        val ok = "        ModelDownloader(modelsDir, httpFactory = factory)"
        assertFalse(
            "合规写法被误判为违规",
            CONSTRUCTOR.containsMatchIn(ok) && !ok.contains("httpFactory")
        )

        // helper 调用不该被当成构造
        val helper = "        modelDownloader = noNetworkModelDownloader(modelsDir)"
        assertFalse("helper 调用被误判为真构造", CONSTRUCTOR.containsMatchIn(helper))
    }

    private fun locateTestSourceRoot(): File {
        // 单测的工作目录在不同调用方式下不一样(模块目录 / 仓库根),两种都试。
        val candidates = listOf(
            File("src/test/java"),
            File("app/src/test/java"),
            File("../app/src/test/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/test/java。试过:${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    companion object {
        /**
         * 豁免名单。两者都是**规则的定义处**，不是违规者：
         *
         * * `NoNetworkModelDownloader.kt` —— 假工厂本身，必须调真构造并传 httpFactory。
         * * 本文件 —— 自证用例里要放一行"已知违规"的样例串，KDoc 里也要把被禁的写法
         *   写出来。第一版忘了豁免自己，本地复刻判据时当场把自己抓了出来
         *   （2 处：第 73 行的样例串 + KDoc 里的模式说明）。
         */
        private val EXEMPT_FILES = setOf(
            "NoNetworkModelDownloader.kt",
            "NoNetworkInUnitTestsContractTest.kt",
        )

        /**
         * `ModelDownloader(` 的构造调用。
         *
         * 前面的否定环视排掉 `noNetworkModelDownloader(`；`\(` 排掉
         * `ModelDownloader.HttpFactory` / `.DownloadSpec` 这类成员引用。
         */
        private val CONSTRUCTOR = Regex("(?<![A-Za-z0-9_.])ModelDownloader\\(")
    }
}
