package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.rules.RemoteRuleSetRepository
import org.armman.sakhi.data.rules.RuleEvaluator
import org.armman.sakhi.data.rules.RuleSetApi
import org.armman.sakhi.data.rules.RuleSetRepository
import org.armman.sakhi.data.rules.ZenRuleEvaluator
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * GoRules rule-package fetch/cache/evaluate bindings (CR-032).
 *
 * No special Gson needs here (unlike `ScheduleModule.provideVisitScheduleApi` or
 * `FormsModule.provideFormsApi`) — [RuleSetApi] reuses the app-wide [Retrofit] instance from
 * [NetworkModule], same as `FormsModule.provideFormSubmissionApi`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RulesModule {

  @Binds
  @Singleton
  abstract fun bindRuleSetRepository(impl: RemoteRuleSetRepository): RuleSetRepository

  /**
   * The local-execution swap point (see [RuleEvaluator]'s doc) — if the official GoRules Android
   * binding proves unworkable, only this line changes to point at a replacement implementation.
   */
  @Binds
  @Singleton
  abstract fun bindRuleEvaluator(impl: ZenRuleEvaluator): RuleEvaluator

  companion object {
    @Provides
    @Singleton
    fun provideRuleSetApi(retrofit: Retrofit): RuleSetApi = retrofit.create(RuleSetApi::class.java)
  }
}
