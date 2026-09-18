package me.ethanxu.jevnoisegate.feature.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.app.Notification
import android.provider.Telephony
import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.toEntity
import me.ethanxu.jevnoisegate.core.decision.EventLabels
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.core.dispatch.DecisionDispatcher

/**
 * 通知监听服务 —— 采集 + 判断 + 执行。
 *
 * 本服务的可用性**完全取决于用户在「设置 → 通知 → 通知使用权」里手动授权**。
 * 这是特殊权限，弹不出运行时对话框，也无法用代码申请 —— 未授权时系统根本不会绑定本服务，
 * `onCreate` 都不会被调用。
 *
 * 关于时机的诚实说明：`onNotificationPosted` 是**投递之后**的回调，
 * 因此"取消通知"只能擦掉通知栏的痕迹，无法阻止已经发生的声音与震动。
 * 这不是本实现的缺陷，而是 Android 的能力边界。真正的降噪手段是渠道级引导
 * （识别出稳定噪音源后引导用户关闭该渠道），那是后续工作。
 */
@AndroidEntryPoint
class NoiseGateNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var dao: ObservedEventDao

    @Inject
    lateinit var dispatcher: DecisionDispatcher

    @Inject
    lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var labels: LabelResolver
    private val recentPosts = RecentPostCache()

    override fun onCreate() {
        super.onCreate()
        labels = LabelResolver(this)
        AppLog.i(LogCategory.NOTIFICATION, TAG) { "通知监听服务已创建" }
        logPackageVisibility()
    }

    override fun onDestroy() {
        SmsNotificationSuppressor.detach()
        AppLog.i(LogCategory.SYSTEM, TAG) { "通知监听服务已销毁" }
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i(LogCategory.NOTIFICATION, TAG) { "通知使用权已授予，开始采集与判断" }
        // 短信侧判断完会回调这里来撤销对应的短信通知。
        SmsNotificationSuppressor.attach(::cancelSmsNotification)
    }

    /**
     * 按正文匹配并撤销默认短信应用的通知。
     *
     * **匹配用正文而不是发件人**：短信通知的标题在发件人是已保存联系人时显示的是
     * 联系人名而非号码，用号码匹配会全部失配。正文则是原样带出的。
     *
     * 通知里的正文可能被系统截断，所以做双向包含判断而不是相等。
     */
    private fun cancelSmsNotification(sender: String?, body: String) {
        if (body.isBlank()) return
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(this) ?: return

        val target = activeNotifications.firstOrNull { sbn ->
            if (sbn.packageName != smsPackage) return@firstOrNull false
            val shown = sbn.notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: return@firstOrNull false
            shown.length >= MIN_MATCH_LENGTH &&
                (body.contains(shown) || shown.contains(body))
        }

        if (target != null) {
            cancelNotification(target.key)
        } else {
            // 找不到不算错误：短信 App 可能还没弹通知，或用户关了它的通知。
            AppLog.d(LogCategory.SMS, TAG) { "未找到可撤销的短信通知（正文=${body.take(8)}…）" }
        }
    }

    override fun onListenerDisconnected() {
        AppLog.w(LogCategory.NOTIFICATION, TAG) { "通知使用权已断开" }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return

        // 排除自己的通知：回收站提醒之类的自产通知只会污染统计。
        // 开发者模式把自家通知也当普通通知处理，自测通知因此能走完整链路。
        val allowOwnPackage = settings.preferences.value.developerMode
        if (!NotificationExtractor.isRecordable(notification, packageName, allowOwnPackage)) return

        // 同一通知正文未变的重投直接丢弃，否则音乐播放器的进度更新能把接口打爆。
        // 正文取不到时（自定义布局）改由"标题 + 投递时刻"区分，详见 RecentPostCache。
        val body = NotificationExtractor.bodyForDedup(notification)
        val dedupTitle = NotificationExtractor.titleForDedup(notification)
        if (!recentPosts.markSeen(notification.key, body, dedupTitle, notification.postTime)) return

        val appLabel = labels.appLabel(notification.packageName)
        val channelLabel = labels.channelLabel(
            packageName = notification.packageName,
            uid = notification.uid,
            channelId = notification.notification.channelId,
        )

        val extracted = NotificationExtractor.extract(
            sbn = notification,
            appLabel = appLabel,
            channelLabel = channelLabel,
            id = "${notification.key}#${notification.postTime}",
            nowMs = System.currentTimeMillis(),
        )

        // onNotificationPosted 在主线程上，系统对它有超时预算 —— 立即返回，剩下的丢到 IO。
        val notificationKey = notification.key
        scope.launch {
            try {
                dao.insert(
                    extracted.event.toEntity(
                        appLabel = appLabel,
                        channelLabel = channelLabel,
                    ),
                )

                val decision = dispatcher.dispatch(
                    event = extracted.event,
                    labels = EventLabels(
                        appName = appLabel,
                        channelName = channelLabel,
                        senderIsContact = false,
                    ),
                )

                AppLog.i(LogCategory.DECISION, TAG) {
                    "判断完成 action=${decision.action} by=${decision.decidedBy} " +
                        "cat=${decision.category} conf=${decision.confidence} " +
                        "latency=${decision.latencyMillis}ms fail=${decision.failureReason}"
                }

                // 只有明确判定为噪音才动手；fail-open 的路径一律是 ALLOW，不会走到这里。
                if (decision.action != Action.ALLOW) {
                    cancelNotification(notificationKey)
                }
            } catch (error: Throwable) {
                AppLog.w(LogCategory.NOTIFICATION, TAG, error) { "处理通知失败: ${error.message}" }
            }
        }
    }

    /**
     * 把"包可见性是否生效"变成一个可测量的数字。
     *
     * Android 11+ 的包可见性限制会让 `getApplicationInfo` 对第三方应用抛
     * NameNotFoundException，即使已声明 QUERY_ALL_PACKAGES —— 部分国内 ROM
     * 还会再叠一层用户级开关。这个数字直接告诉我们处于哪种情况。
     */
    private fun logPackageVisibility() {
        val count = labels.visibleAppCount()
        AppLog.i(LogCategory.SYSTEM, TAG) { "包可见性: 可见 $count 个应用; 第三方样本=${labels.visibleAppSamples(5)}" }
        if (count in 0..100) {
            AppLog.w(LogCategory.SYSTEM, TAG) { "可见应用过少，第三方 App 名将解析失败 —— 需在系统设置里为本应用开启「获取应用列表」权限" }
        }
    }

    private companion object {

        /**
         * 通知正文参与匹配的最短长度。
         *
         * 太短的正文（如"1"）会与大量通知误匹配，撤错别人的通知。
         * 6 是个折中：能覆盖"您的验证码"这类短正文，又不至于匹配到单个字符。
         */
        const val MIN_MATCH_LENGTH = 6

        const val TAG = "JevNoiseGate/Notif"
    }
}
