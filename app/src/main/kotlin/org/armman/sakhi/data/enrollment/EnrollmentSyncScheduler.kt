package org.armman.sakhi.data.enrollment

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val ONE_TIME_WORK_NAME = "enrollment_sync_now"
private const val PERIODIC_WORK_NAME = "enrollment_sync_periodic"
private const val PERIODIC_INTERVAL_MINUTES = 15L
private const val BACKOFF_DELAY_SECONDS = 30L

/**
 * Schedules [EnrollmentSyncWorker] runs. An interface (rather than a concrete WorkManager-backed
 * class directly) so [RoomEnrollmentRepository] can be unit-tested against a fake — this repo has
 * no Robolectric/instrumented test setup, so [WorkManagerEnrollmentSyncScheduler] itself is
 * exercised only manually/on-device, same as other Android-framework-backed classes here (see
 * [org.armman.sakhi.data.auth.session.EncryptedSharedPreferencesStore]).
 *
 * Both real implementations of these calls share the same "requires network" constraint —
 * WorkManager itself defers execution while offline and starts it the moment connectivity
 * returns, which is what actually delivers the offline-first guarantee (implementations don't
 * need to watch connectivity themselves).
 */
interface EnrollmentSyncScheduler {
  /** Nudges an immediate sync attempt. A no-op (deferred by WorkManager) if offline, an
   * immediate submit if online — called right after [RoomEnrollmentRepository.saveEnrollment] so
   * a draft saved while online syncs promptly instead of waiting for the next periodic tick. */
  fun syncNow()

  /** Standing safety net (call once at app startup) that catches anything [syncNow] missed, e.g.
   * the app being killed before a queued one-shot sync got to run. */
  fun ensurePeriodicSyncScheduled()
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
    // KEEP: if a sync is already queued/running, this save just rides along with it rather than
    // enqueueing a redundant duplicate run.
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }

  override fun ensurePeriodicSyncScheduled() {
    val request = PeriodicWorkRequestBuilder<EnrollmentSyncWorker>(
      PERIODIC_INTERVAL_MINUTES,
      TimeUnit.MINUTES,
    )
      .setConstraints(networkConstraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
      .build()
    workManager.enqueueUniquePeriodicWork(
      PERIODIC_WORK_NAME,
      ExistingPeriodicWorkPolicy.KEEP,
      request,
    )
  }
}
