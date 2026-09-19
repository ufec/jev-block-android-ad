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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.app.proxyAddressProblem
import me.ethanxu.jevnoisegate.app.proxyHostError
import me.ethanxu.jevnoisegate.app.proxyPortError
import me.ethanxu.jevnoisegate.app.proxyStatus
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
 *
 * ## 表单是草稿，保存才是提交
 *
 * 这一页与其它设置页不同：**改字段不会立刻写进全局配置**。改完点「保存」，
 * 保存会先拿这组值去测链路，通过了才写入，不通过则全局配置一个字都不动。
 *
 * 这么做是因为"生效"与"测过"本来就该是同一件事。前一版是边打字边写库，
 * 于是不得不额外引入"验证"这个概念去追着配置跑：用户改完字段，配置已经生效了，
 * 只是没验证过；然后要靠界面文案去解释"当前按直连处理"。现在这层解释连同它
 * 需要的那些状态一起没了 —— 写入路径只认"测过的配置"，问题不存在。
 *
 * 失败时不写入也是有意的：用户手上那套能用的配置，不该因为一次改错的尝试而被破坏。
 */
@Composable
fun ProxySettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val save by viewModel.proxySave.collectAsStateWithLifecycle()

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
    val hostError = proxyHostError(proxyHost)
    val portError = proxyPortError(proxyPort)
    // 选「不使用」时没有地址可校验 —— 那一栏是隐藏的，也不该拿它去拦保存。
    val addressProblem = if (enabled) proxyAddressProblem(proxyHost, proxyPort) else null
    val status = proxyStatus(prefs)
    // 草稿与已保存的那组不一致 —— 也就是"界面上看到的还没生效"。
    val dirty = proxyType != prefs.proxyType ||
        proxyHost.trim() != prefs.proxyHost ||
        (proxyPort.toIntOrNull() ?: 0) != prefs.proxyPort ||
        proxyUsername.trim() != prefs.proxyUsername ||
        proxyPassword != prefs.proxyPassword
    // SOCKS5 的凭据填了也没用：SDK 的 ProxyBuilder.socks(host, port) 收不下用户名密码。
    val socksWithCredentials =
        proxyType == AppPreferences.PROXY_SOCKS5 && proxyUsername.isNotBlank()

    // 退出会丢掉东西的两种情形，都拦一下。第二种不是"防止误操作"而是**防止静默失败**：
    // 保存跑在 viewModelScope 里，页面一退作用域就没了，这次保存会被取消且不留下任何提示。
    val blocker = when {
        save.running -> BackBlocker.SAVING
        dirty -> BackBlocker.UNSAVED
        else -> null
    }
    var pendingBlocker by remember { mutableStateOf<BackBlocker?>(null) }

    // 顶栏返回按钮与系统返回手势走同一条路，否则两者行为会不一致。
    val leave: () -> Unit = {
        if (blocker == null) navigator.pop() else pendingBlocker = blocker
    }
    BackHandler(enabled = blocker != null) { leave() }

    SubPageScaffold(title = "网络代理", onBack = leave) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SegmentedColumn(title = "当前生效") {
                    item {
                        SegmentedItemContainer {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = status.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (status.isWarning) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                // 草稿不等于已保存时必须说出来，否则用户会以为改完就生效了。
                                if (dirty) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "有未保存的改动，保存成功后才会生效",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
                                        onClick = { proxyType = value },
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
                            // 主机与端口同行。分两行时它们看起来像两个独立的设置，
                            // 实际上是一个地址的两半，缺一不可。
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                // Top 而不是 CenterVertically：任一栏出现错误文案时，
                                // 两栏的输入框仍保持同一水平线。
                                verticalAlignment = Alignment.Top,
                            ) {
                                SegmentedField(
                                    value = proxyHost,
                                    onValueChange = { proxyHost = it },
                                    label = "主机",
                                    modifier = Modifier.weight(1f),
                                    isError = hostError != null,
                                    supportingText = hostError,
                                )
                                SegmentedField(
                                    value = proxyPort,
                                    onValueChange = { input ->
                                        proxyPort = input.filter { it.isDigit() }.take(5)
                                    },
                                    label = "端口",
                                    modifier = Modifier.width(120.dp),
                                    keyboardType = KeyboardType.Number,
                                    isError = portError != null,
                                    supportingText = portError,
                                )
                            }
                        }
                    }
                }

                item {
                    SegmentedColumn(title = "认证（代理不需要认证则留空）") {
                        item {
                            SegmentedField(
                                value = proxyUsername,
                                onValueChange = { input ->
                                    proxyUsername = input
                                    // 用户名空着，密码就永远用不上（hasCredentials 看的是用户名），
                                    // 而密码栏这时又是隐藏的 —— 留着一个既用不到、又没有入口能删的
                                    // 凭据，是纯粹的负担。所以跟着一起清掉。
                                    if (input.isBlank()) proxyPassword = ""
                                },
                                label = "用户名",
                            )
                        }
                        // 没有用户名就没有密码可言，因此密码字段只在填了用户名后出现。
                        if (proxyUsername.isNotBlank()) {
                            item {
                                SegmentedField(
                                    value = proxyPassword,
                                    onValueChange = { proxyPassword = it },
                                    label = "密码",
                                    visualTransformation = PasswordVisualTransformation(),
                                    supportingText = if (socksWithCredentials) {
                                        "SOCKS5 不支持用户名/密码认证，这里填了不起作用"
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "保存") {
                    item {
                        SegmentedItemContainer {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.saveProxyConfig(
                                            baseUrl = prefs.baseUrl,
                                            proxyType = proxyType,
                                            proxyHost = proxyHost,
                                            proxyPort = proxyPort.toIntOrNull() ?: 0,
                                            proxyUsername = proxyUsername,
                                            proxyPassword = proxyPassword,
                                        )
                                    },
                                    enabled = !save.running && addressProblem == null,
                                ) {
                                    Text(if (save.running) "保存中…" else "保存")
                                }

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (enabled) {
                                        "保存会先测这条链路，通过才写入配置；不通过则什么都不改。"
                                    } else {
                                        "不使用代理时没有链路可验，点击直接保存。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // 按钮禁用时必须说明为什么，否则"点不动"比"点了报错"更让人困惑。
                                if (addressProblem != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "还不能保存：$addressProblem",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                save.message?.let { message ->
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (save.saved == true) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SegmentedColumn(title = "说明") {
                    item {
                        SegmentedItemContainer {
                            Text(
                                text = "改动要点「保存」才生效。这一页与其它设置页不同 —— " +
                                    "字段只是草稿，不保存就什么都不会变。\n\n" +
                                    "保存会先测这条链路，通过才写入；不通过则完全不写入，" +
                                    "应用仍按原来那套配置走。这样一次改错的尝试不会破坏手上能用的配置。\n\n" +
                                    "保存成功后需要重启应用才对实际判断生效 —— " +
                                    "HTTP 客户端在启动时按当时的配置构建，之后不会重建。\n\n" +
                                    "地址格式：主机填 IP 或域名（192.168.31.1、proxy.corp.internal、::1），" +
                                    "端口填 1–65535。这里不查 DNS —— 域名能不能解析要等真正连接才知道，" +
                                    "在设置页解析一次既慢，又可能因为当前网络不通而误报。\n\n" +
                                    "探测能证明什么：它能证明经这个代理可以拿到上游的响应。" +
                                    "它不能证明流量确实经过了代理 —— 那需要对比直连与经代理时的出口 IP，" +
                                    "而本应用不为此引入第三方服务。\n\n" +
                                    "HTTP 与 HTTPS 是同一种代理：HTTPS 目标通过 HTTP 代理的 " +
                                    "CONNECT 隧道建立连接，并不存在独立的 HTTPS 代理协议。" +
                                    "分开列出只是为了对齐常见叫法。\n\n" +
                                    "SOCKS5 不支持用户名/密码认证，这与 SDK 的能力边界一致 —— " +
                                    "它的 SOCKS 入口只有主机和端口两个参数。\n\n" +
                                    "密码以明文保存在应用私有的 DataStore 里，与 API Key 同样处理，" +
                                    "本应用没有引入密钥库加解密。设备已 root、或有人能读取应用私有目录时，" +
                                    "这个密码就是可读的，请据此决定要不要在代理上使用重要口令。",
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

    pendingBlocker?.let { reason ->
        DiscardChangesDialog(
            reason = reason,
            onLeaving = {
                pendingBlocker = null
                navigator.pop()
            },
            onStay = { pendingBlocker = null },
        )
    }
}

private val PROXY_OPTIONS: List<Pair<String, String>> = listOf(
    AppPreferences.PROXY_NONE to "不使用",
    AppPreferences.PROXY_HTTP to "HTTP",
    AppPreferences.PROXY_HTTPS to "HTTPS",
    AppPreferences.PROXY_SOCKS5 to "SOCKS5",
)

/** 现在退出会丢的东西。 */
private enum class BackBlocker { UNSAVED, SAVING }

/**
 * 退出确认。
 *
 * 两个分支都**允许用户坚持退出** —— 拦住不给走是另一种糟糕，用户可能就是想放弃。
 * 这里只负责把"会发生什么"说清楚。
 */
@Composable
private fun DiscardChangesDialog(reason: BackBlocker, onLeaving: () -> Unit, onStay: () -> Unit) {
    val saving = reason == BackBlocker.SAVING
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(if (saving) "保存还没结束" else "放弃未保存的改动？") },
        text = {
            Text(
                if (saving) {
                    "现在退出会取消这次保存，配置仍然是原来那一组。"
                } else {
                    "这组改动还没保存，退出后会丢失。应用仍按当前生效的配置走。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onLeaving) {
                Text(
                    text = if (saving) "仍然退出" else "放弃改动",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onStay) {
                Text(if (saving) "继续等待" else "继续编辑")
            }
        },
    )
}
