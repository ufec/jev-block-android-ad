package me.ethanxu.jevnoisegate.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 异步加载应用图标。
 *
 * 用 `produceState` + IO 而不是在 `remember` 里同步取：`getApplicationIcon` 与
 * `toBitmap` 都是实打实的工作，列表滚动时同步做会掉帧。
 *
 * 尺寸固定 96px 而不是原图 —— 系统的 `LauncherApps` 图标本来就带了密度变体，
 * 直接按显示尺寸解码能省下可观的堆内存。
 */
@Composable
fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(width = ICON_PX, height = ICON_PX)
                    .asImageBitmap()
            }.getOrNull()
        }
    }.value
}

private const val ICON_PX = 96

/**
 * 异步解析应用名。
 *
 * 与 [rememberAppIcon] 同理走 IO：`getApplicationInfo` 在部分 ROM 上会触发包管理器查询，
 * 同步做在列表滚动时会掉帧。
 *
 * 解析失败返回 null —— 调用方应退回显示包名，而不是"未知应用"这类无信息量的占位。
 */
@Composable
fun rememberAppLabel(packageName: String): String? {
    val context = LocalContext.current
    return produceState<String?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
        }
    }.value
}
