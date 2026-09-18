package me.ethanxu.jevnoisegate.core.fingerprint

/**
 * 把消息正文归一化成"模板骨架"。
 *
 * 目的：让同一模板的不同实例落到同一个字符串上。
 * 「【淘宝】您关注的商品降价了，仅需￥199」与「……￥299」必须归一化成同一骨架，
 * 否则指纹缓存永远命中不了，每条通知都要打一次网络请求 —— 延迟和成本都会失控。
 *
 * 归一化顺序**不可调换**：URL 必须最先处理，因为其中含有数字与点号，
 * 若先替换数字，URL 会被切碎成无法识别的残片。
 */
object TextNormalizer {

    /** 骨架的最大长度。超过部分丢弃，避免长正文（如带签名档的短信）造成指纹发散。 */
    const val MAX_SKELETON_LENGTH = 40

    /** 占位符：URL。 */
    const val PLACEHOLDER_URL = "U"

    /** 占位符：一切数值（金额、日期、时间、纯数字串）。 */
    const val PLACEHOLDER_NUM = "N"

    // 1) URL —— 必须最先执行
    private val URL_PATTERN = Regex(
        pattern = """(?:https?://|www\.)\S+""" +
            """|[\w.-]+\.(?:com|cn|net|org|io|xyz|top|info|cc|vip|shop|live|club|site|online)(?:/\S*)?""",
        option = RegexOption.IGNORE_CASE,
    )

    // 2) 金额 —— 需在裸数字之前处理，否则「￥199」会先被切成「￥N」
    private val AMOUNT_PATTERN = Regex(
        """[¥￥$]\s*\d+(?:[.,]\d+)?""" +
            """|\d+(?:[.,]\d+)?\s*(?:元|万元|万|亿|块|折)""",
    )

    // 3) 日期与时间
    private val DATE_TIME_PATTERN = Regex(
        """\d{4}\s*[-/.年]\s*\d{1,2}\s*[-/.月]\s*\d{1,2}\s*日?""" +
            """|\d{1,2}\s*[-/.月]\s*\d{1,2}\s*[日号]?""" +
            """|\d{1,2}\s*[日号]""" +
            """|\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?""",
    )

    // 4) 剩下的裸数字串
    private val DIGITS_PATTERN = Regex("""\d+""")

    // 5) 空白与装饰符号 —— 去掉它们，让排版差异不影响同一模板的归并
    private val NOISE_PATTERN = Regex(
        """[\s\u3000\u00A0]+""" +
            """|[，,。.！!？?;；:：、·~@#*_\-—–=+\\/|<>\[\]{}()（）【】「」『』“”"'‘’…]""",
    )

    /**
     * 归一化正文，返回模板骨架。
     *
     * 空输入返回空串；调用方无需特殊处理，空串同样是一个合法的（退化的）指纹输入。
     */
    fun normalize(body: String): String {
        var text = body

        text = URL_PATTERN.replace(text, PLACEHOLDER_URL)
        text = AMOUNT_PATTERN.replace(text, PLACEHOLDER_NUM)
        text = DATE_TIME_PATTERN.replace(text, PLACEHOLDER_NUM)
        text = DIGITS_PATTERN.replace(text, PLACEHOLDER_NUM)
        text = NOISE_PATTERN.replace(text, "")

        return text.take(MAX_SKELETON_LENGTH)
    }
}
