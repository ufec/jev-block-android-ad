package me.ethanxu.jevnoisegate.core.common.sms

/**
 * 短信通知的撤销入口。
 *
 * ## 为什么需要它，以及它解决的是什么
 *
 * 短信的**内容判断**只能在 SMS 侧做：只有 `SMS_RECEIVED` 广播同时带真实发件人与完整正文。
 * 但**撤销短信通知**只能在通知监听侧做：`NotificationListenerService.cancelNotification`
 * 是唯一能把通知从状态栏移除的 API，而它只对持有通知使用权的服务可用。
 *
 * 两边是不同组件，因此需要一座桥。这里就是那座桥：
 * - SMS 侧判断为拦截后调用 [suppress]
 * - 通知监听侧在 [attach] 时注册一个"按发件人+正文查找并撤销"的实现
 *
 * ## 为什么是"短信侧主动调用"而不是"通知侧自己判断"
 *
 * 如果让通知侧自己判断，它看到的是**短信 App 的包名**而不是真实发件人，语义是错的，
 * 而且会对同一条短信产生第二次 API 调用 —— 更糟的是两次判断可能得出相反结论。
 *
 * 决策可能先于通知到达，因此保留有限期的待匹配队列，在通知发布或监听重连时重试。
 *
 * ## 一个诚实的边界
 *
 * `cancelNotification` 只移除通知，**不动数据**。短信本体仍在收件箱与对话列表里。
 * 只有默认短信应用才能删除短信，本应用不是，也不打算是（那意味着接管全部短信通知，
 * 漏一条验证码的代价远大于收益）。因此对短信而言，"拦截"只能做到通知层。
 */
object SmsNotificationSuppressor {
    private val pending = PendingSmsSuppressions()

    fun attach(impl: (sender: String?, body: String) -> Boolean) = pending.attach(impl)
    fun detach() = pending.detach()

    /** 找不到对应通知时保留至 expiresAtMs；默认六十秒，最多保存 128 条。 */
    fun suppress(sender: String?, body: String, expiresAtMs: Long = me.ethanxu.jevnoisegate.core.common.SystemClock.nowMillis() + 60_000L) =
        pending.suppress(sender, body, expiresAtMs)

    fun retryPending() = pending.retryPending()
}

internal class PendingSmsSuppressions(
    private val nowMillis: () -> Long = me.ethanxu.jevnoisegate.core.common.SystemClock::nowMillis,
) {

    /** 由通知监听服务在连接时注册、断开时注销。 */
    @Volatile
    private var canceller: ((sender: String?, body: String) -> Boolean)? = null
    private data class Entry(val sender: String?, val body: String, val expiresAtMs: Long)
    private val pending = LinkedHashMap<Pair<String?, String>, Entry>()
    private val lock = Any()

    fun attach(impl: (sender: String?, body: String) -> Boolean) {
        canceller = impl
        retryPending()
    }

    fun detach() {
        canceller = null
    }

    fun suppress(sender: String?, body: String, expiresAtMs: Long = nowMillis() + 60_000L) {
        if (body.isBlank()) return
        if (expiresAtMs <= nowMillis()) return
        synchronized(lock) {
            val key = sender to body
            val expiry = maxOf(pending[key]?.expiresAtMs ?: 0L, expiresAtMs)
            // A replay of an older database row must not shorten a fresh decision.
            pending.remove(key)
            pending[key] = Entry(sender, body, expiry)
            while (pending.size > 128) {
                pending.remove(pending.keys.first())
            }
        }
        retryPending()
    }

    fun retryPending() {
        val impl = canceller ?: return
        val snapshot = synchronized(lock) {
            val now = nowMillis()
            pending.entries.removeAll { it.value.expiresAtMs <= now }
            pending.toMap()
        }
        snapshot.forEach { (key, entry) ->
            val current = synchronized(lock) {
                pending[key] === entry && entry.expiresAtMs > nowMillis()
            }
            if (current && impl(entry.sender, entry.body)) synchronized(lock) {
                // The callback may have accepted a newer decision for this same content.
                if (pending[key] === entry) pending.remove(key)
            }
        }
    }
}
