package org.armman.sakhi.data.adhocform

import org.armman.sakhi.data.forms.FormAnswers

/**
 * Offline-first persistence boundary for one ad-hoc-form submission (Referral, Referral
 * Follow-up, ANC/Child Closure, Beneficiary Reopen) — the ad-hoc twin of
 * [org.armman.sakhi.data.visitform.VisitFormDraftRepository].
 */
interface AdHocFormDraftRepository {

  /**
   * Saves the submission locally, then — only while online — attempts the real backend submission
   * immediately and returns its actual outcome. While offline it saves locally and leaves the
   * draft PENDING for the next manual Data Upload ([AdHocFormSubmitResult.QueuedOffline]).
   *
   * An online attempt that fails does not lose the submission: the draft stays queued in the
   * background regardless of the immediate outcome.
   *
   * [localFormInstanceUuid] identifies this specific form *opening*, not the beneficiary — see
   * [AdHocFormDraftEntity]'s doc for why that's the key fix over reusing
   * [org.armman.sakhi.data.forms.DynamicFormDraftEntity]'s shape.
   */
  suspend fun submitDraft(
    localFormInstanceUuid: String,
    localBeneficiaryId: String,
    formCode: String,
    formVersionId: String,
    answers: FormAnswers,
    /** REFERRAL_FOLLOWUP_VISIT only — see [org.armman.sakhi.data.adhocform.AdHocFormDraftEntity.referralId]'s doc. */
    referralId: String? = null,
    /** REFERRAL_FOLLOWUP_VISIT only — see [AdHocFormSubmissionCoordinator.submit]'s
     * `capturedImagePaths` doc. */
    capturedImagePaths: Map<String, String> = emptyMap(),
  ): AdHocFormSubmitResult

  /**
   * How many times this beneficiary has already filled a [formCode] ad-hoc form on this device,
   * counting every local draft regardless of [AdHocFormDraftEntity.syncStatus] (a not-yet-synced
   * PENDING referral still happened — the Sakhi filled it, so it counts). Used to auto-number
   * Referral's "Referral visit name" (spec: "RV1, RV2 etc, Autocalculated") — see
   * [org.armman.sakhi.ui.adhocform.AdHocFormViewModel]'s `referral_visit_name` prefill. Local-only,
   * same limitation as every other on-device count in this app: a beneficiary whose referral
   * history spans more than one device could see a number that collides with one counted
   * elsewhere.
   */
  suspend fun countByFormCode(localBeneficiaryId: String, formCode: String): Int
}
