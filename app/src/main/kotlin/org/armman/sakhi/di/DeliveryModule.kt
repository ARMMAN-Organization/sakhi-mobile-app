package org.armman.sakhi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.DeliveryChildRegistrationSyncScheduler
import org.armman.sakhi.data.delivery.DeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.DeliverySessionRepository
import org.armman.sakhi.data.delivery.DeliverySyncScheduler
import org.armman.sakhi.data.delivery.RoomDeliveryChildRegistrationDraftRepository
import org.armman.sakhi.data.delivery.RoomDeliveryFormDraftRepository
import org.armman.sakhi.data.delivery.RoomDeliverySessionRepository
import org.armman.sakhi.data.delivery.WorkManagerDeliveryChildRegistrationSyncScheduler
import org.armman.sakhi.data.delivery.WorkManagerDeliverySyncScheduler
import javax.inject.Singleton

/** Delivery Event Session bindings (CR-042). [org.armman.sakhi.data.delivery
 * .DeliveryFormSubmissionCoordinator], [org.armman.sakhi.data.delivery.DeliveryFormSyncExecutor],
 * [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSubmissionCoordinator] and
 * [org.armman.sakhi.data.delivery.DeliveryChildRegistrationSyncExecutor] need no binding (concrete
 * `@Inject` classes, same as their ad-hoc-form equivalents);
 * [org.armman.sakhi.data.delivery.DeliveryFormDraftDao]/[org.armman.sakhi.data.delivery
 * .DeliverySessionDao]/[org.armman.sakhi.data.delivery.DeliveryChildRegistrationDraftDao] are
 * provided by [DatabaseModule].
 *
 * [DeliverySyncScheduler]/[DeliveryChildRegistrationSyncScheduler] bindings added 2026-09-11 — see
 * those interfaces' own doc for why [org.armman.sakhi.data.sync.ManualSyncTrigger] previously had
 * no way to retry a delivery draft queued offline. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DeliveryModule {

  @Binds
  @Singleton
  abstract fun bindDeliverySessionRepository(
    impl: RoomDeliverySessionRepository,
  ): DeliverySessionRepository

  @Binds
  @Singleton
  abstract fun bindDeliveryFormDraftRepository(
    impl: RoomDeliveryFormDraftRepository,
  ): DeliveryFormDraftRepository

  @Binds
  @Singleton
  abstract fun bindDeliveryChildRegistrationDraftRepository(
    impl: RoomDeliveryChildRegistrationDraftRepository,
  ): DeliveryChildRegistrationDraftRepository

  @Binds
  @Singleton
  abstract fun bindDeliverySyncScheduler(
    impl: WorkManagerDeliverySyncScheduler,
  ): DeliverySyncScheduler

  @Binds
  @Singleton
  abstract fun bindDeliveryChildRegistrationSyncScheduler(
    impl: WorkManagerDeliveryChildRegistrationSyncScheduler,
  ): DeliveryChildRegistrationSyncScheduler
}
