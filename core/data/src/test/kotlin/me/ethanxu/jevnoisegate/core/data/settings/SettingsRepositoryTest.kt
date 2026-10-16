package me.ethanxu.jevnoisegate.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogLevel
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @Test fun `concurrent saves after recovery do not reset another accepted update`() = runTest {
        val colorKey = intPreferencesKey("color_mode")
        val muted = stringSetPreferencesKey("muted_packages")
        val store = Store(preferencesOf(colorKey to 1, muted to setOf("bank"))).apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        store.loadDelay = 100
        val color = async { repo.setColorMode(2) }
        val keyColor = async { repo.setKeyColor(123) }
        color.await(); keyColor.await()
        val reload = DataStoreSettingsRepository(store, backgroundScope)
        reload.awaitReady()
        assertEquals(setOf("bank"), reload.preferences.value.mutedPackages)
        assertEquals(2, reload.preferences.value.colorMode)
        assertEquals(123, reload.preferences.value.keyColor)
    }

    @Test fun `new log level is applied after recovering the stored configuration`() = runTest {
        val store = Store().apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        repo.setLogLevel(AppLog.OFF)
        assertEquals(AppLog.OFF, repo.preferences.value.logLevel)
        assertFalse(AppLog.isEnabled(LogLevel.ERROR))
    }

    @Test fun `save after a recovered load failure preserves unread existing settings`() = runTest {
        val muted = stringSetPreferencesKey("muted_packages")
        val store = Store(preferencesOf(muted to setOf("bank"))).apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        repo.setColorMode(2)
        assertEquals(setOf("bank"), store.value.value[muted])
        assertEquals(setOf("bank"), repo.preferences.value.mutedPackages)
        assertEquals(2, repo.preferences.value.colorMode)
    }

    @Test fun `continuing read failure cannot replace stored configuration with defaults`() = runTest {
        val muted = stringSetPreferencesKey("muted_packages")
        val store = Store(preferencesOf(muted to setOf("bank"))).apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        try { repo.setColorMode(2); fail("Expected IOException") } catch (_: IOException) { }
        assertEquals(setOf("bank"), store.value.value[muted])
        assertNotNull(repo.persistenceError.value)
    }

    @Test fun `stalled read and stalled migration both release readiness at the deadline`() = runTest {
        val stalledRead = Store().apply { loadDelay = 60_000 }
        val first = DataStoreSettingsRepository(stalledRead, backgroundScope)
        first.awaitReady()
        assertEquals(5_000L, testScheduler.currentTime)
        assertNotNull(first.persistenceError.value)

        // The purge is itself a write, so a stalled transaction costs the same 5s budget
        // on top of the read attempt rather than blocking readiness indefinitely.
        val stalledMigration = Store(preferencesOf(stringPreferencesKey("api_key") to "legacy"))
            .apply { writeDelay = 60_000 }
        val second = DataStoreSettingsRepository(stalledMigration, backgroundScope)
        second.awaitReady()
        assertEquals(10_000L, testScheduler.currentTime)
        assertNotNull(second.persistenceError.value)
        stalledMigration.writeDelay = 0
        second.setColorMode(2)
        assertNull(second.persistenceError.value)
    }

    @Test fun `failed load releases readiness and permits a later save`() = runTest {
        val store = Store().apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        assertNotNull(repo.persistenceError.value)
        store.loadFailure = false
        repo.setColorMode(2)
        assertEquals(2, repo.preferences.value.colorMode)
        assertNull(repo.persistenceError.value)
    }

    @Test fun `legacy cloud keys are purged rather than re-read`() = runTest {
        val legacy = preferencesOf(
            stringPreferencesKey("api_key") to "legacy-key",
            stringPreferencesKey("base_url") to "https://old.example",
            stringPreferencesKey("model") to "old-model",
            stringPreferencesKey("proxy_type") to "http",
            stringPreferencesKey("proxy_host") to "10.0.0.1",
            intPreferencesKey("proxy_port") to 8080,
            stringPreferencesKey("ui_mode") to "light",
        )
        val store = Store(legacy)
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        val kept = store.value.value
        assertNull(kept[stringPreferencesKey("api_key")])
        assertNull(kept[stringPreferencesKey("base_url")])
        assertNull(kept[stringPreferencesKey("model")])
        assertNull(kept[stringPreferencesKey("proxy_type")])
        assertNull(kept[stringPreferencesKey("proxy_host")])
        assertNull(kept[intPreferencesKey("proxy_port")])
        // Settings this build still reads survive the purge untouched.
        assertEquals("light", repo.preferences.value.uiMode)
    }

    @Test fun `accepted writes persist in memory order even if UI caller is cancelled`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.writeDelay = 100
        val first = async { repo.setColorMode(2) }
        runCurrent()
        assertEquals(2, repo.preferences.value.colorMode)
        first.cancel()
        val second = async { repo.setKeyColor(7) }
        second.await()
        assertEquals(7, store.value.value[intPreferencesKey("key_color")])
        assertEquals(7, repo.preferences.value.keyColor)
        val reload = DataStoreSettingsRepository(store, backgroundScope)
        reload.awaitReady()
        assertEquals(7, reload.preferences.value.keyColor)
    }

    @Test fun `failed write is reported and consumer recovers`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.writeFailure = true
        try { repo.setColorMode(2); fail("Expected IOException") } catch (_: IOException) { }
        assertNotNull(repo.persistenceError.value)
        store.writeFailure = false
        repo.setColorMode(2)
        assertNull(repo.persistenceError.value)
    }

    @Test fun `nonfinite stored thresholds are discarded`() = runTest {
        val store = Store(preferencesOf(stringPreferencesKey("category_thresholds") to "广告=NaN\n诈骗=Infinity"))
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        assertTrue(repo.preferences.value.categoryThresholds.isEmpty())
    }

    @Test fun `burst snapshots coalesce slow writes and every accepted caller completes`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, backgroundScope)
        repo.awaitReady()
        store.writeDelay = 100
        val writes = (1..200).map { async { repo.setColorMode(it) } }
        writes.forEach { it.await() }
        assertEquals(200, repo.preferences.value.colorMode)
        assertTrue("Burst must not create 200 disk writes", store.writes < 20)
    }

    private class Store(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        var writes = 0
        val value = MutableStateFlow(initial)
        var loadFailure = false
        var writeFailure = false
        var writeDelay = 0L
        var loadDelay = 0L
        override val data: Flow<Preferences> get() = flow {
            delay(loadDelay)
            if (loadFailure) throw IOException("load")
            emitAll(value)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writes++
            delay(writeDelay)
            if (writeFailure) throw IOException("write")
            return transform(value.value).also { value.value = it }
        }
    }
}
