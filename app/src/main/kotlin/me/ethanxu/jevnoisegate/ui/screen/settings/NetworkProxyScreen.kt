package me.ethanxu.jevnoisegate.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.ethanxu.jevnoisegate.app.DownloadProxySettings
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedRadioItem
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator

@Composable
fun NetworkProxyScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val initial = remember { DownloadProxySettings.load(context) }
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var error by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("配置应用的网络代理。当前联网仅用于下载模型，消息分类在本机完成。",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            SegmentedColumn(title = "连接方式") {
                listOf("SYSTEM" to "跟随系统 / VPN", "DIRECT" to "直连", "HTTP" to "HTTP", "SOCKS" to "SOCKS5").forEach { (value, label) ->
                    item(key = value) {
                        SegmentedRadioItem(title = label, selected = type == value,
                            onClick = { type = value; error = "" })
                    }
                }
            }
        }
        if (type == "HTTP" || type == "SOCKS") {
            item {
                OutlinedTextField(value = host, onValueChange = { host = it; error = "" },
                    label = { Text("主机 / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(value = port, onValueChange = { port = it; error = "" },
                    label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
            item {
                Text("支持无认证代理。使用电脑代理时，需允许局域网访问并填写电脑局域网 IP；127.0.0.1 指手机自身。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("保存后在下次下载或继续下载时生效，正在进行的下载保持原连接。", style = MaterialTheme.typography.bodySmall) }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item {
            Button(onClick = {
                try {
                    DownloadProxySettings(type, host, port).save(context)
                    navigator.pop()
                } catch (failure: IllegalArgumentException) { error = failure.message.orEmpty() }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        }
    }
}
