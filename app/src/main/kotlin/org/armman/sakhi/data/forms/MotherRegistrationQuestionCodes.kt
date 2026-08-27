package org.armman.sakhi.data.forms

/**
 * `question_code`s and `value_code`s of the MOTHER_REGISTRATION fields the baseline risk evaluator
 * reads (CR-034). Only the codes NOT already declared elsewhere live here — the obstetric counts
 * stay in [FormObstetricRuleset] (GRAVIDA/PARA/LIVING_CHILDREN/ABORTIONS/STILL_BIRTHS) and the DOB
 * stays as [DOB_QUESTION_CODE], so there is exactly one copy of every literal.
 *
 * Every string below is the code the PUBLISHED schema uses, verified against a live
 * `active-version` response — not the Excel wording. Getting one wrong is silent: the field still
 * renders and submits, but the rule keyed on it simply never matches. That is the exact failure
 * mode this object exists to prevent, so treat these as a contract with the backend, not as labels.
 *
 * NOTE the two deliberate carry-overs of backend typos: `greather_than_2_5_kg` (Q57) and
 * `sickle_cell_trait_sct_carrier`. Do not "fix" the spelling here.
 */
object MotherRegistrationQuestionCodes {

  // --- Current pregnancy -------------------------------------------------------

  /** Q43 — self-reported high-risk conditions communicated at the latest ANC check-up. */
  const val ANC1_HIGH_RISK_CONDITIONS =
    "if_anc1_completed_are_you_identified_with_any_high_risk_condition_as_communicated_by_doctor_" +
      "during_the_latest_anc_checkup_self_reported"

  // --- Past obstetric history: last pregnancy ---------------------------------

  /** Q51 — interval since the last pregnancy. Only rendered when Gravida > 1. */
  const val LAST_PREGNANCY_INTERVAL = "when_was_your_last_pregnancy"

  /** Q52 — complications during previous birth/delivery (multiselect). */
  const val PREVIOUS_DELIVERY_COMPLICATIONS =
    "did_you_experience_any_complications_during_birth_delivery_in_previous_pregnancies"

  /** Q53 — term of the last delivery. */
  const val LAST_DELIVERY_DURATION = "duration_of_last_delivery"

  /** Q54 — type of the last delivery. */
  const val LAST_DELIVERY_TYPE = "type_of_last_delivery"

  /** Q55 — where the last delivery was conducted. */
  const val LAST_DELIVERY_PLACE = "where_was_the_woman_s_last_delivery_conducted"

  /** Q56 — outcome of the last delivery. */
  const val LAST_DELIVERY_OUTCOME = "last_delivery_outcome"

  /** Q57 — birth weight of the last child, collected as a BAND, not a number. */
  const val LAST_CHILD_BIRTH_WEIGHT = "weight_of_the_child_at_the_time_of_birth"

  // --- Self medical history ----------------------------------------------------

  /** Q58 — chronic/past medical conditions (multiselect). */
  const val SELF_MEDICAL_CONDITIONS =
    "have_you_ever_been_diagnosed_with_or_treated_for_any_of_the_following_medical_conditions"

  /** Q60 — sickle cell disease / trait status. */
  const val SICKLE_CELL_STATUS =
    "have_you_been_detected_with_sickle_cell_disease_or_sickle_cell_trait_sct"

  /** Q61 — substance use before/during this pregnancy (multiselect). */
  const val SUBSTANCE_USE =
    "have_you_used_any_of_the_following_substances_before_or_during_this_pregnancy"

  /**
   * `value_code`s the risk rules match on. Dynamic-form answers are these STRINGS — not the 1-based
   * Int indices the legacy static enrollment flow used ([org.armman.sakhi.ui.enrollment
   * .HealthHistoryState]) — so nothing here is interchangeable with `VisitRiskAssessment`'s
   * `sickleCellCode: Int?` contract.
   */
  object ValueCode {
    /** Q51 */
    const val INTERVAL_UNDER_3_YEARS = "less_than_3_years_ago"

    /** Q52 — the three "yes" variants; `no_complications` is the only benign answer. */
    const val COMPLICATION_MISCARRIAGE = "yes_miscarriage"
    const val COMPLICATION_DURING_DELIVERY = "yes_during_delivery_complications"
    const val COMPLICATION_WITH_BABY = "yes_complications_with_baby_during_delivery"
    const val NO_COMPLICATIONS = "no_complications"

    /** Q53 */
    const val TERM_PRE = "pre_term"

    /** Q54 */
    const val DELIVERY_CAESARIAN = "caesarian"

    /** Q55 */
    const val DELIVERY_PLACE_HOME = "home"

    /** Q56 */
    const val OUTCOME_STILL_BIRTH = "still_birth"

    /** Q57 — backend spells the negative case "greather"; kept verbatim. */
    const val BIRTH_WEIGHT_UNDER_2_5_KG = "less_than_2_5_kg"

    /** Q60 */
    const val SICKLE_CELL_DISEASE = "sickle_cell_disease_scd"
    const val SICKLE_CELL_TRAIT = "sickle_cell_trait_sct_carrier"

    /** Q61 */
    const val SUBSTANCE_NONE = "no"
    const val SUBSTANCE_NOT_DISCLOSED = "don_t_know_not_willing_to_disclose"

    /**
     * The "no condition"/"don't know" answers of Q43 and Q58. The two questions use DIFFERENT
     * none-codes — Q58 says `no_known_medical_condition`, Q43 appends `_identified_during_check_up`
     * — so both spellings are listed and every condition rule matches the SET. Selecting any of
     * these is mutually exclusive with a real condition in the UI, so a set that contains only
     * these carries no risk.
     */
    val NON_CONDITION_CODES: Set<String> = setOf(
      "no_known_medical_condition",
      "no_known_medical_condition_identified_during_check_up",
      "don_t_know",
      "don_t_know_not_aware",
    )
  }
}
