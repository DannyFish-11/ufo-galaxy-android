package com.ufo.galaxy.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.ufo.galaxy.agent.AccessibilityExecutor
import com.ufo.galaxy.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * 硬件按键监听服务
 * 通过 AccessibilityService 监听音量键等物理按键
 * 
 * 使用方法:
 * 1. 在 AndroidManifest.xml 中注册此服务
 * 2. 用户需要在系统设置中启用此无障碍服务
 * 3. 服务启用后，双击音量下键可唤醒 UFO Galaxy
 *
 * Also provides concrete [AccessibilityExecutor] action dispatch and screenshot
 * capture used by [com.ufo.galaxy.service.AccessibilityActionExecutor] and
 * [com.ufo.galaxy.service.AccessibilityScreenshotProvider].
 */
class HardwareKeyListener : AccessibilityService() {
    
    companion object {
        private const val TAG = "HardwareKeyListener"
        
        // 双击检测参数
        private const val DOUBLE_CLICK_INTERVAL = 500L  // 双击间隔（毫秒）
        private const val TRIPLE_CLICK_INTERVAL = 800L  // 三击间隔（毫秒）
        
        // 触发模式
        var triggerMode = TriggerMode.DOUBLE_VOLUME_DOWN

        /**
         * Reference to the currently running [HardwareKeyListener] instance.
         * Non-null only while the accessibility service is connected and active.
         */
        @Volatile
        var instance: HardwareKeyListener? = null
            private set

        private const val GESTURE_DURATION_TAP_MS = 50L
        private const val GESTURE_DURATION_SCROLL_MS = 300L
        private const val SCROLL_OFFSET_PX = 300
        /**
         * 等手势回调的额外宽限。手势本身的时长之外再给这么多 —— 系统派发、
         * 主线程排队都要时间。给足够大是刻意的：这里等超时会被判成"手势没完成"，
         * 而误判一次没完成，代价是上层白白重规划一轮。
         */
        private const val GESTURE_RESULT_GRACE_MS = 3_000L

        /** 找输入框时的树遍历深度护栏，防病态深树。 */
        private const val MAX_TREE_DEPTH = 40

        private const val SCREENSHOT_TIMEOUT_SEC = 5L
        private const val JPEG_QUALITY = 80

    }

    /**
     * 截图频率闸门。每个服务实例一份 —— 平台的节流时间戳也是记在服务实例上的。
     */
    private val screenshotThrottle = ScreenshotThrottle()
    
    // 按键时间记录
    private var lastVolumeDownTime = 0L
    private var volumeDownClickCount = 0
    
