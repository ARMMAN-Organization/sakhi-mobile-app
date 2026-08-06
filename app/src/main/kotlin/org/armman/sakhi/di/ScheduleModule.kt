package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.ScheduleRuleSource
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleApi
import org.armman.sakhi.data.schedule.VisitScheduleSyncScheduler
import org.armman.sakhi.data.schedule.WorkManagerVisitScheduleSyncScheduler
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Visit scheduling bindings (CR-022).
 *
 * [bindScheduleRuleSource] is **the M3 swap point**: replacing [HardcodedRuleSource] with
 * `GoRulesRuleSource` (CR-032) moves every scheduling rule from Kotlin constants to published,
 * versioned rule packages, and this one line is the only production change required. Nothing in
 * `data/schedule/` depends on the concrete type.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScheduleModule {

  @Binds
  @Singleton
  abstract fun bindVisitScheduleRepository(
    impl: RoomVisitScheduleRepository,
  ): VisitScheduleRepository

  /** M2: rules in Kotlin. M3 (CR-032): change this to `GoRulesRuleSource` and nothing else. */
  @Binds
  @Singleton
  abstract fun bindScheduleRuleSource(impl: HardcodedRuleSource): ScheduleRuleSource

  @Binds
  @Singleton
  abstract fun bindVisitScheduleSyncScheduler(
    impl: WorkManagerVisitScheduleSyncScheduler,
  ): VisitScheduleSyncScheduler

  companion object {
    @Provides
    @Singleton
    fun provideVisitScheduleApi(retrofit: Retrofit): VisitScheduleApi =
      retrofit.create(VisitScheduleApi::class.java)
  }
}
