package org.armman.sakhi.data.forms

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentMappingException
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409

/** Shown to the Sakhi when a submission can't proceed because a submit-critical lookup category
 * (CASE_TYPE/BENEFICIARY_TYPE) hasn't loaded — a transient, connectivity-driven state (see
 * [org.armman.sakhi.data.lookup.LookupWarmer]), not a form mistake. Deliberately actionable and
 * free of internal jargon, unlike the raw exception text kept on the draft for debugging. */
private const val LOOKUP_UNAVAILABLE_USER_MESSAGE =
  "Couldn't load the data needed to submit. Please connect to the internet and try again."

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

  /** [fieldErrors] carries a `400 VALIDATION_ERROR`'s per-field messages keyed by dotted DTO path
   * (empty for every other failure), threaded up to the ViewModel where it's mapped to
   * `question_code`s and shown inline. See [DynamicFormSubmissionException.BeneficiaryCreationFailed]. */
  data class Failed(
    val message: String?,
    val fieldErrors: Map<String, String> = emptyMap(),
  ) : DynamicFormSyncItemResult

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
    // Reclaim drafts orphaned in SYNCING by a previous pass that never finished (process death, the
    // OS stopping the worker, or WorkManager cancelling it because a fresh manual tap replaced it).
    // Without this they are excluded from getPendingSync() forever while still showing as pending
    // in the Home badge — visible to the Sakhi, impossible to upload. See
    // DynamicFormDraftDao.reclaimStaleSyncing for why re-attempting them is safe.
    dao.reclaimStaleSyncing()

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
              DynamicFormSyncItemResult.DuplicateConflict(error.userMessage)
            }

            error is IOException || error.cause is IOException -> {
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              DynamicFormSyncItemResult.Retryable(error.message)
            }

            else -> {
              // Both the permanent-4xx and generic-error cases resolve to the same thing here:
              // show the Sakhi the error now. The distinction between them only matters to
              // [run]'s WorkManager-job-retry bookkeeping, not to what the Submit button does.
              // Store the raw technical message on the draft (for debugging), but surface a clear,
              // actionable message to the Sakhi — mapping the internal "lookup not seeded?" case to
              // plain language, since to her it just means reference data hasn't loaded yet.
              markFailed(draft, error.message)
              DynamicFormSyncItemResult.Failed(userFacingMessage(error), fieldErrorsOf(error))
            }
          }
        },
      )
    } catch (e: HttpException) {
      // Retrofit's own message is the bare HTTP status line ("Bad Request") — accurate, but it
      // tells the Sakhi nothing she can act on. Keep it on the draft for debugging, show generic.
      markFailed(draft, e.message())
      DynamicFormSyncItemResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  /** The backend's per-field validation messages (dotted DTO paths → message) when the failure is
   * a `400 VALIDATION_ERROR` on beneficiary creation; empty for every other error. Kept DTO-path
   * keyed here (this layer has no schema) — the ViewModel maps it to `question_code`s. */
  private fun fieldErrorsOf(error: Throwable): Map<String, String> =
    (error as? DynamicFormSubmissionException.BeneficiaryCreationFailed)?.fieldErrors ?: emptyMap()

  /**
   * The message actually shown to the Sakhi for a failed submit. The internal "lookup not
   * available" mapping failure becomes [LOOKUP_UNAVAILABLE_USER_MESSAGE]; a backend failure uses
   * its own [DynamicFormSubmissionException.userMessage], which is the cleaned single sentence —
   * never [Throwable.message], which for these exceptions embeds the endpoint, HTTP code and the
   * verbatim response body (that raw text still goes to the draft's debug column via
   * [markFailed]). Anything else that has no usable message falls back to [SubmitErrorCopy.GENERIC]
   * rather than putting a stack-trace-ish string on screen.
   */
  private fun userFacingMessage(error: Throwable): String =
    if (error is DynamicFormSubmissionException.MappingFailed &&
      error.mappingCause is EnrollmentMappingException.LookupNotAvailable
    ) {
      LOOKUP_UNAVAILABLE_USER_MESSAGE
    } else {
      (error as? DynamicFormSubmissionException)?.userMessage
        ?: SubmitErrorCopy.humanize(error.message)
        ?: SubmitErrorCopy.GENERIC
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
