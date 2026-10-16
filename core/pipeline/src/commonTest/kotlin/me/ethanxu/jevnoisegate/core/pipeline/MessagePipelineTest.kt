package me.ethanxu.jevnoisegate.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import me.ethanxu.jevnoisegate.core.decision.*
import me.ethanxu.jevnoisegate.core.model.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagePipelineTest {
    @Test fun `queue and backend durations split and gauges return to zero after timeouts`() = runTest {
        val clock = me.ethanxu.jevnoisegate.core.common.Clock { testScheduler.currentTime }
        val backend = object : DecisionBackend {
            override suspend fun decide(input: DecisionInput): BackendOutcome {
                delay(50)
                return BackendOutcome.Decided("正常", 1f)
            }
        }
        val pipeline = MessagePipeline(backend, clock, deadlineMs = 75, maxConcurrentRequests = 1)
        val results = listOf(async { pipeline.process(input("普通消息A")) }, async { pipeline.process(input("普通消息B")) }).awaitAll()
        assertEquals(0L, results[0].queueMs)
        assertEquals(50L, results[0].backendMs)
        assertEquals(50L, results[1].queueMs)
        assertEquals(25L, results[1].backendMs)
        assertEquals(FailureReason.MODEL_UNAVAILABLE, results[1].decision.failureReason)
        val snapshot = me.ethanxu.jevnoisegate.core.common.PerformanceMetrics.snapshot()
        assertEquals(0, snapshot.active)
        assertEquals(0, snapshot.waiting)
    }
    private fun input(body: String, title: String? = null, blacklist: List<String> = emptyList()) = DecisionInput(
        MessageEvent("1", EventSource.SMS, 1, "sms", senderKey = "10691234", title = title, body = body),
        ChannelContext("sms", null), DefaultCategories.all(), smsSenderBlacklist = blacklist,
    )

    @Test fun `OTP title and split fields never reach backend even with blacklist`() = runTest {
        val backend = FakeBackend.alwaysDecide("广告", 1f)
        val pipeline = MessagePipeline(backend)
        listOf(
            input("请勿向他人透露", "验证码：123456", listOf("1069")),
            input("123456", "验证码"), input("您的验证码为123456"),
            input("Your code is A1B2C3"), input("Your login code: 123456"),
        ).forEach {
            val result = pipeline.process(it)
            assertEquals(Action.ALLOW, result.decision.action)
            assertEquals(DecidedBy.OTP_GATE, result.decision.decidedBy)
            assertFalse(result.backendAttempted)
        }
        assertTrue(backend.received.isEmpty())
    }

    @Test fun `blacklist blocks locally but user correction takes precedence`() = runTest {
        val backend = FakeBackend.alwaysDecide("正常")
        val pipeline = MessagePipeline(backend)
        val message = input("限时优惠立即领取", blacklist = listOf("1069"))
        assertEquals(DecidedBy.BLACKLIST, pipeline.process(message).decision.decidedBy)
        assertEquals(DecidedBy.USER, pipeline.process(message.copy(userAllowed = true)).decision.decidedBy)
        assertTrue(backend.received.isEmpty())
    }

    @Test fun `deadline includes retries and queue wait`() = runTest {
        val backend = object : DecisionBackend {
            override suspend fun decide(input: DecisionInput): BackendOutcome {
                delay(60_000)
                return BackendOutcome.Decided("广告", 1f)
            }
        }
        val pipeline = MessagePipeline(backend, deadlineMs = 100, maxConcurrentRequests = 1)
        val results = listOf(async { pipeline.process(input("广告A")) }, async { pipeline.process(input("广告B")) }).awaitAll()
        results.forEach {
            assertEquals(Action.ALLOW, it.decision.action)
            assertEquals(FailureReason.MODEL_UNAVAILABLE, it.decision.failureReason)
        }
        assertEquals(1, results.count { it.backendAttempted })
    }

    @Test fun `backend concurrency remains bounded and all waiting work completes`() = runTest {
        var active = 0
        var maximum = 0
        var completed = 0
        val backend = object : DecisionBackend {
            override suspend fun decide(input: DecisionInput): BackendOutcome {
                active++
                maximum = maxOf(maximum, active)
                try { delay(20); completed++; return BackendOutcome.Decided("正常", 1f) }
                finally { active-- }
            }
        }
        val pipeline = MessagePipeline(backend, deadlineMs = 1_000, maxConcurrentRequests = 4)
        List(12) { async { pipeline.process(input("普通消息$it")) } }.awaitAll()
        assertEquals(4, maximum)
        assertEquals(12, completed)
        assertEquals(0, active)
    }

    @Test fun `unexpected backend exception fails open and external cancellation propagates`() = runTest {
        val broken = object : DecisionBackend {
            override suspend fun decide(input: DecisionInput): BackendOutcome = error("malformed response")
        }
        assertEquals(Action.ALLOW, MessagePipeline(broken).process(input("广告")).decision.action)
        val entered = CompletableDeferred<Unit>()
        val slow = object : DecisionBackend {
            override suspend fun decide(input: DecisionInput): BackendOutcome {
                entered.complete(Unit); delay(60_000); return BackendOutcome.Decided("广告", 1f)
            }
        }
        val task = async { MessagePipeline(slow).process(input("广告")) }
        entered.await(); task.cancel()
        assertFailsWith<CancellationException> { task.await() }
    }
}
