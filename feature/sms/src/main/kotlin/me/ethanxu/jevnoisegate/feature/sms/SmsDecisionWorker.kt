package me.ethanxu.jevnoisegate.feature.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.dispatch.DecisionDispatcher
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.model.EventSource
import me.ethanxu.jevnoisegate.core.model.MessageEvent

@HiltWorker
class SmsDecisionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val dao: ObservedEventDao,
    private val dispatcher: DecisionDispatcher,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(EVENT_ID) ?: return Result.failure()
        return try {
            val stored = dao.find(id) ?: return Result.success()
            if (stored.action != null) return Result.success()
            val event = MessageEvent(stored.id, EventSource.SMS, stored.timestampMs, stored.packageName,
                stored.channelId, stored.senderKey, stored.title, stored.body)
            val decision = dispatcher.dispatch(event)
            if (decision.action != Action.ALLOW) SmsNotificationSuppressor.suppress(event.senderKey, event.body)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A transient storage problem can recover; backend errors already fail open.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
    companion object { const val EVENT_ID = "eventId" }
}
