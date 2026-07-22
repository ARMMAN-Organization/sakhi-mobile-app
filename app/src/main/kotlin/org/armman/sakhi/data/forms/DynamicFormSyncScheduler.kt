package org.armman.sakhi.data.forms

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

private const val ONE_TIME_WORK_NAME = "dynamic_form_sync_now"
private const val PERIODIC_WORK_NAME = "dynamic_form_sync_periodic"
private const val PERIODIC_INTERVAL_MINUTES = 15L
private const val BACKOFF_DELAY_SECONDS = 30L

/** Schedules [DynamicFormSyncWorker] runs — the CR-018 twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncScheduler]. Same interface-vs-concrete-class
 * split for the same reason: this repo has no Robolectric setup, so the real WorkManager-backed
 * implementation is exercised manually/on-device, while callers depending on the interface stay
 * unit-testable against a fake. */
interface DynamicFormSyncScheduler {
  fun syncNow()
  fun ensurePeriodicSyncScheduled()
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
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }

  override fun ensurePeriodicSyncScheduled() {
    val request = PeriodicWorkRequestBuilder<DynamicFormSyncWorker>(
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
