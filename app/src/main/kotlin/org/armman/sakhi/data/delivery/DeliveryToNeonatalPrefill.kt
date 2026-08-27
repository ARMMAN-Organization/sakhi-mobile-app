package org.armman.sakhi.data.delivery

import org.armman.sakhi.data.forms.DeliveryQuestionCodes
import org.armman.sakhi.data.forms.FormAnswers
import org.armman.sakhi.data.forms.NeonatalVisitQuestionCodes

/**
 * Builds the CR-042 `NEONATAL_VISIT` prefill (NN1 and NN2 alike — both share this one schema) from
 * a submitted `DELIVERY_VISIT` form's own answers. Confirmed 2026-08-19 against the real
 * `GET /forms/NEONATAL_VISIT/active-version` payload: `birth_weight_kg` and `term_of_delivery` are
 * both explicitly labelled "(from the Delivery form, for the KMC eligibility check below)" — they
 * exist on this schema specifically so the Sakhi never re-measures/re-answers something she
 * already recorded once on `DELIVERY_VISIT`. See [NeonatalVisitQuestionCodes] for the confirmed
 * field codes and the `term_of_delivery` vocabulary-match reasoning.
 *
 * ### Not [DeliverySessionStep]-scoped, unlike [DeliveryToChildRegistrationPrefill]
 * NN2 can be opened off the regular visit tracker weeks after a delivery session has already
 * reached [DeliverySessionStep.DONE] (see [org.armman.sakhi.data.schedule.sameSessionNnVisit]'s
 * doc — only ONE of NN1/NN2 is ever the same-session visit; the other, if generated at all, is a
 * plain tracker visit), and it needs this exact same prefill just as much as a same-session NN1
 * does. Callers must therefore resolve the beneficiary's session with a step-agnostic lookup
 * ([DeliverySessionRepository.getMostRecentForBeneficiary]), not
 * [DeliverySessionRepository.getActiveForBeneficiary] — the latter would return null the moment
 * the session finishes, silently dropping this prefill for exactly the visit (NN2, opened later)
 * that most needs it.
 *
 * ### Known gap — which child's birth weight, for twins/triplets
 * `birth_weight_kg` on `DELIVERY_VISIT` is per-child (`child1_birth_weight_kg`/`child2_.../
 * child3_...`), but this app generates exactly one NN visit per delivery, not one per child (see
 * [org.armman.sakhi.data.schedule.NnScheduleGenerator]'s doc — it schedules under the mother's own
 * `localBeneficiaryId`, with no per-child concept at all). There is therefore no existing signal
 * anywhere in this app for "which of the (up to three) children this NN visit is actually about".
 * [childIndex] defaults to 0 (the first child) for that reason — correct for the overwhelmingly
 * common single-birth case, and a real but currently-unavoidable simplification for twins/triplets
 * until either the schedule model or the form itself grows a per-child concept for NN. Flagged
 * here rather than silently guessed at, same spirit as [DeliveryToChildRegistrationPrefill]'s own
 * "left for the Sakhi to answer fresh" calls.
 */
object DeliveryToNeonatalPrefill {

  /** Single-value (`number`/`dropdown`) answers to seed. Either — or both — are omitted (not
   * defaulted) when the delivery form never answered them, same "let the Sakhi fill it in fresh"
   * fallback [DeliveryToChildRegistrationPrefill.singleValueAnswersFor] uses. */
  fun singleValueAnswersFor(deliveryAnswers: FormAnswers, childIndex: Int = 0): Map<String, String> {
    val values = mutableMapOf<String, String>()
    deliveryAnswers.valueOf(DeliveryQuestionCodes.TERM_OF_DELIVERY)?.let {
      values[NeonatalVisitQuestionCodes.TERM_OF_DELIVERY] = it
    }
    deliveryAnswers.valueOf(DeliveryQuestionCodes.childBirthWeightKg(childIndex))?.let {
      values[NeonatalVisitQuestionCodes.BIRTH_WEIGHT_KG] = it
    }
    return values
  }
}
