package me.ethanxu.jevnoisegate.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class ModelDownloadService : Service() {
    @Inject lateinit var backend: LocalLayaBackend
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var download: Job? = null
    private var lastUpdate = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "模型下载", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            download?.cancel()
            backend.interruptDownload()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ID, notification(LocalModelStatus("正在准备下载…", busy = true)),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (download?.isActive == true) return START_REDELIVER_INTENT
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:model-download")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
        download = scope.launch {
            try {
                backend.downloadInService { state ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!state.busy || now - lastUpdate >= 1000) {
                        lastUpdate = now
                        manager.notify(ID, notification(state))
                    }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun notification(state: LocalModelStatus): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (state.ready) "模型已就绪" else "本地模型下载")
            .setContentText(state.message).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(state.busy).setAutoCancel(!state.busy)
        if (state.busy) {
            builder.setProgress(100, (state.progress * 100).toInt(), state.progress == 0f)
            val pause = PendingIntent.getService(this, 1, Intent(this, ModelDownloadService::class.java).setAction(PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "暂停", pause)
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        download?.cancel()
        backend.interruptDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        backend.interruptDownload()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PAUSE = "me.ethanxu.jevnoisegate.PAUSE_MODEL_DOWNLOAD"
        private const val CHANNEL = "model_download"
        private const val ID = 679
    }
}
