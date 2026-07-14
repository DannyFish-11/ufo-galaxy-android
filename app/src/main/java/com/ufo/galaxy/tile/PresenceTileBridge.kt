package com.ufo.galaxy.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * TileSurface → PresenceTileService 的解耦桥。
 * 请求系统重新回调磁贴的 onStartListening 以刷新显示。
 */
object PresenceTileBridge {
    fun requestRefresh(context: Context) {
        try {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context, PresenceTileService::class.java),
            )
        } catch (_: Exception) {
            // 磁贴未添加/系统限流时静默忽略(不影响其他表面)。
        }
    }
}
