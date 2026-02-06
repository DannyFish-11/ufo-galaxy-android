package com.ufo.galaxy.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ufo.galaxy.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

/**
 * UFO Galaxy - 设备状态视图组件
 * 
 * 功能：
 * 1. 显示 Android 设备的各种硬件状态
 * 2. 实时更新状态显示
 * 3. 支持状态变化动画
 * 4. 极简极客风格（黑白渐变）
 * 
 * @author Manus AI
 * @version 2.0
 * @date 2026-02-06
 */
class DeviceStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "DeviceStatusView"
        
        // 状态颜色
        private const val COLOR_ONLINE = "#4CAF50"      // 绿色 - 在线/可用
        private const val COLOR_OFFLINE = "#F44336"     // 红色 - 离线/不可用
        private const val COLOR_WARNING = "#FF9800"     // 橙色 - 警告
        private const val COLOR_DISABLED = "#888888"    // 灰色 - 禁用/不支持
    }
    
    // 设备状态面板
    private var statusPanel: DeviceStatusPanel? = null
    
    // UI 组件
    private lateinit var iconCamera: ImageView
    private lateinit var textCameraStatus: TextView
    private lateinit var iconBluetooth: ImageView
    private lateinit var textBluetoothStatus: TextView
    private lateinit var iconNfc: ImageView
    private lateinit var textNfcStatus: TextView
    private lateinit var iconWifi: ImageView
    private lateinit var textWifiStatus: TextView
    private lateinit var iconAudio: ImageView
    private lateinit var textAudioStatus: TextView
    private lateinit var iconBattery: ImageView
    private lateinit var textBatteryStatus: TextView
    private lateinit var iconServer: ImageView
    private lateinit var textServerStatus: TextView
    private lateinit var iconSensor: ImageView
    private lateinit var textSensorStatus: TextView
    private lateinit var iconNodes: ImageView
    private lateinit var textNodesStatus: TextView
    private lateinit var textLastUpdated: TextView
    
    // 协程
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null
    
    // 服务器连接状态
    var isServerConnected: Boolean = false
        set(value) {
            field = value
            updateServerStatus(value)
        }
    
    // 节点状态
    var activeNodes: Int = 0
    var totalNodes: Int = 113
    
    init {
        orientation = VERTICAL
        initView()
    }
    
    private fun initView() {
        // 加载布局
        LayoutInflater.from(context).inflate(R.layout.layout_device_status, this, true)
        
        // 绑定视图
        bindViews()
        
        // 初始化状态面板
        statusPanel = DeviceStatusPanel(context)
    }
    
    private fun bindViews() {
        iconCamera = findViewById(R.id.icon_camera)
        textCameraStatus = findViewById(R.id.text_camera_status)
        iconBluetooth = findViewById(R.id.icon_bluetooth)
        textBluetoothStatus = findViewById(R.id.text_bluetooth_status)
        iconNfc = findViewById(R.id.icon_nfc)
        textNfcStatus = findViewById(R.id.text_nfc_status)
        iconWifi = findViewById(R.id.icon_wifi)
        textWifiStatus = findViewById(R.id.text_wifi_status)
        iconAudio = findViewById(R.id.icon_audio)
        textAudioStatus = findViewById(R.id.text_audio_status)
        iconBattery = findViewById(R.id.icon_battery)
        textBatteryStatus = findViewById(R.id.text_battery_status)
        iconServer = findViewById(R.id.icon_server)
        textServerStatus = findViewById(R.id.text_server_status)
        iconSensor = findViewById(R.id.icon_sensor)
        textSensorStatus = findViewById(R.id.text_sensor_status)
        iconNodes = findViewById(R.id.icon_nodes)
        textNodesStatus = findViewById(R.id.text_nodes_status)
        textLastUpdated = findViewById(R.id.text_last_updated)
    }
    
    /**
     * 开始监控设备状态
     */
    fun startMonitoring() {
        statusPanel?.startMonitoring()
        
        // 收集状态更新
        updateJob = scope.launch {
            statusPanel?.deviceStatus?.collectLatest { status ->
                updateUI(status)
            }
        }
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        updateJob?.cancel()
        statusPanel?.stopMonitoring()
    }
    
    /**
     * 更新 UI
     */
    private fun updateUI(status: DeviceStatusPanel.DeviceStatus) {
        // 更新摄像头状态
        updateCameraStatus(status.camera)
        
        // 更新蓝牙状态
        updateBluetoothStatus(status.bluetooth)
        
        // 更新 NFC 状态
        updateNfcStatus(status.nfc)
        
        // 更新 WiFi 状态
        updateWifiStatus(status.network)
        
        // 更新音频状态
        updateAudioStatus(status.audio)
        
        // 更新电池状态
        updateBatteryStatus(status.battery)
        
        // 更新传感器状态
        updateSensorStatus(status.sensor)
        
        // 更新节点状态
        updateNodesStatus()
        
        // 更新时间
        updateLastUpdatedTime(status.lastUpdated)
    }
    
    private fun updateCameraStatus(camera: DeviceStatusPanel.CameraStatus) {
        val hasCamera = camera.hasFrontCamera || camera.hasBackCamera
        val statusText = when {
            !hasCamera -> "无摄像头"
            camera.isFrontCameraInUse || camera.isBackCameraInUse -> "使用中"
            else -> "可用"
        }
        
        textCameraStatus.text = statusText
        textCameraStatus.setTextColor(Color.parseColor(
            when {
                !hasCamera -> COLOR_DISABLED
                camera.isFrontCameraInUse || camera.isBackCameraInUse -> COLOR_WARNING
                else -> COLOR_ONLINE
            }
        ))
        
        iconCamera.setColorFilter(Color.parseColor(
            if (hasCamera) COLOR_ONLINE else COLOR_DISABLED
        ))
    }
    
    private fun updateBluetoothStatus(bluetooth: DeviceStatusPanel.BluetoothStatus) {
        val statusText = when {
            !bluetooth.isSupported -> "不支持"
            !bluetooth.isEnabled -> "关闭"
            bluetooth.connectedDevices.isNotEmpty() -> "${bluetooth.connectedDevices.size}已连接"
            else -> "开启"
        }
        
        textBluetoothStatus.text = statusText
        textBluetoothStatus.setTextColor(Color.parseColor(
            when {
                !bluetooth.isSupported -> COLOR_DISABLED
                !bluetooth.isEnabled -> COLOR_OFFLINE
                bluetooth.connectedDevices.isNotEmpty() -> COLOR_ONLINE
                else -> COLOR_WARNING
            }
        ))
        
        iconBluetooth.setColorFilter(Color.parseColor(
            when {
                !bluetooth.isSupported -> COLOR_DISABLED
                bluetooth.isEnabled -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
        ))
    }
    
    private fun updateNfcStatus(nfc: DeviceStatusPanel.NfcStatus) {
        val statusText = when {
            !nfc.isSupported -> "不支持"
            !nfc.isEnabled -> "关闭"
            else -> "开启"
        }
        
        textNfcStatus.text = statusText
        textNfcStatus.setTextColor(Color.parseColor(
            when {
                !nfc.isSupported -> COLOR_DISABLED
                nfc.isEnabled -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
        ))
        
        iconNfc.setColorFilter(Color.parseColor(
            when {
                !nfc.isSupported -> COLOR_DISABLED
                nfc.isEnabled -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
        ))
    }
    
    private fun updateWifiStatus(network: DeviceStatusPanel.NetworkStatus) {
        val statusText = when {
            network.isWifiConnected -> network.wifiSsid ?: "已连接"
            network.isMobileDataConnected -> "移动数据"
            else -> "未连接"
        }
        
        textWifiStatus.text = statusText
        textWifiStatus.setTextColor(Color.parseColor(
            when {
                network.isWifiConnected -> COLOR_ONLINE
                network.isMobileDataConnected -> COLOR_WARNING
                else -> COLOR_OFFLINE
            }
        ))
        
        iconWifi.setColorFilter(Color.parseColor(
            if (network.isWifiConnected || network.isMobileDataConnected) COLOR_ONLINE else COLOR_OFFLINE
        ))
    }
    
    private fun updateAudioStatus(audio: DeviceStatusPanel.AudioStatus) {
        val statusText = when {
            audio.isMuted -> "静音"
            audio.isHeadsetConnected -> "耳机"
            audio.isMicrophoneAvailable -> "正常"
            else -> "不可用"
        }
        
        textAudioStatus.text = statusText
        textAudioStatus.setTextColor(Color.parseColor(
            when {
                audio.isMuted -> COLOR_WARNING
                audio.isMicrophoneAvailable -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
        ))
        
        iconAudio.setColorFilter(Color.parseColor(
            if (audio.isMicrophoneAvailable) COLOR_ONLINE else COLOR_OFFLINE
        ))
    }
    
    private fun updateBatteryStatus(battery: DeviceStatusPanel.BatteryStatus) {
        val statusText = "${battery.level}%" + if (battery.isCharging) " ⚡" else ""
        
        textBatteryStatus.text = statusText
        textBatteryStatus.setTextColor(Color.parseColor(
            when {
                battery.level <= 20 -> COLOR_OFFLINE
                battery.level <= 50 -> COLOR_WARNING
                else -> COLOR_ONLINE
            }
        ))
        
        iconBattery.setColorFilter(Color.parseColor(
            when {
                battery.level <= 20 -> COLOR_OFFLINE
                battery.isCharging -> COLOR_ONLINE
                else -> COLOR_WARNING
            }
        ))
    }
    
    private fun updateServerStatus(isConnected: Boolean) {
        textServerStatus.text = if (isConnected) "已连接" else "未连接"
        textServerStatus.setTextColor(Color.parseColor(
            if (isConnected) COLOR_ONLINE else COLOR_OFFLINE
        ))
        iconServer.setColorFilter(Color.parseColor(
            if (isConnected) COLOR_ONLINE else COLOR_OFFLINE
        ))
    }
    
    private fun updateSensorStatus(sensor: DeviceStatusPanel.SensorStatus) {
        var count = 0
        var total = 0
        
        if (sensor.hasAccelerometer) count++
        total++
        if (sensor.hasGyroscope) count++
        total++
        if (sensor.hasProximity) count++
        total++
        if (sensor.hasLightSensor) count++
        total++
        if (sensor.hasCompass) count++
        total++
        
        textSensorStatus.text = "$count/$total"
        textSensorStatus.setTextColor(Color.parseColor(
            when {
                count == total -> COLOR_ONLINE
                count > 0 -> COLOR_WARNING
                else -> COLOR_OFFLINE
            }
        ))
        
        iconSensor.setColorFilter(Color.parseColor(
            if (count > 0) COLOR_ONLINE else COLOR_DISABLED
        ))
    }
    
    private fun updateNodesStatus() {
        textNodesStatus.text = "$activeNodes/$totalNodes"
        textNodesStatus.setTextColor(Color.parseColor(
            when {
                activeNodes == 0 -> COLOR_DISABLED
                activeNodes < totalNodes / 2 -> COLOR_WARNING
                else -> COLOR_ONLINE
            }
        ))
        
        iconNodes.setColorFilter(Color.parseColor(
            if (activeNodes > 0) COLOR_ONLINE else COLOR_DISABLED
        ))
    }
    
    private fun updateLastUpdatedTime(timestamp: Long) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        textLastUpdated.text = "最后更新: ${sdf.format(Date(timestamp))}"
    }
    
    /**
     * 设置节点状态
     */
    fun setNodesStatus(active: Int, total: Int) {
        activeNodes = active
        totalNodes = total
        updateNodesStatus()
    }
    
    /**
     * 获取设备状态 JSON
     */
    fun getStatusJson(): String {
        return statusPanel?.toJson()?.toString() ?: "{}"
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        statusPanel?.cleanup()
        scope.cancel()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
