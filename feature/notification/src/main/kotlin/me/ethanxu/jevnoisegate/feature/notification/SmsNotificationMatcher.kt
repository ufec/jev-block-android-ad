package me.ethanxu.jevnoisegate.feature.notification

internal object SmsNotificationMatcher {
    fun matches(body: String, shown: String, sender: String?, title: String?, grouped: Boolean): Boolean {
        if (grouped || shown.trim().length < 6) return false
        val shownNumber = title.orEmpty().filter(Char::isDigit)
        val senderNumber = sender.orEmpty().filter(Char::isDigit)
        if (shownNumber.length >= 4 && senderNumber.isNotEmpty() &&
            !senderNumber.endsWith(shownNumber) && !shownNumber.endsWith(senderNumber)) return false
        val text = shown.trim()
        if (body.trim() == text) return true
        val truncated = text.endsWith("…") || text.endsWith("...")
        val prefix = text.removeSuffix("...").removeSuffix("…")
        return truncated && prefix.length >= 16 && body.trim().startsWith(prefix)
    }
}
