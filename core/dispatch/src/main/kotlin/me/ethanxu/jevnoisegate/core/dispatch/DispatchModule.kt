package me.ethanxu.jevnoisegate.core.dispatch

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.core.data.settings.SettingsRepository
import me.ethanxu.jevnoisegate.core.decision.CategoriesProvider
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DefaultCategories
import me.ethanxu.jevnoisegate.core.decision.applyCategoryOverrides
import me.ethanxu.jevnoisegate.core.pipeline.MessagePipeline

@Module
@InstallIn(SingletonComponent::class)
object DispatchModule {

    @Provides
    @Singleton
    fun provideMessagePipeline(backend: DecisionBackend): MessagePipeline =
        MessagePipeline(backend)

    /**
     * 出厂分类 + 用户在设置里做的覆盖。
     *
     * 合并规则本身在 [applyCategoryOverrides] —— 设置页要摆滑块位置，
     * 用的是同一个函数，避免两边算出不同的答案。
     *
     * 每次 `current()` 都重新读一遍偏好，而不是在构造时读一次：
     * 用户拖完滑块，下一条通知就该按新门槛判断，不该要求重启。
     * 代价是读一次 StateFlow 加四个元素的 map，相对一次网络往返可忽略。
     */
    @Provides
    @Singleton
    fun provideCategoriesProvider(settings: SettingsRepository): CategoriesProvider =
        CategoriesProvider {
            val prefs = settings.preferences.value
            applyCategoryOverrides(
                defaults = DefaultCategories.all(),
                thresholds = prefs.categoryThresholds,
                actions = prefs.categoryActions,
            )
        }
}
