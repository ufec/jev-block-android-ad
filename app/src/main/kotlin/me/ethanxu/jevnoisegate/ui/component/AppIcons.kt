package me.ethanxu.jevnoisegate.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本应用用到、但 `material-icons-core` 里没有的那几个图标。
 *
 * ## 为什么要自带
 *
 * 它们原先来自 `androidx.compose.material:material-icons-extended`。那个库把 5,529 个图标
 * 全量编成字节码 —— 光是 `androidx.compose.material.icons` 这个包就占掉 dex 定义字节码的
 * 46.7%（11,100 个 class / classes.jar 35.7 MiB），而本应用只用其中 4 个字形。
 * 为这 4 个把另外 5,525 个一起打进包，是 APK 体积最大的单一来源。
 * 该库在 1.7.8 之后已被 AndroidX 冻结，官方建议就是按需自带。
 *
 * ## 来源与许可
 *
 * 路径数据抄自 `material-icons-extended-android:1.7.8` 的 `-sources.jar`，原样未改。
 * Copyright 2025 The Android Open Source Project，Apache License 2.0。
 *
 * 用公开的 [ImageVector.Builder] 重写而非复用库里的 `materialIcon` / `materialPath` ——
 * 那两个是 `internal`，外部模块用不了。一个图标可能由多段路径组成（`Outlined.Speed`
 * 就是表盘 + 指针两段），因此每段各自对应一次 [path]。
 *
 * ## 为什么是 `by lazy`
 *
 * 图标会在每次重组时被读到，而这里每个都是一整串路径命令。库自己也是这么缓存的，
 * 少了这层缓存等于每次重组都重建一遍矢量。
 */
internal object AppIcons {

    object Filled {

        val Leaderboard: ImageVector by lazy {
            ImageVector.Builder(
                name = "Filled.Leaderboard",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(7.5f, 21.0f)
                    horizontalLineTo(2.0f)
                    verticalLineTo(9.0f)
                    horizontalLineToRelative(5.5f)
                    verticalLineTo(21.0f)
                    close()
                    moveTo(14.75f, 3.0f)
                    horizontalLineToRelative(-5.5f)
                    verticalLineToRelative(18.0f)
                    horizontalLineToRelative(5.5f)
                    verticalLineTo(3.0f)
                    close()
                    moveTo(22.0f, 11.0f)
                    horizontalLineToRelative(-5.5f)
                    verticalLineToRelative(10.0f)
                    horizontalLineTo(22.0f)
                    verticalLineTo(11.0f)
                    close()
                }
            }.build()
        }

        val Speed: ImageVector by lazy {
            ImageVector.Builder(
                name = "Filled.Speed",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(20.38f, 8.57f)
                    lineToRelative(-1.23f, 1.85f)
                    arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
                    lineTo(5.07f, 18.0f)
                    arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
                    lineToRelative(1.85f, -1.23f)
                    arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                    horizontalLineToRelative(13.85f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                    arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
                    close()
                    moveTo(10.59f, 15.41f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
                    lineToRelative(5.66f, -8.49f)
                    lineToRelative(-8.49f, 5.66f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
                    close()
                }
            }.build()
        }

        val Visibility: ImageVector by lazy {
            ImageVector.Builder(
                name = "Filled.Visibility",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(12.0f, 4.5f)
                    curveTo(7.0f, 4.5f, 2.73f, 7.61f, 1.0f, 12.0f)
                    curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                    reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
                    curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                    close()
                    moveTo(12.0f, 17.0f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                    reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                    reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                    reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                    close()
                    moveTo(12.0f, 9.0f)
                    curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                    reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                    reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                    reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
                    close()
                }
            }.build()
        }

        val VisibilityOff: ImageVector by lazy {
            ImageVector.Builder(
                name = "Filled.VisibilityOff",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(12.0f, 7.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                    curveToRelative(0.0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
                    lineToRelative(2.92f, 2.92f)
                    curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
                    curveToRelative(-1.73f, -4.39f, -6.0f, -7.5f, -11.0f, -7.5f)
                    curveToRelative(-1.4f, 0.0f, -2.74f, 0.25f, -3.98f, 0.7f)
                    lineToRelative(2.16f, 2.16f)
                    curveTo(10.74f, 7.13f, 11.35f, 7.0f, 12.0f, 7.0f)
                    close()
                    moveTo(2.0f, 4.27f)
                    lineToRelative(2.28f, 2.28f)
                    lineToRelative(0.46f, 0.46f)
                    curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1.0f, 12.0f)
                    curveToRelative(1.73f, 4.39f, 6.0f, 7.5f, 11.0f, 7.5f)
                    curveToRelative(1.55f, 0.0f, 3.03f, -0.3f, 4.38f, -0.84f)
                    lineToRelative(0.42f, 0.42f)
                    lineTo(19.73f, 22.0f)
                    lineTo(21.0f, 20.73f)
                    lineTo(3.27f, 3.0f)
                    lineTo(2.0f, 4.27f)
                    close()
                    moveTo(7.53f, 9.8f)
                    lineToRelative(1.55f, 1.55f)
                    curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                    curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                    curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
                    lineToRelative(1.55f, 1.55f)
                    curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
                    curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                    curveToRelative(0.0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
                    close()
                    moveTo(11.84f, 9.02f)
                    lineToRelative(3.15f, 3.15f)
                    lineToRelative(0.02f, -0.16f)
                    curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    lineToRelative(-0.17f, 0.01f)
                    close()
                }
            }.build()
        }

    }

    object Outlined {

        val Leaderboard: ImageVector by lazy {
            ImageVector.Builder(
                name = "Outlined.Leaderboard",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(16.0f, 11.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(8.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineTo(2.0f)
                    verticalLineToRelative(12.0f)
                    horizontalLineToRelative(20.0f)
                    verticalLineTo(11.0f)
                    horizontalLineTo(16.0f)
                    close()
                    moveTo(10.0f, 5.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(14.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineTo(5.0f)
                    close()
                    moveTo(4.0f, 11.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(8.0f)
                    horizontalLineTo(4.0f)
                    verticalLineTo(11.0f)
                    close()
                    moveTo(20.0f, 19.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineToRelative(-6.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineTo(19.0f)
                    close()
                }
            }.build()
        }

        val Speed: ImageVector by lazy {
            ImageVector.Builder(
                name = "Outlined.Speed",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(20.38f, 8.57f)
                    lineToRelative(-1.23f, 1.85f)
                    arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
                    horizontalLineTo(5.07f)
                    arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
                    lineToRelative(1.85f, -1.23f)
                    arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                    horizontalLineToRelative(13.85f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                    arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
                    close()
                }

                path(fill = SolidColor(Color.Black)) {
                    moveTo(10.59f, 15.41f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
                    lineToRelative(5.66f, -8.49f)
                    lineToRelative(-8.49f, 5.66f)
                    arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
                    close()
                }
            }.build()
        }

    }
}
