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
        private const val SCREENSHOT_TIMEOUT_SEC = 5L
        private const val JPEG_QUALITY = 80
    }
    
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
                    typeText(action.text)
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
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchScroll(x: Int, y: Int, direction: String): Boolean {
        val startX = x.toFloat()
        val startY = y.toFloat()
        val (endX, endY) = when (direction) {
            "down"  -> startX to startY - SCROLL_OFFSET_PX   // swipe up to scroll down
            "up"    -> startX to startY + SCROLL_OFFSET_PX   // swipe down to scroll up
            "left"  -> startX - SCROLL_OFFSET_PX to startY   // swipe left
            "right" -> startX + SCROLL_OFFSET_PX to startY   // swipe right
            else    -> startX to startY - SCROLL_OFFSET_PX
        }
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_SCROLL_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
     * Captures the current screen as a JPEG byte array.
     * Requires Android 11 (API 30) or later; returns null on older devices.
     * Blocks the calling thread for up to [SCREENSHOT_TIMEOUT_SEC] seconds.
     *
     * Must NOT be called from the main thread.
     */
    fun captureJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+; device is API ${Build.VERSION.SDK_INT}")
            return null
        }
        return captureJpegApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureJpegApi30(): ByteArray? {
        val latch = CountDownLatch(1)
        var resultBytes: ByteArray? = null

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                            resultBytes = stream.toByteArray()
                            bitmap.recycle()
                        }
                        hardwareBuffer.close()
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                    latch.countDown()
                }
            }
        )

        latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)
        return resultBytes
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
