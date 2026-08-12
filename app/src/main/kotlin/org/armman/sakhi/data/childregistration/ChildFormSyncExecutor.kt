package org.armman.sakhi.data.childregistration

import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.enrollment.EnrollmentSyncOutcome
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.SubmitErrorCopy
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_CONFLICT = 409

/**
 * Per-draft outcome of an IMMEDIATE, single-item sync attempt via [ChildFormSyncExecutor.runOne] —
 * used by [ChildFormDraftRepository.submitDraft] to tell the Sakhi the REAL backend result right
 * after Submit. Standalone twin of `DynamicFormSyncItemResult`.
 */
sealed interface ChildFormSyncItemResult {
  data object Synced : ChildFormSyncItemResult
  /** Duplicate child (SRS FR-S-2.4) — blocked. No message: the raw backend sentence stays on the
   * draft for debugging, the screen shows localised copy. */
  data object DuplicateConflict : ChildFormSyncItemResult
  data class Failed(val message: String?) : ChildFormSyncItemResult
  data class Retryable(val message: String?) : ChildFormSyncItemResult
}

/**
 * All of [ChildFormSyncWorker]'s real logic, kept in a plain class for the same reason
 * [org.armman.sakhi.data.forms.DynamicFormSyncExecutor] is: a `CoroutineWorker` needs a real
 * `Context`/`WorkerParameters` this repo's JVM-only test setup can't construct, so anything worth
 * testing has to live outside it. Standalone twin — references only the child dao/coordinator/
 * payload and [ChildRegistrationSubmissionException].
 */
@Singleton
class ChildFormSyncExecutor @Inject constructor(
  private val dao: ChildFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: ChildRegistrationSubmissionCoordinator,
) {

  /** Processes every PENDING draft — used by the background [ChildFormSyncWorker]. Sole source of
   * truth for whether WorkManager's job itself should be retried. */
  suspend fun run(): EnrollmentSyncOutcome {
    // Reclaim drafts orphaned in SYNCING by a pass that never finished — otherwise they're excluded
    // from getPendingSync() forever while still counting toward the Home badge. See
    // ChildFormDraftDao.reclaimStaleSyncing.
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
          onSuccess = { serverBeneficiaryId ->
            dao.upsert(
              draft.copy(
                syncStatus = EnrollmentSyncStatus.SYNCED,
                lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                lastErrorMessage = null,
                remoteBeneficiaryId = serverBeneficiaryId,
              ),
            )
          },
          onFailure = { error ->
            when {
              error is ChildRegistrationSubmissionException.BeneficiaryCreationFailed && error.httpCode == HTTP_CONFLICT -> {
                // SRS FR-S-2.4/2.5 — possible duplicate. Held for the Sakhi to confirm/discard,
                // never auto-retried.
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
                // A 4xx other than 409 is a permanent validation failure — the backend will reject
                // the identical payload on every retry, so mark FAILED and do NOT ask WorkManager
                // to retry (burning battery/data on a payload only a data/mapping change can fix).
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
   * bypassing the WorkManager queue so [RoomChildFormDraftRepository] can react to the real backend
   * result. Returns null if there's no draft row for this id, or if it's already SYNCED (idempotent
   * replay of a fast double-tap).
   */
  suspend fun runOne(localBeneficiaryId: String): ChildFormSyncItemResult? {
    val draft = dao.getByLocalBeneficiaryId(localBeneficiaryId) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) return ChildFormSyncItemResult.Synced

    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localBeneficiaryId)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return ChildFormSyncItemResult.Failed(message)
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
        onSuccess = { serverBeneficiaryId ->
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
              remoteBeneficiaryId = serverBeneficiaryId,
            ),
          )
          ChildFormSyncItemResult.Synced
        },
        onFailure = { error ->
          when {
            error is ChildRegistrationSubmissionException.BeneficiaryCreationFailed && error.httpCode == HTTP_CONFLICT -> {
              dao.upsert(
                draft.copy(
                  syncStatus = EnrollmentSyncStatus.DUPLICATE_CONFLICT,
                  lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                  retryCount = draft.retryCount + 1,
                  lastErrorMessage = error.message,
                ),
              )
              ChildFormSyncItemResult.DuplicateConflict
            }

            error is IOException || error.cause is IOException -> {
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              ChildFormSyncItemResult.Retryable(error.message)
            }

            else -> {
              // Both the permanent-4xx and generic-error cases resolve to the same thing here: show
              // the Sakhi the error now. The distinction only matters to [run]'s WorkManager-retry
              // bookkeeping, not to what the Submit button does.
              //
              // markFailed() keeps the DIAGNOSTIC message (endpoint, HTTP code, verbatim body) in
              // the draft's debug column; only the value handed back to the UI is cleaned.
              markFailed(draft, error.message)
              ChildFormSyncItemResult.Failed(userFacingMessage(error))
            }
          }
        },
      )
    } catch (e: HttpException) {
      markFailed(draft, e.message())
      ChildFormSyncItemResult.Failed(SubmitErrorCopy.humanize(e.message()) ?: SubmitErrorCopy.GENERIC)
    }
  }

  /**
   * The single sentence the Sakhi sees. A backend failure uses its own
   * [ChildRegistrationSubmissionException.userMessage] — never [Throwable.message], which embeds the
   * endpoint, HTTP code and verbatim response body (that raw text still goes to the draft's debug
   * column via [markFailed]). Anything with no usable message falls back to [SubmitErrorCopy.GENERIC]
   * rather than putting a stack-trace-ish string on screen.
   *
   * Mirrors `DynamicFormSyncExecutor.userFacingMessage`; the child clone shipped without it, which is
   * why a 422 rendered as raw JSON in the UI.
   */
  private fun userFacingMessage(error: Throwable): String =
    (error as? ChildRegistrationSubmissionException)?.userMessage
      ?: SubmitErrorCopy.humanize(error.message)
      ?: SubmitErrorCopy.GENERIC

  private suspend fun markFailed(draft: ChildFormDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localBeneficiaryId: String): ChildFormDraftPayload? {
    val json = secureStore.getString(childFormDraftPayloadKey(localBeneficiaryId)) ?: return null
    return runCatching { childFormDraftGson.fromJson(json, ChildFormDraftPayload::class.java) }.getOrNull()
  }

  private fun parseRegistrationDate(iso: String): LocalDate =
    runCatching { LocalDate.parse(iso) }.getOrDefault(LocalDate.now())

  private fun Throwable.httpCodeOrNull(): Int? = when (this) {
    is ChildRegistrationSubmissionException.BeneficiaryCreationFailed -> httpCode
    is ChildRegistrationSubmissionException.FormSubmissionFailed -> httpCode
    else -> null
  }
}
