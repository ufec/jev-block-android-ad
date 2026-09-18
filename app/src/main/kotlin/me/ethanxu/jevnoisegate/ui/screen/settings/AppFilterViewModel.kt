package me.ethanxu.jevnoisegate.ui.screen.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository

/** 列表里的一行应用。只带标识与标签，图标由界面按需异步加载。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

@HiltViewModel
class AppFilterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** null 表示仍在加载 —— 与"加载完成但列表为空"是两种不同状态，界面要能区分。 */
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps.asStateFlow()

    val mutedPackages: StateFlow<Set<String>> = settings.preferences
        .map { it.mutedPackages }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptySet(),
        )

    init {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { loadInstalledApps() }
        }
    }

    fun setMuted(packageName: String, muted: Boolean) {
        viewModelScope.launch { settings.setAppMuted(packageName, muted) }
    }

    /**
     * 只列出**有启动入口**的应用。
     *
     * 设备上装了 577 个包，其中大部分是系统库与后台服务 —— 它们既不会自己发广告，
     * 用户也认不出名字，全列出来只会淹没真正需要关注的几十个。
     * 没有启动入口的应用目前始终参与监听（无法被关掉），这个取舍写在页面说明里。
     */
    private fun loadInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { info ->
                runCatching {
                    InstalledApp(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                    )
                }.getOrNull()
            }
            .filter { it.label.isNotBlank() }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
