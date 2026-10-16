package me.ethanxu.jevnoisegate.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    localModel: LocalLayaBackend,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = repository.preferences
    val localModelStatus = localModel.status
    val persistenceError: StateFlow<String?> = repository.persistenceError
    suspend fun awaitReady() = repository.awaitReady()

    fun setColorMode(value: Int) = launchSetting { repository.setColorMode(value) }
    fun setKeyColor(value: Int) = launchSetting { repository.setKeyColor(value) }
    fun setAppMuted(packageName: String, muted: Boolean) = launchSetting { repository.setAppMuted(packageName, muted) }
    fun setLogLevel(value: Int) = launchSetting { repository.setLogLevel(value) }
    fun setPreferAllowRules(value: List<String>) = launchSetting { repository.setPreferAllowRules(value) }
    fun setPreferBlockRules(value: List<String>) = launchSetting { repository.setPreferBlockRules(value) }
    fun setSmsSenderBlacklist(value: List<String>) = launchSetting { repository.setSmsSenderBlacklist(value) }
    fun setDeveloperMode(value: Boolean) = launchSetting { repository.setDeveloperMode(value) }
    fun setCategoryThreshold(name: String, value: Float) = launchSetting { repository.setCategoryThreshold(name, value) }
    fun setCategoryAction(name: String, value: String) = launchSetting { repository.setCategoryAction(name, value) }
    fun resetCategoryOverrides() = launchSetting { repository.resetCategoryOverrides() }

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w(LogCategory.SYSTEM, "Settings", error) { "设置未保存" }
            }
        }
    }
}
