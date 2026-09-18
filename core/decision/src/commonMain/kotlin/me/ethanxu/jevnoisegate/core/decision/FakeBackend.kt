package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.FailureReason

/**
 * 可脚本化的假后端，用于单测与断网演练。
 *
 * 真实后端无法在单测里确定性地产生"限流"或"超时"，但没有这些场景就无法验证
 * fail-open 是否真的生效 —— 而 fail-open 恰恰是本系统最不能出错的地方。
 */
class FakeBackend(
    private val respond: (DecisionInput) -> BackendOutcome,
) : DecisionBackend {

    /** 记录收到的全部输入，便于断言"哪些消息真的被送出去了"。 */
    val received: MutableList<DecisionInput> = mutableListOf()

    override suspend fun decide(input: DecisionInput): BackendOutcome {
        received += input
        return respond(input)
    }

    companion object {

        /** 恒定返回某个分类。 */
        fun alwaysDecide(
            category: String,
            confidence: Float = 0.99f,
        ): FakeBackend = FakeBackend { input ->
            BackendOutcome.Decided(
                category = category,
                confidence = confidence,
                probabilities = input.categories.associate { it.name to 0f } + (category to confidence),
            )
        }

        /** 恒定失败，用于验证降级路径。 */
        fun alwaysFail(
            reason: FailureReason = FailureReason.NETWORK,
        ): FakeBackend = FakeBackend { BackendOutcome.Failed(reason, "fake failure for tests") }
    }
}
