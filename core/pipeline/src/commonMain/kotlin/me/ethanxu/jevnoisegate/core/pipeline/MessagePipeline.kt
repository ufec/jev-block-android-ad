package me.ethanxu.jevnoisegate.core.pipeline

import me.ethanxu.jevnoisegate.core.common.Clock
import me.ethanxu.jevnoisegate.core.common.SystemClock
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.DecisionMapper
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.FinalDecision

/**
 * 一次判断的结果，连同"是否真的发送到了外部 API"。
 *
 * [uploaded] 是刻意暴露的：隐私承诺（验证码永不上送）必须是可断言、可观测的，
 * 而不是只写在文档里。诊断页据此统计"实际上送率"。
 */
data class PipelineResult(
    val decision: FinalDecision,
    val uploaded: Boolean,
)

/**
 * 消息判断流水线。
 *
 * 当前是**最直接的形态**：每条事件都交给后端判断，不做指纹缓存。
 * 之所以先这么做，是因为缓存的价值完全取决于真实的模板命中率 —— 那个数字只有跑起来才知道，
 * 而延迟能否跑赢通知震动是所有方案变体的共同前提。先用最简单的方式量出来。
 *
 * 结构上刻意保持纯净：只依赖后端接口与时钟，不碰 Room、不碰 Android，
 * 因此整个编排逻辑可以在 JVM 上毫秒级测试。
 */
class MessagePipeline(
    private val backend: DecisionBackend,
    private val clock: Clock = SystemClock,
) {

    suspend fun process(input: DecisionInput): PipelineResult {
        // ① OTP 闸门 —— 命中即放行，且请求根本不会发出去。
        if (OtpGate.isOtpLike(input.event.body)) {
            return PipelineResult(
                decision = FinalDecision(
                    event = input.event,
                    action = Action.ALLOW,
                    decidedBy = DecidedBy.OTP_GATE,
                ),
                uploaded = false,
            )
        }

        // ② 交给后端。只在这一段计时 —— 它才是"能否跑赢震动"所关心的那个数字。
        val startedAt = clock.nowMillis()
        val outcome = backend.decide(input)
        val elapsed = clock.nowMillis() - startedAt

        // ③ 映射为动作。fail-open 在这里落地，所有不确定路径一律放行。
        return PipelineResult(
            decision = DecisionMapper.map(input, outcome, elapsed),
            uploaded = true,
        )
    }
}
