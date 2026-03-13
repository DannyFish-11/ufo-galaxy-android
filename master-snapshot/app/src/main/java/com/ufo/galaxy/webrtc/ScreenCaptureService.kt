package com.ufo.galaxy.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that satisfies the Android 14+ requirement of having a
 * running foreground service with `foregroundServiceType="mediaProjection"`
 * before [android.media.projection.MediaProjectionManager.getMediaProjection]
 * is called.
 *
 * Lifecycle:
 * 1. Started by [WebRTCManager] with [ACTION_START], carrying [EXTRA_RESULT_CODE]
 *    and [EXTRA_DATA] (the MediaProjection permission Intent).
 * 2. Calls [android.app.Service.startForeground] to establish the foreground
 *    notification required by Android 14+.
 * 3. Immediately notifies [WebRTCManager.onCaptureServiceReady] so the manager
 *    can create a [org.webrtc.ScreenCapturerAndroid] while the foreground
 *    service is active.  The capturer internally calls
 *    [android.media.projection.MediaProjectionManager.getMediaProjection] and
 *    creates the [android.hardware.display.VirtualDisplay] that feeds frames
 *    into the WebRTC [org.webrtc.VideoSource].
 * 4. On [ACTION_STOP] the service stops itself; [WebRTCManager] is responsible
 *    for stopping the capturer before sending [ACTION_STOP].
 */
class ScreenCaptureService : Service() {

    private val TAG = "ScreenCaptureService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "screen_capture_channel"

    companion object {
        const val ACTION_START = "com.ufo.galaxy.webrtc.START_CAPTURE"
        const val ACTION_STOP = "com.ufo.galaxy.webrtc.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun getInstance(): ScreenCaptureService? = instance

        fun isRunning(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    // The foreground service is now active. Notify WebRTCManager so it
                    // can safely call MediaProjectionManager.getMediaProjection() via
                    // ScreenCapturerAndroid, which requires the foreground service to
                    // be running on Android 14+.
                    WebRTCManager.getInstance(applicationContext).onCaptureServiceReady(data)
                } else {
                    Log.e(TAG, "ACTION_START missing result_code or data; stopping")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ScreenCaptureService destroyed")
    }

    /**
     * Create the notification channel required for foreground services on
     * Android 8.0 (Oreo) and higher.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕采集服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebRTC 屏幕采集正在运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Build the persistent notification shown while screen capture is active.
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO³ Galaxy")
            .setContentText("屏幕采集中...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

