package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.typesafe.sdk.APIConnectionException
import me.ethanxu.typesafe.sdk.APITimeoutException
import me.ethanxu.typesafe.sdk.AuthenticationException
import me.ethanxu.typesafe.sdk.ChoiceAnswer
import me.ethanxu.typesafe.sdk.InternalServerException
import me.ethanxu.typesafe.sdk.PermissionDeniedException
import me.ethanxu.typesafe.sdk.RateLimitException
import me.ethanxu.typesafe.sdk.TypeSafeClient
import me.ethanxu.typesafe.sdk.TypeSafeException
import kotlinx.coroutines.CancellationException

/**
 * 基于 TypeSafe Jev 的判断后端。
 *
 * 只负责**一次请求的往返与错误归类**，不做任何策略判断 ——
 * 动作映射一律交给 [DecisionMapper]。
 */
class TypeSafeBackend(
    private val client: TypeSafeClient,
) : DecisionBackend {

    override suspend fun decide(input: DecisionInput): BackendOutcome {
        val question = try {
            QuestionFactory.categoryQuestion(input.categories)
        } catch (e: IllegalArgumentException) {
            // 配置非法，请求根本没发出去。这不是"模型判断失败"而是"我们没配好"，
            // 诊断页上需要区分这两种情况。
            return BackendOutcome.Failed(FailureReason.NOT_CONFIGURED, e.message)
        }

        return try {
            val result = client.systemOne(StateBuilder.build(input)) { ask(question) }
            val answer: ChoiceAnswer = result.answer(question)
            BackendOutcome.Decided(
                category = answer.choice,
                confidence = answer.confidence.toFloat(),
                probabilities = answer.probabilities.mapValues { it.value.toFloat() },
            )
        } catch (e: CancellationException) {
            // 协程取消是控制流信号，必须原样传播，否则破坏结构化并发。
            throw e
        } catch (e: APITimeoutException) {
            // 注意顺序：APITimeoutException 继承自 APIConnectionException，
            // 必须排在它前面，否则永远匹配不到。
            BackendOutcome.Failed(FailureReason.TIMEOUT, e.message)
        } catch (e: RateLimitException) {
            BackendOutcome.Failed(FailureReason.RATE_LIMITED, e.message)
        } catch (e: AuthenticationException) {
            BackendOutcome.Failed(FailureReason.AUTH_FAILED, e.message)
        } catch (e: PermissionDeniedException) {
            BackendOutcome.Failed(FailureReason.AUTH_FAILED, e.message)
        } catch (e: InternalServerException) {
            BackendOutcome.Failed(FailureReason.SERVER_ERROR, e.message)
        } catch (e: APIConnectionException) {
            BackendOutcome.Failed(FailureReason.NETWORK, e.message)
        } catch (e: TypeSafeException) {
            BackendOutcome.Failed(FailureReason.INVALID_RESPONSE, e.message)
        }
    }
}
