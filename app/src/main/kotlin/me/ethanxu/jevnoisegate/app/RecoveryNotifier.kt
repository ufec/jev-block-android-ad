package me.ethanxu.jevnoisegate.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity

class RecoveryNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    fun post(event: MessageEventEntity) {
        check(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "请先允许本应用发送通知" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "恢复的消息", NotificationManager.IMPORTANCE_DEFAULT))
        check(NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "请在系统设置开启恢复消息通知" }
        val sourcePackage = if (event.source == "SMS") Telephony.Sms.getDefaultSmsPackage(context) else event.packageName
        val open = sourcePackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(context, MainActivity::class.java)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(me.ethanxu.jevnoisegate.app.R.mipmap.ic_launcher)
            .setContentTitle(event.title ?: "恢复：${event.appLabel ?: event.senderKey ?: event.packageName}")
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setContentIntent(PendingIntent.getActivity(context, event.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).addExtras(android.os.Bundle().apply { putBoolean("jev.recovered", true) }).build()
        manager.notify("restored:${event.id}", 1, notification)
    }
    private companion object { const val CHANNEL = "recovered_messages" }
}
