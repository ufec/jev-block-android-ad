package me.ethanxu.jevnoisegate.feature.notification

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import me.ethanxu.jevnoisegate.core.fingerprint.Fingerprint
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent
import me.ethanxu.jevnoisegate.core.model.bucketKey

/**
 * 从通知里抽取出的一条事件，连同平台层解析出的可读标签。
 *
 * 标签不放进 [MessageEvent]：那是纯领域模型（`:core:model` 零平台依赖），
 * 而 App 名/渠道名必须靠 Android 才能解析。
 */
data class ExtractedEvent(
    val event: MessageEvent,
    val appLabel: String?,
    val channelLabel: String?,
)

/**
 * `StatusBarNotification` → [ExtractedEvent]。
 *
 * 纯函数（除传入的标签外不依赖任何平台状态），因此可以在 JVM 单测里用构造出来的
 * `Notification` 验证抽取逻辑 —— 通知正文的优先级规则是这里最容易出错的地方。
 */
object NotificationExtractor {

    /**
     * 抽取正文，按下面的优先级拼接。
     *
     * `BIG_TEXT` 排在 `TEXT` 前面是有意的：折叠态只显示一行，展开态才是完整正文。
     * 拿折叠态去算指纹，会让"展开后才看得出是广告"的通知漏判。
     */
    fun bodyOf(notification: Notification): String {
        val extras = notification.extras
        val parts = mutableListOf<String>()

        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            // 去重：BIG_TEXT 与 TEXT 常常是同一段文字，重复拼接会污染指纹。
            if (text.isNotEmpty() && text !in parts) parts += text
        }

        add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(extras.getCharSequence(Notification.EXTRA_TEXT))

        // InboxStyle 的行文本（如多条消息摘要）
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }

        add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))

        return parts.joinToString("\n")
    }

    /** 供去重使用：不构造完整事件，只取正文。 */
    fun bodyForDedup(sbn: StatusBarNotification): String = bodyOf(sbn.notification)

    /**
     * 供去重使用：标题。
     *
     * 正文取不到时（自定义 RemoteViews 布局）标题往往仍可读，
     * 它是区分"新消息 / 正在运行 / 下载完成"的唯一线索。
     */
    fun titleForDedup(sbn: StatusBarNotification): String? = titleOf(sbn.notification)

    private fun titleOf(notification: Notification): String? {
        val extras = notification.extras
        val candidates = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_CONVERSATION_TITLE,
        )
        return candidates
            .asSequence()
            .mapNotNull { key -> extras.getCharSequence(key)?.toString()?.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    /**
     * @param nowMs 由调用方注入，便于测试。
     * @param id 稳定标识，由调用方按 `sbn.key` 等生成。
     */
    fun extract(
        sbn: StatusBarNotification,
        appLabel: String?,
        channelLabel: String?,
        id: String,
        nowMs: Long,
    ): ExtractedEvent {
        val notification = sbn.notification
        val body = bodyOf(notification)

        val event = MessageEvent(
            id = id,
            source = EventSource.NOTIFICATION,
            timestampMs = if (sbn.postTime > 0) sbn.postTime else nowMs,
            packageName = sbn.packageName,
            channelId = notification.channelId,
            senderKey = sbn.groupKey ?: sbn.key,
            title = titleOf(notification),
            body = body,
        )

        return ExtractedEvent(
            event = event,
            appLabel = appLabel,
            channelLabel = channelLabel,
        )
    }

    /** 算出分桶键与指纹，供落库使用。 */
    fun fingerprintOf(event: MessageEvent): String = Fingerprint.of(event)

    fun bucketKeyOf(event: MessageEvent): String = event.bucketKey

    fun skeletonOf(event: MessageEvent): String = Fingerprint.skeletonOf(event.body)

    /**
     * 该通知是否值得记录。
     *
     * 默认排除自家通知（回收站提醒之类的自产通知只会污染统计），
     * 开启开发者模式后按普通通知处理 —— 否则「发送测试通知」根本走不完这条链路。
     *
     * 代价要说清楚：放行的是**整个自家包名**，不是只放行自测通知。
     * 多出来的污染由「开发者模式」这个开关承担 —— 用户显式选择进入自产数据会进统计的状态。
     */
    fun isRecordable(
        sbn: StatusBarNotification,
        ownPackageName: String,
        allowOwnPackage: Boolean,
    ): Boolean {
        if (sbn.packageName != ownPackageName) return true
        // 自家通知默认排除（自产数据会污染统计）；
        // 开发者模式下按普通通知处理 —— 否则「自测通知」根本走不完整条链路。
        return allowOwnPackage
    }

    /** Android 8+ 的通知渠道 id；低于 8 时为 null。 */
    fun channelIdOf(sbn: StatusBarNotification): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sbn.notification.channelId else null
}
