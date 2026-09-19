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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.core.decision.DefaultCategories
import me.ethanxu.jevnoisegate.core.decision.applyCategoryOverrides
import me.ethanxu.jevnoisegate.core.model.Action
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedField
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.SegmentedSliderItem
import me.ethanxu.jevnoisegate.ui.component.SegmentedSwitchItem
import me.ethanxu.jevnoisegate.ui.component.SubPageScaffold
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator
import kotlin.math.roundToInt

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

            item {
                ThresholdSection(
                    thresholds = prefs.categoryThresholds,
                    actions = prefs.categoryActions,
                    onThresholdChange = viewModel::setCategoryThreshold,
                    onActionChange = viewModel::setCategoryAction,
                    onReset = viewModel::resetCategoryOverrides,
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
 * 判定门槛与动作。
 *
 * ## 为什么和上面两张卡片分开放
 *
 * 「优先放行 / 优先拦截」是**告诉模型**用户的偏好 —— 软约束，模型有权不采纳。
 * 这里则是**我们自己**在模型给出判断之后执行的规则 —— 硬参数，够门槛就一定生效。
 * 两者的可靠性完全不同，混在一起会让人以为写了规则就一定会拦；
 * 实际上规则只改变模型怎么想，门槛才决定我们怎么做。
 *
 * ## 为什么放行类不可调
 *
 * 放行类的门槛是个死参数：置信度低于门槛走降级放行，高于门槛执行该类别动作，
 * 而它的动作本来就是放行，两条路结果一致。列出来是为了让用户看到全貌，
 * 置灰是为了不让人以为改了有用。
 */
@Composable
private fun ThresholdSection(
    thresholds: Map<String, Float>,
    actions: Map<String, String>,
    onThresholdChange: (String, Float) -> Unit,
    onActionChange: (String, String) -> Unit,
    onReset: () -> Unit,
) {
    // 出厂配置叠加用户覆盖 = 当前**真正生效**的那一份。
    // 用与判断链同一个函数算（applyCategoryOverrides），
    // 否则界面显示的位置和实际执行的门槛可能是两回事 —— 那种分歧极难排查。
    val categories = remember(thresholds, actions) {
        applyCategoryOverrides(DefaultCategories.all(), thresholds, actions)
    }
    val overridden = thresholds.isNotEmpty() || actions.isNotEmpty()

    SegmentedColumn(title = "判定门槛") {
        item {
            SegmentedItemContainer {
                Text(
                    text = "模型给出类别后，置信度要达到这个数才会动手；低于它就退回放行。\n\n" +
                        "这是「宁可放过还是宁可拦住」的取舍 —— 调低更容易拦住，" +
                        "也更容易误伤。注意它只能拦住模型判为噪音的消息：" +
                        "模型坚持说「正常」的，这里调到多少都不会拦。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        categories.forEach { config ->
            item(key = "threshold-${config.name}") {
                val isAllowOnly = config.action == Action.ALLOW
                SegmentedItemContainer {
                    Column {
                        ThresholdItem(
                            name = config.name,
                            threshold = config.minConfidence,
                            isAllowOnly = isAllowOnly,
                            onChange = onThresholdChange,
                        )
                        if (!isAllowOnly) {
                            ActionItem(
                                name = config.name,
                                action = config.action,
                                onChange = onActionChange,
                            )
                        }
                    }
                }
            }
        }

        if (overridden) {
            item {
                SegmentedItemContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onReset) { Text("恢复出厂门槛与动作") }
                    }
                }
            }
        }
    }
}

/**
 * 单个类别的门槛滑块。
 *
 * 拖动中的百分比只放在本地 state 里，松手才提交（原因见 `SegmentedSliderItem`）。
 * `remember` 以传入的门槛为 key：外部把它改了（比如上面那个「恢复出厂」），
 * 滑块要跟着回到真实值，而不是停在用户上次拖到的位置。
 */
@Composable
private fun ThresholdItem(
    name: String,
    threshold: Float,
    isAllowOnly: Boolean,
    onChange: (String, Float) -> Unit,
) {
    var percent by remember(name, threshold) {
        mutableIntStateOf((threshold * 100).roundToInt())
    }

    SegmentedSliderItem(
        title = name,
        percent = percent,
        enabled = !isAllowOnly,
        summary = if (isAllowOnly) "放行类：门槛调到多少都不会拦" else null,
        onPercentChange = { percent = it },
        onPercentChangeFinished = { onChange(name, percent / 100f) },
    )
}

/**
 * 单个类别的处置方式。
 *
 * 两个选项用开关表达而不是两个单选：它们本就是对立的两极
 * （留着能捞回来 / 直接抹掉），且开关省一半高度 —— 这一页已经够长了。
 */
@Composable
private fun ActionItem(
    name: String,
    action: Action,
    onChange: (String, String) -> Unit,
) {
    SegmentedSwitchItem(
        title = "静默移除",
        summary = "开启后直接撤掉通知，不留在隔离区（也就捞不回来）",
        checked = action == Action.SILENT_SUPPRESS,
        onCheckedChange = { silent ->
            onChange(
                name,
                if (silent) Action.SILENT_SUPPRESS.name else Action.QUARANTINE.name,
            )
        },
    )
}

/**
 * 每边最多 20 条。
 *
 * 不是技术限制而是效果限制：规则越多，每条在模型注意力里的权重越低，
 * 到某个数量后加规则反而会让整体判断变差。真需要几十条时，
 * 该考虑用「监听通知 App」做粗筛，而不是把所有判断都塞给模型。
 */
private const val MAX_RULES = 20
