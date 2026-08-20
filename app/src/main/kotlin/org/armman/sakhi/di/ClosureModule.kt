package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.closure.ClosureApi
import org.armman.sakhi.data.closure.ClosureRepository
import org.armman.sakhi.data.closure.RemoteClosureRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the real `POST /closures` implementation, confirmed live by the backend team — mirrors
 * [ReferralModule]'s style. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClosureModule {
  @Binds
  @Singleton
  abstract fun bindClosureRepository(impl: RemoteClosureRepository): ClosureRepository

  companion object {
    @Provides
    @Singleton
    fun provideClosureApi(retrofit: Retrofit): ClosureApi = retrofit.create(ClosureApi::class.java)
  }
}
