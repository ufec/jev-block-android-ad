package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.FailureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecisionMapperTest {

    private fun map(
        outcome: BackendOutcome,
        input: DecisionInput = Fixtures.input(),
    ) = DecisionMapper.map(input, outcome)

    // -----------------------------------------------------------------------
    // 正常路径
    // -----------------------------------------------------------------------

    @Test
    fun `high confidence ad is quarantined`() {
        val decision = map(BackendOutcome.Decided("广告", confidence = 0.95f))

        assertEquals(Action.QUARANTINE, decision.action)
        assertEquals(DecidedBy.MODEL, decision.decidedBy)
        assertEquals("广告", decision.category)
        assertEquals(0.95f, decision.confidence)
    }

    @Test
    fun `high confidence normal is allowed`() {
        val decision = map(BackendOutcome.Decided("正常", confidence = 0.95f))
        assertEquals(Action.ALLOW, decision.action)
        assertEquals(DecidedBy.MODEL, decision.decidedBy)
    }

    @Test
    fun `confidence exactly at the threshold acts`() {
        // 语义是 >= 阈值才执行动作，边界值必须落在"执行"这一侧。
        val decision = map(BackendOutcome.Decided("广告", confidence = 0.9f))
        assertEquals(Action.QUARANTINE, decision.action)
    }

    // -----------------------------------------------------------------------
    // fail-open：所有不确定路径一律放行
    // -----------------------------------------------------------------------

    @Test
    fun `confidence below threshold falls back to allow`() {
        val decision = map(BackendOutcome.Decided("广告", confidence = 0.89f))

        assertEquals(Action.ALLOW, decision.action)
        assertEquals(DecidedBy.MODEL, decision.decidedBy)
        // 仍记录模型的原始判断，便于诊断页解释"为什么这次没拦"
        assertEquals("广告", decision.category)
        assertEquals(0.89f, decision.confidence)
    }

    @Test
    fun `NaN confidence must not act`() {
        // 这是最容易漏掉的一条：NaN 参与的任何比较都是 false，
        // 若只写 `confidence < threshold`，NaN 会掉进"执行动作"分支，
        // 用垃圾数据去拦截用户的消息 —— 正好是 fail-open 的反面。
        val decision = map(BackendOutcome.Decided("广告", confidence = Float.NaN))
        assertEquals(Action.ALLOW, decision.action)
    }

    @Test
    fun `infinite confidence must not act`() {
        assertEquals(Action.ALLOW, map(BackendOutcome.Decided("广告", confidence = Float.POSITIVE_INFINITY)).action)
        assertEquals(Action.ALLOW, map(BackendOutcome.Decided("广告", confidence = Float.NEGATIVE_INFINITY)).action)
    }

    @Test
    fun `unknown category falls back to allow`() {
        // 答案空间由 criteria 定义，正常不该出现未知标签。一旦出现说明契约被破坏。
        val decision = map(BackendOutcome.Decided("完全不存在的分类", confidence = 0.99f))

        assertEquals(Action.ALLOW, decision.action)
        assertEquals("完全不存在的分类", decision.category)
    }

    @Test
    fun `empty confidence with an otherwise valid category falls back to allow`() {
        assertEquals(
            Action.ALLOW,
            map(BackendOutcome.Decided("广告", confidence = 0f)).action,
        )
    }

    // -----------------------------------------------------------------------
    // 后端失败：降级放行，并保留原因
    // -----------------------------------------------------------------------

    @Test
    fun `every failure reason degrades to allow`() {
        FailureReason.entries.forEach { reason ->
            val decision = map(BackendOutcome.Failed(reason))
            assertEquals(Action.ALLOW, decision.action, "reason=$reason 必须放行")
            assertEquals(DecidedBy.FALLBACK, decision.decidedBy, "reason=$reason")
            assertEquals(reason, decision.failureReason, "reason=$reason")
            assertNull(decision.category)
        }
    }

    @Test
    fun `failure keeps the original event for the recycle bin and diagnostics`() {
        val input = Fixtures.input()
        val decision = map(BackendOutcome.Failed(FailureReason.TIMEOUT), input)
        assertEquals(input.event, decision.event)
    }

    // -----------------------------------------------------------------------
    // 安全底线
    // -----------------------------------------------------------------------

    @Test
    fun `otp is never suppressed regardless of confidence`() {
        // 验证码类配置为 minConfidence = 0 且 action = ALLOW，因此无论模型
        // 多确定，都不可能被拦截。这条断言是整个系统的安全底线。
        listOf(0f, 0.5f, 0.9f, 1f).forEach { confidence ->
            val decision = map(BackendOutcome.Decided("验证码", confidence = confidence))
            assertEquals(Action.ALLOW, decision.action, "confidence=$confidence")
        }
    }

    @Test
    fun `no code path can produce a delete-like action`() {
        // 动作层只有三种，且都可撤销。这条断言防止将来有人加进破坏性动作。
        assertEquals(
            listOf(Action.ALLOW, Action.SILENT_SUPPRESS, Action.QUARANTINE),
            Action.entries.toList(),
        )
    }

    @Test
    fun `latency is carried through for the diagnostics page`() {
        val decision = DecisionMapper.map(
            Fixtures.input(),
            BackendOutcome.Decided("正常", 0.9f),
            latencyMillis = 412L,
        )
        assertEquals(412L, decision.latencyMillis)
    }
}
