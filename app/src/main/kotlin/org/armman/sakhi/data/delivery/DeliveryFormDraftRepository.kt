package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.forms.FormAnswers
import java.time.LocalDate

/**
 * Offline-first persistence boundary for one `DELIVERY_VISIT` form submission — the CR-042 twin of
 * [org.armman.sakhi.data.adhocform.AdHocFormDraftRepository].
 */
interface DeliveryFormDraftRepository {

  /**
   * Saves the submission locally, then — only while online — attempts the real backend submission
   * immediately and returns its actual outcome. While offline it saves locally and leaves the
   * draft PENDING for the next manual Data Upload ([DeliveryFormSubmitResult.QueuedOffline]); the
   * [DeliverySessionEntity] advance and [org.armman.sakhi.data.schedule.VisitScheduleCoordinator
   * .onDeliveryRecorded] call happen only once a submission actually succeeds, whenever that is.
   *
   * [localSessionUuid] should be freshly minted by the caller once, at the moment the Sakhi starts
   * the delivery session — passed straight through so a resumed sync can find the right session.
   */
  suspend fun submitDraft(
    localSubmissionUuid: String,
    localSessionUuid: String,
    localBeneficiaryId: String,
    formVersionId: String,
    answers: FormAnswers,
    deliveryDate: LocalDate,
    deliveryFormFilledOn: LocalDate,
  ): DeliveryFormSubmitResult

  /**
   * The answers a `DELIVERY_VISIT` submission was made with, keyed by its own
   * [localSubmissionUuid] (the same id [DeliverySessionEntity.deliverySubmissionLocalUuid]
   * carries). Added for CR-042's next step ([org.armman.sakhi.data.delivery
   * .DeliveryChildRegistrationSubmissionCoordinator]'s caller): the delivery-session
   * `CHILD_REGISTRATION` prefill needs to read back facts (delivery date, sex of baby, birth
   * weight/length) the Sakhi already gave on the delivery form, without asking her to type them a
   * second time. Null if the payload was never written (shouldn't happen once the session has
   * advanced past [DeliverySessionStep.DELIVERY_FORM]) or is no longer present — callers must
   * treat a null return as "prefill unavailable," not fail the screen: everything it would have
   * prefilled is also an ordinary Sakhi-fillable field on `CHILD_REGISTRATION`.
   */
  suspend fun getAnswers(localSubmissionUuid: String): FormAnswers?
}
