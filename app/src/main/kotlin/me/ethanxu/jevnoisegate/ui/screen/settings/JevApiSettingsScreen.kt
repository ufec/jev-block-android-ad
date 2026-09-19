package me.ethanxu.jevnoisegate.ui.screen.settings

import me.ethanxu.typesafe.sdk.ModelCard
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import me.ethanxu.jevnoisegate.app.SettingsViewModel
import me.ethanxu.jevnoisegate.ui.component.AppIcons
import me.ethanxu.jevnoisegate.app.proxyStatus
import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.jevnoisegate.ui.component.SegmentedColumn
import me.ethanxu.jevnoisegate.ui.component.SegmentedField
import me.ethanxu.jevnoisegate.ui.component.SegmentedItemContainer
import me.ethanxu.jevnoisegate.ui.component.transparentFieldColors
import me.ethanxu.jevnoisegate.ui.component.SegmentedRadioItem

/**
 * JevAPI 设置。
 *
 * ## 为什么用本地草稿而不是直接双向绑定
 *
 * `SettingsViewModel.preferences` 的初始值是占位默认值（空 key、默认 URL），
 * DataStore 的真实读数要等一次磁盘 IO。若直接把 `prefs.apiKey` 作为输入框的值，
 * 首次组合会把空串当成草稿，随后到达的真实值又被"用户已编辑"的语义挡住 ——
 * 结果是已配置的 key 显示为空。
 *
 * 因此这里显式等待第一次真实发射再初始化草稿，之后由草稿驱动输入、逐次落盘。
 */
@Composable
fun JevApiSettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val test by viewModel.connectionTest.collectAsStateWithLifecycle()

    var initialized by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(prefs) {
        if (!initialized) {
            apiKey = prefs.apiKey
            baseUrl = prefs.baseUrl
            model = prefs.model
            timeout = prefs.timeoutMs.toString()
            initialized = true
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
                            Text(
                                text = if (prefs.isApiConfigured) "已配置" else "未配置",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (prefs.isApiConfigured) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                text = if (prefs.isApiConfigured) {
                                    "判断会走真实模型，可在「诊断」页看到分类结果与往返延迟。"
                                } else {
                                    "没有 API Key 时每条消息都会因鉴权失败而降级放行 —— " +
                                        "这是刻意的 fail-open：宁可漏放，不可误拦。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            SegmentedColumn(title = "凭据") {
                item {
                    SegmentedField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            viewModel.setApiKey(it.trim())
                        },
                        label = "API Key",
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) {
                                        AppIcons.Filled.VisibilityOff
                                    } else {
                                        AppIcons.Filled.Visibility
                                    },
                                    contentDescription = if (keyVisible) "隐藏" else "显示",
                                )
                            }
                        },
                    )
                }
            }
        }

        item {
            SegmentedColumn(title = "端点") {
                item {
                    SegmentedField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            viewModel.setBaseUrl(it.trim())
                        },
                        label = "Base URL",
                    )
                }
                item {
                    SegmentedModelField(
                        value = model,
                        onValueChange = {
                            model = it
                            viewModel.setModel(it.trim())
                        },
                        models = test.models,
                    )
                }
                item {
                    SegmentedField(
                        value = timeout,
                        onValueChange = { input ->
                            // 只接受数字：非法输入直接忽略，避免把异常值写进设置。
                            val digits = input.filter { it.isDigit() }.take(6)
                            timeout = digits
                            digits.toLongOrNull()?.takeIf { it > 0 }?.let(viewModel::setTimeoutMs)
                        },
                        label = "超时（毫秒）",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }

                item {
                    SegmentedItemContainer {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("出网链路", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = proxyStatus(prefs).detail,
                                style = MaterialTheme.typography.bodySmall,
                                // 警告场景变多了：配了但没验证过也用警告色 ——
                                // 用户以为在用代理、实际是直连，这是最容易被忽略的一种。
                                color = if (proxyStatus(prefs).isWarning) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "只读。修改请到「设置 → 网络代理」。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                    // 代理不用传：本页的测试取「生效中」的代理配置，
                                    // 也就是真实请求会走的那一份。代理的表单值要去
                                    // 「网络代理」页测，两页各答一个问题。
                                    viewModel.testApiConnection(
                                        apiKey = apiKey,
                                        baseUrl = baseUrl,
                                    )
                                },
                                enabled = !test.running,
                            ) {
                                Text(if (test.running) "测试中…" else "测试连接并获取模型")
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
                            text = "这一步用表单里的 API Key 与 Base URL 临时建立连接，" +
                                "因此测的是你刚填的凭据，不需要先保存。\n\n" +
                                "代理不取表单值，取的是「生效中」的那一份 —— 也就是真实判断会走的那条路。" +
                                "所以这里连接失败时，先看上面的出网链路：代理若还没验证，实际走的是直连。" +
                                "代理的验证在「网络代理」页完成。\n\n" +
                                "请求的是 GET /v1/models —— 它同时也是模型列表接口，" +
                                "所以一次请求既验证了连通性，又把可选模型取回来。\n\n" +
                                "修改配置后需要重启应用才会对实际判断生效。",
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
                            text = "默认 Base URL 为 ${AppPreferences.DEFAULT_BASE_URL}，" +
                                "模型 ${AppPreferences.DEFAULT_MODEL}，" +
                                "超时 ${AppPreferences.DEFAULT_TIMEOUT_MS} 毫秒。\n\n" +
                                "超时是单次请求的时限。SDK 默认还会重试 2 次，" +
                                "因此最坏情况下一条通知会占用约三倍的时间加退避。",
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

/**
 * 「模型」字段。
 *
 * 拉取到模型列表后变成**可输入的下拉框**：点开从列表选，也允许继续手输 ——
 * 列表接口可能因鉴权或权限问题拿不到全部模型，硬锁成只读会把用户堵死。
 *
 * 没测过连接时退化为普通输入框，不额外占位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedModelField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<ModelCard>,
) {
    if (models.isEmpty()) {
        SegmentedField(
            value = value,
            onValueChange = onValueChange,
            // 不写"上方/下方"这类位置词：布局一调整文案就错了。
            // 直接引用按钮名，怎么排版都不会失真。
            label = "模型（可点「测试连接并获取模型」拉取可选列表）",
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    SegmentedItemContainer {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                label = { Text("模型") },
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = transparentFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                models.forEach { card ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(card.name)
                                if (card.description.isNotBlank()) {
                                    Text(
                                        text = card.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(card.name)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

