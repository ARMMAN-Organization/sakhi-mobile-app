package org.armman.sakhi.data.delivery

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
 * All of a future delivery-form background worker's real logic, kept in a plain class for the
 * same reason [org.armman.sakhi.data.adhocform.AdHocFormSyncExecutor] is — a `CoroutineWorker`
 * needs a real `Context`/`WorkerParameters` this repo's JVM-only test setup can't construct. No
 * WorkManager scheduler/worker is wired up for this queue yet (same "out of scope for this pass"
 * note as the ad-hoc queue); [run] exists so one can be added later without touching this class.
 */
@Singleton
class DeliveryFormSyncExecutor @Inject constructor(
  private val dao: DeliveryFormDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val coordinator: DeliveryFormSubmissionCoordinator,
) {

  /** Processes every PENDING/FAILED draft. */
  suspend fun run(): EnrollmentSyncOutcome {
    dao.reclaimStaleSyncing()

    val pending = dao.getPendingSync()
    if (pending.isEmpty()) return EnrollmentSyncOutcome.COMPLETED

    var anyRetryableFailure = false
    for (draft in pending) {
      if (attemptSync(draft) is DeliveryFormSyncItemResult.Retryable) {
        anyRetryableFailure = true
      }
    }

    return if (anyRetryableFailure) {
      EnrollmentSyncOutcome.RETRYABLE_FAILURE
    } else {
      EnrollmentSyncOutcome.COMPLETED
    }
  }

  /** Attempts an immediate sync for one specific draft — right after Submit while online —
   * bypassing any queue so [RoomDeliveryFormDraftRepository] can react to the real backend result.
   * Returns null if there's no draft row for this id. */
  suspend fun runOne(localSubmissionUuid: String): DeliveryFormSyncItemResult? {
    val draft = dao.getByLocalSubmissionUuid(localSubmissionUuid) ?: return null
    if (draft.syncStatus == EnrollmentSyncStatus.SYNCED) {
      // Already synced by an earlier attempt (e.g. a resumed session re-entering this step) — the
      // session row already carries whatever childBeneficiaryIds that attempt found; nothing new
      // to report here without re-hitting the network for no reason.
      return DeliveryFormSyncItemResult.Synced(childBeneficiaryIds = null)
    }
    return attemptSync(draft)
  }

  private suspend fun attemptSync(draft: DeliveryFormDraftEntity): DeliveryFormSyncItemResult {
    dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.SYNCING))

    val payload = loadPayload(draft.localSubmissionUuid)
    if (payload == null) {
      val message = "Local draft payload not found"
      markFailed(draft, message)
      return DeliveryFormSyncItemResult.Failed(message)
    }

    return try {
      val result = coordinator.submit(
        localSubmissionUuid = draft.localSubmissionUuid,
        localSessionUuid = draft.localSessionUuid,
        localBeneficiaryId = draft.localBeneficiaryId,
        formVersionId = draft.formVersionId,
        answers = payload.answers,
        deliveryDate = LocalDate.parse(payload.deliveryDateIso),
        deliveryFormFilledOn = LocalDate.parse(payload.deliveryFormFilledOnIso),
      )

      result.fold(
        onSuccess = { childBeneficiaryIds ->
          dao.upsert(
            draft.copy(
              syncStatus = EnrollmentSyncStatus.SYNCED,
              lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
              lastErrorMessage = null,
              // No id field exists on DeliveryFormSubmissionCoordinator's Result<List<String>?> —
              // it returns childBeneficiaryIds, not a submission id, since the session row (not
              // this draft row) is this queue's actual "did it work" record. serverSubmissionId is
              // still worth persisting for audit/debugging even though nothing reads it back
              // today; left null here deliberately rather than invented, since the coordinator
              // doesn't surface the raw submission id past this point.
              serverSubmissionId = draft.serverSubmissionId,
            ),
          )
          DeliveryFormSyncItemResult.Synced(childBeneficiaryIds)
        },
        onFailure = { error ->
          when {
            error is DeliveryFormSubmissionException.NotYetSynced -> {
              dao.upsert(
                draft.copy(
                  syncStatus = EnrollmentSyncStatus.PENDING,
                  lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
                  lastErrorMessage = error.userMessage,
                ),
              )
              DeliveryFormSyncItemResult.Retryable(error.userMessage)
            }

            error is IOException || error.cause is IOException -> {
              dao.upsert(draft.copy(syncStatus = EnrollmentSyncStatus.PENDING))
              DeliveryFormSyncItemResult.Retryable(
                (error as? DeliveryFormSubmissionException)?.userMessage ?: error.message,
              )
            }

            else -> {
              val message = (error as? DeliveryFormSubmissionException)?.userMessage
                ?: SubmitErrorCopy.humanize(error.message)
                ?: SubmitErrorCopy.GENERIC
              markFailed(draft, message)
              DeliveryFormSyncItemResult.Failed(message)
            }
          }
        },
      )
    } catch (e: HttpException) {
      markFailed(draft, e.message())
      DeliveryFormSyncItemResult.Failed(SubmitErrorCopy.GENERIC)
    }
  }

  private suspend fun markFailed(draft: DeliveryFormDraftEntity, errorMessage: String?) {
    dao.upsert(
      draft.copy(
        syncStatus = EnrollmentSyncStatus.FAILED,
        lastAttemptAtEpochMillis = Instant.now().toEpochMilli(),
        retryCount = draft.retryCount + 1,
        lastErrorMessage = errorMessage,
      ),
    )
  }

  private fun loadPayload(localSubmissionUuid: String): DeliveryFormDraftPayload? {
    val json = secureStore.getString(deliveryFormDraftPayloadKey(localSubmissionUuid)) ?: return null
    return runCatching { deliveryFormDraftGson.fromJson(json, DeliveryFormDraftPayload::class.java) }.getOrNull()
  }
}
