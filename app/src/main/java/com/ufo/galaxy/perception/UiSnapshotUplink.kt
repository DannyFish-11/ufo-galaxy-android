package com.ufo.galaxy.perception

import com.ufo.galaxy.protocol.DeviceUiSnapshotPayload

/**
 * 结构化界面快照的上行开关与线材映射（Stage C）。
 *
 * 为什么需要这一层
 * ----------------
 * [AccessibilityUiSnapshotProvider] 读出来的树一直只在设备内部用（注入 VLM prompt、
 * 参与 [GroundingArbiter] 裁决），**从未离开过设备**。V2 侧
 * `UISource.ANDROID_A11Y` 这个来源因此声明了却零生产者，服务端看不见手机屏幕上有什么，
 * 跨设备编排与面板呈现只能靠截图猜。本文件把它变成可上行的线材。
 *
 * 默认关，而且是有理由的默认
 * --------------------------
 * [DEFAULT_ENABLED] 是 `false`。这不是保守，是按需：剪枝后的树仍有上百个节点，
 * 挂在**持续发送**的 DEVICE_PERCEPTION_EMISSION 上每一拍都传，等于把带宽换成一份
 * 没人读的数据流。只有当服务端确实要"看得见"这一屏时才打开它。
 *
 * 上行不改变谁说了算
 * ------------------
 * 传上去是给服务端**看**的，不是交出决定权。Android 的 grounding 归属仍在设备本地
 * （V2 侧 `core/perception_grounding.py` POLICY_1/POLICY_3）：服务端不得用收到的快照
 * 覆盖 [GroundingArbiter] 已经做出的裁决——两端看到的是不同瞬间的屏幕，让后到的
 * 一方推翻先到的一方，得到的不是更准，是事后分不清这一下是谁点的。
 *
 * 纯 JVM，无 Android 依赖，可单测。
 */
object UiSnapshotUplink {

    /** 默认不上行。理由见类文档：按需，不是每一拍。 */
    const val DEFAULT_ENABLED: Boolean = false

    /**
     * 上行的元素数量上限。
     *
     * 比 [UiStructuredSnapshot.MAX_PROMPT_ELEMENTS]（注入 prompt 的 60）宽，
     * 因为服务端是"看整屏"而不是"喂给模型"；但仍然有上限——无上界的载荷迟早会
     * 在某个节点上千的界面上把这条上行撑爆。
     */
    const val MAX_UPLINK_ELEMENTS: Int = 120

    @Volatile
    private var enabled: Boolean = DEFAULT_ENABLED

    /** 打开/关闭上行。运行期可调，便于按会话决定要不要让服务端看见。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /**
     * 快照 → 线材载荷。返回 null 表示**这一拍不带**，三种情况：
     * 开关没开、快照为 null（无障碍服务未连接等）、或树里没有一个带语义标签的元素。
     *
     * 三者都返回 null 是有意的：调用方要做的事完全一样（不带这个字段），
     * 而区分它们的信息在采集端已经有日志了，这里再分一次只会多一处要维护的分支。
     */
    fun toPayload(snapshot: UiStructuredSnapshot?): DeviceUiSnapshotPayload? {
        if (!enabled) return null
        if (snapshot == null) return null

        val labeled = snapshot.elements.filter { it.label.isNotBlank() }
        if (labeled.isEmpty()) return null

        // 超限时优先保留 clickable —— 服务端要"看得见"的首先是能动的东西。
        val selected = if (labeled.size <= MAX_UPLINK_ELEMENTS) {
            labeled
        } else {
            val clickables = labeled.filter { it.clickable }
            val rest = labeled.filter { !it.clickable }
            (clickables + rest).take(MAX_UPLINK_ELEMENTS).sortedBy { it.index }
        }

        return DeviceUiSnapshotPayload(
            packageName = snapshot.packageName,
            screenWidth = snapshot.screenWidth,
            screenHeight = snapshot.screenHeight,
            elements = selected.map { e ->
                DeviceUiSnapshotPayload.DeviceUiElement(
                    index = e.index,
                    text = e.text,
                    contentDescription = e.contentDescription,
                    className = e.className,
                    clickable = e.clickable,
                    left = e.left,
                    top = e.top,
                    right = e.right,
                    bottom = e.bottom
                )
            }
        )
    }
}
