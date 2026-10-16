package me.ethanxu.jevnoisegate.feature.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Slow Binder lookups share work per key; waiting callers have a bounded budget. */
internal class AsyncLookupCache<K, V : Any>(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val positiveTtlMs: Long = 300_000,
    private val negativeTtlMs: Long = 30_000,
    private val maxSize: Int = 256,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val load: suspend (K) -> V?,
) {
    private data class Entry<V>(val value: V?, val expiresAt: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>()
    private val inFlight = mutableMapOf<K, Deferred<V?>>()

    suspend fun get(key: K, budgetMs: Long = 500): V? {
        val work = synchronized(lock) {
            val cached = entries[key]
            if (cached != null && cached.expiresAt > nowMs()) return cached.value
            entries.remove(key)
            inFlight[key] ?: run {
                // Bound background work even if synchronous Binder never returns.
                if (inFlight.size >= maxSize) return null
                scope.async(dispatcher, start = CoroutineStart.LAZY) {
                    try {
                        val value = load(key)
                        synchronized(lock) {
                            if (entries.size >= maxSize) entries.remove(entries.keys.first())
                            entries[key] = Entry(value, nowMs() + if (value == null) negativeTtlMs else positiveTtlMs)
                        }
                        value
                    } finally { synchronized(lock) { inFlight.remove(key) } }
                }.also { inFlight[key] = it }
            }
        }
        work.start()
        // A timeout detaches this waiter; the shared lookup can finish and populate the cache.
        return withTimeoutOrNull(budgetMs) { work.await() }
    }
}
