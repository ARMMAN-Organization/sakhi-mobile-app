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
    // PP1's row 24 note (verbatim): "If 'None' option selected then disable all other option or
    // if other than this option is selected then disable this option." Confirmed against the live
    // POSTPARTUM_VISIT schema (`contraceptive_side_effects` field, "none" option) — the backend
    // also enforces this itself via an EXCLUSIVE_OPTION validation rule on the same field/value,
    // same "client mirrors a real server-side rule" precedent as Q49's vaccination-at-birth entry
    // above.
    "contraceptive_side_effects" to setOf(
      "none",
    ),
    // NN1/NN2 row 7 note (verbatim): "If 'No abnormal signs/symptoms' option selected then disable
    // all other option or if other than this option is selected then desable this option." NN's
    // own question_code is `danger_signs` — a DIFFERENT code from INFANT_VISIT's
    // `is_the_baby_showing_any_danger_signs_since_last_visit` above (same options/wording, but a
    // distinct field on the live NEONATAL_VISIT schema) — confirmed against the backend's
    // `neonatal-visit.json` seed, which also enforces this itself via an EXCLUSIVE_OPTION
    // validation rule on this exact field/value, same precedent as the PP1/Q49 entries above.
    "danger_signs" to setOf(
      "no_abnormal_signs_symptoms",
    ),
    // ANC_VISIT Q29 note (verbatim): "If normal selected then disable rest of the options."
    // Confirmed against the live ANC_VISIT schema's own validationJson, which independently
    // declares the identical rule server-side: {"rule":"EXCLUSIVE_OPTION","field":"urine_test",
    // "exclusiveValues":["normal"]} (active-version v9, 2026-08-21).
    "urine_test" to setOf(
      "normal",
    ),
    // ANC_VISIT Q33 note: "If 'None' is selected, do not allow any other selection" — same
    // one-sided shape as Q44's TD_DOSE_QUESTION_CODE entry above, but a DIFFERENT question_code:
    // this is the visit form's own "vaccination_status" field, not the registration form's
    // "has_the_women_received_td_dose". Also confirmed via the live schema's validationJson:
    // {"rule":"EXCLUSIVE_OPTION","field":"vaccination_status","exclusiveValues":["none"]}.
    "vaccination_status" to setOf(
      "none",
    ),
    // POSTPARTUM_VISIT row 4 (verbatim, same shape as Q43/Q58/Q17 above): "If 'No abnormal
    // signs/symptoms' option selected then disable all other option or if other than this
    // option is selected then disable this option." Confirmed against the live POSTPARTUM_VISIT
    // schema's own validationJson: {"rule":"EXCLUSIVE_OPTION",
    // "field":"danger_signs_since_delivery_or_last_visit","exclusiveValues":
    // ["no_abnormal_signs_and_symptoms"]}. `question_code` is unique to POSTPARTUM_VISIT (not
    // shared with ANC_VISIT's own, differently-worded, danger-signs field), so this is safe as a
    // plain (form-unscoped) entry.
    "danger_signs_since_delivery_or_last_visit" to setOf(
      "no_abnormal_signs_and_symptoms",
    ),
    // POSTPARTUM_VISIT row 10, same shape, confirmed via validationJson:
    // {"rule":"EXCLUSIVE_OPTION","field":"episiotomy_or_csection_wound_issues",
    // "exclusiveValues":["none_of_the_above"]}. Also a `question_code` unique to
    // POSTPARTUM_VISIT — safe as a plain entry.
    "episiotomy_or_csection_wound_issues" to setOf(
      "none_of_the_above",
    ),
  )

  /**
   * Same rule, but for a `question_code` that MORE THAN ONE form's schema reuses — `dehydration`
   * and `swelling` are shared verbatim by ANC_VISIT and POSTPARTUM_VISIT. POSTPARTUM_VISIT's own
   * `validationJson` declares both `EXCLUSIVE_OPTION` (confirmed 2026-08-21); ANC_VISIT's does
   * not — its exclusivity here is a deliberate UI-only product decision (2026-08-21: "No
   * swelling"/"No" dehydration checked alongside a real symptom is self-contradictory data
   * regardless of whether the Excel/backend called it out), not a backend-enforced rule, so it
   * has no [org.armman.sakhi.ui.visitform.DynamicVisitFormViewModel.crossFieldViolations]
   * submit-time guard the way POSTPARTUM_VISIT's does. Keyed by (form_code, question_code) rather
   * than added to [EXCLUSIVE_VALUE_CODES] so a future third form reusing either `question_code`
   * for an unrelated field isn't silently swept in — only [isDisabled] callers that pass a
   * matching [formCode] (currently just the Visit Form) see these applied. */
  private val FORM_SCOPED_EXCLUSIVE_VALUE_CODES: Map<Pair<String, String>, Set<String>> = mapOf(
    ("POSTPARTUM_VISIT" to "dehydration") to setOf(
      "no",
    ),
    ("POSTPARTUM_VISIT" to "swelling") to setOf(
      "no_swelling",
    ),
    ("ANC_VISIT" to "dehydration") to setOf(
      "no",
    ),
    ("ANC_VISIT" to "swelling") to setOf(
      "no_swelling",
    ),
  )

  /**
   * True when [candidateCode] should be disabled given what's already [selected] for
   * [questionCode]. A code that's already selected is never disabled — the Sakhi must always be
   * able to uncheck whatever's checked, including to escape an invalid combination that arrived
   * some other way (an older draft, a backend-restored answer).
   *
   * [formCode] is only consulted for [FORM_SCOPED_EXCLUSIVE_VALUE_CODES] — null (the default,
   * every caller except the Visit Form) simply skips that lookup and falls through to the plain
   * [EXCLUSIVE_VALUE_CODES] map exactly as before.
   */
  fun isDisabled(
    questionCode: String,
    candidateCode: String,
    selected: List<String>,
    formCode: String? = null,
  ): Boolean {
    val exclusiveCodes = (formCode?.let { FORM_SCOPED_EXCLUSIVE_VALUE_CODES[it to questionCode] })
      ?: EXCLUSIVE_VALUE_CODES[questionCode]
      ?: return false
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
