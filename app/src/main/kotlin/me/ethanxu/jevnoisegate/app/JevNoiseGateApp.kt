package me.ethanxu.jevnoisegate.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository

@HiltAndroidApp
class JevNoiseGateApp : Application() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()

        // 阻塞等待设置首次从磁盘加载完成。
        //
        // 下游有若干处**在构造时就需要真实配置**：HTTP 客户端（Base URL / API key / 代理）
        // 与日志等级。它们构造得比首帧早，而内存 StateFlow 此刻还只是默认值 ——
        // 不等待的话会静默按默认值工作，表现为"配了 key 却打到默认地址"且不报错。
        //
        // 代价是一次磁盘读取（实测 10~50ms），发生在冷启动、早于界面绘制。
        settings.awaitReady()

        // 日志落盘要在产生第一条日志之前就绪，否则启动早期的日志重启后会丢。
        AppLog.init(this)
    }
}
