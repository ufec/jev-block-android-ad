package me.ethanxu.jevnoisegate.ui.util

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 是否已授予通知使用权。这是硬依赖 —— 没有它 App 完全收不到数据。 */
fun Context.hasNotificationAccess(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

/** 是否已授予读取短信权限。非硬依赖，缺失时功能降级。 */
fun Context.hasSmsPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatTime(epochMillis: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

/** 从任意 Context 往上找宿主 Activity。主题需要它来设置状态栏图标明暗。 */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
