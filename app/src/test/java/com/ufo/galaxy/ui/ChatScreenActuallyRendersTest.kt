package com.ufo.galaxy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.ufo.galaxy.data.ChatMessage
import com.ufo.galaxy.data.MessageRole
import com.ufo.galaxy.ui.components.ChatScreen
import com.ufo.galaxy.ui.theme.UFOGalaxyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 第一条**真的把界面渲染出来**的测试。
 *
 * 为什么这条测试比它断言的内容更重要
 * ==================================
 * 在它之前，本仓对界面的自动化覆盖是**零**：
 *
 *  - `app/src/androidTest/` 根本不存在，仪器测试 0 个；
 *  - 314 个单测全在 JVM 侧，没有 Robolectric、没有 `createComposeRule`；
 *  - 唯一"引用"到 [MainActivity] 的测试比的是一个字符串常量；
 *  - CI 七道闸跑的是 `compileDebugKotlin` / `assembleDebug` /
 *    `testDebugUnitTest` / `lintDebug` —— 没有模拟器。
 *
 * 也就是说：这些界面**从来没有被执行过**，只被编译过。一个 Composable 里写错
 * 状态判断、把控件放进永远为 false 的分支、或者让某个按钮在该禁用时可点，
 * 编译器和现有的全部测试都不会有任何反应。
 *
 * 这一条的价值首先在于把这条路打通：证明 `testDebugUnitTest` 能真的组合、
 * 布局、并对界面提问。断言什么反而是次要的。
 *
 * 它覆盖不到什么（先说清楚，免得被当成"界面已经有测试了"）
 * =======================================================
 * Robolectric 提供的是 JVM 里的 Android 运行时，不是一台设备：
 *
 *  - 图形是模拟的 —— 截图对比、真实字体度量、渲染性能都不在覆盖内；
 *  - 厂商 ROM 的深色模式覆盖、字体缩放、刘海适配，都看不到；
 *  - 动画在这里是被时钟驱动的，不代表真机上的观感。
 *
 * 它能回答的是"这个控件在这个状态下显不显示、能不能点" —— 而那正是目前
 * 完全没有任何自动化能回答的问题。真机验证仍然必须做，只是不再是**唯一**手段。
 */
@RunWith(RobolectricTestRunner::class)
class ChatScreenActuallyRendersTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(role: MessageRole, content: String) = ChatMessage(
        id = "$role-$content",
        role = role,
        content = content,
        timestamp = 0L,
    )

    /**
     * 按给定状态把 [ChatScreen] 摆出来。回调全是空实现 —— 这里只问渲染，不问交互。
     *
     * @param theme 外层主题。默认套 App 自己的 [UFOGalaxyTheme]；
     *   下面第一条用例刻意换成裸 `MaterialTheme`，理由见那条注释。
     */
    private fun render(
        messages: List<ChatMessage> = emptyList(),
        inputText: String = "",
        isLoading: Boolean = false,
        theme: @Composable (@Composable () -> Unit) -> Unit = { body -> UFOGalaxyTheme { body() } },
    ) {
        compose.setContent {
            theme {
                ChatScreen(
                    messages = messages,
                    inputText = inputText,
                    isLoading = isLoading,
                    onInputChange = {},
                    onSend = {},
                    onVoiceInput = {},
                )
            }
        }
    }

    // ── 这条路本身通不通 ────────────────────────────────────────────────────

    @Test
    fun `不套 App 主题时能渲染出输入框占位文案`() {
        // 这条刻意用裸 MaterialTheme，为的是把失败原因分开：
        //
        //   这条绿、下面的红  → Robolectric 与 Compose 测试链路是通的，
        //                       问题出在 UFOGalaxyTheme（它在 SideEffect 里做
        //                       `(view.context as Activity).window`，对宿主有要求）；
        //   这条也红          → 是环境/依赖没配通，跟界面本身无关。
        //
        // 本机没有 Android SDK，这套东西第一次运行必然在 CI 上 —— 那时候能不能
        // 一眼看出是哪一层坏了，取决于现在有没有把这两种情况分开。
        render(theme = { body -> MaterialTheme { body() } })
        compose.onNodeWithText("输入消息...").assertExists()
    }

    @Test
    fun `套上 App 主题同样能渲染`() {
        render()
        compose.onNodeWithText("输入消息...").assertExists()
    }

    @Test
    fun `用户消息和助手消息都被摆了出来`() {
        render(
            messages = listOf(
                message(MessageRole.USER, "打开设置"),
                message(MessageRole.ASSISTANT, "已经打开了"),
            )
        )
        compose.onNodeWithText("打开设置").assertExists()
        compose.onNodeWithText("已经打开了").assertExists()
    }

    // ── 目前只能靠读代码才知道的行为 ────────────────────────────────────────

    @Test
    fun `输入框为空时发送按钮不可点`() {
        // ChatScreen 里写的是 `enabled = text.isNotBlank() && !isLoading`。
        // 这个条件此前没有任何测试，改错了（比如漏掉 isNotBlank）不会有人发现：
        // 空消息会被发出去，而失败要到网关那一侧才显现。
        render(inputText = "")
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test
    fun `有内容时发送按钮可点`() {
        // 反向保险：把上一条改成"永远不可点"也能让它变绿。
        render(inputText = "打开设置")
        compose.onNodeWithContentDescription("发送").assertIsEnabled()
    }

    @Test
    fun `执行中时发送与语音都被锁住`() {
        // isLoading 期间重复提交是真实事故来源 —— MainViewModel.sendMessage 里
        // 另有一道"已在执行则忽略"的守卫，说明这件事发生过。界面这一层是第一道，
        // 而它此前同样没有测试。
        render(inputText = "打开设置", isLoading = true)
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("语音输入").assertIsNotEnabled()
    }
}
