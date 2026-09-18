package me.ethanxu.jevnoisegate.core.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 进程内唯一的 DataStore 实例。
 *
 * 必须是单例：同一个文件被两个 DataStore 实例同时打开会抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = DataStoreSettingsRepository(context.settingsDataStore)
}
