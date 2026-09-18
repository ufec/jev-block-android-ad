package me.ethanxu.jevnoisegate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import me.ethanxu.jevnoisegate.ui.component.SubPageScaffold
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator
import me.ethanxu.jevnoisegate.ui.navigation.Navigator
import me.ethanxu.jevnoisegate.ui.navigation.Route
import me.ethanxu.jevnoisegate.ui.navigation.rememberNavigator
import me.ethanxu.jevnoisegate.ui.screen.diagnostics.LogScreen
import me.ethanxu.jevnoisegate.ui.screen.main.MainScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.AppFilterSettingsScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.JevApiSettingsScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.ProxySettingsScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.SmsBlacklistScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.UserRulesScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.ThemeSettingsScreen
import me.ethanxu.jevnoisegate.ui.theme.AppSettings
import me.ethanxu.jevnoisegate.ui.theme.ColorMode
import me.ethanxu.jevnoisegate.ui.theme.JevNoiseGateTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsViewModel = viewModel<SettingsViewModel>()
            val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()

            val appSettings = remember(prefs.colorMode, prefs.keyColor) {
                AppSettings(
                    colorMode = ColorMode.fromValue(prefs.colorMode),
                    keyColor = prefs.keyColor,
                )
            }

            // 起始页固定为主页；backstack 由 Navigator 自己保存与恢复。
            val navigator = rememberNavigator(Route.Main)

            JevNoiseGateTheme(appSettings = appSettings) {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    // 顶层只提供背景容器 —— 顶栏与底栏由各屏幕自己带。
                    // 放在这里按路由开关栏，会让转场中途出现栏的突变，视觉上就是"闪"。
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        AppNavHost(navigator)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navigator: Navigator) {
    NavDisplay(
        backStack = navigator.backStack,
        entryDecorators = listOf(
            // 保存各页面的 UI 状态（滚动位置等），返回时不丢失
            rememberSaveableStateHolderNavEntryDecorator(),
            // 让每个 NavEntry 拥有独立的 ViewModel 作用域
            rememberViewModelStoreNavEntryDecorator(),
        ),
        onBack = { navigator.pop() },
        // 显式指定转场，不用 Navigation3 的默认值。
        // 默认的 pop 是「淡入 + 缩放淡出」：上一层先出现，然后当前页原地缩小消失 ——
        // 观感上像卡了一下。改成标准横向滑动后，返回时上一页从左侧滑回、当前页向右滑出，
        // 与系统其它应用的返回手势一致。
        transitionSpec = {
            slideInHorizontally(initialOffsetX = { it }) + fadeIn() togetherWith
                slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut()
        },
        popTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn() togetherWith
                slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn() togetherWith
                slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        },
        entryProvider = { key ->
            when (key) {
                Route.Main -> NavEntry(key) { MainScreen() }

                // 子页各自带自己的顶栏与返回按钮，从而与主页整体做转场，
                // 不会出现"栏在转场中途跳出来"的闪烁。
                Route.ThemeSettings -> NavEntry(key) {
                    SubPageScaffold(title = "主题设置", onBack = { navigator.pop() }) { padding ->
                        Box(Modifier.padding(padding)) { ThemeSettingsScreen() }
                    }
                }

                Route.JevApiSettings -> NavEntry(key) {
                    SubPageScaffold(title = "JevAPI 设置", onBack = { navigator.pop() }) { padding ->
                        Box(Modifier.padding(padding)) { JevApiSettingsScreen() }
                    }
                }

                Route.UserRules -> NavEntry(key) {
                    // UserRulesScreen 自带 SubPageScaffold。
                    UserRulesScreen()
                }

                Route.SmsBlacklist -> NavEntry(key) {
                    // SmsBlacklistScreen 自带 SubPageScaffold。
                    SmsBlacklistScreen()
                }

                Route.ProxySettings -> NavEntry(key) {
                    // ProxySettingsScreen 自带 SubPageScaffold。
                    ProxySettingsScreen()
                }

                Route.AppFilterSettings -> NavEntry(key) {
                    SubPageScaffold(title = "监听通知 App", onBack = { navigator.pop() }) { padding ->
                        Box(Modifier.padding(padding)) { AppFilterSettingsScreen() }
                    }
                }

                // LogScreen 自带 SubPageScaffold，这里不再包一层。
                Route.Logs -> NavEntry(key) { LogScreen() }

                // key 的静态类型是 NavKey 而非 Route，when 无法静态穷尽，需要兜底分支。
                else -> NavEntry(key) {
                    Text(
                        text = "该路由没有注册对应的界面",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        },
    )
}
