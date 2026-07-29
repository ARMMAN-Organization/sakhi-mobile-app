package org.armman.sakhi.data.childregistration

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** DISTINCT unique work name from the mother flow's `dynamic_form_sync_now` so the two feature
 * queues are scheduled and de-duplicated independently by WorkManager. */
private const val ONE_TIME_WORK_NAME = "child_form_sync_now"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [ChildFormSyncWorker] runs — the CR-020 twin of
 * [org.armman.sakhi.data.forms.DynamicFormSyncScheduler]. Same interface-vs-concrete-class split
 * for the same reason: this repo has no Robolectric setup, so the real WorkManager-backed
 * implementation is exercised manually/on-device, while callers depending on the interface stay
 * unit-testable against a fake.
 *
 * **Manual-sync-only (SRS §3A.1).** No periodic-scheduling method exists; the only caller of
 * [syncNow] is [org.armman.sakhi.data.sync.ManualSyncTrigger], driven by the Sakhi tapping Data
 * Upload.
 */
interface ChildFormSyncScheduler {
  /**
   * Enqueues one sync attempt over the pending queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a tap made while offline stays queued and runs when connectivity returns —
   * still a user-initiated sync (SRS "Deferred with retry"), not a background trigger.
   */
  fun syncNow()
}

@Singleton
class WorkManagerChildFormSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : ChildFormSyncScheduler {
  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<ChildFormSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — see WorkManagerDynamicFormSyncScheduler.syncNow() for the full rationale:
    // under KEEP a tap made during the retry backoff window is silently discarded, and manual sync
    // is now the only mechanism. Safe to re-attempt (idempotent on localCaseUuid /
    // localSubmissionUuid), and ChildFormSyncExecutor.run() reclaims stale SYNCING rows.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
