package com.ufo.galaxy.contract

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 钉死"手机端调的每一条服务端路径,V2 上真的有"。
 *
 * 为什么需要这个
 * --------------
 * 这一类缺陷此前没有任何东西能拦住:客户端调一条服务端不存在的路径,编译通过、
 * 单测全绿、代码评审也看不出来 —— 因为路径是字符串,而**没有人手里同时握着两边的清单**。
 * 它只在真机连真服务端时表现为一个 404,而那时候的第一反应永远是"服务是不是没起来"。
 *
 * 一次实测(把 V2 的 core.api_routes 组装成 350 条权威路由,再逐条打真实 HTTP)扫出 6 处:
 *
 * | 调用点                     | 现象                                    |
 * |---------------------------|----------------------------------------|
 * | Validator.checkHealth     | GET /api/v1/health → 404,无兜底          |
 * | Validator.checkConfig     | GET /api/v1/config → 404,无兜底          |
 * | Validator.checkDevicesList| GET /api/v1/devices/list → 404,无兜底    |
 * | GalaxyApiClient.heartbeat | POST /api/v1/devices/heartbeat → **405** |
 * | memory.queryByTaskId      | 把语义 404 当端点缺失                     |
 * | RemoteConfigFetcher       | v1 恒 404,每次白打一次往返                |
 *
 * 前三条意味着"跨仓集成校验器"的四项检查里有三项**恒判失败** —— 一个恒报失败的校验器
 * 比没有校验器更糟:它不再区分"今天真的坏了"和"一直就这样"。
 *
 * 第四条尤其值得记:旧的裸路径会撞上 `/api/v1/devices/{device_id}` 这条 GET 路由,
 * POST 过去拿到的是 **405 而不是 404**,而当时的兜底只认 404 —— 于是那条真的能用的
 * legacy 路径永远到不了。**"路径没了"在 FastAPI 里有两种码**,这是路径重构后最常见的表现。
 *
 * 这份清单是怎么来的
 * ------------------
 * 不是抄文档,是**实测**:
 *
 * ```
 * # 在 ufo-galaxy-realization-v2 仓里。**两层都要挂** —— unified_launcher 的
 * # 步骤 3(权威 API 层)与步骤 4(健康检查层),少挂一层清单就是残的。
 * python - <<'EOF'
 * from fastapi import FastAPI
 * app = FastAPI()
 * from core.api_routes import create_api_routes
 * app.include_router(create_api_routes(service_manager=None, config=None))
 * from core.health_check import create_health_routes
 * hr, _ = create_health_routes(service_manager=None, config=None); app.include_router(hr)
 * print(len(app.openapi()["paths"]))       # 354
 * EOF
 * ```
 *
 * 第一次取这份清单时只挂了步骤 3,于是 `/health` 被误判成"服务端没有" ——
 * 而它就在步骤 4 里。**取权威清单的方式本身错了一层,后面每一条结论都会跟着错**,
 * 所以上面那段脚本必须照抄,不能只挂 api_routes。
 *
 * 服务端加了新路由、或改了旧路由,这里就该跟着更新 —— 而**更新它的动作本身**
 * 就是在强迫有人回答一句"手机端要不要跟着改"。
 *
 * 已知开着的一条(不在本测试范围内)
 * --------------------------------
 * `/auth/oauth/device/start` 与 `/auth/oauth/device/poll`(WearOS 用)、以及
 * `/auth/oauth/{google,github,logout}`(Android 的 OAuthManager 用)**不在这 354 条里** ——
 * 它们由 `nodes/Node_05_Auth/oauth_routes.py` 提供,那是一个**独立节点进程**,
 * 不在统一启动器的 9000 端口上。也就是说客户端拿 restBaseUrl 去打 /auth/* 会 404,
 * 除非部署时另有反代把它们转到 Auth 节点。
 *
 * 这里刻意**不改客户端**:该由谁承载 /auth/* 是进程拓扑问题(启动器代理过去,
 * 还是客户端另配一个 auth base url),属于 125 节点进程模型那一摊,不是路径笔误。
 * 本测试因此只扫 `/api/`,不扫 `/auth/` —— 把一个悬而未决的架构问题变成一条红线,
 * 只会让人把红线注释掉。
 *
 * 刻意的边界
 * ----------
 * * 只扫 `src/main`,不扫测试:测试里出现一条不存在的路径通常正是它想模拟的东西。
 * * 只比路径字面量,不比方法/参数 —— 那些静态看不出来,得靠真跑(见上面的实测脚本)。
 * * 找不到源码根目录时**判失败**而不是跳过。一个"找不到就当通过"的守卫,
 *   在目录结构变化的那天会静默失效,而那正是这份测试要防的病本身。
 */
class V2ServerPathContractTest {

