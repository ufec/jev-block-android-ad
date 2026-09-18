package me.ethanxu.jevnoisegate.feature.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.toEntity
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.dispatch.DecisionDispatcher

/**
 * 短信采集接收器。
 *
 * 依赖 `RECEIVE_SMS` 运行时权限。未授权时系统不会把广播投给本接收器（不是崩溃，是静默收不到）。
 *
 * 与通知采集端的关键差别：**短信侧不存在"执行"环节**。A 档方案下我们不是默认短信 App，
 * 因此既不能删除短信本体，也不能把它从收件箱里藏起来 —— 唯一能做的是把它的通知取消掉，
 * 而那已经由通知采集端负责。这里只做采集与判断，判断结果记录在案，
 * 供后续的渠道级降噪（"这个号码是稳定噪音源"）作为依据。
 *
 * 一个需要知道的后果：**同一条短信会产生两条事件** —— 一条来自这里，另一条来自短信 App
 * 发出的通知。这是真实情况而非缺陷，两者确实都到达了设备。去重属于流水线的工作，
 * 当前刻意保留重复，好让重复率本身也成为可观测的数据。
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var dao: ObservedEventDao

    @Inject
    lateinit var dispatcher: DecisionDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val raw = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).map { message ->
                RawSms(
                    address = message.originatingAddress,
                    body = message.messageBody.orEmpty(),
                    timestampMs = message.timestampMillis,
                )
            }
        }.getOrElse { error ->
            AppLog.w(LogCategory.SMS, TAG, error) { "解析短信广播失败: ${error.message}" }
            return
        }

        if (raw.isEmpty()) return

        // goAsync 让接收器在 onReceive 返回后仍能短暂存活，避免在主线程上做网络与磁盘 IO。
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                SmsEventFactory.eventsFrom(raw).forEach { event ->
                    dao.insert(event.toEntity())

                    val decision = dispatcher.dispatch(event)
                    AppLog.i(LogCategory.DECISION, TAG) {
                        "短信判断完成 action=${decision.action} by=${decision.decidedBy} " +
                            "cat=${decision.category} latency=${decision.latencyMillis}ms"
                    }

                    if (decision.action != Action.ALLOW) {
                        // 短信的"拦截"只能做到**通知层**：撤销短信 App 弹出的那条通知。
                        // 短信本体仍留在收件箱（只有默认短信应用能删），这是系统限制。
                        //
                        // 由本侧主动调用而不是让通知侧自己判断：本侧拿到的是真实发件人与
                        // 完整正文，且决策已出，不存在竞态。
                        SmsNotificationSuppressor.suppress(event.senderKey, event.body)
                        AppLog.i(LogCategory.SMS, TAG) {
                            "已拦截并请求撤销通知（短信本体保留在收件箱）"
                        }
                    }
                }
            } catch (error: Throwable) {
                AppLog.w(LogCategory.SMS, TAG, error) { "短信处理失败: ${error.message}" }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "JevNoiseGate/Sms"
    }
}
