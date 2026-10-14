package me.ethanxu.jevnoisegate.feature.notification

import android.app.Notification
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.common.sms.SmsNotificationSuppressor
import me.ethanxu.jevnoisegate.core.common.sms.PendingSmsSuppression
import me.ethanxu.jevnoisegate.core.common.AndroidMonotonicClock
import me.ethanxu.jevnoisegate.core.common.DecisionTrace
import me.ethanxu.jevnoisegate.core.common.PerformanceCounter
import me.ethanxu.jevnoisegate.core.common.PerformanceMetrics
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.EventRetention
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
    @Inject lateinit var retention: EventRetention

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var labels: LabelResolver
    private val recentPosts = RecentPostCache()
    private val generations = NotificationGenerations()
    private val jobs = mutableMapOf<String, Job>()
    private val packages = mutableMapOf<String, String>()
    private var connected = false
    private var connectionVersion = 0L
    private var bootCount: Int? = null
    private val retryScheduled = AtomicBoolean(false)
    private val retrySms = Runnable {
        retryScheduled.set(false)
        SmsNotificationSuppressor.retryPending()
    }
    private val cancelledBySms = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        labels = LabelResolver(this, scope)
        bootCount = AndroidMonotonicClock.bootCount(this)
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
        val version = ++connectionVersion
        SmsNotificationSuppressor.attachBatch { pending ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cancelSmsNotifications(pending)
            } else {
                if (retryScheduled.compareAndSet(false, true)) mainHandler.post(retrySms)
                emptySet()
            }
        }
        scope.launch {
            try {
                settings.awaitReady()
                val saved = dao.recentSuppressedSms(System.currentTimeMillis() - 60_000L)
                if (connected && connectionVersion == version) {
                    SmsNotificationSuppressor.restore(saved.map {
                        PendingSmsSuppression(it.senderKey, it.body, it.decidedAtMs + 60_000L)
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w(LogCategory.SMS, TAG, failure) { "恢复短信待撤销队列失败" }
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        connectionVersion++
        mainHandler.removeCallbacks(retrySms)
        retryScheduled.set(false)
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
        mainHandler.removeCallbacks(retrySms)
        SmsNotificationSuppressor.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelSmsNotifications(pending: List<PendingSmsSuppression>): Set<PendingSmsSuppression> {
        if (!connected || pending.isEmpty()) return emptySet()
        val smsPackage = Telephony.Sms.getDefaultSmsPackage(this) ?: return emptySet()
        if (smsPackage in settings.preferences.value.mutedPackages) return emptySet()
        return runCatching { matchSmsBatch(pending, load = {
            PerformanceMetrics.increment(PerformanceCounter.SMS_SNAPSHOT)
            activeNotifications.orEmpty().filter { it.packageName == smsPackage }.map { sbn ->
            smsCandidate(sbn)
            }
        }, cancel = { key ->
            runCatching {
                cancelledBySms += key
                jobs.remove(key)?.cancel()
                packages.remove(key)
                generations.remove(key)
                cancelNotification(key)
                true
            }.getOrDefault(false)
        }) }.getOrDefault(emptySet())
    }

    private fun smsCandidate(sbn: StatusBarNotification): SmsNotificationCandidate {
            val note = sbn.notification
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note)?.messages
            val grouped = (note.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
                (messages?.size ?: 0) > 1 || (note.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.size ?: 0) > 1
            val shown = messages?.singleOrNull()?.text?.toString()
                ?: note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: note.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            return SmsNotificationCandidate(sbn.key, shown,
                note.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), grouped)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (!connected) return
        val ingressMs = AndroidMonotonicClock.nowMillis()
        val trace = DecisionTrace(clock = AndroidMonotonicClock, startedMs = ingressMs)
        if (settings.isReady) {
            val prefs = settings.preferences.value
            if (!NotificationPolicy.shouldProcess(notification.packageName, packageName,
                    prefs.developerMode, prefs.mutedPackages)) return
        }
        val key = notification.key
        val content = runCatching {
            NotificationExtractor.contentOf(notification.notification)
        }.getOrElse { return }
        // Android may update progress/extras without changing the text being classified.
        // Preserve the pending decision so repeated updates cannot starve it.
        if (jobs[key]?.isActive == true && generations.matchesPending(key, content.title, content.body)) return
        val generation = generations.begin(key, content.title, content.body)
        jobs.remove(key)?.cancel()
        if (jobs.size >= 64) {
            generations.remove(key)
            AppLog.w(LogCategory.NOTIFICATION, TAG) { "通知处理繁忙，本条放行" }
            PerformanceMetrics.increment(PerformanceCounter.OVERLOAD)
            return
        }
        // Assign the generation before any suspension so a late database read cannot
        // make an older post newer than a notification already delivered by Android.
        packages[key] = notification.packageName
        val job = scope.launch {
            try {
                settings.awaitReady()
                handlePosted(notification, generation, content, trace, ingressMs)
            } catch (cancelled: CancellationException) {
                PerformanceMetrics.increment(PerformanceCounter.CANCELLED)
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

    private suspend fun handlePosted(notification: StatusBarNotification, generation: Long, content: NotificationContent,
        trace: DecisionTrace, ingressMs: Long) {
        val prefs = settings.preferences.value
        if (!connected || !NotificationPolicy.shouldProcess(notification.packageName, packageName,
                prefs.developerMode, prefs.mutedPackages)) return
        if (notification.notification.extras.getBoolean("jev.recovered", false)) return
        val key = notification.key
        val body = content.body
        val title = content.title
        val smsPost = notification.packageName == Telephony.Sms.getDefaultSmsPackage(this)
        // Always offer a new active candidate to the suppression bridge, including duplicates.
        if (smsPost) SmsNotificationSuppressor.retryPending()
        if (!connected || !generations.isCurrent(key, generation) || key in cancelledBySms ||
            notification.packageName in settings.preferences.value.mutedPackages) return
        if (smsPost) {
            val candidate = smsCandidate(notification)
            val admission = admitSmsPost(checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
                candidate, markSeen = { recentPosts.markSeen(key, body, title, notification.postTime) },
                owners = { dao.observeSmsOwners(candidate.body, System.currentTimeMillis() - 60_000L) })
            if (admission == SmsPostAdmission.DUPLICATE) return
            if (admission == SmsPostAdmission.OWNED) {
                // SMS capture/Worker owns this message's decision, even while it is pending or allowed.
                PerformanceMetrics.increment(PerformanceCounter.SMS_OWNED)
                return
            }
            PerformanceMetrics.increment(PerformanceCounter.SMS_OWNER_FALLBACK)
        } else if (!recentPosts.markSeen(key, body, title, notification.postTime)) return
        if (!connected || !generations.isCurrent(key, generation) || key in cancelledBySms ||
            notification.packageName in settings.preferences.value.mutedPackages) return
        val (appLabel, channelLabel) = coroutineScope {
            val app = async { labels.appLabel(notification.packageName) }
            val channel = async { labels.channelLabel(notification.packageName, notification.uid, notification.notification.channelId) }
            app.await() to channel.await()
        }
        val (event, entity) = withContext(Dispatchers.Default) {
            val event = NotificationExtractor.extract(notification, appLabel, channelLabel,
                "$key#${notification.postTime}#$generation", System.currentTimeMillis(), content).event
            event to event.toEntity(appLabel, channelLabel, ingressElapsedMs = ingressMs, bootCount = bootCount)
        }
        if (!connected || !generations.isCurrent(key, generation)) return
        trace.database { dao.insert(entity) }
        retention.request()
        val decision = withContext(Dispatchers.Default) { dispatcher.dispatch(event, EventLabels(appLabel, channelLabel), trace) }
        // Check and cancel on one main-thread turn; an Android update cannot interleave.
        if (decision.action != Action.ALLOW && connected && generations.isCurrent(key, generation) &&
            notification.packageName !in settings.preferences.value.mutedPackages) {
            cancelNotification(key)
        }
        trace.complete()
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
