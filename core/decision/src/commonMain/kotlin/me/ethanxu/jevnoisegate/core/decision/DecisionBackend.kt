package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.CategoryConfig
import me.ethanxu.jevnoisegate.core.model.ChannelContext
import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.jevnoisegate.core.model.MessageEvent

/**
 * 平台层提供的可读标签，用于充实喂给模型的 state。
 *
 * 单独建模而不塞进 [MessageEvent]，是因为标签是**判断期的富化信息**而非消息本身：
 * 核心模块（含 :core:model）不持有 Android 依赖，解析不出 App 名与渠道名，
 * 必须由采集层在构造 [DecisionInput] 时补齐。
 */
data class EventLabels(
    /** App 显示名，如"淘宝"。比包名对模型更有信息量。 */
    val appName: String? = null,
    /** 通知渠道显示名，如"营销活动"。`channelId` 往往是 `channel_promo_1` 这类机器标识。 */
    val channelName: String? = null,
    /** 短信发件人是否在通讯录中。真则几乎必然是正常消息。 */
    val senderIsContact: Boolean = false,
)

/**
 * 一次判断所需的全部输入。
 */
data class DecisionInput(
    val event: MessageEvent,
    /** 该渠道/发件人的历史统计，是纯文本分类拿不到的上下文。 */
    val context: ChannelContext,
    /** 用户声明式配置的分类 —— 它直接构成答案空间。 */
    val categories: List<CategoryConfig>,
    val labels: EventLabels = EventLabels(),
    /** 用户写下的偏好规则。为空表示用户没配。 */
    val userRules: UserRules = UserRules.EMPTY,
)

/**
 * 用户声明的判断偏好。
 *
 * 刻意是**自由文本而非结构化规则**：用户表达的是意图（"验证码一定要放行"）而不是匹配条件。
 * 结构化规则能程序化硬匹配，但覆盖不了语义 —— 广告也会写"验证码"。
 * 这里只作为 state 的一部分交给模型权衡，因此是**软约束**：
 * 模型仍能处理例外（某 App 平时是广告，但这条是真验证码）。
 */
data class UserRules(
    val preferAllow: List<String> = emptyList(),
    val preferBlock: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = preferAllow.isEmpty() && preferBlock.isEmpty()

    companion object {
        val EMPTY = UserRules()
    }
}

/**
 * 后端判断的结果。
 *
 * 刻意用 sealed 类型而非异常来表达失败：**fail-open 是本系统的硬性安全要求**，
 * 让"失败"成为一个必须显式处理的普通返回值，比让调用方记得 catch 更难写错。
 * 真正的 fail-open 落地点在 [DecisionMapper]。
 */
sealed interface BackendOutcome {

    /** 模型给出了分类。 */
    data class Decided(
        val category: String,
        /** 模型对该分类的置信度，0–1。 */
        val confidence: Float,
        /** 全量概率分布，供诊断与后续调参使用。 */
        val probabilities: Map<String, Float> = emptyMap(),
    ) : BackendOutcome

    /** 未能取得判断结果，调用方必须降级放行。 */
    data class Failed(
        val reason: FailureReason,
        val detail: String? = null,
    ) : BackendOutcome
}

/**
 * 判断后端。
 *
 * 抽成接口有两个目的：
 * 1. 让 [FakeBackend] 可以在单测与断网演练中替换真实后端；
 * 2. 留住"以后可能接入本地端侧模型"这个扩展位 —— 那正是当初为延迟与成本留的后路。
 */
interface DecisionBackend {
    suspend fun decide(input: DecisionInput): BackendOutcome
}