    @Test
    fun `every server path referenced in production code exists on V2`() {
        val root = locateMainSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                scanned++
                pathsInStringLiterals(file.readText()).forEach { path ->
                    if (path !in APPROVED_PATHS && APPROVED_PREFIXES.none { path.startsWith(it) }) {
                        offenders += "$path   ← ${file.name}"
                    }
                }
            }

        assertTrue("没扫到任何源码文件,清单等于没在查(root=$root)", scanned > 0)
        if (offenders.isNotEmpty()) {
            fail(
                "以下服务端路径不在 V2 的权威路由清单里(共 ${offenders.size} 条):\n" +
                    offenders.distinct().joinToString("\n") { "  $it" } +
                    "\n\n要么是手机端写错了,要么是服务端新加了路由而这份清单没跟上。" +
                    "\n后者的话,按本文件顶部的实测脚本重新取一遍 V2 的路由清单,再把新路径加进 APPROVED_PATHS。" +
                    "\n**不要**为了让测试变绿而直接把路径加进来 —— 先确认服务端真的有它。"
            )
        }
    }

    /** 已被实测证伪的路径,任何时候都不许再出现在生产代码里。 */
    @Test
    fun `paths proven absent on V2 never come back`() {
        val root = locateMainSourceRoot()
        val resurrected = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val literals = pathsInStringLiterals(file.readText())
                PROVEN_ABSENT.forEach { (path, note) ->
                    if (path in literals) {
                        resurrected += "$path   ← ${file.name}   ($note)"
                    }
                }
            }
        if (resurrected.isNotEmpty()) {
            fail(
                "以下路径已被实测证明在 V2 上不存在,却又出现在生产代码里:\n" +
                    resurrected.distinct().joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * 只从**字符串字面量**里取路径,不从注释里取。
     *
     * 这一条是必须的:本轮的修复注释里大量提到那些错误路径(那正是它们该被记下来的地方),
     * 若连注释一起扫,这份测试会被自己的文档判红 —— 而"为了让测试变绿去删注释"
     * 恰恰会把最该留下的那部分信息删掉。第一版就是这么写的,写完立刻自证了它不对。
     */
    private fun pathsInStringLiterals(text: String): Set<String> =
        STRING_LITERAL.findAll(text)
            .flatMap { lit -> PATH_IN_LITERAL.findAll(lit.value).map { it.value.trimEnd('/') } }
            .toSet()

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

    companion object {
        /** 单行双引号字符串字面量。够用:所有 URL 拼接都在单行里。 */
        private val STRING_LITERAL = Regex("\"[^\"\\n]*\"")

        /** 字面量里的服务端路径(允许 "$base/api/..." 这种插值前缀)。 */
        private val PATH_IN_LITERAL = Regex("/api/v?[0-9]*/?[A-Za-z0-9/_\\-]*")

        /**
         * V2 上实测存在的、手机端会用到的路径。
         * 取自 core.api_routes 组装出的 350 条权威路由(见本文件顶部的取法)。
         */
        private val APPROVED_PATHS = setOf(
            "/api/config",
            "/api/devices/heartbeat",
            "/api/devices/list",
            "/api/devices/register",
            "/api/devices/unregister",
            "/api/v1/chat",
            "/api/v1/devices",
            "/api/v1/devices/register",
            "/api/v1/memory/query",
            "/api/v1/memory/store",
            "/api/v1/health/quick",
            "/api/v1/health/unified",
            "/api/v1/operator/devices/ecosystem",
            "/api/v1/sessions/ingest_turns",
            "/api/v1/sessions/reconcile",
            // 预留:服务端补上之后 RemoteConfigFetcher 会自动切过去(当前实测 404,
            // 只作为第二跳,不作为首选)。
            "/api/v1/config",
        )

        /**
         * 带路径参数的族。字面量拼出来的具体值无法逐条枚举,按前缀放行。
         *
         * 这一放行是**故意宽的** —— 精确的拦截交给下面的 PROVEN_ABSENT。两者分工:
         * 前缀负责"别为了参数化路径产生噪声",黑名单负责"这几条确凿的别再回来"。
         * 都做成精确名单的话,每加一个 device 子路由都要改这里,很快就没人维护了。
         */
        private val APPROVED_PREFIXES = listOf(
            "/api/v1/devices/",  // /{device_id}/heartbeat、/telemetry 等
            "/api/v1/memory/",
            "/api/v1/sessions/",
            "/api/config/",
        )

        /** 实测证伪、不许复活的路径。 */
        private val PROVEN_ABSENT = mapOf(
            "/api/v1/health" to "V2 只有 /quick 与 /unified;裸路径 404",
            "/api/v1/devices/list" to "V2 是 /api/v1/devices;裸 list 404",
            "/api/v1/devices/heartbeat" to "V2 是 /api/v1/devices/{device_id}/heartbeat;裸路径 405(不是 404,兜底认不出来)",
            "/api/memory/store" to "V2 没有无版本的 memory 路由",
            "/api/memory/query" to "同上",
        )
    }
}
