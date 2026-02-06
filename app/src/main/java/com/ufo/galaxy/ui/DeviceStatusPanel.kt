package com.ufo.galaxy.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * UFO Galaxy - 设备状态面板
 * 
 * 功能：
 * 1. 实时监控 Android 设备的各种硬件状态
 * 2. 提供统一的状态数据接口
 * 3. 支持状态变化通知
 * 4. 可扩展的设备状态监控
 * 
 * 监控的设备状态：
 * - 摄像头（前置/后置）
 * - 蓝牙（开关状态、已连接设备）
 * - NFC（启用状态）
 * - 音频（麦克风、扬声器）
 * - 网络（WiFi、移动数据）
 * - 电池（电量、充电状态）
 * - 传感器（加速度计、陀螺仪等）
 * 
 * @author Manus AI
 * @version 2.0
 * @date 2026-02-06
 */
class DeviceStatusPanel(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceStatusPanel"
        private const val UPDATE_INTERVAL = 5000L // 5秒更新一次
    }
    
    // 状态数据类
    data class CameraStatus(
        val hasFrontCamera: Boolean = false,
        val hasBackCamera: Boolean = false,
        val isFrontCameraInUse: Boolean = false,
        val isBackCameraInUse: Boolean = false
    )
    
    data class BluetoothStatus(
        val isEnabled: Boolean = false,
        val isSupported: Boolean = false,
        val connectedDevices: List<String> = emptyList(),
        val pairedDevices: List<String> = emptyList()
    )
    
    data class NfcStatus(
        val isSupported: Boolean = false,
        val isEnabled: Boolean = false
    )
    
    data class AudioStatus(
        val isMicrophoneAvailable: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isHeadsetConnected: Boolean = false,
        val currentVolume: Int = 0,
        val maxVolume: Int = 0,
        val isMuted: Boolean = false
    )
    
    data class NetworkStatus(
        val isWifiConnected: Boolean = false,
        val wifiSsid: String? = null,
        val wifiSignalStrength: Int = 0,
        val isMobileDataConnected: Boolean = false,
        val mobileNetworkType: String? = null,
        val isServerConnected: Boolean = false
    )
    
    data class BatteryStatus(
        val level: Int = 0,
        val isCharging: Boolean = false,
        val chargingType: String = "unknown",
        val temperature: Float = 0f
    )
    
    data class SensorStatus(
        val hasAccelerometer: Boolean = false,
        val hasGyroscope: Boolean = false,
        val hasProximity: Boolean = false,
        val hasLightSensor: Boolean = false,
        val hasCompass: Boolean = false
    )
    
    data class DeviceStatus(
        val camera: CameraStatus = CameraStatus(),
        val bluetooth: BluetoothStatus = BluetoothStatus(),
        val nfc: NfcStatus = NfcStatus(),
        val audio: AudioStatus = AudioStatus(),
        val network: NetworkStatus = NetworkStatus(),
        val battery: BatteryStatus = BatteryStatus(),
        val sensor: SensorStatus = SensorStatus(),
        val lastUpdated: Long = System.currentTimeMillis()
    )
    
    // 状态流
    private val _deviceStatus = MutableStateFlow(DeviceStatus())
    val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus
    
    // 系统服务
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    
    private val nfcManager: NfcManager? by lazy {
        context.getSystemService(Context.NFC_SERVICE) as? NfcManager
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    
    private val batteryManager: BatteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var updateJob: Job? = null
    
    // 广播接收器
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryStatus()
        }
    }
    
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBluetoothStatus()
        }
    }
    
    /**
     * 启动状态监控
     */
    fun startMonitoring() {
        Log.i(TAG, "Starting device status monitoring")
        
        // 注册广播接收器
        registerReceivers()
        
        // 启动定期更新
        updateJob = scope.launch {
            while (isActive) {
                updateAllStatus()
                delay(UPDATE_INTERVAL)
            }
        }
    }
    
    /**
     * 停止状态监控
     */
    fun stopMonitoring() {
        Log.i(TAG, "Stopping device status monitoring")
        updateJob?.cancel()
        unregisterReceivers()
    }
    
    /**
     * 注册广播接收器
     */
    private fun registerReceivers() {
        try {
            context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            
            context.registerReceiver(
                bluetoothReceiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers: ${e.message}")
        }
    }
    
    /**
     * 注销广播接收器
     */
    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(batteryReceiver)
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receivers: ${e.message}")
        }
    }
    
    /**
     * 更新所有状态
     */
    private fun updateAllStatus() {
        val newStatus = DeviceStatus(
            camera = getCameraStatus(),
            bluetooth = getBluetoothStatus(),
            nfc = getNfcStatus(),
            audio = getAudioStatus(),
            network = getNetworkStatus(),
            battery = getBatteryStatus(),
            sensor = getSensorStatus(),
            lastUpdated = System.currentTimeMillis()
        )
        _deviceStatus.value = newStatus
    }
    
    /**
     * 获取摄像头状态
     */
    private fun getCameraStatus(): CameraStatus {
        return try {
            val cameraIds = cameraManager.cameraIdList
            var hasFront = false
            var hasBack = false
            
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                when (facing) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> hasFront = true
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> hasBack = true
                }
            }
            
            CameraStatus(
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera status: ${e.message}")
            CameraStatus()
        }
    }
    
    /**
     * 获取蓝牙状态
     */
    private fun getBluetoothStatus(): BluetoothStatus {
        return try {
            val adapter = bluetoothManager?.adapter
            
            if (adapter == null) {
                return BluetoothStatus(isSupported = false)
            }
            
            val pairedDevices = if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                adapter.bondedDevices?.map { it.name ?: it.address } ?: emptyList()
            } else {
                emptyList()
            }
            
            BluetoothStatus(
                isSupported = true,
                isEnabled = adapter.isEnabled,
                pairedDevices = pairedDevices
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bluetooth status: ${e.message}")
            BluetoothStatus()
        }
    }
    
    private fun updateBluetoothStatus() {
        val currentStatus = _deviceStatus.value
        _deviceStatus.value = currentStatus.copy(
            bluetooth = getBluetoothStatus(),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取 NFC 状态
     */
    private fun getNfcStatus(): NfcStatus {
        return try {
            val nfcAdapter = nfcManager?.defaultAdapter
            NfcStatus(
                isSupported = nfcAdapter != null,
                isEnabled = nfcAdapter?.isEnabled ?: false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NFC status: ${e.message}")
            NfcStatus()
        }
    }
    
    /**
     * 获取音频状态
     */
    private fun getAudioStatus(): AudioStatus {
        return try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            
            AudioStatus(
                isMicrophoneAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                isSpeakerOn = audioManager.isSpeakerphoneOn,
                isHeadsetConnected = audioManager.isWiredHeadsetOn,
                currentVolume = currentVolume,
                maxVolume = maxVolume,
                isMuted = currentVolume == 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get audio status: ${e.message}")
            AudioStatus()
        }
    }
    
    /**
     * 获取网络状态
     */
    private fun getNetworkStatus(): NetworkStatus {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
            
            var wifiSsid: String? = null
            var wifiSignalStrength = 0
            
            if (isWifi) {
                val wifiInfo = wifiManager.connectionInfo
                wifiSsid = wifiInfo?.ssid?.replace("\"", "")
                wifiSignalStrength = WifiManager.calculateSignalLevel(wifiInfo?.rssi ?: -100, 5)
            }
            
            NetworkStatus(
                isWifiConnected = isWifi,
                wifiSsid = wifiSsid,
                wifiSignalStrength = wifiSignalStrength,
                isMobileDataConnected = isCellular
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network status: ${e.message}")
            NetworkStatus()
        }
    }
    
    /**
     * 获取电池状态
     */
    private fun getBatteryStatus(): BatteryStatus {
        return try {
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            
            BatteryStatus(
                level = level,
                isCharging = isCharging
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery status: ${e.message}")
            BatteryStatus()
        }
    }
    
    private fun updateBatteryStatus() {
        val currentStatus = _deviceStatus.value
        _deviceStatus.value = currentStatus.copy(
            battery = getBatteryStatus(),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取传感器状态
     */
    private fun getSensorStatus(): SensorStatus {
        return try {
            val pm = context.packageManager
            SensorStatus(
                hasAccelerometer = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER),
                hasGyroscope = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE),
                hasProximity = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_PROXIMITY),
                hasLightSensor = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_LIGHT),
                hasCompass = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sensor status: ${e.message}")
            SensorStatus()
        }
    }
    
    /**
     * 转换为 JSON 格式（用于发送到服务器）
     */
    fun toJson(): JSONObject {
        val status = _deviceStatus.value
        return JSONObject().apply {
            put("camera", JSONObject().apply {
                put("has_front_camera", status.camera.hasFrontCamera)
                put("has_back_camera", status.camera.hasBackCamera)
                put("is_front_camera_in_use", status.camera.isFrontCameraInUse)
                put("is_back_camera_in_use", status.camera.isBackCameraInUse)
            })
            put("bluetooth", JSONObject().apply {
                put("is_supported", status.bluetooth.isSupported)
                put("is_enabled", status.bluetooth.isEnabled)
                put("paired_devices", JSONArray(status.bluetooth.pairedDevices))
                put("connected_devices", JSONArray(status.bluetooth.connectedDevices))
            })
            put("nfc", JSONObject().apply {
                put("is_supported", status.nfc.isSupported)
                put("is_enabled", status.nfc.isEnabled)
            })
            put("audio", JSONObject().apply {
                put("is_microphone_available", status.audio.isMicrophoneAvailable)
                put("is_speaker_on", status.audio.isSpeakerOn)
                put("is_headset_connected", status.audio.isHeadsetConnected)
                put("current_volume", status.audio.currentVolume)
                put("max_volume", status.audio.maxVolume)
                put("is_muted", status.audio.isMuted)
            })
            put("network", JSONObject().apply {
                put("is_wifi_connected", status.network.isWifiConnected)
                put("wifi_ssid", status.network.wifiSsid)
                put("wifi_signal_strength", status.network.wifiSignalStrength)
                put("is_mobile_data_connected", status.network.isMobileDataConnected)
                put("is_server_connected", status.network.isServerConnected)
            })
            put("battery", JSONObject().apply {
                put("level", status.battery.level)
                put("is_charging", status.battery.isCharging)
                put("charging_type", status.battery.chargingType)
            })
            put("sensor", JSONObject().apply {
                put("has_accelerometer", status.sensor.hasAccelerometer)
                put("has_gyroscope", status.sensor.hasGyroscope)
                put("has_proximity", status.sensor.hasProximity)
                put("has_light_sensor", status.sensor.hasLightSensor)
                put("has_compass", status.sensor.hasCompass)
            })
            put("last_updated", status.lastUpdated)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        scope.cancel()
    }
}
