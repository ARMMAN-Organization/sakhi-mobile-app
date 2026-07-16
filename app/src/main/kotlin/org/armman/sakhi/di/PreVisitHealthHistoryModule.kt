package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryRepository
import org.armman.sakhi.data.previsithealth.StaticPreVisitHealthHistoryRepository
import javax.inject.Singleton

/** Binds the in-memory Pre-Visit Health History store until real persistence exists. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PreVisitHealthHistoryModule {

  @Binds
  @Singleton
  abstract fun bindPreVisitHealthHistoryRepository(
    impl: StaticPreVisitHealthHistoryRepository,
  ): PreVisitHealthHistoryRepository
}
