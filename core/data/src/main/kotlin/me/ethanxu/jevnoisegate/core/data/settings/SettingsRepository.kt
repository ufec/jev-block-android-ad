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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ethanxu.jevnoisegate.core.common.log.AppLog

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
    val preferences: StateFlow<AppPreferences>

    /**
     * 挂起至首次加载完成或失败，不阻塞主线程。加载有五秒总时限。
     *
     * 存在的理由：内存 StateFlow 的初值是默认值，而真实配置要等一次磁盘读取。
     * 任何**在启动阶段就需要读到真实配置**的下游（构造 HTTP 客户端、初始化日志等级）
     * 必须先调用它，否则会静默按默认值工作 —— 表现为"用户配了 API key 但请求打到了默认地址"，
     * 而且不报错，极难排查。
     */
    suspend fun awaitReady()
    val persistenceError: StateFlow<String?>

    suspend fun setUiMode(value: String)
    suspend fun setColorMode(value: Int)
    suspend fun setKeyColor(value: Int)

    suspend fun setApiKey(value: String)
    suspend fun setBaseUrl(value: String)
    suspend fun setModel(value: String)
    suspend fun setTimeoutMs(value: Long)

    /** 关闭/开启对某个 App 的通知监听。 */
    suspend fun setAppMuted(packageName: String, muted: Boolean)

    /** 设置运行日志的最小记录等级（`AppLog.OFF` 表示关闭）。 */
    suspend fun setLogLevel(value: Int)

    // --- 网络代理 ---

    /**
     * 保存一组代理配置，并把它记为「已通过测试」。
     *
     * 这是**唯一**的代理写入入口，而且只应该在测试通过之后调用 —— 界面上的改动在保存前
     * 只留在表单里。于是"当前生效的代理必然是测过的"由写入路径本身保证，
     * 而不是靠每个调用点自觉。
     *
     * 五个字段加指纹**一次写完**：分开写会出现"主机已更新、端口还是旧的"这种中间态，
     * 而一旦有人拿这个中间态去建客户端，就得到一个半配置的代理。
     *
     * 指纹由这里按**实际存下来的那组值**计算，因此必然与 [AppPreferences.isProxyVerified]
     * 的算法一致。让调用方算好再传进来，只要有一处归一化方式不同（比如谁 trim 了谁没 trim），
     * 验证就会永远对不上 —— 那个 bug 已经踩过一次。
     */
    suspend fun saveProxyConfig(
        type: String,
        host: String,
        port: Int,
        username: String,
        password: String,
    )

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
    private val secrets: SecretCodec,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SettingsRepository {

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
    private data class Write(val snapshot: AppPreferences, val done: CompletableDeferred<Unit>)
    private val writes = Channel<Write>(Channel.UNLIMITED)

    init {
        // 启动时从磁盘载入一次。之后内存是权威，磁盘只是持久化副本。
        scope.launch {
            try {
                withTimeoutOrNull(5_000L) {
                    val stored = dataStore.data.first()
                    inMemory.value = stored.toAppPreferences(secrets)
                    loaded = true
                    // The deadline includes migration so a stalled write cannot delay readiness.
                    val secretKeys = listOf(Keys.API_KEY, Keys.PROXY_USERNAME, Keys.PROXY_PASSWORD)
                    if (secretKeys.any { stored[it].orEmpty().let { value ->
                            value.isNotEmpty() && !value.startsWith(AndroidSecretCodec.PREFIX) } }) {
                        dataStore.edit { values ->
                            secretKeys.forEach { key ->
                                val value = values[key].orEmpty()
                                if (value.isNotEmpty() && !value.startsWith(AndroidSecretCodec.PREFIX)) values[key] = secrets.encrypt(value)
                            }
                        }
                    }
                } ?: throw java.io.IOException("Settings load timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = "设置加载或迁移失败，请检查当前配置：${failure::class.java.simpleName}"
            } finally {
                AppLog.configure(inMemory.value.logLevel)
                ready.complete(Unit)
            }
            for (write in writes) {
                try {
                    dataStore.edit { persistSnapshot(it, write.snapshot) }
                    error.value = null
                    write.done.complete(Unit)
                } catch (cancelled: CancellationException) {
                    write.done.cancel(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    error.value = "设置保存失败，重启后可能丢失改动：${failure::class.java.simpleName}"
                    write.done.completeExceptionally(failure)
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
    override suspend fun setApiKey(value: String) = mutate { it.copy(apiKey = value) }
    override suspend fun setBaseUrl(value: String) {
        require(isValidBaseUrl(value)) { "Base URL 必须是有效的 HTTP(S) 地址" }
        mutate { it.copy(baseUrl = value) }
    }
    override suspend fun setModel(value: String) = mutate { it.copy(model = value) }
    override suspend fun setTimeoutMs(value: Long) = mutate { it.copy(timeoutMs = value.coerceIn(1_000L, 60_000L)) }
    override suspend fun setAppMuted(packageName: String, muted: Boolean) = mutate {
        it.copy(mutedPackages = if (muted) it.mutedPackages + packageName else it.mutedPackages - packageName)
    }
    override suspend fun setLogLevel(value: Int) = mutate { it.copy(logLevel = value) }
    override suspend fun saveProxyConfig(type: String, host: String, port: Int, username: String, password: String) = mutate {
        it.copy(proxyType = type, proxyHost = host, proxyPort = port,
            proxyUsername = username, proxyPassword = password,
            proxyVerifiedFingerprint = proxyFingerprint(type, host, port, username, password))
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

    /** Memory update and enqueue are atomic; a single process-owned writer persists snapshots. */
    private suspend fun mutate(updateMemory: (AppPreferences) -> AppPreferences) {
        ready.await()
        ensureLoaded()
        val done = CompletableDeferred<Unit>()
        synchronized(memoryLock) {
            inMemory.update(updateMemory)
            AppLog.configure(inMemory.value.logLevel)
            check(writes.trySend(Write(inMemory.value, done)).isSuccess)
        }
        done.await()
    }

    /** Never overwrite unread settings with the temporary defaults shown after a load failure. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        reloadLock.withLock {
            if (loaded) return@withLock
            try {
                val recovered = withTimeoutOrNull(5_000L) { dataStore.data.first().toAppPreferences(secrets) }
                    ?: throw java.io.IOException("Settings reload timed out")
                synchronized(memoryLock) {
                    inMemory.value = recovered
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
    private fun persistSnapshot(values: androidx.datastore.preferences.core.MutablePreferences, prefs: AppPreferences) {
        values[Keys.UI_MODE] = prefs.uiMode
        values[Keys.COLOR_MODE] = prefs.colorMode
        values[Keys.KEY_COLOR] = prefs.keyColor
        values[Keys.API_KEY] = secrets.encrypt(prefs.apiKey)
        values[Keys.BASE_URL] = prefs.baseUrl
        values[Keys.MODEL] = prefs.model
        values[Keys.TIMEOUT_MS] = prefs.timeoutMs
        values[Keys.MUTED_PACKAGES] = prefs.mutedPackages
        values[Keys.LOG_LEVEL] = prefs.logLevel
        values[Keys.PROXY_TYPE] = prefs.proxyType
        values[Keys.PROXY_HOST] = prefs.proxyHost
        values[Keys.PROXY_PORT] = prefs.proxyPort
        values[Keys.PROXY_USERNAME] = secrets.encrypt(prefs.proxyUsername)
        values[Keys.PROXY_PASSWORD] = secrets.encrypt(prefs.proxyPassword)
        values[Keys.PROXY_VERIFIED_FINGERPRINT] = prefs.proxyVerifiedFingerprint
        values[Keys.PREFER_ALLOW] = prefs.preferAllowRules.joinToString("\n")
        values[Keys.PREFER_BLOCK] = prefs.preferBlockRules.joinToString("\n")
        values[Keys.SMS_BLACKLIST] = prefs.smsSenderBlacklist.joinToString("\n")
        values[Keys.DEVELOPER_MODE] = prefs.developerMode
        values[Keys.CATEGORY_THRESHOLDS] = encodeOverrides(prefs.categoryThresholds)
        values[Keys.CATEGORY_ACTIONS] = encodeOverrides(prefs.categoryActions)
    }

    private object Keys {
        val UI_MODE = stringPreferencesKey("ui_mode")
        val COLOR_MODE = intPreferencesKey("color_mode")
        val KEY_COLOR = intPreferencesKey("key_color")
        val API_KEY = stringPreferencesKey("api_key")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val TIMEOUT_MS = longPreferencesKey("timeout_ms")
        val MUTED_PACKAGES = stringSetPreferencesKey("muted_packages")
        val LOG_LEVEL = intPreferencesKey("log_level")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val PROXY_VERIFIED_FINGERPRINT = stringPreferencesKey("proxy_verified_fingerprint")
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
    }
}

private fun Preferences.toAppPreferences(secrets: SecretCodec): AppPreferences {
    val defaults = AppPreferences()
    return AppPreferences(
        uiMode = this[stringPreferencesKey("ui_mode")] ?: defaults.uiMode,
        colorMode = this[intPreferencesKey("color_mode")] ?: defaults.colorMode,
        keyColor = this[intPreferencesKey("key_color")] ?: defaults.keyColor,
        apiKey = secrets.decrypt(this[stringPreferencesKey("api_key")] ?: defaults.apiKey),
        baseUrl = this[stringPreferencesKey("base_url")]?.takeIf(::isValidBaseUrl) ?: defaults.baseUrl,
        model = this[stringPreferencesKey("model")] ?: defaults.model,
        timeoutMs = this[longPreferencesKey("timeout_ms")]?.coerceIn(1_000L, 60_000L) ?: defaults.timeoutMs,
        mutedPackages = this[stringSetPreferencesKey("muted_packages")] ?: defaults.mutedPackages,
        logLevel = this[intPreferencesKey("log_level")] ?: defaults.logLevel,
        proxyType = this[stringPreferencesKey("proxy_type")] ?: defaults.proxyType,
        proxyHost = this[stringPreferencesKey("proxy_host")] ?: defaults.proxyHost,
        proxyPort = this[intPreferencesKey("proxy_port")] ?: defaults.proxyPort,
        proxyUsername = secrets.decrypt(this[stringPreferencesKey("proxy_username")] ?: defaults.proxyUsername),
        proxyPassword = secrets.decrypt(this[stringPreferencesKey("proxy_password")] ?: defaults.proxyPassword),
        proxyVerifiedFingerprint = this[stringPreferencesKey("proxy_verified_fingerprint")]
            ?: defaults.proxyVerifiedFingerprint,
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
