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
import me.ethanxu.jevnoisegate.core.common.AndroidMonotonicClock
import me.ethanxu.jevnoisegate.core.common.DecisionTrace
import me.ethanxu.jevnoisegate.core.common.sameBootIngress

@HiltWorker
class SmsDecisionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val dao: ObservedEventDao,
    private val dispatcher: DecisionDispatcher,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(EVENT_ID) ?: return Result.failure()
        val workerStarted = AndroidMonotonicClock.nowMillis()
        // 广播到达时可能还没关降噪，或任务排到了重启之后。
        // 关掉时这条短信必须放行，否则"停止降噪"就不是立即生效的。
        if (!me.ethanxu.jevnoisegate.core.common.ServiceState.running) return Result.success()
        return try {
            val loadStarted = AndroidMonotonicClock.nowMillis()
            val stored = dao.find(id) ?: return Result.success()
            val loadMs = AndroidMonotonicClock.nowMillis() - loadStarted
            if (stored.action != null) return Result.success()
            val ingress = sameBootIngress(stored.bootCount, AndroidMonotonicClock.bootCount(applicationContext),
                stored.ingressElapsedMs, workerStarted)
            val trace = DecisionTrace(clock = AndroidMonotonicClock, startedMs = workerStarted, ingressMs = ingress,
                workerWaitMs = ingress?.let { workerStarted - it })
            trace.addDatabaseElapsed(loadMs)
            val event = MessageEvent(stored.id, EventSource.SMS, stored.timestampMs, stored.packageName,
                stored.channelId, stored.senderKey, stored.title, stored.body)
            val decision = dispatcher.dispatch(event, trace = trace)
            if (decision.action != Action.ALLOW) SmsNotificationSuppressor.suppress(event.senderKey, event.body)
            trace.complete()
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
