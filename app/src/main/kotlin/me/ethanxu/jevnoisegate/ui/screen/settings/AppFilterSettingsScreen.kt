package me.ethanxu.jevnoisegate.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.ui.component.ExpressiveSwitch
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedItem
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.SegmentedListItem
import me.ethanxu.jevnoisegate.ui.util.rememberAppIcon

/**
 * 监听通知 App 设置。
 *
 * ## 黑名单而非白名单
 *
 * 默认全部监听，用户只关掉不想看的。白名单会让"刚装完不知道要开哪个"，
 * 开箱即不可用 —— 而这本该是装完就立刻工作的工具。
 *
 * ## 搜索为什么不做防抖
 *
 * 过滤是纯内存的（设备上约 200 个有启动入口的应用），每次按键全量扫一遍也是微秒级。
 * 防抖只会让输入感觉发黏，换来的是看不见的开销节省。
 *
 * ## 为什么用 SegmentedItem(index, count)
 *
 * 列表项是**动态生成**的，无法用 `SegmentedColumn` 的 DSL —— 后者需要调用方在编译期
 * 逐项声明。因此这里显式传入下标与总数，由 `SegmentedItem` 算出首项/末项的圆角，
 * 让整份列表看起来仍是一个连续的分段容器。
 */
@Composable
fun AppFilterSettingsScreen(viewModel: AppFilterViewModel = viewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val muted by viewModel.mutedPackages.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }

    val filtered = remember(apps, query) {
        val all = apps.orEmpty()
        if (query.isBlank()) {
            all
        } else {
            // 包名与应用名双匹配：用户既可能记得"哔哩哔哩"，也可能记得"tv.danmaku.bili"
            all.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SegmentedColumn {
                item {
                    SegmentedItemContainer {
                        Column(modifier = Modifier.padding(16.dp)) {
                            SearchInput(value = query, onValueChange = { query = it })
                            Text(
                                text = buildString {
                                    val total = apps?.size
                                    if (total == null) {
                                        append("正在加载应用列表…")
                                    } else {
                                        append("共 $total 个应用，已关闭 ${muted.size} 个")
                                        if (query.isNotBlank()) append(" · 匹配 ${filtered.size} 个")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (apps != null && filtered.isEmpty()) {
            item {
                SegmentedColumn {
                    item {
                        SegmentedItemContainer {
                            Text(
                                text = "没有匹配「$query」的应用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        if (filtered.isNotEmpty()) {
            itemsIndexed(filtered, key = { _, app -> app.packageName }) { index, app ->
                SegmentedItem(index = index, count = filtered.size) {
                    SegmentedListItem(
                        onClick = { viewModel.setMuted(app.packageName, muted = app.packageName !in muted) },
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName) },
                        leadingContent = { AppIcon(app.packageName) },
                        trailingContent = {
                            ExpressiveSwitch(
                                checked = app.packageName !in muted,
                                onCheckedChange = null,
                            )
                        },
                    )
                }
            }
        }

        item {
            SegmentedColumn(title = "说明") {
                item {
                    SegmentedItemContainer {
                        Text(
                            text = "列表只包含有启动入口的应用。没有启动入口的系统服务目前始终参与监听，" +
                                "它们既不会自己发广告，也无法在这里被关掉。\n\n" +
                                "关闭某个应用后，它的通知不会再进入判断流程，也不会出现在事件列表里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 分段容器里的搜索框，无边框无容器，直接长在容器上。 */
@Composable
private fun SearchInput(value: String, onValueChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索应用名或包名") },
        singleLine = true,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun AppIcon(packageName: String) {
    val icon = rememberAppIcon(packageName)
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
