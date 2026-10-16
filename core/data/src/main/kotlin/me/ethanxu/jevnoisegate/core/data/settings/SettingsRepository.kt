package me.ethanxu.jevnoisegate.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.PerformanceMetrics
import me.ethanxu.jevnoisegate.core.common.PerformanceCounter

/**
 * 设置读写。
 *
 * ## 为什么 [preferences] 是 `StateFlow` 而不是 `Flow`，且写入先改内存
 *
 * 这是一个被真实体验逼出来的设计。最初的实现直接暴露 DataStore 的 Flow，
 * 结果是**切换深色模式要等一次完整磁盘事务回环**（整个 preferences 文件读-改-写 + fsync），
 * 再经协程调度回到界面 —— 观感上就是"卡一下才变色"。
 *
 * 参考实现（KernelSU）用的是 SharedPreferences：get/set 都是**同步内存操作**，
 * 所以主题切换是瞬时的。
 *
 * 这里取两者的折中：**内存 `MutableStateFlow` 是界面看到的唯一真相源，写入立即更新内存、
 * 随后异步落盘**。既拿到了同步切换的即时感，又保留 DataStore 的持久化与类型安全。
 *
 * 仓库是 `@Singleton`，因此任何界面改设置，订阅同一个 StateFlow 的地方（包括 MainActivity 的主题）
 * 都会立刻看到 —— 这正是逐页各持一份 ViewModel 时最容易出错的地方。
 */
interface SettingsRepository {
    /** Defaults are provisional until the first load attempt finishes. */
    val isReady: Boolean get() = true
    val preferences: StateFlow<AppPreferences>

    /**
     * 挂起至首次加载完成或失败，不阻塞主线程。加载有五秒总时限。
     *
     * 存在的理由：内存 StateFlow 的初值是默认值，而真实配置要等一次磁盘读取。
     * 日志等级等启动配置必须先加载完成，避免使用默认值。
     */
    suspend fun awaitReady()
    val persistenceError: StateFlow<String?>

    suspend fun setUiMode(value: String)
    suspend fun setColorMode(value: Int)
    suspend fun setKeyColor(value: Int)


    /** 关闭/开启对某个 App 的通知监听。 */
    suspend fun setAppMuted(packageName: String, muted: Boolean)

    /** 设置运行日志的最小记录等级（`AppLog.OFF` 表示关闭）。 */
    suspend fun setLogLevel(value: Int)

    // --- 用户偏好规则 ---

    suspend fun setPreferAllowRules(value: List<String>)
    suspend fun setPreferBlockRules(value: List<String>)

    suspend fun setSmsSenderBlacklist(value: List<String>)

    suspend fun setDeveloperMode(value: Boolean)

    // --- 判定门槛与动作 ---

    /**
     * 覆盖某个类别的判定门槛。
     *
     * [value] 会被夹到 `0f..1f` —— 界面上是滑块不会越界，但存储层不该假设调用方一定传对。
     */
    suspend fun setCategoryThreshold(name: String, value: Float)

    /**
     * 覆盖某个类别的动作。
     *
     * 收 String 而非 `Action`：见 [AppPreferences.categoryActions]，
     * `:core:data` 不依赖上层类型，解析由读取侧负责。
     */
    suspend fun setCategoryAction(name: String, value: String)

    /** 清空全部覆盖值，回到出厂默认。 */
    suspend fun resetCategoryOverrides()
}

internal class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SettingsRepository {
    override val isReady: Boolean get() = ready.isCompleted

    /** 界面看到的唯一真相源。进程内单例，因此全局一致。 */
    private val inMemory = MutableStateFlow(AppPreferences())

    override val preferences: StateFlow<AppPreferences> = inMemory.asStateFlow()

