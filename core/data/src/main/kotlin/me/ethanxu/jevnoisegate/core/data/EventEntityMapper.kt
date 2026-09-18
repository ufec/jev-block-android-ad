package me.ethanxu.jevnoisegate.core.data

import me.ethanxu.jevnoisegate.core.fingerprint.Fingerprint
import me.ethanxu.jevnoisegate.core.model.MessageEvent
import me.ethanxu.jevnoisegate.core.model.bucketKey

/**
 * [MessageEvent] → 存储实体。
 *
 * 放在 `:core:data` 而不是各采集端，是因为通知与短信两个采集端需要完全一致的落库语义 ——
 * 分桶键与指纹的算法只应有一处实现，否则两端会算出不同的指纹，缓存直接失效。
 *
 * 标签由调用方传入：`:core:model` 是零平台依赖的，解析不出 App 名与渠道名。
 */
fun MessageEvent.toEntity(
    appLabel: String? = null,
    channelLabel: String? = null,
): MessageEventEntity = MessageEventEntity(
    id = id,
    source = source.name,
    timestampMs = timestampMs,
    packageName = packageName,
    channelId = channelId,
    senderKey = senderKey,
    appLabel = appLabel,
    channelLabel = channelLabel,
    title = title,
    body = body,
    bucketKey = bucketKey,
    fingerprint = Fingerprint.of(this),
    skeleton = Fingerprint.skeletonOf(body),
)
