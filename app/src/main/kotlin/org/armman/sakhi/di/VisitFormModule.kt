package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.visitform.StaticVisitFormRepository
import org.armman.sakhi.data.visitform.VisitFormRepository
import javax.inject.Singleton

/** Binds the in-memory Visit Form context store until real persistence exists. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisitFormModule {

  @Binds
  @Singleton
  abstract fun bindVisitFormRepository(impl: StaticVisitFormRepository): VisitFormRepository
}
