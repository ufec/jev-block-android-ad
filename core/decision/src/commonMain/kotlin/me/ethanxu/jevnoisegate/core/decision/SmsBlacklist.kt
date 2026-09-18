package me.ethanxu.jevnoisegate.core.decision

/**
 * 短信发件人号码黑名单匹配。
 *
 * ## 为什么是硬规则
 *
 * 「用户偏好规则」是注入 state 交给模型权衡的**软约束**，模型可能不遵守。
 * 号码黑名单不同：用户填的是一个精确标识，"这个号码的短信我不要"没有需要权衡的余地。
 * 因此命中即拦截，**根本不调用模型** —— 既更可靠，也省下一次网络往返。
 *
 * ## 号码匹配为什么要做归一化
 *
 * 同一个号码在不同来源下写法不同：`+8613800138000`、`13800138000`、
 * `+86 138-0013-8000`。逐字符比较会全部失配，用户会以为功能坏了。
 * 这里去掉所有非数字字符后按**后缀**比较：
 *
 * - `8613800138000` 与 `13800138000` → 后者是前者的后缀，匹配
 * - 这样带不带国家码都能命中，代价是末几位相同的长号码会误判 ——
 *   但号码尾部相同的概率极低，且误判方向是"多拦一条"，用户能立刻发现并改掉。
 */
object SmsBlacklist {

    /**
     * 少于这个位数不参与比较。
     *
     * 取 4 而不是更大：`1069` 这类服务号前缀是合法且常用的写法，位数定高了会直接
     * 把它挡掉 —— 这一点是测试发现的，我最初写的 6 会让"填前几位"这个功能完全失效。
     *
     * 4 的下界足够拦住 `1`、`138` 这种会命中一大片的输入。
     */
    private const val MIN_DIGITS = 4

    fun matches(sender: String?, blacklist: List<String>): Boolean {
        if (sender.isNullOrBlank() || blacklist.isEmpty()) return false
        val normalizedSender = digitsOf(sender)
        if (normalizedSender.length < MIN_DIGITS) return false

        return blacklist.any { entry ->
            val normalizedEntry = digitsOf(entry)
            normalizedEntry.length >= MIN_DIGITS && matchesNormalized(normalizedSender, normalizedEntry)
        }
    }

    /**
     * 三种方向都要判，缺一不可 —— 它们对应三种真实场景：
     *
     * | 场景 | 例子 | 方向 |
     * |---|---|---|
     * | 完全相同 | `13800138000` vs `13800138000` | 相等 |
     * | 填了前几位（服务号端口） | `1069` vs `1069030012345678` | 发件人以条目**开头** |
     * | 一方带国家码 | `+8613800138000` vs `13800138000` | 发件人以条目**结尾** |
     *
     * 最初我只写了 endsWith，结果第二种场景完全不工作 ——
     * 而我在设置页上却写着"填前几位也会生效"。测试把这条不一致抓了出来。
     */
    private fun matchesNormalized(sender: String, entry: String): Boolean =
        sender == entry ||
            sender.startsWith(entry) ||
            sender.endsWith(entry) ||
            entry.endsWith(sender)

    /**
     * 只保留数字。
     *
     * 前缀 `+` 也一并去掉：`+86` 与 `86` 在比较时应当等价，
     * 保留它反而会让两边不一致。
     */
    private fun digitsOf(raw: String): String = raw.filter(Char::isDigit)
}
