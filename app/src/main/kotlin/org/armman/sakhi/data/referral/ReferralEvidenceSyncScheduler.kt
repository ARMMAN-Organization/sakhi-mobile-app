package org.armman.sakhi.data.referral

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "referral_evidence_media"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [ReferralEvidenceSyncWorker] runs — the seventh offline queue, alongside the other
 * `*_drafts`/`visit_schedules` queues. Same interface-vs-concrete-class split as those (no
 * Robolectric here).
 *
 * Enqueued from two places, unlike the manual-sync-only queues:
 * [org.armman.sakhi.data.adhocform.AdHocFormSubmissionCoordinator] calls [syncNow] right after
 * queuing a Referral Follow-up's captured evidence (already eligible at insert time, keyed by
 * [ReferralEvidenceMediaEntity.submissionId] — see that field's doc), and
 * [org.armman.sakhi.data.sync.ManualSyncTrigger] also fans out to it so a stuck/offline-queued
 * item still gets picked up by an explicit Data Upload tap.
 */
interface ReferralEvidenceSyncScheduler {
  /** Enqueues one sync attempt over the pending evidence queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a capture made while offline stays queued and runs when connectivity returns. */
  fun syncNow()
}

@Singleton
class WorkManagerReferralEvidenceSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : ReferralEvidenceSyncScheduler {

  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<ReferralEvidenceSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — same rationale as every other queue's scheduler (see
    // ManualSyncTrigger's doc): KEEP would silently discard a newly-queued photo made during an
    // existing backoff window.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
