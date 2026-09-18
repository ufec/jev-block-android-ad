package me.ethanxu.jevnoisegate.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.ui.component.SegmentedArrowItem
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.ui.navigation.Route
import me.ethanxu.jevnoisegate.ui.theme.ColorMode

/**
 * 设置枢纽。
 *
 * 只放入口、不承载具体设置：主列表永远一屏可读完，也避免外观、接口、过滤三类
 * 完全不同的设置混在一起。每项的副标题直接显示**当前值**，
 * 因此大部分情况下不点进去就知道配置状态。
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SegmentedColumn(title = "外观") {
                item {
                    SegmentedArrowItem(
                        title = "主题设置",
                        summary = ColorMode.fromValue(prefs.colorMode).displayName(),
                        onClick = { navigator.push(Route.ThemeSettings) },
                    )
                }
            }
        }

        item {
            SegmentedColumn(title = "判断") {
                item {
                    SegmentedArrowItem(
                        title = "JevAPI 设置",
                        summary = if (prefs.isApiConfigured) "已配置" else "未配置 · 判断会降级放行",
                        onClick = { navigator.push(Route.JevApiSettings) },
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "用户偏好规则",
                        summary = userRulesSummary(prefs),
                        onClick = { navigator.push(Route.UserRules) },
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "短信号码黑名单",
                        summary = if (prefs.smsSenderBlacklist.isEmpty()) {
                            "未配置"
                        } else {
                            "已拉黑 ${prefs.smsSenderBlacklist.size} 个号码 · 命中直接拦截"
                        },
                        onClick = { navigator.push(Route.SmsBlacklist) },
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "网络代理",
                        summary = proxySummary(prefs),
                        onClick = { navigator.push(Route.ProxySettings) },
                    )
                }
                item {
                    SegmentedArrowItem(
                        title = "监听通知 App",
                        summary = if (prefs.mutedPackages.isEmpty()) {
                            "全部 App 都监听中"
                        } else {
                            "已关闭 ${prefs.mutedPackages.size} 个 App"
                        },
                        onClick = { navigator.push(Route.AppFilterSettings) },
                    )
                }
            }
        }
    }
}

private fun userRulesSummary(prefs: AppPreferences): String {
    val allow = prefs.preferAllowRules.size
    val block = prefs.preferBlockRules.size
    return if (allow == 0 && block == 0) {
        "未配置 · 全部交由模型判断"
    } else {
        "优先放行 $allow 条 · 优先拦截 $block 条"
    }
}

private fun proxySummary(prefs: AppPreferences): String = when {
    prefs.proxyType == AppPreferences.PROXY_NONE -> "直连"
    !prefs.isProxyConfigured -> "未配置完整 · 当前按直连处理"
    else -> "${prefs.proxyType.uppercase()} · ${prefs.proxyHost}:${prefs.proxyPort}"
}

internal fun ColorMode.displayName(): String = when (this) {    ColorMode.SYSTEM -> "跟随系统"
    ColorMode.LIGHT -> "浅色"
    ColorMode.DARK -> "深色"
    ColorMode.MONET_SYSTEM -> "Monet 跟随系统"
    ColorMode.MONET_LIGHT -> "Monet 浅色"
    ColorMode.MONET_DARK -> "Monet 深色"
    ColorMode.DARK_AMOLED -> "深色 AMOLED"
}
