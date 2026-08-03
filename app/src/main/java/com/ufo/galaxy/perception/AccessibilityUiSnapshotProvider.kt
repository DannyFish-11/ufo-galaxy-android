package com.ufo.galaxy.perception

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ufo.galaxy.service.HardwareKeyListener

/**
 * [UiSnapshotProvider] 的生产实现:从已连接的无障碍服务
 * ([HardwareKeyListener.instance]) 读取 `rootInActiveWindow`,拍平为
 * [UiStructuredSnapshot]。
 *
 * 这是双通道感知的结构化采集端:此前无障碍服务只用于**执行**(点按/输入),
 * 树从未被读过一次 —— 感知 100% 压在视觉模型上。本类补上读取侧,与截图同帧
 * 采集,一起送入 VLM 与 [GroundingArbiter]。
 *
 * 剪枝规则(控制规模,树在部分 App 里节点上千):
 *  - 只收可见(isVisibleToUser)节点;
 *  - 只收"有语义价值"的节点:clickable,或带 text/contentDescription;
 *  - bounds 无效(空矩形)的丢弃;
 *  - 总量截断 [MAX_ELEMENTS](深度优先序,屏幕上方元素先入)。
 *
 * 任何异常(服务断连、窗口切换竞态导致节点失效等)都返回 null —— 调用方按
 * "无结构化通道"退化为纯视觉,绝不让感知辅助层把主链路打挂。
 */
class AccessibilityUiSnapshotProvider(
    private val screenWidth: () -> Int = { HardwareKeyListener.instance?.resources?.displayMetrics?.widthPixels ?: 0 },
    private val screenHeight: () -> Int = { HardwareKeyListener.instance?.resources?.displayMetrics?.heightPixels ?: 0 }
) : UiSnapshotProvider {

    private companion object {
        private const val TAG = "AccessibilityUiSnapshot"

        /** 采集上限:大于 prompt 注入上限,给仲裁保留更全的候选面。 */
        private const val MAX_ELEMENTS = 120

        /** 遍历深度护栏,防病态深树。 */
        private const val MAX_DEPTH = 40
    }

    override fun capture(): UiStructuredSnapshot? {
        val service = HardwareKeyListener.instance ?: return null
        return try {
            val root = service.rootInActiveWindow ?: return null
            try {
                val elements = ArrayList<UiStructuredSnapshot.UiElement>(64)
                flatten(root, elements, depth = 0)
                if (elements.isEmpty()) return null
                UiStructuredSnapshot(
                    packageName = root.packageName?.toString() ?: "",
                    screenWidth = screenWidth(),
                    screenHeight = screenHeight(),
                    elements = elements
                )
            } finally {
                // 根节点同样是每次调用新分配的句柄,API < 33 需回收(见 recycleQuietly)。
                recycleQuietly(root)
            }
        } catch (e: Exception) {
            // 窗口切换瞬间节点可能已回收(IllegalStateException 等):静默退化。
            Log.w(TAG, "capture failed — degrade to vision-only: ${e.message}")
            null
        }
    }

    private fun flatten(
        node: AccessibilityNodeInfo,
        out: MutableList<UiStructuredSnapshot.UiElement>,
        depth: Int
    ) {
        if (out.size >= MAX_ELEMENTS || depth > MAX_DEPTH) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val semantic = node.isClickable || text.isNotBlank() || desc.isNotBlank()
            if (semantic) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    out.add(
                        UiStructuredSnapshot.UiElement(
                            index = out.size,
                            text = text,
                            contentDescription = desc,
                            className = node.className?.toString().orEmpty(),
                            clickable = node.isClickable,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom
                        )
                    )
                }
            }
        }
        for (i in 0 until node.childCount) {
            // 采集量已达上限时直接停:继续 getChild 只会白白分配节点句柄。
            if (out.size >= MAX_ELEMENTS) return
            val child = node.getChild(i) ?: continue
            try {
                flatten(child, out, depth + 1)
            } finally {
                recycleQuietly(child)
            }
        }
    }

    /**
     * 真 bug 修复(资源未释放):minSdk 26 上 getChild() 返回的 AccessibilityNodeInfo
     * 持有跨进程原生资源,API < 33 必须 recycle,否则每步定位遍历(最多上百节点)
     * 都在泄漏,长任务下累积耗尽节点池。API 33+ recycle 是 no-op,故仅在旧版本调用;
     * 窗口切换竞态下节点可能已失效,回收失败静默忽略。
     */
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {
            // 已被系统回收/失效:忽略。
        }
    }
}
