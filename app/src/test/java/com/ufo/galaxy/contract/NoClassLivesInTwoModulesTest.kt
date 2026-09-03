package com.ufo.galaxy.contract

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同一个类不许在 :app 和 :shared-transport 里各存一份。
 *
 * 这条门是被真事逼出来的。两个模块曾同时拥有 `com.ufo.galaxy.network.GatewayClient`
 * 和 `com.ufo.galaxy.transport.AipTransportManager`：
 *
 *  - GatewayClient 两份 blob sha 完全相同——纯冗余，改一处忘另一处只是时间问题；
 *  - AipTransportManager **已经漂了**：断网韧性那段「指定传输和默认传输都断时退到
 *    任意仍连接的适配器」只写在 app 那份里。而手表端编译的是 shared-transport 那份。
 *    于是 WS 断、LAN TCP 还活着时，手机发得出去、手表发不出去——同一个类名，两台设备
 *    两种行为，谁都不会报错。
 *
 * 这类缺陷编译期看不出来（两个模块各自编译各自的），只有把两边源码并排放才看得见。
 * 所以判据只能是源码级的：按「包路径 + 文件名」比对两个模块的源集。
 *
 * 顺带说明为什么当初会长成这样：:app 一直只依赖 :shared-protocol，不依赖
 * :shared-transport。要复用就得先加依赖，一加依赖两份同名类就撞——于是省事的做法是
 * 复制一份。这条门堵的就是「下次再省事一次」。
 */
class NoClassLivesInTwoModulesTest {

    private fun sourceKeys(moduleDir: String): Map<String, File> {
        val root = File("../$moduleDir/src/main/java")
        assertTrue("源集不存在: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateBy { it.relativeTo(root).path }
    }

    @Test
    fun `no kotlin source file exists in both app and shared-transport`() {
        val app = sourceKeys("app")
        val shared = sourceKeys("shared-transport")
        val both = app.keys.intersect(shared.keys).sorted()
        assertEquals(
            "以下类在 :app 与 :shared-transport 里各有一份。两个模块各自编译各自的，" +
                "编译期不会报错，但手机与手表会跑到不同的实现上：$both",
            emptyList<String>(),
            both,
        )
    }

    @Test
    fun `app depends on shared-transport so it can reuse instead of copy`() {
        val gradle = File("../app/build.gradle").readText()
        assertTrue(
            ":app 不依赖 :shared-transport —— 想复用就只能复制，上面那条门迟早再红一次",
            gradle.contains("project(':shared-transport')"),
        )
    }

    @Test
    fun `the transport classes the watch compiles against live in shared-transport`() {
        val shared = sourceKeys("shared-transport")
        // 手表仓 settings.gradle.kts 把 :shared-transport 当子项目引进去，只有落在
        // 这个模块里的类它才看得见。放在 :app 里的类对手表等于不存在。
        listOf(
            "com/ufo/galaxy/network/GatewayClient.kt",
            "com/ufo/galaxy/network/GatewayAddress.kt",
            "com/ufo/galaxy/network/GatewayDiscovery.kt",
            "com/ufo/galaxy/transport/AipTransportManager.kt",
        ).forEach {
            assertTrue("手表侧看不见 $it —— 它不在 shared-transport 里", shared.containsKey(it))
        }
    }

    @Test
    fun `the resilience fallback survived the consolidation`() {
        // 合并两份时取的是 app 那份（带修复的）。若有人反向覆盖回 shared-transport
        // 的旧版本，这条会红——那正是手表端此前缺的那段。
        val src = File("../shared-transport/src/main/java/com/ufo/galaxy/transport/AipTransportManager.kt").readText()
        assertTrue(
            "断网韧性回退丢了：指定传输与默认传输都断时不再尝试其它已连接的适配器",
            src.contains("candidate.isConnected()"),
        )
    }
}
