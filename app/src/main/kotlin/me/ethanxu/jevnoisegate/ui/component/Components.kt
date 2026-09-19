package me.ethanxu.jevnoisegate.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 界面组件层。
 *
 * 全部基于**官方稳定的 Material 3**，不含任何 alpha / 实验性 API。
 *
 * 命名上保留了 `Segmented*` / `Expressive*` 这类历史名字，是为了让调用方（各屏幕）
 * 不必改动 —— 之前那套从参考实现移植的组件依赖 material3 1.5-alpha，
 * 已经按需求整体回退。名字与实际实现的对应关系在这里集中说明，
 * 后续可以再做一次纯改名。
 */

// ---------------------------------------------------------------------------
// 基础容器
// ---------------------------------------------------------------------------

/** 分组卡片。[title] 为空时不渲染标题行。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 色调卡片。 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(content = content)
    }
}

/** 页面脚手架。 */
@Composable
fun ExpressiveScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
fun expressiveTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
)

/** 顶栏返回按钮。 */
@Composable
fun TopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = contentDescription)
    }
}

/** 二级页脚手架：自带返回按钮的顶栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TopBarBackButton(onClick = onBack, contentDescription = "返回")
                },
                colors = expressiveTopAppBarColors(),
            )
        },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 设置分组：一张卡片 + 分隔线
// ---------------------------------------------------------------------------

@DslMarker
annotation class SegmentedColumnDsl

class SegmentedColumnScope {
    internal data class Entry(val key: Any?, val content: @Composable () -> Unit)

    internal val entries = mutableListOf<Entry>()

    fun item(key: Any? = null, visible: Boolean = true, content: @Composable () -> Unit) {
        if (visible) entries.add(Entry(key ?: entries.size, content))
    }
}

/**
 * 设置分组。
 *
 * 官方做法就是**一张卡片 + 项间分隔线**，不再有分段圆角那套。
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    content: SegmentedColumnScope.() -> Unit,
) {
    val entries = SegmentedColumnScope().apply(content).entries
    if (entries.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column {
                entries.forEachIndexed { index, entry ->
                    entry.content()
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 静态列表版本。 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    content: List<@Composable () -> Unit>,
) {
    SegmentedColumn(modifier = modifier, title = title) {
        content.forEach { item { it() } }
    }
}

/** 单个分段项容器，用于列表里动态生成的场景。 */
@Composable
fun SegmentedItemContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/** 动态列表里的定位占位。官方样式无分段圆角，这里不需要额外处理。 */
@Composable
fun SegmentedItem(index: Int, count: Int, content: @Composable () -> Unit) {
    content()
}

// ---------------------------------------------------------------------------
// 设置项
// ---------------------------------------------------------------------------

@Composable
private fun ItemRow(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    leadingIcon: ImageVector?,
    title: String,
    summary: String?,
    summaryColor: Color,
    onClickLabel: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel) { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(24.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = summaryColor)
            }
        }
        trailing()
    }
}

/** 开关项。整行可点，触控目标更大；开关自身不再单独响应，避免重复触发。 */
@Composable
fun SegmentedSwitchItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ItemRow(
        modifier = Modifier,
        onClick = { onCheckedChange(!checked) },
        leadingIcon = icon,
        title = title,
        summary = summary,
        summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClickLabel = if (checked) "关闭 $title" else "开启 $title",
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** 跳转项。 */
@Composable
fun SegmentedArrowItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    summaryIsWarning: Boolean = false,
    onClick: () -> Unit,
) {
    ItemRow(
        modifier = Modifier,
        onClick = onClick,
        leadingIcon = icon,
        title = title,
        summary = summary,
        summaryColor = if (summaryIsWarning) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClickLabel = title,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 单选项。 */
@Composable
fun SegmentedRadioItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ItemRow(
        modifier = Modifier,
        onClick = onClick,
        leadingIcon = icon,
        title = title,
        summary = summary,
        summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClickLabel = title,
        trailing = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** 多选项。 */
@Composable
fun SegmentedCheckboxItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ItemRow(
        modifier = Modifier,
        onClick = { onCheckedChange(!checked) },
        leadingIcon = icon,
        title = title,
        summary = summary,
        summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClickLabel = title,
        trailing = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** 自定义内容的行（如图标 + 双行文字 + 任意尾部）。 */
@Composable
fun SegmentedListItem(
    onClick: (() -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = null) { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            headlineContent()
            supportingContent?.invoke()
        }
        trailingContent?.invoke()
    }
}

/** 兼容旧调用：开关行。 */
@Composable
fun AppIconSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: @Composable (() -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        headlineContent = { Text(title, maxLines = 1) },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        leadingContent = leading,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** 开关样式：官方 Switch 即为最终形态。 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

// ---------------------------------------------------------------------------
// 底部导航
// ---------------------------------------------------------------------------

/** 底栏一个 tab。 */
data class TabItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/** 底部导航栏。 */
@Composable
fun MainBottomBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    androidx.compose.material3.NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(item.label) },
            )
        }
    }
}

/** 主页的四个 tab。 */
val MainTabs: List<TabItem> = listOf(
    TabItem(
        label = "事件",
        selectedIcon = Icons.Filled.Notifications,
        unselectedIcon = Icons.Outlined.Notifications,
    ),
    TabItem(
        label = "渠道",
        selectedIcon = AppIcons.Filled.Leaderboard,
        unselectedIcon = AppIcons.Outlined.Leaderboard,
    ),
    TabItem(
        label = "诊断",
        selectedIcon = AppIcons.Filled.Speed,
        unselectedIcon = AppIcons.Outlined.Speed,
    ),
    TabItem(
        label = "设置",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)

/**
 * 分段容器里的输入框。
 *
 * 容器与指示线都透明，让输入框直接"长"在分段卡片上，而不是再嵌一个自己的边框 ——
 * 否则会出现两层容器叠在一起的割裂感。文字色不覆盖，交给主题决定：
 * 硬编码黑白会在自定义主题色下失去对比度。
 */
@Composable
fun SegmentedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    SegmentedItemContainer {
        androidx.compose.material3.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            label = { Text(label) },
            singleLine = true,
            visualTransformation = visualTransformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboardType,
            ),
            trailingIcon = trailingIcon,
            colors = transparentFieldColors(),
        )
    }
}

/** 见 [SegmentedField] 的说明。 */
@Composable
internal fun transparentFieldColors() = androidx.compose.material3.TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/** 搜索框。 */
@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        singleLine = true,
    )
}
