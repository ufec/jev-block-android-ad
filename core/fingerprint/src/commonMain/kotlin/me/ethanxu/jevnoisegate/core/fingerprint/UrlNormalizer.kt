package me.ethanxu.jevnoisegate.core.fingerprint

/** Scan each host token once. A failed long domain must not be retried at every character. */
internal object UrlNormalizer {
    // A standalone greedy token has no failing suffix/backtracking search. Using the platform's
    // word class also preserves Android/JVM Unicode differences in the previous URL regex.
    private val hostToken = Regex("""[\w.-]+""")
    private val urlWhitespace = Regex("""\s""")
    private val suffixes = listOf("com", "cn", "net", "org", "io", "xyz", "top", "info", "cc", "vip",
        "shop", "live", "club", "site", "online")

    fun replace(text: String): String = buildString(text.length) {
        var start = 0
        while (start < text.length) {
            val token = hostToken.find(text, start)
            if (token == null) { append(text, start, text.length); break }
            append(text, start, token.range.first)
            start = token.range.first
            val tokenEnd = token.range.last + 1
            var domainEnd = -1
            var explicitStart = -1
            for (i in start until tokenEnd) {
                if (explicitStart == -1 && explicitPrefixEnd(text, i) != -1) explicitStart = i
                if (i > start && text[i] == '.') {
                    val suffix = suffixes.firstOrNull { text.regionMatches(i + 1, it, 0, it.length, true) }
                    if (suffix != null) domainEnd = i + 1 + suffix.length
                }
            }
            // At the same start, http/www has precedence; a bare host uses the last valid suffix.
            if (explicitStart == start || domainEnd == -1 && explicitStart != -1) {
                append(text, start, explicitStart)
                var end = explicitPrefixEnd(text, explicitStart)
                while (end < text.length && !isUrlWhitespace(text[end])) end++
                append(TextNormalizer.PLACEHOLDER_URL)
                start = end
            } else if (domainEnd != -1) {
                var end = domainEnd
                if (end < text.length && text[end] == '/') {
                    while (end < text.length && !isUrlWhitespace(text[end])) end++
                }
                append(TextNormalizer.PLACEHOLDER_URL)
                start = end
            } else {
                append(text, start, tokenEnd)
                start = tokenEnd
            }
        }
    }

    private fun explicitPrefixEnd(text: String, start: Int): Int {
        val prefix = when {
            text.regionMatches(start, "https://", 0, 8, true) -> 8
            text.regionMatches(start, "http://", 0, 7, true) -> 7
            text.regionMatches(start, "www.", 0, 4, true) -> 4
            else -> return -1
        }
        val end = start + prefix
        return if (end < text.length && !isUrlWhitespace(text[end])) end else -1
    }
    private fun isUrlWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' ||
        // Android/JVM may differ on Unicode whitespace. Verify only possible Unicode spaces,
        // keeping ordinary URL characters on the allocation-free path.
        (c > '\u007f' && (c.isWhitespace() || c == '\u0085') && urlWhitespace.matches(c.toString()))
}
