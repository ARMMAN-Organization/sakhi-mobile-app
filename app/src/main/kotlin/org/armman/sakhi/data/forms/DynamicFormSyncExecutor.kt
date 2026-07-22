package org.armman.sakhi.data.forms

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via
 * [DynamicFormSyncExecutor.runOne] — used by [DynamicFormDraftRepository.submitDraft] to tell the
 * Sakhi the REAL backend result right after Submit. Deliberately a different (simpler) shape than
 * [DynamicFormSyncExecutor.run]'s internal job-retry bookkeeping — see
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncItemResult] for the same rationale on the static
 * enrollment path.
 */
sealed interface DynamicFormSyncItemResult {
  data object Synced : DynamicFormSyncItemResult
  data class DuplicateConflict(val message: String?) : DynamicFormSyncItemResult
  data class Failed(val message: String?) : DynamicFormSyncItemResult
  data class Retryable(val message: String?) : DynamicFormSyncItemResult
}

/**
 * All of [DynamicFormSyncWorker]'s real logic, kept in a plain class for the same reason
 * [org.armman.sakhi.data.enrollment.EnrollmentSyncExecutor] is: a `CoroutineWorker` needs a real
 * `android.content.Context`/`WorkerParameters` this repo's JVM-only test setup can't construct, so
 * anything worth testing has to live outside it.
 */
@Singleton
class DynamicFormSyncExecutor @Inject constructor(
  private val dao: DynamicFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: DynamicFormSubmissionCoordinator,
) {

  /** Processes every PENDING draft — used by the background [DynamicFormSyncWorker]. Unchanged
   * from before [runOne] was added: still the sole source of truth for whether WorkManager's job
   * itself should be retried. */
  suspend fun run(): EnrollmentSyncOutcome {
    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false

    for (draft in pending) {
      dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

      val payload = loadPayload(draft.localBeneficiaryId)
      if (payload == null) {
        markFailed(draft, "Local draft payload not found")
        continue
      }

      try {
        val result = coordinator.submit(
          formVersionId = draft.formVersionId,
          localCaseUuid = draft.localBeneficiaryId,
          localSubmissionUuid = draft.localSubmissionUuid,
          answers = payload.answers,
          fallbackRegistrationDate = parseRegistrationDate(payload.registrationDateIso),
        )
        result.fold(
          onSuccess = {
            dao.upsert(
              draft.copy(
                syncStatus = EnrollmentSyncStatus.SYNCED,
                lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                lastErrorMessage = null,
              ),
            )
          },
          onFailure = { error ->
            when {
              error is DynamicFormSubmissionException.BeneficiaryCreationFailed && error.httpCode == HTTP_CONFLICT -> {
                // SRS FR-S-2.4/2.5 — possible duplicate. Held for the Sakhi to confirm/discard
                // (task #17), never auto-retried.
                dao.upsert(
                  draft.copy(
                    syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT,
                    lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                    retryCount = draft.retryCount + 1,
                    lastErrorMessage = error.message,
                  ),
                )
              }

              error is IOException || error.cause is IOException -> {
                // Connectivity dropped mid-run despite the WorkManager network constraint —
                // transient, worth WorkManager's own backoff-and-retry, not a recorded failure.
                anyRetryableFailure = true
                dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              }

              error.httpCodeOrNull() in 400..499 -> {
                // A 4xx other than 409 (handled above) is a permanent validation failure — bad/
                // missing data in the payload the backend will reject identically on every retry
                // (e.g. an invalid geography UUID, a missing required field). Unlike a 5xx, this
                // is never going to start succeeding on its own; mark it FAILED and do NOT ask
                // WorkManager to retry, or it burns battery/data retrying a payload that can only
                // be fixed by a real data/mapping change, not the passage of time.
                markFailed(draft, error.message)
              }

              else -> {
                anyRetryableFailure = true
                markFailed(draft, error.message)
              }
            }
          },
        )
      } catch (e: HttpException) {
        anyRetryableFailure = true
        markFailed(draft, e.message())
      }
    }

    return if (anyRetryableFailure) EnrollmentSyncOutcome.RETRYABLE_FAILURE else EnrollmentSyncOutcome.COMPLETED
  }

  /**
   * Attempts an immediate sync for one specific draft — e.g. right after Submit while online —
   * bypassing the WorkManager queue so the caller ([RoomDynamicFormDraftRepository]) can react to
   * the real backend result. Returns null if there's no draft row for this id, or if it's already
   * SYNCED (idempotent replay of a fast double-tap).
   */
  suspend fun runOne(localBeneficiaryId: String): DynamicFormSyncItemResult? {
    val draft = dao.getByLocalBeneficiaryId(localBeneficiaryId) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) return DynamicFormSyncItemResult.Synced

    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localBeneficiaryId)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return DynamicFormSyncItemResult.Failed(message)
    }

    return try {
      val result = coordinator.submit(
        formVersionId = draft.formVersionId,
        localCaseUuid = draft.localBeneficiaryId,
        localSubmissionUuid = draft.localSubmissionUuid,
        answers = payload.answers,
        fallbackRegistrationDate = parseRegistrationDate(payload.registrationDateIso),
      )
      result.fold(
        onSuccess = {
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
            ),
          )
          DynamicFormSyncItemResult.Synced
        },
        onFailure = { error ->
          when {
            error is DynamicFormSubmissionException.BeneficiaryCreationFailed && error.httpCode == HTTP_CONFLICT -> {
              dao.upsert(
                draft.copy(
                  syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT,
                  lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                  retryCount = draft.retryCount + 1,
                  lastErrorMessage = error.message,
                ),
              )
              DynamicFormSyncItemResult.DuplicateConflict(error.message)
            }

            error is IOException || error.cause is IOException -> {
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              DynamicFormSyncItemResult.Retryable(error.message)
            }

            else -> {
              // Both the permanent-4xx and generic-error cases resolve to the same thing here:
              // show the Sakhi the error now. The distinction between them only matters to
              // [run]'s WorkManager-job-retry bookkeeping, not to what the Submit button does.
              markFailed(draft, error.message)
              DynamicFormSyncItemResult.Failed(error.message)
            }
          }
        },
      )
    } catch (e: HttpException) {
      markFailed(draft, e.message())
      DynamicFormSyncItemResult.Failed(e.message())
    }
  }

  private suspend fun markFailed(draft: DynamicFormDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localBeneficiaryId: String): DynamicFormDraftPayload? {
    val json = secureStore.getString(dynamicFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching { dynamicFormDraftGson.fromJson(json, DynamicFormDraftPayload::class.java) }.getOrNull()
  }

  private fun parseRegistrationDate(iso: String): LocalDate =
    runCatching { LocalDate.parse(iso) }.getOrDefault(LocalDate.now())

  private fun Throwable.httpCodeOrNull(): Int? = when (this) {
    is DynamicFormSubmissionException.BeneficiaryCreationFailed -> httpCode
    is DynamicFormSubmissionException.FormSubmissionFailed -> httpCode
    else -> null
  }
}
