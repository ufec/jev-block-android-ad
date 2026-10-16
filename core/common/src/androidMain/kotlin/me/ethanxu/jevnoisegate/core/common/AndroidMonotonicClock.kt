package me.ethanxu.jevnoisegate.core.common

import android.content.Context
import android.provider.Settings

object AndroidMonotonicClock : Clock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
    fun bootCount(context: Context): Int? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
}
