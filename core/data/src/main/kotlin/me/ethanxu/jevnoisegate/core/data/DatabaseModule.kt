package me.ethanxu.jevnoisegate.core.data

import android.content.Context
import androidx.room.Room
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideObservedEventDao(database: JevNoiseGateDatabase): ObservedEventDao =
        database.observedEventDao()
}
