package me.ethanxu.jevnoisegate.core.common

/** Durations use a monotonic clock; persisted dates/expiry still use SystemClock (wall time). */
object MonotonicClock : Clock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000
}

enum class PerformanceCounter {
    OVERLOAD, CANCELLED, QUEUE_TIMEOUT, BACKEND_TIMEOUT, SMS_OWNED, SMS_OWNER_FALLBACK,
    CLIENT_CREATED, SMS_SNAPSHOT, RETENTION_RUN, SETTINGS_WRITE, SETTINGS_ENCRYPT, LOG_WRITE,
}

data class TimingSample(
    val endToEndMs: Long?, val preparationMs: Long, val databaseMs: Long,
    val queueMs: Long, val backendMs: Long, val workerWaitMs: Long?,
)

data class DurationDistribution(val count: Int = 0, val p50: Long = 0, val p95: Long = 0, val max: Long = 0) {
    companion object {
        fun from(values: List<Long>): DurationDistribution {
            if (values.isEmpty()) return DurationDistribution()
            val sorted = values.sorted()
            return DurationDistribution(sorted.size, sorted[sorted.size / 2],
                sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)], sorted.last())
        }
    }
}

data class PerformanceSnapshot(
    val endToEnd: DurationDistribution = DurationDistribution(),
    val preparation: DurationDistribution = DurationDistribution(),
    val database: DurationDistribution = DurationDistribution(),
    val queue: DurationDistribution = DurationDistribution(),
    val backend: DurationDistribution = DurationDistribution(),
    val workerWait: DurationDistribution = DurationDistribution(),
    val counters: Map<PerformanceCounter, Long> = emptyMap(),
    val active: Int = 0, val waiting: Int = 0, val peakActive: Int = 0, val peakWaiting: Int = 0,
)

/** Bounded sampling, no list copies or Flow publication on the ingestion path. UI polls on demand. */
object PerformanceMetrics {
    private val lock = Any()
    private val samples = ArrayDeque<TimingSample>()
    private val counters = mutableMapOf<PerformanceCounter, Long>()
    private var active = 0
    private var waiting = 0
    private var peakActive = 0
    private var peakWaiting = 0
    fun increment(counter: PerformanceCounter) = synchronized(lock) {
        counters[counter] = (counters[counter] ?: 0) + 1
    }
    fun queued() = synchronized(lock) { waiting++; peakWaiting = maxOf(peakWaiting, waiting) }
    fun acquired() = synchronized(lock) { waiting--; active++; peakActive = maxOf(peakActive, active) }
    fun finished() = synchronized(lock) { active-- }
    fun dequeued() = synchronized(lock) { waiting-- }
    fun record(sample: TimingSample) = synchronized(lock) {
        if (samples.size == 256) samples.removeFirst()
        samples.addLast(sample)
    }
    fun snapshot(): PerformanceSnapshot {
        val state = synchronized(lock) {
            samples.toList() to PerformanceSnapshot(counters = counters.toMap(), active = active, waiting = waiting,
                peakActive = peakActive, peakWaiting = peakWaiting)
        }
        val entries = state.first
        return state.second.copy(
            endToEnd = DurationDistribution.from(entries.mapNotNull { it.endToEndMs }),
            preparation = DurationDistribution.from(entries.map { it.preparationMs }),
            database = DurationDistribution.from(entries.map { it.databaseMs }),
            queue = DurationDistribution.from(entries.map { it.queueMs }),
            backend = DurationDistribution.from(entries.map { it.backendMs }),
            workerWait = DurationDistribution.from(entries.mapNotNull { it.workerWaitMs }),
        )
    }
}

class DecisionTrace(
    private val clock: Clock = MonotonicClock,
    private val startedMs: Long = clock.nowMillis(),
    private val ingressMs: Long? = startedMs,
    val workerWaitMs: Long? = null,
) {
    private var isPrepared = false
    var databaseMs = 0L; private set
    var preparationMs = 0L; private set
    var queueMs = 0L
    var backendMs = 0L
    fun addDatabaseElapsed(elapsedMs: Long) { databaseMs += elapsedMs.coerceAtLeast(0) }
    fun prepared() { preparationMs = (clock.nowMillis() - startedMs).coerceAtLeast(0); isPrepared = true }
    suspend fun <T> database(block: suspend () -> T): T {
        val start = clock.nowMillis()
        try { return block() }
        finally { addDatabaseElapsed(clock.nowMillis() - start) }
    }
    fun complete(): TimingSample {
        if (!isPrepared) prepared()
        return TimingSample(ingressMs?.let { (clock.nowMillis() - it).coerceAtLeast(0) },
            preparationMs, databaseMs, queueMs, backendMs, workerWaitMs).also(PerformanceMetrics::record)
    }
}

fun sameBootIngress(storedBoot: Int?, currentBoot: Int?, ingressMs: Long?, workerStartedMs: Long): Long? =
    ingressMs?.takeIf { storedBoot != null && storedBoot == currentBoot && it <= workerStartedMs && it >= 0 }
