package me.ethanxu.jevnoisegate.ui.screen.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.common.log.LogEntry
import me.ethanxu.jevnoisegate.core.common.log.LogLevel
import me.ethanxu.jevnoisegate.ui.component.SectionCard
import me.ethanxu.jevnoisegate.ui.component.SubPageScaffold
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator
import me.ethanxu.jevnoisegate.ui.util.formatTime

/**
 * 运行日志。
 *
 * 上半部分是**记录等级设置**，下半部分是**日志查看**，合成一页 ——
 * 因为在排查时这两件事总是一起做："日志怎么这么少"→ 调低等级；
 * "日志太吵了"→ 调高等级。分成两页反而要多跳一次。
 *
 * 筛选（分类 / 等级）只作用于**显示**，不影响记录 ——
 * 调整记录等级才是真正减少热路径开销的手段，这一点在页面上写明了。
 */
@Composable
fun LogScreen(
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val navigator = LocalNavigator.current
    val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val allEntries by AppLog.entries.collectAsStateWithLifecycle()

    var categoryFilter by remember { mutableStateOf<LogCategory?>(null) }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }

    val shown = remember(allEntries, categoryFilter, levelFilter) {
        allEntries.filter { entry ->
            (categoryFilter == null || entry.category == categoryFilter) &&
                (levelFilter == null || entry.level == levelFilter)
        }
    }

    SubPageScaffold(title = "运行日志", onBack = { navigator.pop() }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = "记录等级") {
                    Column {
                        LevelOption(
                            label = "关闭",
                            summary = "完全不记录，热路径零开销",
                            selected = prefs.logLevel == AppLog.OFF,
                            onClick = { settingsViewModel.setLogLevel(AppLog.OFF) },
                        )
                        LEVEL_OPTIONS.forEach { level ->
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            LevelOption(
                                label = level.label,
                                summary = level.hint(),
                                selected = prefs.logLevel == level.value,
                                onClick = { settingsViewModel.setLogLevel(level.value) },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "筛选") {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        FilterRow(
                            label = "分类",
                            options = CATEGORY_FILTERS,
                            selected = categoryFilter,
                            onSelect = { categoryFilter = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        FilterRow(
                            label = "等级",
                            options = LEVEL_FILTERS,
                            selected = levelFilter,
                            onSelect = { levelFilter = it },
                        )
                    }
                }
            }

            item {
                // 磁盘占用随条目变化重算：清空后要立刻反映出来。
                val diskBytes = remember(allEntries) { AppLog.diskUsageBytes() }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "共 ${allEntries.size} 条，显示 ${shown.size} 条（内存上限 $LOG_CAPACITY）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "已落盘 ${formatBytes(diskBytes)}（上限 $MAX_FILE_MB MB × 2 代）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    TextButton(onClick = AppLog::clear) { Text("清空日志") }
                }
            }

            if (shown.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = if (allEntries.isEmpty()) {
                                "还没有日志。让别的 App 发一条通知，或调低记录等级后再看。"
                            } else {
                                "当前筛选条件下没有日志。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(shown, key = { it.seq }) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LevelOption(
    label: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun <T> FilterRow(
    label: String,
    options: List<Pair<String, T?>>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp).padding(top = 12.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { (text, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    SectionCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(
                    text = entry.level.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = entry.level.color(),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun LogLevel.color(): Color = when (this) {
    LogLevel.VERBOSE, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}

private fun LogLevel.hint(): String = when (this) {
    LogLevel.VERBOSE -> "最啰嗦，包含每条通知的原始字段。排查疑难问题才用"
    LogLevel.DEBUG -> "常规调试信息，含每条通知的采集与判断结果"
    LogLevel.INFO -> "推荐。只记关键节点：收到通知、判断结果、请求完成"
    LogLevel.WARN -> "只记异常但不致命的情况，如降级放行"
    LogLevel.ERROR -> "只记错误。日志最少，几乎不影响性能"
}

private val LEVEL_OPTIONS = listOf(
    LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR,
)

/** 首项是"全部"，其 value 为 null。 */
private val CATEGORY_FILTERS: List<Pair<String, LogCategory?>> =
    listOf<Pair<String, LogCategory?>>("全部" to null) +
        LogCategory.entries.map { it.label to it }

private val LEVEL_FILTERS: List<Pair<String, LogLevel?>> =
    listOf<Pair<String, LogLevel?>>("全部" to null) +
        LEVEL_OPTIONS.map { it.label to it }

private const val LOG_CAPACITY = 500
private const val MAX_FILE_MB = 2

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
