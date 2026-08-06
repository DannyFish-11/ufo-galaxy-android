package com.ufo.galaxy.shared.protocol

/**
 * ConnectionPathPlanner — 「这台设备接下来往哪儿连、连不上该怎么说」。
 *
 * 为什么需要它
 * ============
 * 配对时网关会把它**所有**可达路径交给设备（lan / tailscale / funnel，按可达性
 * 排好序）。同一台手机在家、在公司、带流量出门，能连通的是**不同**的那一条 ——
 * 只认一个地址等于换个网就连不上，而用户看到的只是"连不上"，没有任何线索说
 * 该换哪条。
 *
 * 这个类不做 I/O。它只回答两个问题：
 *
 *   1. **按什么顺序试** —— [planAttempts]
 *   2. **全试完了还不通，该跟人说什么** —— [classify]
 *
 * 不做 I/O 是刻意的：真正连接要用各平台自己的 WebSocket 栈（Android 用 OkHttp、
 * 手表用 Ktor），但"顺序"和"怎么解释失败"这两件事**两边必须一模一样**。写成两份
 * 的话，同一个网络故障在手机上说"网关没开"、在手表上说"去装 Tailscale"，
 * 排障时人会先怀疑自己的网。所以放进 shared-protocol，两仓共用一份、共用一套测试。
 *
 * 三档失败为什么要分开报
 * ======================
 * 全糊成"连不上"的话，用户唯一能做的就是重启和瞎试。三档对应三件**不同**的事：
 *
 *   * [Verdict.Unreachable] —— 全超时。网关那台大概没开机，或者你们不在一个网上。
 *     该做的是去看那台电脑，不是折腾手机。
 *   * [Verdict.DegradedButFine] —— 局域网那条没通，但公网/tailnet 那条通了。
 *     **这不算故障**，正常发生在你出门时。不该弹任何东西打扰人。
 *   * [Verdict.NeedsTailscale] —— 全都不通，而且这台设备压根没有 tailnet 那条路
 *     可试。这是**唯一**该建议装 Tailscale 的时刻；在别的档口提它都是噪声。
 */
object ConnectionPathPlanner {

    /** 单条候选路径。与 V2 侧 `core/agent_card.build_candidates` 的产出一一对应。 */
    data class Candidate(
        val kind: String,
        val url: String,
        val priority: Int,
    )

    /** 一次尝试的结果。[elapsedMs] 用于区分"很快被拒"与"一直没响应"。 */
    data class AttemptOutcome(
        val kind: String,
        val connected: Boolean,
        val timedOut: Boolean,
        val elapsedMs: Long = 0L,
    )

    /** 全部试完之后的结论。 */
    sealed class Verdict {
        /** 通了。[kind] 是通的那条，调用方应把它记下来供下次优先。 */
        data class Connected(val kind: String) : Verdict()

        /** 一条都没通，而且全是超时 —— 网关多半没开，或不在同一网络。 */
        object Unreachable : Verdict()

        /** 局域网没通但别的路通了。正常现象，不该打扰用户。 */
        data class DegradedButFine(val kind: String) : Verdict()

        /** 全不通，且没有 tailnet 那条路可试。唯一该建议装 Tailscale 的时刻。 */
        object NeedsTailscale : Verdict()

        /** 压根没有候选路径 —— 还没配对过，或配对时服务端没给。 */
        object NotPaired : Verdict()
    }

    /** 每条候选给多久。3 秒：够局域网握手，又不至于让人在四条路上等半分钟。 */
    const val PER_CANDIDATE_TIMEOUT_MS = 3_000L

    /**
     * 决定试连顺序。
     *
     * 规则只有两条，但顺序要紧：
     *
     * 1. **上次通的那条排最前**。绝大多数情况下网络环境没变，先试它能把整轮试探
     *    省掉。注意它只是**提前**，不是独占 —— 环境真变了时后面几条还在。
     * 2. 其余按 `priority` 升序。那个顺序由 V2 侧 `TailscaleManager.NETWORK_PREFERENCE`
     *    唯一定义（时延与依赖：lan → tailscale → funnel），这里**不重新定义**一份。
     *
     * @param lastGoodKind 上次连通成功的那条；null/空 表示还没成功过。
     */
    @JvmStatic
    fun planAttempts(candidates: List<Candidate>, lastGoodKind: String? = null): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val byPriority = candidates.sortedBy { it.priority }
        if (lastGoodKind.isNullOrBlank()) return byPriority

