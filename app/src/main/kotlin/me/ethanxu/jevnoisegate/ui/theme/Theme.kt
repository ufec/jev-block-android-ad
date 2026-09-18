package me.ethanxu.jevnoisegate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import me.ethanxu.jevnoisegate.ui.util.findActivity

/**
 * 明暗模式。7 档，Monet（壁纸取色）三档与普通三档并列 ——
 * 用户改明暗时的心智是"我要什么观感"，把两个维度压成一维更好选。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    val isSystem: Boolean get() = this == SYSTEM || this == MONET_SYSTEM
    val isDark: Boolean get() = this == DARK || this == MONET_DARK || this == DARK_AMOLED
    val isAmoled: Boolean get() = this == DARK_AMOLED

    companion object {
        fun fromValue(value: Int): ColorMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

data class AppSettings(
    val colorMode: ColorMode = ColorMode.SYSTEM,
    /** 主题色 ARGB；[DYNAMIC_COLOR]（0）表示跟随系统壁纸取色。 */
    val keyColor: Int = DYNAMIC_COLOR,
) {
    val useDynamicColor: Boolean get() = keyColor == DYNAMIC_COLOR

    fun resolveDark(systemDark: Boolean): Boolean =
        if (colorMode.isSystem) systemDark else colorMode.isDark
}

/** 当前外观设置。个别需要感知主题的组件（自绘边框、图表配色）读取。 */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }

/** 当前是否深色。 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用主题。
 *
 * 用**官方稳定的 `MaterialTheme`**（material3 走 BOM 版本，不含任何 alpha API）。
 *
 * 刻意**不做逐色动画**：曾经试过让全部颜色角色各做一次弹簧过渡，
 * 但那意味着动画期间每一帧都要重建整套 ColorScheme 并让整棵树重组，
 * 代价与收益不成比例。明暗切换本身就是一次整屏重绘，直接切换更干脆。
 */
@Composable
fun JevNoiseGateTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = appSettings.resolveDark(isSystemInDarkTheme())
    val amoled = appSettings.colorMode.isAmoled
    val context = LocalContext.current

    val colorScheme: ColorScheme = rememberJevColorScheme(
        seedColor = if (appSettings.useDynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoled,
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.SPEC_2025,
    )

    // 状态栏/导航栏图标随明暗反转，否则深色主题下会看不清。
    LaunchedEffect(darkTheme) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalAppSettings provides appSettings,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

internal val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
)
