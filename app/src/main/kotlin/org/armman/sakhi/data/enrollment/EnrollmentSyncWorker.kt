package org.armman.sakhi.data.enrollment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Thin WorkManager/Hilt adapter over [EnrollmentSyncExecutor], which holds all the real sync
 * logic. Enqueued network-constrained (see [EnrollmentSyncScheduler]) so WorkManager itself — not
 * this class — is responsible for waiting until connectivity is available; that's what makes a
 * Sakhi's offline "Submit" eventually reach the server with no further action from her.
 *
 * Deliberately not unit-tested directly: `CoroutineWorker` requires a real `android.content.Context`
 * and `WorkerParameters`, which this repo's JVM-only test setup (no Robolectric) can't construct.
 * [EnrollmentSyncExecutor] carries the actual logic and is fully covered instead.
 */
@HiltWorker
class EnrollmentSyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val executor: EnrollmentSyncExecutor,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    when (executor.run()) {
      EnrollmentSyncOutcome.COMPLETED -> Result.success()
      // Re-runs the whole worker under WorkManager's exponential backoff; permanent per-item
      // failures are already recorded by the executor and picked up again on the next scheduled
      // run without needing this retry.
      EnrollmentSyncOutcome.RETRYABLE_FAILURE -> Result.retry()
    }
}
