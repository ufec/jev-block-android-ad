package me.ethanxu.jevnoisegate.app

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ethanxu.typesafe.sdk.ProxyKind
import me.ethanxu.typesafe.sdk.ProxySpec
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 代理连通性探测。
 *
 * ## 为什么不复用 SDK 的 `TypeSafeClient`
 *
 * 两个原因，都是实测出来的：
 *
 * 1. **它带重试。** `RetryPolicy` 默认 `maxRetries = 2` 且 `apiConnectionError = true`，
 *    于是测一个被黑洞掉的代理地址要等三次连接超时加两段退避才报错 ——
 *    默认超时下是三十秒量级。用户点一下"测试"然后等半分钟，只会以为是卡死了。
 * 2. **它测的不是代理。** 那个接口同时验证 API Key，401 和"代理不通"会混在一起。
 *    本页只回答一个问题：这条链路能不能通到上游。凭据是 JevAPI 页的事。
 *
 * ## 与 SDK 的重复
 *
 * 下面的代理装配（`Proxy` 映射 + 凭据应答）在 `HttpClientFactory.kt` 里有一份对应的实现，
 * **那边是事实来源**（真实的判断请求走那条路）。改动任何一边时两边都要看。
 *
 * 两份都受同一个 OkHttp 行为影响：CONNECT 之前会先被"抢先"问一次凭据。SDK 那份只判
 * "请求头里有没有 `Proxy-Authorization`"，抢先那次自然没有，于是顺手把凭据带上了，
 * 行为正确；这份因为还要拿它当 407 探针，才需要额外把抢先那次排除掉。
 *
 * SOCKS5 的处境两边也一样：SDK 的 `ProxyBuilder.socks(host, port)` 签名里就没有凭据参数，
 * 所以两条路径都不支持 SOCKS5 认证 —— 界面上对此有明确提示，而不是假装支持。
 */
internal sealed interface ProxyProbeResult {
    /** 拿到了上游的 HTTP 响应，隧道是通的。状态码本身不参与判定。 */
    data class Reachable(val status: Int, val elapsedMs: Long) : ProxyProbeResult

    /**
     * HTTP 407：**代理本体**拒绝了我们，这不是"链路通"。
     *
     * 把 407 单列一类是这份代码存在的主要理由之一：需要认证的代理回 407 时，
     * 从"拿到了 HTTP 响应"的角度看是通的，于是很自然会被判成测试通过 ——
     * 但真实请求同样会被拒，代理等于没生效。
     *
     * 注意 407 在 HTTPS 目标上**不会**以响应形式出现：OkHttp 在 CONNECT 阶段就把它
     * 转成了异常。判定见 [buildProbeClient] 里那个标记 —— 并且必须排除 OkHttp 自己的
     * "抢先认证"询问，它的 code 也是 407。
     */
    data class ProxyAuthRequired(val credentialsSupplied: Boolean) : ProxyProbeResult

    /** 没拿到可用的响应。可能是传输层没通，也可能是代理拒绝了 CONNECT（非 407）。 */
    data class Failed(val reason: String) : ProxyProbeResult
}

/**
 * 经 [spec] 请求 `GET {baseUrl}/v1/models`（[spec] 为 null 即直连）。
 *
 * 不带 `Authorization`：本函数只测链路。整个调用的耗时上限就是 [timeoutMs] ——
 * 连接、读写、整体四项超时都设成它，并且关掉 OkHttp 自己的重试，
 * 这样"测试中…"这个状态永远不会无限期挂着。
 */
internal suspend fun probeProxy(
    baseUrl: String,
    spec: ProxySpec?,
    timeoutMs: Long,
): ProxyProbeResult = withContext(Dispatchers.IO) {
    // 代理回 407 时 OkHttp 不会把响应交给我们 —— 它在 CONNECT 阶段就调用
    // proxyAuthenticator，认证器返回 null 便直接抛 IOException("Failed to authenticate
    // with proxy")。判定"代理是否真的要求认证"因此只能靠认证器，而不是看响应码；
    // 而且认证器**每次都会被抢先调用一次**，所以还得把那次区分出去。
    val challenged = AtomicBoolean(false)
    val client = buildProbeClient(spec, timeoutMs, challenged)
    val request = Request.Builder()
        .url(baseUrl.trimEnd('/') + MODELS_PATH)
        .header("User-Agent", "JevNoiseGate/${BuildConfig.VERSION_NAME} (proxy probe)")
        .get()
        .build()

    val startedAt = System.currentTimeMillis()
    try {
        client.newCall(request).execute().use { response ->
            val elapsed = System.currentTimeMillis() - startedAt
            // 这条分支只有明文 http:// 的 baseUrl 才走得到：那时不走 CONNECT 隧道，
            // 407 会作为一个普通响应回来。默认的 https 目标永远落进下面的 catch。
            if (response.code == HTTP_PROXY_AUTH_REQUIRED) {
                ProxyProbeResult.ProxyAuthRequired(credentialsSupplied = spec?.hasCredentials == true)
            } else {
                ProxyProbeResult.Reachable(status = response.code, elapsedMs = elapsed)
            }
        }
    } catch (e: IOException) {
        if (challenged.get()) {
            ProxyProbeResult.ProxyAuthRequired(credentialsSupplied = spec?.hasCredentials == true)
        } else {
            ProxyProbeResult.Failed(describe(e, timeoutMs))
        }
    } finally {
        // 每次探测单独建客户端，用完把连接池清掉。调度器的空闲线程会自行回收，
        // 不额外 shutdown：那需要碰 Dispatcher 的实现细节，收益不值这个耦合。
        client.connectionPool.evictAll()
    }
}

