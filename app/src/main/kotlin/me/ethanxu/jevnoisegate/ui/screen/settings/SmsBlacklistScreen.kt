package me.ethanxu.jevnoisegate.ui.screen.settings

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedField
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.SubPageScaffold
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator

/**
 * 短信号码黑名单。
 *
 * ## 与「用户偏好规则」的区别（页面上必须写清）
 *
 * 那些是**软约束**：注入 state 交给模型权衡，模型可能不遵守。
 * 这里是**硬规则**：命中即拦截，**根本不调用模型**。
 *
 * 用户填的是精确号码，"这个号码的短信我不要"没有需要权衡的余地，
 * 因此既更可靠，也省下一次网络往返。
 *
 * ## 拦截的真实边界
 *
 * 短信本体**不会被删除** —— 只有系统默认短信应用有权限删短信，本应用不是，
 * 也不打算是（那意味着接管全部短信通知，漏一条验证码的代价远大于收益）。
 *
 * 我们能做的是**撤销短信 App 弹出的那条通知**，让它不出现在状态栏。
 * 短信仍留在收件箱与对话列表里。
 */
@Composable
fun SmsBlacklistScreen(viewModel: SettingsViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val rules = prefs.smsSenderBlacklist
    val full = rules.size >= MAX_ENTRIES

    SubPageScaffold(title = "短信号码黑名单", onBack = { navigator.pop() }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SegmentedColumn(title = "说明") {
                    item {
                        SegmentedItemContainer {
                            Text(
                                text = "命中黑名单的短信不会调用模型，直接拦截 —— " +
                                    "这是硬规则，比「用户偏好规则」可靠。\n\n" +
                                    "拦截的效果是撤销这条短信的通知，让它不弹出来。" +
                                    "短信本体仍保留在收件箱里 —— 只有系统默认短信应用才有权限删除短信，" +
                                    "本应用不是它。\n\n" +
                                    "匹配时会忽略空格、横杠与 +86，所以 138 0013 8000、" +
                                    "+8613800138000 与 13800138000 视作同一号码。\n\n" +
                                    "也可以只填前几位，例如 1069 —— 用于 106 开头的服务号端口，它们很长且不固定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "号码") {
                    item {
                        SegmentedItemContainer {
                            Column(modifier = Modifier.padding(16.dp)) {
                                SegmentedField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    label = if (full) "已达上限 $MAX_ENTRIES 条" else "输入号码或前几位",
                                    keyboardType = KeyboardType.Phone,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        enabled = draft.isNotBlank() && !full,
                                        onClick = {
                                            val entry = draft.trim()
                                            if (entry.isNotEmpty() && entry !in rules) {
                                                viewModel.setSmsSenderBlacklist(rules + entry)
                                                draft = ""
                                            }
                                        },
                                    ) {
                                        Text("添加")
                                    }
                                }
                            }
                        }
                    }

                    rules.forEachIndexed { index, entry ->
                        item(key = "rule-$index") {
                            SegmentedItemContainer {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            viewModel.setSmsSenderBlacklist(rules - entry)
                                        },
                                    ) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }

                    if (rules.isEmpty()) {
                        item {
                            SegmentedItemContainer {
                                Text(
                                    text = "还没有号码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "三种写法都会命中：完全相同、填前几位（1069 命中 1069030012345678）、" +
                        "带不带国家码。少于 4 位不参与匹配，避免误伤一大片。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 上限 100 条。号码不像自然语言规则那样消耗模型注意力，可以放宽。 */
private const val MAX_ENTRIES = 100
