package me.ethanxu.jevnoisegate.sdk.typesafe

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** 单次尝试的默认超时（毫秒）。对应上游 `DEFAULT_TIMEOUT_MS`。 */
const val DEFAULT_TIMEOUT_MS: Long = 10_000

/** 默认重试状态码：408、429，以及全部 5xx。对应上游默认值。 */
val DEFAULT_RETRYABLE_STATUSES: Set<Int> = buildSet {
    add(408)
    add(429)
    addAll(500..599)
}

/**
 * 重试策略。字段与默认值**逐项对齐**上游 `DEFAULT_RETRY_POLICY`。
 *
 * @throws TypeSafeException 任一字段取值非法。
 */
data class RetryPolicy(
    /** 首次尝试之外的最大重试次数；0 表示不重试。 */
    val maxRetries: Int = 2,
    /** 首次退避延迟（毫秒），此后翻倍直至 [backoffMaxMs]。 */
    val backoffInitialMs: Long = 500,
    /** 退避延迟上限（毫秒）。 */
    val backoffMaxMs: Long = 5_000,
    /** 每次退避随机扣减的比例，0–1。 */
    val backoffJitter: Double = 0.25,
    /** 触发重试的 HTTP 状态码。 */
    val httpStatuses: Set<Int> = DEFAULT_RETRYABLE_STATUSES,
    /** 是否优先采用服务端 `Retry-After` / `retry-after-ms`。 */
    val respectRetryAfter: Boolean = true,
    /** 服务端重试延迟上限（毫秒）；超出则退回退避算法。 */
    val maxRetryAfterMs: Long = 60_000,
    /** 是否重试连接失败。 */
    val apiConnectionError: Boolean = true,
    /** 是否重试超时。 */
    val apiTimeoutError: Boolean = true,
) {
    init {
        if (maxRetries < 0) {
            throw TypeSafeException("RetryPolicy.maxRetries must be non-negative, got $maxRetries.")
        }
        if (backoffInitialMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.backoffInitialMs must be non-negative, got $backoffInitialMs.",
            )
        }
        if (backoffMaxMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.backoffMaxMs must be non-negative, got $backoffMaxMs.",
            )
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw TypeSafeException(
                "RetryPolicy.backoffJitter must be between 0 and 1, got $backoffJitter.",
            )
        }
        if (maxRetryAfterMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.maxRetryAfterMs must be non-negative, got $maxRetryAfterMs.",
            )
        }
        httpStatuses.forEach { status ->
            if (status < 100 || status > 999) {
                throw TypeSafeException(
                    "RetryPolicy.httpStatuses must contain HTTP status codes, got $status.",
                )
            }
        }
    }

    /** 该状态码是否可重试。 */
    fun isRetryableStatus(status: Int): Boolean = status in httpStatuses
}

/**
 * 解析 `retry-after-ms` 或 `Retry-After` 为毫秒，优先 `retry-after-ms`。
 *
 * `Retry-After` 支持两种形式：秒数，或 HTTP 日期。两者都无法解析时返回 null。
 */
internal fun parseRetryAfter(
    headers: Map<String, String>,
    nowMs: Long,
): Long? {
    headers["retry-after-ms"]?.let { raw ->
        val ms = raw.trim().toDoubleOrNull()
        if (ms != null && ms.isFinite() && ms >= 0) return ms.toLong()
    }

    val raw = headers["retry-after"]?.trim() ?: return null
    raw.toDoubleOrNull()?.let { seconds ->
        return if (seconds >= 0) (seconds * 1000).toLong() else null
    }
    return parseHttpDateToEpochMillis(raw)?.let { maxOf(0L, it - nowMs) }
}

// ---------------------------------------------------------------------------
// HTTP-date 解析
//
// 上游用 `Date.parse(raw)` 处理 `Retry-After: <HTTP-date>` 形式。Kotlin 的 commonMain
// 里没有 java.time，而为了这一条极少数路径引入 kotlinx-datetime 不划算，
// 因此按 RFC 1123 格式手写解析。日期→纪元秒用 Howard Hinnant 的 days_from_civil 算法，
// 是精确的整数运算，不涉及闰年特判。
// ---------------------------------------------------------------------------

private val RFC_1123_PATTERN = Regex(
    """(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\s*(\d{1,2})\s*([A-Za-z]{3})\s*(\d{4})\s*""" +
        """(\d{1,2}):(\d{2}):(\d{2})\s*GMT""",
    RegexOption.IGNORE_CASE,
)

private val MONTH_INDEX: Map<String, Int> = listOf(
    "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
).withIndex().associate { (index, name) -> name to index + 1 }

/** 解析 RFC 1123 HTTP 日期（如 `Sun, 06 Nov 1994 08:49:37 GMT`）为纪元毫秒；无法解析返回 null。 */
internal fun parseHttpDateToEpochMillis(raw: String): Long? {
    val match = RFC_1123_PATTERN.matchEntire(raw) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = MONTH_INDEX[match.groupValues[2].lowercase()] ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null

    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

    val days = daysFromCivil(year, month, day)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    return (days * 86_400L + secondsOfDay) * 1000L
}

/** Howard Hinnant 的 days_from_civil：返回自 1970-01-01 起的天数（可为负）。 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
}

/**
 * 计算第 [attempt] 次（从 0 起）重试前应等待的毫秒数。
 *
 * 与上游 `retryDelayMs` 一致：服务端给出且不超过上限时采用服务端值，
 * 否则用带上限的指数退避并随机扣减抖动。
 */
internal fun retryDelayMs(
    attempt: Int,
    retryAfterMs: Long?,
    policy: RetryPolicy,
    random: () -> Double,
): Long {
    if (policy.respectRetryAfter && retryAfterMs != null && retryAfterMs <= policy.maxRetryAfterMs) {
        return retryAfterMs
    }
    val exponential = min(
        policy.backoffInitialMs * 2.0.pow(attempt),
        policy.backoffMaxMs.toDouble(),
    )
    return (exponential * (1 - random() * policy.backoffJitter)).roundToLong()
}