    /** 首次加载完成信号，供 [awaitReady] 使用。 */
    private val ready = CompletableDeferred<Unit>()
    private val memoryLock = Any()
    private val reloadLock = Mutex()
    @Volatile private var loaded = false
    private val error = MutableStateFlow<String?>(null)
    override val persistenceError: StateFlow<String?> = error.asStateFlow()
    private data class Write(val snapshot: AppPreferences, val completions: List<CompletableDeferred<Unit>>)
    private var pendingSnapshot: AppPreferences? = null
    private val pendingCompletions = mutableListOf<CompletableDeferred<Unit>>()
    private var lastPersisted: AppPreferences? = null
    private var writing = false
    private val slots = Semaphore(64)
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        // 启动时从磁盘载入一次。之后内存是权威，磁盘只是持久化副本。
        scope.launch {
            try {
                // 读与清各有自己的时限，合起来最坏 10 秒 —— 也就是就绪信号的最长等待。
                // 卡住时必须放行：应用连"设置读不出来"都无法呈现比慢一点更糟。
                val stored = withTimeoutOrNull(5_000L) { dataStore.data.first() }
                    ?: throw java.io.IOException("Settings load timed out")
                withTimeoutOrNull(5_000L) { purgeLegacyKeys(stored) }
                    ?: throw java.io.IOException("Settings migration timed out")
                inMemory.value = stored.toAppPreferences()
                lastPersisted = inMemory.value
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = "设置加载或迁移失败，请检查当前配置：${failure::class.java.simpleName}"
            } finally {
                // 日志等级必须在任何业务代码产出日志之前生效，否则启动早期的日志会漏记。
                // 成功路径上这里读到的是真实配置；加载失败时只能用默认值，但仍然要放行 readiness，
                // 否则整个应用连"设置读不出来"这件事都无法呈现。
                AppLog.configure(inMemory.value.logLevel)
                ready.complete(Unit)
            }
            for (ignored in writes) {
                val write = synchronized(memoryLock) {
                    pendingSnapshot?.let { snapshot ->
                        Write(snapshot, pendingCompletions.toList()).also {
                            writing = true
                            pendingSnapshot = null
                            pendingCompletions.clear()
                        }
                    }
                } ?: continue
                try {
                    dataStore.edit { persistSnapshot(it, write.snapshot) }
                    PerformanceMetrics.increment(PerformanceCounter.SETTINGS_WRITE)
                    synchronized(memoryLock) { lastPersisted = write.snapshot }
                    error.value = null
                    write.completions.forEach { it.complete(Unit) }
                } catch (cancelled: CancellationException) {
                    write.completions.forEach { it.cancel(cancelled) }
                    throw cancelled
                } catch (failure: Exception) {
                    error.value = "设置保存失败，重启后可能丢失改动：${failure::class.java.simpleName}"
                    write.completions.forEach { it.completeExceptionally(failure) }
                } finally {
                    synchronized(memoryLock) { writing = false }
                    repeat(write.completions.size) { slots.release() }
                }
            }
        }
    }

    override suspend fun awaitReady() {
        ready.await()
    }

    override suspend fun setUiMode(value: String) = mutate { it.copy(uiMode = value) }
    override suspend fun setColorMode(value: Int) = mutate { it.copy(colorMode = value) }
    override suspend fun setKeyColor(value: Int) = mutate { it.copy(keyColor = value) }
    override suspend fun setAppMuted(packageName: String, muted: Boolean) = mutate {
        it.copy(mutedPackages = if (muted) it.mutedPackages + packageName else it.mutedPackages - packageName)
    }
    override suspend fun setLogLevel(value: Int) {
        // 先让等级立即生效，再落盘 —— 否则用户在设置里调完等级，
        // 到落盘完成之前的日志仍然按旧等级过滤。
        AppLog.configure(value)
        mutate { it.copy(logLevel = value) }
    }

    override suspend fun setPreferAllowRules(value: List<String>) = mutate { it.copy(preferAllowRules = value.toList()) }

    override suspend fun setPreferBlockRules(value: List<String>) = mutate { it.copy(preferBlockRules = value.toList()) }

    override suspend fun setSmsSenderBlacklist(value: List<String>) = mutate { it.copy(smsSenderBlacklist = value.toList()) }

    override suspend fun setDeveloperMode(value: Boolean) = mutate { it.copy(developerMode = value) }
    override suspend fun setCategoryThreshold(name: String, value: Float) {
        require(value.isFinite()) { "判定门槛必须是有限数" }
        mutate { it.copy(categoryThresholds = it.categoryThresholds + (name to value.coerceIn(0f, 1f))) }
    }
    override suspend fun setCategoryAction(name: String, value: String) = mutate {
        it.copy(categoryActions = it.categoryActions + (name to value))
    }
    override suspend fun resetCategoryOverrides() = mutate {
        it.copy(categoryThresholds = emptyMap(), categoryActions = emptyMap())
    }

    /** Accepted snapshots coalesce; at most 64 accepted callers await persistence, even on slow storage. */
    private suspend fun mutate(updateMemory: (AppPreferences) -> AppPreferences) {
        ready.await()
        ensureLoaded()
        slots.acquire()
        val done = CompletableDeferred<Unit>()
        var accepted = false
        try {
            synchronized(memoryLock) {
                val next = updateMemory(inMemory.value)
                if (!writing && pendingSnapshot == null && next == lastPersisted && next == inMemory.value && error.value == null) return
                inMemory.value = next
                AppLog.configure(next.logLevel)
                pendingSnapshot = next
                pendingCompletions += done
                accepted = true
                check(writes.trySend(Unit).isSuccess)
            }
        } finally {
            if (!accepted) slots.release()
        }
        done.await()
    }

    /** Never overwrite unread settings with the temporary defaults shown after a load failure. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        reloadLock.withLock {
            if (loaded) return@withLock
            try {
                val recovered = withTimeoutOrNull(5_000L) { dataStore.data.first().toAppPreferences() }
                    ?: throw java.io.IOException("Settings reload timed out")
                synchronized(memoryLock) {
                    inMemory.value = recovered
                    lastPersisted = recovered
                    AppLog.configure(recovered.logLevel)
                    loaded = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = "设置尚未读出，未保存改动以保护原配置：${failure::class.java.simpleName}"
                throw failure
            }
        }
    }

    /**
     * 清掉旧版 API 构建留下的密钥与代理配置。
     *
     * 这些键在本版本完全不读：留着它们等于在用户的偏好文件里存一份
     * 没人再用、也再没机会清理的凭据。没有这些键时直接返回 ——
     * 新装与已迁移过的常见路径因此不必付出一次写事务。
     */
    private suspend fun purgeLegacyKeys(stored: Preferences) {
        if (!Keys.LEGACY_KEYS.any { it in stored }) return
        dataStore.edit { prefs -> Keys.LEGACY_KEYS.forEach { prefs.remove(it) } }
    }

    /**
     * 覆盖写有个前提：调用方（[mutate]）保证它先成功读过一次，因此这里的
     * `prefs` 不是"默认值 + 这一个字段"的组合，而是完整的真实配置。
     */
    private fun persistSnapshot(values: androidx.datastore.preferences.core.MutablePreferences, prefs: AppPreferences) {
        values[Keys.UI_MODE] = prefs.uiMode
        values[Keys.COLOR_MODE] = prefs.colorMode
        values[Keys.KEY_COLOR] = prefs.keyColor
        values[Keys.MUTED_PACKAGES] = prefs.mutedPackages
        values[Keys.LOG_LEVEL] = prefs.logLevel
        values[Keys.PREFER_ALLOW] = prefs.preferAllowRules.joinToString("\n")
        values[Keys.PREFER_BLOCK] = prefs.preferBlockRules.joinToString("\n")
        values[Keys.SMS_BLACKLIST] = prefs.smsSenderBlacklist.joinToString("\n")
        values[Keys.DEVELOPER_MODE] = prefs.developerMode
        // 清空覆盖值时写空串而不是 remove：整份覆盖写无法"只删一个键"，
        // 而空串解析出来就是空 map，语义与从未设置一致。
        values[Keys.CATEGORY_THRESHOLDS] = encodeOverrides(prefs.categoryThresholds)
        values[Keys.CATEGORY_ACTIONS] = encodeOverrides(prefs.categoryActions)
    }

    private object Keys {
        val UI_MODE = stringPreferencesKey("ui_mode")
        val COLOR_MODE = intPreferencesKey("color_mode")
        val KEY_COLOR = intPreferencesKey("key_color")
        val MUTED_PACKAGES = stringSetPreferencesKey("muted_packages")
        val LOG_LEVEL = intPreferencesKey("log_level")
        // 规则是自由文本，可能含任意字符；用换行分隔成单条，
        // 而不是 stringSet —— 后者会丢失顺序，而顺序就是用户心里的优先级。
        val PREFER_ALLOW = stringPreferencesKey("prefer_allow_rules")
        val PREFER_BLOCK = stringPreferencesKey("prefer_block_rules")
        val SMS_BLACKLIST = stringPreferencesKey("sms_sender_blacklist")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 覆盖值是键值对，stringSet 存不下（它只有键没有值），
        // 因此用"类别名=值"的换行分隔文本 —— 与上面的规则字段同一套惯例。
        val CATEGORY_THRESHOLDS = stringPreferencesKey("category_thresholds")
        val CATEGORY_ACTIONS = stringPreferencesKey("category_actions")

        /** 旧版 API 构建留下的键，本版本一律清除且不再读取。 */
        val LEGACY_KEYS = listOf<Preferences.Key<*>>(
            stringPreferencesKey("api_key"), stringPreferencesKey("base_url"),
            stringPreferencesKey("model"), longPreferencesKey("timeout_ms"),
            stringPreferencesKey("proxy_type"), stringPreferencesKey("proxy_host"),
            intPreferencesKey("proxy_port"), stringPreferencesKey("proxy_username"),
            stringPreferencesKey("proxy_password"), stringPreferencesKey("proxy_verified_fingerprint"),
        )
    }
}

