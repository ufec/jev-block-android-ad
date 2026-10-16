package me.ethanxu.jevnoisegate.core.pipeline

import me.ethanxu.jevnoisegate.core.common.Clock
import me.ethanxu.jevnoisegate.core.common.MonotonicClock
import me.ethanxu.jevnoisegate.core.common.PerformanceMetrics
import me.ethanxu.jevnoisegate.core.common.PerformanceCounter
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.DecisionMapper
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.FinalDecision
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.jevnoisegate.core.decision.SmsBlacklist
import me.ethanxu.jevnoisegate.core.decision.BackendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次判断的结果，连同是否运行过模型。
 * [backendAttempted] 不代表模型给出了结论；本地放行、黑名单与用户纠错路径恒为 false。
 */
data class PipelineResult(
    val decision: FinalDecision,
    val backendAttempted: Boolean,
    val queueMs: Long = 0,
    val backendMs: Long = 0,
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
    private val clock: Clock = MonotonicClock,
    private val deadlineMs: Long = 15_000L,
    maxConcurrentRequests: Int = 4,
) {
    private val requests = Semaphore(maxConcurrentRequests)

    /** Local policy is shared by SMS broadcasts, workers and notifications. */
    fun localDecision(input: DecisionInput): PipelineResult? {
        val by = when {
            OtpGate.isOtpLike(input.event.body, input.event.title) -> DecidedBy.OTP_GATE
            input.userAllowed -> DecidedBy.USER
            input.event.source == EventSource.SMS &&
                SmsBlacklist.matches(input.event.senderKey, input.smsSenderBlacklist) -> DecidedBy.BLACKLIST
            else -> return null
        }
        return PipelineResult(
            FinalDecision(input.event, if (by == DecidedBy.BLACKLIST) Action.SILENT_SUPPRESS else Action.ALLOW, by),
            backendAttempted = false,
        )
    }

    suspend fun process(input: DecisionInput): PipelineResult {
        // ① 本机判定 —— 验证码、号码黑名单与用户纠错命中即返回，不运行模型。
        localDecision(input)?.let { return it }

        // ② 交给本地模型。只在这一段计时。
        val startedAt = clock.nowMillis()
        var attempted = false
        var queueMs = 0L
        var backendMs = 0L
        PerformanceMetrics.queued()
        val outcome = try { withTimeoutOrNull(deadlineMs) {
            requests.withPermit {
                attempted = true
                queueMs = (clock.nowMillis() - startedAt).coerceAtLeast(0)
                val backendStarted = clock.nowMillis()
                PerformanceMetrics.acquired()
                try {
                    backend.decide(input)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    BackendOutcome.Failed(FailureReason.MODEL_ERROR, error.message)
                } finally {
                    backendMs = (clock.nowMillis() - backendStarted).coerceAtLeast(0)
                    PerformanceMetrics.finished()
                }
            }
        } ?: BackendOutcome.Failed(FailureReason.MODEL_UNAVAILABLE)
        } finally { if (!attempted) PerformanceMetrics.dequeued() }
        val elapsed = (clock.nowMillis() - startedAt).coerceAtLeast(0)
        if (!attempted) queueMs = elapsed
        if (outcome is BackendOutcome.Failed && outcome.reason == FailureReason.MODEL_UNAVAILABLE) {
            PerformanceMetrics.increment(if (attempted) PerformanceCounter.BACKEND_TIMEOUT else PerformanceCounter.QUEUE_TIMEOUT)
        }

        // ③ 映射为动作。fail-open 在这里落地，所有不确定路径一律放行。
        return PipelineResult(
            decision = DecisionMapper.map(input, outcome, elapsed),
            backendAttempted = attempted,
            queueMs = queueMs,
            backendMs = backendMs,
        )
    }
}
