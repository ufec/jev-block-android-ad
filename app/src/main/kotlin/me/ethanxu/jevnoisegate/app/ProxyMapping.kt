package me.ethanxu.jevnoisegate.app

import me.ethanxu.jevnoisegate.core.data.settings.AppPreferences
import me.ethanxu.typesafe.sdk.ProxyKind
import me.ethanxu.typesafe.sdk.ProxySpec

/**
 * 把应用设置里的代理配置映射成 SDK 的 [ProxySpec]；配置不全或地址不合法时返回 null（直连）。
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
 *
 * 注意这里收的是整个 [AppPreferences] 而不是五个参数。之前 `BackendModule` 调的是
 * 五参数版本、却只传了前三个，用户名和密码被静默丢掉，需要认证的代理因此永远 407。
 * 收整个偏好对象，这个漏传从结构上就不可能再发生。
 */
internal fun proxySpecOrNull(prefs: AppPreferences): ProxySpec? =
    proxySpecOrNull(
        type = prefs.proxyType,
        host = prefs.proxyHost,
        port = prefs.proxyPort,
        username = prefs.proxyUsername,
        password = prefs.proxyPassword,
    )

/**
 * 同上，但接收拆开的各项。
 *
 * 保留这个重载是给「用表单当前值临时构造」的场景用的：用户改完还没重启，
 * 用已保存的值去测等于在测旧配置。
 */
internal fun proxySpecOrNull(
    type: String,
    host: String,
    port: Int,
    username: String = "",
    password: String = "",
): ProxySpec? {
    if (type == AppPreferences.PROXY_NONE || host.isBlank() || port !in 1..65535) return null
    // 地址非法一律不成 spec，理由同上面的「任何一项不合法都退回直连」。
    if (proxyHostError(host) != null) return null
    val kind = when (type) {
        AppPreferences.PROXY_HTTP, AppPreferences.PROXY_HTTPS -> ProxyKind.HTTP
        AppPreferences.PROXY_SOCKS5 -> ProxyKind.SOCKS
        else -> return null
    }
    return ProxySpec(kind, host.trim(), port, username.trim(), password)
}

/**
 * **真正生效**的代理：配置完整、地址合法，并且这组配置已经测试通过。
 *
 * 这是全应用唯一的取用入口，`BackendModule` 与 JevAPI 页的连接测试都走它。
 * 两处走同一个函数，是为了保证「测试时用的配置」与「真实请求用的配置」
 * 是同一个东西 —— 否则测试通过也说明不了什么。
 *
 * 「测试通过」的判据是 [AppPreferences.isProxyVerified]，即偏好里存的指纹
 * 与当前配置算出来的一致；任何一项改动都会让它自动失效。
 */
internal fun verifiedProxySpecOrNull(prefs: AppPreferences): ProxySpec? =
    if (prefs.isProxyVerified) proxySpecOrNull(prefs) else null
