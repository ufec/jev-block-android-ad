package me.ethanxu.jevnoisegate.core.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SecretCodec {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

/** Device-bound AES-GCM key; old plaintext values are migrated on the first load. */
internal class AndroidSecretCodec : SecretCodec {
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private val codec = AesGcmSecretCodec(::key)
    override fun encrypt(value: String): String = codec.encrypt(value)
    override fun decrypt(value: String): String = codec.decrypt(value)

    companion object {
        const val PREFIX = AesGcmSecretCodec.PREFIX
        private const val ALIAS = "jevnoisegate.credentials.v1"
        private val keyLock = Any()
    }
}

internal class AesGcmSecretCodec(private val key: () -> SecretKey) : SecretCodec {
    override fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size >= 28) { "Invalid encrypted credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        const val PREFIX = "enc:v1:"
    }
}
