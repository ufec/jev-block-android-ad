package me.ethanxu.jevnoisegate.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration

@HiltAndroidApp
class JevNoiseGateApp : Application(), Configuration.Provider {

    @Inject
    lateinit var settings: SettingsRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // 初始化日志时挂起等待设置，首帧与主线程无需等待磁盘读取。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.awaitReady()
            AppLog.configure(settings.preferences.value.logLevel)
            AppLog.init(this@JevNoiseGateApp)
        }
    }
}
