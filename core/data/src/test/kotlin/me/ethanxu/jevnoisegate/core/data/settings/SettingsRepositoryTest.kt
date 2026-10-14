package me.ethanxu.jevnoisegate.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @Test fun `concurrent saves after recovery do not reset another accepted update`() = runTest {
        val key = stringPreferencesKey("api_key")
        val store = Store(preferencesOf(key to "old-secret")).apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        store.loadDelay = 100
        val color = async { repo.setColorMode(2) }
        val keyColor = async { repo.setKeyColor(123) }
        color.await(); keyColor.await()
        val reload = DataStoreSettingsRepository(store, codec, backgroundScope)
        reload.awaitReady()
        assertEquals("old-secret", reload.preferences.value.apiKey)
        assertEquals(2, reload.preferences.value.colorMode)
        assertEquals(123, reload.preferences.value.keyColor)
    }

    @Test fun `new log level is applied after recovering the stored configuration`() = runTest {
        val store = Store().apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        repo.setLogLevel(AppLog.OFF)
        assertEquals(AppLog.OFF, repo.preferences.value.logLevel)
        assertFalse(AppLog.isEnabled(LogLevel.ERROR))
    }
    @Test fun `save after a recovered load failure preserves unread existing settings`() = runTest {
        val key = stringPreferencesKey("api_key")
        val muted = stringSetPreferencesKey("muted_packages")
        val store = Store(preferencesOf(key to "old-secret", muted to setOf("bank")))
            .apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.loadFailure = false
        repo.setColorMode(2)
        assertEquals("old-secret", codec.decrypt(store.value.value[key].orEmpty()))
        assertEquals(setOf("bank"), store.value.value[muted])
        assertEquals("old-secret", repo.preferences.value.apiKey)
    }

    @Test fun `continuing read failure cannot replace stored configuration with defaults`() = runTest {
        val key = stringPreferencesKey("api_key")
        val store = Store(preferencesOf(key to "old-secret")).apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        try { repo.setColorMode(2); fail("Expected IOException") } catch (_: IOException) { }
        assertEquals("old-secret", store.value.value[key])
        assertNotNull(repo.persistenceError.value)
    }
    private val codec = object : SecretCodec {
        override fun encrypt(value: String) = if (value.isEmpty()) value else "enc:v1:${value.reversed()}"
        override fun decrypt(value: String) = if (value.startsWith("enc:v1:")) value.removePrefix("enc:v1:").reversed() else value
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

    @Test fun `burst snapshots coalesce slow writes and every accepted caller completes`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.writeDelay = 100
        val jobs = (1..200).map { index -> async { repo.setKeyColor(index) } }
        jobs.forEach { it.await() }
        assertEquals(200, repo.preferences.value.keyColor)
        assertEquals(200, store.value.value[androidx.datastore.preferences.core.intPreferencesKey("key_color")])
        assertTrue("Burst must not create 200 disk writes", store.writes < 20)
    }

    @Test fun `API form saves once and unrelated changes preserve credential ciphertext`() = runTest {
        var encryptions = 0
        val counting = object : SecretCodec {
            override fun encrypt(value: String): String { encryptions++; return codec.encrypt(value) }
            override fun decrypt(value: String) = codec.decrypt(value)
        }
        val store = Store()
        val repo = DataStoreSettingsRepository(store, counting, backgroundScope)
        repo.awaitReady()
        repo.saveApiSettings("secret", "https://example.com", "model", 5_000)
        assertEquals(1, store.writes)
        val ciphertext = store.value.value[stringPreferencesKey("api_key")]
        repo.setColorMode(2)
        assertEquals(1, encryptions)
        assertEquals(ciphertext, store.value.value[stringPreferencesKey("api_key")])
        repo.setColorMode(2)
        assertEquals(2, store.writes)
    }

    @Test fun `stalled read and legacy migration both release readiness at the deadline`() = runTest {
        val stalledRead = Store().apply { loadDelay = 60_000 }
        val first = DataStoreSettingsRepository(stalledRead, codec, backgroundScope)
        first.awaitReady()
        assertEquals(5_000L, testScheduler.currentTime)
        assertNotNull(first.persistenceError.value)

        val stalledMigration = Store(preferencesOf(stringPreferencesKey("api_key") to "legacy"))
            .apply { writeDelay = 60_000 }
        val second = DataStoreSettingsRepository(stalledMigration, codec, backgroundScope)
        second.awaitReady()
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals("legacy", second.preferences.value.apiKey)
        assertNotNull(second.persistenceError.value)
        stalledMigration.writeDelay = 0
        second.setApiKey("saved")
        assertNull(second.persistenceError.value)
    }

    @Test fun `failed load releases readiness and permits a later save`() = runTest {
        val store = Store().apply { loadFailure = true }
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        assertNotNull(repo.persistenceError.value)
        store.loadFailure = false
        repo.setApiKey("new-key")
        assertEquals("new-key", repo.preferences.value.apiKey)
        assertNull(repo.persistenceError.value)
    }

    @Test fun `legacy credentials are encrypted without changing their values`() = runTest {
        val key = stringPreferencesKey("api_key")
        val store = Store(preferencesOf(key to "secret"))
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        assertEquals("secret", repo.preferences.value.apiKey)
        assertEquals("enc:v1:terces", store.value.value[key])
    }

    @Test fun `accepted writes persist in memory order even if UI caller is cancelled`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.writeDelay = 100
        val first = async { repo.setApiKey("first") }
        runCurrent()
        assertEquals("first", repo.preferences.value.apiKey)
        first.cancel()
        val second = async { repo.setApiKey("second") }
        second.await()
        assertEquals("enc:v1:dnoces", store.value.value[stringPreferencesKey("api_key")])
        assertEquals("second", repo.preferences.value.apiKey)
        val reload = DataStoreSettingsRepository(store, codec, backgroundScope)
        reload.awaitReady()
        assertEquals("second", reload.preferences.value.apiKey)
    }

    @Test fun `failed write is reported and consumer recovers`() = runTest {
        val store = Store()
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        store.writeFailure = true
        try { repo.setApiKey("failed"); fail("Expected IOException") } catch (_: IOException) { }
        assertNotNull(repo.persistenceError.value)
        store.writeFailure = false
        repo.setApiKey("saved")
        assertNull(repo.persistenceError.value)
    }

    @Test fun `nonfinite stored thresholds are discarded and invalid URL is rejected`() = runTest {
        val store = Store(preferencesOf(stringPreferencesKey("category_thresholds") to "广告=NaN\n诈骗=Infinity"))
        val repo = DataStoreSettingsRepository(store, codec, backgroundScope)
        repo.awaitReady()
        assertTrue(repo.preferences.value.categoryThresholds.isEmpty())
        try { repo.setBaseUrl("abc"); fail("Expected validation error") } catch (_: IllegalArgumentException) { }
        assertEquals(AppPreferences.DEFAULT_BASE_URL, repo.preferences.value.baseUrl)
        assertTrue(isValidBaseUrl("https://example.com/api"))
        assertFalse(isValidBaseUrl("https://user:password@example.com"))
    }
}
