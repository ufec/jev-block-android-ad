package me.ethanxu.jevnoisegate.core.data

import java.security.MessageDigest

/** Corrections apply to complete identical content, never to a truncated template. */
fun correctionKey(bucket: String, title: String?, body: String): String {
    val encoded = listOf(bucket, title.orEmpty(), body).joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
