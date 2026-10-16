package me.ethanxu.jevnoisegate.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JevNoiseGateDatabase =
        Room.databaseBuilder(context, JevNoiseGateDatabase::class.java, JevNoiseGateDatabase.NAME)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .addCallback(BucketHistory.callback)
            .build()

    @Provides
    fun provideObservedEventDao(database: JevNoiseGateDatabase): ObservedEventDao =
        database.observedEventDao()

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE observed_event ADD COLUMN decidedAtMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_allow (fingerprint TEXT NOT NULL PRIMARY KEY, createdAtMs INTEGER NOT NULL)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE observed_event ADD COLUMN capturedAtMs INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE observed_event SET capturedAtMs = timestampMs")
            db.execSQL("ALTER TABLE observed_event ADD COLUMN ingressElapsedMs INTEGER")
            db.execSQL("ALTER TABLE observed_event ADD COLUMN bootCount INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observed_event_source_decidedAtMs ON observed_event(source, decidedAtMs)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observed_event_source_capturedAtMs ON observed_event(source, capturedAtMs)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observed_event_action_timestampMs_id_uploaded ON observed_event(action, timestampMs, id, uploaded)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observed_event_packageName_channelId ON observed_event(packageName, channelId)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_observed_event_decidedBy_timestampMs_latencyMs ON observed_event(decidedBy, timestampMs, latencyMs)")
            db.execSQL("CREATE TABLE IF NOT EXISTS bucket_history (bucketKey TEXT NOT NULL PRIMARY KEY, totalSeen INTEGER NOT NULL, suppressedCount INTEGER NOT NULL)")
            db.execSQL("INSERT INTO bucket_history SELECT bucketKey, COUNT(*), SUM(CASE WHEN action IN ('SILENT_SUPPRESS', 'QUARANTINE') THEN 1 ELSE 0 END) FROM observed_event GROUP BY bucketKey")
            BucketHistory.install(db)
        }
    }
}
