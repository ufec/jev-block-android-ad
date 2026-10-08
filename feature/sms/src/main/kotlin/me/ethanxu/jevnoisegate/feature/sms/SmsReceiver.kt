package me.ethanxu.jevnoisegate.feature.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.dispatch.DecisionDispatcher

@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {
    @Inject lateinit var dao: ObservedEventDao
    @Inject lateinit var dispatcher: DecisionDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val raw = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).map {
                RawSms(it.originatingAddress, it.messageBody.orEmpty(), it.timestampMillis)
            }
        }.getOrElse { return }
        if (raw.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    for (event in SmsEventFactory.eventsFrom(raw)) {
                        ingestSms(event, dao, { dispatcher.dispatchLocal(it) }, { id ->
                            val work = OneTimeWorkRequestBuilder<SmsDecisionWorker>()
                                .setInputData(workDataOf(SmsDecisionWorker.EVENT_ID to id)).build()
                            WorkManager.getInstance(context.applicationContext)
                                .enqueueUniqueWork("sms:$id", ExistingWorkPolicy.KEEP, work).await()
                        })
                    }
                    dao.prune(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w(LogCategory.SMS, "JevNoiseGate/Sms", failure) { "短信采集失败，本条放行" }
            } finally {
                pending.finish()
            }
        }
    }
}