    private var lastVolumeUpTime = 0L
    private var volumeUpClickCount = 0
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "硬件按键监听服务已连接")
        instance = this
        
        // 配置服务
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "服务被中断")
    }
    
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        
        // 只处理按键按下事件
        if (event.action != KeyEvent.ACTION_DOWN) return false
        
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                return handleVolumeDown()
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                return handleVolumeUp()
            }
        }
        
        return false
    }
    
    /**
     * 处理音量下键
     */
    private fun handleVolumeDown(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 检查是否在双击间隔内
        if (currentTime - lastVolumeDownTime < DOUBLE_CLICK_INTERVAL) {
            volumeDownClickCount++
        } else {
            volumeDownClickCount = 1
        }
        lastVolumeDownTime = currentTime
        
        Log.d(TAG, "音量下键点击次数: $volumeDownClickCount")
        
        // 根据触发模式检查
        when (triggerMode) {
            TriggerMode.DOUBLE_VOLUME_DOWN -> {
                if (volumeDownClickCount >= 2) {
                    triggerWakeUp()
                    volumeDownClickCount = 0
                    return true  // 消费事件
                }
            }
            TriggerMode.TRIPLE_VOLUME_DOWN -> {
                if (volumeDownClickCount >= 3) {
                    triggerWakeUp()
                    volumeDownClickCount = 0
                    return true
                }
            }
            else -> {}
        }
        
        return false  // 不消费事件，让系统处理音量调节
    }
    
    /**
     * 处理音量上键
     */
    private fun handleVolumeUp(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastVolumeUpTime < DOUBLE_CLICK_INTERVAL) {
            volumeUpClickCount++
        } else {
            volumeUpClickCount = 1
        }
        lastVolumeUpTime = currentTime
        
        Log.d(TAG, "音量上键点击次数: $volumeUpClickCount")
        
        when (triggerMode) {
            TriggerMode.DOUBLE_VOLUME_UP -> {
                if (volumeUpClickCount >= 2) {
                    triggerWakeUp()
                    volumeUpClickCount = 0
                    return true
                }
            }
            TriggerMode.TRIPLE_VOLUME_UP -> {
                if (volumeUpClickCount >= 3) {
                    triggerWakeUp()
                    volumeUpClickCount = 0
                    return true
                }
            }
            else -> {}
        }
        
        return false
    }
    
    /**
     * 触发唤醒
     */
    private fun triggerWakeUp() {
        Log.i(TAG, "触发 UFO Galaxy 唤醒")
        
        // 发送广播通知
        val intent = Intent(ACTION_WAKE_UP).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        
        // 启动主界面
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRIGGERED_BY, "hardware_key")
        }
        startActivity(activityIntent)
    }

    // ── Accessibility action execution ────────────────────────────────────────

    /**
     * Executes [action] via the Android AccessibilityService APIs.
     * Returns true if the action was dispatched, false on any error.
     */
    fun executeAction(action: AccessibilityExecutor.AccessibilityAction): Boolean {
        return try {
            when (action) {
                is AccessibilityExecutor.AccessibilityAction.Tap ->
                    dispatchTap(action.x, action.y)
                is AccessibilityExecutor.AccessibilityAction.Scroll ->
                    dispatchScroll(action.x, action.y, action.direction)
                is AccessibilityExecutor.AccessibilityAction.TypeText ->
                    typeText(action.text, action.submit)
                is AccessibilityExecutor.AccessibilityAction.OpenApp ->
                    launchApp(action.packageName)
                AccessibilityExecutor.AccessibilityAction.Back ->
                    performGlobalAction(GLOBAL_ACTION_BACK)
                AccessibilityExecutor.AccessibilityAction.Home ->
                    performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAction failed: ${e.message}", e)
            false
        }
    }

    private fun dispatchTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_TAP_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture, GESTURE_DURATION_TAP_MS)
    }

    private fun dispatchScroll(x: Int, y: Int, direction: String): Boolean {
        val startX = x.toFloat()
        val startY = y.toFloat()
        val (endX, endY) = when (direction) {
            "down"  -> startX to startY - SCROLL_OFFSET_PX   // swipe up to scroll down
            "up"    -> startX to startY + SCROLL_OFFSET_PX   // swipe down to scroll up
            "left"  -> startX - SCROLL_OFFSET_PX to startY   // swipe left to scroll right
            "right" -> startX + SCROLL_OFFSET_PX to startY   // swipe right to scroll left
            else    -> startX to startY - SCROLL_OFFSET_PX
        }
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_SCROLL_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture, GESTURE_DURATION_SCROLL_MS)
    }

    /**
     * 派发手势，并**等到系统告诉我们它到底完成了还是被取消了**。
     *
     * 为什么不能只看 `dispatchGesture` 的返回值
     * ========================================
     * 它返回的是"这个手势被接受进队列了吗"，不是"这个手势做完了吗"。系统会在
     * 这些情况下**取消**一个已经接受的手势：
     *
     *  · 用户此刻正在摸屏幕；
     *  · 另一个手势（我们自己上一步的，或者别的无障碍服务的）还没做完；
     *  · 前台窗口在手势中途换了。
     *
     * 取消走的是 `onCancelled` 回调。此前这里回调传的是 `null` —— 于是"被取消"和
     * "已完成"在调用方眼里完全一样，两者都是 `true`。再往上，[com.ufo.galaxy.loop.ExecutorBridge]
     * 把这个 true 记成 [com.ufo.galaxy.loop.StepStatus.SUCCESS]，于是一次没有发生的
     * 点击被算成了一步进展，循环接着往下走。这正是真机上"每步都成功、最后什么也没做成"
     * 的来源之一。
     *
     * 回调派发在主线程，本方法在工作线程等待 —— 所以**不能在主线程调用**，
     * 那样会等一个永远不会被派发的回调。
     *
     * @param durationMs 手势本身的时长；等待上限在此基础上加 [GESTURE_RESULT_GRACE_MS]。
     */
    private fun dispatchAndAwait(gesture: GestureDescription, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "手势被系统取消（用户触屏/手势冲突/窗口切换）")
                    latch.countDown()
                }
            },
            null
        )
        if (!accepted) {
            // 连入队都没成功：多半是 canPerformGestures 没开，或服务已断连。
            Log.e(TAG, "dispatchGesture 拒绝入队 —— 检查 canPerformGestures 能力位")
            return false
        }
        if (!latch.await(durationMs + GESTURE_RESULT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "手势在 ${durationMs + GESTURE_RESULT_GRACE_MS}ms 内没有回调，按未完成处理")
            return false
        }
        return completed.get()
    }

    /**
     * 往当前输入框里填字，[submit] 为真时再按一下回车提交。
     *
     * 三处此前会静默失当的地方
     * ========================
     *  1. **没有焦点就直接失败**。`findFocus(FOCUS_INPUT)` 只在已经有输入焦点时才返回
     *     节点；刚点开一个页面、输入框还没被点过时它是 null，于是"输入"这一步失败，
     *     而失败原因只是一个 false。现在会退而在树里找第一个可编辑节点并主动聚焦。
     *  2. **填完从不提交**。搜索、发消息这类任务里，`ACTION_SET_TEXT` 之后必须有一次
     *     回车/确认，否则字填进去了、任务停在那儿 —— 而每一步都报成功。
     *     提交用 [AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER]（API 30+），
     *     它触发的是输入法上那个"搜索/发送"键，而不是插入一个换行符。
     *  3. **节点没回收**。`rootInActiveWindow` 与树里找到的节点在 API < 33 都持有跨进程
     *     资源，每次输入都漏一份。
     */
    private fun typeText(text: String, submit: Boolean): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "rootInActiveWindow 为空 —— 检查 canRetrieveWindowContent 能力位")
            return false
        }
        try {
            val target = resolveTextTarget(root) ?: run {
                Log.w(TAG, "当前窗口里找不到可编辑的输入框")
                return false
            }
            try {
                // 已经有焦点就不用再点一次：重复 ACTION_FOCUS 在部分输入法上会弹收键盘。
                if (!target.isFocused) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Log.e(TAG, "ACTION_SET_TEXT 失败")
                    return false
                }
                if (!submit) return true
                return pressImeEnter(target)
            } finally {
                recycleNodeQuietly(target)
            }
        } finally {
            recycleNodeQuietly(root)
        }
    }

    /**
     * 找这次输入该落在哪个节点上：优先当前输入焦点，其次树里第一个可编辑节点。
     *
     * 返回的节点由调用方负责回收。
     */
    private fun resolveTextTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            if (focused.isEditable) return focused
            // 焦点在一个不可编辑的控件上（按钮之类）：这不是我们要的目标，回收掉再去找。
            recycleNodeQuietly(focused)
        }
        return findFirstEditable(root, depth = 0)
    }

    /** 深度优先找第一个可见且可编辑的节点。返回的节点由调用方回收。 */
    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser) {
            // 命中的是传进来的这个节点。调用方只会回收**返回值**，而根节点/父节点的
            // 回收由各自的持有者负责，所以这里必须返回一个独立句柄。
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = try {
                findFirstEditable(child, depth + 1)
            } finally {
                recycleNodeQuietly(child)
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 按输入法上的确认键（搜索/发送/前往）。
     *
     * [AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER] 是 API 30 引入的。
     * 低版本没有等价的公开动作 —— 注入 KeyEvent 需要 INJECT_EVENTS 这个签名级权限，
     * 普通应用拿不到。所以低版本上这里如实返回 false，让这一步以"提交不了"失败，
     * 而不是假装提交成功。
     */
    private fun pressImeEnter(target: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "ACTION_IME_ENTER 需要 API 30+，当前 API ${Build.VERSION.SDK_INT}：无法提交")
            return false
        }
        val ok = target.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        )
        if (!ok) Log.w(TAG, "ACTION_IME_ENTER 被拒 —— 该输入框可能没有配置 IME 动作")
        return ok
    }

    /**
     * API < 33 上 [AccessibilityNodeInfo] 持有跨进程资源，必须 recycle，
     * 否则每一步都在漏；API 33+ recycle 是 no-op。窗口切换竞态下节点可能已失效，
     * 回收失败静默忽略。
     */
    private fun recycleNodeQuietly(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
            // 已被系统回收/失效：忽略。
        }
    }

    private fun launchApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed for $packageName: ${e.message}", e)
            false
        }
    }

    // ── Screenshot capture ────────────────────────────────────────────────────

    /**
     * 把当前屏幕截成 JPEG 字节；拿不到返回 null。
     *
     * 需要 Android 11（API 30）及以上 —— [android.accessibilityservice.AccessibilityService.takeScreenshot]
     * 是 API 30 引入的，低版本**没有任何替代路径**（本应用不申请 MediaProjection），
     * 所以在低版本上本地循环整条链路都跑不起来，这一点由 [canCaptureScreen] 在开跑前
     * 就报出来，而不是留到每一步去撞。
     *
     * 会阻塞调用线程：既要等平台的节流窗口（见 [ScreenshotThrottle]），又要等截图
     * 回调（最多 [SCREENSHOT_TIMEOUT_SEC] 秒）。**不能在主线程调用** —— 回调本身
     * 就派发在主线程上，在主线程等它必然死锁。
     */
    fun captureJpeg(): ByteArray? = capture().bytes

    /**
     * 截图能力是否具备。给开跑前体检用 —— 不具备时的表现必须是一条明确的原因，
     * 而不是每一步都拿到一个 null。
     */
    fun canCaptureScreen(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 截图，并把失败原因带回来。
     *
     * 节流被拒时重试一次：本类自己的闸门已经保证了间隔，但同一个服务实例上若有
     * 别处也在截（将来的截屏调试入口之类），仍可能撞上。重试一次的代价是一个节流
     * 窗口，收益是不让整条会话因为一次撞车终止。只对**可重试**的失败重试
     * （见 [ScreenshotOutcome.retryable]）—— 对 FLAG_SECURE 窗口重试是纯浪费。
     */
    fun capture(): ScreenshotOutcome {
        // SDK 判断写在这里而不是调 canCaptureScreen()：lint 的版本流分析只认
        // 就地的 SDK_INT 比较，抽成方法它就看不见，@RequiresApi 会报 NewApi。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot 需要 API 30+，当前设备 API ${Build.VERSION.SDK_INT}")
            return ScreenshotOutcome.failed(ScreenshotOutcome.ERROR_UNSUPPORTED_SDK)
        }
        val first = captureOnceApi30()
        if (!first.retryable) return first
        Log.w(TAG, "截图被平台节流拒绝，等一个窗口后重试一次")
        return captureOnceApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureOnceApi30(): ScreenshotOutcome {
        // 先过闸门。平台的节流判据是 `<=333ms`，且被拒时走的是**正常回调**，
        // 不在这里等，LoopController 每步三次截图里的第二次必被拒，
        // 整个会话随即以 screenshot_failed 终止。
        screenshotThrottle.acquire()

        val latch = CountDownLatch(1)
        // 回调派发在主线程、本方法在工作线程等待 —— 跨线程传结果必须走
        // AtomicReference：await() 超时返回时没有 happens-before 保证，
        // 读一个普通的捕获变量可能读到旧值。
        val outcome = AtomicReference(ScreenshotOutcome.timeout())

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val bytes = encode(screenshotResult)
                        outcome.set(
                            if (bytes == null) {
                                ScreenshotOutcome(bytes = null, errorCode = ScreenshotOutcome.NO_ERROR)
                            } else {
                                ScreenshotOutcome.ok(bytes)
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "截图编码失败: ${e.message}", e)
                        outcome.set(ScreenshotOutcome.failed(ScreenshotOutcome.ERROR_INTERNAL))
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot 失败: errorCode=$errorCode")
                    outcome.set(ScreenshotOutcome.failed(errorCode))
                    latch.countDown()
                }
            }
        )

        if (!latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.e(TAG, "takeScreenshot 在 ${SCREENSHOT_TIMEOUT_SEC}s 内没有回调")
            return ScreenshotOutcome.timeout()
        }
        return outcome.get()
    }

    /**
     * HardwareBuffer → JPEG。
     *
     * **两个** bitmap 都要回收：[Bitmap.wrapHardwareBuffer] 包出来的那个此前没有被
     * 回收过，于是每一步都泄漏一整屏的图形内存；copy 出来的软件位图才是压缩的输入。
     * 色彩空间从 [ScreenshotResult.colorSpace] 取，而不是传 null —— 传 null 会让宽色域
     * 屏上的截图偏色，模型看到的颜色和用户看到的不是一回事。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun encode(result: ScreenshotResult): ByteArray? {
        val hardwareBuffer = result.hardwareBuffer
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace) ?: return null
            try {
                val software = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                try {
                    val stream = ByteArrayOutputStream()
                    software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    return stream.toByteArray()
                } finally {
                    software.recycle()
                }
            } finally {
                wrapped.recycle()
            }
        } finally {
            hardwareBuffer.close()
        }
    }

    /**
     * Returns the screen width in pixels; 0 if unavailable.
     */
    fun getScreenWidth(): Int = getScreenDimensions().first

    /**
     * Returns the screen height in pixels; 0 if unavailable.
     */
    fun getScreenHeight(): Int = getScreenDimensions().second

    private fun getScreenDimensions(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (e: Exception) {
            Log.e(TAG, "getScreenDimensions failed: ${e.message}", e)
            0 to 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "硬件按键监听服务已销毁")
    }
}

/**
 * 触发模式
 */
enum class TriggerMode {
    DOUBLE_VOLUME_DOWN,   // 双击音量下
    TRIPLE_VOLUME_DOWN,   // 三击音量下
    DOUBLE_VOLUME_UP,     // 双击音量上
    TRIPLE_VOLUME_UP,     // 三击音量上
    VOLUME_UP_DOWN,       // 音量上+下组合
    DISABLED              // 禁用
}

// 广播 Action
const val ACTION_WAKE_UP = "com.ufo.galaxy.ACTION_WAKE_UP"
const val EXTRA_TRIGGERED_BY = "triggered_by"
