package me.ethanxu.jevnoisegate.feature.sms

import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent

/** 从 `SmsMessage` 抽出的原始字段。抽出来是为了让分组逻辑可以脱离 Android 做单测。 */
data class RawSms(
    val address: String?,
    val body: String,
    val timestampMs: Long,
)

/**
 * 把原始短信组装成事件。
 *
 * 长短信会被运营商拆成多条 `SmsMessage` 放在**同一个** broadcast 里，
 * 它们的发件地址与时间戳相同。若不合并，一条长广告会被记成 3~5 条事件，
 * 让"这个号码有多吵"的统计严重失真。
 */
object SmsEventFactory {

    fun eventsFrom(raw: List<RawSms>): List<MessageEvent> =
        raw.groupBy { it.address to it.timestampMs }
            .map { (key, parts) ->
                val (address, timestampMs) = key
                val body = parts.joinToString(separator = "") { it.body }

                MessageEvent(
                    id = buildId(address, timestampMs, body),
                    source = EventSource.SMS,
                    timestampMs = timestampMs,
                    // 短信事件里 packageName 填的是采集端的包名占位 —— 真正有意义的
                    // 分桶依据是 senderKey（发件号码），见 MessageEvent.bucketKey。
                    packageName = SMS_SOURCE_PLACEHOLDER,
                    channelId = null,
                    senderKey = address,
                    title = null,
                    body = body,
                )
            }

    /**
     * 稳定标识。同一号码、同一时间戳、同一正文视为同一条 —— 广播重放或系统重投时
     * 主键冲突会被 `OnConflictStrategy.IGNORE` 吃掉，不会产生重复行。
     */
    internal fun buildId(address: String?, timestampMs: Long, body: String): String =
        "sms:$address#$timestampMs#${body.hashCode()}"

    internal const val SMS_SOURCE_PLACEHOLDER: String = "sms"
}
