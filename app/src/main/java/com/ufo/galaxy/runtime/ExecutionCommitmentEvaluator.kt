package com.ufo.galaxy.runtime

/**
 * 本机对一次 `execution_proposal` 的回答。
 *
 * ## 为什么这件事要设备自己判断
 *
 * 中心在派发前已经查过它那一侧能查的一切：peer trust、注册、传输存活、能力图、
 * 跨设备资格（V2 的 `unified_dispatch_readiness_gate` 七道闸）。但有些事只有设备
 * 自己知道，而且**过几百毫秒就变**：它此刻在不在跑别的任务、运行时有没有就位、
 * 它是不是刚被切成本地模式。中心的能力图是"上一次上报时的样子"，不是现在。
 *
 * ## 承诺必须带有效期
 *
 * [ExecutionCommitment.validUntilMs] 不是装饰。设备说"我能做"时看到的那一屏，
 * 几秒之后可能已经不在了；一个在时刻 T 成立的判断不能无限期当成 T+n 也成立。
 * V2 侧把 `valid_until_ms == 0` 直接判为不可用 —— 没有有效期等于让中心去赌。
 *
 * ## 拒绝原因是封闭枚举，不是自由文本
 *
 * 这些字符串和 V2 的 `core.coordination_consensus.DeclineReason` 逐字对齐。中心要
 * 据此换策略：`busy` 换一台、`not_ready` 等一会儿再问同一台、`policy_declined`
 * 换台也没用别重试。自由文本只会被记进日志，然后所有失败被一视同仁地对待 ——
 * 那就等于把这一轮问话的信息量全丢了。
 *
 * ## 这个类是纯的
 *
 * 不碰 Android framework、不碰网络。理由很实际：本仓的 CI 跑不了带设备的测试，
 * 而"该不该接这次任务"恰恰是最需要被测到的判断。把它做成纯函数，JVM 单测就能覆盖。
 */
object ExecutionCommitmentEvaluator {

    /** 和 V2 `DeclineReason` 逐字对齐的封闭集合。 */
    enum class DeclineReason(val wireValue: String) {
        /** 正在跑别的任务。**换一台**。 */
        BUSY("busy"),

        /** 运行时还没就位，或正在切换模式。**过一会儿再问同一台**。 */
        NOT_READY("not_ready"),

        /** 本机策略不接跨设备任务（例如被切成了本地模式）。**换台也没用，别重试**。 */
        POLICY_DECLINED("policy_declined"),

        /** 缺少必要授权（无障碍服务没开等）。**去问人**。 */
        NO_PERMISSION("no_permission"),

        /** 不支持这类任务。 */
        UNSUPPORTED("unsupported"),
    }

    /** 本机此刻的处境。调用方从各自的真实来源填进来，这个类不去猜。 */
    data class DeviceSituation(
        /** 执行模式闸的当前状态 —— 本机是不是在接跨设备任务的形态。 */
        val modeState: LocalExecutionModeGate.ExecutionModeState,
        /** 手头有没有正在执行的任务。 */
        val hasTaskInFlight: Boolean = false,
        /** 执行这类任务所需的授权齐了没有（无障碍服务等）。 */
        val hasRequiredPermissions: Boolean = true,
    )

    /** 回答。[accepted] 为 true 时 [declineReason] 必为 null，反之亦然。 */
    data class ExecutionCommitment(
        val accepted: Boolean,
        val validUntilMs: Long,
        val declineReason: DeclineReason?,
        val bestLevel: String,
    ) {
        /** 装进 `execution_commitment` 的 payload。字段名由 V2 的 `Commitment.from_payload` 决定。 */
        fun toPayload(deviceId: String, proposalId: String): Map<String, Any> = buildMap {
            put("proposal_id", proposalId)
            put("device_id", deviceId)
            put("accepted", accepted)
            put("valid_until_ms", validUntilMs)
            put("best_level", bestLevel)
            declineReason?.let { put("decline_reason", it.wireValue) }
        }
    }

    /**
     * 本机能不能接这一次。
     *
     * 判定顺序是有讲究的：**先看能不能，再看忙不忙**。一台没就位的设备说"我忙"是
     * 误导性的 —— 中心会等它闲下来再问，而它闲下来也还是不能做。
     *
     * @param nowMs 当前时刻。显式传进来而不是内部取 `System.currentTimeMillis()`，
     *   否则有效期这件事在测试里根本没法断言。
     * @param ttlMs 这份承诺管多久。默认 [DEFAULT_TTL_MS]。
     */
    @JvmStatic
    @JvmOverloads
    fun evaluate(
        situation: DeviceSituation,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): ExecutionCommitment {
        val decline = declineReasonFor(situation)
        if (decline != null) {
            // 拒绝不带有效期：它说的是"现在不行"，不是"到某时刻为止不行"。
            return ExecutionCommitment(
                accepted = false,
                validUntilMs = 0L,
                declineReason = decline,
                bestLevel = "",
            )
        }
        return ExecutionCommitment(
            accepted = true,
            validUntilMs = nowMs + ttlMs.coerceAtLeast(1L),
            declineReason = null,
            bestLevel = bestLevelFor(situation.modeState),
        )
    }

    private fun declineReasonFor(situation: DeviceSituation): DeclineReason? = when {
        // 本机形态压根不接跨设备任务 —— 但 INACTIVE/TRANSITIONING 和 LOCAL_ONLY
        // 的含义完全不同，不能都回同一个原因：
        //   · TRANSITIONING 会在一个往返内落定 → not_ready，值得再问一次；
        //   · INACTIVE 是运行时没起来 → not_ready，起来了就能做；
        //   · LOCAL_ONLY 是**有意**退出了跨设备路由 → policy_declined，重试没有意义。
        situation.modeState == LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY ->
            DeclineReason.POLICY_DECLINED

        !situation.modeState.acceptsCrossDeviceTasks -> DeclineReason.NOT_READY

        !situation.hasRequiredPermissions -> DeclineReason.NO_PERMISSION

        situation.hasTaskInFlight -> DeclineReason.BUSY

        else -> null
    }

    /**
     * 接得下来时，本机最好能到哪一档。
     *
     * `CROSS_DEVICE_DEGRADED` 也接 —— 中心通过能力快照早就知道它降级了，由中心决定
     * 这次任务要不要那份被降掉的能力。设备在这里替中心判断，等于把一个中心有更多
     * 信息的决定抢过来做。
     */
    private fun bestLevelFor(state: LocalExecutionModeGate.ExecutionModeState): String = when (state) {
        LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE -> LEVEL_FULL
        LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_DEGRADED -> LEVEL_DEGRADED
        else -> ""
    }

    /**
     * 承诺默认管多久。
     *
     * **这个数是推的，不是量的** —— 和 V2 侧的 `DEFAULT_COMMITMENT_TTL_MS` 取同一个值，
     * 理由也一样：比一次正常往返宽裕得多，又短到"界面在这期间大改"的概率很低。
     * 真机上量出 NATS 往返与唤醒延迟之后，这个默认值该被替换，而不是被默默沿用。
     */
    const val DEFAULT_TTL_MS: Long = 5_000L

    /** 满能力。 */
    const val LEVEL_FULL: String = "full"

    /** 连着但能力有削减；接不接由中心按任务需要决定。 */
    const val LEVEL_DEGRADED: String = "degraded"
}