private fun Preferences.toAppPreferences(): AppPreferences {
    val defaults = AppPreferences()
    return AppPreferences(
        uiMode = this[stringPreferencesKey("ui_mode")] ?: defaults.uiMode,
        colorMode = this[intPreferencesKey("color_mode")] ?: defaults.colorMode,
        keyColor = this[intPreferencesKey("key_color")] ?: defaults.keyColor,
        mutedPackages = this[stringSetPreferencesKey("muted_packages")] ?: defaults.mutedPackages,
        logLevel = this[intPreferencesKey("log_level")] ?: defaults.logLevel,
        preferAllowRules = parseRules(this[stringPreferencesKey("prefer_allow_rules")]),
        preferBlockRules = parseRules(this[stringPreferencesKey("prefer_block_rules")]),
        smsSenderBlacklist = parseRules(this[stringPreferencesKey("sms_sender_blacklist")]),
        developerMode = this[booleanPreferencesKey("developer_mode")] ?: defaults.developerMode,
        // 解析不出浮点数的条目直接丢弃：与其带着一个 NaN 去比较门槛
        // （NaN 参与的比较恒为 false，会让该类别永远走不到"执行动作"那一步），
        // 不如退回出厂默认。
        categoryThresholds = parseOverrides(this[stringPreferencesKey("category_thresholds")])
            .mapNotNull { (name, raw) -> raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let { name to it.coerceIn(0f, 1f) } }
            .toMap(),
        categoryActions = parseOverrides(this[stringPreferencesKey("category_actions")]),
    )
}

