package me.ethanxu.jevnoisegate.feature.notification

/** Accessed on the listener main thread, including the final cancellation check. */
internal class NotificationGenerations {
    private var sequence = 0L
    private val versions = mutableMapOf<String, Long>()
    private val content = mutableMapOf<String, Pair<String?, String>>()
    fun matchesPending(key: String, title: String?, body: String): Boolean =
        key in versions && content[key] == (title to body)
    fun begin(key: String, title: String? = null, body: String = ""): Long = (++sequence).also {
        versions[key] = it
        content[key] = title to body
    }
    fun isCurrent(key: String, generation: Long): Boolean = versions[key] == generation
    fun remove(key: String) { versions.remove(key); content.remove(key) }
    fun clear() { versions.clear(); content.clear() }
}
