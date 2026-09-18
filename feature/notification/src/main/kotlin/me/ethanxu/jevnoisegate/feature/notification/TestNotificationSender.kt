package me.ethanxu.jevnoisegate.feature.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 自发一条测试通知，用来验证完整链路：采集 → 判断 → 执行。
 *
 * ## 为什么需要它
 *
 * 验证"拦截是否生效"原本只能等真实广告推送 —— 不可控、不可复现，改完代码也没法自测。
 * 有了它，点一下就能跑完整条链路，几秒后看通知还在不在。
 *
 * ## 为什么必须在开发者模式下
 *
 * [NotificationExtractor.isRecordable] 默认排除本应用自己的通知（自产数据会污染统计）。
 * 自测通知要能验证完整链路，就必须穿过这道过滤 —— 而放行整个自家包名是有代价的，
 * 因此把它绑在「开发者模式」这个开关上：由用户显式选择进入会污染统计的状态。
 *
 * ## 两个样张
 *
 * [Sample.AD] 期望被拦截、[Sample.OTP] 期望被放行。后者同样重要：
 * 只验证"能拦"是不够的，还得确认没有误拦验证码 —— 那才是这个应用最不能犯的错。
 */
object TestNotificationSender {

    private const val CHANNEL_ID = "jev_selftest"
    private const val NOTIFICATION_ID_AD = 90001
    private const val NOTIFICATION_ID_OTP = 90002

    enum class Sample(val id: Int, val title: String, val body: String) {
        AD(
            id = NOTIFICATION_ID_AD,
            title = "限时秒杀福利",
            body = "全场一折起，立即抢购，点击领取专属优惠券，手慢无！退订回T",
        ),
        OTP(
            id = NOTIFICATION_ID_OTP,
            title = "验证码",
            body = "您的验证码是 482913，5 分钟内有效。请勿向任何人透露。",
        ),
    }

    fun send(context: Context, sample: Sample) {
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sample.title)
            .setContentText(sample.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sample.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // 没有通知权限时静默失败 —— 用户会看到"没有通知出现"，
        // 好过抛异常让整个诊断页崩掉。
        runCatching {
            NotificationManagerCompat.from(context).notify(sample.id, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自测通知",
                // HIGH 才有横幅/声音，否则用户可能注意不到通知已经出现
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "用于验证拦截链路的自测通知，可随时关闭"
            },
        )
    }
}
