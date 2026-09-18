package me.ethanxu.jevnoisegate.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.SegmentedRadioItem
import me.ethanxu.jevnoisegate.ui.theme.ColorMode
import me.ethanxu.jevnoisegate.ui.theme.DYNAMIC_COLOR
import me.ethanxu.jevnoisegate.ui.theme.keyColorOptions

/**
 * 主题设置。
 *
 * 刻意**不暴露** materialKolor 的 `PaletteStyle` 与 `ColorSpec`：那是给专业调参的维度，
 * 普通用户既看不懂也调不出好看的配色。固定 TonalSpot + SPEC_2025（Material 官方推荐组合），
 * 把自由度收在"明暗模式 + 主题色"这两个真正有感知的维度上。
 */
@Composable
fun ThemeSettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val currentMode = ColorMode.fromValue(prefs.colorMode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SegmentedColumn(title = "明暗模式") {
                ColorMode.entries.forEach { mode ->
                    item(key = mode.name) {
                        SegmentedRadioItem(
                            title = mode.displayName(),
                            summary = mode.hint(),
                            selected = mode == currentMode,
                            onClick = { viewModel.setColorMode(mode.value) },
                        )
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = "主题色") {
                item {
                    SegmentedItemContainer {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "选择任意颜色会覆盖壁纸取色。选「跟随系统」则取系统壁纸的主题色。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SegmentedItemContainer {
                        ColorSwatches(
                            selected = prefs.keyColor,
                            onSelect = viewModel::setKeyColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatches(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        DynamicOption(selected = selected, onSelect = onSelect)

        keyColorOptions.chunked(8).forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowColors.forEach { argb ->
                    Swatch(
                        color = Color(argb),
                        isSelected = argb == selected,
                        onClick = { onSelect(argb) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DynamicOption(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSelect(DYNAMIC_COLOR) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (selected == DYNAMIC_COLOR) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = "跟随系统",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun Swatch(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun ColorMode.hint(): String = when (this) {
    ColorMode.SYSTEM, ColorMode.LIGHT, ColorMode.DARK -> "使用应用内置配色"
    ColorMode.MONET_SYSTEM, ColorMode.MONET_LIGHT, ColorMode.MONET_DARK ->
        "从系统壁纸取色（Android 12+）"
    ColorMode.DARK_AMOLED -> "纯黑背景，OLED 屏更省电"
}
