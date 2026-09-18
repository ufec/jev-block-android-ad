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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedField
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.SubPageScaffold
import me.ethanxu.jevnoisegate.ui.navigation.LocalNavigator

/**
 * 网络代理设置。
 *
 * 单独成页而不是塞在 JevAPI 设置里：它们是**两类不同的配置** ——
 * 一个是服务凭据（我是谁），一个是链路（怎么出去）。
 * 有些网络环境直连不到 Base URL（企业内网、部分运营商链路），
 * 这时用户会专门来找"网络"相关的设置，而不是去 API 配置页翻。
 *
 * 测试按钮**用本页表单的当前值**去连，API 凭据取已保存的值 ——
 * 每页测各自负责的那部分，改完不用先保存就能验证。
 */
@Composable
fun ProxySettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val test by viewModel.connectionTest.collectAsStateWithLifecycle()

    var initialized by remember { mutableStateOf(false) }
    var proxyType by remember { mutableStateOf(AppPreferences.PROXY_NONE) }
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("") }
    var proxyUsername by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }

    LaunchedEffect(prefs) {
        if (!initialized) {
            proxyType = prefs.proxyType
            proxyHost = prefs.proxyHost
            proxyPort = if (prefs.proxyPort > 0) prefs.proxyPort.toString() else ""
            proxyUsername = prefs.proxyUsername
            proxyPassword = prefs.proxyPassword
            initialized = true
        }
    }

    val enabled = proxyType != AppPreferences.PROXY_NONE

    SubPageScaffold(title = "网络代理", onBack = { navigator.pop() }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SegmentedColumn(title = "代理类型") {
                    item {
                        SegmentedItemContainer {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                PROXY_OPTIONS.forEach { (value, label) ->
                                    FilterChip(
                                        selected = proxyType == value,
                                        onClick = {
                                            proxyType = value
                                            viewModel.setProxyType(value)
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 选「不使用」时整块消失 —— 留着只会让人以为需要填。
            if (enabled) {
                item {
                    SegmentedColumn(title = "代理地址") {
                        item {
                            SegmentedField(
                                value = proxyHost,
                                onValueChange = {
                                    proxyHost = it
                                    viewModel.setProxyHost(it.trim())
                                },
                                label = "主机",
                            )
                        }
                        item {
                            SegmentedField(
                                value = proxyPort,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(5)
                                    proxyPort = digits
                                    digits.toIntOrNull()
                                        ?.takeIf { it in 1..65535 }
                                        ?.let(viewModel::setProxyPort)
                                },
                                label = "端口",
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }
                }

                item {
                    SegmentedColumn(title = "认证（代理不需要认证则留空）") {
                        item {
                            SegmentedField(
                                value = proxyUsername,
                                onValueChange = {
                                    proxyUsername = it
                                    viewModel.setProxyUsername(it.trim())
                                },
                                label = "用户名",
                            )
                        }
                        // 没有用户名就没有密码可言，因此密码字段只在填了用户名后出现。
                        if (proxyUsername.isNotBlank()) {
                            item {
                                SegmentedField(
                                    value = proxyPassword,
                                    onValueChange = {
                                        proxyPassword = it
                                        viewModel.setProxyPassword(it)
                                    },
                                    label = "密码",
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                            }
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "连接测试") {
                    item {
                        SegmentedItemContainer {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.testConnection(
                                            apiKey = prefs.apiKey,
                                            baseUrl = prefs.baseUrl,
                                            proxyType = proxyType,
                                            proxyHost = proxyHost,
                                            proxyPort = proxyPort.toIntOrNull() ?: 0,
                                            proxyUsername = proxyUsername,
                                            proxyPassword = proxyPassword,
                                        )
                                    },
                                    enabled = !test.running,
                                ) {
                                    Text(if (test.running) "测试中…" else "测试代理连通性")
                                }
                                test.message?.let { message ->
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (test.success == true) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SegmentedItemContainer {
                            Text(
                                text = "测试请求的是 Base URL 下的 GET /v1/models —— " +
                                    "没有专门用于探测的轻量端点，而模型列表本来就是需要拉的，" +
                                    "因此一次请求既验证了链路又把模型取回来。\n\n" +
                                    "用的是本页表单的当前值，不需要先保存。API 凭据取已保存的值，" +
                                    "所以请先在「JevAPI 设置」里填好 Key。\n\n" +
                                    "修改后需重启应用才会对实际判断生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "说明") {
                    item {
                        SegmentedItemContainer {
                            Text(
                                text = "HTTP 与 HTTPS 在实现上是同一种代理 —— HTTPS 目标通过 HTTP 代理的 " +
                                    "CONNECT 隧道建立连接，并不存在独立的 HTTPS 代理协议。" +
                                    "分开列出只是为了对齐常见叫法。\n\n" +
                                    "主机与端口必须都填齐才会启用；只填其一时按直连处理 —— " +
                                    "带着半配置的代理去连只会一路超时，且看不出原因。",
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
}

private val PROXY_OPTIONS: List<Pair<String, String>> = listOf(
    AppPreferences.PROXY_NONE to "不使用",
    AppPreferences.PROXY_HTTP to "HTTP",
    AppPreferences.PROXY_HTTPS to "HTTPS",
    AppPreferences.PROXY_SOCKS5 to "SOCKS5",
)
