package me.ethanxu.jevnoisegate.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory
import me.ethanxu.jevnoisegate.core.common.PerformanceMetrics
import me.ethanxu.jevnoisegate.core.common.PerformanceCounter

/** Coalesce age maintenance outside the per-message decision path; capacity is enforced by insert. */
@Singleton
class EventRetention @Inject constructor(dao: ObservedEventDao) {
    private val scheduler = RetentionScheduler(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clean = {
            dao.pruneExpired(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            PerformanceMetrics.increment(PerformanceCounter.RETENTION_RUN)
        },
        failure = { AppLog.w(LogCategory.SYSTEM, "EventRetention", it) { "事件保留清理失败" } },
    )
    fun request() = scheduler.request()
}

internal class RetentionScheduler(
    scope: CoroutineScope,
    private val clean: suspend () -> Unit,
    private val failure: (Exception) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val intervalMs: Long = 5 * 60_000,
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            var lastClean: Long? = null
            for (ignored in signals) {
                if (lastClean?.let { nowMs() - it < intervalMs } == true) continue
                try {
                    clean()
                    lastClean = nowMs()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure(error)
                    // Avoid spinning on failing storage; the next request after the interval retries.
                    lastClean = nowMs()
                }
            }
        }
    }
    fun request() { signals.trySend(Unit) }
}
