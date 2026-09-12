package org.armman.sakhi.data.delivery

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "delivery_child_registration_drafts"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [DeliveryChildRegistrationSyncWorker] runs — the CR-042 twin of [DeliverySyncScheduler]
 * for the step that follows it. See that class's doc for the bug this closes (bharath, 2026-09-11):
 * [DeliveryChildRegistrationSyncExecutor] already had the right retry lifecycle, nothing ever
 * invoked it again after an offline/failed submit.
 *
 * **Manual-sync-only.** The only caller of [syncNow] is
 * [org.armman.sakhi.data.sync.ManualSyncTrigger], plus the submit-time attempt
 * [RoomDeliveryChildRegistrationDraftRepository] makes while already online.
 */
interface DeliveryChildRegistrationSyncScheduler {
  fun syncNow()
}

@Singleton
class WorkManagerDeliveryChildRegistrationSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : DeliveryChildRegistrationSyncScheduler {

  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<DeliveryChildRegistrationSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
