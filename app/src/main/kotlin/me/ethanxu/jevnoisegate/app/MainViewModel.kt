package me.ethanxu.jevnoisegate.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ethanxu.jevnoisegate.core.data.ActionCount
import me.ethanxu.jevnoisegate.core.data.ChannelCount
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao

/**
 * 一次判断的延迟分布。
 *
 * P50 是常态，P95 才是决定体验的那个数 —— 用户感知到的是尾部延迟，
 * 而不是平均值。
 */
data class LatencyStats(
    val count: Int,
    val p50: Long,
    val p95: Long,
    val max: Long,
) {
    companion object {
        val EMPTY = LatencyStats(0, 0, 0, 0)

        fun from(sortedMillis: List<Long>): LatencyStats {
            if (sortedMillis.isEmpty()) return EMPTY
            return LatencyStats(
                count = sortedMillis.size,
                p50 = sortedMillis[sortedMillis.size * 50 / 100],
                p95 = sortedMillis[(sortedMillis.size * 95 / 100).coerceAtMost(sortedMillis.lastIndex)],
                max = sortedMillis.last(),
            )
        }
    }
}

/**
 * 某一应用的消息事件，按时间倒序。
 *
 * 事件页按包名分组展示 —— 同一个 App 的消息聚在一起，
 * 避免它连续刷屏时把整屏占满、把别的 App 挤出视野。
 */
data class AppEventGroup(
    val packageName: String,
    val label: String,
    val events: List<MessageEventEntity>,
) {
    val latest: MessageEventEntity get() = events.first()
    val count: Int get() = events.size
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ObservedEventDao,
    private val recoveryNotifier: RecoveryNotifier,
) : ViewModel() {

    val events: StateFlow<List<MessageEventEntity>> =
        dao.observeRecent(RECENT_LIMIT).asState(emptyList())

    /**
     * 按应用包名分组的事件。
     *
     * 组间顺序 = 各组最新事件的时间倒序。因为 [events] 本身按时间倒序，
     * `groupBy` 保留键首次出现的顺序，于是"最近活跃的 App"自然排在前面 ——
     * 不需要额外排序。
     *
     * 组内同样是时间倒序，因此在界面上直接把每组第一条当作该 App 的"最近一条"。
     */
    val eventGroups: StateFlow<List<AppEventGroup>> = events
        .map { list ->
            list.groupBy { it.packageName }.map { (pkg, groupEvents) ->
                AppEventGroup(
                    packageName = pkg,
                    // appLabel 在写入时就已解析并落库，因此这里不必再查 PackageManager。
                    label = groupEvents.first().appLabel ?: pkg,
                    events = groupEvents,
                )
            }
        }
        .asState(emptyList())

    val totalCount: StateFlow<Int> = dao.observeCount().asState(0)

    val topChannels: StateFlow<List<ChannelCount>> =
        dao.observeTopChannels(TOP_CHANNEL_LIMIT).asState(emptyList())

    /** 查询已按 latencyMs 升序返回，因此可直接取分位数。 */
    val latencyStats: StateFlow<LatencyStats> =
        dao.observeLatencies().map(LatencyStats::from).asState(LatencyStats.EMPTY)

    val actionCounts: StateFlow<List<ActionCount>> = dao.observeActionCounts().asState(emptyList())

    /** 后端调用尝试数，包括配置缺失等未发送请求的失败。 */
    val backendAttemptCount: StateFlow<Int> = dao.observeBackendAttemptCount().asState(0)
    private val quarantineLimit = MutableStateFlow(100)
    @OptIn(ExperimentalCoroutinesApi::class)
    val quarantinedEvents = quarantineLimit.flatMapLatest { dao.observeQuarantined(it) }.asState(emptyList())
    val quarantineCount = dao.observeQuarantineCount().asState(0)
    fun loadMoreQuarantined() { quarantineLimit.value = (quarantineLimit.value + 100).coerceAtMost(10_000) }
    private val _restoreMessage = MutableStateFlow<String?>(null)
    val restoreMessage = _restoreMessage.asStateFlow()
    private val restoreLock = Mutex()

    fun restore(id: String) {
        viewModelScope.launch {
            restoreLock.withLock {
                try {
                    val event = dao.find(id)?.takeIf { it.action == "QUARANTINE" } ?: return@withLock
                    recoveryNotifier.post(event)
                    dao.restore(id, System.currentTimeMillis())
                    _restoreMessage.value = "已重新发送通知，相同完整内容今后优先放行"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _restoreMessage.value = "恢复失败：${failure.message ?: "请稍后重试"}"
                }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clear() }
    }

    /**
     * 删除某个应用的全部事件。
     *
     * 按**应用**而不是单条删除：事件页是按应用分组的，用户滑掉一张卡片的意图
     * 显然是"这个应用的记录我不要了"，而不是精确到某一条。
     */
    fun deleteApp(packageName: String) {
        viewModelScope.launch { dao.deleteByPackage(packageName) }
    }

    /** 收集时短暂停止订阅，避免屏幕旋转等重配置导致反复重查数据库。 */
    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> = stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = initial,
    )

    private companion object {
        const val RECENT_LIMIT = 300
        const val TOP_CHANNEL_LIMIT = 10
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
