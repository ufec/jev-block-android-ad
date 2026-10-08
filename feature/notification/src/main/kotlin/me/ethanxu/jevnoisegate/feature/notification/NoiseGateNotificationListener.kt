package me.ethanxu.jevnoisegate.feature.notification

import android.app.Notification
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.toEntity
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.decision.EventLabels
import me.ethanxu.jevnoisegate.core.dispatch.DecisionDispatcher
import me.ethanxu.jevnoisegate.core.model.Action

@AndroidEntryPoint
class NoiseGateNotificationListener : NotificationListenerService() {
    @Inject lateinit var dao: ObservedEventDao
    @Inject lateinit var dispatcher: DecisionDispatcher
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var labels: LabelResolver
    private val recentPosts = RecentPostCache()
    private val generations = NotificationGenerations()
    private val jobs = mutableMapOf<String, Job>()
    private val packages = mutableMapOf<String, String>()
    private var connected = false
    private val cancelledBySms = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        labels = LabelResolver(this)
        scope.launch {
            settings.awaitReady()
            settings.preferences.collect { prefs ->
                packages.filterValues { it in prefs.mutedPackages }.keys.toList().forEach { key ->
                    jobs.remove(key)?.cancel()
                    generations.remove(key)
                    recentPosts.forget(key)
                    packages.remove(key)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        SmsNotificationSuppressor.attach { sender, body ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cancelSmsNotification(sender, body)
            } else {
                mainHandler.post { SmsNotificationSuppressor.retryPending() }
                false
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        SmsNotificationSuppressor.detach()
        // Cancellation can run a task's finally block immediately on this thread.
        // Detach the map and invalidate versions before touching any Job.
        val pendingJobs = jobs.values.toList()
        jobs.clear()
        packages.clear()
        generations.clear()
        recentPosts.clear()
        cancelledBySms.clear()
        pendingJobs.forEach { it.cancel() }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected = false
        SmsNotificationSuppressor.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelSmsNotification(sender: String?, body: String): Boolean {
        if (!connected) return false
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(this) ?: return false
        if (smsPackage in settings.preferences.value.mutedPackages) return false
        val matches = runCatching { activeNotifications.orEmpty().filter { sbn ->
            if (sbn.packageName != smsPackage) return@filter false
            val note = sbn.notification
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note)?.messages
            val grouped = (note.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
                (messages?.size ?: 0) > 1 || (note.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.size ?: 0) > 1
            val shown = messages?.singleOrNull()?.text?.toString()
                ?: note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: note.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            SmsNotificationMatcher.matches(body, shown, sender,
                note.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), grouped)
        } }.getOrElse { return false }
        // Without an unambiguous match, keep the notification visible.
        val target = matches.singleOrNull() ?: return false
        return runCatching {
            cancelledBySms += target.key
            jobs.remove(target.key)?.cancel()
            packages.remove(target.key)
            generations.remove(target.key)
            cancelNotification(target.key)
            true
        }.getOrDefault(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!connected) return
        val key = notification.key
        val content = runCatching {
            NotificationExtractor.titleForDedup(notification) to NotificationExtractor.bodyForDedup(notification)
        }.getOrElse { return }
        // Android may update progress/extras without changing the text being classified.
        // Preserve the pending decision so repeated updates cannot starve it.
        if (jobs[key]?.isActive == true && generations.matchesPending(key, content.first, content.second)) return
        val generation = generations.begin(key, content.first, content.second)
        jobs.remove(key)?.cancel()
        if (jobs.size >= 64) {
            generations.remove(key)
            AppLog.w(LogCategory.NOTIFICATION, TAG) { "通知处理繁忙，本条放行" }
            return
        }
        // Assign the generation before any suspension so a late database read cannot
        // make an older post newer than a notification already delivered by Android.
        packages[key] = notification.packageName
        val job = scope.launch {
            try {
                settings.awaitReady()
                handlePosted(notification, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w(LogCategory.NOTIFICATION, TAG, failure) { "处理通知失败，本条放行" }
            } finally {
                if (generations.isCurrent(key, generation)) {
                    jobs.remove(key)
                    packages.remove(key)
                    generations.remove(key)
                }
            }
        }
        if (job.isActive) jobs[key] = job
    }

    private suspend fun handlePosted(notification: StatusBarNotification, generation: Long) {
        val prefs = settings.preferences.value
        if (!connected || !NotificationPolicy.shouldProcess(notification.packageName, packageName,
                prefs.developerMode, prefs.mutedPackages)) return
        if (notification.notification.extras.getBoolean("jev.recovered", false)) return
        if (notification.packageName == Telephony.Sms.getDefaultSmsPackage(this)) {
            // Rebuild pending suppression after process restart, within its original TTL.
            dao.recentSuppressedSms(System.currentTimeMillis() - 60_000L).forEach { event ->
                SmsNotificationSuppressor.suppress(event.senderKey, event.body, event.decidedAtMs + 60_000L)
            }
        }
        SmsNotificationSuppressor.retryPending()
        val key = notification.key
        if (!connected || !generations.isCurrent(key, generation) || key in cancelledBySms ||
            notification.packageName in settings.preferences.value.mutedPackages) return
        val body = NotificationExtractor.bodyForDedup(notification)
        val title = NotificationExtractor.titleForDedup(notification)
        if (!recentPosts.markSeen(key, body, title, notification.postTime)) return
        val appLabel = labels.appLabel(notification.packageName)
        val channelLabel = labels.channelLabel(notification.packageName, notification.uid, notification.notification.channelId)
        val event = NotificationExtractor.extract(notification, appLabel, channelLabel,
            "$key#${notification.postTime}#$generation", System.currentTimeMillis()).event
        dao.insert(event.toEntity(appLabel, channelLabel))
        dao.prune(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        val decision = dispatcher.dispatch(event, EventLabels(appLabel, channelLabel))
        // Check and cancel on one main-thread turn; an Android update cannot interleave.
        if (decision.action != Action.ALLOW && connected && generations.isCurrent(key, generation) &&
            notification.packageName !in settings.preferences.value.mutedPackages) {
            cancelNotification(key)
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        jobs.remove(key)?.cancel()
        packages.remove(key)
        generations.remove(key)
        recentPosts.forget(key)
        cancelledBySms.remove(key)
    }

    private companion object { const val TAG = "JevNoiseGate/Notif" }
}
