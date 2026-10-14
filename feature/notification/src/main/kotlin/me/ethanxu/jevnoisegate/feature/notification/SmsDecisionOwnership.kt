package me.ethanxu.jevnoisegate.feature.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity

internal enum class SmsPostAdmission { DUPLICATE, OWNED, FALLBACK }

/** Completed ownership and fallback paths share the same dedup boundary. */
internal suspend fun admitSmsPost(
    canReadSms: Boolean,
    candidate: SmsNotificationCandidate,
    markSeen: () -> Boolean,
    owners: () -> Flow<List<MessageEventEntity>>,
): SmsPostAdmission {
    if (!markSeen()) return SmsPostAdmission.DUPLICATE
    return if (awaitSmsOwner(canReadSms, candidate, owners)) SmsPostAdmission.OWNED else SmsPostAdmission.FALLBACK
}

/** Link a notification only to one captured SMS, including captures that arrive shortly afterwards. */
internal suspend fun awaitSmsOwner(
    canReadSms: Boolean,
    candidate: SmsNotificationCandidate,
    owners: () -> Flow<List<MessageEventEntity>>,
    graceMs: Long = 1_000,
): Boolean {
    if (!canReadSms || candidate.grouped || candidate.body.isBlank()) return false
    val matches = withTimeoutOrNull(graceMs) {
        owners().map { rows -> rows.filter {
            SmsNotificationMatcher.matches(it.body, candidate.body, it.senderKey, candidate.title, candidate.grouped)
        } }.firstOrNull { it.isNotEmpty() }
    }
    return matches?.size == 1
}
