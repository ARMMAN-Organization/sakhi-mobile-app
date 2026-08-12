package org.armman.sakhi.data.visitform

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "visit_form_drafts"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [VisitFormSyncWorker] runs — the CR-026b fifth offline queue, alongside the three form
 * drafts queues and the visit-schedules queue. Same interface-vs-concrete-class split as those, for
 * the same reason (no Robolectric here).
 *
 * **Manual-sync-only (SRS §3A.1).** The only caller of [syncNow] is
 * [org.armman.sakhi.data.sync.ManualSyncTrigger], plus the submit-time attempt
 * [RoomVisitFormDraftRepository] makes while already online. Nothing schedules this on a timer,
 * app start, or reconnect.
 */
interface VisitFormSyncScheduler {
  /** Enqueues one sync attempt over the pending queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a tap made while offline stays queued and runs when connectivity returns. */
  fun syncNow()
}

@Singleton
class WorkManagerVisitFormSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : VisitFormSyncScheduler {

  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<VisitFormSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — same rationale as every other queue's scheduler: KEEP would silently
    // discard a tap made during an existing backoff window, and manual sync is the only mechanism
    // there is. Re-attempting a draft that's mid-flight is safe: attemptSync resumes from
    // serverVisitId rather than re-creating the visit instance.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
