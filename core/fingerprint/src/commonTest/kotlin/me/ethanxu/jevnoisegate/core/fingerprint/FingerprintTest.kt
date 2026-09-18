package me.ethanxu.jevnoisegate.core.fingerprint

import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FingerprintTest {

    private fun notification(
        packageName: String = "com.taobao.taobao",
        channelId: String = "marketing",
        body: String,
    ) = MessageEvent(
        id = "n",
        source = EventSource.NOTIFICATION,
        timestampMs = 1_700_000_000_000,
        packageName = packageName,
        channelId = channelId,
        body = body,
    )

    private fun sms(senderKey: String = "+8613800000000", body: String) = MessageEvent(
        id = "s",
        source = EventSource.SMS,
        timestampMs = 1_700_000_000_000,
        packageName = "com.android.messaging",
        senderKey = senderKey,
        body = body,
    )

    // -----------------------------------------------------------------------
    // 归一化：同一模板必须收敛到同一骨架
    // -----------------------------------------------------------------------

    @Test
    fun `same template with different amount shares a fingerprint`() {
        // 这是整个缓存机制成立的前提。如果金额不同就产生不同指纹，
        // 那么每一次促销推送都会打一次网络请求，延迟与成本双双失控。
        val cheap = "【淘宝】您关注的商品降价了，仅需￥199，点击查看"
        val pricey = "【淘宝】您关注的商品降价了，仅需￥299，点击查看"
        assertEquals(
            Fingerprint.of(notification(body = cheap)),
            Fingerprint.of(notification(body = pricey)),
        )
    }

    @Test
    fun `same template with different digits shares a fingerprint`() {
        val first = "【某某银行】您的验证码是 123456，5 分钟内有效"
        val second = "【某某银行】您的验证码是 999888，5 分钟内有效"
        assertEquals(Fingerprint.of(sms(body = first)), Fingerprint.of(sms(body = second)))
    }

    @Test
    fun `normalizes urls so different links share a fingerprint`() {
        val a = "【京东】您的专属优惠已到账 https://u.jd.com/abc123 点击领取"
        val b = "【京东】您的专属优惠已到账 https://u.jd.com/xyz789 点击领取"
        assertEquals(Fingerprint.of(notification(body = a)), Fingerprint.of(notification(body = b)))
    }

    @Test
    fun `normalizes bare domains and dates`() {
        val a = "限时活动 2026-09-18 截止，详见 example.com/promo"
        val b = "限时活动 2026-10-25 截止，详见 example.com/other"
        assertEquals(Fingerprint.of(notification(body = a)), Fingerprint.of(notification(body = b)))
    }

    @Test
    fun `skeleton keeps the textual template and drops values`() {
        assertEquals(
            "淘宝您关注的商品降价了仅需N点击查看",
            Fingerprint.skeletonOf("【淘宝】您关注的商品降价了，仅需￥199，点击查看"),
        )
        // 注意这里是两个 N：归一化保留数字**槽位**而不是把整段数值糊成一个 N。
        // 这带来必要的精度 ——「验证码是NN分钟内有效」与「验证码是N」是两个不同模板，
        // 不会被错误地归并到一起。
        assertEquals(
            "某某银行您的验证码是NN分钟内有效",
            Fingerprint.skeletonOf("【某某银行】您的验证码是 123456，5 分钟内有效"),
        )
    }

    @Test
    fun `skeleton normalizes digit width but preserves slot count`() {
        // 槽位结构相同的不同实例必须收敛
        assertEquals(
            Fingerprint.skeletonOf("您有 3 张优惠券将于 7 天后过期"),
            Fingerprint.skeletonOf("您有 5 张优惠券将于 9 天后过期"),
        )
        // 数字**宽度**不影响骨架：6 位验证码与 8 位验证码是同一个模板
        assertEquals(
            Fingerprint.skeletonOf("您的验证码是 123456，5 分钟内有效"),
            Fingerprint.skeletonOf("您的验证码是 12345678，15 分钟内有效"),
        )
        // 但槽位**数量**不同则是不同模板
        assertNotEquals(
            Fingerprint.skeletonOf("您的验证码是 123456，5 分钟内有效"),
            Fingerprint.skeletonOf("您的验证码是 123456"),
        )
    }

    // -----------------------------------------------------------------------
    // 分桶：不同来源绝不能共享指纹
    // -----------------------------------------------------------------------

    @Test
    fun `identical text from different apps must not collide`() {
        // 没有分桶键的话，这条文案在两个 App 之间碰撞，
        // 一次误判会污染另一个 App 的全部通知。
        val text = "限时优惠，点击查看"
        assertNotEquals(
            Fingerprint.of(notification(packageName = "com.taobao.taobao", body = text)),
            Fingerprint.of(notification(packageName = "com.jd.jdmobile", body = text)),
        )
    }

    @Test
    fun `same app different channels must not collide`() {
        // 同一个 App 的"营销活动"与"订单物流"是两个模板族。
        val text = "您的订单有新进展，点击查看"
        assertNotEquals(
            Fingerprint.of(notification(channelId = "marketing", body = text)),
            Fingerprint.of(notification(channelId = "logistics", body = text)),
        )
    }

    @Test
    fun `different senders must not collide even with identical text`() {
        val text = "【积分提醒】您有积分即将过期"
        assertNotEquals(
            Fingerprint.of(sms(senderKey = "+8613800000000", body = text)),
            Fingerprint.of(sms(senderKey = "+8613900000000", body = text)),
        )
    }

    @Test
    fun `same sender with same template keeps one fingerprint`() {
        // 短信按发件号码分桶的价值：同一号码反复发同一模板必须收敛，
        // 这样才能在第 N 次时判定"这个号码是稳定噪音源"。
        val a = sms(body = "【某某商城】您有 1 张优惠券待领取")
        val b = sms(body = "【某某商城】您有 3 张优惠券待领取")
        assertEquals(Fingerprint.of(a), Fingerprint.of(b))
    }

    // -----------------------------------------------------------------------
    // 边界
    // -----------------------------------------------------------------------

    @Test
    fun `empty body does not throw`() {
        assertEquals("", Fingerprint.skeletonOf(""))
        assertTrue(Fingerprint.of(notification(body = "")).isNotEmpty())
    }

    @Test
    fun `long body is truncated to a stable skeleton`() {
        val long = "A".repeat(200)
        assertEquals(TextNormalizer.MAX_SKELETON_LENGTH, Fingerprint.skeletonOf(long).length)
        // 截断后长度相同，但仍应因内容不同而区分
        assertNotEquals(
            Fingerprint.of(notification(body = "A".repeat(200))),
            Fingerprint.of(notification(body = "B".repeat(200))),
        )
    }

    @Test
    fun `fingerprint is a stable 16 char hex string`() {
        val fp = Fingerprint.of(notification(body = "任意内容"))
        assertEquals(16, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" })
        // 同一输入必须永远得到同一输出
        assertEquals(fp, Fingerprint.of(notification(body = "任意内容")))
    }
}
