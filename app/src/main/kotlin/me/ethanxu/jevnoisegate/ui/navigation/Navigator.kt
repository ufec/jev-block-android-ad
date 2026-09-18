package me.ethanxu.jevnoisegate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 简易导航器：持有一个 backstack 与若干一次性结果通道。
 *
 * 参考 KernelSU 的实现，但保存/恢复改为**基于路由 id**：我们的路由都是无参数的
 * `data object`，存一串字符串即可，不需要 Parcelable 或 kotlinx.serialization。
 *
 * 提供 `navigateForResult` / `setResult` / `observeResult` 是因为"去二级页选择后带结果回来"
 * 是很常见的交互（例如在 App 过滤页里挑选），用结果通道比在返回时反查界面状态更可靠。
 */
class Navigator(initialKey: NavKey) {

    val backStack: SnapshotStateList<NavKey> = mutableStateListOf(initialKey)

    private val resultBus = mutableMapOf<String, MutableSharedFlow<Any>>()

    fun push(key: NavKey) {
        backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) backStack[backStack.lastIndex] = key else backStack.add(key)
    }

    /** 清空并替换整个 backstack。[keys] 为空时不做任何事，避免把用户困在空栈里。 */
    fun replaceAll(keys: List<NavKey>) {
        if (keys.isEmpty()) return
        backStack.clear()
        backStack.addAll(keys)
    }

    fun pop() {
        backStack.removeLastOrNull()
    }

    /** 连续出栈直到栈顶满足 [predicate]。 */
    fun popUntil(predicate: (NavKey) -> Boolean) {
        while (backStack.isNotEmpty() && !predicate(backStack.last())) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun navigateForResult(route: Route, requestKey: String) {
        ensureChannel(requestKey)
        push(route)
    }

    fun <T : Any> setResult(requestKey: String, value: T) {
        ensureChannel(requestKey).tryEmit(value)
        pop()
    }

    fun <T : Any> observeResult(requestKey: String): SharedFlow<T> {
        @Suppress("UNCHECKED_CAST")
        return ensureChannel(requestKey) as SharedFlow<T>
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearResult(requestKey: String) {
        ensureChannel(requestKey).resetReplayCache()
    }

    fun current(): NavKey? = backStack.lastOrNull()

    fun backStackSize(): Int = backStack.size

    private fun ensureChannel(key: String): MutableSharedFlow<Any> =
        resultBus.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 0) }

    companion object {
        /**
         * 只存路由 id。`listSaver` 的保存值必须是 Bundle 可容纳的类型，String 满足。
         */
        val Saver: Saver<Navigator, Any> = listSaver(
            save = { navigator ->
                navigator.backStack.map { key -> (key as? Route)?.id ?: Route.Main.id }
            },
            restore = { saved ->
                Navigator(Route.Main).apply {
                    val restored = saved.filterIsInstance<String>().map(Route::fromId)
                    backStack.clear()
                    backStack.addAll(restored.ifEmpty { listOf(Route.Main) })
                }
            },
        )
    }
}

@Composable
fun rememberNavigator(startRoute: NavKey): Navigator =
    rememberSaveable(startRoute, saver = Navigator.Saver) { Navigator(startRoute) }

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator 未提供 —— 检查 MainActivity 是否用 CompositionLocalProvider 包裹了内容")
}
