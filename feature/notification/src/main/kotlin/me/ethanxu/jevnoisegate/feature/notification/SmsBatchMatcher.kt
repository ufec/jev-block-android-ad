package me.ethanxu.jevnoisegate.feature.notification

import me.ethanxu.jevnoisegate.core.common.sms.PendingSmsSuppression

internal data class SmsNotificationCandidate(val key: String, val body: String, val title: String?, val grouped: Boolean)

/** A retry round reads and parses the active notifications once, regardless of queue size. */
internal fun matchSmsBatch(
    pending: List<PendingSmsSuppression>,
    load: () -> List<SmsNotificationCandidate>,
    nowMs: () -> Long = System::currentTimeMillis,
    cancel: (String) -> Boolean,
): Set<PendingSmsSuppression> {
    if (pending.isEmpty()) return emptySet()
    val index = SmsCandidateIndex(load())
    val consumed = mutableSetOf<String>()
    return pending.filter { entry ->
        if (entry.expiresAtMs <= nowMs()) return@filter false
        val target = index.uniqueMatch(entry.body.trim(), entry.sender.orEmpty().filter(Char::isDigit), consumed)
            ?: return@filter false
        if (entry.expiresAtMs <= nowMs()) return@filter false
        cancel(target.key).also { if (it) consumed += target.key }
    }.toSet()
}

/** Exact bodies use hash lookups; truncated previews use a prefix trie, never a full P × A scan. */
private class SmsCandidateIndex(candidates: List<SmsNotificationCandidate>) {
    private data class Prepared(val index: Int, val key: String, val number: String)
    private class Node {
        val children = mutableMapOf<Char, Edge>()
        val candidates = mutableListOf<Prepared>()
    }
    private data class Edge(val text: String, val node: Node)
    private val exact = mutableMapOf<String, MutableList<Prepared>>()
    private val root = Node()

    init {
        candidates.forEachIndexed { index, candidate ->
            val text = candidate.body.trim()
            if (!candidate.grouped && text.length >= 6) {
                val prepared = Prepared(index, candidate.key, candidate.title.orEmpty().filter(Char::isDigit))
                exact.getOrPut(text) { mutableListOf() }.add(prepared)
                val prefix = text.removeSuffix("...").removeSuffix("…")
                if ((text.endsWith("…") || text.endsWith("...")) && prefix.length >= 16) {
                    addPrefix(prefix, prepared)
                }
            }
        }
    }

    // Compressed edges keep node count proportional to candidates, even for very long previews.
    private fun addPrefix(prefix: String, candidate: Prepared) {
        var node = root
        var offset = 0
        while (offset < prefix.length) {
            val char = prefix[offset]
            val edge = node.children[char]
            if (edge == null) {
                node.children[char] = Edge(prefix.substring(offset), Node().also { it.candidates += candidate })
                return
            }
            var common = 0
            while (common < edge.text.length && offset + common < prefix.length &&
                edge.text[common] == prefix[offset + common]) common++
            if (common < edge.text.length) {
                val branch = Node()
                val suffix = edge.text.substring(common)
                branch.children[suffix[0]] = Edge(suffix, edge.node)
                node.children[char] = Edge(edge.text.substring(0, common), branch)
                node = branch
            } else node = edge.node
            offset += common
        }
        node.candidates += candidate
    }

    fun uniqueMatch(body: String, senderNumber: String, consumed: Set<String>): SmsNotificationCandidate? {
        var target: Prepared? = null
        val seen = mutableSetOf<Int>()
        fun accept(candidates: List<Prepared>): Boolean {
            for (candidate in candidates) {
                if (!seen.add(candidate.index) || candidate.key in consumed) continue
                val number = candidate.number
                if (number.length >= 4 && senderNumber.isNotEmpty() &&
                    !senderNumber.endsWith(number) && !number.endsWith(senderNumber)) continue
                if (target != null) return false // Ambiguous: leave the notification visible.
                target = candidate
            }
            return true
        }
        if (!accept(exact[body].orEmpty())) return null
        var node = root
        var offset = 0
        while (offset < body.length) {
            val edge = node.children[body[offset]] ?: break
            if (!body.regionMatches(offset, edge.text, 0, edge.text.length)) break
            offset += edge.text.length
            node = edge.node
            if (!accept(node.candidates)) return null
        }
        return target?.let { SmsNotificationCandidate(it.key, "", null, false) }
    }
}
