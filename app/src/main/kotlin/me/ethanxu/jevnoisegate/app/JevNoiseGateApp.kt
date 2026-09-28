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

@HiltAndroidApp
class JevNoiseGateApp : Application() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var localModel: LocalLayaBackend

    override fun onCreate() {
        super.onCreate()

        // 阻塞等待设置首次从磁盘加载完成。
        //
        // 日志等级必须在首条业务日志前加载。
        //
        // 代价是一次磁盘读取（实测 10~50ms），发生在冷启动、早于界面绘制。
        settings.awaitReady()

        // 日志落盘要在产生第一条日志之前就绪，否则启动早期的日志重启后会丢。
        AppLog.init(this)
        me.ethanxu.jevnoisegate.core.common.ServiceState.enable(
            getSharedPreferences("service", 0).getBoolean("enabled", false),
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { localModel.prepare() }
    }
}
