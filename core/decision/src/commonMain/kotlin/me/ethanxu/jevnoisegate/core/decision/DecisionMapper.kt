package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.jevnoisegate.core.model.FinalDecision

/**
 * 把后端结果映射成最终动作。
 *
 * **这里是整个系统唯一允许存在"策略"的地方**，而且它完全由配置驱动（阈值来自
 * [me.ethanxu.jevnoisegate.core.model.CategoryConfig.minConfidence]），代码里没有任何
 * "什么算广告"这类判断。TypeSafe 官方文档也把这一步明确划给调用方：
 * "Your code encodes the risk tolerance."
 *
 * 同时，**fail-open 在这里落地** —— 所有无法确信的路径一律降级为 [Action.ALLOW]。
 * 集中在一处的好处是它不可能被别的调用路径绕过。
 */
object DecisionMapper {

    fun map(
        input: DecisionInput,
        outcome: BackendOutcome,
        latencyMillis: Long = 0L,
    ): FinalDecision = when (outcome) {
        is BackendOutcome.Failed -> allow(input, DecidedBy.FALLBACK, latencyMillis, outcome.reason)

        is BackendOutcome.Decided -> {
            val config = input.categories.firstOrNull { it.name == outcome.category }

            when {
                // 模型返回了配置里不存在的标签。正常不该发生（答案空间由 criteria 定义），
                // 一旦发生说明契约被破坏，此时唯一安全的做法是放行。
                config == null -> allow(
                    input = input,
                    decidedBy = DecidedBy.MODEL,
                    latencyMillis = latencyMillis,
                    category = outcome.category,
                    confidence = outcome.confidence,
                )

                // 置信度不足，或根本不是有限数（NaN / Infinity）。
                // NaN 必须显式拦下：NaN 参与的任何比较都是 false，若只写
                // `confidence < threshold` 就会让 NaN 掉进"执行动作"分支，
                // 用垃圾数据去拦截用户的消息 —— 这是 fail-open 的反面。
                !outcome.confidence.isFinite() || outcome.confidence < config.minConfidence ->
                    allow(
                        input = input,
                        decidedBy = DecidedBy.MODEL,
                        latencyMillis = latencyMillis,
                        category = outcome.category,
                        confidence = outcome.confidence,
                    )

                else -> FinalDecision(
                    event = input.event,
                    action = config.action,
                    decidedBy = DecidedBy.MODEL,
                    category = outcome.category,
                    confidence = outcome.confidence,
                    latencyMillis = latencyMillis,
                )
            }
        }
    }

    private fun allow(
        input: DecisionInput,
        decidedBy: DecidedBy,
        latencyMillis: Long,
        reason: FailureReason? = null,
        category: String? = null,
        confidence: Float? = null,
    ): FinalDecision = FinalDecision(
        event = input.event,
        action = Action.ALLOW,
        decidedBy = decidedBy,
        category = category,
        confidence = confidence,
        failureReason = reason,
        latencyMillis = latencyMillis,
    )
}
