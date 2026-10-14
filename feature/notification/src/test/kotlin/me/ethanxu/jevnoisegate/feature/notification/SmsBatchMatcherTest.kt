package me.ethanxu.jevnoisegate.feature.notification

import me.ethanxu.jevnoisegate.core.common.sms.PendingSmsSuppression
import org.junit.Assert.*
import org.junit.Test

class SmsBatchMatcherTest {
    @Test fun `indexed candidates preserve exact truncated ambiguity sender and group protections`() {
        val body = "这是一条足够长的完整广告内容请立即领取优惠"
        val entry = PendingSmsSuppression("10691234", body, 100)
        val preview = SmsNotificationCandidate("preview", body.take(16) + "…", "1234", false)
        val exact = preview.copy(key = "exact", body = body)
        fun matches(candidates: List<SmsNotificationCandidate>): Set<PendingSmsSuppression> =
            matchSmsBatch(listOf(entry), { candidates }, { 1 }, { true })
        assertEquals(setOf(entry), matches(listOf(preview)))
        assertTrue(matches(listOf(preview, exact)).isEmpty())
        assertEquals(setOf(entry), matches(listOf(preview.copy(title = "9999"), exact)))
        assertTrue(matches(listOf(preview.copy(grouped = true))).isEmpty())
        assertTrue(matches(listOf(preview.copy(body = "短正文…"))).isEmpty())
        // Literal ellipsis in a full SMS must not count the same candidate twice.
        val literal = entry.copy(body = preview.body)
        assertEquals(setOf(literal), matchSmsBatch(listOf(literal), { listOf(preview) }, { 1 }, { true }))
    }

    @Test fun `batch index agrees with original matcher over mixed candidate sets`() {
        val random = kotlin.random.Random(31)
        repeat(300) {
            val body = "这是相同的长消息前缀用于截断候选" + random.nextInt(4)
            val sender = if (random.nextBoolean()) "10691234" else null
            val pending = PendingSmsSuppression(sender, body, 100)
            val candidates = (0 until random.nextInt(1, 8)).map { index ->
                val shown = when (random.nextInt(4)) {
                    0 -> body
                    1 -> body.take(16) + "..."
                    2 -> "不匹配的消息正文"
                    else -> "短正文"
                }
                SmsNotificationCandidate("$index", shown, if (random.nextBoolean()) "1234" else "9999", random.nextInt(5) == 0)
            }
            val expected = candidates.filter { SmsNotificationMatcher.matches(body, it.body, sender, it.title, it.grouped) }.singleOrNull()?.key
            var actual: String? = null
            matchSmsBatch(listOf(pending), { candidates }, { 1 }, { actual = it; true })
            assertEquals(expected, actual)
        }
    }
    @Test fun `128 unmatched messages read active notifications once`() {
        var reads = 0
        val matched = matchSmsBatch((0..127).map { PendingSmsSuppression("1069", "message-$it", 100) },
            load = { reads++; listOf(SmsNotificationCandidate("key", "unrelated", "1069", false)) },
            nowMs = { 1 }, cancel = { error("must not cancel") })
        assertEquals(1, reads)
        assertTrue(matched.isEmpty())
    }

    @Test fun `expired and ambiguous requests stay visible and unique matches cancel once`() {
        val body = "这是一条完整广告内容请立即领取优惠"
        val valid = PendingSmsSuppression("1069", body, 100)
        val expired = valid.copy(expiresAtMs = 0)
        val candidate = SmsNotificationCandidate("key", body, "1069", false)
        val cancelled = mutableListOf<String>()
        assertTrue(matchSmsBatch(listOf(valid), { listOf(candidate, candidate.copy(key = "other")) },
            { 1 }, { cancelled += it; true }).isEmpty())
        val result = matchSmsBatch(listOf(expired, valid, valid), { listOf(candidate) }, { 1 },
            { cancelled += it; true })
        assertEquals(setOf(valid), result)
        assertEquals(listOf("key"), cancelled)
    }
}
