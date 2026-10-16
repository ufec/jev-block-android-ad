package me.ethanxu.jevnoisegate.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.DefaultCategories
import me.ethanxu.jevnoisegate.core.decision.FakeBackend
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.ChannelContext
import me.ethanxu.jevnoisegate.core.model.DecidedBy
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent

class LocalPipelineTest {
    private fun input(body: String) = DecisionInput(
        event = MessageEvent("test", EventSource.NOTIFICATION, 1, "test.app", body = body),
        context = ChannelContext("test.app", null),
        categories = DefaultCategories.all(),
    )

    @Test
    fun `OTP bypasses model and remains local`() = runTest {
        val backend = FakeBackend.alwaysDecide("广告")
        val result = MessagePipeline(backend).process(input("您的验证码是 123456，五分钟内有效"))
        assertEquals(Action.ALLOW, result.decision.action)
        assertEquals(DecidedBy.OTP_GATE, result.decision.decidedBy)
        assertTrue(backend.received.isEmpty())
        assertFalse(result.backendAttempted)
    }

    @Test
    fun `model decision and model failure both remain local`() = runTest {
        val message = input("限时优惠，全场五折")
        val classified = MessagePipeline(FakeBackend.alwaysDecide("广告")).process(message)
        assertEquals(Action.QUARANTINE, classified.decision.action)
        assertTrue(classified.backendAttempted)
        val failed = MessagePipeline(FakeBackend.alwaysFail()).process(message)
        assertEquals(Action.ALLOW, failed.decision.action)
        assertEquals(DecidedBy.FALLBACK, failed.decision.decidedBy)
        // 模型失败同样算"运行过模型"：它进了模型、拿到了失败结论，
        // 与验证码、黑名单这类根本不进模型的路径必须区分开。
        assertTrue(failed.backendAttempted)
    }
}
