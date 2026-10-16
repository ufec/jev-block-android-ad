package me.ethanxu.jevnoisegate.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    @Test fun `version two history survives upgrade and restored user decision cannot be overwritten`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE observed_event (
                id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, timestampMs INTEGER NOT NULL,
                packageName TEXT NOT NULL, channelId TEXT, senderKey TEXT, appLabel TEXT, channelLabel TEXT,
                title TEXT, body TEXT NOT NULL, bucketKey TEXT NOT NULL, fingerprint TEXT NOT NULL,
                skeleton TEXT NOT NULL, action TEXT, decidedBy TEXT, category TEXT, confidence REAL,
                failureReason TEXT, latencyMs INTEGER, uploaded INTEGER)""")
            listOf("bucketKey", "fingerprint", "timestampMs").forEach { column ->
                old.execSQL("CREATE INDEX index_observed_event_$column ON observed_event($column)")
            }
            old.execSQL("INSERT INTO observed_event (id,source,timestampMs,packageName,title,body,bucketKey,fingerprint,skeleton,action,decidedBy,uploaded) VALUES ('old','NOTIFICATION',100,'bank','订单','完整正文','bank','template','骨架','QUARANTINE','MODEL',1)")
            old.version = 2
        }
        val db = Room.databaseBuilder(context, JevNoiseGateDatabase::class.java, name)
            .addMigrations(DatabaseModule.MIGRATION_2_3, DatabaseModule.MIGRATION_3_4, DatabaseModule.MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val dao = db.observedEventDao()
            val old = dao.find("old")!!
            assertEquals("完整正文", old.body)
            assertEquals("QUARANTINE", old.action)
            assertTrue(old.backendAttempted!!)
            assertEquals(100L, old.capturedAtMs)
            assertNull(old.ingressElapsedMs)
            assertNull(old.bootCount)
            assertEquals(ChannelStats(1, 1), dao.channelStats("bank", "missing"))
            assertEquals(ChannelStats(0, 0), dao.channelStats("bank", "old"))
            assertNotNull(dao.restore("old", 200))
            assertTrue(dao.isUserAllowed(correctionKey(old.bucketKey, old.title, old.body)))
            assertEquals(0, dao.recordDecision("old", "QUARANTINE", "MODEL", "广告", 1f, null, 100, true))
            assertEquals("USER", dao.find("old")!!.decidedBy)
            assertEquals(ChannelStats(1, 0), dao.channelStats("bank", "missing"))
            dao.prune(300)
            assertNull(dao.find("old"))
            assertEquals(ChannelStats(0, 0), dao.channelStats("bank", "missing"))
            assertTrue(dao.isUserAllowed(correctionKey(old.bucketKey, old.title, old.body)))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `correction key includes title and full content beyond template prefix`() {
        val prefix = "a".repeat(80)
        assertNotEquals(correctionKey("app", "A", prefix + "advertisement"), correctionKey("app", "A", prefix + "order"))
        assertNotEquals(correctionKey("app", "A", "body"), correctionKey("app", "B", "body"))
    }
}
