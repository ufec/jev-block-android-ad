package me.ethanxu.jevnoisegate.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 主题色预设。15 个 Material 标准色。
 *
 * 用固定色板而不是自由取色环：主题色是低频设置，用户要的是"几个好看的选项"，
 * 自由取值很容易配出难以辨认的界面。
 */
val keyColorOptions: List<Int> = listOf(
    Color(0xFFF44336).toArgb(),
    Color(0xFFE91E63).toArgb(),
    Color(0xFF9C27B0).toArgb(),
    Color(0xFF673AB7).toArgb(),
    Color(0xFF3F51B5).toArgb(),
    Color(0xFF2196F3).toArgb(),
    Color(0xFF00BCD4).toArgb(),
    Color(0xFF009688).toArgb(),
    Color(0xFF4FAF50).toArgb(),
    Color(0xFFFFEB3B).toArgb(),
    Color(0xFFFFC107).toArgb(),
    Color(0xFFFF9800).toArgb(),
    Color(0xFF795548).toArgb(),
    Color(0xFF607D8F).toArgb(),
    Color(0xFFFF9CA8).toArgb(),
)

/** 0 表示跟随系统壁纸取色。与 `AppPreferences.keyColor` 的约定一致。 */
const val DYNAMIC_COLOR: Int = 0
