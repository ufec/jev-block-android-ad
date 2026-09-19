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
     * 阻塞至首次从磁盘加载完成。
     *
     * 存在的理由：内存 StateFlow 的初值是默认值，而真实配置要等一次磁盘读取。
     * 任何**在启动阶段就需要读到真实配置**的下游（构造 HTTP 客户端、初始化日志等级）
     * 必须先调用它，否则会静默按默认值工作 —— 表现为"用户配了 API key 但请求打到了默认地址"，
     * 而且不报错，极难排查。
     */
    fun awaitReady()

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
) : SettingsRepository {

    /** 界面看到的唯一真相源。进程内单例，因此全局一致。 */
    private val inMemory = MutableStateFlow(AppPreferences())

    override val preferences: StateFlow<AppPreferences> = inMemory.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 首次加载完成信号，供 [awaitReady] 使用。 */
    private val ready = java.util.concurrent.CountDownLatch(1)

    init {
        // 启动时从磁盘载入一次。之后内存是权威，磁盘只是持久化副本。
        scope.launch {
            val loaded = dataStore.data.first().toAppPreferences()
            inMemory.value = loaded
            // 日志等级必须在任何业务代码产出日志之前生效，否则启动早期的日志会漏记。
            AppLog.configure(loaded.logLevel)
            ready.countDown()
        }
    }

    override fun awaitReady() {
        ready.await()
    }

    override suspend fun setUiMode(value: String) = mutate({ it.copy(uiMode = value) }) {
        it[Keys.UI_MODE] = value
    }

    override suspend fun setColorMode(value: Int) = mutate({ it.copy(colorMode = value) }) {
        it[Keys.COLOR_MODE] = value
    }

    override suspend fun setKeyColor(value: Int) = mutate({ it.copy(keyColor = value) }) {
        it[Keys.KEY_COLOR] = value
    }

    override suspend fun setApiKey(value: String) = mutate({ it.copy(apiKey = value) }) {
        it[Keys.API_KEY] = value
    }

    override suspend fun setBaseUrl(value: String) = mutate({ it.copy(baseUrl = value) }) {
        it[Keys.BASE_URL] = value
    }

    override suspend fun setModel(value: String) = mutate({ it.copy(model = value) }) {
        it[Keys.MODEL] = value
    }

    override suspend fun setTimeoutMs(value: Long) = mutate({ it.copy(timeoutMs = value) }) {
        it[Keys.TIMEOUT_MS] = value
    }

    override suspend fun setAppMuted(packageName: String, muted: Boolean) {
        val current = inMemory.value.mutedPackages
        mutate({ it.copy(mutedPackages = if (muted) current + packageName else current - packageName) }) {
            it[Keys.MUTED_PACKAGES] = if (muted) current + packageName else current - packageName
        }
    }

    override suspend fun setLogLevel(value: Int) {
        // 先让等级立即生效，再落盘 —— 否则用户在设置里调完等级，
        // 到落盘完成之前的日志仍然按旧等级过滤。
        AppLog.configure(value)
        mutate({ it.copy(logLevel = value) }) { it[Keys.LOG_LEVEL] = value }
    }

    override suspend fun saveProxyConfig(
        type: String,
        host: String,
        port: Int,
        username: String,
        password: String,
    ) {
        val fingerprint = proxyFingerprint(type, host, port, username, password)
        mutate(
            updateMemory = {
                it.copy(
                    proxyType = type,
                    proxyHost = host,
                    proxyPort = port,
                    proxyUsername = username,
                    proxyPassword = password,
                    proxyVerifiedFingerprint = fingerprint,
                )
            },
            updateDisk = {
                it[Keys.PROXY_TYPE] = type
                it[Keys.PROXY_HOST] = host
                it[Keys.PROXY_PORT] = port
                it[Keys.PROXY_USERNAME] = username
                it[Keys.PROXY_PASSWORD] = password
                it[Keys.PROXY_VERIFIED_FINGERPRINT] = fingerprint
            },
        )
    }

    override suspend fun setPreferAllowRules(value: List<String>) =
        mutate({ it.copy(preferAllowRules = value) }) { it[Keys.PREFER_ALLOW] = value.joinToString("\n") }

    override suspend fun setPreferBlockRules(value: List<String>) =
        mutate({ it.copy(preferBlockRules = value) }) { it[Keys.PREFER_BLOCK] = value.joinToString("\n") }

    override suspend fun setSmsSenderBlacklist(value: List<String>) =
        mutate({ it.copy(smsSenderBlacklist = value) }) { it[Keys.SMS_BLACKLIST] = value.joinToString("\n") }

    override suspend fun setDeveloperMode(value: Boolean) =
        mutate({ it.copy(developerMode = value) }) { it[Keys.DEVELOPER_MODE] = value }

    override suspend fun setCategoryThreshold(name: String, value: Float) {
        val next = inMemory.value.categoryThresholds + (name to value.coerceIn(0f, 1f))
        mutate({ it.copy(categoryThresholds = next) }) {
            it[Keys.CATEGORY_THRESHOLDS] = encodeOverrides(next)
        }
    }

    override suspend fun setCategoryAction(name: String, value: String) {
        val next = inMemory.value.categoryActions + (name to value)
        mutate({ it.copy(categoryActions = next) }) {
            it[Keys.CATEGORY_ACTIONS] = encodeOverrides(next)
        }
    }

    override suspend fun resetCategoryOverrides() {
        mutate({ it.copy(categoryThresholds = emptyMap(), categoryActions = emptyMap()) }) {
            // remove 而不是写空串：两者解析出来都是空 map，但 remove 让 DataStore 里
            // 不再留下这两个键，文件状态与"从未改过"完全一致。
            it.remove(Keys.CATEGORY_THRESHOLDS)
            it.remove(Keys.CATEGORY_ACTIONS)
        }
    }

    /**
     * 先更新内存（让界面立即反映），再异步落盘。
     *
     * 顺序不可颠倒：先落盘就意味着界面要等磁盘。
     * 落盘放在 `scope`（进程级）而不是调用方的 `viewModelScope`，
     * 避免用户快速退出界面时写入被取消。
     */
    private inline fun mutate(
        crossinline updateMemory: (AppPreferences) -> AppPreferences,
        crossinline updateDisk: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        inMemory.update(updateMemory)
        scope.launch { dataStore.edit { updateDisk(it) } }
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

private fun Preferences.toAppPreferences(): AppPreferences {
    val defaults = AppPreferences()
    return AppPreferences(
        uiMode = this[stringPreferencesKey("ui_mode")] ?: defaults.uiMode,
        colorMode = this[intPreferencesKey("color_mode")] ?: defaults.colorMode,
        keyColor = this[intPreferencesKey("key_color")] ?: defaults.keyColor,
        apiKey = this[stringPreferencesKey("api_key")] ?: defaults.apiKey,
        baseUrl = this[stringPreferencesKey("base_url")] ?: defaults.baseUrl,
        model = this[stringPreferencesKey("model")] ?: defaults.model,
        timeoutMs = this[longPreferencesKey("timeout_ms")] ?: defaults.timeoutMs,
        mutedPackages = this[stringSetPreferencesKey("muted_packages")] ?: defaults.mutedPackages,
        logLevel = this[intPreferencesKey("log_level")] ?: defaults.logLevel,
        proxyType = this[stringPreferencesKey("proxy_type")] ?: defaults.proxyType,
        proxyHost = this[stringPreferencesKey("proxy_host")] ?: defaults.proxyHost,
        proxyPort = this[intPreferencesKey("proxy_port")] ?: defaults.proxyPort,
        proxyUsername = this[stringPreferencesKey("proxy_username")] ?: defaults.proxyUsername,
        proxyPassword = this[stringPreferencesKey("proxy_password")] ?: defaults.proxyPassword,
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
            .mapNotNull { (name, raw) -> raw.toFloatOrNull()?.let { name to it.coerceIn(0f, 1f) } }
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
