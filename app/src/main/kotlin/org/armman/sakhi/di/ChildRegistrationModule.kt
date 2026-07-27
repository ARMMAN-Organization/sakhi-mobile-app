package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.childregistration.ChildFormDraftRepository
import org.armman.sakhi.data.childregistration.ChildFormSyncScheduler
import org.armman.sakhi.data.childregistration.RoomChildFormDraftRepository
import org.armman.sakhi.data.childregistration.WorkManagerChildFormSyncScheduler
import javax.inject.Singleton

/** Binds the CR-020 Children Register offline draft store and sync scheduler — the standalone twin
 * of [FormsModule]'s dynamic-form bindings. The `EnrollmentApi`/`FormSubmissionApi` the child
 * coordinator depends on are already provided (EnrollmentModule / FormsModule); the child DAO is
 * provided by [DatabaseModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChildRegistrationModule {
  @Binds
  @Singleton
  abstract fun bindChildFormDraftRepository(impl: RoomChildFormDraftRepository): ChildFormDraftRepository

  @Binds
  @Singleton
  abstract fun bindChildFormSyncScheduler(impl: WorkManagerChildFormSyncScheduler): ChildFormSyncScheduler
}
