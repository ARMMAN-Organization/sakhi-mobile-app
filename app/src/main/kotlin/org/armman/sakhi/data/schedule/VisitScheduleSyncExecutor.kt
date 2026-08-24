package org.armman.sakhi.data.schedule

import android.util.Log
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.visitform.VisitFormSyncScheduler
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_SERVER_ERROR_FLOOR = 500

/** Temporary diagnostic tag for the online-enrollment-to-visit-submit chain (CR-026 debugging). */
private const val TAG = "SakhiSync"

/**
 * Uploads locally generated visit schedules — the fourth offline queue (CR-022e).
 *
 * Logic lives here rather than in [VisitScheduleSyncWorker] for the same reason as the other three
 * queues: a `CoroutineWorker` needs a real `Context`/`WorkerParameters` that this repo's JVM-only
 * test setup cannot construct.
 *
 * ### Batched per beneficiary
 * One request per beneficiary, not per visit. A full ANC series is ten rows, and ten separate
 * requests over a rural connection is ten chances to fail halfway.
 *
 * ### Ordering is enforced by the query, not by the queues
 * The four WorkManager queues run independently with their own backoff, so nothing sequences
 * "beneficiary syncs" before "schedules sync". [VisitScheduleRepository.getUnsynced] therefore
 * returns only rows whose `serverBeneficiaryId` is already populated — a schedule for a beneficiary
 * the server has never heard of is *deferred*, not failed, and drains on a later pass once the
 * registration queue has done its work.
 */
@Singleton
class VisitScheduleSyncExecutor @Inject constructor(
  private val repository: VisitScheduleRepository,
  private val api: VisitScheduleApi,
  private val visitFormSyncScheduler: VisitFormSyncScheduler,
) {

  /**
   * Uploads every eligible schedule, grouped by beneficiary.
   *
   * Returns [EnrollmentSyncOutcome.RETRYABLE_FAILURE] if any batch hit a transient problem, so
   * WorkManager retries the job. Permanent rejections do not trigger a retry — re-sending the same
   * payload would fail identically for as long as backoff allowed.
   */
  suspend fun run(): EnrollmentSyncOutcome {
    val pending = repository.getUnsynced()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    var anySynced = false

    pending.groupBy { it.localBeneficiaryId }.forEach { (_, schedules) ->
      when (uploadBatch(schedules)) {
        BatchOutcome.RETRYABLE -> anyRetryableFailure = true
        BatchOutcome.SYNCED -> anySynced = true
        BatchOutcome.DEFERRED, BatchOutcome.PERMANENT -> Unit
      }
    }

    // Closes the same cross-queue race DynamicFormSyncExecutor already closes for
    // Registration -> Schedule: the visit_form_drafts WorkManager job is independent and
    // unordered, so if it already ran (and found NotYetSynced) before this schedule got its
    // serverScheduleId, nothing would ever re-check it within the same Data Upload tap.
    // Re-enqueuing now (REPLACE-safe, idempotent per VisitFormSyncScheduler's own doc) picks
    // any now-eligible visit form drafts up immediately instead of requiring another tap.
    if (anySynced) {
      visitFormSyncScheduler.syncNow()
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  private suspend fun uploadBatch(schedules: List<VisitScheduleEntity>): BatchOutcome {
    // getUnsynced() guarantees this, but reading it off the row keeps the invariant local.
    val serverBeneficiaryId = schedules.first().serverBeneficiaryId ?: return BatchOutcome.DEFERRED

    val request = BulkVisitScheduleRequestDto(
      beneficiaryId = serverBeneficiaryId,
      // Read from the rows themselves, not from a fresh ScheduleRuleSource.ruleVersion() call —
      // sync can run long after generation, and re-deriving "the current version" here would
      // mis-stamp a batch if a GoRules republish happened in between. A batch is grouped by
      // beneficiary (see this class's KDoc), and in practice every row in one batch is generated
      // together by the same event (enrolment/delivery/etc.), so they share one version; a batch
      // spanning a supersession boundary is a known limitation of this DTO's single top-level
      // field, not something this fix attempts to solve.
      generatedByRuleVersionId = schedules.first().generatedByRuleVersion,
      // NOTE: no generatedAt — the backend's Zod .strict() schema for this endpoint rejects it as
      // an unrecognized key (confirmed via a real 400: "Unrecognized key(s) in object:
      // 'generatedAt'"). Do not re-add without confirming the backend contract accepts it.
      schedules = schedules.map { it.toUploadDto() },
    )

    return try {
      val response = api.uploadSchedules(request)
      when {
        response.isSuccessful -> {
          Log.d(TAG, "uploadSchedules(beneficiary=$serverBeneficiaryId): success, ${schedules.size} rows")
          recordServerIds(response.body()?.data?.schedules.orEmpty())
          BatchOutcome.SYNCED
        }
        // A replay the server already has is a success from the device's point of view, but it must
        // still return the IDs. If it does not, the rows stay unsynced and a later pass retries.
        response.code() == HTTP_CONFLICT -> {
          Log.w(TAG, "uploadSchedules(beneficiary=$serverBeneficiaryId): conflict, already on server")
          BatchOutcome.PERMANENT
        }
        response.code() in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR_FLOOR -> {
          Log.e(
            TAG,
            "uploadSchedules(beneficiary=$serverBeneficiaryId): HTTP ${response.code()} — " +
              "${response.errorBody()?.string()}",
          )
          BatchOutcome.PERMANENT
        }
        else -> {
          Log.w(TAG, "uploadSchedules(beneficiary=$serverBeneficiaryId): HTTP ${response.code()}, retryable")
          BatchOutcome.RETRYABLE
        }
      }
    } catch (e: IOException) {
      // Offline or a dropped connection — the normal case in the field, always worth retrying.
      Log.w(TAG, "uploadSchedules(beneficiary=$serverBeneficiaryId): IOException, retryable", e)
      BatchOutcome.RETRYABLE
    } catch (e: HttpException) {
      Log.w(TAG, "uploadSchedules(beneficiary=$serverBeneficiaryId): HttpException, retryable", e)
      BatchOutcome.RETRYABLE
    }
  }

  /**
   * Writes each returned `scheduleId` back onto its local row.
   *
   * Only the server ID is stored. The schedule's content is device-authored and must never be
   * overwritten by a sync response — a server that echoed back different dates would otherwise
   * silently rewrite a woman's care plan.
   *
   * Rows the response does not mention stay unsynced and are retried, so a partial response costs a
   * retry rather than losing track of a visit.
   */
  private suspend fun recordServerIds(results: List<VisitScheduleUploadResultDto>) {
    results.forEach { repository.markSynced(it.localScheduleUuid, it.scheduleId) }
  }

  private enum class BatchOutcome { SYNCED, DEFERRED, PERMANENT, RETRYABLE }
}
