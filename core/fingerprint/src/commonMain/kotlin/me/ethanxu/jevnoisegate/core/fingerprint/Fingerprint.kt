package me.ethanxu.jevnoisegate.core.fingerprint

import me.ethanxu.jevnoisegate.core.model.MessageEvent
import me.ethanxu.jevnoisegate.core.model.bucketKey

/**
 * 流水线主键：决定哪些消息会命中同一条缓存决策。
 *
 * `fingerprint = hash(分桶键 | 模板骨架)`。
 *
 * 分桶键由 [bucketKey] 提供（通知按包名+渠道，短信按发件号码），这一步是必要的：
 * 只做文本归一化的话，不同 App 的相似文案会碰撞，一次误判会污染另一个 App 的全部通知。
 */
object Fingerprint {

    /**
     * 计算指纹。
     *
     * 使用 FNV-1a 64 位而非 SHA-1：这里的需求是"低碰撞的缓存键"，不是密码学安全。
     * 本地缓存规模在百万级以内，64 位的碰撞概率可忽略，而换来的是零依赖的纯 Kotlin 实现
     * （`commonMain` 里没有 SHA-1），且跨平台结果完全确定。
     */
    fun of(bucketKey: String, body: String): String =
        fnv1a64("$bucketKey|${TextNormalizer.normalize(body)}")

    /** 便捷重载：直接对事件计算，分桶键由事件自身推导。 */
    fun of(event: MessageEvent): String = of(event.bucketKey, event.body)

    /** 暴露骨架，供诊断页展示"为什么这两条被判为同一模板"。 */
    fun skeletonOf(body: String): String = TextNormalizer.normalize(body)

    private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
    private const val FNV_PRIME: ULong = 0x100000001b3uL

    private fun fnv1a64(input: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in input.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        return hash.toString(radix = 16).padStart(length = 16, padChar = '0')
    }
}
