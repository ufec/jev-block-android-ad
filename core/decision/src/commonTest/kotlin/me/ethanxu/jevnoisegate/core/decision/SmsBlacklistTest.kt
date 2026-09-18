package me.ethanxu.jevnoisegate.core.decision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 号码黑名单的匹配规则。
 *
 * 这些断言覆盖的是**最容易出错的一层**：号码在不同来源下写法不同，
 * 逐字符比较会全部失配，用户会以为功能坏了。所以归一化与后缀匹配必须有测试兜住。
 */
class SmsBlacklistTest {

    @Test
    fun `完全相同即命中`() {
        assertTrue(SmsBlacklist.matches("13800138000", listOf("13800138000")))
    }

    @Test
    fun `忽略空格与横杠`() {
        // 用户从通讯录复制过来的号码通常带分隔符
        assertTrue(SmsBlacklist.matches("+86 138-0013-8000", listOf("13800138000")))
        assertTrue(SmsBlacklist.matches("13800138000", listOf("+86 138 0013 8000")))
    }

    @Test
    fun `带不带国家码都能命中`() {
        assertTrue(SmsBlacklist.matches("+8613800138000", listOf("13800138000")))
        assertTrue(SmsBlacklist.matches("13800138000", listOf("+8613800138000")))
    }

    @Test
    fun `填前几位也能命中`() {
        // 106 开头的短信端口号很长且不固定，用户通常只记得前几位
        assertTrue(SmsBlacklist.matches("1069030012345678", listOf("1069")))
    }

    @Test
    fun `不相关的号码不命中`() {
        assertFalse(SmsBlacklist.matches("13900139000", listOf("13800138000")))
    }

    @Test
    fun `过短的条目不参与匹配`() {
        // 否则填个 "1" 就会把所有号码都拦掉
        assertFalse(SmsBlacklist.matches("13800138000", listOf("1")))
        assertFalse(SmsBlacklist.matches("13800138000", listOf("138")))
    }

    @Test
    fun `发件人为空或黑名单为空时不命中`() {
        assertFalse(SmsBlacklist.matches(null, listOf("13800138000")))
        assertFalse(SmsBlacklist.matches("", listOf("13800138000")))
        assertFalse(SmsBlacklist.matches("13800138000", emptyList()))
    }

    @Test
    fun `发件人过短时不命中`() {
        // 3 位数（如 110）低于阈值，不参与比较 —— 否则会命中一大片号码。
        assertFalse(SmsBlacklist.matches("110", listOf("110")))
    }

    @Test
    fun `五位服务号可以被拉黑`() {
        // 10086 这类服务号必须能被拉黑，否则银行/运营商的短信没法处理。
        // 我最初的实现把阈值定成 6，导致它们全部失效。
        assertTrue(SmsBlacklist.matches("10086", listOf("10086")))
    }

    @Test
    fun `命中列表中任意一条即可`() {
        val list = listOf("13800138000", "1069", "95588")
        assertTrue(SmsBlacklist.matches("1069111111", list))
        assertTrue(SmsBlacklist.matches("+95588", list))
    }
}
