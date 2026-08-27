package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.visit.RemoteVisitRepository
import org.armman.sakhi.data.visit.VisitRepository
import org.armman.sakhi.data.visittracker.VisitApi
import retrofit2.Retrofit
import javax.inject.Singleton

/** `GET /padas/{padaId}/visits` is confirmed live — binds the real per-pada visits
 * implementation, replacing the static today's-visits mock. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisitModule {
  @Binds
  @Singleton
  abstract fun bindVisitRepository(impl: RemoteVisitRepository): VisitRepository

  companion object {
    @Provides
    @Singleton
    fun provideVisitApi(retrofit: Retrofit): VisitApi = retrofit.create(VisitApi::class.java)
  }
}
