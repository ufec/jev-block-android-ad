package me.ethanxu.jevnoisegate.core.data.settings

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class SecretCodecTest {
    @Test fun `AES GCM encrypts credentials with fresh IVs and detects tampering`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val codec = AesGcmSecretCodec { key }
        val first = codec.encrypt("api-key/代理密码")
        val second = codec.encrypt("api-key/代理密码")
        assertNotEquals(first, second)
        assertFalse(first.contains("api-key"))
        assertEquals("api-key/代理密码", codec.decrypt(first))
        val bytes = Base64.getDecoder().decode(first.removePrefix(AesGcmSecretCodec.PREFIX))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try {
            codec.decrypt(AesGcmSecretCodec.PREFIX + Base64.getEncoder().encodeToString(bytes))
            fail("Tampered ciphertext must fail authentication")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertEquals("legacy", codec.decrypt("legacy"))
        assertEquals("", codec.encrypt(""))
    }
}
