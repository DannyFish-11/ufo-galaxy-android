package com.ufo.galaxy.network

/**
 * 网关地址的可用性判定 —— 纯函数，不碰 Android 框架，可在 JVM 单测里跑。
 *
 * 放在 `:app` 而不是 `:shared-transport`：后者名字像是共享模块，实际 `:app` **并不依赖它**
 * （只有手表仓依赖），而且它里面的 `GatewayClient` 与 `:app` 下同名同包的文件逐字节相同 ——
 * 加上这条依赖会直接撞重复声明。要真正与手表共享得先把那份重复处理掉，
 * 而且手表 CI 把兄弟仓 checkout 钉在 `ref: main`，本分支上的新类它也看不见。
 *
 * 为什么需要它
 * ------------
 * `assets/config.properties` 里的出厂默认是：
 *
 * ```
 * galaxy_gateway_url=wss://localhost:9000
 * rest_base_url=https://localhost:9000
 * ```
 *
 * 这个值在手机上**永远不可能连通**：`localhost` 指向手机自己，而网关跑在 PC 上。
 * 它不是一个默认值，是一个伪装成默认值的占位符。
 *
 * 差别是有后果的。`MainViewModel.toggleCrossDeviceEnabled` 打开跨设备之前会问一句
 * "网关配好了吗"，判据是 `isRealUrl(settings.galaxyGatewayUrl)`。原实现只认
 * `100.x.x.x` 这类字面占位形态，`localhost` 在它眼里是**配好了的真地址** ——
 * 于是分支走向"已配置"，直连、超时、报一个网络错误，用户被引导去查网络，
 * 而真正该发生的是"你还没告诉我网关在哪"。
 *
 * 环回地址在这里被算作"未配置"，理由不是它非法，而是**它在这个语境下必然无效**：
 * 网关与 App 不在同一台机器上（唯一的例外是模拟器 + adb reverse，那属于开发场景，
 * 由使用者显式设置，而不该是出厂默认）。
 */
object GatewayAddress {

    /** 网关默认端口，与 V2 侧 `GALAXY_GATEWAY_PORT` 一致。 */
    const val DEFAULT_PORT = 9000

    /**
     * 从 `scheme://host:port/path` 里取出 host。
     *
     * 不用 `java.net.URI`：它对 `ws://` / `wss://` 这类 scheme 的解析在部分 JDK/Android
     * 版本上会把 authority 整个吞掉，而这里只需要一次纯字符串切分。IPv6 的方括号形态
     * （`ws://[::1]:9000`）单独处理，否则会被冒号切碎。
     */
    fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", url).trim()
        if (afterScheme.startsWith("[")) {
            // IPv6 字面量：`[::1]:9000/path` → `::1`
            return afterScheme.substring(1).substringBefore("]")
        }
        return afterScheme.substringBefore("/").substringBefore(":")
    }

    /**
     * host 是否指向本机。
     *
     * 覆盖 `localhost`、整个 `127.0.0.0/8`（不只是 127.0.0.1）、IPv6 环回，
     * 以及 `0.0.0.0` / `::` —— 后两个是"监听所有接口"的写法，被当成连接目标时
     * 同样连不到任何远端。
     */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSuffix(".")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "::" || h == "0.0.0.0") return true
        // 127.0.0.0/8：整段都是环回，写 127.0.0.1 之外的地址一样连不到别的机器。
        val octets = h.split(".")
        if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return octets[0].toIntOrNull() == 127
        }
        return false
    }

    /**
     * host 是否是字面占位（如 `100.x.x.x`）。
     *
     * 判据：整串只由数字、点和字母 `x` 组成，且至少含一个 `x` —— 也就是"一个把某些
     * 段写成 x 的点分地址"。
     *
     * 原判据是「含 `x` 且整体不匹配 `[0-9a-fA-F:.]+`」，它会把**合法域名**误判成占位：
     * `xbox.lan`、`nx-gateway.example.com` 都含 x、也都不是纯十六进制，于是被判成
     * "没配置"。这个方向的误判比漏判更糟 —— 用户明明填了个能连的地址，App 却说他
     * 没填。改成按形状判断之后，只有真的写成 `100.x.x.x` 这种样子才算占位。
     */
    fun isLiteralPlaceholderHost(host: String): Boolean {
        if (host.isBlank()) return true
        val h = host.lowercase()
        if (!h.contains('x')) return false
        return h.all { it.isDigit() || it == '.' || it == 'x' }
    }

    /**
     * 这个 URL 能不能真的拿去连网关。
     *
     * 空、字面占位、指向本机 —— 三者都算"没配"。返回 false **不是错误**，
     * 而是"该走发现/配对流程了"这个信号。
     */
    fun isUsableGatewayUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host = hostOf(url)
        if (host.isBlank()) return false
        if (isLiteralPlaceholderHost(host)) return false
        if (isLoopbackHost(host)) return false
        return true
    }
}
