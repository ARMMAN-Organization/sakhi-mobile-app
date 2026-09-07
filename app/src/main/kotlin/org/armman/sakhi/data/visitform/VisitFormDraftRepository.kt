package org.armman.sakhi.data.visitform

import kotlinx.coroutines.flow.Flow
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.FormUploadRecord
import org.armman.sakhi.data.lmpchange.LmpChangeCapture
import org.armman.sakhi.data.referral.ReferralCapture
import org.armman.sakhi.data.rules.RiskGradingResult
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
    /** Phase 5 (CR — offline high-risk rule evaluation): the final visit-level [RiskGradingResult],
     * computed by the caller against the complete final answers right before this call — see
     * [VisitFormDraftPayload.riskResult]'s doc for why this isn't recomputed here. Null for form
     * codes with no risk-grading pack ([org.armman.sakhi.data.rules.GoRulesRiskAdapter]) or when
     * evaluation itself returned null. Stored locally alongside the draft; not yet forwarded to
     * the backend (no confirmed submission-API field for it yet). */
    riskResult: RiskGradingResult? = null,
    /** CR-Referral-01: whatever the Sakhi filled on the standalone Referral tab — see
     * [VisitFormDraftPayload.referralCapture]'s doc for how/when this actually results in a
     * created referral. Null when she left that tab untouched. */
    referralCapture: ReferralCapture? = null,
    /** Task 2 (LMP/Reopen/Referral/Audit task list): whatever the Sakhi filled on ANC_VISIT's own
     * sonography-confirmation branch — see [VisitFormDraftPayload.lmpChangeCapture]'s doc. Null
     * for every form code except ANC_VISIT and every ANC_VISIT submission where she left that
     * branch untouched. */
    lmpChangeCapture: LmpChangeCapture? = null,
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

  /**
   * Bug fix (2026-09-04): the persisted answers for one visit-form draft — whether still queued
   * locally or already synced (the encrypted payload is never cleared once written, same as every
   * other queue in this app). Lets a later visit carry forward an earlier one's own answer as a
   * computed field's baseline — e.g. ANC_VISIT's "Gestational weight gain" needs the woman's
   * weight from her FIRST ANC visit, which [org.armman.sakhi.data.visitform
   * .StaticVisitFormRepository] has no other local source for (MOTHER_REGISTRATION never asks for
   * it — see that class's own doc).
   *
   * Null when nothing was ever saved locally for [localScheduleUuid] on THIS device — a
   * beneficiary whose earlier visit was recorded on a different device, or one that genuinely
   * hasn't happened yet. Callers must treat that as "unknown", not "zero".
   */
  suspend fun getAnswers(localScheduleUuid: String): FormAnswers?
}
