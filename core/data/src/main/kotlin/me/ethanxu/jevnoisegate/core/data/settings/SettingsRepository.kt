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
    )
}

/** 空串与多余空行都会被过滤，避免用户多敲一个回车就产生一条空规则。 */
private fun parseRules(raw: String?): List<String> =
    raw.orEmpty().split("\n").map(String::trim).filter(String::isNotEmpty)
