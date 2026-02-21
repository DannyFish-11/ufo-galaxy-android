package com.ufo.galaxy.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 截图辅助类
 * 
 * 支持两种截图方式：
 * 1. MediaProjection（需要用户授权，可以截取整个屏幕）
 * 2. AccessibilityService（无需授权，但需要启用无障碍服务）
 */
class ScreenshotHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenshotHelper"
        private const val VIRTUAL_DISPLAY_NAME = "UFO_Screenshot"
    }
    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayMetrics = DisplayMetrics()
    
    init {
        windowManager.defaultDisplay.getMetrics(displayMetrics)
    }
    
    /**
     * 使用 MediaProjection 截图（需要用户授权）
     */
    fun takeScreenshotWithMediaProjection(
        mediaProjection: MediaProjection,
        callback: (Bitmap?) -> Unit
    ) {
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi
        
        val imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
        
        val virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
        
        // 延迟一下，确保画面稳定
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    callback(bitmap)
                } else {
                    Log.w(TAG, "Failed to acquire image")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to take screenshot", e)
                callback(null)
            } finally {
                virtualDisplay.release()
                imageReader.close()
            }
        }, 100)
    }
    
    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // 裁剪掉多余的部分
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }
    
    /**
     * 保存 Bitmap 到文件
     */
    fun saveBitmapToFile(bitmap: Bitmap, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            Log.d(TAG, "Screenshot saved to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            false
        }
    }
    
    /**
     * 将 Bitmap 转换为 Base64
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 90): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * 使用无障碍服务截图（需要 Android 9+）
     */
    fun takeScreenshotWithAccessibility(
        accessibilityService: android.accessibilityservice.AccessibilityService,
        callback: (Bitmap?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 支持无障碍服务截图
            accessibilityService.takeScreenshot(
                0,  // TAKE_SCREENSHOT_FULL_SCREEN = 0
                { command -> command.run() },
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                            hardwareBuffer.close()
                            callback(bitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to convert screenshot", e)
                            callback(null)
                        }
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            Log.w(TAG, "Accessibility screenshot requires Android 11+")
            callback(null)
        }
    }
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
}
