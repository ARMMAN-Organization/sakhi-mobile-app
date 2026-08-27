package org.armman.sakhi.di

import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.armman.sakhi.BuildConfig
import org.armman.sakhi.data.schedule.HardcodedRuleSource
import org.armman.sakhi.data.schedule.RoomVisitScheduleRepository
import org.armman.sakhi.data.schedule.ScheduleRuleSource
import org.armman.sakhi.data.schedule.VisitScheduleRepository
import org.armman.sakhi.data.schedule.VisitScheduleApi
import org.armman.sakhi.data.schedule.VisitScheduleSyncScheduler
import org.armman.sakhi.data.schedule.WorkManagerVisitScheduleSyncScheduler
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
    /**
     * Deliberately its own [Retrofit] instance rather than reusing the app-wide one from
     * `NetworkModule` — this is the only endpoint that needs `serializeNulls()`. The backend's
     * `POST /visit-schedules/bulk` Zod schema requires `anchorVisitLocalUuid` present as a key
     * (nullable, but not optional) on every schedule row; the app-wide Gson instance omits null
     * fields entirely, which the backend rejects with a 400 "Required" error (confirmed on a real
     * device). Scoping this to just this API avoids changing null-handling for every other
     * endpoint, several of which rely on the default omit-null behavior.
     */
    @Provides
    @Singleton
    fun provideVisitScheduleApi(client: OkHttpClient): VisitScheduleApi {
      val nullSerializingGson = GsonBuilder().serializeNulls().create()
      val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(nullSerializingGson))
        .build()
      return retrofit.create(VisitScheduleApi::class.java)
    }
  }
}
