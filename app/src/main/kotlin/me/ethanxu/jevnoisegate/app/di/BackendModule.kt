package me.ethanxu.jevnoisegate.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.app.BuildConfig
import me.ethanxu.jevnoisegate.app.verifiedProxySpecOrNull
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.TypeSafeBackend
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.typesafe.sdk.TypeSafeClient
import me.ethanxu.typesafe.sdk.TypeSafeConfig

/**
 * 判断后端的装配。
 *
 * API key 来自 `local.properties`（`typesafe.apiKey=...`），经 BuildConfig 注入。
 * 这里刻意**不**在 key 缺失时抛异常：那样应用会直接起不来，而"没有 key"是一个
 * 正常状态（用户还没配置）。改为提供一个必然失败的[DecisionBackend]，
 * 让流水线走 fail-open 放行 —— 这与整个系统的失败哲学一致，也便于在诊断页显示原因。
 */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {

    @Provides
    @Singleton
    fun provideTypeSafeClient(settings: SettingsRepository): TypeSafeClient {
        // awaitReady 已在 JevNoiseGateApp.onCreate 里调用过，因此这里读到的是真实配置。
        val prefs = settings.preferences.value
        return TypeSafeClient(
            TypeSafeConfig(
                // 用户配置优先；其次回退到 local.properties 注入的 key；最后才是占位符。
                apiKey = prefs.apiKey.ifBlank { BuildConfig.TYPESAFE_API_KEY }.ifBlank { PLACEHOLDER_KEY },
                baseUrl = prefs.baseUrl,
                defaultModel = prefs.model,
                timeoutMs = prefs.timeoutMs,
                // SDK 的默认值是中性占位（commonMain 读不到平台版本号），这里补上真实信息。
                runtimeDescriptor = "android/${Build.VERSION.RELEASE}",
                // 只装配**已验证通过**的代理。未验证就退回直连 —— 一个没测通的代理
                // 比直连更糟：请求会一路超时，而日志里看不出是因为代理。
                // 传整个 prefs 而不是拆开的几项，凭据因此不会再被漏掉。
                proxy = verifiedProxySpecOrNull(prefs),
            ),
        )
    }

    @Provides
    @Singleton
    fun provideDecisionBackend(client: TypeSafeClient): DecisionBackend = TypeSafeBackend(client)

    /** 仅用于让 [TypeSafeConfig] 通过构造校验；真实请求会因鉴权失败而归入 INVALID_RESPONSE。 */
    private const val PLACEHOLDER_KEY = "not-configured"
}
