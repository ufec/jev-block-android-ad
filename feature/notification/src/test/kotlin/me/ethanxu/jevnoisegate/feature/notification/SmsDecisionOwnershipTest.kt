package me.ethanxu.jevnoisegate.feature.notification

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import me.ethanxu.jevnoisegate.core.data.MessageEventEntity
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmsDecisionOwnershipTest {
    @Test fun `completed owned post queries once and changed content queries again`() = runTest {
        val cache = RecentPostCache(nowMillis = { testScheduler.currentTime })
        var queries = 0
        suspend fun admit(shown: SmsNotificationCandidate) = admitSmsPost(true, shown,
            markSeen = { cache.markSeen(shown.key, shown.body, shown.title) },
            owners = { queries++; flowOf(listOf(sms().copy(body = shown.body))) })
        assertEquals(SmsPostAdmission.OWNED, admit(candidate))
        assertEquals(SmsPostAdmission.DUPLICATE, admit(candidate))
        assertEquals(1, queries)
        assertEquals(SmsPostAdmission.OWNED, admit(candidate.copy(body = body + "新内容")))
        assertEquals(2, queries)
    }

    @Test fun `completed missing owner waits only once and late capture can still drive the bridge`() = runTest {
        val cache = RecentPostCache(nowMillis = { testScheduler.currentTime })
        val captured = MutableStateFlow<List<MessageEventEntity>>(emptyList())
        var queries = 0
        suspend fun admit() = admitSmsPost(true, candidate,
            markSeen = { cache.markSeen(candidate.key, body, candidate.title) },
            owners = { queries++; captured })
        val first = async { admit() }
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(SmsPostAdmission.FALLBACK, first.await())
        captured.value = listOf(sms())
        val before = testScheduler.currentTime
        assertEquals(SmsPostAdmission.DUPLICATE, admit())
        assertEquals(before, testScheduler.currentTime)
        assertEquals(1, queries)
        // Removal/reuse admits a new notification; the bridge remains outside this owner gate.
        cache.forget(candidate.key)
        assertEquals(SmsPostAdmission.OWNED, admit())
        assertEquals(2, queries)
    }
    private val body = "这是一条完整的普通短信内容"
    private val candidate = SmsNotificationCandidate("key", body, "1069", false)
    private fun sms(id: String = "sms-id", sender: String = "1069", action: String? = null) =
        MessageEventEntity(id, "SMS", 1, "sms", null, sender, null, null, null, body, sender, "fp", "body", action = action)

    @Test fun `pending and allowed SMS captures both own the notification without a second model decision`() = runTest {
        assertTrue(awaitSmsOwner(true, candidate, { flowOf(listOf(sms())) }))
        assertTrue(awaitSmsOwner(true, candidate, { flowOf(listOf(sms(action = "ALLOW"))) }))
    }

    @Test fun `notification arriving before broadcast waits for captured owner`() = runTest {
        val captured = MutableStateFlow<List<MessageEventEntity>>(emptyList())
        val result = async { awaitSmsOwner(true, candidate, { captured }) }
        runCurrent()
        assertFalse(result.isCompleted)
        captured.value = listOf(sms())
        assertTrue(result.await())
    }

    @Test fun `missing permission absent capture ambiguous sender and grouped messages retain fallback`() = runTest {
        assertFalse(awaitSmsOwner(false, candidate, { error("no permission must not query") }))
        assertFalse(awaitSmsOwner(true, candidate.copy(grouped = true), { error("group must not query") }))
        assertFalse(awaitSmsOwner(true, candidate, { flowOf(emptyList()) }))
        assertFalse(awaitSmsOwner(true, candidate.copy(title = null), { flowOf(listOf(sms(), sms("second", "13800138000"))) }))
        assertFalse(awaitSmsOwner(true, candidate.copy(title = "13800138000"), { flowOf(listOf(sms())) }))
    }
}
