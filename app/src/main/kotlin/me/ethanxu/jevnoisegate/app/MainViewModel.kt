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
import kotlinx.coroutines.flow.asStateFlow
import me.ethanxu.jevnoisegate.ui.util.hasNotificationAccess
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.ethanxu.jevnoisegate.core.common.PerformanceMetrics
import me.ethanxu.jevnoisegate.core.common.PerformanceSnapshot
import me.ethanxu.jevnoisegate.core.data.ActionCount
import me.ethanxu.jevnoisegate.core.data.ChannelCount
import me.ethanxu.jevnoisegate.core.data.EventSummary
import me.ethanxu.jevnoisegate.core.data.EventContent
import me.ethanxu.jevnoisegate.core.data.ObservedEventDao
import me.ethanxu.jevnoisegate.core.data.EventOverview

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
    val events: List<EventSummary>,
) {
    val latest: EventSummary get() = events.first()
    val count: Int get() = events.size
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ObservedEventDao,
    private val localModel: LocalLayaBackend,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val recoveryNotifier: RecoveryNotifier,
) : ViewModel() {
    val modelStatus = localModel.status
    val serviceConnected = me.ethanxu.jevnoisegate.core.common.ServiceState.connected
    val serviceEnabled = me.ethanxu.jevnoisegate.core.common.ServiceState.enabled
    private val notice = kotlinx.coroutines.flow.MutableStateFlow("")
    val serviceNotice = notice.asStateFlow()
    fun downloadModel() = localModel.download()

    fun toggleService() {
        val state = me.ethanxu.jevnoisegate.core.common.ServiceState
        if (state.enabled.value) {
            state.enable(false)
            context.getSharedPreferences("service", 0).edit().putBoolean("enabled", false).apply()
            notice.value = "降噪已停止"
            return
        }
        viewModelScope.launch {
            notice.value = "正在检查启动条件…"
            localModel.prepare()
            when {
                !modelStatus.value.ready -> notice.value = "请先下载并准备模型"
                !context.hasNotificationAccess() -> notice.value = "请先授予通知使用权"
                !state.connected.value -> notice.value = "通知监听服务尚未连接，请点击重新连接或检查系统授权"
                else -> {
                    state.enable(true)
                    context.getSharedPreferences("service", 0).edit().putBoolean("enabled", true).apply()
                    notice.value = "降噪已开启"
                }
            }
        }
    }

    fun reconnectService() {
        android.service.notification.NotificationListenerService.requestRebind(
            android.content.ComponentName(context, me.ethanxu.jevnoisegate.feature.notification.NoiseGateNotificationListener::class.java),
        )
        notice.value = "已请求系统重新连接，请等待服务状态更新"
    }

    val performance = flow {
        while (currentCoroutineContext().isActive) {
            emit(PerformanceMetrics.snapshot())
            delay(1_000)
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PerformanceSnapshot())

    private val events: StateFlow<List<EventSummary>> =
        dao.observeRecentSummaries(RECENT_LIMIT).distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    fun observeContent(id: String): Flow<EventContent?> = dao.observeContent(id).distinctUntilChanged()

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
        .map { list -> withContext(Dispatchers.Default) {
            list.groupBy { it.packageName }.map { (pkg, groupEvents) ->
                AppEventGroup(
                    packageName = pkg,
                    // appLabel 在写入时就已解析并落库，因此这里不必再查 PackageManager。
                    label = groupEvents.first().appLabel ?: pkg,
                    events = groupEvents,
                )
            }
        } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    private val overview = dao.observeOverview().asState(EventOverview())
    val totalCount: StateFlow<Int> = overview.map { it.total }.asState(0)

    val topChannels: StateFlow<List<ChannelCount>> =
        dao.observeTopChannels(TOP_CHANNEL_LIMIT).asState(emptyList())

    /** 查询已按 latencyMs 升序返回，因此可直接取分位数。 */
    val latencyStats: StateFlow<LatencyStats> =
        dao.observeLatencies().map(LatencyStats::from).asState(LatencyStats.EMPTY)

    val actionCounts: StateFlow<List<ActionCount>> = overview.map {
        listOf(ActionCount("ALLOW", it.allowed), ActionCount("SILENT_SUPPRESS", it.suppressed),
            ActionCount("QUARANTINE", it.quarantined)).filter { count -> count.eventCount > 0 }
    }.asState(emptyList())

    data class QuarantineCursor(val timestampMs: Long, val id: String)
    data class QuarantinePage(val cursor: QuarantineCursor?, val entries: List<EventSummary>, val hasNext: Boolean)
    private val quarantineCursor = MutableStateFlow<QuarantineCursor?>(null)
    private val previousCursors = mutableListOf<QuarantineCursor?>()
    private val _quarantinePageNumber = MutableStateFlow(1)
    val quarantinePageNumber = _quarantinePageNumber.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val quarantinePage = quarantineCursor.flatMapLatest { cursor ->
        val rows = if (cursor == null) dao.observeQuarantineSummaries(QUARANTINE_PAGE_SIZE + 1)
            else dao.observeQuarantineSummariesBefore(cursor.timestampMs, cursor.id, QUARANTINE_PAGE_SIZE + 1)
        rows.map { QuarantinePage(cursor, it.take(QUARANTINE_PAGE_SIZE), it.size > QUARANTINE_PAGE_SIZE) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), QuarantinePage(null, emptyList(), false))
    val quarantineCount = overview.map { it.quarantined }.asState(0)
    fun nextQuarantinePage() {
        val page = quarantinePage.value
        if (!page.hasNext || page.cursor != quarantineCursor.value) return
        val last = page.entries.lastOrNull() ?: return
        previousCursors += quarantineCursor.value
        quarantineCursor.value = QuarantineCursor(last.timestampMs, last.id)
        _quarantinePageNumber.value++
    }
    fun previousQuarantinePage() {
        if (previousCursors.isEmpty()) return
        quarantineCursor.value = previousCursors.removeAt(previousCursors.lastIndex)
        _quarantinePageNumber.value--
    }
    private fun resetQuarantinePage() {
        previousCursors.clear()
        quarantineCursor.value = null
        _quarantinePageNumber.value = 1
    }
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
        viewModelScope.launch { dao.clear(); resetQuarantinePage() }
    }

    /**
     * 删除某个应用的全部事件。
     *
     * 按**应用**而不是单条删除：事件页是按应用分组的，用户滑掉一张卡片的意图
     * 显然是"这个应用的记录我不要了"，而不是精确到某一条。
     */
    fun deleteApp(packageName: String) {
        viewModelScope.launch { dao.deleteByPackage(packageName); resetQuarantinePage() }
    }

    /** 收集时短暂停止订阅，避免屏幕旋转等重配置导致反复重查数据库。 */
    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> = distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = initial,
    )

    private companion object {
        const val RECENT_LIMIT = 300
        const val TOP_CHANNEL_LIMIT = 10
        const val STOP_TIMEOUT_MS = 5_000L
        const val QUARANTINE_PAGE_SIZE = 100
    }
}
