package org.armman.sakhi.data.delivery

import kotlinx.coroutines.flow.Flow
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord

/**
 * Offline-first persistence boundary for one delivery-session `CHILD_REGISTRATION` submission —
 * the CR-042 twin of [DeliveryFormDraftRepository], for the step that follows it.
 */
interface DeliveryChildRegistrationDraftRepository {

  /**
   * Saves the submission locally, then — only while online — attempts the real backend submission
   * immediately and returns its actual outcome. While offline it saves locally and leaves the
   * draft PENDING for the next manual Data Upload
   * ([DeliveryChildRegistrationSubmitResult.QueuedOffline]); the [DeliverySessionEntity] advance
   * happens only once a submission actually succeeds, whenever that is.
   *
   * [localSessionUuid] and [serverBeneficiaryId] are passed straight through to
   * [DeliveryChildRegistrationSubmissionCoordinator.submit] on whichever attempt actually runs.
   */
  suspend fun submitDraft(
    localSubmissionUuid: String,
    localSessionUuid: String,
    serverBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
  ): DeliveryChildRegistrationSubmitResult

  /** Bharath, 2026-09-11: same Home "Forms Uploaded" modal gap as [DeliveryFormDraftRepository
   * .observeUploadRecords] — folds into the existing "Registration" card since this submission's
   * own formCode is CHILD_REGISTRATION, the same category a standalone Children Register draft
   * already displays under. */
  fun observeUploadRecords(): Flow<List<FormUploadRecord>>
}
