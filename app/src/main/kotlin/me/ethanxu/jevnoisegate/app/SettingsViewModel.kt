package me.ethanxu.jevnoisegate.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.typesafe.sdk.ModelCard
import me.ethanxu.typesafe.sdk.ProxySpec
import me.ethanxu.typesafe.sdk.TypeSafeClient
import me.ethanxu.typesafe.sdk.TypeSafeConfig

/**
 * 连接测试的结果。
 *
 * `success` 是可空的：null 表示尚未测过，与"测过且失败"是两种不同状态 ——
 * 界面需要区分"还没测"和"测了不通"。
 */
data class ConnectionTestState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null,
    val models: List<ModelCard> = emptyList(),
)

/**
 * 代理保存的结果。
 *
 * 与 [ConnectionTestState] 分开：两页问的问题不同 —— JevAPI 页问"我的应用现在能不能用"，
 * 代理页问"这次改动能不能存下去"。
 */
data class ProxySaveState(
    val running: Boolean = false,
    val saved: Boolean? = null,
    val message: String? = null,
)

/**
 * 设置的状态持有者。
 *
 * 直接转发仓库的 `StateFlow`，**不做 `stateIn`**：
 * 仓库是进程内单例且写入时同步更新内存，因此这里拿到的永远是即时值，
 * 也不需要 `WhileSubscribed` 那种延迟订阅——主题切换必须立刻可见。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = repository.preferences

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _proxySave = MutableStateFlow(ProxySaveState())
    val proxySave: StateFlow<ProxySaveState> = _proxySave.asStateFlow()

    fun setColorMode(value: Int) = launchSetting { repository.setColorMode(value) }

    fun setKeyColor(value: Int) = launchSetting { repository.setKeyColor(value) }

    fun setApiKey(value: String) = launchSetting { repository.setApiKey(value) }

    fun setBaseUrl(value: String) = launchSetting { repository.setBaseUrl(value) }

    fun setModel(value: String) = launchSetting { repository.setModel(value) }

    fun setTimeoutMs(value: Long) = launchSetting { repository.setTimeoutMs(value) }

    fun setAppMuted(packageName: String, muted: Boolean) =
        launchSetting { repository.setAppMuted(packageName, muted) }

    fun setLogLevel(value: Int) = launchSetting { repository.setLogLevel(value) }

    fun setPreferAllowRules(value: List<String>) =
        launchSetting { repository.setPreferAllowRules(value) }

    fun setPreferBlockRules(value: List<String>) =
        launchSetting { repository.setPreferBlockRules(value) }

    fun setSmsSenderBlacklist(value: List<String>) =
        launchSetting { repository.setSmsSenderBlacklist(value) }

    fun setDeveloperMode(value: Boolean) = launchSetting { repository.setDeveloperMode(value) }

    fun setCategoryThreshold(name: String, value: Float) =
        launchSetting { repository.setCategoryThreshold(name, value) }

    fun setCategoryAction(name: String, value: String) =
        launchSetting { repository.setCategoryAction(name, value) }

    fun resetCategoryOverrides() = launchSetting { repository.resetCategoryOverrides() }

    /**
     * 连接测试（JevAPI 页）。
     *
     * 用 `GET /v1/models` 一次请求同时达成两件事：验证连通性、拿到模型列表。
     * 不存在"只测连通"的轻量端点，而这个请求本来就是必需的 —— 合并比开两个入口更省事，
     * 也避免用户先点"测试"再点"刷新模型"。
     *
     * **用表单的 apiKey / baseUrl 临时构造客户端**，而不是复用单例客户端：
     * 单例是按启动时已保存的配置建的，用它测等于在测旧配置 ——
     * 用户改完点测试，结果反映的还是改动前，比不测更误导。
     *
     * 代理则**取生效中的那一份**（[verifiedProxySpecOrNull]），不取表单值：
     * 本页要回答的是"应用现在到底能不能用"，所以必须和真实请求走同一条路。
     * 表单里的代理改动要先去「网络代理」页验证并重启才会生效。
     */
    fun testApiConnection(apiKey: String, baseUrl: String) {
        if (apiKey.isBlank()) {
            _connectionTest.value = ConnectionTestState(
                running = false,
                success = false,
                message = "请先填写 API Key",
            )
            return
        }
        _connectionTest.value = ConnectionTestState(running = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prefs = repository.preferences.value
                    val client = TypeSafeClient(
                        TypeSafeConfig(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            timeoutMs = prefs.timeoutMs,
                            proxy = verifiedProxySpecOrNull(prefs),
                        ),
                    )
                    try {
                        client.listModels()
                    } finally {
                        // 临时客户端必须关掉，否则每次测试都泄漏一个连接池。
                        client.close()
                    }
                }
            }
            _connectionTest.value = result.fold(
                onSuccess = { models ->
                    ConnectionTestState(
                        running = false,
                        success = true,
                        message = "连接成功，账号可用模型 ${models.size} 个",
                        models = models,
                    )
                },
                onFailure = { error ->
                    ConnectionTestState(
                        running = false,
                        success = false,
                        message = "连接失败：${error.message ?: error::class.java.simpleName}",
                    )
                },
            )
        }
    }

    /**
     * 保存代理配置（「网络代理」页）：**先测，通过了才写入全局配置**。
     *
     * 表单在本页只是草稿，改动不会自动落库 —— 于是"生效"与"测过"成了同一件事，
     * 不再需要用户先测试、再理解"验证"是怎么回事、再记得去保存。
     *
     * **失败时不写任何东西。**这是有意的：用户手上那套能用的配置，不该因为一次改错的
     * 尝试而被破坏。所以失败后应用仍然按原来的配置走，界面上也照旧显示原来那套。
     *
     * 选「不使用」时不探测 —— 没有链路可验，直接保存（这也是一次真正的"关掉代理"）。
     */
    fun saveProxyConfig(
        baseUrl: String,
        proxyType: String,
        proxyHost: String,
        proxyPort: Int,
        proxyUsername: String,
        proxyPassword: String,
    ) {
        // 归一化在这里做一次，写库与探测用的是同一组值。偏好文件里存的就是归一化后的版本，
        // 两边不一致会让"界面上看到的"和"已保存的"永远不相等。
        val host = proxyHost.trim()
        val username = proxyUsername.trim()

        if (proxyType == AppPreferences.PROXY_NONE) {
            _proxySave.value = ProxySaveState(running = true)
            viewModelScope.launch {
                repository.saveProxyConfig(proxyType, host, proxyPort, username, proxyPassword)
                _proxySave.value = ProxySaveState(
                    running = false,
                    saved = true,
                    message = "已保存：不使用代理，应用将直连",
                )
                AppLog.i(LogCategory.HTTP, PROBE_TAG) { "代理配置已保存：不使用代理" }
            }
            return
        }

        val spec = proxySpecOrNull(proxyType, host, proxyPort, username, proxyPassword)
        if (spec == null) {
            _proxySave.value = ProxySaveState(
                running = false,
                saved = false,
                message = proxyAddressProblem(host, proxyPort.toString())
                    ?: "代理配置不可用，请检查主机与端口",
            )
            return
        }

        _proxySave.value = ProxySaveState(running = true)
        viewModelScope.launch {
            val timeoutMs = repository.preferences.value.timeoutMs
            // 只记端点，**不记 ProxySpec 对象**。它是 data class，toString() 里带着明文密码，
            // 而这个日志会落到 runtime.log 并显示在运行日志页 —— 我第一版就是这么写的，
            // 密码确实进过文件。要记的东西只有"试了哪儿"和"带没带凭据"。
            AppLog.i(LogCategory.HTTP, PROBE_TAG) {
                "代理保存前探测 target=${spec.host}:${spec.port} " +
                    "含认证=${spec.hasCredentials} timeout=${timeoutMs}ms"
            }

            val result = probeProxy(baseUrl = baseUrl, spec = spec, timeoutMs = timeoutMs)
            val saved = result is ProxyProbeResult.Reachable
            if (saved) {
                repository.saveProxyConfig(proxyType, host, proxyPort, username, proxyPassword)
            }

            _proxySave.value = ProxySaveState(
                running = false,
                saved = saved,
                message = if (saved) {
                    "已保存。${describeProbe(result, spec)}"
                } else {
                    "未保存，仍按原来的配置走。${describeProbe(result, spec)}"
                },
            )
            // 结果进运行日志：代理没生效时用户第一个该看的就是这里 ——
            // 它同时说明了"试了什么"和"结论是什么"，比只看界面上的那句话有用。
            AppLog.i(LogCategory.HTTP, PROBE_TAG) { "代理保存结束 saved=$saved result=$result" }
        }
    }

    /** [spec] 非空：[saveProxyConfig] 只在真的探测过之后才调它。 */
    private fun describeProbe(result: ProxyProbeResult, spec: ProxySpec): String = when (result) {
        is ProxyProbeResult.Reachable ->
            if (result.status in 200..299) {
                "上游返回 ${result.status}（用时 ${result.elapsedMs} 毫秒）"
            } else {
                "上游返回 ${result.status}（用时 ${result.elapsedMs} 毫秒）。" +
                    "这个状态码来自上游而不是代理 —— 保存前的探测不发送凭据，" +
                    "所以它不代表 API Key 有问题。"
            }

        is ProxyProbeResult.ProxyAuthRequired ->
            if (result.credentialsSupplied) {
                "代理拒绝了这组用户名/密码（407）"
            } else {
                "代理要求认证（407），请填写用户名和密码"
            }

        // 失败原因要带上**被测的那个端点** —— OkHttp 的原文常常只提上游 URL
        // （如 "unexpected end of stream on https://…"），光看它不知道该去查哪里。
        is ProxyProbeResult.Failed -> "代理 ${spec.host}:${spec.port} 不可用：${result.reason}"
    }

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val PROBE_TAG = "ProxyProbe"
    }
}
