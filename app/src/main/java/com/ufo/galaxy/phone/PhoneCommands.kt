package com.ufo.galaxy.phone

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ufo.galaxy.network.GalaxyWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 把手机的直接动作接到连接上:中心发来的 `command` 交给 [PhoneCommandExecutor],做完回 `command_result`;
 * 能力上报里的动作清单由这里给(只报做得了的,见 [PhoneActionCatalog.reportable])。
 *
 * 没装上之前一个直接动作都不报 —— 报了没人接,中心只会干等。
 */
object PhoneCommands {
    private const val TAG = "PhoneCommands"

    @Volatile
    private var effects: PhoneEffects? = null

    @Volatile
    private var lastReported: List<String> = emptyList()

    /** 现在做得了的直接动作。 */
    fun reportable(): List<String> = effects?.let { fx -> PhoneActionCatalog.reportable(fx::available) } ?: emptyList()

    fun install(client: GalaxyWebSocketClient, context: Context, scope: CoroutineScope) {
        val fx = AndroidPhoneEffects(context.applicationContext)
        effects = fx
        val executor = PhoneCommandExecutor(fx)
        client.phoneActions = { reportable().also { lastReported = it } }
        client.selfDescription = {
            PhoneSelfDescription.build(
                available = fx::available,
                deviceName = android.os.Build.MODEL.orEmpty(),
                model = android.os.Build.MODEL.orEmpty(),
                hostsAgent = com.ufo.galaxy.bridge.BridgeAgentRuntime.BRIDGE_VERSION,
            )
        }
        client.addListener(object : GalaxyWebSocketClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected() = Unit
            override fun onMessage(message: String) = Unit
            override fun onError(error: String) = Unit

            override fun onDeviceCommand(commandId: String, action: String, paramsJson: String) {
                scope.launch(Dispatchers.IO) {
                    val params = runCatching { JsonParser.parseString(paramsJson).asJsonObject }.getOrDefault(JsonObject())
                    val outcome = executor.execute(action, params)
                    Log.i(TAG, "command $action: ${if (outcome.success) "done" else outcome.error}")
                    val frame = DeviceCommandFrames.resultFrame(client.currentDeviceId(), commandId, outcome, System.currentTimeMillis())
                    if (!client.sendJson(frame.toString())) Log.w(TAG, "command_result for $action was not sent")
                }
            }
        })
    }

    /** 人在设置里给了或收回了权限:做得了的动作变了就重报一次能力,中心看到的设备卡跟着变。 */
    fun refreshIfChanged(client: GalaxyWebSocketClient) {
        if (effects == null || reportable() == lastReported) return
        client.refreshCapabilityReport()
    }
}
