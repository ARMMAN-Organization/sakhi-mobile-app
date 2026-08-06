package org.armman.sakhi.data.schedule

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** DISTINCT unique work name from the three form queues, so WorkManager schedules and de-duplicates
 * the schedule queue independently. */
private const val ONE_TIME_WORK_NAME = "visit_schedule_sync_now"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [VisitScheduleSyncWorker] runs — the CR-022e sibling of the three form-queue
 * schedulers. Same interface-vs-concrete split for the same reason: no Robolectric here, so the
 * WorkManager-backed implementation is exercised on-device while callers stay unit-testable against
 * a fake.
 *
 * **Manual-sync-only (SRS §3A.1).** No periodic method exists; the only caller of [syncNow] is
 * [org.armman.sakhi.data.sync.ManualSyncTrigger], driven by the Sakhi tapping Data Upload.
 */
interface VisitScheduleSyncScheduler {
  /**
   * Enqueues one upload attempt. Carries a `NetworkType.CONNECTED` constraint, so a tap made while
   * offline stays queued and runs when connectivity returns — still a user-initiated sync
   * ("deferred with retry"), not a background trigger.
   */
  fun syncNow()
}

@Singleton
class WorkManagerVisitScheduleSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : VisitScheduleSyncScheduler {

  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<VisitScheduleSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — under KEEP a tap made during the retry backoff window is silently
    // discarded, and manual sync is the only mechanism there is. Safe to re-attempt: the bulk
    // endpoint is idempotent on localScheduleUuid (CR-023 §5.1).
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
