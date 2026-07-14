package com.ufo.galaxy.surface

/**
 * 在场状态 —— 所有"原生表面"(小组件/磁贴/背屏/…)共享的单一状态源快照。
 *
 * 设计原则:一个状态源,N 个表面渲染。任何表面都不得自行拼装状态;
 * 状态由运行时(连接服务/相位状态机/灵动岛)写入 [PresenceStateStore],
 * 各表面只读取渲染。这保证"AI 在干嘛"在所有系统表面上口径一致。
 *
 * @param phase      三态相位 wire 值:silent / liminal / manifest
 * @param connected  与 V2 主脑的 WebSocket 是否已连接(诚实值,不含乐观态)
 * @param statusText 一句话在场状态(如"在听""执行中:打开微信"),空串=无
 * @param updatedAtMs 最近一次写入时间(用于表面侧判断陈旧)
 */
data class PresenceState(
    val phase: String = PHASE_SILENT,
    val connected: Boolean = false,
    val statusText: String = "",
    val updatedAtMs: Long = 0L,
) {
    companion object {
        const val PHASE_SILENT = "silent"
        const val PHASE_LIMINAL = "liminal"
        const val PHASE_MANIFEST = "manifest"

        /** 超过该时长未更新,表面应如实降级显示(而不是假装状态仍新鲜)。 */
        const val STALE_AFTER_MS: Long = 10 * 60 * 1000L
    }

    val isStale: Boolean
        get() = updatedAtMs > 0 && System.currentTimeMillis() - updatedAtMs > STALE_AFTER_MS
}
