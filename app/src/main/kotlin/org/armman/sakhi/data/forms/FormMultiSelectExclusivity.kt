package org.armman.sakhi.data.forms

/**
 * Some `multiselect` fields have an "exclusive" option — "No known medical condition"/"Don't
 * know" — that the spec's dev note says must disable every other checkbox in the group, and
 * vice versa: checking any other option disables the exclusive ones.
 *
 * - Q43's row 43 note (verbatim): "If no known medical condition identified during check up or
 *   don't know is marked then disable all options and if other than these two options selected
 *   then disable these two options."
 * - Q58's row 58 note carries the IDENTICAL rule, just with its own value codes.
 * - Q44's row 44 note is the one-sided version of the same rule ("None received yet" vs. any
 *   dose checkbox) — one exclusive code instead of two, same engine.
 *
 * Value codes below are the live schema's (`api-calls-live.jsonl`), not the spec's wording — Q43's
 * "no known condition" code is `no_known_medical_condition_identified_during_check_up`, Q58's is
 * the shorter `no_known_medical_condition`. Both share `don_t_know` for "Don't know", but that is
 * NOT assumed here — each field declares its own complete exclusive set, in case a future schema
 * publish gives one of them a different "don't know" code without the other.
 */
object FormMultiSelectExclusivity {

  private val EXCLUSIVE_VALUE_CODES: Map<String, Set<String>> = mapOf(
    MotherRegistrationQuestionCodes.ANC1_HIGH_RISK_CONDITIONS to setOf(
      "no_known_medical_condition_identified_during_check_up",
      "don_t_know",
    ),
    MotherRegistrationQuestionCodes.SELF_MEDICAL_CONDITIONS to setOf(
      "no_known_medical_condition",
      "don_t_know",
    ),
    // Q44 row 44 (verbatim): "If 'None' is selected, do not allow any other selection." Only one
    // exclusive code here (no "don't know" variant on this question), but the mapping/lookup path
    // is identical to Q43/Q58 above.
    TdDoseQuestionCodes.TD_DOSE_QUESTION_CODE to setOf(
      TdDoseQuestionCodes.NONE_RECEIVED_YET_VALUE_CODE,
    ),
    // Q49 row 49 note (verbatim): "At least one option selected. If marked to any option other
    // than 'None' then date is mandatory" — confirmed with the backend (CR-040) that "None" and
    // any vaccine checkbox together is rejected server-side. Same one-sided shape as Q44 above.
    VaccinationAtBirthQuestionCodes.VACCINATION_QUESTION_CODE to setOf(
      VaccinationAtBirthQuestionCodes.NONE_VALUE_CODE,
    ),
    "is_the_baby_showing_any_danger_signs_since_last_visit" to setOf(
      "no_abnormal_signs_symptoms",
    ),
    "have_you_been_experiencing_any_of_these_since_the_last_visit" to setOf(
      "no_abnormal_signs_and_symptoms",
    ),
  )

  /**
   * True when [candidateCode] should be disabled given what's already [selected] for
   * [questionCode]. A code that's already selected is never disabled — the Sakhi must always be
   * able to uncheck whatever's checked, including to escape an invalid combination that arrived
   * some other way (an older draft, a backend-restored answer).
   */
  fun isDisabled(questionCode: String, candidateCode: String, selected: List<String>): Boolean {
    val exclusiveCodes = EXCLUSIVE_VALUE_CODES[questionCode] ?: return false
    if (candidateCode in selected) return false

    // "Disable all options" is read literally: once ANY option is checked, every other option in
    // the group is locked out. That covers the two spec directions (exclusive blocks the rest, the
    // rest block the exclusives) and also the case the note leaves implicit — one exclusive option
    // blocking its sibling. "No known medical condition" and "Don't know" contradict each other,
    // so allowing both would record a self-inconsistent answer.
    return if (candidateCode in exclusiveCodes) {
      selected.isNotEmpty()
    } else {
      selected.any { it in exclusiveCodes }
    }
  }
}
