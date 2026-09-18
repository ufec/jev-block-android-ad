package me.ethanxu.jevnoisegate.core.decision

import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.CategoryConfig
import me.ethanxu.jevnoisegate.core.model.ChannelContext
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent

/** 测试夹具：构造最小可用的 [DecisionInput]。 */
object Fixtures {

    fun categories(
        adMinConfidence: Float = 0.9f,
        adAction: Action = Action.QUARANTINE,
    ): List<CategoryConfig> = listOf(
        CategoryConfig(
            name = "验证码",
            description = "包含账号验证码、动态口令",
            keywords = listOf("验证码", "动态码"),
            action = Action.ALLOW,
            minConfidence = 0f,
        ),
        CategoryConfig(
            name = "广告",
            description = "推销商品与活动的营销信息",
            keywords = listOf("优惠", "促销"),
            action = adAction,
            minConfidence = adMinConfidence,
        ),
        CategoryConfig(
            name = "正常",
            description = "用户预期内的信息",
            keywords = emptyList(),
            action = Action.ALLOW,
            minConfidence = 0f,
        ),
    )

    fun notification(
        body: String = "【淘宝】限时优惠，全场五折",
        packageName: String = "com.taobao.taobao",
        channelId: String? = "marketing",
        title: String? = "淘宝",
    ) = MessageEvent(
        id = "n1",
        source = EventSource.NOTIFICATION,
        timestampMs = 1_700_000_000_000,
        packageName = packageName,
        channelId = channelId,
        senderKey = null,
        title = title,
        body = body,
    )

    fun sms(
        body: String = "【某某银行】您的验证码是 123456",
        senderKey: String? = "+8613800000000",
    ) = MessageEvent(
        id = "s1",
        source = EventSource.SMS,
        timestampMs = 1_700_000_000_000,
        packageName = "com.android.messaging",
        channelId = null,
        senderKey = senderKey,
        title = null,
        body = body,
    )

    fun input(
        event: MessageEvent = notification(),
        context: ChannelContext = ChannelContext(
            packageName = event.packageName,
            channelId = event.channelId,
            totalSeen = 30,
            suppressedCount = 27,
        ),
        categories: List<CategoryConfig> = categories(),
        labels: EventLabels = EventLabels(),
    ) = DecisionInput(
        event = event,
        context = context,
        categories = categories,
        labels = labels,
    )
}
