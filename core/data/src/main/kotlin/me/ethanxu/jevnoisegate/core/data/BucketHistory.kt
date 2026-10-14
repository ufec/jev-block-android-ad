package me.ethanxu.jevnoisegate.core.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Triggers also cover cleanup and raw inserts, without a second application-side write path. */
object BucketHistory {
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { install(db) }
    }

    fun install(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    val statements = listOf(
        """CREATE TRIGGER IF NOT EXISTS bucket_history_insert AFTER INSERT ON observed_event BEGIN
            INSERT OR IGNORE INTO bucket_history VALUES (NEW.bucketKey, 0, 0);
            UPDATE bucket_history SET totalSeen = totalSeen + 1,
                suppressedCount = suppressedCount + CASE WHEN NEW.action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END
                WHERE bucketKey = NEW.bucketKey;
        END""",
        """CREATE TRIGGER IF NOT EXISTS bucket_history_delete AFTER DELETE ON observed_event BEGIN
            UPDATE bucket_history SET totalSeen = totalSeen - 1,
                suppressedCount = suppressedCount - CASE WHEN OLD.action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END
                WHERE bucketKey = OLD.bucketKey;
            DELETE FROM bucket_history WHERE bucketKey = OLD.bucketKey AND totalSeen = 0;
        END""",
        """CREATE TRIGGER IF NOT EXISTS bucket_history_update AFTER UPDATE OF bucketKey, action ON observed_event
            WHEN OLD.bucketKey IS NOT NEW.bucketKey OR OLD.action IS NOT NEW.action BEGIN
            UPDATE bucket_history SET totalSeen = totalSeen - 1,
                suppressedCount = suppressedCount - CASE WHEN OLD.action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END
                WHERE bucketKey = OLD.bucketKey;
            DELETE FROM bucket_history WHERE bucketKey = OLD.bucketKey AND totalSeen = 0;
            INSERT OR IGNORE INTO bucket_history VALUES (NEW.bucketKey, 0, 0);
            UPDATE bucket_history SET totalSeen = totalSeen + 1,
                suppressedCount = suppressedCount + CASE WHEN NEW.action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END
                WHERE bucketKey = NEW.bucketKey;
        END""",
    )
}
