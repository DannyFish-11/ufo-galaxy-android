package com.ufo.galaxy.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ufo.galaxy.R
import com.ufo.galaxy.service.EnhancedFloatingService
import com.ufo.galaxy.surface.PresenceState
import com.ufo.galaxy.surface.PresenceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴 —— 下拉即见的系统级 Galaxy 开关(唤醒灵动岛)。
 *
 * active/inactive 跟随连接态;点按启动并展开灵动岛。
 * 磁贴文案跟随在场状态([PresenceStateStore]),口径与小组件一致。
 */
class PresenceTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { applyState(PresenceStateStore.read(this@PresenceTileService)) }
    }

    override fun onClick() {
        super.onClick()
        // 唤醒并展开灵动岛(startForegroundService,稳于广播)。
        startForegroundService(
            Intent(this, EnhancedFloatingService::class.java).apply {
                action = EnhancedFloatingService.ACTION_WAKE_EXPAND
            }
        )
        // 乐观地把磁贴点亮一下(真实态由下次 onStartListening 校正)。
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun applyState(s: PresenceState) {
        val tile = qsTile ?: return
        tile.state = if (s.connected && !s.isStale) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Galaxy"
        // Tile.subtitle 需 API 29(minSdk 26），低版本调用会崩溃 —— 加版本门。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !s.connected -> "未连接"
                s.isStale -> "状态未更新"
                s.statusText.isNotBlank() -> s.statusText
                s.phase == PresenceState.PHASE_MANIFEST -> "执行中"
                s.phase == PresenceState.PHASE_LIMINAL -> "在想"
                else -> "在听"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }
}
