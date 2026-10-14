package me.ethanxu.jevnoisegate.core.fingerprint

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlNormalizerTest {
    private val original = Regex("""(?:https?://|www\.)\S+|[\w.-]+\.(?:com|cn|net|org|io|xyz|top|info|cc|vip|shop|live|club|site|online)(?:/\S*)?""", RegexOption.IGNORE_CASE)

    @Test fun `linear scanner preserves existing URL precedence suffix and path semantics`() {
        val cases = listOf("", "example.com/a 其他", "HTTP://example.cn/path?x=12", "www.X trailing",
            "foo.www.example.com/abc", "example.company", "..com", ".com", "a.com.cn/end",
            "x.comhttp://other.io", "abcwww.X", "abchttps://X", "www. ", "x.com/ ",
            "域名中文example.cn价格", "x.com，说明", "foo.NET/bar\nnext", "example.com\u3000继续",
            "https://example.com\u3000继续", "www.example.cn\u00a0继续", "x.com/path\u0085继续")
        cases.forEach { assertEquals(original.replace(it, "U"), UrlNormalizer.replace(it), it) }
        val random = Random(18)
        val atoms = listOf("abc", "9", ".com", ".cn", ".nope", "www.", "https://", "/path", "_", "-", " ", "中文", "\n", ":")
        repeat(300) {
            val text = (0 until 12).joinToString("") { atoms[random.nextInt(atoms.size)] }
            assertEquals(original.replace(text, "U"), UrlNormalizer.replace(text), text)
        }
    }

    @Test fun `long negative hosts digits and URLs retain complete input semantics`() {
        assertEquals("a".repeat(40), TextNormalizer.normalize("a".repeat(65_536)))
        assertEquals("N", TextNormalizer.normalize("9".repeat(65_536)))
        assertEquals("U尾部", TextNormalizer.normalize("a".repeat(65_536) + ".com/path 尾部"))
        assertEquals("前缀U尾部", TextNormalizer.normalize("前缀https://example.com/" + "a".repeat(65_536) + " 尾部"))
    }
}
