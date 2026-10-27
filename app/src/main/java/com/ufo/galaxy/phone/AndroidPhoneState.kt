package com.ufo.galaxy.phone

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager

/** 从系统读这台手机此刻的状态,交给 [PhoneState.build]。 */
object AndroidPhoneState {

    /** 读系统:都不要权限。哪一样读不到就留 null。 */
    fun read(context: Context): PhoneState.Readings {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        val audio = context.getSystemService(AudioManager::class.java)
        val filter = runCatching { context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()
        return PhoneState.Readings(
            battery = level?.takeIf { it in 0..100 }?.let { it / 100f },
            charging = runCatching { battery?.isCharging }.getOrNull(),
            screenOn = runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull(),
            inCall = runCatching { audio?.mode }.getOrNull()?.let {
                it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
            },
            dnd = filter?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }?.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
    }
}