/** 空串与多余空行都会被过滤，避免用户多敲一个回车就产生一条空规则。 */
private fun parseRules(raw: String?): List<String> =
    raw.orEmpty().split("\n").map(String::trim).filter(String::isNotEmpty)

/**
 * 把"类别名 → 值"的映射编成一行一条的文本。
 *
 * 值用 `toString()`，因此这个函数同时服务门槛（Float）与动作（String）。
 * 声明成 `Map<String, *>` 而不是泛型：调用方两种类型各传各的，
 * 没必要为此引入一个类型参数。
 */
internal fun encodeOverrides(values: Map<String, *>): String =
    values.entries
        .mapNotNull { (name, value) ->
            // 空值跳过：写出去也会在 parseOverrides 里被丢掉，
            // 与其留一行解析不了的垃圾，不如让它根本不出现。两端规则保持对称。
            val text = value?.toString().orEmpty()
            if (name.isBlank() || text.isBlank()) null else "$name=$text"
        }
        .joinToString("\n")

/**
 * [encodeOverrides] 的逆运算。
 *
 * 用**最后**一个等号切分而不是第一个：值是浮点数或枚举名，都不含等号，
 * 而类别名万一含等号（用户自定义类别时完全可能），按最后一个切才能正确往返 ——
 * 按第一个切会把 `a=b=0.9` 切成名字 `a`、值 `b=0.9`，然后因为解析不成浮点数被丢掉。
 *
 * 名字或值为空的行直接跳过，不产生垃圾条目。
 */
internal fun parseOverrides(raw: String?): Map<String, String> =
    raw.orEmpty()
        .split("\n")
        .mapNotNull { line ->
            val separator = line.lastIndexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
        .toMap()