        val preferred = byPriority.filter { it.kind == lastGoodKind }
        // 上次通的那条已经不在清单里了（换了网关、或那条路被关掉）——
        // 此时不能空手而归，按 priority 正常试即可。
        if (preferred.isEmpty()) return byPriority
        return preferred + byPriority.filterNot { it.kind == lastGoodKind }
    }

    /**
     * 把一轮尝试的结果翻译成"该跟人说什么"。
     *
     * @param outcomes 按实际尝试顺序排列。空表示一条都没试（没有候选路径）。
     * @param tailscaleAvailable 这台设备上 tailnet 那条路是否**可能**可用。
     *   Android/手表侧就看候选里有没有 `tailscale`；桌面侧还可以查客户端是否装了。
     */
    @JvmStatic
    fun classify(outcomes: List<AttemptOutcome>, tailscaleAvailable: Boolean): Verdict {
        if (outcomes.isEmpty()) return Verdict.NotPaired

        val winner = outcomes.firstOrNull { it.connected }
        if (winner != null) {
            // 通了。但"通的不是局域网那条"本身是有信息量的：说明你不在家里那个网上。
            // 仍然算成功 —— 只是调用方可以据此在状态栏显示走的是哪条路。
            return if (winner.kind == KIND_LAN) {
                Verdict.Connected(winner.kind)
            } else {
                val lanTried = outcomes.any { it.kind == KIND_LAN }
                if (lanTried) Verdict.DegradedButFine(winner.kind) else Verdict.Connected(winner.kind)
            }
        }

        // 一条都没通。
        //
        // 判"是不是全超时"而不是"有没有超时"：一条路被明确拒绝（比如令牌无效
        // 立刻回 401）和一条路石沉大海，是两种完全不同的故障。混在一起会让
        // "令牌过期"被报成"网关没开机"，而用户按后者去检查电脑，永远查不到。
        val allTimedOut = outcomes.all { it.timedOut }
        if (allTimedOut) {
            return if (tailscaleAvailable) Verdict.Unreachable else Verdict.NeedsTailscale
        }
        // 有明确拒绝的 —— 网关是活的，是别的问题（令牌、鉴权、路由）。
        // 这种情况建议装 Tailscale 纯属误导，所以固定落 Unreachable 那一侧由
        // 调用方带上具体错误。
        return Verdict.Unreachable
    }

    /**
     * 给人看的一句话。放在这里而不是各平台的 UI 里，是为了让两个设备对**同一个**
     * 故障说同一句话 —— 否则排障时人会先怀疑是自己哪台设备的问题。
     */
    @JvmStatic
    fun explain(verdict: Verdict): String = when (verdict) {
        is Verdict.Connected -> "已连上（${kindLabel(verdict.kind)}）"
        is Verdict.DegradedButFine -> "已连上（${kindLabel(verdict.kind)}）"
        Verdict.Unreachable -> "连不上网关。看一下那台电脑是不是关了，或者两边不在同一个网络。"
        Verdict.NeedsTailscale -> "本地网络里找不到网关。要在外面也能连，需要在那台电脑上启用 Tailscale。"
        Verdict.NotPaired -> "还没配对。请在桌面面板点「出示名片」，把短码输进来。"
    }

    /** 是否值得弹给用户看。[Verdict.DegradedButFine] 是**正常**的，不该打扰人。 */
    @JvmStatic
    fun isWorthTellingTheUser(verdict: Verdict): Boolean = when (verdict) {
        is Verdict.Connected, is Verdict.DegradedButFine -> false
        else -> true
    }

    private fun kindLabel(kind: String): String = when (kind) {
        KIND_LAN -> "局域网"
        KIND_TAILSCALE -> "Tailscale"
        KIND_FUNNEL -> "公网"
        else -> kind
    }

    const val KIND_LAN = "lan"
    const val KIND_TAILSCALE = "tailscale"
    const val KIND_FUNNEL = "funnel"
}
