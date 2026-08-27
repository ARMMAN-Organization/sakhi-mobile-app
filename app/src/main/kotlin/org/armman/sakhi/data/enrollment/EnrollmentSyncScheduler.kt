package org.armman.sakhi.data.enrollment

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "enrollment_sync_now"
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [EnrollmentSyncWorker] runs. An interface (rather than a concrete WorkManager-backed
 * class directly) so [RoomEnrollmentRepository] can be unit-tested against a fake — this repo has
 * no Robolectric/instrumented test setup, so [WorkManagerEnrollmentSyncScheduler] itself is
 * exercised only manually/on-device, same as other Android-framework-backed classes here (see
 * [org.armman.sakhi.data.auth.session.EncryptedSharedPreferencesStore]).
 *
 * **Manual-sync-only (SRS §3A.1).** No periodic-scheduling method exists; the only caller of
 * [syncNow] is [org.armman.sakhi.data.sync.ManualSyncTrigger], driven by the Sakhi tapping Data
 * Upload. This legacy static-enrollment queue is unreachable from navigation today, but it is
 * still drained by the manual trigger so any rows left behind by an older build can still reach
 * the server.
 */
interface EnrollmentSyncScheduler {
  /**
   * Enqueues one sync attempt over the pending queue. Carries a `NetworkType.CONNECTED`
   * constraint, so a tap made while offline stays queued and runs when connectivity returns —
   * still a user-initiated sync (SRS "Deferred with retry"), not a background trigger.
   */
  fun syncNow()
}

/** Real, WorkManager-backed [EnrollmentSyncScheduler]. */
@Singleton
class WorkManagerEnrollmentSyncScheduler @Inject constructor(
  private val workManager: WorkManager,
) : EnrollmentSyncScheduler {
  private val networkConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  override fun syncNow() {
    val request = OneTimeWorkRequestBuilder<EnrollmentSyncWorker>()
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    // REPLACE, not KEEP — see WorkManagerDynamicFormSyncScheduler.syncNow() for the full rationale:
    // under KEEP a tap made during the retry backoff window is silently discarded, and manual sync
    // is now the only mechanism.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
  }
}
