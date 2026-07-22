package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.enrollment.EnrollmentApi
import org.armman.sakhi.data.enrollment.EnrollmentRepository
import org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler
import org.armman.sakhi.data.enrollment.RoomEnrollmentRepository
import org.armman.sakhi.data.enrollment.WorkManagerEnrollmentSyncScheduler
import retrofit2.Retrofit
import javax.inject.Singleton

/** Binds the offline-first, Room + encrypted-store-backed enrollment repository (task #14/#16).
 * [org.armman.sakhi.data.enrollment.StaticEnrollmentRepository] (in-memory, CR-015d) is superseded
 * — kept in the codebase only for its existing unit tests until those are ported to the new repo. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnrollmentModule {

  @Binds
  @Singleton
  abstract fun bindEnrollmentRepository(impl: RoomEnrollmentRepository): EnrollmentRepository

  @Binds
  @Singleton
  abstract fun bindEnrollmentSyncScheduler(
    impl: WorkManagerEnrollmentSyncScheduler,
  ): EnrollmentSyncScheduler

  companion object {
    @Provides
    @Singleton
    fun provideEnrollmentApi(retrofit: Retrofit): EnrollmentApi =
      retrofit.create(EnrollmentApi::class.java)
  }
}
