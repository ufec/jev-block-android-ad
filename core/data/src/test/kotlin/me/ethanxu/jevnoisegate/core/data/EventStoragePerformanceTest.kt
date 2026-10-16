package me.ethanxu.jevnoisegate.core.data

import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventStoragePerformanceTest {
    private fun event(id: String, timestamp: Long, action: String? = "QUARANTINE") =
        MessageEventEntity(id, "NOTIFICATION", timestamp, "app", "ch", null, "app", "ch", null,
            "body", "app|ch", "fp", "body", action = action, backendAttempted = action != null)

    @Test fun `capacity remains strict without deleting corrections and duplicate insertion does not evict`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevNoiseGateDatabase::class.java).addCallback(BucketHistory.callback).build()
        try {
            val dao = db.observedEventDao()
            db.withTransaction { repeat(10_000) { dao.insertRaw(event("$it", it.toLong())) } }
            dao.putUserAllow(UserAllowEntity("correction", 1))
            assertEquals(-1, dao.insert(event("9999", 9999)).toInt())
            assertNotNull(dao.find("0"))
            assertEquals(ChannelStats(10_000, 10_000), dao.channelStats("app|ch", "missing"))
            dao.insert(event("new", 10_000))
            assertEquals(10_000, dao.eventCount())
            assertNull(dao.find("0"))
            assertEquals(ChannelStats(9_999, 9_999), dao.channelStats("app|ch", "new"))
            assertTrue(dao.isUserAllowed("correction"))
            dao.pruneExpired(10_000)
            assertEquals(1, dao.eventCount())
            assertEquals(ChannelStats(1, 1), dao.channelStats("app|ch", "missing"))
        } finally { db.close() }
    }

    @Test fun `incremental history agrees with aggregation through every mutation path`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevNoiseGateDatabase::class.java)
            .addCallback(BucketHistory.callback).build()
        try {
            val dao = db.observedEventDao()
            suspend fun check() {
                for (bucket in listOf("app|ch", "other", "missing")) for (excluded in listOf("0", "1", "absent")) {
                    val expected = db.withTransaction {
                        db.openHelper.readableDatabase.query("SELECT COUNT(*), IFNULL(SUM(CASE WHEN action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END), 0) FROM observed_event WHERE bucketKey = ? AND id != ?",
                            arrayOf(bucket, excluded)).use { cursor ->
                            cursor.moveToFirst(); ChannelStats(cursor.getInt(0), cursor.getInt(1))
                        }
                    }
                    assertEquals(expected, dao.channelStats(bucket, excluded))
                }
            }
            check()
            repeat(10) { dao.insertRaw(event("$it", it.toLong(), if (it % 2 == 0) null else "QUARANTINE")) }
            dao.insertRaw(event("0", 99, "ALLOW")); check()
            dao.recordDecision("0", "SILENT_SUPPRESS", "MODEL", null, null, null, 10, true); check()
            dao.recordDecision("0", "SILENT_SUPPRESS", "MODEL", null, null, null, 20, true); check()
            dao.restore("1", 100); check()
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE observed_event SET bucketKey = 'other', action = 'ALLOW' WHERE id = '0'") }; check()
            dao.pruneExpired(3); check()
            dao.trimOldest(2); check()
            dao.insertRaw(event("extra", 100, "QUARANTINE").copy(packageName = "other-app", bucketKey = "other")); check()
            dao.deleteByPackage("app"); check()
            dao.clear(); check()
        } finally { db.close() }
    }

    @Test fun `model latency index preserves latest matching limit with sparse old and new records`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevNoiseGateDatabase::class.java)
            .addCallback(BucketHistory.callback).build()
        try {
            val dao = db.observedEventDao()
            db.withTransaction {
                repeat(2_200) { i -> dao.insertRaw(event("$i", i.toLong(), "ALLOW").copy(
                    decidedBy = if (i < 1_100) { if (i % 2 == 0) "MODEL" else "FALLBACK" } else "OTP_GATE",
                    latencyMs = i.toLong())) }
            }
            assertEquals((100L..1_099L).toList(), dao.observeLatencies().first())
            dao.insertRaw(event("recent", 5_000, "ALLOW").copy(decidedBy = "FALLBACK", latencyMs = 9_999))
            dao.insertRaw(event("null", 6_000, "ALLOW").copy(decidedBy = "MODEL", latencyMs = null))
            assertEquals((101L..1_099L).toList() + 9_999L, dao.observeLatencies().first())
            dao.pruneExpired(1_100)
            assertEquals(listOf(9_999L), dao.observeLatencies().first())
            dao.clear()
            assertTrue(dao.observeLatencies().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun `summary pages retain metadata while content query loads only selected full body`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevNoiseGateDatabase::class.java)
            .addCallback(BucketHistory.callback).build()
        try {
            val dao = db.observedEventDao()
            val body = "a".repeat(16_384) + "末尾"
            dao.insertRaw(event("large", 1).copy(title = "标题", body = body))
            val summary = dao.observeRecentSummaries(300).first().single()
            assertEquals("large", summary.id)
            assertTrue(summary.hasContent)
            assertEquals("QUARANTINE", summary.action)
            assertEquals(listOf(summary), dao.observeQuarantineSummaries(101).first())
            assertEquals(EventContent("标题", body), dao.observeContent("large").first())
            assertNull(dao.observeContent("missing").first())
            dao.clear()
            assertNull(dao.observeContent("large").first())
        } finally { db.close() }
    }

    @Test fun `keyset pages with identical timestamps do not repeat rows after a new first-page arrival`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JevNoiseGateDatabase::class.java).addCallback(BucketHistory.callback).build()
        try {
            val dao = db.observedEventDao()
            db.withTransaction { repeat(205) { dao.insertRaw(event(it.toString().padStart(3, '0'), 100)) } }
            val first = dao.observeQuarantined(101).first().take(100)
            assertEquals("204", first.first().id)
            dao.insert(event("new", 101, "ALLOW"))
            dao.insert(event("new-quarantine", 101))
            val second = dao.observeQuarantineBefore(first.last().timestampMs, first.last().id, 101).first().take(100)
            val third = dao.observeQuarantineBefore(second.last().timestampMs, second.last().id, 101).first()
            val ids = (first + second + third).map { it.id }
            assertEquals(205, ids.size)
            assertEquals(205, ids.toSet().size)
            assertEquals("000", ids.last())
            val overview = dao.observeOverview().first()
            assertEquals(EventOverview(total = 207, allowed = 1, quarantined = 206, attempted = 207), overview)
        } finally { db.close() }
    }
}
