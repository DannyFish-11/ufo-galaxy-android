package com.ufo.galaxy.surface

import android.content.Context

/**
 * 快捷设置磁贴表面 —— 由 [com.ufo.galaxy.tile.PresenceTileService] 承载。
 * 适配器只负责请求磁贴刷新(TileService.requestListeningState)。
 */
object TileSurface : SurfaceRegistry.PresenceSurface {
    override val name: String = "qs_tile"

    override fun isAvailable(context: Context): Boolean = true

    override fun refresh(context: Context) {
        com.ufo.galaxy.tile.PresenceTileBridge.requestRefresh(context)
    }
}
