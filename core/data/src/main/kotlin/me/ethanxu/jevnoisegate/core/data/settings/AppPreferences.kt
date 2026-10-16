package me.ethanxu.jevnoisegate.core.data.settings

/** Persisted local settings. Old API and proxy keys are deliberately ignored on migration. */
data class AppPreferences(
    val uiMode: String = DEFAULT_UI_MODE,
    val colorMode: Int = DEFAULT_COLOR_MODE,
    val keyColor: Int = DEFAULT_KEY_COLOR,
    val mutedPackages: Set<String> = emptySet(),

    val preferAllowRules: List<String> = emptyList(),
    val preferBlockRules: List<String> = emptyList(),
    val categoryThresholds: Map<String, Float> = emptyMap(),
    val categoryActions: Map<String, String> = emptyMap(),
    val smsSenderBlacklist: List<String> = emptyList(),
    val developerMode: Boolean = false,
    val logLevel: Int = DEFAULT_LOG_LEVEL,
) {
    companion object {
        const val DEFAULT_UI_MODE = "miuix"
        const val DEFAULT_COLOR_MODE = 0
        const val DEFAULT_KEY_COLOR = 0
        const val DEFAULT_LOG_LEVEL = 2
    }
}

