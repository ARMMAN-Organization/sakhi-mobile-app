package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.dashboard.DashboardApi
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.RemoteDashboardRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * M3: `GET /sakhi/{sakhiId}/dashboard` is confirmed live (mock, 2026-08-14) — binds the real
 * dashboard implementation. `StaticDashboardRepository` and its hardcoded Figma placeholder
 * values are gone; every field now comes from the backend, with an offline cache fallback (see
 * [RemoteDashboardRepository]).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardModule {
  @Binds
  @Singleton
  abstract fun bindDashboardRepository(impl: RemoteDashboardRepository): DashboardRepository

  companion object {
    @Provides
    @Singleton
    fun provideDashboardApi(retrofit: Retrofit): DashboardApi = retrofit.create(DashboardApi::class.java)
  }
}
