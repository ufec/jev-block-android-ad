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

    @Inject
    lateinit var localModel: LocalLayaBackend

    override fun onCreate() {
        super.onCreate()

        // 降噪开关的持久状态必须在任何通知到达前恢复，否则重启后
        // 用户明明开着降噪，却要等下一次进设置页才会重新生效。
        me.ethanxu.jevnoisegate.core.common.ServiceState.enable(
            getSharedPreferences("service", 0).getBoolean("enabled", false),
        )

        // 初始化日志时挂起等待设置，首帧与主线程无需等待磁盘读取。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.awaitReady()
            AppLog.configure(settings.preferences.value.logLevel)
            AppLog.init(this@JevNoiseGateApp)
            // 模型校验要读 679 MB 并算 SHA-256，只能放在这里；
            // 就绪前 ServiceState.running 恒为 false，不会放行任何消息。
            localModel.prepare()
        }
    }
}
