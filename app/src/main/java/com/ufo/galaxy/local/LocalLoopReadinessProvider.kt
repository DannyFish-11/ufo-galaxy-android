package com.ufo.galaxy.local

import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.service.HardwareKeyListener

/**
 * **Full local-loop readiness gate** — six-subsystem pre-execution readiness check.
 *
 * Provides a [LocalLoopReadiness] snapshot reflecting the state of every subsystem
 * required for the local closed-loop execution pipeline. Evaluated by
 * [com.ufo.galaxy.loop.LoopController] before starting any session.
 *
 * ## Scope — distinction from [com.ufo.galaxy.service.ReadinessChecker]
 * [com.ufo.galaxy.service.ReadinessChecker] is a lightweight **three-check** probe
 * (model files, accessibility, overlay) whose results are persisted to
 * [com.ufo.galaxy.data.AppSettings] and included in the gateway `capability_report`.
 * **This interface** is the richer **six-check** gate that also covers planner loading,
 * grounding loading, and per-subsystem failure categorisation; it is evaluated
 * immediately before local execution begins and is not surfaced in the capability_report.
 *
 * Implementations must be lightweight and non-blocking: they are called from the UI
 * thread for status indicators as well as from background threads before execution.
 */
interface LocalLoopReadinessProvider {

    /**
     * Returns a fresh [LocalLoopReadiness] snapshot.
     * The snapshot reflects the system state at the moment of the call.
     */
    fun getReadiness(): LocalLoopReadiness

    /**
     * 说人话的阻塞原因，会直接出现在任务的失败信息里。为空表示无话可说。
     *
     * [LocalLoopReadiness.blockers] 是一组枚举，只回答"哪一项不行"；而看到失败的人
     * 需要的是"为什么不行、去哪儿修"—— 比如「强行停止会永久吊销无障碍授权，
     * 得回设置里重新勾选」。一个 `ACCESSIBILITY_SERVICE_DISABLED` 说不出这句话。
     *
     * 默认空实现：测试里的假 provider 不必关心这个。
     */
    fun humanBlockers(): List<String> = emptyList()
}

/**
 * Default [LocalLoopReadinessProvider] that queries all local-loop subsystems.
 *
 * Checks:
 * 1. Model file presence on disk ([ModelAssetManager.verifyAll]).
 * 2. Planner loaded state ([LocalPlannerService.isModelLoaded]).
 * 3. Grounding loaded state ([LocalGroundingService.isModelLoaded]).
 * 4. 无障碍**能力位**:感知/执行/截图三条通道分别由 canRetrieveWindowContent /
 *    canPerformGestures / canTakeScreenshot 决定,读的是系统实际授予的位
 *    ([HardwareKeyListener.capabilities]),不是我们在 xml 里声明了什么。
 * 5. 截图还要求 API 30+ —— 低版本上 takeScreenshot 不存在,且没有替代路径。
 *
 * @param modelAssetManager  Source of model file status.
 * @param plannerService     Unified VLM planner backend.
 * @param groundingService   Unified VLM grounding backend.
 */
class DefaultLocalLoopReadinessProvider(
    private val modelAssetManager: ModelAssetManager,
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService
) : LocalLoopReadinessProvider {

    override fun humanBlockers(): List<String> =
        (HardwareKeyListener.instance?.capabilities()
            ?: com.ufo.galaxy.service.A11yCapabilities.NOT_BOUND).blockers

    override fun getReadiness(): LocalLoopReadiness {
        val statuses = modelAssetManager.verifyAll()
        val modelFilesReady = statuses.values.all {
            it == ModelAssetManager.ModelStatus.READY || it == ModelAssetManager.ModelStatus.LOADED
        }
        val plannerLoaded = plannerService.isModelLoaded()
        val groundingLoaded = groundingService.isModelLoaded()

        // 真 bug 修复(体检问错了问题):此前这三行是
        //
        //     val accessibilityBound = HardwareKeyListener.instance != null
        //     val screenshotReady = accessibilityBound
        //     val actionExecutorReady = accessibilityBound
        //
        // 也就是「服务绑上了 ⇒ 截图和执行都就绪」。可这条闭环踩在三个**各自独立**
        // 的能力位上(canRetrieveWindowContent / canPerformGestures / canTakeScreenshot),
        // 缺任何一个,对应的调用都会**静默**失当:树返回 null、手势不生效、截图抛异常 ——
        // 而服务照样是绑定的,这道门照样报 OK。于是真机上看到的是「模型选了个坏动作」,
        // 排查方向被引向模型。
        //
        // 截图还多一条:takeScreenshot 是 API 30 才有的,而本模块 minSdk 26,
        // 且本应用不申请 MediaProjection —— 低版本上没有任何替代路径。
        //
        // 现在读的是系统**实际授予**的能力位(见 [HardwareKeyListener.capabilities])。
        val capabilities = HardwareKeyListener.instance?.capabilities()
            ?: com.ufo.galaxy.service.A11yCapabilities.NOT_BOUND
        val accessibilityBound = capabilities.perceptionReady
        val screenshotReady = capabilities.screenshotReady
        val actionExecutorReady = capabilities.actionsReady

        val blockers = buildList {
            if (!modelFilesReady) add(LocalLoopFailureType.MODEL_FILES_MISSING)
            if (!plannerLoaded) add(LocalLoopFailureType.PLANNER_UNAVAILABLE)
            if (!groundingLoaded) add(LocalLoopFailureType.GROUNDING_UNAVAILABLE)
            if (!accessibilityBound) add(LocalLoopFailureType.ACCESSIBILITY_SERVICE_DISABLED)
            if (!screenshotReady) add(LocalLoopFailureType.SCREENSHOT_UNAVAILABLE)
            if (!actionExecutorReady) add(LocalLoopFailureType.ACTION_EXECUTOR_UNAVAILABLE)
        }

        return LocalLoopReadiness(
            modelFilesReady = modelFilesReady,
            plannerLoaded = plannerLoaded,
            groundingLoaded = groundingLoaded,
            accessibilityReady = accessibilityBound,
            screenshotReady = screenshotReady,
            actionExecutorReady = actionExecutorReady,
            blockers = blockers
        )
    }
}
