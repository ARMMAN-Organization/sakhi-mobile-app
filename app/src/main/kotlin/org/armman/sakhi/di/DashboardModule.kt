package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.dashboard.DashboardRepository
import org.armman.sakhi.data.dashboard.StaticDashboardRepository
import javax.inject.Singleton

/** Binds the static dashboard implementation; swap when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardModule {
  @Binds
  @Singleton
  abstract fun bindDashboardRepository(impl: StaticDashboardRepository): DashboardRepository
}
