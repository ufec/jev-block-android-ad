package me.ethanxu.jevnoisegate.feature.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncLookupCacheTest {
    @Test fun `null is cached until expiry and concurrent callers share one lookup`() = runTest {
        var calls = 0
        val cache = AsyncLookupCache<String, String>(backgroundScope,
            nowMs = { testScheduler.currentTime }, negativeTtlMs = 30,
            dispatcher = StandardTestDispatcher(testScheduler)) { calls++; null }
        val first = async { cache.get("missing") }
        val second = async { cache.get("missing") }
        runCurrent()
        assertNull(first.await())
        assertNull(second.await())
        repeat(10) { assertNull(cache.get("missing")) }
        assertEquals(1, calls)
        advanceTimeBy(31)
        val expired = async { cache.get("missing") }
        runCurrent()
        assertNull(expired.await())
        assertEquals(2, calls)
    }

    @Test fun `slow cold package does not block hot package and timeout leaves shared lookup alive`() = runTest {
        val gate = CompletableDeferred<String>()
        var coldCalls = 0
        val cache = AsyncLookupCache<String, String>(backgroundScope,
            nowMs = { testScheduler.currentTime }, dispatcher = StandardTestDispatcher(testScheduler)) {
            if (it == "cold") { coldCalls++; gate.await() } else "Hot app"
        }
        val warm = async { cache.get("hot") }
        runCurrent()
        assertEquals("Hot app", warm.await())
        val cold = async { cache.get("cold", 50) }
        runCurrent()
        assertEquals("Hot app", cache.get("hot"))
        advanceTimeBy(51)
        assertNull(cold.await())
        assertFalse(gate.isCancelled)
        gate.complete("Cold app")
        runCurrent()
        assertEquals("Cold app", cache.get("cold"))
        assertEquals(1, coldCalls)
    }
}
