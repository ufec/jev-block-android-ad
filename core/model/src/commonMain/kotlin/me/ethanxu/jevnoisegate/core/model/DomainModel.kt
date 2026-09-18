package me.ethanxu.jevnoisegate.core.model

/**
 * 对一条事件采取的动作，按侵入性从低到高排列。
 *
 * 注意：这里刻意**没有**"删除"。Android 上第三方 App 无法从短信收件箱删除消息，
 * 也无法阻止通知的声音/震动（回调发生在投递之后）。因此动作层只提供"不打扰"能力，
 * 真正的降噪手段是渠道级引导（见 :feature:notification）。
 */
enum class Action {
    /** 放行，不做任何处理。默认动作，也是所有失败路径的兜底动作。 */
    ALLOW,

    /** 静默移除：cancelNotification，不留痕迹。 */
    SILENT_SUPPRESS,

    /**
     * 隔离：静默移除并写入本地回收站，用户可在 App 内查看和捞回。
     * 捞回会让该指纹升级为 [DecidedBy.USER] 决策。
     */
    QUARANTINE,
}

/**
 * 决策的来源，同时决定其权重。
 *
 * 权重顺序（高→低）：[USER] > [OTP_GATE] > [MODEL] > [CACHE] > [FALLBACK]。
 * [USER] 永不被模型覆盖。
 */
enum class DecidedBy {
    /** 用户手动纠正（含从回收站捞回）。最高权重，永不被覆盖。 */
    USER,

    /** 本地 OTP 保险丝命中，直接放行且不曾上送 API。 */
    OTP_GATE,

    /** 用户配置的号码黑名单命中，直接拦截且不曾上送 API。 */
    BLACKLIST,

    /** 模型判断结果。 */
    MODEL,

    /** 指纹缓存复用，未产生网络请求。 */
    CACHE,

    /** 失败降级（超时/断网/5xx）。一律 fail-open 放行。 */
    FALLBACK,
}

/**
 * 决策降级放行的原因。
 *
 * 存在的理由是**诊断**：`DecidedBy.FALLBACK` 只说明"我们放行了"，不说明"因为什么放行"。
 * 而"最近 30% 的请求都超时"和"最近 30% 的请求都被限流"需要完全不同的处理，
 * 诊断页必须能区分。
 */
enum class FailureReason {
    /** 单次尝试超时。 */
    TIMEOUT,

    /** 连接层失败（DNS、TLS、连接中断）。 */
    NETWORK,

    /** 触发限流（HTTP 429）。 */
    RATE_LIMITED,

    /**
     * 鉴权失败（HTTP 401 / 403）。
     *
     * 与 [INVALID_RESPONSE] 分开是因为处理方式完全不同：鉴权失败意味着 API key 没配、
     * 配错了或被吊销 —— 那是**必须去修配置**，而不是重试或调参。
     */
    AUTH_FAILED,

    /** 服务端错误（HTTP 5xx，含 529 过载）。 */
    SERVER_ERROR,

    /** 响应无法解析，或问题的答案缺失/类型不符。 */
    INVALID_RESPONSE,

    /** 分类配置缺失或非法（如重名），请求根本没发出去。 */
    NOT_CONFIGURED,

    /** 用户关闭了模型判断。 */
    DISABLED,
}

/**
 * 渠道历史统计，作为 state 的一部分喂给模型。
 *
 * 让模型知道"这个渠道历史上几乎全是广告"，比单看一条文本的准确率高得多 ——
 * 这是纯文本分类做不到的上下文。
 */
data class ChannelContext(
    val packageName: String,
    val channelId: String?,
    val totalSeen: Int = 0,
    val suppressedCount: Int = 0,
    val restoredCount: Int = 0,
) {
    /** 被判定为噪音的比例；样本不足时为 0。 */
    val noiseRatio: Float
        get() = if (totalSeen <= 0) 0f else suppressedCount.toFloat() / totalSeen
}

/**
 * 用户的分类配置 —— 声明式知识的唯一载体。
 *
 * [name] 会**直接成为 TypeSafe `Choice.criteria` 的 key**，即"配置即答案空间"：
 * 增加一个分类不需要改任何代码，只是多一个 Choice 选项。
 */
data class CategoryConfig(
    val name: String,
    /** 进入 `criteria` 的选项描述，是模型判断依据的主要来源。 */
    val description: String,
    /**
     * 辅助提示词。由 `QuestionFactory` 渲染进该分类的 `criteria` 描述里 ——
     * 关键词天然是**分类级**的（"优惠/促销"属于广告，"转账/账户异常"属于诈骗），
     * 因此放进对应选项的 rubric 比放在全局 state 更准确。
     */
    val keywords: List<String> = emptyList(),
    /** 判定为该分类时执行的动作。 */
    val action: Action,
    /**
     * 低于此置信度则不执行 [action]，退回 ALLOW。
     *
     * 这是代码里唯一的"策略"，且完全由配置驱动。验证码类默认 0f，即永不拦截 ——
     * 因为漏放一条广告无感，错拦一条验证码会让用户被锁在银行外面。
     */
    val minConfidence: Float,
)

/**
 * 事件经流水线后的最终结果。
 */
data class FinalDecision(
    val event: MessageEvent,
    val action: Action,
    val decidedBy: DecidedBy,
    /** 命中的分类名；未命中模型或降级时为 null。 */
    val category: String? = null,
    /** 模型给出的置信度；非模型决策时为 null。 */
    val confidence: Float? = null,
    /** 仅当 [decidedBy] 为 [DecidedBy.FALLBACK] 时非空，说明降级原因。 */
    val failureReason: FailureReason? = null,
    /** 端到端耗时，用于诊断页统计 P50/P95 —— 这是"能否跑赢震动"这一首要风险的直接证据。 */
    val latencyMillis: Long = 0L,
)
