package me.ethanxu.jevnoisegate.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProbeTest {
    @Test fun `invalid URL is a failure result rather than an uncaught exception`() = runTest {
        assertTrue(probeProxy("abc", null, 10_000) is ProxyProbeResult.Failed)
        assertTrue(probeProxy("", null, 10_000) is ProxyProbeResult.Failed)
    }
}
