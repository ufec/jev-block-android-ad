package me.ethanxu.jevnoisegate.app

import org.junit.Assert.*
import org.junit.Test
import me.ethanxu.typesafe.sdk.TypeSafeConfig

class ClientLeasePoolTest {
    @Test fun `SDK configuration defaults compare equal so repeated requests reuse the client`() {
        val pool = ClientLeasePool<TypeSafeConfig, Client> { Client() }
        val first = pool.acquire(TypeSafeConfig(apiKey = "test-key"))
        val second = pool.acquire(TypeSafeConfig(apiKey = "test-key"))
        assertSame(first.client, second.client)
        first.close()
        second.close()
        pool.invalidate()
        assertEquals(1, first.client.closes)
    }

    private class Client : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun `same configuration shares clients and changed configuration waits for all users`() {
        var creations = 0
        val pool = ClientLeasePool<String, Client> { creations++; Client() }
        val a = pool.acquire("proxy-A")
        val b = pool.acquire("proxy-A")
        assertSame(a.client, b.client)
        a.close()
        val c = pool.acquire("proxy-B")
        assertNotSame(b.client, c.client)
        assertEquals(0, b.client.closes)
        b.close()
        b.close()
        assertEquals(1, b.client.closes)
        c.close()
        assertEquals(0, c.client.closes)
        assertEquals(2, creations)
        pool.invalidate()
        assertEquals(1, c.client.closes)
    }

    @Test fun `cancelled request releases its lease and failed reconfiguration preserves current client`() {
        val pool = ClientLeasePool<String, Client> { if (it == "bad") error("invalid"); Client() }
        val old = pool.acquire("good")
        try { pool.acquire("bad"); fail() } catch (_: IllegalStateException) { }
        val same = pool.acquire("good")
        assertSame(old.client, same.client)
        pool.invalidate()
        assertEquals(0, old.client.closes)
        old.close()
        same.close()
        assertEquals(1, old.client.closes)
    }
}
