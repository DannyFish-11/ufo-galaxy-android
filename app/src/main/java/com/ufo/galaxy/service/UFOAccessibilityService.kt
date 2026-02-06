package com.ufo.galaxy.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * UFO Galaxy 无障碍服务
 * 
 * 功能：
 * 1. 系统级点击和滑动
 * 2. 读取界面内容
 * 3. 智能元素查找
 * 4. 文本输入
 * 
 * 版本：1.0.0
 * 日期：2026-01-24
 */
class UFOAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UFOAccessibilityService"
        
        @Volatile
        private var instance: UFOAccessibilityService? = null
        
        fun getInstance(): UFOAccessibilityService? = instance
        
        /**
         * 检查服务是否已启用
         */
        fun isEnabled(): Boolean = instance != null
    }
    
    // 截图辅助类
    private lateinit var screenshotHelper: com.ufo.galaxy.utils.ScreenshotHelper

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenshotHelper = com.ufo.galaxy.utils.ScreenshotHelper(this)
        Log.i(TAG, "UFO Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 监听界面变化事件
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Window changed: ${it.packageName}")
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    Log.d(TAG, "View clicked: ${it.text}")
                }
                else -> {}
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "UFO Accessibility Service destroyed")
    }

    // ============================================================================
    // 核心功能：点击
    // ============================================================================

    /**
     * 在指定坐标点击
     * @param x X 坐标
     * @param y Y 坐标
     * @return 是否成功
     */
    suspend fun performClick(x: Float, y: Float): Boolean = suspendCoroutine { continuation ->
        try {
            val path = Path().apply {
                moveTo(x, y)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Click completed at ($x, $y)")
                    continuation.resume(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Click cancelled at ($x, $y)")
                    continuation.resume(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Click failed: ${e.message}")
            continuation.resume(false)
        }
    }

    // ============================================================================
    // 核心功能：滑动
    // ============================================================================

    /**
     * 滑动手势
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX 结束 X 坐标
     * @param endY 结束 Y 坐标
     * @param duration 持续时间（毫秒）
     * @return 是否成功
     */
    suspend fun performSwipe(
        startX: Float, 
        startY: Float,
        endX: Float, 
        endY: Float,
        duration: Long = 300
    ): Boolean = suspendCoroutine { continuation ->
        try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                    continuation.resume(true)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Swipe cancelled")
                    continuation.resume(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed: ${e.message}")
            continuation.resume(false)
        }
    }

    // ============================================================================
    // 核心功能：滚动
    // ============================================================================

    /**
     * 滚动屏幕
     * @param direction 方向：up, down, left, right
     * @param amount 滚动量（像素）
     * @return 是否成功
     */
    suspend fun performScroll(direction: String, amount: Int = 500): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        
        return when (direction.lowercase()) {
            "up" -> performSwipe(centerX, centerY, centerX, centerY - amount, 300)
            "down" -> performSwipe(centerX, centerY, centerX, centerY + amount, 300)
            "left" -> performSwipe(centerX, centerY, centerX - amount, centerY, 300)
            "right" -> performSwipe(centerX, centerY, centerX + amount, centerY, 300)
            else -> false
        }
    }

    // ============================================================================
    // 核心功能：读取界面内容
    // ============================================================================

    /**
     * 获取当前屏幕的所有元素
     * @return JSON 格式的元素列表
     */
    fun getScreenContent(): JSONObject {
        return try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "No active window")
                }
            } else {
                val elements = JSONArray()
                traverseNode(rootNode, elements)
                rootNode.recycle()
                
                JSONObject().apply {
                    put("success", true)
                    put("element_count", elements.length())
                    put("elements", elements)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get screen content failed: ${e.message}")
            JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
        }
    }

    /**
     * 递归遍历节点树
     */
    private fun traverseNode(node: AccessibilityNodeInfo, elements: JSONArray) {
        try {
            val element = JSONObject().apply {
                put("class", node.className?.toString() ?: "")
                put("text", node.text?.toString() ?: "")
                put("content_description", node.contentDescription?.toString() ?: "")
                put("view_id", node.viewIdResourceName ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("focusable", node.isFocusable)
                put("enabled", node.isEnabled)
                put("checked", node.isChecked)
                
                // 获取元素位置
                val rect = Rect()
                node.getBoundsInScreen(rect)
                put("bounds", JSONObject().apply {
                    put("left", rect.left)
                    put("top", rect.top)
                    put("right", rect.right)
                    put("bottom", rect.bottom)
                    put("center_x", (rect.left + rect.right) / 2)
                    put("center_y", (rect.top + rect.bottom) / 2)
                })
            }
            
            // 只添加有意义的元素
            if (node.isClickable || node.isEditable || 
                !node.text.isNullOrEmpty() || 
                !node.contentDescription.isNullOrEmpty()) {
                elements.put(element)
            }
            
            // 递归遍历子节点
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, elements)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Traverse node failed: ${e.message}")
        }
    }

    // ============================================================================
    // 核心功能：智能查找元素
    // ============================================================================

    /**
     * 根据文本查找元素
     * @param text 要查找的文本（支持部分匹配）
     * @param exact 是否精确匹配
     * @return 找到的节点，如果没找到则返回 null
     */
    fun findElementByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val result = findNodeByText(rootNode, text, exact)
        if (result == null) {
            rootNode.recycle()
        }
        return result
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo, 
        text: String, 
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        
        val matches = if (exact) {
            nodeText == text || nodeDesc == text
        } else {
            nodeText.contains(text, ignoreCase = true) || 
            nodeDesc.contains(text, ignoreCase = true)
        }
        
        if (matches) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeByText(child, text, exact)
                if (found != null) {
                    return found
                }
                child.recycle()
            }
        }
        
        return null
    }

    /**
     * 根据 View ID 查找元素
     */
    fun findElementById(viewId: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val result = findNodeById(rootNode, viewId)
        if (result == null) {
            rootNode.recycle()
        }
        return result
    }

    private fun findNodeById(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == viewId) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findNodeById(child, viewId)
                if (found != null) {
                    return found
                }
                child.recycle()
            }
        }
        
        return null
    }

    // ============================================================================
    // 核心功能：智能点击元素
    // ============================================================================

    /**
     * 根据文本点击元素
     * @param text 要查找的文本
     * @param exact 是否精确匹配
     * @return 是否成功
     */
    suspend fun clickElementByText(text: String, exact: Boolean = false): Boolean {
        return try {
            val element = findElementByText(text, exact)
            if (element == null) {
                Log.w(TAG, "Element not found: $text")
                return false
            }
            
            val success = if (element.isClickable) {
                // 如果元素可点击，直接调用点击
                element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // 如果元素不可点击，使用坐标点击
                val rect = Rect()
                element.getBoundsInScreen(rect)
                val centerX = (rect.left + rect.right) / 2f
                val centerY = (rect.top + rect.bottom) / 2f
                performClick(centerX, centerY)
            }
            
            element.recycle()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Click element by text failed: ${e.message}")
            false
        }
    }

    /**
     * 根据 View ID 点击元素
     */
    suspend fun clickElementById(viewId: String): Boolean {
        return try {
            val element = findElementById(viewId)
            if (element == null) {
                Log.w(TAG, "Element not found: $viewId")
                return false
            }
            
            val success = if (element.isClickable) {
                element.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = Rect()
                element.getBoundsInScreen(rect)
                val centerX = (rect.left + rect.right) / 2f
                val centerY = (rect.top + rect.bottom) / 2f
                performClick(centerX, centerY)
            }
            
            element.recycle()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Click element by id failed: ${e.message}")
            false
        }
    }

    // ============================================================================
    // 核心功能：文本输入
    // ============================================================================

    /**
     * 在可编辑元素中输入文本
     * @param text 要输入的文本
     * @return 是否成功
     */
    fun inputText(element: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (!element.isEditable) {
                Log.w(TAG, "Element is not editable")
                return false
            }
            
            // 先聚焦
            element.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            
            // 输入文本
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            element.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Input text failed: ${e.message}")
            false
        }
    }

    /**
     * 根据文本查找可编辑元素并输入
     */
    fun inputTextByFinder(finderText: String, inputText: String): Boolean {
        val element = findElementByText(finderText) ?: return false
        val success = inputText(element, inputText)
        element.recycle()
        return success
    }

    // ============================================================================
    // 辅助功能
    // ============================================================================

    /**
     * 返回主屏幕
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 返回上一页
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 打开最近任务
     */
    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * 打开通知栏
     */
    fun performNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

     /**
     * 打开快速设置
     */
    fun performQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    // ============================================================================
    // 截图功能
    // ============================================================================
    
    /**
     * 截取屏幕（使用无障碍服务，需要 Android 11+）
     */
    fun takeScreenshot(callback: (org.json.JSONObject) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            screenshotHelper.takeScreenshotWithAccessibility(this) { bitmap ->
                if (bitmap != null) {
                    try {
                        // 转换为 Base64
                        val base64 = screenshotHelper.bitmapToBase64(bitmap, quality = 85)
                        val (width, height) = screenshotHelper.getScreenSize()
                        
                        callback(org.json.JSONObject().apply {
                            put("success", true as Any)
                            put("image", base64 as Any)
                            put("width", width as Any)
                            put("height", height as Any)
                            put("format", "jpeg" as Any)
                            put("timestamp", System.currentTimeMillis() as Any)
                        })
                        
                        bitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process screenshot", e)
                        callback(org.json.JSONObject().apply {
                            put("success", false as Any)
                            put("error", (e.message ?: "Unknown error") as Any)
                        })
                    }
                } else {
                    callback(org.json.JSONObject().apply {
                        put("success", false as Any)
                        put("error", "Failed to take screenshot" as Any)
                    })
                }
            }
        } else {
            callback(org.json.JSONObject().apply {
                put("success", false as Any)
                put("error", "Screenshot requires Android 11+ (API 30+)" as Any)
            })
        }
    }
    
    /**
     * 截取屏幕并保存到文件
     */
    fun takeScreenshotToFile(filePath: String, callback: (org.json.JSONObject) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            screenshotHelper.takeScreenshotWithAccessibility(this) { bitmap ->
                if (bitmap != null) {
                    try {
                        val success = screenshotHelper.saveBitmapToFile(bitmap, filePath)
                        val (width, height) = screenshotHelper.getScreenSize()
                        
                        callback(org.json.JSONObject().apply {
                            put("success", success as Any)
                            put("file_path", filePath as Any)
                            put("width", width as Any)
                            put("height", height as Any)
                            put("timestamp", System.currentTimeMillis() as Any)
                        })
                        
                        bitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save screenshot", e)
                        callback(org.json.JSONObject().apply {
                            put("success", false as Any)
                            put("error", (e.message ?: "Unknown error") as Any)
                        })
                    }
                } else {
                    callback(org.json.JSONObject().apply {
                        put("success", false as Any)
                        put("error", "Failed to take screenshot" as Any)
                    })
                }
            }
        } else {
            callback(org.json.JSONObject().apply {
                put("success", false as Any)
                put("error", "Screenshot requires Android 11+ (API 30+)" as Any)
            })
        }
    }
}
