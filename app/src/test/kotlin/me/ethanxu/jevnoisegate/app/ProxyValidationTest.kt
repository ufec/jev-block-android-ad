package me.ethanxu.jevnoisegate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主机/端口格式校验。
 *
 * 重点不在"能认出合法地址"，而在**不误杀**：把合法地址判成非法会直接挡住一个
 * 本来能用的配置，用户没有任何绕过办法。所以每个"应当放行"的用例都值一条断言。
 */
class ProxyValidationTest {

    // -----------------------------------------------------------------------
    // 主机：应当放行
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 合法`() {
        listOf("192.168.31.1", "10.0.0.1", "127.0.0.1", "0.0.0.0", "255.255.255.255", "1.1.1.1")
            .forEach { assertNull("$it 应当放行", proxyHostError(it)) }
    }

    @Test
    fun `域名合法，含单 label 与结尾的点`() {
        listOf(
            "localhost",
            "proxy.corp.internal",
            "a.b",
            "example.com.",
            "xn--fiqs8s.example",
            "my-proxy.example.com",
            "a1.b2.c3",
        ).forEach { assertNull("$it 应当放行", proxyHostError(it)) }
    }

    @Test
    fun `IPv6 合法`() {
        listOf(
            "::1",
            "::",
            "fe80::1",
            "2001:db8:0:0:0:0:0:1",
            "2001:db8::1",
            "::ffff:127.0.0.1",
        ).forEach { assertNull("$it 应当放行", proxyHostError(it)) }
    }

    @Test
    fun `留空不报错 —— 留空是「不用代理」，不是「填错了」`() {
        listOf("", "   ", "\t").forEach { assertNull(proxyHostError(it)) }
    }

    @Test
    fun `两端空白会被忽略`() {
        assertNull(proxyHostError("  192.168.31.1  "))
        assertNull(proxyHostError("  proxy.example.com  "))
    }

    // -----------------------------------------------------------------------
    // 主机：应当拦下
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 越界或被截断都不合法`() {
        listOf("256.1.1.1", "1.1.1", "1.1.1.1.1", "999.999.999.999", "1.1.1.")
            .forEach { assertNotNull("$it 应当被拦下", proxyHostError(it)) }
    }

    @Test
    fun `把主机和端口一起粘进主机栏时，提示指向端口栏`() {
        val error = proxyHostError("127.0.0.1:8080")
        assertNotNull(error)
        assertEquals("端口请填到端口栏，这里只填主机", error)

        // 只留一个冒号也算同一种误操作，包括没跟端口的情况。
        assertEquals("端口请填到端口栏，这里只填主机", proxyHostError("proxy.example.com:3128"))
        assertEquals("端口请填到端口栏，这里只填主机", proxyHostError("127.0.0.1:"))
    }

    @Test
    fun `IPv6 加方括号会被指出去掉`() {
        val error = proxyHostError("[::1]")
        assertNotNull(error)
        assertNotNull(proxyHostError("[fe80::1]"))
    }

    @Test
    fun `带协议头会被拦下`() {
        listOf("http://127.0.0.1", "https://proxy.example.com", "socks5://127.0.0.1")
            .forEach { assertNotNull("$it 应当被拦下", proxyHostError(it)) }
    }

    @Test
    fun `中间有空格会被拦下`() {
        assertNotNull(proxyHostError("192.168 31.1"))
        assertNotNull(proxyHostError("proxy example.com"))
    }

    @Test
    fun `非法 IPv6 会被拦下`() {
        listOf(
            "gggg::1",             // 非十六进制字符
            "1::2::3",             // 两个 ::
            "1:2",                 // 段数不够
            "2001:db8:0:0:0:0:0:1:2", // 段数超了
            "12345::1",            // 单段超过 4 位
        ).forEach { assertNotNull("$it 应当被拦下", proxyHostError(it)) }
    }

    @Test
    fun `域名里的下划线与首尾连字符会被拦下`() {
        listOf("my_proxy.example.com", "-proxy.example.com", "proxy-.example.com", "a..b")
            .forEach { assertNotNull("$it 应当被拦下", proxyHostError(it)) }
    }

    // -----------------------------------------------------------------------
    // 端口
    // -----------------------------------------------------------------------

    @Test
    fun `端口合法`() {
        listOf("1", "80", "3128", "8080", "65535")
            .forEach { assertNull("$it 应当放行", proxyPortError(it)) }
    }

    @Test
    fun `端口留空不报错`() {
        listOf("", "  ").forEach { assertNull(proxyPortError(it)) }
    }

    @Test
    fun `端口越界被拦下`() {
        listOf("0", "65536", "99999").forEach {
            assertNotNull("$it 应当被拦下", proxyPortError(it))
        }
    }

    // -----------------------------------------------------------------------
    // 组合判断：给界面用来禁用测试按钮
    // -----------------------------------------------------------------------

    @Test
    fun `地址不齐时给出可用于禁用按钮的理由`() {
        assertEquals("请先填写代理主机", proxyAddressProblem("", ""))
        assertEquals("请先填写代理主机", proxyAddressProblem("", "8080"))
        assertEquals("请先填写代理端口", proxyAddressProblem("127.0.0.1", ""))
        assertEquals("IPv4 地址应为四段 0–255 的数字，用点分隔", proxyAddressProblem("256.1.1.1", "8080"))
        assertEquals("端口必须在 1–65535 之间", proxyAddressProblem("127.0.0.1", "99999"))
    }

    @Test
    fun `地址齐备且合法时不拦`() {
        assertNull(proxyAddressProblem("127.0.0.1", "8080"))
        assertNull(proxyAddressProblem("proxy.corp.internal", "3128"))
        assertNull(proxyAddressProblem("::1", "1080"))
    }
}
