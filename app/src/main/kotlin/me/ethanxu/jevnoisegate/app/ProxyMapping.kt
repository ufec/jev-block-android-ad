package me.ethanxu.jevnoisegate.app

import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.typesafe.sdk.ProxyKind
import me.ethanxu.typesafe.sdk.ProxySpec

/**
 * 把应用设置里的代理配置映射成 SDK 的 [ProxySpec]；配置不全时返回 null（直连）。
 *
 * 这段映射原先在 SDK 内部的 `ProxySpec.fromSettings` 里。SDK 拆成独立仓库时把它
 * 移了出来 —— `none`/`http`/`https`/`socks5` 是本应用 DataStore 里的取值，
 * 一个要独立发布的 SDK 不该认识另一个应用的字面量。
 *
 * 界面上 HTTP 与 HTTPS 是两个选项，但底层是同一个实现：HTTP 代理对 HTTPS 目标
 * 会自动改用 `CONNECT` 隧道，不存在独立的 HTTPS 代理协议。
 *
 * **任何一项不合法都退回直连**：主机填了端口没填、或端口越界时，带着这种半配置
 * 去连只会一路超时，报错还看不出原因。直连至少能让用户在诊断页看到真实的上游错误。
 */
internal fun proxySpecOrNull(
    type: String,
    host: String,
    port: Int,
    username: String = "",
    password: String = "",
): ProxySpec? {
    if (type == AppPreferences.PROXY_NONE || host.isBlank() || port !in 1..65535) return null
    val kind = when (type) {
        AppPreferences.PROXY_HTTP, AppPreferences.PROXY_HTTPS -> ProxyKind.HTTP
        AppPreferences.PROXY_SOCKS5 -> ProxyKind.SOCKS
        else -> return null
    }
    return ProxySpec(kind, host.trim(), port, username.trim(), password)
}
