package com.ufo.galaxy.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.shared.protocol.call.CallState
import com.ufo.galaxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 通话期间的前台服务。
 *
 * 为什么必须有它
 * ------------
 * 熄屏、切到别的应用就是"应用进入后台"。Android 14 起,后台访问麦克风必须有一个
 * ``foregroundServiceType="microphone"`` 的前台服务在跑 —— 否则麦克风会被系统静音,
 * 表现是**看着屏幕时能说话,一熄屏 AI 就听不见了**,而且不报任何错。
 *
 * 常驻的连接服务顶不上:它是 ``specialUse`` 型,拿不到麦克风豁免;而把麦克风类型永久加到
 * 它身上,等于手机上常年挂着一个录音指示灯。所以通话期间单起一个,挂断就停。(和手表
 * galaxy-wearos ``VoiceCallService`` 同一套。)
 *
 * 通话本体(``PhoneCallController``)也**住在这里**而不是界面里:通话要活过熄屏和
 * 划走界面,而 Compose 的作用域两者都活不过。
 */
class PhoneCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 通话期间收网关下行的通话信令(见 [GalaxyWebSocketClient.Listener.onVoiceCallFrame])。 */
    private var frames: GalaxyWebSocketClient.Listener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            _controller.value?.hangup("user_hangup")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        if (_controller.value != null) return START_NOT_STICKY // 已经在通话里

        // 新的一通电话开始，上一通的失败提示不再有意义。
        _lastFailure.value = null

        val client = runCatching { UFOGalaxyApplication.webSocketClient }.getOrNull()
        if (client == null || !client.isConnected()) {
            Log.w(TAG, "网关未连接,通话无法建立")
            _lastFailure.value = "手机还没连上网关，先打开跨设备连上再拨"
            stopSelf()
            return START_NOT_STICKY
        }

        val controller = PhoneCallController(
            context = applicationContext,
            deviceId = client.currentDeviceId(),
            gateway = client,
        )
        _controller.value = controller

        // 通话信令在这里单独收一份:连接上的监听者可以有多个,不跟别处抢。挂断时摘掉。
        val listener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected() {
                scope.launch { controller.hangup("connection_closed") }
            }
            override fun onMessage(message: String) = Unit
            override fun onError(error: String) = Unit
            override fun onVoiceCallFrame(frameJson: String) {
                scope.launch { controller.onGatewayFrame(frameJson) }
            }
        }
        frames = listener
        client.addListener(listener)
        scope.launch {
            controller.ui.collect { if (it.state == CallState.ENDED) stopSelf() }
        }

        if (!controller.dial()) {
            // dial() 已经把原因写进 ui.endedReason,界面会显示。这里只负责收摊。
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // dispose() 会把结束原因覆盖成「已释放」，所以要在它之前取。服务一停控制器就被置空，
        // 界面再也读不到原因 —— 失败提示靠这一份留下来。
        _controller.value?.let { c ->
            val ui = c.ui.value
            if (ui.state == CallState.ENDED) CallEndNotice.of(ui.endedReason)?.let { _lastFailure.value = it }
        }
        _controller.value?.dispose()
        _controller.value = null
        frames?.let { l -> runCatching { UFOGalaxyApplication.webSocketClient.removeListener(l) } }
        frames = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时通话", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "与智能体通话期间保持麦克风可用"
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getService(
            this,
            1,
            Intent(this, PhoneCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通话中")
            .setContentText("与智能体实时通话")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", hangup)
            .build()

        // 类型必须在 startForeground 时也报一遍,只写清单不够 —— 少了这一步,
        // Android 14 上后台麦克风照样被静音。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "Galaxy.PhoneCallSvc"
        private const val CHANNEL_ID = "galaxy_phone_call"
        private const val NOTIFICATION_ID = 43_001
        const val ACTION_HANGUP = "com.ufo.galaxy.CALL_HANGUP"

        private val _controller = MutableStateFlow<PhoneCallController?>(null)

        /** 当前这通电话。没有通话时是 null。界面据此渲染,不自己持有控制器。 */
        val controller: StateFlow<PhoneCallController?> = _controller.asStateFlow()

        private val _lastFailure = MutableStateFlow<String?>(null)

        /** 上一通电话为什么没打成 / 为什么断了。正常结束或新一通开始后为 null。 */
        val lastFailure: StateFlow<String?> = _lastFailure.asStateFlow()

        fun clearFailure() {
            _lastFailure.value = null
        }

        /** 起一通电话。必须从可见界面调 —— 后台起麦克风型前台服务会被系统拒绝。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhoneCallService::class.java))
        }

        /** 挂断。走服务而不是直接调控制器,保证服务也跟着停掉。 */
        fun hangup(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneCallService::class.java).setAction(ACTION_HANGUP),
            )
        }
    }
}
