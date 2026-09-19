package me.ethanxu.jevnoisegate.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖值的编解码。
 *
 * 这两个函数是**纯 String 转换**，所以能在普通 JVM 单测里跑 ——
 * 这也是当初把它们从 `toAppPreferences` 里抽出来的原因：
 * 那里要碰 androidx 的 `Preferences`，测不动。
 *
 * 它们是"用户调完滑块，重启后设置还在不在"这条链路上唯一没法靠肉眼发现故障的一环，
 * 因此重点测往返与畸形输入。
 */
class CategoryOverridesTest {

    @Test
    fun `往返后映射不变`() {
        val values = mapOf("广告" to "0.9", "诈骗" to "0.7")

        assertEquals(values, parseOverrides(encodeOverrides(values)))
    }

    @Test
    fun `空映射编码成空串并能原样解析回来`() {
        assertEquals("", encodeOverrides(emptyMap<String, Float>()))
        assertEquals(emptyMap<String, String>(), parseOverrides(""))
    }

    @Test
    fun `类别名含等号时按最后一个等号切分`() {
        // 按第一个等号切会得到名字 "a"、值 "b=0.9"，值是解析不成浮点数的，
        // 于是整条覆盖被静默丢弃 —— 用户会看到"改了没生效"。
        val values = mapOf("a=b" to "0.9")

        assertEquals(values, parseOverrides(encodeOverrides(values)))
    }

    @Test
    fun `值为空的条目被丢弃`() {
        // 每个名字都要有值，否则解析出来是空串、进而在门槛解析里变成 NaN 风险。
        assertEquals(emptyMap<String, String>(), parseOverrides("广告=\n=0.9\n无等号的行"))
    }

    @Test
    fun `null 与空白输入得到空映射而不是崩溃`() {
        assertEquals(emptyMap<String, String>(), parseOverrides(null))
        assertEquals(emptyMap<String, String>(), parseOverrides("   \n\n  \n"))
    }

    @Test
    fun `多余的空白会被裁掉`() {
        assertEquals(mapOf("广告" to "0.9"), parseOverrides("  广告 = 0.9  "))
    }

    @Test
    fun `名字或值为空的条目不会进入编码结果`() {
        val encoded = encodeOverrides(mapOf("" to "0.9", "广告" to ""))

        assertTrue("空名字或空值不该出现：$encoded", encoded.isEmpty())
    }
}
