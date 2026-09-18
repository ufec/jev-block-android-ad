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
 * 由短信侧在决策出来之后主动调用，则**没有竞态**：不需要等待、不需要轮询。
 *
 * ## 一个诚实的边界
 *
 * `cancelNotification` 只移除通知，**不动数据**。短信本体仍在收件箱与对话列表里。
 * 只有默认短信应用才能删除短信，本应用不是，也不打算是（那意味着接管全部短信通知，
 * 漏一条验证码的代价远大于收益）。因此对短信而言，"拦截"只能做到通知层。
 */
object SmsNotificationSuppressor {

    /** 由通知监听服务在连接时注册、断开时注销。 */
    @Volatile
    private var canceller: ((sender: String?, body: String) -> Unit)? = null

    fun attach(impl: (sender: String?, body: String) -> Unit) {
        canceller = impl
    }

    fun detach() {
        canceller = null
    }

    /**
     * 请求撤销与这条短信对应的通知。
     *
     * 找不到对应通知时静默返回 —— 那说明短信 App 还没来得及弹通知（或用户关了它的通知），
     * 此时本来也没有可见的提示需要撤销，不是错误。
     */
    fun suppress(sender: String?, body: String) {
        canceller?.invoke(sender, body)
    }
}
