package me.ethanxu.jevnoisegate.app

import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.core.data.settings.proxyFingerprint
import me.ethanxu.typesafe.sdk.ProxyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹与「生效中的代理」这两道闸。
 *
 * 代理页现在是草稿模型：配置只能经「保存」写入，而保存必然先测过。所以正常情况下
 * 已保存的配置就是测过的配置，指纹只是**安全网** —— 它拦的是绕过保存路径写进来的配置，
 * 最现实的一种是版本升级前留下的、从没测过的旧配置：那种配置不该因为升级而自动开始生效。
 *
 * 指纹同时是**自失效**的：它是对"那一组具体取值"算出来的，所以没有任何 setter
 * 需要记得去清标记，改任何一项都自动作废。这些用例就是在钉住这条性质。
 */
class ProxyFingerprintTest {

    @Test
    fun `指纹是 16 位小写十六进制`() {
        val fingerprint = proxyFingerprint("http", "127.0.0.1", 8080, "", "")
        assertEquals(16, fingerprint.length)
        assertTrue("实际为 $fingerprint", fingerprint.all { it in "0123456789abcdef" })
    }

    @Test
    fun `同一组配置得到同一个指纹`() {
        assertEquals(
            proxyFingerprint("socks5", "10.0.0.1", 1080, "u", "p"),
            proxyFingerprint("socks5", "10.0.0.1", 1080, "u", "p"),
        )
    }

    @Test
    fun `任何一项变了指纹就变`() {
        val base = proxyFingerprint("http", "127.0.0.1", 8080, "user", "pass")
        val variants = listOf(
            "类型" to proxyFingerprint("socks5", "127.0.0.1", 8080, "user", "pass"),
            "主机" to proxyFingerprint("http", "127.0.0.2", 8080, "user", "pass"),
            "端口" to proxyFingerprint("http", "127.0.0.1", 8081, "user", "pass"),
            "用户名" to proxyFingerprint("http", "127.0.0.1", 8080, "other", "pass"),
            // 只改密码也必须失效 —— 换密码本来就要重新验证。
            "密码" to proxyFingerprint("http", "127.0.0.1", 8080, "user", "other"),
        )
        variants.forEach { (field, fingerprint) ->
            assertFalse("改$field 后指纹应当变化", base == fingerprint)
        }
    }

    @Test
    fun `分隔符不会被拼接歧义吃掉`() {
        // "a|b" 与 "a" + "|b" 这类跨字段的错位必须得到不同的指纹，
        // 否则 "host=a|b, port=1" 与 "host=a, port=b|1" 会撞在一起。
        assertFalse(
            proxyFingerprint("http", "a|b", 1, "", "") ==
                proxyFingerprint("http", "a", 1, "b|", ""),
        )
    }

    // -----------------------------------------------------------------------
    // AppPreferences.isProxyVerified
    // -----------------------------------------------------------------------

    @Test
    fun `默认配置（直连）不算已验证`() {
        assertFalse(AppPreferences().isProxyVerified)
    }

    @Test
    fun `配置齐全但没验证过时不算已验证`() {
        val prefs = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "127.0.0.1",
            proxyPort = 8080,
        )
        assertTrue(prefs.isProxyConfigured)
        assertFalse(prefs.isProxyVerified)
    }

    @Test
    fun `指纹与被测那组配置一致时才算已验证`() {
        val verified = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "127.0.0.1",
            proxyPort = 8080,
            proxyUsername = "user",
            proxyPassword = "pass",
            proxyVerifiedFingerprint = proxyFingerprint("http", "127.0.0.1", 8080, "user", "pass"),
        )
        assertTrue(verified.isProxyVerified)
    }

    @Test
    fun `改过任何一个字段之后验证立刻失效`() {
        val verified = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "127.0.0.1",
            proxyPort = 8080,
            proxyUsername = "user",
            proxyPassword = "pass",
            proxyVerifiedFingerprint = proxyFingerprint("http", "127.0.0.1", 8080, "user", "pass"),
        )

        listOf(
            verified.copy(proxyType = AppPreferences.PROXY_SOCKS5),
            verified.copy(proxyHost = "10.0.0.1"),
            verified.copy(proxyPort = 8081),
            verified.copy(proxyUsername = "other"),
            verified.copy(proxyPassword = "other"),
        ).forEach { changed ->
            assertFalse("改动后应当失效：$changed", changed.isProxyVerified)
        }
    }

    // -----------------------------------------------------------------------
    // 真正的取用入口
    // -----------------------------------------------------------------------

    @Test
    fun `未验证时取不到生效的代理`() {
        val prefs = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "127.0.0.1",
            proxyPort = 8080,
        )
        assertNotNull("映射本身应当成立", proxySpecOrNull(prefs))
        assertNull("但未验证，不该生效", verifiedProxySpecOrNull(prefs))
    }

    @Test
    fun `已验证时生效，且凭据不会丢`() {
        val prefs = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "127.0.0.1",
            proxyPort = 8080,
            proxyUsername = "user",
            proxyPassword = "pass",
            proxyVerifiedFingerprint = proxyFingerprint("http", "127.0.0.1", 8080, "user", "pass"),
        )

        val spec = verifiedProxySpecOrNull(prefs)
        assertNotNull(spec)
        assertEquals(ProxyKind.HTTP, spec!!.kind)
        assertEquals("127.0.0.1", spec.host)
        assertEquals(8080, spec.port)
        // 这两条是回归断言：BackendModule 曾经只传前三个参数，
        // 于是 hasCredentials 恒为 false，需要认证的代理永远 407。
        assertEquals("user", spec.username)
        assertEquals("pass", spec.password)
        assertTrue(spec.hasCredentials)
    }

    @Test
    fun `地址非法的配置取不到生效的代理`() {
        // 指纹这一层不校验地址格式（那是应用层的事），所以这里造一个
        // "指纹对得上但主机非法"的状态，确认取用入口仍然会拦住它 ——
        // 两道闸是分层叠加的，不是二选一。
        val prefs = AppPreferences(
            proxyType = AppPreferences.PROXY_HTTP,
            proxyHost = "256.1.1.1",
            proxyPort = 8080,
            proxyVerifiedFingerprint = proxyFingerprint("http", "256.1.1.1", 8080, "", ""),
        )
        assertTrue("指纹这一层认为它已验证", prefs.isProxyVerified)
        assertNull("但地址格式这一层把它拦住了", verifiedProxySpecOrNull(prefs))
    }
}
