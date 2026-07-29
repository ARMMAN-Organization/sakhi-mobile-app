package org.armman.sakhi.data.forms

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "dynamic_form_sync_now"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [DynamicFormSyncWorker] runs — the CR-018 twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler]. Same interface-vs-concrete-class
 * split for the same reason: this repo has no Robolectric setup, so the real WorkManager-backed
 * implementation is exercised manually/on-device, while callers depending on the interface stay
 * unit-testable against a fake.
 *
 * **Manual-sync-only (SRS §3A.1 "Data Sync — Manual trigger. Deferred with retry.").** There is
 * deliberately no periodic-scheduling method here: the ONLY caller of [syncNow] is the Sakhi
 * tapping the Home screen's Data Upload pill (see
 * [org.armman.sakhi.data.sync.ManualSyncTrigger]), plus the submit-time attempt the repository
 * makes while already online. Nothing schedules a sync on a timer, on app start, or on
 * connectivity returning. Do not add an `ensurePeriodicSyncScheduled` back without an SRS change.
 */
interface DynamicFormSyncScheduler {
  /**
   * Enqueues one sync attempt over the pending queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a tap made while offline stays queued and runs when connectivity returns —
   * that deferral is still a user-initiated sync (SRS "Deferred with retry"), not a background
   * trigger.
   */
  fun syncNow()
}

@Singleton
class WorkManagerDynamicFormSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : DynamicFormSyncScheduler {
  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<DynamicFormSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, deliberately, not KEEP.
    //
    // KEEP discards the new request whenever existing work with this name is unfinished. After a
    // failed run the worker returns Result.retry(), which puts the work back to ENQUEUED with an
    // exponential backoff delay (30s, 60s, 120s ... up to WorkManager's 5h cap). Under KEEP, every
    // Data Upload tap during that window was silently thrown away — so after a few failures the
    // Sakhi could tap forever and nothing would happen. That was survivable when a 15-minute
    // periodic job existed behind it; with manual-only sync this is the only mechanism there is.
    //
    // REPLACE cancels the waiting (or running) attempt and starts one immediately, which is what a
    // user-initiated action has to do. Re-attempting a draft that may already be mid-flight is safe:
    // both API calls are idempotent on localCaseUuid / localSubmissionUuid. Cancelling a RUNNING
    // worker can leave a draft stranded in SYNCING, which is why DynamicFormSyncExecutor.run()
    // reclaims stale SYNCING rows before each pass — the two changes only work as a pair.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
