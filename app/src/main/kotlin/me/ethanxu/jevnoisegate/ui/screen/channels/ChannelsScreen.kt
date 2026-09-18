package me.ethanxu.jevnoisegate.ui.screen.channels

import me.ethanxu.jevnoisegate.ui.util.rememberAppIcon
import me.ethanxu.jevnoisegate.ui.util.rememberAppLabel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
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
import me.ethanxu.jevnoisegate.ui.component.TonalCard as Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.MainViewModel
import me.ethanxu.jevnoisegate.core.data.ChannelCount

@Composable
fun ChannelsScreen(viewModel: MainViewModel = viewModel()) {
    val channels by viewModel.topChannels.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ChannelsHeader(channels.size) }

        if (channels.isEmpty()) {
            item { EmptyChannels() }
        } else {
            item { ChannelsCard(channels) }
        }
    }
}

@Composable
private fun ChannelsHeader(count: Int) {
    Column {
        Text("噪音源排行", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "发广告的 App 是稳定的少数几个渠道 —— 这份榜单就是渠道级降噪的候选名单。" +
                "当前共 $count 个活跃渠道。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyChannels() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("还没有数据", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "授权通知使用权并等待一段时间后，这里会按事件数排出各个渠道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelsCard(channels: List<ChannelCount>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            channels.forEachIndexed { index, channel ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ChannelRow(rank = index + 1, channel = channel)
            }
        }
    }
}

@Composable
private fun ChannelRow(rank: Int, channel: ChannelCount) {
    val icon = rememberAppIcon(channel.packageName)
    val label = rememberAppLabel(channel.packageName)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )

        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 解析不到应用名时退回包名，而不是显示空白或 "未知应用" ——
                // 包名本身也是有效信息。
                text = label ?: channel.packageName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = channel.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            channel.channelId?.let { channelId ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "渠道 $channelId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(12.dp))
        Text(channel.eventCount.toString(), style = MaterialTheme.typography.titleMedium)
    }
}
