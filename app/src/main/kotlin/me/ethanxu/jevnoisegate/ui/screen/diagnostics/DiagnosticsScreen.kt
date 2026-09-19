package me.ethanxu.jevnoisegate.ui.screen.diagnostics

import me.ethanxu.jevnoisegate.app.SettingsViewModel
import androidx.compose.material3.Switch
import me.ethanxu.jevnoisegate.feature.notification.TestNotificationSender
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import me.ethanxu.jevnoisegate.ui.component.TonalCard as Card
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator
import me.ethanxu.jevnoisegate.ui.navigation.Route
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.LatencyStats
import me.ethanxu.jevnoisegate.app.MainViewModel
import me.ethanxu.jevnoisegate.core.data.ActionCount

@Composable
fun DiagnosticsScreen(
    viewModel: MainViewModel = viewModel(),
    settings: SettingsViewModel = viewModel(),
) {
    val navigator = LocalNavigator.current
    val prefs by settings.preferences.collectAsStateWithLifecycle()
    val devMode = prefs.developerMode
    val latency by viewModel.latencyStats.collectAsStateWithLifecycle()
    val actionCounts by viewModel.actionCounts.collectAsStateWithLifecycle()
    val uploaded by viewModel.uploadedCount.collectAsStateWithLifecycle()
    val total by viewModel.totalCount.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { LatencyCard(latency) }
        item { ActionCard(actionCounts) }
        item { PrivacyCard(uploaded = uploaded, total = total) }
        item { LogEntryCard(onClick = { navigator.push(Route.Logs) }) }
        item { DeveloperModeCard(checked = devMode, onCheckedChange = settings::setDeveloperMode) }
        // 自测工具只在开发者模式下出现 —— 它会往统计里写入自产数据，
        // 不该让普通用户误触。
        if (devMode) {
            item { SelfTestCard() }
        }
    }
}

@Composable
private fun LatencyCard(latency: LatencyStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("模型往返延迟", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "P95 才是决定体验的那个数 —— 用户感知到的是尾部延迟，而不是平均值。" +
                    "它同时也是「网络往返能否跑赢通知震动」的直接答案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (latency.count == 0) {
                Text(
                    "尚无判断记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Metric("P50", latency.p50)
                    Metric("P95", latency.p95)
                    Metric("最大", latency.max)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "样本 ${latency.count} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: Long) {
    Column {
        Text("$value", style = MaterialTheme.typography.headlineSmall)
        Text(
            "$label (ms)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionCard(actionCounts: List<ActionCount>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("动作分布", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (actionCounts.isEmpty()) {
                Text(
                    "尚无判断记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                actionCounts.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = when (row.action) {
                                "ALLOW" -> "放行"
                                "SILENT_SUPPRESS" -> "静默移除"
                                "QUARANTINE" -> "隔离"
                                else -> row.action
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(row.eventCount.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * 运行日志入口。
 *
 * 放在诊断页是因为它是**排查工具**而不是设置 —— 用户遇到"通知没被处理"时
 * 会先来这里看统计，再顺着进日志。
 */
@Composable
private fun LogEntryCard(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("运行日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "查看通知监听、短信与 HTTP 请求的运行日志，可记录等级与筛选条件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 开发者模式开关。
 *
 * 开启后同时做两件事：诊断页出现「自测」工具；本应用自己的通知按普通通知处理。
 * 这两件事必须绑在一起 —— 自测通知要验证完整链路，就必须不被"排除自家通知"的规则挡掉。
 */
@Composable
private fun DeveloperModeCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("开发者模式", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "开启后：出现自测工具；且本应用自己的通知按普通通知处理。" +
                            "注意自测产生的数据会进入统计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
    }
}

/**
 * 自测：发一条通知走完整链路。
 *
 * 内置样张覆盖两条已知路径，缺一不可 —— 只验证"能拦广告"是不够的，
 * 还得确认没有误拦验证码，那才是这个应用最不能犯的错。
 *
 * 自定义输入补的是样张覆盖不到的那一段：真实误判长什么样只有用户手里有。
 * 调提示词、改闸门阈值、收到一条判错的通知时，把它原样粘进来就能复现。
 */
@Composable
private fun SelfTestCard() {
    val context = LocalContext.current
    // 卡片处在 LazyColumn 里，滚出屏幕就会被回收 —— 用 saveable 记住，
    // 否则粘贴到一半去翻上面对照，回来就空了。
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("自测", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "发一条通知走完整链路。几秒后看它是否还在通知栏里 —— " +
                    "广告样张应被撤销，验证码样张应保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题（可留空）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("正文") },
                placeholder = { Text("把收到的那条原样粘进来") },
                minLines = 3,
                maxLines = 6,
            )
            Spacer(Modifier.height(12.dp))
            // 正文是判定与指纹的唯一依据，空正文发出去只会污染统计。
            Button(
                onClick = { TestNotificationSender.send(context, title, body) },
                enabled = body.isNotBlank(),
            ) {
                Text("发送自定义通知")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "内置样张",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        TestNotificationSender.send(context, TestNotificationSender.Sample.AD)
                    },
                ) {
                    Text("广告样张")
                }
                OutlinedButton(
                    onClick = {
                        TestNotificationSender.send(context, TestNotificationSender.Sample.OTP)
                    },
                ) {
                    Text("验证码样张")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "提示：需先配好 API Key，否则判断会降级放行，样张与自定义内容都不会被拦。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PrivacyCard(uploaded: Int, total: Int) {    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("隐私核验", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "验证码类消息会被本地闸门拦下，永不上送 —— 它的 uploaded 始终为 0。" +
                    "这个数字让隐私承诺可被核验，而不是只写在文档里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("已上送 API", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("$uploaded 条", style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("本地拦截未上送", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(total - uploaded).coerceAtLeast(0)} 条", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
