package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryApi
import org.armman.sakhi.data.previsithealth.PreVisitHealthHistoryRepository
import org.armman.sakhi.data.previsithealth.RemotePreVisitHealthHistoryRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real Pre-Visit Health History store, backed by
 * `GET /beneficiaries/:beneficiaryId/visit-history` (FR-S-4.6). Replaces the retired
 * `StaticPreVisitHealthHistoryRepository`'s in-memory 14-fixture stub now that the endpoint
 * exists — see [RemotePreVisitHealthHistoryRepository]'s own doc for the local-id/server-id
 * resolution this repository does before calling out. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PreVisitHealthHistoryModule {

  @Binds
  @Singleton
  abstract fun bindPreVisitHealthHistoryRepository(
    impl: RemotePreVisitHealthHistoryRepository,
  ): PreVisitHealthHistoryRepository

  companion object {
    @Provides
    @Singleton
    fun providePreVisitHealthHistoryApi(retrofit: Retrofit): PreVisitHealthHistoryApi =
      retrofit.create(PreVisitHealthHistoryApi::class.java)
  }
}
