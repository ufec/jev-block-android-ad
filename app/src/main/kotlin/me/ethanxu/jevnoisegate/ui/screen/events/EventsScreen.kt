package me.ethanxu.jevnoisegate.ui.screen.events

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import me.ethanxu.jevnoisegate.ui.component.TonalCard as Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.AppEventGroup
import me.ethanxu.jevnoisegate.app.MainViewModel
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity
import me.ethanxu.jevnoisegate.ui.util.formatTime
import me.ethanxu.jevnoisegate.ui.util.hasNotificationAccess
import me.ethanxu.jevnoisegate.ui.util.hasSmsPermission
import me.ethanxu.jevnoisegate.ui.util.rememberAppIcon

@Composable
fun EventsScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val events by viewModel.events.collectAsStateWithLifecycle()
    val groups by viewModel.eventGroups.collectAsStateWithLifecycle()
    val total by viewModel.totalCount.collectAsStateWithLifecycle()

    // 用户会离开 App 去设置页授权，因此每次回到前台都重新读取权限状态。
    var notificationAccess by remember { mutableStateOf(context.hasNotificationAccess()) }
    var smsGranted by remember { mutableStateOf(context.hasSmsPermission()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = context.hasNotificationAccess()
                smsGranted = context.hasSmsPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> smsGranted = granted }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PermissionCard(
                notificationAccess = notificationAccess,
                smsGranted = smsGranted,
                onOpenNotificationSettings = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onRequestSms = { smsPermissionLauncher.launch(Manifest.permission.READ_SMS) },
            )
        }

        item { CollectedCard(total = total) }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "最近事件 · ${groups.size} 个应用 / ${events.size} 条",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearAll) { Text("清空") }
            }
        }

        if (groups.isEmpty()) {
            item { EmptyHint(notificationAccess = notificationAccess) }
        } else {
            // 按包名分组：组间是"最近活跃优先"，组内时间倒序。
            items(groups, key = { it.packageName }) { group ->
                SwipeToDeleteGroup(
                    group = group,
                    onDelete = { viewModel.deleteApp(group.packageName) },
                )
            }
        }
    }
}

/**
 * 可左右滑动删除的分组卡片。
 *
 * 两个方向都启用：单手操作时左滑右滑的顺手方向因人而异，只留一个方向会让人以为不支持。
 *
 * 删除没有二次确认也没有撤销 —— 这是日志型数据，新的会不断进来，
 * 而加确认会让"清理掉这个烦人的 App"这个高频动作多一步。滑动本身已经是一次明确意图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteGroup(group: AppEventGroup, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()

    // 用 LaunchedEffect 而不是 confirmValueChange 回调里直接删：
    // 后者会在状态机内部触发副作用，属于在动画中间改数据，容易出现卡片卡在半滑状态。
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) onDelete()
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        AppGroupCard(group)
    }
}

/**
 * 单个应用的事件分组。
 *
 * 默认只展开最近 [COLLAPSED_COUNT] 条 —— 分组的目的是让同一 App 不再刷屏占满视野，
 * 所以默认视图必须足够短；想看全部再手动展开。
 */
