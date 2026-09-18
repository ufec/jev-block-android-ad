package me.ethanxu.jevnoisegate.feature.notification

import me.ethanxu.jevnoisegate.core.common.log.LogCategory

import me.ethanxu.jevnoisegate.core.common.log.AppLog

/**
 * 通知重复投递的抑制器。
 *
 * `onNotificationPosted` 在通知**每次更新**时都会触发，因此必须区分两种表面完全相同的投递：
 *
 * | 情况 | 例子 | 应当 |
 * |---|---|---|
 * | 同一通知在**持续刷新** | 音乐进度、前台服务通知 | 丢弃，只记一条 |
 * | 同一通知被**复用**发新事件 | TP-LINK 移动侦测：固定 key、固定文案，每次是一次侦测 | 各记一条 |
 *
 * 两者在通知层面一模一样（同 key、同正文），唯一可用的区别是**时间间隔** ——
 * 因此这里用**滑动时间窗**：窗口内的重复投递丢弃，超窗则视为新的一次。
 *
 * 窗口必须滑动（丢重时把时间戳推到本次），否则持续刷新的通知每过一个窗口就会重新记一条，
 * 一首歌能记几十条。
 *
 * ## 正文取不到时的分支
 *
 * `NotificationExtractor.bodyOf` 只能读 BIG_TEXT / TEXT / TEXT_LINES / SUB_TEXT，
 * **自定义 RemoteViews 布局的通知这四项全空**，此时退回"标题 + 投递时刻"。
 * 不这么做的话去重键会退化成 `"key|0"`，那个 `0` 毫无区分度，
 * 导致这类应用无论发多少条都只记下第一条。
 *
 * 只在 `onNotificationPosted` 这一个线程上使用，因此不需要加锁。
 */
private const val TAG = "NotifDedup"

internal class RecentPostCache(
    private val maxSize: Int = 256,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    /** entry → 最近一次见到它的时刻。 */
    private val seen = LinkedHashMap<String, Long>()

    /** @return true 表示应当记录；false 表示窗口内的重复投递，应丢弃。 */
    fun markSeen(
        notificationKey: String,
        body: String,
        title: String? = null,
        postTime: Long = 0L,
    ): Boolean {
        val discriminator = if (body.isBlank()) {
            // 正文不可读时才把时刻并入键 —— 可读时靠窗口区分即可。
            // 若可读时也并入时刻，每次投递都成了新事件，等于完全不做去重。
            "${title.orEmpty()}|$postTime"
        } else {
            body
        }
        val entry = "$notificationKey|${discriminator.hashCode()}"

        // 窗口必须用**我们的观察时钟**，而不是通知自带的 postTime。
        //
        // 这是一个真实踩过的坑：若应用（或 ROM）在更新通知时不刷新 postTime，
        // 那么 `postTime - lastSeen` 恒为 0、恒落在窗口内，于是**这条通知永远被丢弃** ——
        // 滑动窗口根本没机会推进。表现就是"无论发多少条都只记第一条"。
        //
        // 窗口要回答的问题是"我最近刚见过它吗"，这显然是关于我们自己的观察时间。
        val now = System.currentTimeMillis()

        val lastSeen = seen[entry]
        if (lastSeen != null && now - lastSeen < windowMs) {
            // 滑动：把时间戳推到本次，持续刷新的通知因此永远不会再被记录。
            seen[entry] = now
            AppLog.d(LogCategory.NOTIFICATION, TAG) {
                "重复投递已丢弃 key=$notificationKey 距上次=${now - lastSeen}ms"
            }
            return false
        }

        if (seen.size >= maxSize) {
            // 简单 FIO 淘汰：LinkedHashMap 保持插入序，移除最旧的一批。
            val iterator = seen.keys.iterator()
            repeat(maxSize / 4) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        seen[entry] = now
        return true
    }

    private companion object {
        /**
         * 滑动窗口长度。
         *
         * 要同时满足两头：
         * - **大于持续刷新的间隔**（音乐进度约 1 秒）→ 否则压不住进度类通知
         * - **小于真实事件的最小间隔** → 否则会把相隔几秒的两次独立打扰合并掉
         *
         * 5 秒是个折中，也是唯一的调节旋钮：若发现某类通知仍然只记一条，
         * 说明它的多次投递间隔小于这个值，调小即可。
         */
        const val DEFAULT_WINDOW_MS = 5_000L
    }
}
