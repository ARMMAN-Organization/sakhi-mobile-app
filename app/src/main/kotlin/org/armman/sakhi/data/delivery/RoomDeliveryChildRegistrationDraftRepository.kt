package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.audit.FormAuditRepository
import org.armman.sakhi.data.auth.session.SecureKeyValueStore
import org.armman.sakhi.data.connectivity.ConnectivityChecker
import org.armman.sakhi.data.enrollment.EnrollmentSyncStatus
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.SubmitErrorCopy
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val FORM_CODE_CHILD_REGISTRATION = "CHILD_REGISTRATION"

/**
 * Offline-first [DeliveryChildRegistrationDraftRepository]: sync metadata in Room
 * ([DeliveryChildRegistrationDraftDao]), answers payload in the encrypted [SecureKeyValueStore] —
 * same hybrid split as every other queue in this app. Mirrors
 * [RoomDeliveryFormDraftRepository]'s shape.
 */
@Singleton
class RoomDeliveryChildRegistrationDraftRepository @Inject constructor(
  private val dao: DeliveryChildRegistrationDraftDao,
  private val secureStore: SecureKeyValueStore,
  private val connectivityChecker: ConnectivityChecker,
  private val syncExecutor: DeliveryChildRegistrationSyncExecutor,
  private val formAuditRepository: FormAuditRepository,
) : DeliveryChildRegistrationDraftRepository {

  override suspend fun submitDraft(
    localSubmissionUuid: String,
    localSessionUuid: String,
    serverBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
  ): DeliveryChildRegistrationSubmitResult {
    saveLocally(
      localSubmissionUuid = localSubmissionUuid,
      localSessionUuid = localSessionUuid,
      serverBeneficiaryId = serverBeneficiaryId,
      formVersionId = formVersionId,
      answers = answers,
    )

    // Offline: saved and queued for the Sakhi's next Data Upload tap — same manual-trigger rule
    // every other queue in this app follows.
    if (!connectivityChecker.isOnline()) {
      return DeliveryChildRegistrationSubmitResult.QueuedOffline
    }

    return syncExecutor.runOne(localSubmissionUuid).toSubmitResult()
  }

  private fun DeliveryChildRegistrationSyncItemResult?.toSubmitResult(): DeliveryChildRegistrationSubmitResult =
    when (this) {
      is DeliveryChildRegistrationSyncItemResult.Synced -> DeliveryChildRegistrationSubmitResult.Synced
      is DeliveryChildRegistrationSyncItemResult.Failed -> DeliveryChildRegistrationSubmitResult.Failed(message)
      is DeliveryChildRegistrationSyncItemResult.Retryable ->
        DeliveryChildRegistrationSubmitResult.Failed(message ?: SubmitErrorCopy.GENERIC)
      null -> {
        // Only reachable if saveLocally() above somehow didn't leave a row behind — defensive only.
        DeliveryChildRegistrationSubmitResult.Failed(SubmitErrorCopy.GENERIC)
      }
    }

  private suspend fun saveLocally(
    localSubmissionUuid: String,
    localSessionUuid: String,
    serverBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
  ) {
    formAuditRepository.recordSaved(localSubmissionUuid, FORM_CODE_CHILD_REGISTRATION)
    val payload = DeliveryChildRegistrationDraftPayload(answers = answers)
    secureStore.putString(
      deliveryChildRegistrationDraftPayloadKey(localSubmissionUuid),
      deliveryChildRegistrationDraftGson.toJson(payload),
    )

    val existing = dao.getByLocalSubmissionUuid(localSubmissionUuid)
    dao.upsert(
      DeliveryChildRegistrationDraftEntity(
        localSubmissionUuid = localSubmissionUuid,
        localSessionUuid = localSessionUuid,
        serverBeneficiaryId = serverBeneficiaryId,
        formVersionId = formVersionId,
        // Re-saving (a retry from the same screen) resets to PENDING so the sync worker picks up
        // the fresh payload rather than skipping a stale SYNCED/FAILED row — same rationale as
        // every other queue.
        syncStatus = EnrollmentSyncStatus.PENDING,
        createdAtEpochMillis = existing?.createdAtEpochMillis ?: Instant.now().toEpochMilli(),
        lastAttemptAtEpochMillis = existing?.lastAttemptAtEpochMillis,
        retryCount = existing?.retryCount ?: 0,
        lastErrorMessage = null,
      ),
    )
  }
}