@Composable
private fun AppGroupCard(group: AppEventGroup) {
    var expanded by remember(group.packageName) { mutableStateOf(false) }
    val icon = rememberAppIcon(group.packageName)

    // 展开也不是无限：单个分组最多渲染 EXPANDED_MAX 条。
    //
    // 事件是塞在**同一个 LazyColumn item** 里的（这是一个分组卡片），
    // 卡片内部的这些 EventDetail 不会被 LazyColumn 虚拟化 ——
    // 渲染多少就组合多少。所以必须有硬上限，否则展开一个几十条的分组会一次性
    // 组合几十个节点，把整页拖慢。
    val visible = group.events.take(if (expanded) EXPANDED_MAX else COLLAPSED_COUNT)
    val hidden = group.count - visible.size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${group.count} 条", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = formatTime(group.latest.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            visible.forEach { entity ->
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                EventDetail(entity)
            }

            if (hidden > 0 || expanded) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (expanded) "收起" else "展开其余 ${group.count - visible.size} 条")
                }
            }
            if (expanded && hidden > 0) {
                Text(
                    text = "另有 $hidden 条未显示（单组最多展开 $EXPANDED_MAX 条）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

private const val COLLAPSED_COUNT = 3

/** 单个分组展开后最多渲染的条数。见 AppGroupCard 里关于虚拟化的说明。 */
private const val EXPANDED_MAX = 20

// ---------------------------------------------------------------------------
// 权限
// ---------------------------------------------------------------------------

@Composable
private fun PermissionCard(
    notificationAccess: Boolean,
    smsGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onRequestSms: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("权限", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "通知监听是硬依赖，没有它整个 App 都不会工作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                label = "通知使用权",
                granted = notificationAccess,
                required = true,
                actionLabel = if (notificationAccess) "去设置" else "去授权",
                onAction = onOpenNotificationSettings,
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow(
                label = "读取短信",
                granted = smsGranted,
                required = false,
                actionLabel = if (smsGranted) "已授权" else "授权",
                onAction = onRequestSms,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    granted -> "已授权"
                    required -> "未授权 · 必需"
                    else -> "未授权 · 可选，缺失时降级"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    granted -> MaterialTheme.colorScheme.primary
                    required -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        OutlinedButton(onClick = onAction, enabled = !granted || required) { Text(actionLabel) }
    }
}

// ---------------------------------------------------------------------------
// 事件
// ---------------------------------------------------------------------------

@Composable
private fun CollectedCard(total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(total.toString(), style = MaterialTheme.typography.headlineMedium)
            Text(
                "已采集事件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventDetail(entity: MessageEventEntity) {
    // 内容默认折叠 —— 事件页是"看判断结果"的地方，不是"读消息"的地方。
    // 默认展开意味着任何一次截屏都会把通知/短信正文一起带走。
    var revealed by remember(entity.id) { mutableStateOf(false) }
    val hasContent = !entity.title.isNullOrBlank() || entity.body.isNotBlank()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (entity.source == "SMS") "短信" else "通知",
                style = MaterialTheme.typography.labelSmall,
                color = if (entity.source == "SMS") {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(8.dp))
            // 应用名不在这里重复 —— 外层分组卡片已经显示了包名与应用名。
            Text(
                text = formatTime(entity.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasContent) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { revealed = !revealed },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (revealed) "隐藏内容" else "显示内容",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val subtitle = listOfNotNull(
            entity.channelLabel?.let { "渠道：$it" },
            entity.senderKey?.takeIf { entity.source == "SMS" }?.let { "发件：$it" },
        ).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (revealed) {
            val title = entity.title
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            if (entity.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entity.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = CONTENT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (hasContent) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "内容已折叠",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "指纹 ${entity.fingerprint}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "骨架 ${entity.skeleton.ifBlank { "(空)" }}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        DecisionLine(entity)
    }
}

/** 展开后正文最多显示的行数。再长就该去通知里看了，这里只做核对。 */
private const val CONTENT_MAX_LINES = 4

@Composable
private fun DecisionLine(entity: MessageEventEntity) {
    val action = entity.action ?: return

    val (label, color) = when (action) {
        "ALLOW" -> "放行" to MaterialTheme.colorScheme.onSurfaceVariant
        "SILENT_SUPPRESS" -> "静默移除" to MaterialTheme.colorScheme.error
        "QUARANTINE" -> "隔离" to MaterialTheme.colorScheme.error
        else -> action to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val detail = buildString {
        append(label)
        entity.decidedBy?.let { append(" · ").append(it) }
        entity.category?.let { append(" · ").append(it) }
        entity.confidence?.let { append(" · ").append((it * 100).toInt()).append("%") }
        entity.latencyMs?.let { append(" · ").append(it).append("ms") }
        entity.failureReason?.let { append(" · ").append(it) }
    }

    Spacer(Modifier.height(4.dp))
    Text(text = detail, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun EmptyHint(notificationAccess: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (notificationAccess) "等待通知…" else "尚未授权通知使用权",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (notificationAccess) {
                    "授权已生效。让别的 App 发一条通知，事件会出现在这里。"
                } else {
                    "没有通知使用权，这个 App 收不到任何数据。点上面的「去授权」，在列表里勾选 JevNoiseGate。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
