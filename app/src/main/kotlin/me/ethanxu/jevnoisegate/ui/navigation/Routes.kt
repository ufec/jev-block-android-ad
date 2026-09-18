package me.ethanxu.jevnoisegate.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * 全部导航目的地。
 *
 * **四个 tab 不是四条路由**，而是 [Main] 这个屏幕内部的 pager 页 —— 这一点很关键：
 * 如果 tab 各占一条路由、再由顶层脚手架按路由开关底栏，转场时底栏会突兀地出现/消失，
 * 内容 padding 跟着跳，视觉上就是"闪"。把底栏放进 [Main] 内部，切 tab 时它始终在场，
 * 进子页时又随 [Main] 整体滑出，不会有任何栏的突变。
 */
sealed interface Route : NavKey {

    /** 稳定标识，仅用于 backstack 的保存与恢复。改名会破坏已保存的导航状态。 */
    val id: String

    /** 主页：内含四个 tab 的 pager 与底栏。 */
    data object Main : Route {
        override val id = "main"
    }

    // --- 设置子页 ---

    data object ThemeSettings : Route {
        override val id = "settings/theme"
    }

    data object JevApiSettings : Route {
        override val id = "settings/api"
    }

    /** 网络代理。与 API 凭据分开：一个是链路怎么出去，一个是我是谁。 */
    data object ProxySettings : Route {
        override val id = "settings/proxy"
    }

    /** 用户偏好规则。作为软约束随 state 交给模型。 */
    data object UserRules : Route {
        override val id = "settings/user-rules"
    }

    /** 短信号码黑名单。硬规则，命中不调用模型。 */
    data object SmsBlacklist : Route {
        override val id = "settings/sms-blacklist"
    }

    data object AppFilterSettings : Route {
        override val id = "settings/app-filter"
    }

    /** 运行日志。放在诊断下，因为它是排查工具而非设置。 */
    data object Logs : Route {
        override val id = "diagnostics/logs"
    }

    companion object {
        val all: List<Route> =
            listOf(
                Main, ThemeSettings, JevApiSettings, ProxySettings,
                UserRules, SmsBlacklist, AppFilterSettings, Logs,
            )

        fun fromId(id: String): Route = all.firstOrNull { it.id == id } ?: Main
    }
}
