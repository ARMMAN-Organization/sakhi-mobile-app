package org.armman.sakhi.data.visitform

import kotlinx.coroutines.flow.Flow
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import java.time.LocalDate

/**
 * Offline-first persistence boundary for a visit-form submission (CR-026b) — the twin of
 * [org.armman.sakhi.data.forms.DynamicFormDraftRepository] for this feature.
 */
interface VisitFormDraftRepository {

  /**
   * Saves the submission locally, then — only while online — attempts the real two-call backend
   * submission immediately and returns its actual outcome, exactly like the pre-CR-026b direct
   * [VisitFormSubmissionCoordinator] call did. While offline it saves locally and leaves the draft
   * PENDING for the next manual Data Upload ([VisitFormSubmitResult.QueuedOffline]).
   *
   * Unlike before CR-026b, an online attempt that fails (including
   * [VisitFormSubmissionException.NotYetSynced]) no longer loses the submission: the draft stays
   * queued in the background regardless of the immediate outcome, so the next Data Upload retries
   * it automatically even though the immediate call already reported [VisitFormSubmitResult.Failed]
   * to the Sakhi.
   */
  suspend fun submitDraft(
    localScheduleUuid: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    visitDate: LocalDate,
  ): VisitFormSubmitResult

  /** All CR-026b visit-form drafts, newest first, for the Home screen's "Forms Uploaded"
   * sync-status modal. Reuses [FormUploadRecord] — its shape is form-agnostic — with
   * [FormUploadRecord.localBeneficiaryId] carrying [VisitFormDraftEntity.localScheduleUuid]
   * instead of a beneficiary id; that field is otherwise only read for the duplicate-review flow,
   * which visit submissions have no equivalent of ([FormUploadRecord.pendingNewPregnancyBeneficiaryId]
   * stays null here). */
  suspend fun getUploadRecords(): List<FormUploadRecord>

  /** Observable version of [getUploadRecords] — re-emits whenever a draft is added or its sync
   * status changes, so the Home upload modal reflects this queue's progress live during a manual
   * sync run. Merged with the other queues by
   * [org.armman.sakhi.data.sync.UploadRecordsSource]. */
  fun observeUploadRecords(): Flow<List<FormUploadRecord>>
}
