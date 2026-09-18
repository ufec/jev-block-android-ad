package me.ethanxu.jevnoisegate.sdk.typesafe

/** 默认 API 根地址。对应上游 `DEFAULT_BASE_URL`。 */
const val DEFAULT_BASE_URL: String = "https://api.typesafe.ai"

/** 默认模型。对应上游 `DEFAULT_MODEL`。 */
const val DEFAULT_MODEL: String = "jev-latest"

/**
 * Kotlin SDK 版本。上游对应版本为 `@typesafe-ai/sdk@0.6.0`（MIT）。
 *
 * 归属与授权说明见模块根目录的 NOTICE 与 LICENSE。
 */
const val SDK_VERSION: String = "0.1.0"

internal const val SDK_USER_AGENT: String = "typesafe-sdk-kotlin/$SDK_VERSION"

/**
 * 默认运行时描述，随请求上报。
 *
 * `commonMain` 里读不到平台版本号（`System.getProperty` 是 JVM 专有），因此这里保持中性。
 * 调用方（app 模块）应通过 [TypeSafeConfig.runtimeDescriptor] 传入
 * 形如 `android/<Build.VERSION.RELEASE>` 的值，以获得更有用的遥测。
 */
internal const val DEFAULT_RUNTIME_DESCRIPTOR: String = "kotlin/unknown"

/**
 * 客户端配置。
 *
 * 与上游的一处差异：上游会从 `process.env` 读取 `TYPESAFE_API_KEY` 等变量，
 * Android 上没有这个概念，因此这里**只接受显式传入**，不隐式读环境。
 *
 * @throws TypeSafeException `apiKey` 为空，或 `timeoutMs` 非正数。
 */
data class TypeSafeConfig(
    /** 必填。上游会在缺失时回退到环境变量，本 SDK 不回退。 */
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
    /** 低于该级别的日志被丢弃。 */
    val logLevel: LogLevel = DEFAULT_LOG_LEVEL,
    /** 日志接收器；默认静默。 */
    val logger: TypeSafeLogger = NoOpLogger,
    val retry: RetryPolicy = RetryPolicy(),
    /** 单次尝试的超时（毫秒），不含重试总预算。 */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 附加请求头。与 SDK 自身请求头冲突时以 SDK 为准（对齐上游行为）。 */
    val defaultHeaders: Map<String, String> = emptyMap(),
    val runtimeDescriptor: String = DEFAULT_RUNTIME_DESCRIPTOR,
    /** 网络代理；null 表示直连。 */
    val proxy: ProxySpec? = null,
) {
    init {
        if (apiKey.isBlank()) {
            throw TypeSafeException(
                "No API key was provided. Pass `apiKey` to TypeSafeConfig.",
            )
        }
        if (timeoutMs <= 0) {
            throw TypeSafeException("TypeSafeConfig.timeoutMs must be positive, got $timeoutMs.")
        }
    }

    /** 去掉尾部斜杠，避免与 path 拼接出双斜杠。对应上游 `stripTrailingSlashes`。 */
    internal val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}
