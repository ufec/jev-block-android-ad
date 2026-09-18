package me.ethanxu.jevnoisegate.core.common

/**
 * 可注入的时间源。
 *
 * 存在的理由是**测试**：去重窗口（5 秒）与回收站 TTL（24 小时）都依赖当前时间，
 * 直接用 `System.currentTimeMillis()` 会让这类逻辑无法确定性测试。
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** 生产实现，直接读系统时钟。 */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * 可注入的日志出口。
 *
 * 核心模块保持零 Android 依赖，因此不能直接用 `android.util.Log`；
 * 由 app 模块在装配时注入一个基于 `Log` 的实现。
 */
interface AppLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
}

/** 默认实现，不产生任何输出。 */
object NoOpAppLogger : AppLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String, error: Throwable?) = Unit
}
