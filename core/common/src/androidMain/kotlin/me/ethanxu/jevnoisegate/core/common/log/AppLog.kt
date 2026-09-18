package me.ethanxu.jevnoisegate.core.common.log

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 日志等级。数值越大越严重。 */
enum class LogLevel(val value: Int, val label: String) {
    VERBOSE(0, "详细"),
    DEBUG(1, "调试"),
    INFO(2, "信息"),
    WARN(3, "警告"),
    ERROR(4, "错误");

    companion object {
        fun fromValue(value: Int): LogLevel =
            entries.firstOrNull { it.value == value } ?: INFO
    }
}

/** 日志分类，对应几个会产出日志的子系统。 */
enum class LogCategory(val label: String) {
    NOTIFICATION("通知"),
    SMS("短信"),
    HTTP("请求"),
    DECISION("判断"),
    SYSTEM("系统"),
}

data class LogEntry(
    val seq: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val category: LogCategory,
    val tag: String,
    val message: String,
)

/**
 * 应用内运行日志。
 *
 * ## 三处写入
 *
 * 等级通过后同时写：
 * 1. **logcat** —— 开发时工具链完整（`adb logcat` 可按 tag 过滤）
 * 2. **内存环形缓冲** —— 界面直接读，[MAX_ENTRIES] 条
 * 3. **文件** —— 重启后仍可查
 *
 * ## 落盘：热路径零磁盘 IO
 *
 * 通知监听是热路径，在它上面同步写磁盘会造成可感知的卡顿 —— 这个项目已经踩过
 * "在热路径上等磁盘"的坑。因此热路径只往内存队列塞一条字符串，磁盘交给后台协程：
 *
 * ```
 * 热路径：pending.addLast(...)          ← 不碰磁盘
 *    ↓ 每 2 秒、或攒够 64 条
 * 后台：一次 appendText 写入整批          ← N 次小写入合并成 1 次
 * ```
 *
 * ## 切分：单文件 + 一代轮转
 *
 * 文件超过 [MAX_FILE_BYTES] 就整体挪到 `.1`（覆盖上一代），当前文件从空开始。
 * 只保留两代，因此**磁盘占用上限是 2 × [MAX_FILE_BYTES]**，不会无限膨胀。
 * 不做多代是因为排查几乎只需要看最近这一段。
 *
 * 用户可在「运行日志」页手动清空 —— 那会同时删掉内存与两个文件。
 */
object AppLog {

    /** 关闭日志的哨兵等级：比 ERROR 还高，任何日志都进不来。 */
    const val OFF: Int = 100

    private const val MAX_ENTRIES = 500
    private const val LOGCAT_TAG_PREFIX = "JevNoiseGate"

    /** 单个日志文件的上限。两代合计即磁盘占用上限。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private const val FLUSH_INTERVAL_MS = 2_000L
    private const val FLUSH_BATCH_THRESHOLD = 64

    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "runtime.log"
    private const val ROTATED_SUFFIX = ".1"

    /** 最小记录等级。@Volatile 让热路径上的读取几乎零成本。 */
    @Volatile
    private var minLevel: Int = LogLevel.INFO.value

    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val lock = Any()
    private var seq: Long = 0

    /** 待落盘的文本行。热路径只往这里塞字符串。 */
    private val pending = ArrayDeque<String>()

