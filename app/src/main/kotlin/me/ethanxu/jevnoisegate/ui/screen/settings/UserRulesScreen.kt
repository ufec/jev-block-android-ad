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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 用户偏好规则。
 *
 * ## 这些规则是"软约束"，不是硬开关
 *
 * 它们会作为 `state.userRules` 交给模型，让模型在判断时把你的偏好考虑进去 ——
 * 而不是写死"命中即执行"。这是刻意的：
 *
 * - 你写下"拼多多的营销推送都拦掉"，模型仍然能看到这一条是"快递已到驿站"并放行
 * - 反过来，纯程序化匹配做不到这种例外（广告里也写"验证码"）
 *
 * 代价是**模型仍可能不遵守**。如果你要的是 100% 可靠，该用「监听通知 App」——
 * 那里关掉某个 App 后，它的通知根本不会进入判断。
 *
 * ## 每条规则写一句人话
 *
 * 不需要写匹配语法。"验证码、取件码一定要放行" 比 "keyword:验证码 OR keyword:取件码"
 * 更接近你想表达的意思，模型也读得懂。
 */
@Composable
fun UserRulesScreen(viewModel: SettingsViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    SubPageScaffold(title = "用户偏好规则", onBack = { navigator.pop() }) { padding ->
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
                                text = "规则会随每条消息一起交给模型，让它知道你的偏好。\n\n" +
                                    "这是软约束：模型仍会结合消息内容权衡 —— 比如你写了" +
                                    "「拼多多的推送都拦掉」，但这条是「快递已到驿站」，它仍可能放行。" +
                                    "想彻底不听某个 App，请用「监听通知 App」。",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }

            item {
                RuleSection(
                    title = "优先放行",
                    hint = "比如：验证码、取件码、银行余额变动",
                    rules = prefs.preferAllowRules,
                    onRulesChange = viewModel::setPreferAllowRules,
                )
            }

            item {
                RuleSection(
                    title = "优先拦截",
                    hint = "比如：拼多多的营销推送、游戏体力恢复提醒",
                    rules = prefs.preferBlockRules,
                    onRulesChange = viewModel::setPreferBlockRules,
                )
            }
        }
    }
}

@Composable
private fun RuleSection(
    title: String,
    hint: String,
    rules: List<String>,
    onRulesChange: (List<String>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val full = rules.size >= MAX_RULES

    SegmentedColumn(title = title) {
        item {
            SegmentedItemContainer {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = hint,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SegmentedField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = if (full) "已达上限 $MAX_RULES 条" else "写一条规则",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            enabled = draft.isNotBlank() && !full,
                            onClick = {
                                val rule = draft.trim()
                                if (rule.isNotEmpty() && rule !in rules) {
                                    onRulesChange(rules + rule)
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

        rules.forEachIndexed { index, rule ->
            item(key = "$title-$index") {
                SegmentedItemContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = rule,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRulesChange(rules - rule) }) { Text("删除") }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                SegmentedItemContainer {
                    Text(
                        text = "还没有规则",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * 每边最多 20 条。
 *
 * 不是技术限制而是效果限制：规则越多，每条在模型注意力里的权重越低，
 * 到某个数量后加规则反而会让整体判断变差。真需要几十条时，
 * 该考虑用「监听通知 App」做粗筛，而不是把所有判断都塞给模型。
 */
private const val MAX_RULES = 20
