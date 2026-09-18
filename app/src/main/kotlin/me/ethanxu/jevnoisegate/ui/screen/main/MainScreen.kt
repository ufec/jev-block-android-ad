package me.ethanxu.jevnoisegate.ui.screen.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.ui.component.MainBottomBar
import me.ethanxu.jevnoisegate.ui.component.MainTabs
import me.ethanxu.jevnoisegate.ui.component.ExpressiveScaffold
import me.ethanxu.jevnoisegate.ui.screen.channels.ChannelsScreen
import me.ethanxu.jevnoisegate.ui.screen.diagnostics.DiagnosticsScreen
import me.ethanxu.jevnoisegate.ui.screen.events.EventsScreen
import me.ethanxu.jevnoisegate.ui.screen.settings.SettingsScreen

/**
 * 主页：四个 tab。
 *
 * **tab 是 pager 的页，不是四条导航路由** —— 这是从 KernelSU 学来的关键结构。
 * 底栏因此活在这个屏幕内部：切 tab 时它始终在场（无需重建），进子页时它随主页整体滑出，
 * 全程没有任何栏的突变。
 *
 * 用 pager 而不是"替换 backstack"还有一个实际好处：切 tab 有横向滑动手感，
 * 且各页的滚动位置由 pager 自己保住。
 */
@Composable
fun MainScreen() {
    val pagerState = rememberPagerState(pageCount = { MainTabs.size })
    val scope = rememberCoroutineScope()

    // 在主页但不在第一个 tab 时按返回，先回到第一个 tab 而不是退出 App。
    // 这是 KernelSU 的行为（见其 MainScreenBackHandler），比直接退出更符合直觉。
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    ExpressiveScaffold(
        bottomBar = {
            MainBottomBar(
                items = MainTabs,
                selectedIndex = pagerState.currentPage,
                onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            when (page) {
                0 -> EventsScreen()
                1 -> ChannelsScreen()
                2 -> DiagnosticsScreen()
                else -> SettingsScreen()
            }
        }
    }
}
