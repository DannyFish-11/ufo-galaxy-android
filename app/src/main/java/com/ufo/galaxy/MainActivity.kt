package com.ufo.galaxy

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ufo.galaxy.service.AgentService
import com.ufo.galaxy.service.FloatingWindowService
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button
    private lateinit var floatingWindowButton: Button
    private lateinit var registerDeviceButton: Button
    private lateinit var node50UrlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)
        
        startButton.setOnClickListener {
            startAgentService()
        }
        
        stopButton.setOnClickListener {
            stopAgentService()
        }
        
        testButton.setOnClickListener {
            testAgent()
        }
        
        floatingWindowButton = findViewById(R.id.floatingWindowButton)
        floatingWindowButton.setOnClickListener {
            startFloatingWindow()
        }
        
        registerDeviceButton = findViewById(R.id.registerDeviceButton)
        node50UrlInput = findViewById(R.id.node50UrlInput)
        registerDeviceButton.setOnClickListener {
            registerDevice()
        }
        
        updateStatus()
    }

    private fun startAgentService() {
        val intent = Intent(this, AgentService::class.java)
        startForegroundService(intent)
        updateStatus()
    }

    private fun stopAgentService() {
        val intent = Intent(this, AgentService::class.java)
        stopService(intent)
        updateStatus()
    }

    private fun testAgent() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UFOGalaxyApplication.instance.agentCore.handleTask(
                        "打开相机",
                        mapOf("source" to "ui_test")
                    )
                }
                
                statusText.text = "Test Result:\n${result.toString(2)}"
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "悬浮窗口已启动", Toast.LENGTH_SHORT).show()
    }

    private fun registerDevice() {
        val node50Url = node50UrlInput.text.toString().ifEmpty { "http://192.168.1.100:8050" }
        
        scope.launch {
            try {
                statusText.text = "正在注册设备到 $node50Url..."
                
                val success = withContext(Dispatchers.IO) {
                    UFOGalaxyApplication.instance.agentCore.registerToGalaxy(node50Url)
                }
                
                if (success) {
                    val deviceId = UFOGalaxyApplication.instance.agentCore.getDeviceId()
                    statusText.text = "设备注册成功！\nDevice ID: $deviceId\nNode 50 URL: $node50Url"
                    Toast.makeText(this@MainActivity, "设备注册成功", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.text = "设备注册失败，请检查 Node 50 URL 是否正确"
                    Toast.makeText(this@MainActivity, "设备注册失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                statusText.text = "注册错误: ${e.message}"
                Toast.makeText(this@MainActivity, "注册错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus() {
        val agentCore = UFOGalaxyApplication.instance.agentCore
        val status = agentCore.getNodesStatus()
        statusText.text = "Agent Status:\n${status.toString(2)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
