package org.armman.sakhi.data.forms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome

/** Thin WorkManager/Hilt adapter over [DynamicFormSyncExecutor] — the CR-018 twin of
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncWorker]; see that class's doc for why the real
 * logic lives in the executor instead of here. */
@HiltWorker
class DynamicFormSyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val executor: DynamicFormSyncExecutor,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result =
    when (executor.run()) {
      EnrollmentSyncOutcome.COMPLETED -> Result.success()
      EnrollmentSyncOutcome.RETRYABLE_FAILURE -> Result.retry()
    }
}
