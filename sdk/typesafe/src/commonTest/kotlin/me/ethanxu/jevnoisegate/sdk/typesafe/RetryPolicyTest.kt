package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class RetryPolicyTest {

    private companion object {
        /**
         * `Wed, 21 Oct 2026 07:28:00 GMT` 的纪元毫秒，独立推算而非由被测实现得出：
         * 1970-01-01 至 2026-01-01 为 56×365 + 14 个闰日 = 20454 天；
         * 再至 10-21 为 293 天，共 20747 天 = 1,792,540,800 秒，加 07:28:00 的 26,880 秒。
         * 校验：20747 mod 7 = 6，纪元为周四，+6 天即周三，与字符串中的 `Wed` 自洽。
         */
        const val HTTP_DATE_2026_10_21 = 1_792_567_680_000L
    }

    // -----------------------------------------------------------------------
    // parseRetryAfter
    // -----------------------------------------------------------------------

    @Test
    fun `prefers retry-after-ms over retry-after`() {
        val parsed = parseRetryAfter(
            mapOf("retry-after-ms" to "1500", "retry-after" to "9"),
            nowMs = 0,
        )
        assertEquals(1500L, parsed)
    }

    @Test
    fun `parses retry-after as seconds`() {
        assertEquals(3000L, parseRetryAfter(mapOf("retry-after" to "3"), nowMs = 0))
    }

    @Test
    fun `parses retry-after as an http date`() {
        val target = "Wed, 21 Oct 2026 07:28:00 GMT"
        val parsed = parseRetryAfter(mapOf("retry-after" to target), nowMs = HTTP_DATE_2026_10_21 - 5_000)
        assertEquals(5_000L, parsed)
    }

    @Test
    fun `clamps a past http date to zero`() {
        assertEquals(
            0L,
            parseRetryAfter(
                mapOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT"),
                nowMs = HTTP_DATE_2026_10_21 + 10_000,
            ),
        )
    }

    @Test
    fun `http date parser matches independently known epoch values`() {
        // 这三个值由公开定义独立得出，不经由被测实现，因此能真正验证日期换算：
        //   纪元定义         → 0
        //   Y2K              → 946684800000
        //   2024-01-01T00:00Z → 1704067200000
        assertEquals(0L, parseHttpDateToEpochMillis("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(946_684_800_000L, parseHttpDateToEpochMillis("Sat, 01 Jan 2000 00:00:00 GMT"))
        assertEquals(1_704_067_200_000L, parseHttpDateToEpochMillis("Mon, 01 Jan 2024 00:00:00 GMT"))
    }

    @Test
    fun `http date parser handles leap day`() {
        // 2024 是闰年：2024-02-29T00:00:00Z 应为 1709164800000
        assertEquals(1_709_164_800_000L, parseHttpDateToEpochMillis("Thu, 29 Feb 2024 00:00:00 GMT"))
    }

    @Test
    fun `http date parser rejects malformed input`() {
        assertNull(parseHttpDateToEpochMillis("not a date"))
        assertNull(parseHttpDateToEpochMillis("Wed, 21 Foo 2026 07:28:00 GMT"))
        assertNull(parseHttpDateToEpochMillis("Wed, 21 Oct 2026 25:28:00 GMT"))
        assertNull(parseHttpDateToEpochMillis(""))
    }

    @Test
    fun `returns null when neither header is usable`() {
        assertNull(parseRetryAfter(emptyMap(), nowMs = 0))
        assertNull(parseRetryAfter(mapOf("retry-after" to "not-a-delay"), nowMs = 0))
        assertNull(parseRetryAfter(mapOf("retry-after-ms" to "-5"), nowMs = 0))
    }

    @Test
    fun `negative retry-after seconds are rejected`() {
        assertNull(parseRetryAfter(mapOf("retry-after" to "-1"), nowMs = 0))
    }

    // -----------------------------------------------------------------------
    // retryDelayMs
    // -----------------------------------------------------------------------

    private val noJitter = RetryPolicy(backoffInitialMs = 500, backoffMaxMs = 5_000, backoffJitter = 0.0)

    @Test
    fun `honours retry-after when under the cap`() {
        val delay = retryDelayMs(
            attempt = 0,
            retryAfterMs = 1_200,
            policy = noJitter,
            random = { 0.0 },
        )
        assertEquals(1_200L, delay)
    }

    @Test
    fun `ignores retry-after when it exceeds the cap`() {
        val delay = retryDelayMs(
            attempt = 1,
            retryAfterMs = 120_000,
            policy = noJitter,
            random = { 0.0 },
        )
        // 退回指数退避：500 * 2^1 = 1000
        assertEquals(1_000L, delay)
    }

    @Test
    fun `ignores retry-after when the policy opts out`() {
        val policy = noJitter.copy(respectRetryAfter = false)
        val delay = retryDelayMs(attempt = 0, retryAfterMs = 1_200, policy = policy, random = { 0.0 })
        assertEquals(500L, delay)
    }

    @Test
    fun `exponential backoff doubles per attempt`() {
        val delays = (0..3).map { attempt ->
            retryDelayMs(attempt, retryAfterMs = null, policy = noJitter, random = { 0.0 })
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun `backoff is capped at backoffMaxMs`() {
        val delay = retryDelayMs(attempt = 10, retryAfterMs = null, policy = noJitter, random = { 0.0 })
        assertEquals(5_000L, delay)
    }

    @Test
    fun `jitter only ever reduces the delay`() {
        val policy = noJitter.copy(backoffJitter = 0.25)
        val full = retryDelayMs(2, null, policy) { 0.0 }
        val jittered = retryDelayMs(2, null, policy) { 1.0 }

        assertEquals(2_000L, full)
        assertEquals(1_500L, jittered) // 2000 * (1 - 1.0 * 0.25)
        assertTrue(jittered < full)
    }

    // -----------------------------------------------------------------------
    // 策略校验
    // -----------------------------------------------------------------------

    @Test
    fun `defaults match the upstream sdk`() {
        val policy = RetryPolicy()
        assertEquals(2, policy.maxRetries)
        assertEquals(500L, policy.backoffInitialMs)
        assertEquals(5_000L, policy.backoffMaxMs)
        assertEquals(0.25, policy.backoffJitter, 1e-9)
        assertEquals(60_000L, policy.maxRetryAfterMs)
        assertTrue(policy.respectRetryAfter)
        assertTrue(policy.apiConnectionError)
        assertTrue(policy.apiTimeoutError)
        assertEquals(DEFAULT_TIMEOUT_MS, 10_000L)

        // 408、429 与所有 5xx
        assertTrue(policy.isRetryableStatus(408))
        assertTrue(policy.isRetryableStatus(429))
        assertTrue(policy.isRetryableStatus(500))
        assertTrue(policy.isRetryableStatus(599))
        assertTrue(!policy.isRetryableStatus(400))
        assertTrue(!policy.isRetryableStatus(401))
        assertTrue(!policy.isRetryableStatus(404))
        assertEquals(102, policy.httpStatuses.size)
    }

    @Test
    fun `rejects invalid policy values`() {
        assertTrue(runCatching { RetryPolicy(maxRetries = -1) }.exceptionOrNull() is TypeSafeException)
        assertTrue(runCatching { RetryPolicy(backoffJitter = 1.5) }.exceptionOrNull() is TypeSafeException)
        assertTrue(runCatching { RetryPolicy(httpStatuses = setOf(42)) }.exceptionOrNull() is TypeSafeException)
    }
}
