package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.healtheducation.HealthEducationApi
import org.armman.sakhi.data.healtheducation.HealthEducationRepository
import org.armman.sakhi.data.healtheducation.RemoteHealthEducationRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * CR-M3-06 bindings, against backend's confirmed real "Learn More" contract — see
 * [HealthEducationApi]'s class doc for the endpoints and what this superseded.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HealthEducationModule {

  @Binds
  @Singleton
  abstract fun bindHealthEducationRepository(impl: RemoteHealthEducationRepository): HealthEducationRepository

  companion object {
    @Provides
    @Singleton
    fun provideHealthEducationApi(retrofit: Retrofit): HealthEducationApi =
      retrofit.create(HealthEducationApi::class.java)
  }
}
