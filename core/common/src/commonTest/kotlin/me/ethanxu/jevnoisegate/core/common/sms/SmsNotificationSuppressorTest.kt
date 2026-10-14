package me.ethanxu.jevnoisegate.core.common.sms

import kotlin.test.Test
import kotlin.test.assertEquals

class SmsNotificationSuppressorTest {
    @Test fun `restoring 128 unmatched decisions makes only one batch retry`() {
        val pending = PendingSmsSuppressions { 1L }
        var rounds = 0
        var entries = 0
        pending.attachBatch { rounds++; entries = it.size; emptySet() }
        pending.restore((0..127).map { PendingSmsSuppression("1069", "message-$it", 100) })
        assertEquals(1, rounds)
        assertEquals(128, entries)
        pending.retryPending()
        assertEquals(2, rounds)
    }
    @Test fun `entries that expire while an earlier callback runs cannot cancel notifications`() {
        var now = 1L
        val pending = PendingSmsSuppressions { now }
        pending.suppress("1069", "first", expiresAtMs = 100)
        pending.suppress("1069", "second", expiresAtMs = 10)
        val bodies = mutableListOf<String>()
        pending.attach { _, body ->
            bodies += body
            now = 25
            true
        }
        assertEquals(listOf("first"), bodies)
    }
    @Test fun `older replay cannot shorten a fresh suppression deadline`() {
        var now = 1L
        val pending = PendingSmsSuppressions { now }
        pending.suppress("1069", "相同正文", expiresAtMs = 100)
        pending.suppress("1069", "相同正文", expiresAtMs = 20)
        now = 25
        var cancelled = 0
        pending.attach { _, _ -> cancelled++; true }
        assertEquals(1, cancelled)
    }

    @Test fun `completed callback cannot remove a newer decision accepted during that callback`() {
        val pending = PendingSmsSuppressions { 1L }
        pending.attach { sender, body ->
            pending.detach()
            pending.suppress(sender, body, expiresAtMs = 200)
            pending.attach { _, _ -> false }
            true
        }
        pending.suppress("1069", "相同正文", expiresAtMs = 100)
        var cancelled = 0
        pending.attach { _, _ -> cancelled++; true }
        assertEquals(1, cancelled)
    }

    @Test fun `expired entries are evicted and queue remains bounded to most recent 128 entries`() {
        var now = 1L
        val pending = PendingSmsSuppressions { now }
        repeat(130) { pending.suppress("1069", "message-$it", expiresAtMs = 100) }
        val bodies = mutableListOf<String>()
        pending.attach { _, body -> bodies += body; true }
        assertEquals(128, bodies.size)
        assertEquals("message-2", bodies.first())
        pending.detach()
        pending.suppress("1069", "已过期正文", expiresAtMs = 10)
        now = 10
        pending.attach { _, _ -> error("Expired entries must not reach the canceller") }
    }
    @Test fun `expired suppression is never restored after process restart`() {
        var cancelled = 0
        SmsNotificationSuppressor.attach { _, _ -> cancelled++; true }
        SmsNotificationSuppressor.suppress("1069", "已过期的拦截结果", expiresAtMs = 0)
        SmsNotificationSuppressor.retryPending()
        assertEquals(0, cancelled)
        SmsNotificationSuppressor.detach()
    }
    @Test fun `decision made before notification appears is retried after posting`() {
        var visible = false
        var cancelled = 0
        val body = "广告内容用于待投递测试"
        SmsNotificationSuppressor.attach { _, shown ->
            if (shown == body && visible) { cancelled++; true } else false
        }
        SmsNotificationSuppressor.suppress("10691234", body)
        assertEquals(0, cancelled)
        visible = true
        SmsNotificationSuppressor.retryPending()
        assertEquals(1, cancelled)
        SmsNotificationSuppressor.retryPending()
        assertEquals(1, cancelled)
        SmsNotificationSuppressor.detach()
    }
}
