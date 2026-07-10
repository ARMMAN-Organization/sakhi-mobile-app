package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.visit.StaticVisitRepository
import org.armman.sakhi.data.visit.VisitRepository
import javax.inject.Singleton

/** Binds the static visit implementation; swap when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisitModule {
  @Binds
  @Singleton
  abstract fun bindVisitRepository(impl: StaticVisitRepository): VisitRepository
}
