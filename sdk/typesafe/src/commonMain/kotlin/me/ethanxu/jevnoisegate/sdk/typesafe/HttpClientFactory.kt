package me.ethanxu.jevnoisegate.sdk.typesafe

import io.ktor.client.HttpClient

/**
 * 构造默认的 [HttpClient]。
 *
 * 声明为 `expect` 是因为 HTTP 引擎是平台相关的：Android/JVM 用 OkHttp 引擎，
 * 而 `commonMain` 里不能引用它。测试通过构造函数注入 Ktor `MockEngine`，
 * 因此不会走到这里。
 *
 * 实现必须设置 `expectSuccess = false` —— 让 4xx/5xx 以正常响应的形式返回，
 * 由 [TypeSafeClient] 自行分派异常类型，才能还原上游的状态码 → 异常映射。
 *
 * [proxy] 为 null 时直连。
 */
internal expect fun defaultHttpClient(timeoutMs: Long, proxy: ProxySpec?): HttpClient
