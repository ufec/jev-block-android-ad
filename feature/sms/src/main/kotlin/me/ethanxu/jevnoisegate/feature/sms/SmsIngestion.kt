package me.ethanxu.jevnoisegate.feature.sms

import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.toEntity
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.FinalDecision
import me.ethanxu.jevnoisegate.core.model.MessageEvent

/** Replayed broadcasts may resume an insertion whose local decision or scheduling failed. */
internal suspend fun ingestSms(
    event: MessageEvent,
    dao: ObservedEventDao,
    dispatchLocal: suspend (MessageEvent) -> FinalDecision?,
    enqueue: suspend (String) -> Unit,
    suppress: (String?, String) -> Unit = { sender, body -> SmsNotificationSuppressor.suppress(sender, body) },
) {
    if (dao.insert(event.toEntity()) == -1L && dao.find(event.id)?.action != null) return
    val decision = dispatchLocal(event)
    if (decision == null) enqueue(event.id)
    else if (decision.action != Action.ALLOW) suppress(event.senderKey, event.body)
}
