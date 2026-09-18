package me.ethanxu.jevnoisegate.core.model

/** 消息来源。短信与通知统一成同一条事件流，只在采集端与去重规则上有区别。 */
enum class EventSource {
    SMS,
    NOTIFICATION,
}

/**
 * 一条待判定的消息事件。短信与通知共用。
 *
 * 不含 `fingerprint` —— 它是**派生值**，由流水线用 [bucketKey] 计算后写入存储层，
 * 放在这里会让事件构造与指纹算法产生不必要的耦合。
 *
 * 也刻意不持有平台对象（如 Android 的 `StatusBarNotification`）。平台句柄由采集层
 * 用 `id → handle` 的映射单独维护，从而保证本模块在 `commonMain` 中保持零平台依赖。
 */
data class MessageEvent(
    /** 稳定标识，撤销动作时回查用。 */
    val id: String,
    val source: EventSource,
    val timestampMs: Long,
    /** 通知来源应用包名；短信事件填短信应用的包名。 */
    val packageName: String,
    /** 通知渠道 id；短信事件为 null。 */
    val channelId: String? = null,
    /** 短信发件号码；通知事件为 null。 */
    val senderKey: String? = null,
    val title: String? = null,
    val body: String,
)

/**
 * 指纹分桶键。
 *
 * 分桶的用意是避免不同来源的相似模板互相碰撞：
 * - 通知按 `包名 + 渠道` 分桶 —— 同一个 App 的"营销活动"与"订单物流"是两个不同模板族。
 * - 短信按**发件号码**分桶，而不是短信应用包名 —— 因为同一模板往往由同一号码反复发送，
 *   用发件人做桶才能让"这个号码的发文模板"聚到一起。号码缺失时退回包名。
 */
val MessageEvent.bucketKey: String
    get() = when (source) {
        EventSource.NOTIFICATION -> channelId?.let { "$packageName|$it" } ?: packageName
        EventSource.SMS -> senderKey ?: packageName
    }
