package me.ethanxu.jevnoisegate.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import me.ethanxu.jevnoisegate.app.LocalLayaBackend
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {
    @Provides
    @Singleton
    fun provideDecisionBackend(local: LocalLayaBackend): DecisionBackend = local
}
