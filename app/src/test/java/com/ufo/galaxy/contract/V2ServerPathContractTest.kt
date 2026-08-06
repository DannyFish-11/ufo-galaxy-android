package com.ufo.galaxy.contract

import org.junit.Assert.assertFalse
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
 * `/auth/` 那一族:曾经开着,现在收了
 * ------------------------------------
 * 这里原先写着"`/auth/oauth/` 那一族由独立节点进程提供,不在 9000 上,所以本测试
 * 只扫 `/api/`" —— **那个判断只对了一半**。
 *
 * 后来在 V2 仓里查清楚:`register_oauth_routes()` 定义在
 * `nodes/Node_05_Auth/oauth_routes.py`,而全仓只有它自己那一行 —— 从来没有被调用过。
 * 也就是说那一族既不在统一启动器的 9000 上,**也不在 Node_05 自己的 8005 上**,
 * 任何进程都没有服务过它们。不是"进程拓扑没定",是没人挂。
 *
 * V2 侧已把它并入权威层(`core/auth_surface_merge.py`),所以本测试从这一轮起
 * **把 `/auth/` 也纳入扫描**。留着不扫等于把一条已经能验的契约继续蒙着。
 *
 * 纳入后第一个被误判的是 `ufo-galaxy://auth/callback` —— 那是 GitHub OAuth 的
 * 客户端回调地址(系统按 scheme 把浏览器拉回 App),不是服务端路由。所以扫描器
 * 先按 scheme 整条排除自定义深链,只留 http(s) 与相对路径。
 *
 * 第二次触发(合入 main 之后)
 * ---------------------------
 * 合入 main 带进来一个 `DevicePairingClient`,它调 `/api/v1/pairing/` 那一族。
 * 这份测试当即判红 —— 而**它报的位置对、结论错**,值得记下来:
 *
 * 客户端调的是 `/api/v1/pairing/claim/$requestId`(参数化),V2 上确实有
 * `/api/v1/pairing/claim/{request_id}`。判红的原因在扫描器自己身上:它对每个
 * token 先 `trimEnd('/')`,于是参数化调用被归一成了裸路径 `/api/v1/pairing/claim`,
 * 而那一条 V2 上没有。**两种形态被揉成了同一个字符串。**
 *
 * 这不只是噪声问题 —— 它把本文件顶部那个 405 缺陷判成了看不见:裸的
 * `POST /api/v1/devices/heartbeat` 撞上 `GET /api/v1/devices/{device_id}` 返回 405,
 * 而"裸路径 vs 参数化"正是唯一能把这两者分开的信息。所以这一轮保留了尾部斜杠,
 * 并把参数化的父路径单独列成 [APPROVED_PARAM_PARENTS](精确,不是前缀)。
 *
 * 顺带确认了一件跨仓的事:`/api/v1/pairing/` 那一族原先**只挂在网关侧**,统一
 * 启动器的权威 API 层上没有 —— 手机端发起入网会 404。V2 那边已由
 * `core/gateway_surface_merge.py` 并入权威层,本轮实测 381 条路由里 PRESENT。
 *
 * 一个 Kotlin 的坑(这份文件踩过)
 * -------------------------------
 * Kotlin 的块注释**可嵌套**(与 Java 不同)。KDoc 里写 `/auth/` 加一个星号,
 * 那两个字符会被词法器当成"再开一层块注释",于是结尾那个星号斜杠只关掉一层,
 * 整个文件从那里开始全被吞进注释 —— 报错是 `Unclosed comment`,位置指向文件末尾,
 * 离真正的原因很远。本文件第一版就是这么挂的(CI 报 V2ServerPathContractTest.kt:216:1)。
 * 所以下面提到路径族时一律写成 `/auth/`、`/api/` 这样,不带尾部星号。
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
                    if (!isApproved(path)) {
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

    /**
     * 守卫自身的守卫:裸路径与参数化路径**必须**得到不同的结论。
     *
     * 没有这一条,[isApproved] 的那个分支就是一段没人验过的逻辑 ——
     * 而它恰恰是本文件这一轮唯一改动的地方。把它写反(比如两条都走
     * [APPROVED_PARAM_PARENTS])会让整份契约测试恒绿,而恒绿的契约测试
     * 与没有测试等价。
     */
    @Test
    fun `a bare path is not excused by an approved parameterised parent`() {
        // 这一组对照原先用的是 /api/v1/pairing/claim —— 那一族已随三仓统一到
        // /api/v1/pair/claim 而从放行清单里摘掉了。演示扫描器语义要用清单里**还在**
        // 的条目,否则"参数化放行"这一支根本没被走到,断言恒真、区分度归零。
        //
        // /api/v1/devices 仍在 APPROVED_PARAM_PARENTS 里,拿它来演示。
        assertTrue("参数化调用应放行", isApproved("/api/v1/devices/"))

        // 同一族的裸路径确实会被 APPROVED_PREFIXES 兜住 —— 这正是两套机制的分工:
        // 参数化放行判的是形态,前缀放行判的是族。拦裸路径靠的是 PROVEN_ABSENT。
        assertTrue(isApproved("/api/v1/devices/telemetry"))

        // 统一之后的那条必须在放行清单里,否则设备接入会被本守卫判红。
        assertTrue("统一后的接纳端点应放行", isApproved("/api/v1/pair/claim"))

        // 完全没见过的东西照拦不误。
        assertFalse(isApproved("/api/v1/totally/made/up"))
        assertFalse(isApproved("/api/v1/made-up-parent/"))
    }

    /** 已被实测证伪的路径,任何时候都不许再出现在生产代码里。 */
    @Test
    fun `paths proven absent on V2 never come back`() {
        val root = locateMainSourceRoot()
        val resurrected = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                // 这里只比**裸路径**。末尾带斜杠的 token 是"调用点在后面拼了一个
                // 路径参数",那是另一条路由,不该拿来和裸路径黑名单比。
                //
                // 曾经想过"先 trimEnd('/') 再比,免得漏"—— 那样会埋一个反向的雷:
                // 本文件顶部已经写明 V2 上没有裸的 /api/v1/pairing/claim,谁按这句话
                // 把它加进 PROVEN_ABSENT,正确的 `/claim/$requestId` 调用就会被误判。
                // 而"漏"其实并不存在:`"$base/api/v1/health/"` 这种写法末尾带斜杠,
                // 会去查 APPROVED_PARAM_PARENTS 而查不到,上面第一条用例就拦下了。
                val literals = pathsInStringLiterals(file.readText()).filterNot { it.endsWith("/") }.toSet()
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
            .filterNot { CUSTOM_SCHEME_URI.containsMatchIn(it.value) }
            .flatMap { lit -> PATH_IN_LITERAL.findAll(lit.value).map { it.value } }
            .toSet()

    /**
     * 一条扫出来的路径算不算数。
     *
     * **末尾那个斜杠是信息,不是噪声。**
     * 这里此前对每个 token 先 `trimEnd('/')` 再比,于是
     * `"$base/api/v1/pairing/claim/$requestId"`(正确:V2 上是
     * `/api/v1/pairing/claim/{request_id}`)和裸的 `"/api/v1/pairing/claim"`
     * (V2 上根本没有这条)会被归一成同一个字符串 —— 两者从此无法区分。
     *
     * 而"裸路径撞上一条参数化路由"正是本文件顶部记的那个 405 缺陷的形状:
     * `POST /api/v1/devices/heartbeat` 撞上 `GET /api/v1/devices/{device_id}`,
     * 拿到 405 而不是 404。把这两种形态揉成一个,等于把这一整类缺陷判成看不见。
     *
     * 所以分两条路判:
     *  - token 以 `/` 收尾 → 调用点在后面拼了路径参数,查 [APPROVED_PARAM_PARENTS];
     *  - 否则 → 是一条完整路径,查 [APPROVED_PATHS] 或 [APPROVED_PREFIXES]。
     */
    private fun isApproved(token: String): Boolean {
        if (token.endsWith("/")) {
            return token.trimEnd('/') in APPROVED_PARAM_PARENTS
        }
        return token in APPROVED_PATHS || APPROVED_PREFIXES.any { token.startsWith(it) }
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

    companion object {
        /** 单行双引号字符串字面量。够用:所有 URL 拼接都在单行里。 */
        private val STRING_LITERAL = Regex("\"[^\"\\n]*\"")

        /**
         * 自定义 scheme 的深链,不是服务端路径。
         *
         * ``ufo-galaxy://auth/callback`` 是 GitHub OAuth 的**客户端回调地址** ——
         * 系统按 scheme 把浏览器拉回 App,V2 上不存在也不该存在这条路由。
         * 把 /auth/ 纳入扫描后它第一个被误判,所以先按 scheme 整条排除。
         *
         * 只排非 http(s) 的:``https://...`` 里出现的路径仍然要查,那种才是真调用。
         */
        private val CUSTOM_SCHEME_URI = Regex("\\b(?!https?\\b)[a-zA-Z][a-zA-Z0-9+.-]*://")

        /** 字面量里的服务端路径(允许 "$base/api/..." 这种插值前缀)。 */
        private val PATH_IN_LITERAL = Regex("/(?:api|auth)/v?[0-9]*/?[A-Za-z0-9/_\\-]*")

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
            // OAuth 登录面。这一族此前**任何进程都没有服务过** ——
            // register_oauth_routes() 在 V2 仓里从来没被调用过,既不在统一启动器的
            // 9000 上,也不在 Node_05_Auth 自己的 8005 上。V2 侧已把它并入权威层
            // (core/auth_surface_merge.py),本清单随之把 /auth/ 纳入扫描范围。
            "/auth/oauth/google",
            "/auth/oauth/github",
            "/auth/oauth/logout",
            "/auth/oauth/refresh",
            "/auth/oauth/providers",
            "/auth/oauth/callback",
            "/auth/oauth/me",
            "/auth/oauth/health",
            // 设备接纳。三仓统一到这一条:桌面出示名片(短码/二维码),设备把码连同
            // **自己的身份**交过来,当场换回属于自己的能力令牌 + 可达路径清单。
            //
            // 换掉了 `/api/v1/pairing/enroll → status → claim/{rid}` 那条三段式
            // (手表那侧还另有一套 OAuth device flow)。三种设备三条路,凭证形态与
            // 失败模式各不相同,而它们要接的是同一台机器。
            //
            // V2 侧对这条**免鉴权** —— 还没配对的设备手里没有任何令牌,要求它先带
            // 令牌才能来换令牌就是死锁。凭证是那个一次性短码本身。
            "/api/v1/pair/claim",
            // 预留:服务端补上之后 RemoteConfigFetcher 会自动切过去(当前实测 404,
            // 只作为第二跳,不作为首选)。
            "/api/v1/config",
        )

        /**
         * 调用点在其后拼了路径参数的那些父路径 —— token 形如 `/api/v1/pairing/claim/`。
         *
         * 与 [APPROVED_PREFIXES] 的区别是**精确**:列在这里表示"V2 上确实有一条
         * `<父路径>/{参数}` 的路由",而裸的父路径本身**不**被这条放行。
         * 逐条实测过(把 core.api_routes + core.health_check 组装成 381 条权威路由再比):
         *
         *   /api/v1/devices        → /api/v1/devices/{device_id}/heartbeat 等 22 条
         *   /api/v1/pairing/claim  → /api/v1/pairing/claim/{request_id}
         *   /api/v1/pairing/status → /api/v1/pairing/status/{request_id}
         */
        private val APPROVED_PARAM_PARENTS = setOf(
            "/api/v1/devices",
        )

        /**
         * 整族放行的前缀 —— 用于"这一族下面会不断加子路由"的地方。
         *
         * 这一放行是**故意宽的**。三套机制的分工:
         *  - 前缀:别让"这一族又加了一条子路由"变成噪声;
         *  - [APPROVED_PARAM_PARENTS]:精确表达"这里拼的是一个路径参数";
         *  - [PROVEN_ABSENT]:这几条确凿不存在的,任何时候都不许回来。
         *
         * 都做成精确名单的话,每加一个 device 子路由都要改这里,很快就没人维护了;
         * 反过来全靠前缀,则[isApproved] 里那条"裸路径 vs 参数化"的区分会失效。
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
