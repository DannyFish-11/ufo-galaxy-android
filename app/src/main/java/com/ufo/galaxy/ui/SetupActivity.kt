package com.ufo.galaxy.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ufo.galaxy.MainActivity
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.config.GalaxyConfig
import com.ufo.galaxy.service.FloatingWindowService
import kotlinx.coroutines.*

/**
 * UFO Galaxy 设置引导 Activity
 * ============================
 * 
 * 首次启动时引导用户完成配置：
 * 1. 服务器地址配置
 * 2. 权限申请
 * 3. 功能开关设置
 * 
 * 作者：Manus AI
 * 日期：2026-02-06
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_OVERLAY = 1002
        private const val REQUEST_ACCESSIBILITY = 1003
    }

    private lateinit var config: GalaxyConfig
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // UI 组件
    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnStartService: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var progressBar: ProgressBar
    
    // 权限状态
    private lateinit var tvCameraPermission: TextView
    private lateinit var tvMicrophonePermission: TextView
    private lateinit var tvOverlayPermission: TextView
    private lateinit var tvAccessibilityPermission: TextView
    
    // 功能开关
    private lateinit var switchCamera: Switch
    private lateinit var switchMicrophone: Switch
    private lateinit var switchBluetooth: Switch
    private lateinit var switchNfc: Switch
    private lateinit var switchFloatingWindow: Switch
    private lateinit var switchAutoStart: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 检查是否已配置
        config = GalaxyConfig.getInstance(this)
        if (config.getServerUrl().isNotEmpty() && hasRequiredPermissions()) {
            // 已配置，直接进入主界面
            startMainActivity()
            return
        }
        
        setContentView(R.layout.activity_setup)
        
        initViews()
        loadConfig()
        checkPermissions()
    }
    
    private fun initViews() {
        // 服务器配置
        etServerUrl = findViewById(R.id.et_server_url)
        etDeviceName = findViewById(R.id.et_device_name)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnStartService = findViewById(R.id.btn_start_service)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        progressBar = findViewById(R.id.progress_bar)
        
        // 权限状态
        tvCameraPermission = findViewById(R.id.tv_camera_permission)
        tvMicrophonePermission = findViewById(R.id.tv_microphone_permission)
        tvOverlayPermission = findViewById(R.id.tv_overlay_permission)
        tvAccessibilityPermission = findViewById(R.id.tv_accessibility_permission)
        
        // 功能开关
        switchCamera = findViewById(R.id.switch_camera)
        switchMicrophone = findViewById(R.id.switch_microphone)
        switchBluetooth = findViewById(R.id.switch_bluetooth)
        switchNfc = findViewById(R.id.switch_nfc)
        switchFloatingWindow = findViewById(R.id.switch_floating_window)
        switchAutoStart = findViewById(R.id.switch_auto_start)
        
        // 设置监听器
        btnTestConnection.setOnClickListener { testConnection() }
        btnStartService.setOnClickListener { startService() }
        
        // 权限点击
        tvCameraPermission.setOnClickListener { requestCameraPermission() }
        tvMicrophonePermission.setOnClickListener { requestMicrophonePermission() }
        tvOverlayPermission.setOnClickListener { requestOverlayPermission() }
        tvAccessibilityPermission.setOnClickListener { requestAccessibilityPermission() }
        
        // 功能开关监听
        switchCamera.setOnCheckedChangeListener { _, checked -> config.setCameraEnabled(checked) }
        switchMicrophone.setOnCheckedChangeListener { _, checked -> config.setMicrophoneEnabled(checked) }
        switchBluetooth.setOnCheckedChangeListener { _, checked -> config.setBluetoothEnabled(checked) }
        switchNfc.setOnCheckedChangeListener { _, checked -> config.setNfcEnabled(checked) }
        switchFloatingWindow.setOnCheckedChangeListener { _, checked -> 
            config.setFloatingWindowEnabled(checked)
            if (checked && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            }
        }
        switchAutoStart.setOnCheckedChangeListener { _, checked -> config.setAutoStartEnabled(checked) }
    }
    
    private fun loadConfig() {
        etServerUrl.setText(config.getServerUrl())
        etDeviceName.setText(config.getDeviceName())
        
        switchCamera.isChecked = config.isCameraEnabled()
        switchMicrophone.isChecked = config.isMicrophoneEnabled()
        switchBluetooth.isChecked = config.isBluetoothEnabled()
        switchNfc.isChecked = config.isNfcEnabled()
        switchFloatingWindow.isChecked = config.isFloatingWindowEnabled()
        switchAutoStart.isChecked = config.isAutoStartEnabled()
    }
    
    private fun checkPermissions() {
        // 相机权限
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        updatePermissionStatus(tvCameraPermission, cameraGranted)
        
        // 麦克风权限
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        updatePermissionStatus(tvMicrophonePermission, micGranted)
        
        // 悬浮窗权限
        val overlayGranted = Settings.canDrawOverlays(this)
        updatePermissionStatus(tvOverlayPermission, overlayGranted)
        
        // 无障碍权限
        val accessibilityGranted = isAccessibilityServiceEnabled()
        updatePermissionStatus(tvAccessibilityPermission, accessibilityGranted)
    }
    
    private fun updatePermissionStatus(textView: TextView, granted: Boolean) {
        if (granted) {
            textView.text = "✓ 已授权"
            textView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            textView.text = "✗ 点击授权"
            textView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }
    
    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSIONS)
    }
    
    private fun requestMicrophonePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
    }
    
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY)
        }
    }
    
    private fun requestAccessibilityPermission() {
        AlertDialog.Builder(this)
            .setTitle("启用无障碍服务")
            .setMessage("UFO Galaxy 需要无障碍服务来控制系统界面。请在设置中找到 'UFO Galaxy' 并启用。")
            .setPositiveButton("前往设置") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, REQUEST_ACCESSIBILITY)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/com.ufo.galaxy.service.UFOAccessibilityService"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(serviceName) == true
    }
    
    private fun testConnection() {
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        progressBar.visibility = View.VISIBLE
        btnTestConnection.isEnabled = false
        tvConnectionStatus.text = "正在连接..."
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        
        scope.launch {
            try {
                // 保存配置
                config.setServerUrl(serverUrl)
                config.setDeviceName(etDeviceName.text.toString().trim())
                
                // 测试连接
                val coreManager = UFOGalaxyApplication.getInstance().getCoreManager()
                val result = coreManager.initialize()
                
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        tvConnectionStatus.text = "✓ 连接成功"
                        tvConnectionStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, android.R.color.holo_green_dark))
                        btnStartService.isEnabled = true
                    } else {
                        tvConnectionStatus.text = "✗ 连接失败: ${result.exceptionOrNull()?.message}"
                        tvConnectionStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, android.R.color.holo_red_dark))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "✗ 连接失败: ${e.message}"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this@SetupActivity, android.R.color.holo_red_dark))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnTestConnection.isEnabled = true
                }
            }
        }
    }
    
    private fun startService() {
        // 保存配置
        config.setServerUrl(etServerUrl.text.toString().trim())
        config.setDeviceName(etDeviceName.text.toString().trim())
        
        // 启动悬浮窗服务
        if (config.isFloatingWindowEnabled() && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        
        // 进入主界面
        startMainActivity()
    }
    
    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            checkPermissions()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY, REQUEST_ACCESSIBILITY -> checkPermissions()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
