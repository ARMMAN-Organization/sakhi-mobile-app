package org.armman.sakhi.data.forms

/**
 * The `question_code`s and `value_code`s of the CHILD_REGISTRATION schema that more than one layer
 * has to agree on.
 *
 * These four strings were previously declared three times over — file-private in
 * `DynamicChildRegistrationViewModel`, again in `ChildRegistrationSubmissionMapper.QuestionCode`, and
 * again in the tests. [FormDateRuleset] now needs the path answer too, which would have made a
 * fourth copy. A `question_code` typo or a backend rename is exactly the kind of change that must
 * break loudly in ONE place, so the shared subset lives here.
 *
 * **Why `data/forms` and not `data/childregistration`:** [FormDateRuleset] consumes these, and
 * `data/childregistration` already depends on `data/forms`. Putting them in the child package would
 * invert that dependency.
 *
 * Codes that only one layer reads (the mapper's DTO-shaped set, the ViewModel's consent gate) stay
 * where they are — this is deliberately the shared subset, not a dumping ground for every code.
 */
object ChildRegistrationQuestionCodes {

  /** The radio that selects which registration path the Sakhi is on. */
  const val WHO_ARE_YOU_REGISTERING = "who_are_you_registering_in_the_program"

  /** [WHO_ARE_YOU_REGISTERING] = child of a mother already enrolled in the programme. Carries a
   * `mother_beneficiary_id` link and the tighter 0-183 day eligibility window. */
  const val PATH_REGISTERED_MOTHER = "child_of_a_registered_pregnant_woman"

  /** [WHO_ARE_YOU_REGISTERING] = child registered directly, mother never enrolled. No mother link,
   * wider 0-365 day eligibility window. */
  const val PATH_DIRECT = "child_directly_mother_not_registered_in_the_program"

  /** The infant's date of birth — the beneficiary's own DOB on this form. Distinct from
   * [MOTHER_DOB_QUESTION_CODE] (the mother's DOB, also collected here) and from
   * [DOB_QUESTION_CODE] (the beneficiary's DOB on the mother-enrollment form). */
  const val DATE_OF_BIRTH_OF_INFANT = "date_of_birth_of_infant"

  /** The infant's weight at birth in kilograms. Shared so the beneficiary profile screen can read
   * a child's own weight instead of [org.armman.sakhi.data.beneficiaryprofile
   * .ScheduleBackedBeneficiaryProfileRepository]'s mother-only `weight_kg` code. */
  const val CHILD_WEIGHT_AT_BIRTH_KG = "child_weight_at_birth_in_kg"
}
