package me.ethanxu.jevnoisegate.core.common.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One consumer owns file IO. Producers only update a bounded queue and wake it. */
internal class BatchedLogWriter(
    scope: CoroutineScope,
    private val initialize: () -> Unit = {},
    private val write: (List<String>) -> Unit,
    private val clearFiles: () -> Unit,
    private val publish: () -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val flushIntervalMs: Long = 2_000,
    private val batchThreshold: Int = 64,
    private val maxPending: Int = 2_048,
) {
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private var clearRequested = false
    private var firstPendingAt: Long? = null
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            initialize()
            publish()
            while (isActive) {
                val deadline = synchronized(lock) { firstPendingAt?.plus(flushIntervalMs) }
                val signalled = if (deadline == null) {
                    signals.receive()
                    true
                } else withTimeoutOrNull((deadline - nowMs()).coerceAtLeast(1)) { signals.receive(); true } == true
                // Coalesce list publication and burst wakeups; never delay the producer.
                if (signalled) {
                    val remaining = synchronized(lock) { firstPendingAt?.plus(flushIntervalMs)?.minus(nowMs()) }
                    delay(remaining?.coerceIn(0, 100) ?: 100)
                }
                val work = synchronized(lock) {
                    val due = firstPendingAt?.let { nowMs() - it >= flushIntervalMs } == true
                    val clear = clearRequested
                    clearRequested = false
                    val batch = if (clear || due || pending.size >= batchThreshold) {
                        pending.toList().also { pending.clear(); firstPendingAt = null }
                    } else emptyList()
                    clear to batch
                }
                if (work.first) clearFiles()
                if (work.second.isNotEmpty()) write(work.second)
                publish()
            }
        }
    }

    fun append(line: String) {
        synchronized(lock) {
            if (pending.isEmpty()) firstPendingAt = nowMs()
            if (pending.size >= maxPending) pending.removeFirst()
            pending.addLast(line)
        }
        signals.trySend(Unit)
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            firstPendingAt = null
            clearRequested = true
        }
        signals.trySend(Unit)
    }
}
