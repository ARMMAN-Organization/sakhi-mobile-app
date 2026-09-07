package org.armman.sakhi.data.forms

/**
 * Phase-1 post-submission editable `question_code`s for the Beneficiary Profile's Edit stub
 * (CR-Registration-Edit, tasks 10/11 of the LMP/Reopen/Referral/Audit gap analysis) — the
 * MOTHER_REGISTRATION/CHILD_REGISTRATION subset of the full `PATCH /form-submissions/:id/answers`
 * allowlist backend confirmed 2026-09-02. The other five form codes on that contract
 * (DELIVERY_VISIT, NEONATAL_VISIT, ANC_CLOSURE_VISIT, CHILD_CLOSURE_VISIT, and the shared
 * INFANT_VISIT/INC_VISIT/CCV_VISIT vaccination list) have no existing "view/edit a past
 * submission" screen at all — deferred to a later phase, see the project tracking doc's
 * "Architecture gap" section.
 *
 * Deliberately a client-side allowlist copy rather than trusting the backend's error response
 * alone: filtering the fields the edit screen even shows means the Sakhi never sees (and can't
 * tap into) a field that would just 422 back at her, and the schema-driven renderer only ever
 * needs to ask [FormsRepository] for questions matching one of these codes.
 */
object EditableFieldCodes {
  val MOTHER_REGISTRATION: Set<String> = setOf(
    "enter_the_beneficiary_address",
    "mobile_number",
    "who_owns_the_phone",
    "gravida_total_number_of_pregnancies",
    "para_number_of_births_after_24_weeks",
    "living_children",
    "abortions_pregnancy_losses_before_24_weeks",
    "still_births",
    "dead_children",
    "have_you_been_detected_with_sickle_cell_disease_or_sickle_cell_trait_sct",
  )

  val CHILD_REGISTRATION: Set<String> = setOf(
    "caregiver_name_first_name_middle_name_last_name",
    "mother_date_of_birth",
    "enter_the_beneficiary_address",
    "mobile_number",
    "who_owns_the_phone",
    "child_length_at_birth_in_cm",
    "child_weight_at_birth_in_kg",
  )

  /** [formCode] must be `"MOTHER_REGISTRATION"` or `"CHILD_REGISTRATION"` — every other form code
   * has no Phase-1 UI, so this returns an empty set rather than throwing (callers gate on
   * emptiness to show a "not editable yet" state instead of crashing). */
  fun forFormCode(formCode: String): Set<String> = when (formCode) {
    "MOTHER_REGISTRATION" -> MOTHER_REGISTRATION
    "CHILD_REGISTRATION" -> CHILD_REGISTRATION
    else -> emptySet()
  }
}
