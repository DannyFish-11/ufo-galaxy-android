package com.ufo.galaxy.memory

import android.content.Context

/**
 * 上行事件的**每设备单调序号**。
 *
 * 为什么需要它
 * ============
 * 账本靠 ``(device_id, seq)`` 判重。设计里的形态是"断网本地缓存、联网补传"——补传
 * 必然把同一批事件再发一遍，没有序号就无从识别哪些是重复的。
 *
 * 序号刻意**不由服务端分配**：服务端的号只反映"到达顺序"，而断网缓存要保住的恰恰是
 * "发生顺序"。两者在补传场景下必然不同，而那正是需要序号的那个场景。
 *
 * 跳号无害，重号有害
 * ==================
 * 所以 [next] 是**先持久化再返回**：崩溃发生在持久化之后、发送之前，结果是这个号被
 * 跳过——账本只要求单调递增与唯一，不要求连续，跳号什么也不影响。
 *
 * 反过来若先返回再持久化，崩溃会导致下次重复发放同一个号。那时服务端会把一条**真**
 * 事件当成补传丢掉，而且不报错——正是这套改动要消除的那类静默丢失。
 *
 * 存储被抽成 [Store] 而不是直接用 SharedPreferences，是为了让这个类能在 JVM 上单测：
 * 序号分配的正确性是纯逻辑，不该为了验证它去起一个 Android 运行时。
 */
class UplinkSequence(private val store: Store) {

    /** 序号的持久化后端。实现只需保证 [write] 返回时值已经落下去。 */
    interface Store {
        fun read(): Long

        /** 必须是**同步**写：返回即已持久化。异步写会把"先持久化"这条保证打掉。 */
        fun write(value: Long)
    }

    private val lock = Any()

    /** 取下一个号。先把 ``value + 1`` 落盘，再把 ``value`` 交出去。 */
    fun next(): Long = synchronized(lock) {
        val current = store.read()
        store.write(current + 1)
        current
    }

    companion object {
        private const val PREFS_FILE = "galaxy_uplink_seq"
        private const val KEY_NEXT = "next_seq"

        /**
         * 落在 SharedPreferences 上的实例。
         *
         * 用 ``commit()`` 不用 ``apply()``：apply 是异步落盘，返回时值可能还在内存里，
         * 那样"先持久化"这条保证就不成立了。这条路径的调用频率是**任务事件级**
         * （不是每帧），一次同步写的代价可以忽略。
         */
        fun forContext(context: Context): UplinkSequence {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            return UplinkSequence(
                object : Store {
                    override fun read(): Long = prefs.getLong(KEY_NEXT, 1L)

                    @Suppress("ApplySharedPref") // 见上：这里必须同步落盘
                    override fun write(value: Long) {
                        prefs.edit().putLong(KEY_NEXT, value).commit()
                    }
                },
            )
        }
    }
}
