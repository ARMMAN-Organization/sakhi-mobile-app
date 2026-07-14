package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.geography.GeographyRepository
import org.armman.sakhi.data.geography.StaticGeographyRepository
import javax.inject.Singleton

/** Binds the static geography source until the geography API exists. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GeographyModule {

  @Binds
  @Singleton
  abstract fun bindGeographyRepository(impl: StaticGeographyRepository): GeographyRepository
}
