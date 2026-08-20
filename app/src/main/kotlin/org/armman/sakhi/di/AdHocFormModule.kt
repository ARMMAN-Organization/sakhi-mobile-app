package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.adhocform.AdHocFormDraftRepository
import org.armman.sakhi.data.adhocform.AdHocFormSyncScheduler
import org.armman.sakhi.data.adhocform.RoomAdHocFormDraftRepository
import org.armman.sakhi.data.adhocform.WorkManagerAdHocFormSyncScheduler
import javax.inject.Singleton

/** Binds the ad-hoc-form offline-submit queue's repository and scheduler — mirrors
 * [VisitFormModule]'s style. [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator] and
 * [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutor] need no binding (concrete `@Inject`
 * classes, same as their visit-form equivalents); [org.armman.sakhi.data.adhocform.AdHocFormDraftDao]
 * is provided by [DatabaseModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdHocFormModule {

  @Binds
  @Singleton
  abstract fun bindAdHocFormDraftRepository(impl: RoomAdHocFormDraftRepository): AdHocFormDraftRepository

  @Binds
  @Singleton
  abstract fun bindAdHocFormSyncScheduler(impl: WorkManagerAdHocFormSyncScheduler): AdHocFormSyncScheduler
}
