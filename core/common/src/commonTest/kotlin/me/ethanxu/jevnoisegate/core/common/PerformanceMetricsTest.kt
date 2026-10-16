package me.ethanxu.jevnoisegate.core.common

import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PerformanceMetricsTest {
    @Test fun `completed trace includes preparation database and completion but keeps phases separate`() = runTest {
        val trace = DecisionTrace(Clock { testScheduler.currentTime })
        trace.database { delay(5) }
        delay(2)
        trace.prepared()
        trace.queueMs = 10
        trace.backendMs = 30
        delay(40)
        trace.database { delay(3) }
        val result = trace.complete()
        assertEquals(50L, result.endToEndMs)
        assertEquals(7L, result.preparationMs)
        assertEquals(8L, result.databaseMs)
        assertEquals(10L, result.queueMs)
        assertEquals(30L, result.backendMs)
    }

    @Test fun `unknown or changed boot cannot yield a scheduling or ingress duration`() {
        assertEquals(10L, sameBootIngress(2, 2, 10, 50))
        assertNull(sameBootIngress(2, 3, 10, 50))
        assertNull(sameBootIngress(null, null, 10, 50))
        assertNull(sameBootIngress(2, 2, 60, 50))
    }

    @Test fun `diagnostic sampling remains bounded and excludes absent ingress values`() {
        repeat(300) { PerformanceMetrics.record(TimingSample(null, 1, 2, 3, 4, null)) }
        val snapshot = PerformanceMetrics.snapshot()
        assertEquals(256, snapshot.backend.count)
        assertEquals(0, snapshot.endToEnd.count)
        assertEquals(0, snapshot.workerWait.count)
    }
}
