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
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.typesafe.sdk.ModelCard
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

    fun setColorMode(value: Int) = launchSetting { repository.setColorMode(value) }

    fun setKeyColor(value: Int) = launchSetting { repository.setKeyColor(value) }

    fun setApiKey(value: String) = launchSetting { repository.setApiKey(value) }

    fun setBaseUrl(value: String) = launchSetting { repository.setBaseUrl(value) }

    fun setModel(value: String) = launchSetting { repository.setModel(value) }

    fun setTimeoutMs(value: Long) = launchSetting { repository.setTimeoutMs(value) }

    fun setAppMuted(packageName: String, muted: Boolean) =
        launchSetting { repository.setAppMuted(packageName, muted) }

    fun setLogLevel(value: Int) = launchSetting { repository.setLogLevel(value) }

    fun setProxyType(value: String) = launchSetting { repository.setProxyType(value) }

    fun setProxyHost(value: String) = launchSetting { repository.setProxyHost(value) }

    fun setProxyPort(value: Int) = launchSetting { repository.setProxyPort(value) }

    fun setProxyUsername(value: String) = launchSetting { repository.setProxyUsername(value) }

    fun setProxyPassword(value: String) = launchSetting { repository.setProxyPassword(value) }

    fun setPreferAllowRules(value: List<String>) =
        launchSetting { repository.setPreferAllowRules(value) }

    fun setPreferBlockRules(value: List<String>) =
        launchSetting { repository.setPreferBlockRules(value) }

    fun setSmsSenderBlacklist(value: List<String>) =
        launchSetting { repository.setSmsSenderBlacklist(value) }

    fun setDeveloperMode(value: Boolean) = launchSetting { repository.setDeveloperMode(value) }

    /**
     * 连接测试。
     *
     * 用 `GET /v1/models` 一次请求同时达成两件事：验证连通性、拿到模型列表。
     * 不存在"只测连通"的轻量端点，而这个请求本来就是必需的 —— 合并比开两个入口更省事，
     * 也避免用户先点"测试"再点"刷新模型"。
     *
     * **用表单当前值临时构造客户端**，而不是复用单例客户端：单例是按已保存的配置建的，
     * 用它测等于在测旧配置 —— 用户改完代理点测试，结果反映的还是改动前，比不测更误导。
     */
    fun testConnection(
        apiKey: String,
        baseUrl: String,
        proxyType: String,
        proxyHost: String,
        proxyPort: Int,
        proxyUsername: String,
        proxyPassword: String,
    ) {
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
                    val client = TypeSafeClient(
                        TypeSafeConfig(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            timeoutMs = repository.preferences.value.timeoutMs,
                            proxy = proxySpecOrNull(
                                type = proxyType,
                                host = proxyHost,
                                port = proxyPort,
                                username = proxyUsername,
                                password = proxyPassword,
                            ),
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

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
