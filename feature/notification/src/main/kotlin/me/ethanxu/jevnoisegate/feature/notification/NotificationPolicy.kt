package me.ethanxu.jevnoisegate.feature.notification

internal object NotificationPolicy {
    fun shouldProcess(source: String, own: String, developerMode: Boolean, muted: Set<String>): Boolean =
        source !in muted && (source != own || developerMode)
}
