package me.ethanxu.jevnoisegate.app

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy

/** Applies only to model downloads; SYSTEM preserves Android/VPN routing. */
data class DownloadProxySettings(val type: String = "SYSTEM", val host: String = "", val port: String = "") {
    fun toProxy(): Proxy? = when (type) {
        "SYSTEM" -> null
        "DIRECT" -> Proxy.NO_PROXY
        "HTTP", "SOCKS" -> {
            require(host.isNotBlank() && !host.contains("://")) { "请填写代理主机或 IP，不含协议前缀" }
            val number = port.toIntOrNull()
            require(number != null && number in 1..65535) { "代理端口需为 1–65535" }
            Proxy(if (type == "HTTP") Proxy.Type.HTTP else Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(host.trim(), number))
        }
        else -> error("未知代理类型")
    }

    fun save(context: Context) {
        toProxy()
        context.getSharedPreferences("model_download", 0).edit()
            .putString("proxy_type", type).putString("proxy_host", host.trim()).putString("proxy_port", port).apply()
    }

    companion object {
        fun load(context: Context): DownloadProxySettings {
            val prefs = context.getSharedPreferences("model_download", 0)
            return DownloadProxySettings(prefs.getString("proxy_type", "SYSTEM")!!,
                prefs.getString("proxy_host", "")!!, prefs.getString("proxy_port", "")!!)
        }
    }
}
