package me.ethanxu.jevnoisegate.core.data.settings

/**
 * 全部用户设置。
 *
 * 刻意用**原始类型**（String / Int / Set）而不是 UI 层的枚举：`:core:data` 不该反向依赖
 * `:app`。枚举的解析放在 UI 层，存储层只负责如实保存。
 */
data class AppPreferences(
    /** [me.ethanxu.jevnoisegate.ui.UiMode] 的值，默认 Miuix。 */
    val uiMode: String = DEFAULT_UI_MODE,
    /** [me.ethanxu.jevnoisegate.ui.theme.ColorMode] 的序号，默认跟随系统。 */
    val colorMode: Int = DEFAULT_COLOR_MODE,
    /** 主题色 ARGB；0 表示跟随系统动态取色。 */
    val keyColor: Int = DEFAULT_KEY_COLOR,

    // --- JevAPI ---
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,

    // --- 采集过滤 ---
    /**
     * 被用户关闭监听的包名集合（黑名单）。
     *
     * 采用黑名单而非白名单模型：默认全监听，用户只关掉不想看的。
     * 白名单会让"装完什么都不知道该开哪个"，开箱不可用。
     */
    val mutedPackages: Set<String> = emptySet(),

    // --- 网络代理 ---
    /**
     * 代理类型，取值见本文件底部的 `PROXY_*` 常量。
     *
     * 有些网络环境无法直连 Base URL（企业内网、部分运营商链路），
     * 因此代理是可用性问题而非可选项。
     */
    val proxyType: String = DEFAULT_PROXY_TYPE,

    /** 代理主机。留空表示不启用代理，即使 [proxyType] 不是 none。 */
    val proxyHost: String = DEFAULT_PROXY_HOST,

    /** 代理端口。0 表示未设置。 */
    val proxyPort: Int = DEFAULT_PROXY_PORT,

    /** 代理认证用户名。留空表示代理不需要认证。 */
    val proxyUsername: String = "",

    /**
     * 代理认证密码。
     *
     * 与 API key 一样以明文存在应用私有 DataStore 中 —— 本应用没有引入密钥库
     * （Keystore）加解密的复杂度。这个取舍写在设置页说明里，不隐瞒。
     */
    val proxyPassword: String = "",

    // --- 用户偏好规则 ---
    /**
     * 用户声明的"应当放行"偏好，每条一句话。
     *
     * 与 [mutedPackages] 的区别：后者是"完全不处理某个 App"（事件不入库），
     * 这里则是"照常处理，但把偏好告诉模型"。因此两者语义不同，不能互相替代。
     */
    val preferAllowRules: List<String> = emptyList(),

    /** 用户声明的"应当拦截"偏好。 */
    val preferBlockRules: List<String> = emptyList(),

    // --- 短信号码黑名单 ---
    /**
     * 短信发件人号码黑名单（**硬规则**）。
     *
     * 与 [preferBlockRules] 的区别：后者是注入 state 交给模型权衡的软约束，
     * 这个命中即拦截、根本不调用模型。用户填的是精确标识，没有需要权衡的余地。
     */
    val smsSenderBlacklist: List<String> = emptyList(),

    // --- 开发者模式 ---
    /**
     * 开发者模式。
     *
     * 开启后：诊断页展示「自测」工具；且**本应用自己发出的通知按普通通知处理**
     * （正常情况下自家通知会被排除，以免自产数据污染统计）。
     *
     * 这两件事必须绑在同一个开关上：自测通知要能验证完整链路，就必须被当作普通通知；
     * 而既然开了开发者模式，用户已经知道自己在看调试数据，统计被污染是可接受的代价。
     */
    val developerMode: Boolean = false,

    // --- 运行日志 ---
    /**
     * 最小记录等级；[DEFAULT_LOG_LEVEL]。取 `LogLevel` 的数值，
     * [me.ethanxu.jevnoisegate.core.common.log.AppLog.OFF]（100）表示完全关闭。
     *
     * 存原始 Int 而非枚举，理由同本文件顶部说明。
     */
    val logLevel: Int = DEFAULT_LOG_LEVEL,
) {
    /** key 是否已配置。用于界面上提示"未配置"而不是静默失败。 */
    val isApiConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 代理是否已配置齐全。
     *
     * 主机与端口**都**要有才算配置完成 —— 只填其一多半是还没写完，
     * 这时应当直连而不是带着半配置去连代理然后超时。
     */
    val isProxyConfigured: Boolean
        get() = proxyType != PROXY_NONE && proxyHost.isNotBlank() && proxyPort in 1..65535

    companion object {
        const val DEFAULT_UI_MODE: String = "miuix"
        const val DEFAULT_COLOR_MODE: Int = 0
        const val DEFAULT_KEY_COLOR: Int = 0
        const val DEFAULT_BASE_URL: String = "https://api.typesafe.ai"
        const val DEFAULT_MODEL: String = "jev-latest"
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L

        /** 默认 INFO：日志功能开箱可见，又不至于把 VERBOSE 的噪音全记下来。 */
        const val DEFAULT_LOG_LEVEL: Int = 2

        const val PROXY_NONE: String = "none"
        const val PROXY_HTTP: String = "http"
        const val PROXY_HTTPS: String = "https"
        const val PROXY_SOCKS5: String = "socks5"

        const val DEFAULT_PROXY_TYPE: String = PROXY_NONE
        const val DEFAULT_PROXY_HOST: String = ""
        const val DEFAULT_PROXY_PORT: Int = 0

        /** 界面上可选的全部代理类型，顺序即显示顺序。 */
        val PROXY_TYPES: List<String> = listOf(PROXY_NONE, PROXY_HTTP, PROXY_HTTPS, PROXY_SOCKS5)
    }
}
