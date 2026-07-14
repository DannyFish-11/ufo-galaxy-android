package com.ufo.galaxy.surface

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 在场状态源(单一属主)—— DataStore 持久化,进程内外皆可读。
 *
 * 用 DataStore 而非内存 StateFlow 的原因:小组件(Glance)在独立的
 * 渲染时机取数,磁贴服务(TileService)可能在主进程未活跃时被系统拉起,
 * 都需要"离进程"可读的状态;DataStore 同时给到 Flow 观察能力。
 *
 * 写入方:GalaxyConnectionService(连接/断开)、PhaseStateMachine 桥接
 * (相位变化)、灵动岛(状态文本)。写入后主动通知各表面刷新
 * ([SurfaceRegistry.notifyAll]),表面按需拉新快照。
 */
object PresenceStateStore {
    private const val TAG = "PresenceStateStore"
    private val Context.presenceDataStore by preferencesDataStore(name = "presence_state")

    private val KEY_PHASE = stringPreferencesKey("phase")
    private val KEY_CONNECTED = booleanPreferencesKey("connected")
    private val KEY_STATUS_TEXT = stringPreferencesKey("status_text")
    private val KEY_UPDATED_AT = longPreferencesKey("updated_at_ms")

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun flow(context: Context): Flow<PresenceState> =
        context.applicationContext.presenceDataStore.data.map { p ->
            PresenceState(
                phase = p[KEY_PHASE] ?: PresenceState.PHASE_SILENT,
                connected = p[KEY_CONNECTED] ?: false,
                statusText = p[KEY_STATUS_TEXT] ?: "",
                updatedAtMs = p[KEY_UPDATED_AT] ?: 0L,
            )
        }

    suspend fun read(context: Context): PresenceState = flow(context).first()

    /**
     * 部分更新(null = 保持原值),写毕通知全部表面刷新。
     * fire-and-forget 版本,调用方无需协程环境。
     */
    fun update(
        context: Context,
        phase: String? = null,
        connected: Boolean? = null,
        statusText: String? = null,
    ) {
        val app = context.applicationContext
        writeScope.launch {
            try {
                app.presenceDataStore.edit { p ->
                    phase?.let { p[KEY_PHASE] = it }
                    connected?.let { p[KEY_CONNECTED] = it }
                    statusText?.let { p[KEY_STATUS_TEXT] = it }
                    p[KEY_UPDATED_AT] = System.currentTimeMillis()
                }
                SurfaceRegistry.notifyAll(app)
            } catch (e: Exception) {
                Log.w(TAG, "presence 状态写入失败(表面将显示旧值): $e")
            }
        }
    }
}
