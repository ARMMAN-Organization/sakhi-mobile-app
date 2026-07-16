package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.enrollment.EnrollmentRepository
import org.armman.sakhi.data.enrollment.StaticEnrollmentRepository
import javax.inject.Singleton

/** Binds the in-memory enrollment store until real persistence exists. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnrollmentModule {

  @Binds
  @Singleton
  abstract fun bindEnrollmentRepository(impl: StaticEnrollmentRepository): EnrollmentRepository
}
