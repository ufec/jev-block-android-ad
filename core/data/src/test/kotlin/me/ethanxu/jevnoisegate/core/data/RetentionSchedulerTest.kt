package me.ethanxu.jevnoisegate.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetentionSchedulerTest {
    @Test fun `bursts coalesce and age maintenance never runs in the producer`() = runTest {
        var cleans = 0
        val scheduler = RetentionScheduler(backgroundScope, { cleans++ }, nowMs = { testScheduler.currentTime })
        repeat(100) { scheduler.request() }
        assertEquals(0, cleans)
        runCurrent()
        assertEquals(1, cleans)
        repeat(100) { scheduler.request() }
        runCurrent()
        assertEquals(1, cleans)
        advanceTimeBy(300_000)
        scheduler.request()
        runCurrent()
        assertEquals(2, cleans)
    }
}
