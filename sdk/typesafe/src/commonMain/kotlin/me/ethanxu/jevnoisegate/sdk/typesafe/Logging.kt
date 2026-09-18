package me.ethanxu.jevnoisegate.sdk.typesafe

/** 日志级别，由详细到简略。对应上游 `LOG_LEVELS`。 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR, OFF;
}

/** 默认日志级别。对应上游 `DEFAULT_LOG_LEVEL`。 */
val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.WARN

/**
 * 日志接收器。
 *
 * 上游直接依赖 `console`；Android 侧应适配到 `android.util.Log`，
 * 因此这里只保留接口，由宿主注入实现。
 */
interface TypeSafeLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

/** 不做任何事的日志器。SDK 的默认值 —— 静默优于意外打到 logcat。 */
object NoOpLogger : TypeSafeLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String, throwable: Throwable?) = Unit
    override fun error(message: String, throwable: Throwable?) = Unit
}

/** 把日志器按级别过滤，低于 [level] 的调用变成空操作。对应上游 `withLevel`。 */
internal fun TypeSafeLogger.atLevel(level: LogLevel): TypeSafeLogger {
    if (level == LogLevel.OFF) return NoOpLogger
    val rank = level.ordinal
    return object : TypeSafeLogger {
        override fun debug(message: String) {
            if (LogLevel.DEBUG.ordinal >= rank) this@atLevel.debug(message)
        }

        override fun info(message: String) {
            if (LogLevel.INFO.ordinal >= rank) this@atLevel.info(message)
        }

        override fun warn(message: String, throwable: Throwable?) {
            if (LogLevel.WARN.ordinal >= rank) this@atLevel.warn(message, throwable)
        }

        override fun error(message: String, throwable: Throwable?) {
            if (LogLevel.ERROR.ordinal >= rank) this@atLevel.error(message, throwable)
        }
    }
}

/** 保留 key 后缀以辨认身份的凭证类 header。 */
private val KEY_HEADERS: Set<String> = setOf("authorization", "proxy-authorization", "x-api-key")

/** 值整体打码的 header。 */
private val OPAQUE_HEADERS: Set<String> = setOf("cookie", "set-cookie")

/**
 * 打码一个凭证值：保留 scheme，并在密钥长度大于 8 时保留末四位。
 * 对应上游 `redactKey`。
 */
private fun redactKey(value: String): String {
    val parts = value.split(Regex("\\s+"), limit = 2)
    val scheme = if (parts.size == 2) parts[0] else null
    val secret = if (parts.size == 2) parts[1] else parts[0]
    val tail = if (secret.length > 8) secret.takeLast(4) else ""
    return (scheme?.let { "$it " } ?: "") + "***" + tail
}

/** 复制一份 header，把已知凭证打码。对应上游 `redactHeaders`。 */
internal fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) ->
        when (name.lowercase()) {
            in KEY_HEADERS -> redactKey(value)
            in OPAQUE_HEADERS -> "***"
            else -> value
        }
    }
