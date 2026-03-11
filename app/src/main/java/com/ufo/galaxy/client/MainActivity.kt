package com.ufo.galaxy.client

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.ufo.galaxy.agent.GalaxyAgent
import com.ufo.galaxy.config.AppConfig

class MainActivity : AppCompatActivity() {
    
    private val TAG = "MainActivity"
    private lateinit var galaxyAgent: GalaxyAgent
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "🚀 UFO³ Galaxy Android Agent 启动中...")
        
        // 初始化 Galaxy Agent
        galaxyAgent = GalaxyAgent.getInstance(this)
        
        // Read Gateway URL from assets/config.properties; fall back to a safe default
        // so the agent can start even without a configured server.
        AppConfig.loadConfig(this)
        val configUrl = AppConfig.getString("galaxy.gateway.url", "").trimEnd('/')
        val gatewayUrl = if (configUrl.isNotEmpty()) "$configUrl/ws/agent"
                         else "ws://192.168.1.100:8050/ws/agent"
        
        galaxyAgent.initialize(gatewayUrl)
        galaxyAgent.start()
        
        // 启动悬浮窗服务
        val intent = Intent(this, FloatingWindowService::class.java)
        startService(intent)
        
        Log.i(TAG, "✅ UFO³ Galaxy Android Agent 已启动")
        Log.i(TAG, "   Agent ID: ${galaxyAgent.getStatus().optString("agent_id")}")
        Log.i(TAG, "   Gateway URL: $gatewayUrl")
        
        // 关闭主 Activity（悬浮窗和 Agent 会保持运行）
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 注意：这里不要调用 galaxyAgent.cleanup()
        // 因为 Agent 需要在后台持续运行
    }
}