    private var logFile: File? = null
    private var rotatedFile: File? = null
    private var flusher: CoroutineScope? = null

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 最新在最前。 */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /**
     * 指定日志目录并载入历史。
     *
     * 必须在产生第一条日志之前调用（放在 `Application.onCreate`），
     * 否则启动早期的日志只会进内存、重启后丢失。
     * 重复调用无副作用。
     */
    fun init(context: Context) {
        if (flusher != null) return

        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        logFile = File(dir, LOG_FILE)
        rotatedFile = File(dir, LOG_FILE + ROTATED_SUFFIX)

        loadHistory()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        flusher = scope
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    fun configure(levelValue: Int) {
        minLevel = levelValue
    }

    fun isEnabled(level: LogLevel): Boolean = level.value >= minLevel

    fun v(category: LogCategory, tag: String, error: Throwable? = null, message: () -> String) =
        log(LogLevel.VERBOSE, category, tag, error, message)

    fun d(category: LogCategory, tag: String, error: Throwable? = null, message: () -> String) =
        log(LogLevel.DEBUG, category, tag, error, message)

    fun i(category: LogCategory, tag: String, error: Throwable? = null, message: () -> String) =
        log(LogLevel.INFO, category, tag, error, message)

    fun w(category: LogCategory, tag: String, error: Throwable? = null, message: () -> String) =
        log(LogLevel.WARN, category, tag, error, message)

    fun e(category: LogCategory, tag: String, error: Throwable? = null, message: () -> String) =
        log(LogLevel.ERROR, category, tag, error, message)

    /** 清空内存与磁盘上的全部日志。日志页的「清空」按钮调用。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            pending.clear()
            _entries.value = emptyList()
        }
        runCatching {
            logFile?.takeIf { it.exists() }?.delete()
            rotatedFile?.takeIf { it.exists() }?.delete()
        }
    }

    /** 磁盘上当前占用的字节数，供界面显示。 */
    fun diskUsageBytes(): Long =
        (logFile?.length() ?: 0L) + (rotatedFile?.length() ?: 0L)

    private fun log(
        level: LogLevel,
        category: LogCategory,
        tag: String,
        error: Throwable?,
        message: () -> String,
    ) {
        // 提前返回，message 这个 lambda 因此不会被执行。
        if (!isEnabled(level)) return

        val text = message()
        writeToLogcat(level, tag, text, error)

        // 异常在 App 内只留一行摘要（堆栈仍可从 logcat 取）；界面不适合展示完整堆栈。
        val stored = if (error == null) {
            text
        } else {
            "$text\n${error::class.java.simpleName}: ${error.message}"
        }
        val now = System.currentTimeMillis()

        synchronized(lock) {
            seq += 1
            buffer.addFirst(
                LogEntry(
                    seq = seq,
                    timestampMs = now,
                    level = level,
                    category = category,
                    tag = tag,
                    message = stored,
                ),
            )
            while (buffer.size > MAX_ENTRIES) buffer.removeLast()
            _entries.value = buffer.toList()

            // 只入队，不落盘 —— 磁盘 IO 留给后台协程。
            pending.addLast(encode(now, level, category, tag, stored))

            // 积压太多时立即刷一次，避免长时间不写导致进程被杀时丢得太多。
            if (pending.size >= FLUSH_BATCH_THRESHOLD) {
                val batch = pending.toList()
                pending.clear()
                writeBatch(batch)
            }
        }
    }

    private fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }
        writeBatch(batch)
    }

    private fun writeBatch(batch: List<String>) {
        val file = logFile ?: return
        runCatching {
            rotateIfNeeded(file)
            // 一次 appendText 写入整批：把 N 次小写入合并成 1 次系统调用。
            file.appendText(batch.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    /**
     * 超过上限就把当前文件整体挪成 `.1`（覆盖上一代），当前文件从空开始。
     *
     * 只保留两代 → 磁盘占用上限 2 × [MAX_FILE_BYTES]，不会无限膨胀。
     */
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val rotated = rotatedFile ?: return
        runCatching {
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        }
    }

    /** 启动时把文件尾部读回内存，使重启后仍能看到最近的日志。 */
    private fun loadHistory() {
        val lines = runCatching {
            logFile?.takeIf { it.exists() }?.readLines()?.takeLast(MAX_ENTRIES)
        }.getOrNull().orEmpty()
        if (lines.isEmpty()) return

        val restored = lines.mapNotNull(::decode)
        if (restored.isEmpty()) return

        synchronized(lock) {
            // 恢复的条目必须**重新编号**：磁盘上不存 seq，decode 出来的全是 0，
            // 而界面用 seq 做 LazyColumn 的 key —— 不重编号会让所有键重复，
            // 直接抛 "Key was already used" 崩溃。
            restored.forEach { entry ->
                seq += 1
                buffer.addLast(entry.copy(seq = seq))
            }
            _entries.value = buffer.toList()
        }
    }

    // -----------------------------------------------------------------------
    // 行编解码
    //
    // 自定义的极简格式而非 JSON：写入在攒批路径上，每行省下的序列化开销
    // 在几千行规模下是实打实的。字段固定五个，'|' 分隔，消息里的换行与 '|' 转义。
    // -----------------------------------------------------------------------

    private fun encode(
        timestampMs: Long,
        level: LogLevel,
        category: LogCategory,
        tag: String,
        message: String,
    ): String = listOf(
        timestampMs.toString(),
        level.value.toString(),
        category.name,
        tag,
        message,
    ).joinToString("|") { escape(it) }

    private fun decode(line: String): LogEntry? {
        val parts = line.split('|')
        if (parts.size < 5) return null
        return runCatching {
            LogEntry(
                seq = 0,
                timestampMs = parts[0].toLong(),
                level = LogLevel.fromValue(parts[1].toInt()),
                category = LogCategory.valueOf(parts[2]),
                tag = unescape(parts[3]),
                // 消息里可能含 '|'，所以第 5 段之后要重新拼回。
                message = unescape(parts.drop(4).joinToString("|")),
            )
        }.getOrNull()
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    private fun unescape(raw: String): String =
        raw.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")

    /**
     * 双写的 logcat 一侧。
     *
     * 已有的 TAG 本身就带 `JevNoiseGate/` 前缀（如 `JevNoiseGate/Notif`），
     * 若无条件再拼一次会得到 `JevNoiseGate/JevNoiseGate/Notif`。所以先判断再补。
     */
    private fun writeToLogcat(level: LogLevel, tag: String, text: String, error: Throwable?) {
        val prefixed = if (tag.startsWith(LOGCAT_TAG_PREFIX)) tag else "$LOGCAT_TAG_PREFIX/$tag"
        when (level) {
            LogLevel.VERBOSE -> android.util.Log.v(prefixed, text, error)
            LogLevel.DEBUG -> android.util.Log.d(prefixed, text, error)
            LogLevel.INFO -> android.util.Log.i(prefixed, text, error)
            LogLevel.WARN -> android.util.Log.w(prefixed, text, error)
            LogLevel.ERROR -> android.util.Log.e(prefixed, text, error)
        }
    }
}
