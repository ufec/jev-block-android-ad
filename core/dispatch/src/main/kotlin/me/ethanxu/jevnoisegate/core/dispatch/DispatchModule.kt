package me.ethanxu.jevnoisegate.core.dispatch

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.core.decision.CategoriesProvider
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DefaultCategories
import me.ethanxu.jevnoisegate.core.pipeline.MessagePipeline

@Module
@InstallIn(SingletonComponent::class)
object DispatchModule {

    @Provides
    @Singleton
    fun provideMessagePipeline(backend: DecisionBackend): MessagePipeline =
        MessagePipeline(backend)

    /**
     * 暂时固定返回出厂分类。
     *
     * 抽成接口是为了让"用户自定义分类"落地时只换实现，不动流水线 ——
     * 分类配置本来就是这个系统里**唯一承载知识**的东西，它必须可替换。
     */
    @Provides
    @Singleton
    fun provideCategoriesProvider(): CategoriesProvider =
        CategoriesProvider { DefaultCategories.all() }
}
