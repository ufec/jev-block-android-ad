package me.ethanxu.jevnoisegate.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ethanxu.jevnoisegate.app.BuildConfig
import me.ethanxu.jevnoisegate.app.verifiedProxySpecOrNull
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.decision.BackendOutcome
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.decision.TypeSafeBackend
import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.typesafe.sdk.TypeSafeClient
import me.ethanxu.typesafe.sdk.TypeSafeConfig

/** Each request uses the current saved settings; missing credentials fail open. */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun provideDecisionBackend(settings: SettingsRepository): DecisionBackend = object : DecisionBackend {
        override suspend fun decide(input: DecisionInput): BackendOutcome = withContext(Dispatchers.IO) {
            settings.awaitReady()
            val prefs = settings.preferences.value
            // The BuildConfig fallback is populated only in debug builds.
            val key = prefs.apiKey.ifBlank { BuildConfig.TYPESAFE_API_KEY }
            if (key.isBlank()) return@withContext BackendOutcome.Failed(FailureReason.NOT_CONFIGURED)
            try {
                val client = TypeSafeClient(TypeSafeConfig(
                    apiKey = key,
                    baseUrl = prefs.baseUrl,
                    defaultModel = prefs.model,
                    timeoutMs = prefs.timeoutMs,
                    runtimeDescriptor = "android/${Build.VERSION.RELEASE}",
                    proxy = verifiedProxySpecOrNull(prefs),
                ))
                try {
                    TypeSafeBackend(client).decide(input)
                } finally {
                    client.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                BackendOutcome.Failed(FailureReason.NOT_CONFIGURED, invalid.message)
            }
        }
    }
}
