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
 * ## 两个样张 + 自定义输入
 *
 * [Sample.AD] 期望被拦截、[Sample.OTP] 期望被放行。后者同样重要：
 * 只验证"能拦"是不够的，还得确认没有误拦验证码 —— 那才是这个应用最不能犯的错。
 *
 * 样张只能覆盖这两条已知路径，改提示词或调闸门阈值时需要的是**任意输入**
 * （真实世界里误判的那些通知长什么样，只有用户手里有），因此还有自定义重载
 * `send(context, title, body)`：标题与正文都由调用方给。
 */
object TestNotificationSender {

    private const val CHANNEL_ID = "jev_selftest"
    private const val NOTIFICATION_ID_AD = 90001
    private const val NOTIFICATION_ID_OTP = 90002

    /**
     * 自定义通知的 id 区间。
     *
     * 不复用固定 id 是因为 [RecentPostCache] 的去重键 = `通知 key + 正文`，
     * 而 key 里含 id：固定 id + 同样正文的两次自测落在 5 秒窗口内时，**第二次会被整条丢弃**，
     * 表现成"点了发送却没反应"。每次换 id 让两次投递成为两个键。
     *
     * 换来的是 id 会变，所以发送前要先撤销上一条（[lastCustomId]），
     * 否则每点一次就多一条留在通知栏里。
     */
    private const val NOTIFICATION_ID_CUSTOM_BASE = 91000
    private const val NOTIFICATION_ID_CUSTOM_RANGE = 64

    /** 只在主线程（Compose 回调）上读写，不需要同步。 */
    private var customSeq = 0
    private var lastCustomId: Int? = null

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
        notify(context, sample.id, sample.title, sample.body)
    }

    /**
     * 发送一条内容自定义的测试通知。
     *
     * 与样张走的是**同一条链路**：同样带 `jev_selftest` 渠道、同样只在开发者模式下被记录，
     * 区别只是内容由调用方给。这样"自定义输入"验证的才不是一条被特殊对待的旁路。
     *
     * @param title 可为空。它不进指纹、也不参与本地闸门，但会作为上下文随正文一起给模型，
     *   所以想复现"同一条正文、不同标题"的误判时，这里就是那个变量。
     * @param body 正文，本地闸门与指纹的唯一依据；空白时调用方不应发送。
     */
    fun send(context: Context, title: String, body: String) {
        lastCustomId?.let { previous ->
            runCatching { NotificationManagerCompat.from(context).cancel(previous) }
        }
        val id = NOTIFICATION_ID_CUSTOM_BASE + (customSeq++ % NOTIFICATION_ID_CUSTOM_RANGE)
        lastCustomId = id
        notify(context, id, title, body)
    }

    private fun notify(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)

        val text = body.trim()
        // 空标题传 null 而不是空串：空串会让通知栏显示一行空白标题。
        val heading: String? = title.trim().ifEmpty { null }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // 没有通知权限时静默失败 —— 用户会看到"没有通知出现"，
        // 好过抛异常让整个诊断页崩掉。
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
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
