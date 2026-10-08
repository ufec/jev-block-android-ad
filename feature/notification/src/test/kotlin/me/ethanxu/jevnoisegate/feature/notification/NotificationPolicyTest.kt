package me.ethanxu.jevnoisegate.feature.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun `identical pending text preserves decision but changed title replaces it`() {
        val versions = NotificationGenerations()
        val first = versions.begin("key", "订单", "处理进度")
        assertTrue(versions.matchesPending("key", "订单", "处理进度"))
        assertTrue(versions.isCurrent("key", first))
        assertFalse(versions.matchesPending("key", "促销", "处理进度"))
        versions.begin("key", "促销", "处理进度")
        assertFalse(versions.isCurrent("key", first))
        versions.remove("key")
        assertFalse(versions.matchesPending("key", "促销", "处理进度"))
    }
    @Test fun `muted app never enters processing even in developer mode`() {
        assertFalse(NotificationPolicy.shouldProcess("bank", "own", true, setOf("bank")))
        assertTrue(NotificationPolicy.shouldProcess("bank", "own", false, emptySet()))
        assertFalse(NotificationPolicy.shouldProcess("own", "own", false, emptySet()))
    }
    @Test fun `old result cannot cancel a newer or reposted notification`() {
        val versions = NotificationGenerations()
        val ad = versions.begin("key")
        val normal = versions.begin("key")
        assertFalse(versions.isCurrent("key", ad))
        assertTrue(versions.isCurrent("key", normal))
        versions.remove("key")
        val repost = versions.begin("key")
        assertFalse(versions.isCurrent("key", normal))
        assertTrue(versions.isCurrent("key", repost))
    }
    @Test fun `changed title is distinct and removal clears dedup`() {
        var now = 100L
        val cache = RecentPostCache(nowMillis = { now })
        assertTrue(cache.markSeen("key", "正文", "订单A"))
        now += 1
        assertTrue(cache.markSeen("key", "正文", "订单B"))
        cache.forget("key")
        assertTrue(cache.markSeen("key", "正文", "订单B"))
    }
    @Test fun `aggregate and unrelated SMS notifications cannot match`() {
        val body = "这是完整的营销短信正文请立即领取"
        assertTrue(SmsNotificationMatcher.matches(body, body, "10691234", "10691234", false))
        assertFalse(SmsNotificationMatcher.matches(body, body, "10691234", "13800138000", false))
        assertFalse(SmsNotificationMatcher.matches(body, body, "10691234", null, true))
        assertFalse(SmsNotificationMatcher.matches(body, "营销短信正文", "10691234", null, false))
        assertFalse(SmsNotificationMatcher.matches(body, "$body\n正常消息", "10691234", null, false))
    }
}
