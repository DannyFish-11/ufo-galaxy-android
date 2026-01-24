package com.ufo.galaxy

import android.app.Application
import android.util.Log

/**
 * UFO³ Galaxy Android Application
 * 
 * 应用程序入口，负责全局初始化
 * 
 * @author Manus AI
 * @version 1.0
 * @date 2026-01-24
 */
class UFOGalaxyApplication : Application() {
    
    private val TAG = "UFOGalaxyApplication"
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "UFO³ Galaxy Application started")
        
        // 全局初始化
        initializeComponents()
    }
    
    /**
     * 初始化全局组件
     */
    private fun initializeComponents() {
        // 可以在这里初始化全局单例
        // 例如：DeviceManager, WebRTCManager 等
        Log.i(TAG, "Components initialized")
    }
}
