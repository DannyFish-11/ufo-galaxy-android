package com.ufo.galaxy.example

import android.content.Context
import com.ufo.galaxy.GalaxyClient
import com.ufo.galaxy.communication.DeviceMessage
import com.ufo.galaxy.communication.CommandMessage
import org.json.JSONObject

/**
 * UFO Galaxy - 使用示例
 */
object UsageExample {
    
    fun basicUsage(context: Context) {
        val client = GalaxyClient.getInstance(context)
        client.initialize()
        
        client.setListener(object : GalaxyClient.Listener {
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onMessage(message: DeviceMessage) {}
            override fun onCommand(command: CommandMessage) {}
            override fun onError(error: String) {}
        })
        
        client.connect("http://100.123.215.126:8888")
        client.sendText("你好，Galaxy！")
    }
}
