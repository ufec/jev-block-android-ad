package me.ethanxu.jevnoisegate.feature.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationLifecycleTest {
    @Test fun `disconnect releases cached text so an interrupted message can be retried`() {
        val listener = NoiseGateNotificationListener()
        val field = NoiseGateNotificationListener::class.java.getDeclaredField("recentPosts").apply { isAccessible = true }
        val cache = field.get(listener) as RecentPostCache
        assertTrue(cache.markSeen("key", "same body", "title"))
        assertFalse(cache.markSeen("key", "same body", "title"))
        listener.onListenerDisconnected()
        assertTrue(cache.markSeen("key", "same body", "title"))
        listener.onDestroy()
    }
    @Test fun `disconnect cancels every task even when cancellation removes its own entry`() {
        // onCreate injection is not needed to exercise disconnect's actual task cleanup.
        val listener = NoiseGateNotificationListener()
        val field = NoiseGateNotificationListener::class.java.getDeclaredField("jobs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val jobs = field.get(listener) as MutableMap<String, Job>
        var cancelled = 0
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        repeat(3) { index ->
            val key = "notification-$index"
            jobs[key] = scope.launch {
                try { awaitCancellation() }
                finally { jobs.remove(key); cancelled++ }
            }
        }
        listener.onListenerDisconnected()
        assertEquals(3, cancelled)
        assertTrue(jobs.isEmpty())
        listener.onDestroy()
    }
}
