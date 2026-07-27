package org.armman.sakhi.data.childregistration

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

/** DISTINCT unique work names from the mother flow's `dynamic_form_sync_*` so the two feature
 * queues are scheduled and de-duplicated independently by WorkManager. */
private const val ONE_TIME_WORK_NAME = "child_form_sync_now"
private const val PERIODIC_WORK_NAME = "child_form_sync_periodic"
private const val PERIODIC_INTERVAL_MINUTES = 15L
private const val BACKOFF_DELAY_SECONDS = 30L

/** Schedules [ChildFormSyncWorker] runs — the CR-020 twin of
 * [org.armman.sakhi.data.forms.DynamicFormSyncScheduler]. Same interface-vs-concrete-class split
 * for the same reason: this repo has no Robolectric setup, so the real WorkManager-backed
 * implementation is exercised manually/on-device, while callers depending on the interface stay
 * unit-testable against a fake. */
interface ChildFormSyncScheduler {
  fun syncNow()
  fun ensurePeriodicSyncScheduled()
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
    workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
  }

  override fun ensurePeriodicSyncScheduled() {
    val request = PeriodicWorkRequestBuilder<ChildFormSyncWorker>(
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