private const val MODELS_PATH: String = "/v1/models"
private const val HTTP_PROXY_AUTH_REQUIRED = 407

private fun buildProbeClient(
    spec: ProxySpec?,
    timeoutMs: Long,
    challenged: AtomicBoolean,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        // OkHttp 默认会在连接失败时换个路由重试。这里是探测，不是业务请求：
        // 要的是立刻拿到失败结论，而不是替用户多试几次。
        .retryOnConnectionFailure(false)
        // 3xx 也算"拿到了响应"，没必要再走一趟。
        .followRedirects(false)

    if (spec != null) {
        // createUnresolved：代理主机名的解析放到请求里去做，让解析失败落进上面那个
        // catch，而不是在这里抛到调用方。
        val address = InetSocketAddress.createUnresolved(spec.host, spec.port)
        builder.proxy(
            when (spec.kind) {
                ProxyKind.HTTP -> Proxy(Proxy.Type.HTTP, address)
                ProxyKind.SOCKS -> Proxy(Proxy.Type.SOCKS, address)
            },
        )

        // 认证器**始终**安装，即使没填凭据：它同时充当"代理是否真的要求认证"的探针。
        // 只在有凭据时才真的作答，否则返回 null 让 OkHttp 照常失败。
        // 只装在 HTTP 代理上：OkHttp 的 proxyAuthenticator 只在 CONNECT 的 407 上起作用，
        // 而 407 是 HTTP 代理的概念，SOCKS5 没有 —— 这个 kind 判断不是偷懒，
        // 是在描述真实能力边界。
        if (spec.kind == ProxyKind.HTTP) {
            builder.proxyAuthenticator { _, response ->
                // OkHttp 在发 CONNECT **之前**会先调一次认证器，并塞一个它自己合成的
                // 407 进来问"要不要预先带上凭据"（顺带省掉一个来回）。那个响应不是代理的
                // 拒绝，必须排除掉，否则每一次探测 —— 连不上、超时、成功 —— 都会走进
                // 407 分支，"代理要求认证"会变成一个永远为真的谎言。
                //
                // 判据取自 OkHttp 5 `RealRoutePlanner.createTunnelRequest`：合成响应带
                // `Proxy-Authenticate: OkHttp-Preemptive`，message 是 "Preemptive Authenticate"，
                // 且收发时间戳都是 -1。三个都判一遍，任何一个成立即认定是合成的那种 ——
                // 命中其一就足够，重复是为了不把判断押在单个字符串上。
                if (!response.isPreemptiveChallenge()) {
                    challenged.set(true)
                }

                if (spec.hasCredentials && response.request.header(PROXY_AUTHORIZATION) == null) {
                    response.request.newBuilder()
                        .header(PROXY_AUTHORIZATION, Credentials.basic(spec.username, spec.password))
                        .build()
                } else {
                    // 没凭据可答，或者已经答过一次又被拒 —— 再答就是死循环。
                    null
                }
            }
        }
    }

    return builder.build()
}

/**
 * 是不是 OkHttp 自己合成的"抢先认证"询问，而不是代理真的回了 407。
 *
 * 两者不容易分辨：合成响应的 `code` 也是 407。不做区分的话，探测的任何失败
 * 都会被报成「代理要求认证」，而真正的原因（连不上、超时）就丢了 ——
 * 实测确认过这一点，不是理论担忧。
 */
private fun Response.isPreemptiveChallenge(): Boolean =
    header(OKHTTP_PREEMPTIVE_HEADER) == OKHTTP_PREEMPTIVE_MARKER ||
        message == OKHTTP_PREEMPTIVE_MESSAGE ||
        sentRequestAtMillis == -1L

private const val OKHTTP_PREEMPTIVE_HEADER = "Proxy-Authenticate"
private const val OKHTTP_PREEMPTIVE_MARKER = "OkHttp-Preemptive"
private const val OKHTTP_PREEMPTIVE_MESSAGE = "Preemptive Authenticate"

private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

/**
 * 把底层异常翻成一句用户能据此行动的话。
 *
 * 顺序有讲究：`SocketTimeoutException` 是 `InterruptedIOException` 的子类，
 * 必须先判，否则读超时会被后者接走、报成"整体限时"。
 */
private fun describe(error: IOException, timeoutMs: Long): String = when (error) {
    is SocketTimeoutException -> "读写超时（${timeoutMs} 毫秒）"
    is InterruptedIOException -> "超过限时 ${timeoutMs} 毫秒仍未完成"
    is UnknownHostException -> "主机名解析不了：${error.message ?: "未知主机"}"
    is ConnectException -> error.message ?: "连接被拒绝"
    else -> error.message ?: error::class.java.simpleName
}
