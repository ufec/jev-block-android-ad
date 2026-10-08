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
 * 合并标题与正文匹配验证码关键词及 4–8 位数字/字母代码，也保护纯数字代码。
 * 这是启发式规则，无法保证识别所有验证码格式；命中的内容不会调用后端。
 *
 * 刻意保持"宁可多放行不可少放行"的偏向：这是保险丝，不是分类器。
 */
object OtpGate {

    private val KEYWORD_PATTERN = Regex(
        pattern = """验证码|校验码|动态码|动态口令|短信密码|一次性密码|安全码|登录码|认证码|确认码|授权码|""" +
            """verification\s+code|security\s+code|one[\s-]?time\s+(code|password)|""" +
            """\bOTP\b|\b(?:login|sign[\s-]?in|authentication|confirmation)\s+code\b|\byour\s+code\b|\bpasscode\b|\b2FA\b""",
        option = RegexOption.IGNORE_CASE,
    )

    /** 4–8 位数字或字母代码，以及分隔的数字代码。 */
    private val CODE_PATTERN = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{4,8}(?![A-Za-z0-9])""")
    private val SPACED_CODE_PATTERN = Regex("""(?<!\d)\d(?:[ -]?\d){3,7}(?!\d)""")
    private val NUMERIC_ONLY_PATTERN = Regex("""\d{4,8}""")

    /**
     * @return true 表示这是一条疑似验证码消息，应当放行且不上送。
     */
    fun isOtpLike(body: String, title: String? = null): Boolean {
        val text = listOfNotNull(title, body).joinToString("\n")
        if (text.isBlank()) return false
        return (KEYWORD_PATTERN.containsMatchIn(text) &&
            (CODE_PATTERN.containsMatchIn(text) || SPACED_CODE_PATTERN.containsMatchIn(text))) ||
            NUMERIC_ONLY_PATTERN.matches(body.trim())
    }
}
