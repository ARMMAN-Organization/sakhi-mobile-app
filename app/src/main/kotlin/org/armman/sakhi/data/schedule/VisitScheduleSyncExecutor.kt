package org.armman.sakhi.data.schedule

import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_SERVER_ERROR_FLOOR = 500

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
  private val ruleSource: ScheduleRuleSource,
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

    pending.groupBy { it.localBeneficiaryId }.forEach { (_, schedules) ->
      if (uploadBatch(schedules) == BatchOutcome.RETRYABLE) anyRetryableFailure = true
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
      generatedByRuleVersionId = ruleSource.ruleVersion,
      generatedAt = Instant.now().toString(),
      schedules = schedules.map { it.toUploadDto() },
    )

    return try {
      val response = api.uploadSchedules(request)
      when {
        response.isSuccessful -> {
          recordServerIds(response.body()?.data?.schedules.orEmpty())
          BatchOutcome.SYNCED
        }
        // A replay the server already has is a success from the device's point of view, but it must
        // still return the IDs. If it does not, the rows stay unsynced and a later pass retries.
        response.code() == HTTP_CONFLICT -> BatchOutcome.PERMANENT
        response.code() in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR_FLOOR -> BatchOutcome.PERMANENT
        else -> BatchOutcome.RETRYABLE
      }
    } catch (_: IOException) {
      // Offline or a dropped connection — the normal case in the field, always worth retrying.
      BatchOutcome.RETRYABLE
    } catch (_: HttpException) {
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
