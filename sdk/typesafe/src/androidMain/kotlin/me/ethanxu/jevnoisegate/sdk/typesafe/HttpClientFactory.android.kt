package me.ethanxu.jevnoisegate.sdk.typesafe

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Credentials

internal actual fun defaultHttpClient(timeoutMs: Long, proxy: ProxySpec?): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
        if (proxy != null) {
            engine {
                // Ktor 3.x 用 ProxyBuilder 工厂而非链式 Builder：
                // HTTP 走 Url，SOCKS 走 host/port。
                //
                // HTTP 代理对 HTTPS 目标会自动改用 CONNECT 隧道，因此
                // "HTTP 代理"与"HTTPS 代理"是同一份配置（见 ProxySpec 注释）。
                this.proxy = when (proxy.kind) {
                    ProxyKind.HTTP -> ProxyBuilder.http("http://${proxy.host}:${proxy.port}")
                    ProxyKind.SOCKS -> ProxyBuilder.socks(proxy.host, proxy.port)
                }

                if (proxy.hasCredentials) {
                    config {
                        // Ktor 的 ProxyConfig 不带凭据（在 JVM 上它就是 java.net.Proxy），
                        // 所以认证只能下沉到 OkHttp：代理返回 407 时补上 Proxy-Authorization 重试。
                        proxyAuthenticator { _, response ->
                            // 已经有该头说明重试过一轮仍被拒 —— 再重试就是死循环。
                            if (response.request.header("Proxy-Authorization") != null) {
                                null
                            } else {
                                response.request.newBuilder()
                                    .header(
                                        "Proxy-Authorization",
                                        Credentials.basic(proxy.username, proxy.password),
                                    )
                                    .build()
                            }
                        }
                    }
                }
            }
        }
    }
