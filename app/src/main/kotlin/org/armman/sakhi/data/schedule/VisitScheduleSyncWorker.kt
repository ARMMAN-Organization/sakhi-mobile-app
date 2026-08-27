package org.armman.sakhi.data.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome

/** Thin WorkManager/Hilt adapter over [VisitScheduleSyncExecutor] — the CR-022e sibling of the
 * three form-queue workers; the real logic lives in the executor so it stays testable without a
 * real `Context`/`WorkerParameters`. */
@HiltWorker
class VisitScheduleSyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val executor: VisitScheduleSyncExecutor,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    when (executor.run()) {
      EnrollmentSyncOutcome.COMPLETED -> Result.success()
      EnrollmentSyncOutcome.RETRYABLE_FAILURE -> Result.retry()
    }
}
