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
            .addMigrations(MIGRATION_2_3)
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
}
