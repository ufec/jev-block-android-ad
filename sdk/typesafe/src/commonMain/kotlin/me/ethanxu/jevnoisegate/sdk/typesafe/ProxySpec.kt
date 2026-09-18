package me.ethanxu.jevnoisegate.sdk.typesafe

/**
 * 代理类型。
 *
 * 只有两种底层实现，这与用户在设置里看到的三个选项不是一回事：
 *
 * - [HTTP]：HTTP 代理。**HTTPS 目标也走它** —— 通过 `CONNECT` 建立隧道，
 *   这是 HTTP 代理的标准行为，不存在独立的"HTTPS 代理"协议。
 * - [SOCKS]：SOCKS5 代理。
 *
 * 界面上把 HTTP 与 HTTPS 分开列出只是为了对齐常见叫法，
 * 两者映射到同一个实现，详见 [ProxySpec.fromSettings]。
 */
enum class ProxyKind {
    HTTP,
    SOCKS,
}

/** 一组可用的代理配置。为 null 表示直连。 */
data class ProxySpec(
    val kind: ProxyKind,
    val host: String,
    val port: Int,
    /** 代理认证用户名；为空表示代理不需要认证。 */
    val username: String = "",
    val password: String = "",
) {
    val hasCredentials: Boolean get() = username.isNotBlank()

    companion object {
        /**
         * 从设置值构造。任何一项不合法都返回 null（直连）。
         *
         * **宁可直连也不要半配置的代理**：主机填了端口没填、或端口越界时，
         * 带着这种配置去连只会一路超时，报错还看不出原因。
         * 直连至少能让用户在诊断页看到真实的上游错误。
         */
        fun fromSettings(
            type: String,
            host: String,
            port: Int,
            username: String = "",
            password: String = "",
        ): ProxySpec? {
            if (type == PROXY_NONE || host.isBlank() || port !in 1..65535) return null
            val kind = when (type) {
                PROXY_HTTP, PROXY_HTTPS -> ProxyKind.HTTP
                PROXY_SOCKS5 -> ProxyKind.SOCKS
                else -> return null
            }
            return ProxySpec(kind, host.trim(), port, username.trim(), password)
        }
    }
}

// 与 :core:data 的 AppPreferences 常量保持一致。
// SDK 不依赖 core:data（它是可独立发布的产物），因此这里重复声明字面量，
// 由下方 fromSettings 的 when 兜底未知取值 —— 拼错只会退化为直连，不会崩。
private const val PROXY_NONE = "none"
private const val PROXY_HTTP = "http"
private const val PROXY_HTTPS = "https"
private const val PROXY_SOCKS5 = "socks5"
