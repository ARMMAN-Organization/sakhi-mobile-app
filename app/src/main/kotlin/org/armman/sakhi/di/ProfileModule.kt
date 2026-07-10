package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.profile.ProfileRepository
import org.armman.sakhi.data.profile.StaticProfileRepository
import javax.inject.Singleton

/** Binds the static profile implementation; swap when the API lands. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileModule {
  @Binds
  @Singleton
  abstract fun bindProfileRepository(impl: StaticProfileRepository): ProfileRepository
}
