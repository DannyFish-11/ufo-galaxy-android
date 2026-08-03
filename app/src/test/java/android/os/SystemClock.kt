package android.os

/**
 * 单测专用的真实 [SystemClock] 替身(仅 test 源集,不进 APK)。
 *
 * 背景:纯 JVM 单测跑在 mockable android.jar 之上,`returnDefaultValues = true` 使
 * `SystemClock.elapsedRealtime()` 恒返回 0 —— 于是所有基于该单调时钟的墙钟逻辑
 * (LoopController 的 step/goal 超时、GalaxyWebSocketClient 的 pong 超时、
 * RuntimeController 的 stale-lock 检测)在单测里永远测不出"时间流逝",
 * LocalLoopCorrectnessTest 的超时用例直接假绿/假红。
 *
 * 处理方式与 build.gradle 里引入真实 org.json Maven 制品同理:test 源集/依赖类路径
 * 排在 mockable android.jar 之前,故这里定义的同名类会取代桩实现。用 System.nanoTime()
 * 提供真实的单调毫秒流逝(以类加载时刻为原点,行为等同设备开机起算的 elapsedRealtime)。
 *
 * 对既有测试的影响评估:所有生产调用点均为「now - earlierNow >= 阈值」形态,
 * 且未配置超时的路径(默认 0 = 关闭)完全不受影响;时钟从 0 附近起算,
 * 不会触发任何以 0 为初值的字段产生虚假的大间隔。
 */
object SystemClock {

    private val originNanos: Long = System.nanoTime()

    /** 自类加载起流逝的单调毫秒数(等价语义:自"开机"起)。 */
    @JvmStatic
    fun elapsedRealtime(): Long = (System.nanoTime() - originNanos) / 1_000_000L

    /** 自类加载起流逝的单调纳秒数。 */
    @JvmStatic
    fun elapsedRealtimeNanos(): Long = System.nanoTime() - originNanos

    /** JVM 上不区分 deep sleep,与 [elapsedRealtime] 一致。 */
    @JvmStatic
    fun uptimeMillis(): Long = elapsedRealtime()

    /** 粗略近似:JVM 无线程 CPU 时钟直读,退化为单调墙钟。 */
    @JvmStatic
    fun currentThreadTimeMillis(): Long = elapsedRealtime()

    /** 与真实 SystemClock.sleep 一致:吞掉中断并保持中断标志。 */
    @JvmStatic
    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
