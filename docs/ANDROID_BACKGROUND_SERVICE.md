# Android Background Service for 24/7 Operation

This document describes how to implement 24/7 background operation for the UFO Galaxy Android client.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android System                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │           UFO Galaxy Background Service               │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │  │
│  │  │ Foreground   │  │ WebSocket    │  │ Heartbeat  │ │  │
│  │  │ Service      │  │ Client       │  │ Manager    │ │  │
│  │  └──────────────┘  └──────────────┘  └────────────┘ │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                  │
│  ┌────────────────────────┼──────────────────────────────┐  │
│  │           WorkManager  │ (Periodic Tasks)              │  │
│  │  ┌─────────────────────┼──────────────────────────┐  │  │
│  │  │ Health Check Worker │ Reconnection Worker      │  │  │
│  │  └─────────────────────┴──────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
│                           │                                  │
│  ┌────────────────────────┼──────────────────────────────┐  │
│  │           AlarmManager │ (Keep Alive)                  │  │
│  └────────────────────────┴──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Implementation

### 1. Foreground Service

```kotlin
// UFOForegroundService.kt
class UFOForegroundService : Service() {
    
    companion object {
        const val CHANNEL_ID = "ufo_galaxy_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_SERVICE"
        const val ACTION_STOP = "STOP_SERVICE"
    }
    
    private lateinit var webSocketClient: EnhancedWebSocketClient
    private lateinit var heartbeatManager: HeartbeatManager
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
        }
        
        // Restart if killed
        return START_STICKY
    }
    
    private fun startService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Initialize WebSocket
        webSocketClient = EnhancedWebSocketClient()
        webSocketClient.connect()
        
        // Start heartbeat
        heartbeatManager = HeartbeatManager(webSocketClient)
        heartbeatManager.start()
        
        // Schedule keep-alive
        scheduleKeepAlive()
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO Galaxy")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "UFO Galaxy Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
    
    private fun scheduleKeepAlive() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, KeepAliveReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60000,
            60000,
            pendingIntent
        )
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
```

### 2. Heartbeat Manager

```kotlin
// HeartbeatManager.kt
class HeartbeatManager(
    private val webSocketClient: EnhancedWebSocketClient
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    
    fun start() {
        job = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(30000) // 30 seconds
            }
        }
    }
    
    fun stop() {
        job?.cancel()
    }
    
    private suspend fun sendHeartbeat() {
        val message = AIPMessage(
            type = MessageType.HEARTBEAT,
            payload = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "device_id" to getDeviceId(),
                "battery" to getBatteryLevel(),
                "network" to getNetworkType()
            )
        )
        webSocketClient.send(message)
    }
}
```

### 3. WorkManager for Periodic Tasks

```kotlin
// HealthCheckWorker.kt
class HealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        // Check WebSocket connection
        if (!WebSocketClient.isConnected()) {
            WebSocketClient.reconnect()
        }
        
        // Check service status
        if (!isServiceRunning(UFOForegroundService::class.java)) {
            startForegroundService()
        }
        
        // Report health status
        reportHealthStatus()
        
        return Result.success()
    }
    
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
                15, TimeUnit.MINUTES
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "health_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
```

### 4. Boot Receiver

```kotlin
// BootReceiver.kt
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start foreground service
            val serviceIntent = Intent(context, UFOForegroundService::class.java).apply {
                action = UFOForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            
            // Schedule health checks
            HealthCheckWorker.schedule(context)
        }
    }
}
```

### 5. Keep Alive Receiver

```kotlin
// KeepAliveReceiver.kt
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ensure service is running
        if (!isServiceRunning(context, UFOForegroundService::class.java)) {
            val serviceIntent = Intent(context, UFOForegroundService::class.java).apply {
                action = UFOForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
```

## AndroidManifest.xml Updates

```xml
<manifest>
    <!-- Permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    
    <application>
        <!-- Foreground Service -->
        <service
            android:name=".service.UFOForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        
        <!-- Boot Receiver -->
        <receiver
            android:name=".receiver.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
        
        <!-- Keep Alive Receiver -->
        <receiver
            android:name=".receiver.KeepAliveReceiver"
            android:enabled="true"
            android:exported="false" />
    </application>
</manifest>
```

## Battery Optimization

```kotlin
// BatteryOptimizationHelper.kt
object BatteryOptimizationHelper {
    
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        }
    }
}
```

## Usage

```kotlin
// Start 24/7 service
UFOForegroundService.start(context)

// Schedule health checks
HealthCheckWorker.schedule(context)

// Request battery optimization exemption
BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context)
```

## Features

- ✅ Foreground service with persistent notification
- ✅ Automatic restart on boot
- ✅ WebSocket connection with auto-reconnect
- ✅ Heartbeat every 30 seconds
- ✅ Health checks every 15 minutes
- ✅ Keep-alive alarms every minute
- ✅ Battery optimization handling

## Server Alignment

| Feature | Android | Server | Status |
|---------|---------|--------|--------|
| Heartbeat | ✅ 30s interval | ✅ handle_heartbeat() | Aligned |
| Auto-reconnect | ✅ Exponential backoff | ✅ Connection retry | Aligned |
| Health check | ✅ WorkManager | ✅ Health monitor | Aligned |
| Boot start | ✅ BootReceiver | ✅ Systemd service | Aligned |
