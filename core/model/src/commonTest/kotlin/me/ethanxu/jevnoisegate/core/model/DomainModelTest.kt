package me.ethanxu.jevnoisegate.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 校验 KMP 测试基础设施可用（commonTest → androidHostTest 已连通），
 * 同时把领域模型的几个不变量固定下来。
 */
class DomainModelTest {

    private fun notificationEvent(
        id: String = "n1",
        packageName: String = "com.example.shop",
        channelId: String? = "promo",
        body: String = "【某某商城】限时优惠，全场五折",
    ) = MessageEvent(
        id = id,
        source = EventSource.NOTIFICATION,
        timestampMs = 1_700_000_000_000,
        packageName = packageName,
        channelId = channelId,
        senderKey = null,
        title = "某某商城",
        body = body,
    )

    private fun smsEvent(
        senderKey: String? = "+8613800000000",
        packageName: String = "com.android.messaging",
        body: String = "【某某银行】您的验证码是 123456",
    ) = MessageEvent(
        id = "s1",
        source = EventSource.SMS,
        timestampMs = 1_700_000_000_000,
        packageName = packageName,
        channelId = null,
        senderKey = senderKey,
        title = null,
        body = body,
    )

    // -----------------------------------------------------------------------
    // 分桶键 —— 指纹算法的主键，决定了哪些消息会命中同一条缓存决策
    // -----------------------------------------------------------------------

    @Test
    fun `notification buckets by package and channel`() {
        // 同一个 App 的"营销活动"与"订单物流"必须是两个不同的模板族，
        // 否则一次误判会污染该 App 的全部通知。
        val promo = notificationEvent(channelId = "promo")
        val logistics = notificationEvent(channelId = "logistics")
        assertEquals("com.example.shop|promo", promo.bucketKey)
        assertEquals("com.example.shop|logistics", logistics.bucketKey)
        assertTrue(promo.bucketKey != logistics.bucketKey)
    }

    @Test
    fun `notification without channel falls back to package`() {
        assertEquals("com.example.shop", notificationEvent(channelId = null).bucketKey)
    }

    @Test
    fun `sms buckets by sender number not by sms app package`() {
        // 关键设计：同一模板往往由同一号码反复发送，用发件人做桶才能让
        // "这个号码的发文模板"聚到一起。若按短信应用包名分桶，
        // 所有短信会挤进同一个桶，指纹算法直接失效。
        val fromBank = smsEvent(senderKey = "+8613800000000")
        val fromSpammer = smsEvent(senderKey = "+8613900000000")
        assertEquals("+8613800000000", fromBank.bucketKey)
        assertTrue(fromBank.bucketKey != fromSpammer.bucketKey)

        // 同一个号码、不同内容 → 同一个桶，由指纹算法再做模板归一化
        val sameSenderDifferentBody = smsEvent(body = "【某某银行】您的验证码是 999999")
        assertEquals(fromBank.bucketKey, sameSenderDifferentBody.bucketKey)
    }

    @Test
    fun `sms without sender number falls back to package`() {
        assertEquals("com.android.messaging", smsEvent(senderKey = null).bucketKey)
    }

    // -----------------------------------------------------------------------
    // 渠道统计
    // -----------------------------------------------------------------------

    @Test
    fun `noiseRatio is zero before any sample`() {
        val context = ChannelContext(packageName = "com.example.shop", channelId = "promo")
        assertEquals(0f, context.noiseRatio)
    }

    @Test
    fun `noiseRatio is suppressed over total`() {
        val context = ChannelContext(
            packageName = "com.example.shop",
            channelId = "promo",
            totalSeen = 30,
            suppressedCount = 27,
        )
        assertEquals(0.9f, context.noiseRatio)
    }

    @Test
    fun `restored events do not reduce total sample size`() {
        // 用户把 3 条从回收站捞回来，说明它们不应被静默；分母仍是"见过的全部"，
        // 因为噪音比例的用途是判断这个渠道值不值得整体降噪。
        val context = ChannelContext(
            packageName = "com.example.shop",
            channelId = "promo",
            totalSeen = 10,
            suppressedCount = 4,
            restoredCount = 3,
        )
        assertEquals(0.4f, context.noiseRatio)
    }

    // -----------------------------------------------------------------------
    // 安全底线
    // -----------------------------------------------------------------------

    @Test
    fun `sms event carries no channel but carries sender`() {
        val event = smsEvent()
        assertNull(event.channelId)
        assertEquals("+8613800000000", event.senderKey)
    }

    @Test
    fun `otp category defaults to never suppress`() {
        // 这是整个系统的安全底线：验证码类的置信度阈值必须是 0，即无论模型多不确定都放行。
        // 漏放一条广告是无感，错拦一条验证码会让用户被锁在银行外面。
        val otp = CategoryConfig(
            name = "验证码",
            description = "包含账号验证码、校验码、动态口令",
            keywords = listOf("验证码", "校验码", "动态码", "OTP"),
            action = Action.ALLOW,
            minConfidence = 0f,
        )
        assertEquals(Action.ALLOW, otp.action)
        assertTrue(otp.minConfidence <= 0f)
    }

    @Test
    fun `action enum exposes no destructive option`() {
        // 动作层刻意不提供"删除"：Android 上第三方 App 既删不掉短信，
        // 也无法阻止通知已经发出的声音与震动。三者都必须可撤销。
        assertEquals(
            listOf(Action.ALLOW, Action.SILENT_SUPPRESS, Action.QUARANTINE),
            Action.entries.toList(),
        )
    }
}
