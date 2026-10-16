package me.ethanxu.jevnoisegate.app

import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DownloadProxyTest {
    @Test fun httpDownloadUsesConfiguredProxyWithoutResolvingOrigin() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        first
                    }
                }
                val proxy = DownloadProxySettings("HTTP", "127.0.0.1", server.localPort.toString()).toProxy()!!
                val connection = URL("http://origin.invalid/model").openConnection(proxy)
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                assertEquals("ok", connection.getInputStream().bufferedReader().use { it.readText() })
                assertEquals("GET http://origin.invalid/model HTTP/1.1", request.get(5, TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
}
