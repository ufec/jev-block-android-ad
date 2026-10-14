package me.ethanxu.jevnoisegate.app

import java.util.concurrent.atomic.AtomicBoolean

/** Keep one current configuration; retire earlier clients only after their requests release them. */
internal class ClientLeasePool<K, C : AutoCloseable>(private val create: (K) -> C) {
    private class Entry<K, C>(val key: K, val client: C, var users: Int = 0, var retired: Boolean = false)
    private val lock = Any()
    private var current: Entry<K, C>? = null

    class Lease<C>(val client: C, private val release: () -> Unit) : AutoCloseable {
        private val released = AtomicBoolean(false)
        override fun close() { if (released.compareAndSet(false, true)) release() }
    }

    fun acquire(key: K): Lease<C> {
        var toClose: C? = null
        val entry = synchronized(lock) {
            val old = current
            val next = if (old != null && old.key == key) old else {
                // Failed creation must not discard a usable current client.
                Entry(key, create(key)).also {
                    current = it
                    old?.retired = true
                    if (old?.users == 0) toClose = old.client
                }
            }
            next.users++
            next
        }
        toClose?.let { runCatching { it.close() } }
        return Lease(entry.client) {
            val retired = synchronized(lock) {
                entry.users--
                entry.retired && entry.users == 0
            }
            if (retired) runCatching { entry.client.close() }
        }
    }

    fun invalidate() {
        val toClose = synchronized(lock) {
            val old = current
            current = null
            old?.retired = true
            old?.takeIf { it.users == 0 }?.client
        }
        toClose?.let { runCatching { it.close() } }
    }
}
