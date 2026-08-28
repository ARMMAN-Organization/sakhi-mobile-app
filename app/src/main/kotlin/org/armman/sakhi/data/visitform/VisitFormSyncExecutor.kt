package org.armman.sakhi.data.visitform

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

/**
 * All of [VisitFormSyncWorker]'s real logic, kept in a plain class for the same reason
 * [org.armman.sakhi.data.forms.DynamicFormSyncExecutor] is — a `CoroutineWorker` needs a real
 * `Context`/`WorkerParameters` this repo's JVM-only test setup can't construct.
 *
 * The one thing every other queue's executor doesn't have to handle: [VisitFormSubmissionCoordinator.submit]
 * is a two-call sequence, so a draft can fail having *partially* succeeded
 * ([org.armman.sakhi.data.visitform.VisitFormDraftEntity.serverVisitId] set, form submission not
 * yet done). [attemptSync] always passes the draft's current `serverVisitId` back in as
 * `existingVisitId`, so a resumed attempt skips straight to step 2 rather than risking a second
 * visit instance for the same visit. It also always passes the draft's persisted
 * [org.armman.sakhi.data.visitform.VisitFormDraftEntity.localSubmissionUuid] straight through, so
 * a resumed submission call replays the same idempotency key rather than minting a fresh one.
 */
@Singleton
class VisitFormSyncExecutor @Inject constructor(
  private val dao: VisitFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: VisitFormSubmissionCoordinator,
) {

  /** Processes every PENDING/FAILED draft — used by the background [VisitFormSyncWorker]. */
  suspend fun run(): EnrollmentSyncOutcome {
    // Same rationale as DynamicFormSyncExecutor.run(): a row stuck in SYNCING from a run that
    // never finished (process death, the OS stopping the worker, a fresh manual tap replacing it)
    // would otherwise be invisible to getPendingSync() forever.
    dao.reclaimStaleSyncing()

    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    for (draft in pending) {
      if (attemptSync(draft) is VisitFormSyncItemResult.Retryable) {
        anyRetryableFailure = true
      }
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  /**
   * Attempts an immediate sync for one specific draft — right after Submit while online —
   * bypassing the WorkManager queue so [RoomVisitFormDraftRepository] can react to the real
   * backend result. Returns null if there's no draft row for this id.
   */
  suspend fun runOne(localScheduleUuid: String): VisitFormSyncItemResult? {
    val draft = dao.getByLocalScheduleUuid(localScheduleUuid) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) return VisitFormSyncItemResult.Synced
    return attemptSync(draft)
  }

  private suspend fun attemptSync(draft: VisitFormDraftEntity): VisitFormSyncItemResult {
    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localScheduleUuid)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return VisitFormSyncItemResult.Failed(message)
    }

    // Mutable capture so a step-1-only success (step 2 then throws) still gets persisted below —
    // onVisitCreated already wrote it to Room immediately, but the in-memory `draft` here is a
    // stale copy from before that write, so every branch below re-applies it explicitly.
    var capturedVisitId: String? = draft.serverVisitId

    return try {
      val result = coordinator.submit(
        localScheduleUuid = draft.localScheduleUuid,
        formVersionId = draft.formVersionId,
        answers = payload.answers,
        visitDate = parseVisitDate(draft.visitDateIso),
        localSubmissionUuid = draft.localSubmissionUuid,
        existingVisitId = draft.serverVisitId,
        referralCapture = payload.referralCapture,
        onVisitCreated = { visitId ->
          capturedVisitId = visitId
          dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING, serverVisitId = visitId))
        },
      )

      result.fold(
        onSuccess = {
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
              serverVisitId = capturedVisitId,
            ),
          )
          VisitFormSyncItemResult.Synced
        },
        onFailure = { error ->
          when {
            error is VisitFormSubmissionException.NotYetSynced -> {
              // Not the Sakhi's fault and not permanent — the beneficiary/schedule just hasn't
              // synced yet. Stay PENDING (not FAILED) so this keeps quietly retrying on every
              // future Data Upload without needing manual intervention — the same treatment
              // VisitScheduleSyncExecutor already gives this exact condition for schedules.
              dao.upsert(
                draft.copy(
                  syncStatus = EnrollmentSyncStatus.PENDING,
                  lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                  lastErrorMessage = error.userMessage,
                  serverVisitId = capturedVisitId,
                ),
              )
              VisitFormSyncItemResult.Retryable(error.userMessage)
            }

            error is IOException || error.cause is IOException -> {
              // Connectivity dropped mid-run despite the WorkManager network constraint —
              // transient, worth retrying, not a recorded failure.
              dao.upsert(
                draft.copy(syncStatus = EnrollmentSyncStatus.PENDING, serverVisitId = capturedVisitId),
              )
              VisitFormSyncItemResult.Retryable((error as? VisitFormSubmissionException)?.userMessage ?: error.message)
            }

            else -> {
              val message = (error as? VisitFormSubmissionException)?.userMessage
                ?: SubmitErrorCopy.humanize(error.message)
                ?: SubmitErrorCopy.GENERIC
              markFailed(draft.copy(serverVisitId = capturedVisitId), message)
              VisitFormSyncItemResult.Failed(message)
            }
          }
        },
      )
    } catch (e: HttpException) {
      // Retrofit's own message is the bare HTTP status line — accurate, but not actionable. Kept
      // on the draft for debugging, generic copy shown.
      markFailed(draft.copy(serverVisitId = capturedVisitId), e.message())
      VisitFormSyncItemResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun markFailed(draft: VisitFormDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localScheduleUuid: String): VisitFormDraftPayload? {
    val json = secureStore.getString(visitFormDraftPayloadKey(localScheduleUuid)) ?: return null
    return runCatching { visitFormDraftGson.fromJson(json, VisitFormDraftPayload::class.java) }.getOrNull()
  }

  private fun parseVisitDate(iso: String): LocalDate =
    runCatching { LocalDate.parse(iso) }.getOrDefault(LocalDate.now())
}
