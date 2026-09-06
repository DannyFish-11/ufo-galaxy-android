package com.ufo.galaxy.contract

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍服务的三个能力位必须是打开的。
 *
 * 为什么要单独钉这一条
 * ====================
 * 代码侧用的是完整的 a11y 运行时 API（dispatchGesture / rootInActiveWindow /
 * takeScreenshot），而这三个调用各自依赖 accessibility_service_config.xml 里的一个
 * 能力位。能力位关掉之后，**代码照样编译、照样接线、照样跑**，只是在真机上什么都不做：
 * rootInActiveWindow 返回 null、dispatchGesture 不生效、takeScreenshot 抛 SecurityException。
 *
 * 而两道就位检查都拦不住：ReadinessChecker 问的是 AccessibilityManager「服务启用了吗」，
 * AccessibilityActionExecutor 问的是 instance 是否为 null —— 服务一绑定，两者都报 OK。
 * 于是这类故障在真机上的表象是「模型选了个坏动作」，排查方向被引向模型。
 *
 * 这份配置此前**没有任何测试盯着**，而它曾经就是关着的（canRetrieveWindowContent="false"，
 * 另外两位根本没声明）。这条守卫的作用不是发现问题，是防止它再次悄悄退回去。
 *
 * 判据只看配置文件本身,不依赖 Robolectric 起 Android 运行时 —— 要钉的就是**声明**,
 * 不是运行期行为;运行期行为要真机才能证,那不是单测该承诺的事。
 */
class AccessibilityCapabilitiesAreDeclaredTest {

    @Test
    fun `三个能力位都必须声明为 true`() {
        val xml = locateConfig().readText()
        for ((attr, why) in REQUIRED) {
            assertTrue(
                "accessibility_service_config.xml 缺少或未开启 android:$attr=\"true\"。$why",
                xml.contains("android:$attr=\"true\""),
            )
        }
    }

    @Test
    fun `canRetrieveWindowContent 不能被写成 false`() {
        // 单独钉这一条:它是唯一一个「写了但写成 false」也能通过上面那条包含检查之外
        // 的写法陷阱 —— 上面查的是 ="true" 存在,这里查 ="false" 不存在,两条合起来
        // 才排除掉「同时写了两次、后一次是 false」这种真实会发生的合并事故。
        val xml = locateConfig().readText()
        assertTrue(
            "canRetrieveWindowContent 被声明成了 false —— a11y 节点树会全部读不到,而且不报错。",
            !xml.contains("android:canRetrieveWindowContent=\"false\""),
        )
    }

    private fun locateConfig(): File {
        // 单测工作目录在不同调用方式下不一样(模块目录 / 仓库根),两种都试。
        val candidates = listOf(
            File("src/main/res/xml/accessibility_service_config.xml"),
            File("app/src/main/res/xml/accessibility_service_config.xml"),
            File("../app/src/main/res/xml/accessibility_service_config.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "找不到 accessibility_service_config.xml。试过:" +
                    candidates.joinToString { it.absolutePath } +
                    "。这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    companion object {
        /** 能力位 → 少了它哪个调用会哑掉。理由写进断言消息,红的时候不用回来翻代码。 */
        private val REQUIRED = listOf(
            "canRetrieveWindowContent" to "缺了 rootInActiveWindow 返回 null,a11y 节点树整棵读不到。",
            "canPerformGestures" to "缺了 dispatchGesture 不生效,点击与滑动全部落空。",
            "canTakeScreenshot" to "缺了 takeScreenshot 抛 SecurityException,像素兜底那条路断掉。",
        )
    }
}
