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

private const val ONE_TIME_WORK_NAME = "delivery_form_drafts"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [DeliverySyncWorker] runs — the CR-042 twin of
 * [org.armman.sakhi.data.adhocform.AdHocFormSyncScheduler].
 *
 * Bharath, 2026-09-11 ("delivery form filled offline shows 'will sync automatically once you have
 * network connectivity' but never actually syncs, even after reconnecting"): [DeliveryFormSyncExecutor]
 * itself has always existed with the correct PENDING/SYNCING/FAILED/SYNCED lifecycle — its own doc
 * used to say "no WorkManager scheduler/worker is wired up for this queue yet", same as the ad-hoc
 * queue's had before this class's twin was added. Unlike the ad-hoc queue though, nothing here EVER
 * called [DeliveryFormSyncExecutor.run] again after the first online attempt (made eagerly inside
 * [RoomDeliveryFormDraftRepository.submitDraft]) failed or was skipped for being offline — so a
 * queued draft sat in PENDING forever, un-retried by anything, contradicting the very toast the
 * Sakhi saw. This scheduler plus [org.armman.sakhi.data.sync.ManualSyncTrigger] wiring it into the
 * Home "Data Upload" fan-out is what actually makes the retry happen — manually, same as every
 * other queue in this app (SRS §3A.1), never automatically on reconnect.
 *
 * **Manual-sync-only.** The only caller of [syncNow] is
 * [org.armman.sakhi.data.sync.ManualSyncTrigger], plus the submit-time attempt
 * [RoomDeliveryFormDraftRepository] makes while already online. Nothing schedules this on a timer,
 * app start, or reconnect.
 */
interface DeliverySyncScheduler {
  /** Enqueues one sync attempt over the pending queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a tap made while offline stays queued and runs when connectivity returns. */
  fun syncNow()
}

@Singleton
class WorkManagerDeliverySyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : DeliverySyncScheduler {

  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<DeliverySyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — same rationale as every other queue's scheduler: KEEP would silently
    // discard a tap made during an existing backoff window, and manual sync is the only mechanism
    // there is.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
