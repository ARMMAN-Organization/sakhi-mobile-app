package org.armman.sakhi.data.enrollment

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409

/** Whether [EnrollmentSyncExecutor.run] should be retried by WorkManager's backoff policy, or
 * whether the outcome is settled (success and permanent per-item failures both count as settled
 * — WorkManager's own `retry()` is only for the run itself hitting a transient problem). */
enum class EnrollmentSyncOutcome { COMPLETED, RETRYABLE_FAILURE }

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via [EnrollmentSyncExecutor.runOne]
 * — used by [EnrollmentRepository.submitEnrollment] to tell the Sakhi the REAL backend result
 * right after Submit, instead of only a local save. Deliberately a different (simpler) shape than
 * [run]'s internal job-retry bookkeeping: from the Submit button's perspective there are only two
 * questions — "did the backend accept it" and if not, "what do I tell the Sakhi" — versus [run]'s
 * "should WorkManager retry the whole batch job."
 */
sealed interface EnrollmentSyncItemResult {
  /** Backend confirmed the beneficiary was created. */
  data class Synced(val remoteBeneficiaryId: String?) : EnrollmentSyncItemResult

  /** Backend rejected as a possible duplicate (SRS FR-S-2.4/2.5) — held for the Sakhi to
   * confirm/discard, never auto-retried. */
  data class DuplicateConflict(val message: String?) : EnrollmentSyncItemResult

  /** Backend rejected for a reason that will not resolve itself (validation, mapping, a non-2xx
   * HTTP response) — worth showing the Sakhi now rather than silently deferring. */
  data class Failed(val message: String?) : EnrollmentSyncItemResult

  /** Transient — connectivity dropped mid-call. Nothing useful to show the Sakhi; fall back to
   * the normal offline-first queue rather than surfacing this as a hard error. */
  data class Retryable(val message: String?) : EnrollmentSyncItemResult
}

/**
 * All of [EnrollmentSyncWorker]'s real logic, deliberately kept in a plain class with no
 * `android.content.Context`/`WorkerParameters` dependency: this repo's JVM-only unit test setup
 * (no Robolectric) cannot construct a real `CoroutineWorker`, so anything worth testing has to
 * live outside it. [EnrollmentSyncWorker] itself is a thin Hilt/WorkManager adapter over this.
 */
@Singleton
class EnrollmentSyncExecutor @Inject constructor(
  private val dao: EnrollmentDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val mapper: EnrollmentApiMapper,
  private val api: EnrollmentApi,
) {

  /** Processes every PENDING draft — used by the background [EnrollmentSyncWorker]. Unchanged
   * from before [runOne] was added: still the sole source of truth for whether WorkManager's job
   * itself should be retried. */
  suspend fun run(): EnrollmentSyncOutcome {
    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false

    for (draft in pending) {
      dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

      val record = loadRecord(draft.beneficiaryId)
      if (record == null) {
        // Payload missing from the encrypted store (shouldn't normally happen — would mean the
        // metadata row outlived its payload). Not retryable; surface rather than loop forever.
        markFailed(draft, "Local draft payload not found")
        continue
      }

      val requestResult = mapper.toCreateBeneficiaryRequest(record)
      val request = requestResult.getOrNull()
      if (request == null) {
        markFailed(draft, requestResult.exceptionOrNull()?.message)
        continue
      }

      try {
        val response = api.createBeneficiary(request)
        when {
          response.isSuccessful -> dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              remoteBeneficiaryId = response.body()?.data?.id,
              lastErrorMessage = null,
            ),
          )

          response.code() == HTTP_CONFLICT -> {
            // SRS FR-S-2.4/2.5 — possible duplicate. Held for the Sakhi to confirm/discard (#17),
            // never auto-retried: resubmitting unchanged would just get the same 409 again.
            dao.upsert(
              draft.copy(
                syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT,
                lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                retryCount = draft.retryCount + 1,
                lastErrorMessage = response.errorBody()?.string(),
              ),
            )
          }

          else -> {
            anyRetryableFailure = true
            markFailed(draft, "HTTP ${response.code()}: ${response.errorBody()?.string()}")
          }
        }
      } catch (e: HttpException) {
        anyRetryableFailure = true
        markFailed(draft, e.message())
      } catch (e: IOException) {
        // Connectivity dropped mid-run despite the WorkManager network constraint — transient,
        // worth WorkManager's own backoff-and-retry rather than being marked FAILED.
        anyRetryableFailure = true
        dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
      }
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  /**
   * Attempts an immediate sync for one specific beneficiary — e.g. right after Submit while
   * online — bypassing the WorkManager queue so the caller ([RoomEnrollmentRepository]) can react
   * to the real backend result instead of only a local save. Returns null if there's no draft row
   * for this id (shouldn't happen right after a save, but guards against a race).
   */
  suspend fun runOne(beneficiaryId: String): EnrollmentSyncItemResult? {
    val draft = dao.getByBeneficiaryId(beneficiaryId) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) {
      return EnrollmentSyncItemResult.Synced(draft.remoteBeneficiaryId)
    }

    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val record = loadRecord(draft.beneficiaryId)
    if (record == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return EnrollmentSyncItemResult.Failed(message)
    }

    val requestResult = mapper.toCreateBeneficiaryRequest(record)
    val request = requestResult.getOrNull()
    if (request == null) {
      val message = requestResult.exceptionOrNull()?.message
      markFailed(draft, message)
      return EnrollmentSyncItemResult.Failed(message)
    }

    return try {
      val response = api.createBeneficiary(request)
      when {
        response.isSuccessful -> {
          val remoteId = response.body()?.data?.id
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              remoteBeneficiaryId = remoteId,
              lastErrorMessage = null,
            ),
          )
          EnrollmentSyncItemResult.Synced(remoteId)
        }

        response.code() == HTTP_CONFLICT -> {
          val message = response.errorBody()?.string()
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              retryCount = draft.retryCount + 1,
              lastErrorMessage = message,
            ),
          )
          EnrollmentSyncItemResult.DuplicateConflict(message)
        }

        else -> {
          val message = "HTTP ${response.code()}: ${response.errorBody()?.string()}"
          markFailed(draft, message)
          EnrollmentSyncItemResult.Failed(message)
        }
      }
    } catch (e: HttpException) {
      markFailed(draft, e.message())
      EnrollmentSyncItemResult.Failed(e.message())
    } catch (e: IOException) {
      dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
      EnrollmentSyncItemResult.Retryable(e.message)
    }
  }

  private suspend fun markFailed(draft: EnrollmentDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadRecord(beneficiaryId: String): EnrollmentRecord? {
    val json = secureStore.getString(enrollmentDraftPayloadKey(beneficiaryId)) ?: return null
    return runCatching { enrollmentRecordGson.fromJson(json, EnrollmentRecord::class.java) }
      .getOrNull()
  }
}
