package me.ethanxu.jevnoisegate.core.pipeline

/**
 * 一次性验证码的本地闸门 —— **系统的隐私边界**。
 *
 * 命中的消息直接放行，且**永不上送 API**。这一条同时服务两个目标：
 *
 * 1. **隐私**：验证码是最不该外发的数据。把它交给第三方 API，无论对方多可信都是不必要的暴露。
 * 2. **安全**：验证码是被误拦时代价最高的消息类型 —— 漏放一条广告无感，
 *    错拦一条验证码会让用户被锁在银行外面。
 *
 * 判定规则是"命中关键词 **且** 含数字"。只匹配关键词会误伤（"下载 App 获取验证码"这类营销文案
 * 也含"验证码"），而真实验证码短信**必然同时含关键词和验证码数字**，两者取交集既精确又够宽。
 *
 * 刻意保持"宁可多放行不可少放行"的偏向：这是保险丝，不是分类器。
 */
object OtpGate {

    private val KEYWORD_PATTERN = Regex(
        pattern = """验证码|校验码|动态码|动态口令|短信密码|一次性密码|安全码|""" +
            """verification\s+code|security\s+code|one[\s-]?time\s+(code|password)|OTP""",
        option = RegexOption.IGNORE_CASE,
    )

    /** 4–8 位连续数字，覆盖主流验证码长度。 */
    private val CODE_PATTERN = Regex("""\d{4,8}""")

    /**
     * @return true 表示这是一条疑似验证码消息，应当放行且不上送。
     */
    fun isOtpLike(body: String): Boolean {
        if (body.isBlank()) return false
        return KEYWORD_PATTERN.containsMatchIn(body) && CODE_PATTERN.containsMatchIn(body)
    }
}
